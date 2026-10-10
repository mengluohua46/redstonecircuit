package com.jiangyuefengyu.redstonecircuit.compat.physics;

import com.jiangyuefengyu.redstonecircuit.RCConfig;
import com.jiangyuefengyu.redstonecircuit.RCRegistry;
import com.jiangyuefengyu.redstonecircuit.block.StructureBlockEntity;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierPose;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierStore;
import com.jiangyuefengyu.redstonecircuit.carrier.ProductFrame;
import com.jiangyuefengyu.redstonecircuit.structure.pocket.PocketDimension;
import com.jiangyuefengyu.redstonecircuit.structure.pocket.PocketSpace;

import com.simibubi.create.api.behaviour.movement.MovementBehaviour;
import com.simibubi.create.api.contraption.ContraptionMovementSetting;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.Contraption;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import com.simibubi.create.content.contraptions.render.ContraptionMatrices;
import com.simibubi.create.foundation.virtualWorld.VirtualRenderWorld;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;

import org.jetbrains.annotations.Nullable;

import java.util.Map;

/**
 * A placed structure block rides a Create contraption - and comes back whole.
 *
 * <p>A product block's data is in its own block entity, which Create captures and writes back itself; what needs us
 * is the plot, which records where its owner stands and is swept once the owner is gone. When the block leaves, the
 * plot's record is stamped as <b>travelling</b>, and when the block stands anywhere at all its own tick tells the
 * table where it is - so the landing needs no landing event. A block with a block entity is not movable by default,
 * so both the movement setting and the behaviour are registered here.
 */
final class MovementProductHook {

    private static boolean installed;
    private static boolean usable;

    private MovementProductHook() {
    }

    /**
     * Registers both halves for {@code redstonecircuit:structure}, once, from the backend's own init. It does not
     * wait for a first placement: the block is ours and there is exactly one of it.
     */
    static void install() {
        if (installed) {
            return;
        }
        installed = true;
        try {
            ContraptionMovementSetting.REGISTRY.register(RCRegistry.STRUCTURE.get(),
                    () -> ContraptionMovementSetting.MOVABLE);
            MovementBehaviour.REGISTRY.register(RCRegistry.STRUCTURE.get(), HOOK);
            usable = true;
            RCConfig.LOGGER.info("[redstonecircuit] placed structure blocks are movable on contraptions "
                    + "(ContraptionMovementSetting=MOVABLE) and their plots are protected while they travel");
        } catch (RuntimeException | LinkageError failed) {
            RCConfig.LOGGER.warn("[redstonecircuit] a placed structure block cannot ride a contraption: {}",
                    failed.toString());
        }
    }

    /** Whether both registrations went in; used by the {@code /rc physics} report. */
    static boolean installed() {
        return usable;
    }

    private static final MovementBehaviour HOOK = new MovementBehaviour() {

        @Override
        public void startMoving(MovementContext context) {
            onStartMoving(context);
        }

        @Override
        public void stopMoving(MovementContext context) {
            onStopMoving(context);
        }

        /**
         * The only tick a carried placed structure gets: Create takes the block out of the world, so the level's
         * ticker never reaches it and its faces are never read - the plot keeps what it was injected with.
         */
        @Override
        public void tick(MovementContext context) {
            onTick(context);
        }

        /**
         * Create's own client-side drawing hook for a carried structure, and empty until the drawing half
         * has a client pass of its own. The miniature needs the cells, a {@code BlockRenderDispatcher}
         * (a client singleton, so that call has to live in a client class) and the pose for one local coordinate out
         * of {@link ContraptionMatrices}; an override only the contraption renderer calls cannot be loaded by a
         * server-side path, so declaring it here is safe.
         */
        @Override
        public void renderInContraption(MovementContext context, VirtualRenderWorld renderWorld,
                                        ContraptionMatrices matrices, MultiBufferSource buffer) {
            // Draws nothing yet; the javadoc above names what the drawing pass still needs.
        }
    };

    /**
     * The block has left the world: stamp its plot as travelling. The world position is {@code anchor + localPos},
     * and the block entity is read <b>before</b> Create takes the block away.
     */
    private static void onStartMoving(MovementContext context) {
        try {
            ServerLevel level = MovementHostHook.serverLevel(context);
            BlockPos worldPos = MovementHostHook.statePos(context);
            if (level == null || worldPos == null) {
                return;
            }
            BlockStateHolder found = productAt(level, worldPos);
            if (found == null) {
                return;
            }
            ServerLevel pocket = PocketDimension.of(level.getServer());
            if (pocket == null) {
                return;
            }
            boolean marked = PocketSpace.of(pocket).noteTravelling(level.dimension(), worldPos,
                    level.getGameTime());
            RCConfig.LOGGER.info("[redstonecircuit] carrier pickup product local={} worldPos={} plot={} "
                            + "protected={} - a contraption is carrying a placed structure", 
                    context.localPos == null ? "?" : context.localPos.toShortString(), worldPos.toShortString(),
                    found.plot, marked);
        } catch (RuntimeException | LinkageError failed) {
            diag("startMoving", context, failed);
        }
    }

    /**
     * The block is being put down: the re-owning itself happens on the block's own first tick, and this only
     * finishes the job immediately when the block already stands at the landing position.
     */
    private static void onStopMoving(MovementContext context) {
        try {
            // The ride is over, so the block entity built out of the captured data is dead weight - and a stale one
            // would be worse if the same contraption were assembled again with a different structure in that slot.
            CARRIED.remove(context.contraption);
            // The "the CREATE path is ticking this" line is dropped too, so a new ride says which carrier it has.
            TICKED.remove(context.contraption);
            ServerLevel level = MovementHostHook.serverLevel(context);
            BlockPos worldPos = MovementHostHook.statePos(context);
            if (level == null || worldPos == null) {
                return;
            }
            BlockStateHolder found = productAt(level, worldPos);
            if (found == null) {
                // The ordinary case: Create writes the block back a moment after this callback, so the block's own
                // tick is what re-owns the plot. Nothing to report - a line per put-down would be a line per motion.
                return;
            }
            ServerLevel pocket = PocketDimension.of(level.getServer());
            if (pocket == null) {
                return;
            }
            boolean moved = PocketSpace.of(pocket).reown(found.plot, level.dimension(), worldPos, found.structure);
            RCConfig.LOGGER.info("[redstonecircuit] carrier landing product local={} worldPos={} plot={} "
                            + "reowned={}", context.localPos == null ? "?" : context.localPos.toShortString(),
                    worldPos.toShortString(), found.plot, moved);
        } catch (RuntimeException | LinkageError failed) {
            diag("stopMoving", context, failed);
        }
    }

    // -------------------------------------------------------------- the tick --

    /**
     * The block entities this hook has built out of contraptions' captured data, one per contraption. Keyed by the
     * {@code Contraption} object itself, because Create reuses one instance for every callback of one assembly, so
     * object identity is stable, exact and free; entries are dropped when the block is put down.
     */
    private static final java.util.Map<Contraption, Carried> CARRIED = new java.util.IdentityHashMap<>();

    /** A product rebuilt from a contraption slot, and the captured data it was rebuilt from. */
    private record Carried(BlockPos localPos, int dataStamp, StructureBlockEntity product) {
    }

    /**
     * One tick of a placed structure a contraption is carrying. Three things have to be in hand, and each is a fact
     * only this callback has: the contraption's entity, the local coordinate Create captured it at, and the captured
     * block entity data. A physics-assembled structure is left alone - it is a Sable sub-level <em>and</em> a
     * contraption entity under one UUID, and the two coordinate spaces are not the same frame. Nothing escapes here.
     */
    private static void onTick(MovementContext context) {
        try {
            if (!CarrierStore.isActive()) {
                return;
            }
            ServerLevel level = MovementHostHook.serverLevel(context);
            Contraption contraption = context.contraption;
            AbstractContraptionEntity entity = CarrierResolver.entityOf(contraption);
            if (level == null || contraption == null || entity == null || context.localPos == null) {
                note(context, "no level, no contraption, no entity or no local coordinate (level="
                        + (level != null) + " contraption=" + (contraption != null) + " entity="
                        + (entity != null) + ")");
                return;
            }
            if (CarrierResolver.isSubLevelBacked(entity.getUUID())) {
                return;
            }
            ServerLevel pocket = PocketDimension.of(level.getServer());
            if (pocket == null) {
                note(context, "the pocket dimension is not loaded, so there is no plot to inject into");
                return;
            }
            StructureBlockEntity product = productOf(contraption, context, level);
            if (product == null) {
                note(context, "its captured block entity data could not be read (present="
                        + (context.blockEntityData != null) + ")");
                return;
            }
            CarrierPose pose = CarrierResolver.poseOf(entity);
            if (pose == null) {
                note(context, "the contraption's pose could not be read, so the structure's faces cannot be "
                        + "asked where it is drawn");
                return;
            }
            noteTicked(contraption, entity, context);
            StructureBlockEntity.carriedTick(level, pocket, product,
                    ProductFrame.of(pose, (neighbour, side) -> carriedSignal(contraption, level, neighbour, side)),
                    context.localPos);
        } catch (RuntimeException | LinkageError failed) {
            if (RCConfig.debugLog()) {
                diag("tick", context, failed);
            }
        }
    }

    /**
     * What the contraption's own block at one local coordinate answers across one local side. A contraption keeps its
     * blocks in a map, so a neighbour that travelled <em>with</em> the structure is in no level position at all. The
     * state is asked in its <b>own</b> frame - the frame the block's own rules are written in - and a coordinate the
     * contraption does not hold answers 0, which is what makes the frame's {@code max} mean what it does.
     */
    private static int carriedSignal(Contraption contraption, ServerLevel level, BlockPos localNeighbour,
                                     Direction localFace) {
        try {
            Map<BlockPos, StructureBlockInfo> blocks = contraption.getBlocks();
            StructureBlockInfo info = blocks == null ? null : blocks.get(localNeighbour);
            if (info == null) {
                return 0;
            }
            BlockState state = info.state();
            return Math.max(state.getSignal(level, localNeighbour, localFace),
                    state.getDirectSignal(level, localNeighbour, localFace));
        } catch (RuntimeException | LinkageError unreadable) {
            return 0;
        }
    }

    /**
     * One line, once per contraption, saying why a carried structure is not being ticked. Every way this can decline
     * is a silent {@code return} otherwise, which is a defect that cannot be diagnosed from a screenshot.
     */
    private static void note(MovementContext context, String reason) {
        Contraption contraption = context.contraption;
        if (MovementHostHook.serverLevel(context) == null) {
            // A client calls the same callback with a ClientLevel and has nothing to tick, so it must not take the
            // one line a real reason gets per structure.
            return;
        }
        if (contraption == null || !NOTED.add(contraption)) {
            return;
        }
        RCConfig.LOGGER.info("[redstonecircuit] carried structure at contraption local={} is NOT ticked: {}",
                context.localPos == null ? "?" : context.localPos.toShortString(), reason);
    }

    /** Contraptions already reported, so {@link #note} writes one line per structure. */
    private static final java.util.Set<Contraption> NOTED =
            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

    /**
     * One line per assembly naming the carrier that is really ticking a carried structure. A physics assembly is
     * <em>both</em> a Sable sub-level and a Create contraption entity under one UUID, so both halves say what they
     * did: this line from the Create side, and the Sable driver's when it really ticks a product inside a sub-level.
     * It does not share {@link #NOTED}, so a failed tick cannot consume the line that says the tick happens.
     */
    private static void noteTicked(Contraption contraption, AbstractContraptionEntity entity,
                                   MovementContext context) {
        if (!TICKED.add(contraption)) {
            return;
        }
        RCConfig.LOGGER.info("[redstonecircuit] carried structure local={} is ticked by the CREATE path "
                        + "(carrier uuid={} subLevelBacked={} sableLoaded={})",
                context.localPos == null ? "?" : context.localPos.toShortString(), entity.getUUID(),
                CarrierResolver.isSubLevelBacked(entity.getUUID()), PhysicsCompat.sableLoaded());
    }

    /** Contraptions already named by {@link #noteTicked}, so that line is written once per assembly. */
    private static final java.util.Set<Contraption> TICKED =
            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

    /**
     * The product of one contraption slot, rebuilt from the captured data when that data has changed. The block is
     * not in the level while it rides, so the object to tick has to be built here; it is cached per contraption
     * because it carries real per-tick state - the six faces' last answers.
     */
    @Nullable
    private static StructureBlockEntity productOf(Contraption contraption, MovementContext context,
                                                  ServerLevel level) {
        CompoundTag data = context.blockEntityData;
        if (data == null) {
            return null;
        }
        int stamp = data.hashCode();
        Carried cached = CARRIED.get(contraption);
        if (cached != null && cached.localPos().equals(context.localPos) && cached.dataStamp() == stamp) {
            return cached.product();
        }
        try {
            StructureBlockEntity product = new StructureBlockEntity(context.localPos, context.state);
            product.setLevel(level);
            product.loadWithComponents(data, level.registryAccess());
            CARRIED.put(contraption, new Carried(context.localPos, stamp, product));
            return product;
        } catch (RuntimeException | LinkageError unreadable) {
            return null;
        }
    }

    /**
     * The product block at a world position, with the two things the pocket needs from it, or null. Read from the
     * world rather than from the captured NBT: at {@code startMoving} the block is still there, and at
     * {@code stopMoving} the question "is it there yet?" decides whether this callback has anything to do.
     */
    private static BlockStateHolder productAt(ServerLevel level, BlockPos pos) {
        if (!(level.getBlockEntity(pos) instanceof StructureBlockEntity product) || product.plotIndex() < 0) {
            return null;
        }
        return new BlockStateHolder(product.plotIndex(), product.structureId());
    }

    /** The two fields of a product block the pocket side needs, kept out of the Create callbacks' scope. */
    private record BlockStateHolder(int plot, java.util.UUID structure) {
    }

    /** One line, never more than once per reason, for a failure inside Create's own assembly. */
    private static void diag(String event, MovementContext context, Throwable failed) {
        RCConfig.LOGGER.warn("[redstonecircuit] product carrier {} at {} failed: {}",
                event, context.localPos == null ? "?" : context.localPos.toShortString(), failed.toString());
    }
}

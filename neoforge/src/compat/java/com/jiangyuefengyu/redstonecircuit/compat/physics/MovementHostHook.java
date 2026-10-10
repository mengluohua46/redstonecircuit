package com.jiangyuefengyu.redstonecircuit.compat.physics;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.jiangyuefengyu.redstonecircuit.RCConfig;
import com.jiangyuefengyu.redstonecircuit.carrier.CarriedHostIndex;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierId;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierIdentity;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierKind;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierPose;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierStore;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierTrace;
import com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneNode;
import com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneStore;

import com.simibubi.create.api.behaviour.movement.MovementBehaviour;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.Contraption;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import org.jetbrains.annotations.Nullable;

/**
 * The hook that makes a host block travel: Create's {@code MovementBehaviour} registry, registered on
 * demand for exactly the block types that have ever held a component.
 *
 * <p>The structure is always nameable from the movement context, but the contraption's entity is assigned
 * only after the contraption is built, so a claim made during assembly is filed under a provisional
 * identity and re-labelled onto the entity as soon as one exists - normally within the same tick. The
 * invariant is that a block which really holds a component is never skipped: a missing entity is not a
 * reason to return, and every genuinely impossible case is reported by name rather than passed over in
 * silence.
 *
 * <p>Both callbacks ask the world store first, so the common case - a machine mostly of stone - costs one
 * lookup per block. The registry is a map and only an absent entry is ever written, so a block type Create
 * or another mod already has behaviour for keeps it and its components do not travel; that is reported at
 * registration time rather than silently ignored.
 */
final class MovementHostHook {

    /**
     * The one {@link MovementBehaviour} instance every host block type shares.
     *
     * <p>Stateless - everything it needs is in the {@code MovementContext} - so sharing it is safe.
     */
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
         * Create's own per-actor callback, once per actor per engine tick and only while the behaviour is
         * active - the default, since nothing here overrides {@code isActive}.
         *
         * <p>Deliberately not {@code mustTickWhileDisabled}: returning true there would keep a machine's
         * redstone running after a player disabled its actors with a wrench. Also not
         * {@code visitNewPosition}, which only fires when the actor's active area moves a block - a parked
         * bearing would never be solved, and a machine just assembled would keep its ground values.
         */
        @Override
        public void tick(MovementContext context) {
            onTick(context);
        }
    };

    /** Block types already given the hook, so a block is registered at most once. */
    private static final Set<Block> REGISTERED = new HashSet<>();

    /**
     * Whether the contraption's entity has ever been non-null, per contraption object. Read only by the
     * one-shot diagnostics, to answer whether the entity was there when {@code startMoving} ran. Entries
     * are added, never enumerated.
     */
    private static final Map<Contraption, Boolean> ENTITY_SEEN = new java.util.IdentityHashMap<>();

    private MovementHostHook() {
    }

    /**
     * Registers the hook for one block type, the first time that block type is seen holding a component.
     * Server thread only; never twice for the same block, so the log line appears once per block type.
     */
    static void register(Block block) {
        if (block == null || !REGISTERED.add(block)) {
            return;
        }
        try {
            MovementBehaviour existing = MovementBehaviour.REGISTRY.get(block.defaultBlockState());
            if (existing != null) {
                // Somebody else's behaviour owns this block type: overwriting it would break that mod
                // (silently, since the registry is a plain map), so it is left alone and its components do
                // not travel - which is reported and is a far smaller problem.
                RCConfig.LOGGER.info("[redstonecircuit] not hooking {}: another MovementBehaviour is "
                                + "already registered for it, so components inside it will not travel",
                        block.builtInRegistryHolder().key().location());
                return;
            }
            MovementBehaviour.REGISTRY.register(block, HOOK);
            RCConfig.LOGGER.info("[redstonecircuit] registered MovementBehaviour for {} "
                            + "(first block of this type to hold a component)",
                    block.builtInRegistryHolder().key().location());
        } catch (RuntimeException | LinkageError failed) {
            REGISTERED.remove(block);
            RCConfig.LOGGER.warn("[redstonecircuit] could not register MovementBehaviour for {}: {}",
                    block, failed.toString());
        }
    }

    /** How many block types currently carry the hook; used by the {@code /rc physics} report. */
    static int registeredCount() {
        return REGISTERED.size();
    }

    // ------------------------------------------------------------- the move --

    /**
     * A host block has started to move: snapshot it into the contraption. The entity names the carrier when it
     * is already there, and a provisional identity is used when it is not; the only genuinely impossible case -
     * no contraption and no local coordinate - is reported rather than passed over.
     */
    private static void onStartMoving(MovementContext context) {
        try {
            if (!CarrierStore.isActive()) {
                return;
            }
            ServerLevel level = serverLevel(context);
            Contraption contraption = context.contraption;
            AbstractContraptionEntity entity = CarrierResolver.entityOf(contraption);
            oneShotContext("startMoving", context, entity);

            // Answered before anything else, because it is the answer for almost every block: the hook
            // is per block *type*, so a machine full of stone calls in here once per stone.
            BlockPos worldPos = statePos(context);
            InnerRedstoneNode node = level == null || worldPos == null
                    ? null
                    : InnerRedstoneStore.get(level).get(worldPos);
            boolean hasComponent = node != null && !node.isEmpty();

            if (!hasComponent) {
                if (level != null) {
                    CarrierTrace.noteEmptyBlock(level.dimension());
                }
                // Not our block. Only logged when the diagnostics are asked for, because this is the
                // common case and a line per plain stone would drown the log.
                if (RCConfig.debugLog() && RCConfig.traceCarrier()) {
                    diag("startMoving", context, "no component inside; nothing to carry");
                }
                return;
            }

            // From here on there IS a component to carry, and the claim happens: that is the invariant.
            if (level == null) {
                diag("startMoving", context, "component found but the context has no server level");
                return;
            }
            if (contraption == null) {
                diag("startMoving", context, "component found but the context has no contraption");
                return;
            }
            if (worldPos == null) {
                diag("startMoving", context, "component found but there is no anchor to work the "
                        + "original position out from");
                return;
            }
            if (defersToSable("startMoving", context, entity)) {
                return;
            }

            CarrierId provisional = entity == null
                    ? CarrierResolver.provisionalFor(contraption)
                    : null;
            CarrierStore.startMoving(level, entity == null ? null : entity.getUUID(), provisional,
                    CarrierKind.CONTRAPTION, context.localPos, worldPos);
            CarrierPose pose = entity == null
                    ? CarrierStore.poseOf(level, provisional)
                    : CarrierResolver.poseOf(entity);
            RCConfig.LOGGER.info("[redstonecircuit] carrier pickup contraption local={} "
                            + "statePos={} anchor={} worldState={} entity={} identity={} pose={}",
                    context.localPos.toShortString(), worldPos.toShortString(),
                    anchor(context) == null ? "?" : anchor(context).toShortString(),
                    worldState(context, worldPos),
                    entity == null ? "absent (provisional claim)" : entity.getUUID().toString(),
                    entity == null ? "provisional" : "entity",
                    pose == null ? "?" : pose.describe());
        } catch (RuntimeException | LinkageError failed) {
            // This runs inside Create's assembly. A diagnostic that can take the assembly down is
            // worse than no diagnostic, so nothing is allowed out of here.
            diag("startMoving", context, "failed: " + failed);
        }
    }

    /**
     * A host block has stopped: put it down, idempotently, matching either identity.
     *
     * <p>The landing position comes from the contraption's transform - where Create will put the block back a
     * moment later - and the release is idempotent, because this callback fires four to five times for one
     * motion with identical arguments. A claim still filed under a provisional identity is reconciled onto the
     * entity first, and the release then tries the entity's UUID and the provisional id, so a structure that
     * came apart before it ever had an entity still gets its components back.
     */
    private static void onStopMoving(MovementContext context) {
        try {
            if (!CarrierStore.isActive()) {
                return;
            }
            ServerLevel level = serverLevel(context);
            Contraption contraption = context.contraption;
            AbstractContraptionEntity entity = CarrierResolver.entityOf(contraption);
            oneShotContext("stopMoving", context, entity);

            if (context.localPos == null) {
                diag("stopMoving", context, "no local position");
                return;
            }
            if (level == null) {
                diag("stopMoving", context, "no server level");
                return;
            }
            if (defersToSable("stopMoving", context, entity)) {
                return;
            }

            UUID entityUuid = entity == null ? null : entity.getUUID();
            // The identity this claim may still be filed under, if it was made before the entity existed.
            // Asked of the registry rather than remembered here, so the same contraption object answers the
            // same id on every callback of one assembly; the ordinary case costs one identity-map lookup.
            CarrierId provisional = contraption == null ? null
                    : (CarrierResolver.hasProvisional(contraption)
                            ? CarrierResolver.provisionalFor(contraption)
                            : (entityUuid == null ? CarrierResolver.provisionalFor(contraption) : null));
            if (provisional != null && entityUuid != null) {
                // Settle the name first, so the release below finds the live entry under the entity's UUID
                // rather than falling through to the provisional one.
                CarrierStore.reconcile(level, provisional, entityUuid,
                        CarrierIdentity.Phase.PROVISIONAL);
            }

            // DEFERRED, NOT IMMEDIATE. A put-down is not the only thing this callback means: a direction
            // change fires it too, and releasing on the spot would drop the host off a block that is still
            // AIR in the world with the contraption entity still present. The release therefore waits for
            // positive evidence, and anything that says the structure is still being driven cancels it. A
            // genuine put-down is unchanged: the world block comes back and the release lands there.
            BlockPos worldBlock = statePos(context);
            var outcome = CarrierStore.stopMovingOrDefer(level, entityUuid, provisional,
                    CarrierKind.CONTRAPTION, context.localPos,
                    worldBlock == null ? context.localPos : worldBlock);
            boolean deferred = outcome.status() == CarriedHostIndex.ReleaseStatus.UNKNOWN_CARRIER;
            if (provisional != null
                    && outcome.status() != CarriedHostIndex.ReleaseStatus.UNKNOWN_CARRIER) {
                // The claim is settled, or its hosts are already back in the world; either way nothing is
                // waiting on this provisional identity any more.
                CarrierResolver.forgetProvisional(provisional);
            }
            RCConfig.LOGGER.info("[redstonecircuit] carrier stopMoving contraption local={} "
                            + "statePos={} anchor={} worldState={} entity={} provisional={} deferred={} "
                            + "outcome={}",
                    context.localPos.toShortString(),
                    statePos(context) == null ? "?" : statePos(context).toShortString(),
                    anchor(context) == null ? "?" : anchor(context).toShortString(),
                    statePos(context) == null ? "?" : worldState(context, statePos(context)),
                    entityUuid == null ? "absent" : entityUuid.toString(),
                    provisional == null ? "-" : provisional.shortId(),
                    // A field rather than a sentence so that a reader can count it: true means the structure
                    // asked to stand down and the world did not agree that it had. A real put-down says
                    // false, and that is the only case in which a host was released here.
                    deferred,
                    outcome.describe());
        } catch (RuntimeException | LinkageError failed) {
            diag("stopMoving", context, "failed: " + failed);
        }
    }

    // ------------------------------------------------- one host, one carrier --

    /** Contraption objects already reported as standing down, so the line appears once per structure. */
    private static final Set<Contraption> DEFERRED = java.util.Collections
            .newSetFromMap(new java.util.IdentityHashMap<>());

    /**
     * Whether this Create contraption is really a Sable sub-level, in which case the Create path does nothing
     * at all: a physics-assembled structure is a sub-level <em>and</em> a contraption entity created with the
     * <b>same UUID</b>, so both halves file a claim for the same host block in two different local spaces and
     * neither can see the other. Sable is the half that actually holds the blocks and the pose that can be read
     * for them, so it owns the structure and this half returns before the claim - and equally before the
     * put-down, the release and the solve.
     *
     * <p>The test is the entity UUID against Sable's own container, not a guess about positions: the two halves
     * disagree about where the blocks are (plot coordinates against anchor-relative ones). True means the caller
     * does nothing at all for this callback.
     */
    private static boolean defersToSable(String event, MovementContext context,
                                         @Nullable AbstractContraptionEntity entity) {
        if (entity == null || !CarrierResolver.isSubLevelBacked(entity.getUUID())) {
            return false;
        }
        Contraption contraption = context.contraption;
        if (contraption != null && DEFERRED.add(contraption)) {
            RCConfig.LOGGER.info("[redstonecircuit] carrier {} contraption local={}: this contraption "
                            + "entity {} is a SABLE SUB-LEVEL under the same UUID, so the Create path "
                            + "stands down (no claim, no solve, no release) and the Sable path owns the "
                            + "structure. Two claims for one host is what made the component stay behind",
                    event, context.localPos == null ? "?" : context.localPos.toShortString(),
                    entity.getUUID());
        }
        return true;
    }

    // -------------------------------------------------------------- helpers --

    /**
     * A host block's actor ticked: settle the identity, then solve the structure.
     *
     * <p>This callback runs once the contraption entity exists, so it is the earliest reliable place to
     * re-label a provisional claim, which is what gets a machine claimed during assembly drawn on its very
     * first moving tick. It then answers one question - "which structure is this block riding" - and hands the
     * whole structure to the carrier layer to be solved <b>once</b>: solving per actor would run a full network
     * solve for every stone in the machine.
     *
     * <p>A missing entity is <b>not</b> a reason to return: the reconciliation is skipped, but the solve is
     * not, because the claim may legitimately still be provisional and the hosts it protects still need
     * solving.
     */
    private static void onTick(MovementContext context) {
        try {
            if (!CarrierStore.isActive()) {
                return;
            }
            ServerLevel level = serverLevel(context);
            Contraption contraption = context.contraption;
            AbstractContraptionEntity entity = CarrierResolver.entityOf(contraption);
            oneShotContext("tick", context, entity);
            if (level == null || contraption == null) {
                return;
            }
            if (defersToSable("tick", context, entity)) {
                return;
            }

            CarrierId carrier;
            if (entity != null) {
                if (CarrierResolver.hasProvisional(contraption)) {
                    CarrierId provisional = CarrierResolver.provisionalFor(contraption);
                    CarrierStore.reconcile(level, provisional, entity.getUUID(),
                            CarrierIdentity.Phase.PROVISIONAL);
                }
                carrier = CarrierId.contraption(entity.getUUID());
            } else {
                // Still no entity: the claim is under the provisional identity, and that is the carrier
                // whose hosts have to be solved.
                carrier = CarrierResolver.provisionalFor(contraption);
            }
            if (!RCConfig.carrierSolveEnabled()) {
                return;
            }
            CarrierStore.tickHost(level, carrier);
        } catch (RuntimeException | LinkageError failed) {
            // Runs inside Create's actor tick. A failure here must not take a moving machine - and
            // therefore the tick it is moving in - down.
            if (RCConfig.debugLog()) {
                diag("tick", context, "failed: " + failed);
            }
        }
    }

    static ServerLevel serverLevel(MovementContext context) {
        Level world = context.world;
        return world instanceof ServerLevel server ? server : null;
    }

    /**
     * The world position this contraption block came from: {@code contraption.anchor + localPos}. The anchor
     * does not move while a contraption does, and the same value comes back on every {@code stopMoving} call,
     * which is what makes the arithmetic recover the original block.
     */
    static BlockPos statePos(MovementContext context) {
        Contraption contraption = context.contraption;
        if (contraption == null || contraption.anchor == null || context.localPos == null) {
            return null;
        }
        return contraption.anchor.offset(context.localPos);
    }

    private static BlockPos anchor(MovementContext context) {
        Contraption contraption = context.contraption;
        return contraption == null ? null : contraption.anchor;
    }

    private static String worldState(MovementContext context, BlockPos pos) {
        Level world = context.world;
        if (world == null || pos == null) {
            return "unknown";
        }
        try {
            BlockState state = world.getBlockState(pos);
            return state + (state.isAir() ? " (AIR)" : " (still there)");
        } catch (RuntimeException failed) {
            return "unreadable: " + failed;
        }
    }

    /** Where the contraption's own transform says this block is now, for the log. */
    private static String contraptionSays(AbstractContraptionEntity entity, BlockPos localPos) {
        Vec3 at = CarrierResolver.worldOf(entity, localPos);
        return at == null ? "?" : String.format(java.util.Locale.ROOT, "(%.3f, %.3f, %.3f)",
                at.x, at.y, at.z);
    }

    /** One diagnostic line about a callback that could not do its job. */
    private static void diag(String event, MovementContext context, String reason) {
        try {
            RCConfig.LOGGER.info("[redstonecircuit] carrier {} contraption local={} skipped: {} "
                            + "(contraptionSays={} entity={})",
                    event, context.localPos == null ? "?" : context.localPos.toShortString(), reason,
                    CarrierResolver.worldOf(CarrierResolver.entityOf(context.contraption),
                            context.localPos),
                    CarrierResolver.entityOf(context.contraption) == null ? "absent" : "present");
        } catch (RuntimeException | LinkageError ignored) {
            // A diagnostic about a diagnostic. Nothing useful can be added, and it must not throw.
        }
    }

    // ------------------------------------------------- one-shot identification --

    /** Contraption objects already printed in full, so the dump appears once per structure. */
    private static final Set<Contraption> DIAGNOSED = java.util.Collections
            .newSetFromMap(new java.util.IdentityHashMap<>());

    /**
     * Prints everything a {@code MovementContext} can say about a carrier's identity, once per contraption
     * object: one line per assembly is affordable, and "was the entity there at {@code startMoving}?" is the
     * question that decides whether the fallback identity has to do anything at all.
     *
     * <p>Every field is printed, not just the ones expected to matter, because a guess about which field is
     * missing is exactly what this dump exists to replace.
     */
    private static void oneShotContext(String event, MovementContext context,
                                       @Nullable AbstractContraptionEntity entity) {
        try {
            Contraption contraption = context.contraption;
            if (contraption == null) {
                if (DIAGNOSED.add(contraption)) {
                    RCConfig.LOGGER.info("[redstonecircuit] carrier identity {}: contraption=null "
                            + "world={} localPos={} state={}", event, context.world != null,
                            context.localPos == null ? "null" : context.localPos.toShortString(),
                            context.state);
                }
                return;
            }
            boolean first = DIAGNOSED.add(contraption);
            if (!first) {
                // One line per structure is the point; a per-callback dump would drown the log. The only
                // thing worth repeating is the first time an entity appears where there had never been
                // one - that is the reconciliation event, and it is logged by CarrierStore itself.
                return;
            }
            Boolean seenBefore = ENTITY_SEEN.put(contraption, entity != null);
            AABB bounds = contraption.bounds;
            Map<BlockPos, ?> blocks = contraption.getBlocks();
            RCConfig.LOGGER.info("[redstonecircuit] carrier identity at {}: contraption={} "
                            + "(identityHash={} entity={} entitySeenBefore={}) localPos={} statePos={} "
                            + "anchor={} bounds={} blocks={} actors={} world={} state={} "
                            + "provisionalNeeded={}",
                    event, contraption.getClass().getSimpleName(),
                    System.identityHashCode(contraption),
                    entity == null ? "null" : entity.getUUID() + " class=" + entity.getClass().getSimpleName(),
                    seenBefore,
                    context.localPos == null ? "null" : context.localPos.toShortString(),
                    statePos(context) == null ? "?" : statePos(context).toShortString(),
                    contraption.anchor == null ? "null" : contraption.anchor.toShortString(),
                    bounds == null ? "null"
                            : String.format(java.util.Locale.ROOT, "[%.2f %.2f %.2f .. %.2f %.2f %.2f]",
                                    bounds.minX, bounds.minY, bounds.minZ, bounds.maxX, bounds.maxY,
                                    bounds.maxZ),
                    blocks == null ? "null" : Integer.toString(blocks.size()),
                    contraption.getActors() == null ? "null"
                            : Integer.toString(contraption.getActors().size()),
                    context.world == null ? "null" : context.world.dimension().location(),
                    context.state,
                    entity == null ? "YES - claimed under a provisional id" : "no");
        } catch (RuntimeException | LinkageError failed) {
            // A diagnostic must never take an assembly down, and this one runs on every first callback.
            try {
                RCConfig.LOGGER.warn("[redstonecircuit] carrier identity dump at {} failed: {}", event,
                        failed.toString());
            } catch (RuntimeException | LinkageError ignored) {
                // Nothing left to do.
            }
        }
    }
}

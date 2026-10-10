package com.jiangyuefengyu.redstonecircuit.mixin;

import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.jiangyuefengyu.redstonecircuit.carrier.CarrierProjection;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierStore;
import com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneStore;
import com.jiangyuefengyu.redstonecircuit.data.Slot;
import com.jiangyuefengyu.redstonecircuit.logic.BlockSignalSources;
import com.jiangyuefengyu.redstonecircuit.logic.InnerRedstoneNetwork;
import com.jiangyuefengyu.redstonecircuit.logic.PublishedStructureOutput;
import com.jiangyuefengyu.redstonecircuit.compat.physics.PhysicsCompat;
import com.jiangyuefengyu.redstonecircuit.structure.pocket.PocketSpace;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;

/**
 * Lets inner redstone power the world outside its host block, by injecting into {@code getSignal} and
 * {@code getDirectSignal} - the two methods every redstone consumer asks. NeoForge has no hook that lets a mod
 * make an existing vanilla block emit a signal, and replacing candidate host blocks would change block identity,
 * so a mixin into {@code BlockStateBase} (where both are declared) is the only way to make an inner component
 * indistinguishable from a real signal source.
 *
 * <p>Three places a component can be "at" a world position are asked: the level's own store, a carried
 * structure's projection of the machine, and the same host at its coordinate <em>inside</em> the structure. The
 * suppression window hides the world store and nothing else. Weak and strong power are answered separately, the
 * reader's hop is charged here because this is the one place the reader's identity is known, and <b>which</b>
 * answer wins is {@link BlockSignalSources}' business, so that rule is pinned by offline tests.
 */
@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class BlockStateSignalMixin {

    @Inject(method = "getSignal", at = @At("HEAD"), cancellable = true)
    private void redstonecircuit$innerSignal(BlockGetter level, BlockPos pos, Direction direction,
                                             CallbackInfoReturnable<Integer> cir) {
        int power = redstonecircuit$innerPower(level, pos, direction, false);
        if (power > 0) {
            cir.setReturnValue(power);
        }
    }

    @Inject(method = "getDirectSignal", at = @At("HEAD"), cancellable = true)
    private void redstonecircuit$innerDirectSignal(BlockGetter level, BlockPos pos, Direction direction,
                                                   CallbackInfoReturnable<Integer> cir) {
        int power = redstonecircuit$innerPower(level, pos, direction, true);
        if (power > 0) {
            cir.setReturnValue(power);
        }
    }

    /**
     * How hard a cell inside a pocket plot is being pushed, laid over whatever vanilla answered.
     *
     * <p>The injected cell answers on every face, at {@code RETURN} and as a <b>max</b>: a cell vanilla already
     * reports higher keeps its own value. <b>If the cell is dust</b> the answer depends on who is asking, because
     * the design reads it as "that cell's dust is driven to S": another wire gets {@code S - 1}, its own hop, and
     * anything else gets the full {@code S}. Weak power only - a wire gives no direct signal, so answering the
     * strong query as well would leak power through walls - and the gate in front of it is one static boolean.
     */
    @Inject(method = "getSignal", at = @At("RETURN"), cancellable = true)
    private void redstonecircuit$injectedSignal(BlockGetter level, BlockPos pos, Direction direction,
                                                CallbackInfoReturnable<Integer> cir) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        int injected = PocketSpace.injectedStrength(serverLevel, pos);
        if (injected <= 0) {
            return;
        }
        Integer vanilla = cir.getReturnValue();
        if (vanilla != null && vanilla >= injected) {
            return;
        }
        cir.setReturnValue(redstonecircuit$injectedAnswer(serverLevel, pos, direction, injected));
    }

    /**
     * The READ end of the two new sources, said out loud: "we notified" and "somebody asked" are two different
     * ends, and only the first was visible in the log. At most once every {@code READ_LOG_TICKS} ticks, so a
     * moving machine cannot flood the log.
     */
    private static void redstonecircuit$noteRead(ServerLevel level, BlockPos pos, Direction direction, int answer,
                                                 String source) {
        if (!com.jiangyuefengyu.redstonecircuit.RCConfig.debugLog()) {
            return;
        }
        long now = level.getGameTime();
        if (now - redstonecircuit$lastReadLine < READ_LOG_TICKS) {
            return;
        }
        redstonecircuit$lastReadLine = now;
        com.jiangyuefengyu.redstonecircuit.RCConfig.LOGGER.info("[redstonecircuit] a world query at {} towards {} "
                        + "is answered {} from the {} source", pos.toShortString(), direction, answer, source);
    }

    /** The tick the last read line was written on. */
    private static long redstonecircuit$lastReadLine = Long.MIN_VALUE;

    /** How many ticks apart two read lines may be. */
    private static final int READ_LOG_TICKS = 5;

    /**
     * What one injected cell answers one asker - see the injection above. {@code direction} is the way the signal
     * travels from the asker to this cell, so the asker is on the opposite side.
     */
    private static int redstonecircuit$injectedAnswer(ServerLevel level, BlockPos pos, Direction direction,
                                                      int injected) {
        if (direction == null || !level.getBlockState(pos).is(Blocks.REDSTONE_WIRE)) {
            // Not a dust cell: a virtual source of S, answering everyone alike.
            return injected;
        }
        BlockPos asker = pos.relative(direction.getOpposite());
        if (level.getBlockState(asker).is(Blocks.REDSTONE_WIRE)) {
            // A wire neighbour has its own hop to pay, so it is told S - 1 and lands on S - 1.
            return Math.max(0, injected - 1);
        }
        return injected;
    }

    /**
     * Shared body: gather what the three sources hold at this position, ask {@link BlockSignalSources} which one
     * answers, and report that. {@code strong} is true for the strong-power query ({@code getDirectSignal}).
     */
    private static int redstonecircuit$innerPower(@Nullable BlockGetter level, BlockPos pos,
                                                  @Nullable Direction direction, boolean strong) {
        if (!(level instanceof ServerLevel serverLevel) || direction == null) {
            return 0;
        }
        // The suppression window hides the WORLD STORE only: the two carrier sources are other components, not
        // this solve's own output, and they are the only route by which one physicalised structure reads its
        // neighbour. See BlockSignalSources, which owns the rule and the argument.
        boolean worldStoreSuppressed = BlockSignalSources.suppressWorldStore();

        InnerRedstoneStore store = InnerRedstoneStore.get(serverLevel);
        if (store == null) {
            return 0;
        }

        Slot slot = worldStoreSuppressed || store.isEmpty() ? null : store.slotAt(pos);
        CarrierProjection.ProjectedHost projected = null;
        CarrierProjection.ProjectedHost inStructure = null;
        boolean structureFrameDeclined = false;
        if (slot == null) {
            try {
                projected = CarrierStore.projectedHostAt(serverLevel, pos);
                if (projected == null) {
                    // The second position a carried host answers at: its coordinate inside the structure, which
                    // for a Sable sub-level is a real level position - the query a vanilla device the machine is
                    // carrying makes, in the structure's own frame. The lookup is ownership-guarded: a coordinate
                    // belonging to another live sub-level is refused and left to the world projection source.
                    inStructure = CarrierStore.structureHostAt(serverLevel, pos);
                    structureFrameDeclined = CarrierStore.lastStructureFrameDeclined();
                }
            } catch (RuntimeException | LinkageError failed) {
                // A failure in the bridge must look like a jar without the physics mod, not a broken world.
                return 0;
            }
        }

        // Strong power asks one question more: whether the block doing the asking holds a component of its own,
        // because a host block is the component inside it and not a block of stone. A carried host counts, at its
        // world position or at the structure's own coordinate; the rule only consumes the answer, so the lookup
        // lives here.
        boolean neighbourHoldsComponent = false;
        if (strong) {
            BlockPos asker = pos.relative(direction.getOpposite());
            neighbourHoldsComponent = store.slotAt(asker) != null
                    || CarrierStore.isProjectedAt(serverLevel, asker)
                    || CarrierStore.isStructureHostAt(serverLevel, asker);
        }

        BlockSignalSources.Read read = BlockSignalSources.select(slot, projected, inStructure,
                structureFrameDeclined, worldStoreSuppressed, direction, strong,
                neighbourHoldsComponent);
        int answer = read.answer();
        // AND THE ONE SOURCE THAT IS NOT A BLOCK OF ANY KIND: a physicalised structure's product is DRAWN where
        // the machine is, and that position holds air, so every source above answers nothing there. The product
        // publishes the six strengths it answers with at its drawn position, and this is what reads them. Weak
        // only: that is the question the product block on the ground answers.
        if (answer == 0) {
            if (!strong) {
                // The direction is flipped here: vanilla's `direction` names the way the signal travels from the
                // asker to this block, while a published strength belongs to the face the signal leaves through -
                // the face pointing back at the asker. The product block on the ground does the same conversion.
                int published = PublishedStructureOutput.answer(serverLevel.dimension(), pos,
                        direction.getOpposite(), serverLevel.getGameTime());
                if (published > 0) {
                    redstonecircuit$noteRead(serverLevel, pos, direction, published, "published");
                    return published;
                }
            }
            // AND THE ONE SOURCE THAT IS A BLOCK OF ANOTHER KIND: what a machine carries is not always a product
            // of ours - a redstone block, a torch, a repeater, another structure's product - and it is DRAWN at a
            // world position while the block itself stands in the carrier's plot. The carrier half turns the
            // position back into each live sub-level's own coordinates and asks the block that is really there.
            // The direction is NOT converted here: the block in the plot is asked exactly as the block on the
            // ground would be, and that conversion happens in the carrier's own frame.
            int carried = PhysicsCompat.carriedSignalAt(serverLevel, pos, direction, strong);
            if (carried > 0) {
                redstonecircuit$noteRead(serverLevel, pos, direction, carried, "drawn block");
                return carried;
            }
        }
        // THE HOP THE READER CHARGES, AND WHO THE READER IS. A vanilla redstone wire charges one step for a
        // neighbouring wire, but its own scan only recognises a neighbour whose block state is a redstone wire -
        // so a component inside a host block was read at full strength and a line of them held each other at
        // fifteen. The extra block read is behind the gate that can make it matter: a weak query, a non-zero
        // answer, and a component that is itself a wire. The projection source is read by a component of ours
        // too, and its world cell is air, so the gate answers "not a wire" there and the seam's own charge has to
        // be applied instead - both are the solver's one hop rule.
        if (!strong && answer > 0 && read.slot() != null && read.slot().type.isWire()) {
            BlockPos reader = pos.relative(direction.getOpposite());
            boolean readerIsRedstoneWire = serverLevel.getBlockState(reader).is(Blocks.REDSTONE_WIRE);
            if (read.source() == BlockSignalSources.Source.WORLD_PROJECTION) {
                answer = BlockSignalSources.projectionChargedByReader(read.host(), answer,
                        InnerRedstoneNetwork.seamReaderAt(serverLevel, pos, direction),
                        readerIsRedstoneWire);
            } else {
                answer = BlockSignalSources.hopChargedByReader(read.slot(), answer, readerIsRedstoneWire);
            }
        }
        if (read.fromCarrier() || read.structureFrameDeclined() || read.projectionHit()) {
            CarrierStore.noteSignalRead(serverLevel, read, pos, direction, answer);
        }
        return answer;
    }
}

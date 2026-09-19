package com.jiangyuefengyu.redstonecircuit.mixin;

import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneStore;
import com.jiangyuefengyu.redstonecircuit.logic.InnerRedstoneNetwork;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockBehaviour;

/**
 * Lets inner redstone power the world outside its host block.
 *
 * <h2>Why a mixin is required</h2>
 * NeoForge 21.1.x has no hook that lets a mod make an existing vanilla block emit a redstone signal:
 * there is no {@code Capabilities.RedstoneSignal}, no {@code getSignal} event, and overriding
 * {@code Block#getSignal} only works for blocks you own. Replacing every candidate host block with a
 * modded variant would change block identity across the world, which the design rules out. A mixin
 * into {@code BlockState#getSignal} is therefore the only way to make an inner component
 * indistinguishable from a real signal source.
 *
 * <h2>What it does</h2>
 * Before the vanilla implementations of {@code getSignal} and {@code getDirectSignal} run, this
 * checks whether a component is stored inside the queried position and, if so, reports its power.
 * Every redstone consumer - pistons, lamps, wire, comparators, observers - asks exactly these two
 * methods, so they all see the inner component.
 *
 * <p>The emitted signal is omnidirectional (like a redstone block), which needs no per-direction
 * state and keeps the "power in equals power out" contract that {@code PowerSolver} relies on.
 *
 * <h2>Safety</h2>
 * <ul>
 *   <li>Uses {@link Inject} with an early return, never {@code @Overwrite}, so vanilla logic is left
 *       intact and co-exists with other mixins touching these methods.</li>
 *   <li>Returns immediately when the level is not a {@link ServerLevel} (the store is
 *       server-authoritative), or when the query happens while {@link InnerRedstoneNetwork} is
 *       evaluating its own power - otherwise a component would charge itself from its own value and
 *       the network could never settle.</li>
 *   <li>Rejects {@code Direction.DOWN}, matching vanilla wire, which does not signal downwards.</li>
 * </ul>
 *
 * <h2>Why the target is {@code BlockStateBase}</h2>
 * {@code getSignal} and {@code getDirectSignal} are declared on
 * {@code BlockBehaviour.BlockStateBase}, not on {@code BlockState} itself, and Mixin's target search
 * does not walk up to superclass declarations. Targeting {@code BlockState} therefore fails at load
 * time with "could not find any targets matching 'getSignal'". Targeting the declaring class is what
 * actually patches every block state, since all of them are instances of it.
 */
@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class BlockStateSignalMixin {

    @Inject(method = "getSignal", at = @At("HEAD"), cancellable = true)
    private void redstonecircuit$innerSignal(BlockGetter level, BlockPos pos, Direction direction,
                                             CallbackInfoReturnable<Integer> cir) {
        int power = redstonecircuit$innerPower(level, pos, direction);
        if (power > 0) {
            cir.setReturnValue(power);
        }
    }

    @Inject(method = "getDirectSignal", at = @At("HEAD"), cancellable = true)
    private void redstonecircuit$innerDirectSignal(BlockGetter level, BlockPos pos, Direction direction,
                                                   CallbackInfoReturnable<Integer> cir) {
        int power = redstonecircuit$innerPower(level, pos, direction);
        if (power > 0) {
            cir.setReturnValue(power);
        }
    }

    /** Shared body: the inner component's power, or 0 when this position should behave normally. */
    private static int redstonecircuit$innerPower(@Nullable BlockGetter level, BlockPos pos,
                                                  @Nullable Direction direction) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return 0;
        }
        // Vanilla wire does not signal downwards; mirroring that avoids surprising behaviour below
        // a host block and keeps the rule identical to the vanilla source the solver was derived from.
        if (direction == Direction.DOWN) {
            return 0;
        }
        if (InnerRedstoneNetwork.isSignalSuppressed(serverLevel)) {
            return 0;
        }

        InnerRedstoneStore store = InnerRedstoneStore.get(serverLevel);
        if (store == null || store.isEmpty()) {
            return 0;
        }
        return store.getSignal(pos);
    }
}

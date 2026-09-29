package com.jiangyuefengyu.redstonecircuit.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.jiangyuefengyu.redstonecircuit.block.SuperconductingWireBlock;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Teaches vanilla redstone wire about 超导红石粉 (see {@link SuperconductingWireBlock}).
 *
 * <h2>Why four injections</h2>
 * All of vanilla's wire methods that matter are private or static, so a subclass cannot override them;
 * this mixin intercepts them instead of replacing anything. Each one exists for a different half of the
 * mixing rule, except the last, which is about the wire's <em>shape</em>:
 *
 * <ol>
 *   <li>{@code updatePowerStrength} - when the state being updated is a superconductor, compute its
 *       strength with {@link SuperconductingWireBlock#strengthAt} instead. This is the only place a
 *       wire's strength is ever decided, however the change was triggered.</li>
 *   <li>{@code getWireSignal} - the scan an ordinary wire runs over its neighbours, whose result it then
 *       subtracts one from. Counting the superconductor here, rather than letting it appear as a
 *       <em>source</em>, is what makes "superconductor to dust costs one" true: the ordinary wire's own
 *       hop rule is left to do the subtraction.</li>
 *   <li>{@code calculateTargetStrength} - a bracket around that method so the superconductor knows to
 *       report zero while an ordinary wire is deciding. Without it the superconductor would be found by
 *       the ordinary wire's source scan at full strength and the subtraction above would be undone.</li>
 *   <li>{@code shouldConnectTo} - whether a wire may connect to a block at all, which is what decides
 *       the four {@code none|side|up} properties the blockstate JSON turns into a picture. Vanilla
 *       answers {@code true} unconditionally for its own dust and, for everything else, only when it is
 *       asked about a particular side - and the wire's own rule asks with <em>no</em> side exactly when
 *       it wants to know whether it may climb or drop past a block
 *       ({@code getConnectingSide}'s two {@code null}-direction calls). A superconductor was therefore
 *       always told no, and both ends of a step - the rising bar at its foot and the arm reaching down
 *       off it - came out missing while the strength crossed the step anyway, because that is a
 *       different question asked a different way. NeoForge exposes this one answer as
 *       {@code Block#canConnectRedstone} and {@link SuperconductingWireBlock} overrides it there;
 *       Fabric has no such hook, so the static predicate is intercepted here. This is a shape-only
 *       change: nothing in the power path consults it.</li>
 * </ol>
 *
 * <h2>Why this is safe</h2>
 * Every injection returns to vanilla behaviour for any state that is not a superconductor, so ordinary
 * redstone is untouched. The two flag injections only manage a counter; nothing is cancelled, and no
 * method is overwritten.
 */
@Mixin(RedStoneWireBlock.class)
public abstract class RedStoneWireBlockMixin {

    /** Brackets vanilla's calculation, so a superconductor beside it stays quiet for the duration. */
    @Inject(method = "calculateTargetStrength", at = @At("HEAD"))
    private void redstonecircuit$beforeStrength(Level level, BlockPos pos,
                                                CallbackInfoReturnable<Integer> callback) {
        SuperconductingWireBlock.beginSuppression();
    }

    @Inject(method = "calculateTargetStrength", at = @At("RETURN"))
    private void redstonecircuit$afterStrength(Level level, BlockPos pos,
                                               CallbackInfoReturnable<Integer> callback) {
        SuperconductingWireBlock.endSuppression();
    }

    /** Lets an ordinary wire's wire scan see a superconductor, so it charges its own hop for it. */
    @Inject(method = "getWireSignal", at = @At("HEAD"), cancellable = true)
    private void redstonecircuit$wireSignal(BlockState state, CallbackInfoReturnable<Integer> callback) {
        if (SuperconductingWireBlock.isSuperconductor(state)) {
            callback.setReturnValue(state.getValue(RedStoneWireBlock.POWER));
        }
    }

    /**
     * Hands the strength calculation over to the superconductor's own rules.
     *
     * <p>Which solves the whole run rather than this one block: a lossless wire cannot be decided from
     * its neighbours' current values, or a run whose source has just been switched off would hold itself
     * up for ever. See {@link SuperconductingWireBlock}.
     *
     * <p>The nested calls are skipped while a solve is running. Every wire a solve writes notifies its
     * neighbours, which would otherwise start a fresh solve of the same run for each of them - the same
     * answer every time, at the cost of walking the run once per wire. Skipping them is safe because a
     * solve finishes the whole run, and because one run never feeds another: a superconductor that is
     * merely next to another, rather than joined to it, is not a supply for it.
     */
    @Inject(method = "updatePowerStrength", at = @At("HEAD"), cancellable = true)
    private void redstonecircuit$updateStrength(Level level, BlockPos pos, BlockState state,
                                                CallbackInfo callback) {
        if (!SuperconductingWireBlock.isSuperconductor(state)) {
            return;
        }
        if (SuperconductingWireBlock.isSuppressed()) {
            callback.cancel();
            return;
        }
        SuperconductingWireBlock.updateComponent(level, pos);
        callback.cancel();
    }

    /**
     * A wire connects to a superconductor the way it connects to ordinary dust: yes, unconditionally.
     *
     * <p>This is vanilla's own first branch for dust ({@code state.is(Blocks.REDSTONE_WIRE)}), given to
     * the superconductor as well. The step cases call this overload with no direction at all - see the
     * class comment - which is the whole reason both ends of a step used to draw nothing.
     *
     * <p>Static because the target is: static methods are hidden rather than overridden, so a subclass
     * cannot reach this decision and the predicate itself has to answer.
     */
    @Inject(method = "shouldConnectTo(Lnet/minecraft/world/level/block/state/BlockState;)Z",
            at = @At("HEAD"), cancellable = true)
    private static void redstonecircuit$stepConnectsToSuperconductor(BlockState state,
                                                                     CallbackInfoReturnable<Boolean> callback) {
        if (SuperconductingWireBlock.isSuperconductor(state)) {
            callback.setReturnValue(true);
        }
    }

    /** The same answer for the side-aware question the flat connection asks. */
    @Inject(method = "shouldConnectTo(Lnet/minecraft/world/level/block/state/BlockState;"
                    + "Lnet/minecraft/core/Direction;)Z",
            at = @At("HEAD"), cancellable = true)
    private static void redstonecircuit$connectsToSuperconductor(BlockState state, Direction direction,
                                                                 CallbackInfoReturnable<Boolean> callback) {
        if (SuperconductingWireBlock.isSuperconductor(state)) {
            callback.setReturnValue(true);
        }
    }
}

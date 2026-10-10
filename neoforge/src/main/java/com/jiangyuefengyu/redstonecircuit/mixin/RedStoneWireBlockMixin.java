package com.jiangyuefengyu.redstonecircuit.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.jiangyuefengyu.redstonecircuit.block.SuperconductingWireBlock;
import com.jiangyuefengyu.redstonecircuit.structure.pocket.PocketSpace;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Teaches vanilla redstone wire about 超导红石粉 (see {@link SuperconductingWireBlock}).
 *
 * <p>All three of vanilla's wire methods that matter are private, so a subclass cannot override them; this mixin
 * intercepts them instead of replacing anything:
 *
 * <ol>
 *   <li>{@code updatePowerStrength} - when the state being updated is a superconductor, compute its strength with
 *       {@link SuperconductingWireBlock#strengthAt} instead. This is the only place a wire's strength is ever
 *       decided, however the change was triggered.</li>
 *   <li>{@code getWireSignal} - the scan an ordinary wire runs over its neighbours, whose result it then subtracts
 *       one from. Counting the superconductor here, rather than letting it appear as a <em>source</em>, is what makes
 *       "superconductor to dust costs one" true.</li>
 *   <li>{@code calculateTargetStrength} - a bracket around that method so the superconductor knows to report zero
 *       while an ordinary wire is deciding. Without it the superconductor would be found by the ordinary wire's
 *       source scan at full strength and the subtraction above would be undone.</li>
 * </ol>
 *
 * <p>Every injection returns to vanilla behaviour for any state that is not a superconductor, so ordinary redstone is
 * untouched: the two flag injections only manage a counter, and nothing is cancelled or overwritten.
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
     * Hands the strength calculation over to the superconductor's own rules, which solve the whole run rather than
     * this one block: a lossless wire cannot be decided from its neighbours' current values, or a run whose source
     * has just been switched off would hold itself up for ever.
     *
     * <p>Nested calls are skipped while a solve is running, because every wire a solve writes notifies its neighbours
     * and would otherwise start a fresh solve of the same run for each of them. That is safe because a solve finishes
     * the whole run, and one run never feeds another: a superconductor merely next to another, rather than joined to
     * it, is not a supply for it.
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
     * A cell the pocket overlay injects into stops its own dust at the injected strength.
     *
     * <p>Dust computing its own strength only looks at its neighbours and never at itself, so the "this cell reports
     * S" signal added for everyone else ({@code BlockStateSignalMixin}) has no effect on the cell itself: it would
     * settle at S−2 and decay from there. Pinning the cell's own <em>target</em> strength to
     * {@code max(vanilla, injected)} is the literal reading of "this cell's dust is S" - neighbours still read S−1
     * through the signal overlay, and decay continues normally afterwards. {@code calculateTargetStrength} is the one
     * place dust decides its own strength, so lifting the value on RETURN is enough, and when nothing is injected the
     * vanilla answer stands.
     */
    @Inject(method = "calculateTargetStrength", at = @At("RETURN"), cancellable = true)
    private void redstonecircuit$injectedCell(Level level, BlockPos pos, CallbackInfoReturnable<Integer> callback) {
        if (!(level instanceof net.minecraft.server.level.ServerLevel serverLevel)) {
            return;
        }
        int injected = PocketSpace.injectedStrength(serverLevel, pos);
        if (injected > 0 && injected > callback.getReturnValue()) {
            callback.setReturnValue(injected);
        }
    }
}

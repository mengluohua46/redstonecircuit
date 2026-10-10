package com.jiangyuefengyu.redstonecircuit.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.jiangyuefengyu.redstonecircuit.logic.NoWeakPowerConduction;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.SignalGetter;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Takes the weak-power term back out of a signal query about one of the mod's own devices.
 *
 * <h2>Why this is a mixin and not an override</h2>
 * {@code getSignal} is a <b>default method on {@link SignalGetter}</b>, and it is the one place the
 * weak-power term is added:
 *
 * <pre>
 *   int own = state.getSignal(this, pos, direction);
 *   return Math.max(own, this.getDirectSignalTo(pos));
 * </pre>
 *
 * <p>A block cannot override an interface's default method, and vanilla offers no hook to opt out of that
 * second term, so the method itself is patched: for a block that is a {@link NoWeakPowerConduction}, the
 * answer becomes the block's own, exactly as it was before the term was added. Everything that is not one of
 * those blocks is untouched - the injection returns the value vanilla computed.
 *
 * <p>The injection is at {@code RETURN} rather than {@code HEAD} on purpose: it must not have to re-derive
 * anything vanilla already worked out, and a block that is not ours is not even looked at, so the cost on the
 * hottest signal path in the game is one {@code instanceof} per query. {@code cancellable} is what makes the
 * replacement possible at that point.
 *
 * <p>See {@link NoWeakPowerConduction} for what goes wrong without it.
 */
@Mixin(SignalGetter.class)
public interface SignalGetterMixin {

    @Inject(method = "getSignal(Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;)I",
            at = @At("RETURN"),
            cancellable = true)
    private void redstonecircuit$onlyItsOwnPower(BlockPos pos, Direction direction,
                                                 CallbackInfoReturnable<Integer> cir) {
        // The mixin's own `this` is this interface, so the queried level is reached through the target type
        // instead - which is also what keeps the handler honest: it only ever uses what SignalGetter offers.
        SignalGetter level = (SignalGetter) (Object) this;
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof NoWeakPowerConduction) {
            cir.setReturnValue(state.getSignal(level, pos, direction));
        }
    }
}

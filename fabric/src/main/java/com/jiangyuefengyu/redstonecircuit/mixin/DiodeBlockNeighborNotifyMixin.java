package com.jiangyuefengyu.redstonecircuit.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.jiangyuefengyu.redstonecircuit.logic.InnerRedstoneTickHandler;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.DiodeBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The third place a neighbour notification starts, mirroring NeoForge.
 *
 * <h2>What this adds to {@link ServerLevelNeighborNotifyMixin}</h2>
 * NeoForge fires {@code NeighborNotifyEvent} from three call sites, and
 * {@code DiodeBlock#updateNeighborsInFront} is one of them (NeoForge 21.1.100
 * {@code patches/net/minecraft/world/level/block/DiodeBlock.java.patch}): a repeater or comparator
 * whose output changes notifies the block <em>in front of it</em>, and that notification is announced
 * with the diode as its position and exactly one notified side - the direction from the diode towards
 * the block it drives - rather than as "all sides except one".
 *
 * <p>The other two are the {@code ServerLevel} methods, and they cover the position this one names
 * (the diode's own second call, {@code updateNeighborsAtExceptFromFacing(front, this, facing)}, reports
 * the host in front as an {@code updateNeighborsAtExceptFromFacing} notification too). So this hook is
 * the redundant one - kept because "the mod reacts to the outside world exactly where NeoForge's event
 * does" is a property worth being able to check by reading three injections, and because a hook that
 * never fires costs one virtual call per diode output change.
 *
 * <p>{@code ObserverBlock} carries its own copy of {@code updateNeighborsInFront}; NeoForge does not
 * patch that one, and there is nothing to gain from being stricter than the loader whose behaviour the
 * author confirmed in game - the position it notifies arrives through
 * {@code ServerLevel#updateNeighborsAtExceptFromFacing} all the same.
 *
 * <h2>Which side is which</h2>
 * {@code FACING} is the diode's <em>input</em> side: vanilla reads its input from
 * {@code pos.relative(FACING)} and drives the block the other way. The notified side is therefore
 * {@code FACING.getOpposite()}, which is exactly the single direction NeoForge puts in the event.
 *
 * <p>{@code require = 1}: see {@link ServerLevelNeighborNotifyMixin} for why a silent failure here is
 * worse than a loud one at startup.
 */
@Mixin(DiodeBlock.class)
public abstract class DiodeBlockNeighborNotifyMixin {

    @Inject(
            method = "updateNeighborsInFront(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)V",
            at = @At("HEAD"))
    private void redstonecircuit$neighborNotify(Level level, BlockPos pos, BlockState state,
                                                CallbackInfo callback) {
        if (level instanceof ServerLevel serverLevel) {
            InnerRedstoneTickHandler.onNeighborNotify(serverLevel, pos,
                    InnerRedstoneTickHandler.side(state.getValue(DiodeBlock.FACING).getOpposite()),
                    "a diode's front");
        }
    }
}

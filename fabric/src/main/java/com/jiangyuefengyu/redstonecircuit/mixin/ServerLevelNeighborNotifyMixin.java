package com.jiangyuefengyu.redstonecircuit.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.jiangyuefengyu.redstonecircuit.logic.InnerRedstoneTickHandler;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;

/**
 * Fabric's stand-in for NeoForge's {@code BlockEvent.NeighborNotifyEvent}.
 *
 * <h2>Why the mod cannot do without this</h2>
 * Inner redstone is stored outside the world's block states, so nothing vanilla does ever marks it
 * dirty. A lever placed against a host block, a torch switched off beside one, a repeater that starts
 * driving into one: none of those change a state of ours, and without being told about them the
 * component inside would keep reporting the power it had when it was placed - levers and torches would
 * appear to do nothing, and power would stay latched after the source was removed.
 *
 * <h2>Why {@code ServerLevel} and not {@code Level}</h2>
 * <b>This is the whole point of the class, and getting it wrong is silent.</b> Vanilla declares the
 * neighbour-notification entry points twice: {@code Level} has them as empty bodies, and
 * {@code ServerLevel} overrides both with the real work -
 *
 * <pre>
 *   Level#updateNeighborsAt(pos, block)                        { }            // empty
 *   Level#updateNeighborsAtExceptFromFacing(pos, block, skip)  { }            // empty
 *   ServerLevel#updateNeighborsAt(pos, block)                  { neighborUpdater.updateNeighborsAtExceptFromFacing(pos, block, null); }
 *   ServerLevel#updateNeighborsAtExceptFromFacing(...)         { neighborUpdater.updateNeighborsAtExceptFromFacing(pos, block, skip); }
 * </pre>
 *
 * <p>Every level a server actually runs is a {@code ServerLevel}, and Java dispatches a virtual call to
 * the override, so a {@code @Mixin(Level.class)} injection into either method <em>never executes</em>.
 * That was exactly this mod's bug: the world→inner-redstone input chain was dead while the inner→inner
 * and inner→world chains (which never come through here) kept working, so levers and wires beside a host
 * block did nothing at all.
 *
 * <p>NeoForge has the same shape and patches the same two places - its {@code NeighborNotifyEvent} is
 * fired from {@code ServerLevel#updateNeighborsAt} and
 * {@code ServerLevel#updateNeighborsAtExceptFromFacing} (NeoForge 21.1.100
 * {@code patches/net/minecraft/server/level/ServerLevel.java.patch}), and from
 * {@code DiodeBlock#updateNeighborsInFront} (see {@link DiodeBlockNeighborNotifyMixin}). Injecting into
 * those same three call sites is what makes this a faithful stand-in rather than a plausible-looking one.
 *
 * <p>{@code require = 1}: if a future Minecraft reshapes these methods, the mod would silently stop
 * reacting to the outside world, which is far worse than a loud failure at startup.
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelNeighborNotifyMixin {

    /**
     * Every side is notified: the six neighbours of {@code pos} are all told, and so is {@code pos}
     * itself (which may hold a component whose own output just changed).
     */
    @Inject(
            method = "updateNeighborsAt(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Block;)V",
            at = @At("HEAD"))
    private void redstonecircuit$neighborNotify(BlockPos pos, Block block, CallbackInfo callback) {
        InnerRedstoneTickHandler.onNeighborNotify((ServerLevel) (Object) this, pos,
                InnerRedstoneTickHandler.ALL_SIDES, "updateNeighborsAt");
    }

    /**
     * All sides but one. This is the overload the redstone blocks themselves call - wire, diodes,
     * observers, torches, levers, buttons, plates - so it carries most of the outside world's news.
     *
     * <p>{@code skipSide} is the one direction vanilla leaves alone, which is all that is needed to
     * rebuild the notified set. It is {@code null} on some call paths, meaning "skip nothing".
     */
    @Inject(
            method = "updateNeighborsAtExceptFromFacing(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Block;Lnet/minecraft/core/Direction;)V",
            at = @At("HEAD"))
    private void redstonecircuit$neighborNotifyExceptFromFacing(BlockPos pos, Block block,
                                                                Direction skipSide,
                                                                CallbackInfo callback) {
        InnerRedstoneTickHandler.onNeighborNotify((ServerLevel) (Object) this, pos,
                InnerRedstoneTickHandler.allSidesExcept(skipSide),
                skipSide == null ? "updateNeighborsAtExceptFromFacing(null)" : "updateNeighborsAtExceptFromFacing");
    }
}

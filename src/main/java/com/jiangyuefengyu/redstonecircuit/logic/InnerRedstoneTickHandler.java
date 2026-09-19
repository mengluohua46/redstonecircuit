package com.jiangyuefengyu.redstonecircuit.logic;

import com.jiangyuefengyu.redstonecircuit.RCConfig;
import com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneStore;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * Resolves queued inner-redstone power updates once per level tick.
 *
 * <p>Batching to the end of the tick means a burst of placements (or a chain reaction of block
 * changes) costs a single propagation pass instead of one per change.
 */
public final class InnerRedstoneTickHandler {

    @SubscribeEvent
    public void onLevelTick(LevelTickEvent.Post event) {
        if (event.getLevel() instanceof ServerLevel level) {
            InnerRedstoneNetwork.tick(level);
        }
    }

    /**
     * Re-solves inner redstone when the world around a host block changes.
     *
     * <p>Without this, an inner component would never react to the outside world: placing a lever or
     * redstone torch against a host block changes no state of ours, so nothing would ever mark the
     * network dirty and the component would keep reporting its old power forever. That was a real
     * bug - levers and torches appeared to do nothing, and power stayed latched after the source was
     * removed.
     */
    @SubscribeEvent
    public void onNeighborNotify(BlockEvent.NeighborNotifyEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        InnerRedstoneStore store = InnerRedstoneStore.get(level);
        if (store.isEmpty()) {
            return;
        }

        // The block at event.getPos() is the one that CHANGED. Its neighbours are the ones being
        // notified, so a host block is the *neighbour* here, not the source. Checking getPos()
        // instead would miss every case where the source disappeared: when a lever is broken, the
        // position it used to occupy is air by the time this event fires.
        for (Direction direction : event.getNotifiedSides()) {
            BlockPos neighbour = event.getPos().relative(direction);
            if (store.slotAt(neighbour) != null) {
                if (RCConfig.debugLog()) {
                    RCConfig.LOGGER.info(
                            "[redstonecircuit] world change at {} notified host {} -> re-solving",
                            event.getPos().toShortString(), neighbour.toShortString());
                }
                InnerRedstoneNetwork.markDirty(level, neighbour);
            }
        }
    }

    /** Drops queued work when a level unloads, so stale positions cannot leak across reloads. */
    @SubscribeEvent
    public void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            InnerRedstoneNetwork.clear(level);
        }
    }

    @SubscribeEvent
    public void onServerStopped(ServerStoppedEvent event) {
        if (RCConfig.debugLog()) {
            RCConfig.LOGGER.info("[redstonecircuit] server stopped, clearing queued redstone updates");
        }
    }
}

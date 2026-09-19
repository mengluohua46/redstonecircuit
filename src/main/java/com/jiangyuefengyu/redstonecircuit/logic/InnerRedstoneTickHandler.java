package com.jiangyuefengyu.redstonecircuit.logic;

import com.jiangyuefengyu.redstonecircuit.RCConfig;

import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
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

package com.jiangyuefengyu.redstonecircuit.structure.pocket;

import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import com.jiangyuefengyu.redstonecircuit.RCConfig;

/**
 * Keeps the pocket's environment in step with the overworld, and clears the plot table when a server
 * stops.
 *
 * <p>Hooked on the same event the mod's own tick handler uses, for the same reason: this is a level tick
 * that has already happened, so nothing done here can change the tick it is reacting to.
 */
public final class PocketTickHandler {

    @SubscribeEvent
    public void onLevelTick(LevelTickEvent.Post event) {
        // Only the pocket: every other level returns on the first statement, so one comparison per level tick.
        if (!(event.getLevel() instanceof ServerLevel level)
                || !level.dimension().equals(PocketDimension.STRUCTURE_SPACE)) {
            return;
        }
        // A self test waits for a tick to happen, so it gets its answer here - one visit per level tick, no queue.
        PocketCommand.tick(level);
        if (!RCConfig.structurePocketSyncEnvironment()) {
            return;
        }
        ServerLevel overworld = level.getServer().overworld();
        if (overworld == null || overworld == level) {
            return;
        }
        PocketSpace.of(level).syncEnvironmentFrom(overworld);
    }

    @SubscribeEvent
    public void onServerStopped(ServerStoppedEvent event) {
        PocketCommand.clear();
        PocketSpace.forgetAll();
    }
}

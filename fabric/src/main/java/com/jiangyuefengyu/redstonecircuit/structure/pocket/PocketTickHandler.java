package com.jiangyuefengyu.redstonecircuit.structure.pocket;

import com.jiangyuefengyu.redstonecircuit.RCConfig;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.level.ServerLevel;

/**
 * Keeps the pocket's environment in step with the overworld, and clears the plot table when a server
 * stops.
 *
 * <p>Hooked on the same tick the mod's own handler uses, and for the same reason: the world tick callback
 * reports a tick that has already happened, so nothing done here can change the tick it is reacting to.
 */
public final class PocketTickHandler {

    private PocketTickHandler() {
    }

    /** Registers the per-level tick and the shutdown sweep. Called from the entrypoint. */
    public static void register() {
        ServerTickEvents.END_WORLD_TICK.register(PocketTickHandler::onLevelTick);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            PocketCommand.clear();
            PocketSpace.forgetAll();
        });
    }

    private static void onLevelTick(ServerLevel level) {
        // Only the pocket: every other level returns on the first statement, so one comparison per level tick.
        if (!level.dimension().equals(PocketDimension.STRUCTURE_SPACE)) {
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
}

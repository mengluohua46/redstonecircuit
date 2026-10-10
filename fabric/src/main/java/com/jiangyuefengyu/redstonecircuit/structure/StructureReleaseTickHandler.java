package com.jiangyuefengyu.redstonecircuit.structure;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.level.ServerLevel;

/**
 * Advances releases, one level tick at a time.
 *
 * <p>Loader glue: the NeoForge build subscribes the same three moments to its event bus; here they are
 * Fabric callbacks, all three of which hand over exactly what is needed -
 * {@code ServerTickEvents.END_WORLD_TICK} gives the {@link ServerLevel} itself rather than a
 * {@code Level} to be filtered, and the lifecycle callbacks give the server.
 *
 * <p>A class of its own rather than a branch inside the mod's per-level handler, which is the carrier and
 * solver path: this one costs one empty-list check per level tick while no release is running.
 */
public final class StructureReleaseTickHandler {

    private StructureReleaseTickHandler() {
    }

    /** Registers the retry loop, the start-up repair and the shutdown sweep. Called from the entrypoint. */
    public static void register() {
        ServerTickEvents.END_WORLD_TICK.register(StructureReleaseTickHandler::onLevelTick);
        // The repair needs every level to exist and the store to be readable, which is why it waits for the
        // whole server rather than for a level tick. See ReleaseJournal for why "write again" is the repair.
        ServerLifecycleEvents.SERVER_STARTED.register(ReleaseJournal::recover);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> StructureRelease.forgetAll());
    }

    private static void onLevelTick(ServerLevel level) {
        // The pacing lives on each job, so a release runs at the speed it was started with whatever the
        // configuration says later.
        StructureRelease.tick(level);
    }
}

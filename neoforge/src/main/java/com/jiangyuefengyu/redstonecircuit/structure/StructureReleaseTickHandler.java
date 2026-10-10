package com.jiangyuefengyu.redstonecircuit.structure;

import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * Advances releases, one level tick at a time.
 *
 * <p>Loader glue: the fabric build writes its own. A listener of its own rather than a branch inside the mod's
 * per-level handler, which is the carrier and solver path; this one costs one empty-list check per level tick while no
 * release is running.
 */
public final class StructureReleaseTickHandler {

    @SubscribeEvent
    public void onLevelTick(LevelTickEvent.Post event) {
        if (event.getLevel() instanceof ServerLevel level) {
            // The pacing lives on each job, so a release runs at the speed it was started with whatever the
            // configuration says later.
            StructureRelease.tick(level);
        }
    }

    /**
     * Picks up a release a previous run left half-written. On {@code ServerStartedEvent} rather than a level tick: the
     * repair needs every level to exist and the store to be readable. See {@link ReleaseJournal} for why "write again".
     */
    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        ReleaseJournal.recover(event.getServer());
    }

    @SubscribeEvent
    public void onServerStopped(ServerStoppedEvent event) {
        StructureRelease.forgetAll();
    }
}

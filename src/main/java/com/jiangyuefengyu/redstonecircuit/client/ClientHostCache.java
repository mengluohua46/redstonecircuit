package com.jiangyuefengyu.redstonecircuit.client;

import java.util.List;

import com.jiangyuefengyu.redstonecircuit.RCConfig;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * The client's copy of "which blocks hold redstone", and the thing that makes the world re-render
 * when that answer changes.
 *
 * <p>This is the client-only half: it owns the shared {@link HostIndex} and turns "this section
 * changed" into a mesh rebuild. The bookkeeping itself lives in {@code HostIndex} so it can be tested
 * without a game.
 */
public final class ClientHostCache {

    private static final HostIndex INDEX = new HostIndex(ClientHostCache::markSectionDirty);

    private ClientHostCache() {
    }

    /** Replaces the whole set. Sent on login, dimension change and respawn. */
    public static void applySnapshot(ResourceKey<Level> dimension, List<BlockPos> positions) {
        INDEX.applySnapshot(dimension, positions);
        // Logged because "the client never learned about the block" and "the client learned but drew
        // nothing" look identical on screen; this line tells the two apart.
        if (RCConfig.debugLog()) {
            RCConfig.LOGGER.info("[redstonecircuit] client: {} host(s) in {}",
                    INDEX.size(), dimension.location());
        }
    }

    /** Adds or removes one position. Sent whenever a block gains or loses a component. */
    public static void applyChange(ResourceKey<Level> dimension, BlockPos pos, boolean present) {
        int before = INDEX.size();
        INDEX.applyChange(dimension, pos, present);
        if (INDEX.size() != before && RCConfig.debugLog()) {
            RCConfig.LOGGER.info("[redstonecircuit] client: host {} at {} -> {} host(s) in total",
                    present ? "added" : "removed", pos.toShortString(), INDEX.size());
        }
    }

    /** Drops everything, e.g. when leaving the world. */
    public static void clear() {
        INDEX.clear();
    }

    /**
     * The hosts inside one section, for the renderer.
     *
     * <p>Returns a copy on purpose: the caller may keep it past the end of the frame, because the
     * section mesh is built on a worker thread.
     */
    public static List<BlockPos> hostsInSection(ResourceKey<Level> dimension, BlockPos sectionOrigin) {
        return INDEX.hostsInSection(dimension, sectionOrigin);
    }

    /** How many blocks the client currently believes hide redstone; used by the debug command. */
    public static int size() {
        return INDEX.size();
    }

    /**
     * Queues one section for a mesh rebuild.
     *
     * <p>Nothing else re-renders the world when our data changes: the block state is untouched, so
     * the chunk renderer would happily keep serving the mesh it built before. This is the vanilla
     * entry point for that, and modded chunk renderers hook it too (Sodium's {@code LevelRendererMixin}
     * does).
     */
    private static void markSectionDirty(long sectionKey) {
        Minecraft minecraft = Minecraft.getInstance();
        // Null only in a headless unit test; the index is deliberately usable without a client.
        if (minecraft == null) {
            return;
        }
        LevelRenderer renderer = minecraft.levelRenderer;
        if (renderer == null) {
            return;
        }
        renderer.setSectionDirty(SectionPos.x(sectionKey), SectionPos.y(sectionKey),
                SectionPos.z(sectionKey));
    }
}

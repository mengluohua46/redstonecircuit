package com.jiangyuefengyu.redstonecircuit.client;

import java.util.List;
import java.util.function.BiConsumer;

import org.jetbrains.annotations.Nullable;

import com.jiangyuefengyu.redstonecircuit.RCConfig;
import com.jiangyuefengyu.redstonecircuit.data.HostEntry;
import com.jiangyuefengyu.redstonecircuit.data.Slot;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * The client's copy of "which blocks hold redstone, and what is inside them".
 *
 * <p>This is the client-only half: it owns the shared {@link HostIndex}. The bookkeeping itself lives
 * in {@code HostIndex} so it can be tested without a game.
 *
 * <h2>Nothing is baked into the world</h2>
 * The shell and the component inside it are drawn once per frame rather than written into the chunk
 * mesh, so a change here needs no mesh rebuild: the next frame simply draws something else. (The
 * earlier build wrote the shell into the section's own buffer through
 * {@code AddSectionGeometryEvent}, which looked right but could not be switched off again - a baked
 * mesh cannot react to a player putting goggles on - and had to force a section rebuild on every
 * power change.)
 */
public final class ClientHostCache {

    private static final HostIndex INDEX = new HostIndex();

    private ClientHostCache() {
    }

    /** Replaces the whole set. Sent on login, dimension change and respawn. */
    public static void applySnapshot(ResourceKey<Level> dimension, List<HostEntry> entries) {
        INDEX.applySnapshot(dimension, entries);
        // Logged because "the client never learned about the block" and "the client learned but drew
        // nothing" look identical on screen; this line tells the two apart.
        if (RCConfig.debugLog()) {
            RCConfig.LOGGER.info("[redstonecircuit] client: {} host(s) in {}",
                    INDEX.size(), dimension.location());
        }
    }

    /** Adds, updates or removes one position. */
    public static void applyChange(ResourceKey<Level> dimension, BlockPos pos, @Nullable Slot slot) {
        int before = INDEX.size();
        INDEX.applyChange(dimension, pos, slot);
        if (INDEX.size() != before && RCConfig.debugLog()) {
            RCConfig.LOGGER.info("[redstonecircuit] client: host {} at {} -> {} host(s) in total",
                    slot != null ? "added" : "removed", pos.toShortString(), INDEX.size());
        }
    }

    /** Adds, updates or removes several positions in one packet. */
    public static void applyChanges(ResourceKey<Level> dimension, List<HostEntry> entries) {
        INDEX.applyChanges(dimension, entries);
    }

    /** Drops everything, e.g. when leaving the world. */
    public static void clear() {
        INDEX.clear();
    }

    /** Visits every host within a radius of sections; the per-frame renderer's entry point. */
    public static void forEachNear(ResourceKey<Level> dimension, BlockPos center, int sectionRadius,
                                   BiConsumer<BlockPos, Slot> action) {
        INDEX.forEachNear(dimension, center, sectionRadius, action);
    }

    /** What the client believes is inside one block, or {@code null}. */
    @Nullable
    public static Slot slotAt(ResourceKey<Level> dimension, BlockPos pos) {
        return INDEX.slotAt(dimension, pos);
    }

    /** How many blocks the client currently believes hold redstone; used by the debug command. */
    public static int size() {
        return INDEX.size();
    }
}

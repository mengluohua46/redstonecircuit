package com.jiangyuefengyu.redstonecircuit.compat.physics;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * The structures 方块放置器 has assembled and not yet judged.
 *
 * <p>A sub-level's body cannot be read in the call that made it: Sable computes a plot's bounding box and uploads a
 * structure's mass after that call has returned, so a sound structure asked too early answers exactly what an empty
 * one does. The judgement therefore belongs to the next carrier walk, and this is the list that walk reads.
 *
 * <p>Each entry carries the two facts such a line needs - the sub-level's own identity and the world cell the
 * machine was pointing at - plus the dimension it belongs to and the tick it was assembled in. Both are filters
 * rather than diagnostics: a walk of one level cannot see another level's structures at all, so it must not judge
 * them, and a structure assembled in the tick a walk runs in is still being written.
 *
 * <p>Bounded on purpose: the judgement is a backstop, and a walk that never runs must not be able to grow this list
 * for the rest of the run.
 */
public final class PlacerAssemblyWatch {

    /** The most structures that may wait for a judgement at once. */
    public static final int MAX_PENDING = 64;

    /** One structure waiting for its body to be judged. */
    public record Pending(UUID uuid, ResourceKey<Level> dimension, BlockPos worldTarget, long tick) {
    }

    private static final Map<UUID, Pending> PENDING = new LinkedHashMap<>();

    private PlacerAssemblyWatch() {
    }

    /** Registers a structure Sable has just assembled, to be judged on a later walk. */
    public static synchronized void watch(UUID uuid, ResourceKey<Level> dimension, BlockPos worldTarget, long tick) {
        if (uuid == null || dimension == null || worldTarget == null) {
            return;
        }
        Pending entry = new Pending(uuid, dimension, worldTarget, tick);
        // Re-inserted rather than left where it stands: the map is ordered oldest first, which is the order the cap
        // below gives up in.
        PENDING.remove(uuid);
        PENDING.put(uuid, entry);
        while (PENDING.size() > MAX_PENDING) {
            Iterator<UUID> oldest = PENDING.keySet().iterator();
            oldest.next();
            oldest.remove();
        }
    }

    /** Everything waiting for a judgement, oldest first. */
    public static synchronized List<Pending> pending() {
        return List.copyOf(PENDING.values());
    }

    /**
     * Takes one structure off the list, whether it was judged or given up on.
     *
     * @return whether it was waiting at all
     */
    public static synchronized boolean resolve(UUID uuid) {
        return uuid != null && PENDING.remove(uuid) != null;
    }

    /** Forgets every waiting structure, as a freshly started server would. Every test starts from here. */
    public static synchronized void clearForTests() {
        PENDING.clear();
    }
}

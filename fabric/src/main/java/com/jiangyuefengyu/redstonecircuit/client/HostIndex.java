package com.jiangyuefengyu.redstonecircuit.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

import org.jetbrains.annotations.Nullable;

import com.jiangyuefengyu.redstonecircuit.data.HostEntry;
import com.jiangyuefengyu.redstonecircuit.data.Slot;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * Which blocks hold redstone - and what is inside them - as far as one client knows, bucketed by
 * {@link SectionPos}.
 *
 * <p>One dimension at a time: every packet names its own dimension, so packets for another are ignored rather than
 * merged, and nothing stale is drawn. A {@link Slot} per position rather than a bare position, because the renderer
 * draws the component inside the block. Everything runs on the client main thread, so {@link #forEachNear} hands
 * entries straight to the caller instead of snapshotting them.
 */
public final class HostIndex {

    /** {@link SectionPos#asLong} -> the hosts inside that section, with what each one holds. */
    private final Map<Long, List<HostEntry>> bySection = new HashMap<>();

    /** The dimension the stored positions belong to, or {@code null} before the first snapshot. */
    private ResourceKey<Level> dimension;

    /** One host block: where it is and what is inside it. */
        // -------------------------------------------------------------- updates --

    /** Replaces the whole set. Sent on login, dimension change and respawn. */
    public void applySnapshot(ResourceKey<Level> dim, List<HostEntry> entries) {
        bySection.clear();
        dimension = dim;
        for (HostEntry entry : entries) {
            put(entry);
        }
    }

    /** Adds, replaces or removes one position. */
    public void applyChange(ResourceKey<Level> dim, BlockPos pos, @Nullable Slot slot) {
        if (!accepts(dim)) {
            return;
        }
        if (slot == null) {
            remove(pos);
        } else {
            put(new HostEntry(pos.immutable(), slot));
        }
    }

    /** Adds, replaces or removes several positions at once; see {@link #applyChange}. */
    public void applyChanges(ResourceKey<Level> dim, List<HostEntry> entries) {
        if (!accepts(dim)) {
            return;
        }
        for (HostEntry entry : entries) {
            if (entry.slot() == null) {
                remove(entry.pos());
            } else {
                put(new HostEntry(entry.pos().immutable(), entry.slot()));
            }
        }
    }

    /**
     * Whether updates for {@code dim} belong to the level this index describes. An update that arrives before the
     * snapshot would mean the server forgot to send one, so the dimension is adopted rather than the update dropped.
     */
    private boolean accepts(ResourceKey<Level> dim) {
        if (dimension == null) {
            dimension = dim;
        }
        return dimension.equals(dim);
    }

    /** Drops everything, e.g. when leaving the world. */
    public void clear() {
        bySection.clear();
        dimension = null;
    }

    // ---------------------------------------------------------------- reads --

    /**
     * The hosts inside one section, for the renderer. Returns a copy: the caller may keep it past the end of the
     * frame, because the section mesh is built on a worker thread and the index can change while that runs.
     */
    public List<HostEntry> hostsInSection(ResourceKey<Level> dim, BlockPos sectionOrigin) {
        if (dimension == null || !dimension.equals(dim)) {
            return List.of();
        }
        List<HostEntry> entries = bySection.get(SectionPos.asLong(sectionOrigin));
        return entries == null || entries.isEmpty() ? List.of() : List.copyOf(entries);
    }

    /**
     * Visits every host within {@code sectionRadius} sections of {@code center}. Hands the entries over instead of
     * building a list, because a level can hold any number of hosts and this runs once per frame.
     */
    public void forEachNear(ResourceKey<Level> dim, BlockPos center, int sectionRadius,
                            BiConsumer<BlockPos, Slot> action) {
        if (dimension == null || !dimension.equals(dim)) {
            return;
        }
        int centerX = SectionPos.blockToSectionCoord(center.getX());
        int centerY = SectionPos.blockToSectionCoord(center.getY());
        int centerZ = SectionPos.blockToSectionCoord(center.getZ());
        for (Map.Entry<Long, List<HostEntry>> bucket : bySection.entrySet()) {
            long key = bucket.getKey();
            if (Math.abs(SectionPos.x(key) - centerX) > sectionRadius
                    || Math.abs(SectionPos.y(key) - centerY) > sectionRadius
                    || Math.abs(SectionPos.z(key) - centerZ) > sectionRadius) {
                continue;
            }
            for (HostEntry entry : bucket.getValue()) {
                action.accept(entry.pos(), entry.slot());
            }
        }
    }

    /** What the client believes is inside one block, or {@code null}. */
    @Nullable
    public Slot slotAt(ResourceKey<Level> dim, BlockPos pos) {
        if (dimension == null || !dimension.equals(dim)) {
            return null;
        }
        List<HostEntry> entries = bySection.get(SectionPos.asLong(pos));
        if (entries == null) {
            return null;
        }
        for (HostEntry entry : entries) {
            if (entry.pos().equals(pos)) {
                return entry.slot();
            }
        }
        return null;
    }

    /** Every known host in the current dimension, for diagnostics. */
    public List<HostEntry> all() {
        List<HostEntry> out = new ArrayList<>();
        for (List<HostEntry> entries : bySection.values()) {
            out.addAll(entries);
        }
        return out;
    }

    /** Total number of known hosts, across every section. */
    public int size() {
        int total = 0;
        for (List<HostEntry> entries : bySection.values()) {
            total += entries.size();
        }
        return total;
    }

    /** How many sections currently have at least one host. */
    public int sectionCount() {
        return bySection.size();
    }

    // --------------------------------------------------------------- helpers --

    private void put(HostEntry entry) {
        List<HostEntry> entries =
                bySection.computeIfAbsent(SectionPos.asLong(entry.pos()), key -> new ArrayList<>());
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).pos().equals(entry.pos())) {
                entries.set(i, entry);
                return;
            }
        }
        entries.add(entry);
    }

    private boolean remove(BlockPos pos) {
        long sectionKey = SectionPos.asLong(pos);
        List<HostEntry> entries = bySection.get(sectionKey);
        if (entries == null) {
            return false;
        }
        boolean removed = entries.removeIf(entry -> entry.pos().equals(pos));
        if (!removed) {
            return false;
        }
        if (entries.isEmpty()) {
            // Keep the map free of empty buckets: hosts come and go constantly while building, and an
            // empty bucket would make hostsInSection allocate a copy for nothing.
            bySection.remove(sectionKey);
        }
        return true;
    }
}

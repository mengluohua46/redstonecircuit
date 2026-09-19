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
 * Which blocks hold redstone - and what is inside them - as far as one client knows, indexed by
 * 16x16x16 section.
 *
 * <h2>One dimension at a time</h2>
 * A client has exactly one level loaded and every packet names the dimension it belongs to, so the
 * index holds a single set plus the dimension it describes. Packets for another dimension are ignored
 * rather than merged, which also covers the gap between the player switching dimension and the
 * matching snapshot arriving: until then the index still names the old dimension and the renderer
 * asks for the current one, so nothing stale is drawn.
 *
 * <h2>Why a slot, not just a position</h2>
 * The renderer has to draw the component <em>inside</em> the block - a wire, a repeater facing
 * somewhere, a torch that is lit or not - so a bare position is not enough. The server sends the same
 * {@link Slot} it stores, and because a host block's power changes constantly while a circuit runs,
 * updates for one position are sent as they happen rather than by re-sending the set.
 *
 * <h2>Indexed by section</h2>
 * The renderer asks "what is near me" once per frame, so positions are bucketed by {@link SectionPos}
 * instead of living in one flat set: a level with thousands of hosts costs a walk over occupied
 * sections, not a scan of every block.
 *
 * <h2>Threading</h2>
 * Everything here runs on the client main thread (payload handlers are enqueued there), and the
 * renderer reads it on that same thread, so no section snapshot is needed: {@link #forEachNear} hands
 * entries straight to the caller.
 *
 * <p>Deliberately free of any {@code Minecraft} reference so the bucketing rules can be unit-tested;
 * {@link ClientHostCache} is the thin client-side wrapper.
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
     * Whether updates for {@code dim} belong to the level this index describes.
     *
     * <p>An update that arrives before the snapshot would mean the server forgot to send one; the
     * dimension is adopted rather than dropping the update silently.
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
     * The hosts inside one section, for the renderer.
     *
     * <p>Returns a copy on purpose: the caller may keep it past the end of the frame, because the
     * section mesh is built on a worker thread and the index can change while that runs.
     */
    public List<HostEntry> hostsInSection(ResourceKey<Level> dim, BlockPos sectionOrigin) {
        if (dimension == null || !dimension.equals(dim)) {
            return List.of();
        }
        List<HostEntry> entries = bySection.get(SectionPos.asLong(sectionOrigin));
        return entries == null || entries.isEmpty() ? List.of() : List.copyOf(entries);
    }

    /**
     * Visits every host within {@code sectionRadius} sections of {@code center}.
     *
     * <p>Used by the per-frame renderer, so it hands the entries over instead of building a list: a
     * level can hold any number of hosts and this runs once per frame.
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

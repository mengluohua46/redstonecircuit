package com.jiangyuefengyu.redstonecircuit.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongConsumer;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * Which blocks hold redstone, as far as one client knows, indexed by 16x16x16 section.
 *
 * <h2>One dimension at a time</h2>
 * A client has exactly one level loaded and every packet names the dimension it belongs to, so the
 * index holds a single set plus the dimension it describes. Packets for another dimension are ignored
 * rather than merged, which also covers the gap between the player switching dimension and the
 * matching snapshot arriving: until then the index still names the old dimension and the renderer
 * asks for the current one, so nothing stale is drawn.
 *
 * <h2>Indexed by section</h2>
 * The renderer asks "what is in this section" once per mesh rebuild, so positions are bucketed by
 * {@link SectionPos} instead of living in one flat set: a level with thousands of hosts costs a map
 * lookup per rebuild, not a scan.
 *
 * <h2>Threading</h2>
 * Everything here runs on the client main thread (payload handlers are enqueued there). Section
 * meshes are built on a worker thread, so they never touch this object: the renderer takes a snapshot
 * copy through {@link #hostsInSection(ResourceKey, BlockPos)}.
 *
 * <p>Deliberately free of any {@code Minecraft} reference so the bucketing rules can be unit-tested;
 * {@link ClientHostCache} is the thin client-side wrapper that turns section changes into mesh
 * rebuilds.
 */
public final class HostIndex {

    /** {@link SectionPos#asLong} -> the host positions inside that section. */
    private final Map<Long, List<BlockPos>> bySection = new HashMap<>();

    /** Notified with a section key whenever that section's contents change and it must re-render. */
    private final LongConsumer onSectionChanged;

    /** The dimension the stored positions belong to, or {@code null} before the first snapshot. */
    private ResourceKey<Level> dimension;

    public HostIndex(LongConsumer onSectionChanged) {
        this.onSectionChanged = onSectionChanged;
    }

    // -------------------------------------------------------------- updates --

    /** Replaces the whole set. Sent on login, dimension change and respawn. */
    public void applySnapshot(ResourceKey<Level> dim, List<BlockPos> positions) {
        bySection.clear();
        dimension = dim;
        for (BlockPos pos : positions) {
            add(pos);
        }
        for (long sectionKey : bySection.keySet()) {
            onSectionChanged.accept(sectionKey);
        }
    }

    /** Adds or removes one position. Sent whenever a block gains or loses a component. */
    public void applyChange(ResourceKey<Level> dim, BlockPos pos, boolean present) {
        if (dimension == null) {
            // A change before the snapshot would mean the server forgot to send one; adopt the
            // dimension rather than silently dropping the update.
            dimension = dim;
        }
        if (!dimension.equals(dim)) {
            return;
        }
        if (present) {
            add(pos);
        } else if (!remove(pos)) {
            return;
        }
        onSectionChanged.accept(SectionPos.asLong(pos));
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
    public List<BlockPos> hostsInSection(ResourceKey<Level> dim, BlockPos sectionOrigin) {
        if (dimension == null || !dimension.equals(dim)) {
            return List.of();
        }
        List<BlockPos> positions = bySection.get(SectionPos.asLong(sectionOrigin));
        return positions == null || positions.isEmpty() ? List.of() : List.copyOf(positions);
    }

    /** Total number of known hosts, across every section. */
    public int size() {
        int total = 0;
        for (List<BlockPos> positions : bySection.values()) {
            total += positions.size();
        }
        return total;
    }

    /** How many sections currently have at least one host. */
    public int sectionCount() {
        return bySection.size();
    }

    // --------------------------------------------------------------- helpers --

    private void add(BlockPos pos) {
        List<BlockPos> positions =
                bySection.computeIfAbsent(SectionPos.asLong(pos), key -> new ArrayList<>());
        BlockPos immutable = pos.immutable();
        if (!positions.contains(immutable)) {
            positions.add(immutable);
        }
    }

    private boolean remove(BlockPos pos) {
        long sectionKey = SectionPos.asLong(pos);
        List<BlockPos> positions = bySection.get(sectionKey);
        if (positions == null || !positions.remove(pos)) {
            return false;
        }
        if (positions.isEmpty()) {
            // Keep the map free of empty buckets: hosts come and go constantly while building, and an
            // empty bucket would make hostsInSection allocate a copy for nothing.
            bySection.remove(sectionKey);
        }
        return true;
    }
}

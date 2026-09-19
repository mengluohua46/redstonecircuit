package com.jiangyuefengyu.redstonecircuit.data;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Per-dimension store for "redstone inside a block".
 *
 * <p>Data layout: {@code BlockPos.asLong() -> InnerRedstoneNode}. The store deliberately lives
 * next to the world instead of replacing the host block, which is what allows redstone to sit
 * inside untouched vanilla blocks such as stone.
 *
 * <h2>Performance</h2>
 * Later stages call into this from redstone hot paths, so reads are guarded by two cheap levels
 * before the map is touched:
 * <ol>
 *   <li>{@link #isEmpty()} - a single boolean while no redstone exists anywhere in the level;</li>
 *   <li>{@link #sectionHasNodes(int, int, int)} - a set of occupied 16x16x16 sections.</li>
 * </ol>
 * Only then does {@link #get(BlockPos)} consult the position map.
 */
public final class InnerRedstoneStore extends SavedData {

    /** Bumped whenever the on-disk layout changes, so old saves can be migrated or discarded. */
    public static final int DATA_VERSION = 1;

    private static final String DATA_NAME = "redstonecircuit_inner";

    private final Long2ObjectMap<InnerRedstoneNode> nodes = new Long2ObjectOpenHashMap<>();
    /** Section keys ({@link SectionPos#asLong}) that contain at least one node. */
    private final LongSet occupiedSections = new LongOpenHashSet();

    private InnerRedstoneStore() {
    }

    // --------------------------------------------------------------- access --

    /**
     * Fetches the store for a level, or {@code null} on the client / for non-level getters.
     *
     * <p>Redstone queries hand us a {@link BlockGetter}; only a server {@link Level} owns the
     * authoritative data, so anything else is rejected here.
     */
    @Nullable
    public static InnerRedstoneStore get(@Nullable BlockGetter level) {
        if (level instanceof ServerLevel serverLevel) {
            return get(serverLevel);
        }
        return null;
    }

    public static InnerRedstoneStore get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(InnerRedstoneStore::new, InnerRedstoneStore::load),
                DATA_NAME);
    }

    // ---------------------------------------------------------------- reads --

    /** Fastest possible guard: {@code true} while nothing at all is stored in this level. */
    public boolean isEmpty() {
        return nodes.isEmpty();
    }

    public int size() {
        return nodes.size();
    }

    public boolean sectionHasNodes(int sectionX, int sectionY, int sectionZ) {
        return occupiedSections.contains(SectionPos.asLong(sectionX, sectionY, sectionZ));
    }

    public boolean sectionHasNodes(BlockPos pos) {
        return occupiedSections.contains(SectionPos.asLong(pos));
    }

    @Nullable
    public InnerRedstoneNode get(BlockPos pos) {
        return get(pos.asLong());
    }

    @Nullable
    public InnerRedstoneNode get(long posKey) {
        return nodes.get(posKey);
    }

    /**
     * Signal strength this position should report to the outside world, or {@code 0}.
     *
     * <p>Stage 5 wires this into {@code BlockState#getSignal}. For now it reports the strongest
     * dust installed on any face, which is the value the wrench/goggles stages will visualise.
     */
    public int getSignal(BlockPos pos) {
        if (nodes.isEmpty()) {
            return 0;
        }
        InnerRedstoneNode node = nodes.get(pos.asLong());
        return node == null ? 0 : node.maxPower();
    }

    /** Every stored node; used by the debug command and by client sync in later stages. */
    public List<BlockPos> positions() {
        List<BlockPos> out = new ArrayList<>(nodes.size());
        for (long key : nodes.keySet()) {
            out.add(BlockPos.of(key));
        }
        return out;
    }

    public LongSet positionKeys() {
        return LongSets.unmodifiable(nodes.keySet());
    }

    // --------------------------------------------------------------- writes --

    /** Returns the node at {@code pos}, creating and indexing it when absent. */
    public InnerRedstoneNode getOrCreate(BlockPos pos) {
        long key = pos.asLong();
        InnerRedstoneNode node = nodes.get(key);
        if (node == null) {
            node = new InnerRedstoneNode();
            nodes.put(key, node);
            occupiedSections.add(SectionPos.asLong(pos));
        }
        return node;
    }

    /** Removes everything stored at {@code pos}. Returns the removed node, or {@code null}. */
    @Nullable
    public InnerRedstoneNode remove(BlockPos pos) {
        long key = pos.asLong();
        InnerRedstoneNode removed = nodes.remove(key);
        if (removed != null) {
            pruneSectionIndex(pos);
            setDirty();
        }
        return removed;
    }

    /**
     * Removes a single face; drops the whole node when it becomes empty so that the
     * hot-path section index stays tight.
     */
    @Nullable
    public Slot removeSlot(BlockPos pos, Direction face) {
        InnerRedstoneNode node = nodes.get(pos.asLong());
        if (node == null) {
            return null;
        }
        Slot removed = node.remove(face);
        if (removed == null) {
            return null;
        }
        if (node.isEmpty()) {
            nodes.remove(pos.asLong());
            pruneSectionIndex(pos);
        }
        setDirty();
        return removed;
    }

    /** Marks the store dirty; call after mutating a node obtained from {@link #getOrCreate}. */
    public void markDirty() {
        setDirty();
    }

    private void pruneSectionIndex(BlockPos pos) {
        long sectionKey = SectionPos.asLong(pos);
        for (long key : nodes.keySet()) {
            if (SectionPos.asLong(BlockPos.of(key)) == sectionKey) {
                return;
            }
        }
        occupiedSections.remove(sectionKey);
    }

    // ------------------------------------------------------------------ NBT --

    private static InnerRedstoneStore load(CompoundTag tag, HolderLookup.Provider registries) {
        InnerRedstoneStore store = new InnerRedstoneStore();
        int version = tag.getInt("dataVersion");
        if (version > DATA_VERSION) {
            // Written by a newer build; refuse to guess and start clean rather than corrupting data.
            return store;
        }

        ListTag list = tag.getList("nodes", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            long key = entry.getLong("pos");
            InnerRedstoneNode node = InnerRedstoneNode.load(entry.getCompound("node"));
            if (!node.isEmpty()) {
                store.nodes.put(key, node);
                store.occupiedSections.add(SectionPos.asLong(BlockPos.of(key)));
            }
        }
        return store;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("dataVersion", DATA_VERSION);

        ListTag list = new ListTag();
        for (Long2ObjectMap.Entry<InnerRedstoneNode> entry : nodes.long2ObjectEntrySet()) {
            CompoundTag nodeTag = new CompoundTag();
            nodeTag.putLong("pos", entry.getLongKey());
            nodeTag.put("node", entry.getValue().save());
            list.add(nodeTag);
        }
        tag.put("nodes", list);
        return tag;
    }
}

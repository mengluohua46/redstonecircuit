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
 * <p>Data layout: {@code BlockPos.asLong() -> InnerRedstoneNode}, one node per host block holding at most one
 * component. The store lives next to the world instead of replacing the host block, which is what allows redstone to sit
 * inside untouched vanilla blocks such as stone.
 *
 * <p>Redstone hot paths call into this, so a read passes two cheap guards before the position map is touched: {@link
 * #isEmpty()}, a single boolean while no redstone exists anywhere in the level, and {@link
 * #sectionHasNodes(int, int, int)}, a set of occupied 16x16x16 sections. Only then does {@link #get(BlockPos)} consult
 * the position map.
 */
public final class InnerRedstoneStore extends SavedData {

    /** Bumped whenever the on-disk layout changes, so old saves can be migrated or discarded. */
    public static final int DATA_VERSION = 2;

    private static final String DATA_NAME = "redstonecircuit_inner";

    private final Long2ObjectMap<InnerRedstoneNode> nodes = new Long2ObjectOpenHashMap<>();
    /** Section keys ({@link SectionPos#asLong}) that contain at least one node. */
    private final LongSet occupiedSections = new LongOpenHashSet();

    /**
     * The settings of a device that cannot keep them in its own block state, by position.
     *
     * <p>时钟源 is the one such device: 秒(0-3600) × tick(20) × 强度(15) × 朝向(4) × 亮(2) is about <b>4.3 million</b>
     * {@code BlockState} objects built at startup, so its three numbers live beside the block instead - see
     * {@code ClockRule} and {@code ClockBlock}.
     *
     * <p>They live <b>here</b> rather than in a store of their own because this is already the level's saved home for
     * "data beside a block that is not part of any block": written to the save file, keyed by position in one dimension,
     * and - the part that matters - {@code StructureCapture#capture} already walks every position of a captured box and
     * asks this store what belongs to it, so a clock's period travels with the structure it stands in. A second
     * {@code SavedData} would have meant a second save format, a second registration and a second walk over the same box
     * for no gain.
     */
    private final Long2ObjectMap<com.jiangyuefengyu.redstonecircuit.logic.ClockRule.Setting> clocks =
            new Long2ObjectOpenHashMap<>();

    /**
     * 信号延长器's two numbers, by position - the same kind of data as the clocks above, and here for the same reasons:
     * 秒(0-3600) × 刻(20) × 朝向(4) × 亮(2) would be 576,160 block states if they lived in the state, and a captured
     * structure has to carry them with it.
     *
     * <p>Only the settings: what is <em>left</em> of a hold is a fact about when an input left, which belongs to no
     * block and survives no reload - {@code ExtenderDeadlines} holds that, and holds it outside the save file.
     */
    private final Long2ObjectMap<com.jiangyuefengyu.redstonecircuit.logic.ExtenderRule.Setting> extenders =
            new Long2ObjectOpenHashMap<>();

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
                new SavedData.Factory<>(InnerRedstoneStore::new, InnerRedstoneStore::load, null),
                DATA_NAME);
    }

    // ---------------------------------------------------------------- reads --

    /** Fastest possible guard: {@code true} while nothing at all is stored in this level. */
    public boolean isEmpty() {
        // A clock counts. It holds no node, but it is work the level's tick has to do every tick (its pulse edges),
        // so a level whose only content is one 时钟源 must not be skipped.
        return nodes.isEmpty() && clocks.isEmpty();
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

    /** Shorthand for {@link #get(BlockPos)}, for the solver and the network layer. */
    @Nullable
    public Slot slotAt(BlockPos pos) {
        InnerRedstoneNode node = nodes.get(pos.asLong());
        return node == null ? null : node.slot();
    }

    /**
     * Signal strength this position should report to the outside world, or {@code 0}.
     *
     * <p>Wired into {@code BlockState#getSignal}.
     */
    public int getSignal(BlockPos pos) {
        if (nodes.isEmpty()) {
            return 0;
        }
        InnerRedstoneNode node = nodes.get(pos.asLong());
        return node == null ? 0 : node.power();
    }

    /** Every stored node; used by the debug command and by client sync. */
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

    /** Marks the store dirty; call after mutating a node obtained from {@link #getOrCreate}. */
    public void markDirty() {
        setDirty();
    }

    // ------------------------------------------------------------------- 时钟源 --

    /**
     * The clock settings stored at {@code pos}, or {@code null} when no clock stands there.
     *
     * <p>{@code null} rather than a default setting, because "there is no clock here" and "there is a clock here and it
     * is set to the default" are different facts: the first must not be written into a captured structure, and the second
     * must.
     */
    @Nullable
    public com.jiangyuefengyu.redstonecircuit.logic.ClockRule.Setting clockAt(BlockPos pos) {
        return clocks.get(pos.asLong());
    }

    /** Stores one clock's settings, or forgets them when {@code setting} is {@code null}. */
    public void putClock(BlockPos pos, @Nullable com.jiangyuefengyu.redstonecircuit.logic.ClockRule.Setting setting) {
        if (setting == null) {
            forgetClock(pos);
            return;
        }
        clocks.put(pos.asLong(), setting);
        setDirty();
    }

    /** A clock left this position - broken, pushed away, or replaced by something that is not one. */
    public void forgetClock(BlockPos pos) {
        if (clocks.remove(pos.asLong()) != null) {
            setDirty();
        }
    }

    /** How many clocks this level holds, for the diagnostics. */
    public int clockCount() {
        return clocks.size();
    }

    /**
     * The positions of every clock in this level, as raw keys.
     *
     * <p>What {@code ClockBlock#tickStanding} walks once per level tick: a clock's settings are the one place that knows
     * where the level's clocks are, so the pass that has to ask them all every tick costs one map iteration and no list.
     */
    public LongSet clockKeys() {
        return LongSets.unmodifiable(clocks.keySet());
    }

    // ---------------------------------------------------------------- 信号延长器 --

    /**
     * 信号延长器's settings at {@code pos}, or {@code null} when none stands there.
     *
     * <p>{@code null} for the same reason the clocks answer {@code null}: "there is no extender here" and "there is one
     * here set to the default" are different facts, and only the second should travel with a captured structure.
     */
    @Nullable
    public com.jiangyuefengyu.redstonecircuit.logic.ExtenderRule.Setting extenderAt(BlockPos pos) {
        return extenders.get(pos.asLong());
    }

    /** Stores one extender's settings, or forgets them when {@code setting} is {@code null}. */
    public void putExtender(BlockPos pos,
                            @Nullable com.jiangyuefengyu.redstonecircuit.logic.ExtenderRule.Setting setting) {
        if (setting == null) {
            forgetExtender(pos);
            return;
        }
        extenders.put(pos.asLong(), setting);
        setDirty();
    }

    /** An extender left this position - broken, pushed away, or replaced by something that is not one. */
    public void forgetExtender(BlockPos pos) {
        if (extenders.remove(pos.asLong()) != null) {
            setDirty();
        }
    }

    /**
     * The positions of every extender in this level, as raw keys.
     *
     * <p>What {@code ExtenderBlock#tickStanding} walks once per level tick: this table is the one place that knows where
     * the level's extenders are, and each of them has to be asked every tick because a hold runs out on the clock rather
     * than on a notification.
     */
    public LongSet extenderKeys() {
        return LongSets.unmodifiable(extenders.keySet());
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
        if (version < DATA_VERSION) {
            // Layout changed between versions 1 and 2 (per-face components -> one component per
            // block). There is no meaningful automatic migration, and the mod is unreleased, so the
            // old data is simply dropped.
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

        // And the clocks. A key that is not there at all is a save written before this device existed, which is exactly
        // the empty list - so no data version bump is needed for the addition.
        ListTag clockList = tag.getList("clocks", Tag.TAG_COMPOUND);
        for (int i = 0; i < clockList.size(); i++) {
            CompoundTag entry = clockList.getCompound(i);
            store.clocks.put(entry.getLong("pos"),
                    com.jiangyuefengyu.redstonecircuit.logic.ClockRule.Setting.load(entry.getCompound("setting")));
        }
        // And the extenders, in their own list for the same reason - and with no version bump either.
        ListTag extenderList = tag.getList("extenders", Tag.TAG_COMPOUND);
        for (int i = 0; i < extenderList.size(); i++) {
            CompoundTag entry = extenderList.getCompound(i);
            store.extenders.put(entry.getLong("pos"),
                    com.jiangyuefengyu.redstonecircuit.logic.ExtenderRule.Setting
                            .load(entry.getCompound("setting")));
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

        // The clocks, as their own list so a save written before this device existed reads back unchanged.
        ListTag clockList = new ListTag();
        for (Long2ObjectMap.Entry<com.jiangyuefengyu.redstonecircuit.logic.ClockRule.Setting> entry
                : clocks.long2ObjectEntrySet()) {
            CompoundTag clockTag = new CompoundTag();
            clockTag.putLong("pos", entry.getLongKey());
            CompoundTag setting = new CompoundTag();
            entry.getValue().save(setting);
            clockTag.put("setting", setting);
            clockList.add(clockTag);
        }
        tag.put("clocks", clockList);

        // And the extenders' own list, written the same way.
        ListTag extenderList = new ListTag();
        for (Long2ObjectMap.Entry<com.jiangyuefengyu.redstonecircuit.logic.ExtenderRule.Setting> entry
                : extenders.long2ObjectEntrySet()) {
            CompoundTag extenderTag = new CompoundTag();
            extenderTag.putLong("pos", entry.getLongKey());
            CompoundTag setting = new CompoundTag();
            entry.getValue().save(setting);
            extenderTag.put("setting", setting);
            extenderList.add(extenderTag);
        }
        tag.put("extenders", extenderList);
        return tag;
    }
}

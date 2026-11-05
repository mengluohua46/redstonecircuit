package com.jiangyuefengyu.redstonecircuit.data;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

import it.unimi.dsi.fastutil.longs.Long2ByteMap;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
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
     * {@code BlockState} objects built at startup, so its four numbers live beside the block instead - see
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

    /**
     * 同步火把的频道, by position - the one form of this device that has no slot to keep it on: a 同步火把 standing in
     * the world is a block, and its channel has to live beside it for exactly the reason a 时钟源's period does.
     *
     * <p>0 means "no channel here", which is what a position that holds no sync torch answers; the inner form keeps its
     * channel on {@link Slot#syncGroup} instead, because that one has to follow a piston.
     */
    private final Long2IntMap syncTorches = new Long2IntOpenHashMap();

    /**
     * 站着的同步火把在这一 tick 有哪几面输入, 一位一面 - 与 {@link Slot#syncInputs} 同义, 只是站着的这一形态没有槽位
     * 可挂。同样不进存档: 它是"此刻"的事实, 每趟 pass 重写一遍。
     */
    private final Long2ByteMap syncTorchInputs = new Long2ByteOpenHashMap();

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
        // A clock counts. It holds no node, but it is work the level's tick has to do every tick (its pulse edges),
        // so a level whose only content is one 时钟源 must not be skipped. A 同步火把 standing in the world counts for
        // the same reason: the group pass has to visit it every tick, and the pass is behind this guard.
        return nodes.isEmpty() && clocks.isEmpty() && syncTorches.isEmpty();
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

    /**
     * Everything stored at one position: the component, and the settings of a device that cannot keep them in its own
     * block state. The three are one value because a device that moves must move all three together - a 时钟源 that
     * arrived without its two numbers would come back at the default setting.
     *
     * <p>The node and the slot are <b>copies</b>: what {@link #peek} hands out is a snapshot, not the live object, so a
     * caller that carries it to another position cannot be writing through the store's own entry.
     */
    public record Stored(InnerRedstoneNode node,
                         com.jiangyuefengyu.redstonecircuit.logic.ClockRule.Setting clock,
                         com.jiangyuefengyu.redstonecircuit.logic.ExtenderRule.Setting extender) {

        /** Nothing at all is stored here. */
        public boolean isEmpty() {
            return node == null && clock == null && extender == null;
        }

        /** The component itself, or {@code null} when this position holds only a device's settings. */
        public Slot slot() {
            return node == null ? null : node.slot();
        }
    }

    /** A copy of everything stored at {@code pos}; never the store's own objects. */
    public Stored peek(BlockPos pos) {
        long key = pos.asLong();
        InnerRedstoneNode live = nodes.get(key);
        InnerRedstoneNode copy = null;
        if (live != null) {
            copy = new InnerRedstoneNode();
            Slot slot = live.slot();
            if (slot != null) {
                copy.setSlot(slot.copy());
            }
        }
        return new Stored(copy, clocks.get(key), extenders.get(key));
    }

    /** Writes a snapshot back at {@code pos}. The caller owns {@code pos} - nothing here removes what was there. */
    public void put(BlockPos pos, Stored stored) {
        if (stored == null || stored.isEmpty()) {
            return;
        }
        long key = pos.asLong();
        if (stored.node() != null) {
            nodes.put(key, stored.node());
            occupiedSections.add(SectionPos.asLong(pos));
        }
        if (stored.clock() != null) {
            clocks.put(key, stored.clock());
        }
        if (stored.extender() != null) {
            extenders.put(key, stored.extender());
        }
        setDirty();
    }

    /** Removes everything stored at {@code pos}: the component and both devices' settings. */
    public void forgetAll(BlockPos pos) {
        remove(pos);
        forgetClock(pos);
        forgetExtender(pos);
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

    // ---------------------------------------------------------------- 同步火把 --

    /**
     * 同步火把's channel at {@code pos}, or {@code 0} when it belongs to no channel - which is also what a position that
     * holds no torch of that kind answers.
     *
     * <p>{@code 0} rather than {@link com.jiangyuefengyu.redstonecircuit.logic.SyncTorchRule#UNBOUND}: a channel number
     * and "this cell is on no channel" are the only two things the <b>channel</b> can be, and the row's own presence is
     * what says whether a torch stands here ({@link #hasSyncTorch}). Keeping the row for an unbound torch is what lets
     * the group pass find it at all - a torch with no row would be invisible to the level's tick.
     */
    public int syncTorchChannelAt(BlockPos pos) {
        return syncTorches.get(pos.asLong());
    }

    /** 这一格的火把此刻哪几面有输入; 没写过就是 0 (一面都没有). */
    public byte syncTorchInputsAt(BlockPos pos) {
        return syncTorchInputs.get(pos.asLong());
    }

    /** 记下这一格的火把此刻哪几面有输入 - 由组 pass 每趟写一次。 */
    public void putSyncTorchInputs(BlockPos pos, byte inputs) {
        syncTorchInputs.put(pos.asLong(), inputs);
    }

    /** Whether a standing 同步火把 is recorded here, bound to a channel or not. */
    public boolean hasSyncTorch(BlockPos pos) {
        return syncTorches.containsKey(pos.asLong());
    }

    /**
     * Records one standing 同步火把: {@code channel} 1 upward to bind it, {@code 0} to keep it here and unbound.
     *
     * <p>A negative number is read as 0, because "unbound" is the only thing that is not a channel; forgetting the row is
     * what {@link #removeSyncTorch} is for, and the two must not be the same call - a bound torch that is pushed away
     * has to leave its row behind at the old cell and take it to the new one.
     */
    public void putSyncTorch(BlockPos pos, int channel) {
        syncTorches.put(pos.asLong(), Math.max(0, channel));
        setDirty();
    }

    /** A 同步火把 left this position - broken, replaced, or carried elsewhere. */
    public void removeSyncTorch(BlockPos pos) {
        syncTorchInputs.remove(pos.asLong());
        if (syncTorches.containsKey(pos.asLong())) {
            syncTorches.remove(pos.asLong());
            setDirty();
        }
    }

    /**
     * Every position this level holds a standing 同步火把 at.
     *
     * <p>What {@code SyncGroups#tick} walks once per level tick: this table is the only place that knows where the
     * level's sync torches are, so the group pass costs one iteration here and no scan of the world.
     */
    public Set<BlockPos> syncTorchPositions() {
        Set<BlockPos> out = new LinkedHashSet<>(syncTorches.size());
        for (long key : syncTorches.keySet()) {
            out.add(BlockPos.of(key));
        }
        return out;
    }

    /** How many standing sync torches this level holds, for the diagnostics. */
    public int syncTorchCount() {
        return syncTorches.size();
    }

    /**
     * 发一个新频道号: <b>此刻没人在用的最小号码</b>.
     *
     * <p>不用自增计数器。号码是玩家看得见的身份 - 颜色由它派生, 提示里直接报"已加入频道 #7" - 而自增会把它越堆越大:
     * 同一个存档里前后绑过二十次组, 玩家会被告知"频道 #21", 哪怕此刻全世界只有一组。号码唯一的意义是"此刻谁和谁一组",
     * 一个没有成员的号码不代表任何东西, 所以空出来的号可以安全复用。
     *
     * <p>扫描两张表 (站着的火把 + 内嵌元件的槽位) 而不是去问世界: 没加载的区块里那一支也还带着自己的号, 它们同样算占用。
     * 发号只在玩家点一次右键时发生一次, 一次线性扫描在这里不构成代价。
     */
    public int allocateSyncChannel() {
        int channel = 1;
        while (isSyncChannelInUse(channel)) {
            channel++;
        }
        setDirty();
        return channel;
    }

    /** 这个维度里有没有一支同步火把 (站着的或塞在方块里的) 已经答应 {@code channel}. */
    private boolean isSyncChannelInUse(int channel) {
        for (int bound : syncTorches.values()) {
            if (bound == channel) {
                return true;
            }
        }
        for (long key : nodes.keySet()) {
            Slot slot = slotAt(BlockPos.of(key));
            if (slot != null && slot.type == ComponentType.SYNC_TORCH && slot.syncGroup == channel) {
                return true;
            }
        }
        return false;
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
        // And the sync torches' channels, in their own list and with no version bump either: a key that is not there
        // is a save written before this device existed, which is exactly the empty table.
        ListTag syncList = tag.getList("syncTorches", Tag.TAG_COMPOUND);
        for (int i = 0; i < syncList.size(); i++) {
            CompoundTag entry = syncList.getCompound(i);
            // 频道 0 也要读回来: 那一行说的是"这里有一支同步火把, 它还没绑频道", 而组 pass 靠这一行才能在每 tick
            // 找到它 (未绑定的火把自成一个组, 不是不存在)。
            store.syncTorches.put(entry.getLong("pos"), Math.max(0, entry.getInt("channel")));
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

        // 同步火把's channels, and the counter that hands out the next one.
        ListTag syncList = new ListTag();
        for (Long2IntMap.Entry entry : syncTorches.long2IntEntrySet()) {
            CompoundTag syncTag = new CompoundTag();
            syncTag.putLong("pos", entry.getLongKey());
            syncTag.putInt("channel", entry.getIntValue());
            syncList.add(syncTag);
        }
        tag.put("syncTorches", syncList);
        return tag;
    }
}

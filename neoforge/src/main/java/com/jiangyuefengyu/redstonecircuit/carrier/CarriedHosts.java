package com.jiangyuefengyu.redstonecircuit.carrier;

import com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneNode;
import com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneStore;
import com.jiangyuefengyu.redstonecircuit.data.Slot;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.saveddata.SavedData;

import org.jetbrains.annotations.Nullable;

/**
 * The per-dimension saved home of the carried index, and the one place the two stores are joined.
 *
 * <h2>Two stores, on purpose</h2>
 * {@link InnerRedstoneStore} keeps its world coordinates. That is not an implementation detail to be
 * tidied away: "redstone can live inside any block" is expressed entirely by the component being
 * addressed by the position of the block that hides it, and rewriting those keys into
 * {@code carrier + local} would break every query in the mod the moment nothing is carrying anything.
 * So the carried data lives beside it, in this file, and only while something is actually being
 * carried:
 *
 * <pre>
 *   redstonecircuit_inner     world positions  - always authoritative, unchanged by P1
 *   redstonecircuit_carried   carrier + local  - exists only while a structure is in flight
 * </pre>
 *
 * <h2>Why a separate file rather than a field in the existing one</h2>
 * Three reasons, in order of how much they matter. The on-disk layout of the world store is left
 * exactly as it was, so an old save loads unchanged and a save taken while something is in flight
 * still has a readable world store. Code that knows nothing about moving structures never touches it.
 * And the index can be a plain object with no Minecraft types in it, which is what makes its contract
 * unit-testable - see {@link CarriedHostIndex}.
 *
 * <h2>The bridge</h2>
 * {@link #slots(ServerLevel)} is the adapter that turns "read and write a component at a world
 * position" into the world store's own accessors, so the index never learns what a
 * {@code ServerLevel} is. Reads there go through the node the store already has; writes create one
 * when needed and remove it when the value is {@code null}, because a node with no component in it is
 * an empty entry that would make {@code /rc list} and the snapshot sync lie.
 */
public final class CarriedHosts extends SavedData {

    /** Bumped whenever the carried file's layout changes. */
    public static final int DATA_VERSION = 1;

    private static final String DATA_NAME = "redstonecircuit_carried";

    private final CarriedHostIndex index;

    private CarriedHosts(CarriedHostIndex index) {
        this.index = index;
    }

    /** Fetches the carried index for a level, or {@code null} on the client / for other getters. */
    @Nullable
    public static CarriedHosts get(@Nullable BlockGetter level) {
        if (level instanceof ServerLevel serverLevel) {
            return get(serverLevel);
        }
        return null;
    }

    public static CarriedHosts get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(() -> new CarriedHosts(new CarriedHostIndex()),
                        CarriedHosts::load),
                DATA_NAME);
    }

    /**
     * The carried index of a level, or {@code null} when there is none to be had.
     *
     * <p>The convenience the compat half and the tick handler actually call: {@code null} on the
     * client, and never {@code null} on the server.
     */
    @Nullable
    public static CarriedHostIndex indexOf(@Nullable BlockGetter level) {
        CarriedHosts holder = get(level);
        return holder == null ? null : holder.index();
    }

    /** The index itself. Mutating it does not mark the file dirty; call {@link #markDirty()}. */
    public CarriedHostIndex index() {
        return index;
    }

    /** The store accessors the index works through; see the class doc. */
    public static CarriedHostIndex.SlotWorld slots(ServerLevel level) {
        return new CarriedHostIndex.SlotWorld() {

            @Override
            @Nullable
            public Slot get(BlockPos pos) {
                InnerRedstoneStore store = InnerRedstoneStore.get(level);
                InnerRedstoneNode node = store.get(pos);
                return node == null || node.isEmpty() ? null : node.slot();
            }

            @Override
            public void set(BlockPos pos, @Nullable Slot slot) {
                InnerRedstoneStore store = InnerRedstoneStore.get(level);
                if (slot == null) {
                    store.remove(pos);
                } else {
                    store.getOrCreate(pos).setSlot(slot);
                    store.markDirty();
                }
            }
        };
    }

    /**
     * Removes a world entry, whatever it is.
     *
     * <p>Used when the index has just taken a component out of the world: the entry is normally gone
     * already, but a caller that reached the store by another route must not leave a stale node behind
     * for {@code /rc list} to report.
     */
    public static void forgetWorldEntry(ServerLevel level, BlockPos pos) {
        InnerRedstoneStore.get(level).remove(pos);
    }

    // ------------------------------------------------------------------ NBT --

    private static CarriedHosts load(CompoundTag tag, HolderLookup.Provider registries) {
        int version = tag.getInt("dataVersion");
        if (version > DATA_VERSION) {
            // Written by a newer build: refuse to guess. Nothing is carried any more, which is a
            // recoverable state - the world store still holds every component that came home.
            return new CarriedHosts(new CarriedHostIndex());
        }
        return new CarriedHosts(CarriedHostIndex.load(tag));
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        index.saveInto(tag);
        tag.putInt("dataVersion", DATA_VERSION);
        return tag;
    }
}

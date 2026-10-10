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
 * <p>{@link InnerRedstoneStore} keeps its world coordinates, and that is not an implementation detail to tidy
 * away: "redstone can live inside any block" is expressed entirely by the component being addressed by the
 * position of the block that hides it, so the carried data lives beside it, in its own saved file
 * ({@code redstonecircuit_carried}), only while something is actually being carried.
 *
 * <p>{@link #slots(ServerLevel)} turns "read and write a component at a world position" into the world store's
 * own accessors; a write with a {@code null} value removes the node, because an empty entry would make
 * {@code /rc list} and the snapshot sync lie.
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
     * The carried index of a level, or {@code null} when there is none to be had: {@code null} on the client,
     * never {@code null} on the server.
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

    /** The world-store accessors the carried index works through. */
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
     * Removes a world entry, whatever it is. Used when the index has just taken a component out of the world: a
     * caller that reached the store by another route must not leave a stale node behind for {@code /rc list}.
     */
    public static void forgetWorldEntry(ServerLevel level, BlockPos pos) {
        InnerRedstoneStore.get(level).remove(pos);
    }

    // ------------------------------------------------------------------ NBT --

    private static CarriedHosts load(CompoundTag tag, HolderLookup.Provider registries) {
        int version = tag.getInt("dataVersion");
        if (version > DATA_VERSION) {
            // Written by a newer build: refuse to guess, leaving nothing carried, which is recoverable.
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

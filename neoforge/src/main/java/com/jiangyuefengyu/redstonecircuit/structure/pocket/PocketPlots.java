package com.jiangyuefengyu.redstonecircuit.structure.pocket;

import java.util.LinkedHashMap;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * The pocket's plot table, saved with the world.
 *
 * <p>A placed product's blocks really are in the pocket dimension, so after a restart they are still there.
 * What is <em>not</em> there is everything held in memory: which cell belongs to which product, and the
 * chunk tickets that made it tick. Without a saved table the blocks would be an orphaned copy that nothing
 * ticks and nothing can reclaim.
 *
 * <p>So the table records, per plot, who owns it and what reclaiming it needs; the runtime half is rebuilt
 * from it the first time the pocket is used in a session. An entry that cannot be parsed is skipped.
 */
public class PocketPlots extends SavedData {

    /** Bumped when the layout changes in a way an older reader cannot handle. */
    public static final int DATA_VERSION = 1;

    private static final String DATA_NAME = "redstonecircuit_pocket_plots";

    /**
     * One claimed plot: the product block that owns it, and how to take it back.
     *
     * <p>{@code travellingSince} is 0 while the block owns its plot from where it stands, and is set when the
     * block <b>leaves the world inside a moving structure</b>; the first tick of the block that lands clears
     * it. Until then the plot's owner cannot be proven gone, and the reclaim has to wait. It is a saved field
     * rather than an in-memory set because a restart while a machine is in the air must not reclaim a plot
     * whose blocks are still aboard.
     */
    public record Entry(ResourceKey<Level> ownerDimension, BlockPos ownerPos, Vec3i size, boolean shell,
                        int columns, long travellingSince) {

        /** The common case, for callers that are not moving anything. */
        public Entry(ResourceKey<Level> ownerDimension, BlockPos ownerPos, Vec3i size, boolean shell, int columns) {
            this(ownerDimension, ownerPos, size, shell, columns, 0L);
        }

        /** The same entry, standing somewhere else - what a landed product's plot record becomes. */
        public Entry standingAt(ResourceKey<Level> dimension, BlockPos pos) {
            return new Entry(dimension, pos, this.size, this.shell, this.columns, 0L);
        }

        /** The same entry, out of the world inside a carrier since {@code gameTime}. */
        public Entry travelling(long gameTime) {
            return new Entry(this.ownerDimension, this.ownerPos, this.size, this.shell, this.columns, gameTime);
        }

        /**
         * Whether this record is away from home <b>and</b> still inside the lease: a machine destroyed
         * mid-flight can never clear the stamp, so after {@code TRAVEL_LEASE_TICKS} the plot is reclaimed like
         * any other orphan.
         */
        public boolean travelling(long now, long leaseTicks) {
            return this.travellingSince != 0 && now - this.travellingSince <= leaseTicks;
        }
    }

    private final Map<Integer, Entry> entries = new LinkedHashMap<>();

    public static PocketPlots get(ServerLevel pocket) {
        return pocket.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(PocketPlots::new, PocketPlots::load), DATA_NAME);
    }

    /** The claimed plots, by cell index. */
    public Map<Integer, Entry> entries() {
        return Map.copyOf(this.entries);
    }

    @Nullable
    public Entry entry(int plot) {
        return this.entries.get(plot);
    }

    public int size() {
        return this.entries.size();
    }

    public void put(int plot, Entry entry) {
        this.entries.put(plot, entry);
        this.setDirty();
    }

    /** Forgets a plot, returning what it was so a caller can keep reclaiming it. */
    @Nullable
    public Entry remove(int plot) {
        Entry removed = this.entries.remove(plot);
        if (removed != null) {
            this.setDirty();
        }
        return removed;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("data_version", DATA_VERSION);
        ListTag list = new ListTag();
        this.entries.forEach((plot, entry) -> {
            CompoundTag one = new CompoundTag();
            one.putInt("plot", plot);
            one.putString("owner_dimension", entry.ownerDimension().location().toString());
            one.putLong("owner_pos", entry.ownerPos().asLong());
            one.putInt("size_x", entry.size().getX());
            one.putInt("size_y", entry.size().getY());
            one.putInt("size_z", entry.size().getZ());
            one.putBoolean("shell", entry.shell());
            one.putInt("columns", entry.columns());
            // Absent in an older save reads back as 0 = "standing at home", which is what those saves mean, so
            // no version bump is needed.
            one.putLong("travelling_since", entry.travellingSince());
            list.add(one);
        });
        tag.put("plots", list);
        return tag;
    }

    public static PocketPlots load(CompoundTag tag, HolderLookup.Provider registries) {
        PocketPlots plots = new PocketPlots();
        if (tag.getInt("data_version") != DATA_VERSION) {
            // A format from the future (or a file that was never ours): start empty rather than guess, and
            // let the sweep in PocketSpace hand the cells back as it finds owners that no longer exist.
            return plots;
        }
        ListTag list = tag.getList("plots", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag one = list.getCompound(i);
            ResourceLocation dimension = ResourceLocation.tryParse(one.getString("owner_dimension"));
            if (dimension == null) {
                continue;
            }
            plots.entries.put(one.getInt("plot"), new Entry(
                    ResourceKey.create(Registries.DIMENSION, dimension),
                    BlockPos.of(one.getLong("owner_pos")),
                    new Vec3i(one.getInt("size_x"), one.getInt("size_y"), one.getInt("size_z")),
                    one.getBoolean("shell"),
                    one.getInt("columns"),
                    one.getLong("travelling_since")));
        }
        return plots;
    }
}

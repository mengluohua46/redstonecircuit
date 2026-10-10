package com.jiangyuefengyu.redstonecircuit.structure;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneNode;
import com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneStore;
import com.jiangyuefengyu.redstonecircuit.network.HostSync;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Puts a captured structure back into the world.
 *
 * <p>Blocks are written over ticks ({@code structureReleaseBlocksPerTick}) so a large release does not freeze
 * the server. With the conflict guard on, an overwriting release is refused once with the number of blocks it
 * would destroy and goes ahead only when asked a second time. Blocks, their block entity data and this mod's
 * slot data come back; the context shell is dropped.
 */
public final class StructureRelease {

    /** How a release is paced and whether it asks before overwriting. */
    public record Options(int blocksPerTick, boolean conflictGuard) {
    }

    /** Either a refusal, or the job that is now running. */
    public record Result(@Nullable Job job, String refusal) {

        public boolean started() {
            return this.job != null;
        }
    }

    /** One release in progress. */
    public static final class Job {

        /** How often a running release tells the players around it how far it has got, in ticks. */
        private static final int PROGRESS_TICKS = 10;

        /** How far away a player is told, in blocks - the action bar is for people watching the thing happen. */
        private static final double PROGRESS_RANGE = 48.0;

        private final UUID structureId;
        private final String name;
        private final ServerLevel level;
        private final BlockPos origin;
        private final Vec3i size;
        private final int total;
        private final int[] cells;
        private final List<BlockState> states;
        private final Map<Integer, StructurePayload.BlockEntityEntry> blockEntities;
        private final Map<Integer, StructurePayload.SlotEntry> slots;
        /** A clock's settings, which live beside the block rather than in it. */
        private final Map<Integer, StructurePayload.SettingEntry> settings;
        private final Options options;
        private int cursor;
        private long lastReport;

        private Job(StructurePayload payload, ServerLevel level, BlockPos origin, List<BlockState> states,
                    Options options) {
            this.structureId = payload.header().uuid();
            this.name = payload.header().name();
            this.level = level;
            this.origin = origin;
            this.size = payload.header().size();
            this.total = payload.header().size().getX() * payload.header().size().getY()
                    * payload.header().size().getZ();
            this.cells = payload.indices().toIntArray();
            this.states = states;
            this.options = options;
            this.blockEntities = byLocalIndex(payload.blockEntities(),
                    StructurePayload.BlockEntityEntry::localPos, payload.header().size());
            this.slots = byLocalIndex(payload.slotData(), StructurePayload.SlotEntry::localPos,
                    payload.header().size());
            this.settings = byLocalIndex(payload.settingData(), StructurePayload.SettingEntry::localPos,
                    payload.header().size());
        }

        /** Writes this release down as unfinished, so a crash in the middle of it can be repaired. */
        private void journal() {
            if (this.level.getServer() != null) {
                ReleaseJournal.write(this.level.getServer(),
                        new ReleaseJournal.Entry(this.structureId, this.name, this.level.dimension(), this.origin));
            }
        }

        /** Forgets the record: this release is over. */
        private void unjournal() {
            if (this.level.getServer() != null) {
                ReleaseJournal.remove(this.level.getServer(), this.structureId);
            }
        }

        /**
         * Tells the players standing around how far this release has got: the running line is an action bar
         * message, the final one stays in chat.
         */
        private void reportProgress(boolean done) {
            long now = this.level.getGameTime();
            if (!done && now - this.lastReport < PROGRESS_TICKS) {
                return;
            }
            this.lastReport = now;
            net.minecraft.network.chat.Component line = net.minecraft.network.chat.Component.translatable(
                    done ? "redstonecircuit.structure.release.done" : "redstonecircuit.structure.release.progress",
                    this.name, this.placed(), this.total);
            for (net.minecraft.server.level.ServerPlayer player : this.level.players()) {
                if (player.blockPosition().closerThan(this.origin, PROGRESS_RANGE)) {
                    player.displayClientMessage(line, !done);
                }
            }
        }

        /**
         * The pacing this job was started with; kept on the job so a config edit cannot change a release in
         * flight.
         */
        public Options options() {
            return this.options;
        }

        public String name() {
            return this.name;
        }

        public UUID structureId() {
            return this.structureId;
        }

        public BlockPos origin() {
            return this.origin;
        }

        public Vec3i size() {
            return this.size;
        }

        public int total() {
            return this.total;
        }

        public int placed() {
            return this.cursor;
        }

        public boolean done() {
            return this.cursor >= this.total;
        }

        public ServerLevel level() {
            return this.level;
        }
    }

    /** The releases in flight, in the order they were started. */
    private static final List<Job> JOBS = new ArrayList<>();

    private StructureRelease() {
    }

    /**
     * Counts the blocks in the target region that a release would destroy.
     *
     * <p>Air is not "in the way", everything else is - including blocks a player would call harmless
     * (grass, snow). The number is what a player is shown before they confirm, so it errs towards warning.
     */
    public static int occupied(ServerLevel level, BlockPos origin, Vec3i size) {
        int occupied = 0;
        for (int y = 0; y < size.getY(); y++) {
            for (int z = 0; z < size.getZ(); z++) {
                for (int x = 0; x < size.getX(); x++) {
                    if (!level.getBlockState(origin.offset(x, y, z)).isAir()) {
                        occupied++;
                    }
                }
            }
        }
        return occupied;
    }

    /**
     * Starts a release of {@code payload} with its minimum corner at {@code origin}.
     *
     * @param confirmed true when the player has already been told what will be overwritten and said yes
     */
    public static Result start(ServerLevel level, StructurePayload payload, BlockPos origin,
                               boolean confirmed, Options options) {
        var blocks = level.holderLookup(Registries.BLOCK);
        List<BlockState> states = new ArrayList<>(payload.palette().size());
        for (int index = 0; index < payload.palette().size(); index++) {
            // Resolved once, so an unresolvable palette entry cannot surface halfway through writing.
            states.add(payload.resolvePalette(blocks, index));
        }

        if (options.conflictGuard() && !confirmed) {
            int occupied = occupied(level, origin, payload.header().size());
            if (occupied > 0) {
                return new Result(null, "the target already holds " + occupied + " block(s); releasing "
                        + "would overwrite them (confirm with 'confirm', or turn off "
                        + "structureReleaseConflictGuard)");
            }
        }

        Job job = new Job(payload, level, origin, states, options);
        JOBS.add(job);
        // A release that fits in one tick cannot be interrupted, so only a bigger one is journalled.
        int perTick = Math.max(1, options.blocksPerTick());
        if (job.total() > perTick) {
            job.journal();
        }
        return new Result(job, "");
    }

    /** The releases in flight, oldest first. */
    public static List<Job> jobs() {
        return List.copyOf(JOBS);
    }

    /** Forgets every job; called when a server stops. */
    public static void forgetAll() {
        JOBS.clear();
    }

    /**
     * Writes each running job's budget; called once per level tick, and only for the jobs of that level, so
     * another dimension's tick cannot advance a release twice.
     */
    public static void tick(ServerLevel level) {
        if (JOBS.isEmpty()) {
            return;
        }
        List<Job> finished = new ArrayList<>();
        for (Job job : JOBS) {
            if (job.level() != level) {
                continue;
            }
            int budget = Math.max(1, job.options().blocksPerTick());
            while (budget-- > 0 && !job.done()) {
                writeCell(job, job.cursor);
                job.cursor++;
            }
            if (job.done()) {
                finished.add(job);
            } else {
                job.reportProgress(false);
            }
        }
        for (Job job : finished) {
            // The record goes first: a journal that outlives its release would resume a finished structure.
            job.unjournal();
            job.reportProgress(true);
            JOBS.remove(job);
        }
    }

    private static void writeCell(Job job, int index) {
        Vec3i size = job.size();
        int perLayer = size.getX() * size.getZ();
        int y = index / perLayer;
        int z = (index / size.getX()) % size.getZ();
        int x = index % size.getX();
        BlockPos pos = job.origin().offset(x, y, z);

        job.level().setBlock(pos, job.states.get(job.cells[index]), 3);

        StructurePayload.BlockEntityEntry blockEntity = job.blockEntities.get(index);
        if (blockEntity != null) {
            // The block is in place, so the level has made its block entity and the saved data can go into it.
            BlockEntity created = job.level().getBlockEntity(pos);
            if (created != null) {
                created.loadWithComponents(blockEntity.nbt(), job.level().registryAccess());
                created.setChanged();
            }
        }

        StructurePayload.SlotEntry slot = job.slots.get(index);
        if (slot != null) {
            // A component inside a block lives in the level's store, not in the block, so it is written
            // next to the block rather than as part of it.
            InnerRedstoneNode node = InnerRedstoneNode.load(slot.node());
            InnerRedstoneStore store = InnerRedstoneStore.get(job.level());
            store.getOrCreate(pos).setSlot(node.slot());
            store.markDirty();
            // The mirror of the rule above: a component put back has to be named on the wire too, or the server
            // has it and the client does not, leaving it undrawn until something else touches that position.
            HostSync.broadcast(job.level(), pos, node.slot());
        }

        // A clock's or an extender's setting is written beside the block for the same reason: no block state has room
        // for 0-3600 秒. Which of the two it is, the block that was just placed says. Nothing is broadcast - neither is
        // drawn, and each block's own LIT state is set when it next announces itself.
        StructurePayload.SettingEntry setting = job.settings.get(index);
        if (setting != null) {
            Block block = job.level().getBlockState(pos).getBlock();
            if (block instanceof com.jiangyuefengyu.redstonecircuit.block.ClockBlock) {
                InnerRedstoneStore.get(job.level()).putClock(pos,
                        com.jiangyuefengyu.redstonecircuit.logic.ClockRule.Setting.load(setting.setting()));
            } else if (block instanceof com.jiangyuefengyu.redstonecircuit.block.ExtenderBlock) {
                InnerRedstoneStore.get(job.level()).putExtender(pos,
                        com.jiangyuefengyu.redstonecircuit.logic.ExtenderRule.Setting.load(setting.setting()));
            }
        }
    }

    private static <T> Map<Integer, T> byLocalIndex(List<T> entries,
                                                    java.util.function.Function<T, BlockPos> position,
                                                    Vec3i size) {
        Map<Integer, T> map = new HashMap<>();
        for (T entry : entries) {
            BlockPos pos = position.apply(entry);
            map.put(pos.getX() + size.getX() * (pos.getZ() + size.getZ() * pos.getY()), entry);
        }
        return map;
    }

    /** The payload of a structure, for a caller that has an id and a store. */
    @Nullable
    public static StructurePayload payloadOf(StructureWorldStore store, @Nullable UUID id) {
        return id == null ? null : store.loadOrNull(id);
    }

    /** Sorts entries by their local index - for callers that want to see what a release will place. */
    public static <T> List<T> inPlacementOrder(List<T> entries,
                                               java.util.function.Function<T, BlockPos> position,
                                               Vec3i size) {
        List<T> copy = new ArrayList<>(entries);
        copy.sort(Comparator.comparingInt(entry -> {
            BlockPos pos = position.apply(entry);
            return pos.getX() + size.getX() * (pos.getZ() + size.getZ() * pos.getY());
        }));
        return copy;
    }

    /** A payload's block state at a local coordinate, or {@code null} when it is outside. */
    @Nullable
    public static BlockState stateAt(StructurePayload payload, ServerLevel level, int x, int y, int z) {
        Vec3i size = payload.header().size();
        if (x < 0 || y < 0 || z < 0 || x >= size.getX() || y >= size.getY() || z >= size.getZ()) {
            return null;
        }
        int index = x + size.getX() * (z + size.getZ() * y);
        int palette = payload.indices().get(index);
        return palette < 0 ? null : payload.resolvePalette(level.holderLookup(Registries.BLOCK), palette);
    }

    /** The stored tag of a block entity at a local coordinate, or {@code null} when there is none. */
    @Nullable
    public static CompoundTag blockEntityAt(StructurePayload payload, int x, int y, int z) {
        for (StructurePayload.BlockEntityEntry entry : payload.blockEntities()) {
            if (entry.localPos().getX() == x && entry.localPos().getY() == y
                    && entry.localPos().getZ() == z) {
                return entry.nbt();
            }
        }
        return null;
    }
}

package com.jiangyuefengyu.redstonecircuit.structure;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

import com.jiangyuefengyu.redstonecircuit.InnerRedstoneInteraction;
import com.jiangyuefengyu.redstonecircuit.block.RedstoneStructureBlock;
import com.jiangyuefengyu.redstonecircuit.block.StructureBlock;
import com.jiangyuefengyu.redstonecircuit.block.StructureBlockEntity;
import com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneNode;
import com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneStore;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Captures a region of the world into a payload.
 *
 * <p>Three things travel with a region: block entity data ({@code saveWithoutMetadata}, so the coordinates
 * are rewritten on release), this mod's slot data, which lives in the world's {@code InnerRedstoneStore}
 * keyed by position rather than in the block, and one block of world around the selection, which decides how
 * redstone at the edge behaves - it is kept but never released back. Every refusal comes back as a
 * {@link Refusal} with player-readable words.
 */
public final class StructureCapture {

    /** The rules a capture is checked against, in the loader-free form the payload layer can use. */
    public record Limits(PayloadLimits payload, Set<Block> blacklist, int maxNestingDepth,
                         boolean migrateSlots) {

        /** Migration on: the form ordinary callers use. */
        public Limits(PayloadLimits payload, Set<Block> blacklist, int maxNestingDepth) {
            this(payload, blacklist, maxNestingDepth, true);
        }

        /** Only the size rules, with no blacklist. */
        public static Limits of(PayloadLimits payload) {
            return new Limits(payload, Set.of(), 1, true);
        }
    }

    /**
     * What to capture, and how.
     *
     * <p>{@code faces}, {@code manual} and {@code output} are the controller's three tables, written into the
     * payload so a placed structure carries the tables that were set up in front of it. {@code output} is
     * separate from {@code faces} because it runs the other way: one says this face pushes into that cell, the
     * other says that cell makes this face answer.
     */
    public record Request(BlockPos cornerA, BlockPos cornerB, IoMode ioMode, String name, boolean cut,
                          boolean shell, @Nullable BlockPos controller,
                          Map<Direction, List<ActivationTable.FaceEntry>> faces,
                          List<ActivationTable.ManualEntry> manual,
                          List<ActivationTable.OutputEntry> output) {

        public Request {
            faces = faces == null ? Map.of() : Map.copyOf(faces);
            manual = manual == null ? List.of() : List.copyOf(manual);
            output = output == null ? List.of() : List.copyOf(output);
        }

        /** The same request without an output table. */
        public Request(BlockPos cornerA, BlockPos cornerB, IoMode ioMode, String name, boolean cut,
                       boolean shell, @Nullable BlockPos controller,
                       Map<Direction, List<ActivationTable.FaceEntry>> faces,
                       List<ActivationTable.ManualEntry> manual) {
            this(cornerA, cornerB, ioMode, name, cut, shell, controller, faces, manual, List.of());
        }

        /** The same request with an activation table and no manual panel. */
        public Request(BlockPos cornerA, BlockPos cornerB, IoMode ioMode, String name, boolean cut,
                       boolean shell, @Nullable BlockPos controller,
                       Map<Direction, List<ActivationTable.FaceEntry>> faces) {
            this(cornerA, cornerB, ioMode, name, cut, shell, controller, faces, List.of(), List.of());
        }

        /** The same request with none of the three tables. */
        public Request(BlockPos cornerA, BlockPos cornerB, IoMode ioMode, String name, boolean cut,
                       boolean shell, @Nullable BlockPos controller) {
            this(cornerA, cornerB, ioMode, name, cut, shell, controller, Map.of(), List.of(), List.of());
        }

        public static Request of(BlockPos cornerA, BlockPos cornerB, boolean cut, String name) {
            return new Request(cornerA, cornerB, IoMode.ACTIVATION_FACES, name, cut, true, null);
        }
    }

    /** Why a capture did not happen. */
    public record Refusal(String reason) {
    }

    /** A finished capture. */
    public record Captured(StructurePayload payload, int volume, int paletteSize, int shellBlocks,
                           int slotEntries, int blockEntities, int nestingDepth) {
    }

    /** Either a capture, or the reason there is none. */
    public record Result(@Nullable Captured captured, String refusal) {

        public static Result refused(String reason) {
            return new Result(null, reason);
        }

        public boolean allowed() {
            return this.captured != null;
        }
    }

    /** The axis-aligned box a request describes, with the corners in whatever order they were given. */
    public record Box(BlockPos min, BlockPos max, Vec3i size) {

        public static Box of(BlockPos a, BlockPos b) {
            BlockPos min = new BlockPos(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()),
                    Math.min(a.getZ(), b.getZ()));
            BlockPos max = new BlockPos(Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()),
                    Math.max(a.getZ(), b.getZ()));
            return new Box(min, max, new Vec3i(max.getX() - min.getX() + 1, max.getY() - min.getY() + 1,
                    max.getZ() - min.getZ() + 1));
        }

        public boolean contains(BlockPos pos) {
            return pos.getX() >= this.min.getX() && pos.getX() <= this.max.getX()
                    && pos.getY() >= this.min.getY() && pos.getY() <= this.max.getY()
                    && pos.getZ() >= this.min.getZ() && pos.getZ() <= this.max.getZ();
        }

        public int volume() {
            return this.size.getX() * this.size.getY() * this.size.getZ();
        }

        /** The one index formula this mod uses, for capture and for release alike. */
        public int index(int x, int y, int z) {
            return x + this.size.getX() * (z + this.size.getZ() * y);
        }
    }

    /**
     * Captures {@code request} from {@code level}.
     *
     * <p>Everything is read before anything is removed, so a refusal never leaves a half-cut region
     * behind, and a cut cannot delete a block whose data has not been collected yet.
     */
    public static Result capture(ServerLevel level, Request request, Limits limits) {
        Box box = Box.of(request.cornerA(), request.cornerB());

        Optional<String> tooBig = limits.payload().refuseSize(box.size());
        if (tooBig.isPresent()) {
            return Result.refused(tooBig.get());
        }
        if (request.controller() != null && box.contains(request.controller())) {
            return Result.refused("the selection contains the redstone structure block itself, so capturing "
                    + "it would take the controller with it");
        }
        Refusal forbidden = firstForbidden(level, box, limits.blacklist());
        if (forbidden != null) {
            return Result.refused(forbidden.reason());
        }
        int nesting = nestingDepth(level, box);
        if (nesting > limits.maxNestingDepth()) {
            return Result.refused("the selection would be " + nesting + " structures deep, the limit is "
                    + limits.maxNestingDepth() + " (structureMaxNestingDepth)");
        }

        Map<BlockState, Integer> paletteIndex = new LinkedHashMap<>();
        List<CompoundTag> palette = new ArrayList<>();
        int[] cells = new int[box.volume()];
        List<StructurePayload.BlockEntityEntry> blockEntities = new ArrayList<>();
        List<StructurePayload.SlotEntry> slots = new ArrayList<>();
        List<StructurePayload.SettingEntry> settings = new ArrayList<>();
        InnerRedstoneStore store = InnerRedstoneStore.get(level);

        for (int y = 0; y < box.size().getY(); y++) {
            for (int z = 0; z < box.size().getZ(); z++) {
                for (int x = 0; x < box.size().getX(); x++) {
                    BlockPos pos = box.min().offset(x, y, z);
                    BlockState state = level.getBlockState(pos);
                    Integer index = paletteIndex.get(state);
                    if (index == null) {
                        index = palette.size();
                        paletteIndex.put(state, index);
                        palette.add(StructurePayload.paletteEntry(state));
                    }
                    cells[box.index(x, y, z)] = index;

                    BlockEntity blockEntity = level.getBlockEntity(pos);
                    if (blockEntity != null) {
                        // Without metadata, so a captured chest does not keep the old x/y/z.
                        CompoundTag data = blockEntity.saveWithoutMetadata(level.registryAccess());
                        // A copied structure block must not inherit its plot tag: the id travels, and a plot-less
                        // block asks for its own plot instead of pointing at another instance's cells.
                        if (blockEntity instanceof StructureBlockEntity) {
                            data.remove("plot");
                        }
                        blockEntities.add(new StructurePayload.BlockEntityEntry(new BlockPos(x, y, z), data));
                    }
                    InnerRedstoneNode node = store.get(pos);
                    if (limits.migrateSlots() && node != null && !node.isEmpty()) {
                        slots.add(new StructurePayload.SlotEntry(new BlockPos(x, y, z), node.save()));
                    }
                    // A clock's setting lives beside the block, not in any block state, and travels with the
                    // structure; without this it comes back at the default one second.
                    com.jiangyuefengyu.redstonecircuit.logic.ClockRule.Setting clock = store.clockAt(pos);
                    if (limits.migrateSlots() && clock != null) {
                        CompoundTag setting = new CompoundTag();
                        clock.save(setting);
                        settings.add(new StructurePayload.SettingEntry(new BlockPos(x, y, z), setting));
                    }
                    // And 信号延长器's two numbers, which live beside the block for the same reason.
                    com.jiangyuefengyu.redstonecircuit.logic.ExtenderRule.Setting extender = store.extenderAt(pos);
                    if (limits.migrateSlots() && extender != null) {
                        CompoundTag setting = new CompoundTag();
                        extender.save(setting);
                        settings.add(new StructurePayload.SettingEntry(new BlockPos(x, y, z), setting));
                    }
                }
            }
        }

        List<StructurePayload.ShellEntry> shell = request.shell()
                ? collectShell(level, box, paletteIndex, palette)
                : List.of();

        PayloadHeader header = new PayloadHeader(PayloadHeader.CURRENT_FORMAT, java.util.UUID.randomUUID(),
                request.name(), box.size(), request.cornerA().subtract(box.min()), nesting,
                request.ioMode(), request.cut);
        StructurePayload payload = StructurePayload.of(header, request.faces(), request.manual(),
                request.output(), palette, IndexArray.of(cells, palette.size()), shell, slots, settings,
                blockEntities);

        if (request.cut()) {
            for (int y = 0; y < box.size().getY(); y++) {
                for (int z = 0; z < box.size().getZ(); z++) {
                    for (int x = 0; x < box.size().getX(); x++) {
                        BlockPos pos = box.min().offset(x, y, z);
                        // No drops: everything the block carried is in the payload now.
                        level.removeBlock(pos, false);
                        // The component inside a block goes only if it travelled: with migration off it stays,
                        // because deleting it would destroy the player's work.
                        if (limits.migrateSlots() && store.get(pos) != null) {
                            // The client's copy is of the store and not of the payload, so a removal nobody names
                            // on the wire leaves a component the player can see inside whatever block comes next.
                            InnerRedstoneInteraction.forget(level, pos);
                        }
                    }
                }
            }
            if (limits.migrateSlots()) {
                store.markDirty();
            }
        }

        return new Result(new Captured(payload, box.volume(), palette.size(), shell.size(), slots.size(),
                blockEntities.size(), nesting), "");
    }

    /** The first block in the box that may not be captured, or {@code null} when the box is clean. */
    @Nullable
    public static Refusal firstForbidden(ServerLevel level, Box box, Set<Block> blacklist) {
        if (blacklist.isEmpty()) {
            return null;
        }
        for (int y = 0; y < box.size().getY(); y++) {
            for (int z = 0; z < box.size().getZ(); z++) {
                for (int x = 0; x < box.size().getX(); x++) {
                    BlockPos pos = box.min().offset(x, y, z);
                    Block block = level.getBlockState(pos).getBlock();
                    if (blacklist.contains(block)) {
                        return new Refusal("the selection contains " + block.getName().getString()
                                + " at " + pos.toShortString() + ", which may not be captured");
                    }
                }
            }
        }
        return null;
    }

    /**
     * How deep the structures inside this box go: {@code 1 + max(child depth)}, or 0 when there is none.
     */
    public static int nestingDepth(ServerLevel level, Box box) {
        int deepestChild = 0;
        boolean found = false;
        for (int y = 0; y < box.size().getY(); y++) {
            for (int z = 0; z < box.size().getZ(); z++) {
                for (int x = 0; x < box.size().getX(); x++) {
                    BlockPos pos = box.min().offset(x, y, z);
                    BlockState state = level.getBlockState(pos);
                    if (!(state.getBlock() instanceof StructureBlock)
                            && !(state.getBlock() instanceof RedstoneStructureBlock)) {
                        continue;
                    }
                    found = true;
                    BlockEntity blockEntity = level.getBlockEntity(pos);
                    int childDepth = blockEntity instanceof StructureBlockEntity product
                            ? product.nestingDepth()
                            : 0;
                    deepestChild = Math.max(deepestChild, childDepth);
                }
            }
        }
        return found ? 1 + deepestChild : 0;
    }

    /**
     * The one block of world around the box, without the box itself; air is not kept.
     */
    private static List<StructurePayload.ShellEntry> collectShell(ServerLevel level, Box box,
                                                                  Map<BlockState, Integer> paletteIndex,
                                                                  List<CompoundTag> palette) {
        List<StructurePayload.ShellEntry> shell = new ArrayList<>();
        for (int y = -1; y <= box.size().getY(); y++) {
            for (int z = -1; z <= box.size().getZ(); z++) {
                for (int x = -1; x <= box.size().getX(); x++) {
                    boolean insideBox = x >= 0 && x < box.size().getX()
                            && y >= 0 && y < box.size().getY()
                            && z >= 0 && z < box.size().getZ();
                    if (insideBox) {
                        continue;
                    }
                    BlockPos pos = box.min().offset(x, y, z);
                    BlockState state = level.getBlockState(pos);
                    if (state.isAir()) {
                        continue;
                    }
                    Integer index = paletteIndex.get(state);
                    if (index == null) {
                        index = palette.size();
                        paletteIndex.put(state, index);
                        palette.add(StructurePayload.paletteEntry(state));
                    }
                    shell.add(new StructurePayload.ShellEntry(new BlockPos(x, y, z), index));
                }
            }
        }
        return shell;
    }

    private StructureCapture() {
    }
}

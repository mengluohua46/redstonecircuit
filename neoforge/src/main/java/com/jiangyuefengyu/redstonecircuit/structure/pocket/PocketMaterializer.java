package com.jiangyuefengyu.redstonecircuit.structure.pocket;

import java.util.HashMap;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

import com.jiangyuefengyu.redstonecircuit.RCConfig;
import com.jiangyuefengyu.redstonecircuit.block.StructureBlockEntity;
import com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneNode;
import com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneStore;
import com.jiangyuefengyu.redstonecircuit.structure.StructurePayload;
import com.jiangyuefengyu.redstonecircuit.structure.StructureWorldStore;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Puts a captured structure into the pocket: the step that makes a placed product block a <b>view</b>, with the blocks
 * it stands for written as ordinary blocks in a pocket plot where vanilla itself runs their redstone.
 *
 * <p>Nothing here simulates anything: it writes the same block states, the same block entity data and the same slot
 * data, and then lets the plot's ticket do the rest.
 *
 * <p>A product carries three things into its plot: the selection's blocks, from the payload's palette and indices; the
 * <b>context shell</b>, one block of world around them, because whether the block beside a wire is solid decides strong
 * power and the shape of dust - written into the plot and never released back; and this mod's own slot data, into the
 * <em>pocket's</em> store rather than the world's, because the pocket is a level of its own with its own data.
 */
public final class PocketMaterializer {

    /** Either a plot that is now holding the structure, or the reason it is not. */
    public record Result(@Nullable Integer plot, String refusal, int blocks) {

        public boolean materialized() {
            return this.plot != null;
        }
    }

    /**
     * Writes {@code payload} into a fresh plot of the pocket dimension and starts ticking it.
     *
     * <p>{@code shell} asks for the payload's context shell as well. {@code ownerDimension} and {@code ownerPos} are the
     * level and position of the product block the plot is for, which the table keeps so a restart can find the owner
     * again and reclaim the plot if it is gone.
     */
    public static Result materialize(ServerLevel world, StructurePayload payload, boolean shell,
                                     net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>
                                             ownerDimension,
                                     BlockPos ownerPos) {
        ServerLevel pocket = PocketDimension.of(world.getServer());
        if (pocket == null) {
            return new Result(null, "this server has no " + PocketDimension.STRUCTURE_SPACE.location()
                    + " dimension, so there is nowhere to put the structure", 0);
        }
        return materializeInto(pocket, payload, shell, ownerDimension, ownerPos, 0);
    }

    /**
     * The same, into a level that is given rather than looked up - and a nested structure comes with the one it stands
     * in.
     *
     * <p>A nested product block written into a plot would otherwise wait for its own tick to be given a plot of its own
     * (the block's self repair), and a block inside a plot is only ticked while that plot's chunks are ticking - so the
     * nested structure is materialised <b>here</b>, in the same breath as the outer one, by reading the inner payload
     * from this save's store. A refusal (no file, no room) is left alone: the block's own retry still covers it, and it
     * is the only path that can report <em>why</em>.
     *
     * <p>Taking the pocket level as an argument is also what makes this testable: the GameTest harness cannot host the
     * datapack dimension, so a test passes the test level as the pocket - see {@code NestedStructureMaterializeGameTest}.
     */
    public static Result materializeInto(ServerLevel pocket, StructurePayload payload, boolean shell,
                                         net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>
                                                 ownerDimension,
                                         BlockPos ownerPos, int depth) {
        Vec3i size = payload.header().size();
        int longest = Math.max(size.getX(), Math.max(size.getY(), size.getZ()));
        // The fallback driver's list is cleared HERE, at the start of the materialise that is about to fill it - never
        // by a plot number change, which happens after the materialise returns and would empty it again (see
        // StructureBlockEntity#setPlotIndex).
        forgetNestedProductsOf(pocket, ownerDimension, ownerPos);
        PocketSpace space = PocketSpace.of(pocket);
        PocketSpace.Plotted claimed = space.claim(longest, shell, ownerDimension, ownerPos);
        if (!claimed.allowed()) {
            return new Result(null, claimed.refusal(), 0);
        }
        // THE PLOT IS MARKED AS BEING WRITTEN BEFORE THE FIRST BLOCK GOES IN. A claim happens before the block it is for
        // learns its plot number, so without this marker the owner of this very plot looks like a block that has moved
        // on for the whole of the write - and a structure nested inside this payload (see #materialiseNested below) asks
        // for a plot of its own in exactly that window.
        space.beginWriting(claimed.plot().index());

        BlockPos origin = claimed.plot().origin();
        Map<BlockPos, CompoundTag> blockEntities = new HashMap<>();
        Map<BlockPos, CompoundTag> slots = new HashMap<>();
        // 时钟源's settings, the same kind of "beside the block, not in it" data as a slot - see
        // StructurePayload#settingData for why it travels in the payload at all.
        Map<BlockPos, CompoundTag> settings = new HashMap<>();
        for (StructurePayload.BlockEntityEntry entry : payload.blockEntities()) {
            blockEntities.put(entry.localPos(), entry.nbt());
        }
        for (StructurePayload.SlotEntry entry : payload.slotData()) {
            slots.put(entry.localPos(), entry.node());
        }
        for (StructurePayload.SettingEntry entry : payload.settingData()) {
            settings.put(entry.localPos(), entry.setting());
        }

        var blocks = pocket.holderLookup(Registries.BLOCK);
        InnerRedstoneStore store = InnerRedstoneStore.get(pocket);
        int written = 0;

        // THE CONTEXT SHELL FIRST, and this order is load-bearing. The shell is the one block of world around the
        // selection - the block a dust at the selection's bottom edge stands on, the solid block that decides strong
        // power beside it - and a block written into a cell whose support is not there yet is removed again by vanilla
        // on the very next neighbour update: `RedStoneWireBlock#neighborChanged` drops a wire that cannot survive, and
        // placing the structure's own blocks first means the shell's stone arrives one update too late. Capture-side the
        // shell is also written around the selection and never released, so this is the order the pocket wants: the
        // world the structure came from exists first, then the structure is put down in it.
        if (shell) {
            for (StructurePayload.ShellEntry entry : payload.contextShell()) {
                BlockState state = payload.resolvePalette(blocks, entry.paletteIndex());
                pocket.setBlock(origin.offset(entry.relativePos()), state, 3);
                written++;
            }
        }

        for (int y = 0; y < size.getY(); y++) {
            for (int z = 0; z < size.getZ(); z++) {
                for (int x = 0; x < size.getX(); x++) {
                    int index = payload.indices().get(x + size.getX() * (z + size.getZ() * y));
                    if (index < 0) {
                        continue;
                    }
                    BlockState state = payload.resolvePalette(blocks, index);
                    BlockPos pos = origin.offset(x, y, z);
                    pocket.setBlock(pos, state, 3);
                    written++;

                    CompoundTag data = blockEntities.get(new BlockPos(x, y, z));
                    if (data != null) {
                        BlockEntity created = pocket.getBlockEntity(pos);
                        if (created != null) {
                            created.loadWithComponents(data, pocket.registryAccess());
                            // A COPY NEVER INHERITS A PLOT, whatever the payload says: the number describes one instance
                            // in one world - and a structure file keeps its inner blocks' tags for ever. Forcing the
                            // plot-less state here is the half that cannot be forgotten: the copy keeps its structure
                            // id, so its own next tick gives it a plot of its own
                            // (StructureBlockEntity#repairPlotIfDue).
                            if (created instanceof StructureBlockEntity product) {
                                product.setPlotIndex(-1);
                                // and the structure standing in that cell gets its own plot in the same breath, rather
                                // than waiting for a tick that may never come - see materializeInto.
                                materialiseNested(pocket, product, shell, depth);
                                // and its owner - the block this plot was claimed for - is told that a product stands
                                // here, so it can drive it if the level ever stops doing so (see
                                // StructureBlockEntity#driveNestedProducts).
                                noteNestedProduct(pocket, ownerDimension, ownerPos, new BlockPos(x, y, z));
                            }
                            created.setChanged();
                        }
                    }
                    CompoundTag node = slots.get(new BlockPos(x, y, z));
                    if (node != null) {
                        store.getOrCreate(pos).setSlot(InnerRedstoneNode.load(node).slot());
                    }
                    CompoundTag setting = settings.get(new BlockPos(x, y, z));
                    if (setting != null) {
                        // Which device the entry belongs to is read off the block that now stands here, so the payload
                        // never has to name the kind: both of them keep their settings beside the block, not in it.
                        BlockState here = pocket.getBlockState(pos);
                        if (here.getBlock() instanceof com.jiangyuefengyu.redstonecircuit.block.ClockBlock) {
                            store.putClock(pos,
                                    com.jiangyuefengyu.redstonecircuit.logic.ClockRule.Setting.load(setting));
                        } else if (here.getBlock() instanceof com.jiangyuefengyu.redstonecircuit.block.ExtenderBlock) {
                            store.putExtender(pos,
                                    com.jiangyuefengyu.redstonecircuit.logic.ExtenderRule.Setting.load(setting));
                        }
                    }
                }
            }
        }
        store.markDirty();

        if (RCConfig.debugLog()) {
            RCConfig.LOGGER.info("[redstonecircuit] pocket: plot {} now holds {} ({} block(s), {} column(s))",
                    claimed.plot().index(), payload.header().name(), written, claimed.plot().columns());
        }
        // THE WINDOW CLOSES HERE, and nowhere earlier: everything the payload needed a plot for - including the nested
        // structures materialised inside this loop - has been asked for by now. The caller sets the plot index on the
        // block this was claimed for, so from here on that block answers for its plot and the marker has nothing left to
        // say (see PocketSpace#beginWriting).
        space.endWriting(claimed.plot().index());
        return new Result(claimed.plot().index(), "", written);
    }

    /** How deep {@link #materializeNested} follows product blocks inside a payload; the capture limit is lower. */
    private static final int MAX_NESTED_MATERIALIZE = 4;

    /**
     * Tells the block this plot was claimed for that a product block now stands at {@code local} inside it.
     *
     * <p>Quietly skipped when the owner cannot be reached (another dimension, an unloaded chunk, a block that is not a
     * product): the list it fills is a fallback driver, not the mechanism - the level ticks a plot's blocks as it always
     * did, and this exists only because a block entity can drop out of that list without leaving a trace.
     */
    private static void noteNestedProduct(ServerLevel pocket, net.minecraft.resources.ResourceKey<
            net.minecraft.world.level.Level> ownerDimension, BlockPos ownerPos, BlockPos local) {
        net.minecraft.server.MinecraftServer server = pocket.getServer();
        ServerLevel ownerLevel = server == null ? null : server.getLevel(ownerDimension);
        if (ownerLevel == null || !(ownerLevel.getBlockEntity(ownerPos) instanceof StructureBlockEntity owner)) {
            return;
        }
        owner.noteNestedProduct(local);
    }

    /** The same lookup, for the materialise that is about to write a fresh set of nested products. */
    private static void forgetNestedProductsOf(ServerLevel pocket, net.minecraft.resources.ResourceKey<
            net.minecraft.world.level.Level> ownerDimension, BlockPos ownerPos) {
        net.minecraft.server.MinecraftServer server = pocket.getServer();
        ServerLevel ownerLevel = server == null ? null : server.getLevel(ownerDimension);
        if (ownerLevel == null || !(ownerLevel.getBlockEntity(ownerPos) instanceof StructureBlockEntity owner)) {
            return;
        }
        owner.forgetNestedProducts();
    }

    /**
     * Gives the structure inside {@code product} a plot of its own, in the pocket that product stands in.
     *
     * <p>Nothing here is new machinery: it is the same two steps a product's own tick runs
     * ({@code StructureBlockEntity#repairPlot}). What is new is doing it now, while the outer structure is still
     * being written - see {@link #materializeInto} for why.
     */
    private static void materialiseNested(ServerLevel pocket, StructureBlockEntity product, boolean shell,
                                          int depth) {
        if (depth >= MAX_NESTED_MATERIALIZE || product.structureId() == null) {
            return;
        }
        StructurePayload inner = StructureWorldStore.of(pocket.getServer(), RCConfig.structurePayloadLimits())
                .loadOrNull(product.structureId());
        if (inner == null) {
            return;
        }
        Result nested = materializeInto(pocket, inner, shell, pocket.dimension(), product.getBlockPos(),
                depth + 1);
        if (nested.materialized()) {
            product.setPlotIndex(nested.plot());
        }
    }

    /**
     * Empties a plot and gives it back.
     *
     * <p>The clearing lives in {@link PocketSpace#clearBlocks}, because the repair sweep needs to do the same
     * thing when it finds a plot whose owner has gone - and two copies of "what a plot consists of" would
     * drift apart.
     */
    public static boolean release(ServerLevel world, int plotIndex) {
        ServerLevel pocket = PocketDimension.of(world.getServer());
        if (pocket == null) {
            return false;
        }
        PocketSpace space = PocketSpace.of(pocket);
        PocketPlots.Entry claim = PocketPlots.get(pocket).entry(plotIndex);
        if (claim != null) {
            space.clearBlocks(plotIndex, claim.size(), claim.shell());
        }
        return space.release(plotIndex);
    }

    private PocketMaterializer() {
    }
}

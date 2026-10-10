package com.jiangyuefengyu.redstonecircuit;

import java.util.Set;
import java.util.UUID;

import com.jiangyuefengyu.redstonecircuit.block.StructureBlockEntity;
import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneNode;
import com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneStore;
import com.jiangyuefengyu.redstonecircuit.structure.IoMode;
import com.jiangyuefengyu.redstonecircuit.structure.PayloadLimits;
import com.jiangyuefengyu.redstonecircuit.structure.StructureCapture;
import com.jiangyuefengyu.redstonecircuit.structure.StructurePayload;
import com.jiangyuefengyu.redstonecircuit.structure.StructureProductData;
import com.jiangyuefengyu.redstonecircuit.structure.StructureWorldStore;
import com.jiangyuefengyu.redstonecircuit.structure.pocket.PocketSpace;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Capture: what a region becomes, and what it refuses to become.
 *
 * <p>A capture's payload has to agree with the region block for block, a cut has to take the region while a copy leaves
 * it, and what may not be captured has to be refused with a reason. Two of the assertions are about content that is
 * <b>not</b> a block state, which is what a player notices when it goes missing: a chest's contents, and this mod's own
 * component buried inside a host block.
 */
public final class StructureCaptureGameTest implements FabricGameTest {

    private static final BlockPos FROM = new BlockPos(1, 2, 1);
    /** Corners are inclusive: 3×2×3 = 18 blocks. */
    private static final BlockPos TO = new BlockPos(3, 3, 3);

    /**
     * Builds the region the tests capture: a chest with something in it, one of our own repeaters, a component inside a
     * host block, and one block of world just outside the selection. {@code relativeFrom} is <b>relative</b> to the test
     * structure, because that is what {@code helper.setBlock} takes; anything that talks to the level directly goes
     * through {@code helper.absolutePos}.
     */
    private static void buildRegion(GameTestHelper helper, ServerLevel level, BlockPos relativeFrom) {
        helper.setBlock(relativeFrom, Blocks.STONE);
        helper.setBlock(relativeFrom.offset(1, 0, 0), Blocks.CHEST);
        helper.setBlock(relativeFrom.offset(2, 0, 0), Blocks.REDSTONE_WIRE);
        helper.setBlock(relativeFrom.offset(0, 1, 0), Blocks.STONE);
        helper.setBlock(relativeFrom.offset(1, 1, 0), RCRegistry.RETAINING_REPEATER);
        helper.setBlock(relativeFrom.offset(2, 1, 0), Blocks.STONE);
        // One block outside the box: the shell's whole purpose.
        helper.setBlock(relativeFrom.offset(-1, 0, 0), Blocks.OBSIDIAN);

        if (level.getBlockEntity(helper.absolutePos(relativeFrom.offset(1, 0, 0)))
                instanceof ChestBlockEntity chest) {
            chest.setItem(0, new ItemStack(Items.DIAMOND, 3));
        }
        // A component inside a host block: not in the block, in the world's own store.
        InnerRedstoneStore.get(level).getOrCreate(helper.absolutePos(relativeFrom))
                .setSlot(InnerRedstoneNode.of(ComponentType.DUST).slot());
    }

    private static BlockPos from(GameTestHelper helper) {
        return helper.absolutePos(FROM);
    }

    private static BlockPos to(GameTestHelper helper) {
        return helper.absolutePos(TO);
    }

    private static StructureCapture.Limits limits(Set<net.minecraft.world.level.block.Block> blacklist,
                                                  int maxNesting) {
        return new StructureCapture.Limits(PayloadLimits.DEFAULT, blacklist, maxNesting);
    }

    @GameTest(template = "redstonecircuit:empty")
    public void aCutCaptureTakesTheRegionAndKeepsWhatItCarried(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        buildRegion(helper, level, FROM);
        BlockPos from = from(helper);
        BlockPos to = to(helper);
        StructureCapture.Box box = StructureCapture.Box.of(from, to);

        // What the region looked like before anything happened to it - the thing the payload has to agree
        // with. Read first, because a cut is about to remove all of it.
        BlockState[] before = new BlockState[box.volume()];
        for (int y = 0; y < box.size().getY(); y++) {
            for (int z = 0; z < box.size().getZ(); z++) {
                for (int x = 0; x < box.size().getX(); x++) {
                    before[box.index(x, y, z)] = level.getBlockState(from.offset(x, y, z));
                }
            }
        }

        StructureCapture.Result result = StructureCapture.capture(level,
                new StructureCapture.Request(from, to, IoMode.ACTIVATION_FACES, "cut test", true, true, null),
                limits(Set.of(), 1));

        helper.assertTrue(result.allowed(), "a plain 3x2x3 region with nothing odd in it must capture: "
                + result.refusal());
        StructureCapture.Captured captured = result.captured();
        StructurePayload payload = captured.payload();
        helper.assertTrue(captured.volume() == 18, "18 blocks, got " + captured.volume());

        // The payload agrees with the region, block for block.
        var blocks = level.holderLookup(Registries.BLOCK);
        for (int y = 0; y < box.size().getY(); y++) {
            for (int z = 0; z < box.size().getZ(); z++) {
                for (int x = 0; x < box.size().getX(); x++) {
                    int index = payload.indices().get(box.index(x, y, z));
                    BlockState stored = payload.resolvePalette(blocks, index);
                    helper.assertTrue(stored.equals(before[box.index(x, y, z)]),
                            "cell " + x + "," + y + "," + z + " was " + before[box.index(x, y, z)]
                                    + " and the payload says " + stored);
                }
            }
        }
        helper.assertTrue(payload.indices().refersOnlyTo(payload.palette().size()),
                "and every index points inside the palette");
        helper.assertTrue(payload.header().byCut(), "the payload remembers it was a cut");
        helper.assertTrue(payload.header().size().getX() == 3 && payload.header().size().getY() == 2
                        && payload.header().size().getZ() == 3,
                "and how big the selection was");

        // A cut takes the region, with no drops: everything it carried is in the payload now.
        helper.assertTrue(level.getBlockState(from).isAir()
                        && level.getBlockState(from.offset(2, 1, 0)).isAir(),
                "after a cut the region must be gone");

        // What the blocks carried.
        helper.assertTrue(captured.blockEntities() >= 1, "the chest's data must be captured");
        helper.assertTrue(captured.slotEntries() == 1,
                "the component inside the host block must be captured too, got " + captured.slotEntries());
        InnerRedstoneNode left = InnerRedstoneStore.get(level).get(from);
        helper.assertTrue(left == null || left.isEmpty(),
                "and a cut must remove it from the world's store, or it would exist in two places");

        // The shell: the one block of world placed outside the box is in it, as obsidian, and the world
        // keeps it. (The test structure has blocks of its own around the region, so this asks about that
        // block rather than about the count.)
        helper.assertTrue(captured.shellBlocks() >= 1,
                "the shell must hold the world around the selection, got " + captured.shellBlocks());
        boolean obsidianInShell = payload.contextShell().stream().anyMatch(entry ->
                entry.relativePos().equals(new BlockPos(-1, 0, 0))
                        && payload.resolvePalette(blocks, entry.paletteIndex()).is(Blocks.OBSIDIAN));
        helper.assertTrue(obsidianInShell,
                "the block just outside the selection has to be in the shell as obsidian; the shell holds "
                        + captured.shellBlocks() + " block(s)");
        helper.assertTrue(level.getBlockState(from.offset(-1, 0, 0)).is(Blocks.OBSIDIAN),
                "the shell is a copy: the world outside the selection is not touched");

        // And the whole thing goes into this save, under its own id.
        try {
            StructureWorldStore store = StructureWorldStore.of(level.getServer(), PayloadLimits.DEFAULT);
            store.add(payload);
            helper.assertTrue(store.contains(payload.header().uuid()),
                    "the capture must be readable from this save's own directory");
            helper.assertTrue(store.load(payload.header().uuid()).equals(payload),
                    "and come back as the same structure");
        } catch (java.io.IOException failed) {
            helper.fail("storing the capture failed: " + failed);
            return;
        }
        helper.succeed();
    }

    @GameTest(template = "redstonecircuit:empty")
    public void aCopyCaptureLeavesTheWorldAlone(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        buildRegion(helper, level, FROM);
        BlockPos from = from(helper);
        BlockPos to = to(helper);
        BlockState chestBefore = level.getBlockState(from.offset(1, 0, 0));

        StructureCapture.Result result = StructureCapture.capture(level,
                new StructureCapture.Request(from, to, IoMode.ACTIVATION_FACES, "copy test", false, true, null),
                limits(Set.of(), 1));

        helper.assertTrue(result.allowed(), result.refusal());
        helper.assertTrue(level.getBlockState(from.offset(1, 0, 0)).equals(chestBefore),
                "a copy leaves the block where it was");
        InnerRedstoneNode kept = InnerRedstoneStore.get(level).get(from);
        helper.assertTrue(kept != null && !kept.isEmpty(),
                "and leaves the component inside a host block where it was");
        helper.assertTrue(level.getBlockEntity(from.offset(1, 0, 0)) instanceof ChestBlockEntity chest
                        && chest.getItem(0).getCount() == 3,
                "the chest keeps its contents");
        helper.assertTrue(!result.captured().payload().header().byCut(),
                "the payload remembers this one was a copy");
        helper.assertTrue(result.captured().slotEntries() == 1,
                "the component still travelled into the payload");
        helper.succeed();
    }

    @GameTest(template = "redstonecircuit:empty")
    public void captureRefusesWhatItMustAndSaysWhy(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos from = from(helper);
        BlockPos to = to(helper);

        // A forbidden block: a captured bedrock can be released anywhere, and a command block keeps its command.
        helper.setBlock(FROM.offset(1, 0, 1), Blocks.BEDROCK);
        StructureCapture.Result forbidden = StructureCapture.capture(level,
                new StructureCapture.Request(from, to, IoMode.ACTIVATION_FACES, "bedrock", false, false, null),
                limits(Set.of(Blocks.BEDROCK), 1));
        helper.assertTrue(!forbidden.allowed() && forbidden.refusal().contains("Bedrock"),
                "bedrock must be refused by name, got: " + forbidden.refusal());

        // The negative control for that check: the same region with an empty blacklist is accepted, so the
        // refusal above really came from the blacklist and not from something else about the region.
        StructureCapture.Result sameRegionAllowed = StructureCapture.capture(level,
                new StructureCapture.Request(from, to, IoMode.ACTIVATION_FACES, "no blacklist", false, false,
                        null),
                limits(Set.of(), 1));
        helper.assertTrue(sameRegionAllowed.allowed(),
                "with no blacklist the same region captures, so the refusal was the blacklist: "
                        + sameRegionAllowed.refusal());
        helper.setBlock(FROM.offset(1, 0, 1), Blocks.AIR);

        // A region bigger than the limit is refused before anything is read.
        StructureCapture.Result tooBig = StructureCapture.capture(level,
                new StructureCapture.Request(from, to.offset(60, 0, 0), IoMode.ACTIVATION_FACES, "too big",
                        false, false, null),
                limits(Set.of(), 1));
        helper.assertTrue(!tooBig.allowed() && tooBig.refusal().contains("longest axis"),
                "an oversized selection must be refused by name, got: " + tooBig.refusal());

        // The controller may not be inside its own selection: capturing it would take it with it.
        StructureCapture.Result withController = StructureCapture.capture(level,
                new StructureCapture.Request(from, to, IoMode.ACTIVATION_FACES, "controller", false, false,
                        from.offset(1, 1, 1)),
                limits(Set.of(), 1));
        helper.assertTrue(!withController.allowed() && withController.refusal().contains("controller"),
                "a selection containing the controller must be refused, got: " + withController.refusal());

        // Nesting: a structure block inside the selection makes it one deeper, so a limit of 0 refuses.
        helper.setBlock(FROM.offset(2, 1, 1), RCRegistry.STRUCTURE);
        StructureCapture.Result nested = StructureCapture.capture(level,
                new StructureCapture.Request(from, to, IoMode.ACTIVATION_FACES, "nested", false, false, null),
                limits(Set.of(), 0));
        helper.assertTrue(!nested.allowed() && nested.refusal().contains("deep"),
                "with structureMaxNestingDepth=0 a structure block inside must be refused, got: "
                        + nested.refusal());

        // And the negative control: the same region is allowed at the default limit of 1, which is what
        // proves the refusal above was the depth and not the block.
        StructureCapture.Result nestedAllowed = StructureCapture.capture(level,
                new StructureCapture.Request(from, to, IoMode.ACTIVATION_FACES, "nested ok", false, false,
                        null),
                limits(Set.of(), 1));
        helper.assertTrue(nestedAllowed.allowed(),
                "at the default limit of 1 the same region is allowed: " + nestedAllowed.refusal());
        helper.assertTrue(nestedAllowed.captured().nestingDepth() == 1,
                "and the payload records that it is one deep, got "
                        + nestedAllowed.captured().nestingDepth());
        helper.succeed();
    }

    /**
     * <b>A structure block inside a capture travels without its plot number.</b>
     *
     * <p>Nesting depth 1 is exactly "this region contains a placed structure block", so a payload routinely carries one,
     * and its block entity tag records which pocket plot it holds - the number of one instance in one world. A copy that
     * inherits it drives somebody else's machine while that plot exists, and spends its first tick discovering that when
     * it does not. The id is the opposite: it is the name of a file in this save, so the copy can always be given a plot
     * of its own.
     */
    @GameTest(template = "redstonecircuit:empty")
    public void aCapturedStructureBlockComesBackWithNoPlot(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos owner = helper.absolutePos(FROM.offset(1, 0, 1));
        helper.setBlock(FROM.offset(1, 0, 1), RCRegistry.STRUCTURE);
        if (!(level.getBlockEntity(owner) instanceof StructureBlockEntity product)) {
            helper.fail("the placed structure block inside the region must have a block entity");
            return;
        }
        UUID innerId = UUID.randomUUID();
        product.setStructure(innerId, "inner", new Vec3i(2, 2, 2), IoMode.ACTIVATION_FACES, 0);
        PocketSpace space = PocketSpace.of(level);
        PocketSpace.Plotted claimed = space.claim(2, false, level.dimension(), owner);
        if (!claimed.allowed()) {
            helper.fail("the inner structure needs a plot to have a number to lose: " + claimed.refusal());
            return;
        }
        product.setPlotIndex(claimed.plot().index());

        StructureCapture.Result captured = StructureCapture.capture(level,
                new StructureCapture.Request(helper.absolutePos(FROM), helper.absolutePos(TO),
                        IoMode.ACTIVATION_FACES, "nested copy", false, true, null),
                limits(Set.of(), 1));
        try {
            helper.assertTrue(captured.allowed(), captured.refusal());
            CompoundTag inner = null;
            for (StructurePayload.BlockEntityEntry entry : captured.captured().payload().blockEntities()) {
                if (entry.nbt().contains("structure")) {
                    inner = entry.nbt();
                }
            }
            helper.assertTrue(inner != null,
                    "the placed structure block must travel as a block entity - that is what makes this a nested "
                            + "capture at all");
            helper.assertTrue(!inner.contains("plot"),
                    "and it must not carry the plot number it held in the world: a copy that keeps it drives whatever "
                            + "structure owns that number now");
            helper.assertTrue(inner.contains("structure"),
                    "while its own id does travel - as the int array UUIDUtil writes, which is why this asks for the key "
                            + "and not for a string - because that id is what lets the copy be given a plot of its own: "
                            + inner);
        } finally {
            level.setBlock(owner, Blocks.AIR.defaultBlockState(), 3);
            space.release(claimed.plot().index());
        }
        helper.succeed();
    }

    /**
     * The item half: a structure item describes a structure through its component, and a placed one keeps
     * the id - which is what "the item only carries an id" means from both ends.
     */
    @GameTest(template = "redstonecircuit:empty")
    public void aStructureItemDescribesItsStructure(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        UUID id = UUID.randomUUID();
        StructureProductData data = new StructureProductData(id, "carried", new Vec3i(4, 2, 6),
                IoMode.MANUAL, 1);

        ItemStack stack = RCRegistry.structureStack(data);
        helper.assertTrue(data.equals(RCRegistry.structureOf(stack)),
                "a structure item must describe the structure it is, through the component");
        helper.assertTrue(RCRegistry.structureOf(new ItemStack(RCRegistry.STRUCTURE_ITEM)) == null,
                "a plain structure item describes nothing, which is the placeholder case");

        helper.setBlock(FROM, RCRegistry.STRUCTURE);
        if (!(level.getBlockEntity(helper.absolutePos(FROM)) instanceof StructureBlockEntity product)) {
            helper.fail("the placed structure must have a block entity");
            return;
        }
        product.setStructure(data);
        helper.assertTrue(id.equals(product.structureId()) && product.nestingDepth() == 1,
                "and the block holds it, depth included - the depth is what bounds nesting");
        helper.assertTrue(product.size().equals(new Vec3i(4, 2, 6)),
                "with the size it was captured at");
        helper.succeed();
    }
}

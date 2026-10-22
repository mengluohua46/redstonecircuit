package com.jiangyuefengyu.redstonecircuit;

import com.jiangyuefengyu.redstonecircuit.block.RedstoneStructureBlockEntity;
import com.jiangyuefengyu.redstonecircuit.network.RCNetwork;
import com.jiangyuefengyu.redstonecircuit.structure.ActivationTable;
import com.jiangyuefengyu.redstonecircuit.structure.RegionEditor;
import com.jiangyuefengyu.redstonecircuit.structure.StructureEdits;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;

/**
 * <b>The panel's row button: one entry replaced whole, and the index that is not there.</b>
 *
 * <p>An entry's identity is its {@code (key, cell)} pair, so renaming it is not "remove, then add" - between those two
 * edits the row is gone, and a refused second half would lose it for good. What is driven here is the one edit that
 * replaces it: the panel keeps its count, the row keeps its place in the grid, and the entries behind it do not move.
 * The same test then asks for an index the panel does not have, which is what a client sends when two players edit one
 * controller at once, and requires a refusal that names the count.
 */
public final class StructureManualReplaceGameTest implements FabricGameTest {

    private static final BlockPos CONTROLLER = new BlockPos(1, 2, 1);

    private static RedstoneStructureBlockEntity placeController(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(CONTROLLER);
        level.setBlock(pos, RCRegistry.REDSTONE_STRUCTURE_BLOCK.defaultBlockState(), 3);
        level.setBlock(pos.below(), Blocks.STONE.defaultBlockState(), 3);
        if (level.getBlockEntity(pos) instanceof RedstoneStructureBlockEntity controller) {
            return controller;
        }
        helper.fail("the placed controller must have a block entity");
        throw new IllegalStateException("unreachable");
    }

    /** A floor with a row of wire on it: the cells the panel's entries name. */
    private static void floorAndWire(ServerLevel level, BlockPos first, int length) {
        for (int x = 0; x < length; x++) {
            level.setBlock(first.offset(x, -1, 0), Blocks.STONE.defaultBlockState(), 3);
            level.setBlock(first.offset(x, 0, 0), Blocks.REDSTONE_WIRE.defaultBlockState(), 3);
        }
    }

    /**
     * The edit the grid's "update" button sends, applied through the slots the packet handler reads it from - which is
     * where a wrong slot would show, since the index and the name are what name the row.
     */
    private static StructureEdits.Result sendReplace(ServerLevel level, BlockPos pos,
                                                     RCNetwork.StructureEditPayload edit) {
        return StructureEdits.replaceManualEntry(level, pos, edit.a(), edit.first(),
                new BlockPos(edit.b(), edit.c(), edit.d()), edit.e());
    }

    private static Vec3i at(BlockPos pos) {
        return new Vec3i(pos.getX(), pos.getY(), pos.getZ());
    }

    @GameTest(template = "redstonecircuit:empty")
    public void oneEntryIsReplacedInItsOwnPlaceAndAnIndexThePanelLacksIsRefused(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        RedstoneStructureBlockEntity controller = placeController(helper);
        BlockPos pos = controller.getBlockPos();
        BlockPos first = helper.absolutePos(new BlockPos(3, 2, 1));
        BlockPos second = first.offset(1, 0, 0);
        BlockPos fresh = first.offset(0, 0, 1);
        floorAndWire(level, first, 2);
        helper.assertTrue(StructureEdits.setRegion(level, pos,
                RegionEditor.absolute(at(first), new Vec3i(2, 1, 2))).ok(),
                "a region first: an entry names a cell inside it");

        // Two keys, in the order the grid draws them.
        helper.assertTrue(StructureEdits.setManualEntry(level, pos, "key.keyboard.g", first, 7, "the door").ok(),
                "the panel's first key");
        helper.assertTrue(StructureEdits.setManualEntry(level, pos, "key.keyboard.h", second, 5, "the gate").ok(),
                "the panel's second key");
        helper.assertTrue(controller.manual().size() == 2,
                "two entries before the update, got " + controller.manual());

        // The row's own button: entry 0 becomes a new name on a new cell at a new strength.
        StructureEdits.Result replaced = sendReplace(level, pos,
                RCNetwork.StructureEditPayload.manualReplace(pos, 0, "the blast door", fresh, 12));
        helper.assertTrue(replaced.ok(), "the update button must be accepted: " + replaced.message());
        helper.assertTrue(controller.manual().size() == 2,
                "a replacement is not a removal plus an addition, so the count stays, got " + controller.manual());

        ActivationTable.ManualEntry row = controller.manual().get(0);
        helper.assertTrue(row.key().equals("the blast door") && row.label().equals("the blast door"),
                "the new name is both the key and the label, got " + row);
        helper.assertTrue(row.localPos().equals(fresh.subtract(first)),
                "on the cell that was typed, stored selection-local, got " + row.localPos().toShortString());
        helper.assertTrue(row.strength() == 12, "and at the strength that was typed, got " + row.strength());

        // The row behind it is the row that was there, in the place it was: the edit replaces a slot, it does not
        // remove one and append another.
        ActivationTable.ManualEntry untouched = controller.manual().get(1);
        helper.assertTrue(untouched.key().equals("key.keyboard.h") && untouched.label().equals("the gate")
                        && untouched.strength() == 5 && untouched.localPos().equals(second.subtract(first)),
                "entry 1 must still be its own row, still second, got " + untouched);

        // An index the panel does not have - a client that is one edit behind - is refused by name, not ignored.
        StructureEdits.Result missing = sendReplace(level, pos,
                RCNetwork.StructureEditPayload.manualReplace(pos, 2, "a third door", first, 9));
        helper.assertTrue(!missing.ok(), "an index past the end must be refused, not appended");
        helper.assertTrue(missing.message().contains("2 entr(ies)"),
                "and the refusal has to say how many the panel has, got " + missing.message());
        StructureEdits.Result before = sendReplace(level, pos,
                RCNetwork.StructureEditPayload.manualReplace(pos, -1, "a door before the first", first, 9));
        helper.assertTrue(!before.ok(), "and so must an index before the first");

        // The three refusals the panel's add button has, unchanged here: no name, a strength the game cannot carry,
        // and a cell outside the selection.
        helper.assertTrue(!sendReplace(level, pos,
                RCNetwork.StructureEditPayload.manualReplace(pos, 0, "", first, 9)).ok(),
                "a button with no name is not a button");
        helper.assertTrue(!sendReplace(level, pos,
                RCNetwork.StructureEditPayload.manualReplace(pos, 0, "the blast door", first, 99)).ok(),
                "a strength of 99 must be refused");
        helper.assertTrue(!sendReplace(level, pos, RCNetwork.StructureEditPayload.manualReplace(pos, 0,
                "the blast door", helper.absolutePos(new BlockPos(9, 2, 1)), 9)).ok(),
                "a cell outside the region must be refused");

        helper.assertTrue(controller.manual().size() == 2
                        && controller.manual().get(0).key().equals("the blast door")
                        && controller.manual().get(1).key().equals("key.keyboard.h"),
                "and none of the refusals may write anything, got " + controller.manual());
        helper.succeed();
    }
}

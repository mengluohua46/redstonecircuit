package com.jiangyuefengyu.redstonecircuit;

import java.util.List;
import java.util.Map;

import com.jiangyuefengyu.redstonecircuit.block.RedstoneStructureBlockEntity;
import com.jiangyuefengyu.redstonecircuit.structure.ActivationTable;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;

/**
 * The controller's own side of the activation table: filling a face in, and the table surviving.
 *
 * <p>Mode A puts the table on the <b>controller</b> - the player fills it in there and a capture writes it into the
 * payload - so this is the block entity that has to hold it, and it is the one part of the feature the injection tests
 * do not touch (they start from a table handed to them).
 *
 * <p>The first assertion is the first entry of an empty table, which must not throw: {@code new EnumMap<>(Map)} cannot
 * infer a key type from an empty map.
 */
public final class StructureFaceTableGameTest implements FabricGameTest {

    private static final BlockPos FIRST_CELL = new BlockPos(0, 0, 0);
    private static final BlockPos SECOND_CELL = new BlockPos(1, 0, 0);

    private static int strengthOf(Map<Direction, List<ActivationTable.FaceEntry>> table, Direction face,
                                 BlockPos cell) {
        for (ActivationTable.FaceEntry entry : ActivationTable.entries(table, face)) {
            if (entry.localPos().equals(cell)) {
                return entry.strength();
            }
        }
        return -1;
    }

    @GameTest(template = "redstonecircuit:empty")
    public void aControllersTableTakesItsFirstEntryAndSurvivesASave(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(new BlockPos(1, 2, 1));
        level.setBlock(pos, RCRegistry.REDSTONE_STRUCTURE_BLOCK.defaultBlockState(), 3);
        if (!(level.getBlockEntity(pos) instanceof RedstoneStructureBlockEntity controller)) {
            helper.fail("the placed controller must have a block entity");
            return;
        }
        helper.assertTrue(controller.faces().isEmpty(), "a fresh controller has no table at all");

        // The first entry of an empty table.
        controller.setFaceEntry(Direction.EAST, FIRST_CELL, 9);
        helper.assertTrue(controller.faces().size() == 1, "the first entry must land on the east face");
        helper.assertTrue(strengthOf(controller.faces(), Direction.EAST, FIRST_CELL) == 9,
                "at the strength it was given, got " + strengthOf(controller.faces(), Direction.EAST, FIRST_CELL));

        controller.setFaceEntry(Direction.EAST, SECOND_CELL, 4);
        controller.setFaceEntry(Direction.EAST, FIRST_CELL, 12);
        helper.assertTrue(ActivationTable.entries(controller.faces(), Direction.EAST).size() == 2,
                "setting the same cell twice replaces its strength instead of adding a second entry for it, "
                        + "got " + ActivationTable.entries(controller.faces(), Direction.EAST));
        helper.assertTrue(strengthOf(controller.faces(), Direction.EAST, FIRST_CELL) == 12,
                "and the replacement is the strength that stands");

        controller.setFaceEntry(Direction.UP, FIRST_CELL, 15);
        helper.assertTrue(strengthOf(controller.faces(), Direction.EAST, FIRST_CELL) == 12,
                "a second face does not disturb the first");
        helper.assertTrue(strengthOf(controller.faces(), Direction.UP, FIRST_CELL) == 15,
                "and it carries its own strength");

        // The table is part of what the block reports, so it comes back with the block.
        CompoundTag tag = controller.saveWithFullMetadata(level.registryAccess());
        RedstoneStructureBlockEntity reloaded = new RedstoneStructureBlockEntity(pos.offset(0, 4, 0),
                controller.getBlockState());
        reloaded.loadWithComponents(tag, level.registryAccess());
        helper.assertTrue(reloaded.faces().equals(controller.faces()),
                "the table must survive a save and a load, got " + reloaded.faces());

        controller.clearFace(Direction.UP);
        helper.assertTrue(ActivationTable.entries(controller.faces(), Direction.UP).isEmpty(),
                "clearing a face empties it");
        helper.assertTrue(strengthOf(controller.faces(), Direction.EAST, FIRST_CELL) == 12,
                "and leaves the other faces alone");
        controller.clearFace(Direction.UP);
        helper.assertTrue(controller.faces().size() == 1, "clearing an empty face changes nothing");
        helper.succeed();
    }
}

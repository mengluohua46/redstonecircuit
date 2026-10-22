package com.jiangyuefengyu.redstonecircuit;

import java.util.Optional;

import com.jiangyuefengyu.redstonecircuit.block.StructureBlockEntity;
import com.jiangyuefengyu.redstonecircuit.structure.pocket.PocketSounds;
import com.jiangyuefengyu.redstonecircuit.structure.pocket.PocketSpace;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * <b>A sound made inside a structure is heard at that structure, and not once the structure is away.</b>
 *
 * <p>A structure's blocks run in the pocket dimension, where the ordinary sound path reaches nobody, so
 * {@link PocketSounds} moves the sound to the product block that owns the plot. What is pinned here is the answer that
 * decision rests on: a cell inside a claimed plot belongs to that plot's owner block, a cell outside every plot belongs
 * to nobody, and an owner that is no longer standing in its world (broken, or carried off by a machine) owns nothing -
 * the sound stays silent rather than speaking from where the block used to be.
 *
 * <p>The second test pins the coordinate space rather than the mapping: what the world is told is the owner's cell, not
 * the cell in the pocket the sound was made in. Those are the only two things that decide whether a player hears it —
 * which level sends the packet, and how far away it says the sound is — so a bridge that keeps the pocket's own
 * coordinates would pass a "is it bridged at all" test and still be silent.
 */
public final class StructureSoundBridgeGameTest implements FabricGameTest {

    private static final BlockPos PRODUCT = new BlockPos(2, 2, 2);

    /**
     * A grid slot no test can be holding: the plot table hands out the lowest free index first and holds at most a
     * budget's worth, so a high index is a slot with no record - which is what "outside every plot" has to mean here.
     * The whole suite shares one level, so a coordinate merely far from this structure may be another test's plot.
     */
    private static final int UNCLAIMED_PLOT = 4096;

    @GameTest(template = "redstonecircuit:empty")
    public void aSoundInsideAPlotIsHeardAtItsOwnerBlock(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos productPos = helper.absolutePos(PRODUCT);
        level.setBlock(productPos, RCRegistry.STRUCTURE.defaultBlockState(), 3);
        helper.assertTrue(level.getBlockEntity(productPos) instanceof StructureBlockEntity,
                "the product block has to be there for the plot to have an owner");

        PocketSpace.Plotted claimed = PocketSpace.of(level).claim(3, false, level.dimension(), productPos);
        helper.assertTrue(claimed.allowed(), "a plot has to be claimable here: " + claimed.refusal());
        BlockPos origin = claimed.plot().origin();

        Vec3 inside = Vec3.atCenterOf(origin.offset(1, 1, 1));
        Optional<PocketSounds.Source> heard = PocketSounds.worldSource(level, inside);
        helper.assertTrue(heard.isPresent(), "a sound at " + inside + " is inside the plot, so it has an owner");
        helper.assertTrue(heard.get().pos().equals(productPos),
                "and it is heard at the owner block, got " + heard.get().pos().toShortString());
        helper.assertTrue(heard.get().dimension().equals(level.dimension()),
                "on the owner's own level, got " + heard.get().dimension().location());

        // Outside every plot: a slot this level holds no record for is nobody's sound.
        BlockPos unclaimed = PocketSpace.originOf(UNCLAIMED_PLOT).offset(1, 1, 1);
        helper.assertTrue(PocketSounds.worldSource(level, Vec3.atCenterOf(unclaimed)).isEmpty(),
                "a sound in a slot this level has no plot for belongs to no structure");

        // The owner has gone: silent, not a sound from an empty cell.
        level.setBlock(productPos, Blocks.AIR.defaultBlockState(), 3);
        helper.assertTrue(PocketSounds.worldSource(level, inside).isEmpty(),
                "with the owner block gone the plot's record is stale, and a stale record must not speak");
        helper.succeed();
    }

    @GameTest(template = "redstonecircuit:empty")
    public void aSoundIsHeardFromTheWorldCellNotFromItsPocketCell(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos productPos = helper.absolutePos(PRODUCT);
        level.setBlock(productPos, RCRegistry.STRUCTURE.defaultBlockState(), 3);

        PocketSpace.Plotted claimed = PocketSpace.of(level).claim(3, false, level.dimension(), productPos);
        helper.assertTrue(claimed.allowed(), "a plot has to be claimable here: " + claimed.refusal());
        BlockPos pocketCell = claimed.plot().origin().offset(1, 1, 1);
        helper.assertTrue(!pocketCell.equals(productPos),
                "the cell the sound is made in and the cell it is heard in must differ, or this test could not tell "
                        + "the two coordinate spaces apart");

        Optional<PocketSounds.Source> heard = PocketSounds.worldSource(level, Vec3.atCenterOf(pocketCell));
        helper.assertTrue(heard.isPresent(), "a sound in the plot is bridged at all");
        helper.assertTrue(heard.get().pos().equals(productPos),
                "the world is told the owner's cell (" + productPos.toShortString() + "), never the pocket's ("
                        + pocketCell.toShortString() + ")");
        helper.assertTrue(heard.get().dimension().equals(level.dimension()),
                "and the owner's level is the one that sends the packet: vanilla measures the distance rule there, "
                        + "from the block centre it is handed, so the volume fades over world metres");
        helper.succeed();
    }
}

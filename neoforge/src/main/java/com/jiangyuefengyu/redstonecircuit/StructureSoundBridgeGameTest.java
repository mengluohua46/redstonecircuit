package com.jiangyuefengyu.redstonecircuit;

import java.util.Optional;

import com.jiangyuefengyu.redstonecircuit.block.StructureBlockEntity;
import com.jiangyuefengyu.redstonecircuit.structure.pocket.PocketSounds;
import com.jiangyuefengyu.redstonecircuit.structure.pocket.PocketSpace;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * <b>A sound made inside a structure is heard at that structure, and not once the structure is away.</b>
 *
 * <p>A structure's blocks run in the pocket dimension, where the ordinary sound path reaches nobody, so
 * {@link PocketSounds} moves the sound to the product block that owns the plot. What is pinned here is the answer that
 * decision rests on: a cell inside a claimed plot belongs to that plot's owner block, a cell outside every plot belongs
 * to nobody, and an owner that is no longer standing in its world (broken, or carried off by a machine) owns nothing -
 * the sound stays silent rather than speaking from where the block used to be.
 */
@GameTestHolder(RedstoneCircuit.MODID)
@PrefixGameTestTemplate(false)
public final class StructureSoundBridgeGameTest {

    private static final BlockPos PRODUCT = new BlockPos(2, 2, 2);

    @GameTest(template = "empty")
    public void aSoundInsideAPlotIsHeardAtItsOwnerBlock(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos productPos = helper.absolutePos(PRODUCT);
        level.setBlock(productPos, RCRegistry.STRUCTURE.get().defaultBlockState(), 3);
        helper.assertTrue(level.getBlockEntity(productPos) instanceof StructureBlockEntity,
                "the product block has to be there for the plot to have an owner");

        PocketSpace.Plotted claimed = PocketSpace.of(level).claim(3, false, level.dimension(), productPos);
        helper.assertTrue(claimed.plot() != null, "a plot has to be claimable here: " + claimed.refusal());
        BlockPos origin = claimed.plot().origin();

        Vec3 inside = Vec3.atCenterOf(origin.offset(1, 1, 1));
        Optional<PocketSounds.Source> heard = PocketSounds.worldSource(level, inside);
        helper.assertTrue(heard.isPresent(), "a sound at " + inside + " is inside the plot, so it has an owner");
        helper.assertTrue(heard.get().pos().equals(productPos),
                "and it is heard at the owner block, got " + heard.get().pos().toShortString());
        helper.assertTrue(heard.get().dimension().equals(level.dimension()),
                "on the owner's own level, got " + heard.get().dimension().location());

        // Outside every plot: a coordinate the grid does not hand to this plot is nobody's sound.
        helper.assertTrue(PocketSounds.worldSource(level, Vec3.atCenterOf(productPos.north(200))).isEmpty(),
                "a sound outside every claimed plot belongs to no structure");

        // The owner has gone: silent, not a sound from an empty cell.
        level.setBlock(productPos, Blocks.AIR.defaultBlockState(), 3);
        helper.assertTrue(PocketSounds.worldSource(level, inside).isEmpty(),
                "with the owner block gone the plot's record is stale, and a stale record must not speak");
        helper.succeed();
    }
}

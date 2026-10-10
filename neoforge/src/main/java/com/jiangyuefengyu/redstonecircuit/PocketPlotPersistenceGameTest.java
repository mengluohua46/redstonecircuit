package com.jiangyuefengyu.redstonecircuit;

import java.util.UUID;

import com.jiangyuefengyu.redstonecircuit.block.StructureBlockEntity;
import com.jiangyuefengyu.redstonecircuit.structure.IoMode;
import com.jiangyuefengyu.redstonecircuit.structure.pocket.PocketPlots;
import com.jiangyuefengyu.redstonecircuit.structure.pocket.PocketSpace;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * What a restart does to a plot: does the claim come back, and does a claim with no owner ever go away.
 *
 * <p>A restart cannot be performed inside a running server, so this test does the next best thing and
 * forgets everything that is held in memory ({@code PocketSpace.forgetAll}) while leaving the saved table
 * where it is. That is precisely the state a restart leaves behind, and it is what the restore path has to
 * handle: the allocator's grid, the budget and the tickets are rebuilt, and the repair sweep runs.
 *
 * <p>It runs in the test level rather than the pocket because the machinery is keyed by level and the
 * harness has no pocket dimension - the sweep, the reserve and the table are the same code either way.
 */
@GameTestHolder(RedstoneCircuit.MODID)
@PrefixGameTestTemplate(false)
public final class PocketPlotPersistenceGameTest {

    private static final BlockPos PRODUCT = new BlockPos(2, 2, 2);

    @GameTest(template = "empty", timeoutTicks = 200)
    public void aPlotSurvivesARestartAndIsReclaimedWhenItsOwnerIsGone(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos ownerPos = helper.absolutePos(PRODUCT);
        helper.setBlock(PRODUCT, RCRegistry.STRUCTURE.get());
        if (!(level.getBlockEntity(ownerPos) instanceof StructureBlockEntity product)) {
            helper.fail("the placed structure must have a block entity");
            return;
        }
        product.setStructure(UUID.randomUUID(), "persisted", new Vec3i(3, 2, 3), IoMode.ACTIVATION_FACES, 0);

        PocketSpace space = PocketSpace.of(level);
        PocketSpace.Plotted claimed = space.claim(3, false, level.dimension(), ownerPos);
        helper.assertTrue(claimed.allowed(), "claiming a cell must work: " + claimed.refusal());
        int plot = claimed.plot().index();
        product.setPlotIndex(plot);

        // A restart: everything in memory goes, the level's saved data stays.
        PocketSpace.forgetAll();
        PocketSpace after = PocketSpace.of(level);

        helper.assertTrue(after.plots().containsKey(plot),
                "the plot must come back from the save, plot table now " + after.plots().keySet());
        helper.assertTrue(after.allocator().isLive(plot),
                "and its cell must be reserved, so nothing else is given it");
        helper.assertTrue(after.budget().usedPockets() >= 1,
                "and it must be charged to the budget again, or the limits would drift upwards");
        PocketPlots.Entry entry = PocketPlots.get(level).entry(plot);
        helper.assertTrue(entry != null && ownerPos.equals(entry.ownerPos()),
                "with its owner recorded, which is how it can be reclaimed later");

        // The cell a restored plot sits in must not be handed out again.
        PocketSpace.Plotted second = after.claim(3, false, level.dimension(), ownerPos);
        helper.assertTrue(second.allowed(), second.refusal());
        helper.assertTrue(second.plot().index() != plot,
                "a restored plot's cell must never be claimed twice, got " + second.plot().index());

        // Now the owner stops claiming it - what a removal nothing announced leaves behind.
        product.setPlotIndex(-1);
        PocketSpace.forgetAll();
        PocketSpace swept = PocketSpace.of(level);

        helper.assertTrue(!swept.plots().containsKey(plot),
                "a plot whose owner no longer claims it must be reclaimed by the sweep, plots now "
                        + swept.plots().keySet());
        helper.assertTrue(PocketPlots.get(level).entry(plot) == null,
                "and forgotten in the saved table, or the next restart would bring it back");
        helper.assertTrue(!swept.allocator().isLive(plot), "with its cell free again");

        // Tidy up: the second claim is still live, and this level is shared with the other tests.
        PocketSpace.of(level).release(second.plot().index());
        helper.succeed();
    }
}

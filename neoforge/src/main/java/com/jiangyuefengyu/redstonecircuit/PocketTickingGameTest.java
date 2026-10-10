package com.jiangyuefengyu.redstonecircuit;

import com.jiangyuefengyu.redstonecircuit.block.StructureBlockEntity;
import com.jiangyuefengyu.redstonecircuit.structure.PlotAllocator;
import com.jiangyuefengyu.redstonecircuit.structure.pocket.PocketSpace;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * A region we ticket is ticked by vanilla, and the same region without our ticket is not.
 *
 * <p>The circuit is lever → dust → <b>repeater</b> → lamp, and the repeater is what makes the claim testable:
 * plain dust passes power on through an ordinary neighbour update, while a repeater schedules a block tick, and
 * a block tick only runs in a chunk that is in block-ticking range ({@code ServerLevel#shouldTickBlocksAt}).
 * That is exactly what a plot ticket buys.
 *
 * <p>The test runs in two halves on the same circuit: with no ticket anywhere the lamp must still be dark, and
 * the dark lamp only means something because the region is not in block-ticking range to begin with; then the
 * ticket goes on and the lamp must light. The far coordinates are 300,000 blocks out, in a chunk nothing else
 * has touched - loaded but <em>not</em> ticking, since the block writes load the chunk and loading is not
 * ticking.
 */
@GameTestHolder(RedstoneCircuit.MODID)
@PrefixGameTestTemplate(false)
public final class PocketTickingGameTest {

    /** Far outside anything the test level loads or ticks: x=300000 is chunk 18750, z the same. */
    private static final BlockPos FAR = new BlockPos(300000, 100, 300000);

    /** The ticket key this test uses. It is not a plot index - no plot was claimed for this region. */
    private static final int TEST_TICKET = 1;

    private static boolean lampIsLit(ServerLevel level, BlockPos lampPos) {
        return level.getBlockState(lampPos).getValue(BlockStateProperties.LIT);
    }

    private static int wirePower(ServerLevel level, BlockPos dustPos) {
        return level.getBlockState(dustPos).getValue(RedStoneWireBlock.POWER);
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public void aTicketedRegionIsTickedAndAnUnticketedOneIsNot(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        PocketSpace space = PocketSpace.of(level);

        BlockPos leverPos = FAR;
        BlockPos dustPos = FAR.east();
        BlockPos repeaterPos = FAR.east(2);
        BlockPos lampPos = FAR.east(3);

        // The platform is what a lever and dust need to stand on; everything is inside one chunk column.
        for (int dx = 0; dx <= 3; dx++) {
            level.setBlock(FAR.offset(dx, -1, 0), Blocks.STONE.defaultBlockState(), 3);
        }
        BlockState lever = Blocks.LEVER.defaultBlockState()
                .setValue(FaceAttachedHorizontalDirectionalBlock.FACE, AttachFace.FLOOR)
                .setValue(HorizontalDirectionalBlock.FACING, Direction.NORTH);
        level.setBlock(leverPos, lever, 3);
        level.setBlock(dustPos, Blocks.REDSTONE_WIRE.defaultBlockState(), 3);
        level.setBlock(repeaterPos, Blocks.REPEATER.defaultBlockState()
                .setValue(HorizontalDirectionalBlock.FACING, Direction.WEST), 3);
        level.setBlock(lampPos, Blocks.REDSTONE_LAMP.defaultBlockState(), 3);

        final long chunk = ChunkPos.asLong(FAR.getX() >> 4, FAR.getZ() >> 4);
        final int columns = PlotAllocator.columnsNeeded(4, false);

        helper.assertTrue(level.getBlockState(lampPos).is(Blocks.REDSTONE_LAMP)
                        && level.getBlockState(repeaterPos).is(Blocks.REPEATER),
                "the circuit has to be in the world before anything can be said about it: a chunk far "
                        + "from the test structures must still accept blocks");

        helper.startSequence()
                .thenExecute(() -> {
                    // Throwing the lever is a plain neighbour update - it needs no tick, which is what
                    // makes the wire the control for "the world is live at all".
                    level.setBlock(leverPos, level.getBlockState(leverPos).cycle(LeverBlock.POWERED), 3);
                    level.updateNeighborsAt(leverPos, Blocks.LEVER);
                })
                .thenIdle(2)
                .thenExecute(() -> {
                    helper.assertTrue(wirePower(level, dustPos) > 0,
                            "the lever must power the dust, or nothing later in this test means anything");
                    // The real negative control is this, not the dark lamp: if the region is in
                    // block-ticking range anyway, nothing below distinguishes a ticket from no ticket.
                    helper.assertFalse(level.shouldTickBlocksAt(chunk),
                            "the far region must NOT be in block-ticking range to start with, otherwise "
                                    + "this test certifies nothing");
                    // And this is what makes the dark lamp meaningful: the repeater is waiting for a
                    // block tick it cannot get. A repeater that never scheduled one would also leave the
                    // lamp dark, and would prove nothing about ticking.
                    helper.assertTrue(level.getBlockTicks().hasScheduledTick(repeaterPos, Blocks.REPEATER),
                            "the repeater must be waiting for a block tick");
                })
                .thenIdle(40)
                .thenExecute(() -> helper.assertFalse(lampIsLit(level, lampPos),
                        "WITHOUT a ticket the repeater must not fire: a repeater waits for a block tick, "
                                + "and this region is loaded but not ticking"))
                .thenExecute(() -> space.ticket(TEST_TICKET, FAR, columns, true))
                // The ticket is registered immediately, but the chunk's level is propagated by the
                // distance manager on the level's own tick, so "is it ticking now" is only a fair
                // question a few ticks later.
                .thenIdle(5)
                .thenExecute(() -> helper.assertTrue(level.shouldTickBlocksAt(chunk),
                        "the plot ticket must put the chunk in block-ticking range (ticket level 31)")
                        )
                // A wait rather than a single check after a fixed idle: the repeater's pending tick has to be
                // collected, and a scheduled tick is only collected for a chunk whose full status has finished
                // propagating - which depends on the chunk manager's own tick, not on how long the test idles.
                .thenWaitUntil(() -> helper.assertTrue(lampIsLit(level, lampPos),
                        "with the plot ticket on, the repeater's pending tick must run: the redstone inside a "
                                + "structure has to keep working. shouldTick="
                                + level.shouldTickBlocksAt(chunk)
                                + " pendingTick=" + level.getBlockTicks()
                                        .hasScheduledTick(repeaterPos, Blocks.REPEATER)
                                + " lamp=" + level.getBlockState(lampPos)))
                .thenExecute(() -> space.ticket(TEST_TICKET, FAR, columns, false))
                .thenSucceed();
    }

    @GameTest(template = "empty")
    public void aPlotIsClaimedAndReleased(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        PocketSpace space = PocketSpace.of(level);
        // Charged as a delta, because a GameTest batch is not alone in its level: another batch runs beside it
        // and may hold plots of its own, so an absolute count would assert about the scheduler rather than about
        // the budget.
        int pocketsBefore = space.budget().usedPockets();
        int columnsBefore = space.budget().usedColumns();

        PocketSpace.Plotted claimed = space.claim(48, true, level.dimension(), BlockPos.ZERO);
        helper.assertTrue(claimed.allowed(),
                "a 48³ structure with a context shell is 16 columns and must fit the default budget: "
                        + claimed.refusal());
        int index = claimed.plot().index();
        helper.assertTrue(claimed.plot().columns() == 16, "16 columns, as the design's table says");
        helper.assertTrue(space.budget().usedColumns() == columnsBefore + 16
                        && space.budget().usedPockets() == pocketsBefore + 1,
                "the budget must have been charged exactly once: columns " + columnsBefore + "->"
                        + space.budget().usedColumns() + ", pockets " + pocketsBefore + "->"
                        + space.budget().usedPockets());
        helper.assertTrue(space.allocator().isLive(index), "and the cell must be marked live");

        helper.assertTrue(space.release(index), "a live plot must be releasable");
        helper.assertTrue(space.budget().usedColumns() == columnsBefore
                        && space.budget().usedPockets() == pocketsBefore,
                "releasing gives both counters back");
        helper.assertTrue(!space.allocator().isLive(index), "and the cell stops being live");
        helper.assertTrue(!space.release(index), "releasing it twice is not a state change");
        helper.succeed();
    }

    /**
     * A full pocket hands back the plot nothing is using, and never the one its owner is working.
     *
     * <p>The budget makes room instead of refusing a claim, so what is tested here is what the reclaim must
     * never get wrong: the plot whose owner is standing there and working it is not the one handed back. The
     * refusal survives only for the case where nothing can be handed back, which is {@code PocketBudget}'s own
     * unit test.
     */
    @GameTest(template = "empty")
    public void thePocketHandsBackWhatNothingIsUsing(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        PocketSpace space = PocketSpace.of(level);

        BlockPos abandoned = helper.absolutePos(new BlockPos(1, 2, 1));
        BlockPos kept = helper.absolutePos(new BlockPos(2, 2, 1));
        level.setBlock(abandoned, RCRegistry.STRUCTURE.get().defaultBlockState(), 3);
        level.setBlock(kept, RCRegistry.STRUCTURE.get().defaultBlockState(), 3);
        if (!(level.getBlockEntity(abandoned) instanceof StructureBlockEntity abandonedProduct)
                || !(level.getBlockEntity(kept) instanceof StructureBlockEntity keptProduct)) {
            helper.fail("both claims need their owner product block");
            return;
        }
        PocketSpace.Plotted first = space.claim(16, true, level.dimension(), abandoned);
        PocketSpace.Plotted second = space.claim(16, true, level.dimension(), kept);
        if (!first.allowed() || !second.allowed()) {
            helper.fail("the two claims this test needs must fit: " + first.refusal() + " / " + second.refusal());
            return;
        }
        int abandonedPlot = first.plot().index();
        int keptPlot = second.plot().index();
        abandonedProduct.setPlotIndex(abandonedPlot);
        keptProduct.setPlotIndex(keptPlot);
        // The way its own tick works this plot, every tick: this is what makes it "in use" rather than "idle".
        space.injection(keptPlot);

        java.util.Set<Integer> before = new java.util.HashSet<>(space.plots().keySet());
        try {
            // The block behind the first plot goes away without a word - a command, an explosion, another
            // machine's leftovers.
            level.setBlock(abandoned, Blocks.AIR.defaultBlockState(), 3);
            helper.assertTrue(space.handBackOnePlot(),
                    "a plot whose block is not there any more is what a pocket hands back first");
            java.util.Set<Integer> handedBack = new java.util.HashSet<>(before);
            handedBack.removeAll(space.plots().keySet());
            helper.assertTrue(handedBack.size() == 1,
                    "exactly one plot is handed back per call, and it has to be one that was there: "
                            + handedBack.size());
            for (int index : handedBack) {
                helper.assertTrue(!space.hasPlot(index),
                        "and it stops being a plot of this pocket in the saved table as well as in the grid: plot "
                                + index);
            }
            helper.assertTrue(space.hasPlot(keptPlot),
                    "while the plot whose owner is standing there and working it is left alone - a reclaim that took "
                            + "this one would be the leak this guard prevents");
        } finally {
            // This level is shared: whatever happens above, the pocket goes back the way it was found.
            level.setBlock(abandoned, Blocks.AIR.defaultBlockState(), 3);
            level.setBlock(kept, Blocks.AIR.defaultBlockState(), 3);
            space.release(abandonedPlot);
            space.release(keptPlot);
        }
        helper.assertTrue(!space.hasPlot(abandonedPlot) && !space.hasPlot(keptPlot),
                "and the two plots this test made are gone from the pocket it made them in - the budget itself is not "
                        + "compared with where it started, because this level is shared and a claim made here may well "
                        + "have handed somebody else's abandoned plot back to make room (which is the point)");
        helper.succeed();
    }

    /**
     * A plot a materialise is filling right now is not handed back, however its owner looks.
     *
     * <p>The other half of {@link #thePocketHandsBackWhatNothingIsUsing}, and the reason that guard is a marker
     * around the write rather than an idle window: during a materialise the block the plot was claimed for has
     * <b>not learned its number yet</b> ({@code setPlotIndex} runs after {@code PocketMaterializer#materializeInto}
     * returns), so for the whole of the write it looks exactly like a block that has gone - and a structure nested
     * inside the payload asks for a plot of its own in exactly that window.
     *
     * <p>The two halves pull in opposite directions: the plot being written survives a reclaim pass, while an
     * owner that is <b>really</b> gone is still the first thing a full pocket hands back.
     */
    @GameTest(template = "empty")
    public void aPlotBeingWrittenIsNotHandedBack(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        PocketSpace space = PocketSpace.of(level);

        BlockPos ownerAt = helper.absolutePos(new BlockPos(3, 2, 1));
        level.setBlock(ownerAt, RCRegistry.STRUCTURE.get().defaultBlockState(), 3);
        if (!(level.getBlockEntity(ownerAt) instanceof StructureBlockEntity product)) {
            helper.fail("the claim this test needs an owner product block for");
            return;
        }
        PocketSpace.Plotted claimed = space.claim(16, true, level.dimension(), ownerAt);
        if (!claimed.allowed()) {
            helper.fail("the claim this test needs must fit the budget: " + claimed.refusal());
            return;
        }
        int plot = claimed.plot().index();
        try {
            // The state a materialise is in, made as harsh as it can be: the plot is claimed, the block has not learned
            // its number yet, and the block that will own it is not readable at all any more.
            product.setPlotIndex(plot);
            level.setBlock(ownerAt, Blocks.AIR.defaultBlockState(), 3);
            space.beginWriting(plot);

            space.handBackOnePlot();

            helper.assertTrue(space.hasPlot(plot),
                    "a plot being written right now must still be a plot afterwards, whatever else the reclaim pass "
                            + "took instead: taking it wipes the cells the materialise has already written and the "
                            + "structure inside them");
            space.endWriting(plot);
        } finally {
            // This level is shared: whatever happens above, the pocket goes back the way it was found.
            level.setBlock(ownerAt, Blocks.AIR.defaultBlockState(), 3);
            space.endWriting(plot);
            space.release(plot);
        }
        helper.succeed();
    }
}

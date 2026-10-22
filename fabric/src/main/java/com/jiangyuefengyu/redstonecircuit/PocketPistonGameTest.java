package com.jiangyuefengyu.redstonecircuit;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.jiangyuefengyu.redstonecircuit.block.StructureBlockEntity;
import com.jiangyuefengyu.redstonecircuit.structure.IoMode;
import com.jiangyuefengyu.redstonecircuit.structure.StructureLiveView;
import com.jiangyuefengyu.redstonecircuit.structure.pocket.PocketSpace;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestSequence;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * <b>A piston inside a plot: does the animation finish, and does the pocket keep a placeholder?</b>
 *
 * <p>Everything else in this mod reads those cells through
 * {@link com.jiangyuefengyu.redstonecircuit.structure.StructureLiveView}, and the sampler treats a
 * {@code moving_piston} placeholder as "no news" - a cell that keeps the value it had. If vanilla finishes the animation
 * in the ticks it is documented to, a placeholder is a two-tick affair; but a plot that keeps one leaves a cell that is
 * wrong <em>for ever</em>, because both the diff and the periodic resend read the same filtered value. The test drives
 * five pushes in a row, with a plain piston and a plot ticket as the only mod machinery involved.
 */
public final class PocketPistonGameTest implements FabricGameTest {

    /** A piston's own cell, the cell the head reaches, and the cell a pushed block lands in. */
    private static final BlockPos BASE = new BlockPos(2, 1, 2);
    private static final BlockPos HEAD = new BlockPos(2, 2, 2);
    private static final BlockPos ABOVE = new BlockPos(2, 3, 2);
    private static final BlockPos POWER = new BlockPos(2, 1, 1);

    private static final int PLOT_SIZE = 4;
    private static final int CYCLES = 5;
    private static final int OBSERVE_TICKS = 10;

    private static String name(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        String path = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
        if (state.hasProperty(BlockStateProperties.EXTENDED)) {
            return path + (state.getValue(BlockStateProperties.EXTENDED) ? ":ext" : ":ret");
        }
        return path;
    }

    /** Where any placeholder is, anywhere in the plot: the pocket is supposed to have none once it settles. */
    private static List<String> placeholders(ServerLevel level, BlockPos origin) {
        List<String> found = new ArrayList<>();
        for (int y = 0; y < PLOT_SIZE; y++) {
            for (int z = 0; z < PLOT_SIZE; z++) {
                for (int x = 0; x < PLOT_SIZE; x++) {
                    BlockPos pos = origin.offset(x, y, z);
                    if (level.getBlockState(pos).is(Blocks.MOVING_PISTON)) {
                        found.add(x + "," + y + "," + z);
                    }
                }
            }
        }
        return found;
    }

    private static String snapshot(ServerLevel level, BlockPos origin) {
        return "base=" + name(level, origin.offset(BASE))
                + " head=" + name(level, origin.offset(HEAD))
                + " above=" + name(level, origin.offset(ABOVE))
                + " placeholders=" + placeholders(level, origin);
    }

    /**
     * Claims a plot the way a placed product does: with a real owner block that points back at it. The owner matters
     * because another test in the suite sweeps plots whose owner is not a product block, and it is also what makes the
     * plot tick at all.
     */
    private static PocketSpace.Plotted claimWithOwner(GameTestHelper helper, ServerLevel level, PocketSpace space) {
        BlockPos owner = helper.absolutePos(new BlockPos(1, 2, 1));
        level.setBlock(owner, RCRegistry.STRUCTURE.defaultBlockState(), 3);
        if (!(level.getBlockEntity(owner) instanceof StructureBlockEntity product)) {
            helper.fail("the owner product block must have a block entity");
            return null;
        }
        product.setStructure(UUID.randomUUID(), "piston test owner", new Vec3i(4, 4, 4), IoMode.MANUAL, 0);
        PocketSpace.Plotted claimed = space.claim(PLOT_SIZE, false, level.dimension(), owner);
        if (claimed.allowed()) {
            product.setPlotIndex(claimed.plot().index());
        }
        return claimed;
    }

    // A round of its own: every test in a batch shares one test world, and a plot is handed back - blocks and
    // all - when the plot board thinks nobody is using it. Too many claims in one round is enough to lose this
    // one mid-push, which reads as "the piston never finished" when the piston was fine. Alone in a batch, the
    // board is this test's own while it runs.
    @GameTest(template = "redstonecircuit:empty", timeoutTicks = 400, batch = "pocket_piston")
    public void aPistonInsideAPlotFinishesItsAnimationEveryTime(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        PocketSpace space = PocketSpace.of(level);
        PocketSpace.Plotted claimed = claimWithOwner(helper, level, space);
        if (claimed == null || !claimed.allowed()) {
            helper.fail("the budget refused a 4³ plot, so nothing here could be tested: "
                    + (claimed == null ? "no owner block" : claimed.refusal()));
            return;
        }
        int plot = claimed.plot().index();
        BlockPos origin = PocketSpace.originOf(plot);

        for (int y = -1; y <= PLOT_SIZE; y++) {
            for (int z = -1; z <= PLOT_SIZE; z++) {
                for (int x = -1; x <= PLOT_SIZE; x++) {
                    level.setBlock(origin.offset(x, y, z), Blocks.AIR.defaultBlockState(), 3);
                }
            }
        }
        level.setBlock(origin.offset(BASE), Blocks.STICKY_PISTON.defaultBlockState()
                .setValue(BlockStateProperties.FACING, Direction.UP), 3);
        level.setBlock(origin.offset(HEAD), Blocks.WHITE_WOOL.defaultBlockState(), 3);

        // Every tick of every cycle, so the timeline can be read rather than guessed at.
        StringBuilder timeline = new StringBuilder();
        String[] lastLine = { snapshot(level, origin) };
        timeline.append("\n  t0 ").append(lastLine[0]);
        // Every move the sampler would report, in the form the client is told it: the slide needs to know where each
        // moving block came from.
        List<String> movesSeen = new ArrayList<>();

        GameTestSequence sequence = helper.startSequence()
                .thenExecute(() -> helper.assertTrue(level.getBlockState(origin.offset(BASE)).is(Blocks.STICKY_PISTON),
                        "the piston has to be in the plot before anything can be said about it"))
                // <b>A piston moves through its own block entity</b>, and a block entity is only ticked once its chunk
                // is entity-ticking - a plot's ticket gets there on the distance manager's own tick, not the instant it
                // is claimed. A push that starts inside that window leaves its moving-piston placeholder standing for
                // as long as the window lasts, so the plot is waited out here rather than assumed ready: the sampler's
                // side of it - "a placeholder must not become a lie that nothing can correct" - is asserted in
                // StructureLiveViewTest.
                .thenWaitUntil(() -> helper.assertTrue(level.isPositionEntityTicking(origin.offset(BASE)),
                        "the plot's chunk has to be entity-ticking before a piston in it can finish a move"));
        // The plot is held for the whole test, and no owner block can re-materialise these raw blocks, so every tick
        // says the plot is in use: a plot untouched for IDLE_EVICT_TICKS is one the reclaim order treats as nobody's,
        // and handing this one back clears the very blocks the timeline is made of.
        for (int cycle = 0; cycle < CYCLES; cycle++) {
            final int at = cycle;
            sequence = sequence
                    .thenExecute(() -> {
                        level.setBlock(origin.offset(POWER), Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);
                        timeline.append("\n  --- cycle ").append(at).append(" power on");
                    });
            for (int tick = 0; tick < OBSERVE_TICKS; tick++) {
                sequence = sequence.thenIdle(1).thenExecute(() -> {
                    space.touch(plot);
                    String line = snapshot(level, origin);
                    if (!line.equals(lastLine[0])) {
                        timeline.append("\n  ").append(line);
                        RCConfig.LOGGER.info("[redstonecircuit] pocket piston: {}", line);
                        lastLine[0] = line;
                    }
                    movesSeen.addAll(movesHere(level, origin));
                });
            }
            sequence = sequence.thenExecute(() -> {
                // The push is over: the head must be out, the block must have moved, and the pocket must be
                // holding no placeholder at all.
                helper.assertTrue(level.getBlockState(origin.offset(HEAD)).is(Blocks.PISTON_HEAD),
                        "the head must be out while the piston is powered, got "
                                + snapshot(level, origin) + timeline);
                helper.assertTrue(level.getBlockState(origin.offset(ABOVE)).is(Blocks.WHITE_WOOL),
                        "the block must have been pushed up, got " + snapshot(level, origin) + timeline);
                helper.assertTrue(placeholders(level, origin).isEmpty(),
                        "a finished push must leave no placeholder behind, got "
                                + placeholders(level, origin) + timeline);
                level.setBlock(origin.offset(POWER), Blocks.AIR.defaultBlockState(), 3);
                timeline.append("\n  --- cycle ").append(at).append(" power off");
            });
            for (int tick = 0; tick < OBSERVE_TICKS; tick++) {
                sequence = sequence.thenIdle(1).thenExecute(() -> {
                    space.touch(plot);
                    String line = snapshot(level, origin);
                    if (!line.equals(lastLine[0])) {
                        timeline.append("\n  ").append(line);
                        RCConfig.LOGGER.info("[redstonecircuit] pocket piston: {}", line);
                        lastLine[0] = line;
                    }
                    movesSeen.addAll(movesHere(level, origin));
                });
            }
            sequence = sequence.thenExecute(() -> {
                helper.assertTrue(level.getBlockState(origin.offset(HEAD)).is(Blocks.WHITE_WOOL),
                        "the block must come back to where it started, got " + snapshot(level, origin) + timeline);
                helper.assertTrue(placeholders(level, origin).isEmpty(),
                        "a finished retraction must leave no placeholder behind either - a placeholder that stays is "
                                + "a cell the live view can never report again, got "
                                + placeholders(level, origin) + timeline);
            });
        }
        sequence.thenExecute(() -> {
            // What the client is told a moving cell is, and where it comes from: these three are what the slide is
            // drawn from, and the two directions together prove the offset's sign is the way the block is travelling
            // (the same code path for pushing and pulling).
            helper.assertTrue(movesSeen.contains("white_wool from (0,-1,0)"),
                    "a block being pushed up must be reported as moving from the cell below: " + movesSeen);
            helper.assertTrue(movesSeen.contains("white_wool from (0,1,0)"),
                    "and a block being pulled back down as moving from the cell above: " + movesSeen);
            helper.assertTrue(movesSeen.contains("piston_head from (0,-1,0)"),
                    "and the arm itself as growing out of the piston: " + movesSeen);
        }).thenExecute(() -> space.release(plot)).thenSucceed();
    }

    /** Every cell of this plot that is mid-move right now, as the client would be told about it. */
    private static List<String> movesHere(ServerLevel level, BlockPos origin) {
        List<String> seen = new ArrayList<>();
        for (BlockPos cell : List.of(origin.offset(HEAD), origin.offset(ABOVE), origin.offset(BASE))) {
            if (!level.getBlockState(cell).is(Blocks.MOVING_PISTON)) {
                continue;
            }
            int[] moving = StructureBlockEntity.movingRecord(level, cell);
            if (moving == null) {
                continue;
            }
            BlockState moved = Block.stateById(moving[0]);
            seen.add(BuiltInRegistries.BLOCK.getKey(moved.getBlock()).getPath() + " from ("
                    + StructureLiveView.fromX(moving[1]) + "," + StructureLiveView.fromY(moving[1]) + ","
                    + StructureLiveView.fromZ(moving[1]) + ")");
        }
        return seen;
    }

    @GameTest(template = "redstonecircuit:empty", timeoutTicks = 200)
    public void aPlaceholderThatNeverFinishesIsFinishedForIt(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        PocketSpace space = PocketSpace.of(level);
        PocketSpace.Plotted claimed = claimWithOwner(helper, level, space);
        if (claimed == null || !claimed.allowed()) {
            helper.fail("the budget refused a 4³ plot, so nothing here could be tested: "
                    + (claimed == null ? "no owner block" : claimed.refusal()));
            return;
        }
        int plot = claimed.plot().index();
        BlockPos origin = PocketSpace.originOf(plot);
        BlockPos stuck = origin.offset(1, 1, 1);

        // A placeholder only ever resolves through its own block entity, so a cell whose block entity is gone is a
        // move stranded mid-flight - the one shape vanilla can never finish by itself.
        level.setBlock(stuck, Blocks.MOVING_PISTON.defaultBlockState(), 3);
        level.removeBlockEntity(stuck);
        helper.assertTrue(level.getBlockState(stuck).is(Blocks.MOVING_PISTON),
                "the placeholder has to be in the plot before anything can be said about it");

        long[] since = new long[PLOT_SIZE * PLOT_SIZE * PLOT_SIZE];
        long now = level.getGameTime();
        Vec3i size = new Vec3i(PLOT_SIZE, PLOT_SIZE, PLOT_SIZE);
        helper.assertTrue(StructureBlockEntity.finishStuckMoves(level, origin, size, since, now) == 0,
                "a placeholder seen for the first time is a move in progress, not a stuck one");
        helper.assertTrue(StructureBlockEntity.finishStuckMoves(level, origin, size, since,
                        now + StructureBlockEntity.STUCK_PLACEHOLDER_TICKS - 1) == 0,
                "and it is left alone right up to its deadline");
        helper.assertTrue(StructureBlockEntity.finishStuckMoves(level, origin, size, since,
                        now + StructureBlockEntity.STUCK_PLACEHOLDER_TICKS) == 1,
                "past the deadline the move is finished for it");
        helper.assertTrue(!level.getBlockState(stuck).is(Blocks.MOVING_PISTON),
                "so the cell is a cell again rather than a placeholder, got " + level.getBlockState(stuck));
        helper.assertTrue(placeholders(level, origin).isEmpty(),
                "and nothing else in the plot was touched: " + placeholders(level, origin));
        space.release(plot);
        helper.succeed();
    }
}

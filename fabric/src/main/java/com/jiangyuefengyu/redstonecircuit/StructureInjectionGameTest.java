package com.jiangyuefengyu.redstonecircuit;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.jiangyuefengyu.redstonecircuit.block.StructureBlockEntity;
import com.jiangyuefengyu.redstonecircuit.structure.ActivationTable;
import com.jiangyuefengyu.redstonecircuit.structure.IoMode;
import com.jiangyuefengyu.redstonecircuit.structure.pocket.PocketSpace;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * The activation-face injection, as far as a GameTest can see it.
 *
 * <p>注入格用表的强度回答信号查询，旁边原版的粉尘重算并点亮灯，不属于本 plot 的格子一律不答；这些都在测试关卡里
 * 认领的 plot 上运行，因为 {@code PocketSpace} 刻意与维度无关，不需要口袋维度。
 *
 * <p>需要真实维度的那一半 - 已放置的产物方块读自己的六面并推入它的 plot - 走不到: {@code PocketMaterializer} 在没有
 * {@code redstonecircuit:structure_space} 时拒绝，而这套 harness 无法承载数据包维度。那条链是真实服务器上的
 * {@code /rc pocket injecttest} 判定。
 */
public final class StructureInjectionGameTest implements FabricGameTest {

    /** What the one table entry pushes. Deliberately neither 15 nor 1: a wrong answer would be obvious. */
    private static final int STRENGTH = 9;

    private static Map<Direction, List<ActivationTable.FaceEntry>> table(BlockPos cell, int strength) {
        return Map.of(Direction.EAST, List.of(new ActivationTable.FaceEntry(cell, strength)));
    }

    /** A signal vector with only the east face energized - what the controller's east face would read. */
    private static int[] eastFaceEnergized() {
        int[] signals = new int[Direction.values().length];
        signals[Direction.EAST.ordinal()] = 15;
        return signals;
    }

    private static int[] noFaceEnergized() {
        return new int[Direction.values().length];
    }

    /** The plot as the design's example has it: the injected cell, the dust beside it, and a lamp. It clears a little
     * more than it builds, because a plot cell is handed out again after a release without its blocks being removed. */
    private static void build(ServerLevel level, BlockPos cell) {
        for (int x = -1; x <= 7; x++) {
            for (int z = -1; z <= 1; z++) {
                for (int y = -1; y <= 2; y++) {
                    level.setBlock(cell.offset(x, y, z), Blocks.AIR.defaultBlockState(), 3);
                }
            }
        }
        for (int x = 0; x <= 1; x++) {
            level.setBlock(cell.offset(x, -1, 0), Blocks.STONE.defaultBlockState(), 3);
        }
        level.setBlock(cell, Blocks.REDSTONE_WIRE.defaultBlockState(), 3);
        level.setBlock(cell.offset(1, 0, 0), Blocks.REDSTONE_WIRE.defaultBlockState(), 3);
        level.setBlock(cell.offset(2, 0, 0), Blocks.REDSTONE_LAMP.defaultBlockState(), 3);
    }

    private static int dustPower(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.is(Blocks.REDSTONE_WIRE) ? state.getValue(RedStoneWireBlock.POWER) : -1;
    }

    /** Claims a plot the way a placed product does: with a real owner block that points back at it. The owner is not
     * decoration - a restore sweeps plots whose owner does not claim them, so a test that claimed one with a made-up
     * owner position would be at the mercy of whichever other test runs beside it. The owner's table is left empty on
     * purpose: its entity ticks, and an empty table makes it do nothing, so this test drives the ledger itself. */
    private static PocketSpace.Plotted claimWithOwner(GameTestHelper helper, ServerLevel level,
                                                     PocketSpace space) {
        BlockPos owner = helper.absolutePos(new BlockPos(1, 2, 1));
        level.setBlock(owner, RCRegistry.STRUCTURE.defaultBlockState(), 3);
        if (!(level.getBlockEntity(owner) instanceof StructureBlockEntity product)) {
            helper.fail("the owner product block must have a block entity");
            return null;
        }
        product.setStructure(UUID.randomUUID(), "injection test owner", new Vec3i(4, 4, 4),
                IoMode.ACTIVATION_FACES, 0);
        PocketSpace.Plotted claimed = space.claim(4, false, level.dimension(), owner);
        if (claimed.allowed()) {
            product.setPlotIndex(claimed.plot().index());
        }
        return claimed;
    }

    @GameTest(template = "redstonecircuit:empty")
    public void anInjectionPushesThePlotAndVanillaReacts(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        PocketSpace space = PocketSpace.of(level);
        PocketSpace.Plotted claimed = claimWithOwner(helper, level, space);
        if (claimed == null || !claimed.allowed()) {
            helper.fail("the budget refused a 4³ plot, so nothing here could be tested: "
                    + (claimed == null ? "no owner block" : claimed.refusal()));
            return;
        }
        int plot = claimed.plot().index();
        BlockPos cell = PocketSpace.originOf(plot);
        BlockPos beside = cell.offset(1, 0, 0);
        BlockPos lamp = cell.offset(2, 0, 0);
        build(level, cell);

        helper.startSequence()
                .thenExecute(() -> {
                    // (a) Nothing drives the face: nothing is injected, and the pocket has not moved.
                    helper.assertTrue(space.applyInjection(plot, table(BlockPos.ZERO, STRENGTH),
                                    noFaceEnergized()) == 0,
                            "a face with no signal must not push anything - that is the negative control");
                    helper.assertTrue(level.getSignal(cell, Direction.EAST) == 0,
                            "and with nothing injected the cell must still answer vanilla's own 0, got "
                                    + level.getSignal(cell, Direction.EAST));
                    helper.assertTrue(dustPower(level, beside) == 0, "the dust beside it is dark");
                })
                .thenExecute(() -> {
                    // (b) The face is energized: the table's cell is pushed, and vanilla answers.
                    helper.assertTrue(space.applyInjection(plot, table(BlockPos.ZERO, STRENGTH),
                                    eastFaceEnergized()) == 1,
                            "one cell changed, so one cell has to be notified");
                    helper.assertTrue(level.getSignal(cell, Direction.EAST) == STRENGTH,
                            "a cell asked from the side that is not a wire answers the table's strength, got "
                                    + level.getSignal(cell, Direction.EAST));
                    // The reader-aware half of the rule is not assertable here: the cell's own dust is *already* at
                    // the filled strength, so vanilla's own answer for it wins the max before the overlay is
                    // consulted. What it changes is what a wire is told while a wire is scanning - and its effect is
                    // the next assertion, the neighbour landing one below instead of at S.
                    helper.assertTrue(dustPower(level, beside) == STRENGTH - 1,
                            "vanilla's own dust beside it lands one below the filled strength, got "
                                    + dustPower(level, beside));
                    helper.assertTrue(level.getBlockState(lamp).getValue(BlockStateProperties.LIT),
                            "and the lamp the dust drives is lit");
                })
                // The dust beside the injected cell is a real wire, so it feeds one step back into the cell
                // that drove it - vanilla's wire-to-wire rule - and the lamp turns off through a scheduled
                // tick. Both settle within a few ticks, and reading them later is also what proves the
                // plot's chunks are ticking at all.
                .thenIdle(8)
                .thenExecute(() -> {
                    helper.assertTrue(dustPower(level, cell) == STRENGTH,
                            "the cell's own dust is the filled strength, pinned there by the wire mixin, got "
                                    + dustPower(level, cell));
                    helper.assertTrue(dustPower(level, beside) == STRENGTH - 1,
                            "and the dust beside it stays one below, got " + dustPower(level, beside));
                })
                .thenExecute(() -> {
                    // (c) The face goes dark again: the power has to go away with it.
                    helper.assertTrue(space.applyInjection(plot, table(BlockPos.ZERO, STRENGTH),
                                    noFaceEnergized()) == 1,
                            "losing the signal is a change too, or the power would never come down");
                })
                // The lamp goes out through vanilla's own four-tick scheduled tick, and a scheduled tick is
                // only collected for a chunk whose full status has finished propagating - so this waits for it
                // rather than counting ticks. The assertion is unchanged: the power must come down.
                .thenWaitUntil(() -> {
                    helper.assertTrue(level.getSignal(cell, Direction.EAST) == 0,
                            "with the face dark the cell is back to vanilla's own answer, got "
                                    + level.getSignal(cell, Direction.EAST));
                    helper.assertTrue(dustPower(level, cell) == 0,
                            "the injected cell's dust has decayed, got " + dustPower(level, cell));
                    helper.assertTrue(dustPower(level, beside) == 0,
                            "the dust beside it is dark again, got " + dustPower(level, beside));
                    helper.assertTrue(!level.getBlockState(lamp).getValue(BlockStateProperties.LIT),
                            "and so is the lamp");
                })
                .thenExecute(() -> space.release(plot))
                .thenSucceed();
    }

    @GameTest(template = "redstonecircuit:empty")
    public void anInjectionOnlyAnswersInsideItsOwnPlot(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        PocketSpace space = PocketSpace.of(level);
        PocketSpace.Plotted claimed = claimWithOwner(helper, level, space);
        if (claimed == null || !claimed.allowed()) {
            helper.fail("the budget refused a 4³ plot: "
                    + (claimed == null ? "no owner block" : claimed.refusal()));
            return;
        }
        int plot = claimed.plot().index();
        BlockPos inside = PocketSpace.originOf(plot);
        // Inside the plot's 64×64 cell, but outside the structure the plot stands for: a face table can never
        // name it (loading a payload refuses entries outside the selection), so a ledger that holds it is the
        // only way it could ever answer - which is exactly the leak this test is about.
        BlockPos outsideTheStructure = inside.offset(5, 0, 0);
        build(level, inside);

        helper.startSequence()
                .thenExecute(() -> {
                    Map<Direction, List<ActivationTable.FaceEntry>> twoCells = Map.of(Direction.EAST, List.of(
                            new ActivationTable.FaceEntry(BlockPos.ZERO, STRENGTH),
                            new ActivationTable.FaceEntry(new BlockPos(5, 0, 0), 6)));
                    space.applyInjection(plot, twoCells, eastFaceEnergized());

                    helper.assertTrue(level.getSignal(inside, Direction.EAST) == STRENGTH,
                            "the cell inside the structure answers " + STRENGTH + ", got "
                                    + level.getSignal(inside, Direction.EAST));
                    // The scope rule, in the form that survives a suite where other tests hold plots of their
                    // own: a cell beyond the structure's size is not part of it, however close it is.
                    helper.assertTrue(level.getSignal(outsideTheStructure, Direction.EAST) == 0,
                            "a cell beyond the structure's own size is not part of it and must not answer, got "
                                    + level.getSignal(outsideTheStructure, Direction.EAST));
                })
                .thenExecute(() -> space.release(plot))
                .thenSucceed();
    }
}

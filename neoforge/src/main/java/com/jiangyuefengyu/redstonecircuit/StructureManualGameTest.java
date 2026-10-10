package com.jiangyuefengyu.redstonecircuit;

import java.util.List;
import java.util.UUID;

import com.jiangyuefengyu.redstonecircuit.block.StructureBlockEntity;
import com.jiangyuefengyu.redstonecircuit.network.StructureManualTrigger;
import com.jiangyuefengyu.redstonecircuit.RCConfig;
import com.jiangyuefengyu.redstonecircuit.structure.ActivationTable;
import com.jiangyuefengyu.redstonecircuit.structure.IoMode;
import com.jiangyuefengyu.redstonecircuit.structure.pocket.PocketSpace;

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
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Mode B, from the press to the pocket: a player presses a key, and the plot's redstone moves.
 *
 * <p>整条服务器路径，不需要客户端: 一个带手动面板和 plot 的已放置结构，然后 {@code StructureManualTrigger.press}
 * - 包处理器、命令和自检都走这一个函数 - 然后看 plot 自己的方块。覆盖规则（模式、条目、距离、冷却）、脉冲
 * （会自己结束的功率），以及这次按压变成的注入。
 *
 * <p>覆盖不到的是点击: "一个屏幕每次按压是否只发一个包"需要客户端，那一半是产物方块自己的界面，它会记录每次发出的按压。
 */
@GameTestHolder(RedstoneCircuit.MODID)
@PrefixGameTestTemplate(false)
public final class StructureManualGameTest {

    /** What the panel's entry pushes. Neither 15 nor 1: a wrong answer would be obvious. */
    private static final int STRENGTH = 9;

    /** A player id of its own per test, so one test's cooldown cannot refuse another test's press. */
    private static final UUID PLAYER = UUID.randomUUID();

    private static BlockPos cell(GameTestHelper helper) {
        return helper.absolutePos(new BlockPos(1, 2, 1));
    }

    /** Dust with a lamp beside it: the injected cell, and something that shows it was pushed. */
    private static void buildCircuit(ServerLevel level, BlockPos cell) {
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

    /** A placed structure in the given mode, owning a plot of its own, with one key that pushes {@link #STRENGTH} into
     * the circuit's first cell. {@code owner} is a position of its own per structure: two structures in one place are
     * one structure. */
    private static StructureBlockEntity placeStructure(GameTestHelper helper, ServerLevel level,
                                                       PocketSpace space, IoMode ioMode, BlockPos owner) {
        level.setBlock(owner, RCRegistry.STRUCTURE.get().defaultBlockState(), 3);
        if (!(level.getBlockEntity(owner) instanceof StructureBlockEntity product)) {
            helper.fail("the placed structure must have a block entity");
            return null;
        }
        product.setStructure(UUID.randomUUID(), "manual test", new Vec3i(4, 4, 4), ioMode, 0, java.util.Map.of(),
                List.of(new ActivationTable.ManualEntry("key.keyboard.g", BlockPos.ZERO, STRENGTH, "push")),
                null);
        PocketSpace.Plotted claimed = space.claim(4, false, level.dimension(), owner);
        if (!claimed.allowed()) {
            helper.fail("the budget refused a 4-cubed plot: " + claimed.refusal());
            return null;
        }
        product.setPlotIndex(claimed.plot().index());
        return product;
    }

    private static int dustPower(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.is(Blocks.REDSTONE_WIRE) ? state.getValue(RedStoneWireBlock.POWER) : -1;
    }

    private static boolean lampLit(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.is(Blocks.REDSTONE_LAMP) && state.getValue(BlockStateProperties.LIT);
    }

    @GameTest(template = "empty")
    public void aKeyPressPushesThePlotAndThenEndsByItself(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        PocketSpace space = PocketSpace.of(level);
        StructureManualTrigger.forgetAll();
        StructureBlockEntity product = placeStructure(helper, level, space, IoMode.MANUAL,
                helper.absolutePos(new BlockPos(1, 4, 1)));
        if (product == null) {
            return;
        }
        BlockPos plotOrigin = PocketSpace.originOf(product.plotIndex());
        // The circuit goes <b>in the plot</b>: the panel's entry names a cell inside the structure, and the
        // structure's blocks live in the plot. (There is nothing to capture here - the harness has no pocket to
        // capture into - so the plot is built by hand, exactly as the injection test does.)
        buildCircuit(level, plotOrigin);

        helper.startSequence()
                .thenExecute(() -> {
                    // Nothing has been pressed: the pocket is dark.
                    helper.assertTrue(level.getSignal(plotOrigin, Direction.EAST) == 0,
                            "nothing is pressed, so nothing is pushed");
                    helper.assertFalse(lampLit(level, plotOrigin.offset(2, 0, 0)), "and the lamp is dark");
                })
                .thenExecute(() -> {
                    // The press, through the same function the packet handler calls.
                    StructureManualTrigger.Result result = StructureManualTrigger.pressIn(level, level, PLAYER,
                            Vec3.atCenterOf(product.getBlockPos()), product.getBlockPos(), 0);
                    helper.assertTrue(result.accepted(), "a press from right next to the block must be "
                            + "accepted, was refused: " + result.refusal());
                    helper.assertTrue(level.getSignal(plotOrigin, Direction.EAST) == STRENGTH,
                            "the entry's strength must be pushed into the plot, got "
                                    + level.getSignal(plotOrigin, Direction.EAST));
                    helper.assertTrue(dustPower(level, plotOrigin) == STRENGTH,
                            "and the cell's own dust reads the entry's strength, got "
                                    + dustPower(level, plotOrigin));
                    helper.assertTrue(dustPower(level, plotOrigin.offset(1, 0, 0)) == STRENGTH - 1,
                            "with the dust beside it one hop down, got "
                                    + dustPower(level, plotOrigin.offset(1, 0, 0)));
                    helper.assertTrue(lampLit(level, plotOrigin.offset(2, 0, 0)),
                            "the entry's cell is the first dust, so the lamp two cells along lights");
                })
                // A press is a pulse: it has to end on its own, and the pocket has to hear about that - which is the
                // whole reason the manual mode does not need a release packet.
                //
                // What ends it on a real server is the block's own tick (mode B's tick does exactly these two calls),
                // and the harness's chunks around a test structure are loaded but not block-ticking - so this asks the
                // same question with the clock moved on. The ticked path is what `/rc pocket manualtest` checks on a
                // real server. The injection's rate limit reads the level's own clock, so the ticks have to pass for
                // real here too - on a server they always do, because the block tick offers the sources again.
                .thenIdle(6)
                .thenExecute(() -> {
                    long after = level.getGameTime() + RCConfig.structureManualPulseTicks() + 1;
                    helper.assertTrue(product.pulses().expire(after),
                            "a pulse that has run out must be noticed, or the power would never come down");
                    product.pushInjection(level);

                    helper.assertTrue(level.getSignal(plotOrigin, Direction.EAST) == 0,
                            "the pulse must end by itself, still pushing "
                                    + level.getSignal(plotOrigin, Direction.EAST));
                    helper.assertTrue(dustPower(level, plotOrigin) == 0,
                            "and the cell's own dust must be shed too - the injection pinned it, so the end of "
                                    + "the injection has to reach the cell itself, got "
                                    + dustPower(level, plotOrigin));
                    helper.assertTrue(dustPower(level, plotOrigin.offset(1, 0, 0)) == 0,
                            "and the dust must decay, got " + dustPower(level, plotOrigin.offset(1, 0, 0)));
                })
                // The lamp is the slow half: vanilla turns one off through a scheduled tick, so the answer is a
                // few ticks away. Waiting for it keeps the assertion and loses the timing edge - the same shape
                // the injection test uses for its lamp.
                .thenWaitUntil(() -> helper.assertFalse(lampLit(level, plotOrigin.offset(2, 0, 0)),
                        "and the lamp goes out"))
                .thenExecute(() -> space.release(product.plotIndex()))
                .thenSucceed();
    }

    @GameTest(template = "empty")
    public void theRulesRefuseWhatTheyShouldRefuse(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        PocketSpace space = PocketSpace.of(level);
        StructureManualTrigger.forgetAll();
        StructureBlockEntity product = placeStructure(helper, level, space, IoMode.MANUAL,
                helper.absolutePos(new BlockPos(1, 4, 1)));
        if (product == null) {
            return;
        }
        // A second structure of its own, in the other mode: the mode rule is about the block that was pressed,
        // so the two must not share a position.
        StructureBlockEntity faceMode = placeStructure(helper, level, space, IoMode.ACTIVATION_FACES,
                helper.absolutePos(new BlockPos(4, 4, 1)));
        if (faceMode == null) {
            return;
        }
        BlockPos pos = product.getBlockPos();

        helper.startSequence()
                .thenExecute(() -> {
                    StructureManualTrigger.Result far = StructureManualTrigger.pressIn(level, level, PLAYER,
                            Vec3.atCenterOf(pos).add(100, 0, 0), pos, 0);
                    helper.assertFalse(far.accepted(), "a press from 100 blocks away must be refused");
                    helper.assertTrue(far.refusal().contains("away"), far.refusal());

                    StructureManualTrigger.Result nameless = StructureManualTrigger.pressIn(level, level, PLAYER,
                            Vec3.atCenterOf(pos), pos, 7);
                    helper.assertFalse(nameless.accepted(), "entry 7 of a one-entry panel must be refused");
                    helper.assertTrue(nameless.refusal().contains("no manual entry"), nameless.refusal());

                    StructureManualTrigger.Result wrongMode = StructureManualTrigger.pressIn(level, level,
                            UUID.randomUUID(), Vec3.atCenterOf(faceMode.getBlockPos()),
                            faceMode.getBlockPos(), 0);
                    helper.assertFalse(wrongMode.accepted(),
                            "a structure in activation-face mode must not answer a key");
                    helper.assertTrue(wrongMode.refusal().contains("activation-face"), wrongMode.refusal());

                    // None of those were accepted, so a good press still is (they must not have spent the
                    // cooldown, or a refused press would rate limit the player).
                    StructureManualTrigger.Result good = StructureManualTrigger.pressIn(level, level, PLAYER,
                            Vec3.atCenterOf(pos), pos, 0);
                    helper.assertTrue(good.accepted(), "a good press after four refusals: " + good.refusal());

                    StructureManualTrigger.Result again = StructureManualTrigger.pressIn(level, level, PLAYER,
                            Vec3.atCenterOf(pos), pos, 0);
                    helper.assertFalse(again.accepted(), "a second press in the same tick must hit the cooldown");
                    helper.assertTrue(again.refusal().contains("cooldown"), again.refusal());
                })
                .thenExecute(() -> {
                    space.release(product.plotIndex());
                    space.release(faceMode.plotIndex());
                })
                .thenSucceed();
    }
}


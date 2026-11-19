package com.jiangyuefengyu.redstonecircuit;

import com.jiangyuefengyu.redstonecircuit.block.ClockBlock;
import com.jiangyuefengyu.redstonecircuit.block.RcPlateBlock;
import com.jiangyuefengyu.redstonecircuit.block.TransformerBlock;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * 薄板四件 in a real level, on faces other than the floor: a plate on a wall reads inside that wall's plane, the box
 * follows the face it hangs on, and it comes down with the block it is glued to.
 *
 * <p>方向口径 is the surface diodes': {@code FACING} is the face it <b>reads</b>, the answer leaves on
 * {@code FACING.getOpposite()}, and both lie in the plate's own plane - never along {@code MOUNT}.
 */
@GameTestHolder(RedstoneCircuit.MODID)
@PrefixGameTestTemplate(false)
public final class PlateMountGameTest {

    private static BlockPos at(GameTestHelper helper, int x, int y, int z) {
        return helper.absolutePos(new BlockPos(x, y, z));
    }

    private static BlockState transformer(Direction mount, Direction facing, int setting) {
        return RCRegistry.TRANSFORMER.get().defaultBlockState()
                .setValue(RcPlateBlock.MOUNT, mount)
                .setValue(RcPlateBlock.FACING, facing)
                .setValue(TransformerBlock.SETTING, setting);
    }

    /**
     * 挂西墙的一块变压器，读上面：上面来 15，背面（下面）答 7，而它自己读的那一面什么都不答。
     */
    @GameTest(template = "empty")
    public void aWallPlateReadsAlongItsWall(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        helper.setBlock(new BlockPos(2, 1, 2), Blocks.STONE.defaultBlockState());
        helper.setBlock(new BlockPos(3, 1, 2), transformer(Direction.WEST, Direction.UP, 7));
        BlockPos pos = at(helper, 3, 1, 2);

        helper.startSequence()
                .thenExecuteAfter(1, () -> helper.setBlock(new BlockPos(3, 2, 2),
                        Blocks.REDSTONE_BLOCK.defaultBlockState()))
                .thenExecuteAfter(2, () -> {
                    TransformerBlock.announce(level, pos);
                    BlockState state = level.getBlockState(pos);
                    helper.assertTrue(state.getValue(RcPlateBlock.MOUNT) == Direction.WEST,
                            "挂西墙就是挂西墙，读回来的是 " + state.getValue(RcPlateBlock.MOUNT));
                    helper.assertTrue(TransformerBlock.answerAt(level, pos, state) == 7,
                            "输入 15、设定 7：要输出 7，但方块说 " + TransformerBlock.answerAt(level, pos, state));
                    // side 是「问的人在哪边」: 站在下面问，方向就是从下面指向板子的 UP。
                    helper.assertTrue(state.getSignal(level, pos, Direction.UP) == 7,
                            "站在下面（背面）问要答 7，实际 " + state.getSignal(level, pos, Direction.UP));
                    helper.assertTrue(state.getSignal(level, pos, Direction.DOWN) == 0,
                            "站在上面（它自己读的输入面）问，什么都不答");
                    helper.assertTrue(state.getSignal(level, pos, Direction.WEST) == 0,
                            "挂着的那一面也不回答");
                })
                .thenSucceed();
    }

    /** 盒子跟着挂载面走：挂西墙就只有最西那 2 像素，掉下来的那一刻盒子就是拆墙的那一格。 */
    @GameTest(template = "empty")
    public void theBoxFollowsTheFaceAndThePlateFallsWithItsWall(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        helper.setBlock(new BlockPos(2, 1, 2), Blocks.STONE.defaultBlockState());
        helper.setBlock(new BlockPos(3, 1, 2), RCRegistry.CLOCK.get().defaultBlockState()
                .setValue(RcPlateBlock.MOUNT, Direction.WEST)
                .setValue(RcPlateBlock.FACING, Direction.NORTH));
        BlockPos pos = at(helper, 3, 1, 2);

        helper.startSequence()
                .thenExecuteAfter(1, () -> {
                    VoxelShape shape = level.getBlockState(pos).getShape(level, pos);
                    // 盒子的单位是格: 2 像素 = 0.125。
                    helper.assertTrue(shape.min(Direction.Axis.X) == 0.0 && shape.max(Direction.Axis.X) == 0.125,
                            "挂西墙：盒子该贴在格子的西边 2 像素，实际 " + shape.min(Direction.Axis.X) + "…"
                                    + shape.max(Direction.Axis.X));
                    helper.assertTrue(shape.min(Direction.Axis.Y) == 0.0 && shape.max(Direction.Axis.Y) == 1.0,
                            "而且整格高，不再只是一条 2 像素高的地板");
                })
                .thenExecuteAfter(1, () -> helper.setBlock(new BlockPos(2, 1, 2),
                        Blocks.AIR.defaultBlockState()))
                .thenExecuteAfter(1, () -> helper.assertFalse(
                        level.getBlockState(pos).getBlock() == RCRegistry.CLOCK.get(),
                        "墙没了，挂在它上面的那块该掉"))
                .thenSucceed();
    }

    /**
     * 转向杆: 挂西墙的一块变压器, 正面从北开始走完自己那一圈(北 → 下 → 南 → 上 → 北), 贴着的那一面一动不动,
     * 而答案每一圈都跟着新的输入面重算。
     */
    @GameTest(template = "empty")
    public void theRodTurnsThePlateInsideItsWall(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        helper.setBlock(new BlockPos(2, 1, 2), Blocks.STONE.defaultBlockState());
        helper.setBlock(new BlockPos(3, 1, 2), transformer(Direction.WEST, Direction.NORTH, 7));
        BlockPos pos = at(helper, 3, 1, 2);

        helper.startSequence()
                .thenExecuteAfter(1, () -> helper.setBlock(new BlockPos(3, 1, 1),
                        Blocks.REDSTONE_BLOCK.defaultBlockState()))
                .thenExecuteAfter(2, () -> {
                    TransformerBlock.announce(level, pos);
                    helper.assertTrue(TransformerBlock.answerAt(level, pos, level.getBlockState(pos)) == 7,
                            "北面有电, 正面朝北: 该答 7");
                    // 一圈: 北 → 下 → 南 → 上 → 北
                    assertTurn(helper, level, pos, Direction.DOWN);
                    assertTurn(helper, level, pos, Direction.SOUTH);
                    assertTurn(helper, level, pos, Direction.UP);
                    assertTurn(helper, level, pos, Direction.NORTH);
                })
                .thenSucceed();
    }

    /** 转一格, 并且确认这一格是真的转到对的那一格上、贴面没被带走。 */
    private static void assertTurn(GameTestHelper helper, ServerLevel level, BlockPos pos, Direction expected) {
        Direction now = com.jiangyuefengyu.redstonecircuit.logic.RotationRod.turnWorldDiode(level, pos);
        helper.assertTrue(now == expected, "转向杆该转到 " + expected + ", 实际 " + now);
        BlockState state = level.getBlockState(pos);
        helper.assertTrue(state.getValue(RcPlateBlock.FACING) == expected,
                "方块状态里的正面该是 " + expected + ", 实际 " + state.getValue(RcPlateBlock.FACING));
        helper.assertTrue(state.getValue(RcPlateBlock.MOUNT) == Direction.WEST, "贴面不许被转向杆带走");
        TransformerBlock.announce(level, pos);
        int answer = TransformerBlock.answerAt(level, pos, state);
        helper.assertTrue(expected == Direction.NORTH ? answer == 7 : answer == 0,
                "正面在 " + expected + " 时背后该答 " + (expected == Direction.NORTH ? 7 : 0) + ", 实际 " + answer);
    }

    /** 背后没有东西就挂不住：判据是「贴着的那一面是不是实心面」，放置路径也问同一个方法。 */
    @GameTest(template = "empty")
    public void aPlateNeedsAFaceToHangOn(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = at(helper, 5, 1, 5);
        BlockState hanging = RCRegistry.CLOCK.get().defaultBlockState()
                .setValue(RcPlateBlock.MOUNT, Direction.WEST)
                .setValue(RcPlateBlock.FACING, Direction.NORTH);

        // 先把西边那一格清成空气：模板里有没有东西不该决定这条用例的答案。
        helper.setBlock(new BlockPos(4, 1, 5), Blocks.AIR.defaultBlockState());
        helper.assertTrue(!RcPlateBlock.canStay(hanging, level, pos), "空气里挂不住");
        helper.setBlock(new BlockPos(4, 1, 5), Blocks.STONE.defaultBlockState());
        helper.startSequence()
                .thenExecuteAfter(1, () -> helper.assertTrue(RcPlateBlock.canStay(hanging, level, pos),
                        "有了那面墙就挂得住"))
                .thenSucceed();
    }
}

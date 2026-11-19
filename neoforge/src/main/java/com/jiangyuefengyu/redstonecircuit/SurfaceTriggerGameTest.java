package com.jiangyuefengyu.redstonecircuit;

import com.jiangyuefengyu.redstonecircuit.block.SurfaceTriggerBlock;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * 红石触发器（贴面） in a real level: 正面 reads, 背面 answers, and one rising edge toggles the switch - from any face.
 *
 * <p>方向口径 is the floor version's: {@code FACING} is the face it <b>reads</b>; the answer leaves on
 * {@code FACING.getOpposite()}, so the neighbour that hears it asks with {@code side == FACING}.
 */
@GameTestHolder(RedstoneCircuit.MODID)
@PrefixGameTestTemplate(false)
public final class SurfaceTriggerGameTest {

    private static BlockPos at(GameTestHelper helper, int x, int y, int z) {
        return helper.absolutePos(new BlockPos(x, y, z));
    }

    private static BlockState twin(Direction mount, Direction facing) {
        return RCRegistry.SURFACE_TRIGGER.get().defaultBlockState()
                .setValue(SurfaceTriggerBlock.MOUNT, mount)
                .setValue(SurfaceTriggerBlock.FACING, facing);
    }

    private static int answerAt(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.getSignal(level, pos, state.getValue(SurfaceTriggerBlock.FACING));
    }

    /**
     * 挂西墙的一片，读北面：北面来一次上升沿就翻成通，答案从南面出去；把电撤掉再给一次，它翻回来。
     */
    @GameTest(template = "empty")
    public void aWallTriggerReadsItsFrontAndAnswersBehind(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        helper.setBlock(new BlockPos(2, 1, 2), Blocks.STONE.defaultBlockState());
        helper.setBlock(new BlockPos(3, 1, 2), twin(Direction.WEST, Direction.NORTH));
        BlockPos pos = at(helper, 3, 1, 2);
        helper.setBlock(new BlockPos(3, 1, 1), Blocks.STONE.defaultBlockState());

        helper.assertFalse(level.getBlockState(pos).getValue(SurfaceTriggerBlock.POWERED), "刚放下: 关着");
        helper.assertTrue(answerAt(level, pos) == 0, "关着就不答");

        helper.startSequence()
                .thenExecuteAfter(2, () -> helper.setBlock(new BlockPos(3, 1, 1),
                        Blocks.REDSTONE_BLOCK.defaultBlockState()))
                .thenExecuteAfter(2, () -> {
                    helper.assertTrue(level.getBlockState(pos).getValue(SurfaceTriggerBlock.POWERED),
                            "正面来了一次上升沿，开关就该翻");
                    helper.assertTrue(answerAt(level, pos) == 15,
                            "背面该答 15，实际 " + answerAt(level, pos));
                    helper.assertTrue(level.getSignal(pos, Direction.SOUTH) == 0,
                            "除背面以外一律不答");
                })
                .thenExecuteAfter(2, () -> helper.setBlock(new BlockPos(3, 1, 1), Blocks.AIR.defaultBlockState()))
                .thenExecuteAfter(2, () -> helper.setBlock(new BlockPos(3, 1, 1),
                        Blocks.REDSTONE_BLOCK.defaultBlockState()))
                .thenExecuteAfter(2, () -> helper.assertTrue(
                        !level.getBlockState(pos).getValue(SurfaceTriggerBlock.POWERED),
                        "第二次上升沿该把它翻回来（撤电再给电是一次新的沿）"))
                .thenSucceed();
    }

    /** 四个侧面都能挂：每面一块石头、各自一格，撤掉一面墙只有那一面掉。 */
    @GameTest(template = "empty")
    public void itHangsOnEverySideFaceAndDropsWithItsWall(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Direction[] mounts = { Direction.WEST, Direction.EAST, Direction.NORTH, Direction.SOUTH };
        BlockPos[] cells = { new BlockPos(3, 1, 2), new BlockPos(3, 1, 5), new BlockPos(3, 1, 8),
                new BlockPos(6, 1, 2) };
        helper.startSequence()
                .thenExecuteAfter(1, () -> {
                    for (int index = 0; index < mounts.length; index++) {
                        helper.setBlock(cells[index].relative(mounts[index]), Blocks.STONE.defaultBlockState());
                        helper.setBlock(cells[index], twin(mounts[index], Direction.UP));
                        helper.assertTrue(level.getBlockState(at(helper, cells[index].getX(), cells[index].getY(),
                                        cells[index].getZ())).getBlock() == RCRegistry.SURFACE_TRIGGER.get(),
                                mounts[index] + " 那一面该挂得住");
                    }
                })
                .thenExecuteAfter(1, () -> {
                    // 撤掉第一格那面墙：只有挂在它上面的那片掉
                    helper.setBlock(cells[0].relative(mounts[0]), Blocks.AIR.defaultBlockState());
                    helper.assertFalse(level.getBlockState(at(helper, cells[0].getX(), cells[0].getY(),
                                    cells[0].getZ())).getBlock() == RCRegistry.SURFACE_TRIGGER.get(),
                            "墙没了，挂在它上面的那片该掉");
                    helper.assertTrue(level.getBlockState(at(helper, cells[2].getX(), cells[2].getY(),
                                    cells[2].getZ())).getBlock() == RCRegistry.SURFACE_TRIGGER.get(),
                            "别的墙还在，那几片不该被带下去");
                })
                .thenSucceed();
    }

    /** 物品分流：点顶面交给本体（返回 null），点侧面才换成孪生。 */
    @GameTest(template = "empty")
    public void theItemRoutesSideClicksToTheTwin(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        helper.setBlock(new BlockPos(3, 1, 2), Blocks.STONE.defaultBlockState());
        helper.startSequence()
                .thenExecuteAfter(1, () -> {
                    net.minecraft.world.phys.BlockHitResult top = new net.minecraft.world.phys.BlockHitResult(
                            net.minecraft.world.phys.Vec3.atCenterOf(at(helper, 3, 1, 2)),
                            Direction.UP, at(helper, 3, 1, 2), false);
                    net.minecraft.world.InteractionResult floorClick =
                            com.jiangyuefengyu.redstonecircuit.block.SurfaceTriggerPlacement.tryPlace(
                                    new net.minecraft.world.item.context.UseOnContext(level, null,
                                            net.minecraft.world.InteractionHand.MAIN_HAND,
                                            new net.minecraft.world.item.ItemStack(RCRegistry.TRIGGER_ITEM.get()), top));
                    helper.assertTrue(floorClick == null, "点顶面该交还给本体，实际 " + floorClick);

                    net.minecraft.world.phys.BlockHitResult side = new net.minecraft.world.phys.BlockHitResult(
                            net.minecraft.world.phys.Vec3.atCenterOf(at(helper, 3, 1, 2)),
                            Direction.EAST, at(helper, 3, 1, 2), false);
                    net.minecraft.world.InteractionResult wallClick =
                            com.jiangyuefengyu.redstonecircuit.block.SurfaceTriggerPlacement.tryPlace(
                                    new net.minecraft.world.item.context.UseOnContext(level, null,
                                            net.minecraft.world.InteractionHand.MAIN_HAND,
                                            new net.minecraft.world.item.ItemStack(RCRegistry.TRIGGER_ITEM.get()), side));
                    helper.assertTrue(wallClick != null && wallClick.consumesAction(),
                            "点侧面该放下孪生，实际 " + wallClick);
                    BlockState placed = level.getBlockState(at(helper, 4, 1, 2));
                    helper.assertTrue(placed.getBlock() == RCRegistry.SURFACE_TRIGGER.get(),
                            "放下的该是孪生，实际 " + placed.getBlock());
                    helper.assertTrue(placed.getValue(SurfaceTriggerBlock.MOUNT) == Direction.WEST,
                            "挂载该是西（贴在那块石头上），实际 " + placed.getValue(SurfaceTriggerBlock.MOUNT));
                })
                .thenSucceed();
    }

    /**
     * 转向杆：一下一格。墙面走板面内的四态（上、右、下、左 —— 上下也在环里），贴地/吊顶走四个水平朝向。
     *
     * <p>走的是 {@code RotationRod#turnWorldDiode}，也就是转向杆真正调的那一条，不是绕过它直接改状态。
     */
    @GameTest(template = "empty")
    public void theRodTurnsTheTrigger(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        helper.setBlock(new BlockPos(2, 1, 2), Blocks.STONE.defaultBlockState());
        BlockPos wall = at(helper, 3, 1, 2);
        helper.setBlock(new BlockPos(3, 1, 2), twin(Direction.WEST, Direction.NORTH));

        helper.assertTrue(com.jiangyuefengyu.redstonecircuit.logic.RotationRod.turnWorldDiode(level, wall)
                        == Direction.DOWN, "贴墙：北的下一个是下（板面内的顺时针）");
        helper.assertTrue(level.getBlockState(wall).getValue(SurfaceTriggerBlock.FACING) == Direction.DOWN,
                "状态里的朝向要真的跟着改");
        helper.assertTrue(com.jiangyuefengyu.redstonecircuit.logic.RotationRod.turnWorldDiode(level, wall)
                        == Direction.SOUTH, "再点是南");
        helper.assertTrue(com.jiangyuefengyu.redstonecircuit.logic.RotationRod.turnWorldDiode(level, wall)
                        == Direction.UP, "再点是上：墙上上下两个朝向也在环里");
        helper.assertTrue(com.jiangyuefengyu.redstonecircuit.logic.RotationRod.turnWorldDiode(level, wall)
                        == Direction.NORTH, "一圈回到北");

        // 地面那件本体也可以转，走的是四个水平朝向（它的朝向上/下不存在）
        helper.setBlock(new BlockPos(6, 1, 2), Blocks.STONE.defaultBlockState());
        BlockPos floor = at(helper, 6, 2, 2);
        helper.setBlock(new BlockPos(6, 2, 2), RCRegistry.TRIGGER.get().defaultBlockState());
        helper.assertTrue(com.jiangyuefengyu.redstonecircuit.logic.RotationRod.turnWorldDiode(level, floor)
                        == Direction.EAST, "贴地：北的下一个是东");
        helper.assertTrue(level.getBlockState(floor)
                        .getValue(com.jiangyuefengyu.redstonecircuit.block.TriggerBlock.FACING) == Direction.EAST,
                "本体状态里的朝向也要跟着改");
        helper.succeed();
    }
}

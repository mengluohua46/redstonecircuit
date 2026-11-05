package com.jiangyuefengyu.redstonecircuit;

import com.jiangyuefengyu.redstonecircuit.block.HoneyPistonBlock;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * <b>蜂蜜活塞的两种断电</b> - 推开之后什么都不拉回来, 伸进空气之后拉回一格.
 *
 * <p>两件事各自只在一个地方能被钉死: "这次是伸进空气"写在 {@code GRABBING} 上 (断电时世界已经一模一样, 问不出来),
 * 而杆头必须是 {@code honey_piston_head} 才算真的走了 {@code moveBlocks} 造杆头那一条路.
 *
 * <p>所以每一例都先上电、验伸缩, 再断电、验收回, 中间不断言任何"应该已经发生"却没人看见过的状态; 最后一例是原版粘性活塞,
 * 它拉回方块就说明 mixin 没把原版那条路一起改掉.
 */
public final class HoneyPistonGameTest implements FabricGameTest {

    private static final Direction FACING = Direction.EAST;
    /** The piston, the cell in front of it (where a head goes) and the one beyond it (where a block is taken from). */
    private static final BlockPos BASE = new BlockPos(1, 1, 1);
    private static final BlockPos FRONT = BASE.relative(FACING);
    private static final BlockPos OUTER = BASE.relative(FACING, 2);
    /** Beside the piston, not behind it: a powered neighbour is enough, and no cell of the template is a wall. */
    private static final BlockPos POWER = new BlockPos(1, 1, 0);

    private static void placeHoneyPiston(GameTestHelper helper, BlockPos base, Direction facing) {
        helper.setBlock(base, RCRegistry.HONEY_PISTON.defaultBlockState()
                .setValue(BlockStateProperties.FACING, facing));
    }

    private static void placeHoneyPiston(GameTestHelper helper) {
        placeHoneyPiston(helper, BASE, FACING);
    }

    private static void placeStickyPiston(GameTestHelper helper) {
        helper.setBlock(BASE, Blocks.STICKY_PISTON.defaultBlockState()
                .setValue(BlockStateProperties.FACING, FACING));
    }

    private static BlockState stateAt(GameTestHelper helper, BlockPos relative) {
        ServerLevel level = helper.getLevel();
        return level.getBlockState(helper.absolutePos(relative));
    }

    private static void assertBlock(GameTestHelper helper, BlockPos relative, Block block, String why) {
        Block actual = stateAt(helper, relative).getBlock();
        helper.assertTrue(actual == block, relative.toShortString() + " 应该是 " + block.getName().getString()
                + ", 实际是 " + actual.getName().getString() + " - " + why);
    }

    private static void assertExtended(GameTestHelper helper, BlockPos base, boolean extended) {
        BlockState state = stateAt(helper, base);
        helper.assertTrue(state.is(RCRegistry.HONEY_PISTON), "底座自己必须还在 " + base.toShortString());
        helper.assertTrue(state.getValue(HoneyPistonBlock.EXTENDED) == extended,
                base.toShortString() + " 的 EXTENDED 应该是 " + extended + ", 实际 "
                        + state.getValue(HoneyPistonBlock.EXTENDED));
    }

    private static void assertExtended(GameTestHelper helper, boolean extended) {
        assertExtended(helper, BASE, extended);
    }

    /** The bit recorded when the piston extended, read off the world rather than off a local. */
    private static boolean grabbing(GameTestHelper helper, BlockPos base) {
        return stateAt(helper, base).getValue(HoneyPistonBlock.GRABBING);
    }

    private static boolean grabbing(GameTestHelper helper) {
        return grabbing(helper, BASE);
    }

    /** The head really came from {@code moveBlocks}: our block, not the vanilla one it replaced there. */
    private static void assertHoneyHead(GameTestHelper helper, BlockPos front) {
        Block actual = stateAt(helper, front).getBlock();
        helper.assertTrue(actual == RCRegistry.HONEY_PISTON_HEAD,
                front.toShortString() + " 应该是 honey_piston_head, 实际是 " + actual.getName().getString()
                        + " - 原版杆头说明 moveBlocks 里那一处没换掉");
    }

    private static void assertHoneyHead(GameTestHelper helper) {
        assertHoneyHead(helper, FRONT);
    }

    /** (a) 正前方有方块: 推开, 然后只收杆. */
    @GameTest(template = "redstonecircuit:empty")
    public void aPushedBlockStaysWhereItWasPushed(GameTestHelper helper) {
        placeHoneyPiston(helper);
        helper.setBlock(FRONT, Blocks.STONE.defaultBlockState());

        helper.startSequence()
                .thenExecuteAfter(2, () -> helper.setBlock(POWER, Blocks.REDSTONE_BLOCK.defaultBlockState()))
                .thenExecuteAfter(8, () -> {
                    assertBlock(helper, OUTER, Blocks.STONE, "这一推要成立, 后面的断言才有意义");
                    assertHoneyHead(helper);
                    assertExtended(helper, true);
                    helper.assertFalse(grabbing(helper),
                            "推开的那一次 GRABBING 必须是 false: 它正是「断电不拉」的唯一依据");
                })
                .thenExecuteAfter(1, () -> helper.setBlock(POWER, Blocks.AIR.defaultBlockState()))
                .thenExecuteAfter(8, () -> {
                    assertBlock(helper, OUTER, Blocks.STONE, "断电不把推出去的方块拉回来");
                    assertBlock(helper, FRONT, Blocks.AIR, "杆头收回, 那一格让出来");
                    assertExtended(helper, false);
                })
                .thenSucceed();
    }

    /** (b) 正前方空气、外侧有可推方块: 先只伸杆, 断电把外面那块拉进杆让出的那一格. */
    @GameTest(template = "redstonecircuit:empty")
    public void anExtensionIntoAirDragsTheOuterBlockBack(GameTestHelper helper) {
        placeHoneyPiston(helper);
        helper.setBlock(OUTER, Blocks.STONE.defaultBlockState());

        helper.startSequence()
                .thenExecuteAfter(2, () -> helper.setBlock(POWER, Blocks.REDSTONE_BLOCK.defaultBlockState()))
                .thenExecuteAfter(8, () -> {
                    assertHoneyHead(helper);
                    assertBlock(helper, OUTER, Blocks.STONE, "伸进空气不碰外面那一格");
                    helper.assertTrue(grabbing(helper), "伸进空气的那一次必须记下来, 否则断电无从判断");
                })
                .thenExecuteAfter(1, () -> helper.setBlock(POWER, Blocks.AIR.defaultBlockState()))
                .thenExecuteAfter(8, () -> {
                    assertBlock(helper, FRONT, Blocks.STONE, "外面那块被拉进杆让出的那一格");
                    assertBlock(helper, OUTER, Blocks.AIR, "而它原来的位置空了");
                    assertExtended(helper, false);
                })
                .thenSucceed();
    }

    /** (c) 正前方与外侧都是空气: 伸、收, 世界不变, 也不出错. */
    @GameTest(template = "redstonecircuit:empty")
    public void anExtensionIntoNothingChangesNothing(GameTestHelper helper) {
        placeHoneyPiston(helper);

        helper.startSequence()
                .thenExecuteAfter(2, () -> helper.setBlock(POWER, Blocks.REDSTONE_BLOCK.defaultBlockState()))
                .thenExecuteAfter(8, () -> {
                    assertHoneyHead(helper);
                    assertBlock(helper, OUTER, Blocks.AIR, "外面本来就是空气");
                    helper.assertTrue(grabbing(helper), "同样是伸进空气");
                })
                .thenExecuteAfter(1, () -> helper.setBlock(POWER, Blocks.AIR.defaultBlockState()))
                .thenExecuteAfter(8, () -> {
                    assertBlock(helper, FRONT, Blocks.AIR, "没东西可拉, 只收杆");
                    assertBlock(helper, OUTER, Blocks.AIR, "外面那一格也不该凭空出现东西");
                    assertExtended(helper, false);
                })
                .thenSucceed();
    }

    /** (d) 外侧是不可推方块: 断电只收杆, 黑曜石不动. */
    @GameTest(template = "redstonecircuit:empty")
    public void obsidianOutsideTheHeadIsNotDragged(GameTestHelper helper) {
        placeHoneyPiston(helper);
        helper.setBlock(OUTER, Blocks.OBSIDIAN.defaultBlockState());

        helper.startSequence()
                .thenExecuteAfter(2, () -> helper.setBlock(POWER, Blocks.REDSTONE_BLOCK.defaultBlockState()))
                .thenExecuteAfter(8, () -> {
                    assertHoneyHead(helper);
                    helper.assertTrue(grabbing(helper), "仍然是伸进空气");
                })
                .thenExecuteAfter(1, () -> helper.setBlock(POWER, Blocks.AIR.defaultBlockState()))
                .thenExecuteAfter(8, () -> {
                    assertBlock(helper, OUTER, Blocks.OBSIDIAN, "黑曜石推不动也就拉不动, 它留在原处");
                    assertBlock(helper, FRONT, Blocks.AIR, "只收杆");
                    assertExtended(helper, false);
                })
                .thenSucceed();
    }

    /** (e) 回归: 原版粘性活塞照样把方块拉回来 - mixin 没有连原版那条路一起改. */
    @GameTest(template = "redstonecircuit:empty")
    public void vanillaStickyPistonStillDrags(GameTestHelper helper) {
        placeStickyPiston(helper);
        helper.setBlock(FRONT, Blocks.STONE.defaultBlockState());

        helper.startSequence()
                .thenExecuteAfter(2, () -> helper.setBlock(POWER, Blocks.REDSTONE_BLOCK.defaultBlockState()))
                .thenExecuteAfter(8, () -> assertBlock(helper, OUTER, Blocks.STONE, "先把方块推出去"))
                .thenExecuteAfter(1, () -> helper.setBlock(POWER, Blocks.AIR.defaultBlockState()))
                .thenExecuteAfter(8, () -> {
                    assertBlock(helper, FRONT, Blocks.STONE, "原版粘性活塞断电仍然拉回一格");
                    assertBlock(helper, OUTER, Blocks.AIR, "原来的位置空了");
                    helper.assertTrue(stateAt(helper, BASE).is(Blocks.STICKY_PISTON)
                            && !stateAt(helper, BASE).getValue(BlockStateProperties.EXTENDED),
                            "并且底座收回成粘性活塞");
                })
                .thenSucceed();
    }

    /**
     * 两台蜂蜜活塞同一 tick 收杆: 各自外面那块都要被拉进自己杆让出的那一格.
     *
     * <p>摆放锁两件事. 底座在 (1,1,1) 与 (1,1,3), 相距两格 - 谁的邻居列表里都没有对方, 所以任何一台的通知都不会直接
     * 走到另一台上; 电源只有 (1,1,2) 那一个红石块, 它同时贴着两台, 撤掉它时那一次六面走查会先后通知两台, 两次
     * blockEvent 落在同一 tick、由同一个 runBlockEvents 循环消化. 也就是说"两台各自的拉不拉判定紧挨着发生", 判定若有哪
     * 一帧丢了, 后果就是其中一台只收杆不拉块, 而那正是 FRONT 上那块石头的断言.
     *
     * <p>修复前后都是绿的: 服务端的 blockEvent 只是入队 (ServerLevel#blockEvent), 真正跑 triggerEvent 的是
     * runBlockEvents 里那一个 while 循环, 前一台返回之后才轮到后一台, 所以这里没有嵌套. 这一例因此锁的是"两台同时在收"
     * 这个摆法, 不是 DECIDING 的栈语义 (那条路径只在客户端可达).
     */
    @GameTest(template = "redstonecircuit:empty")
    public void twoHoneyPistonsDraggingInTheSameTickBothPullTheirBlock(GameTestHelper helper) {
        BlockPos nearBase = new BlockPos(1, 1, 1);
        BlockPos farBase = new BlockPos(1, 1, 3);
        BlockPos nearFront = nearBase.relative(FACING);
        BlockPos farFront = farBase.relative(FACING);
        BlockPos nearOuter = nearBase.relative(FACING, 2);
        BlockPos farOuter = farBase.relative(FACING, 2);
        BlockPos sharedPower = new BlockPos(1, 1, 2);

        placeHoneyPiston(helper, nearBase, FACING);
        placeHoneyPiston(helper, farBase, FACING);
        helper.setBlock(nearOuter, Blocks.STONE.defaultBlockState());
        helper.setBlock(farOuter, Blocks.STONE.defaultBlockState());

        helper.startSequence()
                // One redstone block, both pistons: they extend in the same tick, into air, both recording GRABBING.
                .thenExecuteAfter(2, () -> helper.setBlock(sharedPower, Blocks.REDSTONE_BLOCK.defaultBlockState()))
                .thenExecuteAfter(8, () -> {
                    assertHoneyHead(helper, nearFront);
                    assertHoneyHead(helper, farFront);
                    assertBlock(helper, nearOuter, Blocks.STONE, "伸进空气不碰外面那一格");
                    assertBlock(helper, farOuter, Blocks.STONE, "两台都一样");
                    helper.assertTrue(grabbing(helper, nearBase) && grabbing(helper, farBase),
                            "两台都是伸进空气, 两台都该记下来");
                })
                // Taking that one block away unpowers both, so both contract in the same tick.
                .thenExecuteAfter(1, () -> helper.setBlock(sharedPower, Blocks.AIR.defaultBlockState()))
                .thenExecuteAfter(8, () -> {
                    assertBlock(helper, nearFront, Blocks.STONE,
                            "近的那台把外面那块拉进杆让出的那一格");
                    assertBlock(helper, farFront, Blocks.STONE,
                            "远的那台同样 - 两台同一 tick 收, 哪一台的判定都不能丢");
                    assertBlock(helper, nearOuter, Blocks.AIR, "它原来的位置空了");
                    assertBlock(helper, farOuter, Blocks.AIR, "两台都是");
                    assertExtended(helper, nearBase, false);
                    assertExtended(helper, farBase, false);
                })
                .thenSucceed();
    }

    /**
     * 一台收起的蜂蜜活塞像原版活塞那样被别人推走 - isPushable 那处注入唯一的直接证据.
     *
     * <p>它的 pushReaction 是照抄原版活塞的 BLOCK, 所以少了那处注入, 原版 isPushable 会走到 pushReaction 那一支直接答
     * false, 原版活塞的 resolve 就失败、根本不伸: 下面"被推到 (2,1,1)"这一条会红.
     *
     * <p>伸出的那一半测不出东西, 所以没有: 伸出的活塞前面必有杆头, 而杆头自己就推不动 (PISTON_HEAD 的 pushReaction 是
     * BLOCK), resolve 在轮到活塞本体之前就已经失败 - 有没有这处注入, 结果一模一样.
     */
    @GameTest(template = "redstonecircuit:empty")
    public void aRetractedHoneyPistonIsPushedLikeAnyOtherPiston(GameTestHelper helper) {
        BlockPos pusher = new BlockPos(0, 1, 1);
        BlockPos honey = new BlockPos(1, 1, 1);
        BlockPos landed = new BlockPos(2, 1, 1);
        // Diagonal to the honey piston, so only the vanilla one is powered and the pushed one stays retracted.
        BlockPos power = new BlockPos(0, 1, 0);

        helper.setBlock(pusher, Blocks.PISTON.defaultBlockState()
                .setValue(BlockStateProperties.FACING, FACING));
        placeHoneyPiston(helper, honey, FACING);

        helper.startSequence()
                .thenExecuteAfter(2, () -> helper.setBlock(power, Blocks.REDSTONE_BLOCK.defaultBlockState()))
                .thenExecuteAfter(8, () -> {
                    assertBlock(helper, landed, RCRegistry.HONEY_PISTON, "收起的蜂蜜活塞被推走一格");
                    assertBlock(helper, honey, Blocks.PISTON_HEAD, "推它的那台原版活塞伸出了杆头, 停在那里");
                    helper.assertFalse(stateAt(helper, landed).getValue(HoneyPistonBlock.EXTENDED),
                            "被推走的是收着的那台, 落地也不该自己伸出");
                })
                .thenSucceed();
    }
}

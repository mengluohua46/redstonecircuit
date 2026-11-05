package com.jiangyuefengyu.redstonecircuit;

import com.jiangyuefengyu.redstonecircuit.block.TriggerBlock;
import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneStore;
import com.jiangyuefengyu.redstonecircuit.data.Slot;
import com.jiangyuefengyu.redstonecircuit.logic.TriggerRule;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * 红石触发器 in a real level: one rising edge flips the switch, a hold does not, a fall does not - in both forms.
 *
 * <p>What has to be proved here is the <b>mechanism</b>, not the rule: {@code TriggerRuleTest} already pins the rule itself,
 * and both forms call it. So each test walks a real input through a real block and reads the answer the way its neighbours
 * read it - a vanilla {@code getSignal} query from the side that is supposed to hear it, which is also the only way "只在
 * 背面回答" can be checked at all.
 *
 * <p>方向口径: {@code getSignal(pos, side)} 的 {@code side} 是"从提问者指向这个方块", 所以站在背面的那个邻居问的是
 * {@code side == FACING} - {@link #answerAt} 就是把这一句话写成一个方法, 好让两个形态用同一个问法.
 */
@GameTestHolder(RedstoneCircuit.MODID)
@PrefixGameTestTemplate(false)
public final class TriggerGameTest {

    /** 世界形态: 触发器站在 (2,1,2) 上, 支撑是 (2,0,2); 正面朝北时输入面是 (2,1,1). */
    private static final BlockPos INPUT = new BlockPos(2, 1, 1);

    private static BlockPos at(GameTestHelper helper, int x, int y, int z) {
        return helper.absolutePos(new BlockPos(x, y, z));
    }

    /** 贴地放一个触发器, 正面指向 {@code facing}; 返回它的世界坐标. */
    private static BlockPos placeFloorTrigger(GameTestHelper helper, Direction facing) {
        helper.setBlock(new BlockPos(2, 0, 2), Blocks.STONE.defaultBlockState());
        helper.setBlock(new BlockPos(2, 1, 2), RCRegistry.TRIGGER.get().defaultBlockState()
                .setValue(TriggerBlock.FACE, AttachFace.FLOOR)
                .setValue(TriggerBlock.FACING, facing));
        return at(helper, 2, 1, 2);
    }

    /** 站在背面的那个邻居听到的答案 - vanilla 的问法, 一个字都不绕过. */
    private static int answerAt(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.getSignal(level, pos, state.getValue(TriggerBlock.FACING));
    }

    private static boolean powered(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos).getValue(TriggerBlock.POWERED);
    }

    /**
     * 验收 2: 贴地放一个, 正面打两次上升沿脉冲 - 背面第一次 15、第二次 0, 而中间保持通电的那几 tick 一次都没再切换.
     *
     * <p>输入用一块红石块换掉石头 / 换回来做出来: 红石块在原版里就是"整面 15", 于是"放上去"是一条干净的上升沿,
     * "拿走"是一条干净的下降沿 - 没有红石线该有的那点衰减可以吵。
     */
    @GameTest(template = "empty")
    public void twoRisingEdgesAnswerFifteenThenZero(GameTestHelper helper) {
        BlockPos pos = placeFloorTrigger(helper, Direction.NORTH);
        ServerLevel level = helper.getLevel();
        helper.assertFalse(powered(level, pos), "刚放下：关着");
        helper.assertTrue(answerAt(level, pos) == 0, "关着就一声不响");

        helper.setBlock(INPUT, Blocks.REDSTONE_BLOCK.defaultBlockState());
        helper.assertTrue(powered(level, pos), "第一条上升沿把开关翻开");
        helper.assertTrue(answerAt(level, pos) == TriggerRule.ON,
                "开着背面答 15，实际 " + answerAt(level, pos));

        helper.startSequence()
                .thenExecuteAfter(4, () -> {
                    helper.assertTrue(powered(level, pos), "保持通电 4 tick 之后还是开着：中间一次都没再翻");
                    helper.assertTrue(answerAt(level, pos) == TriggerRule.ON, "答案一直是 15");
                    helper.assertTrue(level.getBlockState(pos).getValue(TriggerBlock.INPUT),
                            "边沿记忆记着「上一次看到的是有电」- 这就是没有第二个边的理由");
                })
                .thenExecute(() -> helper.setBlock(INPUT, Blocks.STONE.defaultBlockState()))
                .thenExecuteAfter(2, () -> {
                    helper.assertTrue(powered(level, pos), "断电那一下不切换：开着的还是开着");
                    helper.assertTrue(answerAt(level, pos) == TriggerRule.ON, "而且照样答 15");
                })
                .thenExecute(() -> helper.setBlock(INPUT, Blocks.REDSTONE_BLOCK.defaultBlockState()))
                .thenExecuteAfter(2, () -> {
                    helper.assertFalse(powered(level, pos), "第二条上升沿才把它关掉");
                    helper.assertTrue(answerAt(level, pos) == 0, "关掉就答 0");
                })
                .thenSucceed();
    }

    /**
     * 验收 2 的另一半: 输出只在背面 - 另外五个方向 (正面在内) 一律 0.
     *
     * <p>{@code side} 逐个数过六个方向, 只有 {@code FACING} 那一个 (也就是站在背面那个邻居的问法) 有答案.
     */
    @GameTest(template = "empty")
    public void onlyTheBackFaceAnswers(GameTestHelper helper) {
        BlockPos pos = placeFloorTrigger(helper, Direction.NORTH);
        ServerLevel level = helper.getLevel();
        helper.setBlock(INPUT, Blocks.REDSTONE_BLOCK.defaultBlockState());
        BlockState state = level.getBlockState(pos);
        Direction back = state.getValue(TriggerBlock.FACING);
        for (Direction side : Direction.values()) {
            int expected = side == back ? TriggerRule.ON : 0;
            int actual = state.getSignal(level, pos, side);
            helper.assertTrue(actual == expected, "方向 " + side + " 听到 " + actual + "，应该是 " + expected
                    + "（只有背面答 15：侧面的线、正面那条输入、以及上下一概不听）");
        }
        helper.succeed();
    }

    /**
     * 验收 2 的贴墙那一半: 对着墙点<b>放不下</b>, 而不是降级成贴地.
     *
     * <p>两个断言: 一个是那条纯规则 ({@code faceFor}), 一个是真拿一个点墙的 {@code BlockPlaceContext} 去问 - 后者才是
     * 方块物品在服务器上真正走的那条路.
     */
    @GameTest(template = "empty")
    public void aWallClickPlacesNothing(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        helper.setBlock(new BlockPos(2, 0, 2), Blocks.STONE.defaultBlockState());
        helper.setBlock(new BlockPos(2, 1, 2), Blocks.STONE.defaultBlockState());
        BlockPos wall = at(helper, 2, 1, 2);

        helper.assertTrue(TriggerBlock.faceFor(Direction.UP) == AttachFace.FLOOR, "点顶面 = 贴地");
        helper.assertTrue(TriggerBlock.faceFor(Direction.DOWN) == AttachFace.CEILING, "点底面 = 贴天花板");
        for (Direction side : Direction.Plane.HORIZONTAL) {
            helper.assertTrue(TriggerBlock.faceFor(side) == null, "点侧面 " + side + " = 不接受（这一件不做贴墙）");
        }

        BlockPlaceContext wallClick = contextOn(helper, wall, Direction.NORTH, wall);
        helper.assertTrue(RCRegistry.TRIGGER.get().getStateForPlacement(wallClick) == null,
                "对着墙点：没有状态可以放，物品留在手里（不降级成贴地）");

        BlockPlaceContext floorClick = contextOn(helper, wall.above(), Direction.UP, wall);
        BlockState placed = RCRegistry.TRIGGER.get().getStateForPlacement(floorClick);
        helper.assertTrue(placed != null && placed.getValue(TriggerBlock.FACE) == AttachFace.FLOOR,
                "而点顶面是贴地，实际 " + placed);
        helper.assertFalse(placed.getValue(TriggerBlock.POWERED), "刚放下的触发器是关着的");
        helper.succeed();
    }

    /** 一个"玩家点了 {@code clickedPos} 的 {@code clickedFace} 那一面"的放置上下文. */
    private static BlockPlaceContext contextOn(GameTestHelper helper, BlockPos clickedPos, Direction clickedFace,
                                               BlockPos hitBlock) {
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(hitBlock), clickedFace, clickedPos, false);
        return new BlockPlaceContext(helper.makeMockPlayer(GameType.CREATIVE), InteractionHand.MAIN_HAND,
                new ItemStack(RCRegistry.TRIGGER_ITEM.get()), hit);
    }

    /**
     * 验收 3: 塞进宿主方块, 输入面朝北 - 同样的上升沿序列得到同样的结果.
     *
     * <p>元件由世界那一趟 tick 推进 ({@code InnerRedstoneNetwork#tickTriggers}), 所以每一次都等两个 tick 再看: 一个 tick
     * 让它翻, 一个 tick 让传播跟上.
     */
    @GameTest(template = "empty")
    public void theStoredTriggerStepsTheSameEdges(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos host = at(helper, 3, 1, 2);
        helper.setBlock(new BlockPos(3, 1, 2), Blocks.STONE.defaultBlockState());
        InnerRedstoneStore store = InnerRedstoneStore.get(level);
        Slot slot = new Slot(ComponentType.TRIGGER);
        slot.facing = Direction.NORTH;
        store.getOrCreate(host).setSlot(slot);
        store.markDirty();

        Slot fresh = store.slotAt(host);
        helper.assertTrue(fresh != null && fresh.type == ComponentType.TRIGGER, "元件就在宿主方块里");
        helper.assertFalse(fresh.powered, "刚塞进去：关着");
        helper.assertFalse(fresh.triggerInput, "而且边沿记忆是「上一次读到没电」，与世界形态的默认 INPUT=false 一致");

        // 输入面 north = (3,1,1); 背面 south = (3,1,3): 站在背面的邻居问 getSignal(host, NORTH).
        helper.setBlock(new BlockPos(3, 1, 1), Blocks.REDSTONE_BLOCK.defaultBlockState());
        helper.startSequence()
                .thenExecuteAfter(2, () -> {
                    Slot now = store.slotAt(host);
                    helper.assertTrue(now.powered, "第一条上升沿把开关翻开");
                    helper.assertTrue(now.power == TriggerRule.ON, "输出强度 15，实际 " + now.power);
                    helper.assertTrue(level.getSignal(host, Direction.NORTH) == TriggerRule.ON,
                            "宿主替它答背面：15");
                    helper.assertTrue(level.getSignal(host, Direction.SOUTH) == 0, "正面不回答，也不回灌");
                })
                .thenExecuteAfter(3, () -> {
                    helper.assertTrue(store.slotAt(host).powered, "保持通电 3 tick：还是开着，中间没有第二个边");
                    helper.assertTrue(level.getSignal(host, Direction.NORTH) == TriggerRule.ON, "答案一直是 15");
                })
                .thenExecute(() -> helper.setBlock(new BlockPos(3, 1, 1), Blocks.STONE.defaultBlockState()))
                .thenExecuteAfter(2, () -> {
                    helper.assertTrue(store.slotAt(host).powered, "断电那一下不切换");
                    helper.assertTrue(level.getSignal(host, Direction.NORTH) == TriggerRule.ON, "照样答 15");
                })
                .thenExecute(() -> helper.setBlock(new BlockPos(3, 1, 1), Blocks.REDSTONE_BLOCK.defaultBlockState()))
                .thenExecuteAfter(2, () -> {
                    Slot off = store.slotAt(host);
                    helper.assertFalse(off.powered, "第二条上升沿才关掉");
                    helper.assertTrue(off.power == 0, "输出强度 0");
                    helper.assertTrue(level.getSignal(host, Direction.NORTH) == 0, "背面也不再回答");
                })
                .thenSucceed();
    }

    /**
     * 验收 3 的另一半: 内嵌形态可以竖着 - 输入面是<b>上</b>也照样成立 (世界形态只做贴地/贴天花板, 内嵌形态可以竖).
     *
     * <p>方向仍然是"读 facing 那一面, 写对面": {@code facing = UP} 时宿主答的是 {@code getSignal(host, UP)}, 也就是站在
     * 下面那个邻居的问法.
     */
    @GameTest(template = "empty")
    public void aStoredTriggerReadsUpwards(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos host = at(helper, 3, 1, 2);
        helper.setBlock(new BlockPos(3, 1, 2), Blocks.STONE.defaultBlockState());
        InnerRedstoneStore store = InnerRedstoneStore.get(level);
        Slot slot = new Slot(ComponentType.TRIGGER);
        slot.facing = Direction.UP;
        store.getOrCreate(host).setSlot(slot);
        store.markDirty();

        helper.setBlock(new BlockPos(3, 2, 2), Blocks.REDSTONE_BLOCK.defaultBlockState());
        helper.startSequence()
                .thenExecuteAfter(2, () -> {
                    Slot now = store.slotAt(host);
                    helper.assertTrue(now.powered, "输入面朝上：上面来了电，开关翻开");
                    helper.assertTrue(level.getSignal(host, Direction.UP) == TriggerRule.ON,
                            "答案从对面（下面）出去：15");
                    helper.assertTrue(level.getSignal(host, Direction.DOWN) == 0, "输入那一面（上）不回答");
                })
                .thenExecute(() -> helper.setBlock(new BlockPos(3, 2, 2), Blocks.STONE.defaultBlockState()))
                .thenExecuteAfter(2, () -> helper.assertTrue(store.slotAt(host).powered, "断电不切换"))
                .thenExecute(() -> helper.setBlock(new BlockPos(3, 2, 2), Blocks.REDSTONE_BLOCK.defaultBlockState()))
                .thenExecuteAfter(2, () -> {
                    helper.assertFalse(store.slotAt(host).powered, "第二条上升沿关掉");
                    helper.assertTrue(level.getSignal(host, Direction.UP) == 0, "而背面答 0");
                })
                .thenSucceed();
    }

    /** 验收 5: 方块状态一共 32 个 - face 2 × facing 4 × powered 2 × input 2, 资源那边按这个数写. */
    @GameTest(template = "empty")
    public void theBlockStateHasThirtyTwoCombinations(GameTestHelper helper) {
        int states = RCRegistry.TRIGGER.get().getStateDefinition().getPossibleStates().size();
        helper.assertTrue(states == 32, "face(floor/ceiling) × facing(4) × powered × input = 2 × 4 × 2 × 2 = 32，实际 "
                + states);
        BlockState fresh = RCRegistry.TRIGGER.get().defaultBlockState();
        helper.assertTrue(fresh.getValue(TriggerBlock.FACE) == AttachFace.FLOOR, "默认贴地");
        helper.assertTrue(fresh.getValue(TriggerBlock.FACING) == Direction.NORTH, "默认正面朝北");
        helper.assertFalse(fresh.getValue(TriggerBlock.POWERED), "默认关着");
        helper.assertFalse(fresh.getValue(TriggerBlock.INPUT), "默认「上一次读到没电」- 于是第一趟读数就是一条上升沿");
        helper.succeed();
    }

}

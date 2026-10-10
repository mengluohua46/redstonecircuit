package com.jiangyuefengyu.redstonecircuit;

import com.jiangyuefengyu.redstonecircuit.block.RcPlateBlock;
import com.jiangyuefengyu.redstonecircuit.block.TransformerBlock;
import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneStore;
import com.jiangyuefengyu.redstonecircuit.data.Slot;
import com.jiangyuefengyu.redstonecircuit.logic.InnerRedstoneNetwork;
import com.jiangyuefengyu.redstonecircuit.logic.PowerSolver;
import com.jiangyuefengyu.redstonecircuit.logic.TransformerRule;

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
 * <b>红石变压器 in a real level - the one rule, the face that answers, and the plate's own shape.</b>
 *
 * <p>规则: 无论输入强度是多少，输出都是设定值；没有输入时输出 0。输入用 mod 自己的元件（写在方块内部，
 * {@code InnerRedstoneStore}），因为原版线会在下一 tick 重算写进去的值，而内部粉尘保持注入强度、又照常回答
 * 世界的信号查询，所以 3 就是 3 - 这正是"规则不看输入大小"的器件所需要的前提。
 *
 * <p>面方向从两侧问 ({@code state.getSignal}): 只看答案的测试会漏掉"答案从正面出来"，那会驱动玩家建错边的线。
 */
@GameTestHolder(RedstoneCircuit.MODID)
@PrefixGameTestTemplate(false)
public final class TransformerGameTest {

    private static BlockPos at(GameTestHelper helper, int x, int y, int z) {
        return helper.absolutePos(new BlockPos(x, y, z));
    }

    /** Places inner dust at an absolute position with a chosen strength, the way {@code /rc place} injects one. */
    private static void placeDust(GameTestHelper helper, BlockPos pos, int power) {
        ServerLevel level = helper.getLevel();
        InnerRedstoneStore store = InnerRedstoneStore.get(level);

        Slot slot = new Slot(ComponentType.DUST);
        slot.power = power;
        if (power > 0) {
            slot.injectedPower = power;
        }
        store.getOrCreate(pos).setSlot(slot);
        store.markDirty();
        InnerRedstoneNetwork.markDirtyWithNeighbours(level, pos);
        InnerRedstoneInteraction.notifyNeighbours(level, pos);
    }

    /** A plate at (2,1,2) set to {@code setting}, with its 正面 pointing {@code facing}. */
    private static BlockPos placeTransformer(GameTestHelper helper, Direction facing, int setting) {
        helper.setBlock(new BlockPos(2, 1, 2), RCRegistry.TRANSFORMER.get().defaultBlockState()
                .setValue(TransformerBlock.FACING, facing)
                .setValue(TransformerBlock.SETTING, setting));
        return at(helper, 2, 1, 2);
    }

    /** Feeds the front face (one cell north of the plate when 正面 = north) with a chosen strength. */
    private static void feedFront(GameTestHelper helper, int power) {
        ServerLevel level = helper.getLevel();
        BlockPos front = at(helper, 2, 1, 1);
        level.setBlock(front, Blocks.STONE.defaultBlockState(), 3);
        placeDust(helper, front, power);
    }

    /** <b>A weak input comes out at the set strength</b>, and it is the <b>back</b> that answers. Three rather than
     * fifteen: any device that copied its input would pass at fifteen, and this one only passes at three. */
    @GameTest(template = "empty")
    public void theSettingIgnoresTheInputsSize(GameTestHelper helper) {
        BlockPos plate = placeTransformer(helper, Direction.NORTH, 7);
        feedFront(helper, 3);

        TransformerBlock.announce(helper.getLevel(), plate);
        BlockState state = helper.getLevel().getBlockState(plate);

        helper.assertTrue(TransformerBlock.answerAt(helper.getLevel(), plate, state) == 7,
                "输入 3、设定 7：要输出 7，但方块说 " + TransformerBlock.answerAt(helper.getLevel(), plate, state));
        helper.assertTrue(state.getValue(TransformerBlock.LIT), "and the display is lit while it answers");
        helper.assertTrue(state.getSignal(helper.getLevel(), plate, Direction.NORTH) == 7,
                "asked from behind (the 背面), the plate answers 7");
        helper.assertTrue(state.getSignal(helper.getLevel(), plate, Direction.SOUTH) == 0,
                "asked from in front (its own input), it answers nothing - a device does not feed its own input");
        helper.assertTrue(state.getSignal(helper.getLevel(), plate, Direction.EAST) == 0,
                "and a side hears nothing back either");
        helper.succeed();
    }

    /** No input means the output is 0, and the display goes dark with it. */
    @GameTest(template = "empty")
    public void nothingAtTheFrontIsNothingAtTheBack(GameTestHelper helper) {
        BlockPos plate = placeTransformer(helper, Direction.NORTH, 15);
        feedFront(helper, 0);

        TransformerBlock.announce(helper.getLevel(), plate);
        BlockState state = helper.getLevel().getBlockState(plate);

        helper.assertTrue(TransformerBlock.answerAt(helper.getLevel(), plate, state) == 0,
                "没有输入就必须输出 0，哪怕设定是 15");
        helper.assertFalse(state.getValue(TransformerBlock.LIT), "and the display is not lit");

        // And the same plate answers 15 the moment anything arrives - the other half of the rule, on one block.
        feedFront(helper, 1);
        TransformerBlock.announce(helper.getLevel(), plate);
        helper.assertTrue(TransformerBlock.answerAt(helper.getLevel(), plate,
                        helper.getLevel().getBlockState(plate)) == 15,
                "输入 1（最弱的活信号）就要输出设定值 15");
        helper.succeed();
    }

    /** The screen's write: the setting lands on the block and the answer follows in the same tick. */
    @GameTest(template = "empty")
    public void theScreensWriteMovesTheAnswer(GameTestHelper helper) {
        BlockPos plate = placeTransformer(helper, Direction.NORTH, 15);
        feedFront(helper, 5);
        TransformerBlock.announce(helper.getLevel(), plate);

        helper.assertTrue(TransformerBlock.applySettings(helper.getLevel(), plate, 4),
                "界面的写入必须落到方块状态上");
        helper.assertTrue(helper.getLevel().getBlockState(plate).getValue(TransformerBlock.SETTING) == 4,
                "设定值要照着写进去");
        helper.assertTrue(TransformerBlock.answerAt(helper.getLevel(), plate,
                        helper.getLevel().getBlockState(plate)) == 4,
                "and the answer follows it without a second poke");
        helper.assertFalse(TransformerBlock.applySettings(helper.getLevel(), plate, 0),
                "0 不是一个输出强度：越界的写入必须被拒绝，而不是夹成 1 之后悄悄改掉玩家的设定");
        helper.assertTrue(TransformerBlock.applySettings(helper.getLevel(), plate, 1),
                "而下界 1 是合法的");
        helper.succeed();
    }

    /** The plate itself: one eighth of a block thick, not a full cube, so a component can never be stored inside it -
     * the "可放入方块" rule read the other way round - and the setting it starts with. */
    @GameTest(template = "empty")
    public void thePlateIsAnEighthOfABlockAndNeverAHost(GameTestHelper helper) {
        BlockState fresh = RCRegistry.TRANSFORMER.get().defaultBlockState();
        helper.assertTrue(fresh.getValue(TransformerBlock.SETTING) == TransformerRule.DEFAULT,
                "放下就能用的默认值：输出 15（任何真信号都按满强度输出）");
        helper.assertFalse(fresh.getValue(TransformerBlock.LIT), "and a fresh plate is not lit");

        BlockPos pos = at(helper, 2, 1, 2);
        helper.setBlock(new BlockPos(2, 1, 2), fresh);
        BlockState placed = helper.getLevel().getBlockState(pos);

        double top = placed.getShape(helper.getLevel(), pos).max(Direction.Axis.Y);
        helper.assertTrue(Math.abs(top - 0.125) < 1e-6, "薄板高 1/8 格（0.125），量到的是 " + top);
        helper.assertFalse(placed.isCollisionShapeFullBlock(helper.getLevel(), pos),
                "and it is not a full cube, which is what the host rule reads");
        helper.assertFalse(HostRules.isValidHost(placed),
                "所以红石元件塞不进它里面 - 它自己六面已有归属");
        helper.succeed();
    }

    /** The same rule for a transformer stored inside a stone block: the component form reads the face it was mounted
     * on ({@code Slot#facing}, taken from the click) and answers on the opposite one, exactly like the plate - one rule
     * ({@link TransformerRule}) asked at two addresses. The chain asserted is the setting, the solver's answer, the
     * one-way direction, and the host block's own reply to the world. */
    @GameTest(template = "empty")
    public void theTransformerAlsoWorksInsideAHostBlock(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos host = at(helper, 2, 1, 2);
        helper.setBlock(new BlockPos(2, 1, 2), Blocks.STONE.defaultBlockState());

        Slot slot = new Slot(ComponentType.TRANSFORMER);
        // 正面 = east (the face it was placed on), so it reads east and answers on 背面 = west.
        slot.facing = Direction.EAST;
        slot.transformer = 9;
        InnerRedstoneStore store = InnerRedstoneStore.get(level);
        store.getOrCreate(host).setSlot(slot);
        store.markDirty();

        // The front face carries 2 - any live signal at all, which is the whole of this device's condition.
        helper.setBlock(new BlockPos(3, 1, 2), Blocks.STONE.defaultBlockState());
        placeDust(helper, at(helper, 3, 1, 2), 2);
        InnerRedstoneNetwork.markDirtyWithNeighbours(level, host);
        InnerRedstoneInteraction.notifyNeighbours(level, host);
        InnerRedstoneNetwork.recompute(level, store, host);

        Slot after = InnerRedstoneStore.get(level).slotAt(host);
        helper.assertTrue(after != null && after.power == 9,
                "输入 2、设定 9 ⇒ 输出 9，实际是 " + (after == null ? "没有元件" : Integer.toString(after.power)));
        helper.assertTrue(after != null && PowerSolver.emitsToward(after, Direction.WEST), "答案从 背面（西）出去");
        helper.assertTrue(after != null && !PowerSolver.emitsToward(after, Direction.EAST),
                "不往正面回灌 - 它是单向的，和世界里的薄板一样");
        helper.assertTrue(level.getSignal(host, Direction.EAST) == 9,
                "宿主方块替它回答西边（问句 travelled east）：9，实际 " + level.getSignal(host, Direction.EAST));
        helper.assertTrue(level.getSignal(host, Direction.WEST) == 0, "而正面那一边听不到自己的答案");

        // The screen shows what the solve read, recorded where it read it: re-deriving the input with a level query
        // would use the world's frame instead.
        helper.assertTrue(RcPlateBlock.hostReading(level, host, after) == 2,
                "界面上的「输入」必须是求解器读到的那一个（2），实际 " + RcPlateBlock.hostReading(level, host, after));

        // And the input going away takes the answer with it, in the same solve.
        placeDust(helper, at(helper, 3, 1, 2), 0);
        InnerRedstoneNetwork.recompute(level, store, host);
        helper.assertTrue(store.slotAt(host).power == 0,
                "输入没了 ⇒ 输出 0，实际 " + store.slotAt(host).power);
        helper.assertTrue(RcPlateBlock.hostReading(level, host, store.slotAt(host)) == 0,
                "读数也要跟着回到 0");
        helper.succeed();
    }

    /** What the host screen's write has to reach: the setting on the stored component, and the answer the solver
     * derives from it. The click and the packet are not testable here, but the write they end in is. */
    @GameTest(template = "empty")
    public void theHostScreensWriteReachesTheStoredComponent(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos host = at(helper, 2, 1, 2);
        helper.setBlock(new BlockPos(2, 1, 2), Blocks.STONE.defaultBlockState());

        InnerRedstoneStore store = InnerRedstoneStore.get(level);
        Slot slot = new Slot(ComponentType.TRANSFORMER);
        slot.facing = Direction.EAST;
        store.getOrCreate(host).setSlot(slot);
        store.markDirty();

        helper.assertTrue(TransformerBlock.applyHostSettings(level, host, store.slotAt(host), 6),
                "界面的写入必须落到方块里那个元件上");
        helper.assertTrue(store.slotAt(host) != null && store.slotAt(host).transformer == 6,
                "设定值要照着写进去，实际 " + (store.slotAt(host) == null ? "没有元件"
                        : Integer.toString(store.slotAt(host).transformer)));
        helper.assertFalse(TransformerBlock.applyHostSettings(level, host, store.slotAt(host), 16),
                "越界的写入要被拒绝，元件必须原地不动");
        helper.assertTrue(store.slotAt(host).transformer == 6, "and it did not move");

        helper.setBlock(new BlockPos(3, 1, 2), Blocks.STONE.defaultBlockState());
        placeDust(helper, at(helper, 3, 1, 2), 4);
        InnerRedstoneNetwork.recompute(level, store, host);
        helper.assertTrue(store.slotAt(host).power == 6, "输入 4、设定 6 ⇒ 输出 6，实际 " + store.slotAt(host).power);
        helper.succeed();
    }
}

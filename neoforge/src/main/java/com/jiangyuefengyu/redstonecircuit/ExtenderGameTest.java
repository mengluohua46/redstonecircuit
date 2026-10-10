package com.jiangyuefengyu.redstonecircuit;

import com.jiangyuefengyu.redstonecircuit.block.ExtenderBlock;
import com.jiangyuefengyu.redstonecircuit.block.RcPlateBlock;
import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneStore;
import com.jiangyuefengyu.redstonecircuit.data.Slot;
import com.jiangyuefengyu.redstonecircuit.logic.ExtenderDeadlines;
import com.jiangyuefengyu.redstonecircuit.logic.ExtenderRule;
import com.jiangyuefengyu.redstonecircuit.logic.InnerRedstoneNetwork;

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
 * <b>信号延长器 in a real level - 跟随、保持、以及只有背面回答.</b>
 *
 * <p>两台半: 世界里的薄板（自己的 tick 走查推进保持）与方块内那一个（世界那一趟推进）。输入用 mod 自己的内部粉尘写,
 * 和 /rc place 的注入口径一样 - 原版线会在下一 tick 重算写进去的值, 内部粉尘保持注入强度又照常回答世界的信号查询,
 * 所以"7 进去 7 出来"是被真问出来的, 不是被假设的。
 *
 * <p>tick 数由 {@code ExtenderRuleTest} 钉在规则层; 这里钉的是机制: 保持真的会自己结束（没有任何通知）、墙上的亮灭跟着
 * 答案、以及宿主方块替方块里那一个把答案说给世界。
 */
@GameTestHolder(RedstoneCircuit.MODID)
@PrefixGameTestTemplate(false)
public final class ExtenderGameTest {

    /** 正面朝北时, 输入格、输出格在 (2,1,2) 的哪一侧. */
    private static final Direction FRONT = Direction.NORTH;

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

    /** A plate at (2,1,2) with 正面 = {@code facing} and the given two numbers. */
    private static BlockPos placeExtender(GameTestHelper helper, Direction facing, ExtenderRule.Setting setting) {
        helper.setBlock(new BlockPos(2, 1, 2), RCRegistry.EXTENDER.get().defaultBlockState()
                .setValue(ExtenderBlock.FACING, facing));
        BlockPos plate = at(helper, 2, 1, 2);
        ExtenderBlock.applySettings(helper.getLevel(), plate, setting);
        return plate;
    }

    /** 正面那一格收到一个选定强度（0 = 没有输入）。 */
    private static void feedFront(GameTestHelper helper, int power) {
        helper.setBlock(new BlockPos(2, 1, 1), Blocks.STONE.defaultBlockState());
        placeDust(helper, at(helper, 2, 1, 1), power);
    }

    /** 这一次问到的答案, 走的是方块自己那条查询路. */
    private static int askedFromBehind(GameTestHelper helper, BlockPos plate) {
        return helper.getLevel().getBlockState(plate).getSignal(helper.getLevel(), plate, FRONT);
    }

    @GameTest(template = "empty")
    public void theAnswerIsTheInputsOwnStrengthAndOnlyTheBackSaysIt(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos plate = placeExtender(helper, FRONT, new ExtenderRule.Setting(0, 6));
        feedFront(helper, 7);
        ExtenderBlock.announce(level, plate);

        BlockState state = level.getBlockState(plate);
        helper.assertTrue(ExtenderBlock.answerAt(level, plate, state) == 7,
                "输入 7 就要输出 7（原样通过, 不整形）, 实际 " + ExtenderBlock.answerAt(level, plate, state));
        helper.assertTrue(state.getValue(ExtenderBlock.LIT), "有输出就亮着（模型靠它挑贴图）");
        helper.assertTrue(askedFromBehind(helper, plate) == 7, "背面回答 7");
        helper.assertTrue(state.getSignal(level, plate, FRONT.getOpposite()) == 0,
                "正面不回答 - 器件不往自己的输入回灌");
        helper.assertTrue(state.getSignal(level, plate, Direction.EAST) == 0
                        && state.getSignal(level, plate, Direction.WEST) == 0,
                "两个侧面也不回答");
        helper.succeed();
    }

    /**
     * 那条例子的机制这一半：输入的那一刻把截止时刻推到 {@code now + 设定时长}, 输入消失之后**没有任何通知**,
     * 保持仍然按那个时刻自己结束（世界里那件靠 {@link ExtenderBlock#tickStanding}, 每关卡 tick 一次）。
     */
    @GameTest(template = "empty")
    public void theHoldKeepsTheAnswerAndThenStopsOnItsOwn(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos plate = placeExtender(helper, FRONT, new ExtenderRule.Setting(0, 6));
        feedFront(helper, 9);
        ExtenderBlock.announce(level, plate);

        long now = level.getGameTime();
        long deadline = ExtenderDeadlines.get(level, plate);
        helper.assertTrue(deadline == now + 6,
                "输入在的这一刻, 截止时刻就是 now + 6 秒的 tick 数, 实际差 " + (deadline - now));
        helper.assertTrue(askedFromBehind(helper, plate) == 9, "输入还在, 输出 9");

        // 输入走了：世界不再通知任何东西, 剩下的全是时间的事。
        feedFront(helper, 0);
        ExtenderBlock.announce(level, plate);
        helper.assertTrue(askedFromBehind(helper, plate) == 9,
                "输入刚走的这一 tick 输出还在 - 这就是「停下时再延长设定时长」");

        watchHold(helper, plate, 9, deadline);
    }

    /** 一 tick 一看, 直到截止时刻过去：保持期间一直回答那个强度, 过了那一刻自己变 0，亮灭同步。 */
    private static void watchHold(GameTestHelper helper, BlockPos plate, int strength, long deadline) {
        helper.runAfterDelay(1, () -> {
            ServerLevel level = helper.getLevel();
            long now = level.getGameTime();
            int power = ExtenderBlock.answerAt(level, plate, level.getBlockState(plate));
            helper.assertTrue(level.getBlockState(plate).getValue(ExtenderBlock.LIT) == (power > 0),
                    "亮灭必须和答案一致");
            if (now < deadline) {
                helper.assertTrue(power == strength,
                        "截止时刻 " + deadline + " 之前（现在 " + now + "）必须一直是 " + strength + "，实际 " + power);
                watchHold(helper, plate, strength, deadline);
                return;
            }
            // 世界那一趟在 tick 末尾写答案, 测试是在 tick 中间看的, 所以截止那一刻前后允许差一 tick。
            if (now <= deadline + 1) {
                helper.assertTrue(power == 0 || power == strength,
                        "截止那一刻（" + deadline + "）前后只该是这两种数, 实际 " + power + "，现在 " + now);
                watchHold(helper, plate, strength, deadline);
                return;
            }
            helper.assertTrue(power == 0, "截止时刻一过就是 0，实际 " + power);
            helper.succeed();
        });
    }

    /** 0 秒 0 刻 = 不延长：输入在就原样出去, 输入一 tick 都不多留。 */
    @GameTest(template = "empty")
    public void zeroSecondsZeroTicksExtendsNothing(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos plate = placeExtender(helper, FRONT, ExtenderRule.Setting.of(0, 0));
        feedFront(helper, 5);
        ExtenderBlock.announce(level, plate);
        helper.assertTrue(askedFromBehind(helper, plate) == 5, "输入在, 原样输出 5");

        feedFront(helper, 0);
        ExtenderBlock.announce(level, plate);
        helper.assertTrue(askedFromBehind(helper, plate) == 0,
                "0 秒 0 刻不延长：输入走了输出就走, 实际 " + askedFromBehind(helper, plate));
        helper.assertFalse(level.getBlockState(plate).getValue(ExtenderBlock.LIT), "灯也灭");
        helper.succeed();
    }

    /** 两个数字不住在方块状态里：朝向 4 × 亮 2 = 8 个状态, 秒和刻在方块旁边那张表里, 且照范围夹取。 */
    @GameTest(template = "empty")
    public void theSettingsLiveBesideTheBlockAndNotInTheState(GameTestHelper helper) {
        int states = RCRegistry.EXTENDER.get().getStateDefinition().getPossibleStates().size();
        helper.assertTrue(states == 8, "朝向 4 × 亮 2 = 8 个状态。秒 0-3600 × 刻 20 要是进了状态就是 57 万个，实际 "
                + states);

        BlockPos plate = placeExtender(helper, FRONT, new ExtenderRule.Setting(2, 5));
        helper.assertTrue(ExtenderBlock.settingAt(helper.getLevel(), plate).equals(new ExtenderRule.Setting(2, 5)),
                "两个数要在方块旁边那张表里, 读到的是 " + ExtenderBlock.settingAt(helper.getLevel(), plate));

        ExtenderBlock.applySettings(helper.getLevel(), plate, new ExtenderRule.Setting(99_999, 99));
        ExtenderRule.Setting clamped = ExtenderBlock.settingAt(helper.getLevel(), plate);
        helper.assertTrue(clamped.equals(new ExtenderRule.Setting(3600, 19)), "越界的数字要夹进范围，实际 " + clamped);
        // 而屏幕送来的越界字段是拒绝, 不是夹取：0 秒 0 刻 是合法设定, 夹取会把玩家的意思改掉。
        helper.assertTrue(ExtenderBlock.wanted(3601, 0) == null && ExtenderBlock.wanted(0, 20) == null
                        && ExtenderBlock.wanted(0, 0) != null && ExtenderBlock.wanted(3600, 19) != null,
                "解码/屏幕这一侧：越界拒绝, 边界合法");
        helper.succeed();
    }

    /** 可放入方块内: 方块里那一件同样跟随输入, 保持同样由世界那一趟自己结束。 */
    @GameTest(template = "empty")
    public void theStoredExtenderFollowsItsInputAndHoldsToo(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos host = at(helper, 2, 1, 2);
        helper.setBlock(new BlockPos(2, 1, 2), Blocks.STONE.defaultBlockState());

        Slot slot = new Slot(ComponentType.EXTENDER);
        // 正面 = east（它被装上的那一面）, 所以读东边、从西边（背面）回答。
        slot.facing = Direction.EAST;
        slot.extender = new ExtenderRule.Setting(0, 5);
        InnerRedstoneStore store = InnerRedstoneStore.get(level);
        store.getOrCreate(host).setSlot(slot);
        store.markDirty();

        helper.setBlock(new BlockPos(3, 1, 2), Blocks.STONE.defaultBlockState());
        placeDust(helper, at(helper, 3, 1, 2), 6);
        InnerRedstoneNetwork.recompute(level, store, host);

        Slot after = store.slotAt(host);
        helper.assertTrue(after != null && after.power == 6, "输入 6 ⇒ 输出 6, 实际 "
                + (after == null ? "没有元件" : Integer.toString(after.power)));
        helper.assertTrue(level.getSignal(host, Direction.EAST) == 6,
                "宿主方块替它回答背面（问句 travelled east）：6，实际 " + level.getSignal(host, Direction.EAST));
        helper.assertTrue(level.getSignal(host, Direction.WEST) == 0, "正面那一边听不到自己的答案");
        helper.assertTrue(RcPlateBlock.hostReading(level, host, after) == 6, "界面上的「输入」是求解器读到的那一个");

        // 输入要活过一个 tick：方块内那一个唯一的驱动者是世界那一趟, 它每 tick 才看输入一次, 截止时刻就是它写下的。
        helper.runAfterDelay(1, () -> {
            placeDust(helper, at(helper, 3, 1, 2), 0);
            InnerRedstoneNetwork.recompute(level, store, host);
            helper.assertTrue(store.slotAt(host).power == 6,
                    "输入没了, 输出要留着（保持）, 实际 " + store.slotAt(host).power);
            watchStoredHold(helper, host, 6, 5, level.getGameTime(), new int[2]);
        });
    }

    /** 方块内那一件的一 tick 一看: 保持期间一直是那个强度, 然后自己变成 0（那趟走查不靠任何通知）。 */
    private static void watchStoredHold(GameTestHelper helper, BlockPos host, int strength, int hold,
                                        long fallTick, int[] counts) {
        ServerLevel level = helper.getLevel();
        long now = level.getGameTime();
        Slot slot = InnerRedstoneStore.get(level).slotAt(host);
        int power = slot == null ? -1 : slot.power;
        if (power > 0) {
            helper.assertTrue(power == strength, "保持期间只能回答那个强度, 实际 " + power);
            counts[0]++;
        } else {
            counts[1]++;
        }
        if (now <= fallTick + hold + 2) {
            helper.runAfterDelay(1, () -> watchStoredHold(helper, host, strength, hold, fallTick, counts));
            return;
        }
        // 世界那一趟每 tick 才看输入一次, 所以它记下的时刻可以差一 tick。
        helper.assertTrue(counts[0] >= hold - 1 && counts[0] <= hold + 1,
                "输入走后应当再保持约 " + hold + " tick, 实际 " + counts[0] + " tick");
        helper.assertTrue(counts[1] >= 1, "而它必须真的结束（没有任何通知会来做这件事）");
        helper.assertTrue(level.getSignal(host, Direction.EAST) == 0, "宿主方块也不再回答");
        helper.succeed();
    }

    /** 界面写到方块里那一个：两个数落到元件上, 越界拒绝, 而跟随与保持都由求解器/世界那一趟接管。 */
    @GameTest(template = "empty")
    public void theHostScreensWriteReachesTheStoredExtender(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos host = at(helper, 2, 1, 2);
        helper.setBlock(new BlockPos(2, 1, 2), Blocks.STONE.defaultBlockState());

        InnerRedstoneStore store = InnerRedstoneStore.get(level);
        Slot slot = new Slot(ComponentType.EXTENDER);
        slot.facing = Direction.EAST;
        store.getOrCreate(host).setSlot(slot);
        store.markDirty();

        helper.assertTrue(ExtenderBlock.applyHostSettings(level, host, store.slotAt(host),
                        new ExtenderRule.Setting(2, 5)),
                "界面的写入必须落到方块里那个元件上");
        helper.assertTrue(store.slotAt(host).extender.equals(new ExtenderRule.Setting(2, 5)),
                "两个数要照着写进去, 实际 " + store.slotAt(host).extender);
        helper.assertTrue(store.slotAt(host).extender.holdTicks() == 45, "2 秒 5 刻 = 45 tick");
        helper.assertFalse(ExtenderBlock.applyHostSettings(level, host, store.slotAt(host), null),
                "没有设定就得拒绝, 而不是把元件改成默认值");
        helper.assertTrue(store.slotAt(host).extender.equals(new ExtenderRule.Setting(2, 5)), "元件原地不动");
        helper.succeed();
    }

    /** 薄板高 1/8 格、永远不是宿主, 以及放下就有默认设定（捕获时靠那张表把它带走）。 */
    @GameTest(template = "empty")
    public void thePlateIsAnEighthOfABlockAndNeverAHost(GameTestHelper helper) {
        BlockState fresh = RCRegistry.EXTENDER.get().defaultBlockState();
        helper.assertTrue(fresh.getValue(ExtenderBlock.FACING) == Direction.NORTH, "默认正面朝北");
        helper.assertFalse(fresh.getValue(ExtenderBlock.LIT), "and a fresh plate is not lit");
        helper.assertTrue(ExtenderRule.Setting.DEFAULT.equals(ExtenderBlock.settingAt(helper.getLevel(),
                        at(helper, 2, 1, 2))), "没有存过设定的地方读到的是默认值（1 秒）");

        BlockPos pos = at(helper, 2, 1, 2);
        helper.setBlock(new BlockPos(2, 1, 2), fresh);
        BlockState placed = helper.getLevel().getBlockState(pos);

        double top = placed.getShape(helper.getLevel(), pos).max(Direction.Axis.Y);
        helper.assertTrue(Math.abs(top - 0.125) < 1e-6, "薄板高 1/8 格（0.125），量到的是 " + top);
        helper.assertFalse(placed.isCollisionShapeFullBlock(helper.getLevel(), pos),
                "and it is not a full cube, which is what the host rule reads");
        helper.assertFalse(HostRules.isValidHost(placed), "所以红石元件塞不进它里面 - 它自己六面已有归属");
        helper.assertTrue(InnerRedstoneStore.get(helper.getLevel()).extenderAt(pos) != null,
                "放下一个延长器就会给它写一份默认设定：捕获时靠这张表把它带走（StructureCapture#capture）");
        helper.succeed();
    }
}

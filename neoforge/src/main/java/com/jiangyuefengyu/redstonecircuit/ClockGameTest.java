package com.jiangyuefengyu.redstonecircuit;

import com.jiangyuefengyu.redstonecircuit.block.ClockBlock;
import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneStore;
import com.jiangyuefengyu.redstonecircuit.data.Slot;
import com.jiangyuefengyu.redstonecircuit.logic.ClockRule;

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
 * 时钟源 in a real level: it answers to time, and it has to do that on its own.
 *
 * <p>Its output moves with <b>no input at all</b>, so what has to be proved is the <b>mechanism</b>: that
 * something turns the device over every period in both of its forms - the plate standing in the world and
 * the component inside a host block. Both are asserted by watching it tick, a four-tick period over
 * sixteen level ticks producing about eight pulses. Exact phases are not asserted here (the world's clock
 * is nobody's choice); the phase is pinned in {@code ClockRuleTest}.
 */
@GameTestHolder(RedstoneCircuit.MODID)
@PrefixGameTestTemplate(false)
public final class ClockGameTest {

    /** The period the ticking tests use: 0 秒 + 4 tick, so four ticks are one whole cycle. */
    private static final ClockRule.Setting FAST = new ClockRule.Setting(0, 4, 10);

    /** How many level ticks those tests watch it for: four whole cycles. */
    private static final int WATCHED_TICKS = 16;

    private static BlockPos at(GameTestHelper helper, int x, int y, int z) {
        return helper.absolutePos(new BlockPos(x, y, z));
    }

    /** Places a clock plate at (2,1,2) with its 正面 pointing {@code facing}, and sets its three numbers. */
    private static BlockPos placeClock(GameTestHelper helper, Direction facing, ClockRule.Setting setting) {
        helper.setBlock(new BlockPos(2, 1, 2), RCRegistry.CLOCK.get().defaultBlockState()
                .setValue(ClockBlock.FACING, facing));
        BlockPos plate = at(helper, 2, 1, 2);
        ClockBlock.applySettings(helper.getLevel(), plate, setting);
        return plate;
    }

    /**
     * Whether what was observed is what the rule says at this moment, allowing one tick of lag: the block
     * changes its answer during the level tick and the test looks from outside it. Any older answer fails.
     */
    private static boolean agrees(int observed, ClockRule.Setting setting, long now) {
        boolean on = observed > 0;
        return on == ClockRule.isOn(now, setting.period())
                || on == ClockRule.isOn(now - 1, setting.period());
    }

    /**
     * The plate ticks on its own and only 背面 answers. What is watched is the phase, not a count: every tick
     * the answer must be what the rule gives for that tick of the world clock, and the three other faces are
     * asked too, because an answer out of 正面 or a side would light a wire on the wrong side.
     */
    @GameTest(template = "empty")
    public void thePlateEmitsOnItsBackAndOnlyItsBack(GameTestHelper helper) {
        BlockPos plate = placeClock(helper, Direction.NORTH, FAST);
        watch(helper, plate, FAST, WATCHED_TICKS, new int[4]);
    }

    private static void watch(GameTestHelper helper, BlockPos plate, ClockRule.Setting setting, int left,
                              int[] counters) {
        helper.runAfterDelay(1, () -> {
            ServerLevel level = helper.getLevel();
            BlockState state = level.getBlockState(plate);
            long now = level.getGameTime();
            helper.assertTrue(state.getBlock() instanceof ClockBlock,
                    "时钟源必须还在那里，实际是 " + state);

            int power = state.getSignal(level, plate, Direction.NORTH);
            counters[3]++;
            if (power > 0) {
                counters[0]++;
            }
            helper.assertTrue(power == 0 || power == setting.strength(),
                    "背面只回答两种数：设定强度或 0，实际 " + power);
            helper.assertTrue(state.getValue(ClockBlock.LIT) == (power > 0),
                    "亮灭必须和答案一致（上表面亮 = 正在输出），tick=" + now);
            helper.assertTrue(state.getSignal(level, plate, Direction.SOUTH) == 0,
                    "正面（朝向的那一面）永远不回答 - 它没有输入，也不回灌");
            helper.assertTrue(state.getSignal(level, plate, Direction.EAST) == 0
                            && state.getSignal(level, plate, Direction.WEST) == 0,
                    "两个侧面也不回答");
            note(counters, now, state.getValue(ClockBlock.LIT), ClockRule.isOn(now, setting.period()));
            if (!agrees(power, setting, now)) {
                counters[2]++;
            }

            if (left <= 1) {
                endWatch(helper, counters);
                return;
            }
            watch(helper, plate, setting, left - 1, counters);
        });
    }

    /**
     * Records one look, and counts the ones that disagree with the rule.
     *
     * <p>The first twenty looks are printed: a bare failure number cannot tell "the chain is dead" from "the
     * phase is off", and the tick, the phase and both values can.
     */
    private static void note(int[] counters, long now, boolean lit, boolean expected) {
        if (counters[3] <= 20) {
            RCConfig.LOGGER.info("[redstonecircuit] clock watch {}: t={} lit={} rule={}",
                    counters[3], now, lit, expected);
        }
    }

    /** The window is over: every look has to have agreed with the rule, up to the one tick between the two. */
    private static void endWatch(GameTestHelper helper, int[] counters) {
        helper.assertTrue(counters[2] == 0,
                "每一 tick 的答案都必须是世界时钟那一刻的答案（允许差一 tick）：" + counters[3] + " 次查看里有 "
                        + counters[2] + " 次对不上，其中 " + counters[0] + " 次是有输出的");
        helper.succeed();
    }

    /** The settings are not block state: two properties, eight states, and the three numbers read from beside the block. */
    @GameTest(template = "empty")
    public void theSettingsLiveBesideTheBlockAndNotInTheState(GameTestHelper helper) {
        int states = RCRegistry.CLOCK.get().getStateDefinition().getPossibleStates().size();
        helper.assertTrue(states == 8,
                "朝向 4 × 亮 2 = 8 个状态。秒 0-3600 要是进了状态就是 430 万个，实际 " + states);

        BlockPos plate = placeClock(helper, Direction.NORTH, new ClockRule.Setting(0, 7, 3));
        ClockRule.Setting stored = ClockBlock.settingAt(helper.getLevel(), plate);
        helper.assertTrue(stored.equals(new ClockRule.Setting(0, 7, 3)),
                "三个数要在方块旁边那张表里，读到的是 " + stored);

        // And a caller that hands the block raw numbers gets them into range rather than written down as they are.
        ClockBlock.applySettings(helper.getLevel(), plate, new ClockRule.Setting(99_999, 99, 99));
        ClockRule.Setting clamped = ClockBlock.settingAt(helper.getLevel(), plate);
        helper.assertTrue(clamped.equals(new ClockRule.Setting(ClockRule.MAX_SECONDS, ClockRule.MAX_TICKS,
                        ClockRule.MAX_STRENGTH)),
                "越界的数字要夹进范围，实际 " + clamped);
        helper.succeed();
    }

    /** 0 秒 0 tick is off, and it comes back the moment a period is set. */
    @GameTest(template = "empty")
    public void zeroIsOffAndARealPeriodWakesItUp(GameTestHelper helper) {
        BlockPos plate = placeClock(helper, Direction.NORTH, new ClockRule.Setting(0, 0, 15));
        final int[] seen = new int[2];
        watchOff(helper, plate, 8, seen);
    }

    private static void watchOff(GameTestHelper helper, BlockPos plate, int left, int[] seen) {
        helper.runAfterDelay(1, () -> {
            ServerLevel level = helper.getLevel();
            BlockState state = level.getBlockState(plate);
            seen[0]++;
            if (state.getValue(ClockBlock.LIT) || state.getSignal(level, plate, Direction.NORTH) != 0) {
                seen[1]++;
            }
            if (left <= 1) {
                helper.assertTrue(seen[1] == 0, "0 秒 0 tick 就是关掉：一次也不该输出，实际 "
                        + seen[1] + " / " + seen[0] + " tick");
                // Switch it on: a two-tick period is one continuous pulse, so a single tick later it must be emitting.
                ClockBlock.applySettings(level, plate, new ClockRule.Setting(0, 2, 15));
                helper.runAfterDelay(1, () -> {
                    BlockState after = level.getBlockState(plate);
                    helper.assertTrue(after.getValue(ClockBlock.LIT)
                                    && after.getSignal(level, plate, Direction.NORTH) == 15,
                            "设回一个真周期就要马上跑起来");
                    helper.succeed();
                });
                return;
            }
            watchOff(helper, plate, left - 1, seen);
        });
    }

    /**
     * LIT is the answer: a period that fits inside the two-tick pulse is continuously on, and 0 秒 0 tick is
     * off. Written as two settings rather than by forcing the property, because {@code setBlock} with a new
     * state calls {@code onPlace}, which announces the answer from the rule - the behaviour under test.
     */
    @GameTest(template = "empty")
    public void theLitPropertyIsTheAnswer(GameTestHelper helper) {
        BlockPos plate = placeClock(helper, Direction.NORTH, new ClockRule.Setting(0, 1, 6));
        ServerLevel level = helper.getLevel();

        helper.assertTrue(level.getBlockState(plate).getValue(ClockBlock.LIT),
                "周期 1 tick 比脉冲还短：它只能一直输出，所以亮着");
        helper.assertTrue(level.getBlockState(plate).getSignal(level, plate, Direction.NORTH) == 6,
                "亮着就是答案：背面按设定强度回答，实际 "
                        + level.getBlockState(plate).getSignal(level, plate, Direction.NORTH));

        ClockBlock.applySettings(level, plate, new ClockRule.Setting(0, 0, 6));
        helper.assertFalse(level.getBlockState(plate).getValue(ClockBlock.LIT), "关掉就不亮");
        helper.assertTrue(level.getBlockState(plate).getSignal(level, plate, Direction.NORTH) == 0,
                "而背面回答 0");
        helper.succeed();
    }

    /**
     * 可放入方块内: the stored clock ticks on its own too. The plate's scheduled tick is no help to a
     * component inside a block - there is no block of ours at that coordinate - so the level's own pass
     * writes its output ({@code InnerRedstoneNetwork#tickClocks}), watched the same way as the plate.
     */
    @GameTest(template = "empty")
    public void theStoredClockTicksOnItsOwn(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos host = at(helper, 2, 1, 2);
        helper.setBlock(new BlockPos(2, 1, 2), Blocks.STONE.defaultBlockState());

        Slot slot = new Slot(ComponentType.CLOCK);
        slot.facing = Direction.EAST;
        slot.clock = FAST;
        InnerRedstoneStore store = InnerRedstoneStore.get(level);
        store.getOrCreate(host).setSlot(slot);
        store.markDirty();

        watchStored(helper, host, FAST, WATCHED_TICKS, new int[4]);
    }

    private static void watchStored(GameTestHelper helper, BlockPos host, ClockRule.Setting setting, int left,
                                    int[] counters) {
        helper.runAfterDelay(1, () -> {
            ServerLevel level = helper.getLevel();
            long now = level.getGameTime();
            Slot slot = InnerRedstoneStore.get(level).slotAt(host);
            helper.assertTrue(slot != null && slot.type == ComponentType.CLOCK, "元件还在那里");
            int power = slot.power;
            counters[3]++;
            if (power > 0) {
                counters[0]++;
            }
            helper.assertTrue(power == 0 || power == setting.strength(),
                    "方块里的时钟也只回答两种数：设定强度或 0，实际 " + power);
            // 宿主替它回答那一面。方向口径：getSignal(pos, dir) 的 dir 是"问句走来的方向"，所以 facing = EAST 时背面是 getSignal(host, EAST)。
            if (power > 0 && level.getSignal(host, Direction.EAST) == power) {
                counters[1]++;
            }
            helper.assertTrue(level.getSignal(host, Direction.WEST) == 0,
                    "不往正面回灌 - 和世界里的薄板一样");
            note(counters, now, power > 0, ClockRule.isOn(now, setting.period()));
            if (!agrees(power, setting, now)) {
                counters[2]++;
            }

            if (left <= 1) {
                helper.assertTrue(counters[1] > 0,
                        "宿主方块必须替方块里的时钟把答案说给世界（" + counters[0] + " 个有输出的查看里一次都没说出来）");
                endWatch(helper, counters);
                return;
            }
            watchStored(helper, host, setting, left - 1, counters);
        });
    }

    /** The screen's write for a stored clock: the three numbers land on the slot, and the tick picks them up. */
    @GameTest(template = "empty")
    public void theHostScreensWriteReachesTheStoredClock(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos host = at(helper, 2, 1, 2);
        helper.setBlock(new BlockPos(2, 1, 2), Blocks.STONE.defaultBlockState());

        InnerRedstoneStore store = InnerRedstoneStore.get(level);
        Slot slot = new Slot(ComponentType.CLOCK);
        slot.facing = Direction.EAST;
        store.getOrCreate(host).setSlot(slot);
        store.markDirty();

        helper.assertTrue(ClockBlock.applyHostSettings(level, host, store.slotAt(host),
                        new ClockRule.Setting(2, 5, 4)),
                "界面的写入必须落到方块里那个元件上");
        helper.assertTrue(store.slotAt(host).clock.equals(new ClockRule.Setting(2, 5, 4)),
                "三个数要照着写进去，实际 " + store.slotAt(host).clock);
        helper.assertFalse(ClockBlock.applyHostSettings(level, host, store.slotAt(host), null),
                "没有设定就得拒绝，而不是把元件改成默认值");

        // 2 秒 5 tick = 45 tick：两个 tick 的脉冲里它输出 4，其余时间 0 - watched for one whole period.
        ClockRule.Setting setting = store.slotAt(host).clock;
        watchStoredPeriod(helper, host, setting, setting.period(), 0, 0);
    }

    private static void watchStoredPeriod(GameTestHelper helper, BlockPos host, ClockRule.Setting setting,
                                          int left, int pulseTicks, int silentTicks) {
        helper.runAfterDelay(1, () -> {
            Slot slot = InnerRedstoneStore.get(helper.getLevel()).slotAt(host);
            int power = slot == null ? -1 : slot.power;
            int pulses = pulseTicks + (power > 0 ? 1 : 0);
            int silences = silentTicks + (power == 0 ? 1 : 0);
            if (left <= 1) {
                helper.assertTrue(pulses >= 1 && silences >= 1,
                        "一个 45 tick 的周期里必须既有输出也有静默，实际 " + pulses + " / " + silences);
                helper.succeed();
                return;
            }
            watchStoredPeriod(helper, host, setting, left - 1, pulses, silences);
        });
    }

    /** 可放入方块内, and a clock can never be a host for something else. */
    @GameTest(template = "empty")
    public void thePlateIsAnEighthOfABlockAndNeverAHost(GameTestHelper helper) {
        BlockState fresh = RCRegistry.CLOCK.get().defaultBlockState();
        helper.assertTrue(fresh.getValue(ClockBlock.FACING) == Direction.NORTH, "默认正面朝北");
        helper.assertFalse(fresh.getValue(ClockBlock.LIT), "and a fresh plate is not lit");
        helper.assertTrue(ClockRule.Setting.DEFAULT.equals(ClockBlock.settingAt(helper.getLevel(), at(helper, 2, 1, 2))),
                "没有存过设定的地方读到的是默认值（1 秒、满强度）");

        BlockPos pos = at(helper, 2, 1, 2);
        helper.setBlock(new BlockPos(2, 1, 2), fresh);
        BlockState placed = helper.getLevel().getBlockState(pos);

        double top = placed.getShape(helper.getLevel(), pos).max(Direction.Axis.Y);
        helper.assertTrue(Math.abs(top - 0.125) < 1e-6, "薄板高 1/8 格（0.125），量到的是 " + top);
        helper.assertFalse(placed.isCollisionShapeFullBlock(helper.getLevel(), pos),
                "and it is not a full cube, which is what the host rule reads");
        helper.assertFalse(HostRules.isValidHost(placed),
                "所以红石元件塞不进它里面 - 它自己六面已有归属");
        helper.assertTrue(InnerRedstoneStore.get(helper.getLevel()).clockAt(pos) != null,
                "放下一个时钟就会给它写一份默认设定：捕获时靠这张表把它带走（StructureCapture#capture）");
        helper.succeed();
    }
}

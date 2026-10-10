package com.jiangyuefengyu.redstonecircuit.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import net.minecraft.nbt.CompoundTag;

/**
 * 信号延长器's rule, pinned without a running game.
 *
 * <p>这件设备的两句话就是全部: 输入一次脉冲, 输出相同强度持续设定时长; 输入是长信号就一直输出, 停下时再延长设定时长。这里按
 * tick 走一遍这台设备, 逐 tick 对答案 - 包括"上一次的答案"这一路, 因为保持的强度就是它。
 */
class ExtenderRuleTest {

    /** 走一遍设备: 逐 tick 把输入喂进去, 答案记下来。返回每一 tick 的输出, 下标就是 tick。 */
    private static int[] run(int ticks, int hold, int[] inputAt) {
        int[] out = new int[ticks];
        long deadline = ExtenderRule.NO_DEADLINE;
        int held = 0;
        for (int now = 0; now < ticks; now++) {
            int input = now < inputAt.length ? inputAt[now] : 0;
            deadline = ExtenderRule.deadline(now, input, hold, deadline);
            out[now] = ExtenderRule.answer(now, input, held, deadline);
            // 下一次问的"上一次的答案"就是这一次的答案, 和方块旁的答案缓存同一件事。
            held = out[now];
        }
        return out;
    }

    private static int[] constant(int ticks, int value) {
        int[] input = new int[ticks];
        java.util.Arrays.fill(input, value);
        return input;
    }

    @Test
    @DisplayName("秒和刻相加，用的是时钟源那一对单位与范围")
    void theTwoUnitsAddUp() {
        assertEquals(20, ExtenderRule.holdTicks(1, 0), "1 秒 = 20 tick");
        assertEquals(25, ExtenderRule.holdTicks(1, 5), "两个单位相加");
        assertEquals(5, ExtenderRule.holdTicks(0, 5), "只用刻也合法");
        assertEquals(3 * 20, ExtenderRule.holdTicks(3, 0), "3 秒 = 60 tick");
        assertEquals(ClockRule.MAX_PERIOD, ExtenderRule.holdTicks(3600, 19), "最长的一档");
        // 同一对单位就是同一对: 范围也必须是时钟源那两个函数说了算, 不另写一份。
        for (int value : new int[] {-1, 0, 19, 20, 3600, 3601, 99_999}) {
            assertEquals(ClockRule.isSeconds(value), ExtenderRule.isSeconds(value), "秒字段 " + value);
        }
        for (int value : new int[] {-1, 0, 19, 20, 99}) {
            assertEquals(ClockRule.isTicks(value), ExtenderRule.isTicks(value), "刻字段 " + value);
        }
    }

    @Test
    @DisplayName("0 秒 0 刻 = 不延长：输入什么样输出就什么样，输入一 tick 都不多留")
    void zeroIsPassThrough() {
        int[] out = run(6, 0, new int[] {7, 7, 0, 0, 9, 0});
        assertEquals(7, out[0]);
        assertEquals(7, out[1]);
        assertEquals(0, out[2], "输入走的那一 tick 输出也走");
        assertEquals(0, out[3]);
        assertEquals(9, out[4], "再来一次还是原样过去");
        assertEquals(0, out[5]);

        // 同一 tick 里先来后走也是直通：0 秒 0 刻 不留任何保持, 所以没有"上一次的答案"可答。
        long live = ExtenderRule.deadline(7, 5, 0, ExtenderRule.NO_DEADLINE);
        assertEquals(ExtenderRule.NO_DEADLINE, live, "这一档写下的就是「没有保持」");
        assertEquals(0, ExtenderRule.answer(7, 0, 5, live), "输入在同一 tick 里走了, 就答 0");
    }

    @Test
    @DisplayName("一次脉冲 + 设定 3 秒 ⇒ 输出同样强度共 脉冲时长 + 3 秒")
    void aPulseIsHeldForTheSetTimeAfterItLeaves() {
        int hold = ExtenderRule.holdTicks(3, 0);
        int[] out = run(70, hold, new int[] {7, 7});
        for (int now = 0; now <= 2 + hold - 1; now++) {
            assertEquals(7, out[now], "tick " + now + " 应当在输出");
        }
        for (int now = 2 + hold; now < out.length; now++) {
            assertEquals(0, out[now], "tick " + now + " 已经停了");
        }
        assertEquals(62, 2 + hold, "两 tick 的脉冲 + 60 tick 的保持");
    }

    @Test
    @DisplayName("长信号：输入期间一直输出，输入停下之后再延长设定时长")
    void aLongInputAnswersUntilItStopsAndThenHolds() {
        int hold = 20;
        int[] input = new int[100];
        java.util.Arrays.fill(input, 0, 80, 11);
        int[] out = run(130, hold, input);
        for (int now = 0; now < 80; now++) {
            assertEquals(11, out[now], "输入还在，tick " + now);
        }
        for (int now = 80; now < 80 + hold; now++) {
            assertEquals(11, out[now], "输入走了之后还要保持 " + hold + " tick，tick " + now);
        }
        for (int now = 80 + hold; now < out.length; now++) {
            assertEquals(0, out[now], "tick " + now + " 保持已经结束");
        }
    }

    @Test
    @DisplayName("强度原样通过：7 就是 7；保持的是输入走那一刻的那个强度")
    void theStrengthIsPassedThroughAndTheHoldKeepsTheLastOne() {
        int[] input = new int[] {7, 7, 7, 3, 3, 0, 0, 0, 0};
        int[] out = run(input.length, 3, input);
        assertEquals(7, out[0]);
        assertEquals(3, out[3], "输入变了，输出跟着变");
        assertEquals(3, out[5], "输入走了，保持的是消失那一刻的 3");
        assertEquals(3, out[7], "三 tick 的保持");
        assertEquals(0, out[8]);
        // 15 也原样过去，不整形、不放大。
        assertEquals(15, run(1, 5, new int[] {15})[0]);
        assertEquals(1, run(1, 5, new int[] {1})[0]);
    }

    @Test
    @DisplayName("保持期间再来一次输入：截止时刻往后推，输出不中断")
    void aSecondPulsePushesTheDeadlineOut() {
        int hold = 10;
        int[] input = new int[22];
        input[0] = 5;
        // 第二次落在第一次的保持里面（第一次的保持到 tick 10），所以它是一次"推后"而不是一次新的保持。
        input[8] = 5;
        int[] out = run(input.length, hold, input);
        for (int now = 0; now <= 8 + hold; now++) {
            assertEquals(5, out[now], "第二次输入把保持推到了 " + (8 + hold) + "，tick " + now + " 不该断");
        }
        assertEquals(0, out[9 + hold], "推后之后照样会停");
    }

    @Test
    @DisplayName("还剩多少 tick：截止那一 tick 算在内，之后是 0")
    void ticksLeftCountsTheCurrentTick() {
        long deadline = ExtenderRule.deadline(100, 4, 20, ExtenderRule.NO_DEADLINE);
        assertEquals(120, deadline, "有输入就推到 now + 设定时长");
        assertEquals(21, ExtenderRule.ticksLeft(100, deadline), "设定 20 tick，含当前这一 tick 是 21");
        assertEquals(1, ExtenderRule.ticksLeft(120, deadline), "截止那一 tick 还在保持");
        assertEquals(0, ExtenderRule.ticksLeft(121, deadline));
        assertTrue(ExtenderRule.isHolding(120, deadline));
        assertFalse(ExtenderRule.isHolding(121, deadline));
    }

    @Test
    @DisplayName("没有输入时截止时刻不动：设定的改动不会挪动正在跑的那次保持")
    void theDeadlineOnlyMovesWhileSomethingArrives() {
        long first = ExtenderRule.deadline(10, 6, 5, ExtenderRule.NO_DEADLINE);
        assertEquals(15, first);
        assertEquals(first, ExtenderRule.deadline(11, 0, 5, first), "没有输入就原样留着");
        assertEquals(first, ExtenderRule.deadline(99, 0, 40, first), "改设定也一样");
        assertEquals(ExtenderRule.NO_DEADLINE, ExtenderRule.deadline(0, 0, 5, ExtenderRule.NO_DEADLINE),
                "从来没有输入过就一直是没有");
        assertEquals(0, ExtenderRule.answer(0, 0, 9, ExtenderRule.NO_DEADLINE), "也没有可以保持的东西");
    }

    @Test
    @DisplayName("设定：出厂 1 秒，越界夹进范围，写进存档再读回来一样，0 秒 0 刻就是关掉延长")
    void theSettingRoundTripsAndIsClamped() {
        assertEquals(new ExtenderRule.Setting(1, 0), ExtenderRule.Setting.DEFAULT);
        assertEquals(new ExtenderRule.Setting(3600, 19), ExtenderRule.Setting.of(99_999, 99), "越界夹进范围");
        assertEquals(new ExtenderRule.Setting(0, 0), ExtenderRule.Setting.of(-5, -5));
        assertTrue(ExtenderRule.Setting.of(0, 0).isOff());
        assertFalse(ExtenderRule.Setting.DEFAULT.isOff());
        assertEquals(45, ExtenderRule.Setting.of(2, 5).holdTicks(), "2 秒 5 刻 = 45 tick");

        ExtenderRule.Setting setting = new ExtenderRule.Setting(2, 5);
        CompoundTag tag = new CompoundTag();
        setting.save(tag);
        assertEquals(setting, ExtenderRule.Setting.load(tag), "写进存档再读回来必须一样");
        // 0 秒 0 刻 是合法设定，所以判据是有没有这个键，不是数字是多少。
        CompoundTag zero = new CompoundTag();
        ExtenderRule.Setting.of(0, 0).save(zero);
        assertEquals(ExtenderRule.Setting.of(0, 0), ExtenderRule.Setting.load(zero));
        assertSame(ExtenderRule.Setting.DEFAULT, ExtenderRule.Setting.load(new CompoundTag()),
                "旧存档里没有这条设置就是默认值");
    }
}

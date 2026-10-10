package com.jiangyuefengyu.redstonecircuit.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 时钟源's rule, pinned without a running game.
 *
 * <p>可设置每 X tick 或秒输出一次指定强度的红石信号，tick 和秒单位可同时存在并相加。0 秒 0 tick 是关掉，脉宽是
 * 2 tick，强度 1-15，相位跟着世界时钟。
 */
class ClockRuleTest {

    @Test
    @DisplayName("tick和秒相加：1 秒 = 20 tick，上限是 3600 秒 19 tick")
    void theTwoUnitsAddUp() {
        assertEquals(20, ClockRule.period(1, 0), "1 秒就是 20 tick");
        assertEquals(25, ClockRule.period(1, 5), "两个单位相加，不是二选一");
        assertEquals(5, ClockRule.period(0, 5), "只用 tick 也是合法的");
        assertEquals(40, ClockRule.period(2, 0));
        assertEquals(ClockRule.MAX_PERIOD, ClockRule.period(ClockRule.MAX_SECONDS, ClockRule.MAX_TICKS),
                "最大周期 = 3600 秒 19 tick");
        assertEquals(72_019, ClockRule.MAX_PERIOD);
        assertEquals(3600 * 20 + 19, ClockRule.MAX_PERIOD, "一小时零 19 tick");
    }

    @Test
    @DisplayName("0 秒 0 tick = 关掉：任何时刻都不输出，也没有下一个边沿")
    void zeroIsOff() {
        assertEquals(0, ClockRule.period(0, 0));
        for (long time : new long[] {0L, 1L, 7L, 20L, 999_999L}) {
            assertEquals(0, ClockRule.answer(time, 0, 0, 15), "关掉的时钟在任何 tick 都不输出");
            assertFalse(ClockRule.isOn(time, 0));
            assertEquals(0, ClockRule.ticksToEdge(time, 0), "没有边沿可等");
        }
    }

    @Test
    @DisplayName("一个周期里前 2 tick 输出设定强度，其余为 0")
    void thePulseIsTheFirstTwoTicks() {
        assertEquals(7, ClockRule.answer(0, 1, 0, 7), "相位 0 = 脉冲的第一 tick");
        assertEquals(7, ClockRule.answer(1, 1, 0, 7), "相位 1 = 脉冲的第二 tick");
        assertEquals(7, ClockRule.answer(20, 1, 0, 7), "下一个周期又从脉冲开始");
        for (int phase = ClockRule.PULSE_TICKS; phase < 20; phase++) {
            assertEquals(0, ClockRule.answer(phase, 1, 0, 7), "相位 " + phase + " 应当在静默");
        }
    }

    @Test
    @DisplayName("跟着世界时钟：同一个周期，相位只由 gameTime 决定（时间平移一个周期答案相同）")
    void thePhaseFollowsTheWorldClock() {
        for (int seconds : new int[] {0, 1, 3}) {
            for (int ticks : new int[] {0, 1, 19}) {
                int period = ClockRule.period(seconds, ticks);
                if (period == 0) {
                    continue;
                }
                for (long time = 0; time < 200; time++) {
                    assertEquals(ClockRule.answer(time, seconds, ticks, 9),
                            ClockRule.answer(time + period * 3L, seconds, ticks, 9),
                            "同一台时钟隔三个周期应当一模一样");
                }
            }
        }
        assertTrue(ClockRule.isOn(0, 20), "世界时钟 0 落在脉冲里");
        assertFalse(ClockRule.isOn(5, 20));
        assertTrue(ClockRule.isOn(40, 20));
    }

    @Test
    @DisplayName("周期不比脉冲宽时就是一直输出：1 tick 和 2 tick 都没有能静默的那一 tick")
    void aPeriodThatFitsInsideThePulseIsAlwaysOn() {
        assertEquals(ClockRule.PULSE_TICKS, 2, "脉宽固定 2 tick");
        for (long time = 0; time < 50; time++) {
            assertTrue(ClockRule.isOn(time, 1), "周期 1 tick：一直输出");
            assertTrue(ClockRule.isOn(time, 2), "周期 2 tick：也没有静默的空隙");
            assertEquals(4, ClockRule.answer(time, 0, 1, 4));
        }
        assertFalse(ClockRule.isOn(2, 3), "周期 3 tick 是第一个真正会闪的周期");
        assertTrue(ClockRule.isOn(1, 3));
    }

    @Test
    @DisplayName("下一个边沿：脉冲里等它结束，静默里等下一次脉冲")
    void ticksToTheNextEdge() {
        assertEquals(2, ClockRule.ticksToEdge(0, 20), "相位 0：还有 2 tick 灭");
        assertEquals(1, ClockRule.ticksToEdge(1, 20), "相位 1：还有 1 tick 灭");
        assertEquals(18, ClockRule.ticksToEdge(2, 20), "相位 2：静默里，还有 18 tick 亮");
        assertEquals(1, ClockRule.ticksToEdge(19, 20), "相位 19：下一 tick 就亮");
        assertEquals(2, ClockRule.ticksToEdge(0, 5), "周期 5、相位 0：脉冲仍然只有 2 tick 宽，2 tick 后就灭");
        assertEquals(1, ClockRule.ticksToEdge(4, 5), "周期 5、相位 4：下一 tick 就是下一个周期的相位 0");
        assertEquals(2, ClockRule.ticksToEdge(0, 2), "周期 2 tick：脉冲占满，等满一个周期也是等 2 tick");
        assertEquals(1, ClockRule.ticksToEdge(0, 1), "周期 1 tick：一个 tick 就回到相位 0");
        // Walking the whole period must land on an edge every time - the scheduled tick chain is built from this number.
        for (int period : new int[] {3, 20, 41, ClockRule.MAX_PERIOD}) {
            long time = 0;
            for (int step = 0; step < 400; step++) {
                int delay = ClockRule.ticksToEdge(time, period);
                assertTrue(delay >= 1 && delay <= period, "周期 " + period + " 的步长应当在 1..period 内，实际 " + delay);
                time += delay;
                assertTrue(ClockRule.phase(time, period) == 0 || ClockRule.phase(time, period) == ClockRule.PULSE_TICKS,
                        "走了 " + delay + " tick 之后必须正落在边沿上，实际相位 " + ClockRule.phase(time, period));
            }
        }
    }

    @Test
    @DisplayName("范围与夹取：秒 0-3600、tick 0-19、强度 1-15")
    void theRanges() {
        assertTrue(ClockRule.isSeconds(0) && ClockRule.isSeconds(3600));
        assertFalse(ClockRule.isSeconds(-1) && ClockRule.isSeconds(3601));
        assertTrue(ClockRule.isTicks(0) && ClockRule.isTicks(19));
        assertFalse(ClockRule.isTicks(-1) && ClockRule.isTicks(20));
        assertTrue(ClockRule.isStrength(1) && ClockRule.isStrength(15));
        assertFalse(ClockRule.isStrength(0), "0 不是强度：0 是\"没有输出\"，不是设定的值");
        assertFalse(ClockRule.isStrength(16));

        assertEquals(3600, ClockRule.clampSeconds(99_999));
        assertEquals(0, ClockRule.clampSeconds(-4));
        assertEquals(19, ClockRule.clampTicks(20));
        assertEquals(0, ClockRule.clampTicks(-1));
        assertEquals(1, ClockRule.clampStrength(0));
        assertEquals(15, ClockRule.clampStrength(99));
        assertEquals(12, ClockRule.clampStrength(12));
    }

    @Test
    @DisplayName("默认是 1 秒 0 tick、满强度，而且现在的设定就是能用的设定")
    void theDefaults() {
        assertEquals(20, ClockRule.period(ClockRule.DEFAULT_SECONDS, ClockRule.DEFAULT_TICKS),
                "新放下的时钟默认每秒输出一次");
        assertEquals(15, ClockRule.DEFAULT_STRENGTH);
        assertTrue(ClockRule.isSeconds(ClockRule.DEFAULT_SECONDS));
        assertTrue(ClockRule.isTicks(ClockRule.DEFAULT_TICKS));
        assertTrue(ClockRule.isStrength(ClockRule.DEFAULT_STRENGTH));
        assertEquals(ClockRule.DEFAULT_STRENGTH,
                ClockRule.answer(0, ClockRule.DEFAULT_SECONDS, ClockRule.DEFAULT_TICKS, ClockRule.DEFAULT_STRENGTH),
                "放下的那一刻正好落在脉冲里");
    }

    @Test
    @DisplayName("越界的设定被夹到范围内，而不是让时钟停摆或输出越界强度")
    void outOfRangeSettingsAreClamped() {
        assertEquals(15, ClockRule.answer(0, 0, 1, 99), "强度夹到 15");
        assertEquals(1, ClockRule.answer(0, 0, 1, -3), "强度夹到 1");
        assertEquals(ClockRule.MAX_PERIOD, ClockRule.period(99_999, 99), "秒和 tick 都夹到上限");
        assertEquals(ClockRule.answer(0, 1, 0, 15), ClockRule.answer(0, 99_999, 99, 15));
    }
}

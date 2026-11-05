package com.jiangyuefengyu.redstonecircuit.logic;

import net.minecraft.nbt.CompoundTag;

/**
 * <b>时钟源 - the three sentences as one pure function of the world clock.</b>
 *
 * <p>器件没有输入，只有周期、脉宽和强度：每个周期的头 {@code 脉冲} 个 tick 成立，而脉宽是<b>每台时钟自己的设置</b>
 * （1 或 2 tick，放下时 2 tick）。周期 = {@code 秒 × 20 + tick}（秒 0-3600，tick 0-19 为秒以下余数，故同一周期
 * 只有一种写法）；相位跟随世界时钟，答案是 {@code floorMod(gameTime, period) < pulse}，不存状态，所以重载与搬运
 * 都不改变相位、两个同样的钟自然同步，0 秒 0 tick 表示关掉。设置不住在方块状态里（那个组合会在启动时建出几百万个
 * {@code BlockState}），而是方块旁的 {@link ClockSettings}，状态里只留 {@code LIT}。
 */
public final class ClockRule {

    /** 一秒 = 20 tick - the exchange rate between the clock's two units. */
    public static final int TICKS_PER_SECOND = 20;

    /** The longest 秒 a clock may be set to (0-3600). */
    public static final int MAX_SECONDS = 3600;

    /** The largest tick field: 19, one short of a second. Not 20, because 20 ticks <em>is</em> a second, so allowing it
     * would spell "1 秒" two ways and make the sum the player reads back from the two fields disagree with the period
     * used. */
    public static final int MAX_TICKS = 19;

    /** The weakest strength the clock may be told to emit - the same 1-15 as 控制器 and 红石变压器. */
    public static final int MIN_STRENGTH = 1;

    /** The strongest. */
    public static final int MAX_STRENGTH = 15;

    /** The narrowest 脉冲 a clock may be set to: one tick. It is offered because a designer chasing a tight interlock
     * wants two adjacent clocks not to overlap at all; the price (a consumer that only reads every other tick can miss
     * it) is the player's to weigh, so it is a choice rather than something this class decides. */
    public static final int MIN_PULSE_TICKS = 1;

    /** The widest: <b>2 tick</b>, and the default. Two rather than one because a pulse shorter than the window the
     * structure machinery reads faces in can be treated as "already caught up" and lost, and one tick is also short
     * enough for an ordinary consumer to miss between two reads; two is vanilla's own repeater/observer pulse width. */
    public static final int MAX_PULSE_TICKS = 2;

    /** What a clock is placed with - and what a save written before 脉冲 was a setting reads back as (see
     * {@link Setting#load}). */
    public static final int DEFAULT_PULSE_TICKS = 2;

    /** Out of the box: one second, full strength - a clock a player can see working before they touch anything. */
    public static final int DEFAULT_SECONDS = 1;

    /** Out of the box: no sub-second remainder. */
    public static final int DEFAULT_TICKS = 0;

    /** Out of the box: full strength, like the other two devices. */
    public static final int DEFAULT_STRENGTH = MAX_STRENGTH;

    /** The longest period there is, in ticks: 3600 秒 19 tick. */
    public static final int MAX_PERIOD = MAX_SECONDS * TICKS_PER_SECOND + MAX_TICKS;

    private ClockRule() {
    }

    /** Whether a number is a 秒 this clock may be set to; the payload decoder and the screen both ask. */
    public static boolean isSeconds(int value) {
        return value >= 0 && value <= MAX_SECONDS;
    }

    /** Whether a number is a tick field this clock may be set to. */
    public static boolean isTicks(int value) {
        return value >= 0 && value <= MAX_TICKS;
    }

    /** Whether a number is a strength this clock may be set to. */
    public static boolean isStrength(int value) {
        return value >= MIN_STRENGTH && value <= MAX_STRENGTH;
    }

    /** Whether a number is a 脉冲 width this clock may be set to - the 1-2 the screen's one button walks. */
    public static boolean isPulse(int value) {
        return value >= MIN_PULSE_TICKS && value <= MAX_PULSE_TICKS;
    }

    /** The same number, brought into range - what a slot read out of an older save is passed through. */
    public static int clampSeconds(int value) {
        return Math.max(0, Math.min(MAX_SECONDS, value));
    }

    /** The same, for the tick field. */
    public static int clampTicks(int value) {
        return Math.max(0, Math.min(MAX_TICKS, value));
    }

    /** The same, for the strength. */
    public static int clampStrength(int value) {
        return Math.max(MIN_STRENGTH, Math.min(MAX_STRENGTH, value));
    }

    /** The same, for the 脉冲. 0 and 3 are not widths this device has, and the rule below is what would otherwise turn a
     * 0 into a clock that never answers, so it is clamped here rather than left to each caller. */
    public static int clampPulse(int value) {
        return Math.max(MIN_PULSE_TICKS, Math.min(MAX_PULSE_TICKS, value));
    }

    /** The two units added, in one expression. 0 means <b>off</b>: a clock with no period does not run, which is the
     * switch a free-running source otherwise does not have. {@code seconds} is 0-3600 and {@code ticks} 0-19; the
     * result is the period in ticks, or 0 for a clock that is switched off. */
    public static int period(int seconds, int ticks) {
        return clampSeconds(seconds) * TICKS_PER_SECOND + clampTicks(ticks);
    }

    /** Where in its period the clock stands, from the world clock. {@code floorMod} rather than {@code %}: the world
     * clock can be handed to us negative by a test or by a level that has not been ticked yet, and a negative phase
     * would put every edge in the wrong place. */
    public static long phase(long gameTime, int period) {
        return period <= 0 ? 0L : Math.floorMod(gameTime, period);
    }

    /** Whether the clock is emitting right now. {@code gameTime} is the world clock ({@code Level#getGameTime()}),
     * {@code period} is {@link #period} (or 0 for a clock that is switched off) and {@code pulse} is how many ticks of
     * every period the strength stands for. A period no wider than the pulse is on at every phase, which is what this
     * one comparison says on its own - there is deliberately no branch for it. */
    public static boolean isOn(long gameTime, int period, int pulse) {
        return period > 0 && phase(gameTime, period) < clampPulse(pulse);
    }

    /** What 背面 answers right now - the whole device, in one call. {@code seconds} is 0-3600, {@code ticks} 0-19,
     * {@code strength} 1-15 and {@code pulse} 1-2; the answer is that strength during the pulse and 0 for the rest of
     * the period. */
    public static int answer(long gameTime, int seconds, int ticks, int strength, int pulse) {
        return isOn(gameTime, period(seconds, ticks), pulse) ? clampStrength(strength) : 0;
    }

    /** How many ticks until the output next changes - the next edge, whichever kind it is, from the same {@code pulse}
     * the answer uses, so the two can never disagree about where the edge is. This is what the block schedules its own
     * tick on instead of waking up every tick, capped at the period so a clock whose pulse fills its whole period is
     * still asked once per period rather than once per pulse. The answer is 1..period, or 0 for a clock that is
     * switched off and will never have an edge. */
    public static int ticksToEdge(long gameTime, int period, int pulse) {
        if (period <= 0) {
            return 0;
        }
        int width = clampPulse(pulse);
        long phase = phase(gameTime, period);
        long untilOff = width - phase;
        long delay = phase < width ? untilOff : period - phase;
        return (int) Math.min(delay, period);
    }

    /** The clock's four numbers as one value: 秒, tick, 强度 and 脉冲宽度. One record rather than fields scattered over
     * the two places a clock is stored - the settings beside a standing block, and the slot of one held inside a host -
     * because the four always travel together: a save format, a payload, a screen and a rule all want exactly this
     * value.
     *
     * <p>The 脉冲 lives in the setting rather than in a constant so that a clock held in a slot, exported into a
     * blueprint or carried inside a structure brings its own width along without any of those having to know about
     * it. Every number is explicit: there is no shorter spelling of a setting that would mean "the default width". */
    public record Setting(int seconds, int ticks, int strength, int pulse) {

        /** One second at full strength, 2 tick 脉冲: what a freshly placed clock runs at. */
        public static final Setting DEFAULT = new Setting(DEFAULT_SECONDS, DEFAULT_TICKS, DEFAULT_STRENGTH,
                DEFAULT_PULSE_TICKS);

        /** The two units added, asked of a setting. */
        public int period() {
            return ClockRule.period(seconds, ticks);
        }

        /** Whether this setting is 0 秒 0 tick - a clock that is switched off. */
        public boolean isOff() {
            return period() == 0;
        }

        /** What the clock answers at this moment of the world clock. */
        public int answer(long gameTime) {
            return ClockRule.answer(gameTime, seconds, ticks, strength, pulse);
        }

        /** The same setting with its numbers replaced - what the screen's three fields and its 脉冲 button send. */
        public Setting with(int newSeconds, int newTicks, int newStrength, int newPulse) {
            return of(newSeconds, newTicks, newStrength, newPulse);
        }

        /** One setting, with all four numbers brought into range. The one place a setting is built from raw numbers, so
         * "the record cannot hold a period or a 脉冲 this device has no meaning for" is true by construction: the block
         * and the slot both pass through here, while the payload decoder refuses an out-of-range field outright - a
         * screen that said 3601 must be told so, not quietly given 3600 or "off". */
        public static Setting of(int seconds, int ticks, int strength, int pulse) {
            return new Setting(clampSeconds(seconds), clampTicks(ticks), clampStrength(strength), clampPulse(pulse));
        }

        /** Writes all four numbers. {@code pulse} is a <b>new</b> key rather than a different meaning for an old one,
         * so that a tag written by this version and a tag written before the option existed are told apart by
         * {@link #load}. */
        public void save(CompoundTag tag) {
            tag.putInt("seconds", seconds);
            tag.putInt("ticks", ticks);
            tag.putInt("strength", strength);
            tag.putInt("pulse", pulse);
        }

        /** Reads a saved setting, and reads a compound with no setting in it as {@link #DEFAULT}. The strength is the
         * sentinel rather than any of the four, because 0 秒 and 0 tick are both legal numbers while 0 is <b>not</b> a
         * strength this device has - which is what an older save, and a slot that never held a clock, both look like.
         *
         * <p>An <b>absent</b> {@code pulse} is the default 2 tick, not a clamped 0: every clock written before the
         * width became a setting was pulsing at two ticks, so an old save, an old blueprint and an old structure keep
         * exactly the behaviour they had. The sentinel above stays the strength for the same reason - moving it to the
         * pulse would have redefined what "no setting here" means for no gain. */
        public static Setting load(CompoundTag tag) {
            if (!tag.contains("strength")) {
                return DEFAULT;
            }
            int pulse = tag.contains("pulse") ? clampPulse(tag.getInt("pulse")) : DEFAULT_PULSE_TICKS;
            return new Setting(clampSeconds(tag.getInt("seconds")), clampTicks(tag.getInt("ticks")),
                    clampStrength(tag.getInt("strength")), pulse);
        }
    }
}

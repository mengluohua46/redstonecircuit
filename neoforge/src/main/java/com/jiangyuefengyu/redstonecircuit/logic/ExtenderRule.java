package com.jiangyuefengyu.redstonecircuit.logic;

import net.minecraft.nbt.CompoundTag;

/**
 * <b>信号延长器 - 输入在, 输出就在; 输入走了, 输出再保持设定时长.</b>
 *
 * <p>一条规则, 一个纯函数: 这一 tick 的答案只看"输入现在是多少""上一次的答案是多少""保持的截止时刻". 保持不是倒计时, 而是
 * <b>截止时刻</b>（{@code gameTime + 设定时长}）: 输入每一次出现都把截止时刻推到那一刻之后, 于是"输入期间一直输出"和"停下
 * 之后再延长设定时长"是同一条规则, 而错过的 tick 也不会把延长拖长 - 到点就是到点。设定为 0 秒 0 刻 就是"不延长": 没有任何
 * 保持, 输入什么样输出就什么样。
 *
 * <p>{@link #answer} 的"上一次的答案"由调用者给: 世界里那件给的是方块旁的答案缓存, 方块内的那件给的是元件自己的输出。
 * 秒与刻就是 时钟源 的那两个时间字段 - 同一套单位、同一套范围、同一个换算, 所以这里向 {@link ClockRule} 问, 不写第二遍。
 */
public final class ExtenderRule {

    /** 出厂设置: 1 秒 - 一件刚放下的延长器要看得见它在做事. */
    public static final int DEFAULT_SECONDS = 1;

    /** 出厂设置: 没有秒以下的余数. */
    public static final int DEFAULT_TICKS = 0;

    /**
     * "没有保持这回事"的截止时刻。
     *
     * <p>不是 0: 世界时钟从 0 开始（测试里还可能给它负数）, 所以 0 是一个真的时刻, 用了它一件刚放下的延长器就会被算成
     * 正在保持。
     */
    public static final long NO_DEADLINE = Long.MIN_VALUE;

    private ExtenderRule() {
    }

    /** 一个秒字段能不能被这件设备接受 - 与时钟源同一套范围. */
    public static boolean isSeconds(int value) {
        return ClockRule.isSeconds(value);
    }

    /** 一个刻字段能不能被这件设备接受. */
    public static boolean isTicks(int value) {
        return ClockRule.isTicks(value);
    }

    /** 设定时长换算成 tick: 秒 × 20 + 刻, 0 表示"不延长". */
    public static int holdTicks(int seconds, int ticks) {
        return ClockRule.period(seconds, ticks);
    }

    /**
     * 这一刻的截止时刻: 有输入就推到 {@code now + 设定时长}, 没有输入就还是原来那个.
     *
     * <p>设定时长为 0 的那一档（"不延长"）不留任何保持, 所以它写下的就是 {@link #NO_DEADLINE}: 输入在同一 tick 里去而复返
     * 也答不出上一次的数, 因为根本没有"上一次"这回事 - 0 秒 0 刻 的语义是直通, 不是"保持零 tick"。
     */
    public static long deadline(long now, int input, int hold, long deadline) {
        if (input <= 0) {
            return deadline;
        }
        return hold > 0 ? now + hold : NO_DEADLINE;
    }

    /** 保持是否还在进行. 截止时刻那一 tick 仍算在内, 所以设定 3 秒就是输入走后的整整 60 tick. */
    public static boolean isHolding(long now, long deadline) {
        return now <= deadline;
    }

    /** 还剩多少 tick（含当前这 tick）, 0 表示没有在保持 - 屏幕上那一行的数字. */
    public static int ticksLeft(long now, long deadline) {
        if (!isHolding(now, deadline)) {
            return 0;
        }
        return (int) Math.min(deadline - now + 1L, Integer.MAX_VALUE);
    }

    /**
     * 背面这一刻的答案: 输入在就跟着输入的强度, 输入走了就保持它走之前那个答案, 保持结束就是 0.
     *
     * <p>输入的强度是原样过去的 - 这件设备不整形, 只延时。
     */
    public static int answer(long now, int input, int held, long deadline) {
        if (input > 0) {
            return PowerSolver.clamp(input);
        }
        return isHolding(now, deadline) ? PowerSolver.clamp(held) : 0;
    }

    /** 两个时间字段作为一条设置. 一起存、一起传、一起显示, 所以是一个值而不是两个散着的字段. */
    public record Setting(int seconds, int ticks) {

        /** 1 秒: 一件刚放下的延长器运行的设定. */
        public static final Setting DEFAULT = new Setting(DEFAULT_SECONDS, DEFAULT_TICKS);

        /** 设定时长, 问这条设置. */
        public int holdTicks() {
            return ExtenderRule.holdTicks(seconds, ticks);
        }

        /** 0 秒 0 刻 - "不延长", 输入什么样输出就什么样. */
        public boolean isOff() {
            return holdTicks() == 0;
        }

        /** 同一设置换两个数字 - 屏幕上那两个框送来的. */
        public Setting with(int newSeconds, int newTicks) {
            return of(newSeconds, newTicks);
        }

        /** 唯一一处从裸数字建设置的地方: 两个数都夹进范围, 所以这条记录拿不到这件设备没有的设定. */
        public static Setting of(int seconds, int ticks) {
            return new Setting(ClockRule.clampSeconds(seconds), ClockRule.clampTicks(ticks));
        }

        public void save(CompoundTag tag) {
            tag.putInt("seconds", seconds);
            tag.putInt("ticks", ticks);
        }

        /** 没有这条设置的存档（旧存档, 或者一个从没放过延长器的槽位）读成 {@link #DEFAULT}. 0 秒 0 刻是合法设定,
         * 所以判据是有没有这个键, 不是数字是多少。 */
        public static Setting load(CompoundTag tag) {
            if (!tag.contains("ticks")) {
                return DEFAULT;
            }
            return of(tag.getInt("seconds"), tag.getInt("ticks"));
        }
    }
}

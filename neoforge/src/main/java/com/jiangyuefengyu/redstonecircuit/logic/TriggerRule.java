package com.jiangyuefengyu.redstonecircuit.logic;

/**
 * <b>红石触发器 - the three sentences as one pure function of two readings.</b>
 *
 * <p>一条上升沿切换一次开关: 输入从"没电"变成"有电"的那一次翻一下, 保持通电期间不再翻, 断电那一下也不翻. 这句话里有一件
 * <b>方块状态自己回答不了</b>的事 - 光看开关判不出"这一次是上升沿还是保持通电": 一个开着的触发器, 和一个刚刚被上升沿打开、
 * 此后一直通着电的触发器, 在"现在有电"这四个字上一模一样. 所以两个形态各存一份<b>上一次读到的输入</b> (世界形态存在方块
 * 状态的 {@code INPUT} 上, 内嵌形态存在 {@code Slot#triggerInput} 上), 而这条规则是唯一读它的地方 - 世界形态与内嵌形态
 * 调的是同一个 {@link #step}, 所以两条路不可能给出不同的答案.
 *
 * <p>输出只有两种: 开着 {@link #ON}, 关着 {@link #OFF}. 没有强度可调, 也就没有 0-15 之间的第三个答案.
 *
 * <p>纯函数: 没有 level, 没有 Slot, 没有时间 - 给"上次输入 / 这次输入 / 当前开关", 回"新的开关 / 新的上次输入". 它同时是
 * <b>幂等</b>的: 拿同一个输入再算一次什么都不变 (上一次输入已经等于这一次), 于是同一个 tick 里被求解多次也不会连翻两下.
 * 这不是靠"记住这一 tick 已经翻过"那种额外状态, 而是这条规则本身的性质 - 边沿记忆记的是<b>读数</b>, 所以它天然把重复的
 * 同一次读数合并掉了.
 */
public final class TriggerRule {

    /** 开着的时候背面答的强度 - 与其余器件同一个 15. */
    public static final int ON = PowerSolver.MAX_POWER;

    /** 关着的时候: 一声不响. */
    public static final int OFF = 0;

    private TriggerRule() {
    }

    /** 一次求值的结果: 新的开关, 以及这一次看过的输入 - 下一次判上升沿要拿它比. */
    public record Step(boolean powered, boolean input) {
    }

    /**
     * 走一步: 只有上升沿会翻开关.
     *
     * <p>{@code lastInput} 是上一次看过的输入 (也就是存档里那个), {@code input} 是这一次读到的, {@code powered} 是当前
     * 的开关. 返回的两个值都要写回: 输入<b>每一趟</b>都要记下来, 否则下一趟会把同一次通电再当成一次新的上升沿 - 一个一直
     * 通着电的输入会把开关翻成每趟一次.
     */
    public static Step step(boolean lastInput, boolean input, boolean powered) {
        boolean rising = input && !lastInput;
        return new Step(rising ? !powered : powered, input);
    }

    /** 一个强度算不算"有电": 任何非零读数都是 (这一件只认有/没有, 不认强度). */
    public static boolean live(int strength) {
        return strength > 0;
    }

    /** 开关对应的输出强度: 15 或 0, 没有别的可能. */
    public static int output(boolean powered) {
        return powered ? ON : OFF;
    }
}

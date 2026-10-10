package com.jiangyuefengyu.redstonecircuit.logic;

/**
 * 传输门's whole behaviour, as one function: 恰好一个副输入有信号时输出主输入的强度, 否则不输出。
 *
 * <p>"有信号" 指强度大于 0, 接在关着的元件上的副面不算; 输出的是主输入的强度, 而不是副输入的 - 四个副面只是放行的
 * 许可, 正面才是说话的内容。两个或更多副输入有信号是"没有输出", 而不是"更多信号"。
 */
public final class TransmissionRule {

    /** The 0-15 scale, as everywhere else in the mod. */
    public static final int MAX = 15;

    private TransmissionRule() {
    }

    /** {@code mainStrength} 是正面强度(0-15), {@code poweredSecondaries} 是四个副面中有信号的个数。 */
    public static int answer(int mainStrength, int poweredSecondaries) {
        return poweredSecondaries == 1 ? clamp(mainStrength) : 0;
    }

    /** Anything read out of a level is clamped into the scale before it is reasoned about. */
    public static int clamp(int strength) {
        return Math.max(0, Math.min(MAX, strength));
    }
}

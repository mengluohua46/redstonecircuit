package com.jiangyuefengyu.redstonecircuit.logic;

/**
 * 红石变压器: a level shifter - 正面读一个面, 背面答出玩家设定的强度, 读到 1 还是 15 都一样。
 *
 * <p>与中继器相反: 中继器把衰减的线恢复到 15, 它把线钉在设定强度上。输入为 0 时输出 0, 否则就成了一个关不掉的红石块。
 */
public final class TransformerRule {

    /**
     * 可设定的最弱强度。取 1 而不是 0: 0 不是强度, 而是"吞掉到达的一切", 那是个开关; 输入为空时本来就答 0。
     */
    public static final int MIN = 1;

    /** The strongest signal the back face can be told to answer with. */
    public static final int MAX = 15;

    /** Out of the box: any real input comes out at full strength, which is what a player expects of a first placement. */
    public static final int DEFAULT = MAX;

    /** Whether a number is a strength this device may be set to; the payload decoder and the screen both ask. */
    public static boolean isOutput(int value) {
        return value >= MIN && value <= MAX;
    }

    /** The same number, brought into range - what the block, the slot and the screen all store. */
    public static int clamp(int value) {
        return Math.max(MIN, Math.min(MAX, value));
    }

    /** 输入 > 0 答设定强度, 输入 == 0 答 0。{@code input} 为 0-15, {@code output} 为 1-15。 */
    public static int answer(int input, int output) {
        return input > 0 ? clamp(output) : 0;
    }

    private TransformerRule() {
    }
}

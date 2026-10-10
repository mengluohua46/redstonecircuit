package com.jiangyuefengyu.redstonecircuit.logic;

import net.minecraft.util.StringRepresentable;

/**
 * 逻辑整合器 - the seven gates, as one pure rule: the brief is about <em>numbers</em> only, so the whole table is a
 * function of ints and can be pinned without a running game.
 *
 * <p>X and Y come from the block's own screen, so {@link #UNSET} is a first-class value and not an error: a gate whose
 * numbers are not filled in does not work, and says which one is missing. 与门/或门/同或/异或 name only the case that
 * produces a signal, so each answers <b>0</b> when its condition does not hold.
 */
public final class LogicGate {

    /**
     * The value 非门/与门/或门… have before the player fills them in. <b>Not</b> zero: zero is a strength a player may
     * choose ("the input must be exactly nothing"), and a gate that cannot tell "I was told zero" from "I was told
     * nothing" cannot report the difference either, so the empty field travels as its own value from screen to table.
     */
    public static final int UNSET = -1;

    /** The strengths a gate can be given, and the strengths it can answer with. */
    public static final int MAX = 15;

    /**
     * The seven gates of 逻辑整合器, in the order the screen offers them. {@code inputs} is the count rule - 非门 takes
     * exactly one signal and every other mode exactly two, more or less does not work - and {@code yIsOutput} is the
     * one asymmetry: 同或 and 异或 answer with X.
     */
    public enum Mode implements StringRepresentable {
        /** 非门: exactly one input, equal to X, answers 0; any other single input answers Y. */
        NOT("not", 1, true),
        /** 与门: both inputs equal to X answer Y, anything else answers 0. */
        AND("and", 2, true),
        /** 或门: any input equal to X answers Y, otherwise 0. */
        OR("or", 2, true),
        /** 与非门: both inputs equal to X answer 0, otherwise Y. */
        NAND("nand", 2, true),
        /** 或非门: any input equal to X answers 0, otherwise Y. */
        NOR("nor", 2, true),
        /** 同或门: two equal inputs answer X, otherwise 0. */
        XNOR("xnor", 2, false),
        /** 异或门: two different inputs answer X, otherwise 0. */
        XOR("xor", 2, false);

        private final String name;
        private final int inputs;
        private final boolean outputsY;

        Mode(String name, int inputs, boolean outputsY) {
            this.name = name;
            this.inputs = inputs;
            this.outputsY = outputsY;
        }

        @Override
        public String getSerializedName() {
            return this.name;
        }

        /** The key suffix the screen's labels and the block state's property value are both built from. */
        public String key() {
            return this.name;
        }

        /** How many of the input faces must carry a signal. */
        public int neededInputs() {
            return this.inputs;
        }

        /** Whether this gate's answer is Y. False for 同或/异或, whose answer is X. */
        public boolean outputsY() {
            return this.outputsY;
        }
    }

    /**
     * Why a gate is not answering, or that it is. One value rather than a message, because the two places that say it
     * - the block's screen and the diagnostics - say it in their own words. The count failure is one verdict and not
     * two ("too few"/"too many"): the screen knows the count and the requirement from the same payload.
     */
    public enum Verdict {
        /** The gate answered. */
        OK,
        /** The wrong number of input faces carry a signal; see {@link Mode#neededInputs()}. */
        WRONG_INPUT_COUNT,
        /** X is still blank, and this gate reads it (every gate does). */
        X_UNSET,
        /** Y is still blank, and this gate answers with it. */
        Y_UNSET
    }

    /**
     * The answer and whether it is one. {@code power} is 0 whenever {@code verdict} is not {@link Verdict#OK} - "不工作"
     * is not a gate output of zero that could be mistaken for a decision.
     */
    public record Answer(int power, Verdict verdict) {

        /** True when the gate really decided this answer. */
        public boolean works() {
            return verdict == Verdict.OK;
        }
    }

    private LogicGate() {
    }

    /**
     * What this gate answers, given the player's two numbers and the strengths of the faces that count as inputs.
     *
     * <p>Which faces those are is the caller's question, because it needs a level ({@code LogicIntegratorBlock#sight});
     * a face whose wire is <b>off</b> is an input of strength 0, and 0 has to compare like any other value, or
     * "非门 X=10、输入 0 → 输出 Y" could never happen. The count is checked against {@link Mode#neededInputs()} first.
     */
    public static Answer evaluate(Mode mode, int x, int y, int... inputs) {
        if (mode == null) {
            return new Answer(0, Verdict.WRONG_INPUT_COUNT);
        }
        if (inputs.length != mode.neededInputs()) {
            return new Answer(0, Verdict.WRONG_INPUT_COUNT);
        }
        if (x == UNSET) {
            return new Answer(0, Verdict.X_UNSET);
        }
        if (mode.outputsY() && y == UNSET) {
            return new Answer(0, Verdict.Y_UNSET);
        }
        return new Answer(compute(mode, x, y, inputs), Verdict.OK);
    }

    /**
     * The seven sentences of the brief, one line each. Reached only with the counts and the two numbers already
     * settled, so no arm has to re-ask whether it is allowed to answer.
     */
    private static int compute(Mode mode, int x, int y, int[] inputs) {
        return switch (mode) {
            case NOT -> inputs[0] == x ? 0 : y;
            case AND -> allEqual(inputs, x) ? y : 0;
            case OR -> anyEqual(inputs, x) ? y : 0;
            case NAND -> allEqual(inputs, x) ? 0 : y;
            case NOR -> anyEqual(inputs, x) ? 0 : y;
            case XNOR -> inputs[0] == inputs[1] ? x : 0;
            case XOR -> inputs[0] != inputs[1] ? x : 0;
        };
    }

    private static boolean allEqual(int[] inputs, int x) {
        for (int input : inputs) {
            if (input != x) {
                return false;
            }
        }
        return true;
    }

    private static boolean anyEqual(int[] inputs, int x) {
        for (int input : inputs) {
            if (input == x) {
                return true;
            }
        }
        return false;
    }

    /** The state property value for a strength, or for {@link #UNSET}; see the block's {@code X}/{@code Y} properties. */
    public static int toState(int value) {
        return value == UNSET ? MAX + 1 : value;
    }

    /** The strength a state property value means, or {@link #UNSET}. */
    public static int fromState(int stateValue) {
        return stateValue == MAX + 1 ? UNSET : stateValue;
    }

    /** Whether {@code value} is a strength a player may type: 0-15, and never the blank sentinel. */
    public static boolean isStrength(int value) {
        return value >= 0 && value <= MAX;
    }

    /** Whether {@code value} may be stored: a strength, or {@link #UNSET}. */
    public static boolean isSettable(int value) {
        return value == UNSET || isStrength(value);
    }
}

package com.jiangyuefengyu.redstonecircuit.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.jiangyuefengyu.redstonecircuit.logic.LogicGate.Answer;
import com.jiangyuefengyu.redstonecircuit.logic.LogicGate.Mode;
import com.jiangyuefengyu.redstonecircuit.logic.LogicGate.Verdict;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 逻辑整合器's seven gates, sentence by sentence.
 *
 * <p>The rules are comparisons and counts of numbers, so none of them needs a level:
 * {@link LogicGate#evaluate} takes the strengths that arrived and answers what the gate answers.
 *
 * <p>Each gate gets its own test, walking the sentence it comes from: the case that produces a signal, the
 * case that does not, and the count that is not allowed. A gate whose condition does not hold answers
 * <b>0</b>, not something else.
 */
class LogicGateTest {

    /** A strength that is a legitimate setting, so the tests that pass it are not testing {@code UNSET} by accident. */
    private static final int X = 3;
    private static final int Y = 9;

    @Test
    @DisplayName("非门: the one input equal to X answers 0, any other single input answers Y")
    void theNotGate() {
        assertEquals(0, power(Mode.NOT, X, Y, X), "the input equals X, so the gate is off");
        assertEquals(Y, power(Mode.NOT, X, Y, 7), "one input that is not X answers Y");
        assertEquals(Y, power(Mode.NOT, X, Y, 15), "and the strength it is does not matter, only that it is not X");
        assertEquals(Y, power(Mode.NOT, X, Y, X + 1),
                "one step off X is still 'not X', so it answers Y like any other single input");
    }

    @Test
    @DisplayName("非门 with no input or with two: 不工作, because it allows exactly one")
    void theNotGateRefusesEveryOtherCount() {
        assertEquals(Verdict.WRONG_INPUT_COUNT, verdict(Mode.NOT, X, Y),
                "no input at all is not one input");
        assertEquals(Verdict.WRONG_INPUT_COUNT, verdict(Mode.NOT, X, Y, 4, 5),
                "两个输入 is more than the one this gate allows");
        assertEquals(0, power(Mode.NOT, X, Y, 4, 5), "and 不工作 is an answer of zero, not a decision");
    }

    @Test
    @DisplayName("与门: both inputs equal to X answer Y, anything else answers 0")
    void theAndGate() {
        assertEquals(Y, power(Mode.AND, X, Y, X, X), "两个输入都为X，则输出Y");
        assertEquals(0, power(Mode.AND, X, Y, X, X + 1), "one input that is not X is not 'both are X'");
        assertEquals(0, power(Mode.AND, X, Y, 15, 15), "and two equal inputs that are not X are not X either");
    }

    @Test
    @DisplayName("或门: any input equal to X answers Y, otherwise 0")
    void theOrGate() {
        assertEquals(Y, power(Mode.OR, X, Y, X, 15), "只要有一个输入为X，输出就是Y");
        assertEquals(Y, power(Mode.OR, X, Y, 15, X), "either side, not just the first");
        assertEquals(0, power(Mode.OR, X, Y, 4, 15), "neither input is X, so the gate is off");
    }

    @Test
    @DisplayName("与非门 and 或非门: 0 exactly when the plain gate would answer Y")
    void theNegatedGates() {
        assertEquals(0, power(Mode.NAND, X, Y, X, X), "两个输入都为X，则输出为0");
        assertEquals(Y, power(Mode.NAND, X, Y, X, 4), "否则输出为Y");
        assertEquals(0, power(Mode.NOR, X, Y, 4, X), "两个输入其中一个为X，则输出为0");
        assertEquals(Y, power(Mode.NOR, X, Y, 4, 5), "否则输出为Y");
    }

    @Test
    @DisplayName("同或门 and 异或门: the two inputs are compared with each other, and the answer is X")
    void theXorFamily() {
        assertEquals(X, power(Mode.XNOR, X, Y, 6, 6), "两个输入强度相同，则输出X");
        assertEquals(0, power(Mode.XNOR, X, Y, 6, 7), "different inputs are not 'the same'");
        assertEquals(X, power(Mode.XOR, X, Y, 6, 7), "两个输入不同，则输出X");
        assertEquals(0, power(Mode.XOR, X, Y, 6, 6), "and equal inputs are not 'different'");
        assertNotEquals(6, power(Mode.XNOR, X, Y, 6, 6),
                "the answer is X - the player's number - and not the strength that happened to arrive");
    }

    @Test
    @DisplayName("a blank X stops every gate; a blank Y stops only the gates that answer with Y")
    void theUnsetNumbersAreNotZero() {
        for (Mode mode : Mode.values()) {
            // The count each gate allows, so that what stops it is the blank number and not the wiring:
            // a gate that refuses two inputs would answer WRONG_INPUT_COUNT here and hide the case.
            int[] arrived = strengths(X, mode.neededInputs());
            assertEquals(Verdict.X_UNSET, verdict(mode, LogicGate.UNSET, Y, arrived),
                    mode.key() + " reads X, so an empty X must stop it");
            assertEquals(0, power(mode, LogicGate.UNSET, Y, arrived), "and it answers nothing meanwhile");
        }
        for (Mode mode : List.of(Mode.NOT, Mode.AND, Mode.OR, Mode.NAND, Mode.NOR)) {
            assertEquals(Verdict.Y_UNSET, verdict(mode, X, LogicGate.UNSET, strengths(X, mode.neededInputs())),
                    mode.key() + " answers with Y, so an empty Y must stop it");
        }
        for (Mode mode : List.of(Mode.XNOR, Mode.XOR)) {
            assertTrue(LogicGate.evaluate(mode, X, LogicGate.UNSET, 4, 5).works(),
                    mode.key() + " answers with X and never reads Y, so a blank Y is not its business");
        }
    }

    /** All {@code count} inputs at one strength - the shape a caller hands in when the wiring is right. */
    private static int[] strengths(int strength, int count) {
        int[] inputs = new int[count];
        java.util.Arrays.fill(inputs, strength);
        return inputs;
    }

    @Test
    @DisplayName("'X = 0' and 'X is blank' are different settings, which is the whole reason UNSET is not 0")
    void zeroIsAValue() {
        assertEquals(Y, power(Mode.NOT, 0, Y, 7), "X = 0 with an input of 7 answers Y: 7 is not 0");
        assertEquals(Y, power(Mode.NOT, 0, Y, 0 + 1), "and 1 is not 0 either");
        assertTrue(LogicGate.evaluate(Mode.NOT, 0, Y, 7).works(), "X = 0 is a decision, not a missing value");
        assertFalse(LogicGate.evaluate(Mode.NOT, LogicGate.UNSET, Y, 7).works(),
                "while a blank X stops the gate - two different answers for two different states");
    }

    @Test
    @DisplayName("the caller decides what counts as an input, and 0 is then a value like any other")
    void theCallerDecidesWhatAnInputIs() {
        // The face below has a wire on it and the wire is off: one input, reading 0.
        assertEquals(Y, power(Mode.NOT, 10, Y, 0), "one wired face reading 0 is one input, and 0 != 10 answers Y");
        assertEquals(0, power(Mode.NOT, 0, Y, 0), "and with X = 0 that same wiring answers 0: 0 == 0");
        assertEquals(Y, power(Mode.AND, 0, Y, 0, 0), "与门 with two wired faces both reading 0 has both equal to X = 0");
        assertEquals(0, power(Mode.AND, 5, Y, 0, 0), "while X = 5 makes the same two faces 'not both X'");
        assertEquals(Verdict.WRONG_INPUT_COUNT, verdict(Mode.AND, 5, Y, 0),
                "and one wired face is still only one input: the count is the count of faces, not of signals");
        assertEquals(Y, power(Mode.OR, 0, Y, 0, 7), "或门: the 0 matches X = 0, so the answer is Y");
        assertEquals(4, power(Mode.XNOR, 4, LogicGate.UNSET, 0, 0),
                "同或门 compares the two inputs with each other and answers X, so two wired faces both reading 0 answer 4");
    }

    @Test
    @DisplayName("the count each gate demands")
    void theCountsAreTheBriefs() {
        assertEquals(1, Mode.NOT.neededInputs(), "非门模式只允许一个信号输入");
        for (Mode mode : List.of(Mode.AND, Mode.OR, Mode.NAND, Mode.NOR, Mode.XNOR, Mode.XOR)) {
            assertEquals(2, mode.neededInputs(), mode.key() + " 只能有两个信号输入");
            assertEquals(Verdict.WRONG_INPUT_COUNT, verdict(mode, X, Y, X),
                    mode.key() + " with one input is 大于或小于, so it does not work");
            assertEquals(Verdict.WRONG_INPUT_COUNT, verdict(mode, X, Y, X, X, X),
                    mode.key() + " with three inputs is 大于, so it does not work either");
        }
        assertFalse(Mode.XOR.outputsY(), "同或/异或 answer with X");
        assertTrue(Mode.NAND.outputsY(), "the other five answer with Y");
    }

    @Test
    @DisplayName("the screen's order, the property's names and the state's two sentinels all agree")
    void theNamesAndTheStateRoundTrip() {
        assertEquals(List.of(Mode.NOT, Mode.AND, Mode.OR, Mode.NAND, Mode.NOR, Mode.XNOR, Mode.XOR),
                List.copyOf(List.of(Mode.values())), "the order the screen offers them in");
        for (Mode mode : Mode.values()) {
            assertEquals(mode.name().toLowerCase(java.util.Locale.ROOT), mode.getSerializedName());
            assertEquals(mode.getSerializedName(), mode.key(), "the label key and the property value are one name");
        }
        assertEquals(0, LogicGate.toState(0));
        assertEquals(15, LogicGate.toState(15));
        assertEquals(16, LogicGate.toState(LogicGate.UNSET),
                "a block state property cannot hold -1, so 'blank' travels as the value above the strengths");
        for (int value : List.of(0, 1, 7, 15, LogicGate.UNSET)) {
            assertEquals(value, LogicGate.fromState(LogicGate.toState(value)), "value " + value + " round-trips");
        }
        assertTrue(LogicGate.isSettable(0) && LogicGate.isSettable(15) && LogicGate.isSettable(LogicGate.UNSET));
        assertFalse(LogicGate.isSettable(16), "16 is the blank sentinel in a state, never a strength");
        assertFalse(LogicGate.isSettable(-2), "and nothing below UNSET is a strength");
    }

    private static int power(Mode mode, int x, int y, int... inputs) {
        return LogicGate.evaluate(mode, x, y, inputs).power();
    }

    private static Verdict verdict(Mode mode, int x, int y, int... inputs) {
        Answer answer = LogicGate.evaluate(mode, x, y, inputs);
        return answer.verdict();
    }
}

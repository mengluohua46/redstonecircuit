package com.jiangyuefengyu.redstonecircuit.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 红石变压器's rule, pinned without a running game: 有输入时输出设定强度, 无输入时输出 0。
 */
class TransformerRuleTest {

    @Test
    @DisplayName("无论输入多少：只要有输入，输出就是设定值")
    void anyInputComesOutAtTheSetting() {
        for (int input = 1; input <= TransformerRule.MAX; input++) {
            assertEquals(7, TransformerRule.answer(input, 7),
                    "输入 " + input + " 是活的信号，输出必须是设定值 7");
        }
        assertEquals(1, TransformerRule.answer(15, 1), "设定 1 时，最强的输入也只输出 1");
        assertEquals(15, TransformerRule.answer(1, 15), "设定 15 时，最弱的输入也输出满强度 - 这就是它和原版中继器的区别");
    }

    @Test
    @DisplayName("无输入就是 0：不然它就是一块关不掉的红石块")
    void noInputIsZero() {
        for (int setting = TransformerRule.MIN; setting <= TransformerRule.MAX; setting++) {
            assertEquals(0, TransformerRule.answer(0, setting),
                    "输入为 0 时必须输出 0，设定 " + setting + " 也不例外");
        }
    }

    @Test
    @DisplayName("默认值是 15：放下就能用，且十六进制之外的数字不被接受")
    void theDefaultAndTheRange() {
        assertEquals(15, TransformerRule.DEFAULT, "新放下的变压器默认输出满强度");
        assertTrue(TransformerRule.isOutput(TransformerRule.DEFAULT));
        assertTrue(TransformerRule.isOutput(1) && TransformerRule.isOutput(15));
        assertFalse(TransformerRule.isOutput(0),
                "0 不是一个「输出强度」：它等于「有输入也不输出」，那是一个开关，而这个装置无输入时本来就是 0");
        assertFalse(TransformerRule.isOutput(16));
        assertFalse(TransformerRule.isOutput(-1));

        assertEquals(1, TransformerRule.clamp(0), "夹取的下界是 1，和「0 不是输出强度」是同一条");
        assertEquals(15, TransformerRule.clamp(16));
        assertEquals(9, TransformerRule.clamp(9));
    }

    @Test
    @DisplayName("输出总是被夹在 1-15：越界的设定按上下界回答，而不是原样漏出去")
    void theAnswerIsClamped() {
        assertEquals(1, TransformerRule.answer(5, 0),
                "设定 0（老存档里没有这个键时读到的值）必须被读成下界，而不是让背面回答 0 强度");
        assertEquals(15, TransformerRule.answer(5, 99));
    }
}

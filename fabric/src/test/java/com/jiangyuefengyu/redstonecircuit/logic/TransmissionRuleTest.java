package com.jiangyuefengyu.redstonecircuit.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 传输门's rule, as a table: 恰好一个副输入有信号时输出主输入的强度, 没有或不止一个副输入有信号时不输出。
 *
 * <p>副输入只被计数, 从不被当作数值读取, 所以两个副输入是静默而不是"更多信号"; 强度 1 的副输入也放行完整的主强度。
 */
class TransmissionRuleTest {

    @Test
    @DisplayName("恰好一个副输入有信号：输出 = 主输入强度")
    void oneSideInputPassesTheMainStrength() {
        assertEquals(7, TransmissionRule.answer(7, 1));
        assertEquals(15, TransmissionRule.answer(15, 1));
        assertEquals(1, TransmissionRule.answer(1, 1), "一格的强度也是一格，不会被放大或四舍五入");
        assertEquals(0, TransmissionRule.answer(0, 1), "主输入本身没有信号时，放行出来的也只能是 0");
    }

    @Test
    @DisplayName("没有副输入有信号：不输出")
    void noSideInputIsSilence() {
        assertEquals(0, TransmissionRule.answer(15, 0));
        assertEquals(0, TransmissionRule.answer(0, 0));
    }

    @Test
    @DisplayName("多于一个副输入有信号：不输出")
    void moreThanOneSideInputIsSilence() {
        for (int count = 2; count <= 4; count++) {
            assertEquals(0, TransmissionRule.answer(15, count), "副输入个数：" + count);
        }
    }

    @Test
    @DisplayName("任何强度先夹进 0-15 再参与判断（从世界读到的东西不保证在刻度里）")
    void strengthsAreClampedIntoTheScale() {
        assertEquals(0, TransmissionRule.answer(-3, 1));
        assertEquals(15, TransmissionRule.answer(99, 1));
        assertEquals(0, TransmissionRule.answer(20, 2), "越界不会把'不输出'变成输出");
        assertEquals(0, TransmissionRule.clamp(0));
        assertEquals(15, TransmissionRule.clamp(15));
    }
}

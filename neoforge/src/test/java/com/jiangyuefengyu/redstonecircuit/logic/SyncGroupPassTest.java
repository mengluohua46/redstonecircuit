package com.jiangyuefengyu.redstonecircuit.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.jiangyuefengyu.redstonecircuit.logic.SyncGroups.Answer;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * 组 pass 的那一条语义, 不需要 ServerLevel.
 *
 * <p>"同一频道一起亮"与"未绑定的自成一个组"是两件事, 而它们的区别只有一处: 前者看全组的 OR, 后者只看自己。写错的后果
 * 是两个刚放下、还没调频的火把在墙上互相点亮 - 玩家看不出这是 bug, 只会认为这件设备坏了。
 *
 * <p>输入在这里是一个<b>掩码</b>(一位一面): 答案因此分两半 —— 亮不亮是全组的事, 哪一面不输出是成员自己的处境。
 */
class SyncGroupPassTest {

    private static final BlockPos A = new BlockPos(0, 64, 0);
    private static final BlockPos B = new BlockPos(30, 64, 0);
    private static final BlockPos C = new BlockPos(-30, 70, 5);

    /** 表里没有的位置就是"一面也没有输入". */
    private static Map<BlockPos, Answer> pass(SyncChannels channels, Map<BlockPos, Integer> masks) {
        return SyncGroups.decide(channels, masks);
    }

    @Test
    @DisplayName("两根未绑定的火把互不联动：一根亮，另一根照样灭")
    void unboundTorchesAreIndependent() {
        SyncChannels channels = new SyncChannels();
        channels.putSolo(A);
        channels.putSolo(B);

        Map<BlockPos, Answer> answer = pass(channels, Map.of(B, 15));

        assertEquals(0, answer.get(A).power(), "B 有信号，不该点亮未绑定的 A：它们不是一组");
        assertEquals(15, answer.get(B).power(), "各看各的输入");
        assertTrue(channels.channels().isEmpty(), "未绑定的成员不进任何频道");
    }

    @Test
    @DisplayName("未绑定的火把照样能用：任一面有信号就亮，没信号就灭")
    void anUnboundTorchStillAnswersItsOwnInput() {
        SyncChannels channels = new SyncChannels();
        channels.putSolo(A);

        assertEquals(15, pass(channels, Map.of(A, 3)).get(A).power(), "有信号就亮（强度不看大小）");
        assertEquals(0, pass(channels, Map.of()).get(A).power(), "没信号就灭");
    }

    @Test
    @DisplayName("同一频道是 OR：任一成员有输入，全组一起亮，而掩码各归各")
    void oneMemberInputLightsTheWholeGroup() {
        SyncChannels channels = new SyncChannels();
        channels.put(A, 4);
        channels.put(B, 4);

        int bEast = SyncTorchRule.bit(Direction.EAST);
        Map<BlockPos, Answer> answer = pass(channels, Map.of(B, bEast));

        assertEquals(15, answer.get(A).power(), "B 收到信号，A 也跟着亮 - 这就是同步");
        assertEquals(15, answer.get(B).power());
        assertEquals(0, answer.get(A).inputs(), "A 自己一面都没收到, 所以它照常往六面输出");
        assertEquals(bEast, answer.get(B).inputs(), "B 的东面收到了, 那一面从此不输出");
        assertEquals(2, channels.membersOf(4).size());
    }

    @Test
    @DisplayName("一个成员都没有信号，全组同 tick 一起灭")
    void noMemberInputDarkensTheWholeGroup() {
        SyncChannels channels = new SyncChannels();
        channels.put(A, 4);
        channels.put(B, 4);

        Map<BlockPos, Answer> answer = pass(channels, Map.of());

        assertEquals(0, answer.get(A).power());
        assertEquals(0, answer.get(B).power());
    }

    @Test
    @DisplayName("两个频道互不影响，未绑定的也不掺进去")
    void channelsDoNotLeakIntoEachOther() {
        SyncChannels channels = new SyncChannels();
        channels.put(A, 4);
        channels.put(B, 5);
        channels.putSolo(C);

        Map<BlockPos, Answer> answer = pass(channels, Map.of(A, 15, C, 15));

        assertEquals(15, answer.get(A).power(), "自己的组有人有信号");
        assertEquals(0, answer.get(B).power(), "另一组不许被点亮");
        assertEquals(15, answer.get(C).power(), "未绑定的看自己");
        assertFalse(answer.containsKey(new BlockPos(1, 2, 3)), "表里没有的位置不算答案");
    }
}

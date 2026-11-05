package com.jiangyuefengyu.redstonecircuit.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;

import net.minecraft.core.Direction;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 同步火把's rule, pinned without a running game.
 *
 * <p>这件器件只有三件事值得钉住: 亮灭是 OR (任一成员有输入 → 全组亮), 亮的时候答满强度, 以及一个频道号永远算出同一个
 * 颜色 - 颜色不上线, 两端各算一遍, 所以"两端算出同一个数"必须是一条可以离线检查的规则。
 */
class SyncTorchRuleTest {

    @Test
    @DisplayName("同一个 channel 永远算出同一个颜色")
    void colourIsStableForOneChannel() {
        for (int channel : new int[] {1, 2, 3, 17, 64, 1_000}) {
            assertEquals(SyncTorchRule.colour(channel), SyncTorchRule.colour(channel),
                    "channel " + channel + " 必须是一个纯函数: 两端各自算出来要一样");
        }
        // 不透明: 客户端画的世界颜色要能直接塞进 ARGB, 没有透明度会被当成全透明。
        assertEquals(0xFF, SyncTorchRule.colour(1) >>> 24, "颜色必须是不透明的");
    }

    @Test
    @DisplayName("channel 1..64 两两不同: 并排的火把不能撞色")
    void colourSeparatesTheFirstSixtyFourChannels() {
        Set<Integer> seen = new HashSet<>();
        for (int channel = 1; channel <= 64; channel++) {
            assertTrue(seen.add(SyncTorchRule.colour(channel)),
                    "channel " + channel + " 与前面某个频道算出了同一个颜色");
        }
        assertEquals(64, seen.size());
    }

    @Test
    @DisplayName("未绑定是一个确定的颜色, 且不属于任何频道号")
    void unboundHasItsOwnColour() {
        assertEquals(SyncTorchRule.UNBOUND_COLOUR, SyncTorchRule.colour(SyncTorchRule.UNBOUND));
        assertEquals(SyncTorchRule.colour(0), SyncTorchRule.colour(SyncTorchRule.UNBOUND),
                "0 和 -1 都不是频道, 两个都拿到未绑定的颜色");
        for (int channel = 1; channel <= 64; channel++) {
            assertNotEquals(SyncTorchRule.UNBOUND_COLOUR, SyncTorchRule.colour(channel),
                    "channel " + channel + " 不许和未绑定的灰色撞色");
        }
    }

    @Test
    @DisplayName("亮灭就是 OR: 任一成员有输入就亮")
    void litIsTheAnyOfTheGroup() {
        assertTrue(SyncTorchRule.lit(true), "有成员收到信号 → 亮");
        assertFalse(SyncTorchRule.lit(false), "一个都没有 → 灭");
    }

    @Test
    @DisplayName("亮的时候答满强度, 灭的时候答 0")
    void outputIsFullSpeedOrNothing() {
        assertEquals(PowerSolver.MAX_POWER, SyncTorchRule.output(true));
        assertEquals(15, SyncTorchRule.output(true));
        assertEquals(0, SyncTorchRule.output(false));
    }

    @Test
    @DisplayName("频道号从 1 开始, 别的都不是频道")
    void onlyPositiveNumbersAreChannels() {
        assertFalse(SyncTorchRule.isChannel(SyncTorchRule.UNBOUND), "未绑定不是频道");
        assertFalse(SyncTorchRule.isChannel(0), "0 表示这里没有火把, 不是频道");
        assertFalse(SyncTorchRule.isChannel(-7));
        assertTrue(SyncTorchRule.isChannel(1), "第一个频道是 1");
        assertTrue(SyncTorchRule.isChannel(9_999));
    }

    @Test
    @DisplayName("一轮 pass 的迭代上限是 4")
    void thePassIsBounded() {
        assertEquals(4, SyncTorchRule.MAX_PASS_ITERATIONS,
                "两个组互相当对方的输入时, 收敛回合必须有个上限, 否则一个 tick 就没有尽头");
    }

    @Test
    @DisplayName("一面有输入就不输出: 掩码把那一面关掉, 吸附面永远是关的")
    void anInputFaceStopsEmitting() {
        byte none = 0;
        byte east = SyncTorchRule.bit(Direction.EAST);
        // 立着的一支: 吸附面朝下, 所以往下本来就不输出。
        assertTrue(SyncTorchRule.emitsOn(none, Direction.DOWN, Direction.UP), "没有输入时朝上输出");
        assertFalse(SyncTorchRule.emitsOn(none, Direction.DOWN, Direction.DOWN), "吸附面永远不输出");
        assertTrue(SyncTorchRule.emitsOn(east, Direction.DOWN, Direction.WEST), "东边有输入不影响西边输出");
        assertFalse(SyncTorchRule.emitsOn(east, Direction.DOWN, Direction.EAST),
                "东边有输入, 东边就不输出 - 这是不自锁的全部机制");
        assertTrue(SyncTorchRule.hasInput(east, Direction.EAST));
        assertFalse(SyncTorchRule.hasInput(east, Direction.WEST));
        for (Direction side : Direction.values()) {
            assertFalse(SyncTorchRule.hasInput(none, side), "空掩码说的是六面都没有");
        }
    }

    @Test
    @DisplayName("我们正在往它输出的那一面不听: 它的读数可能就是我们自己那一份")
    void aFaceWeAreDrivingIsNotListenedTo() {
        byte none = 0;
        // 亮着、没有输入: 五面都在输出, 那五面都不听; 吸附面本来就不输出, 所以它照旧听。
        assertFalse(SyncTorchRule.listensTo(none, Direction.DOWN, true, Direction.EAST),
                "东面正在输出, 不听 - 否则就是自己喂自己(每两 tick 抖一次)");
        assertTrue(SyncTorchRule.listensTo(none, Direction.DOWN, true, Direction.DOWN),
                "吸附面永远不输出, 所以它永远听");
        // 灭着: 什么都不输出, 于是每一面都听 —— 这也正是"灭着的火把读到的信号不可能是自己造的"。
        assertTrue(SyncTorchRule.listensTo(none, Direction.DOWN, false, Direction.EAST));
        // 某一面已经是输入: 我们不再往它输出, 于是它继续被听着(外部信号撤掉时才能跟着灭)。
        byte east = SyncTorchRule.bit(Direction.EAST);
        assertTrue(SyncTorchRule.listensTo(east, Direction.DOWN, true, Direction.EAST),
                "已经在听的那一面必须继续听, 否则外部信号一走它就再也看不见自己该灭了");
        assertFalse(SyncTorchRule.listensTo(east, Direction.DOWN, true, Direction.WEST),
                "其余还在输出的那几面照旧不听");
    }

    @Test
    @DisplayName("一个邻居给多少: 导体只算它的弱信号(里面只有强充能那一份), 其余强弱都算")
    void askReadsConductorsAndSourcesDifferently() {
        assertEquals(15, SyncTorchRule.ask(true, 15, 0), "被拉杆强充能的石头");
        assertEquals(0, SyncTorchRule.ask(true, 0, 15),
                "导体这一路不读 direct signal: 弱信号里已经含了被强充能的那一份");
        assertEquals(15, SyncTorchRule.ask(false, 15, 0), "红石粉/红石块答的弱信号");
        assertEquals(15, SyncTorchRule.ask(false, 0, 15), "非导体的 direct signal 也算");
        assertEquals(0, SyncTorchRule.ask(false, 0, 0), "什么都没有就是 0");
    }

}

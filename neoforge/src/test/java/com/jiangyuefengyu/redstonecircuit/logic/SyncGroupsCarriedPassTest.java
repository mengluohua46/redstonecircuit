package com.jiangyuefengyu.redstonecircuit.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.jiangyuefengyu.redstonecircuit.carrier.CarrierId;
import com.jiangyuefengyu.redstonecircuit.logic.SyncGroups.Answer;
import com.jiangyuefengyu.redstonecircuit.logic.SyncGroups.Roster;
import com.jiangyuefengyu.redstonecircuit.logic.SyncGroups.Spot;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * 组 pass 跨"世界 / 机器"的那一条语义, 不需要 ServerLevel.
 *
 * <p>口径是一句话: 同一频道的成员算同一组, <b>不论它在世界上还是在被搬着的机器里</b>。这里钉的就是这句话 - 世界
 * 成员与搬运成员在同一个频道号下, OR 要一起算; 未绑定的搬运成员与世界那支同一条规则 (自成一个组); 两台不同机器的同
 * 频道成员是同一组。
 *
 * <p>为什么这些值而不是别的: 一条写错的 OR 不会崩, 它只会让"机器经过那盏灯"或"隔着两台机器的同一组"不亮 - 玩家看不
 * 出这是 bug, 只会认为这件设备坏了。
 */
class SyncGroupsCarriedPassTest {

    private static final BlockPos WORLD = new BlockPos(10, 64, 10);
    private static final CarrierId MACHINE = CarrierId.subLevel(new UUID(1L, 1L));
    private static final CarrierId OTHER_MACHINE = CarrierId.subLevel(new UUID(2L, 2L));
    private static final BlockPos LOCAL = new BlockPos(3, 1, 0);
    private static final BlockPos OTHER_LOCAL = new BlockPos(-2, 4, 6);

    private static Map<Spot, Answer> pass(Roster roster, Map<Spot, Integer> masks) {
        return SyncGroups.decideRoster(roster, masks);
    }

    @Test
    @DisplayName("世界里的成员收到信号，机器里的同频道成员一起亮")
    void aWorldMemberLightsAMemberInsideAMachine() {
        Roster roster = new Roster();
        roster.put(WORLD, 7);
        roster.putCarried(MACHINE, LOCAL, 7);

        Map<Spot, Answer> answer = pass(roster, Map.of(Spot.standing(WORLD), 15));

        assertEquals(15, answer.get(Spot.carried(MACHINE, LOCAL)).power(), "同一频道就是同一组，机器里的那支跟着亮");
        assertEquals(15, answer.get(Spot.standing(WORLD)).power());
    }

    @Test
    @DisplayName("机器里的成员收到信号，世界里的同频道成员一起亮")
    void aCarriedMemberLightsAWorldMember() {
        Roster roster = new Roster();
        roster.put(WORLD, 7);
        roster.putCarried(MACHINE, LOCAL, 7);

        int movedEast = SyncTorchRule.bit(Direction.EAST);
        Map<Spot, Answer> answer = pass(roster, Map.of(Spot.carried(MACHINE, LOCAL), movedEast));

        assertEquals(15, answer.get(Spot.standing(WORLD)).power(), "机器照亮了世界的另一半 - OR 不分在哪一侧");
        assertEquals(0, answer.get(Spot.standing(WORLD)).inputs(), "世界那支自己一面都没收到, 所以它照常往六面输出");
        assertEquals(movedEast, answer.get(Spot.carried(MACHINE, LOCAL)).inputs(), "机器那支的东面收到了, 那一面不输出");
    }

    @Test
    @DisplayName("同一频道在两台不同的机器上：一起亮，而掩码各归各")
    void twoMachinesOnOneChannelAreOneGroup() {
        Roster roster = new Roster();
        roster.putCarried(MACHINE, LOCAL, 9);
        roster.putCarried(OTHER_MACHINE, OTHER_LOCAL, 9);

        Map<Spot, Answer> lit = pass(roster, Map.of(Spot.carried(OTHER_MACHINE, OTHER_LOCAL), 1));

        assertEquals(15, lit.get(Spot.carried(MACHINE, LOCAL)).power(), "第二台机器有信号，第一台也亮");
        assertEquals(15, lit.get(Spot.carried(OTHER_MACHINE, OTHER_LOCAL)).power());
        assertEquals(0, pass(roster, Map.of()).get(Spot.carried(MACHINE, LOCAL)).power(), "都没信号就一起灭");
    }

    @Test
    @DisplayName("未绑定的搬运成员自成一个组：绝不点亮世界里的或别的机器上的未绑定火把")
    void anUnboundCarriedMemberIsAlone() {
        Roster roster = new Roster();
        roster.putSoloCarried(MACHINE, LOCAL);
        roster.putSoloCarried(OTHER_MACHINE, OTHER_LOCAL);
        roster.putSolo(WORLD);

        Map<Spot, Answer> answer = pass(roster, Map.of(Spot.standing(WORLD), 15));

        assertEquals(0, answer.get(Spot.carried(MACHINE, LOCAL)).power(), "世界那支有信号，机器里未绑定的那支照旧灭");
        assertEquals(0, answer.get(Spot.carried(OTHER_MACHINE, OTHER_LOCAL)).power(), "两台机器上的未绑定成员也互不相干");
        assertEquals(15, answer.get(Spot.standing(WORLD)).power(), "未绑定的看自己");
        assertFalse(roster.channels().contains(1), "未绑定的成员不进任何频道");
    }

    @Test
    @DisplayName("未绑定的搬运成员照样能用：自己的吸附面有信号就亮")
    void anUnboundCarriedMemberStillAnswersItsOwnInput() {
        Roster roster = new Roster();
        roster.putSoloCarried(MACHINE, LOCAL);

        assertEquals(15, pass(roster, Map.of(Spot.carried(MACHINE, LOCAL), 4))
                .get(Spot.carried(MACHINE, LOCAL)).power());
        assertEquals(0, pass(roster, Map.of()).get(Spot.carried(MACHINE, LOCAL)).power());
    }

    @Test
    @DisplayName("三种形态同一频道: 立着的、塞在方块里的、被机器搬着的，一起亮而掩码各归各")
    void theStandingTheInnerAndTheCarriedFormsAreOneGroup() {
        BlockPos inner = new BlockPos(-40, 64, 12);
        Roster roster = new Roster();
        roster.put(WORLD, 6);
        roster.put(inner, 6);
        roster.putCarried(MACHINE, LOCAL, 6);

        int movedSouth = SyncTorchRule.bit(Direction.SOUTH);
        Map<Spot, Answer> answer = pass(roster, Map.of(Spot.carried(MACHINE, LOCAL), movedSouth));

        // 机器里那一支收到信号: 世界里立着的、方块里塞着的都跟着亮 - 同一频道就是同一组, 不分在哪一形态里。
        assertEquals(15, answer.get(Spot.standing(WORLD)).power());
        assertEquals(15, answer.get(Spot.standing(inner)).power());
        assertEquals(15, answer.get(Spot.carried(MACHINE, LOCAL)).power());
        assertEquals(0, answer.get(Spot.standing(WORLD)).inputs(), "世界那一支一面都没收到, 六面照常输出");
        assertEquals(movedSouth, answer.get(Spot.carried(MACHINE, LOCAL)).inputs(),
                "搬运那一支自己南面收到了, 那一面不输出 - 掩码各归各");
        assertEquals(0, pass(roster, Map.of()).get(Spot.carried(MACHINE, LOCAL)).power(), "都没信号就一起灭");
    }

    @Test
    @DisplayName("两个频道互不影响，搬运成员也不许漏到隔壁组")
    void channelsDoNotLeakAcrossTheCarriedHalf() {
        Roster roster = new Roster();
        roster.put(WORLD, 4);
        roster.putCarried(OTHER_MACHINE, OTHER_LOCAL, 4);
        roster.putCarried(MACHINE, LOCAL, 5);

        Map<Spot, Answer> answer = pass(roster, Map.of(Spot.standing(WORLD), 15));

        assertEquals(15, answer.get(Spot.carried(OTHER_MACHINE, OTHER_LOCAL)).power(),
                "四号那台机器与世界里那支同组, 世界那支有信号它就亮");
        assertEquals(0, answer.get(Spot.carried(MACHINE, LOCAL)).power(), "五号没有信号, 不许被四号点亮");
        assertEquals(2, roster.channels().size(), "两个频道都在表里");
    }
}

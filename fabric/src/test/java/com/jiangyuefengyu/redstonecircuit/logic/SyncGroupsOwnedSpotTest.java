package com.jiangyuefengyu.redstonecircuit.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.jiangyuefengyu.redstonecircuit.carrier.CarrierId;
import com.jiangyuefengyu.redstonecircuit.logic.SyncGroups.Answer;
import com.jiangyuefengyu.redstonecircuit.logic.SyncGroups.CarriedSpot;
import com.jiangyuefengyu.redstonecircuit.logic.SyncGroups.Roster;
import com.jiangyuefengyu.redstonecircuit.logic.SyncGroups.Snapshot;
import com.jiangyuefengyu.redstonecircuit.logic.SyncGroups.Spot;

import net.minecraft.core.BlockPos;

/**
 * <b>一支站在 plot 坐标上的火把属于哪一段</b> - 要钉的 bug: 眼镜把线画到了很远的地方.
 *
 * <p>一台 Sable 子层级的方块实体就躺在自己的 plot 坐标上, 那是一个真实存在、离玩家很远的世界坐标。名单要是按"它在
 * level 里有没有一格"两段一分, 机器里的那一支就以那个远方坐标进了<b>世界段</b>, 客户端照世界坐标画线 - 那不是渲染
 * 错, 是名单里混进了 plot 坐标。于是判据必须是"这个坐标归不归一台活着的机器": 归, 它就是 (机器, 机器内坐标), 归了
 * 却说不清画在哪, 就两段都不放。
 *
 * <p>这里钉的是纯值那一半: 归属由 {@link SyncGroups.Ownership} 交进来, 于是不需要游戏。世界那一侧的实现在
 * {@link SyncGroups} 的 {@code Inquiry}。
 */
class SyncGroupsOwnedSpotTest {

    private static final CarrierId MACHINE =
            CarrierId.subLevel(UUID.fromString("00000000-0000-0000-0000-0000000000c1"));

    /** 一个真的会出现在子层级身上的坐标: 远, 而且是 level 里的一格。 */
    private static final BlockPos PLOT = new BlockPos(120_000, 64, -90_000);

    private static final BlockPos WORLD = new BlockPos(10, 64, 10);

    private static final int CHANNEL = 5;

    private static SyncChannels only(BlockPos pos, int channel) {
        SyncChannels channels = new SyncChannels();
        channels.put(pos, channel);
        return channels;
    }

    @Test
    @DisplayName("一台机器上的世界坐标位置被换算进搬运段, 世界段里没有它")
    void aPositionOnAMachineIsFiledAsCarried() {
        Roster roster = SyncGroups.roster(only(PLOT, CHANNEL),
                pos -> SyncGroups.placed(pos, MACHINE, pos));

        Snapshot snapshot = SyncGroups.snapshot(roster);
        assertEquals(List.of(new CarriedSpot(MACHINE, PLOT)), snapshot.carried().get(CHANNEL),
                "这一格的地址是 (机器, 机器内坐标): 一个 Sable 子层级的方块就站在自己的 plot 坐标上");
        assertNull(snapshot.standing().get(CHANNEL),
                "而世界段里没有它 - 那个远方坐标正是客户端会照着画一条线的数");
    }

    @Test
    @DisplayName("归属成立但说不清它画在哪: 两段都不出现")
    void aPositionThatCannotBeConvertedIsInNeitherSegment() {
        Roster roster = SyncGroups.roster(only(PLOT, CHANNEL),
                pos -> SyncGroups.placed(pos, MACHINE, null));

        Snapshot snapshot = SyncGroups.snapshot(roster);
        assertNull(snapshot.carried().get(CHANNEL), "没有位姿就没有坐标, 于是搬运段也不许有它");
        assertNull(snapshot.standing().get(CHANNEL), "世界段更不许: 宁可这一帧不画, 也不给一个很远的地方");
        assertTrue(snapshot.standing().isEmpty() && snapshot.carried().isEmpty(),
                "整份名单里它一格都没有 - 频道因此不出现, 而不是出现一个空组");
    }

    @Test
    @DisplayName("没人搬的世界位置照旧走世界段")
    void anOrdinaryWorldPositionStaysStanding() {
        Roster roster = SyncGroups.roster(only(WORLD, CHANNEL), SyncGroups.Ownership.STANDING);

        Snapshot snapshot = SyncGroups.snapshot(roster);
        assertEquals(Set.of(WORLD), snapshot.standing().get(CHANNEL), "它是世界里的一格, 地址就是那一格");
        assertNull(snapshot.carried().get(CHANNEL), "没有人搬它");
    }

    @Test
    @DisplayName("同一个位置不会两段各算一次: 搬运段的那一个成员只算一个")
    void oneAddressIsOneMember() {
        Roster roster = SyncGroups.roster(only(PLOT, CHANNEL),
                pos -> SyncGroups.placed(pos, MACHINE, pos));
        Spot spot = Spot.carried(MACHINE, PLOT);
        assertTrue(roster.holds(spot),
                "收表时机器作用域那张表要问这一句: 已经进过名单的地址不再算第二个成员");
        assertFalse(roster.holds(Spot.standing(PLOT)),
                "而'世界里的那一格'是另一个地址, 它没有进这张名单 - 这一条要挡的正是它");

        // 机器作用域那一行就算再报一次同一个地址, 名单里也还是一个成员。
        roster.putCarried(MACHINE, PLOT, CHANNEL);
        Snapshot snapshot = SyncGroups.snapshot(roster);
        assertEquals(1, snapshot.carried().get(CHANNEL).size(), "同一支火把只算一个成员");
        assertNull(snapshot.standing().get(CHANNEL), "它不在世界段: 两段各算一次就是那条线");
    }

    @Test
    @DisplayName("组内语义不变: 机器里的那一支与世界里的那一支同一个频道, 仍是 OR")
    void theGroupStillOrs() {
        SyncChannels channels = new SyncChannels();
        channels.put(PLOT, CHANNEL);
        channels.put(WORLD, CHANNEL);
        Roster roster = SyncGroups.roster(channels,
                pos -> pos.equals(PLOT) ? SyncGroups.placed(pos, MACHINE, pos) : Spot.standing(pos));
        assertEquals(2, roster.membersOf(CHANNEL).size(), "两段各一个成员, 同一频道就是同一组");

        Map<Spot, Answer> answer = SyncGroups.decideRoster(roster, Map.of(Spot.standing(WORLD), 15));
        assertEquals(15, answer.get(Spot.carried(MACHINE, PLOT)).power(),
                "世界那一支收到信号, 机器里的这一支跟着亮 - 这是全组 OR, 与它在哪一段无关");
        assertEquals(0, answer.get(Spot.carried(MACHINE, PLOT)).inputs(),
                "而掩码各归各: 它自己一面都没收到");
    }
}

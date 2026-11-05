package com.jiangyuefengyu.redstonecircuit.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collection;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;

/**
 * 频道表的几条形状, 不需要 ServerLevel.
 *
 * <p>组 pass 自己跑不起来 (它要读世界), 但它依据的那张表可以: 一个位置只能属于一个频道、换频道是搬走不是复制、最后
 * 一个人走了频道就不存在。这三条写错的后果都是"一组火把里有一支永远不跟着动", 而那是玩家看得见、也解释不通的故障。
 */
class SyncChannelsTest {

    private static final BlockPos DOOR = new BlockPos(10, 64, 10);
    private static final BlockPos TOWER = new BlockPos(-40, 90, 300);
    private static final BlockPos KITCHEN = new BlockPos(0, 0, 0);

    @Test
    @DisplayName("两个位置绑到同一个频道就是一组")
    void twoPositionsOneChannel() {
        SyncChannels channels = new SyncChannels();
        channels.put(DOOR, 3);
        channels.put(TOWER, 3);

        assertEquals(3, channels.channelOf(DOOR));
        assertEquals(3, channels.channelOf(TOWER));
        assertTrue(channels.membersOf(3).contains(DOOR), "相隔很远的两个位置也可以是同一组");
        assertTrue(channels.membersOf(3).contains(TOWER));
        assertEquals(2, channels.membersOf(3).size());
        assertEquals(2, channels.size());
    }

    @Test
    @DisplayName("一个位置只能属于一个频道: 换频道是搬走, 不是复制")
    void onePositionBelongsToOneChannelOnly() {
        SyncChannels channels = new SyncChannels();
        channels.put(DOOR, 1);
        channels.put(DOOR, 2);

        assertEquals(2, channels.channelOf(DOOR), "新的频道说了算");
        assertFalse(channels.membersOf(1).contains(DOOR), "旧频道里不该还留着一个副本");
        assertTrue(channels.membersOf(1).isEmpty(), "旧频道只剩它一个人, 于是它不存在了");
        assertFalse(channels.channels().contains(1), "空频道不许继续存在");
        assertEquals(1, channels.size(), "搬走不是又加了一个");
    }

    @Test
    @DisplayName("解绑之后频道就没了, 不会留下一个空壳")
    void anEmptiedChannelIsForgotten() {
        SyncChannels channels = new SyncChannels();
        channels.put(DOOR, 5);
        channels.put(KITCHEN, 5);

        channels.remove(DOOR);
        assertTrue(channels.channels().contains(5), "还有一个人在, 频道就在");

        channels.remove(KITCHEN);
        assertFalse(channels.channels().contains(5), "最后一个人走了, 频道也不存在了");
        assertEquals(0, channels.channelOf(KITCHEN), "不在任何频道上的位置答 0");
        assertTrue(channels.isEmpty());
    }

    @Test
    @DisplayName("解绑之后它自成一个组: 不再属于共享频道, 但还在表里")
    void unbindingMakesItAGroupOfOne() {
        SyncChannels channels = new SyncChannels();
        channels.put(DOOR, 4);
        channels.putSolo(DOOR);

        assertEquals(0, channels.channelOf(DOOR), "未绑定不是另一个频道, 它是没有共享频道");
        assertFalse(channels.channels().contains(4), "频道 4 只剩它一个人, 它走了频道就没了");
        assertTrue(channels.soloPositions().contains(DOOR), "它还在表里 - 否则组 pass 就再也找不到这一支");
        assertEquals(1, channels.size());
        assertTrue(channels.snapshot().isEmpty(), "自成一组的成员不上线: 客户端没有线可画");

        // 0 不是频道号, put 它什么都不改: 解绑只有 putSolo 一条路。
        channels.put(DOOR, 0);
        assertTrue(channels.soloPositions().contains(DOOR));
    }

    @Test
    @DisplayName("快照就是客户端要收到的那一份")
    void snapshotCarriesEveryChannel() {
        SyncChannels channels = new SyncChannels();
        channels.put(DOOR, 7);
        channels.put(TOWER, 7);
        channels.put(KITCHEN, 8);

        Map<Integer, Collection<BlockPos>> snapshot = channels.snapshot();
        assertEquals(2, snapshot.size(), "两个频道");
        assertEquals(2, snapshot.get(7).size());
        assertTrue(snapshot.get(8).contains(KITCHEN));
        assertTrue(channels.membersOf(99).isEmpty(), "没听说过的频道答空集, 不是 null");
    }

    @Test
    @DisplayName("remove 一个从没来过的地方什么也不改")
    void removingAnUnknownPositionChangesNothing() {
        SyncChannels channels = new SyncChannels();
        channels.put(DOOR, 2);
        channels.remove(TOWER);

        // size() 是成员数: 只 put 过一个位置, 所以是 1。remove 一个从没来过的地方不改任何事。
        assertEquals(1, channels.size());
        assertTrue(channels.channels().contains(2));
    }
}

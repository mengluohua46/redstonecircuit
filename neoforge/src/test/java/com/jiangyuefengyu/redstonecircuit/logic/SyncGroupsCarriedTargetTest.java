package com.jiangyuefengyu.redstonecircuit.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import com.jiangyuefengyu.redstonecircuit.carrier.CarriedSyncTorches;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierId;
import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.Slot;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;

/**
 * <b>被机器搬着的那一支同步火把, 调频器改的是哪一份存档.</b>
 *
 * <p>两种形态住的地方不一样: 内嵌形态的号码在 {@code Slot#syncGroup} 上 (跟着槽位走), 立在世界里被搬走的那一支没有槽位,
 * 号码在 {@code CarriedSyncTorches} 的机器作用域表里。写错一份不会崩、也不会报错 - 只会让玩家读回一个没变的号码, 或者让
 * 组 pass 收不到它。所以"绑 / 解绑 / 换组"三条按形态各钉一遍, 而这里连一个 {@code ServerLevel} 都不需要:
 * 判据 ({@code carriedTarget}) 与写入 ({@code writeChannel}) 都是纯值。
 */
class SyncGroupsCarriedTargetTest {

    private static final CarrierId MACHINE = CarrierId.subLevel(new UUID(31L, 31L));
    private static final CarrierId OTHER = CarrierId.subLevel(new UUID(32L, 32L));
    private static final BlockPos LOCAL = new BlockPos(4, 2, 1);
    private static final BlockPos ORIGIN = new BlockPos(-20, 70, 8);

    @AfterEach
    void forgetEveryMachine() {
        CarriedSyncTorches.clear();
    }

    private static SyncGroups.CarriedTarget embedded(int channel) {
        Slot slot = new Slot(ComponentType.SYNC_TORCH);
        slot.syncGroup = channel;
        SyncGroups.CarriedTarget target = SyncGroups.carriedTarget(MACHINE, LOCAL, slot, null);
        assertNotNull(target, "一个 SYNC_TORCH 槽位就是一支同步火把");
        assertEquals(SyncGroups.CarriedForm.EMBEDDED, target.form(),
                "内嵌形态: 号码在槽位上, 不在机器作用域那张表里");
        assertSame(slot, target.slot(), "写入改的是索引里那一个活对象, 与组 pass 写答案同一条路");
        assertEquals(channel, target.channel(), "读回来的号码必须是槽位此刻的那一个");
        return target;
    }

    private static SyncGroups.CarriedTarget standing(int channel) {
        CarriedSyncTorches.capture(MACHINE, LOCAL, ORIGIN, channel, (byte) 0, false);
        CarriedSyncTorches.Row row = CarriedSyncTorches.at(MACHINE, LOCAL);
        assertNotNull(row, "机器里那一格查得到这一支");
        SyncGroups.CarriedTarget target = SyncGroups.carriedTarget(MACHINE, LOCAL, null, row.channel());
        assertNotNull(target, "没有槽位、但机器作用域表里有一行, 也是同步火把");
        assertEquals(SyncGroups.CarriedForm.STANDING, target.form(),
                "世界形态被搬走的那一支: 号码在机器作用域那张表的那一行上");
        assertNull(target.slot(), "它没有槽位 - 这正是它的号码要另存一份的原因");
        assertEquals(channel, target.channel());
        return target;
    }

    @Test
    @DisplayName("内嵌形态: 绑定 / 换组 / 解绑都写在 slot.syncGroup 上, 而且不碰机器作用域那张表")
    void theEmbeddedTorchIsWrittenThroughItsSlot() {
        SyncGroups.CarriedTarget target = embedded(SyncTorchRule.UNBOUND);
        assertFalse(target.hasChannel(), "UNBOUND 不是频道号");

        assertTrue(SyncGroups.writeChannel(target, 4), "手里没有频道时的第一次右击 = 新开一个并绑定");
        assertEquals(4, target.slot().syncGroup);
        assertFalse(SyncGroups.writeChannel(target, 4), "同一个号码再写一次什么也没改, 于是调用方不必广播");

        assertTrue(SyncGroups.writeChannel(target, 7), "换组: 从 4 搬到 7");
        assertEquals(7, target.slot().syncGroup);

        assertTrue(SyncGroups.writeChannel(target, SyncTorchRule.UNBOUND), "Shift+右键 = 这一支退回无频道");
        assertEquals(SyncTorchRule.UNBOUND, target.slot().syncGroup);
        assertFalse(CarriedSyncTorches.any(), "内嵌形态一个字节也不写到机器作用域那张表里");
    }

    @Test
    @DisplayName("被搬着的世界形态: 绑定 / 换组 / 解绑写在机器作用域表的那一行上, 槽位那条路一个字不写")
    void theStandingTorchIsWrittenThroughItsRow() {
        SyncGroups.CarriedTarget target = standing(9);
        assertTrue(target.hasChannel(), "捕获时带来的号码就是它此刻的频道");

        assertTrue(SyncGroups.writeChannel(target, 12), "换组: 9 -> 12");
        CarriedSyncTorches.Row row = CarriedSyncTorches.at(MACHINE, LOCAL);
        assertNotNull(row, "那一行还在 - 换组只改号码");
        assertEquals(12, row.channel(), "号码进了那一行");

        assertTrue(SyncGroups.writeChannel(target, SyncTorchRule.UNBOUND), "解绑: 行里的号码回到 0 (= 未绑定)");
        assertEquals(0, CarriedSyncTorches.at(MACHINE, LOCAL).channel(),
                "机器作用域那张表用 0 写未绑定, 与 capture / handBack 同一个写法");
        assertFalse(SyncGroups.writeChannel(target, 0), "0 也是未绑定, 再解一次什么也没改");
        assertFalse(SyncGroups.writeChannel(target, SyncTorchRule.UNBOUND), "同一次解绑不会两次都算改动");
    }

    @Test
    @DisplayName("绑定 / 解绑 / 换组三条对两种形态都成立: 谁的存档被改, 另一份就不动")
    void eachFormHasItsOwnLedger() {
        Slot slot = new Slot(ComponentType.SYNC_TORCH);
        slot.syncGroup = 2;
        CarriedSyncTorches.capture(MACHINE, LOCAL, ORIGIN, 3, (byte) 0, false);
        SyncGroups.CarriedTarget target = SyncGroups.carriedTarget(MACHINE, LOCAL, slot, 3);
        assertNotNull(target);
        assertEquals(SyncGroups.CarriedForm.EMBEDDED, target.form(),
                "两份记录同时成立时, 赢的是真的带着元件的那个: 内嵌形态 (与 roster 的收表顺序同一条)");

        SyncGroups.writeChannel(target, 8);
        assertEquals(8, slot.syncGroup);
        assertEquals(3, CarriedSyncTorches.at(MACHINE, LOCAL).channel(),
                "内嵌形态的写入不该改动机器作用域那张表");

        SyncGroups.CarriedTarget carried = SyncGroups.carriedTarget(MACHINE, LOCAL, null, 3);
        assertNotNull(carried);
        SyncGroups.writeChannel(carried, 6);
        assertEquals(6, CarriedSyncTorches.at(MACHINE, LOCAL).channel());
        assertEquals(8, slot.syncGroup, "世界形态的写入不该改动槽位");
    }

    @Test
    @DisplayName("不是同步火把就不是目标: 别的元件、空槽位、别的机器的那一格")
    void onlyASyncTorchIsATarget() {
        Slot lever = new Slot(ComponentType.LEVER);
        assertNull(SyncGroups.carriedTarget(MACHINE, LOCAL, lever, null),
                "一个塞着拉杆的宿主不是调频器的目标");
        assertNull(SyncGroups.carriedTarget(MACHINE, LOCAL, null, null), "两份记录都没有 = 那一格没有同步火把");
        assertNull(SyncGroups.carriedTarget(null, LOCAL, lever, 4), "没有机器就没有搬运位");

        CarriedSyncTorches.capture(MACHINE, LOCAL, ORIGIN, 4, (byte) 0, false);
        assertNull(SyncGroups.carriedTarget(OTHER, LOCAL, null, null),
                "号码按 (机器, 机器内坐标) 记: 另一台机器的同一格是空的");
        assertNull(CarriedSyncTorches.at(OTHER, LOCAL), "另一台机器的同一格上没有行");
    }
}

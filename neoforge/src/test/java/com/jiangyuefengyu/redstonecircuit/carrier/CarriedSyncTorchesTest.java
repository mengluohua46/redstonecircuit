package com.jiangyuefengyu.redstonecircuit.carrier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;

/**
 * 一台机器带着的同步火把: 捕获、搬运中读到、释放时写回哪一格 - 不需要 ServerLevel。
 *
 * <p>这三件事各错一次的样子都是一样的安静: 捕获漏了就是"机器一动那一支退组"; 搬运中读不到就是"它在机器上不亮"; 落地算
 * 错一格就是"频道回到它被拆下来的地方"。没有一条会崩, 所以它们在这里被逐条钉住。
 */
class CarriedSyncTorchesTest {

    private static final CarrierId MACHINE = CarrierId.subLevel(new UUID(11L, 11L));
    private static final CarrierId OTHER = CarrierId.subLevel(new UUID(22L, 22L));
    private static final BlockPos LOCAL = new BlockPos(3, 1, 0);
    private static final BlockPos ORIGIN = new BlockPos(100, 64, -40);
    private static final int CHANNEL = 9;

    @AfterEach
    void forgetEverything() {
        CarriedSyncTorches.clear();
    }

    @Test
    @DisplayName("捕获: 机器认领一支, 机器内的那一格就能读到它的号码与这一刻的掩码")
    void aClaimedTorchIsReadableFromItsMachine() {
        CarriedSyncTorches.capture(MACHINE, LOCAL, ORIGIN, CHANNEL, (byte) 4, false);

        assertTrue(CarriedSyncTorches.any(), "有一支在机器上, 世界 store 是空的时候组 pass 也得跑");
        CarriedSyncTorches.Row row = CarriedSyncTorches.at(LOCAL);
        assertNotNull(row, "机器里那一格查得到这一支");
        assertSame(MACHINE, row.carrier());
        assertEquals(LOCAL, row.local(), "它按机器内的坐标住, 不是按世界坐标");
        assertEquals(ORIGIN, row.origin(), "离开世界时的那一格留着, 没有位姿可问时落回那里");
        assertEquals(CHANNEL, row.channel(), "号码是捕获进来的那一个 - 频道跟着走");
        assertEquals((byte) 4, row.inputs(), "此刻哪几面有输入也一起带进来, 否则第一趟 pass 会先闪一下");
        assertFalse(row.lit(), "它搬走时是灭的");
    }

    @Test
    @DisplayName("同一支只认领一次: 第二次认领不许把真号码覆盖成未绑定")
    void aTorchIsClaimedOnce() {
        CarriedSyncTorches.capture(MACHINE, LOCAL, ORIGIN, CHANNEL, (byte) 1, true);
        CarriedSyncTorches.capture(MACHINE, LOCAL, ORIGIN.offset(0, 3, 0), 0, (byte) 0, false);

        assertEquals(CHANNEL, CarriedSyncTorches.at(LOCAL).channel(),
                "下一趟扫描再看到这一支时手里已经没有世界那一行了, 那一趟的号码是 0 - 它不许盖掉真号码");
        assertEquals(1, CarriedSyncTorches.rows().size(), "一次认领就是一个成员, 不是两个");
    }

    @Test
    @DisplayName("搬运中: 组 pass 写下的答案与掩码落在表里, 驱动那一趟读到的就是它")
    void theAnswerIsWrittenWhileCarried() {
        CarriedSyncTorches.capture(MACHINE, LOCAL, ORIGIN, CHANNEL, (byte) 0, false);

        CarriedSyncTorches.noteAnswer(MACHINE, LOCAL, (byte) 2, true);

        CarriedSyncTorches.Row row = CarriedSyncTorches.at(LOCAL);
        assertEquals((byte) 2, row.inputs(), "掩码每趟重写: 这一支自己的处境");
        assertTrue(row.lit(), "答案每趟重写: 全组 OR 的结果");
    }

    @Test
    @DisplayName("两台机器各带一支: 只有自己那一格读到自己的号码, 一个也不会漏到隔壁")
    void twoMachinesDoNotShareRows() {
        CarriedSyncTorches.capture(MACHINE, LOCAL, ORIGIN, CHANNEL, (byte) 0, false);
        CarriedSyncTorches.capture(OTHER, LOCAL.offset(0, 0, 5), ORIGIN.offset(9, 0, 0), CHANNEL + 1,
                (byte) 0, false);

        assertEquals(CHANNEL, CarriedSyncTorches.at(LOCAL).channel());
        assertEquals(CHANNEL + 1, CarriedSyncTorches.at(LOCAL.offset(0, 0, 5)).channel());
        assertEquals(2, CarriedSyncTorches.rows().size());
        assertNull(CarriedSyncTorches.at(ORIGIN), "世界坐标上不再是这一支的家");
    }

    @Test
    @DisplayName("落地: 有位姿时落在投影算出的那一格, 没有位姿时落回它离开世界的那一格")
    void theLandingPlanIsTheProjectionOrTheOrigin() {
        CarriedSyncTorches.capture(MACHINE, LOCAL, ORIGIN, CHANNEL, (byte) 8, true);

        Map<BlockPos, CarriedSyncTorches.Row> moved =
                CarriedSyncTorches.landingPlan(MACHINE, local -> local.offset(200, 5, 200));
        assertEquals(1, moved.size());
        CarriedSyncTorches.Row landed = moved.get(LOCAL.offset(200, 5, 200));
        assertNotNull(landed, "机器移动过: 频道跟着它到新位置去, 不许回它被拆下来的地方");
        assertEquals(CHANNEL, landed.channel());
        assertEquals((byte) 8, landed.inputs(), "这一刻的读数也一起落地");
        assertTrue(landed.lit());

        Map<BlockPos, CarriedSyncTorches.Row> home = CarriedSyncTorches.landingPlan(MACHINE, null);
        assertNotNull(home.get(ORIGIN), "问不到位姿时落回原处, 那是唯一总是可用的答案");
    }

    @Test
    @DisplayName("落地之后清掉: 表里没有这一支了, 那一格上也不会剩下一行")
    void theTableIsClearedWhenTheMachineLands() {
        CarriedSyncTorches.capture(MACHINE, LOCAL, ORIGIN, CHANNEL, (byte) 0, false);
        CarriedSyncTorches.capture(OTHER, LOCAL.offset(0, 0, 5), ORIGIN.offset(9, 0, 0), CHANNEL + 1,
                (byte) 0, false);

        CarriedSyncTorches.forgetCarrier(MACHINE);

        assertNull(CarriedSyncTorches.at(LOCAL), "这一台落地之后它的行就走了");
        assertTrue(CarriedSyncTorches.any(), "另一台还在飞, 它的行不许被一起清掉");
        CarriedSyncTorches.forgetCarrier(OTHER);
        assertFalse(CarriedSyncTorches.any(), "两台都落地了, 这张表就该是空的");
        assertEquals(List.of(), CarriedSyncTorches.rows());
    }

    @Test
    @DisplayName("离开世界的那一份只寄放一小会儿: 没人认领就过期, 认领过的就取走")
    void theLiftedRowExpires() {
        // 过期靠世界时钟, 这里只钉"取走"那一半: 取一次拿到, 再取就没有了。
        BlockPos origin = new BlockPos(7, 70, 7);
        assertNull(CarriedSyncTorches.take(null, origin), "没有世界就没有寄存可谈, 答案只能是 null");

        CarriedSyncTorches.capture(MACHINE, LOCAL, ORIGIN, CHANNEL, (byte) 0, false);
        CarriedSyncTorches.forgetCarrier(MACHINE);
        assertFalse(CarriedSyncTorches.any());
    }
}

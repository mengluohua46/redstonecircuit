package com.jiangyuefengyu.redstonecircuit.carrier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>「这一格变了」叫醒的是机器里自己那一格, 而且一次就够。</b>
 *
 * <p>组 pass 每 tick 都要判断这一支火把此刻答什么, 而"答案变了之后谁该重算"是另一件事: 一根贴在它侧面的红石粉
 * 因为听见了它的 15 才亮起来, 也必须在它不再往那一面输出的那一刻听见"该重算了"。世界形态那条有
 * {@code SyncTorchBlock#applyLit} 的 {@code updateNeighborsAt}; 被搬着的那一支的邻居在机器自己那一格里 - 而世界
 * 那一侧的通知 ({@code SyncGroups#pokeDrawn}) 只叫醒"画出来那一格"。缺了这一句, 粉保留上一次的 15, 组 pass 每趟
 * 把它读成输入: 自锁。
 *
 * <p>这里钉的是判据那一半 (纯值, 不需要游戏): 哪一格、叫几次、叫不到时说什么。判据仍然只有
 * {@code CarrierStore#storageInLevel} 那一句 - 子层级的 local 就是 level 坐标, 于是叫得醒; contraption 的 local
 * 相对锚点, 那里在 level 里是别人的一格, 而它自己的 world 没有邻居通知这条路, 于是答"叫不到"。
 */
class CarrierCellNoticeTest {

    private static final CarrierId SUB_LEVEL =
            CarrierId.subLevel(UUID.fromString("00000000-0000-0000-0000-00000000c117"));
    private static final CarrierId CONTRAPTION =
            CarrierId.contraption(UUID.fromString("00000000-0000-0000-0000-00000000c118"));

    /** 机器里那一格, 也是它在 level 里的 plot 坐标 (子层级: 两个数是同一个)。 */
    private static final BlockPos LOCAL = new BlockPos(17, 64, -31);

    @Test
    @DisplayName("子层级: 通知落在机器自己那一格, 而且只落一次")
    void aSubLevelNotifiesItsOwnCellExactlyOnce() {
        List<BlockPos> told = new ArrayList<>();

        assertTrue(CarrierStore.notifyCellChanged(SUB_LEVEL, LOCAL, told::add));

        assertEquals(List.of(LOCAL), told,
                "叫的是机器里那一格 (它的 local 就是 level 坐标), 不是它被画出来的那一格: "
                        + "画出来那一格由世界那一侧管, 而机器里那根粉只听这一格");
    }

    @Test
    @DisplayName("contraption: 那一格不在 level, 于是叫不到 - 绝不拿 local 去打 level 的某个坐标")
    void aContraptionHasNoLevelCellToNotify() {
        List<BlockPos> told = new ArrayList<>();

        assertFalse(CarrierStore.notifyCellChanged(CONTRAPTION, LOCAL, told::add),
                "答 false 才是诚实的: 它自己的 world 是一份只读的方块视图, 没有 updateNeighborsAt 这条路");
        assertTrue(told.isEmpty(), "锚点相对的坐标在 level 里是别人的一格: 往那儿叫就是把别人的电路点着");
        assertNull(CarrierStore.cellNoticeInLevel(CONTRAPTION, LOCAL), "同一句话的另一半");
    }

    @Test
    @DisplayName("没有载体 (世界形态) 时零变化: 一次通知都没有, 也没有异常")
    void noCarrierMeansNoNotice() {
        List<BlockPos> told = new ArrayList<>();

        assertFalse(CarrierStore.notifyCellChanged(null, LOCAL, told::add));
        assertFalse(CarrierStore.notifyCellChanged(SUB_LEVEL, null, told::add));
        assertFalse(CarrierStore.notifyCellChanged(null, null, told::add));
        assertNull(CarrierStore.cellNoticeInLevel(null, LOCAL));

        assertTrue(told.isEmpty(), "世界形态那条不经过这里: 它自己 updateNeighborsAt (见 SyncTorchBlock#applyLit)");
    }

    @Test
    @DisplayName("没有物理后端 / 没有 level 时公开入口直接关着: 答 false, 不抛")
    void thePublicEntryStaysClosedWithoutABackendOrALevel() {
        assertFalse(CarrierStore.notifyCellChanged(null, SUB_LEVEL, LOCAL),
                "载体层没装时这一句只是一次静态布尔读");
        assertFalse(CarrierStore.notifyCellChanged(null, CONTRAPTION, LOCAL));
    }

    @Test
    @DisplayName("没交通知进来时不假装叫过")
    void noNotifierMeansNoNotice() {
        assertFalse(CarrierStore.notifyCellChanged(SUB_LEVEL, LOCAL, null));
    }
}

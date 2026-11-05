package com.jiangyuefengyu.redstonecircuit.carrier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>"一台载体的某一格"那一对入口, 以及两种载体之间唯一的那条判据.</b>
 *
 * <p>组 pass 的搬运分支上一版直接读 {@code level}: 一台 Sable 子层级的格子就在 level 里, 于是它是对的; 一台 Create
 * contraption 的 {@code local} 是<b>相对锚点</b>的坐标, 那里在 level 里是别人的一格, 于是那支被判成"不是火把"。
 * 这里钉的就是"读/写不再问 level, 而是问载体"以及"哪一份存档"这一条判据, 两件事都不需要真方块: 单元测试没有方块注册表
 * ({@code Blocks.AIR.defaultBlockState()} 会先炸), 所以视图答 null - 而"答 null 之后仍然不退回 level"正是要钉的那句话。
 */
class CarriedCellEntryTest {

    private static final CarrierId MACHINE =
            CarrierId.contraption(UUID.fromString("00000000-0000-0000-0000-000000008401"));
    private static final CarrierId SUB_LEVEL =
            CarrierId.subLevel(UUID.fromString("00000000-0000-0000-0000-000000008402"));
    private static final BlockPos LOCAL = new BlockPos(4, 1, -3);
    private static final BlockPos ORIGIN = new BlockPos(120, 64, -40);

    @Nullable
    private CarrierStore.CarrierSource previous;

    @BeforeEach
    void rememberTheInstalledSource() {
        previous = CarrierStore.source();
        CarriedSyncTorches.clear();
    }

    @AfterEach
    void putItBack() {
        CarrierStore.install(previous);
        CarriedSyncTorches.clear();
    }

    /** A carrier's own block view. It records the coordinate it was asked about - a view asked about a world
     * position, or about a projected one, would answer about a different block entirely. */
    private static final class FakeView implements BlockGetter {

        private final List<BlockPos> reads = new ArrayList<>();

        @Nullable
        @Override
        public BlockEntity getBlockEntity(BlockPos pos) {
            return null;
        }

        @Nullable
        @Override
        public BlockState getBlockState(BlockPos pos) {
            this.reads.add(pos);
            return null;
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return Fluids.EMPTY.defaultFluidState();
        }

        @Override
        public int getHeight() {
            return 384;
        }

        @Override
        public int getMinBuildHeight() {
            return -64;
        }
    }

    /** The compat half, as far as these two entries can tell: a block view and the write half. */
    private static final class FakeSource implements CarrierStore.CarrierSource {

        @Nullable
        private final CarrierAccess access;

        private final List<String> blockWrites = new ArrayList<>();

        FakeSource(@Nullable CarrierAccess access) {
            this.access = access;
        }

        @Nullable
        @Override
        public CarrierPose poseOf(ServerLevel level, CarrierId carrier) {
            return null;
        }

        @Nullable
        @Override
        public CarrierId carrierAt(ServerLevel level, BlockPos worldPos) {
            return null;
        }

        @Nullable
        @Override
        public CarrierAccess access() {
            return this.access;
        }

        @Override
        public boolean setCarriedBlock(CarrierId carrier, BlockPos local, BlockState state) {
            this.blockWrites.add(carrier.shortId() + "@" + local.toShortString());
            return true;
        }

        @Override
        public String describe() {
            return "unit-test source";
        }
    }

    // ------------------------------------------------------------------ cases --

    @Test
    @DisplayName("contaption 那一格: 读的是载体自己的视图, 而且问的就是那个 local 坐标")
    void theContraptionReadAsksTheCarriersOwnView() {
        FakeView view = new FakeView();
        CarrierStore.install(new FakeSource(carrier -> view));

        CarrierStore.CarriedCell cell = CarrierStore.carriedCellAt(null, MACHINE, LOCAL);

        assertNotNull(cell, "读不到方块也仍然回答'这一格': 这一句问的是载体, 不是 level");
        assertNull(cell.state(), "视图答 null (这里没有方块注册表) 就照实说读不到");
        assertEquals(List.of(LOCAL), view.reads,
                "问的是机器里的那个坐标本身 - 不是任何投影出来的世界坐标");
    }

    @Test
    @DisplayName("载体读不出来时答'读不到', 绝不退回去读 level 的方块网格")
    void noViewMeansNothingRatherThanTheLevel() {
        CarrierStore.install(new FakeSource(carrier -> null));

        assertNull(CarrierStore.carriedCellAt(null, MACHINE, LOCAL),
                "没有视图就是没有这一格: 拿 local 去问 level 只会问到别人的方块");
    }

    @Test
    @DisplayName("contaption 的掩码在机器作用域那一行上 (那一格在 level 里没有位置)")
    void theContraptionReadingComesFromTheMachineScopedRow() {
        CarrierStore.install(new FakeSource(carrier -> new FakeView()));
        CarriedSyncTorches.capture(MACHINE, LOCAL, ORIGIN, 7, (byte) 6, true);

        CarrierStore.CarriedCell cell = CarrierStore.carriedCellAt(null, MACHINE, LOCAL);

        assertNotNull(cell);
        assertEquals((byte) 6, cell.inputs(),
                "掩码读的是 (机器, 机器内坐标) 那一行: 世界 store 按世界坐标存, 而这里没有那个世界坐标");
    }

    @Test
    @DisplayName("子层级那一格仍然读 level: 没有 level 就没有这一格, 与上一版同一条路")
    void theSubLevelStillReadsTheLevel() {
        FakeView view = new FakeView();
        CarrierStore.install(new FakeSource(carrier -> view));
        CarriedSyncTorches.capture(SUB_LEVEL, LOCAL, ORIGIN, 7, (byte) 6, true);

        CarrierStore.CarriedCell cell = CarrierStore.carriedCellAt(null, SUB_LEVEL, LOCAL);

        assertNull(cell, "子层级的存储就是 level, 于是没有 level 可读时它答'读不到'");
        assertTrue(view.reads.isEmpty(), "而且一次都不问载体视图 - 那是 contraption 那一支的路");
    }

    @Test
    @DisplayName("只写读数的那一次: 方块那一半一次也没被调用")
    void aReadingOnlyWriteLeavesTheBlockAlone() {
        FakeSource source = new FakeSource(carrier -> new FakeView());
        CarrierStore.install(source);

        assertFalse(CarrierStore.setCarriedCell(null, MACHINE, LOCAL,
                        new CarrierStore.CarriedCell(null, (byte) 3)),
                "没有方块状态就没有'真的改了', 调用方因此不必叫醒世界");
        assertTrue(source.blockWrites.isEmpty(), "方块那一半是载体层的入口, 它为 null 时不该被调用");
    }

    @Test
    @DisplayName("没有物理后端时一个字节都不动")
    void withoutABackendNothingIsAsked() {
        CarrierStore.install(null);

        assertNull(CarrierStore.carriedCellAt(null, MACHINE, LOCAL));
        assertFalse(CarrierStore.setCarriedCell(null, MACHINE, LOCAL,
                new CarrierStore.CarriedCell(null, (byte) 1)));
    }
}

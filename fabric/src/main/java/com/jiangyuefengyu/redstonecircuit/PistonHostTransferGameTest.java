package com.jiangyuefengyu.redstonecircuit;

import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneStore;
import com.jiangyuefengyu.redstonecircuit.data.Slot;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonHeadBlock;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.PistonType;

/**
 * <b>方块内那一个跟着方块走 - 被活塞推走的方块把里面的元件一起带走.</b>
 *
 * <p>钉的是机制而不是规则: 元件住在 {@code InnerRedstoneStore} 里, 而那张表按坐标开键, 所以"方块走了、元件留下"是那张表的必然结果。
 * 这里让一台真活塞推一次真石头, 然后直接问表: 旧格空了没有、新格有没有同一个元件、强度还是不是原来那个数。
 *
 * <p>第二例推的是**一排两块**: 两块各自带一个不同强度的元件, 落在相邻两格 - 近的那块的落点正是远的那块的起点, 所以
 * 这一例是"先把所有起点清空、再逐个写入"的唯一真实证据。
 *
 * <p>第三例是**粘性活塞拉回**: 事件带的是活塞的朝向, 而拉回时方块走的是反方向 - 弄错这一格, 表现就正好是"方块被拉回去了,
 * 元件留在原地"。
 */
public final class PistonHostTransferGameTest implements FabricGameTest {

    private static BlockPos at(GameTestHelper helper, int x, int y, int z) {
        return helper.absolutePos(new BlockPos(x, y, z));
    }

    /** Puts one inner dust of a chosen strength at an absolute position, the way {@code /rc place} injects one. */
    private static void placeDust(GameTestHelper helper, BlockPos pos, int power) {
        ServerLevel level = helper.getLevel();
        InnerRedstoneStore store = InnerRedstoneStore.get(level);
        Slot slot = new Slot(ComponentType.DUST);
        slot.power = power;
        slot.injectedPower = power;
        store.getOrCreate(pos).setSlot(slot);
        store.markDirty();
    }

    /** A piston that is not powered yet: powered later, so the push happens inside the test's own timing. */
    private static void placePiston(GameTestHelper helper, int x, int y, int z, Direction facing) {
        placePiston(helper, x, y, z, facing, false);
    }

    private static void placePiston(GameTestHelper helper, int x, int y, int z, Direction facing, boolean sticky) {
        helper.setBlock(new BlockPos(x, y, z),
                (sticky ? Blocks.STICKY_PISTON : Blocks.PISTON).defaultBlockState()
                        .setValue(BlockStateProperties.FACING, facing));
    }

    @GameTest(template = "redstonecircuit:empty")
    public void aPushedBlockTakesItsComponentWithIt(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos host = at(helper, 2, 1, 1);
        BlockPos landed = at(helper, 3, 1, 1);

        placePiston(helper, 1, 1, 1, Direction.EAST);
        helper.setBlock(new BlockPos(2, 1, 1), Blocks.STONE.defaultBlockState());
        placeDust(helper, host, 7);

        helper.startSequence()
                // The power comes after both the piston and the stone exist, so the push is the test's own doing.
                .thenExecuteAfter(2, () -> helper.setBlock(new BlockPos(0, 1, 1),
                        Blocks.REDSTONE_BLOCK.defaultBlockState()))
                // Two ticks of piston travel and two more before the moved block lands as itself.
                .thenExecuteAfter(8, () -> {
                    InnerRedstoneStore store = InnerRedstoneStore.get(level);
                    helper.assertTrue(store.slotAt(host) == null,
                            "元件必须跟着方块离开旧格: " + host.toShortString() + " 还留着一个");
                    Slot moved = store.slotAt(landed);
                    helper.assertTrue(moved != null,
                            "方块落到 " + landed.toShortString() + " 之后, 元件要在那里, 实际是空的");
                    helper.assertTrue(moved.injectedPower == 7,
                            "强度要原样带过去, 实际 " + moved.injectedPower);
                })
                .thenSucceed();
    }

    @GameTest(template = "redstonecircuit:empty")
    public void aRowOfPushedBlocksKeepsEveryComponentItsOwn(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        // Three blocks, not two: the reported bug needed enough of them for the re-entrant resolutions to shift every
        // component past the blocks. Pushed WEST so that all four cells fit inside the five-wide template, and powered
        // from a cell beside the piston rather than behind it (behind is the template wall).
        placePiston(helper, 4, 1, 1, Direction.WEST);
        helper.setBlock(new BlockPos(3, 1, 1), Blocks.STONE.defaultBlockState());
        helper.setBlock(new BlockPos(2, 1, 1), Blocks.STONE.defaultBlockState());
        helper.setBlock(new BlockPos(1, 1, 1), Blocks.STONE.defaultBlockState());
        placeDust(helper, at(helper, 3, 1, 1), 7);
        placeDust(helper, at(helper, 2, 1, 1), 3);
        placeDust(helper, at(helper, 1, 1, 1), 5);

        helper.startSequence()
                .thenExecuteAfter(2, () -> helper.setBlock(new BlockPos(4, 1, 0),
                        Blocks.REDSTONE_BLOCK.defaultBlockState()))
                .thenExecuteAfter(8, () -> {
                    InnerRedstoneStore store = InnerRedstoneStore.get(level);
                    // The dusts end up neighbours, so their ANSWERS solve against each other; what each block carried
                    // is the injected strength, which the solve leaves alone.
                    int[] expected = {7, 3, 5};
                    for (int i = 0; i < expected.length; i++) {
                        BlockPos landed = at(helper, 3 - i - 1, 1, 1);
                        helper.assertTrue(level.getBlockState(landed).is(Blocks.STONE),
                                "先说清楚: 这一推本身要成立, " + landed.toShortString() + " 上现在是 "
                                        + level.getBlockState(landed).getBlock());
                        Slot here = store.slotAt(landed);
                        helper.assertTrue(here != null,
                                "第 " + (i + 1) + " 块方块的元件要在 " + landed.toShortString() + " 上, 实际是空的"
                                        + " - 这正是「推四个只有最外面两个还在」那个 bug 的样子");
                        helper.assertTrue(here.injectedPower == expected[i],
                                "第 " + (i + 1) + " 块带的是 " + expected[i] + ", 实际 " + here.injectedPower
                                        + " - 说明有东西把它又往前搬了一格");
                    }
                    helper.assertTrue(store.slotAt(at(helper, 3, 1, 1)) == null,
                            "排在最里面那格的元件必须跟着它自己的方块走掉");
                })
                .thenSucceed();
    }

    @GameTest(template = "redstonecircuit:empty")
    public void aPulledBlockTakesItsComponentWithIt(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos start = at(helper, 2, 1, 1);
        BlockPos pushed = at(helper, 3, 1, 1);

        // A real sticky cycle rather than a hand-built extended state: extend with a power source, then take the power
        // away and let the piston pull the block back. Both halves are vanilla's, so a failure here is about our
        // transfer and not about a state this test made up.
        placePiston(helper, 1, 1, 1, Direction.EAST, true);
        helper.setBlock(new BlockPos(2, 1, 1), Blocks.STONE.defaultBlockState());
        placeDust(helper, start, 7);

        helper.startSequence()
                .thenExecuteAfter(2, () -> helper.setBlock(new BlockPos(0, 1, 1),
                        Blocks.REDSTONE_BLOCK.defaultBlockState()))
                // Pushed out: the component has to be at the far cell by now, or the pull below proves nothing.
                .thenExecuteAfter(8, () -> {
                    Slot out = InnerRedstoneStore.get(level).slotAt(pushed);
                    helper.assertTrue(out != null && out.injectedPower == 7,
                            "推出去这一步要先成立, 否则拉回这一条没有意义");
                })
                .thenExecuteAfter(2, () -> helper.setBlock(new BlockPos(0, 1, 1), Blocks.AIR.defaultBlockState()))
                .thenExecuteAfter(8, () -> {
                    InnerRedstoneStore store = InnerRedstoneStore.get(level);
                    helper.assertTrue(store.slotAt(pushed) == null,
                            "拉回时元件也要跟着走: " + pushed.toShortString() + " 还留着一个");
                    Slot back = store.slotAt(start);
                    helper.assertTrue(back != null,
                            "方块被拉回 " + start.toShortString() + " 之后, 元件要在那里, 实际是空的");
                    helper.assertTrue(back.injectedPower == 7,
                            "强度要原样带回来, 实际 " + back.injectedPower);
                })
                .thenSucceed();
    }
}

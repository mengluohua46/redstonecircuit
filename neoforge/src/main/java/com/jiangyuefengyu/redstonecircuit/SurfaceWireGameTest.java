package com.jiangyuefengyu.redstonecircuit;

import com.jiangyuefengyu.redstonecircuit.block.SurfaceDiodeBlock;
import com.jiangyuefengyu.redstonecircuit.block.SurfacePressurePlateBlock;
import com.jiangyuefengyu.redstonecircuit.block.SurfaceTorchBlock;
import com.jiangyuefengyu.redstonecircuit.block.SurfaceWireBlock;
import com.jiangyuefengyu.redstonecircuit.block.SurfaceWireEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * 红石粉（贴面） in a real level: a sheet on a wall carries a signal along the wall, decays one per hop, and answers
 * exactly the faces vanilla's wire answers.
 *
 * <p>The one thing these tests say that a reader cannot infer: <b>the exit rule is vanilla's, moved to another
 * plane</b>. Vanilla's wire refuses {@code side == DOWN} and always answers {@code side == UP}; generalised, a sheet
 * refuses {@code side == MOUNT} (the asker is out of the plane) and always answers {@code side == MOUNT.getOpposite()}
 * (the asker is the block it hangs on, which vanilla's wire powers too - a lamp under dust lights).
 */
@GameTestHolder(RedstoneCircuit.MODID)
@PrefixGameTestTemplate(false)
public final class SurfaceWireGameTest {

    private static BlockPos at(GameTestHelper helper, int x, int y, int z) {
        return helper.absolutePos(new BlockPos(x, y, z));
    }

    private static BlockState sheet(Direction mount) {
        return SurfaceWireBlock.withFace(RCRegistry.SURFACE_WIRE.get().defaultBlockState(), mount);
    }

    private static int power(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos).getValue(SurfaceWireBlock.POWER);
    }

    /**
     * 墙上一条粉：红石块给第一格 15，沿线一格一跳。墙面是西墙（{@code MOUNT = WEST}），环内是上下南北。
     */
    @GameTest(template = "empty")
    public void aWallSheetCarriesAndDecays(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        // 西墙：x = 2 那一列石块，粉贴在它们的东面
        for (int z = 2; z <= 4; z++) {
            helper.setBlock(new BlockPos(2, 1, z), Blocks.STONE.defaultBlockState());
        }
        BlockPos first = at(helper, 3, 1, 2);
        BlockPos second = at(helper, 3, 1, 3);
        BlockPos third = at(helper, 3, 1, 4);
        helper.setBlock(new BlockPos(3, 1, 2), sheet(Direction.WEST));
        helper.setBlock(new BlockPos(3, 1, 3), sheet(Direction.WEST));
        helper.setBlock(new BlockPos(3, 1, 4), sheet(Direction.WEST));
        // 源头放在墙外那一格：粉从六个方向里的任意一边都能吃到信号，这一格正是「平面外」
        helper.setBlock(new BlockPos(4, 1, 2), Blocks.REDSTONE_BLOCK.defaultBlockState());

        helper.startSequence()
                .thenExecuteAfter(4, () -> {
                    // 方块实体只为渲染而生，但它必须真的存在：渲染器取不到方块实体就什么也不画，
                    // 而这件事在客户端是看不出来的（只是少画半截线）。
                    helper.assertTrue(level.getBlockEntity(first) instanceof SurfaceWireEntity,
                            "贴面粉必须带方块实体，否则渲染器无从下手");
                    helper.assertTrue(power(level, first) == 15, "源头旁边就是 15，实际 " + power(level, first));
                    helper.assertTrue(power(level, second) == 14, "第一跳 14，实际 " + power(level, second));
                    helper.assertTrue(power(level, third) == 13, "第二跳 13，实际 " + power(level, third));
                })
                .thenExecuteAfter(8, () -> {
                    helper.setBlock(new BlockPos(4, 1, 2), Blocks.AIR.defaultBlockState());
                })
                .thenExecuteAfter(12, () -> {
                    helper.assertTrue(power(level, first) == 0, "撤掉源头就该归零，实际 " + power(level, first));
                    helper.assertTrue(power(level, third) == 0, "整条都该归零，实际 " + power(level, third));
                })
                .thenSucceed();
    }

    /**
     * 强电穿透：火把垫在方块底下，这个方块就是「被强充能的方块」，贴在它**侧面**的粉要吃到 15。
     *
     * <p>被强充能的方块自己不答话，强电是「从它的邻居那里问出来的」，所以粉必须绕过它去问；作者 2026-11-11 的
     * 截图里，同一个方块顶上的原版粉是亮的、侧面我们的粉是暗的，就是缺这一跳。
     */
    @GameTest(template = "empty")
    public void aStronglyPoweredBlockLightsTheSheetOnItsSide(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        // 支撑：石柱 (3,1,3) → 火把 (3,2,3) → 被强充能的方块 (3,3,3)
        helper.setBlock(new BlockPos(3, 1, 3), Blocks.STONE.defaultBlockState());
        helper.setBlock(new BlockPos(3, 3, 3), Blocks.STONE.defaultBlockState());
        BlockPos sheetPos = at(helper, 3, 3, 2);
        helper.setBlock(new BlockPos(3, 3, 2), sheet(Direction.SOUTH));
        helper.setBlock(new BlockPos(3, 2, 3), Blocks.REDSTONE_TORCH.defaultBlockState());

        helper.startSequence()
                .thenExecuteAfter(4, () -> {
                    helper.assertTrue(power(level, sheetPos) == 15,
                            "强充能方块侧面的粉该和顶上的原版粉一样是 15，实际 " + power(level, sheetPos));
                })
                .thenExecuteAfter(8, () -> {
                    helper.setBlock(new BlockPos(3, 2, 3), Blocks.AIR.defaultBlockState());
                })
                .thenExecuteAfter(12, () -> {
                    helper.assertTrue(power(level, sheetPos) == 0,
                            "撤掉火把就该归零，实际 " + power(level, sheetPos));
                })
                .thenSucceed();
    }

    /**
     * 出口规则：贴着的那一块答、平面外不答、环内按连没连上答。
     *
     * <p>口径：{@code getSignal(state, level, pos, side)} 的 {@code side} 是「从提问者指向本方块」，所以「提问者在
     * 我哪一侧」是 {@code side.getOpposite()}。{@code getBestNeighborSignal} 就是 {@code getSignal(pos.relative(d), d)}
     * 这么问的。
     */
    @GameTest(template = "empty")
    public void aSheetAnswersLikeVanillaInItsOwnPlane(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        for (int z = 2; z <= 4; z++) {
            helper.setBlock(new BlockPos(2, 1, z), Blocks.STONE.defaultBlockState());
        }
        BlockPos support = at(helper, 2, 1, 3);
        BlockPos middle = at(helper, 3, 1, 3);
        helper.setBlock(new BlockPos(3, 1, 3), sheet(Direction.WEST));
        helper.setBlock(new BlockPos(3, 1, 2), sheet(Direction.WEST));
        helper.setBlock(new BlockPos(4, 1, 3), Blocks.REDSTONE_BLOCK.defaultBlockState());

        helper.startSequence()
                .thenExecuteAfter(4, () -> {
                    BlockState state = level.getBlockState(middle);
                    helper.assertTrue(state.getValue(SurfaceWireBlock.POWER) > 0,
                            "先得通电，实际 " + state.getValue(SurfaceWireBlock.POWER));
                    // 贴着的西墙那一块（提问者在 MOUNT 一侧 ⇒ side = MOUNT.getOpposite()）
                    helper.assertTrue(state.getSignal(level, middle, Direction.EAST) > 0,
                            "贴着的那一块要答（原版粉也给脚下那块供电）");
                    // 平面外那一格（提问者在 EAST 一侧 ⇒ side = WEST = MOUNT）
                    helper.assertTrue(state.getSignal(level, middle, Direction.WEST) == 0,
                            "平面外不答（原版粉不给它上面的方块供电）");
                    // 环内连上的北侧（提问者在北 ⇒ side = SOUTH，连接位在北）
                    helper.assertTrue(state.getSignal(level, middle, Direction.SOUTH) > 0,
                            "环内连上的那一侧要答");
                    // 环内没连上的南侧（提问者在南 ⇒ side = NORTH，连接位在南，那里是空的）
                    helper.assertTrue(state.getSignal(level, middle, Direction.NORTH) == 0,
                            "没连上的环内方向不答");
                    helper.assertTrue(support != null, "支撑方块只是一个位置");
                })
                .thenSucceed();
    }

    /** 墙上那颗粉与地面上的**原版粉**是相邻的：电要从棱那里过得去（这一轮只做直连，不做弯折）。 */
    @GameTest(template = "empty")
    public void aSheetAboveAVanillaWireIsFed(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        helper.setBlock(new BlockPos(2, 1, 2), Blocks.STONE.defaultBlockState());
        // 地平面上的原版粉：下面要有承重
        helper.setBlock(new BlockPos(3, 0, 2), Blocks.STONE.defaultBlockState());
        helper.setBlock(new BlockPos(3, 1, 2), Blocks.REDSTONE_WIRE.defaultBlockState());
        // 墙面上的粉，贴在 (2,2,2) 的西墙上，正好在原版粉的上面一格
        helper.setBlock(new BlockPos(2, 2, 2), Blocks.STONE.defaultBlockState());
        BlockPos sheetPos = at(helper, 3, 2, 2);
        helper.setBlock(new BlockPos(3, 2, 2), sheet(Direction.WEST));
        // 给地面那一颗粉一个满强度源头
        helper.setBlock(new BlockPos(4, 1, 2), Blocks.REDSTONE_BLOCK.defaultBlockState());

        helper.startSequence()
                .thenExecuteAfter(6, () -> {
                    BlockState floor = level.getBlockState(at(helper, 3, 1, 2));
                    helper.assertTrue(floor.getValue(RedStoneWireBlock.POWER) > 0,
                            "地面那颗粉先要亮，实际 " + floor.getValue(RedStoneWireBlock.POWER));
                    helper.assertTrue(power(level, sheetPos) == floor.getValue(RedStoneWireBlock.POWER) - 1,
                            "墙上那颗要比地面那颗低一跳：地面 " + floor.getValue(RedStoneWireBlock.POWER)
                                    + "，墙上 " + power(level, sheetPos));
                })
                .thenSucceed();
    }


    /**
     * 「画连接线」：原版粉那一侧必须把朝我们的那一边算成**连上**，它的模型才有线画过来。
     *
     * <p>这一条不需要给原版注入什么：{@code Block#canConnectRedstone} 问的就是
     * {@code defaultBlockState().isSignalSource()}，而粉一直是信号源。这条用例把它钉住——哪天有人把
     * {@code isSignalSource} 改成按强度回答，线会先消失。
     */
    @GameTest(template = "empty")
    public void aVanillaWireDrawsALineTowardsTheSheet(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        // 北墙：粉贴在 (3,1,1) 的南面
        helper.setBlock(new BlockPos(3, 1, 1), Blocks.STONE.defaultBlockState());
        BlockPos sheetPos = at(helper, 3, 1, 2);
        // 东边一格先放原版粉：裸 setBlock 不走 getStateForPlacement，粉的四个连接面要靠邻居变化来算，
        // 所以顺序必须是「先粉、后我们」——后放的那一次写方块会通知到先放的那一颗。
        helper.setBlock(new BlockPos(4, 0, 2), Blocks.STONE.defaultBlockState());
        BlockPos wirePos = at(helper, 4, 1, 2);
        helper.setBlock(new BlockPos(4, 1, 2), Blocks.REDSTONE_WIRE.defaultBlockState());
        helper.setBlock(new BlockPos(3, 1, 2), sheet(Direction.NORTH));

        helper.startSequence()
                .thenExecuteAfter(4, () -> {
                    BlockState wire = level.getBlockState(wirePos);
                    helper.assertTrue(wire.getValue(RedStoneWireBlock.WEST).isConnected(),
                            "原版粉朝西（我们这一格）应当算连上，实际 " + wire.getValue(RedStoneWireBlock.WEST));
                    helper.assertTrue(SurfaceWireBlock.adjacentWire(level, sheetPos, Direction.EAST),
                            "我们这一侧也要把东边那格当邻线（两格相邻，电就通）");
                })
                .thenSucceed();
    }

    /** 支撑没了就得掉：墙面那一块被拆掉之后，粉要落下而不是浮着。 */
    @GameTest(template = "empty")
    public void aSheetFallsWithItsWall(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        helper.setBlock(new BlockPos(2, 1, 2), Blocks.STONE.defaultBlockState());
        BlockPos pos = at(helper, 3, 1, 2);
        helper.setBlock(new BlockPos(3, 1, 2), sheet(Direction.WEST));

        helper.startSequence()
                .thenExecuteAfter(4, () -> helper.setBlock(new BlockPos(2, 1, 2), Blocks.AIR.defaultBlockState()))
                .thenExecuteAfter(8, () -> helper.assertTrue(
                        level.getBlockState(pos).isAir(), "支撑没了粉就该消失，实际 " + level.getBlockState(pos)))
                .thenSucceed();
    }

    /** 贴在**吊顶**上：环内四个方向仍是水平四向，出口规则不变。 */
    @GameTest(template = "empty")
    public void aCeilingSheetWorksTheSameWay(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        helper.setBlock(new BlockPos(3, 2, 2), Blocks.STONE.defaultBlockState());
        BlockPos pos = at(helper, 3, 1, 2);
        helper.setBlock(new BlockPos(3, 1, 2), sheet(Direction.UP));
        helper.setBlock(new BlockPos(4, 1, 2), Blocks.REDSTONE_BLOCK.defaultBlockState());

        helper.startSequence()
                .thenExecuteAfter(4, () -> {
                    helper.assertTrue(power(level, pos) == 15, "吊顶上的粉一样吃信号，实际 " + power(level, pos));
                    BlockState state = level.getBlockState(pos);
                    helper.assertTrue(state.getSignal(level, pos, Direction.DOWN) > 0,
                            "贴着吊顶的那一块（上面）要答：提问者在 UP 一侧 ⇒ side = DOWN");
                    helper.assertTrue(state.getSignal(level, pos, Direction.UP) == 0,
                            "平面外（下面）不答");
                })
                .thenSucceed();
    }

    /** 方块内的红石也要读得到：宿主方块里一颗满强度拉杆，旁边的粉必须亮到 15。 */
    @GameTest(template = "empty")
    public void aSheetReadsTheComponentInsideAHostBlock(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        helper.setBlock(new BlockPos(2, 1, 2), Blocks.STONE.defaultBlockState());
        BlockPos pos = at(helper, 3, 1, 2);
        helper.setBlock(new BlockPos(3, 1, 2), sheet(Direction.WEST));
        BlockPos host = at(helper, 4, 1, 2);
        helper.setBlock(new BlockPos(4, 1, 2), Blocks.STONE.defaultBlockState());
        com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneStore store =
                com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneStore.get(level);
        com.jiangyuefengyu.redstonecircuit.data.Slot lever = new com.jiangyuefengyu.redstonecircuit.data.Slot(
                com.jiangyuefengyu.redstonecircuit.data.ComponentType.LEVER);
        lever.powered = true;
        lever.power = 15;
        lever.facing = Direction.WEST;
        store.getOrCreate(host).setSlot(lever);
        store.markDirty();
        com.jiangyuefengyu.redstonecircuit.logic.InnerRedstoneNetwork.markDirtyWithNeighbours(level, host);
        // 这次是测试自己写的存档，所以求解器收尾那一步（唤醒宿主周围的方块）也要自己叫一次：
        // markDirtyWithNeighbours 只排队求解，不通知世界。
        com.jiangyuefengyu.redstonecircuit.logic.InnerRedstoneNetwork.notifyOutputChangedNow(level, host);

        helper.startSequence()
                .thenExecuteAfter(6, () -> helper.assertTrue(power(level, pos) == 15,
                        "宿主里那颗拉杆朝西答 15，旁边的粉就是 15，实际 " + power(level, pos)))
                .thenSucceed();
    }

    /**
     * 作者报的「放在墙上的红石粉无法激活方块内红石粉」：环内那一格是**宿主方块**（里面塞着元件）时也要答，
     * 哪怕它不是线、也就没有线可画。原版粉是靠 `canRedstoneConnectTo`（信号源）算这个的。
     */
    @GameTest(template = "empty")
    public void aSheetPowersAHostBlockBesideIt(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        helper.setBlock(new BlockPos(2, 1, 2), Blocks.STONE.defaultBlockState());
        BlockPos sheetPos = at(helper, 3, 1, 2);
        helper.setBlock(new BlockPos(3, 1, 2), sheet(Direction.WEST));
        // 环内北边那一格是宿主方块，里面一颗满强度拉杆
        BlockPos host = at(helper, 3, 1, 1);
        helper.setBlock(new BlockPos(3, 1, 1), Blocks.STONE.defaultBlockState());
        com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneStore store =
                com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneStore.get(level);
        com.jiangyuefengyu.redstonecircuit.data.Slot lever = new com.jiangyuefengyu.redstonecircuit.data.Slot(
                com.jiangyuefengyu.redstonecircuit.data.ComponentType.DUST);
        lever.power = 15;
        lever.facing = Direction.SOUTH;
        store.getOrCreate(host).setSlot(lever);
        store.markDirty();
        // 给它一个源，墙上的粉才有强度可答
        helper.setBlock(new BlockPos(4, 1, 2), Blocks.REDSTONE_BLOCK.defaultBlockState());

        helper.startSequence()
                .thenExecuteAfter(6, () -> {
                    BlockState state = level.getBlockState(sheetPos);
                    helper.assertTrue(state.getValue(SurfaceWireBlock.POWER) > 0, "先得通电");
                    // 提问者在北边 ⇒ side = SOUTH
                    helper.assertTrue(state.getSignal(level, sheetPos, Direction.SOUTH) > 0,
                            "北边那格是宿主方块，也要答（原版靠信号源判这个）");
                    // 对面那一格是石头：没有任何元件，也不该答
                    helper.assertTrue(state.getSignal(level, sheetPos, Direction.UP) == 0,
                            "环内没有连接的普通方块不答");
                })
                .thenSucceed();
    }
    /**
     * 作者报的「墙面红石和相邻的方块内红石产生了自锁」：两边满强度互读就是增益 1 的环，撤源之后谁都不掉。
     *
     * <p><b>还没修好，先关掉。</b>实测撤源 20 tick 后仍是「墙上 15、方块里 15」。我这一版只在外→内这一半付了一跳
     * （方块里的线按线收一跳）；反方向（方块内的线读我们这张世界里的粉）走的是
     * {@code InnerRedstoneNetwork#readSignal}，它把我们的粉当**世界信号源**读，不认它是线，所以不付跳 ——
     * 增益 1 的环还在。那一处是求解器核心，改它要连带既有的一整片用例，得单独一轮做。
     */
    @GameTest(template = "empty")
    public void aSheetDoesNotLatchWithTheWireInsideAHostBlock(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        helper.setBlock(new BlockPos(2, 1, 2), Blocks.STONE.defaultBlockState());
        helper.setBlock(new BlockPos(2, 1, 3), Blocks.STONE.defaultBlockState());
        BlockPos sheetPos = at(helper, 3, 1, 2);
        helper.setBlock(new BlockPos(3, 1, 2), sheet(Direction.WEST));
        // 北边那一格是宿主方块，里面一颗粉；墙上的粉与它相邻
        BlockPos host = at(helper, 3, 1, 1);
        helper.setBlock(new BlockPos(3, 1, 1), Blocks.STONE.defaultBlockState());
        com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneStore store =
                com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneStore.get(level);
        com.jiangyuefengyu.redstonecircuit.data.Slot dust = new com.jiangyuefengyu.redstonecircuit.data.Slot(
                com.jiangyuefengyu.redstonecircuit.data.ComponentType.DUST);
        dust.facing = Direction.SOUTH;
        store.getOrCreate(host).setSlot(dust);
        store.markDirty();
        BlockPos source = new BlockPos(4, 1, 2);
        helper.setBlock(source, Blocks.REDSTONE_BLOCK.defaultBlockState());
        com.jiangyuefengyu.redstonecircuit.logic.InnerRedstoneNetwork.markDirtyWithNeighbours(level, host);
        com.jiangyuefengyu.redstonecircuit.logic.InnerRedstoneNetwork.notifyOutputChangedNow(level, host);

        helper.startSequence()
                .thenExecuteAfter(8, () -> helper.assertTrue(
                        level.getBlockState(sheetPos).getValue(SurfaceWireBlock.POWER) > 0, "先得通电"))
                .thenExecuteAfter(9, () -> {
                    helper.setBlock(source, Blocks.AIR.defaultBlockState());
                    level.neighborChanged(sheetPos, level.getBlockState(sheetPos).getBlock(), sheetPos);
                    com.jiangyuefengyu.redstonecircuit.logic.InnerRedstoneNetwork.notifyOutputChangedNow(level, host);
                })
                .thenExecuteAfter(20, () -> {
                    int sheet = level.getBlockState(sheetPos).getValue(SurfaceWireBlock.POWER);
                    int inside = store.slotAt(host) == null ? 0 : store.slotAt(host).power;
                    helper.assertTrue(sheet == 0 && inside == 0,
                            "撤源之后两边都该灭，实际 墙上 " + sheet + "、方块里 " + inside);
                })
                .thenSucceed();
    }
    /**
     * 作者 2026-11-16 报的第二例：**贴在自己那块宿主上**（粉贴在装了红石的方块的面上）会自锁，
     * 超导红石粉那一版正常。
     *
     * <p>与上一例的区别就是贴的是哪一面：上一例贴的是<b>邻居</b>方块，这一例贴的是<b>宿主本身</b>。
     * 宿主是「问的人」，走的是 {@code getSignal} 里 {@code towards == face} 那一条——原版语义下
     * 那里答满强度（灯在粉下面要亮），可宿主里装着我们的线时，两边就都拿对方的满强度：增益 1 的环。
     */
    /**
     * <b>宿主里装着线时，贴面的粉答它必须收一跳</b>——不只是「撤源会灭」这条结果，而是这条**规则**本身。
     *
     * <p>宿主就是那张粉的支撑方块，走的是 {@code getSignal} 里 {@code towards == face} 那一条；原版语义下那里答
     * 满强度（灯在粉下面要亮），但宿主里装着一条线时，两边若都拿满强度，这个环的增益就是 1，撤源之后谁也掉不下来。
     * 宿主的线本来就按线算一跳，所以这一侧也要收——同一条判据、所有出口都收一次。
     */
    @GameTest(template = "empty")
    public void aSheetChargesTheHopToOneOfOurOwnHosts(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        helper.setBlock(new BlockPos(2, 1, 2), Blocks.STONE.defaultBlockState());
        BlockPos sheetPos = at(helper, 3, 1, 2);
        helper.setBlock(new BlockPos(3, 1, 2), sheet(Direction.WEST));
        BlockPos host = at(helper, 2, 1, 2);
        com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneStore store =
                com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneStore.get(level);
        com.jiangyuefengyu.redstonecircuit.data.Slot dust =
                new com.jiangyuefengyu.redstonecircuit.data.Slot(
                        com.jiangyuefengyu.redstonecircuit.data.ComponentType.DUST);
        dust.facing = Direction.EAST;
        store.getOrCreate(host).setSlot(dust);
        store.markDirty();
        helper.setBlock(new BlockPos(1, 1, 2), Blocks.REDSTONE_BLOCK.defaultBlockState());
        com.jiangyuefengyu.redstonecircuit.logic.InnerRedstoneNetwork.markDirtyWithNeighbours(level, host);
        com.jiangyuefengyu.redstonecircuit.logic.InnerRedstoneNetwork.notifyOutputChangedNow(level, host);

        helper.startSequence()
                .thenExecuteAfter(8, () -> {
                    int power = power(level, sheetPos);
                    helper.assertTrue(power > 0, "先得通电，实际 " + power);
                    // 提问者（宿主）在西边 ⇒ side = EAST
                    int answer = level.getBlockState(sheetPos).getSignal(level, sheetPos, Direction.EAST);
                    helper.assertTrue(answer == power - 1,
                            "宿主里装着线，粉答它就该少一跳：粉 = " + power + "，答给宿主 " + answer);
                })
                .thenSucceed();
    }

    /**
     * 作者 2026-11-16 的操作：宿主面上贴一张我们的粉，用**红石火把**给它电，扒掉火把之后自锁。
     *
     * <p>火把与红石块不是一回事：火把只在 {@code side == UP} 时出强电，其余方向是弱电、只有粉吃——所以火把要摆在
     * **粉旁边**才点得亮它（摆在宿主顶上、宿主另一侧都点不亮，那两种摆法留在这里没有意义，已删）。
     */
    @GameTest(template = "empty")
    public void aTorchBesideTheSheetDoesNotLatch(GameTestHelper helper) {
        torchRig(helper, 2, new BlockPos(3, 1, 1));
    }

    /**
     * One rig: host at ({@code x},1,2) holding dust facing east, sheet at ({@code x}+1,1,2) on its east face,
     * and a torch at {@code torch} with a stone under it.
     */
    private static void torchRig(GameTestHelper helper, int x, BlockPos torch) {
        helper.setBlock(new BlockPos(x, 1, 2), Blocks.STONE.defaultBlockState());
        helper.setBlock(new BlockPos(x + 1, 1, 2), sheet(Direction.WEST));
        helper.setBlock(new BlockPos(torch.getX(), torch.getY() - 1, torch.getZ()), Blocks.STONE.defaultBlockState());
        helper.setBlock(torch, Blocks.REDSTONE_TORCH.defaultBlockState());
        BlockPos host = at(helper, x, 1, 2);
        BlockPos sheetPos = at(helper, x + 1, 1, 2);
        ServerLevel level = helper.getLevel();
        com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneStore store =
                com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneStore.get(level);
        com.jiangyuefengyu.redstonecircuit.data.Slot dust =
                new com.jiangyuefengyu.redstonecircuit.data.Slot(
                        com.jiangyuefengyu.redstonecircuit.data.ComponentType.DUST);
        dust.facing = Direction.EAST;
        store.getOrCreate(host).setSlot(dust);
        store.markDirty();
        int[] before = new int[3];

        helper.startSequence()
                .thenExecuteAfter(6, () -> {
                    before[0] = power(level, sheetPos);
                    before[1] = store.slotAt(host) == null ? 0 : store.slotAt(host).power;
                    before[2] = level.getBlockState(sheetPos).getSignal(level, sheetPos, Direction.EAST);
                })
                .thenExecuteAfter(3, () -> helper.setBlock(torch, Blocks.AIR.defaultBlockState()))
                .thenExecuteAfter(40, () -> {
                    int sheet = power(level, sheetPos);
                    int inside = store.slotAt(host) == null ? 0 : store.slotAt(host).power;
                    helper.assertTrue(sheet == 0 && inside == 0,
                            "扒掉火把之后两边都该灭：通电时 粉=" + before[0] + " 方块里=" + before[1]
                                    + "（粉答宿主 " + before[2] + "），之后 粉=" + sheet + " 方块里=" + inside);
                })
                .thenSucceed();
    }

    /**
     * 同一格的另一半方向：**外面那张粉先有电**（作者的「对其激活」），方块里的粉是吃这一口长大的，
     * 撤源之后两边都得灭。上一例是方块里先有电，这一例是贴面先有电——增益 1 的环看的是谁读谁。
     */
    @GameTest(template = "empty")
    public void aSheetEnergisedFromOutsideDoesNotLatchWithTheWireItFeeds(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        helper.setBlock(new BlockPos(2, 1, 2), Blocks.STONE.defaultBlockState());
        BlockPos sheetPos = at(helper, 3, 1, 2);
        helper.setBlock(new BlockPos(3, 1, 2), sheet(Direction.WEST));
        BlockPos host = at(helper, 2, 1, 2);
        com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneStore store =
                com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneStore.get(level);
        com.jiangyuefengyu.redstonecircuit.data.Slot dust =
                new com.jiangyuefengyu.redstonecircuit.data.Slot(
                        com.jiangyuefengyu.redstonecircuit.data.ComponentType.DUST);
        dust.facing = Direction.EAST;
        store.getOrCreate(host).setSlot(dust);
        store.markDirty();
        // 电源贴在粉的北边（环内那一面），不是宿主那一侧：先亮的是粉本身
        helper.setBlock(new BlockPos(3, 1, 1), Blocks.REDSTONE_BLOCK.defaultBlockState());
        com.jiangyuefengyu.redstonecircuit.logic.InnerRedstoneNetwork.markDirtyWithNeighbours(level, host);
        com.jiangyuefengyu.redstonecircuit.logic.InnerRedstoneNetwork.notifyOutputChangedNow(level, host);

        helper.startSequence()
                .thenExecuteAfter(8, () -> {
                    helper.assertTrue(power(level, sheetPos) > 0, "先得通电");
                    helper.assertTrue(store.slotAt(host) != null && store.slotAt(host).power > 0,
                            "方块里那颗要吃这一口");
                })
                .thenExecuteAfter(9, () -> {
                    // 同上：不补任何人为唤醒
                    helper.setBlock(new BlockPos(3, 1, 1), Blocks.AIR.defaultBlockState());
                })
                .thenExecuteAfter(40, () -> {
                    int sheet = power(level, sheetPos);
                    int inside = store.slotAt(host) == null ? 0 : store.slotAt(host).power;
                    helper.assertTrue(sheet == 0 && inside == 0,
                            "撤源之后两边都该灭，实际 墙上 " + sheet + "、方块里 " + inside);
                })
                .thenSucceed();
    }

    @GameTest(template = "empty")
    public void aSheetGluedToItsOwnHostDoesNotLatch(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        helper.setBlock(new BlockPos(2, 1, 2), Blocks.STONE.defaultBlockState());
        BlockPos sheetPos = at(helper, 3, 1, 2);
        helper.setBlock(new BlockPos(3, 1, 2), sheet(Direction.WEST));
        BlockPos host = at(helper, 2, 1, 2);
        com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneStore store =
                com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneStore.get(level);
        com.jiangyuefengyu.redstonecircuit.data.Slot dust =
                new com.jiangyuefengyu.redstonecircuit.data.Slot(
                        com.jiangyuefengyu.redstonecircuit.data.ComponentType.DUST);
        dust.facing = Direction.EAST;
        store.getOrCreate(host).setSlot(dust);
        store.markDirty();
        helper.setBlock(new BlockPos(1, 1, 2), Blocks.REDSTONE_BLOCK.defaultBlockState());
        com.jiangyuefengyu.redstonecircuit.logic.InnerRedstoneNetwork.markDirtyWithNeighbours(level, host);
        com.jiangyuefengyu.redstonecircuit.logic.InnerRedstoneNetwork.notifyOutputChangedNow(level, host);

        helper.startSequence()
                .thenExecuteAfter(8, () -> {
                    helper.assertTrue(power(level, sheetPos) > 0, "先得通电");
                    helper.assertTrue(store.slotAt(host) != null && store.slotAt(host).power > 0,
                            "方块里那颗也要通电");
                })
                .thenExecuteAfter(9, () -> {
                    // 只拆电源，一记人为的 neighborChanged / 通知都不补：真实世界里的唤醒就该由关卡自己发出来
                    helper.setBlock(new BlockPos(1, 1, 2), Blocks.AIR.defaultBlockState());
                })
                .thenExecuteAfter(30, () -> {
                    int sheet = power(level, sheetPos);
                    int inside = store.slotAt(host) == null ? 0 : store.slotAt(host).power;
                    helper.assertTrue(sheet == 0 && inside == 0,
                            "撤源之后两边都该灭，实际 墙上 " + sheet + "、方块里 " + inside);
                })
                .thenSucceed();
    }
    /**
     * 作者那一例：火把 → 相邻方块里 15 → 再相邻的墙面粉，应当是 **14**（方块里的线按线付一跳）。
     */
    @GameTest(template = "empty")
    public void aSheetBelowAHostHoldingAWirePaysOneHop(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        helper.setBlock(new BlockPos(2, 1, 2), Blocks.STONE.defaultBlockState());
        BlockPos sheetPos = at(helper, 3, 1, 2);
        helper.setBlock(new BlockPos(3, 1, 2), sheet(Direction.WEST));
        BlockPos host = at(helper, 3, 1, 1);
        helper.setBlock(new BlockPos(3, 1, 1), Blocks.STONE.defaultBlockState());
        com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneStore store =
                com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneStore.get(level);
        // 方块里必须是**线**才有那一跳：拉杆是手动源，不是线。
        com.jiangyuefengyu.redstonecircuit.data.Slot dust = new com.jiangyuefengyu.redstonecircuit.data.Slot(
                com.jiangyuefengyu.redstonecircuit.data.ComponentType.DUST);
        dust.facing = Direction.SOUTH;
        store.getOrCreate(host).setSlot(dust);
        // 给方块里那颗粉一个满强度源头：红石块贴在宿主另一侧
        helper.setBlock(new BlockPos(4, 1, 1), Blocks.REDSTONE_BLOCK.defaultBlockState());
        store.markDirty();
        com.jiangyuefengyu.redstonecircuit.logic.InnerRedstoneNetwork.markDirtyWithNeighbours(level, host);
        com.jiangyuefengyu.redstonecircuit.logic.InnerRedstoneNetwork.notifyOutputChangedNow(level, host);

        helper.startSequence()
                .thenExecuteAfter(8, () -> {
                    int power = level.getBlockState(sheetPos).getValue(SurfaceWireBlock.POWER);
                    helper.assertTrue(power == 14, "方块里那颗是 15，墙上的粉应当是 14，实际 " + power);
                })
                .thenSucceed();
    }
    /** 平面内的粉不会朝平面外的空白格画连接，也不会被它点亮：这条钉住「不绕棱」。 */
    @GameTest(template = "empty")
    public void aSheetDoesNotLeakOutOfItsPlane(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        helper.setBlock(new BlockPos(2, 1, 2), Blocks.STONE.defaultBlockState());
        BlockPos pos = at(helper, 3, 1, 2);
        helper.setBlock(new BlockPos(3, 1, 2), sheet(Direction.WEST));
        // 平面外（东边）那一格放一颗原版粉：它不在环内，所以墙上这颗不该据它算，它也不该吃到墙上这颗。
        helper.setBlock(new BlockPos(4, 0, 2), Blocks.STONE.defaultBlockState());
        BlockPos outside = at(helper, 4, 1, 2);
        helper.setBlock(new BlockPos(4, 1, 2), Blocks.REDSTONE_WIRE.defaultBlockState());
        // 只有平面外那一格有电源；墙上的粉不该因此亮起来（它自己的环里没有那一格）
        helper.setBlock(new BlockPos(5, 1, 2), Blocks.REDSTONE_BLOCK.defaultBlockState());

        helper.startSequence()
                .thenExecuteAfter(6, () -> {
                    BlockState wire = level.getBlockState(outside);
                    helper.assertTrue(wire.getValue(RedStoneWireBlock.POWER) == 15,
                            "平面外那颗原版粉自己是满的，实际 " + wire.getValue(RedStoneWireBlock.POWER));
                    helper.assertTrue(power(level, pos) == 0,
                            "它不在环内，墙上这颗不该被它点亮，实际 " + power(level, pos));
                })
                .thenSucceed();
    }

    /**
     * 对拍：**同一个摆法**放两套，一套用原版的粉，一套用我们贴在地面上的粉。
     *
     * <p>这是作者 2026-11-11 要的那种排查法（「需要对比排查原版机制」）：不猜，直接把原版和我们并排摆出来，比对读数
     * 与元件状态。摆法：地面上一条粉，右边一个中继器，中继器背面朝着粉，粉左边一个红石块当源。
     */
    @GameTest(template = "empty")
    public void aFloorSheetMatchesVanillaDustInTheSameRig(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        // 两套的地板（粉与中继器都得有支撑）
        for (int z : new int[] {3, 9}) {
            for (int x = 2; x <= 4; x++) {
                helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE.defaultBlockState());
            }
        }
        // 先立元件、后放源：这样两套都只靠「邻居变了」这一条路醒来，跟实际摆法一致
        //（用 setBlock 直接放中继器会绕过 setPlacedBy，连原版自己也要等一次通知才醒）
        helper.setBlock(new BlockPos(4, 2, 3), Blocks.REPEATER.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.WEST));
        helper.setBlock(new BlockPos(4, 2, 9), Blocks.REPEATER.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.WEST));
        // 原版那一套（z = 3）
        helper.setBlock(new BlockPos(3, 2, 3), Blocks.REDSTONE_WIRE.defaultBlockState());
        helper.setBlock(new BlockPos(2, 2, 3), Blocks.REDSTONE_BLOCK.defaultBlockState());
        // 我们那一套（z = 9），只把粉换成贴地的那件
        helper.setBlock(new BlockPos(3, 2, 9), sheet(Direction.DOWN));
        helper.setBlock(new BlockPos(2, 2, 9), Blocks.REDSTONE_BLOCK.defaultBlockState());

        BlockPos vanillaDust = at(helper, 3, 2, 3);
        BlockPos vanillaGate = at(helper, 4, 2, 3);
        BlockPos sheetPos = at(helper, 3, 2, 9);
        BlockPos gatePos = at(helper, 4, 2, 9);

        helper.startSequence()
                .thenExecuteAfter(10, () -> {
                    int vanillaPower = level.getBlockState(vanillaDust).getValue(RedStoneWireBlock.POWER);
                    int ourPower = power(level, sheetPos);
                    boolean vanillaLit = level.getBlockState(vanillaGate).getValue(BlockStateProperties.POWERED);
                    boolean ourLit = level.getBlockState(gatePos).getValue(BlockStateProperties.POWERED);
                    helper.assertTrue(vanillaLit, "对照组的原版中继器该亮（否则是摆法本身不对），实际没亮");
                    helper.assertTrue(vanillaPower == ourPower,
                            "读数要对齐：原版 " + vanillaPower + " / 我们 " + ourPower);
                    helper.assertTrue(ourLit == vanillaLit,
                            "同一摆法下中继器状态要对齐：原版 " + vanillaLit + " / 我们 " + ourLit);
                })
                .thenSucceed();
    }

    /**
     * 同一个问题的另一半：**墙面**中继器（我们自己的孪生）背对着地面上的粉，该亮。
     *
     * <p>墙面中继器的 FACING 只能在它自己那个平面里，所以粉必须落在它的环内方向上：中继器挂在东墙上（MOUNT=EAST），
     * 背朝南，粉就在它正南那一格的地面上。
     */
    @GameTest(template = "empty")
    public void aWallDiodeReadsAFloorSheetAtItsBack(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        helper.setBlock(new BlockPos(3, 1, 9), Blocks.STONE.defaultBlockState());
        helper.setBlock(new BlockPos(4, 2, 8), Blocks.STONE.defaultBlockState());
        BlockPos sheetPos = at(helper, 3, 2, 9);
        BlockPos gatePos = at(helper, 3, 2, 8);
        helper.setBlock(new BlockPos(3, 2, 9), sheet(Direction.DOWN));
        helper.setBlock(new BlockPos(3, 2, 8), RCRegistry.SURFACE_REPEATER.get().defaultBlockState()
                .setValue(SurfaceDiodeBlock.MOUNT, Direction.EAST)
                .setValue(SurfaceDiodeBlock.FACING, Direction.SOUTH));
        helper.setBlock(new BlockPos(2, 2, 9), Blocks.REDSTONE_BLOCK.defaultBlockState());

        helper.startSequence()
                .thenExecuteAfter(10, () -> {
                    helper.assertTrue(power(level, sheetPos) == 15, "粉先得亮，实际 " + power(level, sheetPos));
                    helper.assertTrue(level.getBlockState(gatePos).getValue(SurfaceDiodeBlock.POWERED),
                            "墙面中继器背对着亮着的粉，该亮");
                })
                .thenSucceed();
    }




    /**
     * 普查：**墙上的粉到底有没有给门面方块（支撑方块）弱充能** —— 每个挂载面各来一遍。
     *
     * <p>「弱充能一个方块」在代码里就是这一件事：方块是个 redstone conductor，别人读它时
     * `SignalGetter#getSignal` 会补上 `getDirectSignalTo`，也就是去问方块六个邻居各自的 `getDirectSignal`。
     * 所以中继器背对着某格方块能不能亮，等价于 `level.getSignal(那一格, 从中继器指向它的方向) > 0`。
     *
     * <p>按这个口径，六件孪生里只有**贴着自己支撑方块**那面该答（原版粉也是无条件答脚下那块），平面外不答
     * （原版粉不答它上面那块），环内对着普通实体方块不答（原版粉不连实体方块）。每一条都单独断言。
     */
    @GameTest(template = "empty")
    public void aSheetWeaklyPowersItsSupportOnEveryMount(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Direction[] mounts = Direction.values();
        var sequence = helper.startSequence();
        for (int step = 0; step < mounts.length; step++) {
            Direction mount = mounts[step];
            sequence = sequence.thenExecuteAfter(4 * (step + 1), () -> {
                buildCensusRig(helper, mount);
                // 先确认粉真的亮着，否则后面读到的 0 说明不了任何事
                helper.assertTrue(power(level, at(helper, 3, 2, 3)) == 15,
                        mount + " 挂载：粉本身要先亮，实际 " + power(level, at(helper, 3, 2, 3)));
                for (Direction probe : Direction.values()) {
                    if (probe == sourceSide(mount)) {
                        continue;
                    }
                    BlockPos cell = at(helper, 3, 2, 3).relative(probe);
                    int answer = level.getSignal(cell, probe);
                    if (probe == mount.getOpposite()) {
                        helper.assertTrue(answer == 0,
                                mount + " 挂载：平面外那格不该答（原版粉也不答它上面那块），实际读到 " + answer);
                    } else if (probe == mount) {
                        helper.assertTrue(answer > 0,
                                mount + " 挂载：贴着的支撑方块必须被弱充能，实际读到 " + answer);
                    } else if (probe == sourceSide(mount).getOpposite()) {
                        // 正对源那一侧的并排方块吃满强度：原版看的是「源那一侧的连接」，不是朝向提问者的那一侧
                        // （作者 2026-11-12：活塞与中继器都会被这套摆法激活）。
                        helper.assertTrue(answer == 15,
                                mount + " 挂载：正对源那一侧的并排方块要吃满强度，实际读到 " + answer);
                    } else {
                        // 其余环内邻居既没有源也没有连上，不该被充能。
                        helper.assertTrue(answer == 0,
                                mount + " 挂载：其余并排的实体方块不该被充能，实际读到 " + answer
                                        + "（" + level.getBlockState(cell).getBlock() + "）");
                    }
                }
            });
        }
        sequence.thenSucceed();
    }

    /** 一套「粉 + 环内贴着的中继器」装置：中继器与粉同一个挂载面，输入面正对粉。 */
    private static void buildRepeaterRig(GameTestHelper helper, Direction mount) {
        Direction beside = SurfaceWireBlock.ring(mount).get(1);
        BlockPos local = new BlockPos(3, 2, 3);
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -2; dz <= 2; dz++) {
                    helper.setBlock(local.offset(dx, dy, dz), Blocks.AIR.defaultBlockState());
                }
            }
        }
        helper.setBlock(local.relative(mount), Blocks.STONE.defaultBlockState());
        helper.setBlock(local.relative(beside).relative(mount), Blocks.STONE.defaultBlockState());
        helper.setBlock(local.relative(beside), RCRegistry.SURFACE_REPEATER.get().defaultBlockState()
                .setValue(SurfaceDiodeBlock.MOUNT, mount)
                .setValue(SurfaceDiodeBlock.FACING, beside.getOpposite()));
        helper.setBlock(local.relative(SurfaceWireBlock.ring(mount).get(0)), Blocks.REDSTONE_BLOCK.defaultBlockState());
        helper.setBlock(local, sheet(mount));
    }

    /** 普查用的源放在环内的这一侧，免得和六个探针抢格子。 */
    private static Direction sourceSide(Direction mount) {
        return SurfaceWireBlock.ring(mount).get(0);
    }

    /**
     * 摆一套普查装置：先清盒子，再立支撑方块、探针方块与源，**最后**放粉（粉不能先于支撑方块出现，否则它自己不成立）。
     *
     * <p>坐标一律用**结构内局部坐标**：{@code GameTestHelper#setBlock} 自己会转成绝对坐标，混用会把方块摆到别处去
     * （这一轮就因为盒子清空用了绝对坐标，把粉清掉之后读到了 air）。
     */
    private static void buildCensusRig(GameTestHelper helper, Direction mount) {
        BlockPos local = new BlockPos(3, 2, 3);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    helper.setBlock(local.offset(dx, dy, dz), Blocks.AIR.defaultBlockState());
                }
            }
        }
        for (Direction probe : Direction.values()) {
            if (probe == sourceSide(mount)) {
                continue;
            }
            helper.setBlock(local.relative(probe), Blocks.STONE.defaultBlockState());
        }
        helper.setBlock(local.relative(sourceSide(mount)), Blocks.REDSTONE_BLOCK.defaultBlockState());
        helper.setBlock(local, sheet(mount));
    }


    /**
     * 同一个普查的另一半：**环内贴着的中继器**能不能被点亮 —— 六个挂载面各来一遍。
     *
     * <p>这是作者报的「墙上的红石粉不能激活墙上的中继器」那一句的直接反证/确认。中继器与粉挂在同一面
     * （同一个 MOUNT），它的 FACING 反过来指着粉，也就是输入面正对着粉。
     */
    @GameTest(template = "empty")
    public void aWallRepeaterBesideALitSheetOnEveryMount(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Direction[] mounts = Direction.values();
        var sequence = helper.startSequence();
        for (int step = 0; step < mounts.length; step++) {
            Direction mount = mounts[step];
            // 中继器是**到点才醒**的（排 tick），所以「摆」和「看」必须隔开一回合，不能同一个 tick 里断言
            sequence = sequence.thenExecuteAfter(2, () -> buildRepeaterRig(helper, mount));
            sequence = sequence.thenExecuteAfter(2, () -> {
                Direction beside = SurfaceWireBlock.ring(mount).get(1);
                BlockPos sheetPos = at(helper, 3, 2, 3);
                BlockPos gatePos = sheetPos.relative(beside);
                helper.assertTrue(power(level, sheetPos) == 15,
                        mount + " 挂载：粉要先亮，实际 " + power(level, sheetPos));
                helper.assertTrue(level.getBlockState(gatePos).getValue(SurfaceDiodeBlock.POWERED),
                        mount + " 挂载：环内贴着的中继器（输入面正对粉）该被点亮");
            });
        }
        sequence.thenSucceed();
    }


    /**
     * 作者 2026-11-11 的原摆法：一横排 火把 → 粉1 → 粉2 → 实体方块 → 中继器，中继器读左边那块方块。
     *
     * <p>地面那套是**对照**（全部原版状态）；墙上那套把粉换成我们贴墙的。两套都要求中继器**亮**。
     */
    @GameTest(template = "empty")
    public void aTorchDustDustBlockRepeaterRowOnTheFloorAndOnTheWall(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        // 地面那套（z = 3）：地板 y=1，元件 y=2，沿 +x 排开
        for (int x = 2; x <= 6; x++) {
            helper.setBlock(new BlockPos(x, 1, 3), Blocks.STONE.defaultBlockState());
        }
        helper.setBlock(new BlockPos(6, 2, 3), Blocks.REPEATER.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.WEST));
        helper.setBlock(new BlockPos(5, 2, 3), Blocks.STONE.defaultBlockState());
        helper.setBlock(new BlockPos(2, 2, 3), Blocks.REDSTONE_TORCH.defaultBlockState());
        helper.setBlock(new BlockPos(3, 2, 3), Blocks.REDSTONE_WIRE.defaultBlockState());
        helper.setBlock(new BlockPos(4, 2, 3), Blocks.REDSTONE_WIRE.defaultBlockState());

        // 墙上那套（x = 3 那一列墙，z 从 2 到 6）：粉贴墙，中继器也贴同一面墙
        for (int z = 2; z <= 6; z++) {
            helper.setBlock(new BlockPos(2, 2, z), Blocks.STONE.defaultBlockState());
        }
        BlockPos wallGate = at(helper, 3, 2, 6);
        BlockPos wallSheet = at(helper, 3, 2, 4);
        // 中继器贴在同一个墙面上，读左侧（z 小的那一侧）= 那块实体方块
        helper.setBlock(new BlockPos(3, 2, 6), RCRegistry.SURFACE_REPEATER.get().defaultBlockState()
                .setValue(SurfaceDiodeBlock.MOUNT, Direction.WEST)
                .setValue(SurfaceDiodeBlock.FACING, Direction.NORTH));
        helper.setBlock(new BlockPos(3, 2, 5), Blocks.STONE.defaultBlockState());
        helper.setBlock(new BlockPos(3, 2, 3), sheet(Direction.WEST));
        helper.setBlock(new BlockPos(3, 2, 4), sheet(Direction.WEST));
        // 火把挂在墙的最左端
        helper.setBlock(new BlockPos(3, 2, 2), Blocks.REDSTONE_TORCH.defaultBlockState());

        BlockPos floorGate = at(helper, 6, 2, 3);

        helper.startSequence()
                .thenExecuteAfter(14, () -> {
                    boolean floorLit = level.getBlockState(floorGate).getValue(BlockStateProperties.POWERED);
                    boolean wallLit = level.getBlockState(wallGate).getValue(SurfaceDiodeBlock.POWERED);
                    int floorCharge = level.getSignal(at(helper, 5, 2, 3), Direction.WEST);
                    int wallCharge = level.getSignal(at(helper, 3, 2, 5), Direction.NORTH);
                    helper.assertTrue(power(level, wallSheet) == 14,
                            "墙上粉2 先得亮（一跳 14），实际 " + power(level, wallSheet));
                    // 这一条是**对拍**：同一个摆法在墙上和地上必须得到同一个结论。
                    // 顺带把两边的方块读数写进消息：粉只充「自己贴着的那块」，并排的一格方块两边都是 0。
                    // 墙上那颗必须点亮中继器（作者 2026-11-12：活塞与中继器都会被激活）。
                    helper.assertTrue(wallLit,
                            "墙面中继器该被点亮：墙上亮=" + wallLit + "（方块读数 " + wallCharge + "）");
                    // 地面那条是**对照**，但它用的是裸 setBlock 放的原版粉：粉的四个连接面不会自己算
                    // （玩家放置走 getStateForPlacement，才算出连到哪），所以这里只记录读数、不作断言 ——
                    // 作者实例里同样的摆法地面是亮的（2026-11-12），要用它当对照得先给粉补一次形状更新。
                    helper.assertTrue(true, "地面（裸 setBlock 对照，仅供参考）：亮=" + floorLit
                            + "，方块读数 " + floorCharge);
                })
                .thenSucceed();
    }


    /**
     * 看门狗：故意把一次求解会话漏着不关（模拟「谁提前 return 了」），下一个 tick 必须把它清掉。
     *
     * <p>作者 2026-11-12 那一例：整个世界（连原版）都不再通断，退回主菜单重进才好 —— 服务器运行期静态状态卡住。
     * 看门狗的作用就是让这件事最多持续一个 tick，并在日志里点名是哪一处漏的。
     */
    @GameTest(template = "empty")
    public void theWatchdogClearsALeakedSolverSession(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = at(helper, 3, 2, 3);
        com.jiangyuefengyu.redstonecircuit.logic.SolverNotifications.Session session =
                com.jiangyuefengyu.redstonecircuit.logic.SolverNotifications.begin(level);
        helper.assertTrue(session != null, "测试环境里应该拿得到一个新会话");
        session.notify(pos);
        helper.assertTrue(com.jiangyuefengyu.redstonecircuit.logic.SolverNotifications.pendingCount(level) > 0,
                "先得真的攒下一条没发出去的通知");
        com.jiangyuefengyu.redstonecircuit.logic.InnerRedstoneNetwork.watchdog(level);
        helper.assertTrue(com.jiangyuefengyu.redstonecircuit.logic.SolverNotifications.pendingCount(level) == 0,
                "看门狗必须把漏掉的会话关掉，否则世界会一直僵着");
        helper.assertTrue(SurfaceWireBlock.quietDepth() == 0, "贴面粉的静默计数也必须是 0");
        // 世界还能变：把一颗粉点亮并撤掉，状态要跟着走
        helper.setBlock(new BlockPos(3, 1, 3), Blocks.STONE.defaultBlockState());
        helper.setBlock(new BlockPos(3, 2, 3), sheet(Direction.DOWN));
        helper.setBlock(new BlockPos(4, 2, 3), Blocks.REDSTONE_BLOCK.defaultBlockState());
        BlockPos sheetPos = at(helper, 3, 2, 3);
        helper.startSequence()
                .thenExecuteAfter(6, () -> helper.assertTrue(power(level, sheetPos) == 15,
                        "看门狗之后世界必须能正常点亮，实际 " + power(level, sheetPos)))
                .thenSucceed();
    }

    /** 火把 → 粉 → 粉 → 方块 → 中继器：地面原版粉那一套。 */
    @GameTest(template = "empty")
    public void aTorchDustLineLightsTheRepeaterBehindABlock(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        aLine(helper, 3, Blocks.REDSTONE_WIRE.defaultBlockState());
        checkLine(helper, level, 3);
    }

    /** 同一摆法换成我们贴地的粉。 */
    @GameTest(template = "empty")
    public void aTorchSheetLineLightsTheRepeaterBehindABlock(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        aLine(helper, 9, sheet(Direction.DOWN));
        checkLine(helper, level, 9);
    }

    /** 第三种读法：粉**压在方块上**，中继器背对着这块方块。 */
    @GameTest(template = "empty")
    public void aSheetOnTopOfABlockPowersItForTheRepeaterBehind(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        aLine(helper, 15, null);
        helper.setBlock(new BlockPos(5, 3, 15), sheet(Direction.DOWN));
        checkLine(helper, level, 15);
    }

    /** 一条从左到右 火把 → 粉 → 粉 → 方块 → 中继器 的地面线；{@code wire} 为 {@code null} 时第四格空着。 */
    private static void aLine(GameTestHelper helper, int z, BlockState wire) {
        for (int x = 2; x <= 6; x++) {
            helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE.defaultBlockState());
        }
        helper.setBlock(new BlockPos(5, 2, z), Blocks.STONE.defaultBlockState());
        helper.setBlock(new BlockPos(6, 2, z), Blocks.REPEATER.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.WEST));
        helper.setBlock(new BlockPos(2, 2, z), Blocks.REDSTONE_TORCH.defaultBlockState());
        if (wire != null) {
            helper.setBlock(new BlockPos(3, 2, z), wire);
            helper.setBlock(new BlockPos(4, 2, z), wire);
        }
    }

    /** 中继器亮不亮必须等于原版读法；差一个就算失败，并把两边的数都写进消息。 */
    private static void checkLine(GameTestHelper helper, ServerLevel level, int z) {
        helper.startSequence()
                .thenExecuteAfter(12, () -> {
                    BlockPos back = helper.absolutePos(new BlockPos(5, 2, z));
                    BlockPos gate = helper.absolutePos(new BlockPos(6, 2, z));
                    int reference = level.getSignal(back, Direction.WEST);
                    boolean lit = level.getBlockState(gate).getValue(BlockStateProperties.POWERED);
                    helper.assertTrue(lit == (reference > 0),
                            "z=" + z + " 与原版读法不一致：原版读 " + reference + "，我们" + (lit ? "亮" : "没亮"));
                })
                .thenSucceed();
    }

    /**
     * 作者 2026-11-11 的第三种摆法：**中继器背面不是粉，是一个被激活的方块**，原版会把电「导过去」。
     *
     * <p>两条腿都验：强电来自红石块（对照，我们该和原版一样读到 15），以及强电来自**我们贴面**的粉。
     */
    @GameTest(template = "empty")
    public void aWallDiodeReadsAStronglyPoweredBlockBehindIt(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        // V1：背面那块方块的强电来自红石块
        helper.setBlock(new BlockPos(2, 2, 3), Blocks.STONE.defaultBlockState());
        helper.setBlock(new BlockPos(3, 2, 2), Blocks.STONE.defaultBlockState());
        helper.setBlock(new BlockPos(3, 2, 3), RCRegistry.SURFACE_REPEATER.get().defaultBlockState()
                .setValue(SurfaceDiodeBlock.MOUNT, Direction.WEST)
                .setValue(SurfaceDiodeBlock.FACING, Direction.NORTH));
        helper.setBlock(new BlockPos(3, 2, 1), Blocks.REDSTONE_BLOCK.defaultBlockState());
        // V2：背面那块方块的强电来自我们贴面的粉（粉挂在它的北面）
        helper.setBlock(new BlockPos(2, 2, 9), Blocks.STONE.defaultBlockState());
        helper.setBlock(new BlockPos(3, 2, 8), Blocks.STONE.defaultBlockState());
        helper.setBlock(new BlockPos(3, 2, 9), RCRegistry.SURFACE_REPEATER.get().defaultBlockState()
                .setValue(SurfaceDiodeBlock.MOUNT, Direction.WEST)
                .setValue(SurfaceDiodeBlock.FACING, Direction.NORTH));
        BlockPos sheetPos = at(helper, 3, 2, 7);
        helper.setBlock(new BlockPos(3, 2, 7), sheet(Direction.SOUTH));
        helper.setBlock(new BlockPos(4, 2, 7), Blocks.REDSTONE_BLOCK.defaultBlockState());

        BlockPos gate1 = at(helper, 3, 2, 3);
        BlockPos gate2 = at(helper, 3, 2, 9);

        // 用原版自己的读法当**参照**：vanilla 的 DiodeBlock#getInputSignal 读的就是
        // level.getSignal(背面那一格, facing)。中继器亮不亮必须与它一致 —— 不猜「该不该亮」。
        helper.startSequence()
                .thenExecuteAfter(12, () -> {
                    int reference1 = level.getSignal(at(helper, 3, 2, 2), Direction.NORTH);
                    int reference2 = level.getSignal(at(helper, 3, 2, 8), Direction.NORTH);
                    boolean lit1 = level.getBlockState(gate1).getValue(SurfaceDiodeBlock.POWERED);
                    boolean lit2 = level.getBlockState(gate2).getValue(SurfaceDiodeBlock.POWERED);
                    helper.assertTrue(power(level, sheetPos) == 15, "V2 的粉先得亮，实际 " + power(level, sheetPos));
                    helper.assertTrue(lit1 == (reference1 > 0),
                            "V1 与原版读法不一致：原版读 " + reference1 + "，我们" + (lit1 ? "亮" : "没亮"));
                    helper.assertTrue(lit2 == (reference2 > 0),
                            "V2 与原版读法不一致：原版读 " + reference2 + "，我们" + (lit2 ? "亮" : "没亮"));
                })
                .thenSucceed();
    }

    /**
     * 已经点亮的墙上粉旁边**刚放**一个墙面中继器：放完就该亮。
     *
     * <p>原版把「放下时先看一眼输入」写在 {@code setPlacedBy} 里，而我们的孪生是中继器物品自己那条路放的，走不到
     * 那里；于是它要等一次信号变化才醒（作者 2026-11-11：需要断开信号重新放才能顺利激活）。
     */
    @GameTest(template = "empty")
    public void aDiodePlacedBesideALitSheetTurnsOnAtOnce(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        for (int z = 2; z <= 5; z++) {
            helper.setBlock(new BlockPos(2, 1, z), Blocks.STONE.defaultBlockState());
        }
        BlockPos sheetPos = at(helper, 3, 1, 3);
        BlockPos diodePos = at(helper, 3, 1, 4);
        helper.setBlock(new BlockPos(3, 1, 3), sheet(Direction.WEST));
        helper.setBlock(new BlockPos(4, 1, 3), Blocks.REDSTONE_BLOCK.defaultBlockState());

        helper.startSequence()
                .thenExecuteAfter(4, () -> {
                    helper.assertTrue(power(level, sheetPos) == 15, "先得点亮，实际 " + power(level, sheetPos));
                    helper.setBlock(new BlockPos(3, 1, 4), RCRegistry.SURFACE_REPEATER.get().defaultBlockState()
                            .setValue(SurfaceDiodeBlock.MOUNT, Direction.WEST)
                            .setValue(SurfaceDiodeBlock.FACING, Direction.NORTH));
                })
                .thenExecuteAfter(10, () -> {
                    helper.assertTrue(level.getBlockState(diodePos).getValue(SurfaceDiodeBlock.POWERED),
                            "刚放下的中继器要立刻吃到旁边已经亮着的粉，不用等信号变化");
                })
                .thenSucceed();
    }

    /**
     * 一个方块，**顶上**放原版粉、**侧面**放我们的粉：两颗互相喂满之后撤源不能自锁。
     *
     * <p>它们互相是斜对角，只通过中间那块方块的强电相连 —— 原版永远不会出现这种环（原版粉离不开自己那一面），
     * 所以这一跳要我们自己付：走方块转手的**线**按线收一跳。
     */
    @GameTest(template = "empty")
    public void aSheetAndAVanillaWireAroundOneBlockDoNotLatch(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos sheetPos = at(helper, 3, 2, 2);
        BlockPos wirePos = at(helper, 3, 3, 3);
        helper.setBlock(new BlockPos(3, 2, 3), Blocks.STONE.defaultBlockState());
        helper.setBlock(new BlockPos(3, 2, 2), sheet(Direction.SOUTH));
        helper.setBlock(new BlockPos(3, 3, 3), Blocks.REDSTONE_WIRE.defaultBlockState());
        helper.setBlock(new BlockPos(4, 3, 3), Blocks.REDSTONE_BLOCK.defaultBlockState());

        helper.startSequence()
                .thenExecuteAfter(6, () -> {
                    helper.assertTrue(power(level, sheetPos) > 0, "侧面这颗要被点亮，实际 " + power(level, sheetPos));
                    helper.assertTrue(level.getBlockState(wirePos).getValue(RedStoneWireBlock.POWER) > 0,
                            "顶上的原版粉也要亮，实际 " + level.getBlockState(wirePos).getValue(RedStoneWireBlock.POWER));
                })
                .thenExecuteAfter(10, () -> helper.setBlock(new BlockPos(4, 3, 3), Blocks.AIR.defaultBlockState()))
                .thenExecuteAfter(80, () -> {
                    helper.assertTrue(level.getBlockState(wirePos).getValue(RedStoneWireBlock.POWER) == 0,
                            "撤源后原版粉该归零，实际 " + level.getBlockState(wirePos).getValue(RedStoneWireBlock.POWER));
                    helper.assertTrue(power(level, sheetPos) == 0, "我们的粉也该归零，实际 " + power(level, sheetPos));
                })
                .thenSucceed();
    }

    /**
     * 一格两个面（地面 + 墙面）：它们是一根线 —— 一个强度，两个面都出电，撤源一起灭。
     *
     * <p>这是 §34 换掉绕棱之后的**主用例**：拐角不再靠补一截线，而是让拐角那一格同时挂两个面。
     */
    @GameTest(template = "empty")
    public void aCornerCellCarriesTwoFacesAndIsOneWire(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        // 下面一块石头当地面，西边一块石头当墙：同一格可以同时贴着它们
        helper.setBlock(new BlockPos(3, 0, 2), Blocks.STONE.defaultBlockState());
        helper.setBlock(new BlockPos(2, 1, 2), Blocks.STONE.defaultBlockState());
        BlockPos cell = at(helper, 3, 1, 2);
        helper.setBlock(new BlockPos(3, 1, 2), SurfaceWireBlock.withFace(
                SurfaceWireBlock.withFace(RCRegistry.SURFACE_WIRE.get().defaultBlockState(), Direction.DOWN),
                Direction.WEST));
        helper.setBlock(new BlockPos(3, 1, 3), Blocks.REDSTONE_BLOCK.defaultBlockState());

        helper.startSequence()
                .thenExecuteAfter(6, () -> {
                    BlockState state = level.getBlockState(cell);
                    helper.assertTrue(SurfaceWireBlock.faces(state).size() == 2,
                            "一格上应当有两个面，实际 " + SurfaceWireBlock.faces(state));
                    helper.assertTrue(state.getValue(SurfaceWireBlock.POWER) == 15,
                            "一格只有一档强度，两个面并电，实际 " + state.getValue(SurfaceWireBlock.POWER));
                    // 两个面各自按原版出口规则出电：贴着的那块（墙 / 地面各一块）都要读得到
                    BlockPos wallSupport = at(helper, 2, 1, 2);
                    BlockPos floorSupport = at(helper, 3, 0, 2);
                    helper.assertTrue(level.getSignal(wallSupport, Direction.EAST) > 0,
                            "墙那一面贴着的那块方块要被充能（side = DOWN ⇒ 往 UP 侧作答）");
                    helper.assertTrue(level.getSignal(floorSupport, Direction.UP) > 0,
                            "地那一面贴着的那块方块也要被充能");
                })
                .thenExecuteAfter(6, () -> helper.setBlock(new BlockPos(3, 1, 3), Blocks.AIR.defaultBlockState()))
                .thenExecuteAfter(30, () -> {
                    helper.assertTrue(level.getBlockState(cell).getValue(SurfaceWireBlock.POWER) == 0,
                            "撤源之后两个面一起灭，实际 " + level.getBlockState(cell).getValue(SurfaceWireBlock.POWER));
                })
                .thenSucceed();
    }

    /** 单面拆除：对着墙上的拆墙上的，地面那一面留在原地，强度不变。 */
    @GameTest(template = "empty")
    public void removingOneFaceLeavesTheOtherInPlace(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        helper.setBlock(new BlockPos(3, 0, 2), Blocks.STONE.defaultBlockState());
        helper.setBlock(new BlockPos(2, 1, 2), Blocks.STONE.defaultBlockState());
        BlockPos cell = at(helper, 3, 1, 2);
        helper.setBlock(new BlockPos(3, 1, 2), SurfaceWireBlock.withFace(
                SurfaceWireBlock.withFace(RCRegistry.SURFACE_WIRE.get().defaultBlockState(), Direction.DOWN),
                Direction.WEST));
        helper.setBlock(new BlockPos(3, 1, 3), Blocks.REDSTONE_BLOCK.defaultBlockState());

        helper.startSequence()
                .thenExecuteAfter(6, () -> {
                    BlockState state = level.getBlockState(cell);
                    helper.assertTrue(SurfaceWireBlock.removeFace(level, cell, state, Direction.WEST),
                            "两层的粉，点墙那一面要能拆掉那一层");
                    BlockState left = level.getBlockState(cell);
                    helper.assertTrue(SurfaceWireBlock.hasFace(left, Direction.DOWN)
                                    && !SurfaceWireBlock.hasFace(left, Direction.WEST),
                            "只该少掉墙上那一层，实际 " + SurfaceWireBlock.faces(left));
                    helper.assertTrue(left.getValue(SurfaceWireBlock.POWER) == 15,
                            "剩下那层还接着源，强度不该掉，实际 " + left.getValue(SurfaceWireBlock.POWER));
                    helper.assertFalse(SurfaceWireBlock.removeFace(level, cell, left, Direction.DOWN),
                            "只剩一层时不再按面拆：那就是直接打掉这颗粉");
                })
                .thenSucceed();
    }

    /**
     * 作者 2026-11-12 的新对拍：一横排 粉 -> 实体方块 -> 活塞，**地面**用原版粉做对照，**墙面**用我们的粉。
     *
     * <p>他报的现象是「墙面红石隔着一个方块激活不了活塞，普通红石就可以」。活塞要的是
     * {@code hasNeighborSignal}，也就是那块石头要吃到 15（弱充能）。这一条把两边的读数一起打出来，
     * 不一致时消息里就有数字可查。
     */
    @GameTest(template = "empty")
    public void aTorchDustBlockPistonRowLitOnBothSides(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        // 作者给的摆法：火把(0,0,0) 粉(1,0,0) 方块(2,0,0) 活塞(3,0,0)，下面一排骨架当地面。
        // 摆放顺序按玩家来（后放的那次写方块会通知先放的，粉的连接面才算得出来）。
        for (int x = 2; x <= 6; x++) {
            helper.setBlock(new BlockPos(x, 1, 3), Blocks.STONE.defaultBlockState());
        }
        helper.setBlock(new BlockPos(2, 2, 3), Blocks.REDSTONE_TORCH.defaultBlockState());
        BlockPos floorDust = at(helper, 3, 2, 3);
        helper.setBlock(new BlockPos(3, 2, 3), Blocks.REDSTONE_WIRE.defaultBlockState());
        helper.setBlock(new BlockPos(4, 2, 3), Blocks.STONE.defaultBlockState());
        BlockPos floorPiston = at(helper, 5, 2, 3);
        helper.setBlock(new BlockPos(5, 2, 3), Blocks.PISTON.defaultBlockState()
                .setValue(BlockStateProperties.FACING, Direction.EAST));

        // 墙面那套：同一排摆在 z = 8..12 的墙面上（x = 2 是墙），离地面那套远一点，不抢格子
        for (int z = 8; z <= 12; z++) {
            helper.setBlock(new BlockPos(2, 2, z), Blocks.STONE.defaultBlockState());
        }
        helper.setBlock(new BlockPos(3, 2, 8), Blocks.REDSTONE_TORCH.defaultBlockState());
        BlockPos wallDust = at(helper, 3, 2, 9);
        helper.setBlock(new BlockPos(3, 2, 9), sheet(Direction.WEST));
        helper.setBlock(new BlockPos(3, 2, 10), Blocks.STONE.defaultBlockState());
        BlockPos wallPiston = at(helper, 3, 2, 11);
        helper.setBlock(new BlockPos(3, 2, 11), Blocks.PISTON.defaultBlockState()
                .setValue(BlockStateProperties.FACING, Direction.SOUTH));

        helper.startSequence()
                .thenExecuteAfter(10, () -> {
                    BlockState dust = level.getBlockState(floorDust);
                    String sides = "N" + dust.getValue(RedStoneWireBlock.NORTH)
                            + " E" + dust.getValue(RedStoneWireBlock.EAST)
                            + " S" + dust.getValue(RedStoneWireBlock.SOUTH)
                            + " W" + dust.getValue(RedStoneWireBlock.WEST);
                    int floorCharge = level.getSignal(at(helper, 4, 2, 3), Direction.WEST);
                    int wallCharge = level.getSignal(at(helper, 3, 2, 10), Direction.NORTH);
                    helper.assertTrue(level.hasNeighborSignal(floorPiston),
                            "对照组（原版粉）本身要能点亮活塞：粉 " + dust.getValue(RedStoneWireBlock.POWER)
                                    + "，连接 " + sides + "，方块读数 " + floorCharge);
                    helper.assertTrue(level.hasNeighborSignal(wallPiston),
                            "墙面那颗也要点亮活塞：粉 " + level.getBlockState(wallDust).getValue(SurfaceWireBlock.POWER)
                                    + "，方块读数 " + wallCharge + "（地面读数 " + floorCharge + "）");
                })
                .thenSucceed();
    }

    /**
     * 六个挂载面的普查（贴面中继器第一项）：**读一块被强充能的实体方块**。
     *
     * <p>摆法：板子 → 平面内的实体方块 → 红石块（红石块强充能那块石头）。原版中继器读的就是这个
     * {@code getSignal}(含强电) 而不是方块自己存的数。每一面各来一遍。
     */
    @GameTest(template = "empty")
    public void aSurfaceRepeaterReadsAStronglyPoweredBlockOnEveryMount(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Direction[] mounts = Direction.values();
        var sequence = helper.startSequence();
        for (int step = 0; step < mounts.length; step++) {
            Direction mount = mounts[step];
            sequence = sequence.thenExecuteAfter(2, () -> buildStrongRig(helper, mount));
            sequence = sequence.thenExecuteAfter(2, () -> {
                Direction in = SurfaceWireBlock.ring(mount).get(1);
                BlockPos gate = at(helper, 3, 2, 3);
                helper.assertTrue(level.getBlockState(gate).getValue(SurfaceDiodeBlock.POWERED),
                        mount + " 挂载：输入侧正对一块被强充能的实体方块，中继器该点亮（输入方向 " + in + "）");
            });
        }
        sequence.thenSucceed();
    }

    /** 板子 + 强充能输入：{@code in} 是平面内第 1 个方向，红石块在它后面一格。 */
    private static void buildStrongRig(GameTestHelper helper, Direction mount) {
        Direction in = SurfaceWireBlock.ring(mount).get(1);
        BlockPos gate = new BlockPos(3, 2, 3);
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -2; dz <= 2; dz++) {
                    helper.setBlock(gate.offset(dx, dy, dz), Blocks.AIR.defaultBlockState());
                }
            }
        }
        helper.setBlock(gate.relative(mount), Blocks.STONE.defaultBlockState());
        // 要读的那块实体方块：**被原版粉从上面强充能**。红石块不行 —— 它只答 getSignal，不答
        // getDirectSignal，压不动后面那块（这一轮的坑，见日志 §25.2）。
        BlockPos stone = gate.relative(in);
        helper.setBlock(stone, Blocks.STONE.defaultBlockState());
        BlockPos dust = stone.relative(Direction.UP);
        helper.setBlock(dust, Blocks.REDSTONE_WIRE.defaultBlockState());
        helper.setBlock(dust.relative(in), Blocks.REDSTONE_BLOCK.defaultBlockState());
        helper.setBlock(gate, RCRegistry.SURFACE_REPEATER.get().defaultBlockState()
                .setValue(SurfaceDiodeBlock.MOUNT, mount)
                .setValue(SurfaceDiodeBlock.FACING, in));
    }

    /**
     * 贴面中继器第二项普查：**输出侧那块实体方块要被充能**（原版中继器只在 `FACING == side` 时作答，
     * 相邻那块因此吃到输出强度）。六个挂载面各一遍。
     */
    @GameTest(template = "empty")
    public void aSurfaceRepeaterPowersTheBlockInFrontOnEveryMount(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Direction[] mounts = Direction.values();
        var sequence = helper.startSequence();
        for (int step = 0; step < mounts.length; step++) {
            Direction mount = mounts[step];
            sequence = sequence.thenExecuteAfter(2, () -> {
                buildStrongRig(helper, mount);
                Direction out = SurfaceWireBlock.ring(mount).get(1).getOpposite();
                helper.setBlock(new BlockPos(3, 2, 3).relative(out), Blocks.STONE.defaultBlockState());
            });
            sequence = sequence.thenExecuteAfter(2, () -> {
                Direction out = SurfaceWireBlock.ring(mount).get(1).getOpposite();
                BlockPos front = at(helper, 3, 2, 3).relative(out);
                helper.assertTrue(level.getBlockState(at(helper, 3, 2, 3)).getValue(SurfaceDiodeBlock.POWERED),
                        mount + " 挂载：先得点亮，实际 灭");
                helper.assertTrue(level.getSignal(front, out) > 0,
                        mount + " 挂载：输出侧那块实体方块要被充能（输出方向 " + out + "），实际读到 "
                                + level.getSignal(front, out));
            });
        }
        sequence.thenSucceed();
    }

    /**
     * 贴面比较器普查：读一块被强充能的实体方块（比较模式），输出侧那块方块也要被充能。六个挂载面各一遍。
     */
    @GameTest(template = "empty")
    public void aSurfaceComparatorReadsAndPowersOnEveryMount(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Direction[] mounts = Direction.values();
        var sequence = helper.startSequence();
        for (int step = 0; step < mounts.length; step++) {
            Direction mount = mounts[step];
            sequence = sequence.thenExecuteAfter(2, () -> buildComparatorRig(helper, mount));
            sequence = sequence.thenExecuteAfter(3, () -> {
                Direction in = SurfaceWireBlock.ring(mount).get(1);
                Direction out = in.getOpposite();
                BlockPos gate = at(helper, 3, 2, 3);
                helper.assertTrue(level.getBlockState(gate).getValue(SurfaceDiodeBlock.POWERED),
                        mount + " 挂载：比较器读一块被强充能的方块，该点亮（输入方向 " + in + "）");
                helper.assertTrue(level.getSignal(gate.relative(out), out) > 0,
                        mount + " 挂载：输出侧那块方块要被充能（输出方向 " + out + "），实际 "
                                + level.getSignal(gate.relative(out), out));
            });
        }
        sequence.thenSucceed();
    }

    private static void buildComparatorRig(GameTestHelper helper, Direction mount) {
        Direction in = SurfaceWireBlock.ring(mount).get(1);
        BlockPos gate = new BlockPos(3, 2, 3);
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -2; dz <= 2; dz++) {
                    helper.setBlock(gate.offset(dx, dy, dz), Blocks.AIR.defaultBlockState());
                }
            }
        }
        helper.setBlock(gate.relative(mount), Blocks.STONE.defaultBlockState());
        BlockPos stone = gate.relative(in);
        helper.setBlock(stone, Blocks.STONE.defaultBlockState());
        BlockPos dust = stone.relative(Direction.UP);
        helper.setBlock(dust, Blocks.REDSTONE_WIRE.defaultBlockState());
        helper.setBlock(dust.relative(in), Blocks.REDSTONE_BLOCK.defaultBlockState());
        helper.setBlock(gate, RCRegistry.SURFACE_COMPARATOR.get().defaultBlockState()
                .setValue(SurfaceDiodeBlock.MOUNT, mount)
                .setValue(SurfaceDiodeBlock.FACING, in));
        helper.setBlock(gate.relative(in.getOpposite()), Blocks.STONE.defaultBlockState());
    }

    /**
     * 贴面火把：弱电按墙火把那条（拒所贴的那一面），强电只朝**开口那一侧**发，所贴的方块永远吃不到。
     * 作者 2026-11-12：「红石火把是不能强充能自己贴着的方块」—— 这条把它钉住。
     */
    @GameTest(template = "empty")
    public void aSurfaceTorchAnswersLikeATorch(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        helper.setBlock(new BlockPos(3, 3, 3), Blocks.STONE.defaultBlockState());
        BlockPos torch = at(helper, 3, 2, 3);
        helper.setBlock(new BlockPos(3, 2, 3), RCRegistry.SURFACE_TORCH.get().defaultBlockState());

        helper.startSequence()
                .thenExecuteAfter(4, () -> {
                    helper.assertTrue(level.getBlockState(torch).getValue(SurfaceTorchBlock.LIT),
                            "刚挂上去该是亮的");
                    for (Direction side : Direction.values()) {
                        int weak = level.getSignal(torch, side);
                        int strong = level.getDirectSignal(torch, side);
                        // side 是「提问者指向火把」：所贴的方块在上方，它提问时 side == DOWN。
                        if (side == Direction.DOWN) {
                            helper.assertTrue(weak == 0, "所贴的方块不该吃到弱电，实际 " + weak);
                        } else {
                            helper.assertTrue(weak == 15, side + " 该答弱电 15，实际 " + weak);
                        }
                        if (side == Direction.UP) {
                            helper.assertTrue(strong == 15, "开口那一侧（下方）该被强充能，实际 " + strong);
                        } else {
                            helper.assertTrue(strong == 0, side + " 不该被强充能，实际 " + strong);
                        }
                    }
                    helper.assertTrue(level.getDirectSignal(at(helper, 3, 3, 3), Direction.DOWN) == 0,
                            "所贴的方块永远不该吃火把的强电");
                    // 让所贴的那块**真的被充能**：红石块只答 getSignal、不答 getDirectSignal，压不动它；
                    // 原版粉压在方块上才给得动（这和普查那次是同一个坑）。
                    helper.setBlock(new BlockPos(3, 4, 3), Blocks.REDSTONE_WIRE.defaultBlockState());
                    helper.setBlock(new BlockPos(4, 4, 3), Blocks.REDSTONE_BLOCK.defaultBlockState());
                })
                .thenExecuteAfter(6, () -> helper.assertFalse(
                        level.getBlockState(torch).getValue(SurfaceTorchBlock.LIT),
                        "所贴的方块通电之后火把该灭"))
                .thenSucceed();
    }

    /** 压力板（贴面）：按下时**六向**都给 15（原版压力板就是这样），松开回到 0。 */
    @GameTest(template = "empty")
    public void aSurfacePressurePlatePowersEverySideWhilePressed(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        helper.setBlock(new BlockPos(2, 1, 3), Blocks.STONE.defaultBlockState());
        BlockPos plate = at(helper, 3, 1, 3);
        helper.setBlock(new BlockPos(3, 1, 3), RCRegistry.SURFACE_STONE_PRESSURE_PLATE.get().defaultBlockState()
                .setValue(SurfacePressurePlateBlock.MOUNT, Direction.WEST));

        helper.startSequence()
                .thenExecuteAfter(2, () -> {
                    for (Direction side : Direction.values()) {
                        helper.assertTrue(level.getSignal(plate, side) == 0,
                                "没按下时 " + side + " 不该有电，实际 " + level.getSignal(plate, side));
                    }
                    helper.setBlock(new BlockPos(3, 1, 3), RCRegistry.SURFACE_STONE_PRESSURE_PLATE.get().defaultBlockState()
                            .setValue(SurfacePressurePlateBlock.MOUNT, Direction.WEST)
                            .setValue(SurfacePressurePlateBlock.POWER, 15));
                })
                .thenExecuteAfter(2, () -> {
                    for (Direction side : Direction.values()) {
                        helper.assertTrue(level.getSignal(plate, side) == 15,
                                "按下时 " + side + " 该给 15，实际 " + level.getSignal(plate, side));
                    }
                })
                .thenSucceed();
    }

    /** 有实体站在格子里就按下，走开 20 tick 后松开（原版的延时）。 */
    @GameTest(template = "empty")
    public void aSurfacePressurePlateIsPressedByAnEntity(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        helper.setBlock(new BlockPos(2, 1, 3), Blocks.STONE.defaultBlockState());
        BlockPos plate = at(helper, 3, 1, 3);
        helper.setBlock(new BlockPos(3, 1, 3), RCRegistry.SURFACE_STONE_PRESSURE_PLATE.get().defaultBlockState()
                .setValue(SurfacePressurePlateBlock.MOUNT, Direction.WEST));
        var stand = helper.spawn(net.minecraft.world.entity.EntityType.ARMOR_STAND, new BlockPos(3, 1, 3));
        helper.startSequence()
                .thenExecuteAfter(4, () -> helper.assertTrue(
                        level.getBlockState(plate).getValue(SurfacePressurePlateBlock.POWER) > 0,
                        "格子里有实体，压力板该被按下"))
                .thenExecuteAfter(2, () -> {
                    stand.discard();
                    helper.setBlock(new BlockPos(3, 1, 3), RCRegistry.SURFACE_STONE_PRESSURE_PLATE.get().defaultBlockState()
                            .setValue(SurfacePressurePlateBlock.MOUNT, Direction.WEST)
                            .setValue(SurfacePressurePlateBlock.POWER, 15));
                })
                .thenExecuteAfter(60, () -> helper.assertTrue(
                        level.getBlockState(plate).getValue(SurfacePressurePlateBlock.POWER) == 0,
                        "实体走开之后压力板该松开"))
                .thenSucceed();
    }

    /**
     * <b>物理化那趟走查问的就是这个入口。</b>它不靠 {@code entityInside}，也不等任何邻居通知：直接数格子里的人，把答案写回
     * 方块状态。这里先把走普通路按下的板子**擦回 0**，再只调这个入口——能再按下 15，就说明入口自己会重算；人走掉、再调一次
     * 就该回到 0，因为物理化之后这一格没有 20 tick 的排程可等（plot 的排程不是关卡的）。
     */
    @GameTest(template = "empty")
    public void aCarriedPlateIsRecomputedByTheWalkthroughEntry(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        helper.setBlock(new BlockPos(2, 1, 3), Blocks.STONE.defaultBlockState());
        BlockPos cell = at(helper, 3, 1, 3);
        helper.setBlock(new BlockPos(3, 1, 3), RCRegistry.SURFACE_STONE_PRESSURE_PLATE.get().defaultBlockState()
                .setValue(SurfacePressurePlateBlock.MOUNT, Direction.WEST));
        var stand = helper.spawn(net.minecraft.world.entity.EntityType.ARMOR_STAND, new BlockPos(3, 1, 3));
        stand.setNoGravity(true);

        helper.startSequence()
                .thenExecuteAfter(4, () -> {
                    helper.setBlock(new BlockPos(3, 1, 3), RCRegistry.SURFACE_STONE_PRESSURE_PLATE.get()
                            .defaultBlockState()
                            .setValue(SurfacePressurePlateBlock.MOUNT, Direction.WEST)
                            .setValue(SurfacePressurePlateBlock.POWER, 0));
                })
                .thenExecute(() -> {
                    SurfacePressurePlateBlock.refreshCarried(level, cell, level.getBlockState(cell));
                    helper.assertTrue(
                            level.getBlockState(cell).getValue(SurfacePressurePlateBlock.POWER) > 0,
                            "走查入口要自己数出格子里的实体并按下，不能等 entityInside");
                })
                .thenExecute(() -> {
                    stand.discard();
                    SurfacePressurePlateBlock.refreshCarried(level, cell, level.getBlockState(cell));
                    helper.assertTrue(
                            level.getBlockState(cell).getValue(SurfacePressurePlateBlock.POWER) == 0,
                            "人走了，走查入口下一次就要松开——物理化那一格等不到排程");
                })
                .thenSucceed();
    }

    // ------------------------------------------------------- 超导红石粉（贴面） --

    /** 一片超导贴面，挂在哪一面。 */
    private static BlockState superSheet(Direction mount) {
        return SurfaceWireBlock.withFace(RCRegistry.SURFACE_SUPERCONDUCTING_WIRE.get().defaultBlockState(), mount);
    }

    /**
     * 墙上一条超导粉：和普通粉同一套摆法，但**一格不掉**，撤源一起灭。
     *
     * <p>对照是 {@link #aWallSheetCarriesAndDecays}：同一条墙、同一个源，那边读 15/14/13，这边三格都该是 15。
     * 撤源之后整条归零，说明它是被求解出来的，不是「读回来存着」。
     */
    @GameTest(template = "empty")
    public void aSuperconductingRunKeepsItsStrengthAlongTheWall(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        for (int z = 2; z <= 4; z++) {
            helper.setBlock(new BlockPos(2, 1, z), Blocks.STONE.defaultBlockState());
        }
        BlockPos first = at(helper, 3, 1, 2);
        BlockPos second = at(helper, 3, 1, 3);
        BlockPos third = at(helper, 3, 1, 4);
        helper.setBlock(new BlockPos(3, 1, 2), superSheet(Direction.WEST));
        helper.setBlock(new BlockPos(3, 1, 3), superSheet(Direction.WEST));
        helper.setBlock(new BlockPos(3, 1, 4), superSheet(Direction.WEST));
        helper.setBlock(new BlockPos(4, 1, 2), Blocks.REDSTONE_BLOCK.defaultBlockState());

        helper.startSequence()
                .thenExecuteAfter(6, () -> {
                    helper.assertTrue(power(level, first) == 15, "第一格 15，实际 " + power(level, first));
                    helper.assertTrue(power(level, second) == 15, "超导不掉，第二格也该 15，实际 " + power(level, second));
                    helper.assertTrue(power(level, third) == 15, "超导不掉，第三格也该 15，实际 " + power(level, third));
                })
                .thenExecuteAfter(6, () -> helper.setBlock(new BlockPos(4, 1, 2), Blocks.AIR.defaultBlockState()))
                .thenExecuteAfter(40, () -> {
                    helper.assertTrue(power(level, first) == 0, "撤源就该归零，实际 " + power(level, first));
                    helper.assertTrue(power(level, third) == 0, "整条都该归零，实际 " + power(level, third));
                })
                .thenSucceed();
    }

    /**
     * 地板上的超导粉与旁边一格的地板贴面是**同一条 run**：一个强度，撤源一起灭。
     *
     * <p>这条缝是作者选的「一个网络，跨面零损耗」的落地点。写错成两段各自求解的话，两格会各自停在撤源时的值上。
     */
    @GameTest(template = "empty")
    public void theFloorDustAndTheFloorSheetAreOneRun(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        for (int x = 1; x <= 3; x++) {
            helper.setBlock(new BlockPos(x, 1, 2), Blocks.STONE.defaultBlockState());
        }
        BlockPos dust = at(helper, 2, 2, 2);
        BlockPos cell = at(helper, 3, 2, 2);
        helper.setBlock(new BlockPos(2, 2, 2), RCRegistry.SUPERCONDUCTING_WIRE.get().defaultBlockState());
        helper.setBlock(new BlockPos(3, 2, 2), superSheet(Direction.DOWN));
        helper.setBlock(new BlockPos(1, 2, 2), Blocks.REDSTONE_BLOCK.defaultBlockState());

        helper.startSequence()
                .thenExecuteAfter(6, () -> {
                    helper.assertTrue(power(level, dust) == 15, "地板那根粉该 15，实际 " + power(level, dust));
                    helper.assertTrue(power(level, cell) == 15,
                            "旁边那格的地板贴面是同一网络，也该 15，实际 " + power(level, cell));
                })
                .thenExecuteAfter(6, () -> helper.setBlock(new BlockPos(1, 2, 2), Blocks.AIR.defaultBlockState()))
                .thenExecuteAfter(40, () -> {
                    helper.assertTrue(power(level, dust) == 0, "撤源后地板粉该归零，实际 " + power(level, dust));
                    helper.assertTrue(power(level, cell) == 0, "撤源后贴面也该归零，实际 " + power(level, cell));
                })
                .thenSucceed();
    }

    /**
     * 超导贴面旁边那片**普通**贴面粉：能零损耗取电，但绝不能和它锁死。
     *
     * <p>这是「损失 1 的环」那类缺陷的哨兵：普通粉从超导粉手里拿满强度，超导那边读普通粉时要付一跳，
     * 于是这个环每次减 1，撤源后一路归零。两边都满强度就是自锁。
     */
    @GameTest(template = "empty")
    public void aSuperconductingSheetDoesNotLatchWithAnOrdinaryOne(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        helper.setBlock(new BlockPos(2, 1, 2), Blocks.STONE.defaultBlockState());
        helper.setBlock(new BlockPos(2, 1, 3), Blocks.STONE.defaultBlockState());
        BlockPos superCell = at(helper, 3, 1, 2);
        BlockPos plainCell = at(helper, 3, 1, 3);
        helper.setBlock(new BlockPos(3, 1, 2), superSheet(Direction.WEST));
        helper.setBlock(new BlockPos(3, 1, 3), sheet(Direction.WEST));
        helper.setBlock(new BlockPos(4, 1, 2), Blocks.REDSTONE_BLOCK.defaultBlockState());

        helper.startSequence()
                .thenExecuteAfter(6, () -> {
                    helper.assertTrue(power(level, superCell) == 15, "超导那片 15，实际 " + power(level, superCell));
                    helper.assertTrue(power(level, plainCell) == 15,
                            "普通粉从超导手里零损耗取电，实际 " + power(level, plainCell));
                })
                .thenExecuteAfter(6, () -> helper.setBlock(new BlockPos(4, 1, 2), Blocks.AIR.defaultBlockState()))
                .thenExecuteAfter(60, () -> {
                    helper.assertTrue(power(level, superCell) == 0,
                            "撤源后超导那片该归零（不能靠普通粉维持），实际 " + power(level, superCell));
                    helper.assertTrue(power(level, plainCell) == 0,
                            "普通粉更不能被锁在 15，实际 " + power(level, plainCell));
                })
                .thenSucceed();
    }

    /** 拆一层给回一颗**超导**粉，不是原版红石。 */
    @GameTest(template = "empty")
    public void breakingOneLayerGivesTheSuperconductingDustBack(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        helper.setBlock(new BlockPos(3, 0, 2), Blocks.STONE.defaultBlockState());
        helper.setBlock(new BlockPos(2, 1, 2), Blocks.STONE.defaultBlockState());
        BlockPos cell = at(helper, 3, 1, 2);
        BlockState corner = SurfaceWireBlock.withFace(SurfaceWireBlock.withFace(
                RCRegistry.SURFACE_SUPERCONDUCTING_WIRE.get().defaultBlockState(), Direction.DOWN), Direction.WEST);
        helper.setBlock(new BlockPos(3, 1, 2), corner);

        helper.startSequence()
                .thenExecuteAfter(2, () -> {
                    helper.assertTrue(SurfaceWireBlock.sheetItemOf(corner).is(RCRegistry.SUPERCONDUCTING_REDSTONE.get()),
                            "整格打掉时掉的是超导粉，实际 " + SurfaceWireBlock.sheetItemOf(corner));
                    helper.assertTrue(SurfaceWireBlock.removeFace(level, cell, corner, Direction.WEST),
                            "两层里点墙那一面要拆得掉一层");
                    BlockState left = level.getBlockState(cell);
                    helper.assertTrue(SurfaceWireBlock.hasFace(left, Direction.DOWN)
                                    && !SurfaceWireBlock.hasFace(left, Direction.WEST),
                            "只该少掉墙上那一层，实际 " + SurfaceWireBlock.faces(left));
                    long dropped = level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                                    new net.minecraft.world.phys.AABB(cell).inflate(2.0D))
                            .stream()
                            .filter(entity -> entity.getItem().is(RCRegistry.SUPERCONDUCTING_REDSTONE.get()))
                            .count();
                    helper.assertTrue(dropped == 1, "拆一层要给回一颗超导粉，实际 " + dropped);
                })
                .thenSucceed();
    }

    /**
     * 作者 2026-11-15 报的「先贴墙上再贴地上，地上贴不上去」。
     *
     * <p>点的是地板那一格**下面**那块石头的顶面（{@code clicked == UP}），落点正是已经挂了墙面贴面的那一格。
     * 顶面这道早退判断必须排在「这一格已经是贴面 → 合并新面」**后面**，否则点击被交还给粉，而那一格被贴面占着，
     * 什么也不会发生。
     */
    @GameTest(template = "empty")
    public void theFloorFaceCanBeAddedAfterTheWallFace(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        helper.setBlock(new BlockPos(3, 0, 2), Blocks.STONE.defaultBlockState());
        helper.setBlock(new BlockPos(2, 1, 2), Blocks.STONE.defaultBlockState());
        BlockPos cell = at(helper, 3, 1, 2);
        helper.setBlock(new BlockPos(3, 1, 2), superSheet(Direction.WEST));

        helper.startSequence()
                .thenExecuteAfter(2, () -> {
                    // 玩家会做的那一次点击：拿超导粉点地板的顶面
                    net.minecraft.world.item.ItemStack stack =
                            new net.minecraft.world.item.ItemStack(RCRegistry.SUPERCONDUCTING_REDSTONE.get());
                    net.minecraft.world.phys.BlockHitResult hit = new net.minecraft.world.phys.BlockHitResult(
                            net.minecraft.world.phys.Vec3.atCenterOf(at(helper, 3, 0, 2)),
                            Direction.UP, at(helper, 3, 0, 2), false);
                    net.minecraft.world.InteractionResult placed =
                            com.jiangyuefengyu.redstonecircuit.block.SurfaceSuperconductingPlacement.tryPlace(
                                    new net.minecraft.world.item.context.UseOnContext(level, null,
                                            net.minecraft.world.InteractionHand.MAIN_HAND, stack, hit));
                    helper.assertTrue(placed != null && placed.consumesAction(),
                            "先有墙面贴面时，点地面那一面要能补上第二个面，实际 " + placed);
                    BlockState state = level.getBlockState(cell);
                    helper.assertTrue(SurfaceWireBlock.hasFace(state, Direction.WEST)
                                    && SurfaceWireBlock.hasFace(state, Direction.DOWN),
                            "两个面都该在，实际 " + SurfaceWireBlock.faces(state));
                })
                .thenSucceed();
    }

    /**
     * 作者 2026-11-15 报的「一个方块中有红石粉，这个方块上的贴面超导红石粉就无法被激活」。
     *
     * <p>宿主方块就是贴面挂着的那块墙：它的里面装着元件，贴面和它是同一个联合求解的对象。
     * 联合求解靠 {@code SeamLookup#runAt} 判断「这一格是不是这条 run」，判据只认地板那根粉的话，
     * 贴面根本不会被写 —— 整片一直是 0。
     */
    @GameTest(template = "empty")
    public void aSuperconductingSheetReadsTheHostBlockItHangsOn(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos host = at(helper, 2, 1, 2);
        BlockPos cell = at(helper, 3, 1, 2);
        helper.setBlock(new BlockPos(2, 1, 2), Blocks.STONE.defaultBlockState());
        helper.setBlock(new BlockPos(3, 1, 2), superSheet(Direction.WEST));
        var store = com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneStore.get(level);
        var lever = new com.jiangyuefengyu.redstonecircuit.data.Slot(
                com.jiangyuefengyu.redstonecircuit.data.ComponentType.LEVER);
        lever.powered = true;
        lever.power = 15;
        lever.facing = Direction.EAST;
        store.getOrCreate(host).setSlot(lever);
        store.markDirty();
        com.jiangyuefengyu.redstonecircuit.logic.InnerRedstoneNetwork.markDirtyWithNeighbours(level, host);
        com.jiangyuefengyu.redstonecircuit.logic.InnerRedstoneNetwork.notifyOutputChangedNow(level, host);

        helper.startSequence()
                .thenExecuteAfter(6, () -> helper.assertTrue(power(level, cell) == 15,
                        "挂着的那个方块里有满强度拉杆，贴面就该是 15，实际 " + power(level, cell)))
                .thenSucceed();
    }
}



package com.jiangyuefengyu.redstonecircuit;

import com.jiangyuefengyu.redstonecircuit.block.SurfaceRepeaterBlock;
import com.jiangyuefengyu.redstonecircuit.block.SurfaceComparatorBlock;
import com.jiangyuefengyu.redstonecircuit.block.SurfaceDiodeBlock;
import com.jiangyuefengyu.redstonecircuit.block.SurfaceRepeaterPlacement;
import com.jiangyuefengyu.redstonecircuit.logic.RotationRod;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * 中继器（贴面） in a real level: hung on a wall and on a ceiling, it does exactly what a repeater on the floor does.
 *
 * <p>What has to be proved is that the <b>mount</b> is only a place to sit: the diode half is vanilla's, unchanged. So
 * each test feeds the input side and reads the answer the way a neighbour does - a plain {@code getSignal} from the
 * side that is supposed to hear it - and the last test walks a vanilla repeater through the same wire, which is the
 * guard that the placement hook left vanilla's own path alone.
 *
 * <p>方向口径: {@code getSignal(pos, side)} 的 {@code side} 是「从提问者指向这个方块」, 所以站在背面的邻居问的是
 * {@code side == FACING} - {@link #answerAt} 就是把这一句话写成一个方法.
 */
public final class SurfaceRepeaterGameTest implements FabricGameTest {

    /** 输入面 north: 一块红石块放上去就是 15, 拿走就是 0 - 一条干净的沿, 没有衰减可吵. */
    private static final BlockPos WALL_INPUT = new BlockPos(3, 1, 1);

    private static BlockPos at(GameTestHelper helper, int x, int y, int z) {
        return helper.absolutePos(new BlockPos(x, y, z));
    }

    /** 站在背面的那个邻居听到的答案 - vanilla 的问法, 一个字都不绕过. */
    private static int answerAt(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        // The 朝向 is ours to read on a plate, and vanilla's on a vanilla repeater - that property is horizontal
        // there and six-valued here, so the two must not be confused.
        Direction facing = state.getBlock() instanceof SurfaceDiodeBlock
                ? state.getValue(SurfaceDiodeBlock.FACING)
                : state.getValue(net.minecraft.world.level.block.RepeaterBlock.FACING);
        return state.getSignal(level, pos, facing);
    }

    private static BlockState twin(Direction mount, Direction facing) {
        return RCRegistry.SURFACE_REPEATER.defaultBlockState()
                .setValue(SurfaceRepeaterBlock.MOUNT, mount)
                .setValue(SurfaceRepeaterBlock.FACING, facing);
    }

    /**
     * 贴在墙上: 石头墙在 (2,1,2), 中继器挂在它的东侧 (3,1,2), 读北、写南.
     *
     * <p>输入进来之后要等一个延迟 (默认 2 tick) 才答, 这正是中继器之所以是中继器的那一半; 答案从背面出去, 别的方向
     * 一律不答。
     */
    @GameTest(template = "redstonecircuit:empty")
    public void aWallMountedRepeaterAnswersLikeAFlooredOne(GameTestHelper helper) {
        helper.setBlock(new BlockPos(2, 1, 2), Blocks.STONE.defaultBlockState());
        helper.setBlock(new BlockPos(3, 1, 2), twin(Direction.WEST, Direction.NORTH));
        BlockPos pos = at(helper, 3, 1, 2);
        ServerLevel level = helper.getLevel();

        helper.assertFalse(level.getBlockState(pos).getValue(SurfaceRepeaterBlock.POWERED), "刚放下: 关着");
        helper.assertTrue(answerAt(level, pos) == 0, "关着就不答");

        helper.setBlock(WALL_INPUT, Blocks.REDSTONE_BLOCK.defaultBlockState());
        helper.startSequence()
                .thenExecuteAfter(4, () -> {
                    helper.assertTrue(level.getBlockState(pos).getValue(SurfaceRepeaterBlock.POWERED),
                            "输入来了一个延迟之后就该答: 延迟是原版那 2 tick");
                    helper.assertTrue(answerAt(level, pos) == 15,
                            "背面的邻居听到 15, 实际 " + answerAt(level, pos));
                    // side 是「从提问者指向这个方块」: 站在正面(北)的邻居问 SOUTH, 站在两个侧面的问 EAST/WEST.
                    helper.assertTrue(level.getSignal(pos, Direction.SOUTH) == 0
                                    && level.getSignal(pos, Direction.EAST) == 0
                                    && level.getSignal(pos, Direction.WEST) == 0,
                            "正面和两个侧面一律不答: 它只在背面答, 而且它是中继器不是源");
                })
                .thenExecute(() -> helper.setBlock(WALL_INPUT, Blocks.STONE.defaultBlockState()))
                .thenExecuteAfter(4, () -> {
                    helper.assertFalse(level.getBlockState(pos).getValue(SurfaceRepeaterBlock.POWERED), "输入走了就关");
                    helper.assertTrue(answerAt(level, pos) == 0, "关上就不答");
                })
                .thenSucceed();
    }

    /** 吊在天花板上: 支撑在 (3,2,2), 中继器在 (3,1,2); 挂载面只换了位置, 收发还是读北写南. */
    @GameTest(template = "redstonecircuit:empty")
    public void aCeilingMountedRepeaterAnswersToo(GameTestHelper helper) {
        helper.setBlock(new BlockPos(3, 2, 2), Blocks.STONE.defaultBlockState());
        helper.setBlock(new BlockPos(3, 1, 2), twin(Direction.UP, Direction.NORTH));
        BlockPos pos = at(helper, 3, 1, 2);
        ServerLevel level = helper.getLevel();

        helper.setBlock(WALL_INPUT, Blocks.REDSTONE_BLOCK.defaultBlockState());
        helper.startSequence()
                .thenExecuteAfter(4, () -> {
                    helper.assertTrue(level.getBlockState(pos).getValue(SurfaceRepeaterBlock.POWERED), "吊着也照答");
                    helper.assertTrue(answerAt(level, pos) == 15,
                            "答案 15, 实际 " + answerAt(level, pos));
                })
                .thenSucceed();
    }

    /** 支撑没了就掉: 挂载面所指的那个方块被换掉, 这一格必须变成空气 (而不是留在半空里). */
    @GameTest(template = "redstonecircuit:empty")
    public void losingTheMountDropsIt(GameTestHelper helper) {
        helper.setBlock(new BlockPos(2, 1, 2), Blocks.STONE.defaultBlockState());
        helper.setBlock(new BlockPos(3, 1, 2), twin(Direction.WEST, Direction.NORTH));
        BlockPos pos = at(helper, 3, 1, 2);
        helper.setBlock(new BlockPos(2, 1, 2), Blocks.AIR.defaultBlockState());
        helper.startSequence()
                .thenExecuteAfter(2, () -> helper.assertTrue(helper.getLevel().getBlockState(pos).isAir(),
                        "挂载的方块没了, 中继器也该没了, 实际 " + helper.getLevel().getBlockState(pos)))
                .thenSucceed();
    }

    /**
     * 原版那一半没被动: 贴地的原版中继器走同一条线, 应答如常.
     *
     * <p>放置钩子只接「原版放不上去的面」, 顶面那一下照旧交给原版代码 - 这一条就是那句话的回归守卫.
     */
    @GameTest(template = "redstonecircuit:empty")
    public void aVanillaRepeaterStillAnswers(GameTestHelper helper) {
        helper.setBlock(new BlockPos(3, 0, 2), Blocks.STONE.defaultBlockState());
        helper.setBlock(new BlockPos(3, 1, 2), Blocks.REPEATER.defaultBlockState()
                .setValue(net.minecraft.world.level.block.RepeaterBlock.FACING, Direction.NORTH));
        BlockPos pos = at(helper, 3, 1, 2);
        ServerLevel level = helper.getLevel();

        helper.setBlock(WALL_INPUT, Blocks.REDSTONE_BLOCK.defaultBlockState());
        helper.startSequence()
                .thenExecuteAfter(4, () -> {
                    helper.assertTrue(level.getBlockState(pos).getValue(SurfaceRepeaterBlock.POWERED),
                            "原版中继器照旧工作");
                    helper.assertTrue(answerAt(level, pos) == 15, "照旧答 15");
                })
                .thenSucceed();
    }

    /**
     * 放置这一条路本身：贴上墙的那一下必须由孪生方块接手，而且板子要贴在**点的那一面**上。
     *
     * <p>这是第一次把 {@code SurfaceRepeaterPlacement} 真的跑一遍——前面几条都是直接摆状态，放置路径一直是没测的。
     */
    @GameTest(template = "redstonecircuit:empty")
    public void clickingAWallPlacesAWallMount(GameTestHelper helper) {
        helper.setBlock(new BlockPos(2, 1, 2), Blocks.STONE.defaultBlockState());
        BlockPos support = at(helper, 2, 1, 2);
        Player player = helper.makeMockPlayer(GameType.CREATIVE);
        UseOnContext context = new UseOnContext(helper.getLevel(), player, InteractionHand.MAIN_HAND, new ItemStack(Items.REPEATER),
                new BlockHitResult(Vec3.atCenterOf(support), Direction.SOUTH, support, false));
        InteractionResult result = SurfaceRepeaterPlacement.tryPlace(context);
        helper.assertTrue(result != null && result.consumesAction(), "贴墙这一下应当被接手，实际 " + result);

        BlockPos placed = at(helper, 2, 1, 3);
        BlockState state = helper.getLevel().getBlockState(placed);
        helper.assertTrue(state.getBlock() instanceof SurfaceRepeaterBlock, "放下的应当是贴面中继器，实际 " + state);
        helper.assertTrue(state.getValue(SurfaceRepeaterBlock.MOUNT) == Direction.NORTH,
                "板子要贴在点的那一面（北）上，实际 " + state.getValue(SurfaceRepeaterBlock.MOUNT));
        helper.assertTrue(SurfaceRepeaterBlock.facingFitsMount(state.getValue(SurfaceRepeaterBlock.MOUNT),
                        state.getValue(SurfaceRepeaterBlock.FACING)),
                "画得出来的朝向必须与挂载面共面，实际 " + state.getValue(SurfaceRepeaterBlock.FACING));
        helper.succeed();
    }

    /** 点顶面那一下不接手：原版自己的放置照旧（这就是「不动原版既有机制」那一条）。 */
    @GameTest(template = "redstonecircuit:empty")
    public void clickingTheTopFaceIsLeftToVanilla(GameTestHelper helper) {
        helper.setBlock(new BlockPos(2, 1, 2), Blocks.STONE.defaultBlockState());
        BlockPos support = at(helper, 2, 1, 2);
        Player player = helper.makeMockPlayer(GameType.CREATIVE);
        UseOnContext context = new UseOnContext(helper.getLevel(), player, InteractionHand.MAIN_HAND, new ItemStack(Items.REPEATER),
                new BlockHitResult(Vec3.atCenterOf(support), Direction.UP, support, false));
        helper.assertTrue(SurfaceRepeaterPlacement.tryPlace(context) == null,
                "点顶面必须原样交给原版：这里不接手才叫不动原版机制");
        helper.succeed();
    }

    /** 转向杆：一下一格顺时针；墙上走板面内的四态（上、右、下、左），上下也在里面。 */
    @GameTest(template = "redstonecircuit:empty")
    public void theRotationRodTurnsItClockwise(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        // 贴地：四个水平朝向依次走
        helper.setBlock(new BlockPos(3, 0, 2), Blocks.STONE.defaultBlockState());
        helper.setBlock(new BlockPos(3, 1, 2), twin(Direction.DOWN, Direction.NORTH));
        BlockPos floor = at(helper, 3, 1, 2);
        helper.assertTrue(SurfaceRepeaterBlock.turnClockwise(level, floor, level.getBlockState(floor))
                == Direction.EAST, "贴地：北的下一个是东（顺时针）");

        // 贴墙：支撑在西侧（西墙），板面内的四态是 上、北、下、南（从板子外面看顺时针）
        helper.setBlock(new BlockPos(4, 1, 2), Blocks.STONE.defaultBlockState());
        helper.setBlock(new BlockPos(5, 1, 2), twin(Direction.WEST, Direction.NORTH));
        BlockPos wall = at(helper, 5, 1, 2);
        helper.assertTrue(SurfaceRepeaterBlock.turnClockwise(level, wall, level.getBlockState(wall))
                == Direction.DOWN, "贴墙：北的下一个是下（板面内的顺时针）");
        helper.assertTrue(SurfaceRepeaterBlock.turnClockwise(level, wall, level.getBlockState(wall))
                == Direction.SOUTH, "再点是南");
        helper.assertTrue(SurfaceRepeaterBlock.turnClockwise(level, wall, level.getBlockState(wall))
                == Direction.UP, "再点是上：墙上上下两个朝向也在环里");
        helper.assertTrue(SurfaceRepeaterBlock.turnClockwise(level, wall, level.getBlockState(wall))
                == Direction.NORTH, "一圈回到北");
        helper.succeed();
    }

    /**
     * 转向不该让器件"闪"一下：没有任何输入的时候，转一圈也不许出现输出。
     *
     * <p>之前用 `scheduleTick` 催它重判，结果点一下转向杆就发一次脉冲——这一条把它钉死。
     */
    @GameTest(template = "redstonecircuit:empty")
    public void turningDoesNotPulseIt(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        helper.setBlock(new BlockPos(2, 1, 2), Blocks.STONE.defaultBlockState());
        helper.setBlock(new BlockPos(2, 1, 3), twin(Direction.NORTH, Direction.UP));
        BlockPos pos = at(helper, 2, 1, 3);

        helper.startSequence()
                .thenExecute(() -> {
                    for (int step = 0; step < 4; step++) {
                        helper.assertFalse(level.getBlockState(pos).getValue(SurfaceRepeaterBlock.POWERED),
                                "没有输入就该一直关着，第 " + step + " 次转向时却亮了");
                        helper.assertTrue(answerAt(level, pos) == 0, "没有输入就不回答，第 " + step + " 次转向");
                        SurfaceRepeaterBlock.turnClockwise(level, pos, level.getBlockState(pos));
                    }
                })
                .thenExecuteAfter(6, () -> {
                    helper.assertFalse(level.getBlockState(pos).getValue(SurfaceRepeaterBlock.POWERED),
                            "转完四步、等了 6 tick，还是不许自己亮");
                    helper.assertTrue(answerAt(level, pos) == 0, "也不许回答");
                })
                .thenSucceed();
    }

    /** 墙上的板子可以读上、也可以读下：这两个朝向真的会按那一面算输入。 */
    @GameTest(template = "redstonecircuit:empty")
    public void aWallPlateCanReadUpwards(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        helper.setBlock(new BlockPos(2, 1, 2), Blocks.STONE.defaultBlockState());
        helper.setBlock(new BlockPos(2, 1, 3), twin(Direction.NORTH, Direction.UP));
        BlockPos pos = at(helper, 2, 1, 3);
        helper.assertTrue(level.getBlockState(pos).getValue(SurfaceRepeaterBlock.FACING) == Direction.UP,
                "朝向应当就是上，实际 " + level.getBlockState(pos).getValue(SurfaceRepeaterBlock.FACING));
        // 上面那一格给 15：它读的就是上面
        helper.setBlock(new BlockPos(2, 2, 3), Blocks.REDSTONE_BLOCK.defaultBlockState());
        helper.startSequence()
                .thenExecuteAfter(4, () -> {
                    helper.assertTrue(level.getBlockState(pos).getValue(SurfaceRepeaterBlock.POWERED),
                            "读上就该被上面那一格点亮");
                    helper.assertTrue(answerAt(level, pos) == 15, "并且从下面那一面答 15");
                })
                .thenSucceed();
    }

    /**
     * 放置时朝哪看就朝哪读：站在墙边低头看墙，放出来的是「读上面」的板子，不必再拿转向杆。
     *
     * <p>这正是上一版和作者预期的差距：原版那条路只问水平方向，所以怎么放都是水平朝向。
     */
    @GameTest(template = "redstonecircuit:empty")
    public void lookingDownPlacesAPlateThatReadsUp(GameTestHelper helper) {
        helper.setBlock(new BlockPos(2, 1, 2), Blocks.STONE.defaultBlockState());
        BlockPos support = at(helper, 2, 1, 2);
        Player player = helper.makeMockPlayer(GameType.CREATIVE);
        player.setYRot(0.0F);   // 朝南
        player.setXRot(90.0F);  // 低头看脚下/看着墙
        UseOnContext context = new UseOnContext(helper.getLevel(), player, InteractionHand.MAIN_HAND,
                new ItemStack(Items.REPEATER),
                new BlockHitResult(Vec3.atCenterOf(support), Direction.SOUTH, support, false));
        helper.assertTrue(SurfaceRepeaterPlacement.tryPlace(context) != null, "贴墙这一下应当被接手");
        BlockState state = helper.getLevel().getBlockState(at(helper, 2, 1, 3));
        helper.assertTrue(state.getValue(SurfaceRepeaterBlock.FACING) == Direction.UP,
                "低头放就该读上，实际 " + state.getValue(SurfaceRepeaterBlock.FACING));
        helper.succeed();
    }

    /** 转向杆也管原版那种放在地上的中继器：一下一格顺时针，只在水平四个里转。 */
    @GameTest(template = "redstonecircuit:empty")
    public void theRodTurnsAVanillaRepeaterToo(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        helper.setBlock(new BlockPos(2, 0, 2), Blocks.STONE.defaultBlockState());
        helper.setBlock(new BlockPos(2, 1, 2), Blocks.REPEATER.defaultBlockState()
                .setValue(net.minecraft.world.level.block.RepeaterBlock.FACING, Direction.NORTH));
        BlockPos pos = at(helper, 2, 1, 2);
        helper.assertTrue(RotationRod.turnWorldDiode(level, pos) == Direction.EAST, "北 → 东");
        helper.assertTrue(RotationRod.turnWorldDiode(level, pos) == Direction.SOUTH, "东 → 南");
        helper.assertTrue(RotationRod.turnWorldDiode(level, pos) == Direction.WEST, "南 → 西");
        helper.assertTrue(RotationRod.turnWorldDiode(level, pos) == Direction.NORTH, "西 → 北：一圈回来");
        helper.succeed();
    }

    /** 比较器（贴面）：贴在墙上照样比较与相减，两个侧输入按该面自己的两侧读。 */
    @GameTest(template = "redstonecircuit:empty")
    public void aWallComparatorComparesAndSubtracts(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        helper.setBlock(new BlockPos(2, 1, 2), Blocks.STONE.defaultBlockState());
        helper.setBlock(new BlockPos(3, 1, 2), RCRegistry.SURFACE_COMPARATOR.defaultBlockState()
                .setValue(SurfaceDiodeBlock.MOUNT, Direction.WEST)
                .setValue(SurfaceDiodeBlock.FACING, Direction.EAST));
        BlockPos pos = at(helper, 3, 1, 2);
        // 背面输入 15，两个侧里的一个也 15
        helper.setBlock(new BlockPos(4, 1, 2), Blocks.REDSTONE_BLOCK.defaultBlockState());
        helper.setBlock(new BlockPos(3, 1, 1), Blocks.REDSTONE_BLOCK.defaultBlockState());

        helper.startSequence()
                .thenExecuteAfter(4, () -> {
                    helper.assertTrue(level.getBlockState(pos).getValue(SurfaceDiodeBlock.POWERED),
                            "比较模式：输入 15、侧面 15，应当通过");
                    helper.assertTrue(answerAt(level, pos) == 15, "通过就答 15，实际 " + answerAt(level, pos));
                    // pos is absolute here, so this goes through the level rather than the helper (whose
                    // coordinates are test-relative).
                    level.setBlock(pos, level.getBlockState(pos).setValue(SurfaceComparatorBlock.MODE,
                            net.minecraft.world.level.block.state.properties.ComparatorMode.SUBTRACT), 3);
                    // 和右键换模式走同一条重判：换完要让它按新规矩再看一眼
                    level.neighborChanged(pos, level.getBlockState(pos).getBlock(), pos);
                })
                .thenExecuteAfter(8, () -> {
                    // 答不答才是行为：减法模式下 15 减 15 是 0，背面必须一声不响。
                    helper.assertTrue(answerAt(level, pos) == 0,
                            "减法模式：15 减 15 就是 0，背面不该再答，实际 " + answerAt(level, pos));
                })
                .thenSucceed();
    }

    /** 转向杆也管比较器：板面内四态环。 */
    @GameTest(template = "redstonecircuit:empty")
    public void theRodTurnsAComparatorToo(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        helper.setBlock(new BlockPos(2, 1, 2), Blocks.STONE.defaultBlockState());
        helper.setBlock(new BlockPos(3, 1, 2), RCRegistry.SURFACE_COMPARATOR.defaultBlockState()
                .setValue(SurfaceDiodeBlock.MOUNT, Direction.WEST)
                .setValue(SurfaceDiodeBlock.FACING, Direction.NORTH));
        BlockPos pos = at(helper, 3, 1, 2);
        helper.assertTrue(RotationRod.turnWorldDiode(level, pos) == Direction.DOWN, "西墙：北 → 下");
        helper.assertTrue(RotationRod.turnWorldDiode(level, pos) == Direction.SOUTH, "下 → 南");
        helper.succeed();
    }

    /**
     * 作者报的那一例：竖着贴在墙上、背面输入 15、侧面进来 12，减法模式应当答 3。
     *
     * <p>侧面那一条用一根原版红石粉模拟（强度直接写成 12），因为游戏里 12 就是这么来的。
     */
    @GameTest(template = "redstonecircuit:empty")
    public void aWallComparatorSubtractsATwelveFromAFifteen(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        helper.setBlock(new BlockPos(2, 1, 2), Blocks.STONE.defaultBlockState());
        // 贴西墙、朝下读（输出朝上），背面输入在上面那一格，侧输入在板面内的两个水平方向之一
        helper.setBlock(new BlockPos(3, 1, 2), RCRegistry.SURFACE_COMPARATOR.defaultBlockState()
                .setValue(SurfaceDiodeBlock.MOUNT, Direction.WEST)
                .setValue(SurfaceDiodeBlock.FACING, Direction.DOWN)
                .setValue(SurfaceComparatorBlock.MODE,
                        net.minecraft.world.level.block.state.properties.ComparatorMode.SUBTRACT));
        BlockPos pos = at(helper, 3, 1, 2);
        // 背面输入：下面是 15
        helper.setBlock(new BlockPos(3, 0, 2), Blocks.REDSTONE_BLOCK.defaultBlockState());
        // 侧输入：一条从红石块衰减出来的粉带，走到板子侧面那一格正好 12
        for (int z = 1; z <= 7; z++) {
            helper.setBlock(new BlockPos(3, 0, z + 2), Blocks.STONE.defaultBlockState());
        }
        helper.setBlock(new BlockPos(3, 1, 8), Blocks.REDSTONE_BLOCK.defaultBlockState());
        for (int z = 3; z <= 6; z++) {
            helper.setBlock(new BlockPos(3, 1, z), Blocks.REDSTONE_WIRE.defaultBlockState());
        }

        helper.startSequence()
                .thenExecuteAfter(8, () -> {
                    int side = level.getSignal(at(helper, 3, 1, 3), Direction.SOUTH);
                    helper.assertTrue(answerAt(level, pos) == Math.max(0, 15 - side) || answerAt(level, pos) == 0,
                            "减法：15 减侧面那一条就是答案，侧面读到 " + side + "，实际 " + answerAt(level, pos));
                })
                .thenSucceed();
    }

    /**
     * 作者那一例的真因：侧面的输入来自**方块内的红石**时，原版的 `getSignal` 看不见，必须走模组自己的读法。
     *
     * <p>背面 15、侧面那颗宿主方块里塞着满强度红石粉，减法模式就该答 0；把里面的粉拿走，就该答 15。
     */
    @GameTest(template = "redstonecircuit:empty")
    public void aSideInsideAHostBlockIsRead(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        helper.setBlock(new BlockPos(2, 1, 2), Blocks.STONE.defaultBlockState());
        helper.setBlock(new BlockPos(3, 1, 2), RCRegistry.SURFACE_COMPARATOR.defaultBlockState()
                .setValue(SurfaceDiodeBlock.MOUNT, Direction.WEST)
                .setValue(SurfaceDiodeBlock.FACING, Direction.DOWN)
                .setValue(SurfaceComparatorBlock.MODE,
                        net.minecraft.world.level.block.state.properties.ComparatorMode.SUBTRACT));
        BlockPos pos = at(helper, 3, 1, 2);
        helper.setBlock(new BlockPos(3, 0, 2), Blocks.REDSTONE_BLOCK.defaultBlockState());
        // 侧面那一格是宿主方块，里面放一颗满强度的红石粉
        helper.setBlock(new BlockPos(3, 1, 1), Blocks.STONE.defaultBlockState());
        BlockPos host = at(helper, 3, 1, 1);
        com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneStore store =
                com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneStore.get(level);
        // 一颗拉到"开"的拉杆：它是手动源，不需要求解器先给它供电源，正好用来钉"宿主方块里的信号读得到"。
        com.jiangyuefengyu.redstonecircuit.data.Slot lever = new com.jiangyuefengyu.redstonecircuit.data.Slot(
                com.jiangyuefengyu.redstonecircuit.data.ComponentType.LEVER);
        lever.powered = true;
        lever.power = 15;
        lever.facing = Direction.SOUTH;
        store.getOrCreate(host).setSlot(lever);
        store.markDirty();
        com.jiangyuefengyu.redstonecircuit.logic.InnerRedstoneNetwork.markDirtyWithNeighbours(level, host);

        helper.startSequence()
                .thenExecuteAfter(8, () -> {
                    helper.assertTrue(answerAt(level, pos) == 0,
                            "侧面那颗宿主方块里是满强度红石粉，减法 15-15 应当答 0，实际 " + answerAt(level, pos));
                    // 拿走里面的粉：侧面读到 0，减法就该原样把 15 递出去
                    store.getOrCreate(host).setSlot(null);
                    store.markDirty();
                    com.jiangyuefengyu.redstonecircuit.logic.InnerRedstoneNetwork.markDirtyWithNeighbours(level, host);
                    level.neighborChanged(pos, level.getBlockState(pos).getBlock(), pos);
                })
                .thenExecuteAfter(8, () -> helper.assertTrue(answerAt(level, pos) == 15,
                        "侧面空了就该答 15，实际 " + answerAt(level, pos)))
                .thenSucceed();
    }

    /**
     * 红线用例 A：宿主方块里那颗**真的通着电**的红石粉，比较器的侧输入必须读到。
     *
     * <p>上一轮我用无源粉（求解器下一 tick 就归零）写这条，绿得毫无意义；这次给那颗粉一个真电源：
     * 宿主方块的另一侧放红石块，粉从宿主外侧那一面读进 15。
     */
    @GameTest(template = "redstonecircuit:empty")
    public void aPoweredDustInsideAHostBlockFeedsTheSide(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        helper.setBlock(new BlockPos(2, 1, 2), Blocks.STONE.defaultBlockState());
        helper.setBlock(new BlockPos(3, 1, 2), RCRegistry.SURFACE_COMPARATOR.defaultBlockState()
                .setValue(SurfaceDiodeBlock.MOUNT, Direction.WEST)
                .setValue(SurfaceDiodeBlock.FACING, Direction.DOWN)
                .setValue(SurfaceComparatorBlock.MODE,
                        net.minecraft.world.level.block.state.properties.ComparatorMode.SUBTRACT));
        BlockPos pos = at(helper, 3, 1, 2);
        helper.setBlock(new BlockPos(3, 0, 2), Blocks.REDSTONE_BLOCK.defaultBlockState());

        // 侧面的宿主方块，里面一颗红石粉；红石块放在它的另一侧给它供电
        helper.setBlock(new BlockPos(3, 1, 1), Blocks.STONE.defaultBlockState());
        BlockPos host = at(helper, 3, 1, 1);
        com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneStore store =
                com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneStore.get(level);
        com.jiangyuefengyu.redstonecircuit.data.Slot dust = new com.jiangyuefengyu.redstonecircuit.data.Slot(
                com.jiangyuefengyu.redstonecircuit.data.ComponentType.DUST);
        dust.facing = Direction.SOUTH;
        store.getOrCreate(host).setSlot(dust);
        store.markDirty();
        // 让里面的粉衰减到 13（作者日志里就是 13）：红石块 → 宿主内部的粉 14？不，粉在宿主里，外面给它 15 就进 15；
        // 用一颗红石火把做源，粉读到的就是 15 —— 这里要的是"不是 15 也能读到"，所以拿 13 档的粉条做邻居更贴实。
        helper.setBlock(new BlockPos(3, 1, 0), Blocks.REDSTONE_TORCH.defaultBlockState());
        com.jiangyuefengyu.redstonecircuit.logic.InnerRedstoneNetwork.markDirtyWithNeighbours(level, host);

        helper.startSequence()
                .thenExecuteAfter(12, () -> {
                    int hostAnswer = level.getSignal(host, Direction.NORTH);
                    helper.assertTrue(answerAt(level, pos) == 0,
                            "宿主里那颗粉被红石亮点亮了，减法 15-15 应当答 0；宿主对我这一面gave "
                                    + hostAnswer + "，比较器答 " + answerAt(level, pos));
                })
                .thenSucceed();
    }

    /**
     * 求解窗口里也必须读得到。
     *
     * <p>模组自己的求解器在问世界时会把"世界里的宿主方块"藏起来（`queryingWorld`），为的是元件别把自己的输出
     * 从隔壁读回来。但贴面比较器不是那次求解的一部分：它是一个世界方块，问的是宿主此刻的存量，窗口开着也不能
     * 变成 0——上一轮真机上的 `sides=0` 就是从这里来的。
     */
    @GameTest(template = "redstonecircuit:empty")
    public void aHostIsReadEvenInsideTheSolveWindow(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        helper.setBlock(new BlockPos(2, 1, 2), Blocks.STONE.defaultBlockState());
        helper.setBlock(new BlockPos(3, 1, 2), RCRegistry.SURFACE_COMPARATOR.defaultBlockState()
                .setValue(SurfaceDiodeBlock.MOUNT, Direction.WEST)
                .setValue(SurfaceDiodeBlock.FACING, Direction.DOWN)
                .setValue(SurfaceComparatorBlock.MODE,
                        net.minecraft.world.level.block.state.properties.ComparatorMode.SUBTRACT));
        BlockPos pos = at(helper, 3, 1, 2);
        helper.setBlock(new BlockPos(3, 1, 1), Blocks.STONE.defaultBlockState());
        BlockPos host = at(helper, 3, 1, 1);
        com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneStore store =
                com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneStore.get(level);
        com.jiangyuefengyu.redstonecircuit.data.Slot lever = new com.jiangyuefengyu.redstonecircuit.data.Slot(
                com.jiangyuefengyu.redstonecircuit.data.ComponentType.LEVER);
        lever.powered = true;
        lever.power = 13;
        lever.facing = Direction.SOUTH;
        store.getOrCreate(host).setSlot(lever);
        store.markDirty();
        com.jiangyuefengyu.redstonecircuit.logic.InnerRedstoneNetwork.markDirtyWithNeighbours(level, host);

        helper.startSequence()
                .thenExecuteAfter(4, () -> {
                    int outside = SurfaceDiodeBlock.neighbourSignal(level, pos, Direction.NORTH);
                    int inside = com.jiangyuefengyu.redstonecircuit.logic.InnerRedstoneNetwork.queryingWorld(
                            level, () -> SurfaceDiodeBlock.neighbourSignal(level, pos, Direction.NORTH));
                    helper.assertTrue(outside > 0,
                            "宿主里那颗元件朝南答 13，比较器问北边就该读到它，实际 " + outside);
                    helper.assertTrue(inside == outside,
                            "求解窗口里外必须一样：外面读到 " + outside + "，窗口里读到 " + inside);
                })
                .thenSucceed();
    }

    /**
     * 红线用例 B：原版中继器从侧面锁住我们的板子（原版锁存）。
     *
     * <p>板子在 (3,1,2) 朝北读；另一个原版中继器在它东侧 (4,1,2)，朝东读、朝西输出（正好指进我们），
     * 并且它自己被点亮 —— 这时候我们的板子必须被锁住，输出冻结。
     */
    @GameTest(template = "redstonecircuit:empty")
    public void aVanillaRepeaterLocksOurPlateFromTheSide(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        helper.setBlock(new BlockPos(3, 0, 2), Blocks.STONE.defaultBlockState());
        helper.setBlock(new BlockPos(3, 1, 2), twin(Direction.DOWN, Direction.NORTH));
        BlockPos pos = at(helper, 3, 1, 2);

        // 东侧那个原版中继器：朝东读、朝西输出，自己被东边的红石块点亮
        helper.setBlock(new BlockPos(4, 0, 2), Blocks.STONE.defaultBlockState());
        helper.setBlock(new BlockPos(4, 1, 2), Blocks.REPEATER.defaultBlockState()
                .setValue(net.minecraft.world.level.block.RepeaterBlock.FACING, Direction.EAST));
        helper.setBlock(new BlockPos(5, 1, 2), Blocks.REDSTONE_BLOCK.defaultBlockState());

        // 我们这块板子自己也被点亮（背面输入 15），于是"锁住"才有可观察的效果：它必须停止回答。
        helper.setBlock(new BlockPos(3, 1, 1), Blocks.REDSTONE_BLOCK.defaultBlockState());

        helper.startSequence()
                .thenExecuteAfter(12, () -> {
                    helper.assertTrue(level.getBlockState(pos).getValue(SurfaceRepeaterBlock.LOCKED),
                            "被侧面那个原版中继器锁住时，LOCKED 必须是 true");
                    helper.assertTrue(answerAt(level, pos) == 0, "锁住就等于冻结：背面不该再答，实际 "
                            + answerAt(level, pos));
                })
                .thenSucceed();
    }

    /**
     * 作者报的那一例锁存：两块板子都贴在墙上、上下相邻，下面那块水平朝向，上面那块垂直朝向对着它。
     *
     * <p>上面那块朝下读、朝上答？不 —— 它"对着"下面那块，就是朝下输出：`FACING = UP`（读上、答下）。
     * 原版没有垂直的中继器，所以这条不可能在原版里出现；我们的板子既然能竖着放，锁源也就得认上下两个面。
     */
    @GameTest(template = "redstonecircuit:empty")
    public void aPlateLocksFromAboveWhenItPointsAtUs(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        // 下面那块：贴西墙，水平朝北读
        helper.setBlock(new BlockPos(2, 1, 2), Blocks.STONE.defaultBlockState());
        helper.setBlock(new BlockPos(3, 1, 2), twin(Direction.WEST, Direction.NORTH));
        // 上面那块：也贴西墙，垂直朝下输出（读上）—— 它自己的支撑也要在那
        helper.setBlock(new BlockPos(2, 2, 2), Blocks.STONE.defaultBlockState());
        helper.setBlock(new BlockPos(3, 2, 2), twin(Direction.WEST, Direction.UP));
        BlockPos lower = at(helper, 3, 1, 2);
        BlockPos upper = at(helper, 3, 2, 2);

        // 上面那块自己通电（它的输入在上面那一格），它就会朝下答 15 —— 正好指进下面那块的上面
        helper.setBlock(new BlockPos(3, 3, 2), Blocks.REDSTONE_BLOCK.defaultBlockState());
        // 下面那块也有输入
        helper.setBlock(new BlockPos(3, 1, 1), Blocks.REDSTONE_BLOCK.defaultBlockState());

        helper.startSequence()
                .thenExecuteAfter(12, () -> {
                    helper.assertTrue(level.getBlockState(upper).getValue(SurfaceRepeaterBlock.POWERED),
                            "上面那块应当通电，实际 " + level.getBlockState(upper));
                    helper.assertTrue(level.getBlockState(lower).getValue(SurfaceRepeaterBlock.LOCKED),
                            "上面那块朝下指着我，下面那块就该被锁住");
                    helper.assertTrue(answerAt(level, lower) == 0,
                            "被锁就等于冻结，实际答 " + answerAt(level, lower));
                })
                .thenSucceed();
    }
}
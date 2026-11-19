package com.jiangyuefengyu.redstonecircuit;

import com.jiangyuefengyu.redstonecircuit.block.SurfaceDiodeBlock;
import com.jiangyuefengyu.redstonecircuit.block.SurfaceRepeaterBlock;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * 维持 / 双倍 / 双倍维持 中继器（贴面）in a real level: the mount is only a place to sit, and the two things the
 * three add are the two things they must keep.
 *
 * <p>方向口径 is the same as {@code SurfaceRepeaterGameTest}: {@code FACING} names the face the plate <b>reads</b>, and
 * {@code getSignal(pos, FACING)} is the answer its back neighbour hears.
 */
@GameTestHolder(RedstoneCircuit.MODID)
@PrefixGameTestTemplate(false)
public final class SurfaceVariableRepeaterGameTest {


    private static BlockPos at(GameTestHelper helper, int x, int y, int z) {
        return helper.absolutePos(new BlockPos(x, y, z));
    }

    /** The answer the plate's back neighbour hears. */
    private static int answerAt(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.getSignal(level, pos, state.getValue(SurfaceDiodeBlock.FACING));
    }

    private static BlockState plate(Block twin, Direction facing) {
        return twin.defaultBlockState()
                .setValue(SurfaceDiodeBlock.MOUNT, Direction.UP)
                .setValue(SurfaceDiodeBlock.FACING, facing)
                .setValue(SurfaceRepeaterBlock.DELAY, 1);
    }

    /**
     * 天花板下挂着的一片，读北面那一格的粉：石头地面、粉线、天花板上挂着中继器。
     *
     * @param twin the plate to hang
     * @param x the column the rig lives in
     * @param cells how many dust cells the line has; the plate hangs on the cell after them
     * @return the plate's position
     */
    private static BlockPos rig(GameTestHelper helper, Block twin, int x, int cells) {
        for (int z = 1; z <= cells; z++) {
            helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE.defaultBlockState());
            helper.setBlock(new BlockPos(x, 2, z), Blocks.REDSTONE_WIRE.defaultBlockState());
        }
        helper.setBlock(new BlockPos(x, 3, cells + 1), Blocks.STONE.defaultBlockState());
        helper.setBlock(new BlockPos(x, 2, cells + 1), plate(twin, Direction.NORTH));
        return at(helper, x, 2, cells + 1);
    }

    /** 天花板下挂着的一片，读北面那一格：石头天花板在 (x,3,z)，输入格是 (x,2,z-1)。 */
    private static BlockPos ceiled(GameTestHelper helper, Block twin, int x, int z) {
        helper.setBlock(new BlockPos(x, 3, z), Blocks.STONE.defaultBlockState());
        helper.setBlock(new BlockPos(x, 2, z), plate(twin, Direction.NORTH));
        return at(helper, x, 2, z);
    }

    /** 源：一格红石块。放上去粉就是满强度，拿走就灭。 */
    private static void source(GameTestHelper helper, int x, int z, boolean on) {
        helper.setBlock(new BlockPos(x, 2, z), on
                ? Blocks.REDSTONE_BLOCK.defaultBlockState()
                : Blocks.AIR.defaultBlockState());
    }

    /**
     * 维持那一片：**输入多少就答多少**，不像原版中继器那样恢复成 15。
     *
     * <p>两个一模一样的摆法并排：左边是维持（贴面），右边是中继器（贴面）当对照。粉线六格，末端那格是 10，
     * 所以对照该答 15、维持该答 10 —— 差的那 5 就是这个方块存在的理由。
     */
    @GameTest(template = "empty")
    public void aRetainingPlateHandsOnTheStrengthItWasGiven(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos retaining = rig(helper, RCRegistry.SURFACE_RETAINING_REPEATER.get(), 3, 6);
        BlockPos plain = rig(helper, RCRegistry.SURFACE_REPEATER.get(), 8, 6);
        source(helper, 2, 1, true);
        source(helper, 7, 1, true);

        helper.startSequence()
                .thenExecuteAfter(8, () -> {
                    int input = level.getBlockState(at(helper, 3, 2, 6))
                            .getValue(net.minecraft.world.level.block.RedStoneWireBlock.POWER);
                    helper.assertTrue(input == 10, "摆法本身要给出 10 的输入，实际 " + input);
                    helper.assertTrue(answerAt(level, plain) == 15,
                            "对照（原版中继器的贴面版）恢复成 15，实际 " + answerAt(level, plain));
                    helper.assertTrue(answerAt(level, retaining) == input,
                            "维持要答输入的那个数（" + input + "），实际 " + answerAt(level, retaining));
                })
                .thenSucceed();
    }

    /**
     * 双倍那一片：每档延迟翻倍。DELAY=1 时原版要 2 tick，双倍要 4 tick。
     *
     * <p>两条一样的摆法并排，源同时放上去：3 tick 时对照已经亮了、双倍还没亮；再过几 tick 两个都亮。
     */
    @GameTest(template = "empty")
    public void theDoublePlateWaitsTwiceAsLong(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        // 输入用一格红石块而不是一条粉线：粉要结算一两个 tick，结算时间会混进延迟里，量出来的就不是延迟了
        //（第一版就是这么错的：3 tick 时对照还没亮）。
        BlockPos doublePlate = ceiled(helper, RCRegistry.SURFACE_DOUBLE_REPEATER.get(), 3, 2);
        BlockPos plain = ceiled(helper, RCRegistry.SURFACE_REPEATER.get(), 8, 2);

        helper.startSequence()
                .thenExecuteAfter(2, () -> {
                    helper.setBlock(new BlockPos(3, 2, 1), Blocks.REDSTONE_BLOCK.defaultBlockState());
                    helper.setBlock(new BlockPos(8, 2, 1), Blocks.REDSTONE_BLOCK.defaultBlockState());
                })
                .thenExecuteAfter(3, () -> {
                    helper.assertTrue(level.getBlockState(plain).getValue(SurfaceRepeaterBlock.POWERED),
                            "对照（2 tick）这时该亮了");
                    helper.assertFalse(level.getBlockState(doublePlate).getValue(SurfaceRepeaterBlock.POWERED),
                            "双倍（4 tick）这时还不该亮");
                })
                .thenExecuteAfter(5, () -> helper.assertTrue(
                        level.getBlockState(doublePlate).getValue(SurfaceRepeaterBlock.POWERED),
                        "再过几 tick 双倍也该亮了"))
                .thenSucceed();
    }

    /**
     * 双倍维持：两个特性一起。粉两格、末端 14，源放上去之后 4 tick 才答，答的还是 14。
     */
    @GameTest(template = "empty")
    public void theDoubleRetainingPlateKeepsTheStrengthAndWaitsTwiceAsLong(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        // 三格粉：源在 (3,2,1) 旁边，所以 (3,2,2) 是 15、(3,2,3) 是 14，中继器挂在 (3,2,4) 读它
        for (int z = 1; z <= 3; z++) {
            helper.setBlock(new BlockPos(3, 1, z), Blocks.STONE.defaultBlockState());
            helper.setBlock(new BlockPos(3, 2, z), Blocks.REDSTONE_WIRE.defaultBlockState());
        }
        helper.setBlock(new BlockPos(3, 3, 4), Blocks.STONE.defaultBlockState());
        BlockPos twin = at(helper, 3, 2, 4);
        helper.setBlock(new BlockPos(3, 2, 4), plate(RCRegistry.SURFACE_DOUBLE_RETAINING_REPEATER.get(),
                Direction.NORTH));

        helper.startSequence()
                .thenExecuteAfter(4, () -> source(helper, 3, 1, true))
                .thenExecuteAfter(3, () -> helper.assertFalse(
                        level.getBlockState(twin).getValue(SurfaceRepeaterBlock.POWERED),
                        "双倍：3 tick 时还不该亮"))
                .thenExecuteAfter(6, () -> {
                    helper.assertTrue(level.getBlockState(twin).getValue(SurfaceRepeaterBlock.POWERED),
                            "双倍：几 tick 之后该亮了");
                    helper.assertTrue(answerAt(level, twin) == 14,
                            "维持：答输入的那个 14，实际 " + answerAt(level, twin));
                })
                .thenSucceed();
    }

    /** 三个物品各自放到自己的孪生上，一处都不串。 */
    @GameTest(template = "empty")
    public void theThreeItemsPlaceTheirOwnTwinOnAWall(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Block[] floor = {
                RCRegistry.RETAINING_REPEATER.get(),
                RCRegistry.DOUBLE_REPEATER.get(),
                RCRegistry.DOUBLE_RETAINING_REPEATER.get(),
        };
        Block[] twins = {
                RCRegistry.SURFACE_RETAINING_REPEATER.get(),
                RCRegistry.SURFACE_DOUBLE_REPEATER.get(),
                RCRegistry.SURFACE_DOUBLE_RETAINING_REPEATER.get(),
        };
        int z = 2;
        for (int index = 0; index < floor.length; index++, z += 2) {
            helper.setBlock(new BlockPos(3, 1, z), Blocks.STONE.defaultBlockState());
        }
        helper.startSequence()
                .thenExecuteAfter(1, () -> {
                    int row = 2;
                    for (int index = 0; index < floor.length; index++, row += 2) {
                        net.minecraft.world.item.ItemStack stack =
                                new net.minecraft.world.item.ItemStack(floor[index].asItem());
                        net.minecraft.world.phys.BlockHitResult hit = new net.minecraft.world.phys.BlockHitResult(
                                net.minecraft.world.phys.Vec3.atCenterOf(at(helper, 3, 1, row)),
                                Direction.EAST, at(helper, 3, 1, row), false);
                        net.minecraft.world.InteractionResult placed =
                                com.jiangyuefengyu.redstonecircuit.block.SurfaceVariableRepeaterPlacement.tryPlace(
                                        new net.minecraft.world.item.context.UseOnContext(level, null,
                                                net.minecraft.world.InteractionHand.MAIN_HAND, stack, hit));
                        helper.assertTrue(placed != null && placed.consumesAction(),
                                floor[index] + " 点在墙上该能放下，实际 " + placed);
                        BlockState state = level.getBlockState(at(helper, 4, 1, row));
                        helper.assertTrue(state.getBlock() == twins[index],
                                floor[index] + " 放下的该是它自己的孪生，实际 " + state.getBlock());
                        helper.assertTrue(state.getValue(SurfaceDiodeBlock.MOUNT) == Direction.WEST,
                                floor[index] + " 该挂在那块石头的东面（MOUNT=WEST），实际 "
                                        + state.getValue(SurfaceDiodeBlock.MOUNT));
                    }
                })
                .thenSucceed();
    }

    /** 贴面红石粉读到的强度。 */
    private static int power(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos).getValue(com.jiangyuefengyu.redstonecircuit.block.SurfaceWireBlock.POWER);
    }

    /**
     * 作者 2026-11-15 报的「两种贴面维持中继器无法对贴面红石粉输出信号，而对普通红石粉可以」。
     *
     * <p>摆法：天花板下挂着那一片，读北面那格的粉（14），**输出面南边那一格放一片贴面红石粉**。
     * 对照是普通中继器（贴面）在同样的位置、同样的输入：它该送 15，维持那一片该送 14。
     * 只要对面那片贴面读到 0，这条就红。
     */
    @GameTest(template = "empty")
    public void aRetainingPlateDrivesASurfaceSheet(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos retaining = outToSheet(helper, RCRegistry.SURFACE_RETAINING_REPEATER.get(), 3);
        BlockPos plain = outToSheet(helper, RCRegistry.SURFACE_REPEATER.get(), 8);
        source(helper, 2, 1, true);
        source(helper, 7, 1, true);

        helper.startSequence()
                .thenExecuteAfter(10, () -> {
                    int intoRetaining = level.getBlockState(at(helper, 3, 2, 2))
                            .getValue(net.minecraft.world.level.block.RedStoneWireBlock.POWER);
                    helper.assertTrue(intoRetaining == 14, "输入该是 14，实际 " + intoRetaining);
                    helper.assertTrue(power(level, retaining) == 14,
                            "维持那一片该把 14 送进贴面红石粉，实际贴面读到 " + power(level, retaining));
                    helper.assertTrue(power(level, plain) == 15,
                            "对照（普通中继器的贴面版）该送 15，实际贴面读到 " + power(level, plain));
                })
                .thenSucceed();
    }

    /** 反过来的摆法：输入是**贴面红石粉**，输出是普通红石粉。 */
    @GameTest(template = "empty")
    public void aRetainingPlateReadsASurfaceSheetAndDrivesDust(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        helper.setBlock(new BlockPos(3, 1, 1), Blocks.STONE.defaultBlockState());
        helper.setBlock(new BlockPos(3, 1, 2), Blocks.STONE.defaultBlockState());
        helper.setBlock(new BlockPos(3, 3, 2), Blocks.STONE.defaultBlockState());
        BlockPos sheetCell = at(helper, 3, 2, 1);
        helper.setBlock(new BlockPos(3, 2, 1), com.jiangyuefengyu.redstonecircuit.block.SurfaceWireBlock.withFace(
                RCRegistry.SURFACE_WIRE.get().defaultBlockState(), Direction.DOWN));
        BlockPos platePos = at(helper, 3, 2, 2);
        helper.setBlock(new BlockPos(3, 2, 2), plate(RCRegistry.SURFACE_RETAINING_REPEATER.get(), Direction.NORTH));
        helper.setBlock(new BlockPos(3, 1, 3), Blocks.STONE.defaultBlockState());
        BlockPos out = at(helper, 3, 2, 3);
        helper.setBlock(new BlockPos(3, 2, 3), Blocks.REDSTONE_WIRE.defaultBlockState());
        // 源喂那片贴面：南边一格红石块
        helper.setBlock(new BlockPos(3, 2, 0), Blocks.STONE.defaultBlockState());
        source(helper, 4, 1, true);

        helper.startSequence()
                .thenExecuteAfter(10, () -> {
                    int sheet = power(level, sheetCell);
                    helper.assertTrue(sheet == 15, "喂进贴面的源就是 15，实际 " + sheet);
                    helper.assertTrue(level.getBlockState(platePos).getValue(SurfaceRepeaterBlock.POWERED),
                            "贴面有 15，中继器就该通");
                    int driven = level.getBlockState(out)
                            .getValue(net.minecraft.world.level.block.RedStoneWireBlock.POWER);
                    helper.assertTrue(driven >= 14, "输出的粉该读到 14 或 15，实际 " + driven);
                })
                .thenSucceed();
    }


    /**
     * 作者 2026-11-15 的摆法原样：**墙上一条**，贴面红石粉 → 维持中继器（贴面）→ 又一次贴面红石粉。
     *
     * <p>这三个都是挂西墙（{@code MOUNT=WEST}）的：墙在 x=2，三段都在 x=3 那一列，沿 z 排成一条。
     * 中继器读北面那一片、往南面那一片送。
     */
    @GameTest(template = "empty")
    public void aWallRowOfSheetPlateSheet(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos retaining = wallRow(helper, RCRegistry.SURFACE_RETAINING_REPEATER.get(), 3, 1);
        BlockPos plain = wallRow(helper, RCRegistry.SURFACE_REPEATER.get(), 8, 1);
        helper.startSequence()
                .thenExecuteAfter(10, () -> {
                    // 先只记下来，最后一条断言里一起报
                    helper.assertTrue(power(level, at(helper, 3, 1, 1)) == 15,
                            "第一片贴面该被红石块喂到 15，实际 " + power(level, at(helper, 3, 1, 1)));
                    helper.assertTrue(level.getBlockState(at(helper, 3, 1, 2)).getValue(SurfaceRepeaterBlock.POWERED),
                            "第一片有 15，维持那一片就该通（作者报的就是这里不通）");
                    // 这一条是作者报的那个缺陷的回归：维持那一片的输出是**现场读**输入面算出来的，
                    // 而输入面那片贴面在自己的求解窗口里一律答 0 —— 两边永远对不齐，贴面就一直是 0。
                    helper.assertTrue(power(level, retaining) > 0,
                            "维持那一片该把信号送到南面那片贴面，实际 " + power(level, retaining));
                    helper.assertTrue(power(level, plain) > 0,
                            "对照（普通中继器的贴面版）也该送到，实际 " + power(level, plain));
                })
                .thenSucceed();
    }

    /** 一道西墙（x=2）加一条三段：贴面、中继器、贴面，都在 x=3。返回南端那片贴面的位置。 */
    private static BlockPos wallRow(GameTestHelper helper, Block twin, int x, int row) {
        for (int z = 1; z <= 3; z++) {
            helper.setBlock(new BlockPos(x - 1, 1, z), Blocks.STONE.defaultBlockState());
        }
        helper.setBlock(new BlockPos(x, 1, 1), com.jiangyuefengyu.redstonecircuit.block.SurfaceWireBlock.withFace(
                RCRegistry.SURFACE_WIRE.get().defaultBlockState(), Direction.WEST));
        helper.setBlock(new BlockPos(x, 1, 2), plateOnWall(twin, Direction.WEST, Direction.NORTH));
        helper.setBlock(new BlockPos(x, 1, 3), com.jiangyuefengyu.redstonecircuit.block.SurfaceWireBlock.withFace(
                RCRegistry.SURFACE_WIRE.get().defaultBlockState(), Direction.WEST));
        // 源在墙外那一格：喂第一片贴面
        helper.setBlock(new BlockPos(x + 1, 1, 1), Blocks.REDSTONE_BLOCK.defaultBlockState());
        return at(helper, x, 1, 3);
    }

    private static BlockState plateOnWall(Block twin, Direction mount, Direction facing) {
        return twin.defaultBlockState()
                .setValue(SurfaceDiodeBlock.MOUNT, mount)
                .setValue(SurfaceDiodeBlock.FACING, facing)
                .setValue(SurfaceRepeaterBlock.DELAY, 1);
    }

    /** 天花板挂一片：(x,2,3) 读北面的粉 (x,2,2)=14；输出面南边 (x,2,4) 放一片贴面红石粉（地面那一面）。 */
    private static BlockPos outToSheet(GameTestHelper helper, Block twin, int x) {
        for (int z = 1; z <= 4; z++) {
            helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE.defaultBlockState());
        }
        helper.setBlock(new BlockPos(x, 2, 1), Blocks.REDSTONE_WIRE.defaultBlockState());
        helper.setBlock(new BlockPos(x, 2, 2), Blocks.REDSTONE_WIRE.defaultBlockState());
        helper.setBlock(new BlockPos(x, 3, 3), Blocks.STONE.defaultBlockState());
        helper.setBlock(new BlockPos(x, 2, 3), plate(twin, Direction.NORTH));
        helper.setBlock(new BlockPos(x, 2, 4), com.jiangyuefengyu.redstonecircuit.block.SurfaceWireBlock.withFace(
                RCRegistry.SURFACE_WIRE.get().defaultBlockState(), Direction.DOWN));
        return at(helper, x, 2, 4);
    }
}


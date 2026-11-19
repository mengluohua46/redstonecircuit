package com.jiangyuefengyu.redstonecircuit;

import java.util.UUID;

import com.jiangyuefengyu.redstonecircuit.block.SurfaceWireBlock;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierId;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierPose;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierStore;
import com.jiangyuefengyu.redstonecircuit.carrier.CarriedBlocks;
import com.jiangyuefengyu.redstonecircuit.carrier.CarriedSupport;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SupportType;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import org.joml.Vector3d;

/**
 * <b>贴面 on the face of a block a machine is carrying.</b>
 *
 * <p>A carried block's world cell holds air: its blocks live in the carrier's own storage, in the carrier's own
 * coordinates, and the pose is what puts them where the player sees them. Asking the world "is that face sturdy" -
 * which is what every one of these families did - therefore answered air, and the click was refused. 作者 (2026-11-16)
 * reported the shape of it: at a wall/floor junction the floor has to be placed first, and only then the wall - because
 * "is this sheet supported" needs <b>one</b> face, so the same cell accepted the wall the moment its floor face was
 * already standing on real ground.
 *
 * <p>The rig is a sub-level: its storage <em>is</em> the level at plot coordinates, so a real stone block at the local
 * coordinate stands for the block the machine is carrying, and the pose is one pure translation to the drawn cell. No
 * fake block view, no physics mod - the same two lookups the production read makes.
 */
@GameTestHolder(RedstoneCircuit.MODID)
@PrefixGameTestTemplate(false)
public final class CarriedSupportGameTest {

    @GameTest(template = "empty")
    public void aFaceOnACarriedBlockHoldsASheet(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        CarrierStore.CarrierSource previous = CarrierStore.source();
        try {
            // Where the machine is DRAWN: air in the world, as it is for a real carried block.
            BlockPos drawn = at(helper, 2, 1, 2);
            assertAir(helper, drawn);
            // Where the machine STORES it: a plain stone block at the structure's own coordinate.
            BlockPos local = at(helper, 4, 1, 2);
            level.setBlock(local, Blocks.STONE.defaultBlockState(), 3);

            CarrierId carrier = CarrierId.subLevel(UUID.fromString("00000000-0000-0000-0000-000000004801"));
            // project(local + p) = p + offset, so this offset puts that one block exactly on the drawn cell.
            CarrierPose pose = CarrierPose.at(new Vector3d(
                    drawn.getX() - local.getX(), drawn.getY() - local.getY(), drawn.getZ() - local.getZ()));
            CarrierStore.install(new CarrierStore.CarrierSource() {

                @Override
                public CarrierPose poseOf(ServerLevel ignored, CarrierId asked) {
                    return asked.equals(carrier) ? pose : null;
                }

                @Override
                public CarrierId carrierAt(ServerLevel ignored, BlockPos worldPos) {
                    return drawn.equals(worldPos) ? carrier : null;
                }

                @Override
                public String describe() {
                    return "game-test sub-level";
                }
            });

            helper.assertTrue(CarriedSupport.sturdy(level, drawn, Direction.DOWN, SupportType.RIGID),
                    "被搬走的方块在世界那一格是空气，但它的存储里是石头：这一面要算得住");
            BlockState sheet = SurfaceWireBlock.withFace(
                    RCRegistry.SURFACE_WIRE.get().defaultBlockState(), Direction.DOWN);
            helper.assertTrue(RCRegistry.SURFACE_WIRE.get().canStay(sheet, level, drawn.above()),
                    "画出来的那一面要能贴粉——作者报的就是这里不给贴");
            helper.assertFalse(CarriedSupport.sturdy(level, at(helper, 4, 2, 2), Direction.DOWN, SupportType.RIGID),
                    "没有载体、世界里也没有东西的那一格，照样不算支撑");
        } finally {
            CarrierStore.install(previous);
        }
        helper.succeed();
    }

    /**
     * <b>A neighbour a machine is drawing reads as a neighbour, not as air.</b> The author's second symptom: at a
     * wall/floor junction across a structure boundary the floor sheet did not light the wall sheet beside it - because
     * every neighbour read was {@code level.getBlockState} and the machine's cell holds air.
     *
     * <p>The link, not the solve: a carried cell's block and the in-plane "joined" answer are what the wire reads to
     * decide whether to pass power on. The carrier pass that would turn the stored sheet's power over is the compat
     * half's, and no test can drive it - so what is pinned here is the read that made the answer "no connection".
     */
    @GameTest(template = "empty")
    public void aNeighbourAMachineDrawsIsAWireOfOurs(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        CarrierStore.CarrierSource previous = CarrierStore.source();
        try {
            // The sheet in the world, lying on a floor of its own.
            BlockPos floor = at(helper, 2, 1, 2);
            helper.setBlock(new BlockPos(2, 0, 2), Blocks.STONE.defaultBlockState());
            helper.setBlock(new BlockPos(2, 1, 2), SurfaceWireBlock.withFace(
                    RCRegistry.SURFACE_WIRE.get().defaultBlockState(), Direction.DOWN));

            // The sheet beside it: drawn by a machine, stored at the structure's own coordinate.
            BlockPos drawn = at(helper, 3, 1, 2);
            BlockPos local = at(helper, 4, 1, 2);
            assertAir(helper, drawn);
            // The sheet needs a floor of its own inside the structure, or it drops the moment it is placed.
            level.setBlock(local.below(), Blocks.STONE.defaultBlockState(), 3);
            level.setBlock(local, SurfaceWireBlock.withFace(
                    RCRegistry.SURFACE_WIRE.get().defaultBlockState(), Direction.DOWN), 3);

            CarrierId carrier = CarrierId.subLevel(UUID.fromString("00000000-0000-0000-0000-000000004802"));
            CarrierPose pose = CarrierPose.at(new Vector3d(
                    drawn.getX() - local.getX(), drawn.getY() - local.getY(), drawn.getZ() - local.getZ()));
            CarrierStore.install(new CarrierStore.CarrierSource() {

                @Override
                public CarrierPose poseOf(ServerLevel ignored, CarrierId asked) {
                    return asked.equals(carrier) ? pose : null;
                }

                @Override
                public CarrierId carrierAt(ServerLevel ignored, BlockPos worldPos) {
                    return drawn.equals(worldPos) ? carrier : null;
                }

                @Override
                public String describe() {
                    return "game-test sub-level";
                }
            });

            BlockState seen = CarriedBlocks.stateAt(level, drawn);
            helper.assertTrue(seen.getBlock() == RCRegistry.SURFACE_WIRE.get(),
                    "机器画出来的那一格要读成我们的粉，不能是世界里的空气，实际 " + seen);
            helper.assertTrue(SurfaceWireBlock.joined(level, floor, Direction.DOWN, Direction.EAST),
                    "同一面里相邻的两格：一格在机器上，也要算连上了——作者报的就是这里不通");
        } finally {
            CarrierStore.install(previous);
        }
        helper.succeed();
    }

    /**
     * <b>The neighbour a player sees is one frame away from the neighbour the block state knows about.</b> Two
     * structures side by side, or a structure beside ordinary ground: one sheet sits in a structure's own storage, the
     * other stands in the world beside where that structure is <b>drawn</b>. Reading only the structure's own coordinate
     * finds air (作者 2026-11-17: 两个结构之间能放了，但通电互相点不亮).
     */
    @GameTest(template = "empty")
    public void aNeighbourAcrossTheFrameIsFound(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        CarrierStore.CarrierSource previous = CarrierStore.source();
        try {
            BlockPos local = at(helper, 2, 1, 2);
            BlockPos drawn = at(helper, 3, 1, 2);
            BlockPos drawnNeighbour = at(helper, 4, 1, 2);
            CarrierId carrier = CarrierId.subLevel(UUID.fromString("00000000-0000-0000-0000-000000004803"));
            CarrierPose pose = CarrierPose.at(new Vector3d(
                    drawn.getX() - local.getX(), drawn.getY() - local.getY(), drawn.getZ() - local.getZ()));

            level.setBlock(local.below(), Blocks.STONE.defaultBlockState(), 3);
            level.setBlock(local, SurfaceWireBlock.withFace(
                    RCRegistry.SURFACE_WIRE.get().defaultBlockState(), Direction.DOWN), 3);
            level.setBlock(drawnNeighbour.below(), Blocks.STONE.defaultBlockState(), 3);
            level.setBlock(drawnNeighbour, SurfaceWireBlock.withFace(
                    RCRegistry.SURFACE_WIRE.get().defaultBlockState(), Direction.DOWN), 3);
            assertAir(helper, drawn);

            CarrierStore.install(new CarrierStore.CarrierSource() {

                @Override
                public CarrierPose poseOf(ServerLevel ignored, CarrierId asked) {
                    return asked.equals(carrier) ? pose : null;
                }

                @Override
                public CarrierId carrierAt(ServerLevel ignored, BlockPos worldPos) {
                    // Nothing is drawn here: the plot cell's own neighbour is empty and the wire stands one frame away.
                    return null;
                }

                @Override
                public CarrierId plotOwnerAt(ServerLevel ignored, BlockPos levelPos) {
                    return local.equals(levelPos) ? carrier : null;
                }

                @Override
                public String describe() {
                    return "game-test sub-level";
                }
            });

            helper.assertTrue(level.getBlockState(local.east()).isAir(),
                    "前提：结构自己那一格里，东边那格没有任何方块（世界里的邻居在另一帧上）");
            BlockState seen = com.jiangyuefengyu.redstonecircuit.carrier.CarriedBlocks
                    .neighbourOf(level, local, Direction.EAST);
            helper.assertTrue(seen.getBlock() == RCRegistry.SURFACE_WIRE.get(),
                    "画出来的东边那一格才是玩家看见的邻居，要读成我们的粉，实际 " + seen);
        } finally {
            CarrierStore.install(previous);
        }
        helper.succeed();
    }

    /**
     * <b>Two structures can cover the same world cell, and the one the container lists first is not the one the player is
     * looking at.</b> 作者's log (2026-11-17) showed the same position answering {@code oak_planks} through one sub-level and
     * {@code air} through another, one call apart - so a neighbour read that stopped at the first match was a coin toss.
     * The reader now asks every covering structure and takes the one that really holds a block there.
     */
    @GameTest(template = "empty")
    public void theStructureThatHoldsABlockWins(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        CarrierStore.CarrierSource previous = CarrierStore.source();
        try {
            BlockPos drawn = at(helper, 2, 1, 2);
            BlockPos emptyLocal = at(helper, 4, 1, 2);
            BlockPos wireLocal = at(helper, 6, 1, 2);
            assertAir(helper, drawn);

            CarrierId emptyCarrier = CarrierId.subLevel(UUID.fromString("00000000-0000-0000-0000-000000004811"));
            CarrierId wireCarrier = CarrierId.subLevel(UUID.fromString("00000000-0000-0000-0000-000000004812"));
            CarrierPose emptyPose = CarrierPose.at(new Vector3d(
                    drawn.getX() - emptyLocal.getX(), drawn.getY() - emptyLocal.getY(), drawn.getZ() - emptyLocal.getZ()));
            CarrierPose wirePose = CarrierPose.at(new Vector3d(
                    drawn.getX() - wireLocal.getX(), drawn.getY() - wireLocal.getY(), drawn.getZ() - wireLocal.getZ()));
            level.setBlock(wireLocal.below(), Blocks.STONE.defaultBlockState(), 3);
            level.setBlock(wireLocal, SurfaceWireBlock.withFace(
                    RCRegistry.SURFACE_WIRE.get().defaultBlockState(), Direction.DOWN), 3);

            CarrierStore.install(new CarrierStore.CarrierSource() {

                @Override
                public CarrierPose poseOf(ServerLevel ignored, CarrierId asked) {
                    if (asked.equals(emptyCarrier)) {
                        return emptyPose;
                    }
                    return asked.equals(wireCarrier) ? wirePose : null;
                }

                @Override
                public CarrierId carrierAt(ServerLevel ignored, BlockPos worldPos) {
                    return drawn.equals(worldPos) ? emptyCarrier : null;
                }

                @Override
                public java.util.List<CarrierId> carriersAt(ServerLevel ignored, BlockPos worldPos) {
                    // The empty structure is listed FIRST, exactly the order that used to win.
                    return drawn.equals(worldPos)
                            ? java.util.List.of(emptyCarrier, wireCarrier) : java.util.List.of();
                }

                @Override
                public String describe() {
                    return "game-test overlapping sub-levels";
                }
            });

            BlockState seen = com.jiangyuefengyu.redstonecircuit.carrier.CarriedBlocks.stateAt(level, drawn);
            helper.assertTrue(seen.getBlock() == RCRegistry.SURFACE_WIRE.get(),
                    "两台机器的包围盒都盖住这一格：要读真的有方块的那一台（粉），不是先列出来的那台空的，实际 " + seen);
        } finally {
            CarrierStore.install(previous);
        }
        helper.succeed();
    }

    private static BlockPos at(GameTestHelper helper, int x, int y, int z) {
        return helper.absolutePos(new BlockPos(x, y, z));
    }

    private static void assertAir(GameTestHelper helper, BlockPos pos) {
        helper.assertTrue(helper.getLevel().getBlockState(pos).isAir(),
                "前提：画出来的那一格在世界里是空气，实际 " + helper.getLevel().getBlockState(pos));
    }
}

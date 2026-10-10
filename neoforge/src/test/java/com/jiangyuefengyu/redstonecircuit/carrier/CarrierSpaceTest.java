package com.jiangyuefengyu.redstonecircuit.carrier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.Slot;
import com.jiangyuefengyu.redstonecircuit.logic.PowerEnvironment;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests the second {@code PowerEnvironment}: one structure's inside, seen through the carried index.
 *
 * <p>只有本结构的 host 应答，同一局部坐标在别的结构上是另一个元件；无 bridge 时外部各面为 0，
 * 有 bridge 时把邻居的局部坐标按搬运姿态投影成世界坐标再读世界；方块查询走结构自己的
 * {@code BlockGetter}，用局部坐标。
 */
class CarrierSpaceTest {

    private static final CarrierId CONTRAPTION =
            CarrierId.contraption(UUID.fromString("00000000-0000-0000-0000-00000000c0ff"));
    private static final CarrierId OTHER =
            CarrierId.contraption(UUID.fromString("00000000-0000-0000-0000-00000000beef"));

    private static final class FakeWorld implements CarriedHostIndex.SlotWorld {

        final Map<Long, Slot> slots = new HashMap<>();

        @Override
        @Nullable
        public Slot get(BlockPos pos) {
            return slots.get(pos.asLong());
        }

        @Override
        public void set(BlockPos pos, @Nullable Slot slot) {
            if (slot == null) {
                slots.remove(pos.asLong());
            } else {
                slots.put(pos.asLong(), slot);
            }
        }
    }

    /**
     * The structure's own blocks.
     *
     * <p>每次读取都抛异常: 真正的 {@code BlockState} 需要游戏跑起来建好的注册表，单元测试里没有，
     * {@code Blocks.AIR.defaultBlockState()} 就会先炸。所以这里改为记录被问到的坐标 - 无游戏时
     * {@code CarrierSpace#isRedstoneConductor} 能出错的地方正是"哪个坐标到了结构"，异常带着坐标即证明。
     */
    private static final class FakeBlocks implements BlockGetter {

        final List<BlockPos> queried = new ArrayList<>();

        @Override
        public BlockState getBlockState(BlockPos pos) {
            queried.add(pos);
            // No registry, no block state - see the class doc.
            throw new UnsupportedOperationException("block state read at " + pos);
        }

        @Override
        @Nullable
        public BlockEntity getBlockEntity(BlockPos pos) {
            return null;
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return Fluids.EMPTY.defaultFluidState();
        }

        // The height accessor half of BlockGetter; unused here, but the interface needs an answer.
        @Override
        public int getHeight() {
            return 384;
        }

        @Override
        public int getMinBuildHeight() {
            return -64;
        }
    }

    private final CarriedHostIndex index = new CarriedHostIndex();
    private final FakeWorld world = new FakeWorld();

    private static BlockPos pos(int x, int y, int z) {
        return new BlockPos(x, y, z);
    }

    /** Picks a component up, the way the assembly callback does. */
    private Slot carry(CarrierId carrier, BlockPos local, BlockPos origin, ComponentType type) {
        Slot slot = new Slot(type);
        world.set(origin, slot);
        index.claim(carrier, local, origin, world);
        return index.at(carrier, local).slot();
    }

    // ------------------------------------------------------------------ lookup --

    @Test
    @DisplayName("a local coordinate answers with this structure's component")
    void insideAnswers() {
        carry(CONTRAPTION, pos(1, 0, 2), pos(10, 64, 10), ComponentType.DUST).power = 9;
        CarrierSpace space = new CarrierSpace(CONTRAPTION, index, world, null, null);

        PowerEnvironment.Node node = space.nodeAt(1, 0, 2);
        assertNotNull(node, "the host the structure is carrying is there");
        assertEquals(ComponentType.DUST, node.type());
        assertEquals(9, node.power());
        assertEquals(1, space.nodeCount());
        assertEquals(List.of(pos(1, 0, 2)), space.localPositions());
    }

    @Test
    @DisplayName("the same local coordinate in another structure is a different component")
    void otherStructuresAreNotVisible() {
        carry(CONTRAPTION, pos(1, 0, 2), pos(10, 64, 10), ComponentType.DUST).power = 9;
        carry(OTHER, pos(1, 0, 2), pos(50, 64, 50), ComponentType.REPEATER).power = 4;

        CarrierSpace mine = new CarrierSpace(CONTRAPTION, index, world, null, null);
        CarrierSpace theirs = new CarrierSpace(OTHER, index, world, null, null);

        assertEquals(ComponentType.DUST, mine.nodeAt(1, 0, 2).type());
        assertEquals(ComponentType.REPEATER, theirs.nodeAt(1, 0, 2).type(),
                "the same coordinate, a different machine - this is what makes the coordinates 'local'");
        assertEquals(9, mine.nodeAt(1, 0, 2).power());
        assertEquals(4, theirs.nodeAt(1, 0, 2).power());
    }

    @Test
    @DisplayName("a coordinate the structure does not carry answers nothing")
    void outsideTheIndexAnswersNothing() {
        carry(CONTRAPTION, pos(1, 0, 2), pos(10, 64, 10), ComponentType.DUST);
        CarrierSpace space = new CarrierSpace(CONTRAPTION, index, world, null, null);

        assertNull(space.nodeAt(0, 0, 0), "the host block next door is not a host block of ours");
        assertNull(space.nodeAt(1, 0, 3), "and neither is empty space inside the machine");
    }

    @Test
    @DisplayName("a host that has been put down reads from the world again")
    void releasedHostsReadFromTheWorld() {
        Slot original = carry(CONTRAPTION, pos(1, 0, 2), pos(10, 64, 10), ComponentType.DUST);
        original.power = 12;
        index.release(CONTRAPTION, pos(1, 0, 2), null, world);

        Slot live = world.get(pos(10, 64, 10));
        assertNotNull(live, "the release wrote it back");
        live.power = 3;

        CarrierSpace space = new CarrierSpace(CONTRAPTION, index, world, null, null);
        PowerEnvironment.Node node = space.nodeAt(1, 0, 2);
        assertEquals(3, node.power(),
                "once a host is back in the world, the world's copy is what a solve must see");
    }

    // --------------------------------------------------------------- supplies --

    @Test
    @DisplayName("with no bridge the outside answers zero, whatever is going on around the structure")
    void theOutsideIsZeroWhenTheBridgeIsClosed() {
        carry(CONTRAPTION, pos(1, 0, 2), pos(10, 64, 10), ComponentType.DUST);
        CarrierSpace space = new CarrierSpace(CONTRAPTION, index, world, null, null);

        assertFalse(space.isBridged(), "no io means the frozen boundary");
        assertEquals(0, space.externalSignal(), "omnidirectional supply");
        for (Direction direction : Direction.values()) {
            assertEquals(0, space.externalSignal(direction), "and per side: " + direction);
        }
    }

    /**
     * The world-to-inside half, end to end and without a game: the component at local {@code (1, 0, 2)}
     * of a structure pivoted at {@code (10, 64, 10)} sits at world {@code (11, 64, 12)}, so the fake world
     * answers 9 at its east neighbour {@code (12, 64, 12)} - through {@code EAST} and nowhere else.
     */
    @Test
    @DisplayName("with a bridge the outside is read at the position the structure currently occupies")
    void theOutsideIsReadWhereTheStructureIs() {
        carry(CONTRAPTION, pos(1, 0, 2), pos(10, 64, 10), ComponentType.DUST);
        Map<BlockPos, Integer> worldSignals = new HashMap<>();
        worldSignals.put(pos(12, 64, 12), 9);
        CarrierSpace space = new CarrierSpace(CONTRAPTION, index, world, null,
                new CarrierWorldIo(CarrierPose.at(new Vector3d(10.0, 64.0, 10.0)),
                        (worldPos, worldDirection) -> {
                            Integer value = worldSignals.get(worldPos);
                            return worldDirection == Direction.EAST && value != null ? value : 0;
                        }));

        assertTrue(space.isBridged());
        space.setQueryPosition(pos(1, 0, 2));

        assertEquals(9, space.externalSignal(Direction.EAST),
                "the block the host's east side is against is read at its own world position");
        assertEquals(0, space.externalSignal(Direction.WEST),
                "and the world says nothing on the far side");
        assertEquals(9, space.externalSignal(), "so the omnidirectional supply is that same nine");
    }

    @Test
    @DisplayName("a bridged space asks the world about the component the solve is looking at")
    void theQueryPositionDecidesWhereTheReadHappens() {
        carry(CONTRAPTION, pos(1, 0, 2), pos(10, 64, 10), ComponentType.DUST);
        carry(CONTRAPTION, pos(4, 0, 2), pos(20, 64, 10), ComponentType.DUST);
        List<BlockPos> asked = new ArrayList<>();
        CarrierSpace space = new CarrierSpace(CONTRAPTION, index, world, null,
                new CarrierWorldIo(CarrierPose.at(new Vector3d(10.0, 64.0, 10.0)),
                        (worldPos, worldDirection) -> {
                            asked.add(worldPos);
                            return 0;
                        }));

        space.setQueryPosition(pos(4, 0, 2));
        space.externalSignal(Direction.EAST);

        assertEquals(List.of(pos(15, 64, 12)), asked,
                "the second host's east neighbour, not the first one's - the solve says which "
                        + "component it is asking about");
    }

    // ----------------------------------------------------------- block reads --

    @Test
    @DisplayName("a conductor query reads the structure's own blocks, in local coordinates")
    void conductorsComeFromTheStructure() {
        carry(CONTRAPTION, pos(1, 0, 2), pos(10, 64, 10), ComponentType.DUST);
        FakeBlocks blocks = new FakeBlocks();
        CarrierSpace space = new CarrierSpace(CONTRAPTION, index, world, id -> blocks, null);

        // The solver's one question that needs the machine's own view of itself, asked in local coordinates.
        UnsupportedOperationException asked = assertThrows(UnsupportedOperationException.class,
                () -> space.isRedstoneConductor(4, 0, 0));
        assertTrue(asked.getMessage().contains(pos(4, 0, 0).toString()), asked.getMessage());
        assertEquals(List.of(pos(4, 0, 0)), blocks.queried,
                "and the structure was asked about the local coordinate, not about a world position");

        // With no way to read the structure the answer degrades to "no" instead of throwing; nothing in
        // the solve loop asks, so this can only under-report.
        FakeBlocks unread = new FakeBlocks();
        CarrierSpace withoutBlocks = new CarrierSpace(CONTRAPTION, index, world, null, null);
        assertFalse(withoutBlocks.isRedstoneConductor(4, 0, 0));
        assertTrue(unread.queried.isEmpty(), "and nothing was asked of a structure we cannot see");
    }

    @Test
    @DisplayName("the world accessor handed out is the one the index writes through")
    void theWorldAccessorIsShared() {
        CarrierSpace space = new CarrierSpace(CONTRAPTION, index, world, null, null);
        assertEquals(world, space.world(), "solving and saving must not be two different stores");
        assertEquals(CONTRAPTION, space.carrier());
    }
}

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
 * <h2>The three claims this pins down</h2>
 * <ol>
 *   <li><b>Only this structure's hosts answer.</b> Two machines can carry a block at the same local
 *       coordinate, and a solve in one of them must not be able to see the other. This is the property
 *       that makes "local coordinates" a coordinate system rather than a convention.</li>
 *   <li><b>With no bridge the outside is zero, on every side; with one it is the world the structure
 *       is really in.</b> Zero is the boundary P1 drew and {@code carrierFreezeWorldIo = true} still
 *       asks for - a solve that leaked a world signal in would power a machine from a world it has
 *       left. P4's bridge is the other case: the neighbour's <em>local</em> coordinate is projected to
 *       a world position through the carrier's pose, the local side is turned into a world direction,
 *       and the world is read there. Both are asserted below, against a fake world holding exactly one
 *       number at exactly one position.</li>
 *   <li><b>Block reads go to the structure, not to the world.</b> The one query that needs the machine's
 *       own view of itself - whether a coordinate is a redstone conductor - must read the
 *       {@code BlockGetter} the compat half supplies, in local coordinates.</li>
 * </ol>
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
     * <p>Every read throws, and that is the point. A real {@code BlockState} needs the registry a
     * running game builds, and a unit test has none - {@code Blocks.AIR.defaultBlockState()} blows up
     * before it returns anything. So instead of pretending to be a structure, this records what it was
     * asked for: the part of {@code CarrierSpace#isRedstoneConductor} that can go wrong without a game
     * is <em>which coordinate reaches the structure</em>, and that is exactly what an exception
     * carrying the coordinate proves.
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

        // The height accessor half of BlockGetter; nothing here reads it, but it has to be answered
        // for the interface to be implemented at all.
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

        assertFalse(space.isBridged(), "no io means the P1/P2 boundary");
        assertEquals(0, space.externalSignal(), "omnidirectional supply");
        for (Direction direction : Direction.values()) {
            assertEquals(0, space.externalSignal(direction), "and per side: " + direction);
        }
    }

    /**
     * P4's world-to-inside half, end to end and without a game.
     *
     * <p>The component is at local {@code (1, 0, 2)} of a structure whose pivot sits at
     * {@code (10, 64, 10)}, so its own world position is {@code (11, 64, 12)} and the block to its
     * east is {@code (12, 64, 12)}. The fake world answers 9 at exactly that position, through exactly
     * the direction a query from the host towards that neighbour would use - {@code EAST} - and
     * nowhere else. So "the machine reads the world it is really next to" is the whole of what this
     * asserts.
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

        // The block read has to happen - the solver's one question that needs the machine's own view
        // of itself rather than the index - and it has to be asked about the local coordinate.
        UnsupportedOperationException asked = assertThrows(UnsupportedOperationException.class,
                () -> space.isRedstoneConductor(4, 0, 0));
        assertTrue(asked.getMessage().contains(pos(4, 0, 0).toString()), asked.getMessage());
        assertEquals(List.of(pos(4, 0, 0)), blocks.queried,
                "and the structure was asked about the local coordinate, not about a world position");

        // With no way to read the structure - the state before the compat half installed itself, or a
        // machine that has just come apart - the answer degrades to "no" rather than throwing. Nothing
        // in the solve loop asks, so this can only ever under-report.
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

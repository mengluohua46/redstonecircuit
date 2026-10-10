package com.jiangyuefengyu.redstonecircuit.carrier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.ConnectionState;
import com.jiangyuefengyu.redstonecircuit.data.Slot;
import com.jiangyuefengyu.redstonecircuit.logic.CarrierLightNetwork;
import com.jiangyuefengyu.redstonecircuit.logic.CarriedTool;
import com.jiangyuefengyu.redstonecircuit.logic.PowerEnvironment;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;

import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The wrench pins a picked line towards a block <em>inside the same structure</em> that holds no
 * component of its own: the far end is read from the structure's own view, in the structure's own frame.
 *
 * <p>The pin lives on the picked record only, and the in-structure solve reads it; the device has no
 * record and the world store is untouched. The state cycle is the wrench's own AUTO -> ON -> OFF -> AUTO.
 */
class CarrierInsideDeviceLockTest {

    private static final CarrierId MINE =
            CarrierId.subLevel(UUID.fromString("c22611c6-0000-0000-0000-000000000000"));

    /** The picked host, with a supply on it. */
    private static final BlockPos A = new BlockPos(1, 0, 0);

    /** The wire beside it inside the same structure, on a DIFFERENT side from the device. */
    private static final BlockPos WIRE = new BlockPos(1, 0, 1);

    /** <b>The device</b>: a block of the same structure with no component of its own - the piston. */
    private static final BlockPos DEVICE = new BlockPos(2, 0, 0);

    /** The side from the pick towards the device, in the structure's own frame. */
    private static final Direction TOWARDS_DEVICE = Direction.EAST;

    /** The side from the pick towards its own neighbouring wire. */
    private static final Direction TOWARDS_WIRE = Direction.SOUTH;

    private final CarriedHostIndex index = new CarriedHostIndex();
    private final Map<Long, Slot> world = new HashMap<>();

    // ------------------------------------------------------------------ setup --

    private record MapWorld(Map<Long, Slot> slots) implements CarriedHostIndex.SlotWorld {

        @Override
        public Slot get(BlockPos position) {
            return slots.get(position.asLong());
        }

        @Override
        public void set(BlockPos position, Slot slot) {
            if (slot == null) {
                slots.remove(position.asLong());
            } else {
                slots.put(position.asLong(), slot);
            }
        }
    }

    /** One host, claimed at a world position - the shape the assembly callback produces. */
    private Slot carry(CarrierId carrier, BlockPos local, BlockPos worldPos, ComponentType type) {
        Slot slot = new Slot(type);
        world.put(worldPos.asLong(), slot);
        index.claim(carrier, local, worldPos, new MapWorld(world));
        return slot;
    }

    /**
     * A host with a supply, a wire beside it on one side, and a device <b>with no record at all</b> on
     * another - the state the inside gesture exists for.
     */
    private void machineWithADevice() {
        carry(MINE, A, new BlockPos(100, 64, 100), ComponentType.DUST);
        carry(MINE, WIRE, new BlockPos(100, 64, 101), ComponentType.DUST);
        index.at(MINE, A).slot().injectedPower = 15;
        assertNull(index.at(MINE, DEVICE), "the setup is the point: the device holds no component, so it "
                + "has no record - that is the block the inside gesture exists for");
    }

    private static CarriedSelection.Selection picked() {
        return new CarriedSelection.Selection(Level.OVERWORLD, MINE, A, new BlockPos(100, 64, 100));
    }

    /**
     * The production link decision, asked by reflection so a missing signature reads as a failed
     * assertion rather than a compile error.
     */
    @Nullable
    private static Object decide(@Nullable CarriedHostIndex index,
                                 CarriedSelection.Selection selected, BlockPos click,
                                 @Nullable CarrierId ownedBy, @Nullable CarrierPose pose,
                                 @Nullable BlockPos pickedLocal,
                                 @Nullable CarriedBlockRemoval.StructureViewState farEnd) {
        try {
            Class<?> interaction =
                    Class.forName("com.jiangyuefengyu.redstonecircuit.carrier.CarrierInteraction");
            Class<?> selection = Class.forName(
                    "com.jiangyuefengyu.redstonecircuit.carrier.CarriedSelection$Selection");
            java.lang.reflect.Method method = interaction.getDeclaredMethod("planLink",
                    CarriedHostIndex.class, selection, BlockPos.class, CarrierId.class, CarrierPose.class,
                    BlockPos.class, boolean.class, java.util.function.Function.class);
            method.setAccessible(true);
            java.util.function.Function<BlockPos, CarriedBlockRemoval.StructureViewState> view =
                    farEnd == null ? null : ignored -> farEnd;
            return method.invoke(null, index, selected, click, ownedBy, pose, pickedLocal, true, view);
        } catch (ReflectiveOperationException absent) {
            return null;
        }
    }

    /** The whole gesture, with the device answered as PRESENT by the structure's own view. */
    @Nullable
    private Object lockTowardsTheDevice() {
        return decide(index, picked(), DEVICE, MINE, identity(), A,
                CarriedBlockRemoval.StructureViewState.PRESENT);
    }

    /** The same gesture on an unturned structure: the identity rotation, so the two frames agree. */
    private static CarrierPose identity() {
        return CarrierPose.of(new Vector3d(-99.0, -64.0, -100.0), new Quaterniond());
    }

    // ------------------------------------------------------- the decision helpers --

    private static String status(Object decision) {
        return decision == null ? "absent" : read(decision, "status").toString();
    }

    private static boolean handled(Object decision) {
        return decision != null && Boolean.TRUE.equals(read(decision, "handled"));
    }

    @Nullable
    private static Object actionOf(@Nullable Object decision) {
        return decision == null ? null : read(decision, "action");
    }

    @Nullable
    private static Object read(Object target, String name) {
        try {
            java.lang.reflect.Method accessor = target.getClass().getDeclaredMethod(name);
            accessor.setAccessible(true);
            return accessor.invoke(target);
        } catch (ReflectiveOperationException broken) {
            return null;
        }
    }

    /** The side the action pinned, or {@code null}. */
    @Nullable
    private static Direction sideOf(@Nullable Object action) {
        return action == null ? null : (Direction) read(action, "side");
    }

    /** The ledger line for the action, built by the production writer. */
    @Nullable
    private static String insideLine(@Nullable Object action, CarriedHostIndex index) {
        try {
            java.lang.reflect.Method method = CarrierLinkLedger.class.getDeclaredMethod("insideLine",
                    Class.forName("com.jiangyuefengyu.redstonecircuit.logic.CarriedTool$Action"),
                    CarriedHostIndex.class);
            method.setAccessible(true);
            Object line = method.invoke(null, action, index);
            return line == null ? null : String.valueOf(line);
        } catch (ReflectiveOperationException absent) {
            return null;
        }
    }

    private ConnectionState stateTowards(CarrierId carrier, BlockPos from, BlockPos to) {
        return CarriedTool.stateTowards(index, carrier, from, to);
    }

    private void solve(CarrierId carrier, BlockPos... seeds) {
        CarrierSpace space = new CarrierSpace(carrier, index, new MapWorld(world), null, null);
        new CarrierLightNetwork(64).solve(space, List.of(seeds), 0L);
    }

    private int levelAt(CarrierId carrier, BlockPos pos) {
        CarrierSpace space = new CarrierSpace(carrier, index, new MapWorld(world), null, null);
        PowerEnvironment.Node node = space.nodeAt(pos.getX(), pos.getY(), pos.getZ());
        return node == null ? -1 : node.power();
    }

    // ------------------------------------------------------------------ cases --

    @Nested
    @DisplayName("pick a component, shift-click a device of the same machine: the line is pinned")
    class TheInsideLineIsLocked {

        @Test
        @DisplayName("the side towards the device is pinned, and it is the ONE side")
        void theRecordIsWritten() {
            machineWithADevice();

            Object decision = lockTowardsTheDevice();

            assertEquals("LOCKED_INSIDE", status(decision),
                    "the gesture must be a lock, not a refusal: "
                            + (decision == null ? "no decision exists on this build" : decision));
            assertTrue(handled(decision), "and the click is ours to consume");
            assertEquals(TOWARDS_DEVICE, sideOf(actionOf(decision)),
                    "the side is the one the device is on, in the structure's own frame: "
                            + index.at(MINE, A).slot().describe());
            assertTrue(index.at(MINE, A).slot().isRoutingLocked(), "A now has a locked side");
            assertFalse(index.at(MINE, A).slot().isOpen(TOWARDS_WIRE),
                    "so the side towards its own neighbouring wire is cut by implication");
            assertEquals(ConnectionState.ON, stateTowards(MINE, A, A.relative(TOWARDS_DEVICE)),
                    "and the override was really written into the record");
        }

        @Test
        @DisplayName("clicking again steps the same AUTO -> ON -> OFF -> AUTO cycle the wrench uses")
        void theCycleIsTheSameOne() {
            machineWithADevice();

            assertEquals("LOCKED_INSIDE", status(lockTowardsTheDevice()));
            assertEquals(ConnectionState.ON, stateTowards(MINE, A, A.relative(TOWARDS_DEVICE)),
                    "AUTO -> ON, the order WrenchLinks#next defines - the same method, so a line to a "
                            + "device inside the machine steps exactly like a line to the world outside it");
            assertEquals("LOCKED_INSIDE", status(lockTowardsTheDevice()));
            assertEquals(ConnectionState.OFF, stateTowards(MINE, A, A.relative(TOWARDS_DEVICE)));
            assertEquals("LOCKED_INSIDE", status(lockTowardsTheDevice()));
            assertEquals(ConnectionState.AUTO, stateTowards(MINE, A, A.relative(TOWARDS_DEVICE)),
                    "and back to automatic");
        }

        @Test
        @DisplayName("the ledger line says INSIDE, and is tellable apart from OUTSIDE and ACROSS")
        void theLedgerLineIsDistinguishable() {
            machineWithADevice();

            String line = insideLine(actionOf(lockTowardsTheDevice()), index);

            assertNotNull(line, "the line a report is grepped for must be buildable");
            assertTrue(line.startsWith("locked INSIDE the structure"),
                    "the first three words name the decision: " + line);
            assertFalse(line.contains("OUTSIDE"),
                    "it is NOT the world's own block, and the two lines have to be separable by grep: "
                            + line);
            assertFalse(line.contains("LINKED_ACROSS"),
                    "and it is not a cross-structure line either - that one means two records were "
                            + "written: " + line);
            assertTrue(line.contains("picked=s:c22611c6 local=1, 0, 0"), line);
            assertTrue(line.contains("(DUST)"), "the component's constant is on the log line: " + line);
            assertTrue(line.contains("side=east"), "the structure's own side: " + line);
            assertTrue(line.contains("state=ON"), line);
            assertTrue(line.contains("local 1, 0, 0 at 2, 0, 0"),
                    "the two positions in the structure's own frame, so a reader can check the adjacency "
                            + "without a conversion: " + line);
        }

        @Test
        @DisplayName("nothing is written at the far end: the device has no record and the world store is untouched")
        void theFarEndIsNotWritten() {
            machineWithADevice();
            Map<Long, Slot> before = new HashMap<>(world);

            lockTowardsTheDevice();

            assertNull(index.at(MINE, DEVICE), "the device holds no component, so there is nothing to "
                    + "file at it - the lock lives entirely on the picked side");
            assertEquals(before, world, "and the world's own store is byte-for-byte unchanged");
        }
    }

    // ------------------------------------------- it really works, not only a display --

    @Nested
    @DisplayName("the lock really changes the solve - the neighbouring wire loses its power")
    class TheSolveReadsIt {

        @Test
        @DisplayName("before the lock the wire beside the pick carries 14; after it, 0")
        void theWireGoesDead() {
            machineWithADevice();
            solve(MINE, A, WIRE);
            assertEquals(15, levelAt(MINE, A));
            assertEquals(14, levelAt(MINE, WIRE),
                    "an automatic line carries the power along it, inside one structure as in the world");

            assertEquals("LOCKED_INSIDE", status(lockTowardsTheDevice()));

            solve(MINE, A, WIRE);
            assertEquals(0, levelAt(MINE, WIRE),
                    "and the solve reads the pin: the wire beside it has nothing to read any more. This is "
                            + "the difference between 'the record says so' and 'it works'");
            assertEquals(15, levelAt(MINE, A), "the component itself is untouched");
        }

        @Test
        @DisplayName("the fixture's two frames agree, so 'the wire went dead' cannot pass by accident")
        void theFixtureAgrees() {
            // Both ends are already in the structure's own frame, and the pose is a pure translation.
            assertEquals(Direction.SOUTH,
                    CarrierDirections.worldDirection(identity(), Direction.SOUTH),
                    "the fixture is unturned, so 'beside it inside the structure' and 'beside it in the "
                            + "world' are the same side");
            assertEquals(WIRE, A.relative(Direction.SOUTH));
            assertEquals(DEVICE, A.relative(Direction.EAST));
        }
    }

    @Nested
    @DisplayName("every refusal writes nothing")
    class Refusals {

        @Test
        @DisplayName("a block that shares no face with the pick is NOT_ADJACENT")
        void aDistantBlockIsNotAdjacent() {
            machineWithADevice();

            Object decision = decide(index, picked(), new BlockPos(9, 0, 9), MINE, identity(), A,
                    CarriedBlockRemoval.StructureViewState.PRESENT);

            assertEquals("NOT_ADJACENT", status(decision), String.valueOf(decision));
            assertNull(actionOf(decision));
            assertFalse(index.at(MINE, A).slot().isRoutingLocked(), "nothing was written");
        }

        @Test
        @DisplayName("an unreadable pose is refused, and the direction is never guessed")
        void anUnreadablePoseIsRefused() {
            machineWithADevice();

            Object decision = decide(index, picked(), DEVICE, MINE, null, A,
                    CarriedBlockRemoval.StructureViewState.PRESENT);

            assertEquals("UNREADABLE_POSE", status(decision),
                    "`CarrierDirections` answers the input direction unchanged for a null orientation, "
                            + "which is the silent wrong side this refuses to write: "
                            + String.valueOf(decision));
            assertFalse(index.at(MINE, A).slot().isRoutingLocked(), "nothing was written");
        }

        @Test
        @DisplayName("a pick that holds no component is NO_COMPONENT")
        void anEmptyPickIsRefused() {
            machineWithADevice();
            index.removeSlot(MINE, A);

            Object decision = decide(index, picked(), DEVICE, MINE, identity(), A,
                    CarriedBlockRemoval.StructureViewState.PRESENT);

            assertEquals("NO_COMPONENT", status(decision), String.valueOf(decision));
        }

        @Test
        @DisplayName("a coordinate the WORLD owns is not this gesture: it is the outside one")
        void theWorldsOwnBlockIsNotInside() {
            machineWithADevice();

            Object decision = decide(index, picked(), DEVICE, null, identity(), A,
                    CarriedBlockRemoval.StructureViewState.PRESENT);

            assertEquals("NOT_INSIDE", status(decision),
                    "the inside gesture answers for its own structure's coordinates only; a block nobody "
                            + "owns is the outside gesture's click");
            assertNull(actionOf(decision));
        }

        @Test
        @DisplayName("another structure's block is not this gesture either: that is the cross-structure path")
        void anotherStructuresBlockIsNotInside() {
            machineWithADevice();
            CarrierId other = CarrierId.subLevel(UUID.randomUUID());

            Object decision = decide(index, picked(), DEVICE, other, identity(), A,
                    CarriedBlockRemoval.StructureViewState.PRESENT);

            // Refused as before, but not silently: one record is written here where the cross-structure
            // path writes two, so a click on another structure's block has to be nameable.
            assertEquals("FAR_END_NOT_THE_PICKED_STRUCTURE", status(decision),
                    "two records could be written at a cross-structure far end, so it belongs to the path "
                            + "that writes both - this one writes one, and now says so: "
                            + String.valueOf(decision));
            assertNull(actionOf(decision));
            assertTrue(String.valueOf(read(decision, "blockedBy")).contains("another-structure"),
                    "and the step that answered is on the decision, so the trace can print it: "
                            + String.valueOf(decision));
        }

        @Test
        @DisplayName("air on that side is NO_SUCH_BLOCK, the world's own nothing_there")
        void airOnThatSideIsRefused() {
            machineWithADevice();

            Object decision = decide(index, picked(), DEVICE, MINE, identity(), A,
                    CarriedBlockRemoval.StructureViewState.GONE);

            assertEquals("NO_SUCH_BLOCK", status(decision),
                    "the structure's own view says there is nothing there, so there is nothing to pin a "
                            + "line towards: " + String.valueOf(decision));
            assertFalse(index.at(MINE, A).slot().isRoutingLocked(), "nothing was written");
        }
    }
}

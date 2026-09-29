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
 * <b>P1.33 commit B: the far end of the inside lock is judged by whether it holds a COMPONENT, not by
 * whether it has a record.</b>
 *
 * <h2>The defect, in the author's own line</h2>
 * P1.32's commit C added the lock towards a device inside the structure - the author's "物理化的活塞"
 * gesture - and on the author's machine it refused every time:
 *
 * <pre>
 *   carrier s:7061d621: filed the carrier host a plot cell was missing local=20481032, 128, 20481032
 *       origin=-2, -59, -5 (record=created empty) …
 *   carrier entry trace: … raw=20481032, 128, 20481032 … -&gt; REFUSED refused: this carried host is EMPTY and
 *       the picked host is not this one, so there is nothing to pin towards it either (INSIDE: NOT_INSIDE)
 * </pre>
 *
 * <p>The two lines belong to one click. The first <b>created</b> the record the second then tripped over:
 * the ownership question ({@code CarrierClickPriority#owner}) used to answer <b>null</b> for a coordinate
 * the index holds a record at, on the assumption that an earlier frame had already answered for every live
 * record - an assumption that is false in production, because that earlier frame asks the structure's own
 * storage without a level. {@code planLink} therefore saw {@code ownedByCarrier == null}, read it as
 * "nobody owns this coordinate" and answered {@code NOT_INSIDE} for a block of the picked structure
 * itself.
 *
 * <h2>What this round changes, and what it deliberately does not</h2>
 * The ownership answer is decided by the RECORD and only tags itself with it
 * ({@code owner=host} / {@code owner=sublevel}): a plot cell of the picked structure is the picked
 * structure's <b>either way</b>, so <code>slot == null</code> - whether because the record is empty or
 * because there is no record at all - reaches the INSIDE device lock. The three genuine refusals are
 * untouched and each still writes nothing: air on that side ({@code NO_SUCH_BLOCK}), a block that shares
 * no face with the pick ({@code NOT_ADJACENT}), and a pose that cannot be read
 * ({@code UNREADABLE_POSE}). And a click whose far end is <b>nobody's</b> coordinate is still
 * {@code NOT_INSIDE} (that is P1.31's outside gesture or the cross-structure path) - asserted unchanged
 * by {@code CarrierInsideDeviceLockTest}.
 *
 * <h2>What is asserted here</h2>
 * <ol>
 *   <li><b>the far cell holds an EMPTY host record</b> - the author's own state - and the line is locked
 *       anyway: the record is written, the pick's other sides are cut, and the block beside it loses its
 *       power in a real in-structure solve (14 → 0);</li>
 *   <li><b>the far cell has no record at all</b>: the same lock, and the same evidence;</li>
 *   <li><b>nothing is written at the far end</b> in either case, and the world store is untouched;</li>
 *   <li><b>the three refusals write nothing either</b>;</li>
 *   <li><b>the clicked block being the picked one is not this gesture</b> - it is the world's own
 *       "drop every override" (or {@code SAME_HOST} when there was nothing to drop), which P1.33 asks
 *       through one predicate in both entrances.</li>
 * </ol>
 */
class CarrierInsidePlotCellLockTest {

    private static final CarrierId MINE =
            CarrierId.subLevel(UUID.fromString("7061d621-0000-0000-0000-000000000000"));

    /** The picked host, with a supply on it. */
    private static final BlockPos A = new BlockPos(1, 0, 0);

    /** A wire of the same structure, on a DIFFERENT side from the device. */
    private static final BlockPos WIRE = new BlockPos(1, 0, 1);

    /** <b>The device</b>: the piston, a block of the same structure that holds no component. */
    private static final BlockPos DEVICE = new BlockPos(2, 0, 0);

    /** The side from the pick towards the device, in the structure's own frame. */
    private static final Direction TOWARDS_DEVICE = Direction.EAST;

    /** The side from the pick towards its own neighbouring wire. */
    private static final Direction TOWARDS_WIRE = Direction.SOUTH;

    private static final BlockPos A_WORLD = new BlockPos(100, 64, 100);
    private static final BlockPos DEVICE_WORLD = new BlockPos(101, 64, 100);

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

    private Slot carry(CarrierId carrier, BlockPos local, BlockPos worldPos, ComponentType type) {
        Slot slot = new Slot(type);
        world.put(worldPos.asLong(), slot);
        index.claim(carrier, local, worldPos, new MapWorld(world));
        return slot;
    }

    /** The author's machine: a host with a supply and a wire beside it, and a device that holds nothing. */
    private void machineWithADevice() {
        carry(MINE, A, A_WORLD, ComponentType.DUST);
        carry(MINE, WIRE, A_WORLD.relative(Direction.SOUTH), ComponentType.DUST);
        index.at(MINE, A).slot().injectedPower = 15;
    }

    /**
     * <b>The state the author's save is in</b> (and the state the previous build created for them): a
     * live carrier record at the device's coordinate that holds nothing at all.
     */
    private void deviceHasAnEmptyRecord() {
        index.registerAt(MINE, DEVICE, DEVICE_WORLD, DEVICE, new MapWorld(world));
        assertNotNull(index.at(MINE, DEVICE), "the setup is the point: the record exists");
        assertNull(index.at(MINE, DEVICE).slot(), "and it is EMPTY - the block never held a component");
    }

    private static CarriedSelection.Selection picked() {
        return new CarriedSelection.Selection(Level.OVERWORLD, MINE, A, A_WORLD);
    }

    private static CarrierPose identity() {
        return CarrierPose.of(new Vector3d(-99.0, -64.0, -100.0), new Quaterniond());
    }

    // --------------------------------------------- the decisions, by reflection --

    /**
     * {@code CarrierClickPriority#plotCellOwnership(owner, index, pos, poses, alive)} - the ownership
     * answer the lock is decided from, asked with the structure the plot lookup named.
     */
    @Nullable
    private CarrierClickPriority.Owned ownership() {
        try {
            Class<?> priority =
                    Class.forName("com.jiangyuefengyu.redstonecircuit.carrier.CarrierClickPriority");
            java.lang.reflect.Method method = priority.getDeclaredMethod("plotCellOwnership",
                    CarrierId.class, CarriedHostIndex.class, BlockPos.class,
                    CarrierStore.PoseSource.class, java.util.function.Predicate.class);
            method.setAccessible(true);
            return (CarrierClickPriority.Owned) method.invoke(null, MINE, index, DEVICE,
                    (CarrierStore.PoseSource) owner -> MINE.equals(owner) ? identity() : null,
                    (java.util.function.Predicate<CarrierId>) carrier -> true);
        } catch (ReflectiveOperationException absent) {
            // The previous build: the ownership answer has no case for a plot cell it holds a record for,
            // so the whole gesture is refused at the first question - which is the defect.
            return null;
        }
    }

    /** {@code CarrierInteraction#planLink(...)} through its production signature. */
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

    /**
     * {@code CarrierInteraction#isThePickedHostItself(selected, carrier, localPos)} - the predicate both
     * entrances ask before this gesture, so a click on the picked block is never read as a device lock.
     */
    private static boolean isThePickedHostItself(@Nullable CarriedSelection.Selection selected,
                                                 CarrierId carrier, BlockPos localPos) {
        try {
            Class<?> interaction =
                    Class.forName("com.jiangyuefengyu.redstonecircuit.carrier.CarrierInteraction");
            Class<?> selection = Class.forName(
                    "com.jiangyuefengyu.redstonecircuit.carrier.CarriedSelection$Selection");
            java.lang.reflect.Method method = interaction.getDeclaredMethod("isThePickedHostItself",
                    selection, CarrierId.class, BlockPos.class);
            method.setAccessible(true);
            return Boolean.TRUE.equals(method.invoke(null, selected, carrier, localPos));
        } catch (ReflectiveOperationException absent) {
            return false;
        }
    }

    // ------------------------------------------------------- the decision helpers --

    private static String status(Object decision) {
        return decision == null ? "absent" : read(decision, "status").toString();
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

    /** The lock, with the far end answered the way the structure's own view answers a piston. */
    private Object lockTowardsTheDevice() {
        CarrierClickPriority.Owned owned = ownership();
        assertNotNull(owned, "the far end is a plot cell of the picked structure, with or without a "
                + "record: the ownership answer has to name it");
        return decide(index, picked(), DEVICE, owned.carrier(), identity(), A,
                CarriedBlockRemoval.StructureViewState.PRESENT);
    }

    // ------------------------------------------------------------------ cases --

    @Nested
    @DisplayName("the far cell has an EMPTY host record - the author's own state - and the line locks anyway")
    class TheEmptyRecordFarEnd {

        @Test
        @DisplayName("ownership names the structure, and the tag says there is a record")
        void theEmptyRecordIsStillTheStructuresOwn() {
            machineWithADevice();
            deviceHasAnEmptyRecord();

            CarrierClickPriority.Owned owned = ownership();

            assertNotNull(owned, "an empty record must not answer `not ours`: this is the whole of P1.33's "
                    + "second commit, and the line the author was refused on");
            assertEquals(MINE, owned.carrier());
            assertEquals(DEVICE, owned.localPos(), "the coordinate IS the structure's local coordinate");
            assertEquals(CarrierClickPriority.Probe.HOST, owned.probe(),
                    "there IS a record, so the migration must not be handed this coordinate");
        }

        @Test
        @DisplayName("the side towards the device is pinned, and it is the one side")
        void theRecordIsWritten() {
            machineWithADevice();
            deviceHasAnEmptyRecord();

            Object decision = lockTowardsTheDevice();

            assertEquals("LOCKED_INSIDE", status(decision),
                    "the gesture must be a lock and not a refusal: "
                            + (decision == null ? "no decision exists on this build" : decision));
            assertEquals(TOWARDS_DEVICE, read(actionOf(decision), "side"));
            assertTrue(index.at(MINE, A).slot().isRoutingLocked(), "A now has a locked side");
            assertFalse(index.at(MINE, A).slot().isOpen(TOWARDS_WIRE),
                    "so the side towards its own neighbouring wire is cut by implication");
            assertEquals(ConnectionState.ON, stateTowards(MINE, A, A.relative(TOWARDS_DEVICE)));
        }

        @Test
        @DisplayName("the ledger line is the INSIDE one, with both ends in the structure's own frame")
        void theLedgerLineIsTheInsideOne() {
            machineWithADevice();
            deviceHasAnEmptyRecord();

            String line = insideLine(actionOf(lockTowardsTheDevice()), index);

            assertNotNull(line, "the line a report is grepped for must be buildable");
            assertTrue(line.startsWith("locked INSIDE the structure"), line);
            assertFalse(line.contains("OUTSIDE"), line);
            assertFalse(line.contains("LINKED_ACROSS"), line);
            assertTrue(line.contains("the far end is a device inside this structure with no component of "
                    + "its own"), line);
            assertTrue(line.contains("local 1, 0, 0 at 2, 0, 0"),
                    "the two coordinates are in the structure's own frame and one face apart: " + line);
        }

        @Test
        @DisplayName("nothing is written at the far end, and the empty record stays empty")
        void theFarEndIsNotWritten() {
            machineWithADevice();
            deviceHasAnEmptyRecord();
            Map<Long, Slot> before = new HashMap<>(world);

            lockTowardsTheDevice();

            assertNull(index.at(MINE, DEVICE).slot(), "the device holds no component, so there is nothing "
                    + "to file at it - the lock lives entirely on the picked side");
            assertEquals(before, world, "and the world's own store is byte-for-byte unchanged");
        }

        @Test
        @DisplayName("the wire beside the pick carries 14 before the lock and 0 after it")
        void theWireGoesDead() {
            machineWithADevice();
            deviceHasAnEmptyRecord();
            solve(MINE, A, WIRE);
            assertEquals(15, levelAt(MINE, A));
            assertEquals(14, levelAt(MINE, WIRE), "an automatic line carries the power along it");

            assertEquals("LOCKED_INSIDE", status(lockTowardsTheDevice()));

            solve(MINE, A, WIRE);
            assertEquals(0, levelAt(MINE, WIRE),
                    "the solve reads the pin, so this is `it works` and not only `the record says so`");
            assertEquals(15, levelAt(MINE, A), "the component itself is untouched");
        }
    }

    @Nested
    @DisplayName("the far cell has no record at all: the same lock, and the same evidence")
    class TheMissingRecordFarEnd {

        @Test
        @DisplayName("ownership is `sublevel`, and the lock is written all the same")
        void theSameLockFromNoRecord() {
            machineWithADevice();
            assertNull(index.at(MINE, DEVICE), "the setup: this cell has no record at all");

            CarrierClickPriority.Owned owned = ownership();
            assertNotNull(owned, "P1.30's widening must keep working");
            assertEquals(CarrierClickPriority.Probe.SUB_LEVEL, owned.probe(),
                    "no record is `owner=sublevel` - the value the migration and the placement path read");

            Object decision = decide(index, picked(), DEVICE, owned.carrier(), identity(), A,
                    CarriedBlockRemoval.StructureViewState.PRESENT);

            assertEquals("LOCKED_INSIDE", status(decision),
                    "the two states of the far end decide the same thing: "
                            + (decision == null ? "no decision exists on this build" : decision));
            assertEquals(ConnectionState.ON, stateTowards(MINE, A, A.relative(TOWARDS_DEVICE)));
            assertFalse(index.at(MINE, A).slot().isOpen(TOWARDS_WIRE));
        }

        @Test
        @DisplayName("and the solve reads it too")
        void theSameSolveFromNoRecord() {
            machineWithADevice();
            solve(MINE, A, WIRE);
            assertEquals(14, levelAt(MINE, WIRE));

            Object decision = decide(index, picked(), DEVICE, MINE, identity(), A,
                    CarriedBlockRemoval.StructureViewState.PRESENT);

            assertEquals("LOCKED_INSIDE", status(decision));
            solve(MINE, A, WIRE);
            assertEquals(0, levelAt(MINE, WIRE));
        }
    }

    @Nested
    @DisplayName("every refusal still writes nothing")
    class Refusals {

        @Test
        @DisplayName("air on that side is NO_SUCH_BLOCK")
        void airOnThatSideIsRefused() {
            machineWithADevice();
            deviceHasAnEmptyRecord();

            Object decision = decide(index, picked(), DEVICE, MINE, identity(), A,
                    CarriedBlockRemoval.StructureViewState.GONE);

            assertEquals("NO_SUCH_BLOCK", status(decision), String.valueOf(decision));
            assertNull(actionOf(decision));
            assertFalse(index.at(MINE, A).slot().isRoutingLocked(), "nothing was written");
        }

        @Test
        @DisplayName("a block that shares no face with the pick is NOT_ADJACENT")
        void aDistantBlockIsNotAdjacent() {
            machineWithADevice();
            deviceHasAnEmptyRecord();

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
            deviceHasAnEmptyRecord();

            Object decision = decide(index, picked(), DEVICE, MINE, null, A,
                    CarriedBlockRemoval.StructureViewState.PRESENT);

            assertEquals("UNREADABLE_POSE", status(decision), String.valueOf(decision));
            assertNull(actionOf(decision));
            assertFalse(index.at(MINE, A).slot().isRoutingLocked(), "nothing was written");
        }

        @Test
        @DisplayName("a coordinate nobody owns stays NOT_INSIDE: that is the world's own gesture")
        void anUnownedCoordinateIsStillNotInside() {
            machineWithADevice();
            deviceHasAnEmptyRecord();

            Object decision = decide(index, picked(), DEVICE, null, identity(), A,
                    CarriedBlockRemoval.StructureViewState.PRESENT);

            assertEquals("NOT_INSIDE", status(decision),
                    "P1.31's outside gesture and the cross-structure path are decided by the same wiring a "
                            + "moment later, and neither is this one: " + String.valueOf(decision));
            assertNull(actionOf(decision));
        }
    }

    @Nested
    @DisplayName("the clicked block being the picked one is a different gesture")
    class ThePickedBlockItself {

        @Test
        @DisplayName("one predicate, and it is true only for the picked end")
        void thePredicateNamesThePickedEnd() {
            assertTrue(isThePickedHostItself(picked(), MINE, A),
                    "the picked block itself: this is the world's own `drop every override` gesture");
            assertFalse(isThePickedHostItself(picked(), MINE, DEVICE),
                    "the block beside it is exactly what the device lock is for");
            assertFalse(isThePickedHostItself(picked(), CarrierId.subLevel(UUID.randomUUID()), A),
                    "and the same coordinate in another structure is another structure's block");
            assertFalse(isThePickedHostItself(null, MINE, A), "no pick at all is not this gesture");
        }

        @Test
        @DisplayName("pointing at the picked block is not a line, and writes nothing")
        void theSameBlockIsNotALine() {
            machineWithADevice();
            deviceHasAnEmptyRecord();

            Object decision = decide(index, picked(), A, MINE, identity(), A,
                    CarriedBlockRemoval.StructureViewState.PRESENT);

            assertNull(actionOf(decision), "a line runs between TWO blocks, so this cannot be the device "
                    + "lock: the device is the block beside this one");
            assertFalse(index.at(MINE, A).slot().isRoutingLocked(), "and nothing was written");
        }
    }
}

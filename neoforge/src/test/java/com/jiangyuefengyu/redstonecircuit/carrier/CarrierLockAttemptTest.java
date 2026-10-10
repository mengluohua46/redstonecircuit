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
 * The lock chain: every click of the wrench gesture ends with either a lock or a <b>named refusal</b>.
 *
 * <p>The chain has two silent branches, and they are the ones a click can fall into: the inside gesture answers
 * {@code NOT_INSIDE} and the outside gesture {@code NOT_OUTSIDE} whenever the clicked coordinate belongs to a
 * structure that is <b>not</b> the picked one, and a dispatch that reads both as "this was the other gesture's
 * click" prints nothing. Two separately assembled blocks are two sub-levels - Sable's own granularity - so a
 * shift-click on the block beside the pick lands exactly in that hole. A far end of another structure is now
 * named {@code FAR_END_NOT_THE_PICKED_STRUCTURE}, with a {@code blocked-by=} step of its own.
 *
 * <p>What is pinned here: the happy paths through the real decision (a block inside the picked structure with
 * no component of its own locks {@code LOCKED_INSIDE}, an adjacent world block locks {@code LOCKED}, and both
 * write a line a report can grep); every refusal having a name <em>and</em> the step that answered it, driven
 * over every geometry and ownership combination this decision can be handed; and that the write is real,
 * because the in-structure solve reads the pin - so a lock is not a display.
 */
class CarrierLockAttemptTest {

    private static final CarrierId MINE =
            CarrierId.subLevel(UUID.fromString("e0677381-0000-0000-0000-000000000000"));
    private static final CarrierId THEIRS =
            CarrierId.subLevel(UUID.fromString("7061d621-0000-0000-0000-000000000000"));

    /** The picked host, with a supply on it. */
    private static final BlockPos A = new BlockPos(1, 0, 0);

    /** The far end of the gesture: a cell of the same structure that holds nothing. */
    private static final BlockPos DEVICE = new BlockPos(2, 0, 0);

    /** A wire of the same structure, on the side the lock does NOT pin. */
    private static final BlockPos WIRE = new BlockPos(1, 0, 1);

    /** A coordinate that shares no face with {@link #A}. */
    private static final BlockPos FAR_AWAY = new BlockPos(9, 0, 9);

    private static final BlockPos A_WORLD = new BlockPos(100, 64, 100);

    /** The world block standing beside the pick. */
    private static final BlockPos WORLD_BESIDE = A_WORLD.relative(Direction.EAST);

    private static final BlockPos WORLD_FAR = new BlockPos(140, 64, 100);

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

    /** The machine: a host with a supply, a wire beside it, and a block that holds nothing. */
    private void machineWithADevice() {
        carry(MINE, A, A_WORLD, ComponentType.DUST);
        carry(MINE, WIRE, A_WORLD.relative(Direction.SOUTH), ComponentType.DUST);
        index.at(MINE, A).slot().injectedPower = 15;
    }

    private static CarriedSelection.Selection picked() {
        return new CarriedSelection.Selection(Level.OVERWORLD, MINE, A, A_WORLD);
    }

    private static CarrierPose identity() {
        return CarrierPose.of(new Vector3d(-99.0, -64.0, -100.0), new Quaterniond());
    }

    // ------------------------------------------------------- the real decision --

    /** {@code CarrierInteraction#planLink} through its production signature. */
    @Nullable
    private static Object decide(@Nullable CarriedHostIndex index,
                                 @Nullable CarriedSelection.Selection selected, BlockPos click,
                                 @Nullable CarrierId ownedBy, @Nullable CarrierPose pose,
                                 @Nullable BlockPos pickedPos, boolean inside,
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
            return method.invoke(null, index, selected, click, ownedBy, pose, pickedPos, inside, view);
        } catch (ReflectiveOperationException absent) {
            return null;
        }
    }

    /** {@code CarrierInteraction#planOutside(index, selected, click, owned, pose, pickedWorld)}. */
    @Nullable
    private static Object planOutside(@Nullable CarriedHostIndex index,
                                      CarriedSelection.Selection selected, BlockPos click,
                                      boolean ownedByAStructure, @Nullable CarrierPose pose,
                                      @Nullable BlockPos pickedWorld) {
        try {
            Class<?> interaction =
                    Class.forName("com.jiangyuefengyu.redstonecircuit.carrier.CarrierInteraction");
            Class<?> selection = Class.forName(
                    "com.jiangyuefengyu.redstonecircuit.carrier.CarriedSelection$Selection");
            java.lang.reflect.Method method = interaction.getDeclaredMethod("planOutside",
                    CarriedHostIndex.class, selection, BlockPos.class, boolean.class, CarrierPose.class,
                    BlockPos.class);
            method.setAccessible(true);
            return method.invoke(null, index, selected, click, ownedByAStructure, pose, pickedWorld);
        } catch (ReflectiveOperationException absent) {
            return null;
        }
    }

    private static String status(Object decision) {
        return decision == null ? "absent" : String.valueOf(read(decision, "status"));
    }

    private static String blockedBy(Object decision) {
        Object value = decision == null ? null : read(decision, "blockedBy");
        return value == null ? "" : String.valueOf(value);
    }

    @Nullable
    private static Object actionOf(@Nullable Object decision) {
        return decision == null ? null : read(decision, "action");
    }

    private static boolean handled(Object decision) {
        Object value = decision == null ? null : read(decision, "handled");
        return Boolean.TRUE.equals(value);
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
    private static String lineFor(Object decision, CarrierId carrier, CarriedHostIndex index) {
        Object action = actionOf(decision);
        if (action == null) {
            return null;
        }
        boolean inside = "LOCKED_INSIDE".equals(status(decision));
        try {
            java.lang.reflect.Method method = CarrierLinkLedger.class.getDeclaredMethod(
                    inside ? "insideLine" : "outsideLine",
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

    /** The inside gesture, with the far end answered the way the structure's own view answers a piston. */
    private Object lockTowardsTheDevice(CarrierId owner) {
        return decide(index, picked(), DEVICE, owner, identity(), A, true,
                CarriedBlockRemoval.StructureViewState.PRESENT);
    }

    /** The outside gesture: an adjacent world block owned by nobody. */
    private Object lockTowardsTheWorld() {
        return planOutside(index, picked(), WORLD_BESIDE, false, identity(), A_WORLD);
    }

    // ------------------------------------------------------------------ cases --

    @Nested
    @DisplayName("the two far ends the gesture has, and both reach a decision")
    class TheTwoFarEnds {

        @Test
        @DisplayName("inside: a block of the picked structure with no component locks INSIDE, and it is named")
        void anInsideBlockLocks() {
            machineWithADevice();

            Object decision = lockTowardsTheDevice(MINE);

            assertEquals("LOCKED_INSIDE", status(decision),
                    "the whole gesture has to reach this answer: "
                            + (decision == null ? "no decision exists at all" : decision));
            assertTrue(handled(decision), "and it is an outcome that consumes the click");
            assertEquals(Direction.EAST, read(actionOf(decision), "side"),
                    "the side towards the device, in the structure's own frame");
            ConnectionState after = stateTowards(MINE, A, DEVICE);
            assertEquals(ConnectionState.ON, after, "the lock was really written on the pick");

            String line = lineFor(decision, MINE, index);
            assertNotNull(line, "and it has a line a report can grep");
            assertTrue(line.startsWith("locked INSIDE the structure"), line);
            assertTrue(line.contains("local 1, 0, 0 at 2, 0, 0"),
                    "both ends in the structure's own frame, one face apart: " + line);
        }

        @Test
        @DisplayName("outside: an adjacent world block locks OUTSIDE, and it is named")
        void anOutsideBlockLocks() {
            machineWithADevice();

            Object decision = lockTowardsTheWorld();

            assertEquals("LOCKED", status(decision),
                    "the world-side gesture: " + (decision == null ? "no decision exists at all" : decision));
            assertEquals(Direction.EAST, read(actionOf(decision), "side"));

            String line = lineFor(decision, MINE, index);
            assertNotNull(line);
            assertTrue(line.startsWith("locked OUTSIDE the structure"), line);
            assertTrue(line.contains("world 100, 64, 100 at 101, 64, 100"),
                    "both ends as world coordinates, so the one conversion can be checked: " + line);
        }

        @Test
        @DisplayName("the write is real: the wire beside the pick carries 14 before the lock and 0 after")
        void theInsideLockIsReadByTheSolve() {
            machineWithADevice();
            solve(MINE, A, WIRE);
            assertEquals(15, levelAt(MINE, A));
            assertEquals(14, levelAt(MINE, WIRE), "an automatic line carries the power along it");

            assertEquals("LOCKED_INSIDE", status(lockTowardsTheDevice(MINE)));

            solve(MINE, A, WIRE);
            assertEquals(0, levelAt(MINE, WIRE),
                    "the solve reads the pin, so this is `it works` and not only `the record says so`");
        }
    }

    @Nested
    @DisplayName("every refusal has a name AND the step that answered")
    class EveryRefusalIsNamed {

        @Test
        @DisplayName("a block that shares no face is NOT_ADJACENT, with the two coordinates in the step")
        void notAdjacent() {
            machineWithADevice();

            Object decision = decide(index, picked(), FAR_AWAY, MINE, identity(), A, true,
                    CarriedBlockRemoval.StructureViewState.PRESENT);

            assertEquals("NOT_ADJACENT", status(decision), String.valueOf(decision));
            assertFalse(handled(decision), "nothing was written");
            assertTrue(blockedBy(decision).startsWith("not-adjacent"), blockedBy(decision));
            assertTrue(blockedBy(decision).contains(FAR_AWAY.toShortString()), blockedBy(decision));
        }

        @Test
        @DisplayName("a pose that cannot be read is UNREADABLE_POSE, and the side is never guessed")
        void unreadablePose() {
            machineWithADevice();

            Object decision = decide(index, picked(), DEVICE, MINE, null, A, true,
                    CarriedBlockRemoval.StructureViewState.PRESENT);

            assertEquals("UNREADABLE_POSE", status(decision), String.valueOf(decision));
            assertTrue(blockedBy(decision).startsWith("unreadable-pose"), blockedBy(decision));
            assertFalse(index.at(MINE, A).slot().isRoutingLocked(), "nothing was written");
        }

        @Test
        @DisplayName("air on that side is NO_SUCH_BLOCK, named as hard as the world's own nothing_there")
        void noSuchBlock() {
            machineWithADevice();

            Object decision = decide(index, picked(), DEVICE, MINE, identity(), A, true,
                    CarriedBlockRemoval.StructureViewState.GONE);

            assertEquals("NO_SUCH_BLOCK", status(decision), String.valueOf(decision));
            assertTrue(blockedBy(decision).startsWith("no-such-block"), blockedBy(decision));
        }

        @Test
        @DisplayName("a far end of another structure is named rather than silently ignored")
        void anotherStructuresFarEndIsNamed() {
            machineWithADevice();

            Object decision = decide(index, picked(), DEVICE, THEIRS, identity(), A, true,
                    CarriedBlockRemoval.StructureViewState.PRESENT);

            assertEquals("FAR_END_NOT_THE_PICKED_STRUCTURE", status(decision),
                    "two records could be written at a cross-structure far end, so this still refuses - but "
                            + "it says so, because a silent branch here is what swallows the click: "
                            + String.valueOf(decision));
            assertTrue(blockedBy(decision).contains("another-structure"), blockedBy(decision));
            assertTrue(blockedBy(decision).contains(THEIRS.shortId()), blockedBy(decision));
            assertTrue(blockedBy(decision).contains(MINE.shortId()),
                    "and it names both structures, so the reader knows which two: " + blockedBy(decision));
            assertFalse(index.at(MINE, A).slot().isRoutingLocked(), "nothing was written");
        }

        @Test
        @DisplayName("an owned coordinate in the OUTSIDE gesture is NOT_OUTSIDE, and it names the step")
        void anOwnedCoordinateIsNotOutside() {
            machineWithADevice();

            Object decision = planOutside(index, picked(), DEVICE, true, identity(), A_WORLD);

            assertEquals("NOT_OUTSIDE", status(decision), String.valueOf(decision));
            assertTrue(blockedBy(decision).contains("inside-the-picked-structure"), blockedBy(decision));
        }

        @Test
        @DisplayName("a world block nobody owns in the INSIDE gesture is NOT_INSIDE, and it names the step")
        void anUnownedCoordinateIsNotInside() {
            machineWithADevice();

            Object decision = decide(index, picked(), DEVICE, null, identity(), A, true,
                    CarriedBlockRemoval.StructureViewState.PRESENT);

            assertEquals("NOT_INSIDE", status(decision), String.valueOf(decision));
            assertTrue(blockedBy(decision).contains("not-a-plot-cell"),
                    "this one really is the outside gesture's click, and the step says so: "
                            + blockedBy(decision));
        }

        @Test
        @DisplayName("nothing picked is NOTHING_PICKED, and the step says which gate answered")
        void nothingPicked() {
            machineWithADevice();

            Object decision = decide(index, null, DEVICE, MINE, identity(), null, true,
                    CarriedBlockRemoval.StructureViewState.PRESENT);

            assertEquals("NOTHING_PICKED", status(decision),
                    "the existing answer for a click with no near end: "
                            + (decision == null ? "no decision exists at all" : decision));
            assertTrue(blockedBy(decision).startsWith("no-pick"), blockedBy(decision));

            Object outside = planOutside(index, picked(), WORLD_FAR, false, identity(),
                    A_WORLD);
            assertEquals("NOT_ADJACENT", status(outside),
                    "and a world block that shares no face is the world's own refusal: " + outside);
            assertTrue(blockedBy(outside).startsWith("not-adjacent"), blockedBy(outside));
        }
    }

    @Nested
    @DisplayName("THE INVARIANT: no click of this gesture can end without a named answer")
    class NoSilentClick {

        /**
         * Every combination this decision can be handed, driven through the real chain.
         *
         * <p>For each geometry and each ownership answer the decision has to be either a lock or a <b>named</b>
         * refusal with a non-blank step: "locked" and "refused, and here is which question answered" are the
         * only two allowed outcomes, and a status a reader would have to call silent is not one of them.
         */
        @Test
        @DisplayName("every answer is a lock or a named refusal, with the step that produced it")
        void noAnswerIsSilent() {
            int checked = 0;
            for (boolean inside : new boolean[] {false, true}) {
                for (BlockPos click : List.of(DEVICE, FAR_AWAY, WORLD_BESIDE, WORLD_FAR)) {
                    for (CarrierId owner : new CarrierId[] {null, MINE, THEIRS}) {
                        for (CarrierPose pose : new CarrierPose[] {identity(), null}) {
                            for (BlockPos pickedPos : List.of(A, FAR_AWAY)) {
                                machineWithADevice();
                                Object decision = decide(index, picked(), click, owner, pose, pickedPos,
                                        inside, CarriedBlockRemoval.StructureViewState.PRESENT);
                                assertNotNull(decision,
                                        "no combination may end with no decision at all: inside=" + inside
                                                + " click=" + click + " owner=" + owner + " pose="
                                                + (pose == null ? "none" : "readable") + " pickedPos="
                                                + pickedPos);
                                String status = status(decision);
                                assertFalse(status.equals("absent") || status.isEmpty(),
                                        "every decision carries a status");
                                assertTrue(!blockedBy(decision).isBlank(),
                                        "and every status carries the step that answered it, so the one "
                                                + "trace line can print where a click was stopped: "
                                                + status);
                                if (!handled(decision)) {
                                    assertFalse(status.equals("LOCKED") || status.equals("LOCKED_INSIDE"),
                                            "an outcome that is not handled cannot claim to have locked");
                                }
                                checked++;
                            }
                        }
                    }
                }
            }
            assertEquals(2 * 4 * 3 * 2 * 2, checked, "the sweep is exhaustive over these axes");
        }
    }

    @Nested
    @DisplayName("the diagnostic that makes the next log answer in one grep")
    class TheLockAttemptTrace {

        @Test
        @DisplayName("the trace line is written from three places in the entry point, and each is gated")
        void theTraceIsWiredWhereALockAttemptEnds() throws java.io.IOException {
            String source = java.nio.file.Files.readString(java.nio.file.Path.of(
                            "src/main/java/com/jiangyuefengyu/redstonecircuit/carrier/CarrierInteraction.java"))
                    .replace("\r\n", "\n");

            assertTrue(source.contains("carrier lock attempt?:"),
                    "the line a report greps for must exist, with the words on it");
            assertTrue(source.contains("blocked-by="),
                    "and it must carry the step that answered, which is the whole point of adding it");
            assertTrue(source.contains("private static void lockAttempt("),
                    "built in one place, so its shape cannot drift from what it describes");
            assertTrue(source.contains("if (!RCConfig.debugLog() || player == null) {"),
                    "behind debugLog, like every other per-click line");
            assertTrue(source.contains("if (gesture != CarrierGesture.Kind.WRENCH) {"),
                    "and it is a WRENCH line: without the tool there is no lock gesture to have lost");
            assertTrue(source.contains("if (selected == null || selected.carrier() == null) {"),
                    "and it is a line about a click that HAD a near end: without a pick there is no lock "
                            + "attempt, which is what keeps it rare on a normal shift-click");
        }

        @Test
        @DisplayName("the dispatch reports the named far end instead of calling it the other gesture's click")
        void theDispatchReportsTheNamedFarEnd() throws java.io.IOException {
            String source = java.nio.file.Files.readString(java.nio.file.Path.of(
                            "src/main/java/com/jiangyuefengyu/redstonecircuit/carrier/CarrierInteraction.java"))
                    .replace("\r\n", "\n");

            assertTrue(source.contains("if (named.status() == OutsideStatus.FAR_END_NOT_THE_PICKED_STRUCTURE)"),
                    "the branch that turned a click on a physicalised block into a named refusal");
            // One predicate decides both "which statuses get a sentence" and "which get a line", so the
            // assertion is that it covers this status rather than that the status is on a second list.
            assertTrue(source.contains("FAR_END_NOT_THE_PICKED_STRUCTURE -> true;"),
                    "and the named-far-end status is on the one list both halves read, beside the four "
                            + "other named refusals");
            assertTrue(source.contains("if (needsANamedAnswer(status)) {"),
                    "which reportOutside asks for the action bar sentence and for the ledger line alike");
        }
    }
}

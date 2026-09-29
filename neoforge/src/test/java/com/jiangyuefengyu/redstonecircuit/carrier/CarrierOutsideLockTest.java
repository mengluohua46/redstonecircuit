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
import com.jiangyuefengyu.redstonecircuit.logic.PowerEnvironment;
import com.jiangyuefengyu.redstonecircuit.logic.WrenchLinks;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * <b>P1.31: the wrench can lock a carried host's line towards a block that stands OUTSIDE every
 * structure.</b>
 *
 * <h2>What the author asked for, and what was there before</h2>
 * <em>"能否加一个用红石扳手可以向外部方块锁定线路"</em> - pick a component inside a machine, shift-right-click
 * a block of the world standing beside it, and pin that line. Before this round that click did
 * <b>nothing at all</b> and said nothing about it: the carrier's resolver answered {@code null} (the
 * coordinate is not a carried host), and the world's wrench found no component in its own store at a
 * position it does not own. The component being pinned is not in the world's store to begin with - it
 * lives in the carrier index under {@code (carrier, localPos)} - so the world's handler could never
 * have served this gesture.
 *
 * <h2>The semantics, and the world's own sentence for them</h2>
 * {@code message.redstonecircuit.wrench.linked_on_device} is the world's own case: <em>the far end holds
 * no embedded redstone, so only this side is pinned - every other direction is cut</em>. That is exactly
 * what this gesture does, because a pin is a statement about the whole component
 * ({@code Slot#isClosed} derives the cuts) - and the tests below pin both halves: the record says one
 * side is pinned, <b>and the solve really stops feeding the other side</b>.
 *
 * <h2>What is asserted</h2>
 * <ol>
 *   <li><b>the lock is written</b>, on the side that faces the world block, in the structure's own
 *       frame, and it steps the same {@code AUTO -> ON -> OFF -> AUTO} cycle the rest of the wrench
 *       uses;</li>
 *   <li><b>the in-structure solve reads it</b>: a neighbouring wire that was carrying power from the
 *       picked component drops to <b>0</b> once the line is locked towards the outside block. This is
 *       the "it really works and is not only a display" evidence;</li>
 *   <li><b>the world store is not touched</b>: no entry is invented at the world block's coordinate,
 *       and none at the picked host's projection either. This is the round's own instruction - only the
 *       carrier record is written - and it is what keeps P1.30's "one source of truth" intact;</li>
 *   <li><b>six refusals, each with a name</b>: no pick, a coordinate a structure owns (the trap: a plot
 *       cell with no component in it yet), not adjacent, an unreadable pose (the direction is never
 *       guessed), no world position, and an empty host;</li>
 *   <li><b>the judgement line</b>: {@code locked OUTSIDE the structure … side=… (world A at B) state=…},
 *       which is distinguishable at a glance from a cross-structure line and from a world one.</li>
 * </ol>
 *
 * <h2>Before the fix (the red half, measured)</h2>
 * This file is written to <b>compile and run against the previous tree</b>: the decision is asked by
 * reflection, so on a build without {@code CarrierInteraction#planOutside} the lookup fails and every
 * assertion is answered "the decision does not exist" - which is the shape of this round's defect, since
 * a click on a world block used to write <b>nothing at all</b>, not the wrong thing.
 *
 * <p>Measured in a worktree at {@code d4be518} (the commit before this one) with only this file copied
 * in: <b>14 tests, 14 failed</b>, e.g.
 * {@code the gesture the author asked for must be a lock, not a refusal: no decision exists on this
 * build} and {@code the line a report is grepped for must be buildable}. Every one of them is a real
 * assertion red, not a compile-order accident.
 */
class CarrierOutsideLockTest {

    private static final CarrierId MINE =
            CarrierId.subLevel(UUID.fromString("d315200a-0000-0000-0000-000000000000"));

    /** The picked host, and the wire beside it inside the same structure. */
    private static final BlockPos A = new BlockPos(1, 0, 0);
    private static final BlockPos WIRE = new BlockPos(1, 0, 1);

    /** Where the structure puts those two: the pose below is exactly this translation. */
    private static final BlockPos A_WORLD = new BlockPos(100, 64, 100);
    private static final BlockPos WIRE_WORLD = new BlockPos(100, 64, 101);

    /** The world block the player points at: one face away from the picked host, to the world EAST. */
    private static final BlockPos OUTSIDE = new BlockPos(101, 64, 100);

    /** A world block that shares no face with the picked host. */
    private static final BlockPos FAR = new BlockPos(140, 64, 100);

    private static final ResourceKey<Level> DIMENSION = ResourceKey.create(Registries.DIMENSION,
            ResourceLocation.parse("redstonecircuit:carrieroutsidelocktest"));

    private final CarriedHostIndex index = new CarriedHostIndex();
    private final Map<Long, Slot> world = new HashMap<>();

    // ------------------------------------------------------------------ setup --

    /** The world store, as a map - the same stand-in the index's own tests use. */
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

    /** A pose that is a plain translation from local to world - the same shape the author's claims print. */
    private static CarrierPose moving(BlockPos local, BlockPos worldPos) {
        return CarrierPose.of(new Vector3d(worldPos.getX() - local.getX(),
                worldPos.getY() - local.getY(), worldPos.getZ() - local.getZ()), new Quaterniond());
    }

    /** The two hosts every "the lock works" test starts from, with A given a supply to route. */
    private void twoWiresWithPower() {
        carry(MINE, A, A_WORLD, ComponentType.DUST);
        carry(MINE, WIRE, WIRE_WORLD, ComponentType.DUST);
        index.at(MINE, A).slot().injectedPower = 15;
    }

    private static CarriedSelection.Selection pick(CarrierId carrier, BlockPos local, BlockPos worldPos) {
        return new CarriedSelection.Selection(DIMENSION, carrier, local, worldPos);
    }

    /** The pick every gesture in this file starts from: the host inside the structure. */
    private static CarriedSelection.Selection picked() {
        return pick(MINE, A, A_WORLD);
    }

    // ------------------------------------------------ the decision, asked by name --
    //
    // ASKED BY REFLECTION ON PURPOSE. The three types this round adds (`OutsideDecision`,
    // `OutsideStatus`, `LockedOutside`) did not exist on the previous tree, so a direct call would make
    // this file a compile error there rather than a red assertion - and the round's report has to say
    // which of the two it is. Asked by name, the failure is a plain, readable "the decision does not
    // exist", which is the same statement P1.27's `CarrierCarriedInsertPlanTest` made.

    /** {@code CarrierInteraction#planOutside(index, pick, click, owned, pose, pickedWorld)}. */
    @Nullable
    private static Object decide(@Nullable CarriedHostIndex index,
                                 @Nullable CarriedSelection.Selection selected, BlockPos click,
                                 boolean owned, @Nullable CarrierPose pose,
                                 @Nullable BlockPos pickedWorld) {
        try {
            Class<?> interaction =
                    Class.forName("com.jiangyuefengyu.redstonecircuit.carrier.CarrierInteraction");
            Class<?> decision =
                    Class.forName("com.jiangyuefengyu.redstonecircuit.carrier.CarrierInteraction$OutsideDecision");
            Class<?> selection = Class.forName(
                    "com.jiangyuefengyu.redstonecircuit.carrier.CarriedSelection$Selection");
            java.lang.reflect.Method method = interaction.getDeclaredMethod("planOutside",
                    CarriedHostIndex.class, selection, BlockPos.class, boolean.class, CarrierPose.class,
                    BlockPos.class);
            method.setAccessible(true);
            Object result = method.invoke(null, index, selected, click, owned, pose, pickedWorld);
            assertNotNull(result, "the decision exists, so it must answer with a value");
            assertTrue(decision.isInstance(result), "and it must answer with its own type");
            return result;
        } catch (ReflectiveOperationException absent) {
            return null;
        }
    }

    /** The status of one decision, by name - {@code "absent"} on a build without the decision. */
    private static String status(Object decision) {
        if (decision == null) {
            return "absent";
        }
        try {
            java.lang.reflect.Method accessor = decision.getClass().getDeclaredMethod("status");
            accessor.setAccessible(true);
            return String.valueOf(accessor.invoke(decision));
        } catch (ReflectiveOperationException broken) {
            return "unreadable";
        }
    }

    /** The action one decision produced, or {@code null}. */
    @Nullable
    private static Object actionOf(@Nullable Object decision) {
        if (decision == null) {
            return null;
        }
        try {
            java.lang.reflect.Method accessor = decision.getClass().getDeclaredMethod("action");
            accessor.setAccessible(true);
            return accessor.invoke(decision);
        } catch (ReflectiveOperationException broken) {
            return null;
        }
    }

    /** Whether the click was ours - the one answer that consumes the event. */
    private static boolean handled(@Nullable Object decision) {
        if (decision == null) {
            return false;
        }
        try {
            java.lang.reflect.Method accessor = decision.getClass().getDeclaredMethod("handled");
            accessor.setAccessible(true);
            return (Boolean) accessor.invoke(decision);
        } catch (ReflectiveOperationException broken) {
            return false;
        }
    }

    /** The local side the action pinned, or {@code null}. */
    @Nullable
    private static Direction sideOf(@Nullable Object action) {
        return read(action, "side", Direction.class);
    }

    /** The state the action left on that side, or {@code null}. */
    @Nullable
    private static ConnectionState stateOfAction(@Nullable Object action) {
        return read(action, "state", ConnectionState.class);
    }

    @Nullable
    @SuppressWarnings("unchecked")
    private static <T> T read(@Nullable Object target, String name, Class<T> type) {
        if (target == null) {
            return null;
        }
        try {
            java.lang.reflect.Method accessor = target.getClass().getDeclaredMethod(name);
            accessor.setAccessible(true);
            return (T) accessor.invoke(target);
        } catch (ReflectiveOperationException broken) {
            return null;
        }
    }

    /** The ledger line for one action, built by the production writer. */
    @Nullable
    private String outsideLine(@Nullable Object action) {
        if (action == null) {
            return null;
        }
        try {
            java.lang.reflect.Method method = CarrierLinkLedger.class.getDeclaredMethod("outsideLine",
                    Class.forName("com.jiangyuefengyu.redstonecircuit.logic.CarriedTool$Action"),
                    CarriedHostIndex.class);
            method.setAccessible(true);
            Object line = method.invoke(null, action, index);
            return line == null ? null : String.valueOf(line);
        } catch (ReflectiveOperationException absent) {
            return null;
        }
    }

    // ------------------------------------------- the fixture's two frames agree --

    @Nested
    @DisplayName("the fixture itself: one translation, and both frames say the same thing")
    class TheFixtureAgrees {

        @Test
        @DisplayName("the unturned pose carries the wire SOUTH in the world too")
        void theTwoFramesAgree() {
            // Without this, "the wire beside the pick went dead" could pass for the wrong reason: a pose
            // that turned the structure would make the local SOUTH a different world direction, and the
            // solve's cut side would still be the right one only by accident. The pose is the identity
            // rotation, so the two frames must answer the same direction for the same pair of hosts.
            assertEquals(Direction.SOUTH,
                    CarrierDirections.worldDirection(moving(A, A_WORLD), Direction.SOUTH),
                    "the fixture is unturned, so 'beside it inside the structure' and 'beside it in the "
                            + "world' are the same side");
            assertEquals(WIRE_WORLD, A_WORLD.relative(Direction.SOUTH),
                    "and the two world positions this file names are one block apart along that side");
            assertEquals(WIRE, A.relative(Direction.SOUTH));
        }
    }

    // -------------------------------------------------------------- the solve --

    private void solve(CarrierId carrier, BlockPos... seeds) {
        CarrierSpace space = new CarrierSpace(carrier, index, new MapWorld(world), null, null);
        new CarrierLightNetwork(64).solve(space, List.of(seeds), 0L);
    }

    private int levelAt(CarrierId carrier, BlockPos pos) {
        CarrierSpace space = new CarrierSpace(carrier, index, new MapWorld(world), null, null);
        PowerEnvironment.Node node = space.nodeAt(pos.getX(), pos.getY(), pos.getZ());
        return node == null ? -1 : node.power();
    }

    private ConnectionState stateTowards(CarrierId carrier, BlockPos from, BlockPos to) {
        return com.jiangyuefengyu.redstonecircuit.logic.CarriedTool
                .stateTowards(index, carrier, from, to);
    }

    // ------------------------------------------------------- the main gesture --

    @Nested
    @DisplayName("pick a carried host, shift-right-click a world block: the line is pinned")
    class TheOutsideLineIsLocked {

        @Test
        @DisplayName("the record's side towards that world block is pinned, and it is the ONE side")
        void theRecordIsWritten() {
            twoWiresWithPower();

            Object decision = decide(index, picked(), OUTSIDE, false, moving(A, A_WORLD), A_WORLD);

            assertEquals("LOCKED", status(decision),
                    "the gesture the author asked for must be a lock, not a refusal: "
                            + (decision == null ? "no decision exists on this build" : decision));
            assertTrue(handled(decision), "and the click is ours to consume");
            // The world block is one block away along world +X, so the side that faces it is EAST in the
            // world - and this structure is unturned, so its own frame says EAST as well.
            assertEquals(Direction.EAST, sideOf(actionOf(decision)),
                    "the side is the one that faces the world block: "
                            + index.at(MINE, A).slot().describe());
            assertEquals(ConnectionState.ON, stateTowards(MINE, A, A.relative(Direction.EAST)),
                    "and the override was really written into the record");
            assertEquals(ConnectionState.ON, stateWrote(A, Direction.EAST));
        }

        @Test
        @DisplayName("clicking again steps the same AUTO -> ON -> OFF -> AUTO cycle the wrench uses")
        void theCycleIsTheSameOne() {
            twoWiresWithPower();

            assertEquals("LOCKED", status(decide(index, picked(), OUTSIDE, false, moving(A, A_WORLD),
                    A_WORLD)));
            assertEquals(ConnectionState.ON, stateWrote(A, Direction.EAST));
            assertEquals(ConnectionState.OFF, stateOfAction(actionOf(
                    decide(index, picked(), OUTSIDE, false, moving(A, A_WORLD), A_WORLD))),
                    "AUTO -> ON -> OFF, the order WrenchLinks#next defines - the same method, so a line to "
                            + "the outside world steps exactly like a line inside one");
            assertEquals(ConnectionState.AUTO, stateOfAction(actionOf(
                    decide(index, picked(), OUTSIDE, false, moving(A, A_WORLD), A_WORLD))),
                    "and back to automatic");
            assertEquals(ConnectionState.AUTO, stateWrote(A, Direction.EAST));
        }

        @Test
        @DisplayName("a turned structure gets ITS OWN side for the same world block")
        void aTurnedStructureGetsItsOwnSide() {
            twoWiresWithPower();
            // A quarter turn about the structure's own origin, so the world direction EAST is one of the
            // structure's own NORTH/SOUTH. The direction is computed once in the world and turned into the
            // structure's frame by the ONE conversion this mod has - never written as "the world direction".
            Quaterniond quarter = new Quaterniond().rotateY(Math.PI / 2.0);
            CarrierPose turned = CarrierPose.of(new Vector3d(0.0, 0.0, 0.0), quarter);

            Object decision = decide(index, picked(), OUTSIDE, false, turned, A_WORLD);

            assertEquals("LOCKED", status(decision), String.valueOf(decision));
            Direction expected = CarrierDirections.localDirection(turned, Direction.EAST);
            assertEquals(expected, sideOf(actionOf(decision)),
                    "the world side turned into the structure's own frame: "
                            + index.at(MINE, A).slot().describe());
            assertFalse(expected == Direction.EAST,
                    "the setup is only meaningful if the turn changed the answer: " + expected);
            assertEquals(ConnectionState.ON, stateTowards(MINE, A, A.relative(expected)));
        }

        @Test
        @DisplayName("the ledger line says OUTSIDE, names the side's frame and both world positions")
        void theLedgerLineIsDistinguishable() {
            twoWiresWithPower();

            Object decision = decide(index, picked(), OUTSIDE, false, moving(A, A_WORLD), A_WORLD);
            String line = outsideLine(actionOf(decision));

            assertNotNull(line, "the line a report is grepped for must be buildable");
            assertTrue(line.startsWith("locked OUTSIDE the structure"),
                    "the first three words name the decision; the old failure printed nothing at all: "
                            + line);
            assertTrue(line.contains("picked=s:d315200a local=1, 0, 0"), line);
            assertTrue(line.contains("(DUST)"), "the component's constant is on the log line: " + line);
            assertTrue(line.contains("side=east"), "the structure's own side: " + line);
            assertTrue(line.contains("state=ON"), line);
            assertTrue(line.contains("world 100, 64, 100 at 101, 64, 100"),
                    "the two WORLD positions, so a reader can check the one conversion: " + line);
            assertTrue(line.contains("only this end is pinned and every other side is cut"),
                    "and the semantics in words, which is the world's own `linked_on_device`: " + line);
            assertFalse(line.contains("LINKED_ACROSS"),
                    "a line to the world's own block is NOT a cross-structure line, and the two must be "
                            + "tellable apart by grep: " + line);
        }
    }

    // ------------------------------------------- it really works, not only a display --

    @Nested
    @DisplayName("the lock really changes the solve - the neighbouring wire loses its power")
    class TheSolveReadsIt {

        @Test
        @DisplayName("before the lock the wire beside the pick carries 14; after it, 0")
        void theWireGoesDead() {
            twoWiresWithPower();
            solve(MINE, A, WIRE);
            assertEquals(15, levelAt(MINE, A));
            assertEquals(14, levelAt(MINE, WIRE),
                    "an automatic line carries the power along it, inside one structure as in the world");

            Object decision = decide(index, picked(), OUTSIDE, false, moving(A, A_WORLD), A_WORLD);
            assertEquals("LOCKED", status(decision), String.valueOf(decision));
            assertTrue(index.at(MINE, A).slot().isRoutingLocked(),
                    "the record now has a locked side: " + index.at(MINE, A).slot().describe());
            assertFalse(index.at(MINE, A).slot().isOpen(Direction.SOUTH),
                    "so the side towards its own neighbouring wire is cut by implication");

            solve(MINE, A, WIRE);
            assertEquals(0, levelAt(MINE, WIRE),
                    "and the solve reads that: the wire beside it has nothing to read any more. This is "
                            + "the difference between 'the record says so' and 'it works'");
            assertEquals(15, levelAt(MINE, A), "the component itself is untouched");
        }

        @Test
        @DisplayName("a line locked outside the structure only cuts the sides that are not the pinned one")
        void thePinnedSideStaysTheOnlyOpenOne() {
            twoWiresWithPower();
            // A second wire on the OTHER side of the picked host, so "one side pinned" can be told apart
            // from "everything cut": the pinned side stays open, every other side is shut.
            BlockPos north = A.relative(Direction.NORTH);
            carry(MINE, north, A_WORLD.relative(Direction.NORTH), ComponentType.DUST);
            solve(MINE, A, WIRE, north);
            assertEquals(14, levelAt(MINE, WIRE));
            assertEquals(14, levelAt(MINE, north));

            decide(index, picked(), OUTSIDE, false, moving(A, A_WORLD), A_WORLD);

            solve(MINE, A, WIRE, north);
            assertEquals(0, levelAt(MINE, WIRE), "the side that was not pinned is cut");
            assertEquals(0, levelAt(MINE, north), "and so is the other one - a pin is a statement about "
                    + "the whole component, which is what makes it mean 'this line and nothing else'");
            assertTrue(index.at(MINE, A).slot().isOpen(Direction.EAST),
                    "while the pinned side itself stays open, facing the world block");
        }
    }

    // ---------------------------------------------------- the world is NOT written --

    @Nested
    @DisplayName("only the carrier record is written - the world store gains nothing")
    class TheWorldStoreIsUntouched {

        @Test
        @DisplayName("no entry is invented at the world block, and the picked host's entry is not re-filed")
        void noWorldEntryIsCreated() {
            twoWiresWithPower();
            world.remove(OUTSIDE.asLong());
            Map<Long, Slot> before = new HashMap<>(world);

            Object decision = decide(index, picked(), OUTSIDE, false, moving(A, A_WORLD), A_WORLD);

            assertEquals("LOCKED", status(decision), String.valueOf(decision));
            assertNull(world.get(OUTSIDE.asLong()),
                    "the world block was only POINTED AT: writing a store entry for it would invent a "
                            + "component for a block that has none, which is the two-sources-of-truth defect "
                            + "P1.26-P1.30 exist to remove");
            assertEquals(before.keySet(), world.keySet(),
                    "and no other coordinate was added or removed either: " + world.keySet());
        }

        @Test
        @DisplayName("a world block that ALREADY holds a component is still not written to")
        void anExistingWorldEntryIsNotTouched() {
            twoWiresWithPower();
            // The author's own case: the block outside happens to hold a component of its own, from the
            // world store. It is a different structure's business (or nobody's), and this gesture is about
            // the PICK's record - so the world's entry must come out byte for byte as it went in.
            Slot outsideEntry = new Slot(ComponentType.RETAINING_REPEATER);
            outsideEntry.facing = Direction.WEST;
            world.put(OUTSIDE.asLong(), outsideEntry);

            Object decision = decide(index, picked(), OUTSIDE, false, moving(A, A_WORLD), A_WORLD);

            assertEquals("LOCKED", status(decision), String.valueOf(decision));
            assertEquals(outsideEntry, world.get(OUTSIDE.asLong()),
                    "the same Slot object, untouched: this decision writes exactly one record and it is "
                            + "the carrier index's");
            assertTrue(outsideEntry.forcedOn.isEmpty() && outsideEntry.forcedOff.isEmpty(),
                    "with no override added to it: " + outsideEntry.describe());
        }
    }

    // ------------------------------------------------------------------ refusals --

    @Nested
    @DisplayName("every refusal has a name, and none of them writes anything")
    class EveryRefusalIsNamed {

        @Test
        @DisplayName("no pick: not this gesture at all, and nothing is written")
        void noPick() {
            twoWiresWithPower();

            Object decision = decide(index, null, OUTSIDE, false, moving(A, A_WORLD), A_WORLD);

            assertEquals("NOTHING_PICKED", status(decision), String.valueOf(decision));
            assertFalse(handled(decision), "the world's own wrench gesture keeps this click");
            assertEquals(ConnectionState.AUTO, stateWrote(A, Direction.EAST));
        }

        @Test
        @DisplayName("a coordinate a structure owns is NOT 'outside' - the empty plot cell trap")
        void aCoordinateAStructureOwnsIsRefused() {
            twoWiresWithPower();
            // The trap: a physicalised block whose host holds nothing yet. `hitAt` answers null for it (it
            // asks for a component), so the click reaches this decision - and taking it for a world block
            // would pin a line towards a plot coordinate two million blocks away.
            Object decision = decide(index, picked(), OUTSIDE, true, moving(A, A_WORLD), A_WORLD);

            assertEquals("NOT_OUTSIDE", status(decision), String.valueOf(decision));
            assertFalse(handled(decision));
            assertEquals(ConnectionState.AUTO, stateWrote(A, Direction.EAST),
                    "nothing was written on any side");
        }

        @Test
        @DisplayName("not adjacent: the world's own not_adjacent refusal, and not silent")
        void notAdjacent() {
            twoWiresWithPower();

            Object decision = decide(index, picked(), FAR, false, moving(A, A_WORLD), A_WORLD);

            assertEquals("NOT_ADJACENT", status(decision), String.valueOf(decision));
            assertFalse(handled(decision));
            assertEquals(ConnectionState.AUTO, stateWrote(A, Direction.EAST));
        }

        @Test
        @DisplayName("an unreadable pose refuses instead of guessing the direction")
        void anUnreadablePoseRefuses() {
            twoWiresWithPower();

            Object decision = decide(index, picked(), OUTSIDE, false, null, A_WORLD);

            assertEquals("UNREADABLE_POSE", status(decision),
                    "the world direction has to be turned into the structure's own frame, and "
                            + "CarrierDirections answers the world direction UNCHANGED for a null pose - "
                            + "which on a turned machine is the wrong side. Refusing is the only honest "
                            + "answer: " + (decision == null ? "no decision" : decision));
            assertFalse(handled(decision));
            assertEquals(ConnectionState.AUTO, stateWrote(A, Direction.EAST),
                    "and nothing was written on the guess this refuses to make");
        }

        @Test
        @DisplayName("no world position at all: refused, with its own name")
        void noWorldPosition() {
            twoWiresWithPower();

            Object decision = decide(index, picked(), OUTSIDE, false, moving(A, A_WORLD), null);

            assertEquals("UNREADABLE_POSITION", status(decision), String.valueOf(decision));
            assertFalse(handled(decision));
        }

        @Test
        @DisplayName("a picked host that holds nothing has nothing to route")
        void anEmptyHostIsRefused() {
            twoWiresWithPower();
            index.removeSlot(MINE, A);

            Object decision = decide(index, picked(), OUTSIDE, false, moving(A, A_WORLD), A_WORLD);

            assertEquals("NO_COMPONENT", status(decision), String.valueOf(decision));
            assertFalse(handled(decision));
        }
    }

    // ------------------------------------------------------------------ fixtures --

    /**
     * The override that stands on one side of the picked host, read through the production question.
     *
     * <p>Asked through {@code WrenchLinks#stateOf} rather than by poking at the sets, because that is the
     * method the wrench's own cycle steps through - "the record says ON" and "the wrench would read ON
     * here" must be one fact.
     */
    private ConnectionState stateWrote(BlockPos local, Direction side) {
        CarriedHost host = index.at(MINE, local);
        return host == null || host.slot() == null ? null : WrenchLinks.stateOf(host.slot(), side);
    }
}

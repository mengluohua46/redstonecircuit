package com.jiangyuefengyu.redstonecircuit.carrier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.ConnectionState;
import com.jiangyuefengyu.redstonecircuit.data.Slot;
import com.jiangyuefengyu.redstonecircuit.logic.CarriedTool;
import com.jiangyuefengyu.redstonecircuit.logic.CarrierLightNetwork;
import com.jiangyuefengyu.redstonecircuit.logic.PowerEnvironment;
import com.jiangyuefengyu.redstonecircuit.logic.WrenchLinks;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * <b>P1.27, defect B: the author's actual machine is two structures, so "a line only runs inside one
 * structure" refused every connection they tried.</b>
 *
 * <h2>The log, in one pair of lines</h2>
 * <pre>
 *   carrier claim s:4a3b8622 world=-7, -60, -11 local=20481032, 127, 20481032 type=RETAINING_REPEATER
 *   carrier claim s:8add8208 world=-7, -60, -12 local=20481032, 127, 20483080 type=DUST
 *   sable claim: 1 host(s) taken into sub-level 4a3b8622-… (sweep #1), 1 candidate(s) examined
 *   sable claim: 1 host(s) taken into sub-level 8add8208-… (sweep #1), 1 candidate(s) examined
 *   carrier s:8add8208: link refused: the pick is in ANOTHER structure … and the two blocks ARE
 *                        neighbours where they stand
 * </pre>
 *
 * <p>Two blocks standing side by side in the world, each assembled into <b>its own</b> sub-level - which
 * is Sable's granularity, not a mistake by the player - and every attempt to draw a line between them was
 * answered with a paragraph about structures. P1.26 refused it on the argument that "a line lives on the
 * two hosts' own records and nothing lets one carrier write into another's index"; that argument is about
 * an implementation that did not exist, not about a limit, because <b>both records are entries of the same
 * {@link CarriedHostIndex}</b>, keyed by {@code (carrier, localPos)}.
 *
 * <h2>What is pinned here</h2>
 * <ol>
 *   <li><b>the same structure still behaves exactly as before</b> - adjacency decided in the structure's
 *       own frame, both ends written, {@code AUTO -> ON -> OFF -> AUTO};</li>
 *   <li><b>two structures, world-adjacent: BOTH records are written</b>, each in its own frame, from one
 *       shared world face - and the ledger says so with four coordinates and both sides;</li>
 *   <li><b>two structures, and the far host holds nothing</b>: the near end is pinned and the far end is
 *       left alone, exactly as {@code FarEndEmpty} says inside one structure;</li>
 *   <li><b>two structures that are not neighbours</b>: still the named refusal, because there is no side
 *       to write on either end;</li>
 *   <li><b>the same host clicked twice</b>: the first click clears and ends the pick, the second says the
 *       pick is still this block and a line needs two blocks - the world's own semantics, in the carrier's
 *       own words, instead of the old and untrue "nothing has been picked yet";</li>
 *   <li><b>the lock really changes BOTH solves</b>: moving the lock on the near end cuts its other sides in
 *       its own structure's solve, and the far end's record carries the mirrored state - so neither end is
 *       silently left behind.</li>
 * </ol>
 */
class CarrierCrossLinkTest {

    private static final CarrierId MINE =
            CarrierId.subLevel(UUID.fromString("d315200a-0000-0000-0000-000000000000"));
    private static final CarrierId THEIRS =
            CarrierId.subLevel(UUID.fromString("f3b2d036-0000-0000-0000-000000000000"));

    /** Two local coordinates, in two structures. The world positions are what makes them neighbours. */
    private static final BlockPos A = new BlockPos(1, 0, 0);
    private static final BlockPos B = new BlockPos(2, 0, 0);

    /** Where the two blocks stand: side by side, the way the author's two did. */
    private static final BlockPos A_WORLD = new BlockPos(100, 64, 100);
    private static final BlockPos B_WORLD = new BlockPos(101, 64, 100);

    private static final ResourceKey<Level> DIMENSION = ResourceKey.create(Registries.DIMENSION,
            ResourceLocation.parse("redstonecircuit:carriercrosslinktest"));

    private final CarriedHostIndex index = new CarriedHostIndex();
    private final Map<Long, Slot> world = new HashMap<>();

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

    /** A pose that is a plain translation: the same shape the author's claims print. */
    private static CarrierPose moving(BlockPos local, BlockPos worldPos) {
        return CarrierPose.of(new Vector3d(worldPos.getX() - local.getX(),
                worldPos.getY() - local.getY(), worldPos.getZ() - local.getZ()), new Quaterniond());
    }

    private static CarriedSelection.Selection pick(CarrierId carrier, BlockPos local, BlockPos worldPos) {
        return new CarriedSelection.Selection(DIMENSION, carrier, local, worldPos);
    }

    // ------------------------------------------------------- the plan, by name --

    // `planWrench`'s pick-aware, world-position-aware form is what this round adds (the six-argument form
    // had no world position for the click, and therefore could not see that two blocks are neighbours), and
    // `linkAcross` did not exist at all. Both are asked by name so this file states the new decision rather
    // than compiling against it silently.

    @Nullable
    private static CarriedTool.Action plan(@Nullable CarriedSelection.Selection selected,
                                           CarriedHostIndex index, CarrierId carrier, BlockPos localPos,
                                           BlockPos worldPos, @Nullable CarrierPose pose) {
        return call("com.jiangyuefengyu.redstonecircuit.carrier.CarrierInteraction", "planWrench",
                new Class<?>[] {net.minecraft.server.level.ServerLevel.class,
                        CarriedSelection.Selection.class, CarriedHostIndex.class, CarrierId.class,
                        BlockPos.class, BlockPos.class, CarrierPose.class},
                null, selected, index, carrier, localPos, worldPos, pose);
    }

    /** {@code CarrierInteraction#linkAcross}, or {@code null} on a build that cannot draw one. */
    @Nullable
    @SuppressWarnings("unchecked")
    private static <T> T call(String owner, String name, Class<?>[] types, Object... args) {
        try {
            java.lang.reflect.Method method = Class.forName(owner).getDeclaredMethod(name, types);
            method.setAccessible(true);
            return (T) method.invoke(null, args);
        } catch (ReflectiveOperationException absent) {
            return null;
        }
    }

    /** What the ledger would print for one action, as text. */
    private String lineOf(@Nullable CarriedTool.Action action, CarrierId carrier, BlockPos localPos,
                          @Nullable CarrierId picked, @Nullable BlockPos pickedLocal) {
        Object line = call("com.jiangyuefengyu.redstonecircuit.carrier.CarrierLinkLedger", "of",
                new Class<?>[] {CarriedTool.Action.class, CarriedHostIndex.class, CarrierId.class,
                        BlockPos.class, CarrierId.class, BlockPos.class},
                action, index, picked, pickedLocal, carrier, localPos);
        return line == null ? "" : String.valueOf(describe(line));
    }

    @Nullable
    private static Object describe(Object line) {
        try {
            java.lang.reflect.Method method = line.getClass().getDeclaredMethod("describe");
            method.setAccessible(true);
            return method.invoke(line);
        } catch (ReflectiveOperationException absent) {
            return null;
        }
    }

    private ConnectionState stateTowards(CarrierId carrier, BlockPos from, BlockPos to) {
        return CarriedTool.stateTowards(index, carrier, from, to);
    }

    // ------------------------------------------------- the same structure stays --

    @Nested
    @DisplayName("one structure: nothing about the old behaviour changed")
    class OneStructureStillWorks {

        @Test
        @DisplayName("two local neighbours link, in the structure's own frame")
        void localNeighboursLink() {
            carry(MINE, A, A_WORLD, ComponentType.DUST);
            carry(MINE, B, new BlockPos(102, 64, 100), ComponentType.DUST);

            CarriedTool.Action action = plan(pick(MINE, A, A_WORLD), index, MINE, B, A_WORLD, null);

            assertNotNull(action, "the decision must exist");
            assertTrue(action instanceof CarriedTool.Action.Linked,
                    "the ordinary single-structure line is unchanged: " + action.describe());
            assertEquals(ConnectionState.ON, stateTowards(MINE, A, B));
            assertEquals(ConnectionState.ON, stateTowards(MINE, B, A), "and the far end was written too");
        }

        @Test
        @DisplayName("a diagonal inside one structure is refused by the world's own sentence")
        void aDiagonalIsStillRefused() {
            carry(MINE, A, A_WORLD, ComponentType.DUST);
            BlockPos diagonal = A.relative(Direction.EAST).relative(Direction.SOUTH);
            carry(MINE, diagonal, new BlockPos(102, 64, 101), ComponentType.DUST);

            CarriedTool.Action action = plan(pick(MINE, A, A_WORLD), index, MINE, diagonal,
                    new BlockPos(102, 64, 101), null);

            assertTrue(action instanceof CarriedTool.Action.NotAdjacent,
                    "not neighbours is not neighbours, in one structure or two: " + action.describe());
        }
    }

    // ------------------------------------------------------ across two structures --

    @Nested
    @DisplayName("two structures, world-adjacent: the line is drawn into BOTH records")
    class AcrossTwoStructures {

        @Test
        @DisplayName("both records are written, each on its own side of the shared face")
        void bothRecordsAreWritten() {
            carry(MINE, A, A_WORLD, ComponentType.RETAINING_REPEATER);
            carry(THEIRS, B, B_WORLD, ComponentType.DUST);

            CarriedTool.Action action = plan(pick(MINE, A, A_WORLD), index, THEIRS, B, B_WORLD,
                    moving(B, B_WORLD));

            assertNotNull(action, "a cross-structure link must be decided, not refused");
            assertEquals("CrossLinked", action.getClass().getSimpleName(),
                    "the author's own gesture: " + action.describe());
            // The two ends are one block apart along x in the world, so the line runs along EAST from the
            // pick towards the click - and each structure's own frame answers its own half of that: the
            // near end keeps EAST (towards the other block), the far end keeps the same line seen from its
            // side, which is WEST.
            assertEquals(ConnectionState.ON,
                    stateTowards(MINE, A, A.relative(Direction.EAST)),
                    "the near end's own side is locked, facing the other block: "
                            + index.at(MINE, A).slot().describe());
            assertEquals(ConnectionState.ON,
                    stateTowards(THEIRS, B, B.relative(Direction.WEST)),
                    "and the far end's own side faces back at it: "
                            + index.at(THEIRS, B).slot().describe());
        }

        @Test
        @DisplayName("the ledger names both structures, both coordinates and both sides")
        void theLedgerNamesBothEnds() {
            carry(MINE, A, A_WORLD, ComponentType.RETAINING_REPEATER);
            carry(THEIRS, B, B_WORLD, ComponentType.DUST);

            CarriedTool.Action action = plan(pick(MINE, A, A_WORLD), index, THEIRS, B, B_WORLD,
                    moving(B, B_WORLD));
            String line = lineOf(action, THEIRS, B, MINE, A);

            assertTrue(line.startsWith("link linked ACROSS STRUCTURES"),
                    "the line says the line crossed two structures, which is the fact the old refusal "
                            + "turned into a paragraph: " + line);
            assertTrue(line.contains("picked=s:d315200a local=1, 0, 0"), line);
            assertTrue(line.contains("on=s:f3b2d036 local=2, 0, 0"), line);
            assertTrue(line.contains("side=east"), "the near end's own direction: " + line);
            assertTrue(line.contains("farSide=west"), "and the far end's, in ITS frame: " + line);
            assertTrue(line.contains("state=ON"), line);
        }

        @Test
        @DisplayName("clicking again steps the same cycle the single-structure line uses")
        void theCycleIsTheSameOne() {
            carry(MINE, A, A_WORLD, ComponentType.DUST);
            carry(THEIRS, B, B_WORLD, ComponentType.DUST);

            assertEquals("CrossLinked", plan(pick(MINE, A, A_WORLD), index, THEIRS, B, B_WORLD,
                    moving(B, B_WORLD)).getClass().getSimpleName());
            assertEquals(ConnectionState.ON, stateTowards(MINE, A, A.relative(Direction.EAST)));
            assertEquals("CrossLinked", plan(pick(MINE, A, A_WORLD), index, THEIRS, B, B_WORLD,
                    moving(B, B_WORLD)).getClass().getSimpleName());
            assertEquals(ConnectionState.OFF, stateTowards(MINE, A, A.relative(Direction.EAST)),
                    "AUTO -> ON -> OFF, the same order WrenchLinks#next defines");
            assertEquals("CrossLinked", plan(pick(MINE, A, A_WORLD), index, THEIRS, B, B_WORLD,
                    moving(B, B_WORLD)).getClass().getSimpleName());
            assertEquals(ConnectionState.AUTO, stateTowards(MINE, A, A.relative(Direction.EAST)),
                    "and back to automatic");
            assertEquals(ConnectionState.AUTO, stateTowards(THEIRS, B, B.relative(Direction.WEST)),
                    "on both ends at every step");
        }

        @Test
        @DisplayName("a turned structure gets its OWN direction for the shared world face")
        void aTurnedStructureGetsItsOwnDirection() {
            carry(MINE, A, A_WORLD, ComponentType.DUST);
            carry(THEIRS, B, B_WORLD, ComponentType.DUST);
            // MINE is turned a quarter turn about its own pivot, so the world direction WEST is its own
            // direction NORTH/SOUTH. The shared face is computed once in the world and converted per
            // structure - which is the only way "these two blocks are neighbours" can be true while their
            // local sides are different directions. The click carries MINE's pose, so that is the frame the
            // conversion has to happen in; the picked structure (THEIRS) is unturned, which is what the
            // production path reads for it.
            Quaterniond quarter = new Quaterniond().rotateY(Math.PI / 2.0);
            CarrierPose turned = CarrierPose.of(new Vector3d(0.0, 0.0, 0.0), quarter);
            // The picked block is THEIRS's; the click is on MINE's, with MINE's pose. A line runs from the
            // pick towards the click in the world - EAST here - so the clicked structure's own side is that
            // direction turned into ITS frame, which is what the implementation asks
            // `CarrierDirections#localDirection` for.
            CarriedTool.Action action = plan(pick(THEIRS, B, B_WORLD), index, MINE, A, A_WORLD, turned);

            assertEquals("CrossLinked", action.getClass().getSimpleName(), action.describe());
            assertEquals(ConnectionState.ON, stateTowards(THEIRS, B, B.relative(Direction.WEST)),
                    "the unturned structure's side is west - the same line seen from its end");
            Direction expected = CarrierDirections.localDirection(turned, Direction.EAST);
            assertEquals(ConnectionState.ON, stateTowards(MINE, A, A.relative(expected)),
                    "and the turned structure's side is the world direction turned into ITS frame ("
                            + expected + "): " + index.at(MINE, A).slot().describe());
            assertFalse(expected == Direction.EAST,
                    "the setup is only meaningful if the turn changed the answer: " + expected);
        }

        @Test
        @DisplayName("a far end that holds nothing pins only the near end, exactly as FarEndEmpty says")
        void aFarEndWithNothingPinsOnlyTheNearEnd() {
            carry(MINE, A, A_WORLD, ComponentType.DUST);
            // B is a block of the other structure that holds nothing: the structure carries it and there is
            // no component inside, which is the state a player leaves by taking one out.
            world.put(B_WORLD.asLong(), new Slot(ComponentType.DUST));
            index.claim(THEIRS, B, B_WORLD, new MapWorld(world));
            index.removeSlot(THEIRS, B);
            world.remove(B_WORLD.asLong());

            CarriedTool.Action action = plan(pick(MINE, A, A_WORLD), index, THEIRS, B, B_WORLD,
                    moving(B, B_WORLD));

            assertEquals("CrossFarEndEmpty", action.getClass().getSimpleName(),
                    "there is nowhere to write the far half, and the line says so: " + action.describe());
            assertEquals(ConnectionState.ON, stateTowards(MINE, A, A.relative(Direction.EAST)),
                    "the near end is pinned");
            assertNotNull(index.at(THEIRS, B), "the far host is still carried");
            assertNull(index.at(THEIRS, B).slot(), "and the far end is left alone");
            String line = lineOf(action, THEIRS, B, MINE, A);
            assertTrue(line.contains("the far end holds no component"), line);
            assertTrue(line.contains("side=east"), line);
        }

        @Test
        @DisplayName("two structures that are NOT neighbours keep the named refusal")
        void aDistantPickIsStillRefusedByName() {
            carry(MINE, A, A_WORLD, ComponentType.DUST);
            carry(THEIRS, B, new BlockPos(140, 64, 100), ComponentType.DUST);

            CarriedTool.Action action = plan(pick(MINE, A, A_WORLD), index, THEIRS, B,
                    new BlockPos(140, 64, 100), null);

            assertNotNull(action);
            String described = action.describe();
            assertTrue(described.contains("the pick is in ANOTHER structure"),
                    "there is no shared face, so there is no line: " + described);
            assertTrue(described.contains("not neighbours anywhere either"), described);
            assertTrue(described.contains("picked=") == false,
                    "the ACTION's own sentence names the two structures rather than denying the pick");
            String line = lineOf(action, THEIRS, B, MINE, A);
            assertTrue(line.contains("picked=s:d315200a local=1, 0, 0"),
                    "and the ledger still names the pick, which the old line denied: " + line);
            assertEquals(ConnectionState.AUTO, stateTowards(MINE, A, A.relative(Direction.EAST)),
                    "nothing was written on either end");
        }
    }

    // -------------------------------------------------- the same host, clicked twice --

    @Nested
    @DisplayName("the same host clicked twice: the pick survives the second click")
    class TheSameHostTwice {

        @Test
        @DisplayName("the first shift-click clears the overrides and ends the pick")
        void theFirstClickClears() {
            carry(MINE, A, A_WORLD, ComponentType.DUST);
            carry(MINE, B, A_WORLD.relative(Direction.EAST), ComponentType.DUST);
            CarriedTool.link(index, MINE, A, B);
            assertEquals(ConnectionState.ON, stateTowards(MINE, A, B), "the setup is a locked line");

            CarriedTool.Action action = plan(pick(MINE, A, A_WORLD), index, MINE, A, A_WORLD, null);

            assertTrue(action instanceof CarriedTool.Action.Cleared,
                    "shift-clicking the picked block itself drops every override: " + action.describe());
            assertEquals(ConnectionState.AUTO, stateTowards(MINE, A, B));
        }

        @Test
        @DisplayName("a second click on the same host says the pick is still this block")
        void theSecondClickNamesThePick() {
            carry(MINE, A, A_WORLD, ComponentType.DUST);
            carry(MINE, B, A_WORLD.relative(Direction.EAST), ComponentType.DUST);
            CarriedTool.link(index, MINE, A, B);
            // First click: clears and ends the pick, as above.
            plan(pick(MINE, A, A_WORLD), index, MINE, A, A_WORLD, null);

            // Second click on the same host. The pick is gone, so this is the genuine "nothing picked
            // yet"; the point of the assertion is that the two clicks are DIFFERENT answers and that the
            // second one is reported rather than silent.
            CarriedTool.Action again = plan(null, index, MINE, A, A_WORLD, null);
            assertNotNull(again);
            assertTrue(again instanceof CarriedTool.Action.NoPick, again.describe());
            String line = lineOf(again, MINE, A, null, null);
            assertTrue(line.contains("nothing has been picked yet"),
                    "the genuine no-pick answer, and it names no pick: " + line);

            // And the third case: the host was already automatic, so the first shift-click had nothing to
            // clear and the pick SURVIVED it - which is the answer the author's log needed.
            carry(MINE, new BlockPos(5, 0, 0), new BlockPos(105, 64, 100), ComponentType.DUST);
            CarriedTool.Action nothingToClear = plan(pick(MINE, new BlockPos(5, 0, 0),
                    new BlockPos(105, 64, 100)), index, MINE, new BlockPos(5, 0, 0),
                    new BlockPos(105, 64, 100), null);
            assertTrue(nothingToClear instanceof CarriedTool.Action.AlreadyClear,
                    "an already-automatic host has nothing to clear: " + nothingToClear.describe());
            String sameHost = lineOf(nothingToClear, MINE, new BlockPos(5, 0, 0), MINE,
                    new BlockPos(5, 0, 0));
            assertTrue(sameHost.contains("the pick and the click are the same host"),
                    "and the line says which of the two it was - the pick is named, not denied: "
                            + sameHost);
            assertTrue(sameHost.contains("picked=s:d315200a local=5, 0, 0"), sameHost);
        }
    }

    // ------------------------------------------------ the lock changes BOTH solves --

    @Nested
    @DisplayName("a cross-structure lock really changes both structures' solves")
    class BothSolvesReadIt {

        /** One pass over one structure's hosts. */
        private void solve(CarrierId carrier, BlockPos... seeds) {
            CarrierSpace space = new CarrierSpace(carrier, index, new MapWorld(world), null, null);
            new CarrierLightNetwork(64).solve(space, java.util.List.of(seeds), 0L);
        }

        private int levelAt(CarrierId carrier, BlockPos pos) {
            CarrierSpace space = new CarrierSpace(carrier, index, new MapWorld(world), null, null);
            PowerEnvironment.Node node = space.nodeAt(pos.getX(), pos.getY(), pos.getZ());
            return node == null ? -1 : node.power();
        }

        @Test
        @DisplayName("locking a side on the near end cuts its other sides in ITS OWN solve")
        void theNearEndsSolveFollowsTheLock() {
            carry(MINE, A, A_WORLD, ComponentType.DUST);
            carry(MINE, new BlockPos(1, 0, 1), A_WORLD.relative(Direction.SOUTH), ComponentType.DUST);
            carry(THEIRS, B, B_WORLD, ComponentType.DUST);
            index.at(MINE, A).slot().injectedPower = 15;

            // Before the cross-structure click: A is automatic, so the power it is given reaches the wire
            // beside it through the ordinary side rule.
            solve(MINE, A, new BlockPos(1, 0, 1));
            assertEquals(15, levelAt(MINE, A));
            assertEquals(14, levelAt(MINE, new BlockPos(1, 0, 1)),
                    "an automatic line carries the power along it, inside one structure as in the world");

            // Now the cross-structure click: it locks A onto the side that faces B (east), and a lock means
            // "this line and nothing else" - every other side of A is cut, including the one the second wire
            // is on. That is the acceptance criterion for this round, and it is answered by the solve.
            CarriedTool.Action action = plan(pick(MINE, A, A_WORLD), index, THEIRS, B, B_WORLD,
                    moving(B, B_WORLD));
            assertEquals("CrossLinked", action.getClass().getSimpleName(), action.describe());
            assertTrue(index.at(MINE, A).slot().isRoutingLocked(), "A now has a locked side");
            assertFalse(index.at(MINE, A).slot().isOpen(Direction.SOUTH),
                    "so the side towards its own second wire is cut: "
                            + index.at(MINE, A).slot().describe());

            // Re-solve MINE from every live host of MINE, the way CarrierNetworks#solve seeds it.
            solve(MINE, A, new BlockPos(1, 0, 1));
            assertEquals(0, levelAt(MINE, new BlockPos(1, 0, 1)),
                    "and the solve reads that: the wire beside it has nothing to read any more");
        }

        @Test
        @DisplayName("the far end's own record carries the mirrored state, in ITS frame")
        void theFarEndsRecordCarriesTheMirror() {
            carry(MINE, A, A_WORLD, ComponentType.DUST);
            carry(THEIRS, B, B_WORLD, ComponentType.DUST);

            plan(pick(MINE, A, A_WORLD), index, THEIRS, B, B_WORLD, moving(B, B_WORLD));

            Slot far = index.at(THEIRS, B).slot();
            assertNotNull(far);
            assertEquals(ConnectionState.ON, WrenchLinks.stateOf(far, Direction.WEST),                    "the far end's record says ON on ITS side, which is the world direction turned into its "
                            + "frame: " + far.describe());
            assertTrue(far.isRoutingLocked(),
                    "and a lock on that side means the far end's every other side is cut in ITS solve - so "
                            + "neither end is written and forgotten");
        }
    }
}

package com.jiangyuefengyu.redstonecircuit.carrier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
 * The wrench's link step, decided against a pick that may live in another structure.
 *
 * <p>Pins the three answers of a link, table-driven: a pick in this structure and an adjacent click links; a pick in
 * another structure is refused <em>by name</em>, naming both structures and both coordinates; no pick says so, and
 * says nothing about a pick that exists. It also pins the ledger's four facts - the pick, the target, the side and the
 * state after the click - that the pick is per player and survives ticks and carriers, and that the link really
 * changes the solve: a line the wrench cut stops the power the in-structure solver would otherwise carry along it. A
 * connection written to the record and not read by the solve is a connection that only looks connected.
 */
class CarrierLinkPlanTest {

    private static final CarrierId MINE =
            CarrierId.subLevel(UUID.fromString("d315200a-0000-0000-0000-000000000000"));
    private static final CarrierId THEIRS =
            CarrierId.subLevel(UUID.fromString("f3b2d036-0000-0000-0000-000000000000"));

    private static final ResourceKey<Level> DIMENSION = ResourceKey.create(Registries.DIMENSION,
            ResourceLocation.parse("redstonecircuit:carrierlinkplantest"));

    private static final ResourceKey<Level> ANOTHER_DIMENSION = ResourceKey.create(Registries.DIMENSION,
            ResourceLocation.parse("redstonecircuit:carrierlinkplantest_other"));

    private static final BlockPos A = new BlockPos(1, 0, 0);
    private static final BlockPos B = new BlockPos(2, 0, 0);

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

    private Slot carry(CarrierId carrier, BlockPos local, ComponentType type) {
        Slot slot = new Slot(type);
        world.put(local.asLong(), slot);
        index.claim(carrier, local, local, new MapWorld(world));
        return slot;
    }

    private static CarriedSelection.Selection pick(CarrierId carrier, BlockPos local, BlockPos worldPos) {
        return new CarriedSelection.Selection(DIMENSION, carrier, local, worldPos);
    }

    /** The side the near end was linked along, from the action the wrench returned. */
    @Nullable
    private static Direction sideOf(CarriedTool.Action action) {
        if (action instanceof CarriedTool.Action.Linked linked) {
            return linked.localSide();
        }
        if (action instanceof CarriedTool.Action.FarEndEmpty far) {
            return CarriedTool.localDirectionBetween(far.localPos(), far.towards()).orElse(null);
        }
        return null;
    }

    private ConnectionState stateTowards(CarrierId carrier, BlockPos from, BlockPos to) {
        return CarriedTool.stateTowards(index, carrier, from, to);
    }

    // ------------------------------------------------ the entry points, called by name --
    //
    // The pick-aware link, the plan and the judgement line are asked for reflectively: a direct call would
    // compile only against one tree, while by name this file compiles and RUNS where they are absent - each
    // answering null - and then every assertion about which answer a gesture gets fails with a plain message
    // rather than with a compile error.

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

    /** {@code CarrierInteraction#planWrench}, or {@code null} on a build that has no such decision. */
    @Nullable
    private static CarriedTool.Action plan(@Nullable CarriedSelection.Selection selected,
                                           CarriedHostIndex index, CarrierId carrier, BlockPos localPos,
                                           BlockPos worldPos, @Nullable CarrierPose pose) {
        return call("com.jiangyuefengyu.redstonecircuit.carrier.CarrierInteraction", "planWrench",
                new Class<?>[] {CarriedSelection.Selection.class, CarriedHostIndex.class, CarrierId.class,
                        BlockPos.class, BlockPos.class, CarrierPose.class},
                selected, index, carrier, localPos, worldPos, pose);
    }

    /** {@code CarrierLinkLedger#of}, described as the line the log would carry. */
    private static String lineText(@Nullable CarriedTool.Action action, @Nullable CarriedHostIndex index,
                                   @Nullable CarrierId picked, @Nullable BlockPos pickedLocalPos,
                                   CarrierId carrier, BlockPos localPos) {
        Object line = call("com.jiangyuefengyu.redstonecircuit.carrier.CarrierLinkLedger", "of",
                new Class<?>[] {CarriedTool.Action.class, CarriedHostIndex.class, CarrierId.class,
                        BlockPos.class, CarrierId.class, BlockPos.class},
                action, index, picked, pickedLocalPos, carrier, localPos);
        // A tree without the ledger answers with the action and builds no line at all, so there is nothing to
        // read and every assertion below fails on the empty text.
        return line == null ? "" : String.valueOf(describe(line));
    }

    /** The {@code describe()} of an action, by name so this file needs no new record type to compile. */
    private static String described(@Nullable CarriedTool.Action action) {
        return action == null ? "" : String.valueOf(describe(action));
    }

    /** The {@code describe()} of a ledger line, by name so this file compiles without the class. */
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

    /**
     * The pick, recorded per player. Asked by name for the same reason as the three above: the player-keyed
     * overloads may be simply absent, and then no pick is ever recorded and every gesture below gets the old
     * answer.
     */
    private static void pickFor(UUID player, ResourceKey<Level> dimension, CarrierId carrier,
                                BlockPos localPos, BlockPos worldPos) {
        call("com.jiangyuefengyu.redstonecircuit.carrier.CarriedSelection", "select",
                new Class<?>[] {UUID.class, ResourceKey.class, CarrierId.class, BlockPos.class,
                        BlockPos.class},
                player, dimension, carrier, localPos, worldPos);
    }

    /** {@code CarriedSelection#get(UUID, ResourceKey)}, or {@code null} when there is no pick. */
    @Nullable
    private static CarriedSelection.Selection pickOf(UUID player, ResourceKey<Level> dimension) {
        return call("com.jiangyuefengyu.redstonecircuit.carrier.CarriedSelection", "get",
                new Class<?>[] {UUID.class, ResourceKey.class}, player, dimension);
    }
    /** {@code CarrierTool#link(index, carrier, near, far)} - the entry point that already existed. */
    private static CarriedTool.Action link(CarriedHostIndex index, CarrierId carrier, BlockPos near,
                                           BlockPos far) {
        CarriedTool.Action action = call("com.jiangyuefengyu.redstonecircuit.logic.CarriedTool", "link",
                new Class<?>[] {CarriedHostIndex.class, CarrierId.class, BlockPos.class, BlockPos.class},
                index, carrier, near, far);
        assertNotNull(action, "CarriedTool#link is the pre-existing entry point and must be there");
        return action;
    }

    /**
     * {@code CarriedTool#linkFrom} - the pick-aware link. Falls back to {@link #link} when the method is absent,
     * so a run against a tree without it is a real run rather than a compile failure.
     */
    private static CarriedTool.Action linkFrom(CarriedHostIndex index, CarrierId carrier, BlockPos near,
                                               boolean picked, @Nullable CarrierId pickedCarrier,
                                               @Nullable BlockPos pickedLocalPos,
                                               boolean worldAdjacent) {
        CarriedTool.Action action = call(
                "com.jiangyuefengyu.redstonecircuit.logic.CarriedTool", "linkFrom",
                new Class<?>[] {CarriedHostIndex.class, CarrierId.class, BlockPos.class, boolean.class,
                        CarrierId.class, BlockPos.class, boolean.class},
                index, carrier, near, picked, pickedCarrier, pickedLocalPos, worldAdjacent);
        return action != null ? action : link(index, carrier, pickedLocalPos, near);
    }

    // --------------------------------------------------------- the table --

    @Nested
    @DisplayName("one shift-click, five situations, five answers")
    class TheTable {

        @Test
        @DisplayName("a pick in this structure and an adjacent click: the line is written to both ends")
        void anAdjacentClickLinks() {
            carry(MINE, A, ComponentType.DUST);
            carry(MINE, B, ComponentType.DUST);
            CarriedSelection.Selection selected = pick(MINE, A, new BlockPos(100, 64, 100));

            CarriedTool.Action action = plan(selected, index, MINE, B,
                    new BlockPos(101, 64, 100), null);

            assertInstanceOf(CarriedTool.Action.Linked.class, action, action.describe());
            assertEquals(ConnectionState.ON, stateTowards(MINE, A, B), "the near end is locked");
            assertEquals(ConnectionState.ON, stateTowards(MINE, B, A), "and so is the far end");
            assertEquals(Direction.EAST, sideOf(action), "along the face they share, in the structure's frame");
        }

        @Test
        @DisplayName("a pick in ANOTHER structure, nowhere near: refused by name, and the pick is NAMED")
        void aPickInAnotherStructureIsNamed() {
            carry(MINE, A, ComponentType.DUST);
            carry(THEIRS, B, ComponentType.DUST);
            // Two blocks in two structures that really are neighbours in the world are LINKED (see the sibling
            // test `aCrossStructurePairOfNeighboursIsLinked`). What is left for this refusal is the genuine
            // "wrong machine, and nowhere near this one", which is what the sentence has to keep saying.
            CarrierPose pose = CarrierPose.of(new Vector3d(0.0, 0.0, 0.0), new Quaterniond());

            CarriedTool.Action action = plan(pick(MINE, A, new BlockPos(100, 64, 100)), index, THEIRS, B,
                    new BlockPos(109, 64, 109), pose);

            // Asserted through the description, by name: the answer is a record type of its own, and naming the
            // type here would tie this file to one tree.
            String refused = described(action);
            assertTrue(refused.contains("the pick is in ANOTHER structure"), refused);
            assertTrue(refused.contains("s:d315200a local=1, 0, 0"),
                    "the structure the pick is really in, and its coordinate there: " + refused);
            assertTrue(refused.contains("this click is on s:f3b2d036 local=2, 0, 0"),
                    "against the structure this click landed on: " + refused);
            assertTrue(refused.contains("not neighbours anywhere"),
                    "and the two blocks are not beside each other either, which is why there is no line to "
                            + "draw: " + refused);
            assertFalse(refused.contains("nothing picked yet"),
                    "the refusal must not deny a pick that exists - it names the pick instead: " + refused);
            assertEquals(ConnectionState.AUTO, stateTowards(MINE, A, B), "and nothing was written");
            assertEquals(ConnectionState.AUTO, stateTowards(THEIRS, B, A));
        }

        @Test
        @DisplayName("a pick in ANOTHER structure whose block IS beside this one: the line is drawn")
        void aCrossStructurePairOfNeighboursIsLinked() {
            carry(MINE, A, ComponentType.DUST);
            carry(THEIRS, B, ComponentType.DUST);
            // Two blocks standing side by side in the world, each assembled into its own sub-level: this is the
            // gesture it looks like, and NOT a paragraph about structures.
            CarrierPose pose = CarrierPose.of(new Vector3d(0.0, 0.0, 0.0), new Quaterniond());
            BlockPos mineWorld = new BlockPos(100, 64, 100);
            BlockPos theirsWorld = new BlockPos(101, 64, 100);

            CarriedTool.Action action = plan(pick(MINE, A, mineWorld), index, THEIRS, B, theirsWorld, pose);

            assertEquals("CrossLinked", action.getClass().getSimpleName(), described(action));
            assertEquals(ConnectionState.ON, stateTowards(MINE, A, A.relative(Direction.EAST)),
                    "the near end's own side: " + index.at(MINE, A).slot().describe());
            assertEquals(ConnectionState.ON, stateTowards(THEIRS, B, B.relative(Direction.WEST)),
                    "and the far end's, in the other structure's frame: "
                            + index.at(THEIRS, B).slot().describe());
            assertFalse(described(action).contains("refused"),
                    "a line that was drawn is not a refusal: " + described(action));
        }

        @Test
        @DisplayName("no pick at all: the one case that really is 'nothing picked yet'")
        void nothingPickedSaysSo() {
            carry(MINE, A, ComponentType.DUST);

            CarriedTool.Action action = plan(null, index, MINE, A,
                    new BlockPos(100, 64, 100), null);

            assertInstanceOf(CarriedTool.Action.NoPick.class, action, action.describe());
            String line = lineText(action, index, null, null, MINE, A);
            assertTrue(line.contains("nothing has been picked yet"), line);
            assertFalse(line.contains("picked="),
                    "there is no pick to name, which is what the reason means: " + line);
            assertEquals(ConnectionState.AUTO, stateTowards(MINE, A, A.relative(Direction.EAST)));
        }

        @Test
        @DisplayName("a pick in this structure but nowhere near the click: the world's own 'not adjacent', by name")
        void aFarPickIsNotAdjacent() {
            carry(MINE, A, ComponentType.DUST);
            carry(MINE, new BlockPos(9, 0, 9), ComponentType.DUST);
            CarriedSelection.Selection selected = pick(MINE, A, new BlockPos(100, 64, 100));

            CarriedTool.Action action = plan(selected, index, MINE,
                    new BlockPos(9, 0, 9), new BlockPos(109, 64, 109), null);

            assertInstanceOf(CarriedTool.Action.NotAdjacent.class, action,
                    "one structure, two coordinates that share no face: this is the world's own refusal and "
                            + "it keeps saying exactly that: " + action.describe());
            String line = lineText(action, index, MINE, A, MINE, new BlockPos(9, 0, 9));
            assertTrue(line.contains("not neighbours inside this structure"), line);
            assertTrue(line.contains("picked=s:d315200a local=1, 0, 0"),
                    "the line still names which block was picked: " + line);
            assertTrue(line.contains("on=s:d315200a local=9, 0, 9"), line);
        }

        @Test
        @DisplayName("a diagonal pick in this structure is refused by name, with both coordinates")
        void aDiagonalIsRefusedByName() {
            carry(MINE, A, ComponentType.DUST);
            BlockPos diagonal = new BlockPos(A.getX() + 1, A.getY(), A.getZ() + 1);
            carry(MINE, diagonal, ComponentType.DUST);
            CarriedSelection.Selection selected = pick(MINE, A, new BlockPos(100, 64, 100));

            CarriedTool.Action action = plan(selected, index, MINE, diagonal,
                    new BlockPos(101, 64, 101), null);

            assertInstanceOf(CarriedTool.Action.NotAdjacent.class, action, action.describe());
            String line = lineText(action, index, MINE, A, MINE, diagonal);
            assertTrue(line.contains("the two hosts are not neighbours inside this structure"), line);
            assertTrue(line.contains("picked=s:d315200a local=1, 0, 0"), "which block was picked: " + line);
            assertTrue(line.contains("on=s:d315200a local=2, 0, 1"), "and which block was clicked: " + line);
            assertTrue(line.contains("side=?"), "and that no side could be named - which is the reason: "
                    + line);
        }

        @Test
        @DisplayName("shift-clicking the picked host itself clears it, exactly as in the world")
        void shiftClickingThePickClearsIt() {
            carry(MINE, A, ComponentType.DUST);
            carry(MINE, B, ComponentType.DUST);
            link(index, MINE, A, B);
            assertEquals(ConnectionState.ON, stateTowards(MINE, A, B), "the setup is a locked line");
            CarriedSelection.Selection selected = pick(MINE, A, new BlockPos(100, 64, 100));

            CarriedTool.Action action = plan(selected, index, MINE, A,
                    new BlockPos(100, 64, 100), null);

            assertInstanceOf(CarriedTool.Action.Cleared.class, action, action.describe());
            assertEquals(ConnectionState.AUTO, stateTowards(MINE, A, B),
                    "every override is gone and the line decides for itself again");
            assertEquals(ConnectionState.ON, stateTowards(MINE, B, A),
                    "and clearing one block is one block's business");
        }
    }

    // ----------------------------------------------------- the ledger's facts --

    @Nested
    @DisplayName("the judgement line carries all four facts, success or failure")
    class TheLedger {

        @Test
        @DisplayName("a successful link names the pick, the target, the side and the new state")
        void aLinkNamesEverything() {
            carry(MINE, A, ComponentType.RETAINING_REPEATER);
            carry(MINE, B, ComponentType.RETAINING_REPEATER);

            CarriedTool.Action action = linkFrom(index, MINE, B, true, MINE, A, true);
            String line = lineText(action, index, MINE, A, MINE, B);

            assertTrue(line.startsWith("link linked"),
                    "a line was really written, and the line says so: " + line);
            assertTrue(line.contains("picked=s:d315200a local=1, 0, 0"),
                    "the line names WHICH block was picked: " + line);
            assertTrue(line.contains("on=s:d315200a local=2, 0, 0"), "the clicked block: " + line);
            assertTrue(line.contains("side=east"), "the side, in the structure's own frame: " + line);
            assertTrue(line.contains("state=ON"), "and what the connection became: " + line);
        }

        @Test
        @DisplayName("a refusal names the same four things, with side=? where no side exists")
        void aRefusalNamesEverythingToo() {
            carry(MINE, A, ComponentType.DUST);
            BlockPos diagonal = new BlockPos(A.getX() + 1, A.getY(), A.getZ() + 1);
            carry(MINE, diagonal, ComponentType.DUST);

            CarriedTool.Action action = linkFrom(index, MINE, diagonal, true, MINE, A, true);
            String line = lineText(action, index, MINE, A, MINE, diagonal);

            assertTrue(line.startsWith("link refused: the two hosts are not neighbours"), line);
            assertTrue(line.contains("picked=s:d315200a local=1, 0, 0"), line);
            assertTrue(line.contains("side=?"), line);
        }

        @Test
        @DisplayName("a cross-structure refusal names BOTH structures")
        void aCrossStructureRefusalNamesBoth() {
            carry(MINE, A, ComponentType.DUST);
            carry(THEIRS, B, ComponentType.DUST);

            CarriedTool.Action action = linkFrom(index, THEIRS, B, true, MINE, A, true);
            String line = lineText(action, index, MINE, A, THEIRS, B);

            assertTrue(line.contains("the pick is in another structure"), line);
            assertTrue(line.contains("picked=s:d315200a local=1, 0, 0"),
                    "the structure the pick is in: " + line);
            assertTrue(line.contains("on=s:f3b2d036 local=2, 0, 0"),
                    "and the structure the click was on: " + line);
        }

        @Test
        @DisplayName("linking a far end that holds nothing still names the side it pinned")
        void aDeviceLinkNamesTheSide() {
            carry(MINE, A, ComponentType.DUST);
            // B holds nothing at all: no record, which is the shape of a plain stone block of a machine.

            CarriedTool.Action action = linkFrom(index, MINE, B, true, MINE, A, true);
            String line = lineText(action, index, MINE, A, MINE, B);

            assertTrue(line.contains("the clicked host holds no component"), line);
            assertTrue(line.contains("side=east"), "the direction from the pick to the click: " + line);
            assertTrue(line.startsWith("link "), "the near end really was pinned: " + line);
            assertEquals(ConnectionState.ON, stateTowards(MINE, A, B));
        }
    }

    // ------------------------------------------------- the ledger of picks --

    @Nested
    @DisplayName("the pick is per player, and it outlives ticks, carriers and re-selections")
    class ThePickLedger {

        @Test
        @DisplayName("a pick made in one tick is still there in the next - which is the whole gesture")
        void aPickSurvivesTicks() {
            UUID player = UUID.randomUUID();
            pickFor(player, DIMENSION, MINE, A, new BlockPos(100, 64, 100));

            // Nothing in the ledger is keyed on a tick or a game time: the only things that can end a gesture are
            // another pick, the same block cleared, the level going away, or the player leaving. This is what "one
            // tick later" means here, and it is the fact a run of selects and refusals turns on.
            CarriedSelection.Selection still = pickOf(player, DIMENSION);
            assertNotNull(still);
            assertEquals(MINE, still.carrier());
            assertEquals(A, still.localPos());
            assertEquals(new BlockPos(100, 64, 100), still.worldPos());
        }

        @Test
        @DisplayName("another player's pick is not this player's")
        void twoPlayersAreIndependent() {
            UUID first = UUID.randomUUID();
            UUID second = UUID.randomUUID();
            pickFor(first, DIMENSION, MINE, A, new BlockPos(100, 64, 100));
            pickFor(second, DIMENSION, THEIRS, B, new BlockPos(101, 64, 100));

            assertEquals(MINE, pickOf(first, DIMENSION).carrier());
            assertEquals(THEIRS, pickOf(second, DIMENSION).carrier());
            CarriedSelection.forget(first);
            assertNull(pickOf(first, DIMENSION), "one player leaving does not disturb the other");
            assertNotNull(pickOf(second, DIMENSION));
            CarriedSelection.forget(second);
        }

        @Test
        @DisplayName("a pick made in another dimension is not usable here")
        void aPickDoesNotSurviveADimensionChange() {
            UUID player = UUID.randomUUID();
            pickFor(player, ANOTHER_DIMENSION, MINE, A, new BlockPos(100, 64, 100));

            assertNull(pickOf(player, DIMENSION),
                    "the dimension is part of the key, so a half-finished gesture cannot cross levels");
            assertNotNull(pickOf(player, ANOTHER_DIMENSION));
            CarriedSelection.forget(player);
        }

        @Test
        @DisplayName("picking a second host is what ends the first - the carrier layer's own clear")
        void pickingAgainReplacesThePick() {
            UUID player = UUID.randomUUID();
            pickFor(player, DIMENSION, MINE, A, new BlockPos(100, 64, 100));
            pickFor(player, DIMENSION, MINE, B, new BlockPos(101, 64, 100));

            assertEquals(B, pickOf(player, DIMENSION).localPos(),
                    "the pick follows the player's last click; selecting a DIFFERENT host replaces the "
                            + "near end rather than leaving two of them");
            // ...and the shift-click on the picked host itself is the other way a pick ends, asserted in
            // TheTable.shiftClickingThePickClearsIt.
            CarriedSelection.forget(player);
            assertNull(pickOf(player, DIMENSION));
        }

        @Test
        @DisplayName("a broken structure drops the picks that were made in it")
        void aGoneCarrierDropsItsPicks() {
            UUID first = UUID.randomUUID();
            UUID second = UUID.randomUUID();
            pickFor(first, DIMENSION, MINE, A, new BlockPos(100, 64, 100));
            pickFor(second, DIMENSION, MINE, B, new BlockPos(101, 64, 100));

            CarriedSelection.clearCarrier(MINE);

            assertNull(pickOf(first, DIMENSION),
                    "a pick of a host that no longer exists is not a pick");
            assertNull(pickOf(second, DIMENSION));
        }
    }

    // ------------------------------------------------ the connection is REAL --

    @Nested
    @DisplayName("the link changes the SOLVE, not only the record")
    class TheSolveReadsIt {

        /**
         * Two wires in one structure, the first one powered from outside.
         *
         * <p>The solver's own rule charges a neighbouring wire one hop below the one beside it, so a
         * connection between them is visible as a number: cut it and the second wire has nothing to read.
         */
        private CarrierSpace spaceWithTwoWires() {
            carry(MINE, A, ComponentType.DUST);
            carry(MINE, B, ComponentType.DUST);
            // The supply goes on the record the SOLVE reads: a claim snapshots the slot into the index, so
            // writing to the world store's copy afterwards would be writing to a slot nobody reads.
            index.at(MINE, A).slot().injectedPower = 15;
            return new CarrierSpace(MINE, index, new MapWorld(world), null, null);
        }

        /** One pass over the two wires, from the first one as the seed. */
        private static void solve(CarrierSpace space) {
            new CarrierLightNetwork(64).solve(space, java.util.List.of(A), 0L);
        }

        private static int levelAt(CarrierSpace space, BlockPos pos) {
            PowerEnvironment.Node node = space.nodeAt(pos.getX(), pos.getY(), pos.getZ());
            return node == null ? -1 : node.power();
        }

        @Test
        @DisplayName("an automatic line carries the power along it")
        void anAutomaticLineConducts() {
            CarrierSpace space = spaceWithTwoWires();

            solve(space);

            assertEquals(15, levelAt(space, A));
            assertEquals(14, levelAt(space, B),
                    "one hop below the wire beside it, exactly as in the world");
        }

        @Test
        @DisplayName("a line the wrench CUT stops it, which is what 'the connection took effect' means")
        void aCutLineStopsThePower() {
            CarrierSpace space = spaceWithTwoWires();
            // The wrench's own gesture: pick A, shift-click B. ON is the first state, and a lock keeps
            // only that side and cuts every other one - so this is a real connection, not a display.
            linkFrom(index, MINE, B, true, MINE, A, true);
            assertNotNull(index.at(MINE, A).slot());
            assertEquals(ConnectionState.ON, WrenchLinks.stateOf(index.at(MINE, A).slot(), Direction.EAST));

            solve(space);
            assertEquals(14, levelAt(space, B),
                    "locked ON conducts, so the lock is a connection and not a display");

            // Move the route. A lock means "this line and nothing else", so the way a player takes the line away
            // from B is to drop every override and then lock A onto another side - and that is exactly what the
            // solve has to follow: A is locked onto a side that is NOT the one between the wires, so the side
            // between them is cut by the lock's own rule. Both ends are cleared, because a lock is given to BOTH
            // hosts and a clear is per block: leaving B's lock standing would keep it emitting at its neighbour.
            CarriedTool.clear(index, MINE, A);
            CarriedTool.clear(index, MINE, B);
            BlockPos northOfA = A.relative(Direction.NORTH);
            CarriedTool.Action relocked = linkFrom(index, MINE, northOfA, true, MINE, A, true);
            assertTrue(relocked instanceof CarriedTool.Action.Linked
                            || relocked instanceof CarriedTool.Action.FarEndEmpty,
                    "the wrench locks the line onto the block north of A: " + relocked.describe());
            assertEquals(ConnectionState.ON,
                    WrenchLinks.stateOf(index.at(MINE, A).slot(), Direction.NORTH),
                    "and that side is the one it kept");
            assertTrue(index.at(MINE, A).slot().isRoutingLocked(),
                    "A now has a locked side, which is what makes its routing explicit");
            assertFalse(index.at(MINE, A).slot().isOpen(Direction.EAST),
                    "so the side between the two wires is cut");
            assertTrue(index.at(MINE, B).slot().forcedOn.isEmpty()
                            && index.at(MINE, B).slot().forcedOff.isEmpty(),
                    "and B is back to automatic, with no lock of its own left to emit at its neighbour");

            CarrierSpace afterLock = new CarrierSpace(MINE, index, new MapWorld(world), null, null);
            assertFalse(afterLock.nodeAt(A.getX(), A.getY(), A.getZ()).open(Direction.EAST),
                    "the NODE the solver is handed says the side is closed - which is the only view the "
                            + "solve reads: " + index.at(MINE, A).slot().describe());
            // The production driver seeds every live host of the structure, so both wires are offered: with the
            // cut in place they are two components, and the one that no longer has a supply is re-derived to
            // nothing.
            new CarrierLightNetwork(64).solve(afterLock, java.util.List.of(A, B), 0L);
            assertEquals(0, levelAt(afterLock, B),
                    "and the solve reads that: the second wire has nothing to read any more. This is what "
                            + "'the connection took effect' means - the record alone would prove nothing");
        }

        @Test
        @DisplayName("a cross-structure line between two blocks that are NOT neighbours changes nothing")
        void aCrossStructureLineChangesNothing() {
            carry(MINE, A, ComponentType.DUST);
            carry(THEIRS, B, ComponentType.DUST);
            index.at(MINE, A).slot().injectedPower = 15;

            // Two blocks that ARE world neighbours are linked, both records written. What this test keeps is the
            // other half - there is no shared face at all, so there is no side to write on either end, and the
            // click is refused by name.
            CarriedTool.Action action = plan(
                    pick(MINE, A, new BlockPos(100, 64, 100)), index, THEIRS, B,
                    new BlockPos(140, 64, 100), null);

            assertTrue(described(action).contains("the pick is in ANOTHER structure"),
                    "the click is refused by name rather than linked: " + described(action));
            assertTrue(index.at(MINE, A).slot().forcedOn.isEmpty(), "no override was written anywhere");
            assertTrue(index.at(THEIRS, B).slot().forcedOn.isEmpty());
            CarrierSpace other = new CarrierSpace(THEIRS, index, new MapWorld(world), null, null);
            new CarrierLightNetwork(64).solve(other, java.util.List.of(B), 0L);
            assertEquals(0, levelAt(other, B),
                    "and the other structure carries nothing across the gap: with no shared face there is no "
                            + "line to store, which is why the refusal is named");
        }

        @Test
        @DisplayName("a cross-structure line between two neighbours writes BOTH structures' records")
        void aCrossStructureLineWritesBoth() {
            carry(MINE, A, ComponentType.DUST);
            carry(THEIRS, B, ComponentType.DUST);
            index.at(MINE, A).slot().injectedPower = 15;

            CarriedTool.Action action = plan(
                    pick(MINE, A, new BlockPos(100, 64, 100)), index, THEIRS, B,
                    new BlockPos(101, 64, 100), null);

            assertEquals("CrossLinked", action.getClass().getSimpleName(), described(action));
            assertEquals(ConnectionState.ON, stateTowards(MINE, A, A.relative(Direction.EAST)),
                    "the near end is locked on its own side: " + index.at(MINE, A).slot().describe());
            assertEquals(ConnectionState.ON, stateTowards(THEIRS, B, B.relative(Direction.WEST)),
                    "and the far end's record says the same thing in ITS frame, so neither end is written "
                            + "and forgotten: " + index.at(THEIRS, B).slot().describe());
        }
    }
}





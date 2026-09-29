package com.jiangyuefengyu.redstonecircuit.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.jiangyuefengyu.redstonecircuit.carrier.CarriedHost;
import com.jiangyuefengyu.redstonecircuit.carrier.CarriedHostIndex;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierId;
import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.ConnectionState;
import com.jiangyuefengyu.redstonecircuit.data.Slot;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>P1.20, ability three: the wrench and the rotation rod on a host a structure is carrying.</b>
 *
 * <h2>The author's question</h2>
 * <em>"Can a physicalised block be used with the wrench or the rotation rod?"</em> It can, and this class
 * pins the whole of the behaviour - the three-state cycle, the adjacency rule, the turn, and every
 * refusal - against {@link CarriedHostIndex} alone: no level, no player, no packet. That is the point of
 * {@link CarriedTool} being a separate class from {@code WrenchLinks} and {@code RotationRod}: the world's
 * logic is not reachable from here in either direction, which is what makes "the world's behaviour is
 * unchanged" a structural fact rather than a promise.
 *
 * <h2>What is pinned</h2>
 * <ol>
 *   <li><b>The cycle is the world's cycle.</b> {@code AUTO -> ON -> OFF -> AUTO}, stepped by repeated
 *       shift-clicks, with both ends of the line written so neither can be re-routed alone.</li>
 *   <li><b>Adjacency is decided inside the structure.</b> Neighbours in the local frame, which is where
 *       the components are: a machine turned by ninety degrees has its local east at the world's south,
 *       and the two blocks a player sees beside each other are neighbours locally.</li>
 *   <li><b>Clear drops every override</b> and puts the host back to deciding for itself.</li>
 *   <li><b>The rod turns to the clicked face</b>, given in the structure's own frame, and refuses wire,
 *       which has no orientation.</li>
 *   <li><b>Every refusal is a refusal and not a side effect.</b> No component, no orientation, not
 *       adjacent, nothing picked - none of them writes anything.</li>
 *   <li><b>A landed host is not a carried host.</b> Once a structure has put a host down, the world's
 *       wrench owns it and this path must not touch its record, whatever the index still remembers.</li>
 * </ol>
 */
class CarriedToolTest {

    private static final CarrierId MINE =
            CarrierId.contraption(UUID.fromString("00000000-0000-0000-0000-000000002101"));
    private static final CarrierId THEIRS =
            CarrierId.contraption(UUID.fromString("00000000-0000-0000-0000-000000002102"));

    /** The world store, as a map - the same stand-in the index's own tests use. */
    private static final class FakeWorld implements CarriedHostIndex.SlotWorld {

        private final Map<Long, Slot> slots = new HashMap<>();

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

    private final CarriedHostIndex index = new CarriedHostIndex();
    private final FakeWorld world = new FakeWorld();

    private static BlockPos pos(int x, int y, int z) {
        return new BlockPos(x, y, z);
    }

    /** Picks a component up, the way the assembly callback does. */
    private CarriedHost carry(CarrierId carrier, BlockPos local, ComponentType type) {
        Slot slot = new Slot(type);
        BlockPos origin = originOf(local);
        world.set(origin, slot);
        index.claim(carrier, local, origin, world);
        return index.at(carrier, local);
    }

    /**
     * Picks a block up that holds nothing.
     *
     * <p>The index only files a claim when the world store has a component there - "a claim that carries
     * nothing is not a claim" - so a block of the machine with no component in it is simply not in the
     * index. The test's own world store can be told to answer nothing, which is what the pick-up path
     * itself sees for a plain block.
     */
    private static CarriedHostIndex indexWithoutComponents() {
        return new CarriedHostIndex();
    }

    private static BlockPos originOf(BlockPos local) {
        return new BlockPos(500 + local.getX(), 64, 500 + local.getZ());
    }

    private Slot slotOf(CarrierId carrier, BlockPos local) {
        CarriedHost host = index.at(carrier, local);
        return host == null ? null : host.slot();
    }

    // ------------------------------------------------- the three-state cycle --

    @Test
    @DisplayName("the wrench steps a carried link through automatic, locked, cut, automatic")
    void theWrenchStepsTheWorldsThreeStates() {
        BlockPos a = pos(1, 0, 0);
        BlockPos b = pos(2, 0, 0);
        carry(MINE, a, ComponentType.DUST);
        carry(MINE, b, ComponentType.DUST);

        assertEquals(ConnectionState.AUTO, CarriedTool.stateTowards(index, MINE, a, b),
                "a fresh line decides for itself");

        CarriedTool.Action first = CarriedTool.link(index, MINE, a, b);
        assertInstanceOf(CarriedTool.Action.Linked.class, first);
        assertEquals(ConnectionState.ON, ((CarriedTool.Action.Linked) first).state(),
                "one click locks it");
        assertEquals(ConnectionState.ON, WrenchLinks.stateOf(slotOf(MINE, a), Direction.EAST),
                "and the near end has the override");
        assertEquals(ConnectionState.ON, WrenchLinks.stateOf(slotOf(MINE, b), Direction.WEST),
                "and so does the far end - neither can be re-routed without the other");

        assertEquals(ConnectionState.OFF,
                ((CarriedTool.Action.Linked) CarriedTool.link(index, MINE, a, b)).state(),
                "the second click cuts it: a real cut the player asked for");
        assertEquals(ConnectionState.OFF, WrenchLinks.stateOf(slotOf(MINE, a), Direction.EAST));

        assertEquals(ConnectionState.AUTO,
                ((CarriedTool.Action.Linked) CarriedTool.link(index, MINE, a, b)).state(),
                "and the third gives it back to the network");
        assertEquals(ConnectionState.AUTO, WrenchLinks.stateOf(slotOf(MINE, a), Direction.EAST));
        assertEquals(ConnectionState.AUTO, WrenchLinks.stateOf(slotOf(MINE, b), Direction.WEST));
    }

    @Test
    @DisplayName("adjacency is decided in the structure's own frame, not in the world's")
    void adjacencyIsLocalToTheStructure() {
        BlockPos a = pos(1, 0, 0);
        carry(MINE, a, ComponentType.DUST);
        carry(MINE, pos(1, 0, 1), ComponentType.DUST);

        assertEquals(Direction.SOUTH, CarriedTool.localDirectionBetween(a, pos(1, 0, 1)).orElseThrow(),
                "local +Z is south, as it is in the world");
        assertEquals(Direction.UP, CarriedTool.localDirectionBetween(a, pos(1, 1, 0)).orElseThrow(),
                "and so is local +Y");
        assertTrue(CarriedTool.localDirectionBetween(a, pos(9, 9, 9)).isEmpty(),
                "two coordinates that share no face are not neighbours");

        CarriedTool.Action diagonal = CarriedTool.link(index, MINE, a, pos(2, 0, 2));
        assertInstanceOf(CarriedTool.Action.NotAdjacent.class, diagonal,
                "a diagonal click is refused rather than clamped to an axis");
        assertEquals(ConnectionState.AUTO, WrenchLinks.stateOf(slotOf(MINE, a), Direction.SOUTH),
                "and a refusal writes nothing at all");
    }

    @Test
    @DisplayName("a line to a neighbouring block that holds nothing pins only the near end")
    void aLineToANeighbourWithoutAComponentPinsOnlyThisEnd() {
        BlockPos a = pos(1, 0, 0);
        carry(MINE, a, ComponentType.DUST);
        // The far end is a block of the same machine that holds no component, so there is no host record
        // for it at all - the shape a plain stone block of a machine has.

        CarriedTool.Action action = CarriedTool.link(index, MINE, a, pos(2, 0, 0));
        assertInstanceOf(CarriedTool.Action.FarEndEmpty.class, action,
                "the far end holds no component, so there is nowhere to write its half");
        assertEquals(ConnectionState.ON, WrenchLinks.stateOf(slotOf(MINE, a), Direction.EAST),
                "and only the clicked end is pinned, exactly as the world's wrench does");
    }

    @Test
    @DisplayName("clear drops every override on a carried host")
    void clearPutsTheHostBackToAutomatic() {
        BlockPos a = pos(1, 0, 0);
        BlockPos b = pos(1, 0, 1);
        carry(MINE, a, ComponentType.REPEATER);
        carry(MINE, b, ComponentType.REPEATER);
        CarriedTool.link(index, MINE, a, b);
        assertEquals(ConnectionState.ON, WrenchLinks.stateOf(slotOf(MINE, a), Direction.SOUTH));

        CarriedTool.Action action = CarriedTool.clear(index, MINE, a);
        assertInstanceOf(CarriedTool.Action.Cleared.class, action);
        assertEquals(ConnectionState.AUTO, WrenchLinks.stateOf(slotOf(MINE, a), Direction.SOUTH),
                "the host decides for itself again");
        assertEquals(ConnectionState.ON, WrenchLinks.stateOf(slotOf(MINE, b), Direction.NORTH),
                "and the far end keeps its own override: clearing one block is one block's business");
    }

    @Test
    @DisplayName("select, link, clear and turn all refuse a coordinate this structure never claimed")
    void aCoordinateWithoutAHostIsRefused() {
        // The host record is what an action writes; a block of the machine that holds no component has
        // none, and neither has a coordinate of a different machine.
        CarriedHostIndex empty = indexWithoutComponents();
        BlockPos none = pos(3, 0, 0);

        assertNull(CarriedTool.hostAt(empty, MINE, none), "no record, so no host");
        assertInstanceOf(CarriedTool.Action.NoComponent.class,
                CarriedTool.select(empty, MINE, none), "nothing to pick");
        assertInstanceOf(CarriedTool.Action.NoComponent.class,
                CarriedTool.link(empty, MINE, none, pos(4, 0, 0)), "nothing to route from");
        assertInstanceOf(CarriedTool.Action.NoComponent.class,
                CarriedTool.clear(empty, MINE, none), "and nothing to clear");
        assertInstanceOf(CarriedTool.Action.NoComponent.class,
                CarriedTool.turn(empty, MINE, none, Direction.NORTH), "and nothing to turn");

        carry(MINE, pos(1, 0, 0), ComponentType.DUST);
        assertInstanceOf(CarriedTool.Action.NoComponent.class,
                CarriedTool.select(index, THEIRS, pos(1, 0, 0)),
                "and another structure's coordinate is not this one's");
        assertNull(CarriedTool.stateTowards(index, MINE, pos(9, 9, 9), pos(1, 0, 0)));
    }

    @Test
    @DisplayName("a coordinate this structure never claimed is refused by every action")
    void anUnclaimedCoordinateIsRefused() {
        carry(MINE, pos(1, 0, 0), ComponentType.DUST);
        BlockPos elsewhere = pos(9, 9, 9);

        assertInstanceOf(CarriedTool.Action.NoComponent.class,
                CarriedTool.select(index, MINE, elsewhere));
        assertInstanceOf(CarriedTool.Action.NoComponent.class,
                CarriedTool.clear(index, MINE, elsewhere));
        assertInstanceOf(CarriedTool.Action.NoComponent.class,
                CarriedTool.turn(index, MINE, elsewhere, Direction.NORTH));
        assertInstanceOf(CarriedTool.Action.NoComponent.class,
                CarriedTool.select(index, THEIRS, pos(1, 0, 0)),
                "and another structure's coordinate is not this one's");
        assertNull(CarriedTool.stateTowards(index, MINE, elsewhere, pos(1, 0, 0)));
    }
    // ------------------------------------------------------------------- rod --

    @Test
    @DisplayName("the rod alternates input and output on the clicked face, in the structure's frame")
    void theRodTurnsToTheClickedFace() {
        BlockPos local = pos(1, 0, 0);
        carry(MINE, local, ComponentType.REPEATER);
        Slot slot = slotOf(MINE, local);

        // The rule, in the form the class documents it: the first click makes the face the place the
        // component READS from, unless that is already where it reads from - in which case that click is
        // the one that turns the output onto it. Stating the expectation from the state rather than
        // hard-coding a direction means the test pins the RULE, not a particular default facing.
        Direction initial = slot.facing;
        boolean firstIsInput = initial != Direction.EAST;

        CarriedTool.Action first = CarriedTool.turn(index, MINE, local, Direction.EAST);
        assertEquals(firstIsInput, first instanceof CarriedTool.Action.TurnedToInput,
                "the first click on a face is input when the component reads elsewhere, and output when "
                        + "it already reads there: " + first.describe());
        assertEquals(firstIsInput ? Direction.EAST : Direction.WEST, slotOf(MINE, local).facing);
        assertEquals(Direction.EAST, localFaceOf(first),
                "and the reported face is the structure's own, which is what the caller converted");

        CarriedTool.Action second = CarriedTool.turn(index, MINE, local, Direction.EAST);
        assertEquals(!firstIsInput, second instanceof CarriedTool.Action.TurnedToInput,
                "clicking the same face again is the other half of the pair: " + second.describe());
        assertEquals(Direction.EAST.getOpposite(), slotOf(MINE, local).facing,
                "so the component is now driven towards that face");

        CarriedTool.Action third = CarriedTool.turn(index, MINE, local, Direction.UP);
        assertEquals(Direction.UP, slotOf(MINE, local).facing,
                "and a new face is simply the new direction");
        assertNotNull(third);
    }

    /** The face an action reports, whichever half of the pair it was. */
    private static Direction localFaceOf(CarriedTool.Action action) {
        if (action instanceof CarriedTool.Action.TurnedToInput turned) {
            return turned.localFace();
        }
        return ((CarriedTool.Action.TurnedToOutput) action).localFace();
    }

    @Test
    @DisplayName("the rod refuses dust, which has no orientation")
    void theRodRefusesWire() {
        BlockPos local = pos(1, 0, 0);
        carry(MINE, local, ComponentType.DUST);
        Direction before = slotOf(MINE, local).facing;

        CarriedTool.Action action = CarriedTool.turn(index, MINE, local, Direction.EAST);
        assertInstanceOf(CarriedTool.Action.NoDirection.class, action);
        assertEquals(before, slotOf(MINE, local).facing, "and nothing was turned");
    }

    @Test
    @DisplayName("the rod refuses a null face rather than guessing one")
    void theRodRefusesANullFace() {
        BlockPos local = pos(1, 0, 0);
        carry(MINE, local, ComponentType.TORCH);
        assertInstanceOf(CarriedTool.Action.NoDirection.class,
                CarriedTool.turn(index, MINE, local, null));
    }

    // ----------------------------------------------------------- the record --

    @Test
    @DisplayName("an action writes the record the client and the solve read, and nothing else")
    void anActionWritesThroughTheIndex() {
        BlockPos local = pos(1, 0, 0);
        BlockPos origin = pos(500 + 1, 64, 500);
        carry(MINE, local, ComponentType.COMPARATOR);
        index.updateSlot(MINE, local, new Slot(ComponentType.COMPARATOR));

        CarriedTool.turn(index, MINE, local, Direction.SOUTH);
        CarriedHost host = index.at(MINE, local);
        assertNotNull(host, "the record is still there");
        assertEquals(Direction.SOUTH, host.slot().facing, "with the new facing");
        assertEquals(origin, host.origin(), "and the origin it was picked up from, untouched");
        assertEquals(origin, host.at(), "and the position it answers at, untouched");
        assertFalse(host.landed(), "still riding");

        // The broadcast entry is the record as it now stands, which is what the overlay draws.
        assertNotNull(CarriedTool.entryOf(index, MINE, local));
        assertEquals(Direction.SOUTH, CarriedTool.entryOf(index, MINE, local).slot().facing);
    }

    @Test
    @DisplayName("a landed host belongs to the world's tools, not to these")
    void aLandedHostIsNotTouchable() {
        BlockPos local = pos(1, 0, 0);
        carry(MINE, local, ComponentType.REPEATER);
        index.release(MINE, local, null, world);
        assertTrue(index.at(MINE, local).landed(), "the structure put it down");

        assertNull(CarriedTool.hostAt(index, MINE, local),
                "so it is not a carried host any more, whatever the index still remembers");
        assertInstanceOf(CarriedTool.Action.NoComponent.class,
                CarriedTool.select(index, MINE, local));
        assertInstanceOf(CarriedTool.Action.NoComponent.class,
                CarriedTool.turn(index, MINE, local, Direction.EAST));
        assertInstanceOf(CarriedTool.Action.NoComponent.class,
                CarriedTool.clear(index, MINE, local));
        assertEquals(ConnectionState.AUTO, WrenchLinks.stateOf(slotOf(MINE, local), Direction.SOUTH),
                "and none of them wrote anything");
    }

    @Test
    @DisplayName("two structures carrying one coordinate do not see each other's hosts")
    void actionsAreScopedToOneCarrier() {
        BlockPos local = pos(1, 0, 0);
        carry(MINE, local, ComponentType.REPEATER);
        carry(THEIRS, local, ComponentType.REPEATER);
        CarriedTool.link(index, MINE, local, pos(2, 0, 0));
        CarriedTool.select(index, MINE, local);

        CarriedTool.turn(index, THEIRS, local, Direction.UP);
        assertEquals(Direction.UP, slotOf(THEIRS, local).facing);
        assertEquals(Direction.NORTH, slotOf(MINE, local).facing,
                "the other machine's component at the same coordinate is a different component");
    }
}

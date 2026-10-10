package com.jiangyuefengyu.redstonecircuit.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.jiangyuefengyu.redstonecircuit.carrier.CarriedHost;
import com.jiangyuefengyu.redstonecircuit.carrier.CarriedHostIndex;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierId;
import com.jiangyuefengyu.redstonecircuit.data.ComparatorMode;
import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.Slot;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A bare hand on a component inside a block a structure is carrying.
 *
 * <p>In the world, a plain right-click with a bare hand works what is hidden inside a block: a lever flips, a repeater's
 * delay steps 1-2-3-4, a comparator swaps mode. On a physicalised block that gesture did nothing, because the world's
 * handler reads {@code InnerRedstoneStore} by a <b>world</b> position while a carried component lives in the carrier
 * index, keyed by the structure's own coordinate.
 *
 * <p>So the rule is <em>the same states, changed the same way</em>, and that is what is pinned here: the delay cycle is
 * the world's cycle, the lever's power is the world's power, a repeater's output is left alone while its setting changes,
 * and nothing is written when the gesture is refused. The one difference - a button - is asserted as a named refusal
 * rather than a silence, because its release would have to be scheduled against a position that does not move.
 */
class CarriedSwitchTest {

    private static final CarrierId MINE =
            CarrierId.contraption(UUID.fromString("00000000-0000-0000-0000-000000005001"));
    private static final CarrierId THEIRS =
            CarrierId.contraption(UUID.fromString("00000000-0000-0000-0000-000000005002"));
    private static final BlockPos LOCAL = new BlockPos(1, 0, 0);

    private final CarriedHostIndex index = new CarriedHostIndex();
    private final Map<Long, Slot> world = new HashMap<>();

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

    /** Claims one host holding {@code slot}. */
    private void carry(CarrierId carrier, BlockPos local, Slot slot) {
        world.put(local.asLong(), slot);
        index.claim(carrier, local, local, new MapWorld(world));
        index.updateSlot(carrier, local, slot);
    }

    private Slot slotOf(CarrierId carrier, BlockPos local) {
        CarriedHost host = index.at(carrier, local);
        return host == null ? null : host.slot();
    }

    // ------------------------------------------------------------------ the lever --

    @Test
    @DisplayName("a lever flips, and flips back, with the world's own power values")
    void theLeverFlips() {
        Slot lever = new Slot(ComponentType.LEVER);
        lever.powered = false;
        lever.power = 0;
        carry(MINE, LOCAL, lever);

        assertEquals(CarriedSwitch.Action.ENGAGED, CarriedSwitch.apply(index, MINE, LOCAL));
        Slot after = slotOf(MINE, LOCAL);
        assertTrue(after.powered, "the lever is on");
        assertEquals(PowerSolver.MAX_POWER, after.power, "and it drives like a lever does in the world");

        assertEquals(CarriedSwitch.Action.RELEASED, CarriedSwitch.apply(index, MINE, LOCAL));
        Slot back = slotOf(MINE, LOCAL);
        assertFalse(back.powered, "and off again");
        assertEquals(0, back.power);
    }

    // --------------------------------------------------------------- the repeaters --

    @Test
    @DisplayName("a repeater steps 1-2-3-4-1, and its output is left alone")
    void theRepeaterCyclesItsDelay() {
        Slot repeater = new Slot(ComponentType.REPEATER);
        repeater.delay = 1;
        repeater.power = 12;
        carry(MINE, LOCAL, repeater);

        for (int expected = 2; expected <= 4; expected++) {
            assertEquals(CarriedSwitch.Action.ADJUSTED, CarriedSwitch.apply(index, MINE, LOCAL));
            assertEquals(expected, slotOf(MINE, LOCAL).delay, "the world's cycle is 1 -> 2 -> 3 -> 4 -> 1");
        }
        assertEquals(CarriedSwitch.Action.ADJUSTED, CarriedSwitch.apply(index, MINE, LOCAL));
        assertEquals(1, slotOf(MINE, LOCAL).delay, "and it wraps");
        assertEquals(12, slotOf(MINE, LOCAL).power,
                "changing a setting must not cut an in-flight pulse short, which is why the world's "
                        + "cycleDelay leaves the output alone");
    }

    @Test
    @DisplayName("every repeater flavour steps the same way, and a double repeater is not special-cased")
    void everyRepeaterFlavourSteps() {
        for (ComponentType type : new ComponentType[] {ComponentType.REPEATER,
                ComponentType.RETAINING_REPEATER, ComponentType.DOUBLE_REPEATER,
                ComponentType.DOUBLE_RETAINING_REPEATER}) {
            CarriedHostIndex fresh = new CarriedHostIndex();
            Map<Long, Slot> slots = new HashMap<>();
            Slot repeater = new Slot(type);
            repeater.delay = 2;
            slots.put(LOCAL.asLong(), repeater);
            fresh.claim(MINE, LOCAL, LOCAL, new MapWorld(slots));
            fresh.updateSlot(MINE, LOCAL, repeater);

            assertEquals(CarriedSwitch.Action.ADJUSTED, CarriedSwitch.apply(fresh, MINE, LOCAL), type.name());
            assertEquals(3, fresh.at(MINE, LOCAL).slot().delay, type.name());
            assertTrue(CarriedSwitch.isWorkable(type), type.name());
        }
    }

    // --------------------------------------------------------------- the comparator --

    @Test
    @DisplayName("a comparator swaps compare and subtract, both ways")
    void theComparatorSwapsMode() {
        Slot comparator = new Slot(ComponentType.COMPARATOR);
        comparator.mode = ComparatorMode.COMPARE;
        carry(MINE, LOCAL, comparator);

        assertEquals(CarriedSwitch.Action.ADJUSTED, CarriedSwitch.apply(index, MINE, LOCAL));
        assertEquals(ComparatorMode.SUBTRACT, slotOf(MINE, LOCAL).mode);
        assertEquals(CarriedSwitch.Action.ADJUSTED, CarriedSwitch.apply(index, MINE, LOCAL));
        assertEquals(ComparatorMode.COMPARE, slotOf(MINE, LOCAL).mode);
    }

    // ----------------------------------------------------------------- refusals --

    @Test
    @DisplayName("a button is refused by name, and nothing is written")
    void aButtonIsRefusedByName() {
        Slot button = new Slot(ComponentType.BUTTON);
        button.powered = false;
        carry(MINE, LOCAL, button);

        assertEquals(CarriedSwitch.Action.BUTTON_NEEDS_A_STABLE_POSITION,
                CarriedSwitch.apply(index, MINE, LOCAL));
        assertFalse(slotOf(MINE, LOCAL).powered, "a refused gesture changes nothing");
        assertFalse(CarriedSwitch.workable(index, MINE, LOCAL),
                "and the entry point is told before it claims the click, so it does not consume it");
        assertTrue(CarriedSwitch.Action.BUTTON_NEEDS_A_STABLE_POSITION.describe()
                        .contains("has to be scheduled"),
                "the reason is in the text, so the refusal is not a silent gap");
    }

    @Test
    @DisplayName("a component with no bare-hand action is refused, and nothing is written")
    void aComponentWithNoBareHandActionIsRefused() {
        for (ComponentType type : new ComponentType[] {ComponentType.DUST, ComponentType.TORCH,
                ComponentType.SUPERCONDUCTOR}) {
            CarriedHostIndex fresh = new CarriedHostIndex();
            Map<Long, Slot> slots = new HashMap<>();
            Slot carried = new Slot(type);
            slots.put(LOCAL.asLong(), carried);
            fresh.claim(MINE, LOCAL, LOCAL, new MapWorld(slots));
            // THE SAME OBJECT, not a fresh lookup: `claim` is a MOVE, so the world map no longer holds
            // the slot afterwards and `slots.get(...)` would hand back null - which would empty the
            // record and make this test assert against an empty host.
            fresh.updateSlot(MINE, LOCAL, carried);
            Slot before = fresh.at(MINE, LOCAL).slot().copy();

            assertEquals(CarriedSwitch.Action.NOT_WORKABLE, CarriedSwitch.apply(fresh, MINE, LOCAL),
                    type.name());
            assertFalse(CarriedSwitch.workable(fresh, MINE, LOCAL), type.name());
            assertEquals(before.describe(), fresh.at(MINE, LOCAL).slot().describe(),
                    "a refused gesture must not touch the record: " + type.name());
        }
    }

    @Test
    @DisplayName("an empty carried host has nothing to work")
    void anEmptyHostHasNothingToWork() {
        Slot dust = new Slot(ComponentType.DUST);
        carry(MINE, LOCAL, dust);
        assertTrue(index.removeSlot(MINE, LOCAL), "the setup empties the host");
        assertEquals(null, slotOf(MINE, LOCAL));

        assertEquals(CarriedSwitch.Action.NOTHING_THERE, CarriedSwitch.apply(index, MINE, LOCAL));
        assertFalse(CarriedSwitch.workable(index, MINE, LOCAL));
    }

    @Test
    @DisplayName("a coordinate with no live carried host is not this layer's business")
    void aCoordinateWithNoHostIsRefusedPosition() {
        carry(MINE, LOCAL, new Slot(ComponentType.LEVER));

        assertEquals(CarriedSwitch.Action.REFUSED_POSITION,
                CarriedSwitch.apply(index, MINE, new BlockPos(9, 9, 9)), "nothing claimed there");
        assertEquals(CarriedSwitch.Action.REFUSED_POSITION,
                CarriedSwitch.apply(index, THEIRS, LOCAL), "no such structure");
        assertEquals(CarriedSwitch.Action.REFUSED_POSITION, CarrierSwitchNulls.applyNullIndex());
        assertEquals(CarriedSwitch.Action.REFUSED_POSITION, CarrierSwitchNulls.applyNullCarrier(index));
        assertEquals(CarriedSwitch.Action.REFUSED_POSITION, CarrierSwitchNulls.applyNullPos(index, MINE));

        // A put-down host is a world host: it is the world's switch path that owns it.
        index.release(MINE, LOCAL, landing -> LOCAL, new MapWorld(world));
        assertTrue(index.at(MINE, LOCAL).landed());
        assertEquals(CarriedSwitch.Action.REFUSED_POSITION, CarriedSwitch.apply(index, MINE, LOCAL));
        assertFalse(CarriedSwitch.workable(index, MINE, LOCAL));
    }

    @Test
    @DisplayName("one carrier's switch never touches another carrier's host at the same coordinate")
    void twoCarriersDoNotShareAHost() {
        Slot mine = new Slot(ComponentType.LEVER);
        mine.powered = false;
        Slot theirs = new Slot(ComponentType.LEVER);
        theirs.powered = true;
        carry(MINE, LOCAL, mine);
        carry(THEIRS, LOCAL, theirs);

        assertEquals(CarriedSwitch.Action.ENGAGED, CarriedSwitch.apply(index, MINE, LOCAL));
        assertTrue(slotOf(MINE, LOCAL).powered);
        assertTrue(slotOf(THEIRS, LOCAL).powered, "the other structure's record is untouched");
        assertNotSame(index.at(MINE, LOCAL).slot(), index.at(THEIRS, LOCAL).slot(),
                "and they are different records, not two views of one");
    }

    @Test
    @DisplayName("workable() agrees with what apply() would do —— 除了动作是「开界面」的四件（控制器、变压器、时钟源、延长器）")
    void workableAgreesWithApply() {
        for (ComponentType type : ComponentType.values()) {
            CarriedHostIndex fresh = new CarriedHostIndex();
            Map<Long, Slot> slots = new HashMap<>();
            Slot carried = new Slot(type);
            slots.put(LOCAL.asLong(), carried);
            fresh.claim(MINE, LOCAL, LOCAL, new MapWorld(slots));
            fresh.updateSlot(MINE, LOCAL, carried);

            boolean predicted = CarriedSwitch.workable(fresh, MINE, LOCAL);
            CarriedSwitch.Action action = CarriedSwitch.apply(fresh, MINE, LOCAL);
            if (type == ComponentType.CONTROLLER || type == ComponentType.TRANSFORMER
                    || type == ComponentType.CLOCK || type == ComponentType.EXTENDER) {
                // The devices whose bare-hand gesture <b>is</b> their control screen are workable and are <b>not</b>
                // steppable: the entry point opens the screen before apply is ever reached (CarrierInteraction), just
                // as the world's own InnerSwitches#toggle has nothing to do for them. So this is the documented
                // exception to the pair, of the same shape as the button's - workable says "a bare hand does something
                // here", not "apply can step it".
                assertTrue(predicted, "徒手右键对 " + type + " 是有动作的（打开它的控制界面）");
                assertFalse(action.acted(), "而 apply 没有可步进的东西");
                continue;
            }
            assertEquals(predicted, action.acted(),
                    "the entry point claims the click on `workable` and acts on `apply`: the two must "
                            + "never disagree, and they disagree for " + type);
        }
    }

    @Test
    @DisplayName("isWorkable covers the world's set minus the one documented exclusion")
    void isWorkableCoversTheWorldsSet() {
        assertTrue(CarriedSwitch.isWorkable(ComponentType.LEVER));
        assertTrue(CarriedSwitch.isWorkable(ComponentType.REPEATER));
        assertTrue(CarriedSwitch.isWorkable(ComponentType.COMPARATOR));
        assertFalse(CarriedSwitch.isWorkable(ComponentType.DUST));
        // The button is the one type the world works and the carrier layer refuses, and the exclusion is
        // stated in both the action's text and the class doc.
        assertTrue(CarriedSwitch.isWorkable(ComponentType.BUTTON),
                "it is a workable component; the refusal is about where its release could be scheduled");
    }

    /** The null-argument edges, gathered so the assertions above stay readable. */
    private static final class CarrierSwitchNulls {

        private CarrierSwitchNulls() {
        }

        static CarriedSwitch.Action applyNullIndex() {
            return CarriedSwitch.apply(null, MINE, LOCAL);
        }

        static CarriedSwitch.Action applyNullCarrier(CarriedHostIndex index) {
            return CarriedSwitch.apply(index, null, LOCAL);
        }

        static CarriedSwitch.Action applyNullPos(CarriedHostIndex index, CarrierId carrier) {
            return CarriedSwitch.apply(index, carrier, null);
        }
    }
}

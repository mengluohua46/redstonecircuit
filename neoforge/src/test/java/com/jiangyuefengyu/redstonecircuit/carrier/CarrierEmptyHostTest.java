package com.jiangyuefengyu.redstonecircuit.carrier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.Slot;
import com.jiangyuefengyu.redstonecircuit.logic.CarriedInsert;
import com.jiangyuefengyu.redstonecircuit.logic.CarriedSwitch;
import com.jiangyuefengyu.redstonecircuit.logic.CarriedTool;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;

import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The structure still carries a block and the block holds nothing: a state, not a crash. Every carrier
 * entry point must not throw, must answer "there is no component here", and must write nothing.
 *
 * <p>Driven with no level, no player and no packet, over {@link CarriedHostIndex} alone.
 */
class CarrierEmptyHostTest {

    private static final CarrierId CARRIER =
            CarrierId.contraption(UUID.fromString("00000000-0000-0000-0000-000000002401"));

    /** The world store, as a map - the same stand-in every carrier test uses. */
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

    private static BlockPos originOf(BlockPos local) {
        return new BlockPos(700 + local.getX(), 64, 700 + local.getZ());
    }

    @BeforeEach
    void clearGuards() {
        // The warning flags and failure counts are process-lifetime by design, so clear them per test.
        CarrierGuards.clear();
    }

    /**
     * One host, picked up with a component and then emptied through {@link CarriedHostIndex#removeSlot},
     * so the state under test is the one the production path produces.
     */
    private CarriedHost carryAndEmpty(BlockPos local, ComponentType type) {
        Slot slot = new Slot(type);
        world.set(originOf(local), slot);
        index.claim(CARRIER, local, originOf(local), world);
        assertTrue(index.removeSlot(CARRIER, local), "the host was there to empty");
        CarriedHost host = index.at(CARRIER, local);
        assertNotNull(host, "the host is still carried - only its contents are gone");
        assertFalse(host.landed(), "and it is still riding");
        assertNull(host.slot(), "and it holds nothing: this is the state every test below is about");
        return host;
    }

    // ------------------------------------------------------- the crash itself --

    /** An empty host must not throw out of the pose heartbeat, and it is left out of the payload. */
    @Test
    @DisplayName("an emptied carried host does not crash the payload build: it is simply left out")
    void anEmptiedHostDoesNotCrashThePayload() {
        BlockPos local = pos(3, 0, 4);
        CarriedHost host = carryAndEmpty(local, ComponentType.RETAINING_REPEATER);

        // An empty host has nothing to copy, nothing to write and nothing to draw.
        assertNull(host.copy().slot(), "copying an empty host keeps it empty rather than dereferencing null");
        assertNull(host.save(), "and saving one writes nothing, because its only payload is the component");

        // The walk over the live hosts has to tolerate the empty one rather than throw.
        int copied = 0;
        for (CarriedHost live : index.liveHostsOf(CARRIER)) {
            Slot held = live.slot();
            if (held != null) {
                copied++;
                assertNotNull(held.copy());
            }
        }
        assertEquals(0, copied, "this structure carries only the emptied host, which contributes nothing");
    }

    /**
     * The emptied host is still <b>live</b> and contributes no view. Both the shell and the component are
     * emitted from the view, so a host with no view has nothing to emit - while absence from the host
     * list is also how a put-down travels.
     */
    @Test
    @DisplayName("the client is told nothing for an emptied host, and the record is still live")
    void anEmptiedHostContributesNoClientView() {
        BlockPos filled = pos(1, 0, 1);
        BlockPos emptied = pos(2, 0, 2);
        Slot slot = new Slot(ComponentType.DUST);
        world.set(originOf(filled), slot);
        index.claim(CARRIER, filled, originOf(filled), world);
        carryAndEmpty(emptied, ComponentType.DUST);

        int contributing = 0;
        for (CarriedHost host : index.liveHostsOf(CARRIER)) {
            Slot held = host.slot();
            if (held != null) {
                contributing++;
                assertNotNull(held.copy(), "a host that holds something is copied as it always was");
            }
        }
        assertEquals(1, contributing,
                "only the host that still holds a component contributes a view; the emptied one is skipped");
        assertEquals(2, index.liveHostsOf(CARRIER).size(),
                "and both are still live hosts - the emptied one is carried, not forgotten");
    }

    // ---------------------------------------------------- the tool entry points --

    /** The wrench, both gestures, on an empty host: "no component here", as the world's wrench says. */
    @Test
    @DisplayName("the wrench on an emptied host answers 'no component' and writes nothing")
    void theWrenchRefusesAnEmptiedHost() {
        BlockPos local = pos(5, 0, 5);
        carryAndEmpty(local, ComponentType.DUST);

        CarriedTool.Action plain = CarriedTool.select(index, CARRIER, local);
        assertInstanceOf(CarriedTool.Action.NoComponent.class, plain,
                "the world's wrench answers 'no component' for an empty host, so this must too: "
                        + plain.describe());
        assertTrue(plain.describe().contains("no component"), plain.describe());

        CarriedTool.Action cleared = CarriedTool.clear(index, CARRIER, local);
        assertInstanceOf(CarriedTool.Action.NoComponent.class, cleared, cleared.describe());

        // Nothing was written: the record is still carried, still empty, and still at its own coordinate.
        CarriedHost after = index.at(CARRIER, local);
        assertNotNull(after);
        assertNull(after.slot());
        assertFalse(after.landed());
    }

    /** The rotation rod on an empty host: the same refusal, and no direction is invented for it. */
    @Test
    @DisplayName("the rotation rod on an emptied host answers 'no component'")
    void theRodRefusesAnEmptiedHost() {
        BlockPos local = pos(6, 0, 6);
        carryAndEmpty(local, ComponentType.RETAINING_REPEATER);

        CarriedTool.Action turned = CarriedTool.turn(index, CARRIER, local);
        assertInstanceOf(CarriedTool.Action.NoComponent.class, turned, turned.describe());
        assertNull(index.at(CARRIER, local).slot(), "and nothing was written");
    }

    /** The bare-hand switch on an empty host: nothing to work, nothing changed. */
    @Test
    @DisplayName("the bare-hand switch on an emptied host does nothing at all")
    void theSwitchRefusesAnEmptiedHost() {
        BlockPos local = pos(7, 0, 7);
        carryAndEmpty(local, ComponentType.REPEATER);

        assertFalse(CarriedSwitch.workable(index, CARRIER, local),
                "the world's own empty host has nothing workable, so this must not claim the click");
        assertEquals(CarriedSwitch.Action.NOTHING_THERE, CarriedSwitch.apply(index, CARRIER, local),
                "and the action itself says so rather than dereferencing the missing component");
        assertNull(index.at(CARRIER, local).slot(), "nothing was written");
    }

    /** Retrieving from an empty host: the world refuses the same way, and no item is created. */
    @Test
    @DisplayName("a retrieval from an emptied host refuses and takes nothing")
    void retrievalFromAnEmptiedHostRefuses() {
        BlockPos local = pos(8, 0, 8);
        carryAndEmpty(local, ComponentType.DUST);

        assertEquals(CarriedInsert.Action.NOTHING_TO_RETRIEVE,
                CarriedInsert.apply(index, CARRIER, local, Direction.UP, true, null, false),
                "there is nothing to take out, so nothing is taken out");
        assertNull(CarriedInsert.slotOf(index, CARRIER, local));
    }

    /** Storing into an empty host still works: the empty state is a state, not a permanent refusal. */
    @Test
    @DisplayName("a component can be stored into an emptied host, and comes back out again")
    void anEmptiedHostStillAcceptsAComponent() {
        BlockPos local = pos(9, 0, 9);
        carryAndEmpty(local, ComponentType.DUST);

        assertEquals(CarriedInsert.Action.INSERT,
                CarriedInsert.apply(index, CARRIER, local, Direction.NORTH, true,
                        ComponentType.LEVER, false),
                "an emptied host is exactly the state a player inserts into");
        Slot stored = CarriedInsert.slotOf(index, CARRIER, local);
        assertNotNull(stored);
        assertEquals(ComponentType.LEVER, stored.type);
        assertEquals(Direction.NORTH, stored.facing, "the clicked face became its input side");

        CarriedTool.Action picked = CarriedTool.select(index, CARRIER, local);
        assertInstanceOf(CarriedTool.Action.Selected.class, picked, picked.describe());
    }

    // ------------------------------------------------------------- the file --

    /** An emptied host must not break the save: it has nothing to write, and it must not throw. */
    @Test
    @DisplayName("an emptied host is not written to the carrier file, and the file still loads")
    void anEmptiedHostIsNotSaved() {
        BlockPos kept = pos(1, 0, 1);
        BlockPos emptied = pos(2, 0, 2);
        Slot slot = new Slot(ComponentType.DUST);
        slot.power = 7;
        world.set(originOf(kept), slot);
        index.claim(CARRIER, kept, originOf(kept), world);
        carryAndEmpty(emptied, ComponentType.COMPARATOR);

        CompoundTag tag = new CompoundTag();
        index.saveInto(tag);

        CarriedHostIndex reloaded = CarriedHostIndex.load(tag);
        List<CarriedHost> hosts = reloaded.hostsOf(CARRIER);
        assertEquals(1, hosts.size(), "only the host that holds something survives a round trip");
        assertEquals(kept, hosts.get(0).localPos());
        assertEquals(7, hosts.get(0).slot().power, "with its component intact");
    }

    // ------------------------------------------------------- the shared barrier --

    /**
     * A carrier entry point that throws answers "not handled" and says so once, however many times it
     * fails: the failure must not reach the caller, and a per-click warning would be a flood.
     */
    @Test
    @DisplayName("a failing carrier entry point answers 'not handled', warns once, and counts every failure")
    void aFailingEntryPointIsContained() {
        CarrierGuards.Guard guard = CarrierGuards.OTHER;
        assertFalse(guard.warned());
        assertEquals(0, guard.failures());

        boolean handled = guard.call(BlockPos.ZERO, () -> {
            Slot absent = null;
            absent.copy();
            return Boolean.TRUE;
        }, false);

        assertFalse(handled, "a failure is 'the carrier layer did not own this gesture'");
        assertEquals(1, guard.failures());
        assertTrue(guard.warned(), "one warning, so the hook that threw is nameable");

        // A second failure on the same entry point is counted and not printed again.
        assertFalse(guard.call(BlockPos.ZERO, () -> {
            throw new IllegalStateException("again");
        }, false));
        assertEquals(2, guard.failures(), "both failures are counted");
        assertTrue(guard.warned(), "and the warning stays written once");
    }

    /** A guard that does not fail changes nothing: the action's own answer comes straight back. */
    @Test
    @DisplayName("a guard on a working action returns its answer and records nothing")
    void aWorkingEntryPointIsUntouched() {
        CarrierGuards.Guard guard = CarrierGuards.OTHER;
        assertTrue(guard.call(BlockPos.ZERO, () -> Boolean.TRUE, false));
        assertFalse(guard.call(BlockPos.ZERO, () -> Boolean.FALSE, true),
                "a 'no' from the action is an answer, not a failure");
        assertEquals(0, guard.failures());
        assertFalse(guard.warned());
    }

    /** Every entry point has its own guard, so one hook's failure cannot silence another's report. */
    @Test
    @DisplayName("each carrier entry point owns its own warning flag")
    void eachEntryPointOwnsItsOwnGuard() {
        CarrierGuards.BLOCK_CLICK.call(BlockPos.ZERO, () -> {
            throw new IllegalStateException("block click");
        }, false);
        assertTrue(CarrierGuards.BLOCK_CLICK.warned());
        assertFalse(CarrierGuards.CONTRAPTION_PACKET.warned(),
                "the contraption packet has not failed, so it must still be able to report its own");
        assertFalse(CarrierGuards.POSE.warned());

        // The names are distinct and stable: the warning line has to say which hook threw.
        assertEquals(CarrierGuards.Entry.BLOCK_CLICK,
                CarrierGuards.BLOCK_CLICK.entry());
        assertTrue(CarrierGuards.Entry.CONTRAPTION_PACKET.label()
                .contains("contraption"));
        assertSame(CarrierGuards.POSE, CarrierGuards.all().get(7));
    }

    // ---------------------------------------------------------- the world side --

    /**
     * {@code slot() == null} has always meant "no component" on the world side: the world store has no
     * entry for a block that holds nothing, so every world-side reader already answers null.
     */
    @Test
    @DisplayName("the world's empty host still answers 'no component' to the wrench and the switch")
    void theWorldSideIsUnchanged() {
        BlockPos worldPos = pos(4, 1, 4);
        // No entry at all in the world store: the world's own empty host.
        assertNull(CarriedTool.hostAt(index, CARRIER, worldPos),
                "a coordinate this structure carries nothing at is not a carried host");
        assertFalse(CarriedSwitch.workable(index, CARRIER, worldPos));
        assertEquals(CarriedSwitch.Action.REFUSED_POSITION,
                CarriedSwitch.apply(index, CARRIER, worldPos),
                "and the world owns the click");
        assertEquals(CarriedInsert.Action.REFUSED_POSITION,
                CarriedInsert.apply(index, CARRIER, worldPos, Direction.UP, true, null, false));
        assertNull(CarriedInsert.slotOf(index, CARRIER, worldPos));
    }

    /**
     * An emptied host and a coordinate the structure carries nothing at are <b>different states</b>:
     * collapsing them would make the world's own click on any block resolve to a carried host.
     */
    @Test
    @DisplayName("an emptied host and a coordinate the structure does not carry are different states")
    void anEmptiedHostIsNotTheSameAsNoHost() {
        BlockPos emptied = pos(2, 0, 2);
        BlockPos notOurs = pos(3, 0, 3);
        carryAndEmpty(emptied, ComponentType.DUST);

        assertNotNull(CarriedTool.hostAt(index, CARRIER, emptied),
                "the emptied host is still a host: the structure carries the block");
        assertNull(CarriedTool.hostAt(index, CARRIER, notOurs),
                "a coordinate the structure carries nothing at is not a host at all");

        assertEquals(CarriedSwitch.Action.NOTHING_THERE,
                CarriedSwitch.apply(index, CARRIER, emptied),
                "'there is a host here and it holds nothing'");
        assertEquals(CarriedSwitch.Action.REFUSED_POSITION,
                CarriedSwitch.apply(index, CARRIER, notOurs),
                "'this is not my host' - a different answer, and the world owns this one");
    }
}

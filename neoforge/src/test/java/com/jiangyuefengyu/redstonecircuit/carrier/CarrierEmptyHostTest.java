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
 * <b>"The structure still carries this block and the block holds nothing" is a state, not a crash.</b>
 *
 * <h2>The author's P1.23 crash, as a test</h2>
 * A host a structure was carrying had had its component taken back out, so the record's
 * {@code slot()} answered {@code null} - the state P1.22 introduced with
 * {@link CarriedHostIndex#removeSlot}. The next wrench shift-click on that host reached
 * {@code HostSync#carrierPayload}, which called {@code slot().copy()}, and the resulting
 * {@code NullPointerException} came out of {@code LevelTickEvent.Post} and killed the server
 * (crash report {@code 9f315663-…}):
 *
 * <pre>
 *   at HostSync.carrierPayload(HostSync.java:273)
 *   at HostSync.broadcastPose(HostSync.java:201)
 *   at CarrierStore.broadcastPose(CarrierStore.java:1656)
 *   at CarrierStore.tick(CarrierStore.java:2617)
 *   at InnerRedstoneTickHandler.onLevelTick(InnerRedstoneTickHandler.java:49)
 * </pre>
 *
 * <p>Every test below drives one half of that state with no level, no player and no packet, because the
 * whole of it is a pure function over {@link CarriedHostIndex} - the same seam {@code CarriedToolTest}
 * and {@code CarriedSwitchTest} use. The three things asserted, for every carrier entry point:
 *
 * <ol>
 *   <li><b>it does not throw</b> - the crash is reproduced here first and is impossible afterwards;</li>
 *   <li><b>it answers "there is no component here"</b> rather than "I did something" - the same answer
 *       the world's own empty host gives;</li>
 *   <li><b>it writes nothing</b> - the record, the world store and the save file are all exactly as they
 *       were.</li>
 * </ol>
 *
 * <p>The world side is checked last, deliberately: {@code slot() == null} there means "this block holds no
 * component", which is the ordinary case for every block in the world and must keep behaving exactly as
 * it did.
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
        // The entry points' warning flags and failure counts are process-lifetime by design, so one test
        // must not see another's. The ledger is the same kind of state.
        CarrierGuards.clear();
    }

    /**
     * One host, picked up with a component, and then emptied - the exact state the crash was in.
     *
     * <p>Emptied through {@link CarriedHostIndex#removeSlot}, which is the named form of "a player took the
     * component back out", rather than by constructing a record by hand: the state under test has to be
     * the one the production path produces, or the test proves something about the test.
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

    /**
     * <b>The crash, reproduced.</b>
     *
     * <p>{@code carrierPayload} is reached from the pose heartbeat, so before this round an empty host on
     * one moving structure threw out of the level tick. The fix has to make the whole host list survive
     * it, and the message the client gets has to be the one the client can act on: this host carries no
     * component, so it is not in the payload at all.
     */
    @Test
    @DisplayName("an emptied carried host does not crash the payload build: it is simply left out")
    void anEmptiedHostDoesNotCrashThePayload() {
        BlockPos local = pos(3, 0, 4);
        CarriedHost host = carryAndEmpty(local, ComponentType.RETAINING_REPEATER);

        // The record itself: an empty host has nothing to copy, nothing to write, and nothing to draw.
        // These three are the crash: `slot().copy()` in the payload build, `slot.save()` in the file, and
        // `slot.copy()` in the record's own copy.
        assertNull(host.copy().slot(), "copying an empty host keeps it empty rather than dereferencing null");
        assertNull(host.save(), "and saving one writes nothing, because its only payload is the component");

        // And the walk the crash was in - `for (CarriedHost host : index.liveHostsOf(carrier))` with one
        // `slot().copy()` per host - has to tolerate the empty one rather than throw.
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
     * The same state, through the list the payload is built from: the emptied host is still <b>live</b>,
     * and it contributes no view.
     *
     * <p>That is what makes "empty" a state the server can hold without dereferencing anything, and the
     * renderer draws nothing at that coordinate: both the shell and the component are emitted from the
     * view, so a host with no view has nothing to emit.
     *
     * <p><b>P1.43: "no view" is not on its own a correction, and this javadoc used to say it was.</b>
     * The sentence here read "the next carrier message corrects a client that had been shown the
     * component before it was taken out" - and it did not, because absence from the host list is also how
     * a <b>put-down</b> travels, so the client kept the view it was holding as a landed record and went
     * on drawing the frame and the component (the author's ghost). The correction is the host-level
     * removal notice that travels <em>beside</em> the host list ({@code CarriedInsert#removalNotice});
     * this test's own assertions - no view, record still live - are unchanged and still what the payload
     * build must do.
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

    /**
     * The wrench, both gestures, on an empty host: "no component here", exactly as the world's wrench says.
     *
     * <p>This is the click the author was making when the server died - a wrench shift-click on a host
     * whose component had already been taken out.
     */
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

        CarriedTool.Action turned = CarriedTool.turn(index, CARRIER, local, Direction.NORTH);
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
     * <b>The barrier: a carrier entry point that throws answers "not handled" and says so once.</b>
     *
     * <p>This is the shape the P1.23 crash needed and did not have. The failure must not reach the caller
     * (the event bus rethrows, and the level tick aborts), the answer must be the caller's fallback, and
     * the report must be written exactly once however many times it happens - a per-click warning is the
     * flood the solver's own aggregation exists to avoid.
     */
    @Test
    @DisplayName("a failing carrier entry point answers 'not handled', warns once, and counts every failure")
    void aFailingEntryPointIsContained() {
        CarrierGuards.Guard guard = CarrierGuards.OTHER;
        assertFalse(guard.warned());
        assertEquals(0, guard.failures());

        // The exact failure the author hit, from the exact call that produced it.
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

        // The names are distinct and stable: the warning line has to say which hook threw, which the
        // author's own log could not - it named a position and a generic 'carrier tool click failed'.
        assertEquals(CarrierGuards.Entry.BLOCK_CLICK,
                CarrierGuards.BLOCK_CLICK.entry());
        assertTrue(CarrierGuards.Entry.CONTRAPTION_PACKET.label()
                .contains("contraption"));
        assertSame(CarrierGuards.POSE, CarrierGuards.all().get(7));
    }

    // ---------------------------------------------------------- the world side --

    /**
     * <b>The world side is unchanged: {@code slot() == null} there has always meant "no component".</b>
     *
     * <p>The world store has no entry for a block that holds nothing, so every world-side reader already
     * answers null; this pins the two the carrier layer shares a rule with, so the two halves cannot drift
     * apart while the carrier side learns about empty hosts.
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
                "and the world owns the click, exactly as before this round");
        assertEquals(CarriedInsert.Action.REFUSED_POSITION,
                CarriedInsert.apply(index, CARRIER, worldPos, Direction.UP, true, null, false));
        assertNull(CarriedInsert.slotOf(index, CARRIER, worldPos));
    }

    /**
     * The other half of the same rule: an emptied host and a coordinate the structure carries nothing at
     * are <b>different states</b>, and the code has to tell them apart.
     *
     * <p>"The block is riding and holds nothing" is what the placement path inserts into; "this
     * coordinate is not one of mine" is what the world owns. Collapsing them would make the world's own
     * click on any block resolve to a carried host, which is the class of bug
     * {@code CarrierLocalResolutionTest} exists for.
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

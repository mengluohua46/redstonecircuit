package com.jiangyuefengyu.redstonecircuit.carrier;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.Slot;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * An emptied carried host must not silence the machine's own solve.
 *
 * <p>The carrier solve walks a structure's live hosts twice - once to collect seeds, once to compare the result
 * against what the clients believe - and the diagnostics walk has the same shape; all three must tolerate a host
 * that still exists with no component. Asserted for every entry point of that path: the solve produces the answer
 * it would produce without the empty host, the broadcast skips it, a projection and the world bridge answer zero,
 * a landing writes nothing and still lands the record, the diagnostics say "empty", and an exception that does
 * escape is counted once per kind, so a flood cannot hide a second, different failure.
 */
class CarrierSolveEmptyHostTest {

    private static final CarrierId CARRIER =
            CarrierId.contraption(UUID.fromString("00000000-0000-0000-0000-000000002502"));
    private static final CarrierId OTHER =
            CarrierId.contraption(UUID.fromString("00000000-0000-0000-0000-000000002503"));
    private static final ResourceKey<Level> DIMENSION =
            ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
                    ResourceLocation.parse("redstonecircuit:solve_empty_host_test"));

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

    /** Counts the write-backs, so "the record was updated" and "the clients were told" are one fact. */
    private static final class Recorder implements CarrierStore.WriteBack {

        final List<CarrierId> writes = new ArrayList<>();

        @Override
        public void wrote(CarrierId carrier) {
            writes.add(carrier);
        }
    }

    private final CarriedHostIndex index = new CarriedHostIndex();
    private final FakeWorld world = new FakeWorld();
    private final Recorder recorder = new Recorder();

    private static BlockPos pos(int x, int y, int z) {
        return new BlockPos(x, y, z);
    }

    private static BlockPos originOf(BlockPos local) {
        return new BlockPos(900 + local.getX(), 64, 900 + local.getZ());
    }

    private CarrierStore.TickContext context(long tick) {
        return new CarrierStore.TickContext(new CarrierStore.Clock(tick, DIMENSION), index, world,
                null, recorder, null);
    }

    /** The same context with the carrier bridge open: a fixed pose, and a world that answers one number. */
    private CarrierStore.TickContext bridged(long tick, int worldSignal) {
        CarrierPose pose = CarrierPose.at(new Vector3d(200.0, 64.0, 200.0));
        return new CarrierStore.TickContext(new CarrierStore.Clock(tick, DIMENSION), index, world,
                null, recorder, new CarrierWorldBridge(carrier -> pose,
                        (worldPos, worldDirection) -> worldSignal));
    }

    /** Picks a component up into a structure, the way the assembly callback does. */
    private Slot carry(CarrierId carrier, BlockPos local, ComponentType type) {
        Slot slot = new Slot(type);
        world.set(originOf(local), slot);
        index.claim(carrier, local, originOf(local), world);
        return index.at(carrier, local).slot();
    }

    /** A logic host with a debug supply planted on it: a wire the solve is guaranteed to move. */
    private Slot carryPowered(CarrierId carrier, BlockPos local, ComponentType type, int injected) {
        Slot slot = carry(carrier, local, type);
        slot.injectedPower = injected;
        return slot;
    }

    /**
     * One host, picked up with a component, and then emptied - through the named production route
     * ({@link CarriedHostIndex#removeSlot}), never by hand.
     */
    private CarriedHost carryAndEmpty(CarrierId carrier, BlockPos local, ComponentType type) {
        carry(carrier, local, type);
        assertTrue(index.removeSlot(carrier, local), "the host was there to empty");
        CarriedHost host = index.at(carrier, local);
        assertNotNull(host, "the host is still carried - only its contents are gone");
        assertNull(host.slot(), "and it holds nothing: the state this class is about");
        return host;
    }

    @BeforeEach
    void reset() {
        CarrierNetworks.clear();
        SolveFailureLedger.clear();
        recorder.writes.clear();
    }

    // --------------------------------------------------------------- the solve --

    /**
     * <b>The failure the log is full of.</b> A wire and an emptied host on the same structure: the wire
     * must still be solved.
     *
     * <p>Before the fix the seeds loop threw on {@code slot.type}, the per-structure catch swallowed it,
     * and the structure counted as <em>not solved at all</em> - {@code carriers=0, nodes=0} for a machine
     * that has a perfectly good wire in it. That is the assertion: a wire that would carry 11 carries 11
     * whether or not some other block of the same machine happens to be empty.
     */
    @Test
    @DisplayName("an emptied host does not stop the structure's own solve")
    void theSolveStillRunsWithAnEmptiedHostOnTheStructure() {
        Slot wire = carryPowered(CARRIER, pos(1, 0, 0), ComponentType.DUST, 11);
        carryAndEmpty(CARRIER, pos(2, 0, 0), ComponentType.RETAINING_REPEATER);

        CarrierNetworks.TickSummary summary =
                CarrierNetworks.tick(context(1L), new CarrierStore.Clock(1L, DIMENSION));

        assertEquals(1, summary.carriers(), "the structure was solved, not skipped");
        assertEquals(1, summary.nodes(), "its one logic host was offered as a seed");
        assertEquals(1, summary.solvedChanges(), "and the wire really was recomputed");
        assertEquals(11, wire.power, "the solve reached the wire");
        assertEquals(0, SolveFailureLedger.failureCount(),
                "and nothing in the solve path threw: " + SolveFailureLedger.report().toString());
    }

    /** The same shape with the empty host <em>first</em> in index order - order must not matter. */
    @Test
    @DisplayName("the emptied host's position in the host list does not matter")
    void theEmptyHostMayComeFirstOrLast() {
        carryAndEmpty(CARRIER, pos(1, 0, 0), ComponentType.REPEATER);
        Slot wire = carryPowered(CARRIER, pos(2, 0, 0), ComponentType.DUST, 9);

        CarrierNetworks.tick(context(1L), new CarrierStore.Clock(1L, DIMENSION));

        assertEquals(9, wire.power, "the wire behind the empty host is solved just the same");
        assertEquals(0, SolveFailureLedger.failureCount(), SolveFailureLedger.report().toString());
    }

    /**
     * The second walk in {@code solve} - the comparison against what the clients believe - must skip the
     * empty host too, and the pass must still report and sync honestly.
     */
    @Test
    @DisplayName("the change comparison skips the emptied host and still syncs the live ones")
    void theComparisonWalkSkipsTheEmptiedHost() {
        Slot wire = carry(CARRIER, pos(1, 0, 0), ComponentType.DUST);
        wire.power = 13;
        carryAndEmpty(CARRIER, pos(2, 0, 0), ComponentType.COMPARATOR);

        CarrierNetworks.TickSummary summary =
                CarrierNetworks.tick(context(1L), new CarrierStore.Clock(1L, DIMENSION));

        assertEquals(1, summary.carriers());
        CarrierNetworks.SolveReport report = CarrierNetworks.reportOf(CARRIER);
        assertEquals(1, report.solves(), "the pass ran");
        assertEquals(1, report.lastDiff(), "and it did find the wire's collapse to zero");
        assertEquals(0, report.unsentDiff(), "which was synced in the same pass");
        assertEquals(0, wire.power, "an unfed wire drops to zero");
        assertEquals(List.of(CARRIER), recorder.writes, "and the clients were told once, as before");
    }

    /** A structure that carries <em>only</em> emptied hosts is still a structure: solved, to nothing. */
    @Test
    @DisplayName("a structure of nothing but emptied hosts solves to nothing instead of failing")
    void aStructureOfOnlyEmptiedHostsSolves() {
        carryAndEmpty(CARRIER, pos(1, 0, 0), ComponentType.DUST);
        carryAndEmpty(CARRIER, pos(2, 0, 0), ComponentType.REPEATER);

        CarrierNetworks.TickSummary summary =
                CarrierNetworks.tick(context(1L), new CarrierStore.Clock(1L, DIMENSION));

        assertEquals(1, summary.carriers(), "the structure is accounted for");
        assertEquals(0, summary.nodes(), "with no logic host to offer as a seed");
        assertEquals(0, summary.solvedChanges());
        assertEquals(0, SolveFailureLedger.failureCount(), SolveFailureLedger.report().toString());
        assertEquals(1, CarrierNetworks.reportOf(CARRIER).solves());
    }

    /** Two structures in flight, one of them emptied: the other one is never affected. */
    @Test
    @DisplayName("one structure's emptied host cannot affect another structure's solve")
    void anotherStructuresSolveIsUnaffected() {
        carryAndEmpty(CARRIER, pos(1, 0, 0), ComponentType.DUST);
        Slot theirs = carryPowered(OTHER, pos(1, 0, 0), ComponentType.DUST, 15);

        CarrierNetworks.tick(context(1L), new CarrierStore.Clock(1L, DIMENSION));

        assertEquals(15, theirs.power, "the other machine solved normally");
        assertEquals(0, SolveFailureLedger.failureCount(), SolveFailureLedger.report().toString());
    }

    /** Putting a component back into an emptied host makes it a seed again on the very next pass. */
    @Test
    @DisplayName("a component put back into an emptied host is solved on the next pass")
    void aRefilledHostIsSolvedAgain() {
        BlockPos local = pos(3, 0, 0);
        carryAndEmpty(CARRIER, local, ComponentType.DUST);

        CarrierNetworks.tick(context(1L), new CarrierStore.Clock(1L, DIMENSION));
        assertEquals(0, CarrierNetworks.reportOf(CARRIER).nodes(), "nothing to solve while it is empty");

        Slot refilled = new Slot(ComponentType.DUST);
        refilled.injectedPower = 7;
        index.updateSlot(CARRIER, local, refilled);

        CarrierNetworks.TickSummary summary =
                CarrierNetworks.tick(context(2L), new CarrierStore.Clock(2L, DIMENSION));

        assertEquals(1, summary.nodes(), "the refilled host is a seed again");
        assertEquals(7, refilled.power, "and the solve reached it");
        assertEquals(0, SolveFailureLedger.failureCount(), SolveFailureLedger.report().toString());
    }

    /** The bridged pass meets the same walk, so it needs the same answer. */
    @Test
    @DisplayName("the world bridge's pass is not broken by an emptied host either")
    void theBridgedPassIsNotBrokenByAnEmptiedHost() {
        Slot wire = carry(CARRIER, pos(1, 0, 0), ComponentType.DUST);
        carryAndEmpty(CARRIER, pos(2, 0, 0), ComponentType.REPEATER);

        CarrierNetworks.tick(bridged(1L, 9), new CarrierStore.Clock(1L, DIMENSION));

        assertEquals(9, wire.power, "the world signal still reaches the machine");
        assertEquals(0, SolveFailureLedger.failureCount(), SolveFailureLedger.report().toString());
    }

    /** The pass is still a pass: a machine that settles keeps settling whether or not a host is empty. */
    @Test
    @DisplayName("a machine with an emptied host still settles instead of failing every tick")
    void anEmptiedHostDoesNotStopConvergence() {
        carryPowered(CARRIER, pos(1, 0, 0), ComponentType.DUST, 11);
        carryAndEmpty(CARRIER, pos(2, 0, 0), ComponentType.REPEATER);

        CarrierNetworks.tick(context(1L), new CarrierStore.Clock(1L, DIMENSION));
        CarrierNetworks.tick(context(2L), new CarrierStore.Clock(2L, DIMENSION));

        CarrierNetworks.SolveReport report = CarrierNetworks.reportOf(CARRIER);
        assertEquals(2, report.solves(), "both passes ran");
        assertEquals(0, report.lastDiff(), "and the second found nothing to change");
        assertTrue(report.settledWithNoChange(), "which is 'it is working', not 'it did nothing'");
        assertEquals(0, SolveFailureLedger.failureCount(), SolveFailureLedger.report().toString());
    }

    // ----------------------------------------------------------- diagnostics --

    /**
     * {@code /rc carrier} walks every host and reads {@code slot().type} to count wires and driven
     * components. It must answer "this one is empty", not throw into the command.
     */
    @Test
    @DisplayName("the /rc carrier diagnostics walk tolerates an emptied host")
    void theDiagnosticsWalkToleratesAnEmptiedHost() {
        carryPowered(CARRIER, pos(1, 0, 0), ComponentType.DUST, 11);
        carryAndEmpty(CARRIER, pos(2, 0, 0), ComponentType.RETAINING_REPEATER);

        List<String> lines = assertDoesNotThrow(() -> CarrierNetworks.describe(CARRIER, index),
                "a diagnostics line must not be the thing that fails the command");
        assertFalse(lines.isEmpty());
        assertTrue(lines.stream().anyMatch(line -> line.contains("hosts=2")),
                "the walk counts the emptied host as a host, not as a wire or a driven component: "
                        + lines);
    }

    // -------------------------------------------------- the rest of the path --

    /** The projection table: a projected host with nothing in it answers zero, and never throws. */
    @Test
    @DisplayName("a projected emptied host offers no signal, in either question")
    void theProjectionAnswersZeroForAnEmptiedHost() {
        CarrierPose pose = CarrierPose.at(new Vector3d(300.0, 64.0, 300.0));
        CarrierProjection.ProjectedHost projected = new CarrierProjection.ProjectedHost(CARRIER,
                pos(1, 0, 0), pos(300, 64, 300), pose, null, 0);

        assertEquals(0, CarrierProjection.emittedToward(projected, Direction.NORTH, false, false));
        assertEquals(0, CarrierProjection.emittedToward(projected, Direction.NORTH, true, false));
        assertEquals(0, CarrierProjection.emittedToward(null, Direction.NORTH, false, false));
    }

    /**
     * The landing path ("落回"): an emptied host that is put down must write nothing into the world, and
     * must still count as landed - otherwise the machine never stops answering for it.
     */
    @Test
    @DisplayName("releasing an emptied host writes nothing into the world and still lands it")
    void releasingAnEmptiedHostWritesNothing() {
        BlockPos local = pos(4, 0, 0);
        carryAndEmpty(CARRIER, local, ComponentType.DUST);
        // A pick-up MOVES the world entry out, so the position the host came from holds nothing.
        assertNull(world.get(originOf(local)));

        CarriedHostIndex.ReleaseOutcome outcome = index.release(CARRIER, local,
                where -> originOf(where), world);

        assertTrue(outcome.wrote(), "the record really did land: " + outcome.describe());
        assertEquals(CarriedHostIndex.ReleaseStatus.LANDED, outcome.status());
        assertNull(world.get(originOf(local)), "and put nothing into the world");
        assertEquals(0, SolveFailureLedger.failureCount(), SolveFailureLedger.report().toString());
    }

    /** The client-facing walk: the emptied host contributes no view, exactly as before. */
    @Test
    @DisplayName("the client-facing walk skips the emptied host rather than dereferencing it")
    void theClientWalkSkipsTheEmptiedHost() {
        carry(CARRIER, pos(1, 0, 0), ComponentType.DUST);
        carryAndEmpty(CARRIER, pos(2, 0, 0), ComponentType.DUST);

        int contributing = 0;
        for (CarriedHost host : index.liveHostsOf(CARRIER)) {
            Slot held = host.slot();
            if (held != null) {
                contributing++;
                assertNotNull(held.copy());
            }
        }
        assertEquals(1, contributing, "only the host that holds something contributes");
        assertEquals(2, index.liveHostsOf(CARRIER).size(), "and both are still live");
    }

    // ------------------------------------------------------------- the ledger --

    /**
     * Counting and "say it once" on the same fallback: every failure is counted, the first of each
     * <em>kind</em> is printed exactly once, and a later, different kind stays visible - the one thing worse
     * than a flood is a flood that hides the real defect behind its own repetition.
     */
    @Test
    @DisplayName("the solve fallback counts every failure and reports each kind once")
    void theSolveFallbackWarnsOncePerKindAndCountsEverything() {
        SolveFailureLedger.fail(CARRIER, new NullPointerException("slot"));
        assertEquals(1, SolveFailureLedger.failureCount());
        assertEquals(1, SolveFailureLedger.distinctKinds());
        assertTrue(SolveFailureLedger.warnedFor(NullPointerException.class));

        // The same kind again: counted, not printed again.
        SolveFailureLedger.fail(CARRIER, new NullPointerException("slot"));
        SolveFailureLedger.fail(OTHER, new NullPointerException("slot"));
        assertEquals(3, SolveFailureLedger.failureCount(), "every failure is counted");
        assertEquals(1, SolveFailureLedger.distinctKinds(), "but the kind is remembered once");

        // A different kind is a different defect and must not be silenced by the first one.
        SolveFailureLedger.fail(CARRIER, new IllegalStateException("a different defect"));
        assertEquals(4, SolveFailureLedger.failureCount());
        assertEquals(2, SolveFailureLedger.distinctKinds());
        assertTrue(SolveFailureLedger.warnedFor(IllegalStateException.class),
                "a second kind of failure must still be reportable");

        List<String> report = SolveFailureLedger.report();
        assertTrue(report.stream().anyMatch(line -> line.contains("NullPointerException")),
                "the report names the kinds: " + report);
        assertTrue(report.stream().anyMatch(line -> line.contains("IllegalStateException")),
                report.toString());
        assertTrue(report.stream().anyMatch(line -> line.contains("4 failure")),
                "and it states the total: " + report);
    }

    /** The ledger is bounded, so a pathological mix of failures cannot grow the table for ever. */
    @Test
    @DisplayName("the failure ledger has a hard ceiling and pools what it cannot name")
    void theFailureLedgerIsBounded() {
        // Twelve genuinely distinct exception KINDS (the ledger keys on the class, so twelve
        // RuntimeException instances would be one kind and would prove nothing about the ceiling).
        List<Throwable> kinds = List.of(
                new NullPointerException("1"), new IllegalStateException("2"),
                new IllegalArgumentException("3"), new UnsupportedOperationException("4"),
                new IndexOutOfBoundsException("5"), new ArithmeticException("6"),
                new ClassCastException("7"), new IllegalMonitorStateException("8"),
                new NegativeArraySizeException("9"), new ArrayStoreException("10"),
                new NumberFormatException("11"), new SecurityException("12"));
        assertTrue(kinds.size() > SolveFailureLedger.MAX_KINDS, "the table has to be overfilled");

        for (Throwable kind : kinds) {
            SolveFailureLedger.fail(CARRIER, kind);
        }
        assertEquals(kinds.size(), SolveFailureLedger.failureCount(),
                "every failure is still counted");
        assertEquals(SolveFailureLedger.MAX_KINDS, SolveFailureLedger.distinctKinds(),
                "but the ledger never exceeds its ceiling");
        assertTrue(SolveFailureLedger.report().stream()
                        .anyMatch(line -> line.contains("beyond the first")),
                "and the overflow is stated rather than dropped silently: "
                        + SolveFailureLedger.report().toString());
    }

    /** Clearing the ledger is what a server stop does, and it must leave no state behind. */
    @Test
    @DisplayName("clearing the ledger leaves no state behind")
    void clearingTheLedgerLeavesNoState() {
        SolveFailureLedger.fail(CARRIER, new NullPointerException("slot"));
        SolveFailureLedger.clear();
        assertEquals(0, SolveFailureLedger.failureCount());
        assertEquals(0, SolveFailureLedger.distinctKinds());
        assertFalse(SolveFailureLedger.warnedFor(NullPointerException.class));
        assertTrue(SolveFailureLedger.report().isEmpty());
    }

    /** A failure inside a whole solve pass is contained by {@code solveAll}, not propagated to the tick. */
    @Test
    @DisplayName("a solve pass is total: whatever one structure does, the caller gets a summary")
    void theSolvePassIsTotal() {
        carryPowered(CARRIER, pos(1, 0, 0), ComponentType.DUST, 11);
        carryAndEmpty(CARRIER, pos(2, 0, 0), ComponentType.DUST);

        assertDoesNotThrow(() -> CarrierNetworks.tick(context(1L), new CarrierStore.Clock(1L, DIMENSION)));
        assertEquals(0, SolveFailureLedger.failureCount(),
                "and with the empty-host dereference gone, nothing throws in the first place");
    }
}

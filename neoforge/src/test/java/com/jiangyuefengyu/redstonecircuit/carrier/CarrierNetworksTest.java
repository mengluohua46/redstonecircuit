package com.jiangyuefengyu.redstonecircuit.carrier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.Slot;
import com.jiangyuefengyu.redstonecircuit.logic.CarrierLightNetwork;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests P2's driver: the part that decides <em>which</em> structures get solved, at what rate, and
 * what happens to the answer.
 *
 * <h2>What is actually at stake</h2>
 * {@link CarrierLightNetworkTest} proves the solve itself. This proves the three things the brief
 * asks of the layer around it:
 *
 * <ul>
 *   <li><b>It is driven per tick, and only for what is in flight.</b> The author's world has ~840 host
 *       blocks; a driver that walked them would be the performance bug the brief warns about. So the
 *       first test is that a level with nothing carried costs nothing, and the second is that what
 *       <em>is</em> carried is solved on the very next pass.</li>
 *   <li><b>The answer is written back.</b> A solve that left the carried record alone would be
 *       invisible: the renderer, {@code /rc dump} and the goggles all read that record.</li>
 *   <li><b>Two drivers do not double-solve.</b> Create's per-actor callback and the level tick can both
 *       fire in one tick, and the guard that stops the second one is worth a test because getting it
 *       wrong is silent - it costs CPU and looks like nothing at all.</li>
 * </ul>
 *
 * <p>A fake index, a map for the store, a counter for the clock and a recording write-back: no game
 * anywhere.
 */
class CarrierNetworksTest {

    private static final CarrierId CONTRAPTION =
            CarrierId.contraption(UUID.fromString("00000000-0000-0000-0000-00000000c0ff"));
    private static final CarrierId OTHER =
            CarrierId.contraption(UUID.fromString("00000000-0000-0000-0000-00000000bee0"));
    private static final ResourceKey<Level> DIMENSION =
            ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
                    ResourceLocation.parse("redstonecircuit:test"));

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

    private CarrierStore.TickContext context(long gameTime) {
        return new CarrierStore.TickContext(new CarrierStore.Clock(gameTime, DIMENSION), index, world,
                null, recorder, null);
    }

    /** The same context with P4's bridge open: a fixed pose, and a world that answers one number. */
    private CarrierStore.TickContext bridged(long gameTime, CarrierPose pose, int worldSignal) {
        return new CarrierStore.TickContext(new CarrierStore.Clock(gameTime, DIMENSION), index, world,
                null, recorder, new CarrierWorldBridge(carrier -> pose,
                        (worldPos, worldDirection) -> worldSignal));
    }

    /** Picks a component up into a structure, the way the assembly callback does. */
    private Slot carry(CarrierId carrier, BlockPos local, BlockPos origin, ComponentType type) {
        Slot slot = new Slot(type);
        world.set(origin, slot);
        index.claim(carrier, local, origin, world);
        return index.at(carrier, local).slot();
    }

    /**
     * One host block carrying {@code type}, with a debug supply planted on it.
     *
     * <p>The supply is what the solver will turn into a value, and it is deliberately not a lever on a
     * neighbouring block: only <b>logic</b> components - wires and driven ones - are offered to the
     * solve as seeds, so a lever would be a component the driver never looks at, and a test built on
     * one would be asserting that nothing happens.
     */
    private Slot carryPowered(CarrierId carrier, BlockPos local, ComponentType type, int injected) {
        Slot slot = carry(carrier, local,
                new BlockPos(local.getX() + 100, 64, local.getZ() + 100), type);
        slot.injectedPower = injected;
        return slot;
    }

    /**
     * A repeater with a wire on its output side, and a powered wire on its input side.
     *
     * <p>Two things about the shape are worth writing down, because getting either wrong makes a
     * repeater that is simply not connected to anything - which in game looks exactly like "the solve
     * does nothing":
     *
     * <ul>
     *   <li>{@code Direction.NORTH} is {@code -Z}. A repeater takes its input from {@code facing} and
     *       emits out of the opposite side, so a repeater facing SOUTH reads the block south of it and
     *       drives the one to its north.</li>
     *   <li>The input wire and the output wire are <b>not</b> in the same component, and cannot be:
     *       a wire reads every side, so the wire south of the repeater sees it, while the repeater
     *       reads only its facing side and so does not see the wire north of it. That is what stops the
     *       repeater's own output from feeding back into its input.</li>
     * </ul>
     *
     * @return the wire on the repeater's output side
     */
    private Slot repeaterWithWire(CarrierId carrier, BlockPos repeaterLocal, ComponentType wireType) {
        carryPowered(carrier, repeaterLocal.relative(Direction.SOUTH), ComponentType.DUST, 15);

        Slot repeater = carry(carrier, repeaterLocal,
                new BlockPos(repeaterLocal.getX() + 100, 64, repeaterLocal.getZ() + 100),
                ComponentType.REPEATER);
        // Facing SOUTH: input from the south, output to the north.
        repeater.facing = Direction.SOUTH;

        return carry(carrier, repeaterLocal.relative(Direction.NORTH),
                new BlockPos(repeaterLocal.getX() + 100, 64, repeaterLocal.getZ()), wireType);
    }

    private static BlockPos pos(int x, int y, int z) {
        return new BlockPos(x, y, z);
    }

    /** Resets the shared static state between tests, so ordering cannot matter. */
    private void reset() {
        CarrierNetworks.clear();
        recorder.writes.clear();
    }

    // --------------------------------------------------------- what gets solved --
    // --------------------------------------------------------- what gets solved --

    @Test
    @DisplayName("a level with nothing in flight costs one boolean and no work at all")
    void nothingInFlightIsFree() {
        reset();
        CarrierNetworks.TickSummary summary =
                CarrierNetworks.tick(context(5L), new CarrierStore.Clock(5L, DIMENSION));

        assertTrue(summary.idle(), "no structure was solved");
        assertEquals(0, summary.nodes(), "and not one host was looked at");
        assertEquals(0, summary.components());
    }

    @Test
    @DisplayName("what is riding is solved on the very next pass, in the structure's own coordinates")
    void carriedHostsAreSolved() {
        reset();
        Slot wire = carryPowered(CONTRAPTION, pos(1, 0, 0), ComponentType.DUST, 11);

        CarrierStore.Clock clock = new CarrierStore.Clock(7L, DIMENSION);
        CarrierNetworks.TickSummary summary = CarrierNetworks.tick(context(7L), clock);

        assertEquals(1, summary.carriers(), "one structure in flight, one structure solved");
        assertEquals(1, summary.nodes(), "its one logic host was offered as a seed");
        assertEquals(1, summary.solvedChanges(), "and the wire went from nothing to eleven");
        assertEquals(0, summary.changes(),
                "and since it was synced in the same pass, nothing is left to send");
        assertEquals(11, wire.power, "the record now holds the recomputed value");
        assertEquals(1, CarrierNetworks.reportOf(CONTRAPTION).solves());
        assertEquals(7L, CarrierNetworks.reportOf(CONTRAPTION).lastTick());
    }

    @Test
    @DisplayName("the write-back reaches the carried record, the store and the clients once")
    void theAnswerIsWrittenBack() {
        reset();
        Slot wire = carryPowered(CONTRAPTION, pos(1, 0, 0), ComponentType.DUST, 11);

        CarrierNetworks.tick(context(1L), new CarrierStore.Clock(1L, DIMENSION));

        assertEquals(11, index.at(CONTRAPTION, pos(1, 0, 0)).slot().power,
                "the index - which is what the renderer and /rc dump read - holds the new value");
        assertEquals(11, wire.power, "and it is the same slot object, not a copy that can drift");
        assertEquals(List.of(CONTRAPTION), recorder.writes,
                "the clients were told exactly once, with the message P1 already sends");
    }

    @Test
    @DisplayName("a structure that settles with nothing to change reports a solve and no difference")
    void aParkedMachineSettlesAndSaysNothing() {
        reset();
        carryPowered(CONTRAPTION, pos(1, 0, 0), ComponentType.DUST, 11);

        CarrierNetworks.tick(context(1L), new CarrierStore.Clock(1L, DIMENSION));
        recorder.writes.clear();
        CarrierNetworks.tick(context(2L), new CarrierStore.Clock(2L, DIMENSION));

        CarrierNetworks.SolveReport report = CarrierNetworks.reportOf(CONTRAPTION);
        assertEquals(2, report.solves(), "the second pass ran");
        assertEquals(0, report.lastDiff(), "and found nothing to change");
        assertTrue(report.settledWithNoChange(),
                "which is the expected state of a machine whose inputs have not moved - the fact the "
                        + "acceptance procedure has to read as 'it is working', not as 'it did nothing'");
        assertTrue(recorder.writes.isEmpty(), "so nothing was sent");
    }

    @Test
    @DisplayName("a host whose value collapses is solved, but not reported as a change to send")
    void theBaselineIsWhatTheClientsAlreadyBelieve() {
        reset();
        // A wire already carrying 11 when the structure picks it up, with nothing feeding it. The
        // carrier message that announced it carried that value, so the solve really does drain it -
        // and there is still no difference to send, because the clients were already told "11".
        Slot wire = carry(CONTRAPTION, pos(1, 0, 0), pos(10, 64, 10), ComponentType.DUST);
        wire.power = 11;

        CarrierNetworks.tick(context(1L), new CarrierStore.Clock(1L, DIMENSION));

        assertEquals(0, wire.power, "an unpowered wire in a structure with no supply drops to zero");
        CarrierNetworks.SolveReport report = CarrierNetworks.reportOf(CONTRAPTION);
        assertEquals(1, report.lastDiff(), "the pass did change something, and says so");
        assertEquals(0, report.unsentDiff(),
                "but nothing is left unsent: the difference is measured against what the clients "
                        + "already believe, which was 11 until the sync corrected it");
        assertEquals(1, recorder.writes.size(), "and the clients were told about the drop once");
    }

    @Test
    @DisplayName("two structures are solved independently, and never leak into each other")
    void structuresAreIndependent() {
        reset();
        Slot mine = carryPowered(CONTRAPTION, pos(1, 0, 0), ComponentType.DUST, 11);
        // The same local coordinates in a second machine, with nothing feeding its wire.
        Slot theirs = carry(OTHER, pos(1, 0, 0), pos(50, 64, 50), ComponentType.DUST);

        CarrierNetworks.tick(context(1L), new CarrierStore.Clock(1L, DIMENSION));

        assertEquals(11, mine.power, "the first machine's wire takes its own supply");
        assertEquals(0, theirs.power,
                "the second machine's wire is at the same local coordinate but has no supply");
    }

    @Test
    @DisplayName("a structure with only manual components is still solved, and changes nothing")
    void manualOnlyStructuresAreNotSolved() {
        reset();
        carry(CONTRAPTION, pos(1, 0, 0), pos(10, 64, 10), ComponentType.LEVER).power = 15;

        CarrierNetworks.TickSummary summary =
                CarrierNetworks.tick(context(1L), new CarrierStore.Clock(1L, DIMENSION));

        assertEquals(1, summary.carriers(), "the structure is still accounted for");
        assertEquals(0, summary.nodes(), "but a lever is not logic, so it is not a seed");
        assertEquals(0, summary.components());
        assertEquals(0, summary.changes(), "and there is nothing to re-send");
        assertFalse(CarrierLightNetwork.isLogic(ComponentType.LEVER));
    }

    // ------------------------------------------------------------------- P4 --
    // ------------------------------------------------------------ the bridge --

    /**
     * P4's whole point, as one assertion: with the bridge open the world is an input the solve can see,
     * and a machine that had nothing to say for ever finally changes value.
     */
    @Test
    @DisplayName("with the bridge open a signal from the world reaches the machine and changes it")
    void theWorldFeedsAMachineWithTheBridgeOpen() {
        reset();
        Slot wire = carry(CONTRAPTION, pos(1, 0, 0), pos(10, 64, 10), ComponentType.DUST);
        CarrierPose pose = CarrierPose.at(new Vector3d(100.0, 64.0, 100.0));

        CarrierNetworks.TickSummary summary =
                CarrierNetworks.tick(bridged(1L, pose, 9), new CarrierStore.Clock(1L, DIMENSION));

        assertEquals(9, wire.power, "the wire holds what the world pushes into the place it now is");
        assertEquals(1, summary.solvedChanges(), "and that is a real change, which is what P4 is for");
        assertEquals(List.of(CONTRAPTION), recorder.writes, "which the clients are told about once");
    }

    @Test
    @DisplayName("with the bridge closed the very same world signal reaches nothing")
    void aFrozenMachineIgnoresTheWorld() {
        reset();
        Slot wire = carry(CONTRAPTION, pos(1, 0, 0), pos(10, 64, 10), ComponentType.DUST);

        CarrierNetworks.tick(context(1L), new CarrierStore.Clock(1L, DIMENSION));

        assertEquals(0, wire.power, "the P1/P2 answer: the world outside cannot power the machine");
    }

    @Test
    @DisplayName("a carrier whose pose cannot be read is frozen for that pass, not broken")
    void aCarrierWithoutAPoseFallsBackToFrozen() {
        reset();
        Slot wire = carry(CONTRAPTION, pos(1, 0, 0), pos(10, 64, 10), ComponentType.DUST);
        // The bridge is open, but this structure cannot say where it is - a machine that has just come
        // apart, or a dimension it is no longer in. The honest answer is the frozen one.
        CarrierStore.TickContext noPose = new CarrierStore.TickContext(
                new CarrierStore.Clock(1L, DIMENSION), index, world, null, recorder,
                new CarrierWorldBridge(carrier -> null, (worldPos, worldDirection) -> 9));

        CarrierNetworks.tick(noPose, new CarrierStore.Clock(1L, DIMENSION));

        assertEquals(0, wire.power, "no pose, no bridge, and no exception either");
    }

    // ------------------------------------------------------------------- drivers --

    @Test
    @DisplayName("two drivers firing in one tick solve once, not twice")
    void theTwoDriversDoNotDoubleSolve() {
        reset();
        carryPowered(CONTRAPTION, pos(1, 0, 0), ComponentType.DUST, 11);

        CarrierStore.Clock clock = new CarrierStore.Clock(42L, DIMENSION);
        CarrierNetworks.tick(context(42L), clock);
        CarrierNetworks.tick(context(42L), clock);

        assertEquals(1, CarrierNetworks.reportOf(CONTRAPTION).solves(),
                "Create's actor tick and the level tick are the same tick, and it runs once");

        // The next tick is a new tick, so the next pass does run.
        CarrierNetworks.tick(context(43L), new CarrierStore.Clock(43L, DIMENSION));
        assertEquals(2, CarrierNetworks.reportOf(CONTRAPTION).solves());
    }

    @Test
    @DisplayName("a structure whose hosts have all been put down is forgotten")
    void putDownStructuresAreForgotten() {
        reset();
        carry(CONTRAPTION, pos(1, 0, 0), pos(10, 64, 10), ComponentType.DUST);
        CarrierNetworks.tick(context(1L), new CarrierStore.Clock(1L, DIMENSION));
        assertEquals(1, CarrierNetworks.trackedCarrierCount());

        index.release(CONTRAPTION, pos(1, 0, 0), null, world);
        assertEquals(List.of(CONTRAPTION), index.forgetReleased(),
                "forgetting a carrier reports it, so the single release exit can announce it");
        CarrierNetworks.tick(context(2L), new CarrierStore.Clock(2L, DIMENSION));

        assertEquals(0, CarrierNetworks.trackedCarrierCount(),
                "the solve's memory is bounded by what is actually in flight");
        assertFalse(CarrierNetworks.reportOf(CONTRAPTION).settledWithNoChange());
    }

    @Test
    @DisplayName("a repeater drives the wire it faces, and takes its delay across passes")
    void aRepeaterDrivesItsOutputAndTakesItsDelay() {
        reset();
        // The lay-out, in Minecraft's coordinates: a repeater facing SOUTH takes its input from +Z and
        // drives -Z. So the powered wire goes at z=2, the repeater at z=1, and the wire it drives at
        // z=0.
        Slot source = carryPowered(CONTRAPTION, pos(0, 0, 2), ComponentType.DUST, 15);
        Slot repeater = carry(CONTRAPTION, pos(0, 0, 1), pos(100, 64, 1), ComponentType.REPEATER);
        repeater.facing = Direction.SOUTH;
        Slot wire = carry(CONTRAPTION, pos(0, 0, 0), pos(100, 64, 0), ComponentType.DUST);

        CarrierNetworks.tick(context(100L), new CarrierStore.Clock(100L, DIMENSION));
        assertEquals(15, source.power, "the source wire is simply carrying what was injected");
        assertEquals(0, repeater.power, "and the repeater has not followed it yet - it has a delay");
        assertEquals(0, wire.power, "so nothing has reached the wire");
        assertEquals(1, CarrierNetworks.reportOf(CONTRAPTION).pendingFlips(),
                "and the repeater's re-evaluation is waiting");

        // The repeater's default setting is one, which is two game ticks, so the pass two ticks later
        // is the one that follows it - and the wire, being in the repeater's own component, comes with
        // it in the same pass.
        CarrierNetworks.tick(context(102L), new CarrierStore.Clock(102L, DIMENSION));
        assertEquals(15, repeater.power, "the repeater has followed its input at full strength");
        assertEquals(15, wire.power, "and the wire it drives is lit in the same pass");

        CarrierNetworks.tick(context(103L), new CarrierStore.Clock(103L, DIMENSION));
        assertEquals(0, CarrierNetworks.reportOf(CONTRAPTION).pendingFlips(), "with nothing left waiting");
        assertEquals(0, CarrierNetworks.reportOf(CONTRAPTION).lastDiff(), "and it has settled");
    }
}

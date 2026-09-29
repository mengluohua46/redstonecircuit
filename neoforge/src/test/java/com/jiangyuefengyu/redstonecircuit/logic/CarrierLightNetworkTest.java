package com.jiangyuefengyu.redstonecircuit.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.jiangyuefengyu.redstonecircuit.data.ComparatorMode;
import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.Slot;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests P2's solve loop: what happens to a redstone network <em>inside</em> a moving structure.
 *
 * <h2>Why this is worth a test of its own</h2>
 * The world's solver is already covered by {@code PowerSolverTest} and the game tests, and the whole
 * reason {@link CarrierLightNetwork} exists is that the loop <em>around</em> that solver is a second
 * implementation - the one that runs over local coordinates with no level, no block states and no
 * neighbour notifications. Everything the brief asks of P2's first two items lives here: that the
 * algorithm really is reused rather than re-derived, that dust still reaches 15 and fades a step per
 * hop, that a repeater still takes its delay rather than switching instantly, and that the per-tick
 * work ceiling refuses rather than half-solving a component.
 *
 * <p>Plain data throughout: a map of positions to nodes, a counter for the clock, and no Minecraft
 * level anywhere.
 */
class CarrierLightNetworkTest {

    /** A solver node that also names its slot, which is how the loop reads delays and debug supplies. */
    private static final class FakeNode implements CarrierLightNetwork.CarrierNode {

        final Slot slot;
        private final Set<Direction> forcedOn = EnumSet.noneOf(Direction.class);
        private final Set<Direction> forcedOff = EnumSet.noneOf(Direction.class);

        FakeNode(ComponentType type) {
            this.slot = new Slot(type);
        }

        FakeNode facing(Direction direction) {
            slot.facing = direction;
            return this;
        }

        FakeNode delay(int ticks) {
            slot.delay = ticks;
            return this;
        }

        FakeNode mode(ComparatorMode value) {
            slot.mode = value;
            return this;
        }

        FakeNode powered(int value) {
            slot.power = value;
            return this;
        }

        FakeNode injected(int value) {
            slot.injectedPower = value;
            return this;
        }

        /** The current output, named so it does not clash with the interface's {@link #power()}. */
        int level() {
            return slot.power;
        }

        @Override
        public Slot slot() {
            return slot;
        }

        @Override
        public ComponentType type() {
            return slot.type;
        }

        @Override
        public int power() {
            return slot.power;
        }

        @Override
        public void setPower(int power) {
            slot.power = PowerSolver.clamp(power);
        }

        @Override
        public Direction facing() {
            return slot.facing;
        }

        @Override
        public ComparatorMode mode() {
            return slot.mode;
        }

        @Override
        public boolean open(Direction direction) {
            return forcedOn.contains(direction);
        }

        @Override
        public boolean explicitlyCut(Direction direction) {
            return forcedOff.contains(direction);
        }

        @Override
        public boolean routingLocked() {
            return !forcedOn.isEmpty();
        }
    }

    /**
     * The inside of one structure: positions to nodes, plus the one thing a test ever wants to vary -
     * the supply arriving from outside, which the real {@code CarrierSpace} pins at zero.
     */
    private static final class FakeSpace implements PowerEnvironment {

        private final Map<Long, FakeNode> nodes = new HashMap<>();
        private int external;

        FakeNode node(int x, int y, int z, ComponentType type) {
            FakeNode node = new FakeNode(type);
            nodes.put(BlockPos.asLong(x, y, z), node);
            return node;
        }

        @Nullable
        FakeNode at(int x, int y, int z) {
            return nodes.get(BlockPos.asLong(x, y, z));
        }

        /** What the structure's outside pushes in; production always leaves this at zero. */
        FakeSpace outside(int signal) {
            this.external = signal;
            return this;
        }

        @Override
        public Node nodeAt(int x, int y, int z) {
            return nodes.get(BlockPos.asLong(x, y, z));
        }

        @Override
        public int externalSignal() {
            return external;
        }

        @Override
        public int externalSignal(Direction direction) {
            return external;
        }

        @Override
        public boolean isRedstoneConductor(int x, int y, int z) {
            return false;
        }
    }

    private static BlockPos at(int x, int y, int z) {
        return new BlockPos(x, y, z);
    }

    private static List<BlockPos> seeds(BlockPos... positions) {
        return new ArrayList<>(Arrays.asList(positions));
    }

    /**
     * Ticks a network forward until it stops finding more to do.
     *
     * <p>Worth spelling out why this is not just "solve twice". A driven component is <em>scheduled</em>
     * during a solve and evaluated when its delay elapses, and the wires it reads may still have been
     * mid-propagation when it was scheduled - which is deliberate, because it is exactly what the
     * world's network does, and what gives a repeater its delay instead of an instant switch. So a
     * circuit that needs a comparator settled is not settled by one pass, and a test that assumed it
     * was would be testing something the game does not do.
     *
     * @return the clock value it stopped at
     */
    private static long settle(CarrierLightNetwork network, PowerEnvironment space, long from,
                               BlockPos... seeds) {
        long now = from;
        for (int i = 0; i < 32; i++) {
            int result = network.solve(space, seeds(seeds), now);
            // A pass that changed nothing *and* has nothing queued is the fixpoint. Both halves are
            // needed: a pass that applies a scheduled flip changes something, and a pass that only
            // leaves a flip waiting has not changed anything yet - neither is settled.
            if (result == 0 && network.pendingCount() == 0) {
                return now;
            }
            now++;
        }
        throw new AssertionError("the network did not settle in 32 passes");
    }

    // ------------------------------------------------------------------ reuse --

    @Test
    @DisplayName("the loop propagates exactly as the world does: a wire hop costs one")
    void wireHopCostsOne() {
        FakeSpace space = new FakeSpace();
        // A lever feeding three pieces of dust to its east. The lever is a source, so the first wire
        // is lit to 15 and each further hop loses one - the vanilla rule, and the thing that would
        // silently differ if this loop re-derived the algorithm instead of reusing PowerSolver.
        space.node(0, 0, 0, ComponentType.LEVER).powered(15);
        space.node(1, 0, 0, ComponentType.DUST);
        space.node(2, 0, 0, ComponentType.DUST);
        space.node(3, 0, 0, ComponentType.DUST);

        CarrierLightNetwork network = new CarrierLightNetwork(64);
        int changed = network.solve(space, seeds(at(1, 0, 0)), 1L);

        assertEquals(15, space.at(1, 0, 0).level(), "the first wire takes the lever at full strength");
        assertEquals(14, space.at(2, 0, 0).level(), "and each hop after that costs one");
        assertEquals(13, space.at(3, 0, 0).level());
        assertEquals(3, changed, "all three wires changed, and are reported once each");
        assertEquals(1, network.componentCount(), "one wire run, not three components");
    }

    @Test
    @DisplayName("superconducting wire keeps the strength it was given, all the way along the run")
    void superconductingRunDoesNotFade() {
        FakeSpace space = new FakeSpace();
        space.node(0, 0, 0, ComponentType.LEVER).powered(15);
        for (int x = 1; x <= 6; x++) {
            space.node(x, 0, 0, ComponentType.SUPERCONDUCTOR);
        }

        new CarrierLightNetwork(64).solve(space, seeds(at(1, 0, 0)), 1L);

        assertEquals(15, space.at(6, 0, 0).level(),
                "a run longer than the 15 blocks ordinary dust survives still carries the signal");
    }

    @Test
    @DisplayName("the structure's outside is the solver's supply, and only that")
    void theOutsideIsTheSolverSupply() {
        // This is the boundary P1 froze and P4 would bridge, expressed as one number. The in-structure
        // environment answers 0 for it, so this test pins down both halves: that a non-zero supply
        // *would* be consumed here (the loop really does read it), and that production leaves it at 0.
        FakeSpace powered = new FakeSpace();
        powered.node(0, 0, 0, ComponentType.DUST);
        powered.outside(11);
        new CarrierLightNetwork(64).solve(powered, seeds(at(0, 0, 0)), 1L);
        assertEquals(11, powered.at(0, 0, 0).level(),
                "a wire takes whatever the world pushes into its host block");

        FakeSpace frozen = new FakeSpace();
        frozen.node(0, 0, 0, ComponentType.DUST);
        frozen.outside(0);
        new CarrierLightNetwork(64).solve(frozen, seeds(at(0, 0, 0)), 1L);
        assertEquals(0, frozen.at(0, 0, 0).level(),
                "and with the boundary frozen - the production setting - a machine cannot be powered "
                        + "by a world it has left");
    }

    @Test
    @DisplayName("an injected debug value is still consumed, because it is part of the component")
    void injectedPowerIsAnInput() {
        FakeSpace space = new FakeSpace();
        space.node(0, 0, 0, ComponentType.DUST).injected(9);
        space.node(1, 0, 0, ComponentType.DUST);

        new CarrierLightNetwork(64).solve(space, seeds(at(0, 0, 0)), 1L);

        assertEquals(9, space.at(0, 0, 0).level(), "the injected supply is the wire's own floor");
        assertEquals(8, space.at(1, 0, 0).level(), "and the next wire is one hop below it");
    }

    // -------------------------------------------------------------- components --

    @Test
    @DisplayName("two repeaters facing away from each other are two components, not one")
    void backToBackDiodesAreSeparate() {
        FakeSpace space = new FakeSpace();
        // West repeater: input on its west side, output to the east. East repeater: the other way
        // round. Neither reads the other, so they are two networks - exactly as in the world, and the
        // reason the seed list has to contain both.
        space.node(0, 0, 0, ComponentType.REPEATER).facing(Direction.WEST);
        space.node(1, 0, 0, ComponentType.REPEATER).facing(Direction.EAST);

        assertEquals(1, CarrierLightNetwork.collectComponent(space, at(0, 0, 0)).size(),
                "the west one cannot see the east one");
        assertEquals(1, CarrierLightNetwork.collectComponent(space, at(1, 0, 0)).size(),
                "and the east one cannot see the west one");
    }

    @Test
    @DisplayName("a driven component delays, then follows its input - and a second change does not reset it")
    void repeaterTakesItsDelayAndSchedulesOnce() {
        FakeSpace space = new FakeSpace();
        space.node(0, 0, 0, ComponentType.LEVER).powered(15);
        FakeNode repeater = space.node(1, 0, 0, ComponentType.REPEATER)
                .facing(Direction.WEST).delay(4);

        CarrierLightNetwork network = new CarrierLightNetwork(64);
        network.solve(space, seeds(at(1, 0, 0)), 100L);

        // A repeater's delay setting is in redstone ticks, so setting 4 is eight game ticks.
        assertEquals(0, repeater.level(), "the output does not follow its input in the same solve");
        assertEquals(1, network.pendingCount(), "it scheduled one re-evaluation instead");

        network.solve(space, seeds(at(1, 0, 0)), 107L);
        assertEquals(0, repeater.level(), "seven ticks in, the eight-tick delay has not elapsed");
        assertEquals(1, network.pendingCount(), "and the second solve did not schedule a second one");

        network.solve(space, seeds(at(1, 0, 0)), 108L);
        assertEquals(15, repeater.level(), "a repeater relays at full strength once its delay is up");
        assertEquals(0, network.pendingCount(), "and the queue is empty again");
    }

    @Test
    @DisplayName("a pulse shorter than the delay disappears, exactly as it does in the world")
    void aShortPulseIsSwallowed() {
        FakeSpace space = new FakeSpace();
        FakeNode lever = space.node(0, 0, 0, ComponentType.LEVER).powered(15);
        FakeNode repeater = space.node(1, 0, 0, ComponentType.REPEATER)
                .facing(Direction.WEST).delay(1);

        CarrierLightNetwork network = new CarrierLightNetwork(64);
        network.solve(space, seeds(at(1, 0, 0)), 0L);
        assertEquals(1, network.pendingCount(), "setting 1 is a two-tick delay");

        // The supply is gone before the delay elapses. The output is decided when the tick arrives,
        // not when it was scheduled - which is what makes a short pulse vanish instead of stretching.
        lever.powered(0);
        network.solve(space, seeds(at(1, 0, 0)), 2L);
        assertEquals(0, repeater.level(), "a pulse shorter than the delay does not latch a repeater on");
    }

    @Test
    @DisplayName("a comparator compares its back input against its sides, and subtracts in subtract mode")
    void comparatorModes() {
        // Dust injects 12 behind the comparator, and a second wire beside it feeds a side at 10. Both
        // wires are seeds of their own, which matters: a comparator reads its sides by design and its
        // two side wires are *not* part of its component - the same reason the driver hands over every
        // host a structure carries rather than one seed per circuit.
        FakeSpace compare = new FakeSpace();
        compare.node(0, 0, 0, ComponentType.DUST).injected(12);
        FakeNode comparing = compare.node(1, 0, 0, ComponentType.COMPARATOR)
                .facing(Direction.WEST).delay(1);
        compare.node(1, 0, 1, ComponentType.DUST).injected(10);

        CarrierLightNetwork network = new CarrierLightNetwork(64);
        settle(network, compare, 0L, at(0, 0, 0), at(1, 0, 0), at(1, 0, 1));
        assertEquals(12, comparing.level(), "compare passes the back signal through when it leads");

        FakeSpace subtract = new FakeSpace();
        subtract.node(0, 0, 0, ComponentType.DUST).injected(12);
        FakeNode subtracting = subtract.node(1, 0, 0, ComponentType.COMPARATOR)
                .facing(Direction.WEST).delay(1).mode(ComparatorMode.SUBTRACT);
        subtract.node(1, 0, 1, ComponentType.DUST).injected(10);

        CarrierLightNetwork other = new CarrierLightNetwork(64);
        settle(other, subtract, 0L, at(0, 0, 0), at(1, 0, 0), at(1, 0, 1));
        assertEquals(2, subtracting.level(), "subtract mode takes the side away from the back");
    }

    @Test
    @DisplayName("a manual source is read but never solved, and is not a seed")
    void manualSourcesAreNeverDriven() {
        FakeSpace space = new FakeSpace();
        FakeNode lever = space.node(0, 0, 0, ComponentType.LEVER).powered(15);

        CarrierLightNetwork network = new CarrierLightNetwork(64);
        network.solve(space, seeds(at(0, 0, 0)), 0L);

        assertEquals(15, lever.level(), "a lever's value is the player's, not the solver's");
        assertEquals(0, network.pendingCount(), "and it never schedules anything");
        assertFalse(CarrierLightNetwork.isLogic(ComponentType.LEVER),
                "it is not counted as logic, so it is never used as a seed either");
        assertTrue(CarrierLightNetwork.isLogic(ComponentType.DUST));
        assertTrue(CarrierLightNetwork.isLogic(ComponentType.SUPERCONDUCTOR));
        assertTrue(CarrierLightNetwork.isLogic(ComponentType.REPEATER));
    }

    // ------------------------------------------------------------------ budgets --

    @Test
    @DisplayName("a component too big for the per-tick budget is refused, not half solved")
    void theBudgetRefusesRatherThanHalfSolving() {
        FakeSpace space = new FakeSpace();
        space.node(0, 0, 0, ComponentType.LEVER).powered(15);
        space.node(1, 0, 0, ComponentType.DUST).powered(7);
        space.node(2, 0, 0, ComponentType.DUST).powered(6);
        space.node(3, 0, 0, ComponentType.DUST).powered(5);

        // A budget of two against a three-block wire run: the whole run is one component, so there is
        // nothing that can be relaxed without resetting a wire the loop cannot finish.
        CarrierLightNetwork network = new CarrierLightNetwork(2);
        assertEquals(-1, network.solve(space, seeds(at(1, 0, 0)), 0L),
                "the caller is told the work did not happen");

        assertEquals(7, space.at(1, 0, 0).level(),
                "and no wire was reset to zero on the way to a fixpoint it never reached");
        assertEquals(6, space.at(2, 0, 0).level());
        assertEquals(5, space.at(3, 0, 0).level());
        assertEquals(0, network.componentCount());
    }

    @Test
    @DisplayName("a component too big for the budget stops the pass, and the caller is told")
    void aBudgetOverflowIsReportedRatherThanGuessedAt() {
        FakeSpace space = new FakeSpace();
        // A three-block run whose head carries an injected value, so the whole run would change if it
        // were relaxed. Note that the injected supply is an input, not a stored value: the head starts
        // at zero like any other freshly picked-up wire.
        space.node(0, 0, 0, ComponentType.DUST).injected(15);
        space.node(1, 0, 0, ComponentType.DUST).powered(9);
        space.node(2, 0, 0, ComponentType.DUST).powered(8);

        // A budget of two against a component of three: nothing can be relaxed without resetting a
        // wire the pass cannot finish, so the pass refuses and reports it.
        CarrierLightNetwork network = new CarrierLightNetwork(2);
        int result = network.solve(space, seeds(at(0, 0, 0)), 0L);

        assertEquals(-1, result, "the caller is told the work did not happen");
        assertEquals(0, space.at(0, 0, 0).level(),
                "and not one wire was reset to zero on the way to a fixpoint it never reached - the "
                        + "head included, even though its injected supply would have raised it");
        assertEquals(9, space.at(1, 0, 0).level());
        assertEquals(8, space.at(2, 0, 0).level());
        assertEquals(0, network.componentCount(), "and the component was not counted as settled");

        // The same network, one more of budget: now it fits, and the whole run is solved in one pass.
        CarrierLightNetwork roomy = new CarrierLightNetwork(4);
        assertEquals(3, roomy.solve(space, seeds(at(0, 0, 0)), 0L),
                "with room for the whole component, all three wires are settled");
        assertEquals(15, space.at(0, 0, 0).level(), "the head reaches its injected strength");
        assertEquals(14, space.at(1, 0, 0).level(), "and the run fades a step per hop, as always");
        assertEquals(13, space.at(2, 0, 0).level());
        assertEquals(1, roomy.componentCount());
    }

    @Test
    @DisplayName("nothing is solved for a coordinate the structure does not carry")
    void emptyCoordinatesAreSkipped() {
        FakeSpace space = new FakeSpace();
        space.node(0, 0, 0, ComponentType.DUST);

        CarrierLightNetwork network = new CarrierLightNetwork(64);
        assertEquals(0, network.solve(space, seeds(at(5, 5, 5)), 0L),
                "a seed that holds nothing costs one lookup and no work");
        assertEquals(0, network.componentCount(), "nothing was settled");
        assertEquals(1, network.solveCount(), "but the pass still happened, for the diagnostics");
        assertEquals(0, space.at(0, 0, 0).level(),
                "and the component that does exist was not touched: it was not in the seed list");
    }
}

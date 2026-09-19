package com.jiangyuefengyu.redstonecircuit.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import com.jiangyuefengyu.redstonecircuit.data.ComparatorMode;
import com.jiangyuefengyu.redstonecircuit.data.ComponentType;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies the rules for everything that can live inside a host block.
 *
 * <p>Three separate things are pinned down here, because all three are invisible until they are wrong
 * in game:
 * <ul>
 *   <li><b>Propagation</b>: a wire hop costs one, a source does not - so a lever lights dust to 15 but
 *       the next piece of dust only to 14.</li>
 *   <li><b>Direction</b>: dust talks to every side, a torch not to the side it hangs on, a repeater
 *       and comparator only to the one they point at. Getting this wrong turns diodes into plain
 *       sources.</li>
 *   <li><b>Decisions</b>: what a torch, repeater or comparator should output for a given input.</li>
 * </ul>
 */
class PowerSolverTest {

    /** Minimal stand-in for the world, so the rules can be tested without a running game. */
    private static final class FakeNode implements PowerEnvironment.Node {

        private final ComponentType type;
        private int power;
        private Direction facing = Direction.NORTH;
        private ComparatorMode mode = ComparatorMode.COMPARE;
        private final Set<Direction> forcedOn = EnumSet.noneOf(Direction.class);
        private final Set<Direction> forcedOff = EnumSet.noneOf(Direction.class);

        FakeNode(ComponentType type, int power) {
            this.type = type;
            this.power = power;
        }

        FakeNode facing(Direction direction) {
            this.facing = direction;
            return this;
        }

        FakeNode mode(ComparatorMode value) {
            this.mode = value;
            return this;
        }

        FakeNode forced(Direction direction, boolean on) {
            (on ? forcedOn : forcedOff).add(direction);
            return this;
        }

        @Override
        public ComponentType type() {
            return type;
        }

        @Override
        public int power() {
            return power;
        }

        @Override
        public void setPower(int value) {
            this.power = value;
        }

        @Override
        public Direction facing() {
            return facing;
        }

        @Override
        public ComparatorMode mode() {
            return mode;
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

    private static final class FakeEnv implements PowerEnvironment {

        private final Map<Long, FakeNode> nodes = new HashMap<>();
        private int external;
        private final Map<Direction, Integer> externalPerSide = new HashMap<>();

        private static long key(int x, int y, int z) {
            return BlockPos.asLong(x, y, z);
        }

        FakeEnv node(int x, int y, int z, ComponentType type, int power) {
            return node(new BlockPos(x, y, z), type, power);
        }

        FakeEnv node(BlockPos pos, ComponentType type, int power) {
            nodes.put(pos.asLong(), new FakeNode(type, power));
            return this;
        }

        FakeNode at(int x, int y, int z) {
            return nodes.get(key(x, y, z));
        }

        FakeEnv outside(int signal) {
            this.external = signal;
            return this;
        }

        FakeEnv outside(Direction side, int signal) {
            externalPerSide.put(side, signal);
            return this;
        }

        @Override
        public Node nodeAt(int x, int y, int z) {
            return nodes.get(key(x, y, z));
        }

        @Override
        public int externalSignal() {
            return external;
        }

        @Override
        public int externalSignal(Direction direction) {
            return externalPerSide.getOrDefault(direction, external);
        }

        @Override
        public boolean isRedstoneConductor(int x, int y, int z) {
            // Coupling does not depend on the surrounding terrain, so this is unused by the rules.
            return false;
        }
    }

    private static final BlockPos HERE = new BlockPos(0, 0, 0);

    private static int target(FakeEnv env, int supply) {
        return PowerSolver.wireTarget(env, HERE, supply, env.nodeAt(0, 0, 0));
    }

    // ------------------------------------------------------------ propagation --

    @Test
    @DisplayName("dust passes its own supply through without attenuation")
    void supplyIsNotAttenuated() {
        assertEquals(12, target(new FakeEnv(), 12),
                "a lever next to the host should give its full strength");
    }

    @Test
    @DisplayName("one wire hop costs exactly one, a source hop costs nothing")
    void wireHopCostsOneButASourceDoesNot() {
        // The receiver is what charges the hop - vanilla's rule, and the one a superconductor bends - so
        // these cases put a wire at the origin rather than asking about an empty position.
        FakeEnv env = new FakeEnv();
        env.node(1, 0, 0, ComponentType.DUST, 15);
        env.node(HERE, ComponentType.DUST, 0);
        assertEquals(14, target(env, 0), "15 - 1 from the neighbouring wire");

        FakeEnv lever = new FakeEnv();
        lever.node(1, 0, 0, ComponentType.LEVER, 15);
        lever.node(HERE, ComponentType.DUST, 0);
        assertEquals(15, target(lever, 0),
                "a lever is a source, so it lights wire at full strength - exactly like vanilla");
    }

    /**
     * The whole point of the new material: a run of superconducting dust keeps the strength it was
     * given, while ordinary dust fades a step per block.
     *
     * <p>Solved the way the network does it - repeatedly taking each wire's target until nothing rises
     * any further - because the point is what reaches the far end of the run, not what one hop does.
     */
    @Test
    @DisplayName("superconducting dust carries its strength without loss")
    void superconductorLosesNothingPerHop() {
        FakeEnv run = run(ComponentType.SUPERCONDUCTOR, -4, 0);
        settle(run, -4, 0);
        for (int x = -4; x <= 0; x++) {
            assertEquals(15, run.at(x, 0, 0).power(),
                    "every block of the run holds the full strength, at x = " + x);
        }

        FakeEnv fading = run(ComponentType.DUST, -4, 0);
        settle(fading, -4, 0);
        assertEquals(11, fading.at(0, 0, 0).power(),
                "the same run in ordinary dust costs one per hop, which is what vanilla does");
    }

    /**
     * The difference is distance, not hops: ordinary dust is out of signal after fifteen blocks, while
     * superconducting dust is still at fifteen after sixty-four.
     *
     * <p>This is also what the network's fixpoint has to survive - a run longer than the fifteen blocks
     * an ordinary signal can cross needs more passes than a bound sized for dust would allow.
     */
    @Test
    @DisplayName("a long superconducting run is still at full strength at the far end")
    void longSuperconductingRunHolds() {
        FakeEnv run = run(ComponentType.SUPERCONDUCTOR, -64, 0);
        settle(run, -64, 0);
        assertEquals(15, run.at(0, 0, 0).power(), "sixty-four free hops cost nothing");

        FakeEnv fading = run(ComponentType.DUST, -64, 0);
        settle(fading, -64, 0);
        assertEquals(0, fading.at(0, 0, 0).power(),
                "while ordinary dust gave out long before: fifteen blocks is all a signal of fifteen crosses");
    }

    /** A run of wire from {@code fromX} to {@code toX} with its west end fed at fifteen. */
    private static FakeEnv run(ComponentType type, int fromX, int toX) {
        FakeEnv env = new FakeEnv();
        for (int x = fromX; x <= toX; x++) {
            env.node(x, 0, 0, type, 0);
        }
        // A source at the west end: standing in for the supply the network would read from the world.
        env.at(fromX, 0, 0).setPower(15);
        return env;
    }

    /**
     * Grows every wire in the run towards its target until nothing changes, as the solver does.
     *
     * <p>The bound is the run's length: a wire's value only rises, so a run can never need more passes
     * than it has blocks.
     */
    private static void settle(FakeEnv env, int fromX, int toX) {
        int length = toX - fromX + 1;
        for (int pass = 0; pass <= length; pass++) {
            boolean changed = false;
            for (int x = fromX; x <= toX; x++) {
                FakeNode node = env.at(x, 0, 0);
                int target = PowerSolver.wireTarget(env, new BlockPos(x, 0, 0), 0, node);
                if (target > node.power()) {
                    node.setPower(target);
                    changed = true;
                }
            }
            if (!changed) {
                return;
            }
        }
        throw new AssertionError("the run did not settle within " + length + " passes");
    }

    /** Entering the superconductor from ordinary wire must not cost anything either. */
    @Test
    @DisplayName("a hop into a superconductor is free even from ordinary dust")
    void enteringASuperconductorIsFree() {
        FakeEnv env = new FakeEnv();
        env.node(1, 0, 0, ComponentType.DUST, 15);
        env.node(HERE, ComponentType.SUPERCONDUCTOR, 0);
        assertEquals(15, target(env, 0), "the superconducting side of the hop charges nothing");

        // ...while the ordinary dust on the far side of it still charges its own hop, as it always does.
        FakeEnv back = new FakeEnv();
        back.node(1, 0, 0, ComponentType.SUPERCONDUCTOR, 15);
        back.node(HERE, ComponentType.DUST, 0);
        assertEquals(14, target(back, 0), "ordinary dust fades a wire feeding it by one");
    }

    @Test
    @DisplayName("a repeater's output is a source for the wire it points at")
    void repeaterOutputIsASource() {
        FakeEnv env = new FakeEnv();
        // The repeater sits east of us and points this way, outputting 15.
        env.node(1, 0, 0, ComponentType.REPEATER, 15).at(1, 0, 0).facing(Direction.EAST);
        assertEquals(15, target(env, 0), "diodes emit at full strength");
    }

    @Test
    @DisplayName("a repeater that points away does not reach us")
    void repeaterPointingAwayDoesNotReach() {
        FakeEnv env = new FakeEnv();
        // FACING is the input side, so a repeater with FACING = WEST outputs towards the east.
        env.node(1, 0, 0, ComponentType.REPEATER, 15).at(1, 0, 0).facing(Direction.WEST);
        assertEquals(0, target(env, 0), "its output goes out the far side, away from us");
    }

    @Test
    @DisplayName("vertical neighbours couple the same way as horizontal ones")
    void verticalNeighbourCouples() {
        FakeEnv above = new FakeEnv();
        above.node(0, 1, 0, ComponentType.DUST, 15);
        above.node(HERE, ComponentType.DUST, 0);
        assertEquals(14, target(above, 0), "inner redstone in the block above is a direct neighbour");

        FakeEnv below = new FakeEnv();
        below.node(0, -1, 0, ComponentType.DUST, 15);
        below.node(HERE, ComponentType.DUST, 0);
        assertEquals(14, target(below, 0), "and so is the block below");
    }

    @Test
    @DisplayName("the strongest neighbour wins, and values stay inside 0-15")
    void strongestNeighbourWins() {
        FakeEnv env = new FakeEnv();
        env.node(1, 0, 0, ComponentType.DUST, 4);
        env.node(-1, 0, 0, ComponentType.DUST, 9);
        env.node(0, 0, 1, ComponentType.DUST, 3);
        env.node(HERE, ComponentType.DUST, 0);
        assertEquals(8, target(env, 0), "the strongest neighbouring wire (9) minus one hop");

        assertEquals(15, PowerSolver.clamp(99), "corrupt saved data is clamped, not propagated");
        assertEquals(0, PowerSolver.clamp(-5));
    }

    @Test
    @DisplayName("an isolated component holds nothing")
    void isolatedComponentStaysUnpowered() {
        assertEquals(0, target(new FakeEnv(), 0));
    }

    // ----------------------------------------------------------- what reaches --

    @Test
    @DisplayName("dust reads and writes every side")
    void dustTalksToEverySide() {
        FakeNode dust = new FakeNode(ComponentType.DUST, 0);
        for (Direction direction : Direction.values()) {
            assertTrue(PowerSolver.readsFrom(dust, direction), "dust reads " + direction);
            assertTrue(PowerSolver.emitsToward(dust, direction), "dust emits " + direction);
        }
    }

    @Test
    @DisplayName("a torch reads only where it hangs and does not signal back into it")
    void torchIsDirectionalLikeVanilla() {
        FakeNode torch = new FakeNode(ComponentType.TORCH, 15).facing(Direction.UP);
        assertTrue(PowerSolver.readsFrom(torch, Direction.UP), "it reads the side it hangs on");
        assertFalse(PowerSolver.emitsToward(torch, Direction.UP),
                "and does not signal back into it, which is what stops it latching itself on");
        for (Direction direction : Direction.values()) {
            if (direction != Direction.UP) {
                assertTrue(PowerSolver.emitsToward(torch, direction),
                        "a torch lights the other five sides, " + direction + " included");
                assertFalse(PowerSolver.readsFrom(torch, direction),
                        "and is deaf to everything except its attachment");
            }
        }
    }

    @Test
    @DisplayName("a repeater reads its input side and emits on the opposite one only")
    void repeaterIsOneWay() {
        FakeNode repeater = new FakeNode(ComponentType.REPEATER, 0).facing(Direction.WEST);
        assertTrue(PowerSolver.readsFrom(repeater, Direction.WEST));
        assertFalse(PowerSolver.readsFrom(repeater, Direction.EAST));
        assertTrue(PowerSolver.emitsToward(repeater, Direction.EAST));
        assertFalse(PowerSolver.emitsToward(repeater, Direction.WEST));
        assertFalse(PowerSolver.emitsToward(repeater, Direction.UP));
    }

    @Test
    @DisplayName("a lever emits everywhere and is never driven")
    void leverEmitsEverywhere() {
        FakeNode lever = new FakeNode(ComponentType.LEVER, 15);
        for (Direction direction : Direction.values()) {
            assertTrue(PowerSolver.emitsToward(lever, direction));
        }
        assertFalse(ComponentType.LEVER.isDriven(), "a lever only moves when a player moves it");
    }

    @Test
    @DisplayName("the wrench overrides both rules, in both directions")
    void wrenchOverridesWin() {
        FakeNode repeater = new FakeNode(ComponentType.REPEATER, 15).facing(Direction.WEST);
        repeater.forced(Direction.UP, true);
        assertTrue(PowerSolver.emitsToward(repeater, Direction.UP), "forced on adds an output side");
        assertTrue(PowerSolver.readsFrom(repeater, Direction.UP), "and an input side");

        repeater.forced(Direction.EAST, false);
        assertFalse(PowerSolver.emitsToward(repeater, Direction.EAST),
                "forced off cuts the side the diode would have used");
        assertFalse(PowerSolver.readsFrom(repeater, Direction.EAST));
    }

    @Test
    @DisplayName("a forced-off dust does not couple that way")
    void forcedOffCutsDust() {
        FakeEnv env = new FakeEnv();
        env.node(1, 0, 0, ComponentType.DUST, 15);
        FakeNode self = env.node(HERE, ComponentType.DUST, 0).at(0, 0, 0);
        assertEquals(14, target(env, 0), "precondition: normally it couples");

        self.forced(Direction.EAST, false);
        assertEquals(0, PowerSolver.wireTarget(env, HERE, 0, self),
                "with the side cut, nothing arrives from it");
    }

    // -------------------------------------------------------- what a device does --

    @Test
    @DisplayName("a torch is lit until its attachment is powered")
    void torchInverts() {
        FakeEnv env = new FakeEnv();
        FakeNode torch = env.node(HERE, ComponentType.TORCH, 15).at(0, 0, 0);
        torch.facing(Direction.NORTH);

        assertEquals(15, PowerSolver.desiredOutput(env, HERE, torch), "nothing on its side: lit");

        env.node(0, 0, -1, ComponentType.LEVER, 15);
        assertEquals(0, PowerSolver.desiredOutput(env, HERE, torch),
                "a lever against its attachment puts it out");
    }

    @Test
    @DisplayName("a torch ignores power arriving on any other side")
    void torchIgnoresOtherSides() {
        FakeEnv env = new FakeEnv();
        FakeNode torch = env.node(HERE, ComponentType.TORCH, 15).at(0, 0, 0);
        torch.facing(Direction.NORTH);
        env.node(1, 0, 0, ComponentType.LEVER, 15);

        assertEquals(15, PowerSolver.desiredOutput(env, HERE, torch),
                "a lever behind it is not its attachment, so the torch stays lit");
    }

    @Test
    @DisplayName("a repeater amplifies any input to full strength")
    void repeaterAmplifies() {
        FakeEnv env = new FakeEnv();
        FakeNode repeater = env.node(HERE, ComponentType.REPEATER, 0).at(0, 0, 0);
        repeater.facing(Direction.NORTH);

        assertEquals(0, PowerSolver.desiredOutput(env, HERE, repeater), "no input, no output");

        env.node(0, 0, -1, ComponentType.DUST, 2);
        assertEquals(15, PowerSolver.desiredOutput(env, HERE, repeater),
                "an input of 2 comes out at 15: a diode reads a wire beside it at its full strength");
    }

    @Test
    @DisplayName("a comparator compares or subtracts, like vanilla")
    void comparatorModes() {
        FakeEnv env = new FakeEnv();
        FakeNode comparator = env.node(HERE, ComponentType.COMPARATOR, 0).at(0, 0, 0);
        comparator.facing(Direction.NORTH);
        // Back input of 8 (a wire of 8 behind it), side input of 6 (a wire of 6 to the east). A diode
        // reads wires at full strength in vanilla, and so does this one: the hop cost is the receiving
        // wire's, and a comparator is not a wire.
        env.node(0, 0, -1, ComponentType.DUST, 8);
        env.node(1, 0, 0, ComponentType.DUST, 6);

        assertEquals(8, PowerSolver.desiredOutput(env, HERE, comparator),
                "compare: the back signal passes through while it is at least the sides");

        comparator.mode(ComparatorMode.SUBTRACT);
        assertEquals(2, PowerSolver.desiredOutput(env, HERE, comparator),
                "subtract: back minus the strongest side");
    }

    @Test
    @DisplayName("a comparator whose sides beat its back goes silent in compare mode")
    void comparatorComparesAgainstSides() {
        FakeEnv env = new FakeEnv();
        FakeNode comparator = env.node(HERE, ComponentType.COMPARATOR, 0).at(0, 0, 0);
        comparator.facing(Direction.NORTH);
        env.node(0, 0, -1, ComponentType.DUST, 6);
        env.node(1, 0, 0, ComponentType.DUST, 12);

        assertEquals(0, PowerSolver.desiredOutput(env, HERE, comparator),
                "back 6 is less than side 12, so compare outputs nothing");
    }

    @Test
    @DisplayName("the vanilla world on the input side feeds a device, other sides do not")
    void worldSignalOnTheInputSideFeedsADevice() {
        FakeEnv env = new FakeEnv();
        FakeNode repeater = env.node(HERE, ComponentType.REPEATER, 0).at(0, 0, 0);
        repeater.facing(Direction.NORTH);

        env.outside(Direction.NORTH, 15);
        assertEquals(15, PowerSolver.desiredOutput(env, HERE, repeater),
                "a lever against the host block on the input side drives the repeater");

        FakeEnv wrongSide = new FakeEnv();
        FakeNode second = wrongSide.node(HERE, ComponentType.REPEATER, 0).at(0, 0, 0);
        second.facing(Direction.NORTH);
        wrongSide.outside(Direction.SOUTH, 15);
        assertEquals(0, PowerSolver.desiredOutput(wrongSide, HERE, second),
                "the same lever behind it does nothing: diodes are directional");
    }

    @Test
    @DisplayName("only a diode charges the block in front of it, everything else is weak power")
    void strongPowerFollowsVanilla() {
        // A lever inside a block must not charge the stone beside it: if it did, the stone would carry
        // that power onwards and light things the lever was never pointed at.
        assertFalse(PowerSolver.directSignalToward(ComponentType.LEVER, Direction.NORTH, Direction.NORTH, false));
        assertFalse(PowerSolver.directSignalToward(ComponentType.BUTTON, Direction.NORTH, Direction.SOUTH, false));
        assertFalse(PowerSolver.directSignalToward(ComponentType.DUST, Direction.NORTH, Direction.NORTH, false));
        assertFalse(PowerSolver.directSignalToward(ComponentType.TORCH, Direction.NORTH, Direction.NORTH, false));

        // The direction is where the signal travels, the same frame emitsToward uses: a repeater with
        // FACING = NORTH reads from the north, so it charges the block to its south. (Vanilla's own
        // getDirectSignal is handed the opposite direction; the mixin converts before calling in.)
        assertTrue(PowerSolver.directSignalToward(ComponentType.REPEATER, Direction.NORTH, Direction.SOUTH, false));
        assertFalse(PowerSolver.directSignalToward(ComponentType.REPEATER, Direction.NORTH, Direction.NORTH, false));
        assertFalse(PowerSolver.directSignalToward(ComponentType.REPEATER, Direction.NORTH, Direction.UP, false));
        assertTrue(PowerSolver.directSignalToward(ComponentType.COMPARATOR, Direction.NORTH, Direction.SOUTH, false));

        // And the wrench can still cut it.
        assertFalse(PowerSolver.directSignalToward(ComponentType.REPEATER, Direction.NORTH, Direction.SOUTH, true));
    }

    /**
     * The same rule stated where it is easy to get backwards: the two queries must agree about which
     * side a diode drives, because vanilla's {@code getDirectSignal} receives the opposite direction
     * from {@code getSignal} and a mistake there is invisible to any weak-power test.
     */
    @Test
    @DisplayName("weak and strong emission agree on which side a diode drives")
    void diodeDrivesOneSideForBothQueries() {
        Direction facing = Direction.WEST;
        Direction output = facing.getOpposite();
        Direction input = facing;

        for (ComponentType type : new ComponentType[] { ComponentType.REPEATER, ComponentType.COMPARATOR }) {
            assertTrue(PowerSolver.emitsToward(type, facing, output, false, false, false),
                    type + " emits weakly towards the block in front");
            assertTrue(PowerSolver.directSignalToward(type, facing, output, false),
                    type + " also charges that block, which is what carries the signal onwards");
            assertFalse(PowerSolver.emitsToward(type, facing, input, false, false, false),
                    type + " must not emit towards its own input");
            assertFalse(PowerSolver.directSignalToward(type, facing, input, false),
                    type + " must not charge the block behind it either");
            assertFalse(PowerSolver.emitsToward(type, facing, Direction.UP, false, false, false),
                    "nor sideways");
        }
    }

    // ---------------------------------------------------------------- delays --

    @Test
    @DisplayName("delays match vanilla: torch 2 ticks, repeater 2 per setting, comparator 2")
    void delaysMatchVanilla() {
        assertEquals(2, ComponentType.TORCH.delayTicks(1));
        assertEquals(2, ComponentType.COMPARATOR.delayTicks(1));
        for (int setting = 1; setting <= 4; setting++) {
            assertEquals(2 * setting, ComponentType.REPEATER.delayTicks(setting),
                    "repeater setting " + setting + " is " + setting + " redstone ticks");
        }
        assertEquals(0, ComponentType.DUST.delayTicks(1), "dust has no delay");
        assertEquals(0, ComponentType.LEVER.delayTicks(1), "neither does a lever");
    }

    @Test
    @DisplayName("a freshly placed torch is lit, everything else starts silent")
    void initialOutputs() {
        assertEquals(15, ComponentType.TORCH.initialPower());
        assertEquals(0, ComponentType.DUST.initialPower());
        assertEquals(0, ComponentType.REPEATER.initialPower());
        assertEquals(0, ComponentType.LEVER.initialPower());
    }
}

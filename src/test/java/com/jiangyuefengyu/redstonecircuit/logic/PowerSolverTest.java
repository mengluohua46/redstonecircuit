package com.jiangyuefengyu.redstonecircuit.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies the inner-redstone power algorithm.
 *
 * <p>The rule is {@code value(x) = max(supply(x), max over neighbours (value(n) - 1))}, where
 * {@code supply} is what the component receives from outside the wire network. These tests pin that
 * down, and in particular the property that a chain resolves to {@code supply - distance} regardless
 * of evaluation order - the absence of which once made pairs of components drain each other to zero.
 */
class PowerSolverTest {

    /** Minimal stand-in for the world, so the algorithm can be tested without a running game. */
    private static final class FakeEnv implements PowerEnvironment {

        private final Map<Long, int[]> wires = new HashMap<>();
        private int outsideSignal;

        private static long key(int x, int y, int z) {
            return BlockPos.asLong(x, y, z);
        }

        FakeEnv wire(int x, int y, int z, int power) {
            wires.put(key(x, y, z), new int[] { power });
            return this;
        }

        FakeEnv outside(int signal) {
            this.outsideSignal = signal;
            return this;
        }

        @Override
        public int bestNeighborSignal() {
            return outsideSignal;
        }

        @Override
        public WireNode dustAt(int x, int y, int z) {
            int[] cell = wires.get(key(x, y, z));
            if (cell == null) {
                return null;
            }
            return new WireNode() {
                @Override
                public int power() {
                    return cell[0];
                }

                @Override
                public void setPower(int power) {
                    cell[0] = power;
                }
            };
        }

        @Override
        public boolean isRedstoneConductor(int x, int y, int z) {
            // Coupling does not depend on the surrounding terrain, so this is unused by the solver.
            return false;
        }
    }

    @Test
    @DisplayName("supply passes through without attenuation")
    void supplyIsNotAttenuated() {
        FakeEnv env = new FakeEnv();
        assertEquals(12, PowerSolver.targetStrength(env, new BlockPos(0, 0, 0), 12),
                "a lever next to the host should give its full strength");
    }

    @Test
    @DisplayName("a single wire hop costs exactly one")
    void wireHopCostsOne() {
        FakeEnv env = new FakeEnv().wire(1, 0, 0, 15);
        assertEquals(14, PowerSolver.targetStrength(env, new BlockPos(0, 0, 0), 0),
                "15 - 1 from the neighbouring wire, with no supply of its own");
    }

    @Test
    @DisplayName("the block above couples just like a horizontal neighbour")
    void verticalNeighbourCouples() {
        FakeEnv above = new FakeEnv().wire(0, 1, 0, 15);
        assertEquals(14, PowerSolver.targetStrength(above, new BlockPos(0, 0, 0), 0),
                "inner redstone in the block above is a direct neighbour");

        FakeEnv below = new FakeEnv().wire(0, -1, 0, 15);
        assertEquals(14, PowerSolver.targetStrength(below, new BlockPos(0, 0, 0), 0),
                "inner redstone in the block below is a direct neighbour too");
    }

    @Test
    @DisplayName("a chain resolves to supply minus distance at every node")
    void chainResolvesFromSupply() {
        // Source at x=3 with 15; the settled values at x=2 and x=1 must not be mistaken for supplies.
        FakeEnv env = new FakeEnv();
        env.wire(3, 0, 0, 15);
        env.wire(2, 0, 0, 14);
        env.wire(1, 0, 0, 13);

        assertEquals(14, PowerSolver.targetStrength(env, new BlockPos(2, 0, 0), 0), "one hop from 15");
        assertEquals(13, PowerSolver.targetStrength(env, new BlockPos(1, 0, 0), 0), "two hops from 15");
        assertEquals(12, PowerSolver.targetStrength(env, new BlockPos(0, 0, 0), 0), "three hops from 15");
    }

    @Test
    @DisplayName("a component with no supply keeps its neighbour's value at arm's length")
    void noSupplyMeansNeighbourMinusOneOnly() {
        // Regression test for the decay bug: a neighbour holding a value is worth value-1, and that
        // is all. It is not a supply that can lift this node above supply-1.
        FakeEnv env = new FakeEnv().wire(1, 0, 0, 15);
        assertEquals(14, PowerSolver.targetStrength(env, new BlockPos(0, 0, 0), 0));

        // And with no neighbour at all, no supply means nothing.
        assertEquals(0, PowerSolver.targetStrength(new FakeEnv(), new BlockPos(0, 0, 0), 0));
    }

    @Test
    @DisplayName("a stronger neighbour wins")
    void strongestNeighbourWins() {
        FakeEnv env = new FakeEnv();
        env.wire(1, 0, 0, 4);
        env.wire(-1, 0, 0, 9);
        env.wire(0, 0, 1, 3);
        env.wire(0, 0, -1, 2);

        assertEquals(8, PowerSolver.targetStrength(env, new BlockPos(0, 0, 0), 0),
                "the strongest neighbouring wire (9) minus one hop");
    }

    @Test
    @DisplayName("own supply beats a weaker neighbour")
    void supplyWinsOverWeakerNeighbour() {
        FakeEnv env = new FakeEnv().wire(1, 0, 0, 6);
        assertEquals(12, PowerSolver.targetStrength(env, new BlockPos(0, 0, 0), 12),
                "a stronger supply is not dragged down by a weak neighbour");
    }

    @Test
    @DisplayName("a supply of 15 is not reduced by neighbours")
    void fullSupplyIsNotReduced() {
        FakeEnv env = new FakeEnv().wire(1, 0, 0, 15);
        assertEquals(15, PowerSolver.targetStrength(env, new BlockPos(0, 0, 0), 15));
    }

    @Test
    @DisplayName("an isolated wire with no supply holds nothing")
    void isolatedWireStaysUnpowered() {
        assertEquals(0, PowerSolver.targetStrength(new FakeEnv(), new BlockPos(0, 0, 0), 0));
    }

    @Test
    @DisplayName("power is clamped into 0-15")
    void clamping() {
        assertEquals(0, PowerSolver.clamp(-5));
        assertEquals(0, PowerSolver.clamp(0));
        assertEquals(15, PowerSolver.clamp(15));
        assertEquals(15, PowerSolver.clamp(99));

        // Out-of-range stored data is read as 15, so the target is 15 minus the one wire hop.
        FakeEnv env = new FakeEnv().wire(1, 0, 0, 999);
        assertEquals(14, PowerSolver.targetStrength(env, new BlockPos(0, 0, 0), 0),
                "999 is clamped to 15, then attenuated by one wire hop");
    }
}

package com.jiangyuefengyu.redstonecircuit.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies the inner-redstone power algorithm against the signal behaviour of vanilla redstone wire.
 *
 * <p>Vanilla's rule, taken from {@code RedStoneWireBlock#calculateTargetStrength}, is: outside power
 * passes through unattenuated while every wire-to-wire hop costs exactly one. These tests pin that
 * down so a future refactor cannot silently change the signal maths.
 *
 * <p>Coupling is checked in all six directions here, which is where this differs from vanilla - see
 * the class comment on {@link PowerSolver} for why.
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
            // Coupling no longer depends on the surrounding terrain, so this is unused by the
            // solver. Implemented as "false" to keep the fake honest about that.
            return false;
        }
    }

    @Test
    @DisplayName("outside power passes through without attenuation")
    void outsidePowerIsNotAttenuated() {
        FakeEnv env = new FakeEnv().outside(12);
        assertEquals(12, PowerSolver.targetStrength(env, new BlockPos(0, 0, 0)),
                "a lever next to the block should give its full strength");
    }

    @Test
    @DisplayName("a single wire hop costs exactly one")
    void wireHopCostsOne() {
        FakeEnv env = new FakeEnv().wire(1, 0, 0, 15);
        assertEquals(14, PowerSolver.targetStrength(env, new BlockPos(0, 0, 0)),
                "15 - 1 from the neighbouring wire, with no outside power");
    }

    @Test
    @DisplayName("the block above couples just like a horizontal neighbour")
    void verticalNeighbourCouples() {
        FakeEnv env = new FakeEnv().wire(0, 1, 0, 15);
        assertEquals(14, PowerSolver.targetStrength(env, new BlockPos(0, 0, 0)),
                "inner redstone in the block above is a direct neighbour");

        FakeEnv below = new FakeEnv().wire(0, -1, 0, 15);
        assertEquals(14, PowerSolver.targetStrength(below, new BlockPos(0, 0, 0)),
                "inner redstone in the block below is a direct neighbour too");
    }

    @Test
    @DisplayName("wire power decays by one per hop along a straight line")
    void linearDecay() {
        // Source wire (power 15) at x=3, decaying away from it towards x=0.
        FakeEnv env = new FakeEnv();
        env.wire(3, 0, 0, 15);
        env.wire(2, 0, 0, 14);
        env.wire(1, 0, 0, 13);

        assertEquals(12, PowerSolver.targetStrength(env, new BlockPos(0, 0, 0)), "three hops from 15");
        assertEquals(13, PowerSolver.targetStrength(env, new BlockPos(1, 0, 0)), "two hops from 15");
        assertEquals(14, PowerSolver.targetStrength(env, new BlockPos(2, 0, 0)), "one hop from 15");
    }

    @Test
    @DisplayName("a stronger neighbour wins")
    void strongestNeighbourWins() {
        FakeEnv env = new FakeEnv();
        env.wire(1, 0, 0, 4);
        env.wire(-1, 0, 0, 9);
        env.wire(0, 0, 1, 3);
        env.wire(0, 0, -1, 2);

        assertEquals(8, PowerSolver.targetStrength(env, new BlockPos(0, 0, 0)),
                "the strongest neighbouring wire (9) minus one hop");
    }

    @Test
    @DisplayName("outside power beats a weaker wire but caps at 15")
    void outsidePowerWins() {
        FakeEnv env = new FakeEnv().wire(1, 0, 0, 6).outside(15);
        assertEquals(15, PowerSolver.targetStrength(env, new BlockPos(0, 0, 0)));
    }

    @Test
    @DisplayName("an isolated wire with no source holds nothing")
    void isolatedWireStaysUnpowered() {
        FakeEnv env = new FakeEnv();
        assertEquals(0, PowerSolver.targetStrength(env, new BlockPos(0, 0, 0)),
                "vanilla computes max(0, 0 - 1), which is 0, not -1");
    }

    @Test
    @DisplayName("a full-strength outside source is not also charged through wires")
    void outsidePowerSkipsWireScan() {
        // With outside == 15 the wire scan is skipped entirely, matching vanilla's `if (i < 15)`.
        FakeEnv env = new FakeEnv().outside(15).wire(1, 0, 0, 15);
        assertEquals(15, PowerSolver.targetStrength(env, new BlockPos(0, 0, 0)));
    }

    @Test
    @DisplayName("power is clamped into 0-15")
    void clamping() {
        assertEquals(0, PowerSolver.clamp(-5));
        assertEquals(0, PowerSolver.clamp(0));
        assertEquals(15, PowerSolver.clamp(15));
        assertEquals(15, PowerSolver.clamp(99));

        // Out-of-range stored data is read as 15, so the target is 15 minus the one wire hop.
        // The point here is that 999 must not leak through as a raw value.
        FakeEnv env = new FakeEnv().wire(1, 0, 0, 999);
        assertEquals(14, PowerSolver.targetStrength(env, new BlockPos(0, 0, 0)),
                "999 is clamped to 15, then attenuated by one wire hop");
    }
}

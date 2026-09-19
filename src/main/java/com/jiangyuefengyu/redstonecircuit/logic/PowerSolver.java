package com.jiangyuefengyu.redstonecircuit.logic;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * The power algorithm for inner redstone dust.
 *
 * <p>The rule matches vanilla redstone wire: outside power passes through unattenuated, while every
 * wire-to-wire hop costs exactly one. That is the whole of vanilla's
 * {@code RedStoneWireBlock#calculateTargetStrength} reduced to its signal-carrying behaviour.
 *
 * <pre>
 * private int calculateTargetStrength(Level level, BlockPos pos) {
 *     this.shouldSignal = false;
 *     int i = level.getBestNeighborSignal(pos);   // outside power, not attenuated
 *     this.shouldSignal = true;
 *     ...
 *     return Math.max(i, j - 1);                  // wire hops cost 1
 * }
 * </pre>
 *
 * <h2>Where this deliberately differs from vanilla</h2>
 * Vanilla only couples wires through the four <em>horizontal</em> directions, and reaches a
 * neighbouring wire vertically via "climb" (a wire on top of an opaque neighbour) and "step down"
 * (a wire under a transparent neighbour). Those rules exist because a vanilla wire is a block
 * sitting <em>on</em> the terrain, so it must be told what it may step onto.
 *
 * <p>Inner redstone is stored <em>inside</em> the host block, so there is no terrain between two
 * components to reason about: a component in the block above is simply adjacent. This solver
 * therefore couples all six directions directly. Copying vanilla's climb rule here was an actual
 * bug - the "wire above the opaque neighbour" position is a plain stone block in this mod, so
 * vertical neighbours never coupled at all.
 */
public final class PowerSolver {

    /** Maximum vanilla redstone signal strength. */
    public static final int MAX_POWER = 15;

    private PowerSolver() {
    }

    /**
     * Computes the signal strength the dust at {@code pos} should hold, given its surroundings.
     *
     * @param env the world view (adjacent dust, external signals, conductor checks)
     * @param pos the dust position
     * @return 0-15
     */
    public static int targetStrength(PowerEnvironment env, BlockPos pos) {
        int outside = clamp(env.bestNeighborSignal());

        int fromWires = 0;
        if (outside < MAX_POWER) {
            // Direct 6-way coupling: inner redstone lives *inside* the host block, so a component
            // in the block above/below is the direct analogue of a neighbouring wire.
            for (Direction direction : Direction.values()) {
                fromWires = Math.max(fromWires, wireSignalAt(env, pos.relative(direction)));
            }
        }

        // Outside power passes through unchanged; every wire-to-wire hop costs exactly one.
        return Math.max(outside, fromWires - 1);
    }

    /** Mirrors vanilla {@code getWireSignal}: only dust carries a wire signal, everything else is 0. */
    private static int wireSignalAt(PowerEnvironment env, BlockPos pos) {
        PowerEnvironment.WireNode wire = env.dustAt(pos.getX(), pos.getY(), pos.getZ());
        return wire == null ? 0 : clamp(wire.power());
    }

    /** Vanilla keeps signals in 0-15; clamp defensively so corrupt data cannot break propagation. */
    public static int clamp(int power) {
        if (power < 0) {
            return 0;
        }
        return Math.min(power, MAX_POWER);
    }
}

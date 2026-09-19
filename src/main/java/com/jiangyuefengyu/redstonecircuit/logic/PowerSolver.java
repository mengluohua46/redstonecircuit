package com.jiangyuefengyu.redstonecircuit.logic;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * The power algorithm for inner redstone dust.
 *
 * <p>A component's value is its own supply (the signal it receives from the vanilla world, or a
 * value injected by the debug command) or one less than the strongest neighbour's own supply:
 *
 * <pre>
 *   value(x) = max(source(x), max over neighbours n of (source(n) - distance(x, n)))
 * </pre>
 *
 * <p>Note what that does <b>not</b> say: the neighbours' <em>settled values</em> are not treated as
 * supplies. They are also computed from {@code source}, so a chain resolves to
 * {@code source - distance} no matter what order the network is evaluated in. Computing from
 * settled values instead makes a pair of components feed off each other and decay towards zero,
 * which is exactly the bug this rule exists to prevent.
 *
 * <h2>Relationship to vanilla</h2>
 * Vanilla's {@code RedStoneWireBlock#calculateTargetStrength} is the same rule stated
 * procedurally: outside power ({@code getBestNeighborSignal}) passes through unattenuated, and each
 * wire hop costs one. Vanilla gets away with reading neighbour <em>values</em> only because it
 * updates one wire at a time and immediately notifies, so a wire never re-reads a value that was
 * derived from itself. This implementation relaxes a whole component at once, so it has to state the
 * rule in terms of sources to stay order-independent.
 *
 * <p>Another difference: vanilla couples wires only horizontally and reaches them vertically through
 * terrain-aware "climb" and "step down" rules. Inner redstone is stored <em>inside</em> its host
 * block, so there is no terrain between two components: every adjacent block is a direct neighbour.
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
    /**
     * The strongest supply available to the dust at {@code pos}, before attenuation.
     *
     * <p>This is {@code max(source(pos), max over neighbours n of (value(n) - 1))}, where a
     * neighbour's {@code value} is itself derived from <em>its</em> supply. Because a neighbour's
     * value may not have been settled yet when this runs, the caller repeats the calculation until
     * the component stops changing; see {@code InnerRedstoneNetwork#relax}. The result is
     * order-independent because every node ends up at {@code supply - distance}.
     *
     * @param env     the world view (adjacent dust, external signals)
     * @param pos     the dust position
     * @param supply  the power this component receives from outside the wire network - the signal of
     *                a lever, torch or redstone block touching the host block, or 0
     * @return 0-15
     */
    public static int targetStrength(PowerEnvironment env, BlockPos pos, int supply) {
        int best = clamp(supply);

        // Direct 6-way coupling: inner redstone lives *inside* the host block, so a component in the
        // block above/below is the direct analogue of a neighbouring wire.
        for (Direction direction : Direction.values()) {
            best = Math.max(best, wireSignalAt(env, pos.relative(direction)) - 1);
        }

        return Math.max(0, best);
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

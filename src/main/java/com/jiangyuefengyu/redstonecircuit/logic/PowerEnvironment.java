package com.jiangyuefengyu.redstonecircuit.logic;

import net.minecraft.core.Direction;

/**
 * What {@link PowerSolver} needs to know about the world.
 *
 * <p>Kept as a narrow interface (rather than taking a {@code Level} directly) so the power
 * algorithm can be unit-tested without bootstrapping Minecraft's registries.
 */
public interface PowerEnvironment {

    /**
     * Strongest signal any neighbouring block pushes into the wire at the queried position.
     *
     * <p>This mirrors vanilla's {@code Level#getBestNeighborSignal}: the maximum of
     * {@code BlockState#getSignal(level, pos, oppositeDirection)} over all six neighbours.
     */
    int bestNeighborSignal();

    /**
     * The inner redstone dust at {@code pos}, or {@code null} when there is none.
     *
     * <p>Only dust participates in wire-to-wire propagation, matching vanilla where
     * {@code getWireSignal} returns 0 for anything that is not redstone wire.
     */
    WireNode dustAt(int x, int y, int z);

    /**
     * Whether the block at {@code (x, y, z)} is a redstone conductor (opaque full block).
     *
     * <p>Vanilla uses this to decide whether a wire may step up onto, or down off, a neighbour.
     */
    boolean isRedstoneConductor(int x, int y, int z);

    /** Read/write access to a neighbouring wire's stored power. */
    interface WireNode {
        int power();

        void setPower(int power);
    }

    /**
     * The four horizontal directions vanilla iterates for wire-to-wire coupling.
     *
     * <p>Vanilla only couples wires horizontally; vertical movement happens through the
     * climb/step-down rules instead. Kept in a class rather than on this interface so it is a
     * compile-time constant instead of an interface field.
     */
    final class Directions {
        public static final Direction[] HORIZONTAL = {
                Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST
        };

        private Directions() {
        }
    }
}

package com.jiangyuefengyu.redstonecircuit.logic;

import org.jetbrains.annotations.Nullable;

import com.jiangyuefengyu.redstonecircuit.data.ComparatorMode;
import com.jiangyuefengyu.redstonecircuit.data.ComponentType;

import net.minecraft.core.Direction;

/**
 * What {@link PowerSolver} needs to know about the world.
 *
 * <p>Kept as a narrow interface (rather than taking a {@code Level} directly) so the power and
 * component rules can be unit-tested without bootstrapping Minecraft's registries.
 */
public interface PowerEnvironment {

    /** The inner component at a position, or {@code null} when that block holds none. */
    @Nullable
    Node nodeAt(int x, int y, int z);

    /**
     * Strongest signal any neighbouring block pushes into the host at the queried position.
     *
     * <p>Mirrors vanilla's {@code Level#getBestNeighborSignal}. This is the supply a piece of inner
     * dust draws from the world around its host block, and it is deliberately omnidirectional: dust
     * has no input side.
     */
    int externalSignal();

    /**
     * Signal the vanilla world pushes into the host at the queried position from one specific side.
     *
     * <p>This is what gives an inner repeater, comparator or torch its direction: a lever or a piece
     * of vanilla wire touching the host block on the component's input side feeds it, while one on
     * any other side does not.
     */
    int externalSignal(Direction direction);

    /** Whether the block at {@code (x, y, z)} is a redstone conductor (opaque full block). */
    boolean isRedstoneConductor(int x, int y, int z);

    /** Read/write access to one inner component, as the solver sees it. */
    interface Node {
        ComponentType type();

        /** The strength this component currently puts out, 0-15. */
        int power();

        void setPower(int power);

        /** Input side for a driven component; for a torch, the side it is attached to. */
        Direction facing();

        /** Comparator output mode. */
        ComparatorMode mode();

        /** True when this direction was locked by the wrench. */
        boolean open(Direction direction);

        /** True when this direction was cut by hand, as opposed to being left out of a lock. */
        boolean explicitlyCut(Direction direction);

        /**
         * True when the wrench has locked at least one direction of this component.
         *
         * <p>See {@code Slot#isRoutingLocked}: a locked component's routing is explicit, so the rules
         * treat every side that was not locked as cut.
         */
        boolean routingLocked();
    }
}

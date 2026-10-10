package com.jiangyuefengyu.redstonecircuit.logic;

import org.jetbrains.annotations.Nullable;

import com.jiangyuefengyu.redstonecircuit.data.ComparatorMode;
import com.jiangyuefengyu.redstonecircuit.data.ComponentType;

import net.minecraft.core.BlockPos;
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
     * Strongest signal any neighbouring block pushes into the host at the queried position, mirroring vanilla's
     * {@code Level#getBestNeighborSignal} - the supply a piece of inner dust draws from the world around its
     * host block. Omnidirectional, because dust has no input side.
     */
    int externalSignal();

    /**
     * Signal the vanilla world pushes into the host at the queried position from one specific side.
     *
     * <p>This is what gives an inner repeater, comparator or torch its direction: only a lever or a piece of
     * vanilla wire on the component's input side feeds it, one on any other side does not.
     */
    int externalSignal(Direction direction);

    /**
     * The comparator output the block on that side offers, 0-15; 0 for every block with none.
     *
     * <p>Vanilla reads a comparator's back input in two halves: the block's own analogue output
     * ({@code getAnalogOutputSignal}) and then the redstone signal it pushes. Only a comparator asks this - a
     * repeater, a torch and a piece of dust read signals and nothing else, and a cake must not power them - so
     * it is <b>not</b> folded into {@link #externalSignal(Direction)}: {@link PowerSolver} asks it where the
     * component type is known. Asked relative to the position last given to {@link #setQueryPosition}, and it
     * defaults to "nothing here" so an environment that cannot answer needs no new code.
     */
    default int analogOutput(Direction direction) {
        return 0;
    }

    /** Whether the block at {@code (x, y, z)} is a redstone conductor (opaque full block). */
    boolean isRedstoneConductor(int x, int y, int z);

    /**
     * Which component the queries that follow are about.
     *
     * <p>The directional questions above are asked <em>relative to a position</em> - "what does the world push
     * into <em>this</em> host block from that side" - but are not handed one, because the solver asks them from
     * inside its own walk and already knows it. An environment whose answers do not depend on a position (a
     * test's fake, the frozen carrier space) leaves this at the no-op default.
     */
    default void setQueryPosition(BlockPos pos) {
    }

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

        /**
         * 控制器's four settings, for the one component that has any.
         *
         * <p>A default method because every other node shape would otherwise have to answer a question about a
         * component it does not hold; implemented by {@code SlotNode} and {@code CarrierSpace.CarrierNode}, both
         * delegating straight to their slot.
         */
        default ControllerRule.Setting controller() {
            return ControllerRule.Setting.DEFAULT;
        }

        /**
         * 红石变压器's one setting, in the same shape as {@link #controller()}: a strength, because that is the
         * whole of the device, defaulting to a fresh transformer's fifteen.
         */
        default int transformer() {
            return TransformerRule.DEFAULT;
        }

        /**
         * The strength this component read at its input face on the last solve - a diagnostic, nothing decides on it.
         *
         * <p>A component inside a machine is read through the structure's own view, which for a sub-level is not the
         * level the player's coordinate names, so the screen must not re-derive this with a level query. The number
         * is recorded where it was read ({@link PowerSolver#desiredOutput}), so "what the screen shows" and "what the
         * circuit used" are the same number by construction. A no-op default: a wire has no single input face and a
         * test's fakes have no slot to write to.
         */
        default void noteInput(int input) {
        }
    }
}

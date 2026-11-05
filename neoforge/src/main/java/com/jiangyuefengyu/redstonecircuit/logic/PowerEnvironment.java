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
     * The same side read, with <b>no hop charged</b> to a neighbouring vanilla redstone wire.
     *
     * <p>Only a comparator's side input asks this. Vanilla reads a comparator's sides through
     * {@code RedStoneWireBlock#getSignal} like any other neighbour, and that answers the wire's own strength -
     * full strength, no decrement. The decrement exists for a wire feeding a wire, and charging it here would
     * make our subtract one too weak: {@code back - side} would come out one too high, so a feedback loop
     * that vanilla brings down to zero would stop at one.
     *
     * <p>A default, so every environment that cannot distinguish the two reads keeps today's behaviour
     * instead of failing to compile: the answer then degrades to {@link #externalSignal(Direction)}.
     */
    default int externalSignalUncharged(Direction direction) {
        return externalSignal(direction);
    }

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

    /**
     * The <b>vanilla</b> diode standing on one side, or {@code null} when that block is not one.
     *
     * <p>Only a vanilla diode can hold a component of ours: vanilla asks a signal getter for the alternate
     * signal with {@code sideInputDiodesOnly}, which walks the neighbours and keeps the answer of a block whose
     * state passes {@code DiodeBlock#isDiode} - so a piece of wire, a torch and a solid block charged to fifteen
     * are all <b>not</b> lock sources, however much signal they push. An environment that cannot look at vanilla
     * blocks at all (a test's fake, the frozen carrier space) answers {@code null} and therefore reports no
     * vanilla lock source, which is the honest degradation rather than a widening of the rule.
     *
     * <p>Asked relative to the position last given to {@link #setQueryPosition}, and {@code direction} points
     * from the component asking towards the neighbour.
     */
    @Nullable
    default VanillaDiode vanillaDiodeOn(Direction direction) {
        return null;
    }

    /**
     * One vanilla diode as an outside caller sees it: the way it faces, and what it pushes towards that caller.
     *
     * <p>{@code facing} is vanilla's own {@code FACING}, the side the diode <em>reads</em> from, because that is
     * what vanilla's {@code shouldPrioritize} compares against; {@code signalToward} is already the answer to
     * "what reaches me", so a caller never has to redo the direction inversion.
     */
    record VanillaDiode(Direction facing, int signalToward) {
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

        /**
         * 同步火把: 哪几面此刻有输入, 一位一面; 别的元件没有这个概念, 于是默认 0.
         *
         * <p>放在接口上而不是另开一条查询路径, 是因为 {@link PowerSolver#emitsToward(PowerEnvironment.Node, Direction)}
         * 已经是"这一面输不输出"的唯一答案: 只要它读不到掩码, 内嵌形态就会往有输入的那一面输出, 而世界形态不会 ——
         * 同一个设计在两处两种行为。
         */
        default byte syncInputs() {
            return 0;
        }

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

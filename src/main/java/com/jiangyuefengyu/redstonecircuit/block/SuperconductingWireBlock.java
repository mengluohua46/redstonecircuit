package com.jiangyuefengyu.redstonecircuit.block;

import com.jiangyuefengyu.redstonecircuit.RCRegistry;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 超导红石粉 placed in the world: a redstone wire whose hops cost nothing.
 *
 * <h2>What is inherited, and what is not</h2>
 * Everything that makes a wire <em>look</em> and behave like a wire is vanilla's: the state definition
 * (strength plus the four connection flags), the shapes and models, where it may be placed, how the
 * connections are worked out, and which blocks it counts as a source. The one thing that has to change
 * is the strength calculation, because vanilla's is the thing that makes a signal fade.
 *
 * <p>Vanilla's rule, read from {@code RedStoneWireBlock}: while a wire works out its strength it flips
 * its own {@code shouldSignal} flag off, so that <em>every</em> wire reports zero as a source and only
 * the direct state read in its wire scan can couple wires - and that scan subtracts one. The result is
 * {@code strength = max(sources, best neighbouring wire - 1)}.
 *
 * <p>This block keeps that structure exactly, with the subtraction removed and the wire scan widened to
 * cover both kinds of wire. Two consequences follow, and they are the mixing rule the design asks for:
 *
 * <ul>
 *   <li>Anything that is not a wire (a torch, a repeater, a charged block) feeds it at full strength,
 *       as in vanilla.</li>
 *   <li>A wire of either kind beside it feeds it at <b>full</b> strength: no hop is charged.</li>
 *   <li>An ordinary wire beside <b>it</b> still charges its own hop, because that is the ordinary
 *       wire's rule, not this block's. {@code RedStoneWireBlockMixin} makes vanilla's wire scan see
 *       this block, which is what gives exactly "superconductor to dust costs one".</li>
 * </ul>
 */
public class SuperconductingWireBlock extends RedStoneWireBlock {

    /**
     * How many ordinary wires are in the middle of their own strength calculation.
     *
     * <p>Vanilla keeps wires from charging each other with a flag on the wire block itself, which does
     * not cover a second wire block. Without a shared flag, an ordinary wire next to this one would find
     * this block's full strength through its source scan as well as through its wire scan - and the
     * subtraction it applies to the latter would be undone by the former, so dust beside a
     * superconductor would lose nothing. A counter rather than a flag because a calculation can, in
     * principle, nest.
     */
    private static int ordinaryWireCalculations;

    public SuperconductingWireBlock(Properties properties) {
        super(properties);
    }

    /** Marks the start of one ordinary wire's strength calculation. */
    public static void beginOrdinaryWireCalculation() {
        ordinaryWireCalculations++;
    }

    /** Marks the end of one ordinary wire's strength calculation. */
    public static void endOrdinaryWireCalculation() {
        ordinaryWireCalculations = Math.max(0, ordinaryWireCalculations - 1);
    }

    /** True while an ordinary wire is working out its strength. */
    public static boolean ordinaryWireIsCalculating() {
        return ordinaryWireCalculations > 0;
    }

    /** True when this state is this block: the check every part of the override needs. */
    public static boolean isSuperconductor(BlockState state) {
        return state.is(RCRegistry.SUPERCONDUCTING_WIRE.get());
    }

    /** The strength a wire of either kind currently holds, read straight from its state. */
    public static int wirePower(BlockState state) {
        if (state.is(net.minecraft.world.level.block.Blocks.REDSTONE_WIRE) || isSuperconductor(state)) {
            return state.getValue(POWER);
        }
        return 0;
    }

    @Override
    protected int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction side) {
        if (ordinaryWireIsCalculating()) {
            // Keep quiet while an ordinary wire is deciding, for the reason on the counter above: this
            // block is deliberately visible to that wire's *wire* scan, where its hop is charged.
            return 0;
        }
        return super.getSignal(state, level, pos, side);
    }

    /**
     * The strength this wire should hold: vanilla's calculation without the hop cost.
     *
     * <p>Written to mirror vanilla's own method so the two are easy to compare, including its
     * {@code i < 15} shortcut and the step-up/step-down rule that lets a wire climb a block. The
     * differences are that wires of both kinds are treated as wires, and that the best neighbouring
     * wire is worth its full value.
     */
    public static int strengthAt(Level level, BlockPos pos) {
        int best = 0;
        for (Direction direction : Direction.values()) {
            BlockPos neighbour = pos.relative(direction);
            BlockState state = level.getBlockState(neighbour);
            if (state.is(Blocks.REDSTONE_WIRE) || isSuperconductor(state)) {
                // Wires are the wire scan's business, and it runs on the horizontal plane only: a wire
                // above or below must not feed this one, or power would travel where the shape shows no
                // connection.
                continue;
            }
            best = Math.max(best, level.getSignal(neighbour, direction));
            if (best >= 15) {
                return 15;
            }
        }

        int wire = 0;
        if (best < 15) {
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                BlockPos neighbour = pos.relative(direction);
                BlockState state = level.getBlockState(neighbour);
                wire = Math.max(wire, wirePower(state));
                BlockPos above = pos.above();
                if (state.isRedstoneConductor(level, neighbour)
                        && !level.getBlockState(above).isRedstoneConductor(level, above)) {
                    wire = Math.max(wire, wirePower(level.getBlockState(neighbour.above())));
                } else if (!state.isRedstoneConductor(level, neighbour)) {
                    wire = Math.max(wire, wirePower(level.getBlockState(neighbour.below())));
                }
            }
        }
        // No subtraction: that is the whole of this block.
        return Math.max(best, wire);
    }

    /**
     * Recomputes this wire and tells the world, exactly as vanilla's own private method does.
     *
     * <p>Called from {@code RedStoneWireBlockMixin} in place of vanilla's calculation, so the state
     * change and the neighbour notifications have to be reproduced here rather than inherited.
     */
    public static void updateStrength(Level level, BlockPos pos, BlockState state) {
        int power = strengthAt(level, pos);
        if (state.getValue(POWER) == power) {
            return;
        }
        if (level.getBlockState(pos) == state) {
            level.setBlock(pos, state.setValue(POWER, power), 2);
        }
        // The wire itself, its six neighbours, and - like vanilla - one block further along the sides,
        // which is what wakes a wire that has just become connected across a step.
        for (Direction direction : Direction.values()) {
            level.updateNeighborsAt(pos.relative(direction), state.getBlock());
        }
        level.updateNeighborsAt(pos, state.getBlock());
    }
}

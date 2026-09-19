package com.jiangyuefengyu.redstonecircuit.block;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
 * connections are worked out, and which blocks it counts as a source. What has to change is the
 * strength calculation, and it turns out that a lossless wire cannot be computed the way vanilla
 * computes a lossy one.
 *
 * <h2>Why the whole run is solved at once</h2>
 * Vanilla's rule is {@code strength = max(sources around it, best neighbouring wire - 1)}, evaluated
 * one wire at a time. The subtraction is what makes that work: every wire is strictly weaker than the
 * neighbour it follows, so a run with no source left in it walks itself down to zero, one step per
 * update.
 *
 * <p>Remove the subtraction and that mechanism disappears. Two wires at fifteen beside each other see
 * nothing wrong with each other, so neither ever changes, and a run whose lever has just been switched
 * off stays at fifteen for good - and keeps lighting whatever redstone is laid against it. A lossless
 * wire therefore cannot be decided from its neighbours' current values at all.
 *
 * <p>What can decide it is the sources: with no loss, an entire connected run sits at the strength of
 * the best source touching it, and at nothing when no source touches it. That is both the physical
 * meaning of a superconductor and a unique answer, so {@link #updateComponent} collects the run,
 * takes the best supply anywhere in it, and gives every wire that value. A run that has lost its source
 * has no supply left anywhere in it, and goes dark.
 *
 * <h2>Keeping quiet while deciding</h2>
 * Vanilla silences its wire while it works out a strength, and for two reasons that both apply here.
 *
 * <p>The first is wire-to-wire coupling: a wire reports its strength as an ordinary signal as well, so
 * without the silence a wire would find itself through its neighbours' source scan and charge itself.
 *
 * <p>The second is subtler and is what makes a lossless run possible at all. Vanilla's wire reports
 * {@code getDirectSignal} as its own strength, which means a wire <em>charges the solid block it lies
 * on</em>; that block then reports the charge back to everything around it, including the wire. For a
 * fading wire that is harmless - the value it reads back is never better than what it has. For a
 * lossless one it is fatal: the run finds its own strength coming out of the ground beneath it, decides
 * it still has a supply, and never goes dark once its lever is switched off. So this block is silent
 * whenever any wire is calculating, vanilla's or its own.
 */
public class SuperconductingWireBlock extends RedStoneWireBlock {

    /**
     * How many strength calculations are in progress.
     *
     * <p>A counter rather than a flag because a calculation can nest: solving a run reads the world, and
     * reading the world can ask a neighbouring wire to recalculate.
     */
    private static int suppressions;

    public SuperconductingWireBlock(Properties properties) {
        super(properties);
    }

    /** Marks the start of one strength calculation; while any is running, this block reports nothing. */
    public static void beginSuppression() {
        suppressions++;
    }

    /** Marks the end of one strength calculation. */
    public static void endSuppression() {
        suppressions = Math.max(0, suppressions - 1);
    }

    /** True while a wire is working out a strength. */
    public static boolean isSuppressed() {
        return suppressions > 0;
    }

    /** True when this state is this block: the check every part of the override needs. */
    public static boolean isSuperconductor(BlockState state) {
        return state.is(RCRegistry.SUPERCONDUCTING_WIRE.get());
    }

    /** The strength a wire of either kind currently holds, read straight from its state. */
    public static int wirePower(BlockState state) {
        if (state.is(Blocks.REDSTONE_WIRE) || isSuperconductor(state)) {
            return state.getValue(POWER);
        }
        return 0;
    }

    @Override
    protected int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction side) {
        if (isSuppressed()) {
            return 0;
        }
        return super.getSignal(state, level, pos, side);
    }

    @Override
    protected int getDirectSignal(BlockState state, BlockGetter level, BlockPos pos, Direction side) {
        if (isSuppressed()) {
            return 0;
        }
        return super.getDirectSignal(state, level, pos, side);
    }

    // ------------------------------------------------------------- the solve --

    /**
     * Recomputes the connected run of superconducting wire that {@code origin} belongs to.
     *
     * <p>Called from {@code RedStoneWireBlockMixin} wherever vanilla would recompute one wire, so it runs
     * however the change was triggered.
     */
    public static void updateComponent(Level level, BlockPos origin) {
        // Silent for the duration, for both reasons on the suppression counter: the run must not read its
        // own strength back out of the ground it lies on, and a nested recalculation triggered by the
        // writes below must not see half-solved values either.
        beginSuppression();
        try {
            List<BlockPos> run = collectRun(level, origin);
            if (run.isEmpty()) {
                return;
            }

            int strength = 0;
            for (BlockPos pos : run) {
                strength = Math.max(strength, supplyAt(level, pos));
            }

            for (BlockPos pos : run) {
                BlockState state = level.getBlockState(pos);
                if (!isSuperconductor(state) || state.getValue(POWER) == strength) {
                    continue;
                }
                // Flag 2, as vanilla uses: this change is sent to clients, and the neighbours are told
                // explicitly just below rather than by the block update itself.
                level.setBlock(pos, state.setValue(POWER, strength), 2);
                for (Direction direction : Direction.values()) {
                    level.updateNeighborsAt(pos.relative(direction), state.getBlock());
                }
                level.updateNeighborsAt(pos, state.getBlock());
            }
        } finally {
            endSuppression();
        }
    }

    /** Every superconducting wire joined to {@code origin}, by the rule that decides the wire's shape. */
    private static List<BlockPos> collectRun(Level level, BlockPos origin) {
        List<BlockPos> run = new ArrayList<>();
        if (!isSuperconductor(level.getBlockState(origin))) {
            return run;
        }
        Set<BlockPos> seen = new HashSet<>();
        Deque<BlockPos> queue = new ArrayDeque<>();
        BlockPos start = origin.immutable();
        queue.add(start);
        seen.add(start);

        while (!queue.isEmpty()) {
            BlockPos pos = queue.poll();
            run.add(pos);
            for (BlockPos neighbour : joinedWires(level, pos)) {
                if (seen.add(neighbour)) {
                    queue.add(neighbour);
                }
            }
        }
        return run;
    }

    /**
     * The superconducting wires this wire is joined to.
     *
     * <p>Vanilla's wire rule: the four horizontal neighbours, plus - where the terrain allows a wire to
     * climb or drop a block - the wire above or below that neighbour. A wire above or below <em>this</em>
     * one is not joined, which is why the shape shows no connection there either.
     */
    private static List<BlockPos> joinedWires(Level level, BlockPos pos) {
        List<BlockPos> joined = new ArrayList<>();
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos neighbour = pos.relative(direction);
            BlockState state = level.getBlockState(neighbour);
            if (isSuperconductor(state)) {
                joined.add(neighbour.immutable());
            }
            BlockPos above = pos.above();
            if (state.isRedstoneConductor(level, neighbour)
                    && !level.getBlockState(above).isRedstoneConductor(level, above)) {
                if (isSuperconductor(level.getBlockState(neighbour.above()))) {
                    joined.add(neighbour.above().immutable());
                }
            } else if (!state.isRedstoneConductor(level, neighbour)) {
                if (isSuperconductor(level.getBlockState(neighbour.below()))) {
                    joined.add(neighbour.below().immutable());
                }
            }
        }
        return joined;
    }

    /**
     * The best strength reaching this wire from anything that is <em>not</em> one of its own kind.
     *
     * <p>Sources - levers, torches, diodes, charged blocks - are read the way vanilla reads them, from
     * any of the six sides. Ordinary dust is read where a wire could connect at all, at its full
     * strength, which is the lossless half of the mixing rule.
     */
    private static int supplyAt(Level level, BlockPos pos) {
        int best = 0;
        for (Direction direction : Direction.values()) {
            BlockPos neighbour = pos.relative(direction);
            BlockState state = level.getBlockState(neighbour);
            if (isSuperconductor(state) || state.is(Blocks.REDSTONE_WIRE)) {
                // Its own kind is the run itself; ordinary dust is the wire scan's business below.
                continue;
            }
            best = Math.max(best, level.getSignal(neighbour, direction));
            if (best >= 15) {
                return 15;
            }
        }

        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos neighbour = pos.relative(direction);
            BlockState state = level.getBlockState(neighbour);
            best = Math.max(best, dustPower(state));
            BlockPos above = pos.above();
            if (state.isRedstoneConductor(level, neighbour)
                    && !level.getBlockState(above).isRedstoneConductor(level, above)) {
                best = Math.max(best, dustPower(level.getBlockState(neighbour.above())));
            } else if (!state.isRedstoneConductor(level, neighbour)) {
                best = Math.max(best, dustPower(level.getBlockState(neighbour.below())));
            }
        }
        return best;
    }

    private static int dustPower(BlockState state) {
        return state.is(Blocks.REDSTONE_WIRE) ? state.getValue(POWER) : 0;
    }
}

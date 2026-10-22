package com.jiangyuefengyu.redstonecircuit.block;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.jiangyuefengyu.redstonecircuit.RCConfig;
import com.jiangyuefengyu.redstonecircuit.RCRegistry;
import com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneStore;
import com.jiangyuefengyu.redstonecircuit.data.Slot;
import com.jiangyuefengyu.redstonecircuit.logic.InnerRedstoneNetwork;
import com.jiangyuefengyu.redstonecircuit.logic.PowerSolver;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.state.BlockState;

import org.jetbrains.annotations.Nullable;

/**
 * 超导红石粉 placed in the world: a redstone wire whose hops cost nothing.
 *
 * <p>Everything that makes a wire <em>look</em> and behave like a wire is vanilla's - the state definition, the shapes
 * and models, where it may be placed, how connections are worked out - except the strength calculation. Vanilla's rule
 * subtracts one per hop, and that subtraction is what lets a fading run walk itself down to zero; without it two wires at
 * fifteen beside each other see nothing wrong, so neither ever changes and a run whose lever has been switched off stays
 * at fifteen for good.
 *
 * <p>A lossless wire therefore cannot be decided from its neighbours' current values at all: {@link #updateComponent}
 * collects the whole connected run, takes the best supply anywhere in it and gives every wire that value - both the
 * physical meaning of a superconductor and a unique answer. The block reports nothing while any wire is calculating (see
 * the suppression counter), because a wire's {@code getDirectSignal} charges the solid block it lies on, and a lossless
 * run would otherwise find its own strength coming out of the ground and never go dark.
 *
 * <p><b>A run is not a network of its own where it meets a host block.</b> Whatever is inside that block is wired to it -
 * a piece of 超导红石粉 buried in the block is the same material - and the two are solved as one by
 * {@code InnerRedstoneNetwork}, from zero, with only real sources as seeds. Solving them one at a time is what used to
 * latch such a pair at fifteen: each side read the other's <em>stored</em> strength, and with no hop charged between two
 * lossless wires every pair of values was a fixed point. {@link #updateComponent} therefore hands the run over to that
 * solve whenever it is laid against a host block, and only computes it here when it is alone in the world.
 *
 * <p>{@link #canConnectRedstone} is the one answer about the wire's <em>shape</em> that is not inherited: vanilla answers
 * its own dust unconditionally and everything else only when asked about a particular side, while the wire's step rule
 * asks with no side at all.
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

    /**
     * Redstone dust connects to a superconductor on any side, exactly as it connects to ordinary redstone dust.
     *
     * <p>This is the one answer vanilla gives that a subclass cannot inherit. Vanilla answers {@code
     * state.is(Blocks.REDSTONE_WIRE)} unconditionally and everything else only when {@code direction != null}, while the
     * wire's own rule asks with a <em>null</em> direction exactly when it wants to know whether it may climb or drop past
     * a block - so a superconductor was told its neighbour could never be climbed and drew no arm up a step, even though
     * the strength crossed it anyway (that is a different question asked a different way).
     *
     * <p>Nothing but this one connection answer changes; the method is only consulted while shapes are being worked out.
     */
    @Override
    public boolean canConnectRedstone(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        return isSuperconductor(state) || super.canConnectRedstone(state, level, pos, direction);
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
     *
     * <p>Two shapes here. A run <b>alone in the world</b> is solved on the spot: its own strength is the best
     * supply anywhere in it, which is the whole of its physics. A run laid against a <b>host block</b> is not:
     * the component inside that block is wired to it, so the two are solved together by
     * {@code InnerRedstoneNetwork} - see {@link #updateComponent}'s body, and {@code SuperconductingWireBlock}'s
     * class note for why solving them apart could never come back down.
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

            InnerRedstoneStore components = level instanceof ServerLevel server
                    ? InnerRedstoneStore.get(server)
                    : null;
            List<BlockPos> hosting = components == null || components.isEmpty()
                    ? List.of()
                    : hostsBeside(components, run);
            if (!hosting.isEmpty()) {
                // A run laid against a host block is not a network of its own: whatever is inside that block is
                // wired to it, and the two have to be solved together or each reads the other's stored value
                // and neither can ever come down. The settle pass owns that solve, so all this does is make
                // sure it happens - and it leaves the state alone, because writing here would be writing half
                // of an answer the other half has not reached yet.
                for (BlockPos host : hosting) {
                    InnerRedstoneNetwork.markDirty((ServerLevel) level, host);
                }
                return;
            }

            int strength = 0;
            for (BlockPos pos : run) {
                strength = Math.max(strength, supplyAt(level, pos, components));
            }
            writeRun(level, run, PowerSolver.clamp(strength));
        } finally {
            endSuppression();
        }
    }

    /**
     * Gives every cell of one run the same strength, and tells the neighbours of the cells it changed.
     *
     * <p>Flag 2, as vanilla uses: this change is sent to clients, and the neighbours are told explicitly
     * below rather than by the block update itself. A cell already holding the strength is left alone, so a
     * solve that changed nothing writes nothing.
     */
    public static void writeRun(Level level, List<BlockPos> run, int strength) {
        for (BlockPos pos : run) {
            BlockState state = level.getBlockState(pos);
            if (!isSuperconductor(state) || state.getValue(POWER) == strength) {
                continue;
            }
            level.setBlock(pos, state.setValue(POWER, strength), 2);
            for (Direction direction : Direction.values()) {
                level.updateNeighborsAt(pos.relative(direction), state.getBlock());
            }
            level.updateNeighborsAt(pos, state.getBlock());
        }
    }

    /**
     * The host blocks this run is laid against that hold a component of ours.
     *
     * <p>Their presence is what makes the run part of a larger network: a component inside a host block is
     * read by the run and reads the run back, so the two cannot be solved one at a time.
     */
    public static List<BlockPos> hostsBeside(@Nullable InnerRedstoneStore components, List<BlockPos> run) {
        List<BlockPos> hosts = new ArrayList<>();
        if (components == null) {
            return hosts;
        }
        for (BlockPos cell : run) {
            for (Direction direction : Direction.values()) {
                BlockPos neighbour = cell.relative(direction);
                if (components.slotAt(neighbour) != null && !hosts.contains(neighbour)) {
                    hosts.add(neighbour.immutable());
                }
            }
        }
        return hosts;
    }

    /** Every superconducting wire joined to {@code origin}, by the rule that decides the wire's shape. */
    public static List<BlockPos> collectRun(Level level, BlockPos origin) {
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
     *
     * <p>A host block is <b>a component and not a block of stone</b>, so it is read from its record rather
     * than through the world query: the query is silent while our own solve runs, and the two must not
     * answer differently depending on whether a solve happens to be in progress. What that component is
     * decides whether it is a supply at all - a lever, a torch or a repeater inside the block feeds this wire,
     * while a <b>wire</b> inside it does not: two wires on either side of one block boundary are one network,
     * and taking the stored value of one as the other's supply is what used to hold such a pair at fifteen
     * for ever. That value is a node of the merged solve ({@code InnerRedstoneNetwork}), never a seed.
     *
     * @param components the level's inner components, or {@code null} when there are none to consult
     */
    public static int supplyAt(Level level, BlockPos pos, @Nullable InnerRedstoneStore components) {
        int best = 0;
        for (Direction direction : Direction.values()) {
            BlockPos neighbour = pos.relative(direction);
            BlockState state = level.getBlockState(neighbour);
            if (isSuperconductor(state) || state.is(Blocks.REDSTONE_WIRE)) {
                // Its own kind is the run itself; ordinary dust is the wire scan's business below.
                continue;
            }
            Slot held = components == null ? null : components.slotAt(neighbour);
            if (held == null) {
                best = Math.max(best, level.getSignal(neighbour, direction));
            } else if (!held.type.isWire()) {
                best = Math.max(best, PowerSolver.signalAnswer(held, direction.getOpposite(), false,
                        true, RCConfig.hostConductsStrongPower()));
            }
            if (best >= PowerSolver.MAX_POWER) {
                return PowerSolver.MAX_POWER;
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

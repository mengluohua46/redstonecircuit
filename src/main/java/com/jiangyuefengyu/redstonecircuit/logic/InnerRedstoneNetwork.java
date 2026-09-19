package com.jiangyuefengyu.redstonecircuit.logic;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.jiangyuefengyu.redstonecircuit.RCConfig;
import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneStore;
import com.jiangyuefengyu.redstonecircuit.data.Slot;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Drives power propagation for inner redstone dust.
 *
 * <p>When anything that could affect a dust network changes (a component is placed or removed, a
 * neighbouring block changes), the affected position is marked dirty. At the end of the tick the
 * dirty positions are resolved: the connected dust component around each one is re-derived from
 * scratch, and - when that changed what the host blocks emit - the surrounding world is told.
 *
 * <h2>A solve has two halves</h2>
 * <ol>
 *   <li><b>Inwards</b> ({@link #relax}): the component is reset to zero and grown to the least fixed
 *       point of {@code value = max(supply, max over neighbours (value - 1))}. Starting from zero is
 *       what makes the result a function of the supplies alone.</li>
 *   <li><b>Outwards</b> ({@link #recompute}): once the values are final <em>and</em> the signal
 *       suppression has been lifted, every host block whose power changed sends a vanilla neighbour
 *       update, so the world re-reads it through {@code BlockState#getSignal}.</li>
 * </ol>
 *
 * <p>The second half is not optional. Storing a new power changes no block state, so nothing
 * schedules an update by itself: without it a redstone lamp lit by an inner component stays lit
 * forever after the component drains, and only corrected itself when an unrelated block change
 * happened to notify it. That was a real bug report.
 *
 * <p>{@link #settle(ServerLevel)} repeats both halves until the queue stays empty, because
 * notifying the world can bounce straight back into the network (a vanilla wire drops a step, a lamp
 * goes out and stops lighting something).
 */
public final class InnerRedstoneNetwork {

    /**
     * Most resolve rounds per tick.
     *
     * <p>Every round either changes nothing (and stops) or strictly decreases some signal, and
     * signals bottom out at 0, so a round is never wasted work. Anything still queued after this
     * many rounds is simply left for the next tick, which keeps a pathological build from stalling
     * the server inside one tick.
     */
    private static final int MAX_ROUNDS_PER_TICK = 2 * PowerSolver.MAX_POWER + 2;

    /** Per-level set of positions awaiting recomputation. */
    private static final Map<net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>, Set<BlockPos>> DIRTY =
            new HashMap<>();

    /**
     * Re-entrancy guard mirroring vanilla's {@code RedStoneWireBlock#shouldSignal}.
     *
     * <p>While a wire evaluates its own target strength it must not report its current power to
     * neighbours, otherwise a wire would charge itself from its own value and the network could
     * never settle. Vanilla flips a field around its {@code getBestNeighborSignal} call; the
     * equivalent here is a level-scoped flag consulted by the signal query in
     * {@code BlockStateSignalMixin}.
     */
    private static final Set<net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>> SUPPRESS_SIGNAL =
            new HashSet<>();

    private InnerRedstoneNetwork() {
    }

    // ------------------------------------------------------------ scheduling --

    /** Queues a position for recomputation; resolved by {@link #settle(ServerLevel)}. */
    public static void markDirty(ServerLevel level, BlockPos pos) {
        DIRTY.computeIfAbsent(level.dimension(), key -> new HashSet<>()).add(pos.immutable());
    }

    /** Queues a position and everything around it, for changes that affect neighbours. */
    public static void markDirtyWithNeighbours(ServerLevel level, BlockPos pos) {
        Set<BlockPos> set = DIRTY.computeIfAbsent(level.dimension(), key -> new HashSet<>());
        set.add(pos.immutable());
        for (Direction direction : Direction.values()) {
            set.add(pos.relative(direction).immutable());
        }
    }

    /** Resolves everything queued for this level. Called once per server tick. */
    public static void tick(ServerLevel level) {
        settle(level);
    }

    /**
     * Drains the queue until no further work appears, then returns.
     *
     * <p>One solve is not always the end of the story, because telling the world about it can change
     * the world in a way that comes straight back: a vanilla wire laid against a host block drops a
     * step (it is charged one hop, see {@link SolverEnvironment#externalSignal()}), and that change
     * is reported back to us synchronously by the very neighbour update we just sent. Repeating the
     * whole solve/notify cycle in the same tick is what makes a source removal visible immediately
     * instead of fading one strength per tick.
     */
    public static void settle(ServerLevel level) {
        Set<BlockPos> dirty = DIRTY.get(level.dimension());
        if (dirty == null || dirty.isEmpty()) {
            return;
        }
        InnerRedstoneStore store = InnerRedstoneStore.get(level);
        if (store.isEmpty()) {
            dirty.clear();
            return;
        }

        int round = 0;
        while (!dirty.isEmpty()) {
            if (++round > MAX_ROUNDS_PER_TICK) {
                if (RCConfig.debugLog()) {
                    RCConfig.LOGGER.info(
                            "[redstonecircuit] {} position(s) still queued after {} rounds; continuing next tick",
                            dirty.size(), MAX_ROUNDS_PER_TICK);
                }
                return;
            }

            List<BlockPos> positions = new ArrayList<>(dirty);
            dirty.clear();

            // Several queued positions usually belong to the same component - placing a component
            // queues the position plus its six neighbours - so remember what has already been solved
            // instead of redoing (and re-logging) it once per member.
            Set<BlockPos> solved = new HashSet<>();
            for (BlockPos pos : positions) {
                if (solved.contains(pos) || store.slotAt(pos) == null) {
                    continue;
                }
                solved.addAll(recompute(level, store, pos));
            }
        }
    }

    /** Drops queued work for a level that is going away. */
    public static void clear(ServerLevel level) {
        DIRTY.remove(level.dimension());
        SUPPRESS_SIGNAL.remove(level.dimension());
    }

    /** True while the given level is evaluating wire power and must not report it to neighbours. */
    public static boolean isSignalSuppressed(net.minecraft.world.level.Level level) {
        return SUPPRESS_SIGNAL.contains(level.dimension());
    }

    // ---------------------------------------------------------- propagation --

    /**
     * Re-evaluates the connected dust component containing {@code seed}, and tells the world if the
     * result changed.
     *
     * <p>The component is recomputed from scratch rather than only from {@code seed}, because a
     * removed power source has to propagate a <em>decrease</em> as well, and that requires
     * re-deriving the whole component.
     *
     * @return every position in the component, so the caller can skip re-solving them
     */
    public static List<BlockPos> recompute(ServerLevel level, InnerRedstoneStore store, BlockPos seed) {
        List<BlockPos> component = collectComponent(level, store, seed);
        if (component.isEmpty()) {
            return component;
        }
        if (RCConfig.debugLog()) {
            RCConfig.LOGGER.info("[redstonecircuit] recompute seed={} component={}",
                    seed.toShortString(), component);
        }

        List<BlockPos> changed;
        // Suppress inner-redstone signal output for the WHOLE solve.
        //
        // While the solver is deciding what each component should hold, the values it reads are
        // intermediate: reporting them to the world would let a component charge itself from its own
        // half-computed value. The flag is therefore scoped to the solve rather than to individual
        // signal queries, which is both easier to reason about and impossible to leak (it is cleared
        // in a finally block even if a solve blows up).
        boolean alreadySuppressed = !SUPPRESS_SIGNAL.add(level.dimension());
        try {
            changed = relax(level, store, component);
        } finally {
            if (!alreadySuppressed) {
                SUPPRESS_SIGNAL.remove(level.dimension());
            }
        }

        // Outwards - and only now. While the solve above was running every inner component reported
        // 0 to the world (that is what stops a component charging itself from its own value), so a
        // consumer woken up mid-solve would read that stub and latch onto the wrong state. With the
        // values final and the stub gone, a plain vanilla neighbour update is all it takes: every
        // consumer re-reads the host block through BlockState#getSignal.
        for (BlockPos pos : changed) {
            notifyOutputChanged(level, pos);
        }
        return component;
    }

    /**
     * Tells the world that the signal emitted by the host block at {@code pos} may have changed.
     *
     * <p>{@code updateNeighborsAt} is exactly the path vanilla uses: it fires
     * {@code NeighborNotifyEvent} and runs each of the six neighbours' own {@code neighborChanged}.
     */
    public static void notifyOutputChanged(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        level.updateNeighborsAt(pos, state.getBlock());
    }

    // --------------------------------------------------------------- solver --

    /**
     * Re-derives every value in {@code component} and returns the positions whose power changed.
     *
     * <p>The solve resets the component to zero and then only ever raises values, so the answer is
     * the <em>least</em> fixed point of {@code value = max(supply, max over neighbours (value - 1))}.
     * That is also the only fixed point: every coupling between two components costs a hop, so a
     * non-zero value has to come from a supply. Stating it this way makes the result a function of
     * the supplies alone, independent of the order the positions happen to be visited in.
     *
     * <p>Seeding the iteration with the previous values instead - letting values fall as well as
     * rise - reaches the same answer in theory but behaves badly in practice: a component whose
     * supply has just been removed can keep feeding off its own stale value and its neighbour's, so
     * the pair walks down one step per pass (visible in the logs as 15 -&gt; 13 -&gt; 11 -&gt; ...)
     * and needs a dozen passes to reach zero. Growing from zero reaches the answer in at most
     * {@code MAX_POWER + 1} passes, because a value of {@code v} needs {@code v} hops of propagation.
     */
    private static List<BlockPos> relax(ServerLevel level, InnerRedstoneStore store, List<BlockPos> component) {
        SolverEnvironment env = new SolverEnvironment(level, store);

        // The solve starts from zero, so "did anything change?" has to be answered against where the
        // component was before.
        Map<BlockPos, Integer> previous = new HashMap<>(component.size() * 2);
        for (BlockPos pos : component) {
            Slot slot = store.slotAt(pos);
            previous.put(pos, slot == null ? 0 : slot.power);
            if (slot != null) {
                slot.power = 0;
            }
        }

        int pass = 0;
        boolean changed = true;
        while (changed) {
            if (pass++ > PowerSolver.MAX_POWER) {
                RCConfig.LOGGER.warn(
                        "[redstonecircuit] inner redstone network did not settle after {} passes",
                        PowerSolver.MAX_POWER + 1);
                break;
            }

            changed = false;
            for (BlockPos pos : component) {
                Slot slot = store.slotAt(pos);
                if (slot == null || slot.type != ComponentType.DUST) {
                    continue;
                }

                env.setQueryPos(pos);
                int target = PowerSolver.targetStrength(env, pos, supplyOf(env, slot));
                if (target > slot.power) {
                    slot.power = target;
                    changed = true;
                }
            }
        }

        List<BlockPos> changedPositions = new ArrayList<>();
        for (BlockPos pos : component) {
            Slot slot = store.slotAt(pos);
            if (slot == null) {
                continue;
            }
            int before = previous.get(pos);
            if (before == slot.power) {
                continue;
            }
            changedPositions.add(pos);

            // Logged whenever a value actually changes, which is the only reliable way to diagnose
            // "the lever does nothing" / "it stays powered" reports: placement alone says nothing
            // about the power that resulted from it. Only final values are reported, never the
            // intermediate ones, so a line here means the component really moved.
            env.setQueryPos(pos);
            RCConfig.LOGGER.info(
                    "[redstonecircuit] {} at {} power {} -> {} (supply={}, externalSignal={}, injected={})",
                    slot.type, pos.toShortString(), before, slot.power, supplyOf(env, slot),
                    env.externalSignal(), slot.injectedPower);
        }

        if (!changedPositions.isEmpty()) {
            store.markDirty();
        }
        return changedPositions;
    }

    /**
     * The power the component draws from outside the wire network.
     *
     * <p>{@link PowerEnvironment#externalSignal()} is what the vanilla world pushes into the host
     * block (a lever, torch or redstone block against it, or a neighbouring wire);
     * {@code injectedPower} is a value planted by the debug command or a game test. Both are inputs.
     * A neighbouring component's own power is never one - that is the difference between "this
     * component is fed" and "this component is merely next to something powered".
     */
    private static int supplyOf(PowerEnvironment env, Slot slot) {
        int supply = env.externalSignal();
        if (slot.hasInjectedPower()) {
            supply = Math.max(supply, slot.injectedPower);
        }
        return PowerSolver.clamp(supply);
    }

    /** Collects every dust position connected to {@code seed}, following vanilla's coupling rules. */
    private static List<BlockPos> collectComponent(ServerLevel level, InnerRedstoneStore store, BlockPos seed) {
        List<BlockPos> out = new ArrayList<>();
        Set<BlockPos> seen = new HashSet<>();
        Deque<BlockPos> queue = new ArrayDeque<>();
        queue.add(seed.immutable());
        seen.add(seed.immutable());

        while (!queue.isEmpty()) {
            BlockPos pos = queue.poll();
            Slot slot = store.slotAt(pos);
            if (slot == null || slot.type != ComponentType.DUST) {
                continue;
            }
            out.add(pos);

            for (BlockPos neighbour : neighbours(store, pos)) {
                if (seen.add(neighbour)) {
                    queue.add(neighbour);
                }
            }
        }
        return out;
    }

    /**
     * The dust positions that couple with {@code pos}.
     *
     * <p>All six directions are considered, mirroring {@link PowerSolver#targetStrength}: inner
     * redstone sits inside its host block, so an adjacent block holding a component is directly
     * coupled. Vanilla's terrain-aware climb/step-down rules deliberately do not apply here - see
     * the note on {@code PowerSolver}.
     */
    private static List<BlockPos> neighbours(InnerRedstoneStore store, BlockPos pos) {
        List<BlockPos> out = new ArrayList<>(6);
        for (Direction direction : Direction.values()) {
            addIfDust(store, pos.relative(direction), out);
        }
        return out;
    }

    private static void addIfDust(InnerRedstoneStore store, BlockPos pos, List<BlockPos> out) {
        Slot slot = store.slotAt(pos);
        if (slot != null && slot.type == ComponentType.DUST) {
            out.add(pos.immutable());
        }
    }

    // ------------------------------------------------------------- env view --

    /** Adapts a level plus the store to the headless {@link PowerEnvironment} used by the solver. */
    private static final class SolverEnvironment implements PowerEnvironment {

        private final ServerLevel level;
        private final InnerRedstoneStore store;

        /** The position currently being solved, needed because {@link #externalSignal()} is a no-arg query. */
        private BlockPos queryPos = BlockPos.ZERO;

        SolverEnvironment(ServerLevel level, InnerRedstoneStore store) {
            this.level = level;
            this.store = store;
        }

        void setQueryPos(BlockPos pos) {
            this.queryPos = pos;
        }

        /**
         * Strongest signal the world pushes into the host block at {@link #queryPos}.
         *
         * <p>This is literally vanilla {@code Level#getBestNeighborSignal}: the same six queries, with
         * the same direction and the same weak-power handling, so a lever, torch, redstone block,
         * repeater, comparator, observer or strongly powered block feeds the inner redstone exactly as
         * it would feed a piece of vanilla wire.
         *
         * <h2>Which direction to ask</h2>
         * {@code getSignal(neighbour, direction)} expects the direction <em>from the queried block to
         * the neighbour</em>; the signal methods themselves then read the other way round (which is why
         * {@code RedstoneTorchBlock} carries a note saying the directions are backwards). Vanilla
         * therefore passes {@code direction} here - not its opposite.
         *
         * <p>Asking with the opposite direction was a real bug, and it hit every <b>directional</b>
         * source: {@code ObserverBlock#getSignal} answers {@code 15} only when
         * {@code FACING == side}, so an observer emitting into a host block reported nothing at all -
         * a repeater or comparator would likewise have been read from its input side, which never
         * answers. Sources that emit in five or six directions (levers, torches, redstone blocks,
         * wire) hid the mistake, which is why only the observer report exposed it.
         *
         * <h2>The one deliberate difference</h2>
         * A neighbouring <b>vanilla redstone wire</b> is charged one hop, exactly as vanilla charges a
         * hop for a neighbouring wire in {@code RedStoneWireBlock#calculateTargetStrength}.
         *
         * <p>That charge is what keeps the system solvable. A wire is the one neighbour that can be
         * powered <em>by</em> the host it powers, and its signal is derived from ours, so passing it
         * through unattenuated would let the two hold each other up at 15 forever - remove the torch
         * and the wire would keep the inner redstone alive, which is precisely the "stays active
         * after the source is gone" report. Charging a hop makes every cycle in the system strictly
         * decreasing, which leaves exactly one solution: with no source anywhere, everything is 0.
         *
         * <p>Inner-redstone output is already suppressed for the duration of the solve, so the weak
         * power a neighbouring host block would otherwise transmit reads as 0 here and the solver
         * cannot feed itself through it.
         */
        @Override
        public int externalSignal() {
            int best = 0;
            for (Direction direction : Direction.values()) {
                BlockPos neighbour = queryPos.relative(direction);
                int signal = level.getSignal(neighbour, direction);
                if (signal > 0 && level.getBlockState(neighbour).is(Blocks.REDSTONE_WIRE)) {
                    signal--;
                }
                best = Math.max(best, signal);
                if (best >= PowerSolver.MAX_POWER) {
                    return PowerSolver.MAX_POWER;
                }
            }
            return best;
        }

        @Override
        public int bestNeighborSignal() {
            return externalSignal();
        }

        @Override
        public WireNode dustAt(int x, int y, int z) {
            Slot slot = store.slotAt(new BlockPos(x, y, z));
            if (slot == null || slot.type != ComponentType.DUST) {
                return null;
            }
            return new WireNode() {
                @Override
                public int power() {
                    return slot.power;
                }

                @Override
                public void setPower(int power) {
                    slot.power = power;
                }
            };
        }

        @Override
        public boolean isRedstoneConductor(int x, int y, int z) {
            BlockPos pos = new BlockPos(x, y, z);
            return level.getBlockState(pos).isRedstoneConductor(level, pos);
        }
    }
}

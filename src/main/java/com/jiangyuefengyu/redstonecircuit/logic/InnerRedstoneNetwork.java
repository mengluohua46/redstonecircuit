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
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Drives power propagation for everything stored inside host blocks.
 *
 * <p>When anything that could affect a network changes (a component is placed or removed, a
 * neighbouring block changes), the affected position is marked dirty. At the end of the tick the
 * dirty positions are resolved, and - when that changed what the host blocks emit - the surrounding
 * world is told.
 *
 * <h2>A solve has three parts</h2>
 * <ol>
 *   <li><b>Dust</b> ({@link #relax}): the wire part of the component is reset to zero and grown to the
 *       least fixed point of {@code value = max(supply, max over sides of what arrives)}, using the
 *       <em>current</em> outputs of the driven components.</li>
 *   <li><b>Devices</b> ({@link #relax} again, second phase): a torch, repeater or comparator whose
 *       output no longer matches its input gets a re-evaluation <em>scheduled a few ticks ahead</em>
 *       rather than changed now. That single decision gives every component its vanilla delay, keeps a
 *       repeater loop ticking at a fixed rate, and - importantly - bounds each tick's work: nothing a
 *       device does can feed back into the same tick's solve.</li>
 *   <li><b>Outwards</b> ({@link #recompute}, and {@link #applyDueFlips} for the scheduled ones): once
 *       the values are final <em>and</em> suppression is lifted, every host whose output changed sends
 *       a vanilla neighbour update, because a stored value is not a block state and nothing else would
 *       wake the lamps, wire and pistons around it.</li>
 * </ol>
 * {@link #settle(ServerLevel)} repeats the first and third parts until the queue stays empty, because
 * notifying the world can bounce straight back into the network.
 */
public final class InnerRedstoneNetwork {

    /**
     * Most resolve rounds per tick.
     *
     * <p>Every round either changes nothing (and stops) or strictly decreases some signal, and signals
     * bottom out at 0, so a round is never wasted work. Anything still queued after this many rounds is
     * left for the next tick, which keeps a pathological build from stalling the server inside one tick.
     */
    private static final int MAX_ROUNDS_PER_TICK = 2 * PowerSolver.MAX_POWER + 2;

    /** Per-level set of positions awaiting recomputation. */
    private static final Map<ResourceKey<Level>, Set<BlockPos>> DIRTY = new HashMap<>();

    /** Per-level set of driven components waiting for their output to follow their input. */
    private static final Map<ResourceKey<Level>, PendingFlips> PENDING = new HashMap<>();

    /**
     * Re-entrancy guard mirroring vanilla's {@code RedStoneWireBlock#shouldSignal}.
     *
     * <p>While a wire evaluates its own target strength it must not report its current power to
     * neighbours, otherwise a wire would charge itself from its own value and the network could never
     * settle. Vanilla flips a field around its {@code getBestNeighborSignal} call; the equivalent here
     * is a level-scoped flag consulted by the signal query in {@code BlockStateSignalMixin}.
     */
    private static final Set<ResourceKey<Level>> SUPPRESS_SIGNAL = new HashSet<>();

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

    /**
     * Queues a button to release itself, and anything else that has to happen at a known later tick.
     *
     * <p>Shares the driven components' queue because it is the same idea: something has to be
     * re-examined once the world has moved on a few ticks.
     */
    public static void scheduleRelease(ServerLevel level, BlockPos pos, int ticks) {
        PENDING.computeIfAbsent(level.dimension(), key -> new PendingFlips())
                .schedule(pos, level.getGameTime() + Math.max(1, ticks));
    }

    /** Resolves everything queued for this level. Called once per server tick. */
    public static void tick(ServerLevel level) {
        InnerRedstoneStore store = InnerRedstoneStore.get(level);
        if (!store.isEmpty()) {
            applyDueFlips(level, store);
        }
        settle(level);
    }

    /**
     * Drains the queue until no further work appears, then returns.
     *
     * <p>One solve is not always the end of the story, because telling the world about it can change
     * the world in a way that comes straight back: a vanilla wire laid against a host block drops a
     * step, and that change is reported back to us synchronously by the very neighbour update we just
     * sent. Repeating the whole solve/notify cycle in the same tick is what makes a source removal
     * visible immediately instead of fading one strength per tick.
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
        PendingFlips pending = PENDING.remove(level.dimension());
        if (pending != null) {
            pending.clear();
        }
    }

    /** True while the given level is evaluating wire power and must not report it to neighbours. */
    public static boolean isSignalSuppressed(Level level) {
        return SUPPRESS_SIGNAL.contains(level.dimension());
    }

    /**
     * Number of driven components waiting for their delayed output, for the debug command.
     */
    public static int pendingCount(ServerLevel level) {
        PendingFlips pending = PENDING.get(level.dimension());
        return pending == null ? 0 : pending.size();
    }

    // ---------------------------------------------------------------- probe --

    /**
     * One direction's worth of {@link #probe}: what the solver sees coming into a host block from
     * that side, and what the vanilla world says without the suppression.
     *
     * @param rawSignal what {@code Level#getSignal} answers with our own components reporting normally
     * @param solverSignal the same query as the solver makes it - with our own signal suppressed, so a
     *     difference between the two is exactly "this value was our own output coming back"
     */
    public record ProbeSide(Direction direction, BlockState block, boolean holdsComponent,
                            int rawSignal, int solverSignal, int innerSignal,
                            boolean reads, boolean emits) {
    }

    /**
     * Explains where a host block's supply comes from, one side at a time.
     *
     * <p>Written for {@code /rc probe} because "something keeps this block powered and I cannot see
     * what" is otherwise unanswerable from the outside. {@code rawSignal} is always vanilla's own
     * answer for that side, while {@code solverSignal} is what the solver actually used; the two
     * differ when the value was our own output coming back (suppressed), or when
     * {@code hostAcceptsStrongPower} is off and the neighbour only <em>carries</em> power rather than
     * emitting it. Either way the line names the block, which is the part that matters.
     */
    public static List<ProbeSide> probe(ServerLevel level, BlockPos host) {
        InnerRedstoneStore store = InnerRedstoneStore.get(level);
        SolverEnvironment env = new SolverEnvironment(level, store);
        env.setQueryPos(host);
        PowerEnvironment.Node view = SlotNode.of(store.slotAt(host));

        List<ProbeSide> sides = new ArrayList<>();
        for (Direction direction : Direction.values()) {
            BlockPos neighbour = host.relative(direction);
            BlockState block = level.getBlockState(neighbour);
            int raw = PowerSolver.clamp(level.getSignal(neighbour, direction));
            int solver = queryingWorld(level, () -> env.externalSignal(direction));
            int inner = PowerSolver.innerFrom(env, host, direction, view);
            sides.add(new ProbeSide(
                    direction,
                    block,
                    store.slotAt(neighbour) != null,
                    raw,
                    solver,
                    inner,
                    view == null || PowerSolver.readsFrom(view, direction),
                    view != null && PowerSolver.emitsToward(view, direction)));
        }
        return sides;
    }

    // ---------------------------------------------------------- propagation --

    /**
     * Re-evaluates the component containing {@code seed}, and tells the world if the result changed.
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

        List<BlockPos> changed = queryingWorld(level, () -> relax(level, store, component));

        // Outwards - and only now. While the solve above was running every inner component reported 0
        // to the world (that is what stops a component charging itself from its own value), so a
        // consumer woken up mid-solve would read that stub and latch onto the wrong state.
        for (BlockPos pos : changed) {
            notifyOutputChanged(level, pos);
        }
        return component;
    }

    /**
     * Runs a query with inner-redstone output suppressed for this level.
     *
     * <p><b>Every</b> signal query the solver makes has to go through here. It is not only about a
     * component charging itself from its own half-computed value: a neighbouring solid block reports
     * the strong power it <em>receives</em>, and a host block is a signal source, so without the
     * suppression a component reads its own output straight back off the stone next door.
     *
     * <p>That is exactly how a repeater used to pin itself on. Its input side faced a solid block, so
     * asking for the signal there returned {@code max(what the block emits, the repeater's own 15
     * transmitted through it)} - a value that never drops, so its input never dropped and the
     * scheduled switch-off was discarded as "computes to the same value".
     */
    private static <T> T queryingWorld(ServerLevel level, java.util.function.Supplier<T> query) {
        boolean alreadySuppressed = !SUPPRESS_SIGNAL.add(level.dimension());
        try {
            return query.get();
        } finally {
            if (!alreadySuppressed) {
                SUPPRESS_SIGNAL.remove(level.dimension());
            }
        }
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

    /**
     * Applies every driven component whose scheduled re-evaluation has come due.
     *
     * <p>The output is decided <em>now</em> rather than remembered from when the re-evaluation was
     * scheduled, which is what vanilla does and what makes a pulse shorter than the delay disappear
     * instead of being stretched by it.
     */
    private static void applyDueFlips(ServerLevel level, InnerRedstoneStore store) {
        PendingFlips pending = PENDING.get(level.dimension());
        if (pending == null || pending.size() == 0) {
            return;
        }
        List<BlockPos> due = pending.takeDue(level.getGameTime());
        if (due.isEmpty()) {
            return;
        }

        SolverEnvironment env = new SolverEnvironment(level, store);
        for (BlockPos pos : due) {
            Slot slot = store.slotAt(pos);
            // A component that was taken out (or replaced) in the meantime simply drops its pending
            // re-evaluation; its replacement starts from its own initial output.
            if (slot == null) {
                continue;
            }

            if (slot.type == ComponentType.BUTTON) {
                releaseButton(level, store, pos, slot);
                continue;
            }
            if (!slot.type.isDriven()) {
                continue;
            }

            env.setQueryPos(pos);
            // Under the same suppression as the solve itself: this query is the solver looking at the
            // world, so it must not see this component's own output coming back at it.
            int desired = queryingWorld(level, () ->
                    PowerSolver.desiredOutput(env, pos, SlotNode.of(slot)));
            if (desired == slot.power) {
                continue;
            }
            RCConfig.LOGGER.info(
                    "[redstonecircuit] {} at {} output {} -> {} (after its delay)",
                    slot.type, pos.toShortString(), slot.power, desired);
            slot.power = desired;
            store.markDirty();
            // The host block emits something else now, and the network around it has to re-settle.
            notifyOutputChanged(level, pos);
            markDirty(level, pos);
        }
    }

    /** A button that has been held down for long enough lets go. */
    private static void releaseButton(ServerLevel level, InnerRedstoneStore store, BlockPos pos,
                                      Slot slot) {
        if (!slot.powered) {
            return;
        }
        slot.powered = false;
        slot.power = 0;
        store.markDirty();
        notifyOutputChanged(level, pos);
        markDirty(level, pos);
        if (RCConfig.debugLog()) {
            RCConfig.LOGGER.info("[redstonecircuit] BUTTON at {} released", pos.toShortString());
        }
    }

    // --------------------------------------------------------------- solver --

    /**
     * Re-derives the component's wire values and schedules any driven component that is stale.
     *
     * @return the positions whose output changed <em>immediately</em> (dust only - a driven component
     *     changes later, at its scheduled tick, and reports itself then)
     */
    private static List<BlockPos> relax(ServerLevel level, InnerRedstoneStore store,
                                        List<BlockPos> component) {
        SolverEnvironment env = new SolverEnvironment(level, store);

        // The wire solve starts from zero, so "did anything change?" has to be answered against where
        // the component was before. Driven components are NOT reset: their output is held until their
        // own scheduled tick replaces it.
        Map<BlockPos, Integer> previous = new HashMap<>(component.size() * 2);
        for (BlockPos pos : component) {
            Slot slot = store.slotAt(pos);
            previous.put(pos, slot == null ? 0 : slot.power);
            if (slot != null && slot.type == ComponentType.DUST) {
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
                int target = PowerSolver.dustTarget(env, pos, supplyOf(env, slot), SlotNode.of(slot));
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
            if (before != slot.power) {
                changedPositions.add(pos);
                env.setQueryPos(pos);
                RCConfig.LOGGER.info(
                        "[redstonecircuit] {} at {} power {} -> {} (supply={}, externalSignal={}, injected={}, from={})",
                        slot.type, pos.toShortString(), before, slot.power, supplyOf(env, slot),
                        env.externalSignal(), slot.injectedPower, env.describeExternalSource());
            }
        }
        if (!changedPositions.isEmpty()) {
            store.markDirty();
        }

        scheduleStaleDevices(level, store, env, component);
        return changedPositions;
    }

    /**
     * Gives every driven component whose output no longer matches its input a delayed re-evaluation.
     *
     * <p>This is the only place a torch, repeater or comparator ever changes, and it never changes
     * here - it changes when the scheduled tick arrives. Vanilla's "do not reschedule while a tick is
     * already pending" rule lives in {@link PendingFlips#schedule}.
     */
    private static void scheduleStaleDevices(ServerLevel level, InnerRedstoneStore store,
                                             SolverEnvironment env, List<BlockPos> component) {
        for (BlockPos pos : component) {
            Slot slot = store.slotAt(pos);
            if (slot == null || !slot.type.isDriven()) {
                continue;
            }
            env.setQueryPos(pos);
            // Already inside {@link #queryingWorld} - this runs from relax() - so the query is
            // suppressed here just like everywhere else the solver looks at the world.
            int desired = PowerSolver.desiredOutput(env, pos, SlotNode.of(slot));
            if (desired == slot.power) {
                continue;
            }
            int delay = slot.type.delayTicks(slot.delay);
            if (delay <= 0) {
                // A driven component with no delay would be a contradiction; treat it as immediate so
                // a future type cannot silently stop working.
                slot.power = desired;
                store.markDirty();
                notifyOutputChanged(level, pos);
                continue;
            }
            PendingFlips pending =
                    PENDING.computeIfAbsent(level.dimension(), key -> new PendingFlips());
            if (pending.schedule(pos, level.getGameTime() + delay) && RCConfig.debugLog()) {
                RCConfig.LOGGER.info(
                        "[redstonecircuit] {} at {} will follow its input in {} tick(s) ({} -> {})",
                        slot.type, pos.toShortString(), delay, slot.power, desired);
            }
        }
    }

    /**
     * The power a piece of dust draws from outside the wire network.
     *
     * <p>{@link PowerEnvironment#externalSignal()} is what the vanilla world pushes into the host
     * block, {@code injectedPower} is a value planted by the debug command or a game test. Both are
     * inputs. A neighbouring component's own power is never one - that is the difference between "this
     * component is fed" and "this component is merely next to something powered".
     */
    private static int supplyOf(PowerEnvironment env, Slot slot) {
        int supply = env.externalSignal();
        if (slot.hasInjectedPower()) {
            supply = Math.max(supply, slot.injectedPower);
        }
        return PowerSolver.clamp(supply);
    }

    /**
     * Collects every component reachable from {@code seed}.
     *
     * <p>Two neighbours are part of the same network when <em>either</em> of them reaches the other, so
     * a lever feeding a repeater that feeds dust is one component, while two repeaters sitting
     * back-to-back are two - each keeps its own value, exactly like vanilla diodes that face away from
     * each other.
     */
    private static List<BlockPos> collectComponent(ServerLevel level, InnerRedstoneStore store,
                                                   BlockPos seed) {
        List<BlockPos> out = new ArrayList<>();
        Set<BlockPos> seen = new HashSet<>();
        Deque<BlockPos> queue = new ArrayDeque<>();
        queue.add(seed.immutable());
        seen.add(seed.immutable());

        while (!queue.isEmpty()) {
            BlockPos pos = queue.poll();
            Slot slot = store.slotAt(pos);
            if (slot == null) {
                continue;
            }
            out.add(pos);

            for (Direction direction : Direction.values()) {
                BlockPos neighbour = pos.relative(direction);
                Slot other = store.slotAt(neighbour);
                if (other == null || !connected(SlotNode.of(slot), SlotNode.of(other), direction)) {
                    continue;
                }
                if (seen.add(neighbour.immutable())) {
                    queue.add(neighbour.immutable());
                }
            }
        }
        return out;
    }

    /** True when the two components talk across the side between them, in either direction. */
    private static boolean connected(PowerEnvironment.Node self, PowerEnvironment.Node other,
                                     Direction direction) {
        return PowerSolver.readsFrom(self, direction)
                        && PowerSolver.emitsToward(other, direction.getOpposite())
                || PowerSolver.readsFrom(other, direction.getOpposite())
                        && PowerSolver.emitsToward(self, direction);
    }

    // ------------------------------------------------------------- env view --

    /** Adapts a level plus the store to the headless {@link PowerEnvironment} used by the solver. */
    private static final class SolverEnvironment implements PowerEnvironment {

        private final ServerLevel level;
        private final InnerRedstoneStore store;

        /** The position currently being solved, needed because the queries here are position-relative. */
        private BlockPos queryPos = BlockPos.ZERO;

        SolverEnvironment(ServerLevel level, InnerRedstoneStore store) {
            this.level = level;
            this.store = store;
        }

        void setQueryPos(BlockPos pos) {
            this.queryPos = pos;
        }

        @Override
        public Node nodeAt(int x, int y, int z) {
            return SlotNode.of(store.slotAt(new BlockPos(x, y, z)));
        }

        @Override
        public int externalSignal() {
            int best = 0;
            for (Direction direction : Direction.values()) {
                best = Math.max(best, externalSignal(direction));
                if (best >= PowerSolver.MAX_POWER) {
                    return PowerSolver.MAX_POWER;
                }
            }
            return best;
        }

        /**
         * Signal the vanilla world pushes into the host block from one side.
         *
         * <p>By default this is vanilla's own query ({@code Level#getSignal(neighbour, direction)}),
         * which for a SOLID neighbour also adds the strong power that neighbour <em>carries</em> - the
         * reason a repeater pushed into a stone block lights the redstone beside it, and also the
         * reason a stone block charged by wiring elsewhere will light the redstone inside the block
         * next to it. {@code hostAcceptsStrongPower = false} drops that half and reads only what the
         * neighbour emits itself.
         *
         * <p>On top of either, a neighbouring <b>vanilla redstone wire</b> is charged one hop, exactly
         * as vanilla charges a hop between two wires in {@code RedStoneWireBlock#calculateTargetStrength}.
         * That charge keeps the system solvable: a wire is the one neighbour that can be powered
         * <em>by</em> the host it powers, so passing it through unattenuated would let the two hold each
         * other up at 15 forever.
         *
         * <p>Inner-redstone output is suppressed for the duration of the solve, so the weak power a
         * neighbouring host block would otherwise transmit reads as 0 here.
         */
        @Override
        public int externalSignal(Direction direction) {
            BlockPos neighbour = queryPos.relative(direction);
            BlockState state = level.getBlockState(neighbour);
            int signal = RCConfig.hostAcceptsStrongPower()
                    ? level.getSignal(neighbour, direction)
                    : state.getSignal(level, neighbour, direction);
            if (signal > 0 && state.is(Blocks.REDSTONE_WIRE)) {
                signal--;
            }
            return PowerSolver.clamp(signal);
        }

        /**
         * Which neighbour is feeding this host from the vanilla world, as text, for the change log.
         *
         * <p>Answers "where did that supply come from" without a second round trip: the value found
         * here is what the solver actually used, so a host pinned on by the block next door names it.
         */
        String describeExternalSource() {
            Direction best = null;
            int bestSignal = 0;
            for (Direction direction : Direction.values()) {
                int signal = externalSignal(direction);
                if (signal > bestSignal) {
                    bestSignal = signal;
                    best = direction;
                }
            }
            if (best == null) {
                return "none";
            }
            BlockPos neighbour = queryPos.relative(best);
            return best.getName() + " " + level.getBlockState(neighbour).getBlock() + "=" + bestSignal;
        }

        @Override
        public boolean isRedstoneConductor(int x, int y, int z) {
            BlockPos pos = new BlockPos(x, y, z);
            return level.getBlockState(pos).isRedstoneConductor(level, pos);
        }
    }
}

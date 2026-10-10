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
import com.jiangyuefengyu.redstonecircuit.compat.physics.PhysicsCompat;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierDirections;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierId;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierProjection;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierStore;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierWorldSignals;
import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.HostEntry;
import com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneNode;
import com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneStore;
import com.jiangyuefengyu.redstonecircuit.data.Slot;
import com.jiangyuefengyu.redstonecircuit.network.HostSync;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.SignalGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import org.jetbrains.annotations.Nullable;

/**
 * Drives power propagation for everything stored inside host blocks: positions a change could affect are
 * marked dirty and resolved at the end of the tick.
 *
 * <p>A solve grows the dust to its least fixed point, schedules a device whose output no longer matches its
 * input a few ticks ahead (its vanilla delay, and why nothing feeds back into the same tick), then sends a
 * vanilla neighbour update from every host whose output changed - a stored value is not a block state, so
 * nothing else wakes the lamps and wire around it. {@link #settle(ServerLevel)} repeats the first and third
 * parts until the queue is empty.
 */
public final class InnerRedstoneNetwork {

    /**
     * Most settle passes per tick; anything still queued waits for the next tick, so a pathological build
     * cannot stall the server inside one tick. Each pass either changes nothing or strictly decreases some
     * signal, and signals bottom out at 0, so no pass is wasted. Public because a test states it as a fact.
     */
    public static final int MAX_ROUNDS_PER_TICK = 2 * PowerSolver.MAX_POWER + 2;

    /**
     * Ceiling on the fixpoint passes one component may need in a single solve: a guard against a pathological
     * wiring order, only reachable by a component far larger than anything a player builds.
     */
    private static final int MAX_FIXPOINT_PASSES = 512;

    /** Per-level set of positions awaiting recomputation. */
    private static final Map<ResourceKey<Level>, Set<BlockPos>> DIRTY = new HashMap<>();

    /** Per-level set of driven components waiting for their output to follow their input. */
    private static final Map<ResourceKey<Level>, PendingFlips> PENDING = new HashMap<>();

    /**
     * How many settle passes the solve running right now has used, and how many components it has recomputed.
     *
     * <p>Per tick rather than per level, and reset at the start of each {@link #tick}: both describe one tick's
     * work. {@link SolverLog} decides when to print them.
     */
    private static int roundsThisTick;
    private static int recomputesThisTick;

    /**
     * Re-entrancy guard mirroring vanilla's {@code RedStoneWireBlock#shouldSignal}.
     *
     * <p>While a wire evaluates its own target strength it must not report its current power to
     * neighbours, otherwise a wire would charge itself from its own value and the network could never
     * settle. Vanilla flips a field around its {@code getBestNeighborSignal} call; the equivalent here
     * is a level-scoped flag consulted by the signal query in {@code BlockStateSignalMixin}.
     */
    private static final Set<ResourceKey<Level>> SUPPRESS_SIGNAL = new HashSet<>();

    /**
     * The one projection a read in progress must not take: a carrier's own host at one world position.
     *
     * <p>Both halves of a merged read go through one accessor, and the projection table answers the vanilla
     * half too, so filtering one half alone would let the same host back in uncharged and a run of carried
     * dust would stay at fifteen however long it was. The refusal covers one carrier and one position only: a
     * carrier's projection anywhere else is the seam between two structures and must keep answering, and the
     * mixin's strong-power question asks about a different position. Server-thread only, saved and restored
     * around the read; the reader is a mixin, which cannot be handed a parameter.
     */
    @Nullable
    private static volatile CarrierId refusedProjectionOwner;

    /** The world position {@link #refusedProjectionOwner} is only refused <em>at</em>. */
    @Nullable
    private static volatile BlockPos refusedProjectionAt;

    /**
     * The receiver the read in progress belongs to, or {@code null} when it names none.
     *
     * <p>A hop is charged to the receiver and only between two wires, which needs the fact a
     * projection-sourced read never had: who is asking. A carried read names it through
     * {@link #seamReader(ComponentType, java.util.function.Supplier)}; every other read leaves this null and is
     * answered by {@link #seamReaderAt(ServerLevel, BlockPos, Direction)}, which takes the asker one step back
     * along the query direction. Server-thread only, saved and restored around the read.
     */
    @Nullable
    private static volatile ComponentType seamReader;

    private InnerRedstoneNetwork() {
    }

    // ------------------------------------------------- only the reader's own projection --

    /**
     * Runs one read with {@code owner}'s projected host at {@code worldPos} refused in both halves of the
     * merged read; re-entrant, so a nested read cannot leak one out of the other.
     */
    public static <T> T refusingOwnProjectionAt(CarrierId owner, BlockPos worldPos,
                                                java.util.function.Supplier<T> read) {
        CarrierId previousOwner = refusedProjectionOwner;
        BlockPos previousAt = refusedProjectionAt;
        refusedProjectionOwner = owner;
        refusedProjectionAt = worldPos;
        try {
            return read.get();
        } finally {
            refusedProjectionOwner = previousOwner;
            refusedProjectionAt = previousAt;
        }
    }

    /**
     * Whether the projection table may answer with {@code found} at {@code at}, for the read running right now:
     * true for everything except the one (carrier, position) pair that read refused. Both halves of the merged
     * read and {@link #projectedSignalAt} come through here, so they cannot disagree about who may answer.
     */
    public static boolean projectionMayAnswer(@Nullable CarrierProjection.ProjectedHost found,
                                              @Nullable BlockPos at) {
        CarrierId owner = refusedProjectionOwner;
        if (owner == null || found == null || at == null || !at.equals(refusedProjectionAt)) {
            return true;
        }
        // The asking carrier's own host pays the hop as an inner neighbour; another carrier's host is the seam.
        return !owner.equals(found.carrier());
    }

    // ------------------------------------------------- the seam's own hop --

    /**
     * Which of our components is doing the reading at a world query, or {@code null} when it names none.
     *
     * <p>A hop is charged to the receiver and only between two wires, so a neighbour that only the projection
     * table knows about must not be handed over as a raw emission - the level sees air where the other block
     * stands and the table would be its only source and charge nothing.
     *
     * <p>A signal query names only the block asked about and the side it is asked from, so the asker is the
     * block one step back along the query direction; a carrier read names its reader outright instead (see the
     * field {@link #seamReader}). This decides nothing about which source may answer, and {@code null} leaves
     * the read unchanged.
     */
    @Nullable
    public static ComponentType seamReaderAt(ServerLevel level, BlockPos worldPos,
                                             Direction worldDirection) {
        if (seamReader != null) {
            return seamReader;
        }
        if (level == null || worldPos == null || worldDirection == null) {
            return null;
        }
        BlockPos asker = worldPos.relative(worldDirection.getOpposite());
        try {
            InnerRedstoneStore store = InnerRedstoneStore.get(level);
            Slot mine = store == null || store.isEmpty() ? null : store.slotAt(asker);
            if (mine != null) {
                return mine.type;
            }
            CarrierProjection.ProjectedHost carried = CarrierStore.projectedHostAt(level, asker);
            return carried == null || carried.slot() == null ? null : carried.slot().type;
        } catch (RuntimeException | LinkageError unreadable) {
            // Must never take a solve down: an unreadable asker answers "not one of ours".
            return null;
        }
    }

    /**
     * Runs one read with {@code reader} named as the receiver, so the projection-sourced half of it can charge
     * that receiver's hop; re-entrant, restored on the way out, so one read's reader cannot leak into the next.
     */
    public static <T> T seamReader(@Nullable ComponentType reader,
                                   java.util.function.Supplier<T> read) {
        ComponentType previous = seamReader;
        seamReader = reader;
        try {
            return read.get();
        } finally {
            seamReader = previous;
        }
    }

    /**
     * The hop a neighbour that only the projection table knows about charges: the receiver pays, and only
     * between two wires. Air, an empty slot or a caller with no identity leave the answer untouched; the value is
     * never zeroed - the seam still answers, it is simply charged.
     */
    public static int seamHop(@Nullable CarrierProjection.ProjectedHost host,
                              @Nullable ComponentType reader, int answer) {
        if (host == null || host.slot() == null) {
            return PowerSolver.clamp(answer);
        }
        return PowerSolver.clamp(PowerSolver.afterHop(reader, host.slot().type, answer));
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
     * Polls a comparator's analogue input, because the world does not always announce a change.
     *
     * <p>A host block is re-solved when a neighbour says something changed, and a block entity that only
     * changes its own state - a kinetic steering wheel is the case this exists for - notifies nobody, so the
     * comparator inside it keeps whatever it read last. Cheap: only a comparator has an analogue input, and a
     * mark-dirty happens only when the answer moved. A comparator riding a machine is read by the carrier
     * layer's own solve instead.
     */
    public static void pollAnalogInputs(ServerLevel level, InnerRedstoneStore store) {
        if (store.isEmpty()) {
            ANALOG_INPUTS.remove(level.dimension());
            return;
        }
        if (Math.floorMod(level.getGameTime(), ANALOG_POLL_TICKS) != 0L) {
            return;
        }
        Map<BlockPos, Integer> last = ANALOG_INPUTS.computeIfAbsent(level.dimension(), key -> new HashMap<>());
        // A component that is gone must not keep a line in the ledger, or this map grows for ever.
        last.keySet().removeIf(pos -> store.slotAt(pos) == null);
        for (BlockPos pos : store.positions()) {
            Slot slot = store.slotAt(pos);
            if (slot == null || slot.type != ComponentType.COMPARATOR) {
                continue;
            }
            int now = readAnalogOutput(level, pos.relative(slot.facing), slot.facing);
            Integer before = last.put(pos, now);
            if (before != null && before != now) {
                markDirty(level, pos);
            }
        }
    }

    /** How often a comparator's analogue input is looked at - one redstone tick. */
    private static final int ANALOG_POLL_TICKS = 2;

    /**
     * The edges of the two devices that answer to <b>time</b> rather than to a notification, for one stored inside a
     * host block: 时钟源 (a function of the world clock) and 信号延长器 (a hold that runs out on the world clock).
     *
     * <p>Everything else here is re-derived when something changes around it; these two are re-derived when time
     * passes, and nothing in the world announces that, so the pass that owns the level asks them once per tick - the
     * only cadence that can catch a clock's two-tick pulse. One comparison per stored component, a write only when the
     * number moved, and the client copy that goes with it is what makes the device look like it is ticking. Either
     * device riding a machine belongs to the carrier's own solve instead.
     */
    public static void tickTimedDevices(ServerLevel level, InnerRedstoneStore store) {
        if (store.isEmpty()) {
            return;
        }
        long now = level.getGameTime();
        for (long key : store.positionKeys()) {
            InnerRedstoneNode node = store.get(key);
            Slot slot = node == null ? null : node.slot();
            if (slot == null) {
                continue;
            }
            int wanted;
            if (slot.type.isTimeDriven()) {
                wanted = slot.clock.answer(now);
            } else if (slot.type == ComponentType.EXTENDER) {
                wanted = advanceExtender(level, BlockPos.of(key), slot, now);
            } else {
                continue;
            }
            if (wanted == slot.power) {
                continue;
            }
            slot.power = wanted;
            store.markDirty();
            BlockPos pos = BlockPos.of(key);
            notifyOutputChanged(level, pos);
            HostSync.broadcast(level, pos, slot);
        }
    }

    /**
     * 信号延长器's own edge: the hold's deadline moves to {@code now + 设定时长} while something is arriving at 正面, and
     * the answer is whatever {@link ExtenderRule} makes of the two.
     *
     * <p>Following the input is the solver's half ({@code PowerSolver#desiredOutput}, combinational, not one tick late);
     * this is the half that needs a clock, and the write the caller does with the answer is how a hold ends.
     */
    private static int advanceExtender(ServerLevel level, BlockPos host, Slot slot, long now) {
        int input = extenderInput(level, host, slot);
        slot.extenderDeadline = ExtenderRule.deadline(now, input, slot.extender.holdTicks(),
                slot.extenderDeadline);
        return ExtenderRule.answer(now, input, slot.power, slot.extenderDeadline);
    }

    /**
     * What is arriving at a stored extender's 正面: the number the last solve read when there is one, and a world read
     * otherwise.
     *
     * <p>The recorded one first because a component inside a machine is read through the structure's own view, which this
     * level's own coordinates cannot reproduce; {@code lastInput < 0} means no solve has read it yet, and then the host
     * is an ordinary block in this world and the neighbour is the honest answer.
     */
    private static int extenderInput(ServerLevel level, BlockPos host, Slot slot) {
        if (slot.lastInput >= 0) {
            return PowerSolver.clamp(slot.lastInput);
        }
        BlockPos neighbour = host.relative(slot.facing);
        return level.isLoaded(neighbour) ? readSignal(level, neighbour, slot.facing) : 0;
    }

    /** What each comparator was last seen reading, per level and host position. */
    private static final Map<ResourceKey<Level>, Map<BlockPos, Integer>> ANALOG_INPUTS = new HashMap<>();

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
        // Holding the notifications until this method ends keeps them from re-entering the solve below; the flush still lands inside this tick.
        SolverNotifications.Session session = SolverNotifications.begin(level);
        int roundsBefore = recomputesThisTick;
        try {
            InnerRedstoneStore store = InnerRedstoneStore.get(level);
            if (!store.isEmpty()) {
                // 时钟源 and 信号延长器 first - nothing notifies them at the right moment (one is a function of the world
                // clock, the other's hold runs out on it), so this pass turns them over.
                tickTimedDevices(level, store);
                applyDueFlips(level, store);
            }
            settle(level);
        } finally {
            if (session != null) {
                session.note(roundsThisTick, recomputesThisTick - roundsBefore);
            }
            SolverNotifications.end(level);
            recomputesThisTick = 0;
            roundsThisTick = 0;
        }
    }

    /**
     * Drains the queue until no further work appears, then returns.
     *
     * <p>One solve is not the end of the story, because telling the world about it can change the world: a
     * vanilla wire laid against a host block drops a step, and a consumer that was not looking has to be woken.
     * That is also why the notifications are collected rather than sent while this loop runs
     * ({@link SolverNotifications}) - sent now they re-enter this very method through
     * {@code NeighborNotifyEvent}, and the two halves of one circuit re-derive from each other until
     * {@link #MAX_ROUNDS_PER_TICK} stops them.
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
                roundsThisTick += round;
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
        roundsThisTick += round;
    }

    /**
     * Resolves the queue for a caller that is not a tick - a command, a test - and sends its
     * notifications before returning.
     *
     * <p>{@link #settle(ServerLevel)} leaves the notifications queued for the tick boundary, which is
     * right for a tick and wrong for an operator waiting on the result of a command. This is the same
     * solve with the same coalescing (so it cannot spin either), with the flush at the end of <em>this
     * call</em> instead of at the end of the tick.
     */
    public static void settleNow(ServerLevel level) {
        SolverNotifications.Session session = SolverNotifications.begin(level);
        int roundsBefore = recomputesThisTick;
        try {
            settle(level);
        } finally {
            if (session != null) {
                session.note(roundsThisTick, recomputesThisTick - roundsBefore);
            }
            SolverNotifications.end(level);
            recomputesThisTick = 0;
            roundsThisTick = 0;
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
        // The level the projected-host log throttle last wrote for is going away, so the next level's first
        // line is not suppressed by a comparison with this one's.
        projectedLoggedTick = Long.MIN_VALUE;
    }

    /** True while the given level is evaluating wire power and must not report it to neighbours. */
    public static boolean isSignalSuppressed(Level level) {
        return SUPPRESS_SIGNAL.contains(level.dimension());
    }

    /**
     * True while <b>any</b> level is evaluating wire power.
     *
     * <p>Asked on the hottest path in the game, which is why it is a set-emptiness test, and its meaning is
     * dimension-free: "a re-entrant solve owns this query" is a fact about the call stack, not about which
     * level is named.
     */
    public static boolean isAnySignalSuppressed() {
        return !SUPPRESS_SIGNAL.isEmpty();
    }

    /** Number of driven components waiting for their delayed output, for the debug command. */
    public static int pendingCount(ServerLevel level) {
        PendingFlips pending = PENDING.get(level.dimension());
        return pending == null ? 0 : pending.size();
    }

    // ---------------------------------------------------------------- probe --

    /**
     * One direction's worth of {@link #probe}: what the solver sees coming into a host block from that side,
     * and what the vanilla world says without our own signal suppressed - a difference between the two is
     * exactly "this value was our own output coming back".
     */
    public record ProbeSide(Direction direction, BlockState block, boolean holdsComponent,
                            int rawSignal, int solverSignal, int innerSignal,
                            boolean reads, boolean emits) {
    }

    /**
     * Explains where a host block's supply comes from, one side at a time, for {@code /rc probe}:
     * {@code rawSignal} is vanilla's own answer for that side and {@code solverSignal} is what the solver
     * used, so the two differ when the value was our own output coming back, or when strong power is refused
     * and the neighbour only carries power.
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
        recomputesThisTick++;
        if (RCConfig.debugLog()) {
            // The recompute, counted by its seed - the spin warning's evidence comes from these lines.
            SolverLog.recompute(seed);
            if (SolverLog.verbose()) {
                RCConfig.LOGGER.info("[redstonecircuit] recompute seed={} component={}",
                        seed.toShortString(), component);
            }
        }

        List<BlockPos> changed = queryingWorld(level, () -> relax(level, store, component));

        // Outwards, and only now: the solve reported 0 to the world, so a consumer woken mid-solve would latch onto the wrong state.
        for (BlockPos pos : changed) {
            notifyOutputChanged(level, pos);
        }
        return component;
    }

    /**
     * Runs a query with inner-redstone output suppressed for this level.
     *
     * <p><b>Every</b> signal query the solver makes goes through here, and not only to stop a component
     * charging itself from its own half-computed value: a host block is a signal source, so a component would
     * otherwise read its own output straight back off the solid block next door, and a repeater's input would
     * never drop. Public because the carrier bridge reads the world through it too, for the same reason.
     */
    public static <T> T queryingWorld(ServerLevel level, java.util.function.Supplier<T> query) {
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
     * Tells the world that the signal emitted by the host block at {@code pos} may have changed, through
     * {@code updateNeighborsAt} - the same path vanilla uses.
     *
     * <p><b>Not necessarily now</b>: while a solve is running ({@link SolverNotifications#begin}) the
     * notification is collected and sent once, when that solve has reached its fixed point, because this call
     * is re-entrant through {@code NeighborNotifyEvent} and a re-entrant notification lets the circuit being
     * solved react to its own half-finished answer. Outside a solve it goes out immediately.
     */
    public static void notifyOutputChanged(ServerLevel level, BlockPos pos) {
        SolverNotifications.offer(level, pos);
    }

    /**
     * The notification itself, with no coalescing in the way; reached only through
     * {@link SolverNotifications}, which decides between "now" and "at the end of the solve".
     *
     * <p>The component is read from the <b>world store</b>, where the position being notified lives; a
     * structure's own coordinate is notified through {@link #notifyNeighboursNow} instead, because the
     * component there is the carried record and not a world entry.
     */
    public static void notifyOutputChangedNow(ServerLevel level, BlockPos pos) {
        notifyNeighboursNow(level, pos, InnerRedstoneStore.get(level).slotAt(pos));
    }

    /**
     * The one neighbour notification, with the component named outright: the vanilla {@code updateNeighborsAt}
     * at {@code pos}, and for a diode the extra step its front block needs, since none of that block's
     * neighbours is a neighbour of the diode.
     *
     * <p>The component is a parameter rather than a store lookup because a position can hold one in two places
     * - the world store, and the carried index at a structure's own coordinate - and the carrier path already
     * has the record in hand.
     */
    public static void notifyNeighboursNow(ServerLevel level, BlockPos pos, @Nullable Slot component) {
        BlockState state = level.getBlockState(pos);
        Block source = state.getBlock();
        level.updateNeighborsAt(pos, source);
        // The shape-update half an observer needs: a host block's state never changes, so an observer sees nothing without it.
        state.updateNeighbourShapes(level, pos, Block.UPDATE_ALL);

        Slot slot = component;
        if (slot == null || !slot.type.isDiode()) {
            return;
        }
        Direction output = slot.facing.getOpposite();
        BlockPos front = pos.relative(output);
        level.neighborChanged(front, source, pos);
        // Everything around the charged block, except back towards the diode - which was just notified.
        level.updateNeighborsAtExceptFromFacing(front, source, output.getOpposite());
    }

    /**
     * Applies every driven component whose scheduled re-evaluation has come due.
     *
     * <p>The output is decided now rather than remembered from when the re-evaluation was scheduled, which is
     * what vanilla does and what makes a pulse shorter than the delay disappear instead of being stretched.
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
            if (!slot.type.hasInputSide()) {
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
            // Counted always, printed only when asked: the per-step line is far too frequent to log unconditionally.
            SolverLog.componentChanged();
            if (SolverLog.verbose()) {
                RCConfig.LOGGER.info(
                        "[redstonecircuit] {} at {} output {} -> {} (after its delay)",
                        slot.type, pos.toShortString(), slot.power, desired);
            }
            slot.power = desired;
            store.markDirty();
            // The host block emits something else now, and the network around it has to re-settle.
            notifyOutputChanged(level, pos);
            markDirty(level, pos);
            HostSync.broadcast(level, pos, slot);
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
        HostSync.broadcast(level, pos, slot);
        if (RCConfig.debugLog()) {
            RCConfig.LOGGER.info("[redstonecircuit] BUTTON at {} released", pos.toShortString());
        }
    }

    // --------------------------------------------------------------- solver --

    /**
     * The one implementation of "read a world signal at a world position, from one side", shared by the world
     * solver and the carrier bridge so the two cannot drift apart.
     *
     * <p>{@code hostAcceptsStrongPower = false} narrows the read to what the neighbour emits itself; a
     * neighbouring vanilla redstone wire is charged one hop, as vanilla charges a hop between two wires, and
     * that charge is what keeps the system solvable, because a wire is the one neighbour that can be powered by
     * the block it powers. {@code direction} is vanilla's, from the block asking towards the neighbour.
     */
    public static int readSignal(BlockGetter blocks, BlockPos pos, Direction direction) {
        BlockState state = blocks.getBlockState(pos);
        int signal;
        if (RCConfig.hostAcceptsStrongPower() && !holdsComponentAt(blocks, pos)
                && blocks instanceof SignalGetter signals) {
            // The strong power a solid neighbour carries is part of what a host block accepts, exactly
            // as it was read before this method was extracted - for a block that really is a block of
            // stone. A block that holds a component of ours is that component and not stone, so the
            // strong power vanilla attributes to it is not read; see holdsComponentAt.
            signal = signals.getSignal(pos, direction);
        } else {
            // No signal getter, strong power refused, or the neighbour is one of our hosts: only what the block
            // emits itself. A view that cannot answer a state at all (a structure that has just come apart) is
            // "no signal" rather than an exception, so one unreadable coordinate cannot take the solve down.
            signal = state == null ? 0 : state.getSignal(blocks, pos, direction);
        }
        return neighbourSignal(signal, state != null && state.is(Blocks.REDSTONE_WIRE));
    }

    /**
     * Vanilla's own "what does this block offer a comparator" question: a comparator's back input is not a
     * redstone signal but the neighbour's analogue output (a cake's bites, a cauldron's level, a chest), and it
     * is additive to whatever redstone signal the same neighbour also pushes. {@code direction} points from the
     * asking block towards the neighbour, vanilla's own convention.
     *
     * <p>Read where the block is <em>drawn</em>: a physicalised machine keeps its blocks in a reserved plot, so
     * the world position beside the host holds air and answers 0, while the block drawn there answers this
     * question. The world's own answer is asked first and is never overridden.
     */
    public static int readAnalogOutput(Level level, BlockPos pos, @Nullable Direction direction) {
        BlockState state = level.getBlockState(pos);
        int world = state == null ? 0 : state.getAnalogOutputSignal(level, pos);
        // Asked about one side it is a different question, and a block may implement only that form. The larger
        // of the two answers is what this neighbour offers.
        int directional = DirectionalAnalog.analogOutputFrom(state, level, pos, direction);
        int here = Math.max(world, directional);
        int drawn = here > 0 || !(level instanceof ServerLevel server)
                ? 0
                : PhysicsCompat.carriedAnalogAt(server, pos, direction);
        // A read of 0 is two facts - nothing there, or a block that answers per side - so the probe names the
        // block, its facing and all three answers.
        if (RCConfig.debugLog() && DirectionalAnalog.answersDirectionally(state == null ? null : state.getBlock())
                && level.getGameTime() - lastAnalogProbeLog >= ANALOG_PROBE_TICKS) {
            lastAnalogProbeLog = level.getGameTime();
            RCConfig.LOGGER.info("[redstonecircuit] a comparator input asked {} ({}) about {}: the plain analogue "
                            + "question answers {}, the directional one {}, and a machine-drawn block there {}{}",
                    pos.toShortString(), describeNeighbour(state, direction), direction, world, directional, drawn,
                    describeBlockEntity(level, pos));
        }
        if (drawn > 0 && RCConfig.debugLog() && level.getGameTime() - lastCarriedAnalogLog >= ANALOG_LOG_TICKS) {
            lastCarriedAnalogLog = level.getGameTime();
            RCConfig.LOGGER.info("[redstonecircuit] a comparator read {} at {}, where the world answers nothing: the "
                            + "block drawn there by a machine is what answered", drawn, pos.toShortString());
        }
        return Math.max(here, drawn);
    }

    /**
     * A neighbour in one phrase - its block, the way it faces, and whether that is the side we asked about.
     *
     * <p>A block whose analogue output depends on the side it is asked about answers something different from
     * the side it faces, so the hint says what to look at rather than what the block means.
     */
    private static String describeNeighbour(@Nullable BlockState state, @Nullable Direction asked) {
        if (state == null) {
            return "nothing";
        }
        if (state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING)) {
            net.minecraft.core.Direction facing = state.getValue(
                    net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING);
            String hint = asked == null ? ""
                    : facing == asked
                            ? " - asked about the side it faces, and a block that answers per side answers something "
                                    + "else from any other one"
                            : " - asked about a side it does not face";
            return state.getBlock() + " facing " + facing + hint;
        }
        return state.getBlock().toString();
    }

    /**
     * The block entity behind the neighbour, in its own words, beside the answers above.
     *
     * <p>Two readings of the same question and direction can still differ if they were taken at different
     * moments, so this dumps the neighbour's saved state: the log then says whether the block was held or
     * turned at the time. Empty for a block with no block entity, and throttled with the line it belongs to.
     */
    private static String describeBlockEntity(Level level, BlockPos pos) {
        net.minecraft.world.level.block.entity.BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity == null) {
            return "";
        }
        String tag;
        try {
            tag = blockEntity.saveWithoutMetadata(level.registryAccess()).toString();
        } catch (RuntimeException | LinkageError failed) {
            tag = "unreadable (" + failed.getClass().getSimpleName() + ")";
        }
        if (tag.length() > 240) {
            tag = tag.substring(0, 240) + "...";
        }
        return " | its own state: " + tag;
    }

    /** How often a neighbour that answers this question differently per side reports all three answers. */
    private static final int ANALOG_PROBE_TICKS = 20;

    /** The game time of the last such line. */
    private static long lastAnalogProbeLog = Long.MIN_VALUE / 2;

    /** How often the carried analogue fallback says that it was the one that answered. */
    private static final int ANALOG_LOG_TICKS = 20;

    /** The game time of the last such line, so a machine being watched cannot flood the log. */
    private static long lastCarriedAnalogLog = Long.MIN_VALUE / 2;

    /**
     * Whether the block at this coordinate is a component of ours rather than a block of stone.
     *
     * <p>The answering side already had this rule - a host block may not charge a neighbouring component with
     * strong power, because the block <em>is</em> the component - but the reading side did not, and that is
     * where a feedback loop lived: our host's emission makes an outer dust worth 15, the dust's strong power
     * charges the conducting block beside it, that block answers 15 to everything, and the solve read it as a
     * supply. Read as the component it holds, that return edge is gone; only a coordinate that holds one is
     * affected, so a plain strongly powered block still feeds the component beside it.
     */
    private static boolean holdsComponentAt(BlockGetter blocks, BlockPos pos) {
        if (!(blocks instanceof ServerLevel level)) {
            return false;
        }
        try {
            InnerRedstoneStore store = InnerRedstoneStore.get(level);
            // isEmpty() first: the cheapest guard, and a level with no components is the common case here.
            return store != null && !store.isEmpty() && store.slotAt(pos) != null;
        } catch (RuntimeException | LinkageError unreadable) {
            // Must never take a solve down: an unreadable store answers "no component here".
            return false;
        }
    }

    /**
     * The arithmetic half of the shared rule, on its own so it can be pinned by a test: a powered vanilla
     * redstone wire offers one step less to whatever it feeds, and everything else is passed through and
     * clamped.
     */
    public static int neighbourSignal(int rawSignal, boolean vanillaWire) {
        int signal = rawSignal;
        if (signal > 0 && vanillaWire) {
            signal--;
        }
        return PowerSolver.clamp(signal);
    }

    /**
     * The bridge one carrier reads the world through: the vanilla world block, or a host another structure is
     * carrying at that position - whichever offers more.
     *
     * <p>Two blocks each physicalised on its own and a world cell apart: the position one is drawn on holds
     * <b>air</b> in the level, so the vanilla half answers 0 and only the projection table knows a full-power
     * dust stands there. The larger answer wins, the same comparison the world-side solve has always made.
     *
     * <p>One boolean and, only when it is on, one hash lookup; a failure inside the carrier half answers zero,
     * so a broken bridge looks like a jar without it.
     */
    public static CarrierWorldSignals worldSignals(ServerLevel level) {
        return (worldPos, worldDirection) -> queryingWorld(level,
                () -> worldSignalAt(level, worldPos, worldDirection));
    }

    /**
     * One world reading from both sources, for a caller already inside the suppression window: the vanilla
     * world block, or a host a structure is carrying there. Level-taking so the carrier half and the world-side
     * solve are provably one implementation.
     */
    public static int worldSignalAt(ServerLevel level, BlockPos worldPos, Direction worldDirection) {
        int vanilla = readSignal(level, worldPos, worldDirection);
        int projected = projectedSignalAt(level, worldPos, worldDirection);
        if (projected > vanilla) {
            logProjectedSignal(level, worldPos, worldDirection, projected, vanilla);
            return projected;
        }
        return vanilla;
    }

    /**
     * The same read, with one carrier's own projection refused.
     *
     * <p>The projected half is a raw emission that charges no hop: right for "a component of somebody else is
     * standing where the level sees air", wrong for a host of the solve that is asking, which already arrives
     * through the inner-neighbour relationship that charges the hop - letting the projection answer too won the
     * {@code max} uncharged, and a run of carried dust stopped fading. Another carrier's host is the seam and
     * must keep answering, and the refusal covers both halves of the read because the vanilla half is answered
     * from the same table.
     */
    public static int worldSignalAt(ServerLevel level, BlockPos worldPos, Direction worldDirection,
                                    @Nullable CarrierId owner) {
        if (owner == null) {
            int vanilla = readSignal(level, worldPos, worldDirection);
            int projected = projectedSignalAt(level, worldPos, worldDirection, owner);
            if (projected > vanilla) {
                logProjectedSignal(level, worldPos, worldDirection, projected, vanilla);
                return projected;
            }
            return vanilla;
        }
        // The refusal covers the whole read: a level read lands in the mixin, whose second source is the same table.
        return refusingOwnProjectionAt(owner, worldPos, () -> {
            int vanilla = readSignal(level, worldPos, worldDirection);
            int projected = projectedSignalAt(level, worldPos, worldDirection, owner);
            if (projected > vanilla) {
                logProjectedSignal(level, worldPos, worldDirection, projected, vanilla);
                return projected;
            }
            return vanilla;
        });
    }

    /**
     * Whether a host the projection table holds may be read as an external supply by one carrier: no for the
     * reader's own hosts, yes for every other structure's.
     *
     * <p>The reader's own neighbour is a node of the solve that is asking and already arrives through the
     * inner-neighbour relationship, which charges the hop, so taking the projection's raw emission as well
     * would cancel that charge; a host another structure carries is the seam, and the only route by which two
     * independently physicalised blocks can read each other at all. Split out as a static question of two values
     * so the rule is pinned offline.
     */
    public static boolean projectionReadableBy(@Nullable CarrierProjection.ProjectedHost found,
                                               @Nullable CarrierId owner) {
        return CarrierProjection.readableBy(found, owner);
    }

    /**
     * The signal a host a structure is carrying offers at this world position, on this side, or 0.
     *
     * <p>Delegated to {@link CarrierProjection#emittedToward}, the same rule the mixin answers vanilla's own
     * query with; weak power, because a read asks what a neighbour <em>emits</em>. With the bridge frozen there
     * is no projection table with anything in it, so this answers zero.
     */
    public static int projectedSignalAt(ServerLevel level, BlockPos worldPos, Direction worldDirection) {
        return projectedSignalAt(level, worldPos, worldDirection, null);
    }

    /**
     * The same, with one carrier's own hosts refused; see
     * {@link #worldSignalAt(ServerLevel, BlockPos, Direction, CarrierId)}.
     *
     * <p>One filter on the host this read already fetched, so the carrier bridge and the world-side solve still
     * share one implementation of "what does a projected host emit that way".
     */
    public static int projectedSignalAt(ServerLevel level, BlockPos worldPos, Direction worldDirection,
                                        @Nullable CarrierId owner) {
        if (!RCConfig.carrierWorldReadsProjection() || level == null || worldPos == null
                || worldDirection == null) {
            return 0;
        }
        try {
            CarrierProjection.ProjectedHost host = CarrierStore.projectedHostAt(level, worldPos);
            if (!projectionReadableBy(host, owner)) {
                return 0;
            }
            int answer = CarrierProjection.emittedToward(host, worldDirection, false, false);
            // The receiver pays the hop here: a projected answer is otherwise a raw, uncharged emission.
            return seamHop(host, seamReaderAt(level, worldPos, worldDirection), answer);
        } catch (RuntimeException | LinkageError failed) {
            // The carrier bridge is a bonus: a failure in it must look like a jar without it, not a broken solve.
            return 0;
        }
    }

    /**
     * One line the first time a projected host feeds a solve in a tick - the projection table answered for a
     * position the level itself sees as air.
     *
     * <p>Once per tick rather than once per pass, enforced on the tick the level reports, so two callers cannot
     * each print their own; the value is re-read only after the line is known to be written, so the common path
     * allocates nothing. Gated on {@code debugLog} like every other per-change line.
     */
    public static void logProjectedSignal(ServerLevel level, BlockPos worldPos,
                                          Direction worldDirection, int projected, int vanilla) {
        if (!RCConfig.debugLog()) {
            return;
        }
        long tick = level.getGameTime();
        if (projectedLoggedTick == tick) {
            return;
        }
        projectedLoggedTick = tick;
        CarrierProjection.ProjectedHost host;
        try {
            host = CarrierStore.projectedHostAt(level, worldPos);
        } catch (RuntimeException | LinkageError failed) {
            host = null;
        }
        if (host == null) {
            return;
        }
        RCConfig.LOGGER.info("[redstonecircuit] world solve at {} side={} <- carrier {} local={} "
                        + "localSide={} signal {} (vanilla said {}): the host riding that structure "
                        + "is feeding the redstone inside the block next to it",
                worldPos.toShortString(), worldDirection.getName(), host.carrier().shortId(),
                host.localPos().toShortString(),
                CarrierDirections.localDirection(host.pose(), worldDirection.getOpposite()).getName(),
                projected, vanilla);
    }

    /**
     * The tick {@link #logProjectedSignal} last wrote for, or {@code Long.MIN_VALUE}; one long, so a level tick
     * cannot produce two of these lines however many structures read a projection in it.
     */
    private static long projectedLoggedTick = Long.MIN_VALUE;

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
            if (slot != null && slot.type.isWire()) {
                slot.power = 0;
            }
        }

        // A wire's value only ever rises and is capped at fifteen, so this loop always terminates; the
        // bound is a performance guard, not a correctness one. It is sized by the component because
        // superconducting dust does not fade: a run of it can be far longer than the fifteen blocks an
        // ordinary signal survives, and a small fixed bound would leave the far end of such a run dark
        // until the next solve.
        int maxPasses = 2 + Math.min(component.size(), MAX_FIXPOINT_PASSES);
        int pass = 0;
        boolean changed = true;
        while (changed) {
            if (pass++ > maxPasses) {
                RCConfig.LOGGER.warn(
                        "[redstonecircuit] inner redstone network did not settle after {} passes over {} block(s)",
                        maxPasses, component.size());
                break;
            }
            changed = false;
            for (BlockPos pos : component) {
                Slot slot = store.slotAt(pos);
                if (slot == null || !slot.type.isWire()) {
                    continue;
                }
                env.setQueryPos(pos);
                int target = PowerSolver.wireTarget(env, pos, supplyOf(env, slot), SlotNode.of(slot));
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
                // A wire being re-solved repeatedly fires this once per power step: counted always, printed only when asked.
                SolverLog.componentChanged();
                if (SolverLog.verbose()) {
                    RCConfig.LOGGER.info(
                            "[redstonecircuit] {} at {} power {} -> {} (supply={}, externalSignal={}, injected={}, from={})",
                            slot.type, pos.toShortString(), before, slot.power, supplyOf(env, slot),
                            env.externalSignal(), slot.injectedPower, env.describeExternalSource());
                }
            }
        }
        if (!changedPositions.isEmpty()) {
            store.markDirty();
            // Tell clients once for the whole component: the drawing shows where power actually is,
            // and a wire that just dropped several steps is one visible change, not several.
            HostSync.broadcast(level, entriesOf(store, changedPositions));
        }

        scheduleStaleDevices(level, store, env, component);
        return changedPositions;
    }

    /**
     * Copies the named positions' current contents into wire form.
     *
     * <p>Copied rather than referenced: the solver keeps mutating its slots, and a packet must carry
     * the value as it was at this moment.
     */
    private static List<HostEntry> entriesOf(InnerRedstoneStore store, List<BlockPos> positions) {
        List<HostEntry> entries = new ArrayList<>(positions.size());
        for (BlockPos pos : positions) {
            Slot slot = store.slotAt(pos);
            if (slot != null) {
                entries.add(new HostEntry(pos.immutable(), slot.copy()));
            }
        }
        return entries;
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
            // hasInputSide, not isDriven: a controller's output follows its input like a repeater's, just with no delay of its own.
            if (slot == null || !slot.type.hasInputSide()) {
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
                HostSync.broadcast(level, pos, slot);
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

        /** The one world-signal reader; see {@link InnerRedstoneNetwork#worldSignals}. */
        private final CarrierWorldSignals world;

        /** The position currently being solved, needed because the queries here are position-relative. */
        private BlockPos queryPos = BlockPos.ZERO;

        SolverEnvironment(ServerLevel level, InnerRedstoneStore store) {
            this.level = level;
            this.store = store;
            this.world = InnerRedstoneNetwork.worldSignals(level);
        }

        void setQueryPos(BlockPos pos) {
            this.queryPos = pos;
        }

        @Override
        public void setQueryPosition(BlockPos pos) {
            setQueryPos(pos);
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
         *
         * <p>A host riding a structure in that position is read here too ({@link #projectedSignalAt}), with the
         * same rule and the same direction conversion the mixin uses: the two answers are combined as the larger,
         * and the direct read can only agree with or exceed the mixin's. This method and {@link #worldSignals}
         * are the two callers of that same pair, so a component beside a machine reads it identically whether it
         * sits in an ordinary world block or inside another structure.
         */
        @Override
        public int externalSignal(Direction direction) {
            // The same implementation the carrier bridge calls, under the same suppression, so the two readers cannot drift apart.
            BlockPos neighbour = queryPos.relative(direction);
            int vanilla = world.externalSignal(neighbour, direction);
            // One hash lookup, gated by one boolean: see projectedSignalAt.
            int projected = projectedSignalAt(level, neighbour, direction);
            if (projected > vanilla) {
                logProjectedSignal(level, neighbour, direction, projected, vanilla);
                return projected;
            }
            return vanilla;
        }

        /**
         * <b>The analogue half of a comparator's back input: what the world block on that side offers.</b>
         *
         * <p>Read straight from the level at the neighbour's own coordinates - no projection, no
         * suppression, no hop charge. None of those belong here: this is not a redstone signal that a
         * component of ours could be answering (a block holding one of our components is a plain block to
         * vanilla and answers 0), and vanilla itself charges nothing for it. It is the cake, the cauldron,
         * the chest and the hopper, read exactly as the game reads them.
         */
        @Override
        public int analogOutput(Direction direction) {
            return readAnalogOutput(level, queryPos.relative(direction), direction);
        }

        /**
         * Which neighbour is feeding this host from the vanilla world, as text, for the change log: the value
         * found here is what the solver actually used, so a host pinned on by the block next door names it.
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

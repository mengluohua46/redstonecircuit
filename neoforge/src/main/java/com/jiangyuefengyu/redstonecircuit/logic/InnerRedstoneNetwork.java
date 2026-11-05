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
import com.jiangyuefengyu.redstonecircuit.block.SuperconductingWireBlock;
import com.jiangyuefengyu.redstonecircuit.compat.physics.PhysicsCompat;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierDirections;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierId;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierPose;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierProjection;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierStore;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierWorldSignals;
import com.jiangyuefengyu.redstonecircuit.data.ComparatorMode;
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
import net.minecraft.world.level.block.DiodeBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.ticks.TickPriority;

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
     * Most cascades one tick may run, whatever {@code solverCascadeLimit} says: the config range's upper bound,
     * stated here because the backoff is bounded by it and a unit test asserts the arithmetic.
     */
    public static final int MAX_CASCADE_LIMIT = 32;

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
     * Per-level cascade state left by the tick before: whether it converged, and the budget it ran with.
     *
     * <p>The backoff is built in rather than configurable, and it exists because "the world answered with more
     * work" is not the same as "the machine is slow": a clock that genuinely oscillates inside one tick never
     * converges, so it would spend the whole budget every tick for ever. Halving its budget puts it back to
     * today's cost within a tick or two, while a machine that does converge keeps the whole budget every tick.
     */
    private static final Map<ResourceKey<Level>, CascadeState> CASCADES = new HashMap<>();

    /** One tick's cascade state: did it converge, and what budget did it run with. */
    private record CascadeState(boolean converged, int budget) {
    }

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
    /**
     * The cells of the network doing the read in progress, or {@code null} when it names none.
     *
     * <p>What a lossless pair must leave out of the partner's own supply is the reader's <b>whole network</b>, not
     * the one cell the read is made from: a network can bite the same partner from several cells, and excluding one
     * of them only removes the smallest loop. Server-thread only, saved and restored around the read by
     * {@link #readingFor}, for the same reason {@link #seamReader} is: the reader is a recalculation a mixin starts,
     * which cannot be handed a parameter.
     */
    @Nullable
    private static Set<BlockPos> readerNetwork;

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

    /**
     * 红石触发器's one writer: every stored trigger is asked what its 正面 says, once per tick, and a rising edge flips it.
     *
     * <p>A pass of its own rather than a branch of the solver, for the reason 时钟源 has one: the solver answers "what
     * should this component hold <em>now</em>", which is a function of what is readable at that moment - while a
     * trigger's answer is the opposite of what it held <b>before the input last rose</b>. That needs the previous
     * reading, so the device is stepped, not derived (see {@code TriggerRule} and {@code ComponentType#TRIGGER}).
     *
     * <p>Once per tick is the whole of what a pulse can be: an input that rises and falls inside one tick never exists as
     * a reading. And a second call of this pass in the same tick would change nothing anyway - {@link TriggerRule#step}
     * is idempotent, because the first step stored the reading it saw, so the second one steps from an input equal to its
     * own and holds the switch. Nothing here has to remember "already flipped this tick".
     *
     * <p>A component riding a machine is not covered: its tick walk is the carrier's ({@code CarrierNetworks}), and this
     * pass reads this level's own store.
     */
    private static void tickTriggers(ServerLevel level, InnerRedstoneStore store) {
        if (store.isEmpty()) {
            return;
        }
        for (long key : store.positionKeys()) {
            InnerRedstoneNode node = store.get(key);
            Slot slot = node == null ? null : node.slot();
            if (slot == null || slot.type != ComponentType.TRIGGER) {
                continue;
            }
            boolean input = TriggerRule.live(triggerReading(level, BlockPos.of(key), slot));
            TriggerRule.Step step = TriggerRule.step(slot.triggerInput, input, slot.powered);
            int before = slot.power;
            boolean readingMoved = step.input() != slot.triggerInput;
            slot.powered = step.powered();
            slot.triggerInput = step.input();
            // The strength is the switch, always: this also repairs a slot whose two fields disagree (a save written by
            // hand, or a component that changed type in place) without a second rule saying which of them wins.
            slot.power = TriggerRule.output(slot.powered);
            if (slot.power != before || readingMoved) {
                // Marked dirty even when only the reading moved: the reading is saved, and a save that lost it would turn
                // the next hold into a rising edge.
                store.markDirty();
            }
            if (slot.power != before) {
                // Only the answer moving is worth telling the world: the reading itself is nobody else's business.
                notifyOutputChanged(level, BlockPos.of(key));
                markDirty(level, BlockPos.of(key));
                HostSync.broadcast(level, BlockPos.of(key), slot);
            }
        }
    }

    /**
     * What a stored trigger's 正面 says right now - read from the world, in this tick.
     *
     * <p>Deliberately <b>not</b> the number the last solve recorded ({@code Slot#lastInput}), which is what 信号延长器
     * reads: this pass runs <em>before</em> the solve in the same tick, so a reading taken from the last solve would be
     * one tick behind - and one tick behind is enough to lose a rising edge for ever. The rise queues a solve, the solve
     * then records the new reading in {@code lastInput}, and a pass that had compared the <em>old</em> reading against
     * itself afterwards would never see the edge at all. 信号延长器 may read the recorded number because its input follows
     * combinationally inside the solve itself; a trigger's answer does not.
     *
     * <p>An unloaded neighbour falls back to that recorded number, which is the honest answer for a coordinate this level
     * cannot read.
     */
    private static int triggerReading(ServerLevel level, BlockPos host, Slot slot) {
        BlockPos neighbour = host.relative(slot.facing);
        if (!level.isLoaded(neighbour)) {
            return PowerSolver.clamp(slot.lastInput);
        }
        return readSignal(level, neighbour, slot.facing);
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
        // Holding the notifications until the pass that made them ends keeps them from re-entering the solve
        // below; every pass then flushes inside this tick, see settleCascading.
        SolverNotifications.Session session = SolverNotifications.begin(level);
        int roundsBefore = recomputesThisTick;
        try {
            InnerRedstoneStore store = InnerRedstoneStore.get(level);
            if (!store.isEmpty()) {
                // 时钟源 and 信号延长器 first - nothing notifies them at the right moment (one is a function of the world
                // clock, the other's hold runs out on it), so this pass turns them over.
                tickTimedDevices(level, store);
                // 红石触发器 last of the two, and before the solve: its answer is an edge rather than a level, so
                // something has to <b>step</b> it once per tick - and whatever it decides has to be visible to the
                // settle below, in the same tick.
                tickTriggers(level, store);
                applyDueFlips(level, store);
            }
            settleCascading(level, session);
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
     * One tick's work: settle, tell the world what changed, and - when it answered with more work - settle
     * again, all inside the same tick.
     *
     * <p>Why this exists: a flush that only happens at the end of the tick means the world's reaction to "this
     * host changed" is seen by the <em>next</em> tick's solve. Flushing between passes closes that loop inside
     * the tick for up to {@code solverCascadeLimit} passes; the session is held open across them, so the
     * world's answer is collected and sent by the following pass rather than sent now and forgotten.
     *
     * <p>Three things it deliberately does not change. A tick with nothing queued still flushes: the timed
     * devices, the triggers and the due flips notify before {@link #settle} runs, and the first pass flushes
     * whether or not that settle found anything to do. With the coalescing switch off there is no session, so
     * the notifications already went out as they were made, {@link #settle} has already absorbed the world's
     * answer, and nothing may cascade - the old behaviour, unchanged. And the budget is halved after a tick
     * that did not converge, so a genuine oscillator costs what it costs today.
     */
    private static void settleCascading(ServerLevel level, @Nullable SolverNotifications.Session session) {
        if (session == null) {
            settle(level);
            return;
        }
        int limit = cascadeLimit();
        CascadeState previous = CASCADES.get(level.dimension());
        int budget = previous == null ? limit : budgetAfter(previous.converged(), previous.budget(), limit);
        if (budget <= 1) {
            // Exactly the behaviour without the cascade, down to the order of the flush: one settle pass, and no
            // keeping-open flush at all, so end() sends this tick's notifications with the session already
            // removed and whatever they cause is the next tick's work. A tick that did not cascade says nothing
            // about whether the machine could have converged, so the budget is judged on the queue alone - a
            // quiet dimension gets its whole limit back the moment it is quiet.
            settle(level);
            CASCADES.put(level.dimension(), new CascadeState(!hasDirty(level), budget));
            return;
        }
        boolean converged = false;
        for (int cascade = 1; ; cascade++) {
            settle(level);
            session.noteCascade();
            SolverNotifications.flushKeepingOpen(level);
            boolean pending = SolverNotifications.pendingCount(level) > 0;
            boolean dirty = hasDirty(level);
            if (!dirty && !pending) {
                converged = true;
                break;
            }
            if (cascade >= budget) {
                // Not converged: the budget is what ended this tick, and the log says so once per tick so an
                // operator can tell a machine that needs a bigger limit from one the limit already covers.
                session.noteCascadeCapped();
                break;
            }
            // The round budget is the tick's real bound: a pass that has spent all of MAX_ROUNDS_PER_TICK has
            // already cost this tick everything one tick is allowed to cost, so another pass would pay twice.
            if (roundsThisTick >= MAX_ROUNDS_PER_TICK) {
                session.noteCascadeCapped();
                break;
            }
        }
        CASCADES.put(level.dimension(), new CascadeState(converged, budget));
    }

    /**
     * The cascade budget a tick may use, given what the tick before it did.
     *
     * <p>Converged, or nothing recorded yet: the whole limit. Not converged: half of the budget the last tick
     * was allowed, rounded down and never below one, capped by the limit - so a stale larger figure cannot
     * outlive a limit that was lowered, and a limit of one stays one however the circuit behaves.
     */
    static int budgetAfter(boolean converged, int previous, int limit) {
        int cap = Math.max(1, Math.min(limit, MAX_CASCADE_LIMIT));
        if (converged) {
            return cap;
        }
        return Math.max(1, Math.min(cap, previous / 2));
    }

    /** The configured cascade limit, clamped to the range the config documents. */
    private static int cascadeLimit() {
        return Math.max(1, Math.min(RCConfig.solverCascadeLimit(), MAX_CASCADE_LIMIT));
    }

    /** Whether this level has positions waiting for a recompute - the cascade's other "is there work left". */
    private static boolean hasDirty(ServerLevel level) {
        Set<BlockPos> dirty = DIRTY.get(level.dimension());
        return dirty != null && !dirty.isEmpty();
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
        // The cascade's backoff describes the ticks this world ran; a reloaded dimension must not inherit a
        // halved budget from a world that is gone.
        CASCADES.remove(level.dimension());
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
     * @return every position the solve covered, so the caller can skip re-solving them
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

        // The component alone is not the network: a world-side superconducting run laid against it is part of
        // it, and both halves are solved together (see collectUnion). The suppression opens before the union
        // is collected, because collecting it reads what feeds each run - and a run must not find its own
        // strength coming back out of the blocks it charges while that read is taken.
        Union union;
        List<BlockPos> changed;
        SuperconductingWireBlock.beginSuppression();
        try {
            union = collectUnion(SeamLookup.of(level, store), component);
            Union solved = union;
            changed = queryingWorld(level, () -> relax(level, store, solved));
        } finally {
            SuperconductingWireBlock.endSuppression();
        }

        // Outwards, and only now: the solve reported 0 to the world, so a consumer woken mid-solve would latch onto the wrong state.
        for (BlockPos pos : changed) {
            notifyOutputChanged(level, pos);
        }
        return union.hosts();
    }

    /**
     * <b>Hands one world-side run to the merged solve when a network a structure is carrying stands beside it.</b>
     *
     * <p>A run laid against a host block is already part of that block's solve - the block update marks the host
     * dirty and {@link #recompute} collects the run into it. A run laid against a <b>machine</b> has no host
     * block in the world store to mark, so the run's own re-evaluation is the only thing that can notice it, and
     * that is what calls this. Both sides are lossless, so solving them apart is a pair at gain exactly one,
     * whose every value is a fixed point: the source can be switched off and the pair still stands at fifteen.
     *
     * <p>Nothing new decides <em>who supplies what</em> here - the run becomes a node of the same monotone solve
     * the port is, which starts at zero and is only ever raised by a real source, so the pair does not exist as
     * a pair any more. And a run's own hop still costs nothing: no rule in this file charges a superconductor.
     *
     * @return true when the run was solved as part of a union, false when nothing carried could join it - the
     *     same four refusals {@link #collectUnion} documents - in which case the caller goes on to solve the run
     *     on its own, exactly as it always did, rather than answering zero
     */
    public static boolean settleCarriedRun(ServerLevel level, BlockPos origin) {
        InnerRedstoneStore store = InnerRedstoneStore.get(level);
        if (store == null) {
            return false;
        }
        SeamLookup seam = SeamLookup.of(level, store);
        List<BlockPos> run = seam.runCells(origin);
        if (run.isEmpty()) {
            return false;
        }
        List<BlockPos> changed;
        // The same window recompute opens, for the same reason: collecting the union reads what feeds the run,
        // and a run must not find its own strength coming back out of the blocks around it while that is read.
        SuperconductingWireBlock.beginSuppression();
        try {
            Union union = collectUnionForRun(seam, run);
            if (union.ports().isEmpty()) {
                return false;
            }
            Union solved = union;
            changed = queryingWorld(level, () -> relax(level, store, solved));
        } finally {
            SuperconductingWireBlock.endSuppression();
        }
        // Outwards, and only now: the solve reported 0 to the world, so a consumer woken mid-solve would latch
        // onto the wrong state.
        for (BlockPos pos : changed) {
            notifyOutputChanged(level, pos);
        }
        return true;
    }

    /**
     * <b>Two carried lossless networks, solved as ONE monotone system.</b>
     *
     * <p>The seam this closes is the one neither half owns: two structures physicalised separately, each
     * carrying a lossless host, each reading the other through the <b>world frame only</b> (the structure frame
     * answers 0 for a neighbour that is not in that structure's own index). Both receivers charge nothing for a
     * hop, so each half's read of the other is a plain copy at gain exactly one: the pair's equations are
     * {@code A = B} and {@code B = A}, every value is a fixed point, and a strength nothing supplies any more
     * stands there for ever. {@link #losslessPairInOneStructure} refuses that pair by design - it compares
     * <em>structures</em>, and the two halves are two - so the seam has to be joined instead: both components
     * become {@link PortNode}s of one solve, which starts every wire from zero and is only ever raised by a real
     * supply, so the least fixed point is reached instead of a fixed point being copied.
     *
     * <p><b>Why this is a separate entry point.</b> The two existing ones both need a subject in the world:
     * {@link #recompute} starts from a component of the world store and {@link #settleCarriedRun} from a
     * world-side run. Two machines facing each other have neither - the level holds air where they are drawn,
     * so the store's {@code slotAt} is null at every cell of the pair and {@link #settle} never visits it (see
     * the trigger gap recorded on {@link #settleUnion}). This starts the same walk from a <b>carried</b>
     * component instead, and everything after that is the walk that already exists: the port's own neighbour
     * cells join the world's runs and hosts, another carrier's projected host beside them joins as a further
     * port, and a structure-coordinate neighbour joins through {@code SeamLookup#structureHostAt}.
     *
     * <p><b>Nothing new decides an answer.</b> A refuse - no pose, a released carrier, an empty component, a
     * component holding anything that is not a lossless wire, including a driven one whose pending flips live in
     * per-carrier state this solve cannot reach - leaves {@code ports()} empty and the caller falls back to the
     * structure's own pass, which is exactly today's behaviour.
     *
     * @return the carriers whose components this solve covered, or an empty list when nothing could join - the
     *     caller's coalescing key, so one pair is assembled once in a tick and never twice
     */
    public static List<CarrierId> settleCarriedUnion(ServerLevel level, CarrierId carrier, BlockPos localSeed) {
        if (level == null || carrier == null || localSeed == null) {
            return List.of();
        }
        InnerRedstoneStore store = InnerRedstoneStore.get(level);
        if (store == null) {
            return List.of();
        }
        SeamLookup seam = SeamLookup.of(level, store);
        Union union;
        List<BlockPos> changed;
        // The same window recompute and settleCarriedRun open, for the same reason: collecting the union reads
        // what feeds each port, and a port must not find its own strength coming back out of the blocks around it
        // while that read is taken.
        SuperconductingWireBlock.beginSuppression();
        try {
            union = collectCarriedUnion(seam, carrier, localSeed);
            if (union.ports().isEmpty()) {
                return List.of();
            }
            Union solved = union;
            changed = queryingWorld(level, () -> relax(level, store, solved));
        } finally {
            SuperconductingWireBlock.endSuppression();
        }
        // Outwards, and only now: a consumer woken mid-solve would latch onto the wrong state. A carried-only
        // union has no world host in it, so this is usually empty - what changed is the carriers' own records,
        // and the carrier pass diffs those against what the clients were told.
        for (BlockPos pos : changed) {
            notifyOutputChanged(level, pos);
        }
        List<CarrierId> covered = new ArrayList<>(union.ports().size());
        for (PortNode port : union.ports()) {
            covered.add(port.carrier());
        }
        return covered;
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
     * Applies every driven component whose scheduled re-evaluation has come due, in queue order.
     *
     * <p>This is vanilla's {@code DiodeBlock#tick}, and its shape is not symmetric. A component that was
     * <b>on</b> follows what it reads now, which is how it turns off. A component that was <b>off</b> is
     * switched on whatever it reads - vanilla does not ask again before that half - and only when the input
     * has already gone is one more full delay queued behind it. That pair of rules is what makes an input pulse
     * shorter than the delay come out of the component exactly one delay wide instead of vanishing; a shorter
     * pulse cannot be shorter than the delay, which is also what a vanilla repeater does.
     *
     * <p>A component held still by a diode beside it is skipped and <b>not</b> re-queued: vanilla's locked
     * repeater does not tick at all. Nothing has to notice the release here either - the lock source announces
     * its own output change, which marks this component dirty and re-derives it.
     */
    private static void applyDueFlips(ServerLevel level, InnerRedstoneStore store) {
        PendingFlips pending = PENDING.get(level.dimension());
        if (pending == null || pending.size() == 0) {
            return;
        }
        List<PendingFlips.Flip> due = pending.drainDue(level.getGameTime());
        if (due.isEmpty()) {
            return;
        }

        SolverEnvironment env = new SolverEnvironment(level, store);
        for (PendingFlips.Flip flip : due) {
            BlockPos pos = flip.pos();
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
            // Under the same suppression as the solve itself: these queries are the solver looking at the
            // world, so they must not see this component's own output coming back at it.
            if (queryingWorld(level, () -> PowerSolver.isSignalLocked(env, pos, SlotNode.of(slot)))) {
                continue;
            }
            int desired = queryingWorld(level, () ->
                    PowerSolver.desiredOutput(env, pos, SlotNode.of(slot)));
            int before = slot.power;
            int after;
            if (before > 0) {
                if (desired == before) {
                    continue;
                }
                after = desired;
            } else if (slot.type.isDiode()) {
                // Off before this tick: vanilla switches a diode on regardless of what arrives now, at the strength
                // the queue recorded - the input that started it is exactly what is no longer readable here.
                after = desired > 0 ? desired : PowerSolver.clamp(flip.target());
                if (after <= 0) {
                    continue;
                }
            } else {
                // A torch asks again before it re-lights itself - vanilla's {@code RedstoneTorchBlock#tick} does
                // the same - so for it the pulse that has already gone is still swallowed.
                if (desired == before) {
                    continue;
                }
                after = desired;
            }
            // Counted always, printed only when asked: the per-step line is far too frequent to log unconditionally.
            SolverLog.componentChanged();
            if (SolverLog.verbose()) {
                RCConfig.LOGGER.info(
                        "[redstonecircuit] {} at {} output {} -> {} (after its delay)",
                        slot.type, pos.toShortString(), before, after);
            }
            slot.power = after;
            store.markDirty();
            // The host block emits something else now, and the network around it has to re-settle.
            notifyOutputChanged(level, pos);
            markDirty(level, pos);
            HostSync.broadcast(level, pos, slot);
            if (before == 0 && desired == 0) {
                // The input left before the delay elapsed: one more full delay turns it off again, which is what
                // makes the output one delay wide. Vanilla schedules that second tick at VERY_HIGH.
                pending.schedule(pos,
                        level.getGameTime() + Math.max(1, slot.type.delayTicks(slot.delay)),
                        TickPriority.VERY_HIGH, 0);
            }
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
     *
     * <p>{@code chargeWire = false} turns that charge off and returns the raw signal clamped: a comparator's
     * side input is read at vanilla's own full strength, so the wire hop is not charged there. Everything else
     * keeps the charged read.
     */
    public static int readSignal(BlockGetter blocks, BlockPos pos, Direction direction) {
        return readSignal(blocks, pos, direction, true);
    }

    /** {@link #readSignal(BlockGetter, BlockPos, Direction)}, naming whether a vanilla wire is charged its hop. */
    public static int readSignal(BlockGetter blocks, BlockPos pos, Direction direction, boolean chargeWire) {
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
        if (!chargeWire) {
            // A comparator's side face, where vanilla's wire answers its own strength: charging the hop here
            // made a subtract come out one too high, and a feedback loop that vanilla brings down to zero
            // stopped at one.
            return PowerSolver.clamp(signal);
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
        return worldSignalAt(level, worldPos, worldDirection, true);
    }

    /**
     * The same read, naming whether a neighbouring vanilla wire is charged its hop.
     *
     * <p>Only the vanilla half of the pair varies: the projected half is a raw emission that charges no hop
     * either way, which is why it is asked identically here. {@code chargeWire = false} is what a comparator's
     * side input reads with.
     */
    public static int worldSignalAt(ServerLevel level, BlockPos worldPos, Direction worldDirection,
                                    boolean chargeWire) {
        int vanilla = readSignal(level, worldPos, worldDirection, chargeWire);
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
     * <b>The world half of one side, with the seam's own kind of read chosen for it.</b>
     *
     * <p>{@link #worldSignalAt} answers what the neighbour <em>holds</em>, and a supply is copied at the
     * receiver's own cost, so a seam between two wires that both charge nothing hands the neighbour's
     * strength over unchanged - in both directions, which makes the pair's equations {@code A = B} and
     * {@code B = A} and every value their fixed point (see {@link PowerSolver#losslessSeam}). A latch that
     * nothing supplies any more stands there for ever.
     *
     * <p>A lossless seam therefore reads what the world pushes <b>into</b> that neighbour - the strength it
     * would hold without us ({@link #worldSupplyAt}) - and every other seam keeps reading the strength the
     * neighbour holds, where the receiver's cost already makes the pair's fixed point unique.
     *
     * <p>Only this side of the seam can ask that question, and that asymmetry is the design: what feeds a
     * world block is that block's neighbours, which are the world's own business and are read here with the
     * reader's own projected hosts refused; a structure's inside is not readable from the world at all, so
     * the world solve keeps reading the carried strength - the honest answer once the carried strength is
     * what this read makes it.
     */
    public static int worldSignalIntoOwnFrame(ServerLevel level, BlockPos worldPos, Direction worldDirection,
                                              @Nullable CarrierId owner,
                                              @Nullable ComponentType reader) {
        // The reader's own type first, because it costs nothing: only a wire that pays no hop for a hop can
        // make a seam lossless, and everything else - a piece of dust, a repeater, a caller with no identity -
        // keeps the value read without a store lookup per side of every component.
        if (!seamIsLossless(reader, worldHostTypeAt(level, worldPos, owner))) {
            return worldSignalAt(level, worldPos, worldDirection, owner);
        }
        return worldSupplyAt(level, worldPos, owner);
    }

    /**
     * <b>Whether the seam at a world cell is lossless, as one question about two component types.</b>
     *
     * <p>Split out so both halves of the read ask it with the <em>same</em> function: the world solve decides it
     * from its own store, the carrier bridge from the store and the projection table together (see
     * {@link #worldSignalIntoOwnFrame}), and a fixture can ask it from whatever tables it has. The reader's own
     * type is checked first because it costs nothing, and everything else - a piece of dust, a repeater, a caller
     * with no identity, a neighbour whose kind cannot be established - keeps the value read.
     */
    public static boolean seamIsLossless(@Nullable ComponentType reader, @Nullable ComponentType neighbour) {
        return reader != null && reader.isWire() && reader.hopCost() == 0
                && PowerSolver.losslessSeam(reader, neighbour);
    }

    /**
     * What the world pushes <b>into</b> the block at {@code worldPos}: its own supply, read around it
     * instead of off it.
     *
     * <p>The one read a lossless seam is allowed to make of the other half. Around the block rather than at
     * it, so the answer is grounded in real sources - a lever, a world wire, a diode - instead of in the
     * pair's own last result.
     *
     * <p>{@code owner}'s own hosts are refused at every one of those cells, which is what keeps the reader
     * out of its own answer. The one exception is a host of ours that holds something which is <b>not</b> a
     * wire: only a wire reads all six sides, so only a wire of ours can hand our own answer back, while a
     * torch or a repeater of the machine's standing beside that block is a source and is read as one.
     */
    public static int worldSupplyAt(ServerLevel level, BlockPos worldPos, @Nullable CarrierId owner) {
        if (level == null || worldPos == null) {
            return 0;
        }
        int best = 0;
        for (Direction direction : Direction.values()) {
            BlockPos neighbour = worldPos.relative(direction);
            best = Math.max(best, worldSignalAt(level, neighbour, direction, owner));
            best = Math.max(best, ourSourceAt(level, neighbour, direction, owner));
            if (best >= PowerSolver.MAX_POWER) {
                return PowerSolver.MAX_POWER;
            }
        }
        return best;
    }

    /**
     * A host of {@code owner}'s own that stands beside a world block as a <b>source</b> there rather than as
     * a wire: the part of the reading machine that still feeds that block once the wires it cannot be quoted
     * to have been refused by {@link #worldSupplyAt}'s own-projection refusal.
     */
    private static int ourSourceAt(ServerLevel level, BlockPos worldPos, Direction worldDirection,
                                   @Nullable CarrierId owner) {
        if (owner == null) {
            return 0;
        }
        try {
            CarrierProjection.ProjectedHost host = CarrierStore.projectedHostAt(level, worldPos);
            if (host == null || host.slot() == null || !owner.equals(host.carrier())
                    || host.slot().type.isWire()) {
                return 0;
            }
            int answer = CarrierProjection.emittedToward(host, worldDirection, false, false);
            return seamHop(host, seamReaderAt(level, worldPos, worldDirection), answer);
        } catch (RuntimeException | LinkageError unreadable) {
            return 0;
        }
    }

    /**
     * The kind of component standing at a world cell, or {@code null} when nothing of ours is there: the level's
     * own host block first, and <b>then the host another structure is carrying there</b>.
     *
     * <p>The second half is not a widening, it is the same question asked of both places a component can stand.
     * A carried host is drawn over <em>air</em> - its world cell holds no block state and the store never has a
     * slot there - so a lookup that consults the store alone answers "not one of ours" for the one neighbour a
     * physicalisation seam can ever have. That answer made {@link #seamIsLossless} say "this seam pays a hop" for
     * two superconducting blocks touching each other from two different structures, and the read fell back to the
     * value: the neighbour's own stored strength, at gain exactly one, in both directions - a pair whose fixed
     * point is every value, so a strength nothing supplied any more stood there for ever.
     *
     * <p>{@code owner}'s own projected hosts are refused, exactly as every other read refuses them: a host of the
     * reader's own that stands beside it arrives through the inner relationship that charges the hop, and calling
     * that a seam would answer with the strength it would hold without itself.
     */
    @Nullable
    private static ComponentType worldHostTypeAt(ServerLevel level, BlockPos worldPos,
                                                 @Nullable CarrierId owner) {
        if (level == null || worldPos == null) {
            return null;
        }
        try {
            InnerRedstoneStore store = InnerRedstoneStore.get(level);
            Slot slot = store == null || store.isEmpty() ? null : store.slotAt(worldPos);
            if (slot != null) {
                return slot.type;
            }
            CarrierProjection.ProjectedHost carried = CarrierStore.projectedHostAt(level, worldPos);
            return carried == null || carried.slot() == null || !projectionReadableBy(carried, owner)
                    ? null
                    : carried.slot().type;
        } catch (RuntimeException | LinkageError unreadable) {
            return null;
        }
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
     * <b>Whether the block a structure's own view holds at one local coordinate is a lossless wire of ours.</b>
     *
     * <p>The counterpart, one table over, of {@link #carriedHoldsWire}: that one asks the projection table about a
     * <em>world</em> position, this one asks the structure's own blocks about a <em>local</em> coordinate. Both
     * answer one question - "is the neighbour on the other half of a lossless pair" - and both are needed,
     * because a block a structure carries can be read from either side.
     *
     * <p>A superconducting wire lying on a machine's surface is a <b>block state</b> of the machine's own grid and
     * never a host of any carrier (only a block holding a component is a host), so no solve of ours owns it and
     * nothing of ours writes its {@code POWER}: the number standing there is the machine's own last answer, and it
     * may not be handed to the component inside the block beside it as a supply. See
     * {@link #losslessPairInOneStructure} for the read that replaces that copy.
     *
     * <p><b>Only the lossless wire.</b> Ordinary dust pays a hop, so a dust partner makes a pair's equations
     * contradictory and it comes down by itself; reading it as a pair would only change a working machine's answer
     * for the worse.
     *
     * <p>{@code null} and a throwing view both answer {@code false}: a structure that cannot be read must never be
     * turned into anything but the ordinary read.
     */
    public static boolean structureHoldsLosslessWire(@Nullable BlockGetter blocks,
                                                     @Nullable BlockPos localPos) {
        if (blocks == null || localPos == null) {
            return false;
        }
        try {
            BlockState state = blocks.getBlockState(localPos);
            return state != null && SuperconductingWireBlock.isSuperconductor(state);
        } catch (RuntimeException | LinkageError unreadable) {
            // Same rule as every other carrier read: a failure looks like a jar without the block view.
            return false;
        }
    }

    /**
     * <b>Whether the host another structure is carrying at a world cell holds a wire.</b>
     *
     * <p>The one kind of neighbour a read may not take as a supply: a wire inside a block is a <em>node</em> of
     * the merged solve and lies on the same network as the wire beside it, which is why a wire the world store
     * holds there is refused by {@code SuperconductingWireBlock#supplyAt}. The single exception is a lossless
     * pair, and it is asked before this refusal rather than after it - see
     * {@link #projectedLosslessPair} and {@link #supplyFromNeighbour}. A carried host is drawn over air, so
     * the store can never answer for it and the projection table is the only place that can - and where the two
     * cannot be joined (a frame that is not wholly a lossless wire is refused whole), that refusal is the
     * difference between one network and two networks reading each other's last answer, which is a pair at gain
     * exactly one in both directions and therefore a strength nothing supplies any more standing for ever.
     */
    public static boolean carriedHoldsWire(Level level, BlockPos worldPos) {
        if (!(level instanceof ServerLevel server) || worldPos == null) {
            return false;
        }
        try {
            CarrierProjection.ProjectedHost host = CarrierStore.projectedHostAt(server, worldPos);
            return host != null && host.slot() != null && host.slot().type.isWire();
        } catch (RuntimeException | LinkageError unreadable) {
            // The carrier bridge is a bonus: a failure in it must look like a jar without it.
            return false;
        }
    }

    /**
     * <b>Whether two cells of one structure are each other's lossless partner.</b>
     *
     * <p>One machine, two tables: a host of the machine lives in the carrier's own records, while a
     * superconducting wire lying on its surface is a block state of the machine's own grid, so no solve of ours
     * owns both halves. Handing either half the other's <em>stored</em> strength makes the pair's equations
     * {@code A = B} and {@code B = A} - a supply is copied at the receiver's own cost, and a superconducting
     * receiver pays nothing - so every value is a fixed point and a strength nothing supplies any more stays where
     * it is. See {@link PowerSolver#losslessSeam}.
     *
     * <p><b>Two conditions and nothing else.</b> {@link PowerSolver#losslessSeam}: a <b>lossless</b> reader and
     * sender, so ordinary dust - which pays a hop, and therefore cannot hold a pair up - never matches, and a
     * torch or a repeater inside the block beside the wire never matches either: it is a source and is read as
     * one. And <b>one</b> structure: a host another structure carries is the physicalisation seam proper, the only
     * route by which two independently solved machines read one another, and it must keep answering. A
     * {@code null} structure is a coordinate no structure's own storage holds - the world - which keeps its
     * supply.
     *
     * <p>What a pair does <b>instead</b> of copying is stated once, here, and both sides ask it before they read:
     * each takes the other's <b>own supply</b>, read around that cell with the reader's own cell left out
     * ({@link #supplyAround}). A wire's value is {@code max(own supply, what its neighbours send it)}, so leaving
     * out the reader's own term can only lower the answer - it cannot light anything that was dark - while a real
     * source on the far side still arrives at full strength (a superconductor charges nothing for the hop) and a
     * pair that supplies nothing but itself comes down to zero instead of holding what it had.
     */
    public static boolean losslessPairInOneStructure(@Nullable ComponentType reader,
                                                     @Nullable ComponentType sender,
                                                     @Nullable CarrierId readerStructure,
                                                     @Nullable CarrierId senderStructure) {
        return readerStructure != null && readerStructure.equals(senderStructure)
                && PowerSolver.losslessSeam(reader, sender);
    }

    /**
     * <b>Whether the host at one structure coordinate is a lossless partner of the lossless wire beside it.</b>
     *
     * <p>The lookup half of {@link #losslessPairInOneStructure} for the run side, where both cells are level
     * coordinates: the host answers through the carrier's structure index - a plot slot lives in the carrier's
     * records, so the level's store can never answer for it - and <b>whose</b> machine it is answers from the
     * <b>wire's</b> coordinate, never the host's: the host is already indexed under one carrier, so asking the
     * host's coordinate would only restate the ownership guard the lookup behind it just applied.
     *
     * <p><b>Two branches, because the two halves of one pair ask different questions.</b> The guarded branch above
     * is the structure index plus an ownership comparison, and it answers byte for byte what it always answered. When
     * it does not, the same pair is asked of the structure the <em>host itself</em> names: the unguarded host at this
     * neighbour ({@code CarrierStore#structureHostAtAnyOwner}), a lossless pair of component kinds, and the wire found
     * by reading <b>that host's own structure view</b> at the wire's cell - literally the test the carrier half makes
     * (see {@code CarrierSpace#losslessPartnerHere}, which names its own carrier twice for the same reason). The case
     * this closes is real and named: a cell of a structure's own grid can lie <b>outside the plot containment box</b>
     * - a wire built into a sub-level after it was physicalised - where the ownership question answers {@code null}
     * and the guarded lookup refuses a host the structure really holds, so the carrier half takes the pair read and
     * the run half copied the stored value instead: each kept the other up and the value flipped 15/0 every tick.
     *
     * <p><b>Why the second branch is safe.</b> Every added pair read replaces a stored copy with a maximum that has
     * the reader's own term removed, so it can only <b>lower</b> an answer and can never light something that was
     * dark; and the fallback is asked only after the existing path declined, so nothing that answers today changes.
     *
     * <p>{@code null} and a throwing bridge both answer {@code false}: an unreadable structure must never be
     * turned into anything but the ordinary read.
     *
     * @param wireCell the <b>wire's own</b> coordinate, whose structure storage decides whose machine the host is
     * @param hostCell the neighbour being read, which the structure index has to hold a host at
     * @param wireState the state standing at {@code wireCell}, passed in so the rule needs no second block read
     */
    public static boolean sameStructureLosslessPair(Level level, BlockPos wireCell, BlockPos hostCell,
                                                    @Nullable BlockState wireState) {
        if (!(level instanceof ServerLevel server) || wireCell == null || hostCell == null || wireState == null
                || !SuperconductingWireBlock.isSuperconductor(wireState)) {
            return false;
        }
        try {
            CarrierProjection.ProjectedHost host = CarrierStore.structureHostAt(server, hostCell);
            if (host != null && host.slot() != null
                    && losslessPairInOneStructure(ComponentType.SUPERCONDUCTOR, host.slot().type,
                            CarrierStore.structureOwnerAt(server, wireCell), host.carrier())) {
                logPairBranch("pair by owner", wireCell, hostCell, host);
                return true;
            }
            // THE SYMMETRIC FALLBACK, and the whole of the asymmetry this branch closes. The carrier half never asks
            // whose coordinate its wire stands on: it reads the wire through its OWN structure's view
            // (CarrierSpace#structureSignal) and names its own carrier twice, so "these two cells are one machine"
            // is true there by construction. The guarded lookup above asks it instead - the structure index plus
            // plotOwnerAt - and a cell of a structure's own grid can lie OUTSIDE the plot containment box (a wire
            // built into a sub-level after it was physicalised), where the owner comes back null and the host is
            // refused even though the structure really holds it. The two halves then disagree about the very same
            // pair: one takes the own-supply read, the other copies the stored 15, and the pair oscillates.
            //
            // So the run half asks the carrier half's own question, in the carrier half's own words: the UNGUARDED
            // host at the neighbour, a lossless pair of component kinds, and the wire found by reading the host's
            // own structure view at the wire's cell - literally the test CarrierSpace#structureSignal makes.
            //
            // WHY THIS IS SAFE, in one line each: every added pair read replaces a stored copy with a maximum that
            // has the reader's own term removed, so it can only LOWER an answer and can never light something dark;
            // and the fallback is asked only after the existing path declined, so nothing that answers today moves.
            CarrierProjection.ProjectedHost anyOwner = CarrierStore.structureHostAtAnyOwner(server, hostCell);
            if (anyOwner == null || anyOwner.slot() == null) {
                return false;
            }
            boolean pair = pairByOwnView(
                    PowerSolver.losslessSeam(ComponentType.SUPERCONDUCTOR, anyOwner.slot().type),
                    structureHoldsLosslessWire(CarrierStore.blockViewOf(anyOwner.carrier()), wireCell));
            if (pair) {
                logPairBranch("pair by own view", wireCell, hostCell, anyOwner);
            }
            return pair;
        } catch (RuntimeException | LinkageError unreadable) {
            // The carrier bridge is a bonus: a failure in it must look like a jar without it.
            return false;
        }
    }

    /**
     * <b>The fallback's whole decision, as one question about two facts</b>, so it is pinned offline - a unit test
     * has neither a {@code ServerLevel} nor a superconductor <em>block state</em> (the block registry throws before
     * it returns), so the level-taking wrapper cannot be driven and the rule has to be stated where it is decided.
     *
     * <p>{@code hostIsLossless} is {@code PowerSolver#losslessSeam} between the run's own kind and the component the
     * neighbour holds, read from the <b>unguarded</b> host lookup - the same condition the owner branch asks, minus
     * the ownership comparison. {@code ownViewHoldsLosslessWire} is
     * {@link #structureHoldsLosslessWire} over the <b>host's own carrier's</b> block view at the wire's cell - the
     * same test the carrier half makes, from the same side: the structure that holds the host is the structure being
     * asked, which is the entire reason the ownership comparison is not a question here.
     *
     * <p>Both, and neither alone: a lossless host with no wire of its own structure beside it is not a pair (the
     * neighbour is the open world's block), and a wire of that structure with a dust, torch, repeater or comparator
     * in the host is not one either - a source is read as a source, and a fading line pays a hop so a pair with it
     * cannot hold itself up.
     */
    static boolean pairByOwnView(boolean hostIsLossless, boolean ownViewHoldsLosslessWire) {
        return hostIsLossless && ownViewHoldsLosslessWire;
    }

    /** When the pair-branch line was last written, for its wall-clock throttle. */
    private static long pairBranchAt;

    /** True at most once per second: the pair lookup runs thousands of times a second and one readable line is
     * what a reporter reproducing by hand needs. */
    private static boolean pairBranchDue() {
        long now = System.currentTimeMillis();
        if (now - pairBranchAt < 1000L) {
            return false;
        }
        pairBranchAt = now;
        return true;
    }

    /**
     * <b>One line naming which branch of {@link #sameStructureLosslessPair} answered</b>, and the cells it was asked
     * about.
     *
     * <p>The two branches are two different questions about one pair, and a log line is what a report is
     * checked against: "the carrier half took the pair read and the run half did not" is answered by seeing which
     * branch answered on either side, and by the fact that the pair read oscillating (15 to 0 and back every tick)
     * is the fallback's absence rather than a solver fault. Gated on {@code debugLog}, throttled by wall clock, and
     * written only for the branch that <b>did</b> answer - a line for every neighbour that declined would bury it.
     */
    private static void logPairBranch(String branch, BlockPos runCell, BlockPos hostCell,
                                      CarrierProjection.ProjectedHost host) {
        if (!RCConfig.debugLog() || !pairBranchDue()) {
            return;
        }
        RCConfig.LOGGER.info("[redstonecircuit] pair read at {} against host {}: {} (carrier {})",
                runCell.toShortString(), hostCell.toShortString(), branch,
                host.carrier() == null ? "none" : host.carrier().shortId());
    }

    /**
     * <b>Whether the host a structure is PROJECTING beside this run is the run's lossless partner.</b>
     *
     * <p>The projection counterpart of {@link #sameStructureLosslessPair}, and the same rule: a lossless wire and
     * a lossless host are a pair whose every value is a fixed point, so neither half may be handed the other's
     * <em>stored</em> strength. What each asks instead is the other's own supply - here
     * {@link #projectedHostSupplyAt} - so that a strength nothing supplies any more comes down to zero while a
     * real source on the far side still arrives at full strength.
     *
     * <p><b>Why a second table is needed at all.</b> {@code structureHostAt} is keyed by a structure's own
     * coordinate, which is right for a host of a structure's own blocks. A host of a structure that is
     * <b>moving</b> is not there: the structure keeps it in its own records and draws it over air at a world
     * position its block storage has never heard of, so {@link CarrierStore#projectedHostAt} is the only place
     * that can answer. Read through the level query instead - which is what the mixin's projected half does -
     * the pair comes back as the host's stored power at the wire's own hop cost, which for a superconductor is
     * nothing: the same gain-zero copy as the host side, and the observation that "as soon as an
     * outer line and the inside touch, it sticks".
     *
     * <p><b>Three refusals, each of them "keep the read that already works".</b> A run that is not lossless -
     * ordinary dust pays a hop, so a pair with it cannot hold itself up - keeps the plain read. A neighbour the
     * structure index already resolves keeps <b>that</b> path (see {@link #sameStructureLosslessPair}), which is
     * the same machine's own surface and is asked before this one. And a host whose own component is not
     * lossless - a torch, a repeater, a comparator or ordinary dust inside it - is a source or a fading line and
     * keeps the plain read too. A projection that cannot be read (no pose, no slot) is never a pair either: an
     * unreadable table must look like a jar without it, and never like a zero.
     *
     * @param runType what the run's cell holds, derived by the caller from the wire's own block state
     * @param structureIndexHostType what the <b>structure index</b> holds at the neighbour, or {@code null} when
     *     it holds nothing there - the fact that says today's path is not the one that applies
     * @param projected the host the projection table holds at the neighbour, or {@code null}
     */
    public static boolean projectedLosslessPair(@Nullable ComponentType runType,
                                                @Nullable ComponentType structureIndexHostType,
                                                @Nullable CarrierProjection.ProjectedHost projected) {
        if (runType == null || !runType.isWire() || runType.hopCost() != 0) {
            return false;
        }
        if (structureIndexHostType != null) {
            // The structure's own coordinate answered, so its read is the one that applies - the machine's own
            // surface rather than somebody else's projection. Two pair reads of one cell would be two answers for
            // one neighbour, and only one of them can be the one the solve is built on.
            return false;
        }
        if (projected == null || projected.slot() == null || projected.pose() == null
                || projected.localPos() == null) {
            return false;
        }
        return PowerSolver.losslessSeam(runType, projected.slot().type);
    }

    /** One side of an "own supply" read: what stands at {@code side} offers the cell it is beside. */
    @FunctionalInterface
    public interface SideSupply {

        /**
         * @param side the neighbour cell being asked
         * @param direction vanilla's convention - from the cell whose supply is being read towards {@code side}
         */
        int toward(BlockPos side, Direction direction);
    }

    /**
     * <b>The sides an "own supply" read asks: the six of a cell, minus the reader's own.</b>
     *
     * <p>The one statement of <em>which</em> cell is left out, so the two sides of a lossless pair cannot drift
     * apart. What is excluded is always the <b>other member of the pair</b>, which is the reader's own cell: the
     * run's cell when the host beside it is read, the host's cell when the wire block beside it is read. Nothing
     * else is skipped - a real source on any of the other five sides is exactly what must still arrive.
     */
    public static List<Direction> ownSupplySides(@Nullable BlockPos cell, @Nullable BlockPos excluded) {
        return ownSupplySidesExcept(cell, excluded == null ? Set.<BlockPos>of() : Set.of(excluded));
    }

    /**
     * <b>The sides an "own supply" read asks, with the reader's <em>whole network</em> left out.</b>
     *
     * <p>The exclusion is a set rather than one cell for a reason stated by the ring: a reader may be a
     * <em>network</em>, and a network can bite the same partner from more than one cell. Excluding the one cell the
     * read is made from removes the smallest form of the loop and leaves every longer one standing - two hosts of
     * one structure reading each other, three of them in a triangle, a run that wraps a host. What is asked instead
     * is "what would you be without <b>this network</b>", and the network is the set of cells the reader belongs to
     * in the solve that is asking ({@link #readerNetworkAt}).
     *
     * <p>Nothing but the exclusion changes: a cell that is <b>not</b> in the reader's network is still asked, which
     * is what keeps a real source - a lever, a torch, a block of another structure - arriving at full strength.
     */
    public static List<Direction> ownSupplySidesExcept(@Nullable BlockPos cell,
                                                       @Nullable Set<BlockPos> excluded) {
        List<Direction> sides = new ArrayList<>(Direction.values().length);
        if (cell == null) {
            return sides;
        }
        for (Direction direction : Direction.values()) {
            if (excluded != null && excluded.contains(cell.relative(direction))) {
                continue;
            }
            sides.add(direction);
        }
        return sides;
    }

    /**
     * <b>What a cell's own sides push into it, with one cell left out.</b>
     *
     * <p>The read a lossless pair makes of the other half - the shape of {@link #worldSupplyAt}, one coordinate
     * frame over - and grounded in real sources rather than in the pair's own last result. A wire's value is
     * {@code max(own supply, what its neighbours send it)}, so leaving one term out of that maximum can only lower
     * the answer, which is why this cannot light anything that was dark.
     */
    public static int supplyAround(@Nullable BlockPos cell, @Nullable BlockPos excluded,
                                   @Nullable SideSupply read) {
        return supplyAround(cell, excluded, read, (SideSupply) null);
    }

    /**
     * <b>The same read in the two frames a cell of a structure really has.</b>
     *
     * <p>A host block riding a machine has <b>two neighbourhoods</b>, and the answer that reaches it is the larger
     * of the two - that is stated once for its input by {@code CarrierSpace#externalSignal}. A structure's own
     * cells are one frame (the machine's own blocks and hosts, read at the machine's coordinates), and the world
     * the machine is drawn in is the other: a vanilla torch standing beside the drawn host block lives in the
     * second and in <b>neither</b> of the first's tables.
     *
     * <p>So an "own supply" read that asks one frame alone is not the partner's own supply, it is half of it, and
     * a real source in the other half is dropped - which shows up as "the inside is lit and the surface is not".
     * The pair rule itself is unchanged: each side is still read around the cell with the reader's own cell left
     * out, and a pair that supplies nothing but itself still comes down to zero, because both readers are given
     * the same exclusion.
     *
     * @param structureRead the frame of the structure's own blocks and hosts, or {@code null} to ask it not
     * @param worldRead the frame of the world the structure is drawn in, or {@code null} when nothing can be
     *     asked there (no pose, no live structure)
     */
    public static int supplyAround(@Nullable BlockPos cell, @Nullable BlockPos excluded,
                                   @Nullable SideSupply structureRead, @Nullable SideSupply worldRead) {
        return supplyAroundExcept(cell, except(excluded, null), structureRead, worldRead);
    }

    /**
     * <b>The same read with a whole network left out</b> - the reader's, not one of its cells.
     *
     * <p>See {@link #ownSupplySidesExcept} for why the exclusion is a set; the arithmetic is unchanged. Both frames
     * are given the <b>same</b> set, so the two halves of one pair cannot exclude different things.
     */
    public static int supplyAroundExcept(@Nullable BlockPos cell, @Nullable Set<BlockPos> excluded,
                                         @Nullable SideSupply structureRead, @Nullable SideSupply worldRead) {
        if (cell == null || (structureRead == null && worldRead == null)) {
            return 0;
        }
        int best = 0;
        for (Direction direction : ownSupplySidesExcept(cell, excluded)) {
            BlockPos side = cell.relative(direction);
            if (structureRead != null) {
                best = Math.max(best, structureRead.toward(side, direction));
            }
            if (worldRead != null) {
                best = Math.max(best, worldRead.toward(side, direction));
            }
            if (best >= PowerSolver.MAX_POWER) {
                return PowerSolver.MAX_POWER;
            }
        }
        return best;
    }

    /**
     * The run-side form of the same question: the reader's own cell, plus <b>every other cell of the network it
     * belongs to</b> in the solve that is asking.
     *
     * <p>The extra parameter is the whole point of this round: a wire that wraps a host, or two hosts of one
     * structure standing in each other's way, each bit the partner from its own cell, and excluding only the cell
     * the read was made from left the longer loops standing. An empty or {@code null} set is the old, conservative
     * read - one cell - so a caller that cannot say which network it is in can never answer zero for a whole
     * machine.
     */
    public static int supplyAround(@Nullable BlockPos cell, @Nullable BlockPos excluded,
                                   @Nullable SideSupply read, @Nullable Set<BlockPos> readerCells) {
        return supplyAroundExcept(cell, except(excluded, readerCells), read, null);
    }

    /** The exclusion set one read asks: the reader's network, and never less than the cell the read is made from. */
    private static Set<BlockPos> except(@Nullable BlockPos excluded, @Nullable Set<BlockPos> readerCells) {
        if (readerCells == null || readerCells.isEmpty()) {
            return excluded == null ? Set.<BlockPos>of() : Set.of(excluded);
        }
        if (excluded == null || readerCells.contains(excluded)) {
            return readerCells;
        }
        Set<BlockPos> all = new HashSet<>(readerCells);
        all.add(excluded);
        return all;
    }

    /**
     * <b>The cells of the network doing the read</b>, for the one caller that has to ask across a seam.
     *
     * <p>Scoped and restored like {@link #seamReader}, because the reader is a mixin-driven recalculation with no
     * parameter to carry it: the run's own cells are collected once by
     * {@code SuperconductingWireBlock#updateComponent} (which already holds them) and are read here by the pair read
     * {@code supplyFromNeighbour} makes. A read that finds nothing scoped for its own cell answers "just this cell",
     * which is exactly the conservative exclusion this round replaces - never a wider one.
     */
    public static <T> T readingFor(@Nullable Set<BlockPos> network, java.util.function.Supplier<T> read) {
        Set<BlockPos> previous = readerNetwork;
        readerNetwork = network;
        try {
            return read.get();
        } finally {
            readerNetwork = previous;
        }
    }

    /** The exclusion set for a read made from {@code cell}: its network, or that one cell. */
    public static Set<BlockPos> readerNetworkAt(@Nullable BlockPos cell) {
        Set<BlockPos> network = readerNetwork;
        if (network != null && cell != null && network.contains(cell)) {
            return network;
        }
        return cell == null ? Set.<BlockPos>of() : Set.of(cell);
    }

    /**
     * <b>THE RULE, stated once: a partner's own network is PART OF THE PARTNER, not a supply to it.</b>
     *
     * <p>一条光网络是一个节点 - one lossless network is one node, not a set of sources feeding one another. A
     * superconductor charges nothing for the hop and every cell of one network holds one strength, so a member of
     * that network read as a <em>supply</em> hands the partner back its own value at gain exactly one: {@code A = B}
     * together with {@code B = A} has <b>every</b> value as a fixed point - fifteen stays fifteen with the lever
     * off, and the value flips 15/0 every tick while the two halves of the pair disagree about the same cell.
     *
     * <p><b>The carrier half already says so by construction.</b> {@code CarrierSpace#ownSupplyOf} hands
     * {@code readerCells()} - the host's own network, narrowed to lossless wire-typed cells plus the host's own cell
     * - to <b>both</b> frames, and {@link #ownSupplySidesExcept} then never asks a side that holds a member: a
     * member is not a neighbour, it <b>is</b> the node. The run half asked a narrower question instead -
     * {@link #readerNetworkAt} of the <em>run</em> - and a run's cells can never contain the partner host's
     * neighbours. So the run half answered a neighbouring host of the same structure from its record
     * ({@link #structureFrameSupplyAt} through {@code CarrierProjection#emittedTowardInStructureFrame}) and the
     * world half answered the same side too - the live line {@code RUN PAIR SIDES ... SOUTH s=15 w=15} -
     * and every value was a fixed point.
     *
     * <p><b>What this fixes is an exclusion and nothing else.</b> A cell's value is
     * {@code max(own supply, what its neighbours send)}, so removing terms from that maximum can only <b>lower</b>
     * an answer and can never light something that was dark; and an unreadable or empty answer leaves the set the
     * caller already had, so a bridge that failed always excludes <b>less</b>, never more.
     *
     * @param partnerCarrier the structure the partner host names, or {@code null} when the level cannot say
     * @param partnerCell the partner host's own coordinate, in that structure's own frame
     * @param readerCells the exclusion the run half already had: the reader's own network
     * @return the union of the two sets, and never less than {@code readerCells}
     */
    private static Set<BlockPos> withPartnerOwnNetwork(ServerLevel level, @Nullable CarrierId partnerCarrier,
                                                       @Nullable BlockPos partnerCell,
                                                       @Nullable Set<BlockPos> readerCells) {
        Set<BlockPos> excluded = readerCells == null ? new HashSet<>() : new HashSet<>(readerCells);
        Set<BlockPos> partner = partnerOwnNetwork(level, partnerCarrier, partnerCell);
        int added = 0;
        if (partner != null) {
            for (BlockPos cell : partner) {
                if (excluded.add(cell)) {
                    added++;
                }
            }
        }
        logPartnerOwnNetwork(partnerCell, partner == null ? -1 : added);
        return excluded;
    }

    /**
     * <b>The partner's own network, read the way the carrier half reads its own.</b>
     *
     * <p>这个伙伴自己的网络是哪几格: the partner host <b>names</b> its carrier, so that carrier's own frame is asked
     * for the component containing the host's own cell - {@link SeamLookup#frameOf}, which is
     * {@code CarrierStore#poseOf} plus the {@link PowerEnvironment} space {@code CarrierSpace#forSolve} builds, now
     * the one composition both the merged solve's ports and this read resolve a structure through - and the walk
     * itself is {@link CarrierLightNetwork#collectComponent}, literally the call
     * {@code CarrierSpace#readerCells()} makes from its own host cell for the same purpose.
     *
     * <p>{@code null} - never an empty set - is the fallback the caller needs. An absent carrier bridge, a
     * structure whose pose cannot be read, a released carrier and a component that walks to nothing all have to
     * leave the exclusion exactly as it was: an unreadable structure must never darken a machine that works.
     */
    @Nullable
    private static Set<BlockPos> partnerOwnNetwork(ServerLevel level, @Nullable CarrierId partnerCarrier,
                                                   @Nullable BlockPos partnerCell) {
        if (level == null || partnerCarrier == null || partnerCell == null) {
            return null;
        }
        try {
            SeamLookup.CarriedFrame frame =
                    SeamLookup.of(level, InnerRedstoneStore.get(level)).frameOf(partnerCarrier);
            if (frame == null) {
                return null;
            }
            return partnerOwnCells(CarrierLightNetwork.collectComponent(frame.space(), partnerCell), partnerCell,
                    frame.space());
        } catch (RuntimeException | LinkageError unreadable) {
            // The carrier bridge is a bonus: a failure in it must look like a jar without it, and "no answer"
            // here means the exclusion stays the one the caller already had.
            return null;
        }
    }

    /**
     * <b>The narrowing both halves apply to a partner's own network, as one function</b>: a lossless wire-typed
     * cell is part of the node; everything else the walk reaches - a lever, a torch, ordinary dust - is a
     * <em>source</em> and stays a supply.
     *
     * <p>The counterpart of {@code CarrierSpace#readerCells()}'s own filter, cell for cell: that one asks the
     * carrier index ({@code slot != null && slot.type.isWire() && slot.type.hopCost() == 0}), this one asks the
     * space it is walking ({@link #lossless}), and both end by adding the host's own cell so an answer that
     * narrowed to nothing still excludes the old one cell and never less.
     *
     * <p>Level-free on purpose: the walk is handed in and the space answers only "does this cell hold something",
     * so a unit test drives the <b>real</b> {@link CarrierLightNetwork#collectComponent} of a <b>real</b>
     * {@code CarrierSpace} with no {@code ServerLevel} and no superconductor block state - the two things a test
     * cannot build.
     *
     * @return the narrowed set with {@code hostCell} in it, or {@code null} when there is no walk to narrow
     */
    @Nullable
    static Set<BlockPos> partnerOwnCells(@Nullable List<BlockPos> component, @Nullable BlockPos hostCell,
                                         @Nullable PowerEnvironment space) {
        if (component == null || component.isEmpty() || space == null || hostCell == null) {
            return null;
        }
        Set<BlockPos> cells = new HashSet<>();
        for (BlockPos cell : component) {
            PowerEnvironment.Node node = space.nodeAt(cell.getX(), cell.getY(), cell.getZ());
            if (node != null && lossless(node.type())) {
                cells.add(cell.immutable());
            }
        }
        cells.add(hostCell.immutable());
        return cells;
    }

    /** When the partner-network line was last written, for its wall-clock throttle. */
    private static long partnerNetworkAt;

    /** True at most once a second: one pair read runs thousands of times a second and one readable line is what a
     * reporter reproducing by hand needs. */
    private static boolean partnerNetworkDue() {
        long now = System.currentTimeMillis();
        if (now - partnerNetworkAt < 1000L) {
            return false;
        }
        partnerNetworkAt = now;
        return true;
    }

    /**
     * <b>One line saying whether the partner's own network was populated at all</b>, and with how many cells.
     *
     * <p>That is the number the next live report needs: "the exclusion came back empty" and "the exclusion was
     * filled and the value still stands" are two entirely different defects, and only this line tells them apart.
     * Gated on {@code debugLog} like every other per-change line, and throttled by wall clock.
     *
     * @param added the cells the partner's own network added to the exclusion, or {@code -1} when it could not be
     *     read at all - in which case the run's own network stands alone
     */
    private static void logPartnerOwnNetwork(@Nullable BlockPos partnerCell, int added) {
        if (!RCConfig.debugLog() || !partnerNetworkDue()) {
            return;
        }
        RCConfig.LOGGER.info("[redstonecircuit] partner network at {}: {} cell(s) added to the pair exclusion{}",
                partnerCell == null ? "none" : partnerCell.toShortString(), added,
                added < 0 ? " (unreadable - the run's own network alone is excluded)" : "");
    }

    /**
     * <b>The structure frame's own supply at one structure cell</b>: what the neighbours there push into it, read
     * around it rather than off it, with {@code excluded} left out.
     *
     * <p>Two tables, as everywhere else in this frame: a host of ours standing beside the cell is a <b>source</b>
     * there and answers from its record (a plot slot is not a block state), and everything else is the structure's
     * own block, read through the structure's own view - the same rule a machine's inside already reads its
     * neighbours with.
     */
    public static int structureSupplyAt(ServerLevel level, BlockPos cell, @Nullable BlockPos excluded) {
        return structureSupplyAt(level, cell, except(excluded, null));
    }

    /**
     * The same read with the reader's <b>whole network</b> left out - see {@link #supplyAroundExcept} - and with the
     * <b>partner's own network</b> left out beside it ({@link #withPartnerOwnNetwork}).
     *
     * <p>Both frames exclude the same set, mapped through the pose for the world half, so the two halves of one pair
     * can never exclude different things; and both are handed the union, because a member of the partner's own
     * network is part of the partner in <b>either</b> frame it can stand in. 一条光网络是一个节点: the run half now asks
     * the question the carrier half asks by construction, at the partner host's own cell.
     */
    public static int structureSupplyAt(ServerLevel level, BlockPos cell, @Nullable Set<BlockPos> excluded) {
        if (level == null || cell == null) {
            return 0;
        }
        CarrierId carrier = CarrierStore.structureOwnerAt(level, cell);
        BlockGetter blocks = carrier == null ? null : CarrierStore.blockViewOf(carrier);
        // THE PARTNER'S OWN NETWORK IS PART OF THE PARTNER. The host at this cell names its carrier, so that
        // carrier's own component walk adds every lossless wire-typed cell of its network - and the host's own cell
        // - to what this read leaves out. Nothing else moves: a lever, a torch, ordinary dust or a host of ANOTHER
        // structure beside the partner is still a supply, read in both frames exactly as before.
        excluded = withPartnerOwnNetwork(level, carrier, cell, excluded);
        int answer = supplyAroundExcept(cell, excluded,
                (side, direction) -> structureFrameSupplyAt(level, blocks, side, direction),
                worldFrameSupplyAround(level, carrier, excluded));
        logRunPairSides(level, cell, excluded, carrier, blocks, answer);
        return answer;
    }

    /**
     * <b>The run half's own view of one pair read: six sides, two frames, and what was left out.</b>
     *
     * <p>The counterpart of {@code CarrierSpace}'s PAIR SIDES line, and the reason it exists: the two halves of one
     * pair implement "the partner's own supply" separately, and a live log has already caught them giving different
     * answers for one host at one instant. Two lines about the same cell, each naming its frames and its exclusions,
     * is what turns that from a disagreement into a diff.
     *
     * <p>Diagnostics only: {@code debugLog}, one line a second, and every read made defensively - a diagnostics line
     * must never be able to take a solve down. {@code owner=null} means the level cannot say whose structure this
     * cell is, which alone makes the structure frame unreadable.
     */
    private static void logRunPairSides(ServerLevel level, BlockPos cell, @Nullable Set<BlockPos> excluded,
                                        @Nullable CarrierId carrier, @Nullable BlockGetter blocks, int answer) {
        if (!RCConfig.debugLog() || !runPairSidesDue()) {
            return;
        }
        StringBuilder line = new StringBuilder(180);
        for (Direction direction : Direction.values()) {
            BlockPos side = cell.relative(direction);
            boolean skipped = excluded != null && excluded.contains(side);
            int structure;
            int world;
            try {
                structure = blocks == null ? 0 : readSignal(blocks, side, direction);
            } catch (RuntimeException | LinkageError unreadable) {
                structure = -1;
            }
            try {
                world = carrier == null ? 0 : worldSignalAt(level,
                        CarrierProjection.project(CarrierStore.poseOf(level, carrier), side),
                        CarrierDirections.worldDirection(CarrierStore.poseOf(level, carrier), direction), carrier);
            } catch (RuntimeException | LinkageError unreadable) {
                world = -1;
            }
            line.append(' ').append(direction.getName()).append(skipped ? "[X]" : "")
                    .append(" s=").append(structure).append(" w=").append(world).append(';');
        }
        RCConfig.LOGGER.info("[redstonecircuit] RUN PAIR SIDES at {} (owner={}) answer={} ::{}", cell.toShortString(),
                carrier == null ? "null" : carrier.shortId(), answer, line);
    }

    /** When the run-side pair line was last written, for its wall-clock throttle. */
    private static long runPairSidesAt;

    /** True at most once a second, so one reproduction gives a readable trace. */
    private static boolean runPairSidesDue() {
        long now = System.currentTimeMillis();
        if (now - runPairSidesAt < 1000L) {
            return false;
        }
        runPairSidesAt = now;
        return true;
    }

    /**
     * One side of a structure cell, read in the <b>structure's own frame</b>: a host of ours standing there answers
     * from its record (a plot slot is not a block state), and everything else is the structure's own block.
     */
    private static int structureFrameSupplyAt(ServerLevel level, @Nullable BlockGetter blocks, BlockPos side,
                                              Direction direction) {
        try {
            CarrierProjection.ProjectedHost host = CarrierStore.structureHostAt(level, side);
            if (host != null && host.slot() != null) {
                return CarrierProjection.emittedTowardInStructureFrame(host, direction, false, false);
            }
        } catch (RuntimeException | LinkageError unreadable) {
            return 0;
        }
        return blocks == null ? 0 : readSignal(blocks, side, direction);
    }

    /**
     * <b>The other neighbourhood a host of a structure really has: the world it is drawn in.</b>
     *
     * <p>Asked because the two frames are the two halves of one answer - {@code CarrierSpace#externalSignal} reads
     * both for the host's own input, and a partner's own supply has to mean the same thing or the pair rule drops
     * every source that lives in the frame it does not ask. A vanilla redstone torch standing beside the drawn
     * block is exactly such a source: it is a block of the level and of no structure's own storage, so it appears
     * in this frame alone.
     *
     * <p>Two exclusions, both of them the pair's: the reader's own cell is skipped here as it is there (mapped
     * through the same pose), and a <b>superconducting wire</b> of ours is a node of the solve rather than a
     * supply, which is the rule {@code SuperconductingWireBlock#supplyAt} applies to its own neighbours.
     *
     * <p>The reader's own carrier is refused on every cell, so a host of this machine cannot arrive here a second
     * time as an uncharged copy; a host of <b>another</b> structure still answers, which is the seam.
     *
     * <p>{@code null} when there is no pose to ask through: an unreadable structure must never change a working
     * machine's answer, it only leaves the structure frame standing alone.
     */
    @Nullable
    private static SideSupply worldFrameSupplyAround(ServerLevel level, @Nullable CarrierId carrier,
                                                     @Nullable Set<BlockPos> excluded) {
        if (carrier == null) {
            return null;
        }
        CarrierPose pose;
        try {
            pose = CarrierStore.poseOf(level, carrier);
        } catch (RuntimeException | LinkageError unreadable) {
            return null;
        }
        if (pose == null) {
            return null;
        }
        Set<BlockPos> excludedWorld = null;
        if (excluded != null && !excluded.isEmpty()) {
            excludedWorld = new HashSet<>(excluded.size());
            for (BlockPos cell : excluded) {
                excludedWorld.add(CarrierProjection.project(pose, cell));
            }
        }
        final Set<BlockPos> skip = excludedWorld;
        return (side, direction) -> {
            BlockPos world = CarrierProjection.project(pose, side);
            if (skip != null && skip.contains(world)) {
                return 0;
            }
            if (SuperconductingWireBlock.isSuperconductor(level.getBlockState(world))) {
                return 0;
            }
            return worldSignalAt(level, world, CarrierDirections.worldDirection(pose, direction), carrier);
        };
    }

    /**
     * <b>The cells a pair read made from {@code runCell} must leave out, stated in the structure's frame.</b>
     *
     * <p>The exclusion is always the reader's own <em>network</em> ({@link #readerNetworkAt}), which stands in
     * world coordinates while {@link #projectedHostSupplyAt} asks at the host's own local coordinate - so the
     * network is carried across the pose one cell at a time, through the one place a world block becomes a
     * structure coordinate ({@link CarrierProjection#localBlockOf}). The run's own cell is added directly as well,
     * one step from the host along the side the run lies on: an inverse that cannot <em>prove</em> a coordinate
     * must never turn the pair's own term back on, and adding a cell to this set can only ever lower the answer.
     *
     * @param runToHost vanilla's direction as the wire's own read names it: <b>from the run towards the host</b>,
     *     which is the reverse of the inward->outward direction a side read is given - so it is turned round here
     *     once, instead of at every caller
     */
    public static Set<BlockPos> localPairExclusion(@Nullable CarrierPose pose, @Nullable BlockPos hostLocal,
                                                   @Nullable BlockPos runCell, @Nullable Direction runToHost) {
        Set<BlockPos> excluded = new HashSet<>();
        if (pose == null || hostLocal == null) {
            return excluded;
        }
        if (runToHost != null) {
            Direction hostToRun = CarrierDirections.localDirection(pose, runToHost.getOpposite());
            if (hostToRun != null) {
                excluded.add(hostLocal.relative(hostToRun).immutable());
            }
        }
        for (BlockPos cell : readerNetworkAt(runCell)) {
            BlockPos local = CarrierProjection.localBlockOf(pose, cell);
            if (local != null) {
                excluded.add(local.immutable());
            }
        }
        return excluded;
    }

    /**
     * <b>What the host beside a lossless run supplies that run: the host's own supply, with the run's own network
     * left out.</b>
     *
     * <p>One statement of the read, so the two halves of a projected pair cannot drift apart. A value is
     * {@code max(own supply, what the neighbours send)}, so dropping the reader's own term from that maximum can
     * only lower the answer; both frames a host of a structure really has are asked, because a real source lives
     * in one of them and never in both.
     */
    public static int projectedPairSupply(@Nullable BlockPos hostLocal, @Nullable Set<BlockPos> excluded,
                                          @Nullable SideSupply structureRead, @Nullable SideSupply worldRead) {
        return supplyAroundExcept(hostLocal, excluded, structureRead, worldRead);
    }

    /**
     * <b>The projection table's own-supply read: what the projected host is supplied with, at its own coordinate
     * and in both of its frames.</b>
     *
     * <p>The counterpart of {@link #structureSupplyAt}, one table over - and deliberately not that method: it
     * derives the owner from a level coordinate ({@code structureOwnerAt}), which answers nothing for a Create
     * contraption, whose local coordinates are 0..15 around its anchor and are not level positions. A projected
     * host names its own carrier outright, so the frames are its structure's own blocks (and hosts, through the
     * structure index) and the world the structure is drawn in, under the one exclusion
     * {@link #localPairExclusion} states - the same composition as {@link #structureSupplyAt}, with the owner
     * taken from the host rather than from the cell.
     *
     * <p>A view that cannot be resolved drops the structure half and keeps the world half; nothing here answers
     * zero for a host that a source feeds.
     */
    public static int projectedHostSupplyAt(ServerLevel level, CarrierProjection.ProjectedHost host,
                                            BlockPos runCell, Direction direction) {
        if (level == null || host == null || host.localPos() == null || host.pose() == null) {
            return 0;
        }
        Set<BlockPos> excluded = localPairExclusion(host.pose(), host.localPos(), runCell, direction);
        // THE SAME RULE, one table over: a projected host names its carrier too, so its own network joins the
        // exclusion in the structure's own frame - and the pose above is what carries it into the world half, cell
        // for cell, exactly as the run's own cells are carried.
        excluded = withPartnerOwnNetwork(level, host.carrier(), host.localPos(), excluded);
        BlockGetter blocks = CarrierStore.blockViewOf(host.carrier());
        return projectedPairSupply(host.localPos(), excluded,
                blocks == null
                        ? null
                        : (side, sideDirection) -> structureFrameSupplyAt(level, blocks, side, sideDirection),
                worldFrameSupplyAround(level, host.carrier(), excluded));
    }

    /**
     * One line naming a projected pair read: the run's cell, the host, and the two answers that matter - what the
     * <em>stored</em> read would have offered, and what the host's own supply is. Gated on {@code debugLog} like
     * every other per-change line, and the stored half is asked only once the line is known to be written.
     */
    private static void logProjectedPairRead(BlockPos runCell, CarrierProjection.ProjectedHost host,
                                             Direction direction, int ownSupply) {
        if (!RCConfig.debugLog()) {
            return;
        }
        int stored = CarrierProjection.emittedToward(host, direction, false, false);
        RCConfig.LOGGER.info("[redstonecircuit] projected pair read at {} -> {} side={}: own supply {} "
                        + "(the stored read would have offered {})",
                runCell.toShortString(), host.describe(), direction.getName(), ownSupply, stored);
    }

    /** The host the projection table holds at a world cell, or {@code null}; a failure looks like no bridge. */
    @Nullable
    private static CarrierProjection.ProjectedHost projectedHostOrNull(ServerLevel level, BlockPos worldPos) {
        try {
            return CarrierStore.projectedHostAt(level, worldPos);
        } catch (RuntimeException | LinkageError unreadable) {
            return null;
        }
    }

    /** What the structure index holds at a structure coordinate, or {@code null} when it holds nothing there. */
    @Nullable
    private static ComponentType structureIndexHostTypeOrNull(ServerLevel level, BlockPos structurePos) {
        try {
            CarrierProjection.ProjectedHost indexed = CarrierStore.structureHostAt(level, structurePos);
            return indexed == null || indexed.slot() == null ? null : indexed.slot().type;
        } catch (RuntimeException | LinkageError unreadable) {
            return null;
        }
    }

    /**
     * <b>What the neighbour beside a lossless run offers it</b>: the pair's own-supply read when the two are
     * one lossless pair - in the structure index <b>or</b> in the projection table - and the neighbour's stock
     * strength otherwise; never a stored copy across a pair, and never the stored value of a wire inside a block,
     * which is a node of somebody's solve rather than a supply.
     *
     * <p>The run side of the rule in one function, so the read and the rule cannot drift apart. Three questions in
     * one order, and the order is the rule: the structure index's pair first (it is the machine's own surface and
     * works today), then the projection table's pair (same rule, a host that is being drawn somewhere), then the
     * refusal of a wire inside a block, then the stock read every other neighbour keeps. See
     * {@link #losslessPairInOneStructure} for why a pair takes the other read, and {@link #supplyAroundExcept} for
     * what it costs.
     */
    public static int supplyFromNeighbour(Level level, BlockPos runCell, BlockPos neighbour,
                                          Direction direction, @Nullable BlockState runState) {
        if (level instanceof ServerLevel server) {
            if (sameStructureLosslessPair(level, runCell, neighbour, runState)) {
                // Not "without this cell" but "without this network": a run can bite the same host from several cells,
                // and excluding only the one the read is made from leaves every longer loop standing.
                return structureSupplyAt(server, neighbour, readerNetworkAt(runCell));
            }
            ComponentType runType =
                    runState != null && SuperconductingWireBlock.isSuperconductor(runState)
                            ? ComponentType.SUPERCONDUCTOR
                            : null;
            CarrierProjection.ProjectedHost projected =
                    runType == null ? null : projectedHostOrNull(server, neighbour);
            if (projected != null && projected.slot() != null
                    && projectedLosslessPair(runType, structureIndexHostTypeOrNull(server, neighbour), projected)) {
                int own = projectedHostSupplyAt(server, projected, runCell, direction);
                logProjectedPairRead(runCell, projected, direction, own);
                return own;
            }
        }
        if (carriedHoldsWire(level, neighbour)) {
            // A wire inside a host block is a NODE of somebody's solve, never a supply for the run beside it: taken
            // as one it is the pair's own value at gain exactly one, which is the latch this file is about. The two
            // pair reads above are the one exception, and they are asked first.
            return 0;
        }
        return level.getSignal(neighbour, direction);
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

    // ------------------------------------------------------- the merged solve --

    /**
     * One solve's whole subject: the hosts whose components it owns, and the <b>world-side</b> superconducting
     * runs merged into them.
     *
     * <p>Kept together because neither half may be solved alone. A run laid against a host block is read by
     * whatever is inside that block and reads it back, and in between the two there is no hop to charge - a
     * lossless wire on both sides of one boundary is a loop of gain exactly one, so <em>every</em> pair of
     * values is a fixed point of "solve each side from the other's current value" and fifteen stays fifteen
     * with the lever off. Merging turns that loop into a single monotone solve whose least fixed point is
     * reached from zero.
     */
    record Union(List<BlockPos> hosts, List<RunNode> runs, List<PortNode> ports,
                 Map<BlockPos, PowerEnvironment.Node> cells) {
    }

    /** One node meeting one cell of a run or a port: the side it emits on, read fresh on every pass. */
    private record Arrival(PowerEnvironment.Node node, Direction towardCell) {
    }

    /**
     * One world-side run of superconducting wire, as a <b>single node</b> of the merged solve.
     *
     * <p>One node rather than one per cell because a run is lossless by definition - every cell of it holds
     * the same strength, which is what the standalone world solve writes - so splitting it up would only add
     * hops that cost nothing. The cells that are not face-adjacent ({@code /rc} climbs and drops, where the
     * wire steps up beside a block) are exactly the ones this collapsing keeps faithful.
     *
     * <p>{@link #value} starts at zero and is only ever raised by {@link #target()}. It is deliberately
     * <b>not</b> read from the {@code POWER} of the cells it covers: that value is what this solve produces,
     * so taking it as an input would make it its own answer, which is the latch this class removes.
     */
    static final class RunNode implements PowerEnvironment.Node {

        private final List<BlockPos> cells;

        /** The inner wires whose value arrives here - one entry per side of a cell that holds a wire. */
        private final List<Arrival> arrivals = new ArrayList<>();

        /** The best real source anywhere in the run. Never a host holding a wire: that value is solved here. */
        private int supply;

        private int value;

        RunNode(List<BlockPos> cells) {
            this.cells = cells;
        }

        List<BlockPos> cells() {
            return cells;
        }

        int value() {
            return value;
        }

        /**
         * Reads what feeds this run, once: the world's own sources, and which inner wires it touches.
         *
         * <p>Read once rather than per pass because none of it can change while the solve runs - the solve
         * only ever writes wire values and, at the end, this one strength.
         */
        void connect(SeamLookup seam, Map<BlockPos, PowerEnvironment.Node> union) {
            for (BlockPos cell : cells) {
                supply = Math.max(supply, seam.supplyAt(cell));
                for (Direction direction : Direction.values()) {
                    BlockPos world = cell.relative(direction);
                    PowerEnvironment.Node node = union.get(world);
                    if (node == null) {
                        Slot held = seam.slotAt(world);
                        if (held != null) {
                            node = SlotNode.of(held);
                        }
                    }
                    if (node != null && node.type().isWire()) {
                        arrivals.add(new Arrival(node, direction.getOpposite()));
                    }
                }
            }
            supply = PowerSolver.clamp(supply);
        }

        /**
         * What this run should hold right now, given what is around it.
         *
         * <p>A hop out of an inner wire into the run is charged to the run, which is superconducting and so
         * charges nothing; a hop out of the run into an inner wire is charged by {@link PowerSolver#innerFrom}
         * to that wire. The rule is one rule in both directions - the receiver pays - and it is the reason the
         * pair cannot hold itself up: an ordinary dust inside the block loses a step on the way in.
         */
        int target() {
            int best = supply;
            for (Arrival arrival : arrivals) {
                if (!PowerSolver.emitsToward(arrival.node(), arrival.towardCell())) {
                    continue;
                }
                best = Math.max(best, PowerSolver.afterHop(
                        ComponentType.SUPERCONDUCTOR, arrival.node().type(), arrival.node().power()));
            }
            return PowerSolver.clamp(best);
        }

        /** Raises the run to its target; true when that changed it. */
        boolean raise() {
            int target = target();
            if (target <= value) {
                return false;
            }
            value = target;
            return true;
        }

        @Override
        public ComponentType type() {
            return ComponentType.SUPERCONDUCTOR;
        }

        @Override
        public int power() {
            return value;
        }

        @Override
        public void setPower(int power) {
            value = PowerSolver.clamp(power);
        }

        @Override
        public Direction facing() {
            // A wire has no input side; nothing in the rules asks a wire which way it faces.
            return Direction.DOWN;
        }

        @Override
        public ComparatorMode mode() {
            return ComparatorMode.COMPARE;
        }

        @Override
        public boolean open(Direction direction) {
            return false;
        }

        @Override
        public boolean explicitlyCut(Direction direction) {
            return false;
        }

        @Override
        public boolean routingLocked() {
            return false;
        }
    }

    /**
    /**
     * One <b>carried lossless network</b> - a whole component of one structure, every cell of which charges
     * nothing for a hop - as a <b>single node</b> of the merged solve.
     *
     * <p>This is the seam defect itself. A carried network and the world network beside it were solved twice,
     * each taking the other's <em>last answer</em> as a supply. Two wires that both charge nothing read each
     * other at gain exactly one, so the pair's equations are {@code A = B} and {@code B = A}, every value is a
     * fixed point, and a strength nothing supplies any more stands there for ever. Joining the carried network
     * to the world's own solve makes the two halves <b>one</b> monotone system whose least fixed point is
     * reached from zero, so the latch does not exist by construction - no rule has to decide whose supply is
     * the real one, and a ring of three structures is no harder than a pair.
     *
     * <p>One node rather than one per cell, for the reason {@link RunNode} is one node: every cell of a
     * lossless component holds the same strength, so splitting it would only add hops that cost nothing.
     *
     * <p>{@link #value} starts at zero and is only ever raised, and it is deliberately <b>not</b> read from the
     * slots it covers: that value is what this solve produces, so taking it as an input would be the latch
     * again. What feeds it is read once by {@link #connect} - before the solve clears anything - and the loop
     * after that reads no world at all.
     */
    static final class PortNode implements PowerEnvironment.Node {

        private final CarrierId carrier;

        /** The component's cells in that structure's own frame - the coordinates its environment speaks. */
        private final List<BlockPos> cells;

        private final PowerEnvironment space;
        private final CarrierPose pose;

        /** Where every cell of the network is <b>drawn</b>: the union's own coordinates for it. */
        private final List<BlockPos> worldCells;

        /** The union's own nodes this network stands beside; read once, like everything else here. */
        private final List<Arrival> arrivals = new ArrayList<>();

        /** The best real source around it, taken only from the sides this solve does not own. */
        private int supply;

        private int value;

        PortNode(CarrierId carrier, List<BlockPos> cells, PowerEnvironment space, CarrierPose pose) {
            this.carrier = carrier;
            this.cells = cells;
            this.space = space;
            this.pose = pose;
            List<BlockPos> projected = new ArrayList<>(cells.size());
            for (BlockPos cell : cells) {
                projected.add(CarrierProjection.project(pose, cell));
            }
            this.worldCells = List.copyOf(projected);
        }

        CarrierId carrier() {
            return carrier;
        }

        List<BlockPos> cells() {
            return cells;
        }

        /** The world coordinates this network stands on, which is where the union's nodes meet it. */
        List<BlockPos> worldCells() {
            return worldCells;
        }

        int value() {
            return value;
        }

        /**
         * Reads what feeds this network, once: the union's own nodes it stands beside, and - only on the sides
         * the union does <b>not</b> own - what the structure's own environment answers there.
         *
         * <p>The exclusion is the whole point. A side that projects onto a cell this solve owns is an <em>edge
         * of the merged solve</em>, already accounted for by the arrival above it; reading it here as well
         * would put the neighbour's stored answer back into the equations this class exists to remove, which
         * is exactly the latch one level down.
         */
        void connect(SeamLookup seam, Map<BlockPos, PowerEnvironment.Node> union) {
            for (BlockPos cell : cells) {
                for (Direction direction : Direction.values()) {
                    BlockPos world = CarrierProjection.project(pose, cell.relative(direction));
                    PowerEnvironment.Node node = union.get(world);
                    if (node == null) {
                        Slot held = seam.slotAt(world);
                        if (held != null) {
                            node = SlotNode.of(held);
                        }
                    }
                    if (node != null) {
                        arrivals.add(new Arrival(node, direction.getOpposite()));
                        continue;
                    }
                    space.setQueryPosition(cell);
                    supply = Math.max(supply, space.externalSignal(direction));
                }
            }
            supply = PowerSolver.clamp(supply);
        }

        /** What this network should hold now: the same rule a run uses, over the same kind of arrivals. */
        int target() {
            int best = supply;
            for (Arrival arrival : arrivals) {
                if (!PowerSolver.emitsToward(arrival.node(), arrival.towardCell())) {
                    continue;
                }
                best = Math.max(best, PowerSolver.afterHop(
                        ComponentType.SUPERCONDUCTOR, arrival.node().type(), arrival.node().power()));
            }
            return PowerSolver.clamp(best);
        }

        boolean raise() {
            int target = target();
            if (target <= value) {
                return false;
            }
            value = target;
            return true;
        }

        /** Writes the settled strength into every cell the network covers. */
        void write() {
            for (BlockPos cell : cells) {
                PowerEnvironment.Node node = space.nodeAt(cell.getX(), cell.getY(), cell.getZ());
                if (node != null) {
                    node.setPower(value);
                }
            }
        }

        @Override
        public ComponentType type() {
            // Only a component whose every cell charges nothing is joined, so the one type that matters for
            // the arithmetic - "a hop into or out of this costs nothing" - is stated here.
            return ComponentType.SUPERCONDUCTOR;
        }

        @Override
        public int power() {
            return value;
        }

        @Override
        public void setPower(int power) {
            value = PowerSolver.clamp(power);
        }

        @Override
        public Direction facing() {
            return Direction.DOWN;
        }

        @Override
        public ComparatorMode mode() {
            return ComparatorMode.COMPARE;
        }

        @Override
        public boolean open(Direction direction) {
            return false;
        }

        @Override
        public boolean explicitlyCut(Direction direction) {
            return false;
        }

        @Override
        public boolean routingLocked() {
            return false;
        }
    }

    /**
     * The component plus every world-side run wired into it, transitively.
     *
     * <p>A run touching a host block joins the solve, and so does every component the run touches - two
     * separate buried networks on one length of superconducting wire are one network, and solving them one at
     * a time would put the run back to being each other's stored input. The walk ends because both halves
     * only ever grow.
     *
     * <p><b>And the walk runs from a run as well as from a host</b> ({@link #collectUnionForRun}): a run laid
     * against a machine rather than against a host block of the level is the same pair seen from the other
     * side, and it must reach the same solve or the two stand each other up for ever.
     *
     * <p><b>The carried half joins too.</b> A host another structure is carrying at a cell of this solve
     * ({@link SeamLookup#carriedAt}) is a port: its whole lossless component is pulled in as one
     * {@link PortNode}, transitively, because pulling only the port would leave the rest of its network to the
     * structure's own pass and close the ring one cell in. Four things refuse a join, and every one of them
     * leaves that seam on the narrow rule ({@code worldSignalIntoOwnFrame}) rather than answering zero: no
     * projection or no host there; a structure whose pose cannot be read; a component that is empty because the
     * carrier was released; and a component holding anything that is not a lossless wire - including a driven
     * component, whose pending flips live in per-carrier state this solve cannot reach.
     */
    static Union collectUnion(SeamLookup seam, List<BlockPos> component) {
        return collectUnionFrom(seam, List.copyOf(component), List.of());
    }

    /**
     * The same union with a <b>world-side run</b> as its subject instead of a host block.
     *
     * <p>A run laid against a network a structure is carrying has no host block of its own - the machine is
     * drawn over air - so the host walk never reaches it, and solving it on its own leaves the two lossless
     * wires reading each other at gain exactly one: every value is a fixed point, and a strength nothing
     * supplies any more stands there for ever. Everything the run reaches joins, exactly as it would from a
     * host: the store's components beside it and the carried networks beside it.
     */
    static Union collectUnionForRun(SeamLookup seam, List<BlockPos> run) {
        return collectUnionFrom(seam, List.of(), List.copyOf(run));
    }

    /**
     * The same union with a <b>carried</b> network as its subject: the walk the other two entries run, started
     * from one structure's own lossless component instead of from a world store slot or a world-side run.
     *
     * <p>Everything after the seed is the existing walk, unchanged: the port's projected neighbour cells join the
     * level's runs and hosts, another carrier's projected host beside them joins as a further port, and a
     * structure-coordinate neighbour joins the same way. So the pair is one solve, and a ring of three is no
     * harder than a pair - which is the whole point of joining rather than of rating one half's supply.
     */
    static Union collectCarriedUnion(SeamLookup seam, CarrierId carrier, BlockPos localSeed) {
        Walk walk = new Walk(seam, List.of());
        walk.joinCarried(carrier, localSeed);
        boolean grew = true;
        while (grew) {
            grew = walk.drain();
        }
        return walk.union();
    }

    private static Union collectUnionFrom(SeamLookup seam, List<BlockPos> component, List<BlockPos> seedRuns) {
        Walk walk = new Walk(seam, component);
        for (BlockPos cell : seedRuns) {
            walk.joinRun(cell);
        }
        // Both halves only ever grow, so the walk ends - and they are one walk, because they feed each other: a
        // host is laid against runs, a run is laid against hosts and against carried networks, and a run the
        // second finds can touch a host block the first has not visited yet.
        boolean grew = true;
        while (grew) {
            grew = walk.drain();
        }
        return walk.union();
    }

    /**
     * One {@link #collectUnionFrom} walk: everything it has taken in so far, and everything still to ask about.
     *
     * <p>A class rather than a dozen parameters, because the walk is one closure over one growing set of facts,
     * and the three questions it asks - "is this a run", "who stands here", "what does that run touch" - are
     * asked of the same state.
     */
    private static final class Walk {

        private final SeamLookup seam;
        private final List<BlockPos> hosts;
        private final Set<BlockPos> known;
        private final List<RunNode> runs = new ArrayList<>();
        /** Run cell to the run holding it: one run is one node, so every cell of it maps to the same one. */
        private final Map<BlockPos, RunNode> cells = new HashMap<>();
        /** Host blocks still to look around, and world cells still to ask "who stands here". */
        private final Deque<BlockPos> queue = new ArrayDeque<>();
        private final Deque<BlockPos> frontier = new ArrayDeque<>();
        private final List<PortNode> ports = new ArrayList<>();
        /** Structures already joined, so one carrier is pulled in once however many of its cells are beside us. */
        private final Set<CarrierId> joined = new HashSet<>();
        private final Set<BlockPos> asked = new HashSet<>();

        Walk(SeamLookup seam, List<BlockPos> component) {
            this.seam = seam;
            this.hosts = new ArrayList<>(component);
            this.known = new HashSet<>(component);
            this.queue.addAll(component);
        }

        /** One round of both loops; true when the round took something in that it did not have before. */
        boolean drain() {
            boolean grew = false;
            while (!queue.isEmpty()) {
                BlockPos host = queue.poll();
                for (Direction direction : Direction.values()) {
                    BlockPos cell = host.relative(direction);
                    frontier.add(cell);
                    grew |= joinRun(cell);
                }
            }
            while (!frontier.isEmpty()) {
                BlockPos world = frontier.poll();
                if (!asked.add(world)) {
                    continue;
                }
                grew |= joinRun(world);
                grew |= joinPort(world);
            }
            return grew;
        }

        /**
         * Takes the run at {@code cell} in as one node, and every component it is laid against; false when
         * there is nothing new there.
         *
         * <p>One node rather than one per cell because a run is lossless by definition - every cell of it holds
         * the same strength, which is what the standalone world solve writes - so splitting it up would only
         * add hops that cost nothing. The cells that are not face-adjacent ({@code /rc} climbs and drops, where
         * the wire steps up beside a block) are exactly the ones this collapsing keeps faithful.
         */
        boolean joinRun(BlockPos cell) {
            if (cells.containsKey(cell) || !seam.runAt(cell)) {
                return false;
            }
            List<BlockPos> run = seam.runCells(cell);
            if (run.isEmpty()) {
                return false;
            }
            RunNode node = new RunNode(run);
            runs.add(node);
            for (BlockPos member : run) {
                cells.put(member, node);
                frontier.add(member);
                for (Direction direction : Direction.values()) {
                    BlockPos other = member.relative(direction);
                    frontier.add(other);
                    joinWorldHost(other);
                }
            }
            return true;
        }

        /**
         * Takes in the world component standing at {@code world}, when a host block of the level stands there;
         * false when the store holds nothing, or when that component is already ours.
         *
         * <p>Asked of a <b>run cell</b> and of a <b>port's cell</b> alike. The two tables that place a component
         * of ours never share a coordinate, so a walk that asks this question of runs alone leaves a block of
         * the level that stands across a carried network outside the solve - and the port then reads that
         * block's stored strength back in as an arrival, at gain exactly one. Which of the two cells happens to
         * seed the solve would decide whether the latch exists, and it must not: one arrangement has one answer.
         */
        boolean joinWorldHost(BlockPos world) {
            if (seam.slotAt(world) == null || known.contains(world)) {
                return false;
            }
            for (BlockPos reached : seam.componentAt(world)) {
                if (known.add(reached)) {
                    hosts.add(reached);
                    queue.add(reached);
                }
            }
            return true;
        }

        /**
         * Takes in the carried network standing at {@code world}, when there is one and it can be reached;
         * false otherwise.
         *
         * <p>Four refusals, every one of them leaving that seam on the narrow rule
         * ({@code worldSignalIntoOwnFrame}) rather than answering zero: no projection or no host there; a
         * structure whose pose cannot be read; a component that is empty because the carrier was released; and
         * a component holding anything that is not a lossless wire - including a driven component, whose
         * pending flips live in per-carrier state this solve cannot reach.
         */
        boolean joinPort(BlockPos world) {
            CarrierProjection.ProjectedHost port = seam.carriedAt(world);
            if (port == null || port.slot() == null || !lossless(port.slot().type)) {
                return false;
            }
            return joinCarried(port.carrier(), port.localPos());
        }

        /**
         * <b>Takes in the carried network a host of ANOTHER carrier stands on at a structure coordinate</b> -
         * the join the projection table cannot make.
         *
         * <p>Asked only from inside a port's own frame, and only for a sub-level, because that is the only
         * frame in which a local coordinate <b>is</b> a level coordinate ({@code CarrierStore#storageInLevel}):
         * for a Create contraption the local coordinate is relative to its anchor and names no level cell at
         * all, so the question would be about somebody else's air. What it finds is the case two frames over one
         * integer make: the cell this port is laid against belongs to <em>this</em> structure's plot and holds a
         * host of another carrier, whose only name is that plot coordinate.
         *
         * <p>Same refusal as {@link #joinPort}: a host that is not a lossless wire, a structure that cannot be
         * reached, a component that is empty because the carrier was released, and a component holding anything
         * that is not a lossless wire all leave the seam on the narrow rule rather than answering zero - and the
         * carrier this port belongs to is already in {@link #joined}, so a host at one's own coordinate is
         * refused by the same set that refuses a second join of the same structure.
         */
        boolean joinStructureHost(BlockPos structurePos) {
            CarrierProjection.ProjectedHost host = seam.structureHostAt(structurePos);
            if (host == null || host.slot() == null || !lossless(host.slot().type)) {
                return false;
            }
            return joinCarried(host.carrier(), host.localPos());
        }

        /**
         * One carried lossless network as one {@link PortNode}, whichever table named it.
         *
         * <p>Split out of {@link #joinPort} when the structure-coordinate join arrived, because the two tables
         * answer the same four questions - may this structure be reached, what does its component cover, is
         * every cell of it lossless, and what does it stand beside - and a second copy of that gate is a second
         * rule about what a seam may be. The gates are unchanged, in the same order: {@link #joined} first,
         * because one carrier is pulled in once however many of its cells are beside us; then the frame, which
         * is the refusal of a structure whose pose cannot be read or that has been released; then the
         * all-lossless test, which is the refusal of a mixed or driven component.
         */
        boolean joinCarried(CarrierId carrier, BlockPos localStart) {
            if (!joined.add(carrier)) {
                return false;
            }
            SeamLookup.CarriedFrame frame = seam.frameOf(carrier);
            List<BlockPos> own = frame == null
                    ? List.of()
                    : CarrierLightNetwork.collectComponent(frame.space(), localStart);
            if (frame == null || own.isEmpty() || !allLossless(frame.space(), own)) {
                joined.remove(carrier);
                return false;
            }
            // The solve starts every wire from zero, and a port is no exception: the value of these cells is what
            // this solve produces, so a value left standing here would be an input the least-fixed-point loop
            // never asked for - the same gain-one pair, one level down.
            for (BlockPos local : own) {
                PowerEnvironment.Node held = frame.space().nodeAt(local.getX(), local.getY(), local.getZ());
                if (held != null && held.type().isWire()) {
                    held.setPower(0);
                }
            }
            PortNode node = new PortNode(carrier, own, frame.space(), frame.pose());
            ports.add(node);
            // A sub-level's own coordinate is a level coordinate, so the cell beside a port can hold a host of
            // another carrier that only the STRUCTURE table names. Asked before the projection table, because the
            // two are different cells on purpose: the one below is where this port is DRAWN, this one is where it
            // IS.
            boolean structureFrameIsTheLevel = carrier.isSubLevel();
            for (BlockPos local : own) {
                for (Direction direction : Direction.values()) {
                    BlockPos besideLocal = local.relative(direction);
                    if (structureFrameIsTheLevel) {
                        joinStructureHost(besideLocal);
                    }
                    BlockPos beside = CarrierProjection.project(frame.pose(), besideLocal);
                    frontier.add(beside);
                    // The other half of the same question a run cell asks: a block of the level standing on the
                    // far side of this port is part of the same network, not a supply standing outside it.
                    joinWorldHost(beside);
                }
            }
            return true;
        }

        /** What the walk stands on: the hosts whose components this solve owns, and every node it covers. */
        Union union() {
            Map<BlockPos, PowerEnvironment.Node> nodes = new HashMap<>(cells);
            // A port stands where a machine is <b>drawn</b>, and the level holds air there, so a port's cell can
            // never be one of the run cells above: the two tables cannot disagree about one coordinate.
            for (PortNode port : ports) {
                for (BlockPos world : port.worldCells()) {
                    nodes.put(world, port);
                }
            }
            for (RunNode run : runs) {
                run.connect(seam, nodes);
            }
            for (PortNode port : ports) {
                port.connect(seam, nodes);
            }
            return new Union(List.copyOf(hosts), List.copyOf(runs), List.copyOf(ports), Map.copyOf(nodes));
        }
    }

    /** True for a wire that charges nothing for a hop - the only kind of network a seam may join. */
    private static boolean lossless(@Nullable ComponentType type) {
        return type != null && type.isWire() && type.hopCost() == 0;
    }

    /**
     * True when every cell of the component is such a wire, so the whole network holds one strength.
     *
     * <p>A mixed component - a superconductor beside a piece of dust - and a component holding a driven
     * component are both refused here, and the refusal is the <b>whole frame</b>: half a network solved here
     * and half by the structure's own pass is the defect this class removes, one cell in.
     */
    private static boolean allLossless(PowerEnvironment space, List<BlockPos> cells) {
        for (BlockPos cell : cells) {
            PowerEnvironment.Node node = space.nodeAt(cell.getX(), cell.getY(), cell.getZ());
            if (node == null || !lossless(node.type())) {
                return false;
            }
        }
        return true;
    }

    /**
     * Re-derives the component's wire values and schedules any driven component that is stale.
     *
     * <p>The solve covers the component <b>and every world-side superconducting run that touches a host
     * block of it</b> ({@link #collectUnion}). That is what makes the two sides one network: a run is not a
     * source for the wire inside the block next to it, and the inner wire is not a source for the run - each
     * is a node of the same monotone solve, which starts from zero and is only ever raised by a real supply.
     *
     * @return the positions whose output changed <em>immediately</em> (dust only - a driven component
     *     changes later, at its scheduled tick, and reports itself then)
     */
    private static List<BlockPos> relax(ServerLevel level, InnerRedstoneStore store, Union union) {
        List<BlockPos> component = union.hosts();
        SeamLookup seam = SeamLookup.of(level, store);
        SolverEnvironment env = new SolverEnvironment(level, store);
        env.owning(union.cells());

        Map<BlockPos, Integer> previous = settleUnion(seam, env, union);

        List<BlockPos> changedPositions = new ArrayList<>();
        for (BlockPos pos : component) {
            Slot slot = seam.slotAt(pos);
            if (slot == null) {
                continue;
            }
            int before = previous.get(pos);
            if (before != slot.power) {
                changedPositions.add(pos);
                env.setQuery(pos, slot.type);
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
     * The merged solve itself: every wire of the union restarts from zero and is only ever raised, every
     * world-side run is raised the same way, and the runs are written back before anything is told about the
     * change.
     *
     * <p>Level-free on purpose. The only world questions this loop and its environment ask are
     * {@link SeamLookup}'s, and the only solve-shaped ones are {@link UnionEnvironment}'s, so the same
     * arithmetic can be run over a test double - which is what makes "two structures on one world ring"
     * testable without a game.
     *
     * <p><b>The trigger, in both directions.</b> The world side reaches this from {@link #recompute} (a
     * component of the store) and from {@link #settleCarriedRun} (a world-side run laid against a machine), and
     * both need a subject <em>in the world</em>: {@link #settle} visits only positions the level marked dirty
     * <em>and</em> whose {@code store.slotAt} is non-null, and a host riding a structure is in neither set. So
     * two machines facing each other - nothing in the store anywhere near them - used to assemble no union at
     * all, and the narrow seam rule ({@code worldSignalIntoOwnFrame}) answered the pair. The carrier side is the
     * half that owns those cells, and its per-tick pass now asks the one question no single structure's pass can
     * ({@link #settleCarriedUnion}): whether two of them are one network. Once per pass, keyed by the carriers
     * that pass covered, so a pair is assembled in the tick it is live and never twice in one tick.
     *
     * @return what each host of the union held <em>before</em> the solve, because the solve starts each wire
     *     from zero and "did anything change?" is a question about where it was
     */
    static Map<BlockPos, Integer> settleUnion(SeamLookup seam, UnionEnvironment env, Union union) {
        List<BlockPos> component = union.hosts();
        // The wire solve starts from zero, so "did anything change?" has to be answered against where
        // the component was before. Driven components are NOT reset: their output is held until their
        // own scheduled tick replaces it.
        Map<BlockPos, Integer> previous = new HashMap<>(component.size() * 2);
        for (BlockPos pos : component) {
            Slot slot = seam.slotAt(pos);
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
        int maxPasses = 2 + Math.min(component.size() + union.runs().size(), MAX_FIXPOINT_PASSES);
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
                Slot slot = seam.slotAt(pos);
                if (slot == null || !slot.type.isWire()) {
                    continue;
                }
                env.setQuery(pos, slot.type);
                int target = PowerSolver.wireTarget(env, pos, supplyOf(env, slot), SlotNode.of(slot));
                if (target > slot.power) {
                    slot.power = target;
                    changed = true;
                }
            }
            // ...and the world-side runs, whose only supply is a real one: the value they hold is what this
            // loop computes, so the block state is read nowhere and starts from zero like every wire here.
            for (RunNode run : union.runs()) {
                if (run.raise()) {
                    changed = true;
                }
            }
            // ...and the carried networks a seam has joined: same rule, same starting point at zero.
            for (PortNode port : union.ports()) {
                if (port.raise()) {
                    changed = true;
                }
            }
        }

        // Written before anything is told about the change, so a consumer woken by the inner half already
        // sees the outer half's new value rather than the one it is replacing.
        for (RunNode run : union.runs()) {
            seam.writeRun(run.cells(), run.value());
        }
        for (PortNode port : union.ports()) {
            port.write();
        }
        return previous;
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
     *
     * <p>A component held still by a diode beside it is not stale and is not queued: {@link
     * PowerSolver#desiredOutput} answers what it already holds, so the comparison below finds nothing to do.
     * That is the same early return vanilla's {@code checkTickOnNeighbor} makes for a locked repeater.
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
            // The strength it is heading for travels with the entry: a pulse shorter than the delay has no input
            // left to read by the time this tick arrives, and vanilla still turns the component on.
            TickPriority priority = PowerSolver.tickPriority(env, pos, SlotNode.of(slot));
            if (pending.schedule(pos, level.getGameTime() + delay, priority, desired) && RCConfig.debugLog()) {
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
    // Also the walk SeamLookup#componentAt delegates to, so a fake can answer "which component does
    // this seed belong to" without a level.
    static List<BlockPos> collectComponent(ServerLevel level, InnerRedstoneStore store,
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
    private static final class SolverEnvironment implements UnionEnvironment {

        private final ServerLevel level;
        private final InnerRedstoneStore store;

        /** The one world-signal reader; see {@link InnerRedstoneNetwork#worldSignals}. */
        private final CarrierWorldSignals world;

        /** The position currently being solved, needed because the queries here are position-relative. */
        private BlockPos queryPos = BlockPos.ZERO;

        /**
         * True while the position being queried holds a <b>wire</b>: only a wire takes what reaches it from a
         * neighbouring node as a hop, so only a wire is told nothing about a run this solve owns.
         */
        private boolean queryingWire;

        /** The world-side runs this solve owns; empty for every other caller of this environment. */
        private Map<BlockPos, PowerEnvironment.Node> owned = Map.of();

        SolverEnvironment(ServerLevel level, InnerRedstoneStore store) {
            this.level = level;
            this.store = store;
            this.world = InnerRedstoneNetwork.worldSignals(level);
        }

        void setQueryPos(BlockPos pos) {
            this.queryPos = pos;
            this.queryingWire = false;
        }

        /** As {@link #setQueryPos}, naming the component that is asking: only a wire takes a node as a hop. */
        public void setQuery(BlockPos pos, @Nullable ComponentType reader) {
            this.queryPos = pos;
            this.queryingWire = reader != null && reader.isWire();
        }

        /** The world cells the solve owns - see {@link Union}. */
        public void owning(Map<BlockPos, PowerEnvironment.Node> cells) {
            this.owned = cells;
        }

        @Override
        public void setQueryPosition(BlockPos pos) {
            setQueryPos(pos);
        }

        @Override
        public Node nodeAt(int x, int y, int z) {
            BlockPos pos = new BlockPos(x, y, z);
            Node run = owned.get(pos);
            return run == null ? SlotNode.of(store.slotAt(pos)) : run;
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
            return externalSignal(direction, true);
        }

        /**
         * The same read, with no hop charged to a neighbouring vanilla redstone wire.
         *
         * <p>What a comparator's side input reads with: vanilla's {@code RedStoneWireBlock#getSignal} answers a
         * comparator's side face with the wire's own strength, so charging the hop here is what made our
         * subtract one too weak. Every other caller keeps the charged read through
         * {@link #externalSignal(Direction)}; the projected half is the same pair of answers either way.
         */
        @Override
        public int externalSignalUncharged(Direction direction) {
            return externalSignal(direction, false);
        }

        /** The one body of both reads: which half of the pair a vanilla wire is charged on is the only difference. */
        private int externalSignal(Direction direction, boolean chargeWire) {
            BlockPos neighbour = queryPos.relative(direction);
            Node node = owned.get(neighbour);
            if (node != null) {
                // A run this solve owns: its strength is an output of this very solve, not an input to it, so
                // the state the block happens to hold is not read. A wire is told nothing and takes the value
                // as a neighbouring node instead (see PowerSolver#wireTarget), where the hop is charged to the
                // receiver - charging it is the whole difference between a run that fades with its source and
                // one that holds itself up. Everything else reads the value live, because it is already solved.
                return queryingWire ? 0 : node.power();
            }
            // The same implementation the carrier bridge calls, under the same suppression, so the two readers cannot drift apart.
            int vanilla = chargeWire
                    ? world.externalSignal(neighbour, direction)
                    : queryingWorld(level, () -> worldSignalAt(level, neighbour, direction, false));
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
         * The vanilla diode on one side, or {@code null} when the block there is not one.
         *
         * <p>Two rules, both vanilla's: only a state whose block is a {@code DiodeBlock} counts, which is what
         * keeps a piece of dust, a torch and a solid block carrying strong power from holding anything; and the
         * signal is asked of the diode itself, in vanilla's own backwards direction convention, where the side
         * names where the signal comes from - so {@code getSignal(neighbour, direction)} is "what that diode
         * pushes towards me".
         */
        @Override
        @Nullable
        public VanillaDiode vanillaDiodeOn(Direction direction) {
            BlockPos neighbour = queryPos.relative(direction);
            BlockState state = level.getBlockState(neighbour);
            if (!DiodeBlock.isDiode(state)) {
                return null;
            }
            return new VanillaDiode(state.getValue(DiodeBlock.FACING),
                    state.getSignal(level, neighbour, direction));
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

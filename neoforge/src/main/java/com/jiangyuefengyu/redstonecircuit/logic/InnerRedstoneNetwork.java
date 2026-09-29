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
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierDirections;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierId;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierProjection;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierStore;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierWorldSignals;
import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.HostEntry;
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
     *
     * <p><b>Public because it is a fact a test states</b>: "the pair settled rather than being stopped by
     * the budget" is exactly {@code rounds < MAX_ROUNDS_PER_TICK}, and the author's defect was a pair that
     * sat AT it every tick. A test that wrote 32 down instead would drift the day this number changes.
     */
    public static final int MAX_ROUNDS_PER_TICK = 2 * PowerSolver.MAX_POWER + 2;

    /**
     * Ceiling on the fixpoint passes one component may need in a single solve.
     *
     * <p>Only reached by a component far larger than anything a player builds (each pass walks the whole
     * component), and only as a guard against a pathological wiring order; {@link #MAX_ROUNDS_PER_TICK}
     * is the bound that actually keeps a tick short.
     */
    private static final int MAX_FIXPOINT_PASSES = 512;

    /** Per-level set of positions awaiting recomputation. */
    private static final Map<ResourceKey<Level>, Set<BlockPos>> DIRTY = new HashMap<>();

    /** Per-level set of driven components waiting for their output to follow their input. */
    private static final Map<ResourceKey<Level>, PendingFlips> PENDING = new HashMap<>();

    /**
     * How many settle rounds the solve that is running right now has used, and how many components it has
     * recomputed.
     *
     * <p>Per tick rather than per level, and reset at the start of each {@link #tick}: both numbers are
     * statements about <em>one tick's</em> work ("did this settle in one round, or was it fed by its own
     * notifications?"), which is exactly the question the convergence evidence line answers. Counted
     * always, and printed only by that line - {@link SolverLog} owns the decision of when to print.
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
     * <b>P1.42: the one projection the read in progress must not take.</b>
     *
     * <h2>Why a filter on one half of a read filters nothing</h2>
     * {@link #worldSignalAt(ServerLevel, BlockPos, Direction, CarrierId)} is the carrier half's one
     * world read, and its "vanilla" half ({@link #readSignal}) is <b>also</b> answered by the projection
     * table: a {@code ServerLevel} is a {@code SignalGetter}, so that read lands in
     * {@code BlockStateSignalMixin} -&gt; {@code BlockSignalSources#select}, whose second source is
     * {@code CarrierStore#projectedHostAt}. P1.41 refused the asking carrier's own host in the explicit
     * {@code projectedSignalAt} half only, so the very same host came back through the other one and
     * {@code max(vanilla, projected)} kept the uncharged copy: a run of carried dust stayed at fifteen
     * however many blocks long it was. The filter has to cover <b>both</b> halves, and both halves go
     * through <em>one</em> accessor, so this is where it lives.
     *
     * <h2>Scoped to one carrier and one position, and that is not a detail</h2>
     * The refusal is only in force while one carrier-side read runs, and only for the world position
     * that read is about. Anything else would be wrong rather than merely broad: the mixin's
     * strong-power question asks about a <em>different</em> position (the block doing the asking), and a
     * carrier's projection at <em>another</em> position is the P1.38 seam, which must keep answering.
     *
     * <p>Server-thread only, saved and restored around the read, and never set outside one - the same
     * shape {@link #SUPPRESS_SIGNAL} has, and for the same reason: the reader is a mixin, which cannot
     * be handed a parameter.
     */
    @Nullable
    private static volatile CarrierId refusedProjectionOwner;

    /** The world position {@link #refusedProjectionOwner} is only refused <em>at</em>. */
    @Nullable
    private static volatile BlockPos refusedProjectionAt;

    /**
     * <b>P1.45: the receiver the read in progress belongs to, or {@code null} when it names none.</b>
     *
     * <p>A hop is charged to the <b>receiver</b> ({@code PowerSolver#afterHop}) and only between two
     * wires, which needs one fact a projection-sourced read never had: who is asking. A carried read
     * knows it exactly and says so through {@link #seamReader(ComponentType, java.util.function.Supplier)}
     * ({@code CarrierWorldIo} looks the reading host up in the carrier index at its own local
     * coordinate); every other read leaves this null and is answered by
     * {@link #seamReaderAt(ServerLevel, BlockPos, Direction)}, which finds the asker the way a vanilla
     * query can: one step back along the query direction.
     *
     * <p>Server-thread only, saved and restored around the read, and never set outside one - the same
     * shape {@link #refusedProjectionOwner} has, and for the same reason (see there).
     */
    @Nullable
    private static volatile ComponentType seamReader;

    private InnerRedstoneNetwork() {
    }

    // ------------------------------------------------- only the reader's own projection --

    /**
     * Runs one read with {@code owner}'s projected host at {@code worldPos} refused everywhere, so that
     * neither half of the merged read can take it. See {@link #refusedProjectionOwner}.
     *
     * <p>Re-entrant: the previous refusal is restored, so nesting two reads (which the mixin can do
     * through a neighbour query) cannot leak one out of the other.
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
     * <b>Whether the projection table may answer with {@code found}, at {@code at}, for the read that
     * is running right now.</b> True for everything except the one (carrier, position) pair that read
     * refused - see {@link #refusedProjectionOwner}.
     *
     * <p>Asked by {@code CarrierStore#projectedHostAt}, which is the single accessor both halves of the
     * merged read go through, and by {@link #projectedSignalAt} - so the two cannot disagree about who
     * may answer, which is the defect this exists for.
     *
     * @param found the host the table holds at {@code at}, or {@code null} for air
     * @param at the world position being read
     */
    public static boolean projectionMayAnswer(@Nullable CarrierProjection.ProjectedHost found,
                                              @Nullable BlockPos at) {
        CarrierId owner = refusedProjectionOwner;
        if (owner == null || found == null || at == null || !at.equals(refusedProjectionAt)) {
            return true;
        }
        // The same rule P1.41 states, applied to the read as a whole: the asking carrier's own host is a
        // node of the solve that is asking - it already arrives through the inner-neighbour
        // relationship, which charges the hop - and another carrier's host is the seam.
        return !owner.equals(found.carrier());
    }

    // ------------------------------------------------- P1.45: the seam's own hop --

    /**
     * <b>P1.45: which of our components is doing the reading at a world query, or {@code null}.</b>
     *
     * <h2>Why a read needs a reader at all</h2>
     * Every hop in this mod is charged to the <b>receiver</b> ({@code PowerSolver#afterHop}), and only
     * between two wires: a repeater that reads a wire reads it at full strength, a dust that reads a dust
     * pays one. The world's own block read has always known that - {@link #readSignal} runs a
     * neighbouring vanilla wire through {@link #neighbourSignal}, and a vanilla <em>reader</em> is
     * charged by {@code BlockSignalSources#hopChargedByReader} (P1.44) - but a neighbour that only the
     * <b>projection table</b> knows about was handed over as a raw emission. That is the free hop the
     * author measured: two independently physicalised blocks a world cell apart, "no attenuation at
     * all", because the level sees air where the other block stands and the table was the only source
     * and charged nothing.
     *
     * <h2>How the asker is found when the caller does not name it</h2>
     * A signal query says only <em>which block is being asked about</em> and <em>from which side</em>, so
     * the asker is the block one step back along the query direction - the same convention
     * {@code BlockStateSignalMixin} already uses for the strong question and for P1.44's hop. A carrier
     * read names its reader outright instead (see the field {@link #seamReader}), which is exact for a
     * machine that has been turned; this fallback is for every caller with no identity to give, and it
     * asks the world store first (an ordinary host block) and then the projection table (where a carried
     * asker stands).
     *
     * <h2>What it deliberately does not decide</h2>
     * Nothing about <em>which source may answer</em>: P1.38's seam, P1.41/P1.42's refusal and the
     * world-side read are all untouched. This asks one question - who is receiving - and answers
     * {@code null} when it cannot know, which leaves the read exactly as it was before this round
     * ({@code PowerSolver#afterHop} with a null receiver is the identity).
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
            // A question asked for the arithmetic must never be the thing that takes a solve down: an
            // unreadable asker answers "not one of ours", which is the pre-P1.45 read.
            return null;
        }
    }

    /**
     * <b>P1.45: the read-scoped reader.</b> Runs one read with {@code reader} named as the receiver, so
     * that the projection-sourced half of it can charge that receiver's hop. See {@link #seamReader}.
     *
     * <p>Re-entrant, and restored on the way out, exactly as {@link #refusingOwnProjectionAt} is: a read
     * can nest (the mixin re-enters through a neighbour query) and one read's reader must never leak into
     * the next.
     *
     * @param reader the component doing the reading, or {@code null} for "this read names none" - which
     *     is the pre-P1.45 behaviour, and what the world-side solve and every fixture get
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
     * <b>P1.45: the hop a neighbour that only the projection table knows about charges.</b>
     *
     * <p>One rule and no second one: the arithmetic is {@code PowerSolver#afterHop}, which is the same
     * statement every other hop in this mod makes - the <b>receiver</b> pays, and only between two
     * wires. So an ordinary dust reading another structure's projected dust pays one and holds fourteen
     * instead of fifteen, a superconductor pays nothing and keeps every step it was given, and a
     * repeater or a lamp reading a projected wire still reads it at full strength exactly as vanilla
     * says. The value is never zeroed: the seam still <em>answers</em> (that is P1.38's whole ability) -
     * it is charged, not refused.
     *
     * <p>{@code host == null} (air, or a host with an empty slot) and {@code reader == null} (a caller
     * with no identity to give) both leave the answer untouched, which is the honest degradation rather
     * than a guess.
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
        // THE NOTIFICATIONS ARE HELD UNTIL THE END OF THIS METHOD. Opening the session here means every
        // notifyOutputChanged the solve below performs is collected instead of sent, so nothing the
        // solve reads can move under it: see SolverNotifications for the re-entrant path
        // (updateNeighborsAt -> NeighborNotifyEvent -> markDirty -> another round of this same solve)
        // that used to run this loop and a neighbouring vanilla wire against each other dozens of times
        // per tick. The flush at the end of the method still happens inside this tick, so a lamp beside
        // a host block reacts as promptly as it ever did.
        SolverNotifications.Session session = SolverNotifications.begin(level);
        int roundsBefore = recomputesThisTick;
        try {
            InnerRedstoneStore store = InnerRedstoneStore.get(level);
            if (!store.isEmpty()) {
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
     * <p>One solve is not always the end of the story, because telling the world about it can change
     * the world: a vanilla wire laid against a host block drops a step, and a consumer that was not
     * looking has to be woken. That is why the world is notified at all - but it is also why the
     * notification is <b>collected rather than sent</b> while this loop runs
     * ({@link SolverNotifications}): a notification sent now re-enters this very method through
     * {@code NeighborNotifyEvent}, and the two halves of one circuit then re-derive from each other
     * inside one tick until {@link #MAX_ROUNDS_PER_TICK} stops them. Held to the end of the tick, the
     * same notifications are sent once each and cannot feed the solve that caused them.
     *
     * <p>A solve run outside a tick ({@code /rc}) still flushes before it returns - see
     * {@link #settleNow(ServerLevel)} - so a command's effect is never left pending.
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
        // P1.38: the tick the projected-host judgement line last wrote for describes a level that is
        // going away. There is one field and no table, so this is one store - and it keeps the next
        // level's first line from being suppressed by a clock comparison with the previous one's.
        projectedLoggedTick = Long.MIN_VALUE;
    }

    /** True while the given level is evaluating wire power and must not report it to neighbours. */
    public static boolean isSignalSuppressed(Level level) {
        return SUPPRESS_SIGNAL.contains(level.dimension());
    }

    /**
     * True while <b>any</b> level is evaluating wire power.
     *
     * <p>Exists for one caller - {@code BlockSignalSources.suppressWorldStore}, the rule the mixin reads
     * - and it is asked on the hottest path in the game, which is why it is a set-emptiness test rather
     * than anything that has to walk a set. Its meaning is deliberately the dimension-free one: "a
     * re-entrant solve owns this query" is a fact about the call stack, not about which level is named,
     * and a query made by a carrier solve for one dimension while another dimension happens to be
     * solving is still a query the world store must not answer.
     */
    public static boolean isAnySignalSuppressed() {
        return !SUPPRESS_SIGNAL.isEmpty();
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
        recomputesThisTick++;
        if (RCConfig.debugLog()) {
            // THE RECOMPUTE, COUNTED BY ITS SEED. This is where the spin warning's evidence comes from:
            // 31,045 of these lines in the author's measured minute, over a handful of seeds - a loop
            // running inside one tick rather than a machine being solved.
            SolverLog.recompute(seed);
            if (SolverLog.verbose()) {
                RCConfig.LOGGER.info("[redstonecircuit] recompute seed={} component={}",
                        seed.toShortString(), component);
            }
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
     *
     * <p>Public because P4's carrier bridge reads the world through it too: a component inside a
     * moving structure must not read its own output back off the ground it flies over, for exactly the
     * same reason. The re-entrancy is handled here, so nesting the two uses is safe.
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
     * Tells the world that the signal emitted by the host block at {@code pos} may have changed.
     *
     * <p>{@code updateNeighborsAt} is exactly the path vanilla uses: it fires
     * {@code NeighborNotifyEvent} and runs each of the six neighbours' own {@code neighborChanged}.
     *
     * <p>A diode does one thing more, which is what vanilla's {@code DiodeBlock#updateNeighborsInFront}
     * exists for: a diode pushes <b>strong</b> power into the block in front of it, so everything around
     * that block sees a new signal too - and none of those blocks is a neighbour of the diode, so
     * notifying the diode's own neighbours is not enough. The case that makes it obvious is "repeater,
     * a stone block, a lamp": without this second step the stone is charged and the lamp never hears
     * about it.
     *
     * <h2>When it actually goes out</h2>
     * <b>Not necessarily now.</b> While a solve is running ({@link SolverNotifications#begin}) the
     * position is collected and the notification is sent once, when that solve has reached its fixed
     * point - because this call is re-entrant through {@code NeighborNotifyEvent}, and a re-entrant
     * notification lets the circuit being solved react to its own half-finished answer. Outside a solve
     * (an operator's click, a wrench toggle, a command) it goes out immediately, exactly as it always
     * has. See {@link SolverNotifications} for the whole of that decision.
     */
    public static void notifyOutputChanged(ServerLevel level, BlockPos pos) {
        SolverNotifications.offer(level, pos);
    }

    /**
     * The notification itself, with no coalescing in the way: the body of
     * {@link #notifyOutputChanged} as it was before this round.
     *
     * <p>Reached only through {@link SolverNotifications}, which is the one place that decides between
     * "now" and "at the end of the solve". Public because that class is the caller, and package-private
     * would put the two halves of one decision in two packages.
     *
     * <p>The component is read from the <b>world store</b>, which is where the position being notified
     * lives. A structure's own coordinate is notified through {@link #notifyNeighboursNow} instead,
     * because the component there is the carried record and not a world entry - see
     * {@code CarrierStore#notifyStructureNow}.
     */
    public static void notifyOutputChangedNow(ServerLevel level, BlockPos pos) {
        notifyNeighboursNow(level, pos, InnerRedstoneStore.get(level).slotAt(pos));
    }

    /**
     * <b>The one neighbour notification, with the component named outright.</b>
     *
     * <p>Everything P1 sent is here, unchanged: the vanilla {@code updateNeighborsAt} at {@code pos},
     * and for a diode the second step vanilla's {@code DiodeBlock#updateNeighborsInFront} exists for -
     * a diode pushes strong power into the block in front of it, and none of <em>that</em> block's
     * neighbours is a neighbour of the diode, so "repeater, stone, lamp" needs the extra two calls.
     *
     * <p>The component is a parameter rather than a store lookup because a position can hold one
     * component in two different places: the world store (an ordinary host block) and the carried index
     * (a host a structure is carrying, at the structure's own coordinate). The carrier path has the
     * record in hand - it is the thing whose output just changed - so it must not be re-derived from a
     * store that does not hold it. P1.36.
     *
     * @param component the component at {@code pos}, or {@code null} for a position that holds none -
     *     in which case only the plain neighbour update is sent
     */
    public static void notifyNeighboursNow(ServerLevel level, BlockPos pos, @Nullable Slot component) {
        BlockState state = level.getBlockState(pos);
        Block source = state.getBlock();
        level.updateNeighborsAt(pos, source);
        // P1.59: THE SHAPE-UPDATE HALF, which an OBSERVER needs and nothing else did.
        //
        // The author's report: "an observer cannot detect the redstone signal inside a block". That is not a
        // signal problem - it is what an observer actually watches. Vanilla's ObserverBlock fires from
        // `updateShape` alone:
        //
        //     if (state.getValue(FACING) == facing && !state.getValue(POWERED)) startSignal(...)
        //
        // i.e. it pulses when the block it FACES sends a SHAPE update, and vanilla sends those when a
        // block's state changes (`BlockState#updateNeighbourShapes`, called from `Level#setBlock`). A host
        // block is stone before and stone after: its state never changes, so no shape update was ever sent
        // and the observer saw nothing however much the inner signal moved. The signal queries
        // (`getSignal`/`getDirectSignal`) are the *other* half of "the world sees the inner component" and
        // were never the problem; this is the state-change half, sent where the rest of the notification is
        // sent and under the same coalescing (see notifyOutputChanged).
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
            // COUNTED ALWAYS, PRINTED ONLY WHEN ASKED. See SolverLog for what the author's own log
            // measured: 242,588 of these per-step lines in one minute, and one 'solver:' summary line
            // per twenty ticks instead.
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
     * The mod's one implementation of "read a world signal at a world position, from one side".
     *
     * <p>Extracted so that the ordinary world solver, P4's carrier bridge and P1.19's
     * <b>structure-own-blocks</b> half cannot drift apart: a component inside a flying machine has to
     * see exactly what a host block standing on the ground sees on that side, whether the block being
     * read is a world block or a block the machine is carrying. The rules are the ones
     * {@code SolverEnvironment#externalSignal} has always used:
     *
     * <ul>
     *   <li>{@code hostAcceptsStrongPower = false} narrows the read to what the neighbour emits
     *       itself, dropping the strong power a solid neighbour carries;</li>
     *   <li>a neighbouring <b>vanilla redstone wire</b> is charged one hop, exactly as vanilla charges
     *       a hop between two wires - that charge is what keeps the system solvable, because a wire is
     *       the one neighbour that can be powered by the block it powers.</li>
     * </ul>
     *
     * <p>Every <em>world</em> read runs under {@link #queryingWorld}, so our own stored components
     * report nothing while the question is being asked. That wrapping stays at the call site
     * ({@link #worldSignals}) rather than in here: a structure's own blocks are not in the world store
     * at all, so the same nesting is harmless but pointless there, and keeping the rule itself free of
     * it means the two halves provably run the same arithmetic.
     *
     * <p>{@code direction} follows vanilla's convention exactly as the world half already did - it is
     * called as {@code getSignal(neighbour, askerToNeighbour)}, so the caller passes the position of
     * the neighbour and the direction from the block asking towards it. For a structure's own blocks
     * that is the <em>local</em> neighbour and the <em>local</em> direction: a machine's own lever is
     * next to it in the machine's coordinates, and no pose is involved.
     *
     * @param blocks the view to read - the level for the world half, the structure's own view for the
     *     structure half; the two are interchangeable because the rule only ever uses the
     *     {@code BlockGetter} surface
     * @param pos the neighbour's position in that view's own coordinates
     * @param direction from the block asking towards {@code pos}
     * @return 0-15
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
            // No signal getter, strong power deliberately refused, or the neighbour is one of our hosts:
            // only what the block emits itself. A view that cannot answer a state at all (a structure
            // that has just come apart) is "no signal" rather than an exception - see CarrierSpace,
            // which drops the whole structure half on a failure rather than letting one unreadable
            // coordinate take the solve down.
            signal = state == null ? 0 : state.getSignal(blocks, pos, direction);
        }
        return neighbourSignal(signal, state != null && state.is(Blocks.REDSTONE_WIRE));
    }

    /**
     * <b>Vanilla's own "what does this block offer a comparator" question.</b>
     *
     * <p>A comparator's <em>back</em> input is not a redstone signal: vanilla asks the block behind it
     * for its analogue output, which is how a cake (one per bite), a cauldron's level, a chest, a
     * hopper, a jukebox, a respawn anchor and every other analogue block is <b>read</b> at all. A block
     * with no analogue output answers 0, so this read is additive to - never a replacement for - the
     * redstone signal the same neighbour may also be pushing.
     *
     * <p><b>This is the half the mod never had.</b> Searching the tree for the vanilla method name found
     * nothing at all, so an inner comparator could see a wire and a lever but not a cake: the author's
     * report ("the comparator inside a block cannot detect the state of the block next to it, a cake for
     * instance") is exactly that hole.
     *
     * @param level the level the neighbour really is in; for a carried host this is the level at the
     *     neighbour's own coordinates, a plot coordinate being a real coordinate in the same level
     * @param pos the neighbour's position in that level
     * @return 0-15
     */
    public static int readAnalogOutput(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state == null ? 0 : state.getAnalogOutputSignal(level, pos);
    }

    /**
     * <b>P1.44: whether the block at this coordinate is a component of ours rather than a block of
     * stone.</b>
     *
     * <h2>The rule this mirrors, and the loop it closes</h2>
     * The project already states this on the <em>answering</em> side: a host block may not charge a
     * neighbouring component with strong power, because "the block <em>is</em> the component: a repeater
     * inside a block should hand its signal to what is next to it, not turn its neighbour's block into a
     * redstone block" - {@code PowerSolver#signalAnswer}'s {@code neighbourHoldsComponent} branch, pinned
     * by {@code InnerRedstoneGameTest#aRepeaterInARowDoesNotPowerBesideTheFirstOne}. The <em>reading</em>
     * side had no such rule, and that is where the author's positive feedback lived:
     *
     * <pre>
     *   our host's emission --&gt; the outer dust is worth 15  (P1.44: the hop is charged now)
     *                        --&gt; the dust's strong power charges the conducting block beside it
     *                            (vanilla: SignalGetter#getSignal / getDirectSignalTo)
     *                        --&gt; that block answers 15 to everything, and our solve read it as a supply
     * </pre>
     *
     * <p>The return edge was the last line: reading a <em>host</em> with the strong-power-inclusive form
     * is exactly "treating a host block as a block of stone", which is the one thing this mod does not
     * do. With the neighbour read as the component it holds, the loop's return edge is gone, and the
     * value can only come down the chain from its source, one hop at a time.
     *
     * <h2>What it deliberately does not change</h2>
     * Only a coordinate that holds a component is affected, so a <b>plain</b> block that a repeater or a
     * torch strongly powers still feeds the component beside it ({@code stronglyPoweredBlockFeedsInnerRedstone}),
     * and a vanilla consumer - a lamp, a piston, a wire - still sees whatever vanilla's own arithmetic
     * gives it. The {@code hostAcceptsStrongPower} switch keeps its meaning for plain neighbours: "how
     * strictly is a block that is not ours read".
     *
     * <p>Asked only where the answer is cheap and certain: a {@code ServerLevel} (the world half, and a
     * Sable sub-level's own frame) against the world store. A view that is not a level - Create's
     * contraption world - holds no level positions at all, so nothing there can be a world store entry.
     */
    private static boolean holdsComponentAt(BlockGetter blocks, BlockPos pos) {
        if (!(blocks instanceof ServerLevel level)) {
            return false;
        }
        try {
            InnerRedstoneStore store = InnerRedstoneStore.get(level);
            // `isEmpty()` first: it is the cheapest guard there is, and a level with no components at all
            // is the common case on this path.
            return store != null && !store.isEmpty() && store.slotAt(pos) != null;
        } catch (RuntimeException | LinkageError unreadable) {
            // A question asked for the arithmetic must never be the thing that takes a solve down: an
            // unreadable store answers "no component here", which is the pre-P1.44 read.
            return false;
        }
    }

    /**
     * The arithmetic half of the shared rule, on its own so it can be pinned by a test.
     *
     * <p>A vanilla redstone wire that is powered offers one step less to whatever it feeds, which is
     * how vanilla charges a hop between two wires. Everything else is passed through and clamped.
     *
     * @param rawSignal what the neighbour emits
     * @param vanillaWire whether that neighbour is {@code minecraft:redstone_wire}
     */
    public static int neighbourSignal(int rawSignal, boolean vanillaWire) {
        int signal = rawSignal;
        if (signal > 0 && vanillaWire) {
            signal--;
        }
        return PowerSolver.clamp(signal);
    }

    /**
     * <b>The bridge one carrier reads the world through: the vanilla world block, or a host another
     * structure is carrying at that position - whichever offers more (P1.38).</b>
     *
     * <h2>Why the second half had to be added here</h2>
     * The world-side solve has read a projected host directly since P1.20
     * ({@code SolverEnvironment#externalSignal} = {@code max(getSignal(neighbour), projected)}), which is
     * what makes "a component inside an ordinary block beside a machine" work. The carrier-side read
     * did not have that step: it read the <em>real</em> world block and nothing else, and the author's
     * own judge line says what that costs -
     *
     * <pre>
     *   projected=2 host(s) worldQueries=0 (thisTick=0) ... structureDeclines=0 ... lastRead=structure_frame
     * </pre>
     *
     * <p>Two blocks, each physicalised on its own and standing one world cell apart. Structure B's dust
     * asks about the position A's block is projected onto. That position holds <b>air</b> in the level -
     * A's block is an entity's business, not the level's - so the vanilla half answers 0. The projection
     * table is the only thing that knows a full-power dust is standing there, and before this round the
     * carrier half never asked it. The seam was dark by construction, permanently, at every power level:
     * the block was there, the table had it, and nothing looked.
     *
     * <p>So this is the world-side step, stated once and used by both halves, which is also why the two
     * readers can never answer differently about the same position.
     *
     * <h2>Why taking the larger of two answers cannot contradict either one</h2>
     * The vanilla half is {@code max(what the real block emits, the strong power it carries)} clamped to
     * 15; the projected half is what the carried component emits, clamped to 15. A position cannot hold
     * both with different values unless a block really is standing where a structure is carrying one -
     * and then the honest answer is the larger of the two, exactly as it is for a solid block that also
     * carries power. Both halves are weak-power reads (a neighbour <em>emits</em>), so this is the same
     * comparison the world-side solve has made since P1.20.
     *
     * <h2>Cost</h2>
     * One boolean ({@code carrierWorldReadsProjection}) and one hash lookup per world read, and the hash
     * is only taken when the first boolean is on. On the author's world the projection table holds a
     * handful of entries; the level store's 840-odd hosts are never walked. A failure inside the carrier
     * half answers zero rather than propagating: a broken bridge must look like a jar without it, not
     * like a broken solve.
     */
    public static CarrierWorldSignals worldSignals(ServerLevel level) {
        return (worldPos, worldDirection) -> queryingWorld(level,
                () -> worldSignalAt(level, worldPos, worldDirection));
    }

    /**
     * One world reading, from both sources, for a caller that is already inside the suppression window
     * (or has no window to open): the vanilla world block, or a host a structure is carrying there.
     *
     * <p>See {@link #worldSignals} for the whole argument. Kept public and level-taking so the carrier
     * half and the world-side solve are provably one implementation and can be pinned by a test that
     * needs no server.
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
     * The same read, with <b>one carrier's own projection refused</b> - P1.41.
     *
     * <h2>Why a carrier-side read must not take its own projected neighbour as a supply</h2>
     * The projected half of this read is a <em>raw emission</em>: it asks what the host a structure is
     * carrying at that position emits that way, and it charges no hop. That is exactly right for the
     * question this source was added for (P1.38) - "is there a component of <b>somebody else</b> standing
     * where I am looking, since the level itself sees air there" - and exactly wrong for a coordinate that
     * holds a host <b>of the solve that is asking</b>. There the neighbour is a node of the running solve,
     * and its value already reaches the asker through the inner-neighbour relationship, which charges the
     * hop ({@code PowerSolver#afterHop}: one for dust, nothing for a superconductor). Letting the
     * projection answer as well handed the same component over a second time, uncharged, and since the two
     * are combined with {@code max} the uncharged copy won: a run of carried dust stopped fading while the
     * same run on the ground still faded.
     *
     * <h2>Only the reading carrier's own projection is refused</h2>
     * The filter is {@link #projectionReadableBy} - false for a host of {@code owner}, true for every
     * other structure's - and that narrowness is the point: two independently physicalised blocks are a
     * host of <em>another</em> carrier seen from the one that is reading, and that read is the whole of
     * P1.38. A {@code null} owner keeps the unfiltered behaviour every reader had before this round,
     * which is what the world-side solve asks for - and what the three-argument form above uses.
     *
     * <h2>P1.42: the refusal covers both halves of the read</h2>
     * P1.41 refused the owner's host in the explicit projected half. The <em>vanilla</em> half of this
     * method is answered from the same table (a {@code ServerLevel} read goes through
     * {@code BlockStateSignalMixin}), so the owner's own host came back through it and {@code max} kept
     * the uncharged copy: a run of carried dust never faded. The whole read therefore runs under
     * {@link #refusingOwnProjectionAt}, which {@code CarrierStore#projectedHostAt} - the one accessor
     * both halves use - honours. The three-argument form and the world-side solve name no owner and are
     * untouched.
     *
     * @param owner the carrier whose own projected hosts must not answer, or {@code null} to read every
     *     projection - the unfiltered form the world-side solve uses
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
        // P1.42: THE WHOLE READ RUNS UNDER THE REFUSAL, NOT JUST HALF OF IT. `readSignal` on a
        // ServerLevel lands in `BlockStateSignalMixin`, whose second source is the very table
        // `projectedSignalAt` reads - so refusing the reader's own host in one half and not the other
        // left the uncharged copy in place and the run of carried dust at fifteen. See
        // `refusedProjectionOwner` for the whole argument.
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
     * <b>P1.41: whether a host the projection table holds may be read as an external supply by one
     * carrier.</b>
     *
     * <p>The answer is "no" for one of the reader's <b>own</b> hosts and "yes" for every other
     * structure's, and it is the whole of this round's rule: the reader's own neighbour is a node of the
     * solve that is asking, so it already arrives through the inner-neighbour relationship - which
     * charges the hop - and taking the projection's raw emission as well would cancel that charge; a host
     * <em>another</em> structure carries is the seam P1.38 opened, and it is the only route by which two
     * independently physicalised blocks can read each other at all.
     *
     * <p>Split out as a static question of two values on purpose: it is reachable offline (the projected
     * host and the reader's identity are both plain data), so the rule itself is pinned by a unit test
     * rather than only by a running server, and the owner-aware {@link #worldSignalAt(ServerLevel,
     * BlockPos, Direction, CarrierId)} / {@link #projectedSignalAt(ServerLevel, BlockPos, Direction,
     * CarrierId)} pair plus the carrier bridge's owner-aware read cannot drift apart about it. The
     * decision itself lives next to {@code ProjectedHost}, in {@link CarrierProjection#readableBy}.
     *
     * @param found the host projected at the position being read, or {@code null} for air
     * @param owner the carrier doing the reading, or {@code null} when the read is not carrier-side and
     *     nothing may be filtered
     */
    public static boolean projectionReadableBy(@Nullable CarrierProjection.ProjectedHost found,
                                               @Nullable CarrierId owner) {
        return CarrierProjection.readableBy(found, owner);
    }

    /**
     * The signal a host a structure is carrying offers at this world position, on this side, or 0.
     *
     * <p>Delegated to {@link CarrierProjection#emittedToward}, which is the <b>same</b> rule
     * {@code BlockStateSignalMixin} answers vanilla's own query with: the world direction becomes the
     * structure's own through the one conversion, and {@code PowerSolver.signalAnswer} decides whether
     * that component emits that way. Weak power, because a read asks what a neighbour <em>emits</em> -
     * strong power is the separate question vanilla asks of a solid block.
     *
     * <p>Read-only, and closed by {@code carrierFreezeWorldIo}: with the bridge frozen there is no
     * projection table with anything in it, so this answers zero - exactly the P1/P2 behaviour.
     */
    public static int projectedSignalAt(ServerLevel level, BlockPos worldPos, Direction worldDirection) {
        return projectedSignalAt(level, worldPos, worldDirection, null);
    }

    /**
     * The same, with one carrier's own hosts refused - P1.41; see
     * {@link #worldSignalAt(ServerLevel, BlockPos, Direction, CarrierId)} for the whole argument.
     *
     * <p>One filter, applied to the host this read already fetched, so the carrier-side bridge and the
     * world-side solve still share this one implementation of "what does a projected host emit that way".
     * A {@code null} owner - the three-argument form above, and the world-side solve's direct call -
     * filters nothing.
     *
     * @param owner the carrier whose projected hosts must answer zero, or {@code null} to read them all
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
            // P1.45: AND THE HOP THE RECEIVER PAYS. The projected answer is a raw emission: it charges
            // nothing, which is right for "a component of SOMEBODY ELSE is standing where the level sees
            // air" and wrong for the one thing every other read in this mod charges - the hop between two
            // wires. So the seam is charged here, by the reader, with `PowerSolver#afterHop` as the only
            // arithmetic (dust one, superconductor nothing, anything else untouched). This is the whole
            // of the round: the seam still answers (P1.38), it is no longer free.
            return seamHop(host, seamReaderAt(level, worldPos, worldDirection), answer);
        } catch (RuntimeException | LinkageError failed) {
            // The carrier bridge is a bonus over the frozen behaviour: a failure in it must look like a
            // jar without it, not like a broken solve.
            return 0;
        }
    }

    /**
     * <b>One line the first time a projected host feeds a solve in a tick</b> - the judgement line for
     * "the machine's neighbour was read from the projection table".
     *
     * <p>Naming everything a reader needs: the world position, the carrier, the structure's own
     * coordinate, the side in both frames, and both values. Gated on {@code debugLog} like every other
     * per-change line.
     *
     * <h2>Once per tick, and why that is the bound rather than once per pass</h2>
     * The world-side solve has its own pass concept and used to set a per-pass flag; the carrier half
     * runs one solve per structure per tick, so "per pass" would mean one line per structure per tick.
     * One line per tick is the bound the round is allowed to pay for, it is the same rate the other
     * low-frequency diagnostics use, and it is enforced here on the tick the level itself reports - so
     * two callers cannot each print their own. The value is re-read rather than carried around, and only
     * after the line has been decided to be written, so the common path allocates nothing.
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
     * The tick {@link #logProjectedSignal} last wrote a line for, or {@code Long.MIN_VALUE}.
     *
     * <p>One long, replaced every time it fires, never grown and never keyed by anything: it exists so
     * that a level tick cannot produce two of these lines however many structures read a projection in
     * it. A game time that goes backwards (a level reloaded) simply writes one more line.
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
                // 47,584 of these in the author's measured minute - one per power step of a wire that was
                // being re-solved dozens of times per tick. Counted always, printed only when asked.
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
         * <h2>P1.20: and a host riding a structure in that position, directly</h2>
         * The projection table already answers vanilla's own signal query for a carried host (see
         * {@code BlockStateSignalMixin}), so this read normally sees it through
         * {@code Level#getSignal}. That route is one mixin deep and depends on {@code Level}'s own
         * implementation reaching {@code BlockState#getSignal}, and the author asked for the world-side
         * solve to read a carried host's output the way it reads any other block's. So it is read here
         * too ({@link #projectedSignalAt}), with the <b>same</b> rule and the <b>same</b> direction
         * conversion the mixin uses, and the two are combined as the larger. The direct read can only
         * ever agree with or exceed the mixin's answer, never contradict it, which is what makes the
         * combination safe.
         *
         * <h2>P1.38: which is now the carrier half's step as well</h2>
         * {@link #worldSignals} and this method are the two callers of the same
         * {@link #worldSignalAt} / {@link #projectedSignalAt} pair, so "a component beside a machine
         * reads it" answers identically whether the component is inside an ordinary world block or
         * inside another structure. Before P1.38 only the world half had the second step, and two
         * physicalised blocks therefore could not read each other at all.
         */
        @Override
        public int externalSignal(Direction direction) {
            // One shared implementation with P4's carrier bridge; see worldSignals(). It already runs
            // the query under the suppression described there, and since P1.38 it is the SAME method the
            // carrier half calls - so the two readers cannot drift apart about the projection.
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
            return readAnalogOutput(level, queryPos.relative(direction));
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

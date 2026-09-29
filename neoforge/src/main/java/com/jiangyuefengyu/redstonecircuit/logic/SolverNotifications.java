package com.jiangyuefengyu.redstonecircuit.logic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import com.jiangyuefengyu.redstonecircuit.RCConfig;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import org.jetbrains.annotations.Nullable;

/**
 * <b>Where the solver's neighbour notifications go: out immediately, or at the tick boundary.</b>
 *
 * <h2>The defect this exists for</h2>
 * {@link InnerRedstoneNetwork#notifyOutputChanged} calls {@code Level#updateNeighborsAt}. That call is
 * <b>re-entrant</b>: it fires {@code NeighborNotifyEvent} and runs each neighbour's own
 * {@code neighborChanged} <em>synchronously</em>, and both of those come straight back into
 * {@link InnerRedstoneTickHandler#onNeighborNotify} &rarr; {@code markDirty} &rarr; the next round of
 * the very solve that sent the notification. A vanilla redstone wire is the worst case, because it
 * re-derives its own strength <em>inside</em> {@code neighborChanged} (see
 * {@code RedStoneWireBlock#neighborChanged} &rarr; {@code updatePowerStrength}), and a host block is
 * both a neighbour that feeds that wire and a reader of it.
 *
 * <p>So one tick looks like this:
 *
 * <pre>
 *   settle round 1: host recomputed -&gt; power 14 -&gt; notify -&gt;
 *                   wire re-derives from the host, drops and notifies back -&gt; host queued again
 *   settle round 2: host recomputed against the wire's new value -&gt; power 13 -&gt; notify -&gt; ...
 * </pre>
 *
 * <p>The author's measured run had a host recomputed fifteen times inside one tick, the same DUST
 * transition repeating 862 times a second, and
 * {@link InnerRedstoneNetwork#MAX_ROUNDS_PER_TICK} turning "never converges" into "32 rounds, every
 * tick, for ever".
 *
 * <h2>What this changes, and what it deliberately does not</h2>
 * With {@code solverCoalesceNotifications} on (the default) a solve collects the positions whose
 * output changed and sends <b>one</b> notification per position <b>after</b> the solve has reached its
 * fixed point. Nothing the solver reads during that solve can move, so every round of it sees the same
 * world and a circuit that has a fixed point reaches it in one round instead of thirty-two. A circuit
 * that genuinely needs several rounds - a chain of components, a comparator, a diode's direction -
 * still gets them: the rounds are still there, they are simply no longer <em>fed</em> by notifications
 * the solve itself caused.
 *
 * <p>What it does <b>not</b> do is delay the notification across ticks. The flush happens at the end
 * of the same solve ({@link InnerRedstoneNetwork#tick} for the level tick, and before the command
 * returns for {@code /rc}), so a lamp beside a host block still reacts within the tick that changed the
 * host - one flush instead of one storm.
 *
 * <h2>Bounded, and level-free where it counts</h2>
 * One session per dimension, holding a set of at most the positions the level's own store holds; the
 * flush walks that set once and removes it. A session always ends ({@link #end(ServerLevel)}), so a
 * dimension cannot accumulate anything across ticks, and {@link #finish} drops a session a test started
 * without ending. Everything that decides anything is a plain {@link Consumer}, so the whole of it is
 * pinned by offline unit tests with no game running.
 */
public final class SolverNotifications {

    /**
     * What one solve's notifications were about, for the convergence evidence line.
     *
     * <p>{@code pending} is always zero once a solve has ended (the flush drains the set before it sends
     * anything), so it is not carried: what a reader wants is what the solve <em>did</em> - its rounds, the
     * components it recomputed, and the notifications it sent - and all three are here.
     *
     * @param rounds how many settle rounds the solve ran
     * @param recomputes how many components it recomputed
     * @param flushed how many positions it notified when it was done
     * @param ranSolve whether the solve actually ran (as opposed to a session opened and closed unused)
     */
    public record TickStats(int rounds, int recomputes, int flushed, boolean ranSolve) {

        static TickStats nothing() {
            return new TickStats(0, 0, 0, false);
        }
    }

    /**
     * One solve's worth of coalescing.
     *
     * <p>Started at the top of a solve, ended at the bottom of it. While it is open nothing is notified
     * by {@link #notify}; {@link #end(ServerLevel)} walks what was collected and notifies each position
     * exactly once, however many times it was offered.
     */
    public static final class Session {

        /** Where the notifications go when the flush happens; never the level. */
        private final Consumer<BlockPos> flush;

        /** Whether this session coalesces at all; see {@link #notify}. */
        private final boolean coalesce;

        /**
         * Positions whose output changed, awaiting one notification each.
         *
         * <p>A set rather than a list because a component that changes twice in one solve is one thing the
         * world has to be told about, and because a duplicate is exactly the "notify twice for one change"
         * the storm was made of. <b>Insertion-ordered</b> ({@link LinkedHashSet}) so the flush sends them in
         * the order the solve found them: the world does not care, and a reader of the log does - "these six
         * neighbours, in this order" and "these six, in some order" are different amounts of information.
         */
        private final Set<BlockPos> pending = new LinkedHashSet<>();

        /** Notifications already handed to the world by this session (the coalescing-off count). */
        private int sent;

        /** How many settle rounds the solve ran; written by the solver, read by the evidence line. */
        private int rounds;

        /** How many components that solve recomputed; same. */
        private int recomputes;

        private boolean ended;

        /** Whether the solve this session belongs to actually ran (as opposed to opening and closing). */
        private boolean noted;

        Session(Consumer<BlockPos> flush, boolean coalesce) {
            this.flush = flush;
            this.coalesce = coalesce;
        }

        /** Records what the solve that owns this session did, for {@link SolverNotifications#end}. */
        void note(int rounds, int recomputes) {
            this.rounds = Math.max(0, rounds);
            this.recomputes = Math.max(0, recomputes);
            this.noted = true;
        }

        /** True once {@link SolverNotifications#end} has run for this session. */
        public boolean ended() {
            return ended;
        }

        /**
         * A position whose host block now emits something else.
         *
         * <p>Coalescing: recorded, and sent by the flush. Not coalescing: sent here and now, which is the
         * behaviour this class was added to fix (and the switch is what restores it).
         */
        public void notify(BlockPos pos) {
            if (pos == null) {
                return;
            }
            if (!coalesce) {
                sent++;
                flush.accept(pos);
                return;
            }
            pending.add(pos.immutable());
        }

        /** How many positions are waiting for the flush. */
        public int pendingCount() {
            return pending.size();
        }

        /** Whether this session holds the notifications back (the switch's value at the time it began). */
        public boolean coalescing() {
            return coalesce;
        }

        /**
         * Sends one notification per collected position and closes the session.
         *
         * <p>The set is drained <b>before</b> the first notification is sent, on purpose: a notification
         * re-enters the solver synchronously, and if the set were still being walked, that re-entry
         * could offer new positions to a set the loop is iterating. Draining first means the flush is a
         * bounded, one-shot walk however the world reacts.
         */
        private int flushPending() {
            if (pending.isEmpty()) {
                return 0;
            }
            List<BlockPos> due = new ArrayList<>(pending);
            pending.clear();
            for (BlockPos pos : due) {
                sent++;
                flush.accept(pos);
            }
            return due.size();
        }
    }

    /** The open session per dimension; at most one per level while its tick is being solved. */
    private static final Map<ResourceKey<Level>, Session> OPEN = new HashMap<>();

    /** What the last solve in each dimension did; the evidence line's read, and nothing else's. */
    private static final Map<ResourceKey<Level>, TickStats> LAST = new HashMap<>();

    /**
     * One structure-frame pass per dimension: the coordinates of a structure's <b>own</b> frame that
     * have already been announced in the current tick.
     *
     * <p>P1.36. This is the same idea as {@link Session} - collect it, send it once - applied to the
     * other half of a carried host's neighbourhood. See {@link StructurePass} for why the tick, and not
     * the solve, is the unit.
     */
    private static final Map<ResourceKey<Level>, StructurePass> STRUCTURE = new HashMap<>();

    private SolverNotifications() {
    }

    /**
     * <b>Where a structure-frame notification goes: once per coordinate per tick, and only while the
     * coalescing switch is on.</b>
     *
     * <h2>What it is for</h2>
     * A component inside a structure the mod is carrying has two neighbourhoods. The world position it
     * is projected onto is announced by {@code CarrierProjection#forEachChange} and has been since P4.
     * The structure's <b>own</b> coordinate - where the machine's blocks really are, which for a Sable
     * sub-level is a real level position - was never announced, so a vanilla device the machine carries
     * (the author's piston above the redstone) never heard that the signal beside it had changed. This
     * pass is that second announcement, and it is deliberately the same shape as the coalesced
     * notification above: offered many times, sent once.
     *
     * <h2>Why it is not a {@link Session}</h2>
     * A session is opened and closed around one solve, and the carrier pass is not a solve: it runs once
     * per level tick, after the in-structure solve, and it reports at most one change per host per pass
     * already. What it does <em>not</em> guarantee on its own is "one per tick" if anything ever drives
     * it twice, and it makes no statement at all about the switch. Keying the set by the level's own
     * clock is what turns "once per call" into "once per tick" as a property of this class, and the set
     * is a plain per-dimension object that the next tick replaces - so it cannot grow across ticks and
     * needs no session to close it.
     *
     * <h2>Bounded</h2>
     * At most one entry per coordinate announced in one tick, dropped whole when the next tick opens a
     * new pass: the size is bounded by the number of changed host coordinates of the structures in
     * flight - a handful - never by the level's store and never by time.
     */
    public static final class StructurePass {

        /** The level tick this pass belongs to; a later tick replaces the whole object. */
        private final long tick;

        /** Where a first-time coordinate goes; never the level. */
        private final Consumer<BlockPos> notify;

        /** Coordinates already announced in this tick. */
        private final Set<BlockPos> sent = new LinkedHashSet<>();

        StructurePass(long tick, Consumer<BlockPos> notify) {
            this.tick = tick;
            this.notify = notify;
        }

        /** The level tick this pass belongs to. */
        public long tick() {
            return tick;
        }

        /**
         * Offers one structure-frame coordinate: notified on the first offer in this tick, and never
         * again in the same tick.
         *
         * @return true when this offer was the one that sent it, which is what the judgement line is
         *     gated on
         */
        public boolean notify(BlockPos structurePos) {
            if (structurePos == null || !sent.add(structurePos.immutable())) {
                return false;
            }
            notify.accept(structurePos);
            return true;
        }

        /** How many coordinates this tick's pass has announced so far. */
        public int sent() {
            return sent.size();
        }
    }

    // ------------------------------------------------------------- production --

    /**
     * Opens a solve for this level: notifications are held until {@link #end(ServerLevel)}.
     *
     * <p>Returns the open session, or {@code null} when the config switch is off - in which case there
     * is nothing to hold back and the caller's {@link #offer} sends immediately, exactly as before.
     */
    @Nullable
    public static Session begin(ServerLevel level) {
        return begin(level, pos -> InnerRedstoneNetwork.notifyOutputChangedNow(level, pos));
    }

    /**
     * The same, with the notification supplied.
     *
     * <p>The shape a unit test uses: everything about the coalescing - when a notification goes out,
     * how many go out, and that one change is announced once - is decided here and nowhere else, so a
     * test needs no level to pin it.
     */
    @Nullable
    public static Session begin(ServerLevel level, Consumer<BlockPos> notify) {
        if (level == null) {
            return null;
        }
        // One session per dimension per solve: a second begin for the same dimension (the command path
        // running inside the tick's own solve) reuses the open one rather than replacing it, so nothing
        // collected so far can be dropped on the floor. See `open(ResourceKey, Consumer)`.
        return open(level.dimension(), notify);
    }

    /** The session a solve is currently collecting into, or {@code null} when none is open. */
    @Nullable
    static Session open(ResourceKey<Level> dimension) {
        return dimension == null ? null : OPEN.get(dimension);
    }

    /**
     * Opens a solve named by dimension, with the notification supplied.
     *
     * <p>The shape {@link #begin(ServerLevel, Consumer)} is built on, and the one a unit test uses: the
     * level is only ever needed for its dimension key, and everything that decides anything is the
     * {@link Consumer}. Registering the session here - rather than handing it straight back - is what makes
     * {@link #end(ResourceKey, long)} find it, so a test drives the same open/flush pairing production
     * does.
     *
     * @return the open session, or {@code null} when the coalescing switch is off
     */
    @Nullable
    static Session open(ResourceKey<Level> dimension, Consumer<BlockPos> notify) {
        if (dimension == null || notify == null || !RCConfig.solverCoalesceNotifications()) {
            return null;
        }
        Session existing = OPEN.get(dimension);
        if (existing != null) {
            return existing;
        }
        Session session = new Session(notify, true);
        OPEN.put(dimension, session);
        return session;
    }

    /**
     * Ends a solve: sends everything collected, records what it did, and closes the session.
     *
     * <p>Called at the end of {@link InnerRedstoneNetwork#tick} and at the end of a command-driven
     * solve. A session that is not the open one (a nested solve that already ended it) is a no-op.
     *
     * @return how many positions were notified, for the caller's diagnostics
     */
    public static int end(@Nullable ServerLevel level) {
        if (level == null) {
            return 0;
        }
        return end(level.dimension(), level.getGameTime());
    }

    /**
     * The same, named by dimension and tick.
     *
     * <p>Split out so the flush's bookkeeping can be driven by a test with no level at all: the level is
     * only ever used for its dimension key and its clock here.
     */
    public static int end(ResourceKey<Level> dimension, long tick) {
        Session session = dimension == null ? null : OPEN.remove(dimension);
        if (session == null) {
            return 0;
        }
        int flushed = session.flushPending();
        session.ended = true;
        LAST.put(dimension, new TickStats(session.rounds, session.recomputes, flushed, session.noted));
        return flushed;
    }

    /** Offers one changed position to the solve that is running, or sends it immediately if none is. */
    public static void offer(@Nullable ServerLevel level, @Nullable BlockPos pos) {
        if (level == null || pos == null) {
            return;
        }
        Session session = OPEN.get(level.dimension());
        if (session == null) {
            // No solve is open: an operator's click, a wrench toggle, a command. It goes out now, which is
            // what those callers have always done and what keeps a click's feedback in its own tick.
            //
            // THE SEND IS THE BODY, NOT `notifyOutputChanged`. Calling the public method back would be a
            // one-line infinite recursion - offer -> notifyOutputChanged -> offer -> ... - which is a stack
            // overflow on the server thread rather than a behaviour change. See `notifyOutputChangedNow`.
            InnerRedstoneNetwork.notifyOutputChangedNow(level, pos);
            return;
        }
        session.notify(pos);
    }

    /** What the last closed solve in this dimension did, or zeroes when there has not been one. */
    public static TickStats lastStats(@Nullable ResourceKey<Level> dimension) {
        if (dimension == null) {
            return TickStats.nothing();
        }
        TickStats stats = LAST.get(dimension);
        return stats == null ? TickStats.nothing() : stats;
    }

    /** The same, for a level. */
    public static TickStats lastStats(@Nullable ServerLevel level) {
        return level == null ? TickStats.nothing() : lastStats(level.dimension());
    }

    /** Drops a dimension's open session and its last stats; called when a level unloads. */
    public static void clear(ResourceKey<Level> dimension) {
        if (dimension == null) {
            return;
        }
        OPEN.remove(dimension);
        LAST.remove(dimension);
        STRUCTURE.remove(dimension);
    }

    /** Drops everything; called when the server stops. */
    public static void clearAll() {
        OPEN.clear();
        LAST.clear();
        STRUCTURE.clear();
    }

    // ------------------------------------------------- structure-frame pass --

    /**
     * Opens (or reuses) this tick's structure-frame pass for one level, or {@code null} when either
     * switch is off.
     *
     * <p>The production entry point. {@code null} is the whole of "the feature is off": the caller
     * offers nothing and the world sees exactly what it saw before this round.
     */
    @Nullable
    public static StructurePass structurePass(@Nullable ServerLevel level,
                                              @Nullable Consumer<BlockPos> notify) {
        if (level == null) {
            return null;
        }
        return structurePass(level.dimension(), level.getGameTime(), notify,
                RCConfig.solverCoalesceNotifications() && RCConfig.carrierNotifyStructureNeighbours());
    }

    /**
     * The same, named by dimension and tick, with the decision supplied.
     *
     * <p>The shape a unit test uses - it can pin both switches without a loaded config file, exactly as
     * {@link #open(ResourceKey, Consumer)} lets it pin the coalescing one. The pass for a dimension is
     * <b>reused within one tick</b> and replaced when the tick changes, so "once per coordinate per
     * tick" holds however many times a caller asks for it. Public rather than package-private for the
     * same reason {@link #begin(ServerLevel, Consumer)} is: the carrier-side tests live in the carrier
     * package and must be able to drive the production decision rather than a copy of it.
     *
     * @param enabled the capability switch <em>and</em> the coalescing switch; false restores the
     *     behaviour before this round
     */
    @Nullable
    public static StructurePass structurePass(@Nullable ResourceKey<Level> dimension, long tick,
                                              @Nullable Consumer<BlockPos> notify, boolean enabled) {
        if (dimension == null || notify == null || !enabled) {
            return null;
        }
        StructurePass existing = STRUCTURE.get(dimension);
        if (existing != null && existing.tick() == tick) {
            return existing;
        }
        StructurePass pass = new StructurePass(tick, notify);
        STRUCTURE.put(dimension, pass);
        return pass;
    }

    /**
     * Ends a session a caller opened and did not end.
     *
     * <p>Exists for the unit tests, whose sessions have no level to be ended by a tick. {@link #end}
     * itself is the production exit and always runs; a session that somehow survived would hold only
     * positions, never a level or a structure, so it could not keep one alive - but leaving it open
     * would mean the next solve in that dimension reused it, which is a test's problem and not a
     * server's.
     */
    static void finish(ResourceKey<Level> dimension) {
        end(dimension, 0L);
        LAST.remove(dimension);
    }
}

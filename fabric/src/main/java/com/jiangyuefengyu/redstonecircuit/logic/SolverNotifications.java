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
 * Where the solver's neighbour notifications go: out immediately, or collected and sent once after the solve
 * has reached its fixed point. {@code Level#updateNeighborsAt} is <b>re-entrant</b> - a neighbour's
 * {@code neighborChanged} runs synchronously and comes straight back into the solve that sent it, so the solve
 * feeds itself. With {@code solverCoalesceNotifications} on (the default) one notification per changed
 * position goes out at the fixed point instead: the rounds still happen, they are simply no longer fed by the
 * solve itself. One session per dimension, ended by every solve; nothing is delayed a tick.
 */
public final class SolverNotifications {

    /**
     * What one solve did: its settle rounds, the components it recomputed, and the positions it notified.
     * {@code pending} is always zero by then, so it is not carried.
     */
    public record TickStats(int rounds, int recomputes, int flushed, boolean ranSolve) {

        static TickStats nothing() {
            return new TickStats(0, 0, 0, false);
        }
    }

    /**
     * One solve's worth of coalescing: opened at the top of a solve, ended at the bottom of it. While it is
     * open nothing is notified; the end walks what was collected and notifies each position exactly once.
     */
    public static final class Session {

        /** Where the notifications go when the flush happens; never the level. */
        private final Consumer<BlockPos> flush;

        /** Whether this session coalesces at all; see {@link #notify}. */
        private final boolean coalesce;

        /**
         * Positions whose output changed, awaiting one notification each - a set, because a component that
         * changes twice in one solve is one thing to tell the world about. Insertion-ordered so the flush sends
         * them in the order the solve found them, which is what a reader of the log wants.
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
         * A position whose host block now emits something else. Coalescing: recorded, and sent by the flush.
         * Not coalescing: sent here and now, which is the behaviour the switch restores.
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
         * Sends one notification per collected position and closes the session. The set is drained <b>before</b>
         * the first notification: a notification re-enters the solver synchronously, and that re-entry could
         * otherwise offer new positions to a set the loop is iterating.
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
     * One structure-frame pass per dimension: the coordinates of a structure's <b>own</b> frame already
     * announced in the current tick - the same "collect it, send it once" idea as {@link Session}.
     */
    private static final Map<ResourceKey<Level>, StructurePass> STRUCTURE = new HashMap<>();

    private SolverNotifications() {
    }

    /**
     * Where a structure-frame notification goes: once per coordinate per tick, and only while the coalescing
     * switch is on.
     *
     * <p>The structure's <b>own</b> coordinate is a carried host's second neighbourhood: the world position it
     * is projected onto is announced elsewhere, and a vanilla device the machine carries never heard that the
     * signal beside it had changed. It is not a {@link Session}, because it runs once per level tick rather
     * than once per solve; keying the set by the level's clock makes it once per tick, and the next tick
     * replaces the whole object, so it cannot grow and needs no session to close it.
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
         * Offers one structure-frame coordinate: notified on the first offer in this tick and never again in
         * the same tick. True when this offer was the one that sent it.
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
     * Opens a solve for this level: notifications are held until {@link #end(ServerLevel)}, and {@code null}
     * comes back when the config switch is off, so the caller's {@link #offer} sends immediately.
     */
    @Nullable
    public static Session begin(ServerLevel level) {
        return begin(level, pos -> InnerRedstoneNetwork.notifyOutputChangedNow(level, pos));
    }

    /**
     * The same, with the notification supplied - the shape a unit test uses, since everything about the
     * coalescing is decided here and nowhere else and a test therefore needs no level to pin it.
     */
    @Nullable
    public static Session begin(ServerLevel level, Consumer<BlockPos> notify) {
        if (level == null) {
            return null;
        }
        // One session per dimension per solve: a second begin reuses the open one rather than replacing it.
        return open(level.dimension(), notify);
    }

    /** The session a solve is currently collecting into, or {@code null} when none is open. */
    @Nullable
    static Session open(ResourceKey<Level> dimension) {
        return dimension == null ? null : OPEN.get(dimension);
    }

    /**
     * Opens a solve named by dimension, with the notification supplied: the level is only ever needed for its
     * dimension key, and everything that decides anything is the {@link Consumer}. Registering the session here
     * is what makes {@link #end(ResourceKey, long)} find it. {@code null} when the coalescing switch is off.
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
     * Ends a solve: sends everything collected, records what it did and closes the session; a session that is
     * not the open one is a no-op. Returns how many positions were notified.
     */
    public static int end(@Nullable ServerLevel level) {
        if (level == null) {
            return 0;
        }
        return end(level.dimension(), level.getGameTime());
    }

    /** The same, named by dimension and tick: the level is only ever used for its key and its clock. */
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
            // No solve is open - an operator's click, a wrench toggle, a command - so it goes out now. The send
            // is the body and not the public method, which would recurse: offer -> notifyOutputChanged -> offer.
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
     * Opens or reuses this tick's structure-frame pass for one level, or {@code null} when either switch is
     * off - and {@code null} is the whole of "the feature is off".
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
     * The same, named by dimension and tick, with the decision supplied - the shape a unit test uses, since it
     * can pin both switches without a loaded config file. The pass is <b>reused within one tick</b> and
     * replaced when the tick changes, so "once per coordinate per tick" holds however often a caller asks for
     * it. {@code enabled} is the capability switch <em>and</em> the coalescing switch.
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
     * Ends a session a caller opened and did not end - for the unit tests, whose sessions have no level tick to
     * close them. A session left open would be reused by the next solve in that dimension.
     */
    static void finish(ResourceKey<Level> dimension) {
        end(dimension, 0L);
        LAST.remove(dimension);
    }
}

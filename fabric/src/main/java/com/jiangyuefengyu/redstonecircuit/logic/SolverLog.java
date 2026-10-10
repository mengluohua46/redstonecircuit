package com.jiangyuefengyu.redstonecircuit.logic;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.jiangyuefengyu.redstonecircuit.RCConfig;
import com.jiangyuefengyu.redstonecircuit.carrier.CarriedHostIndex;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierId;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierProjection;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierStore;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import org.jetbrains.annotations.Nullable;

/**
 * The solver's own logging, aggregated: one summary line per window, and one warning when a host is
 * recomputed over and over inside a single tick.
 *
 * <p>The four per-step lines - the world change that notified a host, the output change that re-solved one,
 * the recompute seed and the component power change - are counted rather than printed, and appear only
 * behind {@code debugLog} <b>and</b> {@code verboseSolverLog}; the summary carries the totals, how many hosts
 * were involved and the worst host's share, once per {@value #SUMMARY_WINDOW_TICKS} ticks.
 *
 * <p>More than {@code solverSpinWarnPerTick} recomputes of one host inside one tick is not a slow machine
 * but the solver being re-fed by its own neighbour notifications, so that warning is written once per host
 * per tick and is not behind the verbose switch.
 *
 * <p>It counts and never decides: no solver behaviour reads anything here, each counter is bounded to
 * {@value #MAX_TRACKED_HOSTS} distinct hosts, and {@link #clear()} runs when a level unloads.
 */
public final class SolverLog {

    /** How many ticks one summary line covers. Twenty is one second, and about 60 lines a minute. */
    public static final int SUMMARY_WINDOW_TICKS = 20;

    /** How many distinct hosts one counter remembers before the rest are pooled into one number. */
    public static final int MAX_TRACKED_HOSTS = 32;

    /** How many spin warnings one tick may write, so a pathological world cannot flood the log either. */
    public static final int MAX_SPIN_WARNINGS_PER_TICK = 4;

    /** Notifications that re-queued a host, and the hosts they were about. */
    private static int changes;
    private static final Map<BlockPos, Integer> changeHosts = new LinkedHashMap<>();

    /** Component recomputes, the seed each was about, and how many hosts were pooled. */
    private static int recomputes;
    private static final Map<BlockPos, Integer> windowPerHost = new LinkedHashMap<>();
    private static int pooledHosts;

    /** Component power changes the solver applied. */
    private static int componentChanges;

    /** Recomputes since the last flush, i.e. within the tick that is running; drives the spin warning. */
    private static final Map<BlockPos, Integer> tickPerHost = new LinkedHashMap<>();

    /**
     * The convergence figures for the window: settle rounds, solves, and notifications sent, read off
     * {@link SolverNotifications#lastStats} once per tick and summed.
     *
     * <p>Rounds per solve is the number that separates "it converged" from "it stopped because the round
     * budget ran out": with the notifications coalesced to the tick boundary a settled circuit reaches its
     * fixed point in one round per solve.
     */
    private static int windowRounds;
    private static int windowSolves;
    private static int windowNotifications;

    /** The same, for one tick; answers "at most one round per tick" directly. */
    private static int roundsThisTick;
    private static int solvesThisTick;

    /**
     * How many ticks the current window has covered: counted rather than read from the level's clock, so a
     * reloaded world whose clock went backwards cannot throw the window off.
     */
    private static int windowTicks;

    /** The last game tick a flush was done for, so several loaded dimensions still end one tick once. */
    private static long lastFlushedTick = Long.MIN_VALUE;

    private SolverLog() {
    }

    /**
     * Whether the per-step detail lines should be written: {@code debugLog} is the mod's general diagnostic
     * switch and is on by default, while this detail is the one thing here that can produce hundreds of lines
     * a second.
     */
    public static boolean verbose() {
        return RCConfig.debugLog() && RCConfig.verboseSolverLog();
    }

    /** One "the world around this host changed, so it has to re-solve" notification. */
    public static void change(@Nullable BlockPos host) {
        if (!RCConfig.debugLog()) {
            return;
        }
        changes++;
        count(changeHosts, host);
    }

    /** One component recompute, named by the seed it started from. */
    public static void recompute(@Nullable BlockPos seed) {
        if (!RCConfig.debugLog()) {
            return;
        }
        recomputes++;
        count(tickPerHost, seed);
        if (!count(windowPerHost, seed)) {
            pooledHosts++;
        }
    }

    /** One component whose power the solver changed. */
    public static void componentChanged() {
        if (!RCConfig.debugLog()) {
            return;
        }
        componentChanges++;
    }

    /**
     * Ends one level tick: the spin warnings for the tick that just ended, then the window summary once
     * {@value #SUMMARY_WINDOW_TICKS} ticks have passed. Returns the lines rather than logging them, so the
     * shape and the thresholds are asserted by offline unit tests.
     */
    public static List<String> tickEnd(@Nullable ServerLevel level) {
        return tickEnd(level == null ? 0L : level.getGameTime(), level);
    }

    /**
     * The same with the tick supplied, so the window arithmetic and the spin threshold can be driven to any
     * tick an offline unit test likes.
     */
    static List<String> tickEnd(long tick, @Nullable ServerLevel level) {
        List<String> out = new ArrayList<>();
        if (!RCConfig.debugLog()) {
            tickPerHost.clear();
            return out;
        }
        windowTicks++;
        try {
            out.addAll(spinWarnings(level, tick));
        } finally {
            tickPerHost.clear();
        }
        recordConvergence(level);
        if (windowTicks >= SUMMARY_WINDOW_TICKS) {
            if (anythingCounted() || windowSolves > 0) {
                out.add(summary());
            }
            resetWindow();
        }
        return out;
    }

    /**
     * The convergence evidence: how many settle rounds one solve took, summed over the window so it costs one
     * line per second however busy the solver is. Read off {@link SolverNotifications}, which owns the
     * per-tick counters; this class only aggregates.
     *
     * <p>Rounds per solve is the number to read: a circuit with a fixed point reaches it in one round, while a
     * figure sitting at {@code MAX_ROUNDS_PER_TICK} means the round budget is what stopped it.
     */
    static void recordConvergence(@Nullable ServerLevel level) {
        if (level == null) {
            return;
        }
        SolverNotifications.TickStats stats = SolverNotifications.lastStats(level);
        roundsThisTick = stats.ranSolve() ? stats.rounds() : 0;
        solvesThisTick = stats.ranSolve() ? 1 : 0;
        if (!stats.ranSolve()) {
            return;
        }
        windowRounds += stats.rounds();
        windowSolves++;
        windowNotifications += stats.flushed();
    }

    /**
     * The production form: end the tick and write whatever that produced.
     *
     * <p>Once per game tick, not once per level event: the counters here are global, and ending the tick once
     * per dimension would split one tick's recomputes across three flushes and leave the spin threshold -
     * which is stated <em>per tick</em> - compared against a third of a tick. So the first level to tick a
     * given game time ends it, and the others are no-ops.
     */
    public static void flush(@Nullable ServerLevel level) {
        long tick = level == null ? 0L : level.getGameTime();
        if (level != null && !claimTick(tick)) {
            return;
        }
        for (String line : tickEnd(tick, level)) {
            RCConfig.LOGGER.info("{}", line);
        }
    }

    /**
     * Claims one game tick for the flush; the second and later calls for the same tick answer false, so three
     * loaded dimensions still end one tick once.
     */
    static boolean claimTick(long tick) {
        if (tick == lastFlushedTick) {
            return false;
        }
        lastFlushedTick = tick;
        return true;
    }

    /**
     * The spin warning: one host recomputed more than {@code solverSpinWarnPerTick} times inside one tick,
     * worst first and capped at {@value #MAX_SPIN_WARNINGS_PER_TICK} lines per tick.
     */
    private static List<String> spinWarnings(@Nullable ServerLevel level, long tick) {
        List<String> out = new ArrayList<>();
        if (tickPerHost.isEmpty()) {
            return out;
        }
        int threshold = RCConfig.solverSpinWarnPerTick();
        List<Map.Entry<BlockPos, Integer>> worst = new ArrayList<>(tickPerHost.entrySet());
        worst.sort(Comparator.comparingInt((Map.Entry<BlockPos, Integer> e) -> e.getValue()).reversed());
        int written = 0;
        for (Map.Entry<BlockPos, Integer> entry : worst) {
            if (entry.getValue() <= threshold) {
                break;
            }
            if (written >= MAX_SPIN_WARNINGS_PER_TICK) {
                out.add("[redstonecircuit] solver spin: " + (worst.size() - written)
                        + " more host(s) were recomputed more than " + threshold
                        + " time(s) in this tick; only the worst " + MAX_SPIN_WARNINGS_PER_TICK
                        + " are named (see the 'solver:' summary for the totals)");
                break;
            }
            written++;
            BlockPos pos = entry.getKey();
            out.add("[redstonecircuit] solver spin: host " + pos.toShortString() + " was recomputed "
                    + entry.getValue() + " time(s) in one tick (tick=" + tick + ", threshold=" + threshold
                    + ")" + structureOf(level, pos)
                    + " - the solver is being re-fed by its own neighbour notifications inside one tick, "
                    + "which is a defect rather than a slow machine; the per-step lines are behind "
                    + "verboseSolverLog, and this summary is not");
        }
        return out;
    }

    /**
     * One line for the window: the totals, how many hosts, the worst host's share, and how many settle rounds
     * each solve needed - all on one line so that a reader has one thing to grep.
     */
    private static String summary() {
        BlockPos worst = null;
        int worstCount = 0;
        for (Map.Entry<BlockPos, Integer> entry : windowPerHost.entrySet()) {
            if (entry.getValue() > worstCount) {
                worst = entry.getKey();
                worstCount = entry.getValue();
            }
        }
        StringBuilder sb = new StringBuilder("[redstonecircuit] solver: ")
                .append(changes).append(" change(s) on ").append(changeHosts.size()).append(" host(s), ")
                .append(recomputes).append(" recompute(s), ")
                .append(componentChanges).append(" component change(s) in the last ")
                .append(windowTicks).append(" tick(s)");
        if (worst != null) {
            sb.append(" (worst host=").append(worst.toShortString()).append(" at ").append(worstCount)
                    .append(" recompute(s) in the window)");
        }
        // "The solver ran" and "the solver converged" are different statements, and only the second one is the
        // fix: rounds/solve is 1 for a circuit that reached its fixed point in a single pass, and
        // MAX_ROUNDS_PER_TICK for one the budget had to stop.
        if (windowSolves > 0) {
            sb.append("; convergence: ").append(windowSolves).append(" solve(s), ").append(windowRounds)
                    .append(" round(s) = ")
                    .append(String.format(java.util.Locale.ROOT, "%.2f",
                            (double) windowRounds / windowSolves))
                    .append(" round(s)/solve, ").append(windowNotifications)
                    .append(" notification(s) sent (coalesced to the tick boundary: "
                            + "solverCoalesceNotifications=")
                    .append(RCConfig.solverCoalesceNotifications()).append(')');
        }
        if (pooledHosts > 0) {
            sb.append(" (").append(pooledHosts).append(" recompute(s) were on hosts beyond the first ")
                    .append(MAX_TRACKED_HOSTS).append(" counted)");
        }
        // The carrier read counters ride on this line so a log with no command in it can still say whether a
        // cross-structure read was attempted; empty when nothing has been read, which keeps the line
        // byte-for-byte the same in a level with nothing physicalised.
        String carrierReads = SignalReadTrace.describe();
        if (!carrierReads.isEmpty()) {
            sb.append("; ").append(carrierReads);
        }
        sb.append("; the per-step lines are behind verboseSolverLog");
        return sb.toString();
    }

    /**
     * Which structure, if any, a spinning position belongs to - and where inside it. Three questions in the
     * order that costs least: is a carried component projected at this world position, is this a position a
     * record answers at, and is this a structure's own plot coordinate. The last one reuses the carrier
     * store's bounded scan, and the whole thing is inside a {@code try} because a warning must never throw.
     */
    private static String structureOf(@Nullable ServerLevel level, @Nullable BlockPos pos) {
        try {
            if (level == null || pos == null || !CarrierStore.isActive()) {
                return "";
            }
            CarrierProjection.ProjectedHost projected = CarrierStore.projectedHostAt(level, pos);
            if (projected != null) {
                return " carrier=" + projected.carrier().shortId()
                        + " local=" + projected.localPos().toShortString();
            }
            CarriedHostIndex index = CarrierStore.indexOf(level);
            if (index == null) {
                return "";
            }
            CarrierId holder = index.carrierHolding(pos);
            if (holder != null) {
                return " carrier=" + holder.shortId() + " local=this position is where it was picked up";
            }
            CarrierStore.LocalScan scan = CarrierStore.scanLocalCoordinate(index, pos);
            if (!scan.records().isEmpty()) {
                CarrierStore.LocalRecord record = scan.records().get(0);
                return " carrier=" + record.carrier().shortId()
                        + " local=" + record.host().localPos().toShortString()
                        + " (this position IS that structure's own coordinate)";
            }
            return "";
        } catch (RuntimeException | LinkageError unreadable) {
            // A diagnostic must never be the thing that fails a tick.
            return "";
        }
    }

    /** True when anything at all was counted in this window. */
    private static boolean anythingCounted() {
        return changes > 0 || recomputes > 0 || componentChanges > 0;
    }

    /**
     * Counts one host, bounded: past {@link #MAX_TRACKED_HOSTS} the map stops growing and the caller is told
     * so it can pool the number instead. Returns whether the host was counted in the map.
     */
    private static boolean count(Map<BlockPos, Integer> map, @Nullable BlockPos pos) {
        if (pos == null) {
            return true;
        }
        Integer previous = map.get(pos);
        if (previous != null) {
            map.put(pos, previous + 1);
            return true;
        }
        if (map.size() >= MAX_TRACKED_HOSTS) {
            return false;
        }
        map.put(pos.immutable(), 1);
        return true;
    }

    private static void resetWindow() {
        changes = 0;
        recomputes = 0;
        componentChanges = 0;
        pooledHosts = 0;
        windowTicks = 0;
        windowRounds = 0;
        windowSolves = 0;
        windowNotifications = 0;
        changeHosts.clear();
        windowPerHost.clear();
    }

    /**
     * How many settle rounds the last tick that ran a solve used: "at most one round for a settled circuit"
     * is what a unit test can assert directly, without a loaded config or a level.
     */
    public static int roundsInLastTick() {
        return roundsThisTick;
    }

    /** How many solves the last tick ran (0 when the tick had nothing queued). */
    public static int solvesInLastTick() {
        return solvesThisTick;
    }

    /** How many distinct hosts the window counted, and how many it pooled; for the tests. */
    public static int trackedHosts() {
        return windowPerHost.size();
    }

    /**
     * Forgets everything, including the window clock: called when a level unloads or the server stops,
     * because a counter that survived a reload would report a window that never happened. Also the unit
     * tests' reset, so one test cannot leak into the next.
     */
    public static void clear() {
        changes = 0;
        recomputes = 0;
        componentChanges = 0;
        pooledHosts = 0;
        windowTicks = 0;
        windowRounds = 0;
        windowSolves = 0;
        windowNotifications = 0;
        roundsThisTick = 0;
        solvesThisTick = 0;
        lastFlushedTick = Long.MIN_VALUE;
        changeHosts.clear();
        windowPerHost.clear();
        tickPerHost.clear();
    }
}

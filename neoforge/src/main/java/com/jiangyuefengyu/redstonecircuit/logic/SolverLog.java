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
 * <b>The solver's own logging, aggregated: one summary line per window, and one warning when a host is
 * being recomputed over and over inside a single tick.</b>
 *
 * <h2>Why this exists, in numbers</h2>
 * The author's P1.22 run produced a 52.5 MB log. 249,027 of its 249,387 lines were this mod's, and
 * <b>242,588</b> of those were four per-step lines:
 *
 * <pre>
 *   world change at … notified host … -> re-solving                          86,543
 *   output change at host … -> re-solving                                    77,416
 *   recompute seed=… component=[…]                                           31,045
 *   DUST at … power 14 -> 13 (supply=13, externalSignal=13, …)               47,584
 * </pre>
 *
 * <p>That is about <b>400 lines a second</b> for a test that lasted a minute, and it is not a
 * player's click rate - a click cannot produce thirty thousand recomputes. It is the solver writing one
 * line per step of a loop that was running inside a single tick, which is the second thing this class is
 * for.
 *
 * <h2>The two things it does</h2>
 * <ol>
 *   <li><b>Counts instead of printing.</b> Those four lines become one {@code solver: …} line per
 *       {@value #SUMMARY_WINDOW_TICKS} ticks, carrying the totals, how many hosts were involved and the
 *       worst host's share. The per-step detail is still available, behind {@code debugLog}
 *       <b>and</b> {@code verboseSolverLog} (off by default) - because when a solver problem is being
 *       chased, the steps are exactly what is wanted, and it should be one config edit away rather than
 *       a rebuild.</li>
 *   <li><b>Says when a host is spinning.</b> More than {@code solverSpinWarnPerTick} recomputes of one
 *       host within one tick is not "a slow machine": it means the solver is being re-fed by its own
 *       neighbour notifications in the same tick, which is a defect and needs to be visible without
 *       reading four million lines. That warning is written <b>once per host per tick</b>, it carries
 *       the host's coordinate, the tick and - if the host belongs to a moving structure - the carrier
 *       and its local coordinate, and it is deliberately <b>not</b> behind the verbose switch: it is the
 *       one line that survives the aggregation.</li>
 * </ol>
 *
 * <h2>What it is not allowed to do</h2>
 * It counts; it never decides. No solver behaviour reads anything here, the counters are bounded to
 * {@value #MAX_TRACKED_HOSTS} distinct hosts per map (the rest are pooled into one number rather than
 * growing the map), the carrier lookup a warning makes is read-only and capped by
 * {@code CarrierStore#scanLocalCoordinate}'s own ceiling, the whole of it is behind {@code debugLog}, and
 * {@link #clear()} is called when a level unloads so nothing survives a reload.
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
     * The convergence figures for the window: settle rounds, solves, and notifications sent.
     *
     * <p>Read off {@link SolverNotifications#lastStats} once per tick and summed. With the notifications
     * coalesced to the tick boundary a settled circuit reaches its fixed point in <b>one</b> round per
     * solve, so "rounds per solve" is the number that separates "it converged" from "it stopped because
     * the round budget ran out" - which is the whole of the evidence the next log has to carry. Part of
     * the window summary, and deliberately <b>not</b> behind {@code verboseSolverLog}: one number per
     * twenty ticks is exactly what the summary line is for.
     */
    private static int windowRounds;
    private static int windowSolves;
    private static int windowNotifications;

    /** The same, for one tick; used to answer "at most one round per tick" directly. */
    private static int roundsThisTick;
    private static int solvesThisTick;

    /**
     * How many ticks the current window has covered.
     *
     * <p>Counted rather than measured against the level's clock on purpose: a summary is due after a
     * number of <em>ticks this code saw</em>, which is what "in the last N ticks" means, and which cannot
     * be thrown off by a reloaded world whose clock went backwards or by a level that was not ticked.
     */
    private static int windowTicks;

    /** The last game tick a flush was done for, so several loaded dimensions still end one tick once. */
    private static long lastFlushedTick = Long.MIN_VALUE;

    private SolverLog() {
    }

    /**
     * Whether the per-step detail lines should be written.
     *
     * <p>Two switches rather than one on purpose: {@code debugLog} is the mod's general diagnostic switch
     * and is on by default, while this detail is the one thing in the mod that can produce hundreds of
     * lines a second. So the default is the summary, and the steps are one config edit away.
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
     * Ends one level tick: the spin warnings for the tick that just ended, then the window summary when
     * {@value #SUMMARY_WINDOW_TICKS} ticks have passed.
     *
     * <p>Returns the lines rather than logging them so the shape and the thresholds are asserted by
     * offline unit tests, exactly as {@code CarrierEntryTrace#candidates} does; {@link #flush} is the
     * production caller and writes them.
     *
     * @param level the level the tick belongs to, or {@code null} in the unit tests
     */
    public static List<String> tickEnd(@Nullable ServerLevel level) {
        return tickEnd(level == null ? 0L : level.getGameTime(), level);
    }

    /**
     * The same, with the tick supplied.
     *
     * <p>Split out so the window arithmetic and the spin threshold - the two things about this class
     * that cannot be checked by reading it - are pinned by an offline unit test that can drive any tick it
     * likes. {@link #tickEnd(ServerLevel)} is the production form and is this with the level's clock.
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
     * <b>The convergence evidence: how many settle rounds one solve took.</b>
     *
     * <p>One line per solve, at the same window as the summary. The number to read is
     * <b>rounds per solve</b>: a circuit that has a fixed point reaches it in one round once the solve
     * cannot be fed by its own notifications, so a figure of 1 (or a small constant, for a chain that
     * genuinely needs to propagate in several passes) is the proof that the fix is in effect. A figure
     * that sits at {@code MAX_ROUNDS_PER_TICK} is the opposite proof: the round budget is what stopped it.
     *
     * <p>Summed over the window rather than printed per tick, so it costs one line per second however
     * busy the solver is. Read off {@link SolverNotifications}, which owns the per-tick counters; this
     * class only aggregates and never decides.
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
     * <p><b>Once per game tick, not once per level event.</b> The tick handler is called for every loaded
     * dimension, and the counters here are deliberately global (a redstone component is a position in one
     * level, and the counts are about the solver as a whole). Ending the tick once per level would split
     * one tick's recomputes across three flushes, and the spin threshold - which is stated <em>per
     * tick</em> - would then be measured against a third of a tick and never fire. So the first level to
     * tick a given game time ends it, and the others are no-ops.
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
     * Claims one game tick for the flush; the second and later calls for the same tick answer false.
     *
     * <p>Package-visible so "three loaded dimensions still end one tick once" is an offline assertion.
     */
    static boolean claimTick(long tick) {
        if (tick == lastFlushedTick) {
            return false;
        }
        lastFlushedTick = tick;
        return true;
    }

    /**
     * <b>The spin warning: one host recomputed too many times inside one tick.</b>
     *
     * <p>Ordered worst-first and capped at {@value #MAX_SPIN_WARNINGS_PER_TICK} lines per tick, so a
     * world where everything spins still produces a bounded number of them. The threshold is
     * {@code solverSpinWarnPerTick}.
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
     * One line for the window: the totals, how many hosts, the worst host's share, and <b>how many settle
     * rounds each solve needed</b>.
     *
     * <p>The rounds figure is the convergence evidence this round exists to provide, and it is on the same
     * line as the totals so that a reader has one thing to grep: {@code rounds/solve}. A settled circuit
     * reaches its fixed point in one round per solve when the solve cannot be fed by its own notifications
     * (see {@link SolverNotifications}), so a small number here - one, or a small constant for a chain that
     * genuinely needs several passes - is the proof; a number sitting at {@code MAX_ROUNDS_PER_TICK} is the
     * proof of the opposite.
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
        // THE CONVERGENCE FIGURES. "The solver ran" and "the solver converged" are different statements,
        // and only the second one is the fix: rounds/solve is 1 for a circuit that reached its fixed
        // point in a single pass, and MAX_ROUNDS_PER_TICK for one the budget had to stop.
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
        // P1.38: THE CARRIER READ COUNTERS, on the line the author already greps. Before this they only
        // existed behind the /rc command, so a log without a command in it could not say whether a
        // cross-structure read had been attempted at all. Empty when nothing has been read, which keeps
        // this line byte-for-byte the same in a level with nothing physicalised.
        String carrierReads = SignalReadTrace.describe();
        if (!carrierReads.isEmpty()) {
            sb.append("; ").append(carrierReads);
        }
        sb.append("; the per-step lines are behind verboseSolverLog");
        return sb.toString();
    }

    /**
     * Which structure, if any, a spinning position belongs to - and where inside it.
     *
     * <p>Three questions in the order that costs least: is a carried component <em>projected</em> at this
     * world position (the ordinary case, one hash lookup); is this position one a record answers at;
     * and - the case the author's own log is full of - is this position a structure's <b>own</b>
     * coordinate, which it is when a sub-level's block is being solved in the plot region. The last one
     * reuses {@code CarrierStore#scanLocalCoordinate}, so it is bounded by that method's own ceiling, and
     * the whole thing is inside a {@code try} because a warning must never be able to throw.
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
     * Counts one host, bounded: past {@link #MAX_TRACKED_HOSTS} the map stops growing and the caller is
     * told so it can pool the number instead.
     *
     * @return true when the host was counted in the map, false when it was pooled
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
     * How many settle rounds the last tick that ran a solve used, and how many solves it ran.
     *
     * <p>The per-tick form of the convergence evidence: "at most one round for a settled circuit" is what
     * a unit test can assert directly, without a loaded config or a level.
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
     * Forgets everything, including the window clock.
     *
     * <p>Called when a level unloads or the server stops, for the same reason the carrier store's own
     * state is: a counter that survived a reload would report a window that never happened. Also the unit
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

package com.jiangyuefengyu.redstonecircuit.client;

import org.jetbrains.annotations.Nullable;

import com.jiangyuefengyu.redstonecircuit.RCConfig;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierHostCheck;

import net.minecraft.core.BlockPos;

/**
 * <b>The throttle and the wording for the two diagnostics this round added: "the structure is not drawn
 * seven chunks from where it was placed" (B1) and "the component inside is visible through a wall" (B2).</b>
 *
 * <h2>Why they are not behind {@code verboseRenderLog}</h2>
 * Both answer a question the author has to reproduce by hand: they put the structure somewhere, fly it
 * away, and look. A diagnostic that only speaks when a switch is already on is a diagnostic that arrives
 * after the reproduction, and the reproduction is the expensive part. So these two are written whenever
 * {@code debugLog=true} - which is on by default - and the size of the log is kept by <b>throttling</b>
 * rather than by a switch. The author's machine runs {@code debugLog=true} and
 * {@code verboseRenderLog=false}, which is the configuration every number below is stated for.
 *
 * <h2>The hard bounds, and the worst-case cost of a frame</h2>
 * <table border="1">
 *   <caption>Every line this class can write, and how often</caption>
 *   <tr><th>line</th><th>bound</th><th>worst case per frame</th></tr>
 *   <tr>
 *     <td>{@code carrier … originSection=… collected=…} (B1, one per problem carrier)</td>
 *     <td>at most {@link #CARRIER_PROBLEM_LINES_PER_WINDOW} per {@link #SWEEP_INTERVAL_TICKS} ticks
 *         <b>per dimension</b> - 8 per 100 ticks = 1.6 per second, 96 per minute; and at most
 *         {@code CARRIER_PROBLEM_LINES_PER_WINDOW} per carrier, since {@link ClientHostCache#carrierSweep}
 *         builds no line for a carrier it did not name</td>
 *     <td>zero: the window is tested first and the sweep's records are not even built until it is due</td>
 *   </tr>
 *   <tr>
 *     <td>{@code carriers=… collected=… skippedOriginOnly=…} (B1 rollup)</td>
 *     <td>one per {@link #SWEEP_INTERVAL_TICKS} ticks at the very most, and in practice <b>only when the
 *         numbers changed</b> (floored by {@link #SETTLEMENT_MIN_INTERVAL_TICKS}) or when the
 *         {@link #SWEEP_HEARTBEAT_TICKS} heartbeat is due - so a healthy machine writes one line and
 *         then nothing for two minutes</td>
 *     <td>zero in the common case: the gate is one string compare</td>
 *   </tr>
 *   <tr>
 *     <td>{@code carrier … local=… drawnWorld=… ray=…} (B2, one worked host)</td>
 *     <td>one per {@link #SIGHT_INTERVAL_TICKS} ticks <b>per dimension</b> - 1 per 100 ticks = 0.2 per
 *         second, 12 per minute - and never the same text twice</td>
 *     <td>zero: one call to {@link CarrierSightThrottle#windowExpired} and one string compare; the line
 *         itself was built by the collector</td>
 *   </tr>
 * </table>
 *
 * <h2>Where the arithmetic lives</h2>
 * Every "is it due" answer is <b>one call into {@link CarrierSightThrottle}</b>, which states the rule as
 * a function from a clock reading to a boolean. This class holds only the memory - when each line was
 * last written, and what settlement the last visit saw - so a test can assert the boolean sequence
 * itself instead of counting the lines a loop happened to produce. The previous round counted lines, and
 * every one of its expectations was off by one; see that class for the convention (in particular that a
 * window opens on {@code now - last >= window} rather than on {@code tick % window == 0}).
 *
 * <h2>What it is not</h2>
 * Nothing in this class is read by any judgement. It reads the client's own index and it writes lines;
 * the pose, the collection, the sight rule and the picture are all decided elsewhere and are unchanged
 * by whether a line was written.
 */
final class CarrierSightLog {

    /** The most problem carriers named in one B1 window; the rest are counted in the rollup. */
    static final int CARRIER_PROBLEM_LINES_PER_WINDOW = 8;

    /**
     * How often the B1 sweep may run at its most frequent, in ticks.
     *
     * <p>Five seconds. The finding it exists for is a <em>state</em> - "this structure's blocks came from
     * somewhere the walk does not look" - and a state does not need to be reported three times a second.
     * It is deliberately longer than the frame summary's window: the per-carrier lines are the long ones,
     * and one look at five seconds of a reproduction is already more than enough to see the difference
     * between "collected" and "not collected".
     */
    static final int SWEEP_INTERVAL_TICKS = 100;

    /**
     * How often the B1 rollup is re-written when its numbers have <b>not</b> changed, in ticks.
     *
     * <p>Two minutes, the same promise the frame summary's heartbeat makes: a diagnostic that cannot
     * report its own absence is not a diagnostic, and an unchanged line that repeats every five seconds
     * is the noise this class exists to avoid. Two minutes of an unchanged picture is one line.
     *
     * <p>Deliberately equal to {@code CarrierRenderTrace.SETTLED_HEARTBEAT_TICKS}: the two lines are
     * written side by side on a parked machine, and a reader comparing them should not have to hold two
     * different periods in their head.
     */
    static final int SWEEP_HEARTBEAT_TICKS = 2400;

    /**
     * How often the B2 line is written, in ticks.
     *
     * <p>Five seconds, for the same reason as the sweep and one more: the host it names is one worked
     * example of a per-frame question, so a reader wants a handful of samples across a reproduction
     * rather than a stream of the same coordinates.
     */
    static final int SIGHT_INTERVAL_TICKS = 100;

    /**
     * How little time may pass between two B1 sweeps when the settlement has changed, in ticks.
     *
     * <p>Two seconds. This is the floor under the "a change is written at once" rule: a machine whose
     * offered-carrier count flickers, or a player walking in and out of the walk's radius, changes the
     * settlement on frame after frame, and without a floor that would be a line per frame - which is the
     * exact shape of the 26,668-line defect this log has already paid for once.
     */
    static final int SETTLEMENT_MIN_INTERVAL_TICKS = 40;

    /** When the B1 sweep last ran, in client ticks; a negative value before the first frame. */
    private static long lastSweepVisitTick = Long.MIN_VALUE;

    /**
     * The settlement the last visit <b>saw</b>, whether or not it was written.
     *
     * <p>Kept apart from the settlement last <em>written</em>, because the two answer different questions
     * and conflating them was the bug this field fixes: with only the written one remembered, a visit
     * that was floored or suppressed left the memory empty, so every later window looked like a change
     * and the gate opened 59 times in 40 seconds of a picture that never changed.
     */
    @Nullable
    private static String lastSweepSeen;

    /** The B1 rollup's own numbers as they were last written, so an unchanged state is not repeated. */
    @Nullable
    private static String lastSweepSignature;

    /** When the B1 rollup was last written, for the heartbeat; a negative value before the first. */
    private static long lastSweepWriteTick = Long.MIN_VALUE;

    /** When the B2 line was last written, in client ticks; a negative value before the first. */
    private static long lastSightTick = Long.MIN_VALUE;

    /** The last B2 line written, so an identical one is never repeated whatever the window says. */
    @Nullable
    private static String lastSightLine;

    private CarrierSightLog() {
    }

    /**
     * <b>Whether the B1 sweep is due on this frame.</b>
     *
     * <p>Called on every frame and given numbers that are cheap to obtain - the carrier count, and the
     * numbers the last sweep produced. The comparison is one string equality, so an ordinary frame costs
     * a few integer reads and one small compare; the walk itself is only paid for when this says yes.
     * The rule is {@link CarrierSightThrottle#sweepDue}, stated once and asserted on its own.
     *
     * <p>The visit is <b>recorded here</b>, whenever a visit happens, whether or not the writer then
     * finds anything worth a line: the memory the change rule is measured against has to be what the
     * last look saw. The writer keeps its own, separate memory of what it last <em>wrote</em>.
     *
     * @param nowTicks the client's game time
     * @param last the previous sweep, or {@code null} before the first one
     * @param carriers how many carriers the client holds before this sweep runs
     * @return true when the caller should build a sweep and offer it to {@link #writeSweep}
     */
    static boolean sweepDue(long nowTicks, @Nullable ClientHostCache.CarrierSweep last, int carriers) {
        if (!RCConfig.debugLog()) {
            return false;
        }
        String seen = settlementOf(last, carriers);
        // THE HEARTBEAT IS MEASURED FROM THE LAST TIME WE SPOKE <b>OR LOOKED</b>, whichever is later.
        //
        // `lastSweepWriteTick` alone is "since the last line", and before the first line it is negative,
        // which `windowExpired` reads as "never written, so due now" - so a caller that drives the gate
        // without the writer (a test, or the first frames after a level change, when the writer has
        // nothing new to say) would re-open the heartbeat on every window and produce one line per
        // window where one line per two minutes is promised. Anchoring on the later of the two keeps the
        // promise in both driving styles: the first visit sets the visit tick, and a write moves the
        // anchor forward as well.
        long spokenOrLooked = Math.max(lastSweepWriteTick, lastSweepVisitTick);
        boolean due = CarrierSightThrottle.sweepDue(lastSweepSeen != null,
                lastSweepSeen == null || !seen.equals(lastSweepSeen),
                nowTicks, lastSweepVisitTick, spokenOrLooked, SETTLEMENT_MIN_INTERVAL_TICKS,
                SWEEP_INTERVAL_TICKS, SWEEP_HEARTBEAT_TICKS);
        if (due) {
            lastSweepSeen = seen;
            lastSweepVisitTick = nowTicks;
        }
        return due;
    }

    /**
     * Whether the settlement these numbers describe differs from the one the last visit saw.
     *
     * <p>Plain equality against a string of the five numbers, and deliberately the <b>same</b> string
     * {@link #writeSweep} compares: two spellings of "did anything change" would be two answers.
     */
    static boolean settlementChanged(String signature) {
        return !signature.equals(lastSweepSeen);
    }

    /** The settlement as one comparable string: the numbers a B1 line exists to report. */
    static String settlement(int carriers, int collected, int onlySkipped, int noPose, int problemLines) {
        return carriers + "/" + collected + "/" + onlySkipped + "/" + noPose + "/" + problemLines;
    }

    /**
     * The settlement of one sweep, in the one spelling the gate and the writer share.
     *
     * @param sweep the last sweep's numbers, or {@code null} before the first one
     * @param carriers the carrier count to state them against; the caller passes the <b>live</b> count
     *     so that a machine appearing or leaving is a change even before the next walk
     */
    static String settlementOf(@Nullable ClientHostCache.CarrierSweep sweep, int carriers) {
        if (sweep == null) {
            // "Nothing has been seen yet" is its own signature, and it can never equal a real one - so the
            // first frame reports itself without a second flag saying so.
            return "none/" + carriers;
        }
        return settlement(carriers, sweep.collected(), sweep.originOnlySkipped(), sweep.noPose(),
                sweep.problemCount());
    }

    /** The settlement as it was last <b>written</b>, for the "did anything change" half of the writer. */
    @Nullable
    static String lastSweepSignature() {
        return lastSweepSignature;
    }

    /** The settlement the last sweep visit saw, whether or not a line came of it; for a test. */
    @Nullable
    static String lastSweepSeen() {
        return lastSweepSeen;
    }

    /** Whether the rollup's heartbeat has opened since the last write; see {@link #writeSweep}. */
    static boolean rollupHeartbeatDue(long nowTicks, long lastWriteTick, int heartbeatTicks) {
        return CarrierSightThrottle.windowExpired(nowTicks, lastWriteTick, heartbeatTicks);
    }

    /**
     * Writes the B1 rollup, and one line per problem carrier, <b>if the state is worth a line</b>.
     *
     * <p>The rollup's numbers are the signature: while they are unchanged the line is suppressed, and
     * once the heartbeat has passed since the last write it is re-written anyway so a reader can always
     * tell "the sweep ran and found nothing" from "the sweep never ran".
     *
     * @param dimensionName the level's name, for the rollup's own line
     * @param sweep what the client's index holds, or {@code null} when there is nothing to describe
     * @param skippedStale carriers the walk offered whose pose had gone stale, as the renderer counted
     *     them for this frame
     * @param skippedFar carriers the walk offered that were past the draw distance from the camera, as
     *     the renderer counted them for this frame
     * @param nowTicks the client's game time
     * @param cameraSection the section the camera is in, or {@code null}
     * @param heartbeatTicks the rollup's heartbeat, passed in rather than read from the constant so the
     *     arithmetic can be driven at any period by a test
     * @return how many lines were written, for the caller's own reporting and for the unit tests
     */
    static int writeSweep(String dimensionName, @Nullable ClientHostCache.CarrierSweep sweep,
                          int skippedStale, int skippedFar, long nowTicks,
                          @Nullable BlockPos cameraSection, int heartbeatTicks) {
        if (sweep == null || !sweep.dimensionKnown()) {
            return 0;
        }
        String signature = settlementOf(sweep, sweep.carriers());
        // A LOOK IS A LOOK, whichever entry point made it. The writer has the settlement in hand, so it
        // records it as seen as well as (below) as written: the change rule is measured against "what the
        // last look saw", and a caller that drives the writer without the gate would otherwise leave that
        // memory empty and make every later window look like a change - the same defect the two separate
        // fields exist to fix, one entry point further out.
        lastSweepSeen = signature;
        boolean changed = !signature.equals(lastSweepSignature);
        boolean heartbeat = rollupHeartbeatDue(nowTicks, lastSweepWriteTick, heartbeatTicks);
        boolean problemsToName = !sweep.problems().isEmpty() && (changed || heartbeat);
        if (!changed && !heartbeat && !problemsToName) {
            return 0;
        }
        int written = 0;
        if (changed || heartbeat) {
            lastSweepSignature = signature;
            lastSweepWriteTick = nowTicks;
            // The counters, in the wording the acceptance procedure greps for. `collected` is the number
            // of carriers the walk offered, which is what a reader compares against `skippedOriginOnly` -
            // the carriers it did not offer for no reason but where their blocks came from.
            // `skippedStale` and `skippedFar` are the renderer's own per-frame counters, so "the walk
            // offered it and the renderer then refused it" stays distinguishable from "the walk never
            // offered it", which is the whole of B1.
            RCConfig.LOGGER.info("[redstonecircuit] client: originWalk {} {} cameraSection={}",
                    dimensionName,
                    CarrierHostCheck.Tally.describeCarriers(sweep.carriers(), sweep.collected(),
                            sweep.originOnlySkipped(), sweep.noPose(), skippedStale, skippedFar),
                    CarrierHostCheck.section(cameraSection));
            written++;
        }
        if (problemsToName) {
            for (ClientHostCache.CarrierSection problem : sweep.problems()) {
                RCConfig.LOGGER.info("[redstonecircuit] client: {}", problem.check().describe());
                written++;
            }
        }
        return written;
    }

    /**
     * Whether the B2 line is due on this frame; see {@link CarrierSightThrottle} for the convention.
     *
     * <p>Called once per frame from the same place the B2 example is recorded, so the line that is
     * written is always a line some frame actually produced.
     */
    static boolean sightDue(long nowTicks) {
        return sightDue(nowTicks, SIGHT_INTERVAL_TICKS);
    }

    /**
     * The same, with the window stated rather than read, so a test can drive the arithmetic at any period.
     *
     * <p>The memory is still this class's: a due answer that comes back true moves the window's start
     * forward, which is what makes the sequence a sequence rather than a function of one reading.
     */
    static boolean sightDue(long nowTicks, int windowTicks) {
        if (!RCConfig.debugLog()) {
            return false;
        }
        boolean due = CarrierSightThrottle.sightDue(nowTicks, lastSightTick, windowTicks);
        if (due && nowTicks >= 0) {
            lastSightTick = nowTicks;
        }
        return due;
    }

    /**
     * Writes the B2 line if {@link #sightDue} said so and the line is not the one already written.
     *
     * @param line the render pass's own worked example, or {@code null} when this frame asked no sight
     *     question at all
     * @return true when a line was written
     */
    static boolean writeSight(@Nullable String line, boolean due) {
        if (!due || line == null || line.equals(lastSightLine)) {
            return false;
        }
        lastSightLine = line;
        RCConfig.LOGGER.info("[redstonecircuit] client: {}", line);
        return true;
    }

    /** Reads the current line for a test; the last B2 line written, or {@code null}. */
    @Nullable
    static String lastSightLine() {
        return lastSightLine;
    }

    /**
     * Forgets every window's memory, so one test cannot suppress the next one's lines.
     *
     * <p>The throttle is process-lifetime by design - that is what makes it a throttle - so the only
     * honest way to test it is to be able to state its starting condition. The same shape
     * {@code CarrierRenderTrace#resetLogThrottleForTest} uses.
     */
    static void resetForTest() {
        lastSweepVisitTick = Long.MIN_VALUE;
        lastSweepSeen = null;
        lastSweepSignature = null;
        lastSweepWriteTick = Long.MIN_VALUE;
        lastSightTick = Long.MIN_VALUE;
        lastSightLine = null;
    }
}

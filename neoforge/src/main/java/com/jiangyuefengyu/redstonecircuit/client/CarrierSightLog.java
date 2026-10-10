package com.jiangyuefengyu.redstonecircuit.client;

import org.jetbrains.annotations.Nullable;

import com.jiangyuefengyu.redstonecircuit.RCConfig;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierHostCheck;

import net.minecraft.core.BlockPos;

/**
 * The throttle and the wording for two diagnostics: "the structure is not drawn seven chunks from where it was
 * placed" (B1) and "the component inside is visible through a wall" (B2).
 *
 * <p>Both are written whenever {@code debugLog=true}, because a diagnostic that only speaks when a switch is already
 * on arrives after the reproduction, which is the expensive part; the log's size is kept by <b>throttling</b> rather
 * than by a switch. Every "is it due" answer is one call into {@link CarrierSightThrottle}, which states the rule as
 * a function from a clock reading to a boolean, and this class holds only the memory of when each line was last
 * written. Nothing here is read by any judgement: it reads the client's own index and writes lines.
 */
final class CarrierSightLog {

    /** The most problem carriers named in one B1 window; the rest are counted in the rollup. */
    static final int CARRIER_PROBLEM_LINES_PER_WINDOW = 8;

    /**
     * How often the B1 sweep may run at its most frequent, in ticks. Five seconds: the finding it exists for is a
     * <em>state</em> - this structure's blocks came from somewhere the walk does not look - and a state does not need
     * reporting three times a second.
     */
    static final int SWEEP_INTERVAL_TICKS = 100;

    /**
     * How often the B1 rollup is re-written when its numbers have <b>not</b> changed, in ticks: two minutes. A
     * diagnostic that cannot report its own absence is not a diagnostic, and an unchanged line repeating every five
     * seconds is the noise this class exists to avoid. Equal to
     * {@code CarrierRenderTrace.SETTLED_HEARTBEAT_TICKS}, since the two lines are read side by side.
     */
    static final int SWEEP_HEARTBEAT_TICKS = 2400;

    /**
     * How often the B2 line is written, in ticks. Five seconds, for the same reason as the sweep and one more: the
     * host it names is one worked example of a per-frame question, so a handful of samples across a reproduction is
     * what a reader wants rather than a stream of the same coordinates.
     */
    static final int SIGHT_INTERVAL_TICKS = 100;

    /**
     * How little time may pass between two B1 sweeps when the settlement has changed, in ticks. This is the floor
     * under the "a change is written at once" rule: a machine whose offered-carrier count flickers, or a player
     * walking in and out of the walk's radius, changes the settlement on frame after frame, and without a floor that
     * would be a line per frame.
     */
    static final int SETTLEMENT_MIN_INTERVAL_TICKS = 40;

    /** When the B1 sweep last ran, in client ticks; a negative value before the first frame. */
    private static long lastSweepVisitTick = Long.MIN_VALUE;

    /**
     * The settlement the last visit <b>saw</b>, whether or not it was written, kept apart from the settlement last
     * <em>written</em>: with only the written one remembered, a visit that was floored or suppressed left the memory
     * empty, so every later window looked like a change.
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
     * <b>Whether the B1 sweep is due on this frame.</b> Called every frame with numbers that are cheap to obtain -
     * the carrier count, and the numbers the last sweep produced - so the comparison is one string equality.
     *
     * <p>The visit is <b>recorded here</b> whenever a visit happens, whether or not the writer then finds anything
     * worth a line: the memory the change rule is measured against has to be what the last look saw, while the writer
     * keeps its own memory of what it last <em>wrote</em>.
     */
    static boolean sweepDue(long nowTicks, @Nullable ClientHostCache.CarrierSweep last, int carriers) {
        if (!RCConfig.debugLog()) {
            return false;
        }
        String seen = settlementOf(last, carriers);
        // The heartbeat is measured from the last time we SPOKE or LOOKED, whichever is later.
        //
        // `lastSweepWriteTick` alone is "since the last line", and before the first line it is negative, which
        // `windowExpired` reads as "never written, so due now" - so a caller that drives the gate without the writer
        // would re-open the heartbeat on every window and write one line per window where one per two minutes is
        // promised. Anchoring on the later of the two keeps the promise in both driving styles.
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
     * Whether the settlement these numbers describe differs from the one the last visit saw; deliberately the
     * <b>same</b> string {@link #writeSweep} compares, because two spellings of "did anything change" would be two
     * answers.
     */
    static boolean settlementChanged(String signature) {
        return !signature.equals(lastSweepSeen);
    }

    /** The settlement as one comparable string: the numbers a B1 line exists to report. */
    static String settlement(int carriers, int collected, int onlySkipped, int noPose, int problemLines) {
        return carriers + "/" + collected + "/" + onlySkipped + "/" + noPose + "/" + problemLines;
    }

    /**
     * The settlement of one sweep, in the one spelling the gate and the writer share. {@code carriers} is the
     * <b>live</b> count, so that a machine appearing or leaving is a change even before the next walk.
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
     * Writes the B1 rollup, and one line per problem carrier, <b>if the state is worth a line</b>: while the numbers
     * are unchanged the line is suppressed, and once the heartbeat has passed it is re-written anyway, so a reader can
     * always tell "the sweep ran and found nothing" from "the sweep never ran". {@code heartbeatTicks} is passed in
     * rather than read from the constant, so a test can drive the arithmetic at any period.
     */
    static int writeSweep(String dimensionName, @Nullable ClientHostCache.CarrierSweep sweep,
                          int skippedStale, int skippedFar, long nowTicks,
                          @Nullable BlockPos cameraSection, int heartbeatTicks) {
        if (sweep == null || !sweep.dimensionKnown()) {
            return 0;
        }
        String signature = settlementOf(sweep, sweep.carriers());
        // A look is a look, whichever entry point made it: the writer records the settlement as seen as well as
        // written, because a caller that drives the writer without the gate would otherwise leave that memory empty
        // and make every later window look like a change.
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
            // The counters, in the wording a reader greps for. `collected` is the number of carriers the walk
            // offered, which is what `skippedOriginOnly` is compared against - the carriers it did not offer for no
            // reason but where their blocks came from. `skippedStale` and `skippedFar` are the renderer's own
            // per-frame counters, so "offered and then refused" stays distinguishable from "never offered".
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
     * Whether the B2 line is due on this frame. Called once per frame from the same place the B2 example is recorded,
     * so the line that is written is always a line some frame actually produced.
     */
    static boolean sightDue(long nowTicks) {
        return sightDue(nowTicks, SIGHT_INTERVAL_TICKS);
    }

    /**
     * The same, with the window stated rather than read, so a test can drive the arithmetic at any period. The memory
     * is still this class's: a due answer moves the window's start forward, which is what makes the sequence a
     * sequence rather than a function of one reading.
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
     * Writes the B2 line if {@link #sightDue} said so and it is not the line already written; {@code line} is the
     * render pass's own worked example, or {@code null} when this frame asked no sight question at all.
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
     * Forgets every window's memory, so one test cannot suppress the next one's lines: the throttle is
     * process-lifetime by design, so a test has to be able to state its starting condition.
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

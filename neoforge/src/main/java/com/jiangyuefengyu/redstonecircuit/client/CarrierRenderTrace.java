package com.jiangyuefengyu.redstonecircuit.client;

import java.util.HashSet;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

import com.jiangyuefengyu.redstonecircuit.RCConfig;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierDrawCheck;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierHostCheck;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierTrace;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * <b>Stage 3 of the three-stage diagnostic</b>: what the client actually received, and what the render
 * pass did with it.
 *
 * <h2>The question this answers</h2>
 * "The component does not follow the machine" has two very different causes on the client, and until
 * this class existed they looked identical on screen:
 *
 * <ul>
 *   <li><b>The client was never told.</b> {@code carrierIndex=0} - the server's stage 2 never reached
 *       this client (or reached it before the claim existed). The fault is on the server side of the
 *       wire, and {@code /rc carrier}'s {@code sync:} line is where to look.</li>
 *   <li><b>The client was told and drew nothing.</b> {@code carriedIndex>0, drawnLastFrame=0} - the
 *       hosts are here, so the fault is here: an unset pose ({@code skippedNoPose}), a pose that has
 *       gone stale ({@code skippedStalePose}), the carrier past the draw distance
 *       ({@code skippedFar}), the overlay switched off, or the goggles off.</li>
 * </ul>
 *
 * <p>The counters are written into {@link CarrierTrace}'s render record for the current dimension, so
 * one snapshot of {@code /rc carrier} - or of the {@code client: carrier ...} log line - carries the
 * whole stage. Nothing here affects rendering: every field is an increment on a path that already ran.
 *
 * <h2>The two inconsistencies that are logged rather than counted</h2>
 * A count is only useful to somebody who already suspects the right thing, so the two shapes that mean
 * "the two sides disagree" are written out as a warning as well:
 *
 * <ul>
 *   <li><b>the client holds more carriers than the server has</b> - a removal that was never
 *       announced, which is exactly the defect class this round of work closed; and</li>
 *   <li><b>a carried host points at a carrier with no pose</b> - a host that cannot be drawn inside
 *       anything, and the shape that used to be invisible because no skip counter covered it.</li>
 * </ul>
 *
 * <p>Each is logged <b>once per dimension</b>. A warning that repeats every second is a warning
 * nobody reads, and the state it describes is a condition rather than an event.
 *
 * <h2>Server and client share the game in single-player</h2>
 * In single-player the server's {@code /rc carrier} and this stage live in the same process, which is
 * why one command can print both halves. On a dedicated server the server half prints alone, and the
 * {@code client: carrier ...} line is the one to read on the client's own log.
 */
public final class CarrierRenderTrace {

    /**
     * <b>How often the periodic frame summary is written, in the quiet (default) mode: ticks.</b>
     *
     * <p>P1.24 measured the problem this closes: 40.1 MB of mod log for one session, of which
     * <b>26,668 lines and 32.0 MB</b> were {@code client: carrier frame drew 0, everything settled}
     * repeated once per throttle window while nothing at all was happening, and a further 4.2 MB of
     * {@code drew 1} summaries through the same window. The window was counted in <em>frames</em>
     * ({@link #FRAME_LOG_INTERVAL} = 20 frames, i.e. three a second at 60 fps), and the "empty" branch
     * printed on every window expiry unconditionally - so a parked machine wrote a line every third of a
     * second for as long as the player stood there.
     *
     * <p>The rule is now:
     *
     * <ul>
     *   <li>a <b>state change</b> writes a line, and there is at most one line per
     *       {@code QUIET_LOG_INTERVAL_TICKS} ticks - the counters changing every frame while a machine
     *       moves is one event, not sixty;</li>
     *   <li>an <b>alarm</b> (a pose that just went stale, a landed host the world path could not draw)
     *       writes a line immediately, throttle or not: those are findings, not cadence;</li>
     *   <li>when <b>nothing</b> is changing, the line is written only once per
     *       {@code SETTLED_HEARTBEAT_TICKS} ticks. The heartbeat is not optional - a diagnostic that only
     *       speaks when it has something interesting to say cannot report its own absence - but two
     *       minutes is often enough to prove the pass is running, and 26,668 lines become a handful;</li>
     *   <li>{@code verboseRenderLog=true} restores the previous per-frame behaviour exactly (see
     *       {@link #frameSummary(ResourceKey, int, int, int, int, int, int, int, long, long)}), for when
     *       the frame-by-frame detail is what is wanted.</li>
     * </ul>
     *
     * <h2>P1.48: why the window is 100 and not 20, with the author's own numbers</h2>
     * The author's setting is {@code verboseRenderLog=false}, and their session produced
     * <b>8.73 MB over 4,808 lines</b>. At 20 ticks the window is <b>one line a second</b>, so an hour of
     * play is ~3,600 lines of this summary - and each of those lines was 1-2 KB, because it carried the
     * geometry counters, the skip counters and the landed bookkeeping that nobody reads on a passing
     * frame. The arithmetic is what names the fix: <b>lines per session x bytes per line</b>.
     *
     * <p>So both factors were attacked, and neither of them is a judgement:
     *
     * <ul>
     *   <li><b>the interval is 100 ticks = 5 seconds</b>, which is slow enough for a state - "is the pass
     *       drawing, and on how many carriers" - and fast enough that a player who just moved a machine
     *       still sees the answer twice a minute. An hour of continuous motion is therefore at most ~720
     *       lines, not ~3,600;</li>
     *   <li><b>the default line is a few hundred bytes</b> instead of 1-2 KB: the per-frame detail moved
     *       behind {@code verboseRenderLog} (see {@link CarrierFrameRow}), which the author turns on for
     *       an investigation and which restores the full line.</li>
     * </ul>
     *
     * <p>720 lines x ~400 B is <b>~280 KB an hour</b>, against 8.73 MB for a shorter session - the two
     * orders of magnitude the round was asked for. The heartbeat already bounded the <em>idle</em> case;
     * this bounds the busy one, which is the case the author actually logs.
     */
    static final int QUIET_LOG_INTERVAL_TICKS = 100;

    /**
     * How often the "nothing is happening" heartbeat is written, in ticks, in the quiet mode.
     *
     * <p>Two minutes: often enough that a log always shows the pass was running, rare enough that a long
     * session's log stays under a megabyte.
     */
    static final int SETTLED_HEARTBEAT_TICKS = 2400;

    /**
     * <b>How often the periodic frame summary is written, in frames, in the verbose mode.</b>
     *
     * <p>The P1.24 rate, kept verbatim for {@code verboseRenderLog=true}: see
     * {@link #QUIET_LOG_INTERVAL_TICKS} for why it is no longer the default.
     *
     * <ul>
     *   <li>the counters changing at all (something was drawn, something was skipped, landed hosts
     *       appeared or went) writes a line immediately, and restarts the throttle window;</li>
     *   <li>otherwise one line a second at most, and <b>none at all</b> once the picture has been
     *       stable and empty for a whole window.</li>
     * </ul>
     *
     * <p>{@code FRAME_LOG_INTERVAL} is therefore a ceiling on the idle rate rather than a fixed
     * cadence. During motion the counters change almost every frame, which is exactly when the log is
     * worth reading; parked, it goes quiet.
     */
    static final int FRAME_LOG_INTERVAL = 20;

    /** Frames since the last summary; only ever touched on the render thread. */
    private static int frameLogCountdown;

    /**
     * The previous frame's counters, so "did anything change" can be answered.
     *
     * <p>Reset with the throttle, so the first frame after a quiet period always reports itself: a
     * state that has just changed must not be swallowed by a window it did not start.
     */
    private static int lastDrawn = Integer.MIN_VALUE;
    private static int lastSkippedNoPose = Integer.MIN_VALUE;
    private static int lastSkippedFar = Integer.MIN_VALUE;
    private static int lastSkippedStale = Integer.MIN_VALUE;
    private static int lastLanded = Integer.MIN_VALUE;
    private static int lastLandedWorldHost = Integer.MIN_VALUE;

    /**
     * The previous frame's "yielded but the world path could not draw" count.
     *
     * <p>Watched as well as printed: it is expected to be zero, and a frame that starts reporting it is
     * a state change worth a log line immediately rather than at the next throttle boundary.
     */
    private static int lastLandedDeferred = Integer.MIN_VALUE;

    /** The previous frame's draw-check counts; see {@link #drawCheck}. */
    private static int lastDrawnOffStructure = Integer.MIN_VALUE;
    private static int lastPlotSpaceHosts = Integer.MIN_VALUE;

    /**
     * The previous frame's emitted geometry, so "it stopped emitting" is a state change.
     *
     * <p>Watched for the same reason the pose age is: the numbers that changed between "the component
     * is on screen" and "the screen is empty" are these two, and a summary that only noticed changes in
     * the <em>host</em> counts would throttle the interesting frame away.
     */
    private static int lastShellQuads = Integer.MIN_VALUE;
    private static int lastContentQuads = Integer.MIN_VALUE;

    /**
     * Whether the last frame that reported an age drew from a stale pose.
     *
     * <p>A boolean rather than the age itself, because the transition is what has to force a log line,
     * and an age of {@code -1} ("this frame drew nothing") is not a transition - comparing ages would
     * make every frame after a stale one look stale again.
     */
    private static boolean lastPoseWasStale;

    /** Frames since the last draw-check line; the verbose mode's throttle. See {@link #drawCheck}. */
    private static int drawCheckCountdown;

    /**
     * When each of the three client lines was last written, in client ticks, and whether it ever was.
     *
     * <p>The quiet mode's throttle is measured in ticks rather than in frames - a frame rate is a property
     * of the machine the player happens to have, and "one line per second" has to mean a second. A tick of
     * {@code < 0} means the caller had no clock to hand (a unit test, or the legacy overloads), and then
     * the old frame-based throttle is used so nothing that does not pass a tick changes behaviour.
     */
    private static long lastSummaryTick = Long.MIN_VALUE;
    private static long lastDrawCheckTick = Long.MIN_VALUE;
    private static long lastMatrixProbeTick = Long.MIN_VALUE;
    private static boolean summaryEverWritten;
    private static boolean drawCheckEverWritten;
    private static boolean matrixProbeEverWritten;

    /** Dimensions whose "client holds more carriers than the server" warning has already been written. */
    private static final Set<ResourceKey<Level>> WARNED_MISMATCH = new HashSet<>();

    /** Dimensions whose "a carried host points at a carrier with no pose" warning has been written. */
    private static final Set<ResourceKey<Level>> WARNED_ORPHAN = new HashSet<>();

    private CarrierRenderTrace() {
    }

    /**
     * One rendered frame.
     *
     * <p>Called from the render pass after it has decided what to draw, so {@code drawn} is the number
     * of carried hosts that were actually emitted rather than the number that merely exist.
     *
     * @param dimension the client level's dimension
     * @param drawn carried hosts the frame drew, inside a structure or at a world block
     * @param skippedNoPose carried hosts skipped because their carrier had no pose at all
     * @param skippedFar carried hosts skipped because their carrier was past the draw distance
     * @param skippedStalePose carried hosts skipped because their carrier's pose had gone stale
     * @param landedWorldHost landed hosts passed over because a real block is at the landing position
     * @param landedDeferred landed hosts drawn at their landing position because the world path could
     *     not draw there yet (a host the sync knows about, over a block that has not come back). Now
     *     that a landed record draws itself in that case, this should stay at zero - it is the count
     *     that would say the two yield gates had drifted apart again.
     * @param carriedWorldHost hosts whose carrier went stale that yielded to the world path at their
     *     origin, counted apart from the landed ones
     */
    public static void frame(ResourceKey<Level> dimension, int drawn, int skippedNoPose,
                             int skippedFar, int skippedStalePose, int landedWorldHost,
                             int landedDeferred, int carriedWorldHost) {
        frame(dimension, drawn, skippedNoPose, skippedFar, skippedStalePose, landedWorldHost,
                landedDeferred, carriedWorldHost, 0, 0, -1L);
    }

    /**
     * The same, with the draw check's two counts.
     *
     * <p>The draw check is the measurement that separates "nothing was drawn" from "something was drawn
     * somewhere else" - see {@code CarrierDrawCheck}. It is supplied by the renderer, which is the only
     * place that holds a carrier's pose and one of its local coordinates at the same moment.
     *
     * @param drawnOffStructure drawn hosts whose computed world point is far from their structure's own
     *     position; expected to be zero, and nonzero is the "drawn but invisible" defect named
     * @param plotSpaceHosts drawn hosts whose local coordinates are in a plot-space magnitude range
     */
    public static void frame(ResourceKey<Level> dimension, int drawn, int skippedNoPose,
                             int skippedFar, int skippedStalePose, int landedWorldHost,
                             int landedDeferred, int carriedWorldHost, int drawnOffStructure,
                             int plotSpaceHosts) {
        frame(dimension, drawn, skippedNoPose, skippedFar, skippedStalePose, landedWorldHost,
                landedDeferred, carriedWorldHost, drawnOffStructure, plotSpaceHosts, -1L);
    }

    /**
     * The same, with the age of the oldest pose this frame drew from.
     *
     * <p>{@code worstPoseAgeTicks} is {@code -1} when the frame drew nothing or the caller does not
     * track it. It is recorded every frame and printed with the summary, because "the component is not
     * following the structure" and "the component is following a four-second-old pose of the structure"
     * are the same picture on screen and were the same line in the log.
     */
    public static void frame(ResourceKey<Level> dimension, int drawn, int skippedNoPose,
                             int skippedFar, int skippedStalePose, int landedWorldHost,
                             int landedDeferred, int carriedWorldHost, int drawnOffStructure,
                             int plotSpaceHosts, long worstPoseAgeTicks) {
        CarrierTrace.Render render = CarrierTrace.render(dimension);
        render.setKnown(ClientHostCache.carrierCount(), ClientHostCache.carriedSize());
        render.setFrame(drawn, skippedNoPose, skippedFar, skippedStalePose);
        render.setClientHeld(ClientHostCache.staleCarrierCount(), ClientHostCache.hostsWithoutPose(),
                ClientHostCache.fallbackCount());
        render.setLanded(ClientHostCache.landedCount(), landedWorldHost, landedDeferred,
                carriedWorldHost);
        render.setDrawCheck(drawnOffStructure, plotSpaceHosts);
        render.setPoseAge(worstPoseAgeTicks);
    }

    /**
     * The same, with the sight half of the frame.
     *
     * <p>The five sight counters are the ones that separate "this host was never asked" from "it was
     * asked and the ray refused it" - two states that used to be the same empty screen and the same
     * silent line. They ride the default frame row, so this overload is what the render pass calls; the
     * shorter ones above are kept for the callers that have no sight question to report (and for the
     * tests that drive the pass without a level).
     *
     * <p><b>The worked B2 line is deliberately not set here.</b> {@code CarrierTrace.Render} carries the
     * tally and the line together, and the render pass records the line a moment after this call
     * ({@code CarrierRenderTrace.sightDetail} → {@code sight}); writing it here would blank the line the
     * caller is about to hand in with the previous frame's, so this overload moves the tally only.
     *
     * @param sight how the frame's sight questions were answered; {@code null} reads as "none asked",
     *     which is the same answer an empty tally gives
     */
    public static void frame(ResourceKey<Level> dimension, int drawn, int skippedNoPose,
                             int skippedFar, int skippedStalePose, int landedWorldHost,
                             int landedDeferred, int carriedWorldHost, int drawnOffStructure,
                             int plotSpaceHosts, long worstPoseAgeTicks,
                             @Nullable CarrierHostCheck.Tally sight) {
        frame(dimension, drawn, skippedNoPose, skippedFar, skippedStalePose, landedWorldHost,
                landedDeferred, carriedWorldHost, drawnOffStructure, plotSpaceHosts, worstPoseAgeTicks);
        CarrierTrace.Render render = CarrierTrace.render(dimension);
        render.setSight(sight, render.sightDetail());
    }

    /**
     * One line, logged when the carrier picture changes and periodically while it is being drawn.
     *
     * <p>Logged rather than only printed on demand because the failing case - "the machine is moving and
     * nothing is drawn" - is not something a player thinks to type a command during.
     */
    public static void log(ResourceKey<Level> dimension, String reason) {
        RCConfig.LOGGER.info("[redstonecircuit] client: carrier {} {} carriers={} carriedHosts={}"
                        + " landedHosts={} fallingBack={} held=[{}]",
                reason, CarrierTrace.render(dimension).describe(), ClientHostCache.carrierCount(),
                ClientHostCache.carriedSize(), ClientHostCache.landedCount(),
                ClientHostCache.fallbackCount(), ClientHostCache.describeCarrierAges());
    }

    /**
     * <b>The geometry half of the frame: what was actually written into the vertex buffers.</b>
     *
     * <p>Called from the render pass after it has finished drawing, with the counts its own loops
     * incremented - not with the number of hosts it collected. The difference is the whole point:
     * {@code frame()} records how many hosts the pass <em>handled</em>, and this records how much
     * geometry came out, so a frame that handled a host and emitted nothing is representable in the
     * log instead of looking exactly like a frame that drew it.
     *
     * <p>It also carries the gates - mode, goggles, colours, {@code showInnerComponents}, whether a
     * shader pack is drawing - because every one of them is a configuration the reader can check
     * against their own settings, and "the overlay is off" and "the overlay drew nothing" have the
     * same frame summary without them.
     *
     * @param emitted the quads and the per-half reasons this frame's hosts were refused, if any
     * @param gates the switches the frame was drawn under, as {@code CarrierGeometry.Gates#describe}
     * @param hostDetail one worked host, or {@code null} when every collected host emitted something
     */
    public static void geometry(ResourceKey<Level> dimension, CarrierGeometry.Emitted emitted,
                                String gates, @Nullable String hostDetail) {
        geometry(dimension, emitted, gates, hostDetail, null);
    }

    /**
     * The same, with the matrix probe this frame took.
     *
     * <p>The probe is the only part of the frame report that is made of the matrices handed to OpenGL
     * rather than of the arithmetic that decided to draw - see {@code CarrierMatrixProbe}. It is
     * appended to the host detail, so one line in the log carries the whole answer.
     */
    public static void geometry(ResourceKey<Level> dimension, CarrierGeometry.Emitted emitted,
                                String gates, @Nullable String hostDetail,
                                @Nullable String matrixProbeLine) {
        String detail = hostDetail;
        if (matrixProbeLine != null) {
            detail = (detail == null ? "" : detail + "  |  ") + matrixProbeLine;
        }
        CarrierTrace.render(dimension).setGeometry(emitted.shellQuads(), emitted.contentQuads(),
                emitted.hostsDrawn(), emitted.hostsCollected(),
                detail == null ? null : detail + "  " + emitted.describe());
    }

    /**
     * <b>Records which render stage each half of the frame was flushed at.</b>
     *
     * <p>The companion to {@link #geometry}: that one says how many quads were written, this one says
     * when they reached the frame. Both are needed because a vertex written into a buffer that is ended
     * before a contraption's own blocks are drawn is covered by them, and every count in this class
     * reads exactly like a correct frame when that happens.
     *
     * <p>Called from the carried stage - the later of the two - with the line the renderer built from
     * both stages' own numbers, so the summary and the drawing cannot disagree.
     */
    public static void flushStages(ResourceKey<Level> dimension, String flushStages) {
        CarrierTrace.render(dimension).setFlushStages(flushStages);
    }

    /**
     * Records the gates this frame <em>will be</em> drawn under, before anything is collected.
     *
     * <p>Called from the top of the render pass, so the frame summary written during collection names
     * this frame's configuration rather than the previous one's. {@code CarrierGeometry.Gates} is the
     * single place those switches are resolved, and this is the line that puts them in the log.
     */
    public static void gates(ResourceKey<Level> dimension, String gates) {
        CarrierTrace.render(dimension).setGates(gates);
    }

    /**
     * <b>Logs the matrix probe line</b>, when the matrix has moved and at most once per 20 ticks.
     *
     * <p>Separate from the summary because it is a much longer line and a much rarer need: it answers
     * "is the matrix the renderer uses the same map the pose describes", which is a question an operator
     * asks once per investigation, not once a second. P1.24 measured 2,289 of these and 2.8 MB - the
     * change test alone was not enough, because a turning machine moves the matrix on every frame, so the
     * line was written on every frame. It is now throttled as well.
     */
    public static boolean matrixProbe(ResourceKey<Level> dimension, @Nullable String line) {
        return matrixProbe(dimension, line, -1L);
    }

    /**
     * The same, with the client's own clock; see {@link #frameSummary(ResourceKey, int, int, int, int, int,
     * int, int, long, long)} for what {@code nowTicks} means.
     *
     * <p>A suppressed frame deliberately does <b>not</b> remember its line: the next frame at or after the
     * throttle boundary still differs from {@link #lastMatrixProbeLine}, so the moving matrix is reported
     * once per second rather than never.
     */
    public static boolean matrixProbe(ResourceKey<Level> dimension, @Nullable String line, long nowTicks) {
        if (line == null || line.equals(lastMatrixProbeLine)) {
            // The same matrix as last time: nothing has moved, so the line would say the same thing.
            return false;
        }
        boolean verbose = nowTicks < 0 || RCConfig.verboseRenderLog();
        if (!verbose && matrixProbeEverWritten && nowTicks - lastMatrixProbeTick
                < QUIET_LOG_INTERVAL_TICKS) {
            return false;
        }
        lastMatrixProbeLine = line;
        matrixProbeEverWritten = true;
        if (nowTicks >= 0) {
            lastMatrixProbeTick = nowTicks;
        }
        RCConfig.LOGGER.info("[redstonecircuit] client: {}", line);
        return true;
    }

    /** The last matrix probe line written, so an unchanged one is not repeated. */
    @Nullable
    private static String lastMatrixProbeLine;

    /**
     * Records the worked B2 example for this frame, so the line that is written when the window opens
     * describes a frame that really ran.
     *
     * <p>Recorded every frame from the collection that produced it; the writing is throttled by
     * {@link CarrierSightLog}. Nothing here is read by a judgement: the line is a string that already
     * exists, and storing it costs one reference.
     */
    public static void sightDetail(ResourceKey<Level> dimension, @Nullable String line) {
        CarrierTrace.Render render = CarrierTrace.render(dimension);
        render.setSight(render.sight(), line);
    }

    /**
     * <b>B2: the sight line, whenever a frame has one and the window is open.</b>
     *
     * <p>Called once per frame from the same place the frame summary is recorded. The line itself was
     * built by the render pass on the frame it describes - this method only decides whether to write it,
     * which is what keeps the per-frame cost of B2 at one tick comparison and one string comparison.
     *
     * @return true when a line was written
     */
    public static boolean sight(ResourceKey<Level> dimension, long nowTicks) {
        return CarrierSightLog.writeSight(CarrierTrace.render(dimension).sightDetail(),
                CarrierSightLog.sightDue(nowTicks));
    }

    /**
     * <b>B1: the origin-walk sweep, and the one line that settles "it stopped being drawn seven chunks
     * from where I placed it".</b>
     *
     * <p>The question {@code CarrierIndex} answers for the renderer is measured from the section of each
     * host's <b>origin</b> - the world block the structure took it from. A structure that has travelled
     * further than the walk's radius from where its blocks were placed therefore has no host offered to
     * the renderer at all, while its pose is right beside the player; nothing on screen distinguishes
     * that from "the machine is not there", and until this sweep existed nothing in the log did either.
     *
     * <p>Called on every frame and does nothing on almost all of them: the gate is tested first
     * ({@link CarrierSightLog#sweepDue}) and the sweep is only built when it says yes. See
     * {@link CarrierSightLog} for the bound on each line and the per-frame cost.
     *
     * @param sectionRadius the walk's section radius, as the renderer uses it
     * @param shift the point the walk's radius is measured from, or {@code null} when there is no camera
     */
    public static void originSweep(ResourceKey<Level> dimension, long nowTicks, int sectionRadius,
                                   @Nullable BlockPos shift) {
        // THE CHEAP HALF, on every frame: one carrier list and one comparison against the last sweep's
        // numbers. No per-host walk, no record, no formatting until the gate says a line is due.
        int carriers = ClientHostCache.carriers().size();
        if (!CarrierSightLog.sweepDue(nowTicks, lastCarrierSweep, carriers)) {
            return;
        }
        ClientHostCache.CarrierSweep sweep =
                ClientHostCache.carrierSweep(dimension, shift, sectionRadius, shift);
        lastCarrierSweep = sweep;
        CarrierTrace.Render render = CarrierTrace.render(dimension);
        CarrierSightLog.writeSweep(dimension.location().toString(), sweep, render.skippedStalePose(),
                render.skippedFar(), nowTicks, shift, CarrierSightLog.SWEEP_HEARTBEAT_TICKS);
    }

    /**
     * <b>The frame summary for a frame the pass did not draw at all</b>, with the reason in words.
     *
     * <h2>Why this exists: the diagnostic went silent exactly when it was needed</h2>
     * The summary used to be written only when the host overlay pass was active. That is defensible for a
     * picture - nothing is drawn, nothing is said - and fatal for a diagnostic, because <b>"the overlay
     * is switched off" and "the overlay is on and nothing reaches the screen" then produce the same log:
     * nothing at all.</b> The author's session of 2026-09-28 is exactly that shape: the machine was
     * claimed at 08:13:49.9, the client held it (`carriedIndex=1`), and the log carries <b>no frame
     * summary for the twelve seconds</b> that follow - so the log cannot say whether the pass was off or
     * the renderer failed. This is the same lesson as §13 in the project's own notes: a diagnostic that
     * only speaks when it has something interesting to say cannot report its own absence.
     *
     * <p>So this is written from the same place, on the same clock, through the same throttle, with a
     * zeroed frame and the gates' own sentence - which names the mode, whether the goggles are worn, and
     * which of them closed the pass (see {@code CarrierGeometry.Gates#inactiveReason}).
     *
     * @param gatesSentence the gates, as {@code Gates#describe()}, including the reason when it is closed
     * @return true when a line was written
     */
    public static boolean frameSummaryInactive(ResourceKey<Level> dimension, String gatesSentence,
                                               long nowTicks) {
        checkConsistency(dimension);
        // The same gate the live summary uses, and the same memory: a switched-off overlay reports itself
        // once (the first frame of the session always does) and then once per heartbeat, so it is neither
        // silent nor a line per frame.
        boolean due = CarrierSightThrottle.frameDue(!summaryEverWritten, false, false, nowTicks,
                lastSummaryTick, QUIET_LOG_INTERVAL_TICKS, SETTLED_HEARTBEAT_TICKS);
        if (!due) {
            return false;
        }
        CarrierTrace.Render render = CarrierTrace.render(dimension);
        render.setKnown(ClientHostCache.carrierCount(), ClientHostCache.carriedSize());
        // Zeros, because nothing was collected: the pass never ran, so any number here would be a guess
        // about a frame that did not happen. `carriers=`/`carriedHosts=` still say what the client holds,
        // which is what makes "the data is here and nothing draws it" readable in one line.
        render.setFrame(0, 0, 0, 0);
        render.setClientHeld(ClientHostCache.staleCarrierCount(), ClientHostCache.hostsWithoutPose(),
                ClientHostCache.fallbackCount());
        render.setLanded(ClientHostCache.landedCount(), 0, 0, 0);
        render.setDrawCheck(0, 0);
        render.setPoseAge(-1L);
        render.setGates(gatesSentence == null ? "-" : gatesSentence);
        markSummaryWritten(nowTicks);
        log(dimension, "frame NOT drawn - the host overlay pass is not active ("
                + (gatesSentence == null ? "no gates" : gatesSentence) + "):");
        return true;
    }

    /**
     * The last B1 sweep's numbers, kept only so the gate can tell whether anything moved.
     *
     * <p>A whole sweep rather than five fields: it is one small record per window, and reusing the type
     * keeps the gate and the writer reading the same numbers from the same place - the alternative, five
     * parallel {@code static int}s, is exactly how a gate and its line come to disagree.
     */
    @Nullable
    private static ClientHostCache.CarrierSweep lastCarrierSweep;

    /**
     * The frame summary: <b>on any state change, and otherwise at most once a second.</b>
     *
     * <p>See {@link #FRAME_LOG_INTERVAL} for the rule and why it is what it is. A frame whose counters
     * are identical to the previous summary's is written only when the throttle window has expired
     * <em>and</em> there is something to say; a stable, empty picture goes completely quiet.
     *
     * @return true when a line was written, so a caller can tell the throttle from the silence
     */
    public static boolean frameSummary(ResourceKey<Level> dimension, int drawn, int skippedNoPose,
                                       int skippedFar, int skippedStalePose, int landedWorldHost,
                                       int landedDeferred, int carriedWorldHost) {
        return frameSummary(dimension, drawn, skippedNoPose, skippedFar, skippedStalePose,
                landedWorldHost, landedDeferred, carriedWorldHost, -1L);
    }

    /**
     * The frame summary: <b>on any state change, and otherwise at most once a second.</b>
     *
     * <p>See {@link #FRAME_LOG_INTERVAL} for the rule and why it is what it is. A frame whose counters
     * are identical to the previous summary's is written only when the throttle window has expired
     * <em>and</em> there is something to say; a stable, empty picture goes completely quiet.
     *
     * @param worstPoseAgeTicks the age of the oldest pose this frame drew from, or {@code -1}
     * @return true when a line was written, so a caller can tell the throttle from the silence
     */
    public static boolean frameSummary(ResourceKey<Level> dimension, int drawn, int skippedNoPose,
                                       int skippedFar, int skippedStalePose, int landedWorldHost,
                                       int landedDeferred, int carriedWorldHost,
                                       long worstPoseAgeTicks) {
        return frameSummary(dimension, drawn, skippedNoPose, skippedFar, skippedStalePose,
                landedWorldHost, landedDeferred, carriedWorldHost, worstPoseAgeTicks, -1L);
    }

    /**
     * The same, with the client's own clock: <b>the quiet rule, measured in ticks</b>.
     *
     * <p>This is the form the render pass calls. {@code nowTicks} is the client level's game time, used
     * only to space the lines out - see {@link #QUIET_LOG_INTERVAL_TICKS}. A negative value means "no clock"
     * and falls back to the frame-based throttle this class used before, which is what the overload above
     * (and every test) gets.
     *
     * <p><b>Nothing about what is drawn changes here.</b> Every counter, gate, stage and vertex is recorded
     * by the same calls on the same frames; the only decision in this method is whether to write the line
     * that describes them.
     */
    public static boolean frameSummary(ResourceKey<Level> dimension, int drawn, int skippedNoPose,
                                       int skippedFar, int skippedStalePose, int landedWorldHost,
                                       int landedDeferred, int carriedWorldHost,
                                       long worstPoseAgeTicks, long nowTicks) {
        checkConsistency(dimension);
        CarrierTrace.Render render = CarrierTrace.render(dimension);
        int shellQuads = render.shellQuads();
        int contentQuads = render.contentQuads();
        int hostsDrawn = render.hostsDrawn();
        int landed = ClientHostCache.landedCount();
        // A pose that has gone stale is a state change, so it must force a line immediately rather than
        // waiting for the throttle window: this is the transition the author's log could not show.
        boolean poseWentStale = poseBecameStale(worstPoseAgeTicks);
        // AND SO IS "GEOMETRY STOPPED EXISTING", on the same footing as the pose. The author's report
        // was a machine that turned and a component that did not appear; every counter in this class
        // said the frame was fine because every counter counted *hosts*. A frame whose emitted geometry
        // changes - and in particular one that drops to zero while hosts are still being collected -
        // is the state change that has to be printed at once rather than at the next throttle boundary.
        boolean geometryChanged = shellQuads != lastShellQuads || contentQuads != lastContentQuads;
        boolean changed = drawn != lastDrawn || skippedNoPose != lastSkippedNoPose
                || skippedFar != lastSkippedFar || skippedStalePose != lastSkippedStale
                || landed != lastLanded || landedWorldHost != lastLandedWorldHost
                || landedDeferred != lastLandedDeferred || poseWentStale || geometryChanged;
        boolean empty = drawn == 0 && skippedNoPose == 0 && skippedFar == 0 && skippedStalePose == 0
                && ClientHostCache.carriedSize() == 0 && ClientHostCache.fallbackCount() == 0
                && landed == 0;

        // WHICH RULE APPLIES. The verbose mode (or a caller with no clock) keeps the frame-counted
        // throttle this class was written with, unchanged; the default quiet mode spaces the lines out by
        // ticks and only speaks when there is something to say or the heartbeat is due.
        //
        // The quiet rule is `CarrierSightThrottle.frameDue`, a pure function of the clock - so the
        // cadence is asserted as a boolean sequence in a test rather than as a count of the lines a loop
        // happened to produce. The previous round counted lines and every expectation was off by one.
        boolean verbose = nowTicks < 0 || RCConfig.verboseRenderLog();
        boolean due;
        if (verbose) {
            due = changed || --frameLogCountdown <= 0;
        } else {
            // AN ALARM IS NEVER THROTTLED OUT. A pose that has just gone stale, and a landed host the world
            // path could not draw yet, are findings rather than cadence - the two things the frame summary
            // exists to make visible.
            boolean alarm = poseWentStale || landedDeferred > 0;
            due = CarrierSightThrottle.frameDue(!summaryEverWritten, alarm, changed, nowTicks,
                    lastSummaryTick, QUIET_LOG_INTERVAL_TICKS, SETTLED_HEARTBEAT_TICKS);
        }
        if (!due) {
            return false;
        }
        if (empty) {
            // Nothing carried, nothing landed, nothing skipped: an empty world. The *transition* into
            // that state is worth a line, and so is the heartbeat - see the block below.
            boolean settled = lastDrawn == 0 && lastLanded == 0;
            lastDrawn = 0;
            lastSkippedNoPose = 0;
            lastSkippedFar = 0;
            lastSkippedStale = 0;
            lastLanded = 0;
            lastLandedWorldHost = 0;
            lastLandedDeferred = 0;
            lastShellQuads = 0;
            lastContentQuads = 0;
            // THE HEARTBEAT, AND WHY IT IS NOT OPTIONAL.
            //
            // This used to return silently once the world had settled, so a session in which the pass
            // never ran at all produced a log with no frame summary in it - which is exactly what
            // happened when dispatch was keyed on a stage name that never matched, and it made the
            // failure look like "the diagnostic is fine, there is just nothing to draw".
            // A diagnostic that only speaks when it has something interesting to say cannot report its
            // own absence. So the line is still written - once per SETTLED_HEARTBEAT_TICKS in the quiet
            // mode (it was once per FRAME_LOG_INTERVAL frames, which is where 26,668 of the author's
            // lines and 32 MB of the 40 MB came from), and once per window in the verbose one.
            frameLogCountdown = FRAME_LOG_INTERVAL;
            markSummaryWritten(nowTicks);
            log(dimension, (settled ? "frame drew 0, everything settled" : "frame settled") + ":");
            return true;
        }
        lastDrawn = drawn;
        lastSkippedNoPose = skippedNoPose;
        lastSkippedFar = skippedFar;
        lastSkippedStale = skippedStalePose;
        lastLanded = landed;
        lastLandedWorldHost = landedWorldHost;
        lastLandedDeferred = landedDeferred;
        lastShellQuads = shellQuads;
        lastContentQuads = contentQuads;
        frameLogCountdown = FRAME_LOG_INTERVAL;
        markSummaryWritten(nowTicks);
        // THE ROW IS BUILT FROM ITS KEYS, not from a format string: see {@link CarrierFrameRow}, which
        // holds the default row's twelve keys and the verbose shape's extras, and which refuses to write
        // the same key twice. The verbose switch is read here, at the one call site.
        log(dimension, CarrierFrameRow.render(
                CarrierFrameRow.Frame.of(render, drawn, skippedNoPose, skippedFar, skippedStalePose,
                        worstPoseAgeTicks),
                RCConfig.verboseRenderLog()) + ":");
        return true;
    }

    /**
     * <b>The frame summary's sentence, in one of its two shapes, from a stated switch.</b>
     *
     * <p>The shapes are two calls of one pure function rather than two runs of the renderer: what a
     * test asserts is the sentence itself, not a run that happened to produce it. The production path
     * reaches the same builder through {@link #frameSummary}, which passes
     * {@code RCConfig.verboseRenderLog()}.
     *
     * <p>Package-private on purpose: no caller outside this class in production, and the test in this
     * package is the reason it is not private.
     *
     * @param verbose whether to append the per-frame detail as well; see {@link CarrierFrameRow}
     */
    static String frameSummaryReason(int drawn, int skippedNoPose, int skippedFar, int skippedStalePose,
                                     int landed, int landedWorldHost, int landedDeferred,
                                     int carriedWorldHost, long worstPoseAgeTicks, int shellQuads,
                                     int contentQuads, int hostsDrawn, CarrierTrace.Render render,
                                     boolean verbose) {
        // The five parameters the row no longer reads from the render record are still accepted, because
        // this is the signature the previous round's tests call and because a caller that has them to
        // hand should not have to reach back into the record for the same numbers. They are the landed
        // bookkeeping and the draw-check pair, all of which the row writes from `render` - the one place
        // the frame's own record lives - so the row and `/rc carrier` cannot spell them two ways.
        return CarrierFrameRow.render(
                CarrierFrameRow.Frame.of(render, drawn, skippedNoPose, skippedFar, skippedStalePose,
                        worstPoseAgeTicks),
                verbose);
    }

    /** Records that a line was written, so the quiet throttle can space the next one out. */
    private static void markSummaryWritten(long nowTicks) {
        summaryEverWritten = true;
        if (nowTicks >= 0) {
            lastSummaryTick = nowTicks;
        }
    }

    /**
     * The value of the {@code poseAge} field, without the key.
     *
     * <p>One spelling of "how old is the pose behind this frame" for both the frame row and the draw
     * check, so the two lines cannot describe the same pose two ways. It carries the stale warning,
     * which is the whole reason the age is printed at all: {@code poseAge=80t/4.00s *** STALE POSE … ***}
     * is a frame that was drawn from a four-second-old pose, and every other counter on the line reads
     * exactly like a healthy one.
     */
    static String poseAgePhrase(long age) {
        if (age < 0) {
            return "?(this frame drew nothing, or the caller does not track it)";
        }
        double seconds = age / 20.0;
        if (age > CarrierDrawCheck.POSE_AGE_WARN_TICKS) {
            return age + "t/" + String.format(java.util.Locale.ROOT, "%.2f", seconds)
                    + "s *** STALE POSE: the client is drawing the structure where it was "
                    + age + " tick(s) ago, not where it is. delta=0 does not mean it is following ***";
        }
        return age + "t/" + String.format(java.util.Locale.ROOT, "%.2f", seconds) + "s";
    }

    /**
     * The "we drew it, but where" line, on the same throttle as the frame summary.
     *
     * <p>This is the client half of {@code CarrierDrawCheck}: the frame summary can only say how many
     * hosts were emitted, and a host emitted at a plot coordinate from the previous session's pose is
     * counted by it as a good draw. Here the two positions are printed side by side, which is what makes
     * "看不见但计数是 1" readable instead of mysterious.
     *
     * <p>Written on a change of either count, and otherwise at most once a second while something is
     * carried - a machine that sits still with a correct picture goes quiet, and a machine whose picture
     * is wrong says so every second without filling the log.
     *
     * @param example one worked host from this frame, or {@code null} when nothing was carried
     */
    public static boolean drawCheck(ResourceKey<Level> dimension,
                                    @Nullable com.jiangyuefengyu.redstonecircuit.carrier.CarrierDrawCheck example,
                                    int drawnOffStructure, int plotSpaceHosts) {
        return drawCheck(dimension, example, drawnOffStructure, plotSpaceHosts, -1L, -1L);
    }

    /**
     * The same, with the age of the oldest pose the frame drew from.
     *
     * <p>The age is forced into the log line and the throttle treats "the pose went stale" as a change,
     * so the one line that used to read {@code delta=0.000 OK} about a four-second-old pose now reads
     * {@code poseAge=80t/4.00s *** STALE POSE … ***}.
     */
    public static boolean drawCheck(ResourceKey<Level> dimension,
                                    @Nullable com.jiangyuefengyu.redstonecircuit.carrier.CarrierDrawCheck example,
                                    int drawnOffStructure, int plotSpaceHosts, long worstPoseAgeTicks) {
        return drawCheck(dimension, example, drawnOffStructure, plotSpaceHosts, worstPoseAgeTicks, -1L);
    }

    /**
     * The same, with the client's own clock: <b>on a change, and at most once per 20 ticks.</b>
     *
     * <p>P1.24 measured 1,317 of these and 0.78 MB. The change test was already there, but on a moving
     * machine the two counts it watches move with the machine, so "a change" was every frame; the throttle
     * that was supposed to bound it was the frame-counted {@link #FRAME_LOG_INTERVAL}, which at frame rates
     * is not a second at all. Spacing it in ticks is what makes it the "once a second while something is
     * wrong" line it was described as.
     *
     * <p>A pose that has just gone stale prints immediately whatever the throttle says, because that is the
     * one transition this line exists for.
     */
    public static boolean drawCheck(ResourceKey<Level> dimension,
                                    @Nullable com.jiangyuefengyu.redstonecircuit.carrier.CarrierDrawCheck example,
                                    int drawnOffStructure, int plotSpaceHosts, long worstPoseAgeTicks,
                                    long nowTicks) {
        boolean poseWentStale = poseBecameStale(worstPoseAgeTicks);
        boolean changed = drawnOffStructure != lastDrawnOffStructure
                || plotSpaceHosts != lastPlotSpaceHosts || poseWentStale;
        boolean verbose = nowTicks < 0 || RCConfig.verboseRenderLog();
        boolean due;
        if (verbose) {
            due = changed || --drawCheckCountdown <= 0;
        } else if (!drawCheckEverWritten) {
            due = true;
        } else if (poseWentStale) {
            due = true;
        } else {
            due = changed && nowTicks - lastDrawCheckTick >= QUIET_LOG_INTERVAL_TICKS;
        }
        if (!due) {
            return false;
        }
        if (example == null && drawnOffStructure == 0 && plotSpaceHosts == 0) {
            // Nothing carried: quiet, but remember the counts so the next frame that carries something
            // counts as a change rather than being throttled away.
            lastDrawnOffStructure = 0;
            lastPlotSpaceHosts = 0;
            drawCheckCountdown = FRAME_LOG_INTERVAL;
            return false;
        }
        lastDrawnOffStructure = drawnOffStructure;
        lastPlotSpaceHosts = plotSpaceHosts;
        drawCheckCountdown = FRAME_LOG_INTERVAL;
        drawCheckEverWritten = true;
        if (nowTicks >= 0) {
            lastDrawCheckTick = nowTicks;
        }
        RCConfig.LOGGER.info("[redstonecircuit] client: drawCheck {} {}",
                example == null
                        ? "offStructure=" + drawnOffStructure + " plotSpace=" + plotSpaceHosts
                                + " (no drawable host in range to name)"
                        : example.describe(),
                "poseAge=" + poseAgePhrase(worstPoseAgeTicks));
        return true;
    }

    /**
     * Records whether this frame drew from a stale pose, and answers whether that is a <b>transition</b>.
     *
     * <p>An age of {@code -1} means the frame drew nothing or the caller does not track ages; it is not
     * a change and does not overwrite what is remembered, so a stale picture does not look like it
     * became stale again on every empty frame.
     */
    private static boolean poseBecameStale(long worstPoseAgeTicks) {
        if (worstPoseAgeTicks < 0) {
            return false;
        }
        boolean stale = worstPoseAgeTicks > CarrierDrawCheck.POSE_AGE_WARN_TICKS;
        boolean transition = stale && !lastPoseWasStale;
        lastPoseWasStale = stale;
        return transition;
    }

    /**
     * The two "the client and the server disagree" shapes, each warned about once per dimension.
     *
     * <p>Deliberately once and not every frame: both describe a state rather than an event, and a
     * warning that repeats is a warning that gets filtered out of the log by the person reading it.
     */
    private static void checkConsistency(ResourceKey<Level> dimension) {
        CarrierTrace.Render render = CarrierTrace.render(dimension);
        int server = render.serverCarriers();
        int client = ClientHostCache.carrierCount();
        if (server > 0 && client > server && WARNED_MISMATCH.add(dimension)) {
            RCConfig.LOGGER.warn("[redstonecircuit] client holds {} carrier(s) but the server last "
                            + "reported {} - a removal was not announced; the pose-staleness sweep "
                            + "will let the extra one(s) go within {} tick(s). Carriers: {}",
                    client, server, ClientHostCache.staleTicks(), describeCarriers());
        }
        int orphans = ClientHostCache.hostsWithoutPose();
        if (orphans > 0 && WARNED_ORPHAN.add(dimension)) {
            RCConfig.LOGGER.warn("[redstonecircuit] {} carried host(s) point at a carrier the client "
                            + "has no pose for: they cannot be drawn inside a structure and are "
                            + "skipped for want of a pose. Carriers: {}", orphans, describeCarriers());
        }
    }

    /** How many carriers the client holds, by identity and age, for the warnings and the log. */
    private static String describeCarriers() {
        return ClientHostCache.describeCarrierAges();
    }

    /** Drops the per-dimension warning bookkeeping; called when a level unloads. */
    public static void clear(@Nullable ResourceKey<Level> dimension) {
        frameLogCountdown = 0;
        drawCheckCountdown = 0;
        forgetLastFrame();
        // The B1 sweep's own memory is deliberately NOT reset here: it is process-lifetime like the rest
        // of the throttle, and clearing it on a level unload would make the first frame of every new
        // dimension write a line whether anything had changed or not.
        if (dimension != null) {
            WARNED_MISMATCH.remove(dimension);
            WARNED_ORPHAN.remove(dimension);
        }
    }

    /** Drops everything; for a full client reset. */
    public static void clearAll() {
        frameLogCountdown = 0;
        drawCheckCountdown = 0;
        forgetLastFrame();
        CarrierSightLog.resetForTest();
        lastCarrierSweep = null;
        WARNED_MISMATCH.clear();
        WARNED_ORPHAN.clear();
    }

    /**
     * Forgets the log throttle's own memory, so one test cannot suppress the next one's lines.
     *
     * <p>Exists because the throttle is process-lifetime by design (that is what makes it a throttle), so
     * the only honest way to test it is to be able to state its starting condition.
     */
    static void resetLogThrottleForTest() {
        forgetLastFrame();
        CarrierSightLog.resetForTest();
        lastCarrierSweep = null;
    }

    /**
     * Forgets the previous frame's counters, so the next frame counts as a change.
     *
     * <p>Without this a summary could be suppressed by a window that the <em>previous</em> world
     * started: the client joins a new level, the counters are reset by the caller but the throttle is
     * not, and the first frames of the new level look "unchanged".
     *
     * <p>The three clocks and their "ever written" flags go with them, for the same reason: the first
     * frame of the new level must report itself, however recently the last level's frames did.
     */
    private static void forgetLastFrame() {
        lastDrawn = Integer.MIN_VALUE;
        lastSkippedNoPose = Integer.MIN_VALUE;
        lastSkippedFar = Integer.MIN_VALUE;
        lastSkippedStale = Integer.MIN_VALUE;
        lastLanded = Integer.MIN_VALUE;
        lastLandedWorldHost = Integer.MIN_VALUE;
        lastLandedDeferred = Integer.MIN_VALUE;
        lastDrawnOffStructure = Integer.MIN_VALUE;
        lastPlotSpaceHosts = Integer.MIN_VALUE;
        lastShellQuads = Integer.MIN_VALUE;
        lastContentQuads = Integer.MIN_VALUE;
        lastMatrixProbeLine = null;
        lastPoseWasStale = false;
        drawCheckCountdown = 0;
        lastSummaryTick = Long.MIN_VALUE;
        lastDrawCheckTick = Long.MIN_VALUE;
        lastMatrixProbeTick = Long.MIN_VALUE;
        summaryEverWritten = false;
        drawCheckEverWritten = false;
        matrixProbeEverWritten = false;
    }
}

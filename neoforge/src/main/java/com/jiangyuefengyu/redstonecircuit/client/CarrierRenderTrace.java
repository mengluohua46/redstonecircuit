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
 * <b>Stage 3 of the three-stage diagnostic</b>: what the client actually received, and what the render pass did with it.
 * The counters are written into {@link CarrierTrace}'s render record for the current dimension, so one snapshot of
 * {@code /rc carrier} - or of the {@code client: carrier ...} log line - carries the whole stage.
 *
 * <p>It separates "the client was never told" ({@code carrierIndex=0}) from "the client was told and drew nothing"
 * ({@code carriedIndex>0, drawnLastFrame=0}, split further by the skip counters), and logs each of the two "the two
 * sides disagree" shapes once per dimension rather than every frame. Nothing here affects rendering.
 */
public final class CarrierRenderTrace {

    /**
     * How often the periodic frame summary is written, in the quiet (default) mode: 100 ticks = 5 seconds, slow enough
     * for a state and fast enough that a player who just moved a machine still sees the answer twice a minute.
     */
    static final int QUIET_LOG_INTERVAL_TICKS = 100;

    /**
     * How often the "nothing is happening" heartbeat is written, in ticks: two minutes, enough that a log always shows
     * the pass was running and rare enough that a long session's log stays under a megabyte.
     */
    static final int SETTLED_HEARTBEAT_TICKS = 2400;

    /**
     * How often the periodic frame summary is written, in frames, in the verbose mode: a ceiling on the idle rate rather
     * than a fixed cadence. The counters changing writes a line immediately and restarts the throttle window.
     */
    static final int FRAME_LOG_INTERVAL = 20;

    /** Frames since the last summary; only ever touched on the render thread. */
    private static int frameLogCountdown;

    /**
     * The previous frame's counters, so "did anything change" can be answered. Reset with the throttle, so the first
     * frame after a quiet period always reports itself.
     */
    private static int lastDrawn = Integer.MIN_VALUE;
    private static int lastSkippedNoPose = Integer.MIN_VALUE;
    private static int lastSkippedFar = Integer.MIN_VALUE;
    private static int lastSkippedStale = Integer.MIN_VALUE;
    private static int lastLanded = Integer.MIN_VALUE;
    private static int lastLandedWorldHost = Integer.MIN_VALUE;

    /** The previous frame's "yielded but the world path could not draw" count; a frame that starts reporting it is a change. */
    private static int lastLandedDeferred = Integer.MIN_VALUE;

    /** The previous frame's draw-check counts; see {@link #drawCheck}. */
    private static int lastDrawnOffStructure = Integer.MIN_VALUE;
    private static int lastPlotSpaceHosts = Integer.MIN_VALUE;

    /**
     * The previous frame's emitted geometry, so "it stopped emitting" is a state change: a summary that only noticed
     * changes in the host counts would throttle the interesting frame away.
     */
    private static int lastShellQuads = Integer.MIN_VALUE;
    private static int lastContentQuads = Integer.MIN_VALUE;

    /**
     * Whether the last frame that reported an age drew from a stale pose. A boolean rather than the age, because the
     * transition is what forces a line, and an age of {@code -1} ("this frame drew nothing") is not a transition.
     */
    private static boolean lastPoseWasStale;

    /** Frames since the last draw-check line; the verbose mode's throttle. See {@link #drawCheck}. */
    private static int drawCheckCountdown;

    /**
     * When each of the three client lines was last written, in client ticks, and whether it ever was. The quiet mode
     * throttles in ticks rather than frames, so "one line per second" means a second; a tick of {@code < 0} means the
     * caller had no clock to hand, and then the old frame-based throttle is used.
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
     * One rendered frame; {@code drawn} is the number of carried hosts actually emitted, not the number that exist.
     * A nonzero {@code landedDeferred} means the two yield gates have drifted apart and should never be seen.
     */
    public static void frame(ResourceKey<Level> dimension, int drawn, int skippedNoPose,
                             int skippedFar, int skippedStalePose, int landedWorldHost,
                             int landedDeferred, int carriedWorldHost) {
        frame(dimension, drawn, skippedNoPose, skippedFar, skippedStalePose, landedWorldHost,
                landedDeferred, carriedWorldHost, 0, 0, -1L);
    }

    /**
     * The same, with the draw check's two counts: it separates "nothing was drawn" from "something was drawn somewhere
     * else". {@code drawnOffStructure} is expected to be zero, and nonzero names the "drawn but invisible" defect.
     */
    public static void frame(ResourceKey<Level> dimension, int drawn, int skippedNoPose,
                             int skippedFar, int skippedStalePose, int landedWorldHost,
                             int landedDeferred, int carriedWorldHost, int drawnOffStructure,
                             int plotSpaceHosts) {
        frame(dimension, drawn, skippedNoPose, skippedFar, skippedStalePose, landedWorldHost,
                landedDeferred, carriedWorldHost, drawnOffStructure, plotSpaceHosts, -1L);
    }

    /**
     * The same, with the age of the oldest pose this frame drew from; {@code -1} when the frame drew nothing or the
     * caller does not track it. "Not following the structure" and "following a four-second-old pose" look alike.
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
     * The same, with the sight half of the frame: the five counters that separate "this host was never asked" from "it
     * was asked and the ray refused it". The worked B2 line is not set here - the caller records it a moment later.
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
     * One line, logged when the carrier picture changes and periodically while it is being drawn: the failing case is a
     * machine that moves with nothing drawn, which nobody thinks to type a command during.
     */
    public static void log(ResourceKey<Level> dimension, String reason) {
        RCConfig.LOGGER.info("[redstonecircuit] client: carrier {} {} carriers={} carriedHosts={}"
                        + " landedHosts={} fallingBack={} held=[{}]",
                reason, CarrierTrace.render(dimension).describe(), ClientHostCache.carrierCount(),
                ClientHostCache.carriedSize(), ClientHostCache.landedCount(),
                ClientHostCache.fallbackCount(), ClientHostCache.describeCarrierAges());
    }

    /**
     * The geometry half of the frame: what was actually written into the vertex buffers, with the gates it was drawn
     * under. {@code frame()} records how many hosts were handled, this records how much geometry came out.
     */
    public static void geometry(ResourceKey<Level> dimension, CarrierGeometry.Emitted emitted,
                                String gates, @Nullable String hostDetail) {
        geometry(dimension, emitted, gates, hostDetail, null);
    }

    /**
     * The same, with the matrix probe this frame took - the only part of the report made of the matrices handed to
     * OpenGL rather than of the arithmetic that decided to draw. Appended to the host detail, so one line carries both.
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
     * Records which render stage each half of the frame was flushed at: {@link #geometry} says how many quads were
     * written, this says when they reached the frame. Both are needed, because a vertex written into a buffer that is
     * ended before a contraption's own blocks are drawn is covered by them, and then every count here reads as correct.
     */
    public static void flushStages(ResourceKey<Level> dimension, String flushStages) {
        CarrierTrace.render(dimension).setFlushStages(flushStages);
    }

    /**
     * Records the gates this frame will be drawn under, before anything is collected, so the frame summary names this
     * frame's configuration rather than the previous one's.
     */
    public static void gates(ResourceKey<Level> dimension, String gates) {
        CarrierTrace.render(dimension).setGates(gates);
    }

    /**
     * Logs the matrix probe line, when the matrix has moved and at most once per 20 ticks. It is a long line answering a
     * question an operator asks once per investigation, not once a second, so the change test alone is not enough.
     */
    public static boolean matrixProbe(ResourceKey<Level> dimension, @Nullable String line) {
        return matrixProbe(dimension, line, -1L);
    }

    /**
     * The same, with the client's own clock; see {@link #frameSummary(ResourceKey, int, int, int, int, int, int, int,
     * long, long)} for what {@code nowTicks} means. A suppressed frame does not remember its line, so a moving matrix is
     * reported once per second rather than never.
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
     * Records the worked B2 example for this frame, so the line written when the window opens describes a frame that
     * really ran. Recording costs one reference; the writing is throttled by {@link CarrierSightLog}.
     */
    public static void sightDetail(ResourceKey<Level> dimension, @Nullable String line) {
        CarrierTrace.Render render = CarrierTrace.render(dimension);
        render.setSight(render.sight(), line);
    }

    /**
     * B2: the sight line, whenever a frame has one and the window is open. The line was built by the render pass on the
     * frame it describes; this only decides whether to write it, at one tick and one string comparison per frame.
     */
    public static boolean sight(ResourceKey<Level> dimension, long nowTicks) {
        return CarrierSightLog.writeSight(CarrierTrace.render(dimension).sightDetail(),
                CarrierSightLog.sightDue(nowTicks));
    }

    /**
     * B1: the origin-walk sweep, and the one line that settles "it stopped being drawn seven chunks from where I placed
     * it". The walk is measured from each host's origin, so a structure that has travelled past the radius offers no
     * host to the renderer at all while its pose sits right beside the player.
     */
    public static void originSweep(ResourceKey<Level> dimension, long nowTicks, int sectionRadius,
                                   @Nullable BlockPos shift) {
        // The cheap half, on every frame: one carrier list and one comparison against the last sweep's numbers.
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
     * The frame summary for a frame the pass did not draw at all, with the reason in words: "the overlay is switched
     * off" and "the overlay is on and nothing reaches the screen" must not produce the same log, which is silence.
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

    /** The last B1 sweep's numbers, kept only so the gate can tell whether anything moved. */
    @Nullable
    private static ClientHostCache.CarrierSweep lastCarrierSweep;

    /**
     * The frame summary: on any state change, and otherwise at most once per {@link #QUIET_LOG_INTERVAL_TICKS} in the
     * quiet mode; a stable, empty picture goes completely quiet.
     */
    public static boolean frameSummary(ResourceKey<Level> dimension, int drawn, int skippedNoPose,
                                       int skippedFar, int skippedStalePose, int landedWorldHost,
                                       int landedDeferred, int carriedWorldHost) {
        return frameSummary(dimension, drawn, skippedNoPose, skippedFar, skippedStalePose,
                landedWorldHost, landedDeferred, carriedWorldHost, -1L);
    }

    /**
     * The same, with the age of the oldest pose this frame drew from ({@code -1} when it drew nothing).
     */
    public static boolean frameSummary(ResourceKey<Level> dimension, int drawn, int skippedNoPose,
                                       int skippedFar, int skippedStalePose, int landedWorldHost,
                                       int landedDeferred, int carriedWorldHost,
                                       long worstPoseAgeTicks) {
        return frameSummary(dimension, drawn, skippedNoPose, skippedFar, skippedStalePose,
                landedWorldHost, landedDeferred, carriedWorldHost, worstPoseAgeTicks, -1L);
    }

    /**
     * The same, with the client's own clock: the quiet rule, measured in ticks. A negative {@code nowTicks} means "no
     * clock" and falls back to the frame-based throttle; nothing about what is drawn changes here.
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
        // A pose that has gone stale is a state change, so it forces a line immediately rather than at the throttle. This
        // is the transition the log has to be able to show.
        boolean poseWentStale = poseBecameStale(worstPoseAgeTicks);
        // And so is "geometry stopped existing": every other counter counts hosts, so a frame whose emitted quads change
        // - above all one that drops to zero while hosts are still collected - must be printed at once, not at the next
        // throttle boundary.
        boolean geometryChanged = shellQuads != lastShellQuads || contentQuads != lastContentQuads;
        boolean changed = drawn != lastDrawn || skippedNoPose != lastSkippedNoPose
                || skippedFar != lastSkippedFar || skippedStalePose != lastSkippedStale
                || landed != lastLanded || landedWorldHost != lastLandedWorldHost
                || landedDeferred != lastLandedDeferred || poseWentStale || geometryChanged;
        boolean empty = drawn == 0 && skippedNoPose == 0 && skippedFar == 0 && skippedStalePose == 0
                && ClientHostCache.carriedSize() == 0 && ClientHostCache.fallbackCount() == 0
                && landed == 0;

        // The verbose mode keeps the frame-counted throttle unchanged; the quiet mode spaces the lines out by ticks and
        // speaks only when there is something to say or the heartbeat is due. The quiet rule is
        // `CarrierSightThrottle.frameDue`, a pure function of the clock, so a test asserts the cadence as a boolean
        // sequence rather than counting the lines a loop happened to produce.
        boolean verbose = nowTicks < 0 || RCConfig.verboseRenderLog();
        boolean due;
        if (verbose) {
            due = changed || --frameLogCountdown <= 0;
        } else {
            // An alarm is never throttled out: a pose that has just gone stale and a landed host the world path could not
            // draw yet are findings rather than cadence.
            boolean alarm = poseWentStale || landedDeferred > 0;
            due = CarrierSightThrottle.frameDue(!summaryEverWritten, alarm, changed, nowTicks,
                    lastSummaryTick, QUIET_LOG_INTERVAL_TICKS, SETTLED_HEARTBEAT_TICKS);
        }
        if (!due) {
            return false;
        }
        if (empty) {
            // Nothing carried, nothing landed, nothing skipped: an empty world. The *transition* into that state is worth
            // a line, and so is the heartbeat - see the block below.
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
            // THE HEARTBEAT, AND WHY IT IS NOT OPTIONAL: a diagnostic that only speaks when it has something
            // interesting to say cannot report its own absence, so the line is still written - once per
            // SETTLED_HEARTBEAT_TICKS in the quiet mode, once per window in the verbose one.
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
        // The row is built from its keys rather than from a format string - see {@link CarrierFrameRow} - and the verbose
        // switch is read here, at the one call site.
        log(dimension, CarrierFrameRow.render(
                CarrierFrameRow.Frame.of(render, drawn, skippedNoPose, skippedFar, skippedStalePose,
                        worstPoseAgeTicks),
                RCConfig.verboseRenderLog()) + ":");
        return true;
    }

    /**
     * The frame summary's sentence, in one of its two shapes, from a stated switch: two calls of one pure function,
     * because what a test asserts is the sentence itself, not a run that happened to produce it.
     */
    static String frameSummaryReason(int drawn, int skippedNoPose, int skippedFar, int skippedStalePose,
                                     int landed, int landedWorldHost, int landedDeferred,
                                     int carriedWorldHost, long worstPoseAgeTicks, int shellQuads,
                                     int contentQuads, int hostsDrawn, CarrierTrace.Render render,
                                     boolean verbose) {
        // Five parameters are accepted but unread: the row writes them from `render`, which is the one place the
        // frame's own record lives, so the row and `/rc carrier` cannot spell them two ways.
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
     * The value of the {@code poseAge} field, without the key: one spelling of "how old is the pose behind this frame"
     * for both the frame row and the draw check. It carries the stale warning, which is why the age is printed at all.
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
     * The "we drew it, but where" line, on the same throttle as the frame summary: the two positions are printed side by
     * side, which is what makes a wrong picture readable instead of mysterious. Written on a change of either count.
     */
    public static boolean drawCheck(ResourceKey<Level> dimension,
                                    @Nullable com.jiangyuefengyu.redstonecircuit.carrier.CarrierDrawCheck example,
                                    int drawnOffStructure, int plotSpaceHosts) {
        return drawCheck(dimension, example, drawnOffStructure, plotSpaceHosts, -1L, -1L);
    }

    /**
     * The same, with the age of the oldest pose the frame drew from; the throttle treats "the pose went stale" as a
     * change, so the line cannot read {@code delta=0.000 OK} about a four-second-old pose.
     */
    public static boolean drawCheck(ResourceKey<Level> dimension,
                                    @Nullable com.jiangyuefengyu.redstonecircuit.carrier.CarrierDrawCheck example,
                                    int drawnOffStructure, int plotSpaceHosts, long worstPoseAgeTicks) {
        return drawCheck(dimension, example, drawnOffStructure, plotSpaceHosts, worstPoseAgeTicks, -1L);
    }

    /**
     * The same, with the client's own clock: on a change, and at most once per {@link #QUIET_LOG_INTERVAL_TICKS}. A pose
     * that has just gone stale prints immediately whatever the throttle says, because that is the transition this line
     * exists for.
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
            // Nothing carried: quiet, but remember the counts so the next frame that carries something counts as a change.
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
     * Records whether this frame drew from a stale pose, and answers whether that is a transition. An age of {@code -1}
     * is not a change and does not overwrite what is remembered, so a stale picture does not look stale again on every
     * empty frame.
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
     * The two "the client and the server disagree" shapes, each warned about once per dimension: both describe a state
     * rather than an event, and a warning that repeats is a warning that gets filtered out of the log.
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
        // The B1 sweep's own memory is not reset here: it is process-lifetime like the rest of the throttle, and clearing
        // it would make the first frame of every new dimension write a line whether anything had changed or not.
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
     * Forgets the log throttle's own memory, so one test cannot suppress the next one's lines: the throttle is
     * process-lifetime, so a test has to be able to state its starting condition.
     */
    static void resetLogThrottleForTest() {
        forgetLastFrame();
        CarrierSightLog.resetForTest();
        lastCarrierSweep = null;
    }

    /**
     * Forgets the previous frame's counters, so the next frame counts as a change: otherwise the first frames of a new
     * level could be suppressed by a window the previous one started. The three clocks and their flags go with them.
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

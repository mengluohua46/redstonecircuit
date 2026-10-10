package com.jiangyuefengyu.redstonecircuit.carrier;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * The three-stage counter. One place that answers, per level and at a glance, whether a moving structure's
 * components were (1) claimed, (2) sent to the client and (3) drawn there.
 *
 * <p>The three stages are independently decidable, so "the claim never happened" can be told apart from "the claim
 * happened and the renderer ignored it":
 *
 * <pre>
 *   claim   ->  CarrierStore.startMoving / reconcile      (CarrierStore.Claims)
 *   sync    ->  HostSync.broadcastCarrier / broadcastPose (CarrierStore.Sync)
 *   render  ->  ClientHostCache / HostRenderPass          (CarrierStore.Render)
 * </pre>
 *
 * <p>Every counter here is a plain increment on a path that already exists, so nothing in the feature depends on the
 * trace being read. Only a {@code ResourceKey} is needed for the map, so the counts can be unit-tested without a
 * running game. {@code /rc carrier} prints all three sections.
 */
public final class CarrierTrace {

    /** What the pick-up side did. */
    public static final class Claims {

        /** Hosts claimed under a contraption entity's own UUID. */
        private long entityClaims;
        /** Hosts claimed under a provisional identity, because there was no entity yet. */
        private long provisionalClaims;
        /** Provisional claims that were successfully re-labelled onto an entity. */
        private long reconciled;
        /** Reconciliations that found nothing to move; reported rather than hidden. */
        private long reconcileNoOps;
        /** Reconciliations that failed outright (the index re-key threw). */
        private long reconcileFailures;
        /** Claims dropped because there was nothing inside the block; the ordinary case. */
        private long emptyBlocks;

        /** Every host that actually moved into a carrier, however it was named. */
        public long claimed() {
            return entityClaims + provisionalClaims;
        }

        public long entityClaims() {
            return entityClaims;
        }

        public long provisionalClaims() {
            return provisionalClaims;
        }

        public long reconciled() {
            return reconciled;
        }

        public long reconcileNoOps() {
            return reconcileNoOps;
        }

        public long reconcileFailures() {
            return reconcileFailures;
        }

        public long emptyBlocks() {
            return emptyBlocks;
        }

        /** One line for {@code /rc carrier}: stage 1, "did the claim happen, and under what name". */
        public String describe() {
            return "claim: claimed=" + claimed()
                    + " (entity=" + entityClaims + " provisional=" + provisionalClaims + ")"
                    + " reconciled=" + reconciled
                    + (reconcileNoOps > 0 ? " reconcileNoOp=" + reconcileNoOps : "")
                    + (reconcileFailures > 0 ? " RECONCILE-FAILED=" + reconcileFailures : "")
                    + " emptyBlocks=" + emptyBlocks;
        }
    }

    /** What the server actually put on the wire. */
    public static final class Sync {

        /** Carrier messages sent: pose plus the hosts riding it. */
        private long carriersSent;
        /** Pose-only messages sent while a structure moved. */
        private long posesSent;
        /** Carrier entries sent, live hosts only - what the client is asked to draw. */
        private long hostsSent;
        /** Snapshot messages sent to a single arriving player. */
        private long snapshotsSent;
        /**
         * World-host announcements sent because a carrier put a host down.
         *
         * <p>The other half of the landing transfer: the carrier message says "this host no longer rides me", and this
         * is the message that says "it is a world host at <em>this</em> position now". Without it the client is told
         * where a component stopped riding and never where it went.
         */
        private long restored;
        /**
         * World-host suppressions sent because a carrier picked a host up.
         *
         * <p>The mirror image: "this position is no longer a world host, it is riding a structure now". Without it the
         * client keeps drawing the component at the block the structure took it from.
         */
        private long worldHostSuppressed;

        /**
         * {@code stopMoving} callbacks that did <b>not</b> release, because the structure was still there.
         *
         * <p>The three counters together describe one motion: {@code deferred} is "the structure asked to stand down
         * and it was not believed", {@code cancelled} is "it was indeed still moving" - which is what a direction
         * change looks like - and {@code settled} is "it really had stopped".
         */
        private long releasesDeferred;
        /** Deferred releases that were settled because the structure really had stopped. */
        private long releasesDeferredSettled;
        /** Deferred releases that were cancelled because the structure was still being driven. */
        private long releasesDeferredCancelled;

        public long carriersSent() {
            return carriersSent;
        }

        public long posesSent() {
            return posesSent;
        }

        public long hostsSent() {
            return hostsSent;
        }

        public long snapshotsSent() {
            return snapshotsSent;
        }

        public long restored() {
            return restored;
        }

        public long worldHostSuppressed() {
            return worldHostSuppressed;
        }

        public long releasesDeferred() {
            return releasesDeferred;
        }

        public long releasesDeferredSettled() {
            return releasesDeferredSettled;
        }

        public long releasesDeferredCancelled() {
            return releasesDeferredCancelled;
        }

        /** One line for {@code /rc carrier}: stage 2, "did the server tell anyone". */
        public String describe() {
            return "sync: carriersSent=" + carriersSent + " posesSent=" + posesSent
                    + " hostsSent=" + hostsSent + " snapshotsSent=" + snapshotsSent
                    + " restoredHosts=" + restored + " worldHostSuppressed=" + worldHostSuppressed
                    + " deferredReleases=" + releasesDeferred + "(settled=" + releasesDeferredSettled
                    + " cancelled=" + releasesDeferredCancelled + ")";
        }
    }

    /** What the client did with what it was told. */
    public static final class Render {

        /** Carriers the client's index currently holds a pose for. */
        private int carriersKnown;
        /**
         * Carriers the server believes are in flight, as of the last message it sent.
         *
         * <p>The comparison that makes "the client is holding a carrier the server has forgotten" visible: the client
         * knows how many it was told about, the server knows how many it has, and a client count above the server
         * count is the shape this pair exists to catch. Zero means "the server has not said yet".
         */
        private int serverCarriers;
        /** Carried hosts the client's index currently holds. */
        private int hostsKnown;
        /** Carried hosts the render pass actually put on screen on the last frame. */
        private int drawn;
        /** Carried hosts in range whose carrier had no pose; skipped rather than drawn at the origin. */
        private int skippedNoPose;
        /** Carried hosts in range but past the draw distance from their carrier. */
        private int skippedFar;
        /**
         * Carried hosts skipped because their carrier's pose had gone stale.
         *
         * <p>A host whose carrier was dropped by the server without a removal notice is not "far" and does not have
         * "no pose" - it has an <em>old</em> pose, which nothing counted at all, so "drawn=0 with every skip counter
         * at zero" was possible and silent. It is now named.
         */
        private int skippedStalePose;

        /** Carriers the client is holding but can no longer place; see the client's staleness sweep. */
        private int staleCarriers;
        /** Carried hosts the client cannot draw because it has no pose for their carrier. */
        private int hostsWithoutPose;
        /** Hosts currently drawn on the world path because their carrier was let go of. */
        private int fallingBack;
        /**
         * Hosts the client is keeping as "landed" - put down by a carrier the server has taken back.
         *
         * <p>A host whose carrier is gone and whose landing position may have no block under it: it is drawn at that
         * position rather than dropped, and this counter is the evidence that the record survives the removal.
         */
        private int landedHosts;
        /**
         * Landed hosts the render pass did <b>not</b> draw because a real block is standing there.
         *
         * <p>Not a failure: the ordinary world path draws a host at a position that has a block, and
         * this is the count of landed candidates yielding to it. It is the other half of the answer to
         * "why did the component not come back" - {@code landed>0, landedSkippedBlock=0} means the
         * landed path drew it, {@code landedSkippedBlock=landed} means the world path owns it, and
         * {@code landed=0} means the client never learned that the host belongs anywhere.
         */
        private int landedSkippedBlock;
        /**
         * Landed hosts drawn at their landing position <b>because the world path could not draw there
         * yet</b>: the ordinary host sync already names the position, but the block has not come back.
         *
         * <p>The frame window this defect lived in, now counted instead of silent. The world renderer
         * refuses to draw a shell over air and the carried path had already let the host go, so the
         * component was drawn by neither path; the landed path now draws it, and this is the number
         * that says so. It is <b>not</b> a failure. What would be a failure is a frame that drew
         * nothing with no skip counter to explain it, which is what this count rules out.
         */
        private int landedDeferred;
        /**
         * Hosts whose carrier went stale mid-flight that yielded to the world path at their origin.
         *
         * <p>The same decision as {@link #landedSkippedBlock}, on the other fallback position, kept
         * apart because "put down" and "let go of mid-flight" are different facts about the host.
         */
        private int carriedSkippedBlock;

        /**
         * Carried hosts the frame drew at a point nowhere near their carrier.
         *
         * <p><b>The counter the Sable path needed and did not have.</b> {@code drawnLastFrame} counts
         * emitted geometry and says nothing about where it went; a host drawn at a plot coordinate in
         * the twenty-millions is a perfect draw by every other number here. This counts the hosts whose
         * computed world point sits more than
         * {@link com.jiangyuefengyu.redstonecircuit.carrier.CarrierDrawCheck#OFF_TOLERANCE_BLOCKS} from
         * the structure's own reported position - i.e. drawn, and drawn somewhere else. Expected to be
         * zero: a nonzero value <em>is</em> the "看不见但画了" defect, named.
         */
        private int drawnOffStructure;
        /**
         * Carried hosts whose local coordinates are in a plot-space magnitude range.
         *
         * <p>Not a fault - Sable's {@code logicalPose} is defined on exactly that space - but the fact
         * that makes a {@code drawnOffStructure} above zero readable rather than mysterious, and the
         * fact that tells an operator they are comparing a Sable carrier against Create ones.
         */
        private int plotSpaceHosts;

        /**
         * The age, in client ticks, of the oldest pose the last frame drew a component from.
         *
         * <p>A machine turning while the components do not is invisible in the other numbers: the pose arithmetic is
         * self-consistent about a four-second-old pose exactly as it is about a current one. {@code -1} means no frame
         * has reported yet, which must not be read as "fresh".
         */
        private long poseAgeTicks = -1L;

        /**
         * Shell quads the last frame actually emitted, over every host it drew.
         *
         * <p>This separates "a host was handled" from "geometry reached the buffer". {@code drawn} counts carried
         * <em>hosts</em>, so a host whose every quad was refused - by a colour of zero, by the shader pack's
         * solid-frame rule, by {@code showInnerComponents} - was counted by it exactly like a host that filled the
         * screen. {@code shellQuads=0} beside {@code drawnLastFrame=1} is "the log says it drew and I see nothing".
         */
        private int shellQuads;
        /** Component quads the last frame actually emitted, over every host it drew. */
        private int contentQuads;
        /** Hosts the pass collected and then emitted something for; {@code drawn} counts collection. */
        private int hostsDrawn;
        /**
         * Hosts the collection behind the <b>geometry</b> held, or {@code -1} when the caller did not say.
         *
         * <p>Travels with {@code shellQuads}/{@code contentQuads} rather than with {@code drawn}, because
         * the summary and the geometry are written by different render stages: the verdict has to compare
         * a frame's quads against that same frame's collection, not against the collection of the frame
         * the summary is being written in. See {@link #setGeometry}.
         */
        private int geometryHostsCollected = -1;
        /**
         * The gates that decided all of it: mode, goggles, colours, {@code showInnerComponents},
         * whether a shader pack is drawing.
         *
         * <p>Printed on the frame summary rather than only counted, because every one of them is a
         * configuration a reader can check against their own settings - and because "the overlay is
         * off" and "the overlay drew and nothing appeared" have the same frame summary otherwise.
         */
        private String gates = "not reported yet";

        /**
         * Which render stage each half of the frame was actually flushed at, and how many quads.
         *
         * <p>{@code shellQuads=30 contentQuads=12} proves geometry was <em>written</em> and says nothing about
         * <em>when</em> it reached the frame: a vertex written into a buffer that is ended before the structure's own
         * blocks are drawn is covered by them, and every other number reads like a correct frame. The stage name is
         * the one NeoForge's dispatcher uses, so it can be checked against the game's own source, and the counts are
         * per half of the frame. {@code "not flushed yet"} means the stage has not run since the client started, not
         * that it flushed nothing; a stage that ran and wrote nothing says {@code 0}.
         */
        private String flushStages = "not flushed yet";

        /** The last frame's per-host geometry, or {@code null} when it collected nothing. */
        @Nullable
        private String hostDetail;

        /** How many shell quads the last frame emitted. */
        public int shellQuads() {
            return shellQuads;
        }

        /** How many component quads the last frame emitted. */
        public int contentQuads() {
            return contentQuads;
        }

        /** How many collected hosts had geometry emitted for them. */
        public int hostsDrawn() {
            return hostsDrawn;
        }

        /** The gates the last frame was drawn under; see {@link #gates}. */
        public String gates() {
            return gates;
        }

        /** The last frame's per-host line, or {@code null}. */
        @Nullable
        public String hostDetail() {
            return hostDetail;
        }

        /**
         * The gates the last frame was drawn under; see {@link #gates}.
         *
         * <p>Set before the frame is collected rather than after it is drawn, because the frame summary
         * is written during collection and a summary that named the <em>previous</em> frame's
         * configuration would be exactly the kind of off-by-one that makes a diagnostic untrustworthy.
         */
        public void setGates(String gates) {
            this.gates = gates == null ? "-" : gates;
        }

        /**
         * The geometry half of the frame: what was actually emitted.
         *
         * <p>Set from the renderer, which is the only place that knows. Kept apart from
         * {@link #setFrame} so the counters that already existed cannot be disturbed by a diagnostic
         * that was added later. The gates are not touched here - see {@link #setGates}.
         *
         * <p>The four-argument form leaves the collected count unknown, which makes
         * {@link #geometryVerdict()} fall back to the frame's own {@code drawn} - the shape every fixture
         * that only feeds geometry uses. Production calls the five-argument form.
         */
        public void setGeometry(int shellQuads, int contentQuads, int hostsDrawn,
                                @Nullable String hostDetail) {
            setGeometry(shellQuads, contentQuads, hostsDrawn, -1, hostDetail);
        }

        /**
         * The same, <b>with the number of hosts this geometry was produced from</b>.
         *
         * <p>The frame summary and the geometry are recorded by <b>different render stages</b>: with no shader pack
         * the summary is written from the world stage and the carried geometry is emitted two stages later, so the
         * summary of frame <i>N</i> describes frame <i>N</i>'s collection and frame <i>N-1</i>'s quads. Comparing
         * those two reports "NOTHING EMITTED" on every frame in which a host appeared, which means only that the
         * previous frame had nothing to draw yet. The verdict therefore compares the count the geometry came from
         * against the geometry, and leaves the frame's own {@code drawn} to the row's {@code drawnLastFrame} field.
         *
         * @param hostsCollected hosts the collection these quads came from held; negative means "not reported", which
         *     is the four-argument form's answer
         */
        public void setGeometry(int shellQuads, int contentQuads, int hostsDrawn, int hostsCollected,
                                @Nullable String hostDetail) {
            this.shellQuads = shellQuads;
            this.contentQuads = contentQuads;
            this.hostsDrawn = hostsDrawn;
            this.geometryHostsCollected = Math.max(hostsCollected, -1);
            this.hostDetail = hostDetail;
        }

        /**
         * The stage each half of the frame was flushed at; see {@link #flushStages}.
         *
         * <p>Set from the renderer, which is the only place that knows, and deliberately a separate
         * setter from {@link #setGeometry}: the quads are emitted where they are counted, the flush
         * happens later in the frame, and a single setter would have to be called twice with half of
         * each answer.
         */
        public void setFlushStages(String flushStages) {
            this.flushStages = flushStages == null ? "not reported" : flushStages;
        }

        /** The stage each half of the frame was flushed at; see {@link #flushStages}. */
        public String flushStages() {
            return flushStages;
        }

        /**
         * The sentence a zero has to carry.
         *
         * <p>{@code drawn>0, shellQuads=0, contentQuads=0} means every collected host had every quad skipped: a log
         * full of healthy-looking counts and an empty screen. Spelled out rather than left to be inferred from three
         * zeros, because the reader of a log is not the person who wrote the counters.
         */
        public String geometryVerdict() {
            // The count the geometry came from, not the frame's own; see setGeometry for why they differ.
            int collected = geometryHostsCollected >= 0 ? geometryHostsCollected : drawn;
            if (collected <= 0) {
                return "";
            }
            if (shellQuads == 0 && contentQuads == 0) {
                return " *** NOTHING EMITTED - " + collected + " host(s) were collected but every quad "
                        + "was skipped; the shell and the component were both refused before a vertex was "
                        + "written. Read the gates above: a mode/colour of zero is a setting, and "
                        + "forceOverlayDraw=true with this message is a renderer fault. ***";
            }
            if (hostsDrawn < collected) {
                return " (of " + collected + " collected, " + hostsDrawn + " emitted geometry)";
            }
            return "";
        }

        public int carriersKnown() {
            return carriersKnown;
        }

        public int serverCarriers() {
            return serverCarriers;
        }

        public int hostsKnown() {
            return hostsKnown;
        }

        public int drawn() {
            return drawn;
        }

        public int skippedNoPose() {
            return skippedNoPose;
        }

        public int skippedFar() {
            return skippedFar;
        }

        public int skippedStalePose() {
            return skippedStalePose;
        }

        public int staleCarriers() {
            return staleCarriers;
        }

        public int hostsWithoutPose() {
            return hostsWithoutPose;
        }

        public int fallingBack() {
            return fallingBack;
        }

        public int landedHosts() {
            return landedHosts;
        }

        public int landedSkippedBlock() {
            return landedSkippedBlock;
        }

        public int landedDeferred() {
            return landedDeferred;
        }

        public int carriedSkippedBlock() {
            return carriedSkippedBlock;
        }

        /** Hosts drawn somewhere other than where their structure is; see {@link #drawnOffStructure}. */
        public int drawnOffStructure() {
            return drawnOffStructure;
        }

        /** Hosts carried in a plot-space coordinate range; see {@link #plotSpaceHosts}. */
        public int plotSpaceHosts() {
            return plotSpaceHosts;
        }

        /** The age of the oldest pose the last frame drew from, in client ticks; {@code -1} if unknown. */
        public long poseAgeTicks() {
            return poseAgeTicks;
        }

        /** One line for the diagnostics: the end of the same sentence {@link #describe()} starts. */
        public String drawCheckSummary(ResourceKey<Level> dimension) {
            return "drawCheck: offStructure=" + drawnOffStructure
                    + " plotSpace=" + plotSpaceHosts
                    + " " + poseAgeText()
                    + " (offStructure>0 = a component WAS drawn, more than "
                    + com.jiangyuefengyu.redstonecircuit.carrier.CarrierDrawCheck.OFF_TOLERANCE_BLOCKS
                    + " block(s) away from its structure's own position - look for the 'drawCheck "
                    + "carrier=' line to see both coordinates; plotSpace>0 = this carrier's local "
                    + "coordinates are a Sable-style plot space, which is expected for a sub-level and "
                    + "is only a fault when offStructure is also non-zero; poseAge>"
                    + com.jiangyuefengyu.redstonecircuit.carrier.CarrierDrawCheck.POSE_AGE_WARN_TICKS
                    + " = the client is drawing an OLD pose, so offStructure=0 does not mean the "
                    + "component is following the structure)";
        }

        /**
         * The pose-age phrase, shared with the client's own log lines so the two can never disagree: this is the copy
         * {@code /rc carrier} prints, and it has to carry the same warning the log does.
         */
        private String poseAgeText() {
            if (poseAgeTicks < 0) {
                return "poseAge=?(this frame drew nothing, or the age is not tracked)";
            }
            if (poseAgeTicks
                    > com.jiangyuefengyu.redstonecircuit.carrier.CarrierDrawCheck.POSE_AGE_WARN_TICKS) {
                return "poseAge=" + poseAgeTicks + "t *** STALE POSE: the client is drawing the "
                        + "structure where it was " + poseAgeTicks + " tick(s) ago ***";
            }
            return "poseAge=" + poseAgeTicks + "t";
        }

        /** One line for {@code /rc carrier}: stage 3, "did anything reach the screen". */
        public String describe() {
            return "render: carrierIndex=" + carriersKnown
                    + (serverCarriers > 0 ? "(server=" + serverCarriers + ")" : "")
                    + " carriedIndex=" + hostsKnown
                    + " drawnLastFrame=" + drawn
                    + " shellQuads=" + shellQuads
                    + " contentQuads=" + contentQuads
                    + " hostsDrawn=" + hostsDrawn
                    + " skippedNoPose=" + skippedNoPose
                    + " skippedStalePose=" + skippedStalePose
                    + " skippedFar=" + skippedFar
                    + " staleCarriers=" + staleCarriers
                    + " noPoseHosts=" + hostsWithoutPose
                    + " fallingBack=" + fallingBack
                    + " landed=" + landedHosts
                    + " landedWorldHost=" + landedSkippedBlock
                    + " landedDeferred=" + landedDeferred
                    + " carriedWorldHost=" + carriedSkippedBlock
                    + " drawnOffStructure=" + drawnOffStructure
                    + " plotSpaceHosts=" + plotSpaceHosts
                    + " " + flushStages
                    + " " + poseAgeText()
                    + geometryVerdict();
        }

        /**
         * <b>The same state, minus every counter the frame summary's own row already carries.</b>
         *
         * <p>The frame summary's row is assembled from a set of <b>keys</b> (see
         * {@code CarrierFrameRow}), and a key may appear on a line exactly once. This method therefore
         * writes only what the row does <b>not</b>: how much the client is holding, what it cannot place,
         * and the flush stage. The four counts the row does carry - {@code carriers},
         * {@code carriedHosts}, {@code landedHosts} and {@code fallingBack} - are deliberately absent,
         * as is {@code poseAge}: every one of them is a key of the row, and writing them here as well
         * would put the same name on the line twice, which is exactly the defect the row builder exists
         * to make impossible.
         *
         * <p>The per-frame counters ({@code drawnLastFrame}, the quads, the skip and landed counts) are
         * written by the verbose row alone, from {@link #frameCounters()}, so there is one copy of each
         * and one spelling of each - the same spelling {@code /rc carrier} prints.
         */
        public String describeWithoutFrameCounters() {
            return "render: carrierIndex=" + carriersKnown
                    + (serverCarriers > 0 ? "(server=" + serverCarriers + ")" : "")
                    + " carriedIndex=" + hostsKnown
                    + " staleCarriers=" + staleCarriers
                    + " noPoseHosts=" + hostsWithoutPose
                    + " flushStages=" + flushStages
                    + geometryVerdict();
        }

        /**
         * The per-frame counters as one key/value group, in the spelling {@code /rc carrier} uses.
         *
         * <p>This is the half the frame summary prints <b>only</b> when {@code verboseRenderLog=true}: it
         * is the per-frame detail an investigation wants and a default log does not need. Handing it to
         * the caller as a string rather than as thirteen getters keeps the one-spelling rule - the
         * verbose line and the command line cannot drift - and {@link #frameCounterKeys()} is the same
         * list as data, so the caller can fold it into a key set without re-typing a single name.
         */
        public String frameCounters() {
            StringBuilder sb = new StringBuilder();
            for (String key : frameCounterKeys()) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(key).append('=').append(frameCounter(key));
            }
            return sb.toString();
        }

        /**
         * The names {@link #frameCounters()} writes, in the order it writes them.
         *
         * <p>Exported as data rather than left implicit in the format string, because two callers need
         * the names and neither may spell them: the verbose row, which folds them into its key set, and
         * the test, which asserts that this list and the row's declaration of the same thirteen counters
         * are <b>equal</b> - so a counter added here and not there is caught rather than silently
         * rendering the verbose row one key short of the command's line.
         */
        public List<String> frameCounterKeys() {
            return COUNTERS;
        }

        public static final String DRAWN_LAST_FRAME = "drawnLastFrame";
        public static final String SHELL_QUADS = "shellQuads";
        public static final String CONTENT_QUADS = "contentQuads";
        public static final String HOSTS_DRAWN = "hostsDrawn";
        public static final String SKIPPED_NO_POSE = "skippedNoPose";
        public static final String SKIPPED_STALE_POSE = "skippedStalePose";
        public static final String SKIPPED_FAR = "skippedFar";
        public static final String LANDED = "landed";
        public static final String LANDED_WORLD_HOST = "landedWorldHost";
        public static final String LANDED_DEFERRED = "landedDeferred";
        public static final String CARRIED_WORLD_HOST = "carriedWorldHost";
        public static final String DRAWN_OFF_STRUCTURE = "drawnOffStructure";
        public static final String PLOT_SPACE_HOSTS = "plotSpaceHosts";

        /**
         * <b>The per-frame counters' names, in the order {@link #frameCounters()} writes them: the one
         * declaration both the line and the frame summary's key set are built from.</b>
         *
         * <p>Data rather than a format string, because two callers need the names and neither may spell
         * them: {@link #frameCounters()} builds the group from this list, and
         * {@code CarrierFrameRow.COUNTER_KEYS} - which is the verbose frame row's declaration of what it
         * writes - is this same list, not a copy. A counter added here therefore reaches the command's
         * line, the verbose row and the row's own disjointness check at once, and cannot reach one of
         * them without the others.
         *
         * <p>Declared after the thirteen names, not before: a static initialiser that reads a static
         * field declared later is an illegal forward reference in Java, and it is the order of these two
         * declarations - not their content - that the compiler checks.
         */
        public static final List<String> COUNTERS = List.of(
                DRAWN_LAST_FRAME,
                SHELL_QUADS,
                CONTENT_QUADS,
                HOSTS_DRAWN,
                SKIPPED_NO_POSE,
                SKIPPED_STALE_POSE,
                SKIPPED_FAR,
                LANDED,
                LANDED_WORLD_HOST,
                LANDED_DEFERRED,
                CARRIED_WORLD_HOST,
                DRAWN_OFF_STRUCTURE,
                PLOT_SPACE_HOSTS);

        /** The value of one per-frame counter, by the name {@link #frameCounters()} gives it. */
        int frameCounter(String key) {
            return switch (key) {
                case DRAWN_LAST_FRAME -> drawn;
                case SHELL_QUADS -> shellQuads;
                case CONTENT_QUADS -> contentQuads;
                case HOSTS_DRAWN -> hostsDrawn;
                case SKIPPED_NO_POSE -> skippedNoPose;
                case SKIPPED_STALE_POSE -> skippedStalePose;
                case SKIPPED_FAR -> skippedFar;
                case LANDED -> landedHosts;
                case LANDED_WORLD_HOST -> landedSkippedBlock;
                case LANDED_DEFERRED -> landedDeferred;
                case CARRIED_WORLD_HOST -> carriedSkippedBlock;
                case DRAWN_OFF_STRUCTURE -> drawnOffStructure;
                case PLOT_SPACE_HOSTS -> plotSpaceHosts;
                default -> throw new IllegalArgumentException(
                        "not a per-frame counter of this record: " + key);
            };
        }

        /**
         * The two lines {@code /rc carrier} prints about the geometry: the gates, then the last host.
         *
         * <p>{@code drawnLastFrame} counts collection, not emission; these two lines carry the rest.
         */
        public String geometrySummary() {
            return "geometry: " + gates
                    + "   shellQuads=" + shellQuads + " contentQuads=" + contentQuads
                    + " hostsDrawn=" + hostsDrawn
                    + " (quads are the geometry actually written into the vertex buffers; a collected "
                    + "host with 0 of them is a host that was skipped by a setting or by the renderer, "
                    + "not a host that was drawn) " + flushStages + geometryVerdict();
        }

        /** The last frame's example host, or a sentence saying none was collected. */
        public String geometryExample() {
            return "geometry host: " + (hostDetail == null
                    ? "(the last frame collected no host to name)"
                    : hostDetail);
        }

        /**
         * What the client's carried index holds right now; called by the client's render trace.
         *
         * @param posed how many carriers the client holds a <em>usable</em> pose for
         * @param hosts how many carried hosts it holds
         */
        public void setKnown(int posed, int hosts) {
            this.carriersKnown = posed;
            this.hostsKnown = hosts;
        }

        /** How many carriers the server last told a client about; called on the sending side. */
        public void setServerCarriers(int carriers) {
            this.serverCarriers = carriers;
        }

        /** What the last frame did; called by the client's render trace. */
        public void setFrame(int drawn, int skippedNoPose, int skippedFar, int skippedStalePose) {
            this.drawn = drawn;
            this.skippedNoPose = skippedNoPose;
            this.skippedFar = skippedFar;
            this.skippedStalePose = skippedStalePose;
        }

        /**
         * How much the client is holding that it cannot place; called by the client's render trace.
         *
         * <p>Three numbers rather than one string, because they answer three questions in the log line
         * {@code /rc carrier} prints - and because the same line is what a reader compares against
         * {@code carrierIndex} to see whether anything is being held.
         */
        public void setClientHeld(int staleCarriers, int hostsWithoutPose, int fallingBack) {
            this.staleCarriers = staleCarriers;
            this.hostsWithoutPose = hostsWithoutPose;
            this.fallingBack = fallingBack;
        }

        /**
         * How old the pose behind the last frame was; called by the client's render trace.
         *
         * <p>A negative value means "this frame drew nothing, or the caller does not track ages", and is
         * kept rather than clamped to zero: zero is the strongest possible claim of freshness.
         */
        public void setPoseAge(long ticks) {
            this.poseAgeTicks = ticks < 0L ? -1L : ticks;
        }

        /**
         * The landed half of the client's picture.
         *
         * <p>Two values rather than one call at the render site, because the landed count is a fact
         * about the client's index (known everywhere) while the skip counts are facts about the last
         * frame (known only to the renderer). {@code CarrierRenderTrace#frame} supplies the first and
         * takes the rest from the renderer's own frame report.
         *
         * <p>{@code deferred} is the count that closes the last way a frame could draw nothing without
         * saying why: a landed host passed over even though the world path had no block to draw over.
         * It is expected to be <b>zero</b> - a landed record now draws itself in exactly that case - so
         * a nonzero value means the two gates have drifted apart again, and it says so in the log.
         */
        public void setLanded(int landedHosts, int landedSkippedBlock, int deferred,
                              int carriedSkippedBlock) {
            this.landedHosts = landedHosts;
            this.landedSkippedBlock = landedSkippedBlock;
            this.landedDeferred = deferred;
            this.carriedSkippedBlock = carriedSkippedBlock;
        }

        /**
         * The draw-check half of the frame: how many drawn hosts were nowhere near their structure.
         *
         * <p>Set from the renderer, which is the only place that holds both the pose and the local
         * coordinate at the same moment. See {@code CarrierDrawCheck}.
         */
        public void setDrawCheck(int drawnOffStructure, int plotSpaceHosts) {
            this.drawnOffStructure = drawnOffStructure;
            this.plotSpaceHosts = plotSpaceHosts;
        }

        /**
         * The sight half of the frame: how many carried hosts were asked whether the eye can reach them, and what the
         * ray answered.
         *
         * <p>{@code drawnOffStructure} says where a host was drawn and nothing about whether something solid stands
         * between the eye and that point, so a component refused by the sight rule and one that is drawn look the
         * same in the trace. These five counts separate them, and {@code sightUnknownTarget} in particular names a
         * question asked with no point to aim at.
         *
         * <p>These five <b>stay on the default frame row</b>; the rest of the frame's numbers are written only under
         * {@code verboseRenderLog}. The split is by usefulness: five short integers that answer the current question,
         * against geometry counters an investigation can afford to turn a switch on for.
         */
        private CarrierHostCheck.Tally sight = CarrierHostCheck.Tally.NONE;

        /**
         * The last worked sight example, already worded by the renderer.
         *
         * <p>A string rather than the fields, for the same reason {@code hostDetail} is one: the
         * renderer is the only place that has a level, an eye and a pose at the same instant, and
         * formatting it there keeps this class free of any render context. {@code null} means this
         * frame's collection reached no sight question at all.
         */
        @Nullable
        private String sightDetail;

        /** The sight counts of the last frame; see {@link #sight}. */
        public CarrierHostCheck.Tally sight() {
            return sight;
        }

        /** The last frame's worked sight example, or {@code null}; see {@link #sightDetail}. */
        @Nullable
        public String sightDetail() {
            return sightDetail;
        }

        /**
         * Records the sight half of the frame; called by the client's render trace once per frame.
         *
         * <p>A {@code null} tally is read as "nothing was asked", which is the honest reading and the
         * same one a zeroed tally would give.
         */
        public void setSight(@Nullable CarrierHostCheck.Tally tally, @Nullable String detail) {
            this.sight = tally == null ? CarrierHostCheck.Tally.NONE : tally;
            this.sightDetail = detail;
        }
    }

    private static final Map<ResourceKey<Level>, Claims> CLAIMS = new LinkedHashMap<>();
    private static final Map<ResourceKey<Level>, Sync> SYNC = new LinkedHashMap<>();
    private static final Map<ResourceKey<Level>, Render> RENDER = new LinkedHashMap<>();

    private CarrierTrace() {
    }

    /** The claim counters for one level, created on first use. */
    public static Claims claims(ResourceKey<Level> dimension) {
        return CLAIMS.computeIfAbsent(dimension, ignored -> new Claims());
    }

    /** The sync counters for one level, created on first use. */
    public static Sync sync(ResourceKey<Level> dimension) {
        return SYNC.computeIfAbsent(dimension, ignored -> new Sync());
    }

    /** The render counters for one level, created on first use. */
    public static Render render(ResourceKey<Level> dimension) {
        return RENDER.computeIfAbsent(dimension, ignored -> new Render());
    }

    /** The claim counters for a level, without creating anything; for the diagnostics. */
    public static Claims claimsIfAny(ResourceKey<Level> dimension) {
        return CLAIMS.get(dimension);
    }

    public static Sync syncIfAny(ResourceKey<Level> dimension) {
        return SYNC.get(dimension);
    }

    public static Render renderIfAny(ResourceKey<Level> dimension) {
        return RENDER.get(dimension);
    }

    /** The three lines {@code /rc carrier} prints for one level, in stage order. */
    public static List<String> describe(ResourceKey<Level> dimension) {
        Claims claims = claimsIfAny(dimension);
        Sync sync = syncIfAny(dimension);
        Render render = renderIfAny(dimension);
        List<String> out = new ArrayList<>(3);
        out.add(claims == null ? "claim: nothing picked up in this level yet" : claims.describe());
        out.add(sync == null ? "sync: nothing sent for this level yet" : sync.describe());
        out.add(render == null ? "render: the client has not reported yet (single-player: same game)"
                : render.describe());
        return out;
    }

    /** Drops one level's counters; called when a level unloads, alongside the rest of the carrier state. */
    public static void clear(ResourceKey<Level> dimension) {
        CLAIMS.remove(dimension);
        SYNC.remove(dimension);
        RENDER.remove(dimension);
    }

    /** Drops everything; for a full client or server reset. */
    public static void clearAll() {
        CLAIMS.clear();
        SYNC.clear();
        RENDER.clear();
    }

    // -------------------------------------------------------- mutate (claims) --

    /** One host was claimed under a contraption entity's UUID. */
    public static void noteEntityClaim(ResourceKey<Level> dimension) {
        claims(dimension).entityClaims++;
    }

    /** One host was claimed under a provisional identity. */
    public static void noteProvisionalClaim(ResourceKey<Level> dimension) {
        claims(dimension).provisionalClaims++;
    }

    /** A provisional claim was re-labelled onto an entity. */
    public static void noteReconcile(ResourceKey<Level> dimension, boolean movedSomething) {
        Claims claims = claims(dimension);
        if (movedSomething) {
            claims.reconciled++;
        } else {
            claims.reconcileNoOps++;
        }
    }

    /** A reconciliation threw; the claim is still in the index, under the name it was made with. */
    public static void noteReconcileFailure(ResourceKey<Level> dimension) {
        claims(dimension).reconcileFailures++;
    }

    /** A block reported in with no component inside it; the ordinary case for most of a machine. */
    public static void noteEmptyBlock(ResourceKey<Level> dimension) {
        claims(dimension).emptyBlocks++;
    }

    // ---------------------------------------------------------- mutate (sync) --

    /** One carrier message went out on the wire. */
    public static void noteCarrierSent(ResourceKey<Level> dimension, int liveHosts) {
        Sync sync = sync(dimension);
        sync.carriersSent++;
        sync.hostsSent += liveHosts;
    }

    /** One pose-only message went out while a structure moved. */
    public static void notePoseSent(ResourceKey<Level> dimension) {
        sync(dimension).posesSent++;
    }

    /**
     * A {@code stopMoving} callback that did <b>not</b> release: the world block was still air and the structure was
     * still there. See {@link PendingRelease}.
     *
     * <p>"The structure asked to stand down" and "the structure put the block down" are two different events.
     */
    public static void noteDeferredRelease(ResourceKey<Level> dimension) {
        sync(dimension).releasesDeferred++;
    }

    /** A deferred release that was settled - the structure really had stopped. */
    public static void noteDeferredReleaseSettled(ResourceKey<Level> dimension) {
        sync(dimension).releasesDeferredSettled++;
    }

    /** A deferred release that was cancelled because the structure was demonstrably still moving. */
    public static void noteDeferredReleaseCancelled(ResourceKey<Level> dimension) {
        sync(dimension).releasesDeferredCancelled++;
    }

    /** A full carrier snapshot was sent to one arriving player. */
    public static void noteSnapshotSent(ResourceKey<Level> dimension, int carriers) {
        Sync sync = sync(dimension);
        sync.snapshotsSent++;
        sync.carriersSent += carriers;
    }

    /** A world host was announced because a carrier put it down there. */
    public static void noteHostRestored(ResourceKey<Level> dimension) {
        sync(dimension).restored++;
    }

    /** A world host was suppressed at a position because a carrier picked it up. */
    public static void noteWorldHostSuppressed(ResourceKey<Level> dimension) {
        sync(dimension).worldHostSuppressed++;
    }

    // -------------------------------------------------------- mutate (render) --

    /** What the client's carried index holds right now. */
    public static void noteClientIndex(ResourceKey<Level> dimension, int carriers, int hosts) {
        Render render = render(dimension);
        render.carriersKnown = carriers;
        render.hostsKnown = hosts;
    }

    /** How many carriers the server has in flight for this level; see {@link Render#serverCarriers}. */
    public static void noteServerCarriers(ResourceKey<Level> dimension, int carriers) {
        render(dimension).serverCarriers = carriers;
    }

    /** What the last rendered frame did with the carried hosts it found. */
    public static void noteRender(ResourceKey<Level> dimension, int drawn, int skippedNoPose,
                                  int skippedFar, int skippedStalePose) {
        Render render = render(dimension);
        render.drawn = drawn;
        render.skippedNoPose = skippedNoPose;
        render.skippedFar = skippedFar;
        render.skippedStalePose = skippedStalePose;
    }
}

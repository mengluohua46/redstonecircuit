package com.jiangyuefengyu.redstonecircuit.carrier;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * <b>The read-only facts of one line-of-sight question asked about a carried host.</b>
 *
 * <h2>Why this class exists</h2>
 * Two failures look identical on screen - "the component is not drawn at all" - and the log could not
 * tell them apart:
 *
 * <ul>
 *   <li><b>B1: the host never entered a walk.</b> {@code CarrierIndex} buckets every carried host by
 *       the section of its <em>origin</em> - the world block the structure took it from - and the
 *       renderer walks only the sections within {@code SECTION_RADIUS} of the camera. A structure that
 *       has flown seven chunks away from where its blocks came from therefore has every host sitting in
 *       a bucket nobody walks, while the pose that would draw them is right beside the camera. The
 *       distance rule is measured in the wrong space, and this is what names it.</li>
 *   <li><b>B2: the host was walked, collected, and then refused.</b> Two things can do that: the
 *       projected point can be the wrong point (a plot coordinate in the twenty-millions, say), or the
 *       sight ray can hit something that is not the target. Only the second is visible in a log that
 *       prints the first position without the ray.</li>
 * </ul>
 *
 * <p>This type is <b>only</b> a place to hold those answers while they travel from the render pass -
 * the one place that has a level, an eye and a pose at the same moment - to the log. Nothing here is
 * read by any judgement: the values are computed <em>from</em> the decisions that were already made
 * ({@code CarrierPose#blockCentre}, {@code HostRenderPass#castSightRay}), never passed back into them.
 * The whole of its contribution to the picture is zero.
 *
 * <h2>Where each half is produced</h2>
 * The B1 half is assembled from {@code CarrierIndex}'s own buckets and pose map, so it describes the
 * walk that really ran rather than a second opinion about it. The B2 half is filled in by the render
 * pass at the moment it asks the sight question, so {@code ray=} and the decision cannot drift.
 *
 * <h2>This class is the one place the B1 line is worded</h2>
 * Both the sweep's rollup call site and the sort of line a test builds come through
 * {@link #describe()}, so the line the log carries and the line a test asserts on cannot be two
 * different strings. That is deliberate: the round that first added this line had the two spelled
 * out separately and drifted by one field, which is exactly the class of defect the project's own
 * notes call "the instrument reporting its own arithmetic".
 *
 * @param carrier the structure this host rides
 * @param originSection the section of the host's <b>origin</b> - the space the walk buckets by
 * @param poseSection the section of the carrier's pose, or {@code "none"} when the client holds no pose
 * @param cameraSection the section the camera is in, or {@code null} when the caller has no camera
 * @param collected whether this host's origin is inside the walk radius this frame
 * @param localPos the host's coordinate inside the structure
 * @param drawnWorld where the renderer projects that coordinate to, or {@code null} when it cannot
 * @param eye the eye the sight question was asked from, or {@code null}
 * @param sight what the ray answered, or {@code null} when no ray was asked
 * @param hit the block the ray hit first, or {@code null} for a miss
 * @param walkedHosts how many of the carrier's hosts are inside the origin walk this frame
 * @param totalHosts how many hosts the carrier holds
 * @param poseAgeTicks the pose's age in client ticks, or {@code -1} when there is no pose
 * @param origin the first host's origin block, or {@code null}; the coordinate the walk buckets by, so
 *     a reader can check the section printed beside it
 */
public record CarrierHostCheck(CarrierId carrier, String originSection, String poseSection,
                               @Nullable BlockPos cameraSection, boolean collected,
                               @Nullable BlockPos localPos, @Nullable BlockPos drawnWorld,
                               @Nullable BlockPos eye, @Nullable Sight sight, @Nullable BlockPos hit,
                               int walkedHosts, int totalHosts, long poseAgeTicks,
                               @Nullable BlockPos origin) {

    /** The section part of every line this class writes; {@code (sx, sy, sz)} like the pose section. */
    public static String section(@Nullable BlockPos pos) {
        if (pos == null) {
            return "none";
        }
        return "(" + SectionPos.blockToSectionCoord(pos.getX()) + ", "
                + SectionPos.blockToSectionCoord(pos.getY()) + ", "
                + SectionPos.blockToSectionCoord(pos.getZ()) + ")";
    }

    /** {@code (x, y, z)}, or {@code unknown}; the one spelling every coordinate in these lines uses. */
    public static String pos(@Nullable BlockPos pos) {
        return pos == null ? "unknown"
                : "(" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")";
    }

    /**
     * <b>The one line that settles "the structure stopped being drawn seven chunks from where it was
     * placed".</b>
     *
     * <pre>
     * carrier=c:1a2b3c4d originSection=(8, 4, -3) poseSection=(9, 4, 3) cameraSection=(9, 4, 3)
     *   collected=no(origin-bucket-outside-radius) hosts=0/1 poseAge=3t
     *   firstHostProjected=(2776, 77, -1394) origin=(2775, 77, -1395)
     * </pre>
     *
     * <p>{@code collected=no(origin-bucket-outside-radius)} next to a {@code poseSection} that is the
     * camera's own section is the whole finding: the pose is right beside the player and the host was
     * never offered to the renderer, because the radius is measured from where the block came from.
     *
     * <p>Every field the acceptance procedure greps for is written here, and the two projections are
     * written <b>even when they are {@code unknown}</b> rather than being omitted: a missing key reads as
     * "this build does not have that diagnostic", while {@code drawnWorld=unknown} reads as the finding it
     * is. The B2 line shares {@link #pos} and {@link #section} with this one for the same reason.
     */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append("carrier=").append(carrier == null ? "-" : carrier.shortId());
        sb.append(" originSection=").append(originSection);
        sb.append(" poseSection=").append(poseSection);
        sb.append(" cameraSection=").append(section(cameraSection));
        sb.append(" collected=").append(collected ? "yes"
                : "no(" + collectionReason() + ")");
        sb.append(" hosts=").append(walkedHosts).append('/').append(totalHosts);
        sb.append(" poseAge=").append(poseAgeTicks < 0 ? "?" : poseAgeTicks + "t");
        // The projection and the origin: the evidence that the pose the client holds is perfectly good
        // and the host was never offered to the renderer. Both keys are always written.
        sb.append(" firstHostProjected=").append(drawnWorld == null ? "unknown" : pos(drawnWorld));
        sb.append(" origin=").append(pos(origin));
        return sb.toString();
    }

    /**
     * <b>Why this host was not offered to the renderer</b>, worded so the causes cannot be confused
     * with one another.
     *
     * <p>The order is the order the filters run in (see {@code CarrierIndex#drawablesNear}), and the
     * first cause that applies is the one reported - a host can be both pose-less and far away, and
     * naming both would leave the reader to work out which one stopped it.
     */
    public String collectionReason() {
        if (collected) {
            return "collected";
        }
        if (poseSection.equals("none")) {
            // Nothing to draw with, whatever the radius says - the walk may well have offered it and
            // the renderer's own skippedNoPose counter is where that is counted.
            return "no-pose";
        }
        if (poseAgeTicks > CarrierDrawCheck.POSE_AGE_WARN_TICKS) {
            // Offered, and refused for want of trust: the renderer's skippedStalePose counts it.
            return "stale-pose";
        }
        // The finding. The pose is beside the camera and the origin is not; the walk buckets by the
        // origin, so no host of this carrier was offered to the renderer at all.
        return "origin-bucket-outside-radius";
    }

    /**
     * <b>The one line that settles "the component inside is visible through a wall".</b>
     *
     * <pre>
     * carrier=c:1a2b3c4d local=(1, 0, 0) drawnWorld=(2776, 77, -1394) eye=(2773, 78, -1390)
     *   ray=miss sight=miss target=(2776, 77, -1394)
     * </pre>
     *
     * <p>{@code drawnWorld=unknown} and {@code drawnWorld=(20481032, 127, 20481032)} are two different
     * faults and this line tells them apart: the first is "there was no pose to project through", the
     * second is "the projection produced a plot coordinate", which is the shape that draws the
     * component where nobody can see it while every counter says a host was drawn.
     *
     * <p>{@code ray=} is what the ray did and {@code sight=} is what the rule decided, so the two cannot
     * be confused: {@code ray=miss sight=miss} is "nothing is in the way and the component is drawn",
     * while {@code ray=hit(…)} {@code sight=refused-hit} is "something is in the way", and the hit's
     * coordinates are on the line so the reader can see whether it is the target's own block or a wall in
     * front.
     */
    public String describeSight() {
        StringBuilder sb = new StringBuilder();
        sb.append("carrier=").append(carrier == null ? "-" : carrier.shortId());
        sb.append(" local=").append(pos(localPos));
        sb.append(" drawnWorld=").append(drawnWorld == null ? "unknown" : pos(drawnWorld));
        sb.append(" eye=").append(eye == null ? "unknown" : pos(eye));
        sb.append(" ray=");
        if (hit != null) {
            sb.append("hit(").append(hit.getX()).append(", ").append(hit.getY()).append(", ")
                    .append(hit.getZ()).append(")");
        } else if (sight != null) {
            sb.append(sight.words());
        } else {
            sb.append("none");
        }
        sb.append(" sight=").append(sight == null ? "not-asked" : sight.words());
        return sb.toString();
    }

    /**
     * The same line with the block the ray was aimed at named, so {@code ray=hit(a, b, c)} and
     * {@code target=(a, b, c)} can be compared by eye.
     *
     * <p>The target is written whether or not there was one: {@code target=unknown} is the finding "no
     * point could be aimed at", and omitting the key would read as "this build is older than the key".
     */
    public String describeSightWithTarget() {
        return describeSight() + " target=" + pos(drawnWorld);
    }

    /**
     * What the sight ray answered, as four values with no overlap.
     *
     * <p>{@link #UNKNOWN_TARGET} is deliberately its own answer rather than a flavour of the other
     * three: when the projected point could not be worked out, the ray was aimed at nothing, and a
     * reader who saw {@code miss} there would conclude "nothing is in the way" - the opposite of what
     * is known.
     */
    public enum Sight {
        /** The ray reached the point, or what it hit is the structure the host is part of. */
        DRAWN,
        /** The ray hit a block strictly in front of the point: something is in the way. */
        REFUSED_HIT,
        /** The ray hit nothing at all. */
        MISS,
        /** No point could be aimed at (no pose, or no projection). */
        UNKNOWN_TARGET;

        /** The word this value writes in a log line. */
        public String words() {
            return switch (this) {
                case DRAWN -> "drawn";
                case REFUSED_HIT -> "refused-hit";
                case MISS -> "miss";
                case UNKNOWN_TARGET -> "unknown-target";
            };
        }

        /**
         * Classifies a ray the renderer has already cast, <b>without changing what it decided</b>.
         *
         * <p>Split out as a pure question so it can be tested offline: the renderer's sight rule returns
         * a boolean and loses which of the four shapes produced it, and the classification has to agree
         * with that boolean by construction rather than by inspection.
         *
         * <p>{@code accepted} and {@code hit} are checked in the order the rule produced them, so
         * {@code accepted=true} with a hit on the structure's own blocks is {@link #DRAWN} - which is the
         * case the rule calls "not a wall", and the one a naive "did the ray hit anything" reading would
         * report backwards.
         *
         * @param accepted what the renderer decided - the value the picture depends on
         * @param hit the ray's result, or {@code null} when no ray was cast
         * @param target the point that was aimed at, or {@code null} when there was none
         */
        public static Sight of(boolean accepted, @Nullable BlockHitResult hit, @Nullable BlockPos target) {
            if (target == null) {
                return UNKNOWN_TARGET;
            }
            if (accepted) {
                return DRAWN;
            }
            if (hit != null && hit.getType() == HitResult.Type.BLOCK) {
                return REFUSED_HIT;
            }
            return MISS;
        }

        /**
         * <b>The same classification from the three facts a caller already holds</b>, so the rule's
         * shape and the renderer's boolean can be compared without a level or a ray.
         *
         * <p>This is the pure half of {@code HostRenderPass#castSightRay}: the renderer computes the
         * three booleans - "was there a point to aim at", "did the rule accept", "did the ray hit a
         * block" - and this turns them into the word the log writes. Splitting it out is what makes
         * "the classification agrees with the decision it was given" a statement a test can check on
         * every combination of the three, rather than one the reader has to take on trust from the
         * renderer's own control flow.
         *
         * @param aimed whether a point was aimed at at all
         * @param accepted what the renderer decided, i.e. what the picture depends on
         * @param hitBlock whether the ray hit a block ({@code false} for a miss or no ray)
         */
        public static Sight ofFacts(boolean aimed, boolean accepted, boolean hitBlock) {
            if (!aimed) {
                return UNKNOWN_TARGET;
            }
            if (accepted) {
                return DRAWN;
            }
            return hitBlock ? REFUSED_HIT : MISS;
        }
    }

    /**
     * <b>The sight counts that ride in the frame summary</b>, so a reader of one line can see how many
     * carried hosts were asked about and how many of each answer came back.
     *
     * <p>Five plain counters in one immutable value: they are set once per frame by the render pass and
     * read once per summary line, and a per-frame allocation of one small record is not a cost worth
     * avoiding with a mutable shared object that could be read half-updated.
     *
     * <p>These five <b>stay on the default line</b> while the other frame counters move behind
     * {@code verboseRenderLog} (see {@code CarrierRenderTrace}): they are what this round is for, and a
     * reader who has to turn a switch on to see them has already done the reproduction. They are five
     * short integers, not a paragraph.
     *
     * <p>The <b>carrier walk</b> is deliberately not part of this: it is a property of the client's index
     * rather than of the frame (see {@code ClientHostCache.CarrierSweep}), and it is written on its own
     * line by the sweep, so folding it in here would give two places the same numbers could disagree
     * from.
     *
     * @param checked carried hosts the sight question was asked about
     * @param drawn hosts the question accepted
     * @param refused the question refused because the ray hit a block in front of the point
     * @param miss the question asked and the ray hit nothing
     * @param unknownTarget the question asked and no point could be aimed at
     */
    public record Tally(int checked, int drawn, int refused, int miss, int unknownTarget) {

        /** "Nothing was asked" - the value every caller starts from, and the honest reading of a null. */
        public static final Tally NONE = new Tally(0, 0, 0, 0, 0);

        /**
         * Adds one answered sight question; pure, so the render pass can accumulate without a mutable
         * field and the test can drive it directly.
         */
        public Tally plus(Sight outcome) {
            return switch (outcome) {
                case DRAWN -> new Tally(checked + 1, drawn + 1, refused, miss, unknownTarget);
                case REFUSED_HIT -> new Tally(checked + 1, drawn, refused + 1, miss, unknownTarget);
                case MISS -> new Tally(checked + 1, drawn, refused, miss + 1, unknownTarget);
                case UNKNOWN_TARGET -> new Tally(checked + 1, drawn, refused, miss, unknownTarget + 1);
            };
        }

        /** {@code sightChecked=… sightDrawn=… …}, the tail of the frame summary line. */
        public String describe() {
            return "sightChecked=" + checked + " sightDrawn=" + drawn + " sightRefusedHit=" + refused
                    + " sightMiss=" + miss + " sightUnknownTarget=" + unknownTarget;
        }

        /** The frame summary's tail; the phrase the line writes and the test asserts. */
        public String frameTail() {
            return describe();
        }

        /**
         * The B1 rollup, worded exactly as the acceptance procedure greps for it.
         *
         * <p>{@code collected=M skippedOriginOnly=K} is the pair that matters: a nonzero
         * {@code skippedOriginOnly} with everything else at zero means the only reason those components
         * were not offered to the renderer is where their <em>blocks came from</em>. {@code skippedStale}
         * and {@code skippedFar} are the renderer's own per-frame counters and are kept as separate keys,
         * because "the walk never offered it" and "the walk offered it and the renderer refused it" are
         * opposite conclusions.
         *
         * @param carriers every carrier the client holds
         * @param collected carriers the origin walk offered to the renderer this frame
         * @param skippedOriginOnly carriers with a pose whose hosts were all outside the radius
         * @param skippedNoPose carriers the client has no pose for at all
         * @param skippedStale carriers the walk offered whose pose had gone stale
         * @param skippedFar carriers the walk offered that were past the draw distance
         */
        public static String describeCarriers(int carriers, int collected, int skippedOriginOnly,
                                              int skippedNoPose, int skippedStale, int skippedFar) {
            return "carriers=" + carriers
                    + " collected=" + collected
                    + " skippedOriginOnly=" + skippedOriginOnly
                    + " skippedNoPose=" + skippedNoPose
                    + " skippedStale=" + skippedStale
                    + " skippedFar=" + skippedFar;
        }
    }
}

package com.jiangyuefengyu.redstonecircuit.carrier;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * The read-only facts of one line-of-sight question asked about a carried host.
 *
 * <p>Holds what the render pass knows at the one moment that has a level, an eye and a pose, so the log can tell
 * "the host was never offered to the renderer" (its origin bucket fell outside the camera radius, because the walk
 * buckets by origin and not by pose) from "it was offered and the sight ray refused it". Nothing here feeds back
 * into a judgement: the values are computed <em>from</em> decisions already made. {@code poseAgeTicks} is -1 when
 * the client holds no pose.
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
     * The B1 line: {@code collected=no(origin-bucket-outside-radius)} beside a {@code poseSection} that is the
     * camera's own section is the whole finding. Every key is written even when its value is {@code unknown}.
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
        // The projection and the origin: the pose is good and the host was never offered. Always written.
        sb.append(" firstHostProjected=").append(drawnWorld == null ? "unknown" : pos(drawnWorld));
        sb.append(" origin=").append(pos(origin));
        return sb.toString();
    }

    /**
     * Why this host was not offered to the renderer, worded in the order the filters run: the first cause that
     * applies is the one reported.
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
     * The B2 line: {@code ray=} is what the ray did and {@code sight=} is what the rule decided, so
     * {@code ray=miss sight=miss} and {@code ray=hit(…)} {@code sight=refused-hit} cannot be confused.
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
     * {@code target=(a, b, c)} can be compared by eye. {@code target=unknown} is itself a finding.
     */
    public String describeSightWithTarget() {
        return describeSight() + " target=" + pos(drawnWorld);
    }

    /**
     * What the sight ray answered, as four values with no overlap. {@link #UNKNOWN_TARGET} is its own answer
     * rather than a flavour of {@code MISS}: when no point could be aimed at, a reader who saw {@code miss} would
     * conclude "nothing is in the way" - the opposite of what is known.
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
         * Classifies a ray the renderer has already cast, <b>without changing what it decided</b>. {@code accepted}
         * is checked before {@code hit}, so {@code accepted=true} with a hit on the structure's own blocks is
         * {@link #DRAWN} rather than a wall.
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
         * The same classification from the three facts a caller already holds, so a test can check it on every
         * combination without a level or a ray.
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
     * The sight counts that ride in the frame summary: five plain counters in one immutable value, set once per
     * frame by the render pass and read once per summary line. The carrier walk is not part of them - it is a
     * property of the client's index rather than of the frame, and is written on its own line by the sweep - and
     * these five stay on the default line while the other frame counters move behind {@code verboseRenderLog}.
     */
    public record Tally(int checked, int drawn, int refused, int miss, int unknownTarget) {

        /** "Nothing was asked" - the value every caller starts from, and the honest reading of a null. */
        public static final Tally NONE = new Tally(0, 0, 0, 0, 0);

        /**
         * Adds one answered sight question; pure, so a caller can accumulate without a mutable field.
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
         * The B1 rollup: a nonzero {@code skippedOriginOnly} with everything else at zero means the only reason
         * those components were not offered to the renderer is where their <em>blocks came from</em>.
         * {@code skippedStale} and {@code skippedFar} are the renderer's own per-frame counters and stay separate
         * keys, because "the walk never offered it" and "the walk offered it and the renderer refused it" are
         * opposite conclusions.
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

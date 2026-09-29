package com.jiangyuefengyu.redstonecircuit.carrier;

import net.minecraft.core.BlockPos;

import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

/**
 * <b>"Where we intend to draw, where the structure actually is, and how far apart those are."</b>
 *
 * <h2>The question this answers, and why it needs its own class</h2>
 * The author's report for the Sable / Create Aeronautics path was: <em>the components inside the block
 * cannot be seen, and they stay behind instead of following</em>. Those are two different failures with
 * one common shape - a carried host drawn somewhere the player is not looking - and the existing
 * counters cannot tell them apart:
 *
 * <ul>
 *   <li>{@code drawnLastFrame=0} with a skip counter at zero used to mean "nothing was even attempted";</li>
 *   <li>{@code drawnLastFrame=1} means <b>a quad was emitted</b>, and says nothing at all about whether
 *       it was emitted where the structure is. A host drawn at a plot coordinate in the twenty-millions
 *       - which is exactly what a Sable sub-level's own local space looks like - counts as a perfect
 *       draw by every counter the mod had before this class.</li>
 * </ul>
 *
 * <p>So the check is deliberately a <em>comparison of two positions</em> rather than another counter:
 * the world point the pose arithmetic is about to draw at, and the structure's own reported position.
 * One number, {@link #deltaBlocks()}, and one verdict. That single line distinguishes the three states
 * an operator actually cares about:
 *
 * <table>
 *   <tr><td>{@code delta} small</td><td>we are drawing where the machine is - a missing component is
 *       a <em>different</em> fault (goggles off, no pose, host never claimed)</td></tr>
 *   <tr><td>{@code delta} enormous</td><td>the local space and the pose disagree: the component is being
 *       drawn, just somewhere else entirely. This is the "画到了百万格外" case</td></tr>
 *   <tr><td>{@code plotSpaceLocal=true}</td><td>the local coordinates are themselves in the
 *       twenty-millions, i.e. the carrier's local space is Sable's <b>plot</b> space. Not a fault by
 *       itself - Sable's own {@code logicalPose} expects that space - but it is the fact that explains
 *       an enormous delta when one appears, and it is what an operator has to know before comparing a
 *       Sable carrier against a Create one</td></tr>
 * </table>
 *
 * <h2>Purity</h2>
 * Nothing here reads a level, an entity or a mod: it is a pose, two positions and arithmetic. The
 * caller - the server diagnostics, the client render trace - supplies the two positions, and this
 * decides what they mean. Both the arithmetic and the verdict are therefore pinned by unit tests with
 * no game running, which matters because this is the measurement the whole acceptance procedure for
 * the Sable path reads.
 *
 * @param carrier the structure the host is riding, or {@code null} when the caller could not name one
 * @param localPos the host's coordinate inside the structure, or {@code null} when there is none yet
 * @param drawWorld where this host's block centre is about to be drawn, or {@code null} when there is
 *     no pose to draw it through
 * @param structureWorld the structure's own reported position in the world, or {@code null} when the
 *     caller cannot report one
 * @param poseAgeTicks how long ago the pose used for this comparison was <b>sent by the server</b>, in
 *     ticks, or {@code -1} when the caller does not know. See {@link #POSE_AGE_WARN_TICKS} for why this
 *     is a first-class part of the check rather than a footnote
 */
public record CarrierDrawCheck(@Nullable CarrierId carrier, @Nullable BlockPos localPos,
                               @Nullable Vector3d drawWorld, @Nullable Vector3d structureWorld,
                               long poseAgeTicks) {

    /**
     * How old a pose may be before the draw check says so loudly, in ticks.
     *
     * <p><b>Why this field exists at all.</b> The author's report was "the bearing turns and the
     * component does not turn with it". Every counter this mod had said the frame was perfect:
     * {@code drawnLastFrame=1}, and {@code delta=0.000 OK} - because the <em>pose arithmetic</em> really
     * was self-consistent, and it was self-consistent about a pose the client had been holding for four
     * seconds. {@code delta=0.000 OK} is true of a stale pose and true of a fresh one, which makes it
     * useless for this defect.
     *
     * <p>The age of the pose is the missing half of the measurement. <b>Forty ticks - two seconds -
     * which is deliberately twice the default heartbeat.</b> It has to sit above the heartbeat, or every
     * parked machine would be reported as a fault on the one tick in twenty when its pose is exactly one
     * heartbeat old; and it has to be well below the four seconds of silence the author's log shows, or
     * it would not have caught the defect. Twice the heartbeat is the smallest value that is
     * unambiguously "the server has stopped talking", rather than "the server is talking at its normal
     * pace".
     */
    public static final long POSE_AGE_WARN_TICKS = 40;

    /**
     * How far a draw point may sit from the structure's own position before the two are called
     * inconsistent.
     *
     * <p>Four blocks. A structure's pose origin is a pivot, not a centre - Create's is its anchor plus
     * half a block, Sable's is its rotation point - so a legitimate host can be a couple of blocks from
     * it even in a one-block machine, and a real machine has components a few blocks from its pivot as
     * a matter of course. Everything this check exists to catch is off by <em>orders of magnitude</em>
     * (plot coordinates are in the millions), so the tolerance is set to be obviously safe against a
     * big machine rather than tight against a small one.
     */
    public static final double OFF_TOLERANCE_BLOCKS = 4.0;

    /**
     * A local coordinate of this magnitude or more is somebody's plot space, not a coordinate relative
     * to an anchor.
     *
     * <p>One hundred thousand: a Create contraption's local coordinates are bounded by its own size (a
     * huge machine is still tens of blocks), and a Sable plot coordinate is around two times ten to the
     * seventh. There is no structure in between, so the threshold only has to be far from both.
     */
    public static final int PLOT_SPACE_THRESHOLD = 100_000;

    /** No carrier: there is nothing to compare, and the line says so rather than inventing a verdict. */
    public static CarrierDrawCheck unknown(@Nullable BlockPos localPos) {
        return new CarrierDrawCheck(null, localPos, null, null, -1L);
    }

    /** The same check for a caller that holds no pose at all and does not know how old it is. */
    public static CarrierDrawCheck of(@Nullable CarrierId carrier, @Nullable BlockPos localPos,
                                      @Nullable Vector3d drawWorld, @Nullable Vector3d structureWorld) {
        return new CarrierDrawCheck(carrier, localPos, drawWorld, structureWorld, -1L);
    }

    /** The same, with the age of the pose that was used. */
    public static CarrierDrawCheck of(@Nullable CarrierId carrier, @Nullable BlockPos localPos,
                                      @Nullable Vector3d drawWorld, @Nullable Vector3d structureWorld,
                                      long poseAgeTicks) {
        return new CarrierDrawCheck(carrier, localPos, drawWorld, structureWorld, poseAgeTicks);
    }

    /** How far the draw point is from the structure's position, or {@code null} when either is unknown. */
    @Nullable
    public Double deltaBlocks() {
        if (drawWorld == null || structureWorld == null) {
            return null;
        }
        return drawWorld.distance(structureWorld);
    }

    /**
     * Whether the structure is being asked to draw a host at a point that is nowhere near it.
     *
     * <p>Deliberately {@code false} when either position is unknown: "we do not know" is not "it is
     * wrong", and a counter that conflated them would fire on every frame before the first pose
     * arrived.
     */
    public boolean drawnFarFromStructure() {
        Double delta = deltaBlocks();
        return delta != null && delta > OFF_TOLERANCE_BLOCKS;
    }

    /**
     * Whether this host's local coordinate is in a plot-space magnitude range rather than in the small
     * "relative to the structure's own origin" range every other part of this mod assumed.
     *
     * <p>This is a <b>fact</b>, not a fault: Sable's {@code logicalPose} is defined on exactly this
     * space, so a carrier whose local coordinates are huge is correct as long as the pose that came
     * with them is Sable's own. It is printed because it is the one number that makes an operator's
     * "the log says it drew, but I see nothing" readable rather than mysterious.
     */
    public boolean plotSpaceLocal() {
        return plotSpace(localPos);
    }

    /**
     * The same fact about a coordinate, for the callers that have the coordinate and not a check.
     *
     * <p>The renderer needs it <b>before</b> it builds the check, because it decides how the sight
     * question is asked - see {@code CarriedSight#isVisible}'s fourth rule and {@code HostRenderPass}.
     * One spelling of "this local coordinate is a plot coordinate" for both callers, deliberately: two
     * spellings of it would let the diagnostic and the judgement disagree about which hosts are Sable's.
     */
    public static boolean plotSpace(@Nullable BlockPos localPos) {
        if (localPos == null) {
            return false;
        }
        return Math.abs(localPos.getX()) >= PLOT_SPACE_THRESHOLD
                || Math.abs(localPos.getY()) >= PLOT_SPACE_THRESHOLD
                || Math.abs(localPos.getZ()) >= PLOT_SPACE_THRESHOLD;
    }

    /**
     * Whether this check, on its own, <b>explains</b> a component that is not on screen.
     *
     * <p>True only for the one state that does: a pose that draws the host nowhere near the structure.
     * Every other state means the fault is elsewhere, and saying so here is what stops a reader
     * chasing this line when the real answer is "no pose arrived at all" (which the skip counters
     * report) or "the local coordinate is negative-millions because the pose is the wrong one" (which
     * the delta reports).
     */
    public boolean explainsInvisibleComponent() {
        return drawnFarFromStructure();
    }

    /**
     * Whether the pose this check was made with is old enough that the drawing cannot be trusted to
     * follow the structure any more.
     *
     * <p>{@code false} when the age is unknown ({@code -1}): "we do not know" is not "it is stale", the
     * same rule {@link #drawnFarFromStructure} follows.
     */
    public boolean poseIsStale() {
        return poseAgeTicks >= 0 && poseAgeTicks > POSE_AGE_WARN_TICKS;
    }

    /** The age of the pose as seconds, or {@code -1} when it is unknown; for the log line. */
    public double poseAgeSeconds() {
        return poseAgeTicks < 0 ? -1.0 : poseAgeTicks / 20.0;
    }

    /**
     * The one line the acceptance procedure reads:
     * {@code drawCheck carrier=… local=… computedWorld=(x,y,z) structureWorld=(x,y,z) delta=… }.
     *
     * <p>Shaped like the log's other {@code key=value} lines so it can be grepped, and complete enough
     * to be judged without any other line: every input is printed, so a disagreement between the two
     * positions can be checked by hand against the coordinates the reader saw in game.
     *
     * <p>{@code poseAge=} was added after the author reported "the bearing turns and the component does
     * not turn with it" against a log in which every one of these lines said {@code delta=0.000 OK}. It
     * did, and it was right: the arithmetic was consistent, about a pose the client had been holding for
     * four seconds. A {@code delta} of zero says nothing about whether the pose is current, so the age is
     * printed beside it and a pose past {@link #POSE_AGE_WARN_TICKS} is named as the fault it is.
     */
    public String describe() {
        Double delta = deltaBlocks();
        StringBuilder sb = new StringBuilder("drawCheck carrier=");
        sb.append(carrier == null ? "-" : carrier.shortId());
        sb.append(" kind=").append(carrier == null ? "-"
                : (carrier.isContraption() ? "create" : "sable"));
        sb.append(" local=").append(localPos == null ? "-" : localPos.toShortString());
        sb.append(" computedWorld=").append(vector(drawWorld));
        sb.append(" structureWorld=").append(vector(structureWorld));
        sb.append(" delta=").append(delta == null ? "-" : fmt(delta));
        sb.append(" poseAge=").append(poseAgeText());
        if (delta == null) {
            sb.append(" (cannot compare: ")
                    .append(drawWorld == null ? "no pose" : "the structure did not report a position")
                    .append(')');
        } else if (drawnFarFromStructure()) {
            sb.append(" OFF-BY-MORE-THAN-").append(fmt(OFF_TOLERANCE_BLOCKS))
                    .append("-BLOCKS - the component is drawn, and drawn somewhere else");
        } else {
            sb.append(" OK-position (the component is drawn where the pose says the structure is -")
                    .append(" see poseAge for whether that pose is CURRENT)");
        }
        if (poseIsStale()) {
            sb.append(" *** STALE POSE: the server has not sent a new pose for ").append(poseAgeTicks)
                    .append(" tick(s) (").append(fmt(poseAgeSeconds())).append("s), so this frame is ")
                    .append("drawing the structure where it was THAT long ago - the delta above being 0 ")
                    .append("does not mean the component is following it ***");
        }
        if (plotSpaceLocal()) {
            sb.append(" plotSpaceLocal=true (|local| >= ").append(PLOT_SPACE_THRESHOLD)
                    .append(": this carrier's local space is a Sable-style plot space, not an "
                            + "anchor-relative one - the pose must be Sable's own logicalPose)");
        }
        return sb.toString();
    }

    /** {@code 7t} / {@code 0.35s}, or {@code ?} when the caller does not know the age. */
    private String poseAgeText() {
        if (poseAgeTicks < 0) {
            return "?";
        }
        return poseAgeTicks + "t/" + fmt(poseAgeSeconds()) + "s";
    }

    private static String vector(@Nullable Vector3d v) {
        return v == null ? "-" : String.format(java.util.Locale.ROOT, "(%.3f, %.3f, %.3f)",
                v.x, v.y, v.z);
    }

    private static String fmt(double value) {
        return String.format(java.util.Locale.ROOT, "%.3f", value);
    }
}

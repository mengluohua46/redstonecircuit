package com.jiangyuefengyu.redstonecircuit.carrier;

import net.minecraft.core.BlockPos;

import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

/**
 * <b>"Where we intend to draw, where the structure actually is, and how far apart those are."</b>
 *
 * <p>计数器分不清"画到了 plot 坐标（百万格）"和"组件根本没画"，前者按每个计数器都是完美的一帧；这里改成比较
 * 两个位置并只给出一个数 ({@link #deltaBlocks()})。{@code plotSpaceLocal} 是事实而不是故障：局部坐标本身就落在
 * Sable 的 plot 空间里。{@code null} 一律表示"调用方不知道"，{@code poseAgeTicks} 为 {@code -1} 表示未知。
 */
public record CarrierDrawCheck(@Nullable CarrierId carrier, @Nullable BlockPos localPos,
                               @Nullable Vector3d drawWorld, @Nullable Vector3d structureWorld,
                               long poseAgeTicks) {

    /** How old a pose may be before the draw check says so loudly, in ticks - twice the default heartbeat, so a
     * parked machine whose pose is exactly one heartbeat old is not reported as a fault, and a stale pose is. */
    public static final long POSE_AGE_WARN_TICKS = 40;

    /** How far a draw point may sit from the structure's position before the two are called inconsistent, in
     * blocks: a pose origin is a pivot, not a centre, so a couple of blocks is normal for any machine. */
    public static final double OFF_TOLERANCE_BLOCKS = 4.0;

    /** A local coordinate of this magnitude or more is somebody's plot space, not an anchor-relative one:
     * Create's are bounded by the machine's own size (tens of blocks), Sable's are around 2e7. */
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

    /** Whether the structure is being asked to draw a host nowhere near it. {@code false} when either position
     * is unknown: "we do not know" is not "it is wrong". */
    public boolean drawnFarFromStructure() {
        Double delta = deltaBlocks();
        return delta != null && delta > OFF_TOLERANCE_BLOCKS;
    }

    /** Whether this host's local coordinate is in plot-space magnitude rather than in the small anchor-relative
     * range. A fact, not a fault: Sable's {@code logicalPose} is defined on exactly this space. */
    public boolean plotSpaceLocal() {
        return plotSpace(localPos);
    }

    /** The same fact about a coordinate, for a caller that has the coordinate and not a check. One spelling of
     * it for both callers, so the diagnostic and the judgement cannot disagree about which hosts are Sable's. */
    public static boolean plotSpace(@Nullable BlockPos localPos) {
        if (localPos == null) {
            return false;
        }
        return Math.abs(localPos.getX()) >= PLOT_SPACE_THRESHOLD
                || Math.abs(localPos.getY()) >= PLOT_SPACE_THRESHOLD
                || Math.abs(localPos.getZ()) >= PLOT_SPACE_THRESHOLD;
    }

    /** Whether this check on its own <b>explains</b> a component that is not on screen: true only for a pose
     * that draws the host nowhere near the structure. Any other state means the fault is elsewhere. */
    public boolean explainsInvisibleComponent() {
        return drawnFarFromStructure();
    }

    /** Whether the pose this check was made with is too old to be trusted to follow the structure any more.
     * {@code false} when the age is unknown ({@code -1}), the same rule {@link #drawnFarFromStructure} follows. */
    public boolean poseIsStale() {
        return poseAgeTicks >= 0 && poseAgeTicks > POSE_AGE_WARN_TICKS;
    }

    /** The age of the pose as seconds, or {@code -1} when it is unknown; for the log line. */
    public double poseAgeSeconds() {
        return poseAgeTicks < 0 ? -1.0 : poseAgeTicks / 20.0;
    }

    /** The one line the draw check's diagnostics read, shaped like the log's other {@code key=value} lines so it
     * can be grepped. A {@code delta} of zero says nothing about whether the pose is current, hence {@code poseAge=}. */
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

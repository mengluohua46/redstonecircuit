package com.jiangyuefengyu.redstonecircuit.carrier;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;

import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3f;
import org.joml.Quaterniond;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * A structure's full rigid pose: {@code world = position + orientation * (local - pivot) * scale}, the
 * same map as Sable's {@code Pose3dc#transformPosition}. {@link #position()} is the <b>pivot</b>'s world
 * position and the pivot is a block's centre, so {@link #blockCentre} - not {@link #transformPosition} -
 * answers "where is this block"; {@link #orientation()} is the double quaternion the arithmetic uses and
 * {@link #orientationF()} the float one a {@code PoseStack} takes.
 */
public record CarrierPose(Vector3dc position, Quaterniond orientation, Vector3dc pivot, double scale) {

    /** The half block between a block's corner and its centre: the pivot of every structure so far. */
    public static final double BLOCK_CENTRE = 0.5;

    /** The orientation is within this of the identity and is treated as "not turned" for logging. */
    private static final double STILL_DEGREES = 0.05;

    /** A pose that changes by less than this is not worth a packet; see {@link #closeTo}. */
    private static final double POSITION_EPSILON = 0.002;

    /** How far the orientation may differ before the pose counts as new: {@code 1 - |dot|} of 1e-9,
     * about 0.005 degrees - below float and network precision, far above a parked body's jitter. */
    private static final double ORIENTATION_EPSILON = 1.0E-9;

    /** Same order of magnitude, for the uniform scale; a scale change is always worth a packet. */
    private static final double SCALE_EPSILON = 1.0E-9;

    // ------------------------------------------------------------- factories --

    /** A structure standing still with its pivot at {@code position}. */
    public static CarrierPose at(Vector3dc position) {
        return new CarrierPose(position, new Quaterniond(), blockCentrePivot(), 1.0);
    }

    /** A structure whose pivot is at {@code position}, turned by {@code orientation}. */
    public static CarrierPose of(Vector3dc position, Quaterniond orientation) {
        return new CarrierPose(position, orientation, blockCentrePivot(), 1.0);
    }

    /** Where the structure is between two ticks, for a frame drawn at partial tick {@code t}: position,
     * pivot and scale are linear, the orientation is slerped, and {@code t <= 0} / {@code t >= 1} return
     * the arguments themselves so every non-render caller keeps whole-pose behaviour. */
    public static CarrierPose lerp(double t, @Nullable CarrierPose from, @Nullable CarrierPose to) {
        if (to == null) {
            return null;
        }
        if (from == null || t >= 1.0) {
            return to;
        }
        if (t <= 0.0) {
            return from;
        }
        Vector3d position = new Vector3d(from.position).lerp(to.position, t);
        Vector3d pivot = new Vector3d(from.pivot).lerp(to.pivot, t);
        double scale = from.scale + (to.scale - from.scale) * t;
        Quaterniond a = new Quaterniond(from.orientation);
        Quaterniond b = new Quaterniond(to.orientation);
        if (a.dot(b) < 0.0) {
            // The short way round: -q is the same rotation as q, and the long way spins most of a turn.
            b = new Quaterniond(-b.x, -b.y, -b.z, -b.w);
        }
        return new CarrierPose(position, a.slerp(b, t), pivot, scale);
    }

    /** The identity pose at the origin - the client's "no pose yet" default: local coordinates are left
     * as they are, so such a host is drawn visibly wrong rather than invisibly. */
    public static CarrierPose identity() {
        return new CarrierPose(new Vector3d(), new Quaterniond(), blockCentrePivot(), 1.0);
    }

    private static Vector3d blockCentrePivot() {
        return new Vector3d(BLOCK_CENTRE, BLOCK_CENTRE, BLOCK_CENTRE);
    }

    // --------------------------------------------------------------- queries --

    /** One local point in world coordinates. Done as a matrix rather than three vector operations
     * because JOML's {@code Quaterniond.transform} writes through {@code dest} as well as reading it,
     * so one object passed for both quietly cancels the rotation. */
    public Vector3d transformPosition(Vector3dc local, Vector3d out) {
        transformMatrix().transformPosition(local, out);
        return out;
    }

    /** The full local-to-world transform. The order of the last two calls is load bearing: the scale
     * must come <em>before</em> the pivot translation, with the pivot negated and scaled inside it, so
     * that {@code S * local - S * pivot = S * (local - pivot)}. */
    public org.joml.Matrix4d transformMatrix() {
        return new org.joml.Matrix4d()
                .translation(position)
                .rotate(orientation)
                .translate(-pivot.x() * scale, -pivot.y() * scale, -pivot.z() * scale)
                .scale(scale);
    }

    /** The inverse of {@link #transformPosition}: a world point back in the structure's own space. */
    public Vector3d transformPositionInverse(Vector3dc world, Vector3d out) {
        transformMatrix().invert().transformPosition(world, out);
        return out;
    }

    /** The centre of the block at local coordinate {@code localBlock}, in world coordinates: a block's
     * coordinate names its corner, so this is the method every caller wants. */
    public Vector3d blockCentre(Vector3dc localBlock, Vector3d out) {
        Vector3d centre = new Vector3d(localBlock);
        centre.add(BLOCK_CENTRE, BLOCK_CENTRE, BLOCK_CENTRE);
        return transformPosition(centre, out);
    }

    /** The same, allocating its own output: for the diagnostics and the draw check, not the hot paths. */
    public static Vector3d blockCentreOf(CarrierPose pose, net.minecraft.core.BlockPos localBlock) {
        return pose.blockCentre(new Vector3d(localBlock.getX(), localBlock.getY(), localBlock.getZ()),
                new Vector3d());
    }

    /** How far the orientation is from "not turned", in degrees, for the logs. */
    public double rotationDegrees() {
        double w = Math.min(1.0, Math.abs(orientation.w()));
        return Math.toDegrees(2.0 * Math.acos(w));
    }

    /** True when the structure is currently not turned at all. */
    public boolean isStill() {
        return rotationDegrees() < STILL_DEGREES;
    }

    /** The orientation as the {@code float} quaternion a {@code PoseStack} multiplies by. */
    public Quaternionf orientationF() {
        return new Quaternionf((float) orientation.x, (float) orientation.y, (float) orientation.z,
                (float) orientation.w);
    }

    /** The rotation this pose applies, as a 3x3 matrix. */
    public Matrix3f rotationMatrix() {
        return new Matrix3f().rotation(orientationF());
    }

    /** Whether re-sending this pose would carry no information. Position, pivot and orientation are
     * compared separately, and a change in orientation is a change however small. */
    public boolean closeTo(@Nullable CarrierPose other) {
        if (other == null) {
            // Nothing was ever sent, so anything is worth sending.
            return false;
        }
        if (Math.abs(scale - other.scale) > SCALE_EPSILON) {
            return false;
        }
        if (position.distanceSquared(other.position) > POSITION_EPSILON * POSITION_EPSILON) {
            return false;
        }
        if (pivot.distanceSquared(other.pivot) > POSITION_EPSILON * POSITION_EPSILON) {
            return false;
        }
        return sameOrientation(other);
    }

    /** How far the orientation is from {@code other}, as {@code 1 - |dot|}: 0 is identical. */
    public double orientationDistanceTo(CarrierPose other) {
        double dot = Math.abs(orientation.x * other.orientation.x
                + orientation.y * other.orientation.y
                + orientation.z * other.orientation.z
                + orientation.w * other.orientation.w);
        return 1.0 - Math.min(1.0, dot);
    }

    /** Whether this pose has the same orientation as {@code other}, to within rounding. Public and
     * separate from {@link #closeTo} so a test can assert the orientation half on its own. */
    public boolean sameOrientation(CarrierPose other) {
        return orientationDistanceTo(other) <= ORIENTATION_EPSILON;
    }

    /** The angle between this pose's orientation and {@code other}'s, in degrees. */
    public double orientationDeltaDegrees(CarrierPose other) {
        return Math.toDegrees(2.0 * Math.acos(Math.min(1.0, 1.0 - orientationDistanceTo(other))));
    }

    // --------------------------------------------------- pose from a transform --

    /** The rotation three basis vectors name, or {@code null} when they are not one. Built from a
     * matrix because a contraption answers {@code applyRotation} and nothing else; the columns are
     * normalised first, so only their directions matter. */
    @Nullable
    public static Quaterniond fromBasisColumns(Vector3dc xColumn, Vector3dc yColumn,
                                               Vector3dc zColumn) {
        Vector3d x = new Vector3d(xColumn);
        Vector3d y = new Vector3d(yColumn);
        Vector3d z = new Vector3d(zColumn);
        if (x.lengthSquared() < 1.0E-12 || y.lengthSquared() < 1.0E-12 || z.lengthSquared() < 1.0E-12) {
            return null;
        }
        x.normalize();
        y.normalize();
        z.normalize();

        // Three mutually perpendicular unit vectors, which is what a rigid rotation has to produce.
        if (Math.abs(x.dot(y)) > 1.0E-4 || Math.abs(x.dot(z)) > 1.0E-4
                || Math.abs(y.dot(z)) > 1.0E-4) {
            return null;
        }

        Matrix3f matrix = new Matrix3f();
        rowMajor(matrix, x, y, z);
        if (!matrix.isFinite()) {
            return null;
        }
        float determinant = matrix.determinant();
        if (determinant < 0.0F) {
            // A left-handed frame is a reflection, not a rotation.
            return null;
        }
        if (determinant < 0.5F) {
            // Collapsed: the axes are mutually perpendicular but not all there.
            return null;
        }
        return quaternionOf(matrix);
    }

    /** The rotation an orthogonal 3x3 matrix describes, written out rather than delegated to JOML -
     * its {@code getNormalizedRotation} reads rows, and this class's convention is columns. */
    private static Quaterniond quaternionOf(Matrix3f m) {
        double m00 = m.m00;
        double m11 = m.m11;
        double m22 = m.m22;
        double trace = m00 + m11 + m22;
        double x;
        double y;
        double z;
        double w;
        if (trace > 0.0) {
            double s = Math.sqrt(trace + 1.0) * 2.0;
            w = 0.25 * s;
            x = (m.m21 - m.m12) / s;
            y = (m.m02 - m.m20) / s;
            z = (m.m10 - m.m01) / s;
        } else if (m00 > m11 && m00 > m22) {
            double s = Math.sqrt(1.0 + m00 - m11 - m22) * 2.0;
            w = (m.m21 - m.m12) / s;
            x = 0.25 * s;
            y = (m.m01 + m.m10) / s;
            z = (m.m02 + m.m20) / s;
        } else if (m11 > m22) {
            double s = Math.sqrt(1.0 + m11 - m00 - m22) * 2.0;
            w = (m.m02 - m.m20) / s;
            x = (m.m01 + m.m10) / s;
            y = 0.25 * s;
            z = (m.m12 + m.m21) / s;
        } else {
            double s = Math.sqrt(1.0 + m22 - m00 - m11) * 2.0;
            w = (m.m10 - m.m01) / s;
            x = (m.m02 + m.m20) / s;
            y = (m.m12 + m.m21) / s;
            z = 0.25 * s;
        }
        Quaterniond rotation = new Quaterniond(x, y, z, w);
        if (!rotation.isFinite() || rotation.lengthSquared() < 1.0E-12) {
            return null;
        }
        return rotation.normalize();
    }

    /** Sets a matrix from three <b>column</b> vectors, one element per source vector per row: the
     * {@code Matrix3f} constructor takes <b>row</b> order, so three columns fed into it build the
     * transpose instead - a plausible-looking rotation whose inverse turns the other way. */
    private static void rowMajor(Matrix3f matrix, Vector3d x, Vector3d y, Vector3d z) {
        matrix.m00 = (float) x.x();
        matrix.m01 = (float) y.x();
        matrix.m02 = (float) z.x();
        matrix.m10 = (float) x.y();
        matrix.m11 = (float) y.y();
        matrix.m12 = (float) z.y();
        matrix.m20 = (float) x.z();
        matrix.m21 = (float) y.z();
        matrix.m22 = (float) z.z();
    }

    /** The pose a Create contraption's own transform describes: the pivot is a block's centre and the
     * origin is the anchor plus that half block. {@code null} when the basis is not a rotation. */
    @Nullable
    public static CarrierPose fromContraptionBasis(Vector3dc anchor, Vector3dc xColumn,
                                                   Vector3dc yColumn, Vector3dc zColumn) {
        Quaterniond orientation = fromBasisColumns(xColumn, yColumn, zColumn);
        if (orientation == null) {
            return null;
        }
        return of(new Vector3d(anchor).add(BLOCK_CENTRE, BLOCK_CENTRE, BLOCK_CENTRE), orientation);
    }

    // ------------------------------------------------------------------ NBT --

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putDouble("px", position.x());
        tag.putDouble("py", position.y());
        tag.putDouble("pz", position.z());
        tag.putDouble("qx", orientation.x);
        tag.putDouble("qy", orientation.y);
        tag.putDouble("qz", orientation.z);
        tag.putDouble("qw", orientation.w);
        tag.putDouble("rx", pivot.x());
        tag.putDouble("ry", pivot.y());
        tag.putDouble("rz", pivot.z());
        tag.putDouble("scale", scale);
        return tag;
    }

    /** Reads one back, or {@code null} when the tag is not a pose (or holds a non-finite number). */
    @Nullable
    public static CarrierPose load(@Nullable CompoundTag tag) {
        if (tag == null || !tag.contains("qw")) {
            return null;
        }
        Quaterniond orientation = new Quaterniond(
                tag.getDouble("qx"), tag.getDouble("qy"), tag.getDouble("qz"), tag.getDouble("qw"));
        if (!orientation.isFinite() || orientation.lengthSquared() < 1.0E-9) {
            // A zero or NaN quaternion has no rotation to apply, so the pose counts as absent.
            return null;
        }
        orientation.normalize();
        double scale = tag.contains("scale") ? tag.getDouble("scale") : 1.0;
        if (!Double.isFinite(scale) || scale == 0.0) {
            scale = 1.0;
        }
        Vector3d position = new Vector3d(tag.getDouble("px"), tag.getDouble("py"), tag.getDouble("pz"));
        if (!position.isFinite()) {
            return null;
        }
        Vector3d pivot = tag.contains("rx")
                ? new Vector3d(tag.getDouble("rx"), tag.getDouble("ry"), tag.getDouble("rz"))
                : blockCentrePivot();
        if (!pivot.isFinite()) {
            pivot = blockCentrePivot();
        }
        return new CarrierPose(position, orientation, pivot, scale);
    }

    /** A short, stable text form for the diagnostics: "pos=(...) rot=12.3deg q=(...)". */
    public String describe() {
        return "pos=" + vector(position)
                + " rot=" + Mth.floor(rotationDegrees() * 1000.0) / 1000.0 + "deg"
                + " pivot=" + vector(pivot)
                + (scale == 1.0 ? "" : " scale=" + scale)
                + " q=" + quaternion(orientation);
    }

    private static String vector(Vector3dc vector) {
        if (vector == null) {
            return "null";
        }
        return String.format(java.util.Locale.ROOT, "(%.3f, %.3f, %.3f)",
                vector.x(), vector.y(), vector.z());
    }

    private static String quaternion(Quaterniond q) {
        return String.format(java.util.Locale.ROOT, "(%.4f, %.4f, %.4f, %.4f)", q.x, q.y, q.z, q.w);
    }
}

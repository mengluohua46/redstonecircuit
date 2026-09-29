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
 * Where a moving structure is, and how it is turned: a <b>full rigid pose</b>, not a right angle.
 *
 * <p>The P1 diagnostic manual settled that by measurement: a real contraption came back with
 * {@code rotation = 9.500deg}, so nothing here may assume the structure only ever faces one of four
 * directions. A pose is therefore a translation plus a quaternion, applied about a pivot:
 *
 * <pre>
 *   world = position + orientation * (local - pivot) * scale
 * </pre>
 *
 * <p>That is exactly Sable's {@code Pose3dc#transformPosition}, which is what lets these numbers be
 * checked against Sable's own round trip instead of against a convention invented here.
 *
 * <h2>The half block, which is the one thing worth being careful about</h2>
 * {@link #position()} is where the <b>pivot</b> is, not where the structure's corner is. A block's
 * local coordinate names its corner, so its centre is half a block further along; for a Create
 * contraption the pivot is a block's centre, and Create says so in its own transform:
 *
 * <pre>
 *   toGlobalVector(v) = applyRotation(v - 0.5) + 0.5 + getAnchorVec()
 * </pre>
 *
 * <p>Matching that against the contract above gives {@code pivot = (0.5, 0.5, 0.5)} and
 * {@code position = anchorVec + 0.5}. Getting the half block wrong moves every carried component half a
 * block sideways - small enough to read as a rendering wobble, large enough to be wrong - so
 * {@link #fromContraptionBasis} applies the shift itself, and {@link #blockCentre} is the method every
 * caller uses when it means "where is this block":
 *
 * <pre>
 *   blockCentre(local, out) === transformPosition(local + 0.5, out)
 * </pre>
 *
 * <h2>Two copies of the orientation on purpose</h2>
 * {@link #orientation()} is a {@code double} quaternion because that is what the local-to-world
 * arithmetic (and Sable's poses) work in; {@link #orientationF()} is a {@code float} one because that
 * is what a {@code PoseStack} takes. Keeping one and converting at the call site would put a precision
 * decision inside the renderer.
 *
 * @param position where the structure's pivot sits in the world
 * @param orientation how the structure is turned
 * @param pivot the local point the orientation turns about, in the structure's own coordinates
 * @param scale uniform scale, 1.0 for every structure seen so far
 */
public record CarrierPose(Vector3dc position, Quaterniond orientation, Vector3dc pivot, double scale) {

    /** The half block between a block's corner and its centre: the pivot of every structure so far. */
    public static final double BLOCK_CENTRE = 0.5;

    /** The orientation is within this of the identity and is treated as "not turned" for logging. */
    private static final double STILL_DEGREES = 0.05;

    /** A pose that changes by less than this is not worth a packet; see {@link #closeTo}. */
    private static final double POSITION_EPSILON = 0.002;

    /**
     * How much the <b>orientation</b> may differ before the pose counts as new.
     *
     * <p>{@code 1 - |dot(q1, q2)|} is the quantity compared, and for unit quaternions near each other it
     * is {@code 1 - cos(theta / 2)} - so {@code 1e-6} was a <b>0.16 degree</b> tolerance rather than the
     * "rounding" it read as. That is not a rounding tolerance at all: it is a sixteenth of a degree of
     * real rotation, silently swallowed.
     *
     * <p>It was tolerable while a pose was compared in order to decide whether to re-send a
     * <em>position</em>. It is exactly wrong for the case that matters: a bearing turning on the spot
     * keeps its pivot fixed and changes only its angle, so a gate that treated a sixteenth of a degree
     * as "the same" would keep answering "nothing changed" while the structure visibly turned.
     *
     * <p>The value here is a genuine rounding tolerance - {@code 1e-9} is about <b>0.005 degrees</b>,
     * which is below the precision a {@code PoseStack} and a quaternion round trip through the network
     * can even express, and three orders of magnitude below the old one. Jitter from a parked physics
     * body or a stalled bearing is far below it ({@code |dot|} stays within a few times
     * {@code 1e-12} of 1.0), so a machine that is genuinely still still sends nothing.
     */
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

    /**
     * <b>Where the structure is <em>between</em> two ticks: the pose a frame should be drawn at (P1.55).</b>
     *
     * <h2>The defect this exists for</h2>
     * The author, standing next to a moving machine: "the components inside the block render ahead of the
     * path; the faster it moves the bigger the gap, the slower the closer it gets" (with
     * {@code seeInnerComponentsThroughWalls = false}, and the machine itself drawn correctly).
     *
     * <p>That is exactly a one-tick lead, and it is what the two renderers were doing: Sable draws the
     * structure <em>interpolated</em> - between the last two ticks, at the frame's partial tick - while
     * this mod drew the components at the <b>latest whole pose</b> it had been sent. At 20 tps and 60 fps
     * the components therefore sat up to a full tick's travel in front of the machine they are inside,
     * which is proportional to speed and vanishes when the machine stops. Nothing was wrong with the
     * visibility rule or the coordinates; the two halves of one picture were drawn at two different times.
     *
     * <h2>What interpolates, and what deliberately does not</h2>
     * Position, pivot and scale are linear in the tick; the orientation is a <b>slerp</b>, and along the
     * short way round ({@code q} and {@code -q} are the same rotation, and slerping the long way would
     * spin a turning machine most of a turn between two ticks - JOML's own slerp does not flip the sign, so
     * this does).
     *
     * <p>{@code t <= 0} and {@code t >= 1} return the arguments <b>themselves</b>, so a caller that has no
     * partial tick to give (every non-render caller: clicks, the solve, the tests) keeps today's behaviour
     * to the bit. Only the render path passes a real partial tick; see {@code CarrierIndex#drawablesNear}.
     *
     * @param t the frame's partial tick, 0.0 = {@code from}, 1.0 = {@code to}
     * @param from the pose of the previous tick, or {@code null} when there is none (a snapshot, a
     *     landing, the first pose after a teleport) - in which case there is nothing to interpolate from
     *     and {@code to} is the answer
     * @param to the pose of the current tick, or {@code null} when the carrier has gone
     */
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
            // The short way round: -q is the same rotation as q, and the long way is a machine spinning
            // most of a turn in one tick. (JOML's own slerp does not flip the sign.)
            b = new Quaterniond(-b.x, -b.y, -b.z, -b.w);
        }
        return new CarrierPose(position, a.slerp(b, t), pivot, scale);
    }

    /**
     * The identity pose at the origin.
     *
     * <p>Used as the "this carrier has no pose yet" default on the client: applying it leaves the local
     * coordinates exactly as they are, so a carried host whose carrier pose has not arrived is drawn
     * where its local coordinate says - visible and obviously wrong rather than invisible.
     */
    public static CarrierPose identity() {
        return new CarrierPose(new Vector3d(), new Quaterniond(), blockCentrePivot(), 1.0);
    }

    private static Vector3d blockCentrePivot() {
        return new Vector3d(BLOCK_CENTRE, BLOCK_CENTRE, BLOCK_CENTRE);
    }

    // --------------------------------------------------------------- queries --

    /**
     * One local point, in world coordinates: {@code position + orientation * (local - pivot) * scale}.
     *
     * <p><b>Why this is a matrix and not three vector operations.</b> JOML's
     * {@code Quaterniond.transform(src, dest)} uses {@code dest} as scratch space as well as writing
     * the answer there, so calling it with the same object for both arguments silently cancels the
     * rotation and returns the input unchanged. It is an easy mistake to make here - the intermediate
     * vector is exactly the thing you want to rotate - and it is invisible in any test whose rotation
     * happens to be the identity, so it survived a whole round of development on this class. Building
     * the transform once and applying it to a separate output vector has no such aliasing to get
     * wrong, and it is also the shape the renderer uses, so the two cannot drift apart.
     *
     * <p>The matrix is written out by hand rather than through {@code Matrix4d.rotate(quaternion)} to
     * keep the exact contract above readable in one place.
     */
    public Vector3d transformPosition(Vector3dc local, Vector3d out) {
        transformMatrix().transformPosition(local, out);
        return out;
    }

    /**
     * The full local-to-world transform, in the same order the renderer applies it:
     * move the pivot to the origin, turn, then move the whole thing to where the structure is.
     *
     * <p><b>The order of the last two calls is load bearing, and it is the opposite of what it looks
     * like.</b> The contract above is {@code world = position + R * (S * (local - pivot))} - the scale
     * applies to the structure's own coordinates, and the pivot is subtracted in those coordinates. The
     * naive chain {@code …rotate().translate(-pivot).scale(scale)}, read left to right, applies the
     * scale <em>after</em> the pivot has been taken out, which scales the local coordinates and then
     * subtracts an unscaled pivot: for {@code pivot = (0.5, 0.5, 0.5)} and {@code scale = 1} those
     * differ by exactly the half block, which is small enough to look like a rendering wobble and large
     * enough to be wrong. Writing {@code scale} before the pivot translation and negating the pivot
     * inside it gives {@code S * local - S * pivot = S * (local - pivot)}, which is the contract.
     *
     * <p>It is spelled this way rather than by hand-multiplying the four matrices so that
     * {@link #transformPosition} and this method are provably the same map: the Sable-side claim checks
     * a pose by transforming one way and inverting the other, and two conventions inside one class is
     * how a claim round-trips in its own verification and lands somewhere else in the world.
     */
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

    /**
     * The centre of the block at local coordinate {@code localBlock}, in world coordinates.
     *
     * <p>The method every caller should reach for: a block's coordinate names its corner, so
     * {@code localBlock} alone is half a block away from where the block actually is.
     */
    public Vector3d blockCentre(Vector3dc localBlock, Vector3d out) {
        Vector3d centre = new Vector3d(localBlock);
        centre.add(BLOCK_CENTRE, BLOCK_CENTRE, BLOCK_CENTRE);
        return transformPosition(centre, out);
    }

    /**
     * The same, allocating its own output.
     *
     * <p>A convenience for the diagnostics and the draw check, which run once per carrier per command
     * or once per frame rather than in a loop over a structure's blocks - the hot paths all use the
     * two-argument form and reuse a scratch vector.
     */
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

    /**
     * Whether this pose is close enough to {@code other} that sending it again would be wasted traffic.
     *
     * <p>A contraption moves every tick, and a pose packet per tick per carrier is the difference
     * between a feature and a bandwidth complaint. Two poses count as the same when the pivot has not
     * moved more than a millimetre or two <b>and the rotation has not changed either</b> - which for a
     * structure that is genuinely still (a stalled bearing, a paused physics body) is <em>always</em>, so
     * a parked machine sends nothing at all.
     *
     * <h2>The orientation is compared, not assumed to follow the position</h2>
     * The defect this method used to carry: the orientation test was written with a tolerance that
     * happens to be a sixteenth of a degree (see {@link #ORIENTATION_EPSILON}), so a slow turn could
     * round to "the same pose" for up to a second of real rotation. Combined with a caller that sends
     * only when this answers {@code false}, the client then draws the structure at an angle the server
     * stopped believing in seconds ago - which is exactly the author's report <em>"the bearing turns and
     * the component does not turn with it"</em>.
     *
     * <p>Stated as a rule rather than as a threshold: <b>a change in orientation is a change, however
     * small.</b> A rotation that a player can act on can never be inside a "same pose" tolerance, and a
     * jitter that cannot be seen is far below the one used here. The three fields are compared
     * separately - position, pivot, orientation - so a diagnostic can tell which of them moved.
     *
     * @param other the pose last sent, or {@code null} when nothing has been sent yet
     * @return true when re-sending would carry no information
     */
    public boolean closeTo(@Nullable CarrierPose other) {
        if (other == null) {
            // Nothing was ever sent, so anything is worth sending. A {@code null} here is the ordinary
            // state of a carrier that has just been claimed.
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

    /**
     * Whether this pose has the same orientation as {@code other}, to within rounding.
     *
     * <p>Public and separate from {@link #closeTo} because it is the one half of the comparison the
     * "the component does not turn with the structure" defect lived in: a unit test can now assert
     * "same position, turned a degree, not close" without also asserting the position half.
     */
    public boolean sameOrientation(CarrierPose other) {
        return orientationDistanceTo(other) <= ORIENTATION_EPSILON;
    }

    /** The angle between this pose's orientation and {@code other}'s, in degrees. */
    public double orientationDeltaDegrees(CarrierPose other) {
        return Math.toDegrees(2.0 * Math.acos(Math.min(1.0, 1.0 - orientationDistanceTo(other))));
    }

    // --------------------------------------------------- pose from a transform --

    /**
     * The rotation described by the three vectors a structure maps the world axes onto.
     *
     * <p>Deliberately built from a rotation matrix rather than from three angles: the source of these
     * vectors is a black box - a Create contraption answers {@code applyRotation(vec)} and nothing else
     * - and a rotation matrix is exactly what such a box can answer without telling us its convention.
     * That also makes it robust to the convention changing, which is not hypothetical: reading three
     * basis vectors is what makes Create's internal idea of "pitch, yaw, roll" irrelevant here.
     *
     * <p>The columns are normalised before the quaternion is extracted. That is not cosmetic: the
     * vectors arrive as the images of whatever vectors were asked about, so a caller that asked about
     * a half-diagonal would otherwise hand in three too-short vectors and get either a refusal or a
     * wrong rotation. Normalising makes the answer depend only on the <em>directions</em>, which are
     * the only thing a rotation is made of.
     *
     * @param xColumn where the structure maps {@code (1, 0, 0)}
     * @param yColumn where it maps {@code (0, 1, 0)}
     * @param zColumn where it maps {@code (0, 0, 1)}
     * @return the orientation, or {@code null} when the three vectors do not describe a rotation at all
     */
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
            // A left-handed frame: a reflection, not a rotation. Refusing is better than handing back
            // a pose whose geometry is mirrored.
            return null;
        }
        if (determinant < 0.5F) {
            // Collapsed: the axes are mutually perpendicular but not all there.
            return null;
        }
        return quaternionOf(matrix);
    }

    /**
     * The rotation an orthogonal 3x3 matrix describes, written out rather than delegated to JOML.
     *
     * <p>JOML's own {@code getNormalizedRotation} reads a matrix whose <em>rows</em> are the images of
     * the basis vectors, which is the transpose of the convention used everywhere else in this class
     * and in the renderer. Handing it a matrix built from columns therefore yields the conjugate
     * rotation - a perfectly valid-looking quaternion that turns everything the wrong way, and whose
     * only symptom is geometry that is subtly mirrored. Doing the arithmetic here, with the
     * {@code R * v} convention stated once, removes the ambiguity; the branch on the largest diagonal
     * element is the standard way to keep it numerically well behaved near a quarter turn.
     */
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

    /**
     * Sets a matrix from three <b>column</b> vectors.
     *
     * <p>{@code new Matrix3f(m00, m01, ...)} takes its arguments in <b>row</b> order, so filling one
     * from three columns by passing {@code (x.x, y.x, z.x, x.y, ...)} builds the transpose - which is
     * still a perfectly plausible-looking rotation matrix, and whose inverse is a different rotation.
     * That mistake cost a round of work on this class: the recovered orientation was the conjugate of
     * the real one, so every carried component would have been drawn rotated the wrong way, and the
     * only symptom was a test asserting two matrices that "should" be equal.
     *
     * <p>Written out row by row, one element per source vector per row, there is nothing left to
     * transpose by accident.
     */
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

    /**
     * The pose a Create contraption's own transform describes.
     *
     * <p>The pivot is a block's centre and the origin is the anchor shifted by half a block; see the
     * class doc for where those two come from. {@code null} when the basis is not a rotation.
     *
     * @param anchor the contraption's anchor vector, i.e. where its local origin sits
     * @param xColumn where the contraption maps {@code (1, 0, 0)}; see {@link #fromBasisColumns}
     * @param yColumn where it maps {@code (0, 1, 0)}
     * @param zColumn where it maps {@code (0, 0, 1)}
     */
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
            // A zero or NaN quaternion has no rotation to apply; treat the whole pose as absent rather
            // than sending geometry to the NaN dimension.
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

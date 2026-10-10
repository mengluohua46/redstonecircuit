package com.jiangyuefengyu.redstonecircuit.carrier;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import org.jetbrains.annotations.Nullable;

/**
 * One host block inside a moving structure, expressed so that every number handed to the pose stack is small - and so
 * that the pose stack's composition order is the contract's order, not its reverse.
 *
 * <p>A {@code PoseStack} right-multiplies, so {@code translate(-pivot); mulPose(rotation); translate(position)} applies
 * as {@code R·p + R·position − pivot}, while the contract {@link CarrierPose#transformMatrix()} states, and every other
 * caller implements, {@code position + R·(p − pivot)}; the two agree only when {@code R} is the identity. {@code
 * T(-pivot)} is also a two-million-block translation performed in {@code float}, where the spacing between
 * representable values at that magnitude is 2, so a one-block shell collapses to a point.
 *
 * <p>Splitting the local coordinate {@code p} into the host block's corner {@code local} and a point {@code v} inside
 * it gives {@code world = position + R·S·(local − pivot) + R·S·v}: the pose stack gets exactly
 * {@code T(position)·R·S}, and the {@code (local − pivot)} term is a per-host offset added to the geometry, computed in
 * {@code double} where the two plot-scale numbers cancel exactly. One frame per host goes to both draw paths, so the
 * shell and the component inside the block are measured from the same place. Pure: no level, no client class, no
 * {@code PoseStack}.
 */
public record CarrierFrame(Vector3d position, Vector3d pivot, @Nullable Quaternionf rotation,
                           float scale, double localX, double localY, double localZ) {

    /**
     * The frame for one block of one structure. {@code localBlock} is the block's coordinate in the structure's own
     * space; a {@code BlockPos} is exact, so a Create contraption's {@code (0,0,0)} produces offsets of exactly
     * {@code (-0.5, -0.5, -0.5)} and every number the shell uses is small.
     */
    public static CarrierFrame forBlock(CarrierPose pose, Vector3dc localBlock) {
        Quaternionf q = pose.orientationF();
        boolean turned = q.x != 0F || q.y != 0F || q.z != 0F;
        return new CarrierFrame(new Vector3d(pose.position()), new Vector3d(pose.pivot()),
                turned ? q : null, (float) pose.scale(),
                localBlock.x(), localBlock.y(), localBlock.z());
    }

    /**
     * The double-precision truth: {@code position + R · S · (p − pivot)}. This is the answer every rendered vertex is
     * checked against, and it is the same expression {@link CarrierPose#transformPosition} evaluates -
     * {@code worldOf(p)} equals {@code pose.transformPosition(p)} for the pose this frame was built from, to the last
     * bit, which a unit test asserts.
     */
    public Vector3d worldOf(double px, double py, double pz, Vector3d out) {
        double x = px - pivot.x;
        double y = py - pivot.y;
        double z = pz - pivot.z;
        if (rotation != null) {
            double nx = rotation.x;
            double ny = rotation.y;
            double nz = rotation.z;
            double nw = rotation.w;
            // The standard quaternion sandwich, written out: R·v for a unit quaternion.
            double tx = 2.0 * (ny * z - nz * y);
            double ty = 2.0 * (nz * x - nx * z);
            double tz = 2.0 * (nx * y - ny * x);
            x += nw * tx + ny * tz - nz * ty;
            y += nw * ty + nz * tx - nx * tz;
            z += nw * tz + nx * ty - ny * tx;
        }
        if (scale != 1.0F) {
            x *= scale;
            y *= scale;
            z *= scale;
        }
        return out.set(position.x + x, position.y + y, position.z + z);
    }

    /** The world centre of the host block, in {@code double}; what the light query and the log use. */
    public Vector3d blockCentre(Vector3d out) {
        return worldOf(localX + CarrierPose.BLOCK_CENTRE, localY + CarrierPose.BLOCK_CENTRE,
                localZ + CarrierPose.BLOCK_CENTRE, out);
    }

    /**
     * The offset the geometry of this host is emitted at, in the pose stack's own space: {@code p − pivot}. Small by
     * construction - a difference of two numbers in the same space, one of which is the structure's own pivot - and the
     * only place the local coordinate appears, as a difference taken in {@code double} before anything is narrowed to
     * {@code float}.
     *
     * <p>Not scaled here even when the pose has a scale: the pose stack is given {@code S} itself (see
     * {@link #frameMatrix()}), so scaling the offset as well would apply it twice.
     */
    public float[] emittedOffset(double px, double py, double pz, float[] out) {
        out[0] = (float) (px - pivot.x);
        out[1] = (float) (py - pivot.y);
        out[2] = (float) (pz - pivot.z);
        return out;
    }

    /** The same for the host block's own corner: what the shell's unit cube is measured from. */
    public float[] cornerOffset(float[] out) {
        return emittedOffset(localX, localY, localZ, out);
    }

    /**
     * The matrix the pose stack is given, as the contract's order states it: {@code T(position) · R · S} - and
     * <b>without</b> the pivot translation, which lives in the emitted offsets instead.
     *
     * <p>Provided as a value rather than as three calls so that a unit test can compare it, element by element, with
     * what a real {@code PoseStack} produces for the same operations: a wrong composition order is invisible on screen
     * for exactly one frame per turn, so a test that compares matrices is the only kind that can hold it.
     */
    public Matrix4f frameMatrix() {
        Matrix4f matrix = new Matrix4f().translation((float) position.x, (float) position.y,
                (float) position.z);
        if (rotation != null) {
            matrix.rotate(rotation);
        }
        if (scale != 1.0F) {
            matrix.scale(scale, scale, scale);
        }
        return matrix;
    }

    /** One line for the diagnostics: where this host is, and how it is turned. */
    public String describe() {
        return String.format(java.util.Locale.ROOT,
                "frame position=(%.3f, %.3f, %.3f) pivot=(%.3f, %.3f, %.3f) local=(%.1f, %.1f, %.1f) "
                        + "turned=%s scale=%.3f",
                position.x, position.y, position.z, pivot.x, pivot.y, pivot.z, localX, localY, localZ,
                rotation == null ? "no" : "yes", scale);
    }
}

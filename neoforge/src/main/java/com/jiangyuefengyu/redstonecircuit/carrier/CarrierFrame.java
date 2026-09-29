package com.jiangyuefengyu.redstonecircuit.carrier;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import org.jetbrains.annotations.Nullable;

/**
 * <b>One host block inside a moving structure, expressed so that every number handed to the pose stack
 * is small - and so that the pose stack's composition order is the contract's order, not its reverse.</b>
 *
 * <h2>Defect one: the order</h2>
 * The old carried path built its matrix as
 * <pre>
 *   pose.translate(-pivot); pose.mulPose(rotation); pose.translate(position);
 * </pre>
 * and a {@code PoseStack} right-multiplies, so that is
 * {@code T(-cam) · T(-pivot) · R · T(position)}. Applied to a point {@code p} it gives
 * <b>{@code R·p + R·position − pivot}</b>, while the contract {@link CarrierPose#transformMatrix()}
 * states - and every other caller implements - is <b>{@code position + R·(p − pivot)}</b>. The two agree
 * only when {@code R} is the identity, which is why the author's machine was visible for exactly one
 * frame of each turn ("转到正着的那一帧，边框和元件会闪一下") and invisible otherwise:
 * <ul>
 *   <li><b>Create</b>, {@code position ≈ (2775.5, 77.5, -1394.5)}: at 30 degrees the component is
 *       carried to {@code R·position ≈ (2404, 1379, -697)} - several hundred blocks away.</li>
 *   <li><b>Sable</b>, {@code p = local ≈ 2.05e7}: the huge local vector itself is rotated, so the
 *       component lands millions of blocks away.</li>
 * </ul>
 *
 * <h2>Defect two: the magnitude</h2>
 * {@code T(-pivot)} is a two-million-block translation performed in {@code float}, where the spacing
 * between representable values at that magnitude is 2. {@code 20481032.0f + 0.5f} is
 * {@code 20481032.0f}, so a one-block shell collapses to a point.
 *
 * <h2>The form that fixes both</h2>
 * Write the contract once more, with the block's local coordinate {@code p} split into the host block's
 * corner {@code local} and a point {@code v} inside it ({@code v} being the shell's corner or a block
 * model's {@code 0..1} vertex):
 *
 * <pre>
 *   world = position + R · S · (p − pivot)
 *         = position + R · S · (local − pivot)  +  R · S · v
 * </pre>
 *
 * <p>So the pose stack gets exactly {@code T(position) · R · S} - <b>position, rotation, scale, and
 * nothing else</b> - and the {@code (local − pivot)} term is a per-host offset added to the geometry.
 * That term is computed in {@code double}, where the two plot-scale numbers cancel exactly, and what
 * comes out is at most a couple of blocks:
 *
 * <pre>
 *   local=(20481032, 127, 20524040)  pivot=(20481032.5, 128.083, 20524040.5)
 *   local − pivot = (-0.5, -1.083, -0.5)          // exact, and small, and all that reaches the GPU
 * </pre>
 *
 * <p>The translation that does go into the stack is {@code position}, which for a Sable sub-level is an
 * ordinary world coordinate ({@code 2775.5}), not a plot coordinate - the same magnitude the vanilla
 * world path has always pushed. Nothing in this class ever narrows a plot coordinate to {@code float}.
 *
 * <h2>Why one frame per host, handed to both draw paths</h2>
 * The shell and the component inside the block use different {@code v} - the shell a unit-cube corner,
 * the component a block-model vertex - but the same {@code position}, {@code R}, {@code S} and the same
 * {@code local − pivot}. Building that once and handing it to both is what makes it impossible for one
 * of them to be measured from the block and the other from the structure's origin, which was the
 * "contents drawn without {@code +local}" defect: invisible for a Create contraption whose {@code local}
 * is {@code (0,0,0)}, and two thousand blocks for a Sable sub-level.
 *
 * <h2>Purity</h2>
 * A pose and arithmetic: no level, no client class, no Sable type, no {@code PoseStack}. That is what
 * lets the matrix order and the plot-space precision both be unit tests instead of screenshots - and the
 * absence of those two tests is why the old order survived a round of work.
 */
public record CarrierFrame(Vector3d position, Vector3d pivot, @Nullable Quaternionf rotation,
                           float scale, double localX, double localY, double localZ) {

    /**
     * The frame for one block of one structure.
     *
     * <p>{@code localBlock} is the block's coordinate in the structure's own space; a {@code BlockPos}
     * is exact, so a Create contraption's {@code (0,0,0)} produces offsets of exactly
     * {@code (-0.5, -0.5, -0.5)} and every number the shell uses is the small one the old code used.
     */
    public static CarrierFrame forBlock(CarrierPose pose, Vector3dc localBlock) {
        Quaternionf q = pose.orientationF();
        boolean turned = q.x != 0F || q.y != 0F || q.z != 0F;
        return new CarrierFrame(new Vector3d(pose.position()), new Vector3d(pose.pivot()),
                turned ? q : null, (float) pose.scale(),
                localBlock.x(), localBlock.y(), localBlock.z());
    }

    /**
     * <b>The double-precision truth:</b> {@code position + R · S · (p − pivot)}.
     *
     * <p>This is the answer every rendered vertex is checked against, and it is deliberately the same
     * expression {@link CarrierPose#transformPosition} evaluates: {@code worldOf(p)} equals
     * {@code pose.transformPosition(p)} for the pose this frame was built from, to the last bit. That
     * equality is asserted by a unit test, which is what stops the renderer and the diagnostics drifting
     * apart again.
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
     * The offset the geometry of this host is emitted at, in the pose stack's own space:
     * {@code p − pivot}.
     *
     * <p>Small by construction - a difference of two numbers in the same space, one of which is the
     * structure's own pivot - and that is the whole point: it is the only place the local coordinate
     * appears, it appears as a difference, and the difference is taken in {@code double} before anything
     * is narrowed to {@code float}.
     *
     * <p>Not scaled here even when the pose has a scale: the pose stack is given {@code S} itself (see
     * {@link #frameMatrix()}), exactly as {@link CarrierPose#transformMatrix()} composes it, so scaling
     * the offset as well would apply it twice.
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
     * The matrix the pose stack is given, as the contract's order states it:
     * {@code T(position) · R · S} - and deliberately <b>without</b> the pivot translation, which lives in
     * the emitted offsets instead.
     *
     * <p>Provided as a value rather than as three calls so that a unit test can compare it, element by
     * element, with what a real {@code PoseStack} produces for the same operations. A wrong composition
     * order is invisible on screen for exactly one frame per turn, so a test that compares matrices is
     * the only kind that can hold it.
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

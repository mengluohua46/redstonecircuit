package com.jiangyuefengyu.redstonecircuit.client;

import java.util.Locale;

import com.jiangyuefengyu.redstonecircuit.carrier.CarrierFrame;

import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.joml.Vector3f;

/**
 * <b>The check that the matrices handed to OpenGL draw the component where the pose says it is.</b>
 *
 * <h2>The gap this closes</h2>
 * Every diagnostic the mod had compared <em>double</em> arithmetic with <em>double</em> arithmetic: the
 * client's {@code drawCheck} line transformed the host's local coordinate through the pose and compared
 * the answer with the structure's own position, and it printed {@code delta=0.000 OK} on every frame of a
 * flight in which the author saw nothing at all. It was right about what the pose says, and it had no
 * opinion about what the renderer did - because the renderer's numbers are not that arithmetic. They are
 * a {@code float} translation pushed into a {@code Matrix4f} and multiplied by a vertex, and that is
 * where both defects of the carried path lived: a composition order that is only correct at
 * {@code R = I}, and a two-million-block pivot translation performed in {@code float}.
 *
 * <p>So this class asks the renderer's own question. The matrix it reads is the very {@link Matrix4f} the
 * vertex consumer is handed - {@code PoseStack.Pose.pose()} - and the point it transforms is the same
 * offset the vertex is written at. If the pose stack has lost the component, this is where it shows: as a
 * distance, in blocks, between where the geometry went and where {@link CarrierFrame} says the block is.
 *
 * <h2>How to read the line</h2>
 * <pre>
 *   matrixProbe[shell] carrier=c:627eedc1 matrixDelta=0.0000 cornerDelta=0.0000 span=1.7321
 *       centreWorld=(2775.000, 77.000, -1395.000) centreExpected=(2775.000, 77.000, -1395.000)
 *       frame=(2775.500, 77.500, -1394.500) local=(0, 0, 0) turned=yes scale=1.000 OK-matrix ...
 * </pre>
 * <ul>
 *   <li><b>{@code matrixDelta}</b> - the distance between where the <em>matrix</em> puts the host block's
 *       centre and where the double arithmetic does. Under a thousandth of a block is the expected
 *       answer. <b>Hundreds of blocks means the composition order, and millions means the pivot was
 *       narrowed to a {@code float}</b> - the two defects, told apart by the size of the number.</li>
 *   <li><b>{@code cornerDelta}</b> - the same for the block's corner, which is the point the shell's unit
 *       cube is measured from. Reported beside the centre because a matrix can put the centre right and
 *       the corner wrong (or vice versa) only if the offsets and the translation disagree about where
 *       the block starts.</li>
 *   <li><b>{@code span}</b> - how far apart the matrix draws two opposite corners of the block. A correct
 *       frame maps the unit cube to a cube, so this is {@code sqrt(3)} times the scale. <b>A span of
 *       zero is the plot-space float collapse</b>: every vertex of the block landing on one point, which
 *       is invisible to every delta that only measures a centre.</li>
 *   <li><b>{@code shellContentGap}</b>, on the paired line - how far apart the two call sites put the
 *       shell and the component inside it. Expected to be zero, because both are probed at the block's
 *       centre. A gap the size of {@code local} is the "the component is drawn without {@code +local}"
 *       defect.</li>
 * </ul>
 *
 * <h2>Two probes, and why</h2>
 * The shell and the component are drawn by two different call sites with two different offsets, and the
 * defect this round fixed lived in the second one. A check that only asked about the shell would have
 * reported the author's Sable flight as perfect.
 *
 * <h2>Purity</h2>
 * Matrices, offsets and arithmetic: no level, no buffer, no client class. That is what lets the whole
 * claim - "the numbers the GPU is given draw the component where the pose says" - be a unit test rather
 * than a screenshot.
 *
 * @param label which call site this matrix belongs to: {@code shell} or {@code contents}
 * @param carrier the carrier's short id, for the log line
 * @param turned whether the frame applies a rotation
 * @param scale the frame's uniform scale
 * @param position the frame's position, i.e. the translation the matrix itself carries
 * @param centreProbe the offset handed to the matrix for the block's centre
 * @param cornerProbe the offset handed to the matrix for the block's corner
 * @param centreExpected where the double arithmetic puts the block's centre
 * @param cornerExpected where the double arithmetic puts the block's corner
 * @param matrix the matrix the very next vertex was handed, copied at draw time
 * @param camera the camera position, because that matrix is camera-relative
 */
record CarrierMatrixProbe(String label, String carrier, boolean turned, float scale, Vector3d position,
                          Vector3f centreProbe, Vector3f cornerProbe, Vector3d centreExpected,
                          Vector3d cornerExpected, Matrix4f matrix, Vector3d camera) {

    /** A tolerance of a thousandth of a block: far below anything visible, far above float noise. */
    static final double MATRIX_TOLERANCE = 1.0E-3;

    /**
     * The world position a point in the matrix's own space is drawn at.
     *
     * <p>{@code matrix * point + camera}: the matrix is camera-relative because the render pass starts
     * from {@code translate(-cameraPos)}, and the frame's position is inside it rather than added here -
     * adding it here would be testing a transform nobody uses.
     */
    Vector3d worldOf(Vector3f point, Vector3d out) {
        Vector3f transformed = matrix.transformPosition(point.x, point.y, point.z, new Vector3f());
        return out.set((double) transformed.x + camera.x, (double) transformed.y + camera.y,
                (double) transformed.z + camera.z);
    }

    /** Where the matrix draws the block's centre. */
    Vector3d centreWorld() {
        return worldOf(centreProbe, new Vector3d());
    }

    /** Where the matrix draws the block's corner. */
    Vector3d cornerWorld() {
        return worldOf(cornerProbe, new Vector3d());
    }

    /** How far the matrix's block centre is from the double arithmetic's. */
    double matrixDelta() {
        return centreWorld().distance(centreExpected);
    }

    /** How far the matrix's block corner is from the double arithmetic's. */
    double cornerDelta() {
        return cornerWorld().distance(cornerExpected);
    }

    /**
     * How far apart the matrix draws the block's corner and its centre, against {@code sqrt(3)/2}.
     *
     * <p>Reported as the raw distance so that a collapse reads as {@code 0.0000} and a correct frame as
     * {@code 0.8660} (times the scale). A delta between one point and its expected value cannot see a
     * collapse at all if the collapsed point happens to be the expected one; this can.
     */
    double span() {
        return cornerWorld().distance(centreWorld());
    }

    /** The span a correct frame produces: half the unit cube's diagonal, times the scale. */
    double expectedSpan() {
        return Math.sqrt(3.0) / 2.0 * Math.abs(scale);
    }

    /** Whether the block still has a block's worth of extent after the matrix. */
    boolean collapsed() {
        return span() < expectedSpan() - MATRIX_TOLERANCE;
    }

    /** One line, and the numbers an operator has to compare. */
    String describe() {
        return String.format(Locale.ROOT,
                "matrixProbe[%s] carrier=%s matrixDelta=%.4f cornerDelta=%.4f span=%.4f "
                        + "centreWorld=%s centreExpected=%s frame=(%.3f, %.3f, %.3f) turned=%s "
                        + "scale=%.3f %s",
                label, carrier, matrixDelta(), cornerDelta(), span(), vector(centreWorld()),
                vector(centreExpected), position.x, position.y, position.z, turned ? "yes" : "no", scale,
                verdict());
    }

    /** The sentence: correct, or the name of the defect. */
    private String verdict() {
        double delta = matrixDelta();
        StringBuilder sb = new StringBuilder();
        if (delta > MATRIX_TOLERANCE) {
            sb.append("*** THE MATRIX DOES NOT DRAW THE COMPONENT WHERE THE POSE SAYS: off by ")
                    .append(fmt(delta)).append(" block(s). ");
            if (delta > 100.0) {
                sb.append("Hundreds of blocks is the COMPOSITION ORDER: the pose stack was built ")
                        .append("T(-pivot)·R·T(position) where the contract is ")
                        .append("T(position)·R·T(-pivot), which agrees only at R = I - the one frame ")
                        .append("per turn in which the geometry flashes into view. ");
            } else {
                sb.append("A small but non-zero value is float rounding in a translation. ");
            }
            if (collapsed()) {
                sb.append("AND THE BLOCK HAS COLLAPSED TO A POINT (span=").append(fmt(span()))
                        .append(" where a block spans ").append(fmt(expectedSpan()))
                        .append("): a plot-scale pivot has been narrowed to a float. ");
            }
            sb.append("***");
        } else if (collapsed()) {
            sb.append("*** matrixDelta is small but THE BLOCK HAS COLLAPSED TO A POINT (span=")
                    .append(fmt(span())).append(" where a block spans ").append(fmt(expectedSpan()))
                    .append("): every vertex of the shell is landing on one place ***");
        } else {
            sb.append("OK-matrix (the matrix the renderer was handed draws the host block at the world ")
                    .append("position the double arithmetic names, with a block's own extent)");
        }
        return sb.toString();
    }

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }

    private static String vector(@Nullable Vector3d v) {
        return v == null ? "-" : String.format(Locale.ROOT, "(%.3f, %.3f, %.3f)", v.x, v.y, v.z);
    }
}

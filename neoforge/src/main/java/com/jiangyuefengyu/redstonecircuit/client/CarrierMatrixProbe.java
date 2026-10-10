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
 * <p>Other diagnostics compare double arithmetic with double arithmetic, and the renderer's numbers are
 * not that arithmetic: it is handed a {@code float} translation inside a {@link Matrix4f}. So this reads
 * the very matrix the vertex consumer got, at the offset the vertex was written at, and reports
 * {@code matrixDelta} and {@code cornerDelta} (blocks between where the geometry went and where
 * {@link CarrierFrame} says the block is) plus {@code span}, which falls short of {@code sqrt(3)} times
 * the scale when the block has collapsed to a point.
 */
record CarrierMatrixProbe(String label, String carrier, boolean turned, float scale, Vector3d position,
                          Vector3f centreProbe, Vector3f cornerProbe, Vector3d centreExpected,
                          Vector3d cornerExpected, Matrix4f matrix, Vector3d camera) {

    /** A tolerance of a thousandth of a block: far below anything visible, far above float noise. */
    static final double MATRIX_TOLERANCE = 1.0E-3;

    /**
     * The world position a point in the matrix's own space is drawn at: {@code matrix * point + camera},
     * because the render pass starts from {@code translate(-cameraPos)}.
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
     * How far apart the matrix draws the block's corner and its centre: a delta against an expected value
     * cannot see a collapse when the collapsed point happens to be the expected one; this can.
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

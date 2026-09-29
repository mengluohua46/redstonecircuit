package com.jiangyuefengyu.redstonecircuit.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jiangyuefengyu.redstonecircuit.carrier.CarrierFrame;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierPose;

import com.mojang.blaze3d.vertex.PoseStack;

import org.joml.Matrix4f;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>Pins the diagnostic that asks the matrix the renderer was handed, rather than the arithmetic that
 * decided to draw.</b>
 *
 * <h2>Why a probe of the matrix, and not another counter</h2>
 * The author's log carried {@code drawCheck ... computedWorld= structureWorld= delta=0.000 OK-position}
 * on every frame of a flight in which nothing was visible. That line was correct: it transformed the
 * host's local coordinate through the pose in {@code double} and compared it with the structure's own
 * position. What it could not see was the thing that was actually wrong - the {@code float} matrix the
 * vertex was multiplied by. These tests drive {@link CarrierMatrixProbe} with the real renderer's matrix
 * and with the old, wrong one, and assert that the probe names the right defect at the right magnitude.
 */
class CarrierMatrixProbeTest {

    private static final Vector3d CAMERA = new Vector3d(2700.25, 90.5, -1300.75);

    /** The author's Create contraption: pivot and local are sub-block. */
    private static CarrierPose contraption(double degrees) {
        return new CarrierPose(new Vector3d(2775.5, 77.5, -1394.5),
                new Quaterniond().rotateX(Math.toRadians(degrees)), new Vector3d(0.5, 0.5, 0.5), 1.0);
    }

    /** The author's Sable sub-level: pivot and local are both plot coordinates. */
    private static CarrierPose sable(double degrees) {
        return new CarrierPose(new Vector3d(2775.5, 78.083, -1394.5),
                new Quaterniond().rotateX(Math.toRadians(degrees)),
                new Vector3d(20481032.5, 128.083, 20524040.5), 1.0);
    }

    private static CarrierFrame contraptionFrame(double degrees) {
        return CarrierFrame.forBlock(contraption(degrees), new Vector3d(0, 0, 0));
    }

    private static CarrierFrame sableFrame(double degrees) {
        return CarrierFrame.forBlock(sable(degrees), new Vector3d(20481032, 127, 20524040));
    }

    /**
     * The pose stack the renderer builds for a carried host, as a matrix.
     *
     * <p>Deliberately the real {@link HostRenderPass#applyCarrierFrame} on a real {@link PoseStack}: a
     * probe tested against a hand-built matrix would be testing the test.
     */
    private static Matrix4f rendererMatrix(CarrierFrame frame) {
        PoseStack pose = new PoseStack();
        pose.translate(-CAMERA.x, -CAMERA.y, -CAMERA.z);
        HostRenderPass.applyCarrierFrame(pose, frame);
        return pose.last().pose();
    }

    private static final float[] NO_EXTRA = { 0F, 0F, 0F };

    /**
     * A probe of one call site of a correct frame.
     *
     * @param extra what the call site translates by before writing vertices: nothing for the shell, the
     *     block's corner offset for the contents
     */
    private static CarrierMatrixProbe shellProbe(CarrierFrame frame) {
        float[] corner = frame.cornerOffset(new float[3]);
        return probe("c:test", frame, rendererMatrix(frame),
                new Vector3f(corner[0] + 0.5F, corner[1] + 0.5F, corner[2] + 0.5F),
                new Vector3f(corner[0], corner[1], corner[2]));
    }

    private static CarrierMatrixProbe contentsProbe(CarrierFrame frame) {
        // The contents call site translates the pose stack by the block's corner offset and then writes
        // the model's own 0..1 vertices - so its matrix has that translation in it and its probe points
        // are the block's centre and corner *inside* the block.
        float[] corner = frame.cornerOffset(new float[3]);
        Matrix4f matrix = rendererMatrix(frame)
                .translate(corner[0], corner[1], corner[2]);
        return probe("c:test", frame, matrix, new Vector3f(0.5F, 0.5F, 0.5F),
                new Vector3f(0F, 0F, 0F));
    }

    /** The probe for one call site: its own matrix, and the two points it writes vertices at. */
    private static CarrierMatrixProbe probe(String carrier, CarrierFrame frame, Matrix4f matrix,
                                            Vector3f centreProbe, Vector3f cornerProbe) {
        return new CarrierMatrixProbe("shell", carrier, frame.rotation() != null, frame.scale(),
                new Vector3d(frame.position()), centreProbe, cornerProbe,
                frame.blockCentre(new Vector3d()),
                frame.worldOf(frame.localX(), frame.localY(), frame.localZ(), new Vector3d()),
                matrix, new Vector3d(CAMERA));
    }

    // -------------------------------------------------------- a correct frame --

    @Test
    @DisplayName("a correct frame reports zero delta and a block's own span")
    void correctFrameReportsZero() {
        for (double degrees : new double[] { 0, 30, 90 }) {
            CarrierFrame[] frames = { contraptionFrame(degrees), sableFrame(degrees) };
            for (CarrierFrame frame : frames) {
                CarrierMatrixProbe shell = shellProbe(frame);
                CarrierMatrixProbe contents = contentsProbe(frame);
                assertEquals(0.0, shell.matrixDelta(), CarrierMatrixProbe.MATRIX_TOLERANCE,
                        "the shell's matrix is wrong at " + degrees + " degrees: " + shell.describe());
                assertEquals(0.0, shell.cornerDelta(), CarrierMatrixProbe.MATRIX_TOLERANCE,
                        "the shell's corner is wrong at " + degrees + " degrees: " + shell.describe());
                assertEquals(0.0, contents.matrixDelta(), CarrierMatrixProbe.MATRIX_TOLERANCE,
                        "the contents' matrix is wrong at " + degrees + " degrees: "
                                + contents.describe());
                assertFalse(shell.collapsed(), "the block collapsed at " + degrees + " degrees");
                assertEquals(Math.sqrt(3.0) / 2.0, shell.span(), 1.0E-3,
                        "a correct frame maps the block's half diagonal");
                // The two call sites agree with each other: this is the "+local" check, and it is the
                // one that would have caught the missing offset in a plot space.
                assertEquals(0.0, shell.centreWorld().distance(contents.centreWorld()),
                        CarrierMatrixProbe.MATRIX_TOLERANCE,
                        "shell and contents disagree at " + degrees + " degrees");
                assertTrue(shell.describe().contains("OK-matrix"), shell.describe());
                assertTrue(shell.describe().contains("matrixDelta=0.0000"), shell.describe());
            }
        }
    }

    // ---------------------------------------------------- the old composition --

    @Test
    @DisplayName("the old composition order is named as hundreds of blocks, not as float rounding")
    void oldOrderIsNamedAsHundredsOfBlocks() {
        CarrierMatrixProbe wrong = probeWithOldMatrix(contraption(30), 0, 0, 0);
        assertTrue(wrong.matrixDelta() > 100.0,
                "the old order should be hundreds of blocks out: " + wrong.describe());
        String line = wrong.describe();
        assertTrue(line.contains("COMPOSITION ORDER"), line);
        assertTrue(line.contains("R = I"), line);
        assertTrue(line.contains("flashes into view"), line);
    }

    @Test
    @DisplayName("the old order is millions of blocks out in a Sable plot space")
    void oldOrderIsMillionsOfBlocksOutInPlotSpace() {
        CarrierMatrixProbe wrong = probeWithOldMatrix(sable(30), 20481032, 127, 20524040);
        assertTrue(wrong.matrixDelta() > 1.0E6,
                "the old order should be millions of blocks out: " + wrong.matrixDelta());
    }

    @Test
    @DisplayName("at R = I the old order was right, which is why only one frame of each turn showed")
    void oldOrderIsRightAtZeroDegrees() {
        CarrierMatrixProbe wrong = probeWithOldMatrix(contraption(0), 0, 0, 0);
        assertEquals(0.0, wrong.matrixDelta(), 1.0E-5,
                "the defect is invisible at zero degrees: " + wrong.describe());
    }

    // ------------------------------------------------------------ the collapse --

    @Test
    @DisplayName("a block that has collapsed to a point is named even when its centre is right")
    void collapseIsNamedEvenWithACorrectCentre() {
        CarrierFrame frame = sableFrame(0);
        // The old path's matrix, which *is* the collapse: its translation is `position - pivot`, a plot
        // coordinate, and at two times ten to the seventh a float's spacing is 2 - so the block's own
        // half block, and even the 0.5 of its centre offset, round away before the geometry is added.
        Matrix4f collapsed = new Matrix4f()
                .translation((float) (2775.5 - 20481032.5), (float) (78.083 - 128.083),
                        (float) (-1394.5 - 20524040.5));
        Vector3d cornerWorld = frame.worldOf(frame.localX(), frame.localY(), frame.localZ(),
                new Vector3d());
        CarrierMatrixProbe probe = probe("s:test", frame, collapsed,
                new Vector3f((float) (frame.localX() + 0.5), (float) (frame.localY() + 0.5),
                        (float) (frame.localZ() + 0.5)),
                new Vector3f((float) frame.localX(), (float) frame.localY(), (float) frame.localZ()));
        assertEquals(frame.blockCentre(new Vector3d()), probe.centreExpected());
        assertEquals(cornerWorld, probe.cornerExpected());
        assertTrue(probe.collapsed(),
                "a degenerate matrix has to be reported as a collapse: " + probe.describe());
        assertTrue(probe.span() < probe.expectedSpan(),
                "the block does not span a block any more: " + probe.span());
        // On the plot-scale axes the block's extent is gone entirely - 20481032.0f + 0.5f is
        // 20481032.0f. The y axis is not plot-scale, so it keeps its half block, and the span is exactly
        // that: the collapse is per axis, and this is the axis detail in one line.
        assertEquals(0.0, probe.centreWorld().x - probe.cornerWorld().x, 0.0);
        assertEquals(0.0, probe.centreWorld().z - probe.cornerWorld().z, 0.0);
        assertTrue(probe.describe().contains("COLLAPSED TO A POINT"), probe.describe());
        // And the correct frame does not collapse: the contrast the verdict is read against.
        assertFalse(shellProbe(frame).collapsed());
        assertEquals(Math.sqrt(3.0) / 2.0, shellProbe(frame).expectedSpan(), 1.0E-9);
    }

    // ------------------------------------------------------------ the helpers --

    @Test
    @DisplayName("the probe line carries every position a reader has to compare by hand")
    void describeCarriesBothPositions() {
        CarrierFrame frame = sableFrame(0);
        CarrierMatrixProbe probe = contentsProbe(frame);
        String line = probe.describe();
        for (String expected : new String[] { "matrixProbe[shell]", "carrier=c:test", "matrixDelta=",
                "cornerDelta=", "span=", "centreWorld=", "centreExpected=", "frame=(", "turned=",
                "scale=", "OK-matrix", "span=0.8660" }) {
            assertTrue(line.contains(expected), "missing " + expected + " in " + line);
        }
    }

    /**
     * The probe for the <b>old</b> carried path: {@code T(-cam)路T(-pivot)路R路T(position)}, which is what
     * {@code translate(-pivot); mulPose(R); translate(position)} builds on a right-multiplying stack.
     *
     * <p>The vertex the old path wrote was the raw local coordinate {@code p}, because the pivot
     * subtraction was baked into the matrix - which is why the probe point here is {@code local + v} and
     * not the offset the new code uses.
     */
    private static CarrierMatrixProbe probeWithOldMatrix(CarrierPose pose, int localX, int localY,
                                                         int localZ) {
        PoseStack stack = new PoseStack();
        stack.translate(-CAMERA.x, -CAMERA.y, -CAMERA.z);
        stack.translate(-pose.pivot().x(), -pose.pivot().y(), -pose.pivot().z());
        stack.mulPose(pose.orientationF());
        stack.translate(pose.position().x(), pose.position().y(), pose.position().z());
        CarrierFrame frame = CarrierFrame.forBlock(pose, new Vector3d(localX, localY, localZ));
        return new CarrierMatrixProbe("shell", "c:test", pose.orientationF().x != 0F, 1.0F,
                new Vector3d(frame.position()),
                new Vector3f((float) localX + 0.5F, (float) localY + 0.5F, (float) localZ + 0.5F),
                new Vector3f((float) localX, (float) localY, (float) localZ),
                frame.blockCentre(new Vector3d()),
                frame.worldOf(localX, localY, localZ, new Vector3d()), stack.last().pose(),
                new Vector3d(CAMERA));
    }
}

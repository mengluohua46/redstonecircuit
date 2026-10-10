package com.jiangyuefengyu.redstonecircuit.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jiangyuefengyu.redstonecircuit.carrier.CarrierFrame;
import com.jiangyuefengyu.redstonecircuit.client.InnerComponentModel.Box;
import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.Slot;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierPose;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.core.BlockPos;

import org.joml.Matrix4f;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests the carried render path's arithmetic: the shell and the block model inside it must land where
 * the pose says, compared against {@link CarrierPose#transformPosition}, the contract.
 *
 * {@code T(-pivot) · R · T(position)}, where the contract is {@code T(position) · R · T(-pivot)}.
 */
class CarrierFrameTest {

    /** A camera far from the machine, so the camera translation is part of what is being checked. */
    private static final Vector3d CAMERA = new Vector3d(2700.25, 90.5, -1300.75);

    /** Well below a visible error: the shell is a block, and this is a ten-thousandth of one. */
    private static final double TOLERANCE = 1.0E-3;

    // --------------------------------------------------------------- fixtures --

    /** The pose of a Create contraption holding one host block: pivot and local are sub-block. */
    private static CarrierPose contraption(double degrees) {
        return new CarrierPose(new Vector3d(2775.5, 77.5, -1394.5), aboutX(degrees),
                new Vector3d(0.5, 0.5, 0.5), 1.0);
    }

    /** A Sable sub-level pose: pivot and local are plot coordinates, far beyond a float's precision. */
    private static CarrierPose sable(double degrees) {
        return new CarrierPose(new Vector3d(2775.5, 78.083, -1394.5), aboutX(degrees),
                new Vector3d(20481032.5, 128.083, 20524040.5), 1.0);
    }

    private static Quaterniond aboutX(double degrees) {
        return new Quaterniond().rotateX(Math.toRadians(degrees));
    }

    /** The world position a vertex at {@code offset} is drawn at, through the real render path. */
    private static Vector3d drawnWorld(CarrierFrame frame, float[] offset) {
        PoseStack pose = new PoseStack();
        pose.translate(-CAMERA.x, -CAMERA.y, -CAMERA.z);
        HostRenderPass.applyCarrierFrame(pose, frame);
        Vector3f drawn = pose.last().pose().transformPosition(offset[0], offset[1], offset[2],
                new Vector3f());
        return new Vector3d((double) drawn.x + CAMERA.x, (double) drawn.y + CAMERA.y,
                (double) drawn.z + CAMERA.z);
    }

    private static CarrierFrame frameOf(CarrierPose pose, int localX, int localY, int localZ) {
        return CarrierFrame.forBlock(pose, new Vector3d(localX, localY, localZ));
    }

    /** Full precision, so a failing assertion names the number rather than rounding it away. */
    private static String full(Vector3d v) {
        return String.format(java.util.Locale.ROOT, "(%.9f, %.9f, %.9f)", v.x, v.y, v.z);
    }

    // --------------------------------------------------------- the contract --

    @Test
    @DisplayName("the shell lands where the pose says, at 0, 30 and 90 degrees (Create)")
    void shellLandsWhereThePoseSaysForAContraption() {
        for (double degrees : new double[] { 0, 30, 90 }) {
            CarrierPose pose = contraption(degrees);
            CarrierFrame frame = frameOf(pose, 0, 0, 0);
            float[] base = frame.cornerOffset(new float[3]);
            for (int face = 0; face < HostShell.FACE_COUNT; face++) {
                for (float a : new float[] { 0F, 0.5F, 1F }) {
                    float[] corner = new float[3];
                    HostShell.corner(face, a, a, corner);
                    Vector3d expected = pose.transformPosition(
                            new Vector3d(corner[0], corner[1], corner[2]), new Vector3d());
                    Vector3d actual = drawnWorld(frame, new float[] {
                            base[0] + corner[0], base[1] + corner[1], base[2] + corner[2] });
                    assertEquals(0.0, expected.distance(actual), TOLERANCE,
                            "a shell vertex at " + degrees + " degrees is drawn somewhere else: "
                                    + expected + " expected, " + actual + " drawn");
                }
            }
        }
    }

    @Test
    @DisplayName("the shell lands where the pose says in a Sable plot space (|local| ~ 2e7)")
    void shellLandsWhereThePoseSaysInPlotSpace() {
        for (double degrees : new double[] { 0, 30, 90 }) {
            CarrierPose pose = sable(degrees);
            CarrierFrame frame = frameOf(pose, 20481032, 127, 20524040);
            float[] base = frame.cornerOffset(new float[3]);
            // Every number handed to the pose stack is a fraction of a block, whatever the plot
            // coordinate was.
            for (float value : base) {
                assertTrue(Math.abs(value) < 2.0F,
                        "a plot coordinate reached the pose stack: offset " + value);
            }
            for (float a : new float[] { 0F, 0.5F, 1F }) {
                float[] corner = new float[3];
                HostShell.corner(0, a, a, corner);
                // An int plus a float would be arithmetic in *float*, where a corner at this magnitude
                // vanishes: the expectation has to be computed in double.
                Vector3d expected = pose.transformPosition(new Vector3d(
                        20481032.0 + corner[0], 127.0 + corner[1], 20524040.0 + corner[2]),
                        new Vector3d());
                Vector3d actual = drawnWorld(frame, new float[] {
                        base[0] + corner[0], base[1] + corner[1], base[2] + corner[2] });
                assertEquals(0.0, expected.distance(actual), TOLERANCE,
                        "a shell vertex in plot space at " + degrees + " degrees is drawn somewhere "
                                + "else: " + full(expected) + " expected, " + full(actual) + " drawn"
                                + " (offset " + base[0] + "," + base[1] + "," + base[2] + " + corner "
                                + corner[0] + "," + corner[1] + "," + corner[2] + ")");
            }
        }
    }

    @Test
    @DisplayName("the component inside the block is in the shell's frame, in plot space too")
    void contentsShareTheShellsFrame() {
        // The block's centre as a point inside the block: the same point for the model, whose vertices
        // are 0..1, and for the shell, whose cube is the block expanded by EXPAND.
        final double vx = 0.5;
        final double vy = 0.5;
        final double vz = 0.5;

        for (double degrees : new double[] { 0, 30, 90 }) {
            CarrierPose pose = sable(degrees);
            CarrierFrame frame = frameOf(pose, 20481032, 127, 20524040);
            float[] base = frame.cornerOffset(new float[3]);

            // The shell's call site: the pose stack, then a vertex at `base + the block's centre`.
            Vector3d shellDrawn = drawnWorld(frame, new float[] {
                    (float) (base[0] + vx), (float) (base[1] + vy), (float) (base[2] + vz) });

            // The contents' call site: the same pose stack, translated by the block's corner - the base the
            // shell emits at.
            PoseStack contents = new PoseStack();
            contents.translate(-CAMERA.x, -CAMERA.y, -CAMERA.z);
            HostRenderPass.applyCarrierFrame(contents, frame);
            float[] anchored = HostRenderPass.carriedModelBase(frame, new float[3]);
            contents.translate(anchored[0], anchored[1], anchored[2]);
            Vector3f modelVertex = contents.last().pose().transformPosition(
                    (float) vx, (float) vy, (float) vz, new Vector3f());
            Vector3d contentsDrawn = new Vector3d((double) modelVertex.x + CAMERA.x,
                    (double) modelVertex.y + CAMERA.y, (double) modelVertex.z + CAMERA.z);

            // Both routes must name the same point, the one the double arithmetic gives.
            Vector3d expected = pose.transformPosition(
                    new Vector3d(20481032.5, 127.5, 20524040.5), new Vector3d());
            assertEquals(0.0, expected.distance(contentsDrawn), TOLERANCE,
                    "the component is not where the pose says at " + degrees + " degrees: "
                            + full(expected) + " expected, " + full(contentsDrawn) + " drawn");
            assertEquals(0.0, shellDrawn.distance(contentsDrawn), TOLERANCE,
                    "the shell and the component inside it are in different frames at " + degrees
                            + " degrees: shell " + full(shellDrawn) + ", contents "
                            + full(contentsDrawn));
        }
    }

    @Test
    @DisplayName("the shell still spans a whole block in plot space, rather than collapsing to a point")
    void shellKeepsABlockWideSpanInPlotSpace() {
        CarrierPose pose = sable(30);
        CarrierFrame frame = frameOf(pose, 20481032, 127, 20524040);
        float[] base = frame.cornerOffset(new float[3]);
        float[] low = new float[3];
        float[] high = new float[3];
        HostShell.corner(2, 0F, 0F, low);
        HostShell.corner(2, 1F, 1F, high);
        Vector3d a = drawnWorld(frame, new float[] { base[0] + low[0], base[1] + low[1], base[2] + low[2] });
        Vector3d b = drawnWorld(frame, new float[] { base[0] + high[0], base[1] + high[1],
                base[2] + high[2] });
        double span = a.distance(b);
        // One face's diagonal: sqrt(2) times the expanded span. Any value near zero is the collapse.
        double expected = Math.sqrt(2.0) * HostShell.SPAN;
        assertEquals(expected, span, 1.0E-3,
                "the shell does not span a block: " + span + " where " + expected + " is expected");
        assertTrue(span > 1.0, "the shell has collapsed to a point");
    }

    @Test
    @DisplayName("a Create contraption's local=(0,0,0) path agrees with the contract")
    void contraptionLocalZeroIsUnchanged() {
        CarrierPose pose = contraption(0);
        CarrierFrame frame = frameOf(pose, 0, 0, 0);
        float[] base = frame.cornerOffset(new float[3]);
        // Exactly the half block the shell's base has always been offset by.
        assertEquals(-0.5F, base[0], 0.0F);
        assertEquals(-0.5F, base[1], 0.0F);
        assertEquals(-0.5F, base[2], 0.0F);

        // The world position it produces is the one the contract names.
        //
        // The tolerance is not 0 because IEEE addition is not associative at these magnitudes, so a
        // literal bit-for-bit claim would be false.
        float[] corner = new float[3];
        HostShell.corner(0, 0.25F, 0.75F, corner);
        Vector3d contract = pose.transformPosition(new Vector3d(corner[0], corner[1], corner[2]),
                new Vector3d());
        Vector3d drawn = drawnWorld(frame, new float[] { base[0] + corner[0], base[1] + corner[1],
                base[2] + corner[2] });
        assertEquals(0.0, contract.distance(drawn), 1.0E-5,
                "the Create path moved: " + full(contract) + " expected, " + full(drawn) + " drawn");
        // At R = I the two orders agree exactly.
        Vector3d oldOrder = oldOrderResult(pose, new Vector3d(corner[0], corner[1], corner[2]),
                new Vector3d());
        assertEquals(0.0, oldOrder.distance(drawn), 1.0E-5,
                "the Create local=(0,0,0) path must agree with the old order at zero rotation");
    }

    // ------------------------------------------------------ the two call paths --

    @Test
    @DisplayName("the world path and the carried path put the component in the same place")
    void theTwoPathsAgree() {
        // World-path vertices are world coordinates; carried-path ones are block-local inside the frame.
        // For a Create contraption the two have to agree exactly, and this test is what says so.
        //
        // Both sides go through the production expressions the two drawing paths actually call, never
        // through a restatement of them written here.
        BlockPos block = new BlockPos(2775, 77, -1395);
        Vector3d position = new Vector3d(block.getX() + 0.5, block.getY() + 0.5, block.getZ() + 0.5);
        CarrierPose pose = new CarrierPose(position, new Quaterniond(), new Vector3d(0.5, 0.5, 0.5),
                1.0);
        CarrierFrame frame = CarrierFrame.forBlock(pose, new Vector3d(0, 0, 0));
        float[] worldBase = HostRenderPass.worldVertexBase(block, new float[3]);
        float[] carriedBase = HostRenderPass.carriedModelBase(frame, new float[3]);
        Slot torch = new Slot(ComponentType.TORCH);
        InnerComponentModel.Basis basis = InnerComponentModel.Basis.of(torch);

        for (Box box : boxesOf(torch)) {
            for (float[] local : InnerComponentModel.corners(box)) {
                float[] mapped = new float[3];
                basis.point(local[0], local[1], local[2], mapped);

                // The world path's vertex: a world coordinate already.
                Vector3d world = new Vector3d(worldBase[0] + mapped[0], worldBase[1] + mapped[1],
                        worldBase[2] + mapped[2]);

                // The carried path's point is block-local; the frame's own translation turns it into a
                // world coordinate.
                Vector3d carried = new Vector3d(position).add(carriedBase[0] + mapped[0],
                        carriedBase[1] + mapped[1], carriedBase[2] + mapped[2]);

                assertEquals(0.0, world.distance(carried), 1.0E-4,
                        "the two paths disagree by " + full(world) + " vs " + full(carried)
                                + " (worldBase " + worldBase[0] + "," + worldBase[1] + ","
                                + worldBase[2] + "; carriedBase " + carriedBase[0] + ","
                                + carriedBase[1] + "," + carriedBase[2] + ")");
            }
        }
    }

    private static java.util.List<Box> boxesOf(Slot slot) {
        java.util.List<Box> boxes = new java.util.ArrayList<>();
        ConnectionBars.bars(slot, direction -> false, boxes);
        return boxes;
    }

    @Test
    @DisplayName("worldOf is the pose's own transform, to within a double's own spacing")
    void worldOfIsThePoseTransform() {        for (double degrees : new double[] { 0, 30, 90 }) {
            CarrierFrame frame = frameOf(sable(degrees), 20481032, 127, 20524040);
            CarrierPose pose = sable(degrees);
            for (double[] p : new double[][] { { 20481032.5, 127.5, 20524040.5 },
                    { 20481032.0, 128.0, 20524040.0 }, { 20481033.25, 127.75, 20524041.5 } }) {
                Vector3d expected = pose.transformPosition(new Vector3d(p), new Vector3d());
                Vector3d actual = frame.worldOf(p[0], p[1], p[2], new Vector3d());
                // The residual at this magnitude is double cancellation, not an error in the transform.
                assertEquals(0.0, expected.distance(actual), 1.0E-6,
                        "worldOf is not the pose's transform at " + degrees + " degrees for "
                                + java.util.Arrays.toString(p) + ": " + full(expected) + " vs "
                                + full(actual));
            }
        }
    }

    @Test
    @DisplayName("the frame's matrix is T(position) . R . S, and carries no pivot translation")
    void frameMatrixIsPositionThenRotationThenScale() {
        CarrierPose pose = sable(30);
        CarrierFrame frame = frameOf(pose, 20481032, 127, 20524040);
        Matrix4f actual = frame.frameMatrix();
        Matrix4f expected = new Matrix4f()
                .translation((float) 2775.5, (float) 78.083, (float) -1394.5)
                .rotate(new org.joml.Quaternionf().rotateX((float) Math.toRadians(30)))
                .scale(1.0F);
        for (int column = 0; column < 4; column++) {
            for (int row = 0; row < 4; row++) {
                assertEquals(expected.get(column, row), actual.get(column, row), 1.0E-6F,
                        "element (" + column + "," + row + ") of the frame matrix");
            }
        }
        // The pivot is two million blocks away: narrowed to a float, it would show in the translation column.
        float translationX = actual.getTranslation(new Vector3f()).x;
        float translationZ = actual.getTranslation(new Vector3f()).z;
        assertTrue(Math.abs(translationX) < 4000.0F,
                "the pivot reached the matrix: tx=" + translationX);
        assertTrue(Math.abs(translationZ) < 4000.0F,
                "the pivot reached the matrix: tz=" + translationZ);
    }

    // ------------------------------------------------------ the old defects --

    @Test
    @DisplayName("the old T(-pivot).R.T(position) order was hundreds of blocks wrong at 30 degrees")
    void theOldOrderWasHundredsOfBlocksWrong() {
        CarrierPose pose = contraption(30);
        Vector3d p = new Vector3d(0.5, 0.5, 0.5);
        Vector3d contract = pose.transformPosition(p, new Vector3d());
        Vector3d oldOrder = oldOrderResult(pose, p, new Vector3d());
        double error = contract.distance(oldOrder);
        // Printed as well as asserted, so the size of the error is visible in the report.
        System.out.println("[CarrierFrameTest] Create-like pose at 30deg, block centre:"
                + " oldOrderError=" + error
                + " newMatrixDelta=" + contract.distance(drawnWorld(frameOf(contraption(30), 0, 0, 0),
                        frameOf(contraption(30), 0, 0, 0).emittedOffset(0.5, 0.5, 0.5, new float[3]))));
        assertTrue(error > 100.0,
                "the old composition order should be hundreds of blocks out at 30 degrees, was " + error);
        // And at zero degrees the two agree exactly.
        Vector3d straightContract = contraption(0).transformPosition(p, new Vector3d());
        Vector3d straightOld = oldOrderResult(contraption(0), p, new Vector3d());
        assertEquals(0.0, straightContract.distance(straightOld), 1.0E-6,
                "at R = I the two orders agree");
    }

    @Test
    @DisplayName("the old order was millions of blocks wrong in a Sable plot space")
    void theOldOrderWasMillionsOfBlocksWrongInPlotSpace() {
        CarrierPose pose = sable(30);
        Vector3d p = new Vector3d(20481032.5, 127.5, 20524040.5);
        double error = pose.transformPosition(p, new Vector3d())
                .distance(oldOrderResult(pose, p, new Vector3d()));
        CarrierFrame frame = frameOf(pose, 20481032, 127, 20524040);
        System.out.println("[CarrierFrameTest] Sable-like plot pose at 30deg, block centre:"
                + " oldOrderError=" + error
                + " newMatrixDelta=" + pose.transformPosition(p, new Vector3d())
                        .distance(drawnWorld(frame, frame.emittedOffset(20481032.5, 127.5, 20524040.5,
                                new float[3]))));
        assertTrue(error > 1.0E6,
                "rotating a plot coordinate should be millions of blocks out, was " + error);
    }

    /**
     * What the old carried path produced: both the point and the position are rotated about the origin, which is the
     * negative control for the contract above.
     */
    private static Vector3d oldOrderResult(CarrierPose pose, Vector3d p, Vector3d out) {
        Vector3d rotatedPoint = new Vector3d(p);
        pose.orientation().transform(rotatedPoint);
        Vector3d rotatedPosition = new Vector3d(pose.position());
        pose.orientation().transform(rotatedPosition);
        return out.set(rotatedPoint).add(rotatedPosition).sub(pose.pivot());
    }
}

package com.jiangyuefengyu.redstonecircuit.carrier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests the coordinate contract the whole carried feature rests on: a structure's pose and its round trip.
 * <pre>
 *   transformPosition(local) = position + orientation * (local - pivot) * scale
 *   blockCentre(local)       = transformPosition(local + 0.5)
 * </pre>
 * A local coordinate names a block's <em>corner</em>, so {@code blockCentre} - not {@code transformPosition}
 * - answers "where is this block", and nothing here may assume a right angle.
 */
class CarrierPoseTest {

    private static final double EPSILON = 1.0E-9;

    private static void assertVector(Vector3d expected, Vector3d actual, double epsilon) {
        assertEquals(expected.x, actual.x, epsilon, "x  [actual=" + actual + "]");
        assertEquals(expected.y, actual.y, epsilon, "y  [actual=" + actual + "]");
        assertEquals(expected.z, actual.z, epsilon, "z  [actual=" + actual + "]");
    }

    private static CarrierPose poseAt(double x, double y, double z) {
        return CarrierPose.of(new Vector3d(x, y, z), new Quaterniond());
    }

    /** The matrix chain and the vector arithmetic are the same map, including the pivot's scale: JOML
     * post-multiplies, so the scale must come before the pivot translation, with the pivot scaled
     * inside it, or the map loses {@code (scale - 1) * pivot}. */
    @Test
    @DisplayName("the transform matrix is the same map as the vector arithmetic, scales and all")
    void transformMatrixAgreesWithTheContract() {
        Vector3d position = new Vector3d(1.0, 2.0, 3.0);
        Quaterniond orientation = new Quaterniond().rotateY(0.6).rotateX(0.25);
        Vector3d pivot = new Vector3d(0.5, 0.5, 0.5);
        double scale = 2.0;
        CarrierPose pose = new CarrierPose(position, orientation, pivot, scale);
        Vector3d local = new Vector3d(4.0, 5.0, 6.0);

        // The contract, written out by hand.
        Vector3d contract = new Vector3d(local).sub(pivot).mul(scale);
        orientation.transform(contract);
        contract.add(position);

        assertVector(contract, pose.transformMatrix().transformPosition(new Vector3d(local),
                new Vector3d()), 1.0E-9);
        assertVector(contract, pose.transformPosition(local, new Vector3d()), 1.0E-9);

        // And the forward map is exactly inverted by the inverse for every scale, not only for 1.
        assertVector(local, pose.transformPositionInverse(
                pose.transformPosition(local, new Vector3d()), new Vector3d()), 1.0E-9);

        // The pose's own pivot maps to its own position: the one local point a scale must not move.
        CarrierPose halfBlock = new CarrierPose(new Vector3d(), new Quaterniond(),
                new Vector3d(0.5, 0.5, 0.5), 3.0);
        assertVector(new Vector3d(0.0, 0.0, 0.0),
                halfBlock.transformPosition(new Vector3d(0.5, 0.5, 0.5), new Vector3d()), 1.0E-9);
    }

    /** The three unit offsets a contraption's own transform is asked about to recover its rotation:
     * differences of transformed points, so pivot and origin cancel and the rotation is what is left. */
    private static Vector3d[] basisOf(CarrierPose pose) {
        Vector3d pivotOut = pose.transformPosition(new Vector3d(0.5, 0.5, 0.5), new Vector3d());
        Vector3d[] columns = new Vector3d[3];
        columns[0] = pose.transformPosition(new Vector3d(1.5, 0.5, 0.5), new Vector3d());
        columns[1] = pose.transformPosition(new Vector3d(0.5, 1.5, 0.5), new Vector3d());
        columns[2] = pose.transformPosition(new Vector3d(0.5, 0.5, 1.5), new Vector3d());
        for (Vector3d column : columns) {
            column.sub(pivotOut);
        }
        return columns;
    }

    /** What a pose's rotation does to one vector, as a plain {@code double} vector. */
    private static Vector3d rotate(CarrierPose pose, double x, double y, double z) {
        Vector3f rotated = pose.rotationMatrix()
                .transform(new Vector3f((float) x, (float) y, (float) z));
        return new Vector3d(rotated.x, rotated.y, rotated.z);
    }

    // ------------------------------------------------- the forward transform --

    @Test
    @DisplayName("the pivot itself is the only local point a rotation leaves alone")
    void thePivotIsTheFixedPoint() {
        Quaterniond arbitrary = new Quaterniond().rotateY(0.7).rotateX(-0.3).rotateZ(1.1);
        CarrierPose pose = CarrierPose.of(new Vector3d(12.0, 64.0, -7.0), arbitrary);

        assertVector(new Vector3d(12.0, 64.0, -7.0),
                pose.transformPosition(new Vector3d(0.5, 0.5, 0.5), new Vector3d()), 1.0E-9);
    }

    @Test
    @DisplayName("an unturned structure is a plain translation")
    void identityRotationIsATranslation() {
        CarrierPose pose = poseAt(100.0, 70.0, -30.0);

        // transformPosition takes the local point the arithmetic is written in: the pivot goes first, so
        // the centre of the block at (1, 2, 6) is the point (1.5, 2.5, 6.5).
        assertVector(new Vector3d(101.0, 72.0, -24.0),
                pose.transformPosition(new Vector3d(1.5, 2.5, 6.5), new Vector3d()), 1.0E-9);
        // blockCentre is the convenience that adds that half block for you, so it agrees exactly.
        assertVector(new Vector3d(101.0, 72.0, -24.0),
                pose.blockCentre(new Vector3d(1.0, 2.0, 6.0), new Vector3d()), 1.0E-9);
    }

    @Test
    @DisplayName("a quarter turn goes the way Sable's transform says it does")
    void quarterTurnMatchesTheDocumentedContract() {
        // 90 degrees about +Y, which is the case a "structure on a bearing" actually produces.
        CarrierPose pose = CarrierPose.of(new Vector3d(), new Quaterniond().rotateY(Math.PI / 2.0));

        // The offset from the pivot is (1, 0, 0), and the quarter turn takes that to (0, 0, -1).
        assertVector(new Vector3d(0.0, 0.0, -1.0),
                pose.blockCentre(new Vector3d(1.0, 0.0, 0.0), new Vector3d()), 1.0E-6);
        assertEquals(1.0, pose.blockCentre(new Vector3d(1.0, 0.0, 0.0), new Vector3d())
                .distance(new Vector3d()), 1.0E-6,
                "a rotation moves a block, it does not stretch the contraption");
        // The pivot's own block never moves, whatever the angle.
        assertVector(new Vector3d(0.0, 0.0, 0.0),
                pose.blockCentre(new Vector3d(0.0, 0.0, 0.0), new Vector3d()), 1.0E-6);
    }

    @Test
    @DisplayName("a non-right-angle rotation is honoured exactly")
    void arbitraryRotationIsHonoured() {
        // A contraption a little off axis: nothing may round this to 0 or 90.
        CarrierPose pose = CarrierPose.of(new Vector3d(),
                new Quaterniond().rotateY(Math.toRadians(9.5)));

        // A turn about a block's own centre leaves that centre exactly where it was.
        assertVector(new Vector3d(0.0, 0.0, 0.0),
                pose.blockCentre(new Vector3d(0.0, 0.0, 0.0), new Vector3d()), 1.0E-9);
        assertEquals(9.5, pose.rotationDegrees(), 1.0E-6,
                "and the pose reports what it is actually doing");
        assertFalse(pose.isStill());
    }

    @Test
    @DisplayName("the round trip is the identity for an arbitrary pose")
    void roundTripIsTheIdentity() {
        Quaterniond orientation = new Quaterniond()
                .rotateY(Math.toRadians(37.0))
                .rotateX(Math.toRadians(-12.5))
                .rotateZ(Math.toRadians(101.25));
        CarrierPose pose = new CarrierPose(new Vector3d(512.25, 63.5, -204.75), orientation,
                new Vector3d(0.5, 0.5, 0.5), 1.0);

        Vector3d local = new Vector3d(3.0, -7.0, 11.0);
        Vector3d world = pose.transformPosition(local, new Vector3d());
        Vector3d back = pose.transformPositionInverse(world, new Vector3d());

        assertVector(local, back, 1.0E-9);
    }

    @Test
    @DisplayName("a scaled pose round trips too")
    void scaledPoseRoundTrips() {
        CarrierPose pose = new CarrierPose(new Vector3d(1.0, 2.0, 3.0),
                new Quaterniond().rotateY(0.4), new Vector3d(0.5, 0.5, 0.5), 2.0);

        Vector3d local = new Vector3d(4.0, 5.0, 6.0);
        Vector3d back = pose.transformPositionInverse(
                pose.transformPosition(local, new Vector3d()), new Vector3d());

        assertVector(local, back, 1.0E-9);
    }

    /** The aliasing hazard: an output vector that is also an input cancels the rotation. */
    @Test
    @DisplayName("the output vector may be the caller's scratch without cancelling the rotation")
    void transformingIntoAReusedVectorIsSafe() {
        CarrierPose pose = CarrierPose.of(new Vector3d(), new Quaterniond().rotateY(Math.PI / 2.0));
        Vector3d scratch = new Vector3d(1.0, 0.0, 0.0);

        // The returned vector must be the caller's own object, holding the transformed answer.
        Vector3d result = pose.blockCentre(new Vector3d(1.0, 0.0, 0.0), scratch);

        assertSame(scratch, result);
        assertVector(new Vector3d(0.0, 0.0, -1.0), result, 1.0E-6);
    }

    // ------------------------------------------------ recovering an orientation --

    @Test
    @DisplayName("an orientation comes back out of three basis vectors")
    void basisColumnsRecoverTheOrientation() {
        Quaterniond original = new Quaterniond().rotateY(Math.toRadians(23.0))
                .rotateX(Math.toRadians(7.0));
        CarrierPose pose = CarrierPose.of(new Vector3d(), original);
        Vector3d[] basis = basisOf(pose);

        Quaterniond recovered = CarrierPose.fromBasisColumns(basis[0], basis[1], basis[2]);

        assertNotNull(recovered);
        // Compare rotations rather than origins: the recovered orientation is what has to match.
        CarrierPose rebuilt = CarrierPose.of(new Vector3d(), recovered);
        assertVector(rotate(pose, 1.0, 2.0, 3.0), rotate(rebuilt, 1.0, 2.0, 3.0), 1.0E-6);
    }

    /** The half-diagonal case: the vectors a caller hands in need not be unit length. */
    @Test
    @DisplayName("basis vectors are normalised, so a half-diagonal still recovers the rotation")
    void basisColumnsAreNormalised() {
        Quaterniond original = new Quaterniond().rotateY(Math.toRadians(31.0));
        CarrierPose pose = CarrierPose.of(new Vector3d(), original);
        Vector3d[] basis = basisOf(pose);
        Vector3d halfLength = new Vector3d(basis[0]).mul(0.5);

        Quaterniond recovered = CarrierPose.fromBasisColumns(halfLength, basis[1], basis[2]);

        assertNotNull(recovered, "a scaled direction is still a direction");
        CarrierPose rebuilt = CarrierPose.of(new Vector3d(), recovered);
        assertVector(rotate(pose, 2.0, -3.0, 4.0), rotate(rebuilt, 2.0, -3.0, 4.0), 1.0E-6);
    }

    @Test
    @DisplayName("a frame that is not a rotation is refused rather than turned into a NaN pose")
    void degenerateFramesAreRefused() {
        Vector3d zero = new Vector3d();
        assertNull(CarrierPose.fromBasisColumns(zero, zero, zero),
                "a collapsed frame has no orientation to recover");

        // Not mutually perpendicular: two of the three axes point the same way.
        assertNull(CarrierPose.fromBasisColumns(new Vector3d(1.0, 0.0, 0.0),
                new Vector3d(1.0, 0.0, 0.0), new Vector3d(0.0, 0.0, 1.0)));
    }

    @Test
    @DisplayName("a pose built from a contraption's basis uses its anchor, shifted by half a block")
    void contraptionBasisUsesTheAnchor() {
        // A contraption standing still: identity basis, anchor at the block it was assembled from.
        CarrierPose pose = CarrierPose.fromContraptionBasis(new Vector3d(100.0, 65.0, 200.0),
                new Vector3d(1.0, 0.0, 0.0), new Vector3d(0.0, 1.0, 0.0), new Vector3d(0.0, 0.0, 1.0));

        assertNotNull(pose);
        // A contraption turns about a block's centre, because that is what its blocks are.
        assertVector(new Vector3d(0.5, 0.5, 0.5), new Vector3d(pose.pivot()), EPSILON);
        // The origin is the anchor shifted by that half block, which is what makes the next lines work.
        assertVector(new Vector3d(100.5, 65.5, 200.5), new Vector3d(pose.position()), EPSILON);
        // Create's own transform subtracts a block centre, rotates, and adds the anchor back - so for an
        // unturned contraption the local coordinate (1, 0, 3) is the position anchor + (1, 0, 3).
        assertVector(new Vector3d(101.0, 65.0, 203.0),
                pose.transformPosition(new Vector3d(1.0, 0.0, 3.0), new Vector3d()), 1.0E-9);
        // A block's centre is half a block past its coordinate.
        assertVector(new Vector3d(101.5, 65.5, 203.5),
                pose.blockCentre(new Vector3d(1.0, 0.0, 3.0), new Vector3d()), 1.0E-9);
        // The anchor block, local (0, 0, 0), has its centre at the anchor plus half a block.
        assertVector(new Vector3d(100.5, 65.5, 200.5),
                pose.blockCentre(new Vector3d(0.0, 0.0, 0.0), new Vector3d()), 1.0E-9);
    }

    /** The invariant: `anchor + localPos` recovers the original block. */
    @Test
    @DisplayName("a turned contraption still puts neighbours exactly one block apart")
    void contraptionBasisKeepsNeighboursOneBlockApart() {
        // A quarter turn about +Y: a contraption on a bearing.
        CarrierPose pose = CarrierPose.of(new Vector3d(50.5, 64.5, 50.5),
                new Quaterniond().rotateY(Math.PI / 2.0));

        Vector3d first = pose.blockCentre(new Vector3d(0.0, 0.0, 0.0), new Vector3d());
        Vector3d second = pose.blockCentre(new Vector3d(1.0, 0.0, 0.0), new Vector3d());

        assertEquals(1.0, first.distance(second), 1.0E-6,
                "a quarter turn moves a block, it does not stretch the contraption");
    }

    /** The multi-block case, decided offline: Create's own transform and this class's are the same
     * function, evaluated literally below for a turned contraption at a local coordinate off the origin. */
    @Test
    @DisplayName("a turned contraption's own transform reproduces Create's toGlobalVector for a non-origin local coordinate")
    void ourTransformIsCreatesTransformForAMultiBlockStructure() {
        Vector3d anchor = new Vector3d(10.0, 64.0, 20.0);
        Quaterniond turn = new Quaterniond().rotateY(Math.toRadians(30.0));
        // The basis Create's applyRotation answers with: where it maps each world axis.
        Vector3d x = turn.transform(new Vector3d(1.0, 0.0, 0.0));
        Vector3d y = turn.transform(new Vector3d(0.0, 1.0, 0.0));
        Vector3d z = turn.transform(new Vector3d(0.0, 0.0, 1.0));

        CarrierPose pose = CarrierPose.fromContraptionBasis(anchor, x, y, z);
        assertNotNull(pose);

        // A host four blocks from the anchor in x and two in z: a structure with more than one block.
        BlockPos local = new BlockPos(4, 0, 2);
        Vector3d centre = new Vector3d(local.getX() + 0.5, local.getY() + 0.5, local.getZ() + 0.5);

        // Create's formula, evaluated literally: the rotation is applied with double arithmetic here
        // rather than through the float rotationMatrix(), which is where the tolerance comes from.
        Vector3d creates = new Vector3d(centre).sub(0.5, 0.5, 0.5);
        turn.transform(creates, creates);
        creates.add(0.5, 0.5, 0.5).add(anchor);

        assertVector(creates,
                pose.blockCentre(new Vector3d(local.getX(), local.getY(), local.getZ()),
                        new Vector3d()),
                1.0E-6);
        // And that is the *only* thing our side has to get right: blockCentre is the same map, read as
        // the transform of the block's centre point.
        assertVector(pose.transformPosition(centre, new Vector3d()),
                pose.blockCentre(new Vector3d(local.getX(), local.getY(), local.getZ()),
                        new Vector3d()),
                1.0E-12);
        // The same identity one block further out, to show it is not a coincidence of one coordinate.
        BlockPos far = new BlockPos(-3, 1, 5);
        Vector3d farCentre = new Vector3d(far.getX() + 0.5, far.getY() + 0.5, far.getZ() + 0.5);
        Vector3d farCreates = new Vector3d(farCentre).sub(0.5, 0.5, 0.5);
        turn.transform(farCreates, farCreates);
        farCreates.add(0.5, 0.5, 0.5).add(anchor);
        assertVector(farCreates,
                pose.blockCentre(new Vector3d(far.getX(), far.getY(), far.getZ()), new Vector3d()),
                1.0E-6);
    }

    // ----------------------------------------------------------- comparison --

    @Test
    @DisplayName("two poses that differ by a hair count as the same, so a parked machine is silent")
    void closeToIgnoresSubMillimetreDrift() {
        CarrierPose a = poseAt(1.0, 2.0, 3.0);
        CarrierPose b = CarrierPose.of(new Vector3d(1.0005, 2.0, 3.0),
                new Quaterniond().rotateY(1.0E-7));

        assertTrue(a.closeTo(b));
        assertFalse(a.closeTo(null), "an unknown pose is always worth sending");
        assertFalse(a.closeTo(poseAt(1.5, 2.0, 3.0)));
        assertFalse(a.closeTo(CarrierPose.of(new Vector3d(1.0, 2.0, 3.0),
                new Quaterniond().rotateY(Math.toRadians(2.0)))));
    }

    @Test
    @DisplayName("ONLY THE ORIENTATION CHANGING IS STILL A CHANGE: the structure turned, so send it")
    void orientationChangeAloneIsDirty() {
        // A bearing turns on the spot: a gate that only watches the position says "nothing changed" for
        // the whole rotation, and the client keeps drawing the angle it was last told.
        Vector3d pivot = new Vector3d(2775.5, 77.5, -1394.5);
        CarrierPose stopped = CarrierPose.of(pivot, new Quaterniond());

        for (double degrees : new double[] {0.25, 1.0, 5.0, 28.795, 33.596, 90.0, 179.0}) {
            CarrierPose turning = CarrierPose.of(pivot, new Quaterniond().rotateX(Math.toRadians(degrees)));
            assertFalse(stopped.closeTo(turning),
                    degrees + " degrees of rotation was treated as 'the same pose'");
            assertFalse(turning.closeTo(stopped),
                    "the comparison has to be symmetric at " + degrees + " degrees");
            assertEquals(0.0, turning.position().distance(stopped.position()), 1.0E-12,
                    "the pivot really did not move - that is why a position-only gate misses this");
        }
    }

    @Test
    @DisplayName("a quarter degree of rotation is already a change")
    void theOrientationToleranceIsRoundingAndNotAnAngle() {
        // The comparison uses 1 - |dot|, which for unit quaternions is 1 - cos(theta / 2): an angle, not
        // a rounding error.
        Vector3d pivot = new Vector3d(0.5, 0.5, 0.5);
        CarrierPose base = CarrierPose.of(pivot, new Quaterniond());

        CarrierPose oldTolerance = CarrierPose.of(pivot, new Quaterniond().rotateY(Math.toRadians(0.1)));
        assertFalse(base.closeTo(oldTolerance),
                "0.1 degrees is a real rotation and must be sent");

        // Two poses a rounding error apart are still the same pose, so a parked machine stays silent.
        CarrierPose jitter = CarrierPose.of(pivot, new Quaterniond().rotateY(1.0E-6));
        assertTrue(base.closeTo(jitter), "a millionth of a degree is jitter, not a turn");
        assertTrue(base.sameOrientation(jitter));
        assertFalse(base.sameOrientation(oldTolerance));
    }

    @Test
    @DisplayName("a quaternion and its negation are the same rotation, so they compare equal")
    void negatedQuaternionIsTheSameRotation() {
        // q and -q are the same orientation: without the absolute value of the dot product, a machine
        // that flipped sign mid-flight would be called turned and re-send its pose for ever.
        Vector3d pivot = new Vector3d(1.0, 2.0, 3.0);
        Quaterniond q = new Quaterniond().rotateZ(Math.toRadians(37.0));
        Quaterniond negated = new Quaterniond(-q.x, -q.y, -q.z, -q.w);

        CarrierPose a = CarrierPose.of(pivot, q);
        CarrierPose b = CarrierPose.of(pivot, negated);

        assertTrue(a.closeTo(b));
        assertEquals(0.0, a.orientationDeltaDegrees(b), 1.0E-6);
    }

    @Test
    @DisplayName("the orientation delta is reported in degrees, for the diagnostics")
    void orientationDeltaIsInDegrees() {
        Vector3d pivot = new Vector3d(0.5, 0.5, 0.5);
        CarrierPose base = CarrierPose.of(pivot, new Quaterniond());
        CarrierPose turned = CarrierPose.of(pivot, new Quaterniond().rotateX(Math.toRadians(28.795)));

        assertEquals(28.795, base.orientationDeltaDegrees(turned), 1.0E-6);
        assertEquals(28.795, turned.orientationDeltaDegrees(base), 1.0E-6);
    }

    // ------------------------------------------------------------------ NBT --

    @Test
    @DisplayName("a pose survives NBT, quaternion and all")
    void poseRoundTripsThroughNbt() {
        CarrierPose original = new CarrierPose(new Vector3d(1.25, -64.5, 300.125),
                new Quaterniond().rotateY(Math.toRadians(9.5)), new Vector3d(0.5, 0.5, 0.5), 1.0);

        CarrierPose loaded = CarrierPose.load(original.save());

        assertNotNull(loaded);
        assertVector(new Vector3d(1.25, -64.5, 300.125), new Vector3d(loaded.position()), EPSILON);
        assertEquals(9.5, loaded.rotationDegrees(), 1.0E-6);
        assertTrue(loaded.closeTo(original));
    }

    @Test
    @DisplayName("a broken pose reads as absent rather than as a NaN")
    void brokenPoseLoadsAsNull() {
        assertNull(CarrierPose.load(null));
        assertNull(CarrierPose.load(new CompoundTag()));

        CompoundTag zeroQuaternion = new CompoundTag();
        zeroQuaternion.putDouble("qw", 0.0);
        assertNull(CarrierPose.load(zeroQuaternion), "a zero quaternion is not a rotation");

        CompoundTag nan = new CompoundTag();
        nan.putDouble("qw", 1.0);
        nan.putDouble("px", Double.NaN);
        assertNull(CarrierPose.load(nan), "a NaN origin would draw nothing at all");
    }

    @Test
    @DisplayName("a loaded pose is normalised, so the arithmetic downstream is well behaved")
    void loadedPoseIsNormalised() {
        CompoundTag tag = new CompoundTag();
        tag.putDouble("qw", 2.0);
        tag.putDouble("qx", 0.0);
        tag.putDouble("qy", 0.0);
        tag.putDouble("qz", 0.0);
        tag.putDouble("px", 0.0);
        tag.putDouble("py", 0.0);
        tag.putDouble("pz", 0.0);

        CarrierPose loaded = CarrierPose.load(tag);

        assertNotNull(loaded);
        double magnitude = Math.sqrt(loaded.orientation().x * loaded.orientation().x
                + loaded.orientation().y * loaded.orientation().y
                + loaded.orientation().z * loaded.orientation().z
                + loaded.orientation().w * loaded.orientation().w);
        assertEquals(1.0, magnitude, EPSILON);
    }

    /** The identity pose is the documented "no pose yet" default, so it must really be the identity. */
    @Test
    @DisplayName("the identity pose is a no-op")
    void identityPoseIsANoOp() {
        CarrierPose identity = CarrierPose.identity();
        // The origin is the pivot, the pivot is a block's centre, and the two cancel.
        assertVector(new Vector3d(0.0, 0.0, 0.0),
                identity.transformPosition(new Vector3d(0.5, 0.5, 0.5), new Vector3d()), EPSILON);
        assertVector(new Vector3d(1.5, 2.5, 3.5),
                identity.blockCentre(new Vector3d(1.5, 2.5, 3.5), new Vector3d()), EPSILON);
        assertTrue(identity.isStill());
    }

    /** A pose's own description is what the diagnostics print, so it must not throw on anything. */
    @Test
    @DisplayName("a pose describes itself without throwing")
    void describeIsSafe() {
        assertNotNull(CarrierPose.identity().describe());
        assertNotNull(CarrierPose.of(new Vector3d(1.0, 2.0, 3.0),
                new Quaterniond().rotateY(1.0)).describe());
    }

    /** The transform must not touch the input vector: the renderer and the landing arithmetic hand in
     * vectors they reuse, one per host, once per frame. */
    @Test
    @DisplayName("transforming does not mutate the vector it was given")
    void transformsDoNotMutateTheirInputs() {
        CarrierPose pose = CarrierPose.of(new Vector3d(5.0, 0.0, 0.0),
                new Quaterniond().rotateY(0.6));
        Vector3d local = new Vector3d(1.0, 2.0, 3.0);
        Vector3d scratch = new Vector3d();

        Vector3d written = pose.blockCentre(local, scratch);

        assertVector(new Vector3d(1.0, 2.0, 3.0), local, 1.0E-12);
        assertSame(scratch, written, "the caller gets the vector it handed in");
        Vector3d expected = pose.transformPosition(new Vector3d(1.5, 2.5, 3.5), new Vector3d());
        assertVector(expected, scratch, 1.0E-12);
    }

    /** Guards the record against a null pivot, which would make every transform an NPE. */
    @Test
    @DisplayName("a null pivot is a programming error, not a silent zero")
    void nullPivotIsRejected() {
        assertThrows(NullPointerException.class, () -> new CarrierPose(new Vector3d(),
                new Quaterniond(), null, 1.0).transformPosition(new Vector3d(), new Vector3d()));
    }

    /** The pose a frame is drawn at, between two ticks. The two ends must be the arguments
     * <b>themselves</b>, so every non-render caller keeps whole-pose behaviour to the bit. */
    @Test
    @DisplayName("a lerp is each whole pose at its own end and halfway between them in the middle")
    void lerpBetweenTwoTicks() {
        CarrierPose from = new CarrierPose(new Vector3d(10.0, 64.0, 10.0), new Quaterniond(),
                new Vector3d(0.5, 0.5, 0.5), 1.0);
        CarrierPose to = new CarrierPose(new Vector3d(11.0, 70.0, 10.0), new Quaterniond(),
                new Vector3d(1.5, 0.5, 0.5), 2.0);

        assertSame(from, CarrierPose.lerp(0.0, from, to), "t = 0 is the previous tick, unchanged");
        assertSame(to, CarrierPose.lerp(1.0, from, to), "t = 1 is the current tick, unchanged");
        assertSame(to, CarrierPose.lerp(1.5, from, to), "and anything past 1 is clamped to it");

        CarrierPose half = CarrierPose.lerp(0.5, from, to);
        assertVector(new Vector3d(10.5, 67.0, 10.0), new Vector3d(half.position()), EPSILON);
        assertVector(new Vector3d(1.0, 0.5, 0.5), new Vector3d(half.pivot()), EPSILON);
        assertEquals(1.5, half.scale(), EPSILON, "the scale interpolates too");
    }

    /** A rotation must take the <b>short</b> way round: {@code q} and {@code -q} are the same rotation,
     * and a machine that turned 350 degrees between two ticks would otherwise spin almost all the way. */
    @Test
    @DisplayName("a turned machine interpolates the short way round, whatever the sign of the quaternion")
    void lerpTakesTheShortWayRound() {
        CarrierPose from = CarrierPose.at(new Vector3d(10.0, 64.0, 10.0));
        CarrierPose far = CarrierPose.of(new Vector3d(10.0, 64.0, 10.0),
                new Quaterniond().rotateY(Math.toRadians(350.0)));

        Vector3d halfway = CarrierPose.lerp(0.5, from, far).orientation()
                .transform(new Vector3d(1.0, 0.0, 0.0), new Vector3d());
        Vector3d shortWay = new Quaterniond().rotateY(Math.toRadians(-5.0))
                .transform(new Vector3d(1.0, 0.0, 0.0), new Vector3d());

        assertVector(shortWay, halfway, 1.0E-6);
    }

    /** Nothing to interpolate from is not an error: a snapshot, a landing and the first pose after a
     * teleport all arrive as one pose, and interpolating across that jump would be worse than the lead
     * this closes. */
    @Test
    @DisplayName("with no previous pose the current one is the answer, and with no current one nothing is")
    void lerpWithoutOneOfItsEnds() {
        CarrierPose to = poseAt(5.0, 64.0, 5.0);
        assertSame(to, CarrierPose.lerp(0.5, null, to), "no previous pose: the current one, whole");
        assertNull(CarrierPose.lerp(0.5, to, null), "no current pose: there is nothing to draw");
        assertNull(CarrierPose.lerp(0.5, null, null));
    }
}

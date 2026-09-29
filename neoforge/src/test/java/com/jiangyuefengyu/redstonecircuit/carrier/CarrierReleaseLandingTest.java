package com.jiangyuefengyu.redstonecircuit.carrier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import net.minecraft.core.BlockPos;

import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>Pins where a host lands when its structure goes away.</b>
 *
 * <h2>The defect this exists for</h2>
 * The author's log, verbatim:
 *
 * <pre>
 *   carrier s:98bef0d9 released wholesale (sable sub-level removed (REMOVED))
 *     LANDED origin=2775, 77, -1395 local=20481032, 127, 20524040 landing=2775, 77, -1395 moved=1
 * </pre>
 *
 * <p>The structure had been flown to {@code 2773,77,-1398} - the pose broadcast a moment earlier says so
 * - and the component was written back to the block the machine had picked it up from, four and a half
 * seconds before. The cause is that Sable had already taken the sub-level out of its container by the
 * time the removal hook ran, so the live pose query answered {@code null}, and the old rule read "no
 * pose" as "put it back where it came from".
 *
 * <p>The rule is now: <b>the origin is the fallback for a structure whose position was never known, not
 * for one whose position stopped being knowable a moment ago.</b> Both halves of that sentence are
 * asserted here, because the second half is the one that was wrong and it is the one that cannot be
 * checked in game without a flight.
 *
 * <h2>Why the expected landing is a block "below" the pose's own coordinate</h2>
 * A landing is computed by {@link CarrierProjection#project}, which takes the block <em>containing</em>
 * the host's centre - {@code BlockPos.containing}, i.e. a floor. A centre at {@code -1397.505} is inside
 * the block {@code -1398}, and that is also what the working Create path has always produced: the
 * author's own log pairs {@code pos=(2775.500, 77.500, -1394.500)} with {@code origin=2775, 77, -1395}.
 */
class CarrierReleaseLandingTest {

    /** Where the block was picked up, from the author's log. */
    private static final BlockPos ORIGIN = new BlockPos(2775, 77, -1395);

    /** The block inside the structure, from the author's log: a Sable plot coordinate. */
    private static final BlockPos LOCAL = new BlockPos(20481032, 127, 20524040);

    /** The pose the server last broadcast, from the author's log - the structure has moved away. */
    private static CarrierPose lastBroadcast() {
        return new CarrierPose(new Vector3d(2773.512, 78.083, -1397.505), new Quaterniond(),
                new Vector3d(20481032.5, 128.083, 20524040.5), 1.0);
    }

    /** A live pose, deliberately somewhere else again, so "which one won" is observable. */
    private static CarrierPose livePose() {
        return new CarrierPose(new Vector3d(2790.5, 80.583, -1400.5), new Quaterniond(),
                new Vector3d(20481032.5, 128.083, 20524040.5), 1.0);
    }

    @Test
    @DisplayName("with no live pose, the last pose the client was told is what the host lands on")
    void releaseWithALastPoseLandsWhereTheStructureIs() {
        CarriedHostIndex.Landing landing = CarrierStore.landingOnRelease(null, lastBroadcast());
        assertNotNull(landing, "a pose was known, so a landing position exists");
        BlockPos landed = landing.landingFor(LOCAL);
        assertEquals(new BlockPos(2773, 77, -1398), landed,
                "the component must come down where the structure actually is, not where it came from");
        assertNotEquals(ORIGIN, landed,
                "landing at the origin is exactly the defect: the component stayed behind");
    }

    @Test
    @DisplayName("with no pose at all, the recorded origin is still the answer")
    void releaseWithNoPoseAtAllFallsBackToTheOrigin() {
        assertNull(CarrierStore.landingOnRelease(null, null),
                "a null landing is what tells the index to use the recorded origin");
    }

    @Test
    @DisplayName("a live pose is preferred over the last one that was broadcast")
    void livePoseWins() {
        CarriedHostIndex.Landing live = CarrierStore.landingOnRelease(livePose(), lastBroadcast());
        assertNotNull(live);
        assertEquals(new BlockPos(2790, 80, -1401), live.landingFor(LOCAL),
                "the live transform is the better answer whenever it exists");
        assertNotEquals(CarrierStore.landingOnRelease(null, lastBroadcast()).landingFor(LOCAL),
                live.landingFor(LOCAL),
                "the two poses put the host in different places, so this test can tell them apart");
    }

    @Test
    @DisplayName("the landing the host records is the block the pose names, and the origin is not it")
    void landingIsWhereTheBlocksArtIs() {
        CarriedHostIndex.Landing landing = CarrierStore.landingOnRelease(null, lastBroadcast());
        assertNotNull(landing);
        // The same arithmetic the in-structure projection uses, so "where would this be put down now"
        // and "where is this now" cannot disagree.
        assertEquals(CarrierProjection.project(lastBroadcast(), LOCAL), landing.landingFor(LOCAL));
        assertEquals(new BlockPos(2773, 77, -1398), landing.landingFor(LOCAL));
        assertNotEquals(ORIGIN, landing.landingFor(LOCAL));
    }

    @Test
    @DisplayName("a Create contraption's release uses the live pose, which is the same rule")
    void contraptionUsesTheLivePose() {
        CarrierPose live = new CarrierPose(new Vector3d(2775.5, 77.5, -1394.5), new Quaterniond(),
                new Vector3d(0.5, 0.5, 0.5), 1.0);
        CarriedHostIndex.Landing landing = CarrierStore.landingOnRelease(live, null);
        assertNotNull(landing);
        // The author's own log line pairs the same pose with `origin=2775, 77, -1395`, which is what
        // makes this the pre-existing behaviour rather than a change to it.
        assertEquals(ORIGIN, landing.landingFor(new BlockPos(0, 0, 0)),
                "a contraption's local (0,0,0) lands at the block its anchor names");
    }

    @Test
    @DisplayName("a last pose that names the origin still lands there, which is not a special case")
    void aPosePointingHomeLandsAtHome() {
        CarrierPose home = new CarrierPose(new Vector3d(2775.5, 78.083, -1394.5), new Quaterniond(),
                new Vector3d(20481032.5, 128.083, 20524040.5), 1.0);
        CarriedHostIndex.Landing landing = CarrierStore.landingOnRelease(null, home);
        assertNotNull(landing);
        assertEquals(ORIGIN, landing.landingFor(LOCAL),
                "a machine that was put down where it was built lands its components there, by the "
                        + "pose rather than by the fallback");
    }
}

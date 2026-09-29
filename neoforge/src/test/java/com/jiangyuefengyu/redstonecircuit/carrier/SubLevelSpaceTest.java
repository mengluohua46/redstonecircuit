package com.jiangyuefengyu.redstonecircuit.carrier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import net.minecraft.core.BlockPos;

import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests the Sable-side claim's coordinate contract: <b>which world blocks a sub-level is holding, and
 * what their local coordinates are.</b>
 *
 * <h2>Why this is the test that matters for the reported defect</h2>
 * The author's report was that a component in a physicalised structure does not follow the structure and
 * cannot be seen. The cause was that nothing on the Sable side ever claimed it. The fix is this
 * arithmetic, and the arithmetic has one property that has to be exactly right: a local coordinate
 * written into the carrier index must be one that the same structure's own pose transforms back to the
 * block the component came from. Half a block out and every carried component sits beside its block;
 * in the wrong space entirely and it is drawn twenty million blocks away - which is the failure the
 * attached log's {@code local (20481033.500, 132.000, 20485129.500)} is a record of.
 *
 * <h2>What is pinned here</h2>
 * <ul>
 *   <li>the round trip {@code world -> local -> world} is the identity through a Sable-shaped pose
 *       (a nine-figure translation, a rotation point in the twenty-millions);</li>
 *   <li>a pose that does not round-trip is <b>refused</b>, not guessed at, because the alternative is
 *       filing a component under a coordinate that draws it somewhere else;</li>
 *   <li>the candidate enumeration is bounded by, and filtered from, the world store's own positions -
 *       never by walking the plot's millions of empty coordinates;</li>
 *   <li>nothing is claimed outside the sub-level's own world bounds, which is what stops a sub-level
 *       allocated over a house from taking the house's components out of it.</li>
 * </ul>
 *
 * <p>Nothing here needs Sable: {@link SubLevelSpace} is deliberately made of a UUID, six integers,
 * six doubles and a {@link CarrierPose}.
 */
class SubLevelSpaceTest {

    private static final UUID SUB_LEVEL = UUID.fromString("4e5560da-1111-2222-3333-444455556666");

    /**
     * A pose shaped like a real sub-level's: the kind of numbers the P1 diagnostics measured.
     *
     * <p>A translation of a few hundred blocks, a rotation point in the twenty-millions, and a local
     * coordinate in the twenty-millions. This is the case that has to work, and the case that fails in a
     * way nothing but a coordinate comparison can see.
     */
    private static CarrierPose sablePose() {
        return new CarrierPose(
                new Vector3d(-352.549, 171.456, 92.766),
                new Quaterniond(),
                new Vector3d(20_481_000.0, 100.0, 20_485_000.0),
                1.0);
    }

    /** A sub-level whose world bounds are exactly the eight blocks of a 2x2x2 local box, identity pose. */
    private static SubLevelSpace boxAt(BlockPos origin, CarrierPose pose) {
        return new SubLevelSpace(SUB_LEVEL,
                origin.getX(), origin.getY(), origin.getZ(),
                origin.getX() + 1, origin.getY() + 1, origin.getZ() + 1,
                origin.getX(), origin.getY(), origin.getZ(),
                origin.getX() + 2.0, origin.getY() + 2.0, origin.getZ() + 2.0,
                pose);
    }

    @Test
    @DisplayName("the block a fractional world point falls in is rounded, so the round trip closes")
    void roundedBlockRecoversTheLocalCoordinate() {
        // The convention this pins is the one a Sable pose forces. A sub-level's world positions are
        // fractional (the measured example is -352.549), so a block's own corner can land at -319.5;
        // flooring that names block -320, and the coordinate that comes back is then a whole block away
        // from the one that produced it. Rounding keeps the round trip the identity, which is what the
        // claim's own verification rests on.
        var pose = sablePose();
        SubLevelSpace space = new SubLevelSpace(SUB_LEVEL,
                20_481_033, 132, 20_485_129, 20_481_033, 132, 20_485_129,
                -352.0, 171.0, 92.0, -351.0, 172.0, 93.0, pose);

        BlockPos local = new BlockPos(20_481_033, 132, 20_485_129);
        Vector3d world = space.worldFor(local);
        assertNotNull(world);

        // The pose produces exactly what the same pose produces: no second convention, no drift.
        Vector3d expected = pose.transformPosition(
                new Vector3d(20_481_033.0, 132.0, 20_485_129.0), new Vector3d());
        assertEquals(expected.x, world.x, 1.0E-9);
        assertEquals(expected.y, world.y, 1.0E-9);
        assertEquals(expected.z, world.z, 1.0E-9);

        BlockPos worldBlock = SubLevelSpace.nearestWorldBlock(world);
        assertEquals(local, space.localFor(worldBlock),
                "the local coordinate that produced this block must come back out of it");
        assertTrue(space.verify(worldBlock, local, ROUND_TRIP),
                "and the verification the claim runs must accept it");
    }

    /** The tolerance the claim uses: a whole block, which is the floor of what a fractional pose costs. */
    private static final double ROUND_TRIP = 1.0;

    @Test
    @DisplayName("a local coordinate from another space is refused, not silently accepted")
    void wrongSpaceIsRefused() {
        // The failure this refuses is the reported one: a plot coordinate used as if it were a world
        // coordinate (or a pose from a different structure). Claiming through it would file the
        // component under a coordinate that draws it twenty million blocks away - precisely the state
        // the attached log's `local (20481033.500, 132.000, 20485129.500)` records.
        var pose = sablePose();
        SubLevelSpace space = new SubLevelSpace(SUB_LEVEL,
                20_481_033, 132, 20_485_129, 20_481_033, 132, 20_485_129,
                -352.0, 171.0, 92.0, -351.0, 172.0, 93.0, pose);

        // A world block a *different* structure is standing on, read through this structure's pose. The
        // local coordinate that comes out is a perfectly ordinary-looking integer, and it round-trips
        // through this pose - which is exactly why the second gate is the structure's own world bounds:
        // a candidate outside them is not this structure's block, whatever the arithmetic says.
        BlockPos elsewhere = new BlockPos(-100, 70, -100);
        assertNotNull(space.localFor(elsewhere), "the pose will answer for any point at all");
        assertFalse(space.containsWorld(elsewhere),
                "but the block is not inside this sub-level, so the claim must skip it");

        // And a block inside the structure that the pose does not put back is refused outright.
        BlockPos local = new BlockPos(20_481_033, 132, 20_485_129);
        Vector3d world = space.worldFor(local);
        BlockPos worldBlock = SubLevelSpace.nearestWorldBlock(world);
        assertTrue(space.verify(worldBlock, local, ROUND_TRIP));
        assertEquals(local, space.localForVerified(worldBlock, ROUND_TRIP),
                "the verification the claim runs must accept the coordinate that produced this block");

        // A *different* local coordinate at the same world position: the pose puts that one somewhere
        // else, so the round trip fails and the claim refuses, rather than filing a component under a
        // coordinate that draws it away from its block.
        BlockPos otherLocal = local.offset(5, 0, 0);
        assertNotEquals(local, otherLocal);
        assertFalse(space.verify(worldBlock, otherLocal, ROUND_TRIP));
        assertNotNull(space.worldFor(otherLocal));
        assertTrue(space.worldFor(otherLocal).distance(world) > 1.0);

        // A null pose is the same refusal, one step earlier: with no pose there is no coordinate space
        // at all, and the honest answer is "cannot".
        SubLevelSpace noPose = new SubLevelSpace(SUB_LEVEL, 0, 0, 0, 1, 1, 1,
                0.0, 0.0, 0.0, 2.0, 2.0, 2.0, null);
        assertFalse(noPose.hasPose());
        assertNull(noPose.localFor(new BlockPos(0, 0, 0)));
        assertNull(noPose.worldFor(new BlockPos(0, 0, 0)));
        assertTrue(noPose.worldPositionsWithComponents(pos -> true, 10).isEmpty());
    }

    @Test
    @DisplayName("a half block of convention error is caught, not excused")
    void halfBlockErrorIsRefused() {
        // The half block is the mistake this code is most likely to make: a block's local coordinate
        // names its corner, and using the block-centre form of the transform would file every component
        // half a block out - which, once rounded to a block, is the *next* block. That is small enough
        // to look like a rendering wobble in game, so it is worth a test that says the verification
        // catches it rather than tolerating it.
        SubLevelSpace space = new SubLevelSpace(SUB_LEVEL, 0, 0, 0, 1, 1, 1,
                10.0, 64.0, 10.0, 12.0, 66.0, 12.0,
                new CarrierPose(new Vector3d(10.0, 64.0, 10.0), new Quaterniond(),
                        new Vector3d(0.0, 0.0, 0.0), 1.0));

        // Identity pose: local (0,0,0) is the block at world (10,64,10), and the claim accepts it.
        assertEquals(new BlockPos(0, 0, 0), space.localFor(new BlockPos(10, 64, 10)));
        assertTrue(space.verify(new BlockPos(10, 64, 10), new BlockPos(0, 0, 0), 1.0E-6));

        // The block-centre convention puts local (0,0,0) at world (10.5, 64.5, 10.5) - the neighbouring
        // block, not this one - and the round trip through that point does not land back on (0,0,0).
        Vector3d centre = space.pose().blockCentre(new Vector3d(0, 0, 0), new Vector3d());
        assertEquals(10.5, centre.x, 1.0E-9);
        assertEquals(new BlockPos(1, 1, 1), space.localFor(SubLevelSpace.nearestWorldBlock(centre)));
        assertFalse(space.verify(SubLevelSpace.nearestWorldBlock(centre),
                new BlockPos(0, 0, 0), 1.0E-6),
                "the block-centre convention must not verify, or the check is not a check");
        assertFalse(space.verify(new BlockPos(10, 64, 10), new BlockPos(1, 1, 1), 1.0E-6));
    }

    @Test
    @DisplayName("candidates are the structure's own blocks that hold a component, and nothing outside it")
    void candidatesAreTheStructuresBlocksThatHoldComponents() {
        // Identity pose, so a local coordinate and the world block it names coincide.
        //
        // WHY THIS IS THE PROPERTY THAT MATTERS. The claim's gate 2 (the pose round trip) passes for
        // *any* world point - a pose is affine and answers for the whole world - so a candidate at the
        // origin of a component some other machine is carrying would be accepted by it. What makes a
        // candidate ours is the world block its local coordinate names, checked against the world
        // bounds Sable maintains from the blocks it really holds.
        SubLevelSpace space = new SubLevelSpace(SUB_LEVEL, 10, 64, 10, 11, 65, 11,
                10.0, 64.0, 10.0, 12.0, 66.0, 12.0,
                new CarrierPose(new Vector3d(0.0, 0.0, 0.0), new Quaterniond(),
                        new Vector3d(0.0, 0.0, 0.0), 1.0));

        Set<BlockPos> components = new HashSet<>(Set.of(
                new BlockPos(10, 64, 10),     // inside, has a component
                new BlockPos(11, 65, 11),     // inside and at the far corner, has a component
                new BlockPos(9, 64, 10),      // just outside, has a component
                new BlockPos(12, 66, 12),     // just outside the far corner, has a component
                new BlockPos(2755, 70, -1395) // far outside, has a component
        ));

        List<BlockPos> found = space.worldPositionsWithComponents(components::contains, 100);
        assertEquals(Set.of(new BlockPos(10, 64, 10), new BlockPos(11, 65, 11)),
                Set.copyOf(found),
                "the structure's own blocks that hold a component, and nothing else");
        assertFalse(found.contains(new BlockPos(9, 64, 10)),
                "a component one block outside the sub-level is not this sub-level's");
        assertFalse(found.contains(new BlockPos(12, 66, 12)),
                "nor is one past its far corner");

        // An in-bounds block with nothing in it is not a candidate either: the walk asks the caller for
        // every coordinate of the structure, and only the ones it says hold a component come back. The
        // component at (11, 64, 10) - a real block of this structure - is deliberately absent from the
        // set above, and it does not appear in the answer.
        assertEquals(2, found.size());

        // A second structure one block over must not see any of them, so the filter is not simply
        // "everything the caller says has a component".
        SubLevelSpace elsewhere = new SubLevelSpace(SUB_LEVEL, 5, 64, 10, 6, 65, 11,
                5.0, 64.0, 10.0, 7.0, 66.0, 12.0,
                new CarrierPose(new Vector3d(0.0, 0.0, 0.0), new Quaterniond(),
                        new Vector3d(0.0, 0.0, 0.0), 1.0));
        assertTrue(elsewhere.worldPositionsWithComponents(components::contains, 100).isEmpty());

        // And a structure with the full eight blocks finds all eight, which is the count the whole-box
        // case has to give: the walk is over the local bounds inclusive on both sides.
        assertEquals(8, space.worldPositionsWithComponents(pos -> true, 100).size());
    }

    @Test
    @DisplayName("the bounds test uses the block's centre, so a half-covered block counts")
    void containsWorldUsesTheCentre() {
        // Sable reports its bounds with the max side plus one, so the box is a half-open block range and
        // the centre is the test that matches it. A corner-based test would drop the last block of every
        // structure or take one past the end.
        SubLevelSpace space = new SubLevelSpace(SUB_LEVEL, 0, 0, 0, 0, 0, 0,
                10.0, 64.0, 10.0, 11.0, 65.0, 11.0, null);

        assertTrue(space.containsWorld(new BlockPos(10, 64, 10)));
        assertFalse(space.containsWorld(new BlockPos(9, 64, 10)));
        assertFalse(space.containsWorld(new BlockPos(11, 64, 10)));
        assertFalse(space.containsWorld(new BlockPos(0, 0, 0)));
        assertFalse(space.containsWorld(null));
    }

    @Test
    @DisplayName("the structure's position is the centre of its own bounds, not the pose's pivot")
    void worldCentreIsTheBoundsCentre() {
        // The draw check compares a drawn point against this, and a pivot is not the answer: a machine
        // whose blocks are all on one side of its pivot would look "off" by its own size. The bounds are
        // what every physics library maintains and what a player sees.
        SubLevelSpace space = new SubLevelSpace(SUB_LEVEL, 0, 0, 0, 1, 1, 1,
                -352.0, 171.0, 92.0, -348.0, 173.0, 96.0, sablePose());

        Vector3d centre = space.worldCentre();
        assertEquals(-350.0, centre.x, 1.0E-9);
        assertEquals(172.0, centre.y, 1.0E-9);
        assertEquals(94.0, centre.z, 1.0E-9);
    }

    @Test
    @DisplayName("the carrier identity is the sub-level's UUID and nothing else")
    void carrierIsKeyedByTheSubLevelUuid() {
        // The anchor and the plot position are both positions, and both change: a sub-level that is taken
        // apart and rebuilt keeps neither. A claim filed under either would be unreachable afterwards,
        // which is exactly the "claimed but never drawn again" shape this whole round is about.
        SubLevelSpace space = boxAt(new BlockPos(10, 64, 10), null);
        assertEquals(CarrierId.subLevel(SUB_LEVEL), space.carrier());
        assertTrue(space.carrier().isSubLevel());
        assertFalse(space.carrier().isContraption());
        assertEquals("s:4e5560da", space.carrier().shortId());
    }

    @Test
    @DisplayName("the candidate limit is honoured, so one structure cannot make a tick unbounded")
    void candidateLimitIsHonoured() {
        SubLevelSpace space = new SubLevelSpace(SUB_LEVEL, 0, 0, 0, 7, 7, 7,
                0.0, 0.0, 0.0, 8.0, 8.0, 8.0,
                new CarrierPose(new Vector3d(0.0, 0.0, 0.0), new Quaterniond(),
                        new Vector3d(0.0, 0.0, 0.0), 1.0));

        // Every block holds a component; the limit is what bounds the pass.
        assertEquals(10, space.worldPositionsWithComponents(pos -> true, 10).size());
        assertEquals(0, space.worldPositionsWithComponents(pos -> true, 0).size());
    }
}

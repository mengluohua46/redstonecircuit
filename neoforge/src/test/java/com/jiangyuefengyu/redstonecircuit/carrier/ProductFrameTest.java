package com.jiangyuefengyu.redstonecircuit.carrier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

/**
 * The frame a placed structure's six faces are read in.
 *
 * <p>一条规则: "物理化之后按它看起来在哪" - so a block nothing is carrying reads exactly where it stands, and a carried
 * block's neighbour and side both go through the carrier's own pose - position through {@link CarrierProjection} (the
 * block centre, as every other carried position in this mod), direction through {@link CarrierDirections} (the pose's
 * quaternion, never an Euler order).
 */
class ProductFrameTest {

    @Test
    void aBlockNothingCarriesAsksExactlyWhereItStands() {
        ProductFrame frame = ProductFrame.carriedAt(null, new BlockPos(4, 5, 6));

        assertSame(ProductFrame.inPlace(), frame);
        assertFalse(frame.carried());
        assertNull(frame.carrier());
        assertEquals(new BlockPos(7, 5, 6), frame.worldPosOf(new BlockPos(7, 5, 6)));
        assertEquals(Direction.EAST, frame.worldFace(Direction.EAST));
        assertTrue(frame.describe().contains("in-place"), frame.describe());
    }

    @Test
    void aMissingPoseIsTheInPlaceFrameRatherThanAGuess() {
        assertSame(ProductFrame.inPlace(), ProductFrame.of(null));
    }

    @Test
    void aCarriedStructureAsksAboutTheNeighbourItIsDrawnAgainst() {
        ProductFrame frame = ProductFrame.of(CarrierPose.at(new Vector3d(10.0, 0.0, 0.0)));

        assertTrue(frame.carried());
        assertNull(frame.carrier());
        // Ten blocks east: the neighbour the player sees on the other side of the face, not the storage's own.
        assertEquals(new BlockPos(13, 4, 5), frame.worldPosOf(new BlockPos(3, 4, 5)));
        // A translation leaves the sides alone: up is still up.
        assertEquals(Direction.UP, frame.worldFace(Direction.UP));
        assertEquals(Direction.DOWN, frame.worldFace(Direction.DOWN));
        assertTrue(frame.describe().contains("storage=carrier"), frame.describe());
    }

    @Test
    void aTurnedStructureAsksInWorldDirections() {
        Quaterniond yaw = new Quaterniond().rotateY(Math.toRadians(90.0));
        ProductFrame frame = ProductFrame.of(CarrierPose.of(new Vector3d(0.0, 0.0, 0.0), yaw));

        Direction north = frame.worldFace(Direction.NORTH);
        assertNotEquals(Direction.NORTH, north);
        assertEquals(0, north.getStepY());
        assertEquals(Direction.UP, frame.worldFace(Direction.UP));
        assertEquals(north.getOpposite(), frame.worldFace(Direction.SOUTH));

        // And the position turns with it: three blocks along +X land three blocks along an axis, not on X.
        BlockPos turned = frame.worldPosOf(new BlockPos(3, 0, 0));
        assertEquals(0, turned.getX());
        assertEquals(3, Math.abs(turned.getZ()));
    }

    @Test
    void thePositionIsTheBlockCentreRoundedToTheGrid() {
        // The offset really does move a coordinate across a block boundary (the centre at world Z 4.5 is the block
        // 4), and a negative offset floors the same way - which is the arithmetic every other carried position in
        // this mod uses (CarrierProjection#project).
        ProductFrame frame = ProductFrame.of(CarrierPose.at(new Vector3d(0.5, 0.0, -0.5)));

        assertEquals(new BlockPos(3, 4, 4), frame.worldPosOf(new BlockPos(3, 4, 5)));
        assertEquals(new BlockPos(0, 0, -1), frame.worldPosOf(new BlockPos(0, 0, 0)));
    }
}

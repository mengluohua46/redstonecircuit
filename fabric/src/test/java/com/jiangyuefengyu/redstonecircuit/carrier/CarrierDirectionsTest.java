package com.jiangyuefengyu.redstonecircuit.carrier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.Slot;
import com.jiangyuefengyu.redstonecircuit.logic.PowerSolver;

import net.minecraft.core.Direction;

import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests the one piece of arithmetic in the bridge: turning a local side into a world side, and a world signal
 * query back into the structure's own frame.
 *
 * <p>This decides which way a machine's repeater points once the machine has been turned, and getting it wrong
 * is invisible in the common case: an unturned structure maps every direction to itself, so a conversion that
 * is the identity - or the conjugate - looks perfect until somebody rotates a bearing. So it round-trips for
 * right angles and for arbitrary ones.
 */
class CarrierDirectionsTest {

    /** A quarter turn about Y, and the same again: the orientation of a structure turned 180 degrees. */
    private static final Quaterniond HALF_TURN = new Quaterniond().rotateY(Math.PI);

    @Test
    @DisplayName("an unturned structure maps every side to itself")
    void identityIsTheIdentity() {
        Quaterniond none = new Quaterniond();
        for (Direction direction : Direction.values()) {
            assertEquals(direction, CarrierDirections.worldDirection(none, direction), "world " + direction);
            assertEquals(direction, CarrierDirections.localDirection(none, direction), "local " + direction);
        }
    }

    @Test
    @DisplayName("with no orientation at all the direction is passed through")
    void noOrientationIsNoRotation() {
        assertEquals(Direction.EAST, CarrierDirections.worldDirection((Quaterniond) null, Direction.EAST));
        assertEquals(Direction.UP, CarrierDirections.localDirection((Quaterniond) null, Direction.UP));
        assertEquals(Direction.EAST, CarrierDirections.worldDirection((CarrierPose) null, Direction.EAST));
    }

    @Test
    @DisplayName("a structure turned 180 degrees points every side the other way, except up and down")
    void halfTurnSwapsTheHorizontalSides() {
        assertEquals(Direction.WEST, CarrierDirections.worldDirection(HALF_TURN, Direction.EAST));
        assertEquals(Direction.EAST, CarrierDirections.worldDirection(HALF_TURN, Direction.WEST));
        assertEquals(Direction.SOUTH, CarrierDirections.worldDirection(HALF_TURN, Direction.NORTH));
        assertEquals(Direction.NORTH, CarrierDirections.worldDirection(HALF_TURN, Direction.SOUTH));
        // The turn is about the Y axis, so the vertical sides cannot move.
        assertEquals(Direction.UP, CarrierDirections.worldDirection(HALF_TURN, Direction.UP));
        assertEquals(Direction.DOWN, CarrierDirections.worldDirection(HALF_TURN, Direction.DOWN));
    }

    @Test
    @DisplayName("a quarter turn is exact and round-trips, because axes still map onto axes")
    void quarterTurnRoundTrips() {
        Quaterniond quarter = new Quaterniond().rotateY(Math.PI / 2.0);
        for (Direction direction : Direction.values()) {
            Direction world = CarrierDirections.worldDirection(quarter, direction);
            assertEquals(direction, CarrierDirections.localDirection(quarter, world),
                    "and back again for " + direction);
        }
        // A quarter turn about Y leaves the vertical axis alone and moves every horizontal side.
        assertEquals(Direction.UP, CarrierDirections.worldDirection(quarter, Direction.UP));
        assertTrue(CarrierDirections.worldDirection(quarter, Direction.EAST).getAxis()
                        == Direction.Axis.Z,
                "east is now along Z - the turn really happened");
    }

    @Test
    @DisplayName("an arbitrary rotation still round-trips, so the quaternion is applied and not its inverse")
    void arbitraryRotationRoundTrips() {
        // Two axes, because a single-axis rotation would round-trip under a conjugate quaternion too.
        Quaterniond tilted = new Quaterniond().rotateY(0.3).rotateX(0.2).rotateZ(0.1);
        for (Direction direction : Direction.values()) {
            Direction world = CarrierDirections.worldDirection(tilted, direction);
            assertEquals(direction, CarrierDirections.localDirection(tilted, world),
                    "local -> world -> local must be the identity for " + direction);
        }
    }

    @Test
    @DisplayName("a side that points between two axes snaps to the nearer one")
    void betweenTheAxesSnapsToTheNearest() {
        // 40 degrees from east towards north: still east.
        assertEquals(Direction.EAST,
                CarrierDirections.worldDirection(new Quaterniond().rotateY(0.7), Direction.EAST));
        // 80 degrees: now nearer north.
        assertEquals(Direction.NORTH,
                CarrierDirections.worldDirection(new Quaterniond().rotateY(1.4), Direction.EAST));
    }

    @Test
    @DisplayName("a vector with no direction at all answers north rather than throwing")
    void degenerateVectorsAreSafe() {
        assertEquals(Direction.NORTH, CarrierDirections.nearest(null));
        assertEquals(Direction.NORTH, CarrierDirections.nearest(new Vector3d(Double.NaN, 0, 0)));
        assertEquals(Direction.NORTH, CarrierDirections.nearest(new Vector3d(0, 0, 0)));
        assertEquals(Direction.DOWN, CarrierDirections.nearest(new Vector3d(0, -2, 0)),
                "a non-unit vector is still a direction");
    }

    /**
     * The case a player would notice: turn the machine around and its repeater drives the world side it is now
     * pointing at.
     */
    @Test
    @DisplayName("a repeater in a structure turned 180 degrees drives the world side it now faces")
    void aTurnedRepeaterDrivesTheOtherWorldSide() {
        Slot repeater = new Slot(ComponentType.REPEATER);
        // Reads from the south, so it drives north - in the structure's own coordinates, which do not turn.
        repeater.facing = Direction.SOUTH;

        // Before the turn: a query arriving from the world's north is answered.
        assertTrue(emitsFromWorldSide(repeater, new Quaterniond(), Direction.NORTH));
        assertFalse(emitsFromWorldSide(repeater, new Quaterniond(), Direction.SOUTH));

        // After a half turn the structure's "north" is the world's south, so the answer swaps.
        assertTrue(emitsFromWorldSide(repeater, HALF_TURN, Direction.SOUTH));
        assertFalse(emitsFromWorldSide(repeater, HALF_TURN, Direction.NORTH));
    }

    /**
     * A vanilla signal query arriving from {@code worldSide}: does this component emit towards it. The exact
     * shape of the conversion {@code BlockStateSignalMixin} performs.
     */
    private static boolean emitsFromWorldSide(Slot slot, Quaterniond orientation, Direction worldSide) {
        Direction local = CarrierDirections.localDirection(orientation, worldSide);
        return PowerSolver.emitsToward(slot, local);
    }
}

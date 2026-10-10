package com.jiangyuefengyu.redstonecircuit.carrier;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The other half of a carried machine's frame: a local side, turned into the world position and the world direction a
 * vanilla signal query has to be asked about. Both halves fail independently.
 *
 * <p><b>The position</b> is <em>not</em> "the host's world position plus the local direction"; it is the projection of
 * the neighbour's own local coordinate. For a right angle the two agree, which is exactly why a test that only ever
 * uses an unturned machine would not notice the difference.
 *
 * <p><b>The direction</b>: vanilla asks "what do you emit towards me", so the local side is turned into the world
 * through the pose's quaternion.
 */
class CarrierWorldIoTest {

    private static BlockPos pos(int x, int y, int z) {
        return new BlockPos(x, y, z);
    }

    /** Records what the world was asked, so the position and the direction can both be asserted. */
    private static final class Recorder implements CarrierWorldSignals {

        final List<BlockPos> positions = new ArrayList<>();
        final List<Direction> directions = new ArrayList<>();

        @Override
        public int externalSignal(BlockPos worldPos, Direction worldDirection) {
            positions.add(worldPos);
            directions.add(worldDirection);
            return 7;
        }
    }

    @Test
    @DisplayName("an unturned structure reads the block next to where it is")
    void unturnedReadsTheNeighbour() {
        Recorder world = new Recorder();
        CarrierWorldIo io = new CarrierWorldIo(CarrierPose.at(new Vector3d(10.0, 64.0, 10.0)), world);

        assertEquals(pos(11, 64, 12), io.worldPosOf(pos(1, 0, 2)),
                "the host itself is at anchor + local");
        assertEquals(7, io.signalInto(pos(1, 0, 2), Direction.EAST));
        assertEquals(List.of(pos(12, 64, 12)), world.positions, "the block to its east, in the world");
        assertEquals(List.of(Direction.EAST), world.directions,
                "and the query points from the host towards that neighbour, in the same frame");
    }

    @Test
    @DisplayName("a structure turned 180 degrees reads the block on the other side")
    void turnedReadsTheMirroredNeighbour() {
        Recorder world = new Recorder();
        CarrierWorldIo io = new CarrierWorldIo(
                CarrierPose.of(new Vector3d(10.0, 64.0, 10.0), new Quaterniond().rotateY(Math.PI)),
                world);

        io.signalInto(pos(1, 0, 2), Direction.EAST);

        // Local east is world west, and the neighbour's local coordinate mirrors with it: local
        // (2,0,2) is world (8,64,8), not (12,64,12).
        assertEquals(List.of(pos(8, 64, 8)), world.positions);
        assertEquals(List.of(Direction.WEST), world.directions);
    }

    @Test
    @DisplayName("the omnidirectional supply is the strongest of the six sides")
    void signalAroundTakesTheBestSide() {
        CarrierWorldIo io = new CarrierWorldIo(CarrierPose.at(new Vector3d(10.0, 64.0, 10.0)),
                (worldPos, worldDirection) -> 0);
        assertEquals(0, io.signalAround(pos(1, 0, 2)), "nothing around it");

        // Only one side is live, and the maximum has to find it whichever direction it is on.
        CarrierWorldIo oneSide = new CarrierWorldIo(CarrierPose.at(new Vector3d(10.0, 64.0, 10.0)),
                (worldPos, worldDirection) -> worldPos.equals(pos(11, 64, 13)) ? 11 : 0);
        assertEquals(11, oneSide.signalAround(pos(1, 0, 2)));

        // And a value above the maximum is clamped rather than handed on.
        CarrierWorldIo tooStrong = new CarrierWorldIo(CarrierPose.at(new Vector3d(10.0, 64.0, 10.0)),
                (worldPos, worldDirection) -> 99);
        assertEquals(15, tooStrong.signalAround(pos(1, 0, 2)));
        assertEquals(15, tooStrong.signalInto(pos(1, 0, 2), Direction.EAST));
    }

    @Test
    @DisplayName("a read on a null side is nothing, not a crash")
    void aNullSideAnswersNothing() {
        CarrierWorldIo io = new CarrierWorldIo(CarrierPose.at(new Vector3d()),
                (worldPos, worldDirection) -> 15);
        assertEquals(0, io.signalInto(pos(0, 0, 0), null));
    }
}

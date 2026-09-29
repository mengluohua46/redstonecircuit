package com.jiangyuefengyu.redstonecircuit.carrier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.Slot;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests P4's projection table: where a carried host is in the world, and what the table says when the
 * world asks.
 *
 * <h2>The three contracts</h2>
 * <ol>
 *   <li><b>The arithmetic is the landing arithmetic.</b> An unturned structure's host at local
 *       {@code (1,0,2)} is at {@code anchor + (1,0,2)} - the half-block pivot cancels, which is the
 *       property that makes "where it is now" and "where it would be put down" the same question.</li>
 *   <li><b>A closed bridge answers nothing even though the table is built.</b> {@code begin(dir,false)}
 *       is what {@code carrierFreezeWorldIo = true} asks for, and the difference from "no table at all"
 *       must not be observable.</li>
 *   <li><b>The change pass reports exactly the positions whose projection moved, appeared, vanished or
 *       changed value.</b> That list is what the world is notified about, and a lamp that is never
 *       notified never lights - so an empty list where something moved is a silent failure.</li>
 * </ol>
 */
class CarrierProjectionTest {

    private static final CarrierId CONTRAPTION =
            CarrierId.contraption(UUID.fromString("00000000-0000-0000-0000-00000000c0ff"));
    private static final ResourceKey<Level> DIMENSION =
            ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
                    ResourceLocation.parse("redstonecircuit:test"));

    private static BlockPos pos(int x, int y, int z) {
        return new BlockPos(x, y, z);
    }

    /** The pose a contraption anchored at {@code (10,64,10)} has: pivot half a block along, no turn. */
    private static CarrierPose poseAt(double x, double y, double z) {
        return CarrierPose.at(new Vector3d(x, y, z));
    }

    private static CarrierProjection.ProjectedHost host(CarrierId carrier, BlockPos local,
                                                        BlockPos world, CarrierPose pose, int power) {
        Slot slot = new Slot(ComponentType.DUST);
        slot.power = power;
        return new CarrierProjection.ProjectedHost(carrier, local, world, pose, slot, power);
    }

    private static List<BlockPos> changes(CarrierProjection projection) {
        List<BlockPos> out = new ArrayList<>();
        projection.forEachChange(DIMENSION, out::add);
        return out;
    }

    // ------------------------------------------------------------ arithmetic --

    @Test
    @DisplayName("an unturned structure projects local (1,0,2) to anchor plus (1,0,2)")
    void unturnedProjectionIsAnchorPlusLocal() {
        CarrierPose pose = poseAt(10.0, 64.0, 10.0);
        assertEquals(pos(11, 64, 12), CarrierProjection.project(pose, pos(1, 0, 2)));
        assertEquals(pos(0, 0, 0), CarrierProjection.project(pose, pos(-10, -64, -10)));
    }

    @Test
    @DisplayName("a pose built the way a contraption's is - anchor plus half a block - projects the same way")
    void theHalfBlockPivotCancels() {
        // CarrierPose.fromContraptionBasis puts the pose's origin half a block past the anchor; the
        // pivot is half a block into the block. The two must cancel exactly, or every carried host
        // would be drawn and read half a block away from where it is.
        CarrierPose pose = CarrierPose.fromContraptionBasis(new Vector3d(10.0, 64.0, 10.0),
                new Vector3d(1, 0, 0), new Vector3d(0, 1, 0), new Vector3d(0, 0, 1));
        assertNotNull(pose);
        assertEquals(pos(11, 64, 12), CarrierProjection.project(pose, pos(1, 0, 2)));
    }

    @Test
    @DisplayName("a structure turned 180 degrees projects to the mirrored position")
    void turnedProjectionFollowsTheOrientation() {
        CarrierPose pose = CarrierPose.of(new Vector3d(10.0, 64.0, 10.0),
                new Quaterniond().rotateY(Math.PI));
        assertEquals(pos(9, 64, 8), CarrierProjection.project(pose, pos(1, 0, 2)));
    }

    // ----------------------------------------------------------------- table --

    @Test
    @DisplayName("the table answers with what is projected at a world position")
    void theTableAnswers() {
        CarrierProjection projection = new CarrierProjection();
        CarrierPose pose = poseAt(10.0, 64.0, 10.0);
        projection.begin(DIMENSION, true);
        projection.put(DIMENSION, host(CONTRAPTION, pos(1, 0, 2), pos(11, 64, 12), pose, 9));

        assertTrue(projection.isBridged(DIMENSION));
        assertEquals(1, projection.size(DIMENSION));
        CarrierProjection.ProjectedHost found = projection.at(DIMENSION, pos(11, 64, 12));
        assertNotNull(found);
        assertEquals(pos(1, 0, 2), found.localPos());
        assertEquals(CONTRAPTION, found.carrier());
        assertNull(projection.at(DIMENSION, pos(11, 64, 13)), "and nothing anywhere else");
    }

    @Test
    @DisplayName("a closed bridge answers nothing, even though the table was built")
    void aClosedBridgeIsInvisible() {
        CarrierProjection projection = new CarrierProjection();
        CarrierPose pose = poseAt(10.0, 64.0, 10.0);
        projection.begin(DIMENSION, false);
        projection.put(DIMENSION, host(CONTRAPTION, pos(1, 0, 2), pos(11, 64, 12), pose, 9));

        assertFalse(projection.isBridged(DIMENSION));
        assertEquals(0, projection.size(DIMENSION), "a put into a closed table is dropped");
        assertNull(projection.at(DIMENSION, pos(11, 64, 12)));
        assertTrue(projection.describe(DIMENSION).contains("bridge closed"));
    }

    @Test
    @DisplayName("nothing is projected before the first pass runs")
    void nothingBeforeTheFirstPass() {
        CarrierProjection projection = new CarrierProjection();
        assertNull(projection.at(DIMENSION, pos(11, 64, 12)));
        assertFalse(projection.isBridged(DIMENSION));
        assertEquals(0, projection.size(DIMENSION));
    }

    // ------------------------------------------------------------ change pass --

    @Test
    @DisplayName("a host that appears, moves, changes value or vanishes is reported once each time")
    void theChangePassReportsWhatMoved() {
        CarrierProjection projection = new CarrierProjection();
        CarrierPose pose = poseAt(10.0, 64.0, 10.0);

        // First tick: the host appears where it is.
        projection.begin(DIMENSION, true);
        projection.put(DIMENSION, host(CONTRAPTION, pos(1, 0, 2), pos(11, 64, 12), pose, 9));
        assertEquals(List.of(pos(11, 64, 12)), changes(projection), "it appeared");

        // Second tick, same place and same value: nothing to say - which is what keeps a parked machine
        // from notifying the world sixty times a second.
        projection.begin(DIMENSION, true);
        projection.put(DIMENSION, host(CONTRAPTION, pos(1, 0, 2), pos(11, 64, 12), pose, 9));
        assertTrue(changes(projection).isEmpty(), "a parked machine is silent");

        // Third tick: same position, new value - the solve changed something.
        projection.begin(DIMENSION, true);
        projection.put(DIMENSION, host(CONTRAPTION, pos(1, 0, 2), pos(11, 64, 12), pose, 4));
        assertEquals(List.of(pos(11, 64, 12)), changes(projection), "the value moved");

        // Fourth tick: the machine moved on, so the old position must be told (whatever it lit has to
        // go dark) and the new one too.
        projection.begin(DIMENSION, true);
        projection.put(DIMENSION, host(CONTRAPTION, pos(1, 0, 2), pos(12, 64, 12), pose, 4));
        assertEquals(List.of(pos(11, 64, 12), pos(12, 64, 12)), changes(projection),
                "the place it left and the place it arrived at");

        // Fifth tick: nothing is being carried any more.
        projection.begin(DIMENSION, true);
        assertEquals(List.of(pos(12, 64, 12)), changes(projection), "and the last position goes dark");
    }

    @Test
    @DisplayName("closing the bridge tells the world every projected position has gone")
    void closingTheBridgeClearsTheWorld() {
        CarrierProjection projection = new CarrierProjection();
        CarrierPose pose = poseAt(10.0, 64.0, 10.0);
        projection.begin(DIMENSION, true);
        projection.put(DIMENSION, host(CONTRAPTION, pos(1, 0, 2), pos(11, 64, 12), pose, 9));
        assertEquals(1, changes(projection).size());

        projection.begin(DIMENSION, false);
        assertEquals(List.of(pos(11, 64, 12)), changes(projection),
                "freezing the bridge must not leave the world believing a machine is still there");
    }

    // ------------------------------------------------------------ diagnostics --

    @Test
    @DisplayName("the world's queries are counted, which is the evidence the bridge is being read")
    void queriesAreCounted() {
        CarrierProjection projection = new CarrierProjection();
        CarrierPose pose = poseAt(10.0, 64.0, 10.0);
        projection.begin(DIMENSION, true);
        CarrierProjection.ProjectedHost projected =
                host(CONTRAPTION, pos(1, 0, 2), pos(11, 64, 12), pose, 9);
        projection.put(DIMENSION, projected);

        assertEquals(0, projection.queries());
        projection.noteQuery(projected);
        projection.noteQuery(projected);
        assertEquals(2, projection.queries());

        projection.begin(DIMENSION, true);
        assertEquals(2, projection.queriesThisTick(), "and the tick boundary is where the counter resets");
        assertTrue(projection.describe(DIMENSION).contains("worldQueries=2"));
    }

    @Test
    @DisplayName("clearing a dimension drops its table and its history")
    void clearingOneDimensionForgetsEverything() {
        CarrierProjection projection = new CarrierProjection();
        CarrierPose pose = poseAt(10.0, 64.0, 10.0);
        projection.begin(DIMENSION, true);
        projection.put(DIMENSION, host(CONTRAPTION, pos(1, 0, 2), pos(11, 64, 12), pose, 9));
        assertEquals(1, changes(projection).size());

        projection.clear(DIMENSION);

        assertNull(projection.at(DIMENSION, pos(11, 64, 12)));
        assertEquals(0, projection.size(DIMENSION));
        assertTrue(changes(projection).isEmpty(),
                "and there is nothing left to notify the world about either");
    }
}

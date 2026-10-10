package com.jiangyuefengyu.redstonecircuit.carrier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.Slot;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The carrier-side tools' two entry points, and the coordinate arithmetic they turn on.
 *
 * <p>A <b>Create contraption's</b> block click is a packet
 * ({@code ContraptionInteractionPacket} -> {@code AbstractContraptionEntity#handlePlayerInteraction}) and never becomes
 * a block click, so the interaction event cannot serve it; a <b>Sable sub-level's</b> click does become a block click,
 * at the structure's own coordinate, and is served by that listener.
 *
 * <p>Both entries therefore carry a click in <b>the structure's own frame</b>, which resolves to the host outright with
 * no dependence on the projection table. That resolution refuses a landed record (a put-down host is the world's
 * business), an unknown coordinate, an unknown structure, and a host with no world position to act at. The face
 * conversion the contraption entry performs twice - local -> world -> local - is the identity at every angle, so the
 * two entries cannot give {@code CarriedTool#turn} two different answers for one click.
 */
class CarriedToolEntryTest {

    private static final CarrierId MINE =
            CarrierId.contraption(UUID.fromString("00000000-0000-0000-0000-000000003001"));
    private static final CarrierId THEIRS =
            CarrierId.contraption(UUID.fromString("00000000-0000-0000-0000-000000003002"));

    /** The host's coordinate in the structure's own frame, well away from the origin. */
    private static final BlockPos LOCAL = new BlockPos(20481032, 127, 20524040);

    /** Where the pose puts it; only ever used for a highlight or a drop. */
    private static final BlockPos WORLD = new BlockPos(2775, 77, -1395);

    private final CarriedHostIndex index = new CarriedHostIndex();
    private final Map<Long, Slot> world = new HashMap<>();

    private static BlockPos pos(int x, int y, int z) {
        return new BlockPos(x, y, z);
    }

    private static Slot dust() {
        return new Slot(ComponentType.DUST);
    }

    /** Claims one host the way the assembly callback does. */
    private CarriedHost carry(CarrierId carrier, BlockPos local, BlockPos origin) {
        Slot slot = dust();
        world.put(origin.asLong(), slot);
        index.claim(carrier, local, origin, new MapWorld(world));
        return index.at(carrier, local);
    }

    /** The world store, as a map - the same stand-in the index's own tests use. */
    private record MapWorld(Map<Long, Slot> slots) implements CarriedHostIndex.SlotWorld {

        @Override
        public Slot get(BlockPos position) {
            return slots.get(position.asLong());
        }

        @Override
        public void set(BlockPos position, Slot slot) {
            if (slot == null) {
                slots.remove(position.asLong());
            } else {
                slots.put(position.asLong(), slot);
            }
        }
    }

    private static CarrierPose pose(double degrees) {
        return CarrierPose.of(new Vector3d(2775.0, 77.0, -1395.0),
                new Quaterniond().rotateY(Math.toRadians(degrees)));
    }

    // --------------------------------------------- the structure-frame lookup --

    @Test
    @DisplayName("a click in the structure's own frame names the host outright, without the projection table")
    void aStructureFrameClickNamesTheHost() {
        carry(MINE, LOCAL, WORLD);

        CarriedBlockRemoval.Hit hit = CarrierStore.hitInStructure(index, MINE, LOCAL, WORLD);

        assertNotNull(hit, "the host is in the index at exactly this coordinate");
        assertEquals(MINE, hit.carrier());
        assertEquals(LOCAL, hit.localPos(),
                "the local coordinate is the coordinate, not the world one the pose projects it to");
        assertEquals(WORLD, hit.worldPos());
    }

    @Test
    @DisplayName("a coordinate the structure does not carry resolves to nothing, whichever structure is named")
    void onlyALiveHostAtThatCoordinateResolves() {
        carry(MINE, LOCAL, WORLD);

        assertNull(CarrierStore.hitInStructure(index, MINE, pos(0, 0, 0), WORLD),
                "a coordinate this structure carries nothing at");
        assertNull(CarrierStore.hitInStructure(index, THEIRS, LOCAL, WORLD),
                "a structure the index has never heard of");
        assertNull(CarrierStore.hitInStructure(index, MINE, LOCAL, null),
                "no world position to highlight or to notify, so nothing may be acted on");
        assertNull(CarrierStore.hitInStructure((CarriedHostIndex) null, MINE, LOCAL, WORLD),
                "no index means no answer, rather than an exception on the click path");
        assertNull(CarrierStore.hitInStructure(index, (CarrierId) null, LOCAL, WORLD));
        assertNull(CarrierStore.hitInStructure(index, MINE, (BlockPos) null, WORLD));
    }

    @Test
    @DisplayName("a host that has been put down is the world's business, not the carrier tools'")
    void aLandedHostIsNotAHit() {
        carry(MINE, LOCAL, WORLD);
        index.release(MINE, LOCAL, landing -> WORLD, new MapWorld(world));

        assertTrue(index.at(MINE, LOCAL).landed(), "the release is what the setup is checking");
        assertNull(CarrierStore.hitInStructure(index, MINE, LOCAL, WORLD),
                "a put-down host is back in the world: the world's wrench and rod own it, and this "
                        + "entry must not act on it a second time");
    }

    @Test
    @DisplayName("the carrier is found by the UUID the other mod hands over, whatever kind it was filed as")
    void theCarrierIsFoundByUuidWhateverItsKind() {
        carry(MINE, LOCAL, WORLD);

        assertEquals(MINE, index.carrierForUuid(MINE.uuid()));
        assertNull(index.carrierForUuid(THEIRS.uuid()));
        assertNull(index.carrierForUuid(null));

        // The point of a UUID lookup rather than a kind lookup: a physics-assembled structure has one
        // UUID shared by a contraption entity and a sub-level, and only the index knows which kind it
        // was filed as - so a caller guessing the kind would be wrong half the time.
        CarrierId asSubLevel = CarrierId.subLevel(MINE.uuid());
        assertNotNull(index.at(asSubLevel, LOCAL),
                "the record is keyed by UUID, so either spelling finds it");
        assertEquals(MINE, index.carrierForUuid(MINE.uuid()),
                "and the lookup reports the kind the index actually filed");
    }

    // -------------------------------------------------- the face conversion --

    @Test
    @DisplayName("local -> world -> local is the identity, at every angle a real structure is found at")
    void theFaceRoundTripIsTheIdentity() {
        for (double degrees : new double[] {0.0, 9.5, 37.5, 90.0, 180.0, -90.0, 270.0}) {
            CarrierPose pose = pose(degrees);
            for (Direction local : Direction.values()) {
                Direction world = CarrierDirections.worldDirection(pose, local);
                assertEquals(local, CarrierDirections.localDirection(pose, world),
                        "the contraption entry converts the face twice and must get its own answer back "
                                + "(local=" + local + " at " + degrees + " degrees)");
            }
        }
    }

    @Test
    @DisplayName("the turn lands on the structure's own coordinate, not on a world one")
    void theTurnLandsOnTheStructureFrame() {
        // A repeater rather than a piece of wire: wire has no orientation, so a turn on it is refused and
        // this test would pass for the wrong reason.
        Slot repeater = new Slot(ComponentType.REPEATER);
        repeater.facing = Direction.NORTH;
        world.put(WORLD.asLong(), repeater);
        index.claim(MINE, LOCAL, WORLD, new MapWorld(world));

        // The contraption entry hands over the face in the structure's own frame; the conversion the
        // entry performs turns it into a world direction and back, and `CarriedTool#turn` then receives
        // the *local* direction - so the record it writes is the one at LOCAL.
        CarrierPose pose = pose(90.0);
        Direction localFace = Direction.EAST;
        Direction asWorld = CarrierDirections.worldDirection(pose, localFace);
        Direction backToLocal = CarrierDirections.localDirection(pose, asWorld);

        com.jiangyuefengyu.redstonecircuit.logic.CarriedTool.Action action =
                com.jiangyuefengyu.redstonecircuit.logic.CarriedTool.turn(index, MINE, LOCAL, backToLocal);

        assertTrue(action instanceof com.jiangyuefengyu.redstonecircuit.logic.CarriedTool.Action.TurnedToInput
                        || action instanceof com.jiangyuefengyu.redstonecircuit.logic.CarriedTool.Action.TurnedToOutput,
                "the component has an orientation, so this must be a turn; was " + action);
        assertEquals(LOCAL, action.localPos(), "the action names the structure's own coordinate");
        assertEquals(localFace, index.at(MINE, LOCAL).slot().facing,
                "and the record at that coordinate is the one that changed");
        assertEquals(WORLD, index.at(MINE, LOCAL).at(),
                "the host's world position is untouched: a tool action changes routing and facing, and "
                        + "must never move or release anything");
        assertFalse(world.containsKey(WORLD.asLong()),
                "and the world store is untouched in either direction: the tools write the index only");
    }
}

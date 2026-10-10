package com.jiangyuefengyu.redstonecircuit.carrier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

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
 * The world's own inner solve reads the output of a host a structure is carrying.
 *
 * <p>Both readers of that answer - vanilla's signal query (through {@code BlockStateSignalMixin}, which
 * is what a lamp or a piston reads) and the mod's world-side inner solve - go through
 * {@link CarrierProjection#emittedToward}, and that is what these tests pin: the direction conversion,
 * the emission rule, the weak/strong split, and the fact that a host which has been turned answers for
 * the direction it now faces.
 */
class CarrierProjectionSignalTest {

    private static final CarrierId CARRIER =
            CarrierId.contraption(UUID.fromString("00000000-0000-0000-0000-000000002201"));

    private static BlockPos pos(int x, int y, int z) {
        return new BlockPos(x, y, z);
    }

    /** One projected host at a world position, with a component of the given type inside it. */
    private static CarrierProjection.ProjectedHost host(CarrierPose pose, Slot slot) {
        return new CarrierProjection.ProjectedHost(CARRIER, pos(1, 0, 0), pos(11, 64, 10), pose, slot,
                slot.power);
    }

    @Test
    @DisplayName("dust inside a carried host answers on every side, exactly as it would on the ground")
    void dustAnswersEverySide() {
        Slot dust = new Slot(ComponentType.DUST);
        dust.power = 9;
        CarrierProjection.ProjectedHost host = host(CarrierPose.at(new Vector3d(10.0, 64.0, 10.0)),
                dust);

        for (Direction side : Direction.values()) {
            assertEquals(9, CarrierProjection.emittedToward(host, side, false, false),
                    "dust emits on every side, and the world side is asked about a direction from the "
                            + "asker towards the host: " + side);
        }
    }

    @Test
    @DisplayName("a repeater inside a carried host drives only the side it points at")
    void aRepeaterDrivesOnlyItsOutputSide() {
        Slot repeater = new Slot(ComponentType.REPEATER);
        repeater.power = 15;
        repeater.facing = Direction.NORTH;
        CarrierPose pose = CarrierPose.at(new Vector3d(10.0, 64.0, 10.0));
        CarrierProjection.ProjectedHost host = host(pose, repeater);

        // The component faces north, so its output side is south. A world block asking from the south
        // (i.e. the direction from the asker towards the host is NORTH) is the one that sees 15.
        assertEquals(15, CarrierProjection.emittedToward(host, Direction.NORTH, false, false),
                "the block on the output side reads the diode");
        assertEquals(0, CarrierProjection.emittedToward(host, Direction.SOUTH, false, false),
                "the block behind it does not");
        assertEquals(0, CarrierProjection.emittedToward(host, Direction.EAST, false, false),
                "and neither does one beside it");
    }

    @Test
    @DisplayName("a turned machine answers for the side it now faces, not the one it faced before")
    void aTurnedMachineAnswersForItsNewSide() {
        Slot repeater = new Slot(ComponentType.REPEATER);
        repeater.power = 15;
        repeater.facing = Direction.NORTH;
        Slot slot = repeater;

        CarrierPose before = CarrierPose.at(new Vector3d(10.0, 64.0, 10.0));
        CarrierPose after = CarrierPose.of(new Vector3d(10.0, 64.0, 10.0),
                new Quaterniond().rotateY(Math.PI / 2.0));

        CarrierProjection.ProjectedHost beforeHost = host(before, slot);
        CarrierProjection.ProjectedHost afterHost = host(after, slot);

        // Local north is world north before the turn and world WEST after a +90 degrees turn about Y
        // (CarrierDirections#worldDirection's convention): the component did not change, the structure did.
        assertEquals(15, CarrierProjection.emittedToward(beforeHost, Direction.NORTH, false, false));
        assertEquals(0, CarrierProjection.emittedToward(beforeHost, Direction.WEST, false, false));

        int afterWest = CarrierProjection.emittedToward(afterHost, Direction.WEST, false, false);
        assertEquals(15, afterWest,
                "the same world side the diode now faces reads it: the conversion goes through the pose");
        assertEquals(0, CarrierProjection.emittedToward(afterHost, Direction.NORTH, false, false),
                "and the side it faced before is silent");
        assertNotEquals(
                CarrierProjection.emittedToward(beforeHost, Direction.WEST, false, false), afterWest,
                "the two poses disagree about the same world side, so a reader that ignored the pose "
                        + "would fail here");
    }

    @Test
    @DisplayName("a redstone torch inside a carried host does not emit towards the face it hangs on")
    void aTorchKeepsItsDeadFace() {
        Slot torch = new Slot(ComponentType.TORCH);
        torch.power = 15;
        torch.facing = Direction.DOWN;
        CarrierProjection.ProjectedHost host = host(CarrierPose.at(new Vector3d(10.0, 64.0, 10.0)),
                torch);

        // The torch hangs on the block below it, so it does not drive it. Asked from below, the
        // direction from the asker towards the host is UP, i.e. the emitted direction is DOWN.
        assertEquals(0, CarrierProjection.emittedToward(host, Direction.UP, false, false),
                "the face it is attached to is dead, exactly as in the world");
        assertEquals(15, CarrierProjection.emittedToward(host, Direction.DOWN, false, false),
                "and every other face is live");
        assertEquals(15, CarrierProjection.emittedToward(host, Direction.EAST, false, false));
    }

    @Test
    @DisplayName("a host with nothing in it, and no host at all, answer zero")
    void anEmptyOrAbsentHostAnswersZero() {
        Slot dust = new Slot(ComponentType.DUST);
        dust.power = 12;
        CarrierProjection.ProjectedHost host = host(CarrierPose.at(new Vector3d()), dust);
        CarrierProjection.ProjectedHost empty =
                new CarrierProjection.ProjectedHost(CARRIER, pos(1, 0, 0), pos(11, 64, 10),
                        CarrierPose.at(new Vector3d()), null, 0);

        assertEquals(0, CarrierProjection.emittedToward(empty, Direction.NORTH, false, false),
                "a host with no component is not a signal source");
        assertEquals(0, CarrierProjection.emittedToward(null, Direction.NORTH, false, false),
                "and neither is a position nothing is projected at - the ordinary answer for almost "
                        + "every query in the level");
        assertEquals(0, CarrierProjection.emittedToward(host, null, false, false),
                "and a null side is not a question");
    }

    @Test
    @DisplayName("strong power is answered separately, and a neighbour with a component is not charged")
    void strongPowerStaysSeparate() {
        Slot repeater = new Slot(ComponentType.REPEATER);
        repeater.power = 15;
        repeater.facing = Direction.NORTH;
        CarrierPose pose = CarrierPose.at(new Vector3d(10.0, 64.0, 10.0));
        CarrierProjection.ProjectedHost host = host(pose, repeater);

        int weak = CarrierProjection.emittedToward(host, Direction.NORTH, false, false);
        int strongIntoPlain = CarrierProjection.emittedToward(host, Direction.NORTH, true, false);
        int strongIntoComponent = CarrierProjection.emittedToward(host, Direction.NORTH, true, true);

        assertEquals(15, weak, "the weak question is what an inner solve reads, and it is the output");
        assertEquals(15, strongIntoPlain,
                "with nothing of ours in front of it, the diode charges the block it drives - which is "
                        + "what makes 'repeater, stone, lamp' work");
        assertEquals(0, strongIntoComponent,
                "but a block that holds a component is not charged onwards: the diode has given its "
                        + "signal TO that component, not turned the block into a redstone block - "
                        + "otherwise two repeaters in a row would light every piece of dust beside the "
                        + "first one");
    }

    @Test
    @DisplayName("a lever inside a carried host answers every side at full strength")
    void aLeverAnswersEverySide() {
        Slot lever = new Slot(ComponentType.LEVER);
        lever.power = 15;
        lever.facing = Direction.UP;
        CarrierProjection.ProjectedHost host = host(CarrierPose.at(new Vector3d(10.0, 64.0, 10.0)),
                lever);

        for (Direction side : Direction.values()) {
            assertEquals(15, CarrierProjection.emittedToward(host, side, false, false),
                    "a lever has no dead face: " + side);
        }
    }
}

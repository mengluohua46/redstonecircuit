package com.jiangyuefengyu.redstonecircuit.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.jiangyuefengyu.redstonecircuit.carrier.CarrierId;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierIndex;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierPose;
import com.jiangyuefengyu.redstonecircuit.data.ComparatorMode;
import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.ConnectionState;
import com.jiangyuefengyu.redstonecircuit.data.HostEntry;
import com.jiangyuefengyu.redstonecircuit.data.Slot;
import com.jiangyuefengyu.redstonecircuit.logic.ClockRule;
import com.jiangyuefengyu.redstonecircuit.logic.DeviceKind;

import io.netty.handler.codec.DecoderException;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

import io.netty.buffer.Unpooled;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Round-trips the host sync payloads.
 *
 * <p>The client's picture of what is inside each block is built entirely from these packets, and a
 * codec mistake would be invisible until it showed up as a component drawn facing the wrong way or a
 * wire stuck at the wrong strength. Every field that changes the drawing is therefore round-tripped
 * here, including the wrench overrides: those decide whether a connection bar is drawn grey, green or
 * cut, and they are the newest fields on the wire.
 */
class RCNetworkCodecTest {

    private static final ResourceKey<Level> OVERWORLD =
            ResourceKey.create(Registries.DIMENSION, ResourceLocation.withDefaultNamespace("overworld"));

    /**
     * A device's live reading has to survive the trip - 时钟源's countdown did not. The reading went over the wire as
     * a <b>signed byte</b>, and a clock's reading is a countdown rather than a strength (0-15), so "165 ticks until
     * the next change" arrived as -91 and the client's decoder refused the whole packet.
     *
     * <p>So: the widest reading any device can report has to round trip, and a number wider than that has to stay
     * refused - the refusal is the tripwire, not the bug.
     */
    @Test
    @DisplayName("设备读数：时钟源的倒计时可以是一整个周期，不能被一个字节截成负数")
    void aDeviceReadingIsAsWideAsTheWidestDevice() {
        RCNetwork.DeviceViewPayload sent = new RCNetwork.DeviceViewPayload(BlockPos.ZERO, true,
                DeviceKind.CLOCK.ordinal(), ClockRule.MAX_SECONDS, ClockRule.MAX_TICKS, ClockRule.MAX_STRENGTH, 0,
                15, ClockRule.MAX_PERIOD);
        RegistryFriendlyByteBuf buffer = buffer();
        RCNetwork.DeviceViewPayload.STREAM_CODEC.encode(buffer, sent);
        assertEquals(sent, RCNetwork.DeviceViewPayload.STREAM_CODEC.decode(buffer),
                "一整个周期（72019 tick）的读数必须原样过线");

        // The regression itself: 165 must not come back as -91.
        RCNetwork.DeviceViewPayload countdown = new RCNetwork.DeviceViewPayload(BlockPos.ZERO, true,
                DeviceKind.CLOCK.ordinal(), 10, 0, 15, 0, 0, 165);
        RegistryFriendlyByteBuf second = buffer();
        RCNetwork.DeviceViewPayload.STREAM_CODEC.encode(second, countdown);
        assertEquals(countdown, RCNetwork.DeviceViewPayload.STREAM_CODEC.decode(second),
                "165 的读数过线之后还必须是 165");

        RegistryFriendlyByteBuf tooBig = buffer();
        RCNetwork.DeviceViewPayload.STREAM_CODEC.encode(tooBig, new RCNetwork.DeviceViewPayload(BlockPos.ZERO, false,
                DeviceKind.CLOCK.ordinal(), 0, 0, 15, 0, 0, DeviceKind.MAX_READING + 1));
        assertThrows(DecoderException.class,
                () -> RCNetwork.DeviceViewPayload.STREAM_CODEC.decode(tooBig),
                "比最宽的读数还宽的数字 = 没有设备会这么读，解码端必须拒（这道闸就是抓出这个 bug 的那一道）");
    }

    private static RegistryFriendlyByteBuf buffer() {
        return new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
    }

    /** A component with every field set to something that is not its default. */
    private static Slot busySlot() {
        Slot slot = new Slot(ComponentType.REPEATER);
        slot.power = 11;
        slot.powered = true;
        slot.facing = Direction.EAST;
        slot.delay = 3;
        slot.mode = ComparatorMode.SUBTRACT;
        slot.setConnection(Direction.UP, ConnectionState.ON);
        slot.setConnection(Direction.DOWN, ConnectionState.OFF);
        return slot;
    }

    private static void assertSameSlot(Slot expected, Slot actual) {
        assertNotNull(actual, "the slot must survive the trip");
        assertEquals(expected.type, actual.type);
        assertEquals(expected.power, actual.power);
        assertEquals(expected.powered, actual.powered);
        assertEquals(expected.facing, actual.facing);
        assertEquals(expected.delay, actual.delay);
        assertEquals(expected.mode, actual.mode);
        assertEquals(expected.forcedOn, actual.forcedOn, "wrench pins must reach the client");
        assertEquals(expected.forcedOff, actual.forcedOff, "and so must the cuts");
    }

    @Test
    @DisplayName("a snapshot survives a round trip, contents and all")
    void snapshotRoundTrip() {
        Slot first = busySlot();
        Slot second = new Slot(ComponentType.DUST);
        second.power = 4;
        RCNetwork.HostSnapshotPayload original = new RCNetwork.HostSnapshotPayload(OVERWORLD, List.of(
                new HostEntry(new BlockPos(1, 2, 3), first),
                new HostEntry(new BlockPos(-40, 70, 900), second)));

        RegistryFriendlyByteBuf encoded = buffer();
        RCNetwork.HostSnapshotPayload.STREAM_CODEC.encode(encoded, original);
        RCNetwork.HostSnapshotPayload decoded =
                RCNetwork.HostSnapshotPayload.STREAM_CODEC.decode(encoded);

        assertEquals(original.dimension(), decoded.dimension());
        assertEquals(2, decoded.entries().size());
        assertEquals(new BlockPos(1, 2, 3), decoded.entries().get(0).pos());
        assertSameSlot(first, decoded.entries().get(0).slot());
        assertEquals(new BlockPos(-40, 70, 900), decoded.entries().get(1).pos());
        assertSameSlot(second, decoded.entries().get(1).slot());
    }

    @Test
    @DisplayName("an empty snapshot round trips as empty, not as a missing one")
    void emptySnapshotRoundTrip() {
        RCNetwork.HostSnapshotPayload original =
                new RCNetwork.HostSnapshotPayload(OVERWORLD, List.of());

        RegistryFriendlyByteBuf encoded = buffer();
        RCNetwork.HostSnapshotPayload.STREAM_CODEC.encode(encoded, original);
        RCNetwork.HostSnapshotPayload decoded =
                RCNetwork.HostSnapshotPayload.STREAM_CODEC.decode(encoded);

        assertEquals(List.of(), decoded.entries(),
                "an empty set is a valid answer - the level really has no hosts");
    }

    @Test
    @DisplayName("a slot update carries every position in the batch")
    void slotBatchRoundTrip() {
        Slot changed = busySlot();
        RCNetwork.HostSlotPayload original = new RCNetwork.HostSlotPayload(OVERWORLD, List.of(
                new HostEntry(new BlockPos(4, 5, 6), changed),
                HostEntry.removed(new BlockPos(4, 5, 7))));

        RegistryFriendlyByteBuf encoded = buffer();
        RCNetwork.HostSlotPayload.STREAM_CODEC.encode(encoded, original);
        RCNetwork.HostSlotPayload decoded = RCNetwork.HostSlotPayload.STREAM_CODEC.decode(encoded);

        assertEquals(OVERWORLD, decoded.dimension());
        assertEquals(2, decoded.entries().size());
        assertEquals(new BlockPos(4, 5, 6), decoded.entries().get(0).pos());
        assertSameSlot(changed, decoded.entries().get(0).slot());
        assertEquals(new BlockPos(4, 5, 7), decoded.entries().get(1).pos());
        assertNull(decoded.entries().get(1).slot(),
                "a null slot is how a removal travels, and it must stay null");
    }

    @Test
    @DisplayName("a wrench selection round trips, including the server's 'still a host' answer")
    void wrenchSelectionRoundTrip() {
        // The flag is the whole of what the client draws on - it cannot be derived from the position
        // (a stale pick and a live carried host share one), so both of its values have to survive the codec.
        RCNetwork.WrenchSelectionPayload selected =
                new RCNetwork.WrenchSelectionPayload(true, new BlockPos(8, 9, 10), true);
        RegistryFriendlyByteBuf encoded = buffer();
        RCNetwork.WrenchSelectionPayload.STREAM_CODEC.encode(encoded, selected);
        RCNetwork.WrenchSelectionPayload decoded =
                RCNetwork.WrenchSelectionPayload.STREAM_CODEC.decode(encoded);
        assertEquals(true, decoded.hasSelection());
        assertEquals(new BlockPos(8, 9, 10), decoded.pos());
        assertEquals(true, decoded.liveHost(), "a live pick draws its box");
        assertNull(decoded.carried(), "a world pick names no structure - and the codec must not invent one");
        assertNull(decoded.carriedLocal());

        RCNetwork.WrenchSelectionPayload stale =
                new RCNetwork.WrenchSelectionPayload(true, new BlockPos(8, 9, 10), false);
        RegistryFriendlyByteBuf third = buffer();
        RCNetwork.WrenchSelectionPayload.STREAM_CODEC.encode(third, stale);
        RCNetwork.WrenchSelectionPayload decodedStale =
                RCNetwork.WrenchSelectionPayload.STREAM_CODEC.decode(third);
        assertEquals(true, decodedStale.hasSelection(), "the position is still named, so the pick is not forgotten");
        assertEquals(false, decodedStale.liveHost(), "and a pick whose host is gone draws nothing");

        RCNetwork.WrenchSelectionPayload none = RCNetwork.WrenchSelectionPayload.none();
        RegistryFriendlyByteBuf second = buffer();
        RCNetwork.WrenchSelectionPayload.STREAM_CODEC.encode(second, none);
        RCNetwork.WrenchSelectionPayload decodedNone =
                RCNetwork.WrenchSelectionPayload.STREAM_CODEC.decode(second);
        assertEquals(false, decodedNone.hasSelection());
    }

    /** A pick inside a machine, which is placed in the machine's own frame on the client. */
    @Test
    @DisplayName("a carried selection carries its structure and local block, and a plot coordinate survives intact")
    void carriedSelectionRoundTrip() {
        CarrierId carrier = CarrierId.contraption(UUID.fromString("00000000-0000-0000-0000-00000000c0ff"));
        // A Sable sub-level's own coordinate: the magnitude is the point of the test - the client places the box with
        // this number, in double, in a frame that never narrows it to float.
        BlockPos local = new BlockPos(20481032, 128, 20489224);
        RCNetwork.WrenchSelectionPayload carried =
                new RCNetwork.WrenchSelectionPayload(true, new BlockPos(-233, -60, 88), true, carrier, local);

        RegistryFriendlyByteBuf encoded = buffer();
        RCNetwork.WrenchSelectionPayload.STREAM_CODEC.encode(encoded, carried);
        RCNetwork.WrenchSelectionPayload decoded =
                RCNetwork.WrenchSelectionPayload.STREAM_CODEC.decode(encoded);

        assertEquals(carrier, decoded.carried(),
                "the structure comes back, which is what lets the box be placed in its frame rather than on the nearest "
                        + "world coordinate that the machine has not moved to");
        assertEquals(local, decoded.carriedLocal());
        assertEquals(new BlockPos(-233, -60, 88), decoded.pos(),
                "and the projected world position stays for a client with no pose for that structure to fall back on");
        assertEquals(true, decoded.liveHost());

        // One flag carries both fields: a structure with no local block is not a place, and the pair is written as a
        // unit, so a payload cannot name one without the other.
        RCNetwork.WrenchSelectionPayload half =
                new RCNetwork.WrenchSelectionPayload(true, new BlockPos(-233, -60, 88), true, carrier, null);
        RegistryFriendlyByteBuf other = buffer();
        RCNetwork.WrenchSelectionPayload.STREAM_CODEC.encode(other, half);
        RCNetwork.WrenchSelectionPayload decodedHalf =
                RCNetwork.WrenchSelectionPayload.STREAM_CODEC.decode(other);
        assertNull(decodedHalf.carried(), "a structure with no block is not a carried pick");
        assertNull(decodedHalf.carriedLocal());
    }

    /** The debug-only power field is deliberately not on the wire; a client must not depend on it. */
    @Test
    @DisplayName("the injected debug power is not sent to clients")
    void injectedPowerIsNotSynced() {
        Slot slot = new Slot(ComponentType.DUST);
        slot.injectedPower = 12;

        RegistryFriendlyByteBuf encoded = buffer();
        RCNetwork.HostSlotPayload.STREAM_CODEC.encode(encoded,
                new RCNetwork.HostSlotPayload(OVERWORLD,
                        List.of(new HostEntry(new BlockPos(0, 0, 0), slot))));
        Slot decoded = RCNetwork.HostSlotPayload.STREAM_CODEC.decode(encoded).entries().get(0).slot();

        assertNotNull(decoded);
        assertEquals(-1, decoded.injectedPower);
    }

    // ------------------------------------------------- moving structures --

    private static final CarrierId CARRIER = CarrierId.contraption(
            UUID.fromString("00000000-0000-0000-0000-0000000000c1"));

    /**
     * A pose that is not a right angle: a contraption can stand at 9.5 degrees, and a codec that rounded or
     * dropped the quaternion would draw every tilted machine wrong while looking perfectly correct in a test
     * that only ever used a yaw of 0 or 90.
     */
    private static CarrierPose tiltedPose() {
        return CarrierPose.of(new Vector3d(101.25, 64.5, -300.75),
                new Quaterniond().rotateY(Math.toRadians(9.5)));
    }

    @Test
    @DisplayName("a carrier's pose and hosts survive the trip")
    void carrierPayloadRoundTrip() {
        CarrierIndex.CarriedView host = new CarrierIndex.CarriedView(CARRIER,
                new BlockPos(1, 0, 2), new BlockPos(101, 64, 202), new BlockPos(101, 64, 202),
                busySlot());
        RCNetwork.CarrierPayload original =
                new RCNetwork.CarrierPayload(OVERWORLD, CARRIER, tiltedPose(), List.of(host));

        RegistryFriendlyByteBuf encoded = buffer();
        RCNetwork.CarrierPayload.STREAM_CODEC.encode(encoded, original);
        RCNetwork.CarrierPayload decoded = RCNetwork.CarrierPayload.STREAM_CODEC.decode(encoded);

        assertEquals(OVERWORLD, decoded.dimension());
        assertEquals(CARRIER, decoded.carrier(), "the kind travels with the id");
        assertNotNull(decoded.pose());
        assertEquals(101.25, decoded.pose().position().x(), 1.0E-9);
        assertEquals(9.5, decoded.pose().rotationDegrees(), 1.0E-6,
                "the rotation is not rounded to a right angle");
        assertEquals(0.5, decoded.pose().pivot().x(), 1.0E-9);
        assertEquals(0.5, decoded.pose().pivot().y(), 1.0E-9);
        assertEquals(0.5, decoded.pose().pivot().z(), 1.0E-9);

        assertEquals(1, decoded.hosts().size());
        CarrierIndex.CarriedView decodedHost = decoded.hosts().get(0);
        assertEquals(new BlockPos(1, 0, 2), decodedHost.localPos());
        assertEquals(new BlockPos(101, 64, 202), decodedHost.origin());
        assertSameSlot(original.hosts().get(0).slot(), decodedHost.slot());
    }

    @Test
    @DisplayName("a carrier with no pose means 'gone', and travels as such")
    void carrierRemovalTravelsAsANullPose() {
        RCNetwork.CarrierPayload original =
                new RCNetwork.CarrierPayload(OVERWORLD, CARRIER, null, List.of());

        RegistryFriendlyByteBuf encoded = buffer();
        RCNetwork.CarrierPayload.STREAM_CODEC.encode(encoded, original);
        RCNetwork.CarrierPayload decoded = RCNetwork.CarrierPayload.STREAM_CODEC.decode(encoded);

        assertNull(decoded.pose(), "a null pose is how a structure leaving is announced");
        assertEquals(List.of(), decoded.hosts());
    }

    @Test
    @DisplayName("the login snapshot carries every carrier with its pose and hosts")
    void carrierSnapshotRoundTrip() {
        CarrierIndex.CarriedView host = new CarrierIndex.CarriedView(CARRIER,
                new BlockPos(3, 1, 0), new BlockPos(200, 64, 200), new BlockPos(200, 64, 200),
                busySlot());
        RCNetwork.CarrierPayload carrier =
                new RCNetwork.CarrierPayload(OVERWORLD, CARRIER, tiltedPose(), List.of(host));
        RCNetwork.CarrierSnapshotPayload original =
                new RCNetwork.CarrierSnapshotPayload(OVERWORLD, List.of(carrier));

        RegistryFriendlyByteBuf encoded = buffer();
        RCNetwork.CarrierSnapshotPayload.STREAM_CODEC.encode(encoded, original);
        RCNetwork.CarrierSnapshotPayload decoded =
                RCNetwork.CarrierSnapshotPayload.STREAM_CODEC.decode(encoded);

        assertEquals(OVERWORLD, decoded.dimension());
        assertEquals(1, decoded.carriers().size());
        RCNetwork.CarrierPayload only = decoded.carriers().get(0);
        assertEquals(CARRIER, only.carrier());
        assertNotNull(only.pose());
        assertEquals(9.5, only.pose().rotationDegrees(), 1.0E-6);
        assertEquals(1, only.hosts().size());
        assertEquals(new BlockPos(3, 1, 0), only.hosts().get(0).localPos());
        assertEquals(CARRIER, only.hosts().get(0).carrier(),
                "each host inside the snapshot is tagged with its own carrier");
    }

    /**
     * A NaN pose would draw geometry that is nowhere at all, which is a much worse bug to report than
     * a packet that was refused - so the decoder checks, and there are two ways to check it.
     */
    @Test
    @DisplayName("a pose that is not finite is refused rather than sent on")
    void nonFinitePoseIsRefused() {
        RegistryFriendlyByteBuf encoded = buffer();
        encoded.writeBoolean(true);
        encoded.writeDouble(Double.NaN);
        encoded.writeDouble(0.0);
        encoded.writeDouble(0.0);
        encoded.writeDouble(0.0);
        encoded.writeDouble(0.0);
        encoded.writeDouble(0.0);
        encoded.writeDouble(1.0);
        encoded.writeDouble(0.5);
        encoded.writeDouble(0.5);
        encoded.writeDouble(0.5);
        encoded.writeDouble(1.0);

        assertNull(RCNetwork.readPose(encoded));
    }

    @Test
    @DisplayName("a zero quaternion is refused too: it carries no rotation at all")
    void zeroQuaternionPoseIsRefused() {
        RegistryFriendlyByteBuf encoded = buffer();
        encoded.writeBoolean(true);
        encoded.writeDouble(0.0);
        encoded.writeDouble(0.0);
        encoded.writeDouble(0.0);
        encoded.writeDouble(0.0);
        encoded.writeDouble(0.0);
        encoded.writeDouble(0.0);
        encoded.writeDouble(0.0);
        encoded.writeDouble(0.5);
        encoded.writeDouble(0.5);
        encoded.writeDouble(0.5);
        encoded.writeDouble(1.0);

        assertNull(RCNetwork.readPose(encoded));
    }

    /** The carrier id's kind has to reach the client, or a contraption and a sub-level would merge. */
    @Test
    @DisplayName("a sub-level carrier keeps its kind on the wire")
    void carrierKindTravels() {
        CarrierId subLevel = CarrierId.subLevel(UUID.fromString("00000000-0000-0000-0000-00000000ab00"));
        RCNetwork.CarrierPayload original =
                new RCNetwork.CarrierPayload(OVERWORLD, subLevel, tiltedPose(), List.of());

        RegistryFriendlyByteBuf encoded = buffer();
        RCNetwork.CarrierPayload.STREAM_CODEC.encode(encoded, original);
        CarrierId decoded = RCNetwork.CarrierPayload.STREAM_CODEC.decode(encoded).carrier();

        assertEquals(subLevel, decoded);
        assertTrue(decoded.isSubLevel());
    }

    /** The codec helpers are used by both payloads; a mismatch between them would be silent. */
    @Test
    @DisplayName("the pose helper round trips on its own")
    void poseHelperRoundTrip() {
        CarrierPose original = tiltedPose();
        RegistryFriendlyByteBuf encoded = buffer();
        RCNetwork.writePose(encoded, original);
        CarrierPose decoded = RCNetwork.readPose(encoded);

        assertNotNull(decoded);
        assertTrue(decoded.closeTo(original));
    }

    /** A snapshot with nothing in flight is what a fresh client in a quiet world gets. */
    @Test
    @DisplayName("an empty carrier snapshot round trips as empty")
    void emptyCarrierSnapshotRoundTrips() {
        RCNetwork.CarrierSnapshotPayload original =
                new RCNetwork.CarrierSnapshotPayload(OVERWORLD, List.of());

        RegistryFriendlyByteBuf encoded = buffer();
        RCNetwork.CarrierSnapshotPayload.STREAM_CODEC.encode(encoded, original);
        RCNetwork.CarrierSnapshotPayload decoded =
                RCNetwork.CarrierSnapshotPayload.STREAM_CODEC.decode(encoded);

        assertEquals(List.of(), decoded.carriers());
    }

    /** Kept out of the round-trip tests above: the map form is what the client handler builds. */
    @Test
    @DisplayName("a snapshot can be folded into the client's pose map")
    void snapshotFoldsIntoAPoseMap() {
        CarrierIndex.CarriedView host = new CarrierIndex.CarriedView(CARRIER,
                new BlockPos(0, 0, 0), new BlockPos(1, 64, 1), new BlockPos(1, 64, 1), busySlot());
        RCNetwork.CarrierPayload carrier =
                new RCNetwork.CarrierPayload(OVERWORLD, CARRIER, tiltedPose(), List.of(host));
        RCNetwork.CarrierSnapshotPayload payload =
                new RCNetwork.CarrierSnapshotPayload(OVERWORLD, List.of(carrier));

        Map<CarrierId, CarrierPose> poses = new HashMap<>();
        for (RCNetwork.CarrierPayload entry : payload.carriers()) {
            if (entry.pose() != null) {
                poses.put(entry.carrier(), entry.pose());
            }
        }

        assertEquals(1, poses.size());
        assertNotNull(poses.get(CARRIER));
    }
}

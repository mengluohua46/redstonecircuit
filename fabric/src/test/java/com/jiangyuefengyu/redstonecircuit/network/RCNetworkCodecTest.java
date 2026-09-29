package com.jiangyuefengyu.redstonecircuit.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import com.jiangyuefengyu.redstonecircuit.data.ComparatorMode;
import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.ConnectionState;
import com.jiangyuefengyu.redstonecircuit.data.HostEntry;
import com.jiangyuefengyu.redstonecircuit.data.Slot;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

import io.netty.buffer.Unpooled;
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
    @DisplayName("a wrench selection round trips, including 'nothing selected'")
    void wrenchSelectionRoundTrip() {
        RCNetwork.WrenchSelectionPayload selected =
                new RCNetwork.WrenchSelectionPayload(true, new BlockPos(8, 9, 10));
        RegistryFriendlyByteBuf encoded = buffer();
        RCNetwork.WrenchSelectionPayload.STREAM_CODEC.encode(encoded, selected);
        RCNetwork.WrenchSelectionPayload decoded =
                RCNetwork.WrenchSelectionPayload.STREAM_CODEC.decode(encoded);
        assertEquals(true, decoded.hasSelection());
        assertEquals(new BlockPos(8, 9, 10), decoded.pos());

        RCNetwork.WrenchSelectionPayload none = RCNetwork.WrenchSelectionPayload.none();
        RegistryFriendlyByteBuf second = buffer();
        RCNetwork.WrenchSelectionPayload.STREAM_CODEC.encode(second, none);
        RCNetwork.WrenchSelectionPayload decodedNone =
                RCNetwork.WrenchSelectionPayload.STREAM_CODEC.decode(second);
        assertEquals(false, decodedNone.hasSelection());
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
}

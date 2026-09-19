package com.jiangyuefengyu.redstonecircuit.network;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import net.minecraft.core.BlockPos;
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
 * <p>The client's picture of which blocks hide redstone is built entirely from these two packets, and
 * a codec mistake would be invisible until it showed up as blocks that never change appearance. Both
 * use only primitives ({@code ResourceLocation}, {@code VarInt}, {@code BlockPos}), so no registries
 * have to be bootstrapped to run this.
 */
class RCNetworkCodecTest {

    private static final ResourceKey<Level> OVERWORLD =
            ResourceKey.create(Registries.DIMENSION, ResourceLocation.withDefaultNamespace("overworld"));

    private static RegistryFriendlyByteBuf buffer() {
        return new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
    }

    @Test
    @DisplayName("a snapshot survives a round trip, dimension and all")
    void snapshotRoundTrip() {
        List<BlockPos> positions = List.of(new BlockPos(1, 2, 3), new BlockPos(-40, 70, 900));
        RCNetwork.HostSnapshotPayload original =
                new RCNetwork.HostSnapshotPayload(OVERWORLD, positions);

        RegistryFriendlyByteBuf encoded = buffer();
        RCNetwork.HostSnapshotPayload.STREAM_CODEC.encode(encoded, original);
        RCNetwork.HostSnapshotPayload decoded = RCNetwork.HostSnapshotPayload.STREAM_CODEC.decode(encoded);

        assertEquals(original.dimension(), decoded.dimension());
        assertEquals(positions, decoded.positions());
    }

    @Test
    @DisplayName("an empty snapshot round trips as empty, not as a missing one")
    void emptySnapshotRoundTrip() {
        RCNetwork.HostSnapshotPayload original =
                new RCNetwork.HostSnapshotPayload(OVERWORLD, List.of());

        RegistryFriendlyByteBuf encoded = buffer();
        RCNetwork.HostSnapshotPayload.STREAM_CODEC.encode(encoded, original);
        RCNetwork.HostSnapshotPayload decoded = RCNetwork.HostSnapshotPayload.STREAM_CODEC.decode(encoded);

        assertEquals(List.of(), decoded.positions(),
                "an empty set is a valid answer - the level really has no hosts");
    }

    @Test
    @DisplayName("a change carries its position and its direction")
    void changeRoundTrip() {
        for (boolean present : new boolean[] { true, false }) {
            RCNetwork.HostChangePayload original =
                    new RCNetwork.HostChangePayload(OVERWORLD, new BlockPos(-7, 69, -22), present);

            RegistryFriendlyByteBuf encoded = buffer();
            RCNetwork.HostChangePayload.STREAM_CODEC.encode(encoded, original);
            RCNetwork.HostChangePayload decoded = RCNetwork.HostChangePayload.STREAM_CODEC.decode(encoded);

            assertEquals(original.dimension(), decoded.dimension());
            assertEquals(original.pos(), decoded.pos());
            assertEquals(present, decoded.present());
        }
    }
}

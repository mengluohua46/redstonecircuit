package com.jiangyuefengyu.redstonecircuit.network;

import java.util.ArrayList;
import java.util.List;

import com.jiangyuefengyu.redstonecircuit.RedstoneCircuit;
import com.jiangyuefengyu.redstonecircuit.client.ClientHostCache;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * The two packets that tell a client which blocks hide redstone.
 *
 * <h2>Why the client needs to be told</h2>
 * The data lives in a server-side {@code SavedData}, so a client has no way of knowing that the
 * stone in front of it holds a redstone component - and it needs to know, because the block's
 * <em>appearance</em> depends on it. The host renderer draws a translucent shell around every such
 * block, and that mesh is built from the client's own copy of the set.
 *
 * <p>The alternative (a synced {@code AttachmentType}) was rejected on purpose: attachments sync on
 * block entities, chunks, entities and levels, not on arbitrary positions, and they resend whole
 * payloads rather than the one position that changed.
 *
 * <h2>Two messages, not one</h2>
 * <ul>
 *   <li>{@link HostSnapshotPayload} - the whole set for one dimension. Sent when a player joins,
 *       changes dimension or respawns, because those are exactly the moments the client has no
 *       usable data at all.</li>
 *   <li>{@link HostChangePayload} - one position gained or lost a component. Sent on every
 *       placement, retrieval and host break, so the common case costs a few bytes.</li>
 * </ul>
 * Minecraft delivers payloads of one connection in order, so the snapshot always lands before any
 * change that follows it.
 */
public final class RCNetwork {

    /** Payload version; bump it whenever a codec below changes shape. */
    private static final String VERSION = "1";

    private RCNetwork() {
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar(VERSION)
                .playToClient(HostSnapshotPayload.TYPE, HostSnapshotPayload.STREAM_CODEC,
                        HostSnapshotPayload::handle)
                .playToClient(HostChangePayload.TYPE, HostChangePayload.STREAM_CODEC,
                        HostChangePayload::handle);
    }

    /** Every block in one dimension that holds a component. Replaces whatever the client had. */
    public record HostSnapshotPayload(ResourceKey<Level> dimension, List<BlockPos> positions)
            implements CustomPacketPayload {

        public static final CustomPacketPayload.Type<HostSnapshotPayload> TYPE =
                new CustomPacketPayload.Type<>(RedstoneCircuit.id("host_snapshot"));

        public static final StreamCodec<RegistryFriendlyByteBuf, HostSnapshotPayload> STREAM_CODEC =
                StreamCodec.of(HostSnapshotPayload::encode, HostSnapshotPayload::decode);

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        private static void encode(RegistryFriendlyByteBuf buffer, HostSnapshotPayload payload) {
            buffer.writeResourceLocation(payload.dimension().location());
            buffer.writeVarInt(payload.positions().size());
            for (BlockPos pos : payload.positions()) {
                buffer.writeBlockPos(pos);
            }
        }

        private static HostSnapshotPayload decode(RegistryFriendlyByteBuf buffer) {
            ResourceKey<Level> dimension =
                    ResourceKey.create(Registries.DIMENSION, buffer.readResourceLocation());
            int size = buffer.readVarInt();
            List<BlockPos> positions = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                positions.add(buffer.readBlockPos());
            }
            return new HostSnapshotPayload(dimension, positions);
        }

        // Runs on the client only: this is a playToClient payload, so it can never arrive on a
        // dedicated server, and the cache it touches therefore never has to be loaded there.
        private static void handle(HostSnapshotPayload payload, IPayloadContext context) {
            context.enqueueWork(() ->
                    ClientHostCache.applySnapshot(payload.dimension(), payload.positions()));
        }
    }

    /** One block gained or lost a component. */
    public record HostChangePayload(ResourceKey<Level> dimension, BlockPos pos, boolean present)
            implements CustomPacketPayload {

        public static final CustomPacketPayload.Type<HostChangePayload> TYPE =
                new CustomPacketPayload.Type<>(RedstoneCircuit.id("host_change"));

        public static final StreamCodec<RegistryFriendlyByteBuf, HostChangePayload> STREAM_CODEC =
                StreamCodec.of(HostChangePayload::encode, HostChangePayload::decode);

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        private static void encode(RegistryFriendlyByteBuf buffer, HostChangePayload payload) {
            buffer.writeResourceLocation(payload.dimension().location());
            buffer.writeBlockPos(payload.pos());
            buffer.writeBoolean(payload.present());
        }

        private static HostChangePayload decode(RegistryFriendlyByteBuf buffer) {
            ResourceKey<Level> dimension =
                    ResourceKey.create(Registries.DIMENSION, buffer.readResourceLocation());
            BlockPos pos = buffer.readBlockPos();
            boolean present = buffer.readBoolean();
            return new HostChangePayload(dimension, pos, present);
        }

        private static void handle(HostChangePayload payload, IPayloadContext context) {
            context.enqueueWork(() ->
                    ClientHostCache.applyChange(payload.dimension(), payload.pos(), payload.present()));
        }
    }
}

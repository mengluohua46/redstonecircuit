package com.jiangyuefengyu.redstonecircuit.network;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import com.jiangyuefengyu.redstonecircuit.RedstoneCircuit;
import com.jiangyuefengyu.redstonecircuit.client.ClientHostCache;
import com.jiangyuefengyu.redstonecircuit.client.WrenchClientState;
import com.jiangyuefengyu.redstonecircuit.data.HostEntry;
import com.jiangyuefengyu.redstonecircuit.data.Slot;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * The packets that tell a client which blocks hide redstone, and what is inside them.
 *
 * <h2>Why the client needs to be told</h2>
 * The data lives in a server-side {@code SavedData}, so a client has no way of knowing that the stone
 * in front of it holds a redstone component - and it needs to know, because the block's
 * <em>appearance</em> depends on it. The renderer draws a translucent shell around every such block
 * and the component inside it, and that geometry is built from the client's own copy of the set.
 *
 * <p>The alternative (a synced {@code AttachmentType}) was rejected on purpose: attachments sync on
 * block entities, chunks, entities and levels, not on arbitrary positions, and they resend whole
 * payloads rather than the one position that changed.
 *
 * <h2>Two messages, not one</h2>
 * <ul>
 *   <li>{@link HostSnapshotPayload} - everything for one dimension, positions <em>and</em> contents.
 *       Sent when a player joins, changes dimension or respawns, because those are exactly the moments
 *       the client has no usable data at all.</li>
 *   <li>{@link HostSlotPayload} - one position's contents: added, changed or removed. A component
 *       changes constantly while a circuit runs (a wire drops a step, a repeater relays, a lever is
 *       thrown), so this is the common case and it is deliberately tiny - one position and one small
 *       NBT tag.</li>
 * </ul>
 * Minecraft delivers payloads of one connection in order, so the snapshot always lands before any
 * change that follows it.
 */
public final class RCNetwork {

    /**
     * Payload version; bump it whenever a codec below changes shape.
     *
     * <p>2: the sync carries each component's contents rather than only its position.
     */
    private static final String VERSION = "2";

    private RCNetwork() {
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar(VERSION)
                .playToClient(HostSnapshotPayload.TYPE, HostSnapshotPayload.STREAM_CODEC,
                        HostSnapshotPayload::handle)
                .playToClient(HostSlotPayload.TYPE, HostSlotPayload.STREAM_CODEC,
                        HostSlotPayload::handle)
                .playToClient(WrenchSelectionPayload.TYPE, WrenchSelectionPayload.STREAM_CODEC,
                        WrenchSelectionPayload::handle);
    }

    // ----------------------------------------------------------------- codecs --

    /**
     * Writes one component's contents as NBT.
     *
     * <p>{@link Slot#save}/{@link Slot#load} already exist for the world save, so reusing them means
     * the two representations cannot drift apart - and a field added later is carried to the client
     * without touching this class. {@code injectedPower} is deliberately not saved, and therefore not
     * sent: it is a debug/testing seed, not part of what a client draws.
     */
    static void writeSlot(RegistryFriendlyByteBuf buffer, @Nullable Slot slot) {
        buffer.writeBoolean(slot != null);
        if (slot != null) {
            CompoundTag tag = new CompoundTag();
            slot.save(tag);
            buffer.writeNbt(tag);
        }
    }

    @Nullable
    static Slot readSlot(RegistryFriendlyByteBuf buffer) {
        if (!buffer.readBoolean()) {
            return null;
        }
        CompoundTag tag = buffer.readNbt();
        return tag == null ? null : Slot.load(tag);
    }

    /** Every block in one dimension that holds a component, with what is inside it. */
    public record HostSnapshotPayload(ResourceKey<Level> dimension, List<HostEntry> entries)
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
            buffer.writeVarInt(payload.entries().size());
            for (HostEntry entry : payload.entries()) {
                buffer.writeBlockPos(entry.pos());
                writeSlot(buffer, entry.slot());
            }
        }

        private static HostSnapshotPayload decode(RegistryFriendlyByteBuf buffer) {
            ResourceKey<Level> dimension =
                    ResourceKey.create(Registries.DIMENSION, buffer.readResourceLocation());
            int size = buffer.readVarInt();
            List<HostEntry> entries = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                BlockPos pos = buffer.readBlockPos();
                Slot slot = readSlot(buffer);
                if (slot != null) {
                    entries.add(new HostEntry(pos, slot));
                }
            }
            return new HostSnapshotPayload(dimension, entries);
        }

        // Runs on the client only: this is a playToClient payload, so it can never arrive on a
        // dedicated server, and the cache it touches therefore never has to be loaded there.
        private static void handle(HostSnapshotPayload payload, IPayloadContext context) {
            context.enqueueWork(() ->
                    ClientHostCache.applySnapshot(payload.dimension(), payload.entries()));
        }
    }

    /**
     * One or more positions gained, changed or lost their component.
     *
     * <p>A list rather than a single position because a solve usually touches a whole wire at once:
     * one packet per solve beats one per block, and a {@code null} slot inside the list is the "lost
     * it" case, so placements, changes and removals all travel the same way.
     */
    public record HostSlotPayload(ResourceKey<Level> dimension, List<HostEntry> entries)
            implements CustomPacketPayload {

        public static final CustomPacketPayload.Type<HostSlotPayload> TYPE =
                new CustomPacketPayload.Type<>(RedstoneCircuit.id("host_slot"));

        public static final StreamCodec<RegistryFriendlyByteBuf, HostSlotPayload> STREAM_CODEC =
                StreamCodec.of(HostSlotPayload::encode, HostSlotPayload::decode);

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        private static void encode(RegistryFriendlyByteBuf buffer, HostSlotPayload payload) {
            buffer.writeResourceLocation(payload.dimension().location());
            buffer.writeVarInt(payload.entries().size());
            for (HostEntry entry : payload.entries()) {
                buffer.writeBlockPos(entry.pos());
                writeSlot(buffer, entry.slot());
            }
        }

        private static HostSlotPayload decode(RegistryFriendlyByteBuf buffer) {
            ResourceKey<Level> dimension =
                    ResourceKey.create(Registries.DIMENSION, buffer.readResourceLocation());
            int size = buffer.readVarInt();
            List<HostEntry> entries = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                BlockPos pos = buffer.readBlockPos();
                entries.add(new HostEntry(pos, readSlot(buffer)));
            }
            return new HostSlotPayload(dimension, entries);
        }

        private static void handle(HostSlotPayload payload, IPayloadContext context) {
            context.enqueueWork(() ->
                    ClientHostCache.applyChanges(payload.dimension(), payload.entries()));
        }
    }

    /**
     * Which block the player's wrench is currently pointing at, so the client can highlight it.
     *
     * <p>Only visual, and only to the player who owns the selection: it is stored per player on the
     * server and echoed back, because a client cannot know which position its own click selected.
     */
    public record WrenchSelectionPayload(boolean hasSelection, BlockPos pos)
            implements CustomPacketPayload {

        public static final CustomPacketPayload.Type<WrenchSelectionPayload> TYPE =
                new CustomPacketPayload.Type<>(RedstoneCircuit.id("wrench_selection"));

        public static final StreamCodec<RegistryFriendlyByteBuf, WrenchSelectionPayload> STREAM_CODEC =
                StreamCodec.of(WrenchSelectionPayload::encode, WrenchSelectionPayload::decode);

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        /** "Nothing selected" - the position is irrelevant and sent as the origin. */
        public static WrenchSelectionPayload none() {
            return new WrenchSelectionPayload(false, BlockPos.ZERO);
        }

        private static void encode(RegistryFriendlyByteBuf buffer, WrenchSelectionPayload payload) {
            buffer.writeBoolean(payload.hasSelection());
            buffer.writeBlockPos(payload.pos());
        }

        private static WrenchSelectionPayload decode(RegistryFriendlyByteBuf buffer) {
            return new WrenchSelectionPayload(buffer.readBoolean(), buffer.readBlockPos());
        }

        private static void handle(WrenchSelectionPayload payload, IPayloadContext context) {
            context.enqueueWork(() -> WrenchClientState.setSelection(
                    payload.hasSelection() ? payload.pos() : null));
        }
    }
}

package com.jiangyuefengyu.redstonecircuit.network;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import com.jiangyuefengyu.redstonecircuit.RCConfig;
import com.jiangyuefengyu.redstonecircuit.RedstoneCircuit;
import com.jiangyuefengyu.redstonecircuit.blueprint.BlueprintExport;
import com.jiangyuefengyu.redstonecircuit.blueprint.BlueprintUploadRequests;
import com.jiangyuefengyu.redstonecircuit.block.ClockBlock;
import com.jiangyuefengyu.redstonecircuit.block.ExtenderBlock;
import com.jiangyuefengyu.redstonecircuit.block.ControllerBlock;
import com.jiangyuefengyu.redstonecircuit.block.TransformerBlock;
import com.jiangyuefengyu.redstonecircuit.block.LogicIntegratorBlock;
import com.jiangyuefengyu.redstonecircuit.block.RcPlateBlock;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierId;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierIndex;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierKind;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierPose;
import com.jiangyuefengyu.redstonecircuit.client.ClientHostCache;
import com.jiangyuefengyu.redstonecircuit.client.DeviceScreens;
import com.jiangyuefengyu.redstonecircuit.client.LogicIntegratorScreens;
import com.jiangyuefengyu.redstonecircuit.client.StructurePreviewClient;
import com.jiangyuefengyu.redstonecircuit.client.StructureLiveViews;
import com.jiangyuefengyu.redstonecircuit.client.StructureScreens;
import com.jiangyuefengyu.redstonecircuit.client.WrenchClientState;
import com.jiangyuefengyu.redstonecircuit.data.HostEntry;
import com.jiangyuefengyu.redstonecircuit.data.Slot;
import com.jiangyuefengyu.redstonecircuit.logic.DeviceKind;
import com.jiangyuefengyu.redstonecircuit.logic.LogicGate;
import com.jiangyuefengyu.redstonecircuit.structure.PreviewRegion;
import com.jiangyuefengyu.redstonecircuit.structure.RegionEditor;
import com.jiangyuefengyu.redstonecircuit.structure.StructureEdits;

import io.netty.handler.codec.DecoderException;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import org.joml.Quaterniond;
import org.joml.Vector3d;

/**
 * The packets that tell a client which blocks hide redstone, and what is inside them.
 *
 * <p>The client needs its own copy of the server-side store because the renderer draws the shell and the
 * component from it. {@link HostSnapshotPayload} carries everything for one dimension (join, dimension change,
 * respawn); {@link HostSlotPayload} the positions that gained, changed or lost their component. Payloads of one
 * connection arrive in order, so a snapshot always lands before the changes that follow it.
 */
public final class RCNetwork {

    /**
     * Payload version; bump it whenever a codec below changes shape. Both sides have to be the same build: a shape
     * change is invisible on the wire to an older client, which would keep drawing what it was last told.
     */
    private static final String VERSION = "15";

    private RCNetwork() {
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar(VERSION)
                .playToClient(HostSnapshotPayload.TYPE, HostSnapshotPayload.STREAM_CODEC,
                        HostSnapshotPayload::handle)
                .playToClient(HostSlotPayload.TYPE, HostSlotPayload.STREAM_CODEC,
                        HostSlotPayload::handle)
                .playToClient(WrenchSelectionPayload.TYPE, WrenchSelectionPayload.STREAM_CODEC,
                        WrenchSelectionPayload::handle)
                .playToClient(CarrierPayload.TYPE, CarrierPayload.STREAM_CODEC,
                        CarrierPayload::handle)
                .playToClient(CarrierSnapshotPayload.TYPE, CarrierSnapshotPayload.STREAM_CODEC,
                        CarrierSnapshotPayload::handle)
                .playToServer(ManualTriggerPayload.TYPE, ManualTriggerPayload.STREAM_CODEC,
                        ManualTriggerPayload::handle)
                .playToClient(PreviewSnapshotPayload.TYPE, PreviewSnapshotPayload.STREAM_CODEC,
                        PreviewSnapshotPayload::handle)
                .playToServer(StructureEditPayload.TYPE, StructureEditPayload.STREAM_CODEC,
                        StructureEditPayload::handle)
                .playToClient(StructureLivePayload.TYPE, StructureLivePayload.STREAM_CODEC, StructureLivePayload::handle)
                .playToClient(StructureMovePayload.TYPE, StructureMovePayload.STREAM_CODEC, StructureMovePayload::handle)
                .playToClient(OpenStructureScreenPayload.TYPE, OpenStructureScreenPayload.STREAM_CODEC,
                        OpenStructureScreenPayload::handle)
                .playToClient(LogicViewPayload.TYPE, LogicViewPayload.STREAM_CODEC, LogicViewPayload::handle)
                .playToServer(LogicEditPayload.TYPE, LogicEditPayload.STREAM_CODEC, LogicEditPayload::handle)
                .playToClient(DeviceViewPayload.TYPE, DeviceViewPayload.STREAM_CODEC, DeviceViewPayload::handle)
                .playToServer(SchematicSelectionPayload.TYPE, SchematicSelectionPayload.STREAM_CODEC,
                        SchematicSelectionPayload::handle)
                .playToServer(DeviceEditPayload.TYPE, DeviceEditPayload.STREAM_CODEC, DeviceEditPayload::handle);
    }

    // ----------------------------------------------------------------- codecs --

    /**
     * Writes one component's contents as NBT. Reusing {@link Slot#save}/{@link Slot#load} keeps the world save and the
     * wire from drifting apart; {@code injectedPower} is a debug/testing seed, so it is neither saved nor sent.
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

    /**
     * Writes a carrier's identity: which kind of structure it is, and its UUID. Hand-written rather than NBT because
     * this travels on every pose update, and the kind is an enum of two values, so a byte beats a length prefix.
     */
    static void writeCarrier(RegistryFriendlyByteBuf buffer, CarrierId carrier) {
        buffer.writeByte(carrier.kind().ordinal());
        buffer.writeUUID(carrier.uuid());
    }

    static CarrierId readCarrier(RegistryFriendlyByteBuf buffer) {
        int ordinal = buffer.readByte();
        CarrierKind[] kinds = CarrierKind.values();
        CarrierKind kind = ordinal >= 0 && ordinal < kinds.length ? kinds[ordinal]
                : CarrierKind.CONTRAPTION;
        return new CarrierId(kind, buffer.readUUID());
    }

    /**
     * Writes a structure's pose: position, pivot, scale and the rotation as a quaternion - not optional, because a
     * machine on rails or in the air carries a tilt an orientation quantised to right angles would place wrongly.
     */
    static void writePose(RegistryFriendlyByteBuf buffer, @Nullable CarrierPose pose) {
        buffer.writeBoolean(pose != null);
        if (pose == null) {
            return;
        }
        buffer.writeDouble(pose.position().x());
        buffer.writeDouble(pose.position().y());
        buffer.writeDouble(pose.position().z());
        buffer.writeDouble(pose.orientation().x);
        buffer.writeDouble(pose.orientation().y);
        buffer.writeDouble(pose.orientation().z);
        buffer.writeDouble(pose.orientation().w);
        buffer.writeDouble(pose.pivot().x());
        buffer.writeDouble(pose.pivot().y());
        buffer.writeDouble(pose.pivot().z());
        buffer.writeDouble(pose.scale());
    }

    /**
     * Reads a pose back, or {@code null}: every number is checked for finiteness, because a NaN reaching the pose
     * stack would turn the shell into geometry that is nowhere at all.
     */
    @Nullable
    static CarrierPose readPose(RegistryFriendlyByteBuf buffer) {
        if (!buffer.readBoolean()) {
            return null;
        }
        Vector3d position = new Vector3d(buffer.readDouble(), buffer.readDouble(), buffer.readDouble());
        Quaterniond orientation = new Quaterniond(
                buffer.readDouble(), buffer.readDouble(), buffer.readDouble(), buffer.readDouble());
        Vector3d pivot = new Vector3d(buffer.readDouble(), buffer.readDouble(), buffer.readDouble());
        double scale = buffer.readDouble();
        if (!position.isFinite() || !orientation.isFinite() || !pivot.isFinite()
                || !Double.isFinite(scale) || scale == 0.0
                || orientation.lengthSquared() < 1.0E-9) {
            return null;
        }
        return new CarrierPose(position, orientation.normalize(), pivot, scale);
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
     * One or more positions gained, changed or lost their component. A list rather than a single position because a
     * solve usually touches a whole wire at once, and a {@code null} slot inside the list is the "lost it" case, so
     * placements, changes and removals all travel the same way.
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
     * One carrier's pose, and the hosts it is carrying; a {@code null} pose means the carrier is gone and everything it
     * carried goes with it. Pose and hosts travel together so a client can never hold a host whose carrier it never
     * heard of. {@code removed} lists the local coordinates the server <b>discarded</b> (destroyed, not put down).
     */
    public record CarrierPayload(ResourceKey<Level> dimension, CarrierId carrier,
                                 @Nullable CarrierPose pose, List<CarrierIndex.CarriedView> hosts,
                                 List<BlockPos> removed)
            implements CustomPacketPayload {

        /** The message with nothing to delete; what every ordinary send carries. */
        public CarrierPayload(ResourceKey<Level> dimension, CarrierId carrier,
                              @Nullable CarrierPose pose, List<CarrierIndex.CarriedView> hosts) {
            this(dimension, carrier, pose, hosts, List.of());
        }

        public static final CustomPacketPayload.Type<CarrierPayload> TYPE =
                new CustomPacketPayload.Type<>(RedstoneCircuit.id("carrier"));

        public static final StreamCodec<RegistryFriendlyByteBuf, CarrierPayload> STREAM_CODEC =
                StreamCodec.of(CarrierPayload::encode, CarrierPayload::decode);

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        private static void encode(RegistryFriendlyByteBuf buffer, CarrierPayload payload) {
            buffer.writeResourceLocation(payload.dimension().location());
            writeCarrier(buffer, payload.carrier());
            writePose(buffer, payload.pose());
            buffer.writeVarInt(payload.hosts().size());
            for (CarrierIndex.CarriedView host : payload.hosts()) {
                buffer.writeBlockPos(host.localPos());
                buffer.writeBlockPos(host.origin());
                buffer.writeBlockPos(host.at());
                writeSlot(buffer, host.slot());
            }
            buffer.writeVarInt(payload.removed().size());
            for (BlockPos localPos : payload.removed()) {
                buffer.writeBlockPos(localPos);
            }
        }

        private static CarrierPayload decode(RegistryFriendlyByteBuf buffer) {
            ResourceKey<Level> dimension =
                    ResourceKey.create(Registries.DIMENSION, buffer.readResourceLocation());
            CarrierId carrier = readCarrier(buffer);
            CarrierPose pose = readPose(buffer);
            int size = buffer.readVarInt();
            List<CarrierIndex.CarriedView> hosts = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                BlockPos localPos = buffer.readBlockPos();
                BlockPos origin = buffer.readBlockPos();
                BlockPos at = buffer.readBlockPos();
                Slot slot = readSlot(buffer);
                if (slot != null) {
                    hosts.add(new CarrierIndex.CarriedView(carrier, localPos, origin, at, slot));
                }
            }
            int removedSize = buffer.readVarInt();
            List<BlockPos> removed = new ArrayList<>(removedSize);
            for (int i = 0; i < removedSize; i++) {
                removed.add(buffer.readBlockPos());
            }
            return new CarrierPayload(dimension, carrier, pose, List.copyOf(hosts),
                    List.copyOf(removed));
        }

        // Runs on the client only, like the other playToClient payloads: the cache it touches never
        // has to exist on a dedicated server.
        private static void handle(CarrierPayload payload, IPayloadContext context) {
            context.enqueueWork(() -> ClientHostCache.applyCarrier(
                    payload.dimension(), payload.carrier(), payload.pose(), payload.hosts(),
                    payload.removed()));
        }
    }

    /** Every carrier in a dimension with its pose and hosts; the "you just logged in" case. */
    public record CarrierSnapshotPayload(ResourceKey<Level> dimension,
                                         List<CarrierPayload> carriers)
            implements CustomPacketPayload {

        public static final CustomPacketPayload.Type<CarrierSnapshotPayload> TYPE =
                new CustomPacketPayload.Type<>(RedstoneCircuit.id("carrier_snapshot"));

        public static final StreamCodec<RegistryFriendlyByteBuf, CarrierSnapshotPayload> STREAM_CODEC =
                StreamCodec.of(CarrierSnapshotPayload::encode, CarrierSnapshotPayload::decode);

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        private static void encode(RegistryFriendlyByteBuf buffer, CarrierSnapshotPayload payload) {
            buffer.writeResourceLocation(payload.dimension().location());
            buffer.writeVarInt(payload.carriers().size());
            for (CarrierPayload carrier : payload.carriers()) {
                CarrierPayload.STREAM_CODEC.encode(buffer, carrier);
            }
        }

        private static CarrierSnapshotPayload decode(RegistryFriendlyByteBuf buffer) {
            ResourceKey<Level> dimension =
                    ResourceKey.create(Registries.DIMENSION, buffer.readResourceLocation());
            int size = buffer.readVarInt();
            List<CarrierPayload> carriers = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                // Each inner payload is written by CarrierPayload.encode, dimension and all, so it is decoded by that
                // one decoder rather than by a second copy that could fall out of step; the outer dimension wins.
                CarrierPayload decoded = CarrierPayload.STREAM_CODEC.decode(buffer);
                carriers.add(new CarrierPayload(dimension, decoded.carrier(), decoded.pose(),
                        decoded.hosts()));
            }
            return new CarrierSnapshotPayload(dimension, List.copyOf(carriers));
        }

        private static void handle(CarrierSnapshotPayload payload, IPayloadContext context) {
            context.enqueueWork(() -> {
                List<CarrierIndex.CarriedView> hosts = new ArrayList<>();
                Map<CarrierId, CarrierPose> poses = new HashMap<>();
                for (CarrierPayload carrier : payload.carriers()) {
                    if (carrier.pose() != null) {
                        poses.put(carrier.carrier(), carrier.pose());
                    }
                    hosts.addAll(carrier.hosts());
                }
                ClientHostCache.applyCarrierSnapshot(payload.dimension(), hosts, poses);
            });
        }
    }

    /**
     * Which block the player's wrench is currently pointing at, so the client can highlight it. A pick inside a
     * structure also travels as a carrier plus a local block, because a world position is a whole-block coordinate while
     * a machine draws between the grid lines; {@code pos} stays as the projected world position.
     */
    public record WrenchSelectionPayload(boolean hasSelection, BlockPos pos, boolean liveHost,
                                         @Nullable CarrierId carried, @Nullable BlockPos carriedLocal)
            implements CustomPacketPayload {

        public static final CustomPacketPayload.Type<WrenchSelectionPayload> TYPE =
                new CustomPacketPayload.Type<>(RedstoneCircuit.id("wrench_selection"));

        public static final StreamCodec<RegistryFriendlyByteBuf, WrenchSelectionPayload> STREAM_CODEC =
                StreamCodec.of(WrenchSelectionPayload::encode, WrenchSelectionPayload::decode);

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        /** The world's pick - a block standing in the world, which has no structure behind it. */
        public WrenchSelectionPayload(boolean hasSelection, BlockPos pos, boolean liveHost) {
            this(hasSelection, pos, liveHost, null, null);
        }

        /** "Nothing selected" - the position is irrelevant and sent as the origin. */
        public static WrenchSelectionPayload none() {
            return new WrenchSelectionPayload(false, BlockPos.ZERO, false, null, null);
        }

        private static void encode(RegistryFriendlyByteBuf buffer, WrenchSelectionPayload payload) {
            buffer.writeBoolean(payload.hasSelection());
            buffer.writeBlockPos(payload.pos());
            // Whether the pick still points at a block holding a component: only the server knows, and the client
            // draws the selection box on this flag alone.
            buffer.writeBoolean(payload.liveHost());
            // The two carried fields go together or not at all, so one flag carries both and a payload cannot name a
            // structure without saying which of its blocks.
            boolean carried = payload.carried() != null && payload.carriedLocal() != null;
            buffer.writeBoolean(carried);
            if (carried) {
                writeCarrier(buffer, payload.carried());
                buffer.writeBlockPos(payload.carriedLocal());
            }
        }

        private static WrenchSelectionPayload decode(RegistryFriendlyByteBuf buffer) {
            boolean hasSelection = buffer.readBoolean();
            BlockPos pos = buffer.readBlockPos();
            boolean liveHost = buffer.readBoolean();
            if (!buffer.readBoolean()) {
                return new WrenchSelectionPayload(hasSelection, pos, liveHost, null, null);
            }
            CarrierId carried = readCarrier(buffer);
            return new WrenchSelectionPayload(hasSelection, pos, liveHost, carried, buffer.readBlockPos());
        }

        private static void handle(WrenchSelectionPayload payload, IPayloadContext context) {
            context.enqueueWork(() -> WrenchClientState.setSelection(
                    payload.hasSelection() ? payload.pos() : null, payload.liveHost(),
                    payload.carried(), payload.carriedLocal()));
        }
    }

    /**
     * <b>Every preview border in one dimension</b>, sent whole whenever any of them changes and once to each player as
     * they arrive.
     * <p>A snapshot rather than "this one changed": a border is a promise about what will happen next, and a client that
     * missed one message would be showing a border that is not there for as long as it stays connected. The list is
     * built by reading the block entities back ({@code StructurePreviews.snapshot}), so what a client is told cannot
     * drift from what the server will do.
     */
    public record PreviewSnapshotPayload(ResourceKey<Level> dimension,
                                         List<StructurePreviews.Entry> entries)
            implements CustomPacketPayload {

        public static final CustomPacketPayload.Type<PreviewSnapshotPayload> TYPE =
                new CustomPacketPayload.Type<>(RedstoneCircuit.id("preview_snapshot"));

        public static final StreamCodec<RegistryFriendlyByteBuf, PreviewSnapshotPayload> STREAM_CODEC =
                StreamCodec.of(PreviewSnapshotPayload::encode, PreviewSnapshotPayload::decode);

        /** Far above any real dimension's borders: a decoder that trusts a count is a decoder that allocates. */
        public static final int MAX_ENTRIES = 4096;

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        private static void encode(RegistryFriendlyByteBuf buffer, PreviewSnapshotPayload payload) {
            buffer.writeResourceLocation(payload.dimension().location());
            buffer.writeVarInt(payload.entries().size());
            for (StructurePreviews.Entry entry : payload.entries()) {
                buffer.writeBlockPos(entry.controller());
                buffer.writeBoolean(entry.release());
                buffer.writeBoolean(entry.armed());
                buffer.writeVarInt(entry.overwrite());
                PreviewRegion.STREAM_CODEC.encode(buffer, entry.region());
            }
        }

        private static PreviewSnapshotPayload decode(RegistryFriendlyByteBuf buffer) {
            ResourceKey<Level> dimension =
                    ResourceKey.create(Registries.DIMENSION, buffer.readResourceLocation());
            int size = buffer.readVarInt();
            if (size < 0 || size > MAX_ENTRIES) {
                throw new DecoderException("a preview snapshot cannot carry " + size + " border(s)");
            }
            List<StructurePreviews.Entry> entries = new ArrayList<>(size);
            for (int index = 0; index < size; index++) {
                BlockPos controller = buffer.readBlockPos();
                boolean release = buffer.readBoolean();
                boolean armed = buffer.readBoolean();
                int overwrite = buffer.readVarInt();
                PreviewRegion region = PreviewRegion.STREAM_CODEC.decode(buffer);
                entries.add(new StructurePreviews.Entry(controller, release, region, armed, overwrite));
            }
            return new PreviewSnapshotPayload(dimension, List.copyOf(entries));
        }

        // Runs on the client only, like the other playToClient payloads: this cache is the client's, and it is
        // the only thing the border renderer reads.
        private static void handle(PreviewSnapshotPayload payload, IPayloadContext context) {
            context.enqueueWork(() -> StructurePreviewClient.accept(payload.dimension(), payload.entries()));
        }
    }

    /**
     * One edit a screen made on a controller: a block, a verb and a few numbers. One payload with a verb rather than a
     * dozen types, because what matters for safety is that the <b>server</b> decides what the numbers mean: the handler
     * turns the verb into the same calls the command uses ({@code StructureEdits}), where the rules live.
     * <p>What each verb means by a-f, first and second is fixed by {@link #apply}; the region travels resolved, and both
     * strings are bounded by {@link #MAX_TEXT}.
     */
    public record StructureEditPayload(BlockPos pos, Verb verb, int a, int b, int c, int d, int e, int f,
                                       String first, String second) implements CustomPacketPayload {

        /** The verbs a screen can send. An ordinal on the wire, checked at the decoder. */
        public enum Verb {
            SET_MODE, SET_TIER, SET_IO, SET_NAME, SET_REGION, MOVE_PREVIEW, RESIZE_PREVIEW, TARGET, CANCEL, PRESS,
            SET_FACE, CLEAR_FACE, REMOVE_FACE_CELL, SET_MANUAL, REMOVE_MANUAL, CLEAR_MANUAL, PRESS_MANUAL,
            /** One row of the output table: a cell, the face it drives, and the strength. */
            SET_OUTPUT, REMOVE_OUTPUT,
            /** Hands the block the structure is holding to the player and empties the slot. */
            TAKE_OUT,
            /**
             * One panel entry replaced whole, by the index a client presses. Appended rather than slotted in beside the
             * other manual verbs because a verb is an ordinal on the wire: a rename changes the entry's identity, so a
             * remove plus an add would lose it between the two steps.
             */
            REPLACE_MANUAL
        }

        /** The longest string any verb carries: a name, a key or a label, all of them 64. */
        public static final int MAX_TEXT = 64;

        public static final CustomPacketPayload.Type<StructureEditPayload> TYPE =
                new CustomPacketPayload.Type<>(RedstoneCircuit.id("structure_edit"));

        public static final StreamCodec<RegistryFriendlyByteBuf, StructureEditPayload> STREAM_CODEC =
                StreamCodec.of(StructureEditPayload::encode, StructureEditPayload::decode);

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        /** An edit that carries no numbers, which is most of the buttons. */
        public static StructureEditPayload of(BlockPos pos, Verb verb) {
            return new StructureEditPayload(pos, verb, 0, 0, 0, 0, 0, 0, "", "");
        }

        public static StructureEditPayload flag(BlockPos pos, Verb verb, boolean value) {
            return new StructureEditPayload(pos, verb, value ? 1 : 0, 0, 0, 0, 0, 0, "", "");
        }

        public static StructureEditPayload numbers(BlockPos pos, Verb verb, int a, int b, int c) {
            return new StructureEditPayload(pos, verb, a, b, c, 0, 0, 0, "", "");
        }

        public static StructureEditPayload region(BlockPos pos, BlockPos min, net.minecraft.core.Vec3i size) {
            return new StructureEditPayload(pos, Verb.SET_REGION, min.getX(), min.getY(), min.getZ(),
                    size.getX(), size.getY(), size.getZ(), "", "");
        }

        public static StructureEditPayload text(BlockPos pos, Verb verb, String text) {
            return new StructureEditPayload(pos, verb, 0, 0, 0, 0, 0, 0, text, "");
        }

        public static StructureEditPayload face(BlockPos pos, Direction face, BlockPos cell, int strength) {
            return new StructureEditPayload(pos, Verb.SET_FACE, face.ordinal(), cell.getX(), cell.getY(),
                    cell.getZ(), strength, 0, "", "");
        }

        public static StructureEditPayload clearFace(BlockPos pos, Direction face) {
            return new StructureEditPayload(pos, Verb.CLEAR_FACE, face.ordinal(), 0, 0, 0, 0, 0, "", "");
        }

        /** One cell off one face - the row's own button on the face page, which removes what it shows. */
        public static StructureEditPayload removeFaceCell(BlockPos pos, Direction face, BlockPos cell) {
            return new StructureEditPayload(pos, Verb.REMOVE_FACE_CELL, face.ordinal(), cell.getX(), cell.getY(),
                    cell.getZ(), 0, 0, "", "");
        }

        /** One output row: while {@code cell} carries a signal, {@code face} answers {@code strength}. */
        public static StructureEditPayload output(BlockPos pos, Direction face, BlockPos cell, int strength) {
            return new StructureEditPayload(pos, Verb.SET_OUTPUT, face.ordinal(), cell.getX(), cell.getY(),
                    cell.getZ(), strength, 0, "", "");
        }

        /** The output page's per-row button: the same cell and face, removed. */
        public static StructureEditPayload removeOutput(BlockPos pos, Direction face, BlockPos cell) {
            return new StructureEditPayload(pos, Verb.REMOVE_OUTPUT, face.ordinal(), cell.getX(), cell.getY(),
                    cell.getZ(), 0, 0, "", "");
        }

        public static StructureEditPayload manual(BlockPos pos, String key, BlockPos cell, int strength,
                                                  String label) {
            return new StructureEditPayload(pos, Verb.SET_MANUAL, cell.getX(), cell.getY(), cell.getZ(),
                    strength, 0, 0, key, label);
        }

        /** The panel's "update" button: entry {@code index} becomes {@code name} on {@code cell}, at a strength. */
        public static StructureEditPayload manualReplace(BlockPos pos, int index, String name, BlockPos cell,
                                                         int strength) {
            return new StructureEditPayload(pos, Verb.REPLACE_MANUAL, index, cell.getX(), cell.getY(), cell.getZ(),
                    strength, 0, name, "");
        }

        public static StructureEditPayload entry(BlockPos pos, Verb verb, int index) {
            return new StructureEditPayload(pos, verb, index, 0, 0, 0, 0, 0, "", "");
        }

        private static void encode(RegistryFriendlyByteBuf buffer, StructureEditPayload payload) {
            buffer.writeBlockPos(payload.pos());
            buffer.writeVarInt(payload.verb().ordinal());
            buffer.writeVarInt(payload.a());
            buffer.writeVarInt(payload.b());
            buffer.writeVarInt(payload.c());
            buffer.writeVarInt(payload.d());
            buffer.writeVarInt(payload.e());
            buffer.writeVarInt(payload.f());
            buffer.writeUtf(payload.first(), MAX_TEXT);
            buffer.writeUtf(payload.second(), MAX_TEXT);
        }

        private static StructureEditPayload decode(RegistryFriendlyByteBuf buffer) {
            BlockPos pos = buffer.readBlockPos();
            int ordinal = buffer.readVarInt();
            Verb[] verbs = Verb.values();
            if (ordinal < 0 || ordinal >= verbs.length) {
                throw new DecoderException("there is no structure edit verb " + ordinal);
            }
            return new StructureEditPayload(pos, verbs[ordinal], buffer.readVarInt(), buffer.readVarInt(),
                    buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt(),
                    buffer.readUtf(MAX_TEXT), buffer.readUtf(MAX_TEXT));
        }

        // Runs on the server: every edit changes the world's controller, so the server is the only place it can
        // happen, and the client is told by the block entity update the edit itself causes.
        private static void handle(StructureEditPayload payload, IPayloadContext context) {
            context.enqueueWork(() -> {
                if (!(context.player() instanceof ServerPlayer player)) {
                    return;
                }
                StructureEdits.Result result = apply(player.serverLevel(), player, payload);
                // The refusal is the player's, on the action bar, exactly as a refused structure edit is: a button
                // that answers nothing cannot be told apart from a broken one. A success says nothing, because the
                // block's own sync is the answer.
                if (!result.ok()) {
                    player.displayClientMessage(
                            net.minecraft.network.chat.Component.literal(result.message())
                                    .withStyle(net.minecraft.ChatFormatting.RED), true);
                }
                if (RCConfig.debugLog()) {
                    RCConfig.LOGGER.info("[redstonecircuit] structure edit {} from {}{}: {}",
                            payload.verb(), player.getName().getString(),
                            result.ok() ? " done" : " refused", result.message());
                }
            });
        }

        /**
         * The wire vocabulary, translated into the one set of edits the command also uses - a switch so a reader can see
         * in one screen that no verb reaches anything except {@code StructureEdits}.
         */
        static StructureEdits.Result apply(ServerLevel level, ServerPlayer player,
                                           StructureEditPayload edit) {
            BlockPos pos = edit.pos();
            return switch (edit.verb()) {
                case SET_MODE -> StructureEdits.setMode(level, pos, edit.a() != 0);
                case SET_TIER -> StructureEdits.setTier(level, pos, edit.a() != 0, player);
                case SET_IO -> StructureEdits.setIoMode(level, pos, edit.a() != 0);
                case SET_NAME -> StructureEdits.setName(level, pos, edit.first());
                case SET_REGION -> StructureEdits.setRegion(level, pos, RegionEditor.absolute(
                        new net.minecraft.core.Vec3i(edit.a(), edit.b(), edit.c()),
                        new net.minecraft.core.Vec3i(edit.d(), edit.e(), edit.f())));
                case MOVE_PREVIEW -> StructureEdits.movePreview(level, pos,
                        new BlockPos(edit.a(), edit.b(), edit.c()));
                case RESIZE_PREVIEW -> StructureEdits.resizePreview(level, pos,
                        new net.minecraft.core.Vec3i(edit.a(), edit.b(), edit.c()));
                case TARGET -> StructureEdits.target(level, pos, new BlockPos(edit.a(), edit.b(), edit.c()));
                case CANCEL -> StructureEdits.cancel(level, pos);
                case PRESS -> StructureEdits.press(level, player, pos);
                case SET_FACE -> StructureEdits.setFaceEntry(level, pos, faceOf(edit.a()),
                        new BlockPos(edit.b(), edit.c(), edit.d()), edit.e());
                case CLEAR_FACE -> StructureEdits.clearFace(level, pos, faceOf(edit.a()));
                case REMOVE_FACE_CELL -> StructureEdits.clearFaceEntry(level, pos, faceOf(edit.a()),
                        new BlockPos(edit.b(), edit.c(), edit.d()));
                case SET_OUTPUT -> StructureEdits.setOutputEntry(level, pos,
                        new BlockPos(edit.b(), edit.c(), edit.d()), faceOf(edit.a()), edit.e());
                case REMOVE_OUTPUT -> StructureEdits.clearOutputEntry(level, pos,
                        new BlockPos(edit.b(), edit.c(), edit.d()), faceOf(edit.a()));
                case SET_MANUAL -> StructureEdits.setManualEntry(level, pos, edit.first(),
                        new BlockPos(edit.a(), edit.b(), edit.c()), edit.d(), edit.second());
                case REMOVE_MANUAL -> StructureEdits.clearManualEntry(level, pos, edit.a());
                case CLEAR_MANUAL -> StructureEdits.clearManual(level, pos);
                case PRESS_MANUAL -> StructureEdits.pressManual(level, player, pos, edit.a());
                case TAKE_OUT -> StructureEdits.takeOut(level, player, pos);
                case REPLACE_MANUAL -> StructureEdits.replaceManualEntry(level, pos, edit.a(), edit.first(),
                        new BlockPos(edit.b(), edit.c(), edit.d()), edit.e());
            };
        }

        /** A face ordinal, or null - which every table edit refuses with a sentence rather than a crash. */
        private static Direction faceOf(int ordinal) {
            Direction[] faces = Direction.values();
            return ordinal >= 0 && ordinal < faces.length ? faces[ordinal] : null;
        }
    }

    /**
     * "These cells of this structure look different now": the diff behind the live miniature; the server samples the
     * structure on a timer and sends only the cells whose block id differs from the last sample.
     * <p>A block id rather than state NBT, because {@code Block.getId}/{@code Block.stateById} are the game's compact,
     * bounded encoding, and two integers per changed cell is the smallest honest packet.
     *
     * <p><b>The index is a cell of the preview</b>, in {@link com.jiangyuefengyu.redstonecircuit.structure.StructureLiveView#index}'s
     * order over the <em>preview's</em> size - not a cell of the structure. A preview coarsened to scale {@code s}
     * stands for {@code s}³ of the structure's cells, and which one of them the picture shows is a decision only the
     * server can make: it has the payload, the scale and the real blocks in the pocket, and the answer is the surface
     * of the group rather than the last cell of it that happened to change
     * ({@link com.jiangyuefengyu.redstonecircuit.structure.StructurePreviewCells#representative}). At one cell per
     * block the two numberings are the same, so nothing about a full-detail structure changed.
     */
    public record StructureLivePayload(BlockPos pos, int[] indices, int[] blockIds, boolean restart)
            implements CustomPacketPayload {

        public static final CustomPacketPayload.Type<StructureLivePayload> TYPE =
                new CustomPacketPayload.Type<>(RedstoneCircuit.id("structure_live"));

        public static final StreamCodec<RegistryFriendlyByteBuf, StructureLivePayload> STREAM_CODEC =
                StreamCodec.of(StructureLivePayload::encode, StructureLivePayload::decode);

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        private static void encode(RegistryFriendlyByteBuf buffer, StructureLivePayload payload) {
            buffer.writeBlockPos(payload.pos());
            // The encoder and the decoder must agree on the count, and the decoder must clamp: writing more than the
            // clamp allows is a protocol error that disconnects the client, not data the other side ignores. The
            // sender batches to the same bound, so the two cannot disagree.
            int count = Math.min(payload.indices().length, payload.blockIds().length);
            count = Math.min(count,
                    com.jiangyuefengyu.redstonecircuit.structure.StructureLiveView.MAX_CHANGES_PER_PACKET);
            buffer.writeVarInt(count);
            // Whether this packet begins a whole picture: guessing it from "the first index is zero" is wrong for
            // an ordinary diff whose lowest changed cell is the structure's own corner.
            buffer.writeBoolean(payload.restart());
            for (int i = 0; i < count; i++) {
                buffer.writeVarInt(payload.indices()[i]);
                buffer.writeVarInt(payload.blockIds()[i]);
            }
        }
        private static StructureLivePayload decode(RegistryFriendlyByteBuf buffer) {
            BlockPos pos = buffer.readBlockPos();
            // Bounded by the same number the sender batches by: a packet asking for more is not a live view.
            int count = Math.min(buffer.readVarInt(),
                    com.jiangyuefengyu.redstonecircuit.structure.StructureLiveView.MAX_CHANGES_PER_PACKET);
            boolean restart = buffer.readBoolean();
            int[] indices = new int[count];
            int[] ids = new int[count];
            for (int i = 0; i < count; i++) {
                indices[i] = buffer.readVarInt();
                ids[i] = buffer.readVarInt();
            }
            return new StructureLivePayload(pos, indices, ids, restart);
        }

        // Runs on the client only: the cache this fills is the client's, like every other playToClient payload.
        private static void handle(StructureLivePayload payload, IPayloadContext context) {
            context.enqueueWork(() -> StructureLiveViews.apply(payload.pos(), payload.indices(), payload.blockIds(),
                    payload.restart()));
        }
    }

    /**
     * Cells that are in the middle of a move: what each one is moving, and the cell it comes from. A move takes two
     * ticks and the pocket's block entities are server-side, so this is one fact a block state cannot carry; it travels
     * only when there is anything to say, capped like the live view.
     */
    public record StructureMovePayload(BlockPos pos, int[] indices, int[] states, int[] from)
            implements CustomPacketPayload {

        public static final CustomPacketPayload.Type<StructureMovePayload> TYPE =
                new CustomPacketPayload.Type<>(RedstoneCircuit.id("structure_moves"));

        public static final StreamCodec<RegistryFriendlyByteBuf, StructureMovePayload> STREAM_CODEC =
                StreamCodec.of(StructureMovePayload::encode, StructureMovePayload::decode);

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        private static void encode(RegistryFriendlyByteBuf buffer, StructureMovePayload payload) {
            buffer.writeBlockPos(payload.pos());
            int count = Math.min(Math.min(payload.indices().length, payload.states().length),
                    Math.min(payload.from().length,
                            com.jiangyuefengyu.redstonecircuit.structure.StructureLiveView.MAX_MOVES_PER_PACKET));
            buffer.writeVarInt(count);
            for (int i = 0; i < count; i++) {
                buffer.writeVarInt(payload.indices()[i]);
                buffer.writeVarInt(payload.states()[i]);
                buffer.writeVarInt(payload.from()[i]);
            }
        }

        private static StructureMovePayload decode(RegistryFriendlyByteBuf buffer) {
            BlockPos pos = buffer.readBlockPos();
            int count = Math.min(buffer.readVarInt(),
                    com.jiangyuefengyu.redstonecircuit.structure.StructureLiveView.MAX_MOVES_PER_PACKET);
            int[] indices = new int[count];
            int[] states = new int[count];
            int[] from = new int[count];
            for (int i = 0; i < count; i++) {
                indices[i] = buffer.readVarInt();
                states[i] = buffer.readVarInt();
                from[i] = buffer.readVarInt();
            }
            return new StructureMovePayload(pos, indices, states, from);
        }

        private static void handle(StructureMovePayload payload, IPayloadContext context) {
            context.enqueueWork(() -> StructureLiveViews.applyMoves(payload.pos(), payload.indices(), payload.states(),
                    payload.from()));
        }
    }

    /**
     * "Open your editor for the controller at this position." One position and nothing else: the screen reads what it
     * shows from the block entity and sends its changes back as its own edits, and the server answers the
     * sneak-right-click, so this is a payload rather than a client-side branch in the block.
     * <p>The handler calls {@code StructureScreens.open}, whose signature mentions no client-only type - naming
     * {@code Minecraft} or {@code Screen} in a common class makes a dedicated server refuse to load the mod. The two
     * paths are the save's own directories, so a player who cannot see where a structure file is can still copy it out.
     */
    public record OpenStructureScreenPayload(BlockPos pos, String structuresPath, String exportPath)
            implements CustomPacketPayload {

        public static final CustomPacketPayload.Type<OpenStructureScreenPayload> TYPE =
                new CustomPacketPayload.Type<>(RedstoneCircuit.id("open_structure_screen"));

        /** Long enough for a save path on any platform, and short enough that nobody can send a novel. */
        public static final int MAX_PATH = 260;

        public static final StreamCodec<RegistryFriendlyByteBuf, OpenStructureScreenPayload> STREAM_CODEC =
                StreamCodec.of(OpenStructureScreenPayload::encode, OpenStructureScreenPayload::decode);

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        private static void encode(RegistryFriendlyByteBuf buffer, OpenStructureScreenPayload payload) {
            buffer.writeBlockPos(payload.pos());
            buffer.writeUtf(payload.structuresPath(), MAX_PATH);
            buffer.writeUtf(payload.exportPath(), MAX_PATH);
        }

        private static OpenStructureScreenPayload decode(RegistryFriendlyByteBuf buffer) {
            return new OpenStructureScreenPayload(buffer.readBlockPos(), buffer.readUtf(MAX_PATH),
                    buffer.readUtf(MAX_PATH));
        }

        // Runs on the client only, like the other playToClient payloads.
        private static void handle(OpenStructureScreenPayload payload, IPayloadContext context) {
            context.enqueueWork(() -> StructureScreens.open(payload));
        }
    }

    /**
     * One manual key pressed on a placed structure: the client's entire input for mode B. The request carries a command
     * and a state, never a structure, so the cell, the strength and the pulse length are the server's own copy. There is
     * no "released" half: a press is a pulse with its own end, so a client that vanishes mid-press leaves nothing pushing.
     */
    public record ManualTriggerPayload(BlockPos pos, int entry) implements CustomPacketPayload {

        public static final CustomPacketPayload.Type<ManualTriggerPayload> TYPE =
                new CustomPacketPayload.Type<>(RedstoneCircuit.id("manual_trigger"));

        public static final StreamCodec<RegistryFriendlyByteBuf, ManualTriggerPayload> STREAM_CODEC =
                StreamCodec.of(ManualTriggerPayload::encode, ManualTriggerPayload::decode);

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        private static void encode(RegistryFriendlyByteBuf buffer, ManualTriggerPayload payload) {
            buffer.writeBlockPos(payload.pos());
            buffer.writeVarInt(payload.entry());
        }

        private static ManualTriggerPayload decode(RegistryFriendlyByteBuf buffer) {
            BlockPos pos = buffer.readBlockPos();
            int entry = buffer.readVarInt();
            // Bounded here as well as in the rules: a packet that names entry 200000 of a 32-entry panel is a number
            // nobody typed, and a decoder that carries it on is a decoder that trusts the sender.
            if (entry < 0 || entry > MAX_ENTRY) {
                throw new DecoderException("a manual press cannot name entry " + entry);
            }
            return new ManualTriggerPayload(pos, entry);
        }

        // Runs on the server: the rules, the cooldown and the pulse are all server-side.
        private static void handle(ManualTriggerPayload payload, IPayloadContext context) {
            context.enqueueWork(() -> {
                if (context.player() instanceof ServerPlayer player) {
                    StructureManualTrigger.Result result = StructureManualTrigger.press(player.serverLevel(),
                            player.getUUID(), player.getEyePosition(), payload.pos(), payload.entry());
                    if (!result.accepted()) {
                        if (RCConfig.debugLog()) {
                            RCConfig.LOGGER.info("[redstonecircuit] manual press from {} refused: {}",
                                    player.getName().getString(), result.refusal());
                        }
                        // The refusal is the player's, on the action bar, exactly as a refused structure edit is: a
                        // key that answers nothing cannot be told apart from a broken one.
                        player.displayClientMessage(
                                net.minecraft.network.chat.Component.literal(result.refusal())
                                        .withStyle(net.minecraft.ChatFormatting.RED), true);
                    }
                }
            });
        }

        /** Far above any legal panel, so a nameless entry index is refused before it is looked up. */
        public static final int MAX_ENTRY = 4096;
    }

    // -------------------------------------------------------- logic integrator --

    /**
     * One screenful of 逻辑整合器: which gate, the player's two numbers, and what the five faces are given. The client
     * decides none of it - the gate and the numbers live in the block state, the readings in the neighbours.
     * <p>{@code x} and {@code y} are strengths 0-15, or {@code LogicGate.UNSET} for a field the player has left empty,
     * which is not the same as zero. {@code readings} is one byte per face in {@link Direction} order, the output face
     * left at zero; {@code inputs} is the mask of the faces that count as inputs, and a face counts when it carries a
     * signal or has something attached, so an attached face may legitimately read 0.
     */
    public record LogicViewPayload(BlockPos pos, boolean open, int mode, int x, int y, int facing,
                                   int[] readings, int inputs, int power, int verdict)
            implements CustomPacketPayload {

        /** One reading per face, in {@link Direction}'s own order. */
        public static final int FACES = 6;

        public static final CustomPacketPayload.Type<LogicViewPayload> TYPE =
                new CustomPacketPayload.Type<>(RedstoneCircuit.id("logic_view"));

        public static final StreamCodec<RegistryFriendlyByteBuf, LogicViewPayload> STREAM_CODEC =
                StreamCodec.of(LogicViewPayload::encode, LogicViewPayload::decode);

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        /**
         * The message for a block that has just been read: the one place that knows the wire's field order, so the
         * block and the decoder cannot drift apart over which number is which.
         */
        public static LogicViewPayload of(BlockPos pos, boolean open, LogicIntegratorBlock.Readout readout) {
            return new LogicViewPayload(pos, open, readout.mode().ordinal(), readout.x(), readout.y(),
                    readout.facing().ordinal(), readout.readings(), readout.inputs(), readout.answer().power(),
                    readout.answer().verdict().ordinal());
        }

        private static void encode(RegistryFriendlyByteBuf buffer, LogicViewPayload payload) {
            buffer.writeBlockPos(payload.pos());
            buffer.writeBoolean(payload.open());
            buffer.writeByte(payload.mode());
            buffer.writeByte(payload.x());
            buffer.writeByte(payload.y());
            buffer.writeByte(payload.facing());
            for (int index = 0; index < FACES; index++) {
                buffer.writeByte(payload.readings()[index]);
            }
            buffer.writeByte(payload.inputs());
            buffer.writeByte(payload.power());
            buffer.writeByte(payload.verdict());
        }

        private static LogicViewPayload decode(RegistryFriendlyByteBuf buffer) {
            BlockPos pos = buffer.readBlockPos();
            boolean open = buffer.readBoolean();
            int mode = buffer.readByte();
            int x = buffer.readByte();
            int y = buffer.readByte();
            int facing = buffer.readByte();
            int[] readings = new int[FACES];
            for (int index = 0; index < FACES; index++) {
                readings[index] = buffer.readByte();
            }
            int inputs = buffer.readByte();
            int power = buffer.readByte();
            int verdict = buffer.readByte();
            // Bounded here as well as on the way out: a gate this build does not have, a strength of 90 or a sixth
            // face reading is a message no block of ours produced, and a screen that drew it would show a setting the
            // block does not have.
            if (mode < 0 || mode >= LogicGate.Mode.values().length
                    || facing < 0 || facing >= FACES
                    || !LogicGate.isSettable(x) || !LogicGate.isSettable(y)
                    || inputs < 0 || inputs >= 1 << FACES
                    || power < 0 || power > LogicGate.MAX
                    || verdict < 0 || verdict >= LogicGate.Verdict.values().length) {
                throw new DecoderException("there is no logic integrator reading " + mode + "/" + x + "/" + y + "/"
                        + facing + "/" + inputs + "/" + power + "/" + verdict);
            }
            for (int reading : readings) {
                if (reading < 0 || reading > LogicGate.MAX) {
                    throw new DecoderException("a face of a logic integrator cannot read " + reading);
                }
            }
            return new LogicViewPayload(pos, open, mode, x, y, facing, readings, inputs, power, verdict);
        }

        /** Client only: open the screen, or hand the numbers to the screen already showing this block. */
        private static void handle(LogicViewPayload payload, IPayloadContext context) {
            context.enqueueWork(() -> LogicIntegratorScreens.show(payload));
        }
    }

    /**
     * <b>One thing the player did on that screen: set 档位 and the two numbers, or ask again.</b> Two verbs share one
     * shape because both end in the same answer: the server reads the block and sends the screen what it now sees,
     * whether anything was written or not.
     *
     * <p>{@code SET} carries the player's gate and strengths, each checked again on arrival
     * ({@code LogicIntegratorBlock#applyEdit}) - a screen is a client, and a client is not trusted to name a gate that
     * exists or a strength of 200. {@code PROBE} carries nothing, which is how a screen open while a lever is thrown
     * can say so.
     */
    public record LogicEditPayload(BlockPos pos, Verb verb, int mode, int x, int y) implements CustomPacketPayload {

        /** The two questions a screen can ask. An ordinal on the wire, checked at the decoder. */
        public enum Verb {
            /** 档位 and the two strengths: what the player typed. */
            SET,
            /** Change nothing; answer with what the block sees right now. */
            PROBE
        }

        public static final CustomPacketPayload.Type<LogicEditPayload> TYPE =
                new CustomPacketPayload.Type<>(RedstoneCircuit.id("logic_edit"));

        public static final StreamCodec<RegistryFriendlyByteBuf, LogicEditPayload> STREAM_CODEC =
                StreamCodec.of(LogicEditPayload::encode, LogicEditPayload::decode);

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        /** The player's edit: one of the seven gates, and the two strengths or {@code LogicGate#UNSET}. */
        public static LogicEditPayload settings(BlockPos pos, LogicGate.Mode mode, int x, int y) {
            return new LogicEditPayload(pos, Verb.SET, mode.ordinal(), x, y);
        }

        /** The screen's refresh: no settings, no write, one answer. */
        public static LogicEditPayload probe(BlockPos pos) {
            return new LogicEditPayload(pos, Verb.PROBE, 0, LogicGate.UNSET, LogicGate.UNSET);
        }

        private static void encode(RegistryFriendlyByteBuf buffer, LogicEditPayload payload) {
            buffer.writeBlockPos(payload.pos());
            buffer.writeVarInt(payload.verb().ordinal());
            buffer.writeByte(payload.mode());
            buffer.writeByte(payload.x());
            buffer.writeByte(payload.y());
        }

        private static LogicEditPayload decode(RegistryFriendlyByteBuf buffer) {
            BlockPos pos = buffer.readBlockPos();
            int ordinal = buffer.readVarInt();
            Verb[] verbs = Verb.values();
            if (ordinal < 0 || ordinal >= verbs.length) {
                throw new DecoderException("there is no logic integrator verb " + ordinal);
            }
            int mode = buffer.readByte();
            int x = buffer.readByte();
            int y = buffer.readByte();
            if (verbs[ordinal] == Verb.SET && (mode < 0 || mode >= LogicGate.Mode.values().length
                    || !LogicGate.isSettable(x) || !LogicGate.isSettable(y))) {
                throw new DecoderException("a logic integrator cannot be set to " + mode + "/" + x + "/" + y);
            }
            return new LogicEditPayload(pos, verbs[ordinal], mode, x, y);
        }

        // Runs on the server: the settings live in the block's state and the readings live in its neighbours, so both
        // verbs are answered there - and the answer is the screen's next picture either way.
        private static void handle(LogicEditPayload payload, IPayloadContext context) {
            context.enqueueWork(() -> {
                if (context.player() instanceof ServerPlayer player) {
                    LogicIntegratorBlock.applyEdit(player, payload);
                }
            });
        }
    }

    /**
     * One device's settings and what it is doing right now, for the screen that is showing it - the same shape as
     * {@link LogicViewPayload} for the same reason: the screen decides nothing, so every number on it came from the
     * server, which sends a fresh one after every edit and refresh.
     *
     * <p>{@code kind} names which device the settings belong to ({@link DeviceKind}), in the world or stored inside a
     * host block. {@code a}-{@code d} are its settings in its own order; {@code power} is what it answers now and
     * {@code reading} what it reads at 正面.
     */
    public record DeviceViewPayload(BlockPos pos, boolean open, int kind, int a, int b, int c, int d, int power,
                                    int reading) implements CustomPacketPayload {

        public static final CustomPacketPayload.Type<DeviceViewPayload> TYPE =
                new CustomPacketPayload.Type<>(RedstoneCircuit.id("device_view"));

        public static final StreamCodec<RegistryFriendlyByteBuf, DeviceViewPayload> STREAM_CODEC =
                StreamCodec.of(DeviceViewPayload::encode, DeviceViewPayload::decode);

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        private static void encode(RegistryFriendlyByteBuf buffer, DeviceViewPayload payload) {
            buffer.writeBlockPos(payload.pos());
            buffer.writeBoolean(payload.open());
            buffer.writeByte(payload.kind());
            buffer.writeVarInt(payload.a());
            buffer.writeVarInt(payload.b());
            buffer.writeVarInt(payload.c());
            buffer.writeVarInt(payload.d());
            buffer.writeByte(payload.power());
            // A varint, not a byte: 时钟源's countdown runs past a byte (see DeviceKind#MAX_READING).
            buffer.writeVarInt(payload.reading());
        }

        private static DeviceViewPayload decode(RegistryFriendlyByteBuf buffer) {
            BlockPos pos = buffer.readBlockPos();
            boolean open = buffer.readBoolean();
            int kind = buffer.readByte();
            int a = buffer.readVarInt();
            int b = buffer.readVarInt();
            int c = buffer.readVarInt();
            int d = buffer.readVarInt();
            int power = buffer.readByte();
            int reading = buffer.readVarInt();
            // Bounded here as well as on the way out: the widest setting a device holds
            // (DeviceKind#MAX_SETTING) and the widest reading any device reports (DeviceKind#MAX_READING).
            if (DeviceKind.byOrdinal(kind) == null
                    || a < 0 || a > DeviceKind.MAX_SETTING
                    || b < 0 || b > DeviceKind.MAX_SETTING
                    || c < 0 || c > DeviceKind.MAX_SETTING
                    || d < 0 || d > DeviceKind.MAX_SETTING
                    || power < 0 || power > DeviceKind.MAX_SETTING
                    || reading < 0 || reading > DeviceKind.MAX_READING) {
                throw new DecoderException("there is no device reading " + kind + "/" + a + "/" + b + "/" + c + "/" + d
                        + "/" + power + "/" + reading);
            }
            return new DeviceViewPayload(pos, open, kind, a, b, c, d, power, reading);
        }

        /** Client only: open the device's screen, or hand the numbers to the screen already showing this device. */
        private static void handle(DeviceViewPayload payload, IPayloadContext context) {
            context.enqueueWork(() -> DeviceScreens.show(payload));
        }
    }

    /**
     * One thing the player did on a device's screen: set the settings, or ask again - {@link LogicEditPayload}'s two
     * verbs, for the same reason: both end in the same answer, which is what the device now sees. {@code SET} is checked
     * again on arrival ({@code RcPlateBlock#applyEdit}); {@code PROBE} carries nothing and refreshes.
     */
    public record DeviceEditPayload(BlockPos pos, Verb verb, int kind, int a, int b, int c, int d)
            implements CustomPacketPayload {

        /** The two questions a device screen can ask. An ordinal on the wire, checked at the decoder. */
        public enum Verb {
            /** The settings: what the player chose or typed. */
            SET,
            /** Change nothing; answer with what the device sees right now. */
            PROBE
        }

        public static final CustomPacketPayload.Type<DeviceEditPayload> TYPE =
                new CustomPacketPayload.Type<>(RedstoneCircuit.id("device_edit"));

        public static final StreamCodec<RegistryFriendlyByteBuf, DeviceEditPayload> STREAM_CODEC =
                StreamCodec.of(DeviceEditPayload::encode, DeviceEditPayload::decode);

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        /** The player's edit: the device's four settings, in the device's own order. */
        public static DeviceEditPayload settings(BlockPos pos, DeviceKind kind, int a, int b, int c, int d) {
            return new DeviceEditPayload(pos, Verb.SET, kind.ordinal(), a, b, c, d);
        }

        /** The screen's refresh: no settings, no write, one answer. */
        public static DeviceEditPayload probe(BlockPos pos, DeviceKind kind) {
            return new DeviceEditPayload(pos, Verb.PROBE, kind.ordinal(), 0, 0, 0, 0);
        }

        private static void encode(RegistryFriendlyByteBuf buffer, DeviceEditPayload payload) {
            buffer.writeBlockPos(payload.pos());
            buffer.writeVarInt(payload.verb().ordinal());
            buffer.writeByte(payload.kind());
            buffer.writeVarInt(payload.a());
            buffer.writeVarInt(payload.b());
            buffer.writeVarInt(payload.c());
            buffer.writeVarInt(payload.d());
        }

        private static DeviceEditPayload decode(RegistryFriendlyByteBuf buffer) {
            BlockPos pos = buffer.readBlockPos();
            int ordinal = buffer.readVarInt();
            Verb[] verbs = Verb.values();
            if (ordinal < 0 || ordinal >= verbs.length) {
                throw new DecoderException("there is no device verb " + ordinal);
            }
            int kind = buffer.readByte();
            int a = buffer.readVarInt();
            int b = buffer.readVarInt();
            int c = buffer.readVarInt();
            int d = buffer.readVarInt();
            if (DeviceKind.byOrdinal(kind) == null
                    || a < 0 || a > DeviceKind.MAX_SETTING
                    || b < 0 || b > DeviceKind.MAX_SETTING
                    || c < 0 || c > DeviceKind.MAX_SETTING
                    || d < 0 || d > DeviceKind.MAX_SETTING) {
                throw new DecoderException("a device cannot be set to " + kind + "/" + a + "/" + b + "/" + c + "/" + d);
            }
            return new DeviceEditPayload(pos, verbs[ordinal], kind, a, b, c, d);
        }

        // Runs on the server: the settings live in the block's state and the reading in its neighbours, so both verbs
        // are answered there; a plate standing in the world is a plate, whatever kind the message called it.
        private static void handle(DeviceEditPayload payload, IPayloadContext context) {
            context.enqueueWork(() -> {
                if (context.player() instanceof ServerPlayer player
                        && player.level() instanceof ServerLevel level) {
                    BlockState state = level.getBlockState(payload.pos());
                    if (state.getBlock() instanceof RcPlateBlock plate) {
                        plate.applyEdit(player, payload);
                        return;
                    }
                    // A device stored <b>inside</b> a host block is addressed at the host's own coordinate and answered
                    // from the store it lives in; the message's kind says which device, so every host screen needs an arm.
                    switch (DeviceKind.byOrdinal(payload.kind())) {
                        case CONTROLLER -> ControllerBlock.applyHostEdit(player, payload);
                        case TRANSFORMER -> TransformerBlock.applyHostEdit(player, payload);
                        case CLOCK -> ClockBlock.applyHostEdit(player, payload);
                        case EXTENDER -> ExtenderBlock.applyHostEdit(player, payload);
                        case null, default -> {
                            // A kind this build does not have was already refused by the decoder; an unrouted kind
                            // refuses too rather than being answered by a stranger.
                        }
                    }
                }
            });
        }
    }

    /**
     * The region a client exported a blueprint from, so the server can put that region's inner redstone into the file
     * the upload produces.
     *
     * <p>The client cannot write it itself - a client holds no redstone store - and Create's upload carries only a file
     * name and its bytes, so the region has to be said separately. It is said before the upload starts, which is why
     * the server can treat a missing announcement as "no components" instead of waiting for one.
     *
     * <p>The <b>dimension</b> travels with it rather than being taken from the receiver, because the box is only a set
     * of coordinates until it is placed in a level, and the one the sender exported from is the only one it means.
     * Everything else about acceptability is decided on the server: the bounds in the decoder are the ones that make a
     * selection a selection at all, and the size, distance and permission rules are the handler's.
     */
    public record SchematicSelectionPayload(String schematic, ResourceKey<Level> dimension, BlockPos first,
                                            BlockPos second) implements CustomPacketPayload {

        public static final CustomPacketPayload.Type<SchematicSelectionPayload> TYPE =
                new CustomPacketPayload.Type<>(RedstoneCircuit.id("schematic_selection"));

        public static final StreamCodec<RegistryFriendlyByteBuf, SchematicSelectionPayload> STREAM_CODEC =
                StreamCodec.of(SchematicSelectionPayload::encode, SchematicSelectionPayload::decode);

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        private static void encode(RegistryFriendlyByteBuf buffer, SchematicSelectionPayload payload) {
            buffer.writeUtf(payload.schematic(), BlueprintExport.MAX_NAME_LENGTH);
            // The key, not the level: a receiver resolves it against its own server, which is the only place the
            // region can be read from anyway.
            buffer.writeResourceLocation(payload.dimension().location());
            buffer.writeBlockPos(payload.first());
            buffer.writeBlockPos(payload.second());
        }

        private static SchematicSelectionPayload decode(RegistryFriendlyByteBuf buffer) {
            String schematic = buffer.readUtf(BlueprintExport.MAX_NAME_LENGTH);
            ResourceLocation dimension = buffer.readResourceLocation();
            BlockPos first = buffer.readBlockPos();
            BlockPos second = buffer.readBlockPos();
            // The name is refused here rather than on the server: it is what a file path is built from, and nothing
            // about "which region" is worth carrying when the file it describes is not a file.
            if (!BlueprintExport.isPlainNbtName(schematic)) {
                throw new DecoderException("a blueprint selection cannot name " + schematic);
            }
            return new SchematicSelectionPayload(schematic,
                    ResourceKey.create(Registries.DIMENSION, dimension), first, second);
        }

        // Runs on the server, which is the only side holding the store: the announcement is checked against the
        // player's own level and position here, and a refusal is silence - there is nothing the client would do about
        // it, and the upload itself is Create's.
        private static void handle(SchematicSelectionPayload payload, IPayloadContext context) {
            context.enqueueWork(() -> {
                if (!(context.player() instanceof ServerPlayer player)
                        || !(player.level() instanceof ServerLevel level)) {
                    return;
                }
                if (!level.dimension().equals(payload.dimension())
                        || BlueprintExport.limits(player, payload.first(), payload.second()).isPresent()) {
                    return;
                }
                BlueprintUploadRequests.remember(player.getUUID(), payload.schematic(), level.dimension(),
                        payload.first(), payload.second());
            });
        }
    }
}

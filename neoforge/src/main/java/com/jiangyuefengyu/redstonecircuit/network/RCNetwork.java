package com.jiangyuefengyu.redstonecircuit.network;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import com.jiangyuefengyu.redstonecircuit.RedstoneCircuit;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierId;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierIndex;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierKind;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierPose;
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

import org.joml.Quaterniond;
import org.joml.Vector3d;

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
     * <p>3: the sync also carries the hosts riding moving structures, with their carriers' poses.
     */
    private static final String VERSION = "3";

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
                        CarrierSnapshotPayload::handle);
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

    /**
     * Writes a carrier's identity: which kind of structure it is, and its UUID.
     *
     * <p>Hand-written rather than NBT, because this travels on every pose update and a pose update
     * happens as often as a structure moves. The kind goes as a single byte - it is an enum with two
     * values, and a string would be a length prefix plus a name on the hottest packet in the feature.
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
     * Writes a structure's pose: where it is, which local point it turns about, how much it is scaled,
     * and its rotation as a quaternion.
     *
     * <p>The quaternion is not optional. The P1 diagnostics measured a real contraption at
     * {@code rotation = 9.500deg}, so anything that quantised the orientation to right angles would
     * draw the component in the wrong place on every machine that is even slightly tilted - which,
     * once a structure is on rails or in the air, is all of them.
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
     * Reads a pose back, or {@code null}.
     *
     * <p>Every number is checked for finiteness on the way in. A NaN reaching the pose stack would
     * turn the shell into geometry that is nowhere at all, and "it vanished" is a much worse bug to
     * report than "the packet was refused".
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
     * One carrier's pose, and the hosts it is carrying.
     *
     * <h2>Why pose and hosts travel together</h2>
     * They are the same fact seen twice - "this structure is here, and these components are inside
     * it" - and the renderer needs both to draw anything at all. Sending them as one message means a
     * client can never be left holding a host whose carrier it has never heard of, which would show up
     * as a component frozen at the position its block used to occupy.
     *
     * <p>It is also the message that carries the removals. A carrier whose pose is {@code null} is
     * gone: the client drops the pose and everything it was told rides it, in one step. A carrier
     * whose pose is still sent but which has fewer hosts is how a single block coming home travels -
     * the entries absent from {@code hosts} are no longer riding it.
     *
     * <p>The list only ever holds hosts that are still riding. A host that has been put down is
     * covered by the ordinary world sync instead, because that is where it is again.
     *
     * <h2>{@code removed}: the one thing "not in the list" cannot say</h2>
     * A host leaves its carrier's list for two genuinely different reasons - it was <b>put down</b>
     * (the block is back in the world, and the client must keep drawing the component at the landing
     * position even where no block stands, see {@code LandedHosts}) or it was <b>destroyed</b> (the
     * component dropped as an item, and nothing may be drawn anywhere). Absence from {@code hosts} is
     * the same shape for both, and a client that guessed "put down" drew a ghost: the outline and the
     * component stayed on screen for the whole flight, until the structure was put down. That was the
     * author's report, and it is not a drawing bug - the client was never told which of the two had
     * happened.
     *
     * <p>So the server says it. {@code removed} lists the local coordinates it has <b>discarded</b>,
     * and the client drops those hosts outright - no pose and no landed record left behind. The list is
     * empty for every ordinary message, and a client that receives one has nothing to delete.
     *
     * <p>Deliberately a list of local coordinates and not a rule about the {@code hosts} list: the host
     * list is authoritative about what is <em>riding</em>, and "absent from it" must keep meaning "put
     * down" or a landing where no block stands would be invisible again.
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
                // Each inner payload is written by CarrierPayload.encode, dimension and all, so the
                // whole thing is decoded by the one decoder rather than by a second copy of it that
                // could fall out of step. The outer dimension is simply overridden afterwards.
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

package com.jiangyuefengyu.redstonecircuit.network;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

import com.jiangyuefengyu.redstonecircuit.RCConfig;
import com.jiangyuefengyu.redstonecircuit.carrier.CarriedHost;
import com.jiangyuefengyu.redstonecircuit.carrier.CarriedHostIndex;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierId;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierIndex;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierPose;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierStore;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierTrace;
import com.jiangyuefengyu.redstonecircuit.data.HostEntry;
import com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneNode;
import com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneStore;
import com.jiangyuefengyu.redstonecircuit.data.Slot;

import net.fabricmc.fabric.api.entity.event.v1.ServerEntityWorldChangeEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Server half of the host sync: keeps every client's copy of "which blocks hold redstone, and what is
 * inside them" current.
 *
 * <p>Two kinds of fact travel: what is standing in the world, and what is riding a moving structure. The
 * first needs traffic at three moments - a player arrives (login, dimension change, respawn) and therefore
 * has no data, and a component appears, changes or disappears. Nothing else is sent, because the client's
 * copy is a pure function of the store and there is no state to reconcile. The second is sent whenever a
 * structure or its contents change, and once to a player who arrives while something is in flight.
 *
 * <h2>The three arrival hooks, on Fabric</h2>
 * NeoForge had one {@code PlayerEvent} per case. Fabric splits them across two modules, and the
 * dimension change is the one worth reading twice:
 *
 * <ul>
 *   <li>login → {@code ServerPlayConnectionEvents.JOIN}. Not {@code INIT}: at JOIN the player is in the
 *       level and can already be sent play payloads.</li>
 *   <li>respawn → {@code ServerPlayerEvents.AFTER_RESPAWN}, which hands over the new player object -
 *       the one that is in the world, and therefore the one to send to.</li>
 *   <li>dimension change → {@code ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD}, whose
 *       {@code destination} is the level to snapshot. Passing the destination explicitly rather than
 *       reading {@code player.level()} also removes any doubt about which side of the move we are on.
 *       The client's cache is keyed by dimension, so the new level's snapshot simply replaces it.</li>
 * </ul>
 */
public final class HostSync {

    private HostSync() {
    }

    /** Registers the three arrival hooks. Called from the common entrypoint. */
    public static void register() {
        ServerPlayConnectionEvents.JOIN.register(
                (handler, sender, server) -> sendSnapshot(handler.getPlayer()));
        ServerPlayerEvents.AFTER_RESPAWN.register(
                (oldPlayer, newPlayer, alive) -> sendSnapshot(newPlayer));
        ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD.register(
                (player, origin, destination) -> sendSnapshot(player));
    }

    /** Sends the complete set for the player's current dimension, contents included. */
    public static void sendSnapshot(ServerPlayer player) {
        if (!(player.level() instanceof ServerLevel level)) {
            return;
        }
        ServerPlayNetworking.send(player,
                new RCNetwork.HostSnapshotPayload(level.dimension(), snapshotOf(level)));
        // A client that arrives while something is in flight has no way to learn about it otherwise:
        // the host sync above only carries what is standing in the world, and the poses are sent on
        // change, which for a parked machine is never.
        sendCarrierSnapshot(player);
    }

    /** Everything stored in one level, as the wire form. */
    public static List<HostEntry> snapshotOf(ServerLevel level) {
        InnerRedstoneStore store = InnerRedstoneStore.get(level);
        if (store.isEmpty()) {
            return List.of();
        }
        List<HostEntry> entries = new ArrayList<>();
        for (BlockPos pos : store.positions()) {
            InnerRedstoneNode node = store.get(pos);
            if (node != null && !node.isEmpty()) {
                entries.add(new HostEntry(pos.immutable(), node.slot().copy()));
            }
        }
        return List.copyOf(entries);
    }

    /**
     * Tells clients that these positions changed: added, updated, or (with a {@code null} slot) emptied.
     *
     * <p>Delivered to everyone in the dimension by default. The alternative - only players near enough to
     * see it - is tempting, since this fires on every step a wire takes, but a client that missed an
     * update keeps drawing the old state until it happens to be re-sent, and nothing re-sends it.
     * {@code slotSyncRange} exists for servers that would rather have the bandwidth: set it to a positive
     * block radius and updates outside it are dropped for that player, at the cost of a stale drawing
     * until the next login or dimension change re-snapshots.
     */
    public static void broadcast(ServerLevel level, List<HostEntry> entries) {
        if (entries.isEmpty()) {
            return;
        }
        double range = RCConfig.slotSyncRange();
        RCNetwork.HostSlotPayload payload = new RCNetwork.HostSlotPayload(
                level.dimension(), copyOf(entries));
        for (ServerPlayer player : level.players()) {
            if (range > 0 && !withinRange(player, entries, range)) {
                continue;
            }
            ServerPlayNetworking.send(player, payload);
        }
        if (RCConfig.debugLog() && RCConfig.traceSlotSync()) {
            RCConfig.LOGGER.info("[redstonecircuit] sync {} position(s): {}",
                    entries.size(), describe(entries));
        }
    }

    /** Tells clients about one position: contents, or {@code null} to say it is now empty. */
    public static void broadcast(ServerLevel level, BlockPos pos, @Nullable Slot slot) {
        broadcast(level, List.of(new HostEntry(pos.immutable(), slot == null ? null : slot.copy())));
    }

    /**
     * Tells clients that a position no longer holds anything: a host that has <em>moved</em> rather than one
     * that was emptied. Not ordered here - the caller must announce the new position first.
     */
    public static void broadcastRemoval(ServerLevel level, BlockPos pos) {
        broadcast(level, pos, null);
    }

    /**
     * Makes the world-side entry at a position a carried host is only <em>drawn</em> at say "nothing here",
     * unless the world keeps a host of its own there: a carried host lives at the structure's coordinates.
     */
    public static void publishWorldEntry(ServerLevel level, BlockPos worldPos) {
        if (InnerRedstoneStore.get(level).slotAt(worldPos) != null) {
            return;
        }
        broadcastRemoval(level, worldPos);
    }

    // ------------------------------------------------------------- carriers --

    /**
     * Tells clients everything they need to know about one moving structure; a {@code null} pose means it is
     * gone. Pose and hosts travel together because one without the other draws a component that is not there.
     */
    public static void broadcastCarrier(ServerLevel level, CarrierId carrier,
                                        @Nullable CarriedHostIndex index,
                                        @Nullable CarrierPose pose) {
        broadcastCarrier(level, carrier, index, pose, List.of());
    }

    /**
     * The same, with the hosts the server has <b>discarded</b> named explicitly - the one thing absence from
     * the host list cannot say, since a host leaves it either by being put down or by being destroyed.
     */
    public static void broadcastCarrier(ServerLevel level, CarrierId carrier,
                                        @Nullable CarriedHostIndex index,
                                        @Nullable CarrierPose pose, List<BlockPos> removed) {
        CarriedHostIndex resolved = index == null ? CarrierStore.indexOf(level) : index;
        sendToDimension(level, carrierPayload(level, carrier, resolved, pose, removed));
        // Counted here rather than at the call sites, so every route that puts a carrier on the wire is
        // counted exactly once.
        int liveHosts = resolved == null ? 0 : resolved.liveHostsOf(carrier).size();
        CarrierTrace.noteCarrierSent(level.dimension(), liveHosts);
        if (RCConfig.debugLog()) {
            RCConfig.LOGGER.info("[redstonecircuit] sent carrier {} pose={} carriedHosts={} removed={} {}",
                    carrier.shortId(), pose == null ? "none" : pose.describe(), liveHosts,
                    removed == null ? 0 : removed.size(),
                    resolved == null ? "hosts=?" : resolved.summary(carrier));
        }
    }

    /** Tells clients only where a structure is; used while it moves. */
    public static void broadcastPose(ServerLevel level, CarrierId carrier,
                                     @Nullable CarrierPose pose) {
        broadcastPose(level, carrier, pose, true);
    }

    /**
     * The same, with the three-stage counter made optional: a heartbeat pose - re-sent because the structure
     * is <em>still there</em> - must not be counted as a pose sent while it moved.
     */
    public static void broadcastPose(ServerLevel level, CarrierId carrier,
                                     @Nullable CarrierPose pose, boolean countAsChange) {
        sendToDimension(level, carrierPayload(level, carrier, CarrierStore.indexOf(level), pose));
        if (countAsChange) {
            CarrierTrace.notePoseSent(level.dimension());
        }
    }

    /** Everything in flight in one dimension, for a client that has just arrived. */
    public static RCNetwork.CarrierSnapshotPayload carrierSnapshotOf(ServerLevel level) {
        List<RCNetwork.CarrierPayload> carriers = new ArrayList<>();
        CarriedHostIndex index = CarrierStore.indexOf(level);
        if (index != null) {
            for (CarrierId carrier : index.carriers()) {
                carriers.add(carrierPayload(level, carrier, index,
                        CarrierStore.poseOf(level, carrier)));
            }
        }
        return new RCNetwork.CarrierSnapshotPayload(level.dimension(), List.copyOf(carriers));
    }

    /** Sends the complete carrier state to one player. */
    public static void sendCarrierSnapshot(ServerPlayer player) {
        if (!(player.level() instanceof ServerLevel level)) {
            return;
        }
        if (!CarrierStore.isActive()) {
            return;
        }
        RCNetwork.CarrierSnapshotPayload payload = carrierSnapshotOf(level);
        if (payload.carriers().isEmpty()) {
            // Nothing is in flight, so there is nothing to correct: a fresh client's carrier view is empty.
            return;
        }
        ServerPlayNetworking.send(player, payload);
        // The ledger that decides whether a removal is owed is advanced only after the packet went out.
        List<CarrierId> named = new ArrayList<>(payload.carriers().size());
        for (RCNetwork.CarrierPayload carrier : payload.carriers()) {
            named.add(carrier.carrier());
        }
        CarrierStore.noteSnapshotSent(level, named);
        CarrierTrace.noteSnapshotSent(level.dimension(), payload.carriers().size());
    }

    /**
     * Builds the message for one carrier: its pose, and every host that is still riding it. Hosts that have
     * been put down are left out - they are back in the world, which the ordinary host sync covers.
     */
    private static RCNetwork.CarrierPayload carrierPayload(ServerLevel level, CarrierId carrier,
                                                           @Nullable CarriedHostIndex index,
                                                           @Nullable CarrierPose pose) {
        return carrierPayload(level, carrier, index, pose, List.of());
    }

    /**
     * The same message, with the discarded host coordinates attached. The list is copied and immutable so a
     * payload handed to the network cannot change under it.
     */
    private static RCNetwork.CarrierPayload carrierPayload(ServerLevel level, CarrierId carrier,
                                                           @Nullable CarriedHostIndex index,
                                                           @Nullable CarrierPose pose,
                                                           List<BlockPos> removed) {
        List<CarrierIndex.CarriedView> views = new ArrayList<>();
        if (index != null) {
            for (CarriedHost host : index.liveHostsOf(carrier)) {
                // An empty host is a state, not a crash: its slot is null, so the view is left out and the
                // client is told the host carries no component.
                Slot slot = host.slot();
                if (slot == null) {
                    continue;
                }
                views.add(new CarrierIndex.CarriedView(carrier, host.localPos().immutable(),
                        host.origin().immutable(), host.at().immutable(), slot.copy()));
            }
        }
        return new RCNetwork.CarrierPayload(level.dimension(), carrier, pose, List.copyOf(views),
                removed == null ? List.of() : List.copyOf(removed));
    }

    /** How many carriers and hosts a client would be told about; used by {@code /rc physics}. */
    public static Map<CarrierId, CarrierPose> carrierPosesOf(ServerLevel level) {
        Map<CarrierId, CarrierPose> poses = new HashMap<>();
        for (CarrierId carrier : CarrierStore.carriersOf(level)) {
            CarrierPose pose = CarrierStore.poseOf(level, carrier);
            if (pose != null) {
                poses.put(carrier, pose);
            }
        }
        return poses;
    }

    /** Every carried host of a level, in the shape the client would be sent. */
    public static List<CarrierIndex.CarriedView> carriedHostsOf(ServerLevel level) {
        List<CarrierIndex.CarriedView> views = new ArrayList<>();
        CarriedHostIndex index = CarrierStore.indexOf(level);
        if (index == null) {
            return views;
        }
        for (CarrierId carrier : index.carriers()) {
            for (CarriedHost host : index.liveHostsOf(carrier)) {
                views.add(new CarrierIndex.CarriedView(carrier, host.localPos(), host.origin(),
                        host.at(), host.slot()));
            }
        }
        return views;
    }

    /**
     * One message to every player in a dimension.
     *
     * <p>Written out rather than reaching for a broadcast helper, because the payload has to be the
     * <em>same object</em> for every player: it is built once and sent N times, which is what keeps a
     * machine with twenty carriers from building twenty copies of every message.
     */
    private static void sendToDimension(ServerLevel level, RCNetwork.CarrierPayload payload) {
        for (ServerPlayer player : level.players()) {
            ServerPlayNetworking.send(player, payload);
        }
    }

    private static boolean withinRange(ServerPlayer player, List<HostEntry> entries, double range) {
        BlockPos playerPos = player.blockPosition();
        for (HostEntry entry : entries) {
            if (playerPos.closerThan(entry.pos(), range)) {
                return true;
            }
        }
        return false;
    }

    private static List<HostEntry> copyOf(List<HostEntry> entries) {
        List<HostEntry> copy = new ArrayList<>(entries.size());
        for (HostEntry entry : entries) {
            // Copied, not referenced: the solver keeps mutating its slots after the packet is handed
            // over, and a payload that changed under the network thread would send nonsense.
            copy.add(new HostEntry(entry.pos().immutable(),
                    entry.slot() == null ? null : entry.slot().copy()));
        }
        return copy;
    }

    private static String describe(List<HostEntry> entries) {
        if (entries.size() == 1) {
            HostEntry only = entries.get(0);
            return only.pos().toShortString() + " -> "
                    + (only.slot() == null ? "removed" : only.slot().describe());
        }
        return entries.size() + " entries";
    }
}

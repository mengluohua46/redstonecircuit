package com.jiangyuefengyu.redstonecircuit.network;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import com.jiangyuefengyu.redstonecircuit.RCConfig;
import com.jiangyuefengyu.redstonecircuit.RedstoneCircuit;
import com.jiangyuefengyu.redstonecircuit.carrier.CarriedHost;
import com.jiangyuefengyu.redstonecircuit.carrier.CarriedHostIndex;
import com.jiangyuefengyu.redstonecircuit.carrier.CarriedHosts;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierId;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierIndex;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierPose;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierStore;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierTrace;
import com.jiangyuefengyu.redstonecircuit.data.HostEntry;
import com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneNode;
import com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneStore;
import com.jiangyuefengyu.redstonecircuit.data.Slot;

/**
 * Server half of the host sync: keeps every client's copy of "which blocks hold redstone, and what is
 * inside them" current.
 *
 * <p>Three moments need traffic: a player arrives (login, dimension change, respawn) and therefore has
 * no data, and a component appears, changes or disappears. Nothing else is sent - the client's copy is
 * a pure function of the store, so there is no state to reconcile.
 */
@EventBusSubscriber(modid = RedstoneCircuit.MODID)
public final class HostSync {

    private HostSync() {
    }

    /** Sends the complete set for the player's current dimension, contents included. */
    public static void sendSnapshot(ServerPlayer player) {
        if (!(player.level() instanceof ServerLevel level)) {
            return;
        }
        PacketDistributor.sendToPlayer(player,
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
     * Tells clients that these positions changed: added, updated, or (with a {@code null} slot)
     * emptied.
     *
     * <p>A list, because one solve usually rewrites a whole wire at once; sending one packet per
     * position would be the difference between a dozen bytes and a dozen packets.
     *
     * <p>Delivered to everyone in the dimension by default. The alternative - only players near enough
     * to see it - is tempting, since this fires on every step a wire takes, but a client that missed
     * an update keeps drawing the old state until it happens to be re-sent, and nothing re-sends it.
     * {@code slotSyncRange} exists for servers that would rather have the bandwidth: set it to a
     * positive block radius and updates outside it are dropped for that player, at the cost of a stale
     * drawing until the next login or dimension change re-snapshots.
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
            PacketDistributor.sendToPlayer(player, payload);
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
     * Tells clients that a position no longer holds anything.
     *
     * <p>A separate name from {@code broadcast(level, pos, null)} only for readability at the call
     * sites that use it for a different reason. There are two of them, and both are about a host that
     * has <em>moved</em> rather than one that has been emptied:
     *
     * <ul>
     *   <li>the position a structure picked a host up from - "stop drawing the component here, it is
     *       inside the machine now". See {@code CarrierStore#suppressWorldHost};</li>
     *   <li>the position a structure put a host down after drifting away from it - "stop drawing it
     *       here, it came home somewhere else". That one is sent <b>after</b> the announcement of the
     *       position it came home to, because a client that heard only the removal would have no world
     *       host to draw at all.</li>
     * </ul>
     *
     * <p>Deliberately not ordered by this method: the caller is the only one that knows which of the
     * two it is doing, and the order that matters (announce the new position first) belongs at the call
     * site that knows where the new position is.
     */
    public static void broadcastRemoval(ServerLevel level, BlockPos pos) {
        broadcast(level, pos, null);
    }

    // ------------------------------------------------------------- carriers --

    /**
     * Tells clients everything they need to know about one moving structure.
     *
     * <p>One message rather than two on purpose: the pose and the hosts are the same fact seen twice,
     * and a client that received one without the other would draw a component at a position that does
     * not exist. A {@code null} pose means the structure is gone.
     */
    public static void broadcastCarrier(ServerLevel level, CarrierId carrier,
                                        @Nullable CarriedHostIndex index,
                                        @Nullable CarrierPose pose) {
        broadcastCarrier(level, carrier, index, pose, List.of());
    }

    /**
     * The same, with the hosts the server has <b>discarded</b> named explicitly.
     *
     * <p>The one thing absence from the host list cannot say. A host leaves the list either because it
     * was put down (the client must keep drawing the component where it landed, even over air) or
     * because its block was destroyed (nothing may be drawn anywhere, ever again). Without the second
     * list the client had to guess, and it guessed "put down" - which is the ghost the author reported:
     * the outline and the component stayed on screen for the whole flight.
     *
     * <p>Sent by the two removal paths and by nothing else, so the ordinary message carries an empty
     * list and costs two bytes.
     */
    public static void broadcastCarrier(ServerLevel level, CarrierId carrier,
                                        @Nullable CarriedHostIndex index,
                                        @Nullable CarrierPose pose, List<BlockPos> removed) {
        CarriedHostIndex resolved = index == null ? CarrierStore.indexOf(level) : index;
        PacketDistributor.sendToPlayersInDimension(level,
                carrierPayload(level, carrier, resolved, pose, removed));
        // STAGE 2 of the three-stage diagnostic, counted here rather than at the call sites so every
        // route that puts a carrier on the wire is counted exactly once.
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
     * The same, with the three-stage counter made optional.
     *
     * <p>A heartbeat pose - one re-sent because the structure is <em>still there</em> rather than
     * because it moved - must not be counted as a pose that "was sent while the structure moved": the
     * {@code sync: posesSent=N} line is how a reader tells "the machine is moving and the client is
     * being kept up to date" from "the machine is parked". The message itself is identical, which is
     * exactly why the flag is here rather than a second payload.
     */
    public static void broadcastPose(ServerLevel level, CarrierId carrier,
                                     @Nullable CarrierPose pose, boolean countAsChange) {
        PacketDistributor.sendToPlayersInDimension(level,
                carrierPayload(level, carrier, CarrierStore.indexOf(level), pose));
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
            // Nothing is in flight, so there is nothing to correct: a fresh client's carrier view is
            // empty already, and an empty packet would only be noise.
            return;
        }
        PacketDistributor.sendToPlayer(player, payload);
        // The carriers this snapshot just named may have to be taken back later, so the ledger that
        // decides whether a removal is owed is advanced only here - after the packet actually went out,
        // never while building it.
        List<CarrierId> named = new ArrayList<>(payload.carriers().size());
        for (RCNetwork.CarrierPayload carrier : payload.carriers()) {
            named.add(carrier.carrier());
        }
        CarrierStore.noteSnapshotSent(level, named);
        CarrierTrace.noteSnapshotSent(level.dimension(), payload.carriers().size());
    }

    /**
     * Builds the message for one carrier: its pose, and every host that is still riding it.
     *
     * <p>Hosts that have been put down are deliberately left out. They are back in the world, the
     * ordinary host sync covers them, and re-listing them here would fight it - the client would be
     * told the same component rides a structure and sits in a block.
     */
    private static RCNetwork.CarrierPayload carrierPayload(ServerLevel level, CarrierId carrier,
                                                           @Nullable CarriedHostIndex index,
                                                           @Nullable CarrierPose pose) {
        return carrierPayload(level, carrier, index, pose, List.of());
    }

    /**
     * The same message, with the discarded host coordinates attached.
     *
     * <p>The list is copied and immutable so a payload handed to the network cannot change under it,
     * exactly like the host views above.
     */
    private static RCNetwork.CarrierPayload carrierPayload(ServerLevel level, CarrierId carrier,
                                                           @Nullable CarriedHostIndex index,
                                                           @Nullable CarrierPose pose,
                                                           List<BlockPos> removed) {
        List<CarrierIndex.CarriedView> views = new ArrayList<>();
        if (index != null) {
            for (CarriedHost host : index.liveHostsOf(carrier)) {
                // AN EMPTY HOST IS A STATE, NOT A CRASH. A host a structure is carrying can hold nothing
                // - the player took the component back out (see CarriedHostIndex#removeSlot) - and
                // `slot()` is then null. It used to be dereferenced here, and this method is reached from
                // the pose heartbeat, so one emptied host on one moving structure killed the server tick
                // (the author's P1.23 crash: `Slot.copy()` on a null return out of
                // CarrierStore#broadcastPose). The client is told the same thing it is told for any other
                // position that holds nothing: the view is left out, and the carrier message says the host
                // no longer carries a component.
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

    // -------------------------------------------------------------- events --

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            sendSnapshot(player);
        }
    }

    @SubscribeEvent
    public static void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            sendSnapshot(player);
        }
    }

    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            sendSnapshot(player);
        }
    }
}

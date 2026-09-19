package com.jiangyuefengyu.redstonecircuit.network;

import java.util.ArrayList;
import java.util.List;

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

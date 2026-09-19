package com.jiangyuefengyu.redstonecircuit.network;

import java.util.ArrayList;
import java.util.List;

import com.jiangyuefengyu.redstonecircuit.RedstoneCircuit;
import com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneNode;
import com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneStore;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Server half of the host sync: keeps every client's copy of "which blocks hold redstone" current.
 *
 * <p>Three moments need traffic: a player arrives (login, dimension change, respawn) and therefore
 * has no data, and a block gains or loses a component and therefore changes appearance. Nothing else
 * is sent - the set is a pure function of the store, so there is no state to reconcile.
 */
@EventBusSubscriber(modid = RedstoneCircuit.MODID)
public final class HostSync {

    private HostSync() {
    }

    /** Sends the complete set for the player's current dimension. */
    public static void sendSnapshot(ServerPlayer player) {
        if (!(player.level() instanceof ServerLevel level)) {
            return;
        }
        InnerRedstoneStore store = InnerRedstoneStore.get(level);
        List<BlockPos> positions;
        if (store.isEmpty()) {
            positions = List.of();
        } else {
            positions = new ArrayList<>();
            for (BlockPos pos : store.positions()) {
                InnerRedstoneNode node = store.get(pos);
                if (node != null && !node.isEmpty()) {
                    positions.add(pos);
                }
            }
        }
        PacketDistributor.sendToPlayer(player,
                new RCNetwork.HostSnapshotPayload(level.dimension(), List.copyOf(positions)));
    }

    /** Tells everyone in the dimension that one block gained or lost a component. */
    public static void broadcastChange(ServerLevel level, BlockPos pos, boolean present) {
        PacketDistributor.sendToPlayersInDimension(level,
                new RCNetwork.HostChangePayload(level.dimension(), pos.immutable(), present));
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

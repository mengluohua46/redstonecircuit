package com.jiangyuefengyu.redstonecircuit.logic;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import com.jiangyuefengyu.redstonecircuit.RedstoneCircuit;
import com.jiangyuefengyu.redstonecircuit.network.RCNetwork;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The block each player's wrench has picked as "A" (R7).
 *
 * <h2>Why the server remembers it</h2>
 * The second half of the gesture is a shift-right-click on a <em>different</em> block, so something has
 * to remember the first one between two clicks. It cannot be the client: the client cannot be trusted
 * with it (a modified client would link two blocks on the far side of the world), and the server is
 * the only side that knows whether the first block still holds a component when the second click
 * arrives.
 *
 * <h2>Why it is not saved</h2>
 * A selection is a half-finished gesture, not data: it is meaningless after a relog, and keeping it
 * across sessions would mean a stale position could be linked by an unrelated later click. So it lives
 * in memory, is dropped when the player leaves, and is checked against the level before use.
 *
 * <p>The client is sent a copy purely so it can highlight the block - see {@code WrenchClientState}.
 */
@EventBusSubscriber(modid = RedstoneCircuit.MODID)
public final class WrenchSelection {

    /** Per player: the block picked, and the dimension it was picked in. */
    private static final Map<UUID, Selection> SELECTIONS = new HashMap<>();

    private record Selection(ResourceKey<Level> dimension, BlockPos pos) {
    }

    private WrenchSelection() {
    }

    /** The block this player picked, or {@code null} if none, or if it was picked in another level. */
    @Nullable
    public static BlockPos get(ServerPlayer player) {
        Selection selection = SELECTIONS.get(player.getUUID());
        if (selection == null || !selection.dimension().equals(player.level().dimension())) {
            return null;
        }
        return selection.pos();
    }

    /** Picks a block, replacing any earlier pick. */
    public static void select(ServerPlayer player, BlockPos pos) {
        SELECTIONS.put(player.getUUID(), new Selection(player.level().dimension(), pos.immutable()));
        send(player, pos);
    }

    /** Forgets the pick. */
    public static void clear(ServerPlayer player) {
        if (SELECTIONS.remove(player.getUUID()) != null) {
            send(player, null);
        }
    }

    /** Forgets the pick if it is the block given - used when that block is emptied or broken. */
    public static void clearIf(ServerLevel level, BlockPos pos) {
        for (ServerPlayer player : level.players()) {
            BlockPos selected = get(player);
            if (pos.equals(selected)) {
                clear(player);
            }
        }
    }

    private static void send(ServerPlayer player, @Nullable BlockPos pos) {
        PacketDistributor.sendToPlayer(player, pos == null
                ? RCNetwork.WrenchSelectionPayload.none()
                : new RCNetwork.WrenchSelectionPayload(true, pos));
    }

    /** Forgets a player's pick when they leave, so a UUID cannot pile up selections over time. */
    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            SELECTIONS.remove(player.getUUID());
        }
    }
}

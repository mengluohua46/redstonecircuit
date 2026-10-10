package com.jiangyuefengyu.redstonecircuit.carrier;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import org.jetbrains.annotations.Nullable;

/**
 * Which host a player's wrench has picked, when that host is riding a structure.
 *
 * <p>{@code WrenchSelection} remembers a <b>world</b> position, which is right for a host on the ground and
 * wrong for a carried one: the component, and therefore "which two hosts are next to each other", lives at
 * the structure's own coordinate. So both are kept - {@code localPos} is what {@code CarriedTool} compares
 * when the second click arrives, and {@code worldPos} goes to the client so the highlight is drawn where the
 * block actually is.
 *
 * <p>Reached only from {@code CarrierInteraction}, and not saved: a stale position must not be usable by a
 * later unrelated click.
 */
public final class CarriedSelection {

    /** The set of selections, keyed by player. */
    private static final Map<UUID, Selection> SELECTIONS = new HashMap<>();

    /**
     * One player's pick in both frames: {@code localPos} decides adjacency, {@code worldPos} is what the client
     * highlights, and the dimension keeps a pick from surviving a dimension change.
     */
    public record Selection(ResourceKey<Level> dimension, CarrierId carrier, BlockPos localPos,
                            BlockPos worldPos) {
    }

    private CarriedSelection() {
    }

    /** Picks a host, replacing any earlier pick. */
    public static void select(ServerPlayer player, CarrierId carrier, BlockPos localPos,
                              BlockPos worldPos) {
        select(player.getUUID(), player.level().dimension(), carrier, localPos, worldPos);
    }

    /**
     * The same pick, keyed by a player and a level. Free of {@code ServerPlayer} so the per-player rule - a
     * pick survives across ticks until that player picks something else - is provable without a game.
     */
    public static void select(UUID playerId, ResourceKey<Level> dimension, CarrierId carrier,
                              BlockPos localPos, BlockPos worldPos) {
        if (playerId == null || dimension == null || carrier == null || localPos == null
                || worldPos == null) {
            // An incomplete pick is refused rather than stored, so a half-built record cannot be read back.
            return;
        }
        SELECTIONS.put(playerId, new Selection(dimension, carrier, localPos.immutable(),
                worldPos.immutable()));
    }

    /** This player's pick, or {@code null} when there is none or it was made in another level. */
    @Nullable
    public static Selection get(ServerPlayer player) {
        if (player == null) {
            return null;
        }
        return get(player.getUUID(), player.level().dimension());
    }

    /**
     * This player's pick in this level, or {@code null}: the level is part of the key, so a pick made before a
     * dimension change cannot be used after it. The player is a {@code UUID} for the reason {@link #select} is.
     */
    @Nullable
    public static Selection get(@Nullable UUID playerId, @Nullable ResourceKey<Level> dimension) {
        if (playerId == null || dimension == null) {
            return null;
        }
        Selection selection = SELECTIONS.get(playerId);
        if (selection == null || !selection.dimension().equals(dimension)) {
            return null;
        }
        return selection;
    }

    /** Forgets this player's pick. */
    public static void clear(ServerPlayer player) {
        if (player != null) {
            SELECTIONS.remove(player.getUUID());
        }
    }

    /** Forgets every pick a player may be holding of one carrier's hosts; used when a structure goes. */
    public static void clearCarrier(CarrierId carrier) {
        if (carrier == null) {
            return;
        }
        SELECTIONS.values().removeIf(selection -> carrier.equals(selection.carrier()));
    }

    /** How many players are holding a pick; for the diagnostics. */
    public static int size() {
        return SELECTIONS.size();
    }

    /** Drops everything; called when a level unloads, so a dimension's picks cannot outlive it. */
    public static void clearAll() {
        SELECTIONS.clear();
    }

    /** Forgets one player's pick when they leave, so a UUID cannot pile up selections over time. */
    public static void forget(UUID playerId) {
        if (playerId != null) {
            SELECTIONS.remove(playerId);
        }
    }

    /** A one-line description of one selection, for a log line that has to name both frames. */
    public static String describe(@Nullable Selection selection) {
        if (selection == null) {
            return "-";
        }
        return selection.carrier().shortId() + " local=" + selection.localPos().toShortString()
                + " world=" + selection.worldPos().toShortString();
    }
}

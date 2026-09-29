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
 * <b>P1.20: which host a player's wrench has picked, when that host is riding a structure.</b>
 *
 * <h2>Why the world's selection cannot be reused</h2>
 * {@code WrenchSelection} remembers a <b>world</b> position, and the highlight it sends to the client is
 * drawn at that world position. Both of those are the right thing for a host standing on the ground and
 * the wrong thing for one a structure is carrying: the block the player clicked is drawn at the
 * projected position, but the component - and therefore "which two hosts are next to each other" - lives
 * at the structure's own coordinate. A selection recorded at the world position would answer the second
 * half of the gesture in the wrong frame, and one recorded at the local coordinate would draw the
 * highlight in the wrong place on the client.
 *
 * <p>So both are kept, and each is used for what it is true of:
 *
 * <ul>
 *   <li>{@code localPos} is what {@code CarriedTool} compares when the second click arrives - adjacency
 *       in the structure's own frame, which is where the components are and which survives the machine
 *       turning;</li>
 *   <li>{@code worldPos} is what goes to the client, through the same payload the world's selection
 *       uses, so the highlight is drawn where the block actually is.</li>
 * </ul>
 *
 * <h2>Why it is here rather than in {@code WrenchSelection}</h2>
 * The world's selection class is on the world's path, and P1.18's withdrawal is the reason nothing
 * carrier-shaped may be added to it. This one is reached only from {@code CarrierInteraction}, which
 * exits immediately for any click that is not about a carried host.
 *
 * <p>Not saved, for the same reason the world's is not: a half-finished gesture is meaningless after a
 * relog, and a stale position is exactly what a later unrelated click must not be able to use.
 */
public final class CarriedSelection {

    /** The set of selections, keyed by player. */
    private static final Map<UUID, Selection> SELECTIONS = new HashMap<>();

    /**
     * One player's pick, in both frames.
     *
     * @param dimension the level it was made in, so a pick cannot survive a dimension change
     * @param carrier the structure that is carrying the host
     * @param localPos its coordinate inside that structure - the frame adjacency is decided in
     * @param worldPos where it currently is, which is what the client highlights
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
     * <b>The same pick, keyed by the two things that actually identify it: a player and a level.</b>
     *
     * <p>The production form above reaches this with {@code player.getUUID()} and
     * {@code player.level().dimension()}. It is public, and takes those two values rather than a
     * {@code ServerPlayer}, for one reason: the rule "a pick is per player, and it survives across
     * ticks until the same player picks something else" has to be provable <b>without a game</b>, and a
     * unit test cannot build a {@code ServerPlayer}. Everything the rule turns on is here, so the test
     * drives the real code rather than a copy of it.
     */
    public static void select(UUID playerId, ResourceKey<Level> dimension, CarrierId carrier,
                              BlockPos localPos, BlockPos worldPos) {
        if (playerId == null || dimension == null || carrier == null || localPos == null
                || worldPos == null) {
            // An incomplete pick is not a pick. Refused rather than stored, so a half-built record can
            // never be read back by the second click of a gesture.
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
     * This player's pick in this level, or {@code null}.
     *
     * <p>The level is part of the key on purpose: a half-finished gesture is meaningless in another
     * dimension, and a pick made before a dimension change must not be usable after it. See
     * {@link #select(UUID, ResourceKey, CarrierId, BlockPos, BlockPos)} for why the player is a
     * {@code UUID} here.
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

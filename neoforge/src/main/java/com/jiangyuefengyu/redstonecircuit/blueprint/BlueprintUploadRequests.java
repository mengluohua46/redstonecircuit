package com.jiangyuefengyu.redstonecircuit.blueprint;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * The server's side of "a client is about to upload this file, and this is the region it came from".
 *
 * <p>An upload is the one path where the server is handed bytes it did not produce, so the region cannot be recovered
 * from the request and has to be announced. The announcement arrives before the upload starts and is consumed when the
 * file is complete; anything else - a lost packet, a cancelled upload, a player who left - leaves an entry that expires
 * and changes nothing.
 *
 * <p>One entry per player, replaced by the next announcement: the flow is "pick a file, upload it", so a second box
 * from the same player before the first landed can only be the newer intent. Nothing here decides whether an
 * announcement is acceptable - that is the caller's business, because the caller is the one holding the player.
 */
public final class BlueprintUploadRequests {

    /** How long an announcement stays usable. An upload of a large blueprint is seconds, not minutes. */
    private static final long TTL_MILLIS = 60_000L;

    /** Above this many players with a live announcement, expired ones are swept on the next write. */
    private static final int SWEEP_THRESHOLD = 32;

    /** What was announced, and when it stops being believed. */
    public record Pending(String schematic, ResourceKey<Level> dimension, BlockPos first, BlockPos second,
                          long expiresAt) {
    }

    private static final Map<UUID, Pending> PENDING = new ConcurrentHashMap<>();

    private BlueprintUploadRequests() {
    }

    /** Records one announcement, replacing whatever this player had announced before. */
    public static void remember(UUID player, String schematic, ResourceKey<Level> dimension, BlockPos first,
                                BlockPos second) {
        if (player == null || schematic == null || dimension == null || first == null || second == null) {
            return;
        }
        PENDING.put(player, new Pending(schematic, dimension, first, second,
                System.currentTimeMillis() + TTL_MILLIS));
        if (PENDING.size() > SWEEP_THRESHOLD) {
            sweep();
        }
    }

    /**
     * Takes the announcement for exactly this file, and forgets it whatever it answers - one announcement describes one
     * upload, and keeping it would let a later upload of the same name inherit an older region.
     */
    @Nullable
    public static Pending take(UUID player, String schematic) {
        if (player == null || schematic == null) {
            return null;
        }
        Pending pending = PENDING.remove(player);
        if (pending == null || !pending.schematic().equals(schematic)
                || pending.expiresAt() < System.currentTimeMillis()) {
            return null;
        }
        return pending;
    }

    /** Drops everything that expired. Called only once the map has grown past {@link #SWEEP_THRESHOLD}. */
    private static void sweep() {
        long now = System.currentTimeMillis();
        PENDING.entrySet().removeIf(entry -> entry.getValue().expiresAt() < now);
    }
}

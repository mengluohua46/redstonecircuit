package com.jiangyuefengyu.redstonecircuit.carrier;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Which carriers the client has been told about - and therefore which ones have to be taken back.
 *
 * <p>A carrier can leave the server's index from more than one place: the ordinary put-down, the orphan sweep, a
 * sub-level removal, a world unload, the reaping of finished structures. If one of them fails to put a "this
 * carrier is gone" message on the wire, the client keeps a carrier the server no longer answers a pose for, and
 * the host riding it is drawn nowhere - neither inside the structure (no pose) nor on the world path (still marked
 * carried). Release therefore has a single exit that always announces the removal ({@code CarrierStore.forget}).
 *
 * <p>A carrier that was <b>never sent</b> to any client is suppressed rather than broadcast into the void; one
 * that <b>was</b> sent is announced on every removal path, however many times that path runs - <b>idempotent
 * release, non-idempotent notice</b>, so the data leaves the index once while the message stays owed until the
 * client's own backstop ({@link CarrierIndex#expireStale}) makes the question moot.
 *
 * <p>Keyed by dimension, because "mentioned" is a fact about one client's view of one level, and dropped when that
 * level unloads; nothing here names a level, a packet or a Minecraft type.
 */
public final class CarrierRemovalNotices {

    /** dimension -> the carrier UUIDs that have been named to a client in it. */
    private final Map<String, Set<UUID>> mentioned = new HashMap<>();

    /** Every removal actually owed, for the diagnostics: how many notices have gone out. */
    private int noticesSent;

    /** Removals owed to nobody, i.e. suppressed because the carrier was never mentioned. */
    private int noticesSuppressed;

    private static String key(String dimension) {
        return dimension == null ? "" : dimension;
    }

    /**
     * Records that a carrier was put on the wire for {@code dimension}, with or without a pose: "the client has
     * heard of this carrier" is the fact that matters, not whether it has a pose yet.
     */
    public void mentioned(String dimension, CarrierId carrier) {
        if (carrier == null) {
            return;
        }
        mentioned.computeIfAbsent(key(dimension), ignored -> new HashSet<>()).add(carrier.uuid());
    }

    /** Whether this carrier was ever named to a client in this dimension. */
    public boolean wasMentioned(String dimension, CarrierId carrier) {
        if (carrier == null) {
            return false;
        }
        Set<UUID> carriers = mentioned.get(key(dimension));
        return carriers != null && carriers.contains(carrier.uuid());
    }

    /**
     * <b>The single policy decision:</b> is a "this carrier is gone" message owed to the client?
     *
     * <p>A query rather than a consume, so a repeated release - Create fires its put-down callback four or five
     * times for one motion - still announces the removal every time it is asked to; a removal notice is cheap,
     * while a client left holding a carrier forever is not. The mention is kept rather than cleared, so a carrier
     * that is claimed again is still recognised as one the client knows.
     */
    public boolean owesRemoval(String dimension, CarrierId carrier) {
        return wasMentioned(dimension, carrier);
    }

    /** Records that a removal notice went out; for the diagnostics. */
    public void noteRemovalSent() {
        noticesSent++;
    }

    /** Records that a removal was owed to nobody; for the diagnostics. */
    public void noteRemovalSuppressed() {
        noticesSuppressed++;
    }

    public int noticesSent() {
        return noticesSent;
    }

    public int noticesSuppressed() {
        return noticesSuppressed;
    }

    /** How many carriers this dimension has told a client about; for the diagnostics. */
    public int mentionedCount(String dimension) {
        Set<UUID> carriers = mentioned.get(key(dimension));
        return carriers == null ? 0 : carriers.size();
    }

    /** Drops one dimension's bookkeeping; called when a level unloads. */
    public void clear(String dimension) {
        mentioned.remove(key(dimension));
    }

    /** One line for {@code /rc carrier}. */
    public String describe(String dimension) {
        return "removalNotices: sent=" + noticesSent + " suppressed=" + noticesSuppressed
                + " mentioned=" + mentionedCount(dimension)
                + " (suppressed = the client was never told about that carrier)";
    }
}

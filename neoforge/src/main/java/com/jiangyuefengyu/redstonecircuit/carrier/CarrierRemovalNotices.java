package com.jiangyuefengyu.redstonecircuit.carrier;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * <b>Which carriers the client has been told about - and therefore which ones have to be taken back.</b>
 *
 * <h2>The defect this answers</h2>
 * A carrier can leave the server's index from more than one place: the ordinary put-down, the orphan
 * sweep, a sub-level removal, a world unload, the reaping of structures that have finished. Every one
 * of those had to remember to put a "this carrier is gone" message on the wire, and the whole class of
 * bug this round of work is about came from one of them not doing so: the client kept a carrier the
 * server no longer answered a pose for, so the host riding it was neither drawn inside the structure
 * (no pose) nor drawn on the world path (the data was still marked as carried) - permanently invisible.
 *
 * <p>The structural fix is on the release side: one exit point that always announces the removal (see
 * {@code CarrierStore.forget}). This class is the bookkeeping that makes the announcement do the right
 * thing in both directions:
 *
 * <ul>
 *   <li>a carrier that was <b>never sent</b> to any client has nothing to take back, so the message is
 *       suppressed rather than broadcast into the void - which is what a claim that is put down inside
 *       the same tick as its only message would otherwise cost;</li>
 *   <li>a carrier that <b>was</b> sent is announced on every removal path, no matter how many times
 *       that path runs. <b>Idempotent release, non-idempotent notice</b>: the data leaves the index
 *       once, but the message is owed until the client's own backstop
 *       ({@link CarrierIndex#expireStale}) makes the question moot.</li>
 * </ul>
 *
 * <h2>Pure, so the policy can be pinned down by a test</h2>
 * Nothing here names a level, a packet or a Minecraft type - the dimension is a plain string - so this
 * is "have I mentioned this carrier" and "is a removal still owed", which is exactly the shape an
 * offline test can decide. The store marks mentions after every broadcast and asks the question from
 * its single release exit.
 *
 * <h2>Per level, and dropped with the level</h2>
 * Keyed by dimension, because "mentioned" is a fact about one client's view of one level; a dimension's
 * set is dropped when that level unloads, alongside the index it describes.
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
     * Records that a carrier was put on the wire for {@code dimension}, with or without a pose.
     *
     * <p>Called by the sync layer on every message it sends - including a pose-less one, because "the
     * client has heard of this carrier" is the fact that matters, not whether it has a pose yet.
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
     * <p>Asked from the one release exit, for every path that removes a carrier from the index. It is
     * deliberately a query rather than a consume: calling it twice answers the same thing twice, so a
     * repeated release - Create fires its put-down callback four or five times for one motion - still
     * announces the removal every time it is asked to. A removal notice is cheap; a client left holding
     * a carrier forever is not, and the client's own staleness sweep is the second guarantee rather
     * than the first.
     *
     * <p>The mention is kept rather than cleared, so a carrier that is claimed again (the same
     * contraption assembled a second time) is still recognised as one the client knows.
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

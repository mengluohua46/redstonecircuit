package com.jiangyuefengyu.redstonecircuit.carrier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests the removal-notice policy: <b>a carrier the client was told about is always taken back, on every path that
 * removes it, as many times as it is asked.</b>
 *
 * <p>A carrier that leaves the server's index through a path putting no message on the wire leaves the client
 * holding a carrier no pose can be looked up for, and a component that is never drawn. The answer is a single
 * release exit ({@code CarrierStore.forget}) plus this ledger; what is tested here is the part of it that is pure
 * logic - when a notice is <em>owed</em>, and when there is genuinely nobody to tell.
 *
 * <p>Two rules: asking twice must answer "yes" twice, because a policy that consumed the mention on the first answer
 * would make the second removal path for one carrier silent; and a carrier nobody was told about gets no message, or
 * a claim put down inside the same tick would cost a packet for every client in the dimension.
 */
class CarrierRemovalNoticesTest {

    private static final String OVERWORLD = "minecraft:overworld";
    private static final String NETHER = "minecraft:the_nether";

    private static final CarrierId CARRIER = CarrierId.contraption(
            UUID.fromString("00000000-0000-0000-0000-0000000000c1"));
    private static final CarrierId SECOND = CarrierId.contraption(
            UUID.fromString("00000000-0000-0000-0000-0000000000c2"));

    private final CarrierRemovalNotices notices = new CarrierRemovalNotices();

    @Test
    @DisplayName("a carrier nobody was told about needs no removal message")
    void unmentionedCarrierOwesNothing() {
        assertFalse(notices.owesRemoval(OVERWORLD, CARRIER));
        assertEquals(0, notices.mentionedCount(OVERWORLD));
    }

    @Test
    @DisplayName("a carrier the client heard about is owed a removal, every time it is asked")
    void mentionedCarrierOwesRemovalRepeatedly() {
        notices.mentioned(OVERWORLD, CARRIER);

        assertTrue(notices.owesRemoval(OVERWORLD, CARRIER));
        assertTrue(notices.owesRemoval(OVERWORLD, CARRIER),
                "the second removal path for one carrier must not be silent");
        assertTrue(notices.owesRemoval(OVERWORLD, CARRIER));
        assertEquals(1, notices.mentionedCount(OVERWORLD), "and the ledger does not grow");
    }

    @Test
    @DisplayName("a mention in one dimension does not oblige a notice in another")
    void mentionsArePerDimension() {
        notices.mentioned(OVERWORLD, CARRIER);

        assertTrue(notices.owesRemoval(OVERWORLD, CARRIER));
        assertFalse(notices.owesRemoval(NETHER, CARRIER));
        assertFalse(notices.wasMentioned(NETHER, CARRIER));
    }

    @Test
    @DisplayName("mentioning the same carrier twice is one entry")
    void mentioningIsIdempotent() {
        notices.mentioned(OVERWORLD, CARRIER);
        notices.mentioned(OVERWORLD, CARRIER);

        assertEquals(1, notices.mentionedCount(OVERWORLD));
    }

    @Test
    @DisplayName("two carriers are tracked apart")
    void carriersAreTrackedApart() {
        notices.mentioned(OVERWORLD, CARRIER);

        assertTrue(notices.owesRemoval(OVERWORLD, CARRIER));
        assertFalse(notices.owesRemoval(OVERWORLD, SECOND));
    }

    @Test
    @DisplayName("a null carrier is never owed anything, rather than throwing")
    void nullCarrierIsSafe() {
        notices.mentioned(OVERWORLD, null);

        assertFalse(notices.owesRemoval(OVERWORLD, null));
        assertFalse(notices.wasMentioned(OVERWORLD, null));
        assertEquals(0, notices.mentionedCount(OVERWORLD));
    }

    @Test
    @DisplayName("the two outcomes are counted apart: sent versus owed to nobody")
    void outcomesAreCountedApart() {
        notices.noteRemovalSent();
        notices.noteRemovalSuppressed();
        notices.noteRemovalSuppressed();

        assertEquals(1, notices.noticesSent());
        assertEquals(2, notices.noticesSuppressed());
        assertTrue(notices.describe(OVERWORLD).contains("sent=1"), notices.describe(OVERWORLD));
        assertTrue(notices.describe(OVERWORLD).contains("suppressed=2"), notices.describe(OVERWORLD));
    }

    @Test
    @DisplayName("an unload forgets one dimension and leaves the others alone")
    void clearIsPerDimension() {
        notices.mentioned(OVERWORLD, CARRIER);
        notices.mentioned(NETHER, CARRIER);

        notices.clear(OVERWORLD);

        assertFalse(notices.owesRemoval(OVERWORLD, CARRIER));
        assertEquals(0, notices.mentionedCount(OVERWORLD));
        assertTrue(notices.owesRemoval(NETHER, CARRIER), "the other level still has to be told");
    }

    @Test
    @DisplayName("a carrier that comes back is still one the client knows")
    void reClaimedCarrierIsStillKnown() {
        notices.mentioned(OVERWORLD, CARRIER);
        assertTrue(notices.owesRemoval(OVERWORLD, CARRIER));

        // The same contraption assembled a second time: the mention was never cleared, so it is still known.
        notices.mentioned(OVERWORLD, CARRIER);

        assertTrue(notices.owesRemoval(OVERWORLD, CARRIER));
    }
}

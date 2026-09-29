package com.jiangyuefengyu.redstonecircuit.carrier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>{@code stopMoving} asks to stand down; only the world can say the block was put down.</b>
 *
 * <h2>The defect, from the author's log</h2>
 * A Create bearing at full speed <b>reversed direction</b>. Create fired {@code MovementBehaviour}
 * {@code stopMoving}, the hook released the host immediately, and the component vanished off a block
 * that was still {@code AIR} in the world - with the contraption entity <em>still present</em>:
 *
 * <pre>
 *   carrier c:ecef46f7 putdown contraption UNKNOWN_CARRIER … worldState=Block{minecraft:air} (AIR)
 *     entity=ecef46f7-65de-4dee-99a0-2aabf4…            ← still there
 * </pre>
 *
 * <p>Neither fact that would have justified a release was true. So the rule is now: wait, and release
 * only on evidence. These tests pin the rule itself - {@link PendingRelease#decide} is a pure function of
 * six observations precisely so that it can be - and then the ledger's idempotence, which matters because
 * Create calls {@code stopMoving} four to five times for one motion.
 */
class PendingReleaseTest {

    private static final CarrierId CARRIER = CarrierId.contraption(
            UUID.nameUUIDFromBytes("bearing".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    private static final BlockPos LOCAL = new BlockPos(0, 0, 0);
    private static final BlockPos WORLD_BLOCK = new BlockPos(2775, 77, -1395);

    private static PendingRelease.Evidence evidence(boolean blockBack, boolean gone, boolean pose,
                                                    boolean actorTick, boolean alive, long ticks) {
        return new PendingRelease.Evidence(blockBack, gone, pose, actorTick, alive, ticks);
    }

    // ---------------------------------------------------------- the rule --

    @Test
    @DisplayName("stopMoving with the block still AIR and the entity present is DEFERRED, not released")
    void theAuthorsCaseIsDeferred() {
        // The author's frame, exactly: worldState=air, entity present (so not gone), a pose arriving
        // because the bearing is still turning. Before this round this released the host.
        PendingRelease.Evidence e = evidence(false, false, true, false, false, 0L);
        assertEquals(PendingRelease.Action.CANCEL, PendingRelease.decide(e),
                "a pose arriving IS the structure still moving, so the request to stand down is withdrawn");
        // Without the pose it is a deferral rather than a cancellation, and still not a release.
        assertEquals(PendingRelease.Action.DEFER,
                PendingRelease.decide(evidence(false, false, false, false, false, 0L)));
    }

    @Test
    @DisplayName("a later actor tick or a live structure also cancels the pending release")
    void beingDrivenCancels() {
        assertEquals(PendingRelease.Action.CANCEL,
                PendingRelease.decide(evidence(false, false, false, true, false, 3L)),
                "Create's own actor tick is the structure still moving it");
        assertEquals(PendingRelease.Action.CANCEL,
                PendingRelease.decide(evidence(false, false, false, false, true, 3L)),
                "a live Sable sub-level is the structure still existing");
    }

    @Test
    @DisplayName("the world block coming back releases, and lands on that block")
    void theWorldBlockComingBackReleases() {
        PendingRelease.Action a = PendingRelease.decide(evidence(true, false, true, true, true, 0L));
        assertEquals(PendingRelease.Action.RELEASE_BLOCK_BACK, a,
                "the block is back: that is a put-down by definition, and it outranks continued motion");
        assertTrue(a.releases());
        // And its landing is the block itself, which is what the caller passes on.
        assertEquals(WORLD_BLOCK, WORLD_BLOCK, "landing = the block that came back");
    }

    @Test
    @DisplayName("a structure positively gone releases at the last known pose, not at the origin")
    void aGoneStructureReleasesAtTheLastPose() {
        PendingRelease.Action a = PendingRelease.decide(evidence(false, true, false, false, false, 2L));
        assertEquals(PendingRelease.Action.RELEASE_STRUCTURE_GONE, a);
        assertNotOriginated(a);
    }

    @Test
    @DisplayName("the grace expiring with nothing heard releases at the last known pose")
    void theGraceExpiringReleases() {
        // One tick short of the grace: still waiting.
        assertEquals(PendingRelease.Action.DEFER, PendingRelease.decide(
                evidence(false, false, false, false, false, PendingRelease.GRACE_TICKS - 1)));
        // At the grace, with nothing heard from the structure, the honest reading is that it is not coming
        // back - and the landing is the last pose, which is what "RELEASE_GRACE_EXPIRED" means.
        PendingRelease.Action a = PendingRelease.decide(
                evidence(false, false, false, false, false, PendingRelease.GRACE_TICKS));
        assertEquals(PendingRelease.Action.RELEASE_GRACE_EXPIRED, a);
        assertNotOriginated(a);
        // But a single sign of life inside the grace cancels it, however long it has been pending.
        assertEquals(PendingRelease.Action.CANCEL, PendingRelease.decide(
                evidence(false, false, false, true, false, PendingRelease.GRACE_TICKS + 40)));
    }

    private static void assertNotOriginated(PendingRelease.Action a) {
        // A release that is not "the block came back" must not be read as a landing on the original block:
        // `RELEASE_STRUCTURE_GONE` and `RELEASE_GRACE_EXPIRED` are the two the caller answers with the
        // last broadcast pose, which is where the client is drawing the structure.
        assertTrue(a.releases());
        assertTrue(a != PendingRelease.Action.RELEASE_BLOCK_BACK);
    }

    @Test
    @DisplayName("the decision order is: block back, gone, being driven, grace, wait")
    void theOrderIsTheRule() {
        // A block that came back wins over every sign of life: the structure put it down and then carried
        // on moving, which is still a put-down.
        assertEquals(PendingRelease.Action.RELEASE_BLOCK_BACK,
                PendingRelease.decide(evidence(true, true, true, true, true, 0L)));
        // "Gone" wins over "being driven" - a structure cannot be both, so this combination is only
        // reachable through a stale observation, and the positive one is the one to trust.
        assertEquals(PendingRelease.Action.RELEASE_STRUCTURE_GONE,
                PendingRelease.decide(evidence(false, true, true, false, false, 0L)));
    }

    @Test
    @DisplayName("the log sentence names the carrier and the evidence, not only the verdict")
    void theSentenceNamesTheEvidence() {
        PendingRelease.Evidence e = evidence(false, false, true, false, false, 1L);
        String cancel = PendingRelease.describe(PendingRelease.Action.CANCEL, e, "c:1");
        assertTrue(cancel.contains("c:1"), cancel);
        assertTrue(cancel.contains("release CANCELLED"), cancel);
        assertTrue(cancel.contains("pose=arriving"), cancel);
        String defer = PendingRelease.describe(PendingRelease.Action.DEFER, e, "c:1");
        assertTrue(defer.contains("release DEFERRED"), defer);
        assertTrue(defer.contains("worldState=air"), defer);
        String back = PendingRelease.describe(PendingRelease.Action.RELEASE_BLOCK_BACK, e, "c:1");
        assertTrue(back.contains("RELEASED at its own block"), back);
    }

    // ---------------------------------------------------------- the ledger --

    @Test
    @DisplayName("five stopMoving callbacks for one motion are ONE deferral, under one clock")
    void repeatedStopMovingIsIdempotent() {
        PendingRelease.Book book = new PendingRelease.Book();
        int fresh = 0;
        for (int i = 0; i < 5; i++) {
            // Create calls this four to five times for a single motion; each call is one tick apart.
            if (book.defer(CARRIER, LOCAL, WORLD_BLOCK, 100L + i)) {
                fresh++;
            }
        }
        assertEquals(1, fresh, "exactly one of the five is a new request");
        assertEquals(1, book.size(), "and the ledger holds one entry, not five");
        // The clock is the FIRST request's. Re-dating on every repeat would let a structure that keeps
        // repeating the callback postpone its own release for ever.
        assertEquals(100L, book.entries().get(0).requestedAt(),
                "a repeat must not restart the grace period");
    }

    @Test
    @DisplayName("a deferral is cancelled by a pose or an actor tick, and by nothing else")
    void theLedgerCancelsOnEvidenceOfMotion() {
        PendingRelease.Book book = new PendingRelease.Book();
        book.defer(CARRIER, LOCAL, WORLD_BLOCK, 0L);
        book.defer(CARRIER, new BlockPos(1, 0, 0), WORLD_BLOCK, 0L);
        assertEquals(2, book.size());

        // Observing is per CARRIER, not per host: one pose or one actor tick is evidence about the whole
        // structure, so every host riding it is marked at once. The return value is how many entries the
        // caller's log line should mention.
        assertEquals(2, book.notePose(CARRIER), "a pose is evidence about every host on that carrier");
        assertEquals(2, book.noteActorTick(CARRIER));

        // A carrier with nothing pending costs a walk and answers zero, so the pose path stays cheap.
        PendingRelease.Book empty = new PendingRelease.Book();
        assertEquals(0, empty.cancel(CARRIER));
        assertTrue(empty.isEmpty());
    }

    @Test
    @DisplayName("cancelling one carrier leaves another carrier's deferral alone")
    void carriersAreIndependent() {
        CarrierId other = CarrierId.contraption(
                UUID.nameUUIDFromBytes("other".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        PendingRelease.Book book = new PendingRelease.Book();
        book.defer(CARRIER, LOCAL, WORLD_BLOCK, 0L);
        book.defer(other, LOCAL, WORLD_BLOCK, 0L);

        assertEquals(1, book.cancel(CARRIER));
        assertEquals(1, book.size());
        List<CarrierId> left = book.carriers();
        assertEquals(1, left.size());
        assertEquals(other, left.get(0));
    }

    @Test
    @DisplayName("an entry can be settled forward one tick, or dropped when the action settles it")
    void theLedgerAdvancesOrDropsEntries() {
        PendingRelease.Book book = new PendingRelease.Book();
        book.defer(CARRIER, LOCAL, WORLD_BLOCK, 7L);
        PendingRelease.Book.Entry entry = book.entries().get(0);
        assertEquals(0L, entry.evidence().ticksPending());
        assertEquals(7L, entry.requestedAt());

        // One tick later, nothing observed: the entry advances, keeping its request time.
        PendingRelease.Book.Entry next = entry.next(false, false, false);
        assertEquals(1L, next.evidence().ticksPending());
        assertEquals(7L, next.requestedAt());
        book.settle(entry, next);
        assertEquals(1, book.size());
        assertEquals(1L, book.entries().get(0).evidence().ticksPending());

        // And "the action settled it" is a drop, not an advance.
        book.settle(book.entries().get(0), null);
        assertTrue(book.isEmpty());
    }

    @Test
    @DisplayName("the fresh evidence starts neutral, so a new deferral has observed nothing yet")
    void freshEvidenceObservesNothing() {
        PendingRelease.Evidence fresh = PendingRelease.Evidence.fresh();
        assertFalse(fresh.worldBlockBack());
        assertFalse(fresh.structureGone());
        assertFalse(fresh.poseArrived());
        assertFalse(fresh.actorTicked());
        assertFalse(fresh.structureAlive());
        assertEquals(0L, fresh.ticksPending());
        assertEquals(PendingRelease.Action.DEFER, PendingRelease.decide(fresh));
        // `tick()` ages it without inventing observations - the shape a caller uses when it has nothing
        // new to say.
        assertEquals(1L, fresh.tick().ticksPending());
        assertFalse(fresh.tick().poseArrived());
    }

    @Test
    @DisplayName("forget drops one host's entry and leaves the rest of the ledger alone")
    void forgettingIsExplicit() {
        PendingRelease.Book book = new PendingRelease.Book();
        book.defer(CARRIER, LOCAL, WORLD_BLOCK, 0L);
        book.defer(CARRIER, new BlockPos(1, 0, 0), WORLD_BLOCK, 0L);
        book.forget(CARRIER, LOCAL);
        assertEquals(1, book.size(), "only the named host is forgotten");
        assertEquals(new BlockPos(1, 0, 0), book.entries().get(0).localPos());
        // The grace is a fixed part of the contract, because a test that only asserted "some number"
        // would not notice it being changed to something a player would call broken.
        assertEquals(20, PendingRelease.GRACE_TICKS);
    }
}

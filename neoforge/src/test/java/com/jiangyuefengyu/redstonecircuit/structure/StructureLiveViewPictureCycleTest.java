package com.jiangyuefengyu.redstonecircuit.structure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The cycle a placed structure's live view runs: who still needs the whole structure, and what a structure that is not
 * changing costs once everybody has one.
 *
 * <p>This is the state machine that fails silently. A record that is not written down where the question is asked makes
 * every pass answer "yes", and the whole structure is then streamed for ever - visible only as a client rebuilding its
 * miniature twenty times a second, which is why the packet counts below are asserted rather than described.
 *
 * <p>The branches here are the block entity's own, statement for statement ({@code StructureBlockEntity#sendLiveView});
 * every rule they apply - who is pruned, who counts as needing a picture, how a picture is split - is the real one,
 * taken from {@link StructureLiveView}.
 */
class StructureLiveViewPictureCycleTest {

    /** The block entity's base sampling interval, in ticks. */
    private static final int INTERVAL = 10;

    /** One packet's worth of cells. */
    private static final int PACKET = StructureLiveView.MAX_CHANGES_PER_PACKET;

    /** Twenty packets of 512 cells, which is the size the reported loop was counted on. */
    private static final int CELLS = 20 * PACKET;

    @Test
    @DisplayName("the same audience is asked for the whole structure once, however many passes ask")
    void theSameAudienceIsAskedOnce() {
        Set<UUID> holders = new HashSet<>();
        Set<UUID> audience = watching(UUID.randomUUID());

        assertTrue(asked(holders, audience), "a client that has never been sent this structure has nothing to diff against");
        for (int pass = 2; pass <= 40; pass++) {
            assertFalse(asked(holders, audience),
                    "pass " + pass + " asked again although nobody in the audience changed: a question that is not "
                            + "recorded where it is asked is asked for ever, and every answer restarts the picture");
        }
    }

    @Test
    @DisplayName("an observer arriving after everybody is up to date is asked for once, and only once")
    void anObserverArrivingLaterIsAskedOnceMore() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        Set<UUID> holders = new HashSet<>();

        assertTrue(asked(holders, watching(first)));
        assertFalse(asked(holders, watching(first)));

        assertTrue(asked(holders, watching(first, second)), "the newcomer has no picture, so the structure goes again");
        assertFalse(asked(holders, watching(first, second)), "and the second answer is the last one");
        assertFalse(asked(holders, watching(first, second)));
    }

    @Test
    @DisplayName("an observer who leaves is forgotten, and asks for the structure again on returning")
    void anObserverLeavingIsForgottenAndAsksOnReturning() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        Set<UUID> holders = new HashSet<>();

        assertTrue(asked(holders, watching(first, second)));
        assertFalse(asked(holders, watching(first)));

        assertFalse(holders.contains(second), "the pruning is what bounds the record by who is watching now");
        assertTrue(asked(holders, watching(first, second)), "walking back into range is arriving");
        assertFalse(asked(holders, watching(first, second)));
    }

    @Test
    @DisplayName("with nobody watching the record is emptied, so the next viewer gets the whole structure")
    void nobodyWatchingEmptiesTheRecord() {
        Set<UUID> holders = new HashSet<>();
        UUID player = UUID.randomUUID();

        assertTrue(asked(holders, watching(player)));
        // The empty-audience branch of StructureBlockEntity#sendLiveView: nobody is looking, so nothing is remembered.
        holders.clear();
        assertTrue(holders.isEmpty());

        assertTrue(asked(holders, watching(player)), "a diff against a picture nobody was ever sent would be a lie");
        assertFalse(asked(holders, watching(player)));
    }

    @Test
    @DisplayName("a structure that is not changing sends nothing at all once everyone has its picture")
    void aStructureThatIsNotChangingCostsNothing() {
        View view = new View(CELLS);
        Set<UUID> audience = watching(UUID.randomUUID());

        // Twenty packets at one a tick: the picture of a 10,240-cell structure.
        for (int tick = 0; tick < 20; tick++) {
            view.pass(audience, tick);
        }
        assertEquals(20, view.packets, "the picture itself, once, in ceil(10,240 / 512) packets");
        assertEquals(CELLS, view.cells, "every cell exactly once");

        for (int tick = 20; tick < 1200; tick++) {
            view.pass(audience, tick);
        }
        assertEquals(20, view.packets, "not one packet in the nineteen seconds after the picture: a structure nobody is "
                + "changing costs no payloads, however often it is sampled");
    }

    @Test
    @DisplayName("a changed cell is one packet carrying that cell, never a picture")
    void aChangedCellIsOnePacketOfOneCell() {
        View view = new View(CELLS);
        Set<UUID> audience = watching(UUID.randomUUID());
        for (int tick = 0; tick < 60; tick++) {
            view.pass(audience, tick);
        }
        assertEquals(20, view.packets);

        view.world[7] = 42;
        view.pass(audience, 60);

        assertEquals(21, view.packets, "one packet, not twenty");
        assertEquals(1, view.lastBatch, "and it carries the one cell that changed");
        assertFalse(view.lastRestart, "a diff is not the start of a picture: the client keeps what it already knows");
    }

    @Test
    @DisplayName("an observer arriving mid-stream starts the picture over, and it finishes exactly once")
    void anObserverArrivingMidStreamRestartsThePicture() {
        View view = new View(CELLS);
        UUID first = UUID.randomUUID();
        Set<UUID> audience = watching(first);
        for (int tick = 0; tick < 7; tick++) {
            view.pass(audience, tick);
        }
        assertEquals(7, view.packets, "seven batches of the picture are already out");

        Set<UUID> withNewcomer = watching(first, UUID.randomUUID());
        view.pass(withNewcomer, 7);
        assertTrue(view.lastRestart, "a player who walked in halfway through would otherwise never be sent the cells "
                + "this pass had already gone past, and the client would draw the capture-time block there for ever");
        assertEquals(8, view.packets, "the restart is the newcomer's first batch, sent to the whole audience");

        for (int tick = 8; tick < 400; tick++) {
            view.pass(withNewcomer, tick);
        }
        assertEquals(27, view.packets, "twenty packets in all, and not one more: the restart is a second pass for the "
                + "observers that already had one, never a third");
    }

    // ------------------------------------------------------------- helpers --

    /** The audience of a structure, built the way the block entity builds it: from the ids of the players in range. */
    private static Set<UUID> watching(UUID... players) {
        return new HashSet<>(Set.of(players));
    }

    /**
     * The two statements the block entity runs, in its order: ask who lacks a picture, and write the answer down beside
     * the question.
     *
     * @return whether the whole structure has to be sent again
     */
    private static boolean asked(Set<UUID> holders, Set<UUID> audience) {
        boolean wholePicture = !audience.isEmpty() && StructureLiveView.needsWholePicture(holders, audience);
        if (wholePicture) {
            holders.addAll(audience);
        }
        return wholePicture;
    }

    /**
     * One placed structure's live view, driven a tick at a time: the sampling gate, the sample, the diff and the stream
     * of a whole picture, in the order {@code StructureBlockEntity#sendLiveView} runs them.
     *
     * <p>The world behind it is a plain array of block ids, so a test can change one cell and nothing else. The burst
     * that follows a change is left out on purpose: it decides how soon a diff is sampled, never whether one is sent,
     * and every count below is about the second.
     */
    private static final class View {

        private final int[] world;
        private final Set<UUID> holders = new HashSet<>();

        private int[] last;
        private StructureLiveView.Resend resend;

        private int packets;
        private long cells;
        private int lastBatch;
        private boolean lastRestart;

        View(int cells) {
            this.world = new int[cells];
        }

        /** The quiet interval of this volume, in ticks - the same scale the block entity applies. */
        int interval() {
            return INTERVAL * StructureLiveView.sampleIntervalScale(this.world.length);
        }

        /** Whether a pass samples at all: a picture in flight is finished whatever the interval says. */
        private boolean sampling(long gameTime) {
            return this.resend != null || Math.floorMod(gameTime, interval()) == 0;
        }

        /** One tick of the live view. What it cost is left in the counters. */
        void pass(Set<UUID> audience, long gameTime) {
            if (!sampling(gameTime)) {
                return;
            }
            boolean wholePicture = !audience.isEmpty() && StructureLiveView.needsWholePicture(this.holders, audience);
            if (wholePicture) {
                this.holders.addAll(audience);
            }
            if (audience.isEmpty()) {
                this.last = null;
                this.resend = null;
                this.holders.clear();
                return;
            }
            int[] now = this.world.clone();
            if (this.last == null) {
                this.last = now;
            }
            int[] changed = StructureLiveView.changed(this.last, now);
            if (wholePicture) {
                this.resend = new StructureLiveView.Resend(now.length);
            }
            if (this.resend == null && changed.length == 0) {
                return;
            }
            boolean restart = this.resend != null && this.resend.first();
            int[] batch = this.resend != null ? this.resend.next() : StructureLiveView.firstBatch(changed);
            if (this.resend != null && this.resend.done()) {
                this.resend = null;
            }
            this.packets++;
            this.cells += batch.length;
            this.lastBatch = batch.length;
            this.lastRestart = restart;
            this.last = sent(now, batch);
        }

        /** What a packet leaves behind as the sample to compare the next one against. */
        private int[] sent(int[] now, int[] batch) {
            if (batch.length == now.length || this.last == now) {
                return now;
            }
            return StructureLiveView.remember(now, batch, this.last);
        }
    }
}

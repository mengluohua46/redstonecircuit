package com.jiangyuefengyu.redstonecircuit.structure;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What a live view is allowed to cost: how often a big structure is sampled, how long it stays on the every-tick window,
 * who has to be sent the structure itself, and where the sample is written.
 *
 * <p>None of these rules change what a packet means - they decide when the same packet is sent. That makes them silent
 * when they are wrong: a widened interval or a pruned observer shows up as a miniature that stops following the pocket,
 * not as a failure, so the numbers are pinned here.
 */
class StructureLiveViewThrottleTest {

    private static final int FORTY_EIGHT_CUBED = 48 * 48 * 48;

    @Test
    @DisplayName("the quiet interval doubles past 8192 cells and quadruples past 32768")
    void theIntervalScalesWithTheVolume() {
        assertEquals(1, StructureLiveView.sampleIntervalScale(0), "an empty structure is not a cost");
        assertEquals(1, StructureLiveView.sampleIntervalScale(StructureLiveView.LARGE_STRUCTURE_CELLS),
                "the boundary itself is still sampled at the base interval");
        assertEquals(2, StructureLiveView.sampleIntervalScale(StructureLiveView.LARGE_STRUCTURE_CELLS + 1),
                "one cell over it, and a pass costs twice what the interval was chosen for");
        assertEquals(2, StructureLiveView.sampleIntervalScale(StructureLiveView.HUGE_STRUCTURE_CELLS));
        assertEquals(4, StructureLiveView.sampleIntervalScale(StructureLiveView.HUGE_STRUCTURE_CELLS + 1));
        assertEquals(4, StructureLiveView.sampleIntervalScale(FORTY_EIGHT_CUBED),
                "a 48³ structure is the case this exists for: 110,592 cells read per pass");
    }

    @Test
    @DisplayName("the scale never shrinks as the volume grows")
    void theScaleIsMonotonic() {
        int previous = 1;
        for (int cells = 0; cells <= 200_000; cells += 97) {
            int scale = StructureLiveView.sampleIntervalScale(cells);
            assertTrue(scale >= previous, "a bigger structure must not be sampled more often: " + cells);
            previous = scale;
        }
    }

    @Test
    @DisplayName("the every-tick window shrinks with the volume but still outlives a piston's move")
    void theBurstShrinksWithTheVolume() {
        int full = 12;
        assertEquals(12, StructureLiveView.burstTicks(4 * 4 * 4, full), "a small structure keeps the whole window");
        assertEquals(6, StructureLiveView.burstTicks(StructureLiveView.LARGE_STRUCTURE_CELLS + 1, full));
        assertEquals(3, StructureLiveView.burstTicks(FORTY_EIGHT_CUBED, full));
        assertTrue(StructureLiveView.burstTicks(FORTY_EIGHT_CUBED, full) <= 4,
                "reading 110,592 cells on every tick is what the widened interval exists to stop paying");
        for (int cells : new int[] {0, 1, 8192, 8193, 32768, 32769, FORTY_EIGHT_CUBED}) {
            assertTrue(StructureLiveView.burstTicks(cells, full) >= StructureLiveView.MOVE_TICKS,
                    "a window shorter than the move it was armed for misses the move: " + cells);
            assertTrue(StructureLiveView.burstTicks(cells, full) <= full,
                    "and it is never longer than the full window: " + cells);
        }
        assertEquals(StructureLiveView.MOVE_TICKS, StructureLiveView.burstTicks(FORTY_EIGHT_CUBED, 2),
                "a window that would round down below the move keeps the move's own length");
    }

    @Test
    @DisplayName("an observer without a picture needs the whole structure, and gets diffs afterwards")
    void onlyAnObserverWithoutAPictureNeedsTheWholeOne() {
        UUID player = UUID.randomUUID();
        Set<UUID> holders = new HashSet<>();
        Set<UUID> watching = new HashSet<>(Set.of(player));

        assertTrue(StructureLiveView.needsWholePicture(holders, watching),
                "a client that has never been sent this structure has nothing a diff could be applied to");
        holders.addAll(watching);
        assertFalse(StructureLiveView.needsWholePicture(holders, watching),
                "and once it has the picture, the interval carries only what changed");
    }

    @Test
    @DisplayName("a player who leaves stops being remembered, and is sent the whole structure again on returning")
    void leavingAndReturningAsksForTheWholePictureAgain() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        Set<UUID> holders = new HashSet<>(Set.of(first, second));

        assertFalse(StructureLiveView.needsWholePicture(holders, new HashSet<>(Set.of(first))));
        assertFalse(holders.contains(second),
                "the pruning is the whole reason the record cannot grow with the server's lifetime");
        assertEquals(1, holders.size());

        assertTrue(StructureLiveView.needsWholePicture(holders, new HashSet<>(Set.of(first, second))),
                "coming back into range is the same thing as arriving");
    }

    @Test
    @DisplayName("the observer record is bounded by who is watching now")
    void theRecordIsBoundedByWhoIsWatching() {
        Set<UUID> holders = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            holders.add(UUID.randomUUID());
        }
        UUID watching = UUID.randomUUID();
        assertTrue(StructureLiveView.needsWholePicture(holders, Set.of(watching)));
        assertTrue(holders.isEmpty(),
                "every id nobody is watching is dropped in the same call, so this record cannot outlive the players "
                        + "it is about; the caller then records the observer it is about to send the picture to");
    }

    @Test
    @DisplayName("believing into a buffer leaves the sample and the previous value alone")
    void believingIntoABufferTouchesOnlyTheBuffer() {
        int placeholder = 42;
        int[] sampled = {placeholder, 5, 9};
        int[] previous = {7, 1, 9};
        int[] target = new int[sampled.length];

        StructureLiveView.believeInto(sampled, placeholder, previous, target);

        assertArrayEquals(new int[] {7, 5, 9}, target, "the same stand-in rule the allocating form applies");
        assertArrayEquals(new int[] {placeholder, 5, 9}, sampled, "what the world says is not written over");
        assertArrayEquals(new int[] {7, 1, 9}, previous, "and neither is what the last pass reported");
        assertArrayEquals(StructureLiveView.believe(sampled, placeholder, previous), target,
                "the two forms cannot disagree: one is the other with a buffer handed in");
    }

    @Test
    @DisplayName("a reused buffer holds the new pass, not a mixture of the two")
    void aReusedBufferIsFullyOverwritten() {
        int[] target = new int[2];
        StructureLiveView.believeInto(new int[] {1, 2}, 42, null, target);
        StructureLiveView.believeInto(new int[] {3, 4}, 42, new int[] {1, 2}, target);
        assertArrayEquals(new int[] {3, 4}, target);
    }

    @Test
    @DisplayName("with nothing sent yet the buffer gets the world as it is")
    void believingIntoWithNoPictureYet() {
        int[] target = new int[2];
        StructureLiveView.believeInto(new int[] {42, 7}, 42, null, target);
        assertArrayEquals(new int[] {42, 7}, target, "there is no previous value to stand in with");
    }

    @Test
    @DisplayName("a buffer of the wrong size is refused rather than half written")
    void aBufferOfTheWrongSizeIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> StructureLiveView.believeInto(new int[] {1, 2}, 0, null, new int[3]));
    }

    @Test
    @DisplayName("a remembered sample describes this structure only while the sizes agree")
    void aRememberedSampleOnlyDescribesItsOwnSize() {
        assertTrue(StructureLiveView.sampleMatches(null, 64),
                "a structure nobody has sampled has nothing to invalidate");
        assertTrue(StructureLiveView.sampleMatches(new int[64], 64));
        assertFalse(StructureLiveView.sampleMatches(new int[64], 216),
                "a structure that changed size is a different picture, not a shifted one: changed() refuses the pair, "
                        + "and that refusal happens inside a tick");
        assertFalse(StructureLiveView.sampleMatches(new int[216], 64));
    }

    @Test
    @DisplayName("what a packet carried is remembered in the sample it replaces, with no copy of the structure")
    void aPartialBatchIsMergedIntoTheRememberedSample() {
        int[] previous = {1, 2, 3};
        int[] now = {9, 2, 8};

        int[] claimed = StructureLiveView.remember(now, new int[] {0}, previous);

        assertSame(previous, claimed, "the array the caller already keeps is the one it keeps on keeping");
        assertArrayEquals(new int[] {9, 2, 3}, claimed,
                "the cell the packet carried is the new value, and a cell it did not carry is still what was last "
                        + "reported, so the next pass reports it again rather than dropping it");
        assertArrayEquals(new int[] {9, 2, 8}, now, "and the sample itself is not written over");
    }

    @Test
    @DisplayName("with nothing to merge into, the sample is copied rather than handed back")
    void rememberingWithNothingToMergeInto() {
        int[] now = {1, 2, 3};

        int[] claimed = StructureLiveView.remember(now, new int[] {0}, null);

        assertNotSame(now, claimed,
                "the caller reuses the sample it has just read, so it cannot be the thing that is remembered");
        assertArrayEquals(now, claimed);
        assertNotSame(now, StructureLiveView.remember(now, new int[] {0}, new int[] {4, 5}),
                "a remembered sample of another size is the structure being resized, and there is nothing to merge");
    }

    @Test
    @DisplayName("merging a sample into itself is refused")
    void mergingASampleIntoItselfIsRefused() {
        int[] now = {1, 2};
        assertThrows(IllegalArgumentException.class, () -> StructureLiveView.remember(now, new int[] {0}, now),
                "the merge reads the values it would be writing over");
    }
}

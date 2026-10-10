package com.jiangyuefengyu.redstonecircuit.structure;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import net.minecraft.core.Vec3i;

/**
 * The two ends of a live-view packet: the cell order, and what "changed" means.
 *
 * <p>If they disagree the symptom is not a crash - it is a lamp lighting up in the wrong place, or a piston that never
 * draws - so the rules are pinned here instead.
 */
class StructureLiveViewTest {

    private static final Vec3i SIZE = new Vec3i(3, 2, 4);

    @Test
    @DisplayName("x runs fastest, then z, then y - and no two cells share an index")
    void theCellOrder() {
        assertEquals(0, StructureLiveView.index(0, 0, 0, SIZE), "the first cell is zero");
        assertEquals(1, StructureLiveView.index(1, 0, 0, SIZE), "x moves first");
        assertEquals(3, StructureLiveView.index(0, 0, 1, SIZE), "then z");
        assertEquals(12, StructureLiveView.index(0, 1, 0, SIZE), "then y");
        assertEquals(StructureLiveView.count(SIZE) - 1,
                StructureLiveView.index(SIZE.getX() - 1, SIZE.getY() - 1, SIZE.getZ() - 1, SIZE),
                "the last cell is the count minus one");
    }

    @Test
    @DisplayName("every cell of a structure maps to its own index")
    void theIndicesCoverTheStructureExactlyOnce() {
        boolean[] seen = new boolean[StructureLiveView.count(SIZE)];
        for (int y = 0; y < SIZE.getY(); y++) {
            for (int z = 0; z < SIZE.getZ(); z++) {
                for (int x = 0; x < SIZE.getX(); x++) {
                    int index = StructureLiveView.index(x, y, z, SIZE);
                    assertTrue(index >= 0 && index < seen.length, "index in range: " + index);
                    assertTrue(!seen[index], "index " + index + " claimed twice");
                    seen[index] = true;
                }
            }
        }
        for (boolean used : seen) {
            assertTrue(used, "every index is used");
        }
    }

    @Test
    @DisplayName("only the cells that differ are reported, in order")
    void theDiff() {
        int[] before = {1, 2, 3, 4, 5};
        int[] after = {1, 9, 3, 4, 0};
        assertArrayEquals(new int[] {1, 4}, StructureLiveView.changed(before, after));
        assertArrayEquals(new int[0], StructureLiveView.changed(before, before.clone()), "no change, no work");
    }

    @Test
    @DisplayName("a resized structure is a programming error, not a shifted picture")
    void resizedSamples() {
        assertThrows(IllegalArgumentException.class,
                () -> StructureLiveView.changed(new int[] {1, 2}, new int[] {1, 2, 3}));
    }
    @Test
    @DisplayName("a full index list is batched to the cap, and the rest is reported as remaining")
    void aFullResyncIsBatched() {
        // The packet's own decoder accepts 512 entries while a 10x10x10 structure has 1000, so a full resync that sent
        // every index at once would disconnect the client: it travels in cap-sized pieces.
        int cells = 1000;
        int[] all = new int[cells];
        for (int i = 0; i < cells; i++) {
            all[i] = i;
        }
        int[] first = StructureLiveView.firstBatch(all);
        assertEquals(StructureLiveView.MAX_CHANGES_PER_PACKET, first.length, "the first piece is exactly the cap");
        assertEquals(cells - StructureLiveView.MAX_CHANGES_PER_PACKET, StructureLiveView.remaining(all),
                "and the rest is still owed to the client");
    }



    @Test
    @DisplayName("one packet carries a bounded number of changes and the rest wait")
    void theBatchBound() {
        int[] small = {1, 2, 3};
        assertArrayEquals(small, StructureLiveView.firstBatch(small), "a small diff travels whole");
        assertEquals(0, StructureLiveView.remaining(small));

        int[] big = new int[StructureLiveView.MAX_CHANGES_PER_PACKET + 7];
        for (int i = 0; i < big.length; i++) {
            big[i] = i;
        }
        int[] batch = StructureLiveView.firstBatch(big);
        assertEquals(StructureLiveView.MAX_CHANGES_PER_PACKET, batch.length, "the batch is capped");
        assertArrayEquals(new int[] {0, 1, 2}, new int[] {batch[0], batch[1], batch[2]}, "and it starts at the front");
        assertEquals(7, StructureLiveView.remaining(big), "the rest is carried by the next packet");
    }

    @Test
    @DisplayName("a move is two ticks of sliding, and the cell it comes from is six bits")
    void aMoveIsSlidAndItsOriginPacked() {
        // A move is two ticks, and vanilla looks smooth because the block is drawn <em>between</em> the cells - which is
        // why the client is told where each moving block is coming from and slides it over exactly those two ticks.
        assertEquals(0.0F, StructureLiveView.slideProgress(100, 100, 0.0F), "a move that just started has not moved");
        assertEquals(0.5F, StructureLiveView.slideProgress(100, 100, 1.0F), "half way through the first tick");
        assertEquals(0.5F, StructureLiveView.slideProgress(100, 101, 0.0F), "and at the end of it");
        assertEquals(1.0F, StructureLiveView.slideProgress(100, 102, 0.0F), "two ticks later it has arrived");
        assertEquals(1.0F, StructureLiveView.slideProgress(100, 200, 0.0F), "and it stays arrived");
        assertEquals(0.0F, StructureLiveView.slideProgress(100, 99, 0.0F), "a move from the future has not started");

        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    int packed = StructureLiveView.packFrom(dx, dy, dz);
                    assertEquals(dx, StructureLiveView.fromX(packed), "x round trips: " + dx + "," + dy + "," + dz);
                    assertEquals(dy, StructureLiveView.fromY(packed), "y round trips: " + dx + "," + dy + "," + dz);
                    assertEquals(dz, StructureLiveView.fromZ(packed), "z round trips: " + dx + "," + dy + "," + dz);
                }
            }
        }
    }

    @Test
    @DisplayName("a whole picture reaches every cell of a structure larger than one packet")
    void aWholePictureReachesEveryCell() {
        // The sample is only recorded as sent once the whole picture has travelled: taking the first 512 cells of a
        // thousand and marking them sent left the cells above the cap never sent at all, and every later pass emptied
        // the client's view and left them out again.
        int cells = 1000;
        StructureLiveView.Resend pass = new StructureLiveView.Resend(cells);
        assertTrue(pass.first(), "a pass that has sent nothing is the one that begins a picture");

        boolean[] sent = new boolean[cells];
        int batches = 0;
        while (!pass.done()) {
            boolean begins = pass.first();
            int[] batch = pass.next();
            assertTrue(batch.length <= StructureLiveView.MAX_CHANGES_PER_PACKET, "no piece is over the cap");
            for (int index : batch) {
                assertTrue(index >= 0 && index < cells, "index in range: " + index);
                assertTrue(!sent[index], "cell " + index + " is sent twice in one pass");
                sent[index] = true;
            }
            assertEquals(batches == 0, begins, "only the first piece says that a picture begins");
            batches++;
            assertTrue(batches <= cells, "the pass has to end");
        }
        for (boolean one : sent) {
            assertTrue(one, "every cell of the structure is sent");
        }
        assertEquals(2, batches, "1000 cells is exactly two packets at this cap");
        assertArrayEquals(new int[0], pass.next(), "a finished pass has nothing left to send");
    }

    @Test
    @DisplayName("a placeholder stands in for the cell it replaced, but only for its grace")
    void aPlaceholderIsNeverNews() {
        // A moving-piston placeholder renders as nothing, so a packet that carries one opens a hole in the miniature
        // exactly where the piston is - and a piston's own cell holds a placeholder on every single retraction. What a
        // cell mid-move "holds" is what the move took out of it or is about to put into it, and that is what is reported.
        int placeholder = 42;
        int[] previous = {7, 1, 9};
        assertArrayEquals(new int[] {7, 1, 9},
                StructureLiveView.believe(new int[] {placeholder, placeholder, placeholder}, placeholder, previous),
                "every placeholder stands in for the value the cell had");
        assertArrayEquals(new int[] {7, 5, 9},
                StructureLiveView.believe(new int[] {placeholder, 5, 9}, placeholder, previous),
                "and a cell that is not one is reported as it is");
    }

    @Test
    @DisplayName("the stand-in cannot outlive the move: the next real value is reported at once")
    void aPlaceholderCannotHideARealChange() {
        // The one danger a stand-in has is hiding a change for ever. It cannot: it applies only while the world's own
        // cell holds a placeholder, so the first sample after the move finishes reports what the cell became - and
        // that sample is the diff's next one, whatever the value now is.
        int placeholder = 42;
        int[] midMove = StructureLiveView.believe(new int[] {placeholder}, placeholder, new int[] {7});
        assertArrayEquals(new int[] {7}, midMove, "mid-move reports what the cell replaced");
        int[] after = StructureLiveView.believe(new int[] {3}, placeholder, midMove);
        assertArrayEquals(new int[] {3}, after, "and the moment the move finishes the real value is sent");
        int[] empty = StructureLiveView.believe(new int[] {0}, placeholder, after);
        assertArrayEquals(new int[] {0}, empty, "including when the cell ends up empty");
    }

    @Test
    @DisplayName("a client with no picture yet is given the world, placeholder and all")
    void theFirstSampleHasNothingToKeep() {
        // There is no previous value to stand in with, so the world is reported as it is - and the client drops a
        // placeholder rather than drawing a hole (StructureLiveViews#apply).
        int[] first = StructureLiveView.believe(new int[] {42, 7}, 42, null);
        assertArrayEquals(new int[] {42, 7}, first, "with nothing sent yet, the world is the picture");
    }
}

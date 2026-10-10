package com.jiangyuefengyu.redstonecircuit.structure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Plot allocation, and the column arithmetic the load budget is counted in.
 *
 * <p>Two plots landing on top of each other would put two structures' blocks in one place - the defect
 * would look like "my machine ate someone else's machine", and it would be attributed to the redstone
 * rather than to the allocator. Hence an explicit no-overlap check rather than trusting the grid
 * arithmetic by eye.
 */
class PlotAllocatorTest {

    @Test
    @DisplayName("cells are handed out in order and never overlap")
    void cellsNeverOverlap() {
        PlotAllocator allocator = new PlotAllocator();
        List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            indices.add(allocator.allocate());
        }

        assertEquals(40, allocator.liveCount());
        for (int i = 0; i < indices.size(); i++) {
            for (int j = i + 1; j < indices.size(); j++) {
                int a = indices.get(i);
                int b = indices.get(j);
                int ax = PlotAllocator.originX(a);
                int az = PlotAllocator.originZ(a);
                int bx = PlotAllocator.originX(b);
                int bz = PlotAllocator.originZ(b);
                assertTrue(Math.abs(ax - bx) >= PlotAllocator.CELL_SIZE
                                || Math.abs(az - bz) >= PlotAllocator.CELL_SIZE,
                        "cells " + a + " and " + b + " would share blocks: ("
                                + ax + "," + az + ") vs (" + bx + "," + bz + ")");
            }
        }
    }

    @Test
    @DisplayName("every cell starts on a chunk boundary, so a ticket covers whole chunks")
    void everyCellIsChunkAligned() {
        for (int index = 0; index < 3 * PlotAllocator.CELLS_PER_ROW + 5; index++) {
            assertEquals(0, Math.floorMod(PlotAllocator.originX(index), PlotAllocator.CHUNK),
                    "cell " + index + " x");
            assertEquals(0, Math.floorMod(PlotAllocator.originZ(index), PlotAllocator.CHUNK),
                    "cell " + index + " z");
        }
        assertEquals(0, PlotAllocator.originX(0));
        assertEquals(0, PlotAllocator.originZ(0));
        assertEquals(PlotAllocator.CELL_SIZE, PlotAllocator.originX(1), "the next cell is a full cell away");
        assertEquals(0, PlotAllocator.originX(16), "the grid starts a new row after a whole row of cells");
        assertEquals(PlotAllocator.CELL_SIZE, PlotAllocator.originZ(16), "one cell further along z");
    }

    @Test
    @DisplayName("a freed cell is handed out again before the grid is extended")
    void freedCellsAreReused() {
        PlotAllocator allocator = new PlotAllocator();
        int first = allocator.allocate();
        int second = allocator.allocate();

        assertTrue(allocator.free(first), "freeing a live cell must work");
        assertFalse(allocator.isLive(first));
        assertEquals(1, allocator.liveCount());
        assertEquals(first, allocator.allocate(), "the freed cell comes back first");
        assertEquals(2, allocator.liveCount());
        assertNotEquals(first, second);
    }

    @Test
    @DisplayName("freeing a cell that is not live changes nothing")
    void freeingSomethingThatIsNotLiveIsHarmless() {
        PlotAllocator allocator = new PlotAllocator();
        allocator.allocate();

        assertFalse(allocator.free(7), "an index nobody owns is not a state change");
        assertFalse(allocator.free(-1));
        assertEquals(1, allocator.liveCount());
        assertFalse(allocator.isLive(7));
    }

    /**
     * The inverse of the grid, which the pocket's signal overlay depends on: {@code indexAt} turns "a redstone
     * query at this position" into "which plot's ledger answers this" without walking the live plots, so a wrong
     * answer would not crash - it would inject power into a neighbour's structure, or into nothing at all.
     */
    @Test
    @DisplayName("a position maps back to the cell it stands in, and nowhere else")
    void positionsMapBackToTheirCell() {
        for (int index = 0; index < 3 * PlotAllocator.CELLS_PER_ROW + 5; index++) {
            int originX = PlotAllocator.originX(index);
            int originZ = PlotAllocator.originZ(index);
            assertEquals(index, PlotAllocator.indexAt(originX, originZ), "first block of cell " + index);
            assertEquals(index, PlotAllocator.indexAt(originX + PlotAllocator.CELL_SIZE - 1,
                    originZ + PlotAllocator.CELL_SIZE - 1), "last block of cell " + index);
            assertEquals(index, PlotAllocator.indexAt(originX + 5, originZ + 5), "a block inside cell " + index);
        }
        assertEquals(-1, PlotAllocator.indexAt(-1, 0), "the shell west of cell 0 is not cell -1");
        assertEquals(-1, PlotAllocator.indexAt(0, -1), "the same below the grid");
        assertEquals(1, PlotAllocator.indexAt(PlotAllocator.CELL_SIZE, 0));
        assertEquals(PlotAllocator.CELLS_PER_ROW,
                PlotAllocator.indexAt(0, PlotAllocator.CELL_SIZE), "one cell along z is one row further");
    }

    @Test
    @DisplayName("the column count matches the design's table: 16 -> 4, 32 -> 9, 48 -> 16")
    void columnsMatchTheDesignTable() {
        assertEquals(4, PlotAllocator.columnsNeeded(16, true), "16³ plus a shell is 18³, inside 2x2 chunks");
        assertEquals(9, PlotAllocator.columnsNeeded(32, true), "32³ plus a shell is 34³, inside 3x3 chunks");
        assertEquals(16, PlotAllocator.columnsNeeded(48, true), "48³ plus a shell is 50³, inside 4x4 chunks");

        assertEquals(1, PlotAllocator.columnsNeeded(1, true), "even a single block with a shell is one column");
        assertEquals(1, PlotAllocator.columnsNeeded(14, false), "without a shell, 14 blocks still fit in one");
        assertEquals(1, PlotAllocator.columnsNeeded(16, false), "and so do exactly 16, sitting in one chunk");
        assertEquals(4, PlotAllocator.columnsNeeded(17, false), "17 blocks is the first size that needs four");
        assertEquals(9, PlotAllocator.columnsNeeded(48, false), "48³ without a shell is exactly 3x3 chunks");
    }
}

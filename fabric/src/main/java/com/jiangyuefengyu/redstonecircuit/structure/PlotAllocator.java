package com.jiangyuefengyu.redstonecircuit.structure;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Deque;
import java.util.List;

/**
 * Hands out non-overlapping cells in the pocket dimension - the "plot allocator".
 *
 * <p>A cell is {@value #CELL_SIZE}×{@value #CELL_SIZE} blocks: the largest legal selection (48) plus the context
 * shell on both sides is 50, which fits inside four chunks on each axis with room to spare, so every plot is
 * chunk-aligned and the indices of a {@value #CELLS_PER_ROW}-wide grid can never overlap.
 *
 * <p>Allocation ignores the structure's size - every cell fits every legal structure, so a size-dependent
 * allocator would only invent fragmentation. The size decides how many chunk columns the plot has to
 * <b>ticket</b>, which is {@link #columnsNeeded} and is the number the load budget is counted in (a 48³
 * structure is 16 columns, not "one structure"). Pure arithmetic: no level, no tickets, no blocks, and released
 * indices are reused before the grid extends, so a repeatedly filled and emptied pocket stays put.
 */
public final class PlotAllocator {

    public static final int CHUNK = 16;
    public static final int CELL_SIZE = 64;
    public static final int CELLS_PER_ROW = 16;

    private final BitSet live = new BitSet();
    private final Deque<Integer> recycled = new ArrayDeque<>();
    private int highWater;

    /** Claims a cell. The caller must {@link #free} it when the plot goes away. */
    public int allocate() {
        Integer reused = this.recycled.pollFirst();
        if (reused != null) {
            this.live.set(reused);
            return reused;
        }
        // Step over cells that are already taken: a restored save can claim cells this allocator never
        // handed out, and they must be skipped rather than handed out twice.
        while (this.live.get(this.highWater)) {
            this.highWater++;
        }
        int index = this.highWater++;
        this.live.set(index);
        return index;
    }

    /** Gives a cell back. False when it was not live, which is a caller bug and not a state change. */
    public boolean free(int index) {
        if (index < 0 || !this.live.get(index)) {
            return false;
        }
        this.live.clear(index);
        this.recycled.addFirst(index);
        return true;
    }

    /**
     * Marks a cell as already taken, without handing it out - for rebuilding the grid from a save, so the next
     * structure is not given a cell that already holds one.
     *
     * <p>The high-water mark is <b>not</b> moved: the cells below a reservation are still free space, and
     * {@link #allocate} steps over the taken ones as it walks up.
     */
    public boolean reserve(int index) {
        if (index < 0 || this.live.get(index)) {
            return false;
        }
        this.recycled.remove(index);
        this.live.set(index);
        return true;
    }

    public boolean isLive(int index) {
        return index >= 0 && this.live.get(index);
    }

    public int liveCount() {
        return this.live.cardinality();
    }

    /** The live indices, lowest first - for diagnostics and for saving the plot table. */
    public List<Integer> liveIndices() {
        List<Integer> out = new ArrayList<>(this.live.cardinality());
        for (int index = this.live.nextSetBit(0); index >= 0; index = this.live.nextSetBit(index + 1)) {
            out.add(index);
        }
        return out;
    }

    /** The corner a cell starts at, on the x axis. */
    public static int originX(int index) {
        return Math.floorMod(index, CELLS_PER_ROW) * CELL_SIZE;
    }

    /** The corner a cell starts at, on the z axis. */
    public static int originZ(int index) {
        return Math.floorDiv(index, CELLS_PER_ROW) * CELL_SIZE;
    }

    /**
     * The cell a position falls in - the inverse of {@link #originX} and {@link #originZ}, or -1 outside the grid.
     *
     * <p>Asked on the hottest path there is (a redstone signal query inside the pocket asking "which plot is this
     * cell in"), so it has to be arithmetic rather than a walk over the live plots. The grid covers non-negative
     * coordinates only, so a negative coordinate answers -1 rather than an index that would alias the first row.
     */
    public static int indexAt(int x, int z) {
        if (x < 0 || z < 0) {
            return -1;
        }
        return Math.floorDiv(z, CELL_SIZE) * CELLS_PER_ROW + Math.floorDiv(x, CELL_SIZE);
    }

    /**
     * The chunk columns a structure of this size occupies, one block of shell on every side included - the number
     * the load budget is counted in: 16 → 4 columns, 32 → 9, 48 → 16.
     */
    public static int columnsNeeded(int size, boolean shell) {
        int span = size + (shell ? 2 : 0);
        int side = (span + CHUNK - 1) / CHUNK;
        return side * side;
    }
}

package com.jiangyuefengyu.redstonecircuit.structure;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The bit-packed block indices.
 *
 * <p>This array is the payload's bulk (110,592 cells for the biggest allowed structure), and it is the
 * one place where a wrong shift produces a structure that loads "almost" correctly - the kind of defect
 * that only shows up as a single wrong block in a build nobody is looking at closely.
 */
class IndexArrayTest {

    private static int[] ramp(int count, int modulo) {
        int[] values = new int[count];
        for (int i = 0; i < count; i++) {
            values[i] = i % modulo;
        }
        return values;
    }

    @Test
    @DisplayName("a palette size decides the width the way the design's size table assumes")
    void bitsFollowThePaletteSize() {
        assertEquals(1, IndexArray.bitsFor(1), "one state still needs a bit to be addressable");
        assertEquals(1, IndexArray.bitsFor(2));
        assertEquals(2, IndexArray.bitsFor(3));
        assertEquals(2, IndexArray.bitsFor(4));
        assertEquals(4, IndexArray.bitsFor(16));
        assertEquals(5, IndexArray.bitsFor(17));
        assertEquals(8, IndexArray.bitsFor(256), "a vanilla-sized palette is one byte per cell");
        assertEquals(9, IndexArray.bitsFor(257));
    }

    @Test
    @DisplayName("the biggest allowed structure packs to the design's 110 KB")
    void theDesignSizeIsEightBitsPerCell() {
        int[] cells = ramp(110592, 200);
        IndexArray array = IndexArray.of(cells, 256);

        assertEquals(8, array.bitsPerIndex());
        assertEquals(110592, array.byteSize(), "48³ at 8 bits per cell is 110,592 bytes");
        assertArrayEquals(cells, array.toIntArray(), "and it still reads back cell for cell");
    }

    @Test
    @DisplayName("every width round-trips, including the ones that straddle a word boundary")
    void roundTripsAtEveryWidth() {
        int[] sizes = {1, 2, 3, 4, 5, 8, 16, 17, 255, 256, 257, 100000};
        for (int paletteSize : sizes) {
            int count = paletteSize > 1000 ? 5000 : 300;
            int[] cells = ramp(count, paletteSize);
            IndexArray array = IndexArray.of(cells, paletteSize);

            assertEquals(paletteSize <= 1 ? 1 : 32 - Integer.numberOfLeadingZeros(paletteSize - 1),
                    array.bitsPerIndex(), "width for a palette of " + paletteSize);
            assertArrayEquals(cells, array.toIntArray(),
                    "palette " + paletteSize + " must survive the packing");
            assertTrue(array.refersOnlyTo(paletteSize), "and must not claim states it does not have");
        }
    }

    @Test
    @DisplayName("a packed array compares by its contents, so payload round-trips can be asserted")
    void equalityIsByValue() {
        IndexArray one = IndexArray.of(ramp(500, 17), 17);
        IndexArray same = IndexArray.of(ramp(500, 17), 17);
        int[] different = ramp(500, 17);
        different[499] = 3;
        IndexArray other = IndexArray.of(different, 17);

        assertEquals(one, same, "identical contents must be equal, or nothing can be compared");
        assertEquals(one.hashCode(), same.hashCode());
        assertNotEquals(one, other, "one changed cell is a different array");
    }

    @Test
    @DisplayName("a cell that is not there reads as -1 instead of throwing")
    void outOfRangeCellsReadAsMinusOne() {
        IndexArray array = IndexArray.of(ramp(10, 4), 4);
        assertEquals(-1, array.get(-1));
        assertEquals(-1, array.get(10));
        assertEquals(1, array.get(1));
    }

    @Test
    @DisplayName("an index outside the palette is refused when packing, not discovered later")
    void anIndexOutsideThePaletteIsRefused() {
        int[] cells = new int[4];
        cells[2] = 7;
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> IndexArray.of(cells, 4));
        assertTrue(refused.getMessage().contains("outside a palette of 4"), refused.getMessage());
    }

    @Test
    @DisplayName("an empty array is legal and describes no cells")
    void emptyIsLegal() {
        IndexArray empty = IndexArray.empty();
        assertEquals(0, empty.count());
        assertEquals(0, empty.byteSize());
        assertArrayEquals(new int[0], empty.toIntArray());
        assertEquals(0, empty.words().length);
    }
}

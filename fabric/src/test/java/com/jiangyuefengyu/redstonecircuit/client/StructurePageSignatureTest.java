package com.jiangyuefengyu.redstonecircuit.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The fingerprint a screen page is compared by: two folds of the same rows have to be the same number, and any change a
 * player could make to a row has to be a different one.
 *
 * <p>It is the whole of "this page is out of date" - a fingerprint that cannot tell a renamed row from a moved one is a
 * page that stops refreshing, and one that differs while nothing changed is a page rebuilt on every tick, which is the
 * stutter a screen cannot afford.
 */
class StructurePageSignatureTest {

    /** Two rows of a key panel, as the page draws them: name, cell, strength. */
    private static long twoRows(String firstName, int strength) {
        long folded = StructurePageSignature.fold(StructurePageSignature.START, firstName, 1, 2, 3, strength);
        folded = StructurePageSignature.fold(folded, "second", 4, 5, 6, 7);
        return StructurePageSignature.of(2, folded);
    }

    @Test
    @DisplayName("the same rows give the same fingerprint, and the count is part of it")
    void theSameRowsAreTheSameFingerprint() {
        assertEquals(twoRows("first", 15), twoRows("first", 15), "the same page folded twice is one number");
        assertNotEquals(StructurePageSignature.of(2, twoRows("first", 15)),
                StructurePageSignature.of(3, twoRows("first", 15)),
                "and a page that lost a row is not the page that had it, whatever its remaining rows fold to");
    }

    @Test
    @DisplayName("a renamed row, a moved row, a re-powered row and a reordered list are four other fingerprints")
    void everyEditIsAnotherFingerprint() {
        long base = twoRows("first", 15);
        assertNotEquals(base, twoRows("renamed", 15), "a rename changes what the page shows");
        assertNotEquals(base, twoRows("first", 14), "and so does a strength");
        long moved = StructurePageSignature.of(2,
                StructurePageSignature.fold(StructurePageSignature.fold(StructurePageSignature.START,
                        "first", 1, 2, 4, 15), "second", 4, 5, 6, 7));
        assertNotEquals(base, moved, "a row that names another cell is another row");
        long swapped = StructurePageSignature.of(2,
                StructurePageSignature.fold(StructurePageSignature.fold(StructurePageSignature.START,
                        "second", 4, 5, 6, 7), "first", 1, 2, 3, 15));
        assertNotEquals(base, swapped,
                "and the order is part of it: every row of these tables is edited by its index, so two rows that traded "
                        + "places are a page whose buttons now edit the wrong ones");
        for (int axis = 0; axis < 3; axis++) {
            int[] cell = {1, 2, 3};
            cell[axis]++;
            long other = StructurePageSignature.of(2,
                    StructurePageSignature.fold(StructurePageSignature.fold(StructurePageSignature.START,
                            "first", cell[0], cell[1], cell[2], 15), "second", 4, 5, 6, 7));
            assertNotEquals(base, other, "a cell one block along axis " + axis + " is another cell");
        }
    }

    @Test
    @DisplayName("a row with no name is a value, not a hole")
    void anEmptyNameIsFoldedAsItsOwnValue() {
        long unnamed = StructurePageSignature.fold(StructurePageSignature.START, null, 0, 0, 0, 0);
        long empty = StructurePageSignature.fold(StructurePageSignature.START, "", 0, 0, 0, 0);
        assertEquals(empty, unnamed, "a null name is folded as the empty one, which is how a row with no name reads");
        assertNotEquals(unnamed, StructurePageSignature.fold(StructurePageSignature.START, "x", 0, 0, 0, 0),
                "and it is still distinguishable from a named row");
    }
}

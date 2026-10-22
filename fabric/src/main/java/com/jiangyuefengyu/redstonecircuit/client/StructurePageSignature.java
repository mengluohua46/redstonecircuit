package com.jiangyuefengyu.redstonecircuit.client;

import org.jetbrains.annotations.Nullable;

/**
 * A fingerprint of one page of the structure screen: what the page's list holds right now, squeezed into one
 * {@code long}.
 *
 * <p>It exists so that a screen showing live data can tell "the block has changed since this page was built" without
 * caching the block's data and without rebuilding the page to find out. One fold over a handful of rows per tick, and a
 * rebuild only when two of them differ.
 *
 * <p>What a row is folded by is what a player reads off it: its <b>name</b> (a face's name for the two tables), the
 * <b>cell</b> it names, and its <b>strength</b>. So a renamed row, a moved row and a re-powered row are three different
 * fingerprints, and so is the same rows in another order - which matters, because every row of these tables is edited by
 * its index.
 *
 * <p>Pure: two integers and a string in, a number out, no screen and no entity.
 */
final class StructurePageSignature {

    /** What a page with no rows starts from. */
    static final long START = 0x9E3779B97F4A7C15L;

    private StructurePageSignature() {
    }

    /**
     * Folds one row in: what it is called, the cell it names and how hard it pushes.
     *
     * <p>A null name is folded as an empty one, because "no name" is a value these rows can have and it has to be
     * distinguishable from every name that could be typed.
     */
    static long fold(long signature, @Nullable String name, int x, int y, int z, int strength) {
        long folded = mix(signature, name == null ? 0 : name.hashCode());
        folded = mix(folded, x);
        folded = mix(folded, y);
        folded = mix(folded, z);
        return mix(folded, strength);
    }

    /**
     * Seals a page's fingerprint, <b>with its row count</b>: a page that lost a row can then never look like the page
     * that had it, whatever the remaining rows fold to.
     */
    static long of(int rows, long folded) {
        return mix(folded, rows);
    }

    /** One step of the avalanche: a small change anywhere in a row has to reach every bit of the answer. */
    private static long mix(long signature, int value) {
        long mixed = signature ^ (value * 0x9E3779B97F4A7C15L);
        mixed ^= mixed >>> 33;
        mixed *= 0xFF51AFD7ED558CCDL;
        mixed ^= mixed >>> 33;
        return mixed * 0xC4CEB9FE1A85EC53L;
    }
}

package com.jiangyuefengyu.redstonecircuit.logic;

/**
 * 方块放置器's rule, as arithmetic: which slot, and whether this activation does anything at all.
 *
 * <p>Both are pure decisions over questions somebody else answers ("is anything in the slots", "is the cell in front
 * free"), so they live here rather than inside a block that needs a level to be tested at all.
 */
public final class PlacerRule {

    /** What one activation does. */
    public enum Verdict {
        /** Put one block down in the cell in front - the caller's business is only <em>which</em> cell. */
        PLACE,
        /** Nothing in the slots: 没有方块 → 无反应. */
        NOTHING,
        /** The cell in front is not free: 面前有方块遮挡 → 无反应. */
        BLOCKED
    }

    private PlacerRule() {
    }

    /**
     * The first slot, in index order, that holds something; {@code -1} when every slot is empty. Vanilla's dispenser
     * picks a <b>random</b> filled slot ({@code DispenserBlockEntity#getRandomSlot}); this device is predictable.
     */
    public static int firstFilled(boolean[] filled) {
        for (int slot = 0; slot < filled.length; slot++) {
            if (filled[slot]) {
                return slot;
            }
        }
        return -1;
    }

    /**
     * An activation places a block only when there is one to place <b>and</b> the cell in front can take it; either
     * half missing means the machine does nothing at all - and, in particular, consumes nothing.
     */
    public static Verdict decide(boolean hasSomethingToPlace, boolean frontIsFree) {
        if (!hasSomethingToPlace) {
            return Verdict.NOTHING;
        }
        return frontIsFree ? Verdict.PLACE : Verdict.BLOCKED;
    }
}

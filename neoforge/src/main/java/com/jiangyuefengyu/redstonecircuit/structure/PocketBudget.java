package com.jiangyuefengyu.redstonecircuit.structure;

/**
 * The two gates that decide whether a pocket may tick, counted the way the design demands.
 *
 * <p>"Max loaded pockets" alone is not a budget: a 48³ structure occupies 4×4 = 16 chunk columns, so
 * sixteen of them are 256 columns - which is why the design has <b>both</b> numbers
 * ({@code structureMaxLoadedColumns} and {@code structureMaxLoadedPockets}) and why the first is
 * counted in columns rather than in structures. Counting structures would have let sixteen 48³ pockets
 * through and called it sixteen, when it is a server's whole simulation budget.
 *
 * <p>A refusal is a value, not an exception: the design asks for the player to be told ("this structure
 * is asleep, the budget is full") rather than for the operation to fail invisibly.
 */
public final class PocketBudget {

    /** Whether a claim fitted, and when it did not, why - in words a player can be shown. */
    public record Verdict(boolean allowed, String reason) {

        public static Verdict yes() {
            return new Verdict(true, "");
        }

        public static Verdict no(String reason) {
            return new Verdict(false, reason);
        }
    }

    private final int maxColumns;
    private final int maxPockets;
    private int usedColumns;
    private int usedPockets;

    public PocketBudget(int maxColumns, int maxPockets) {
        this.maxColumns = Math.max(0, maxColumns);
        this.maxPockets = Math.max(0, maxPockets);
    }

    /**
     * Asks for one pocket occupying {@code columns} chunk columns.
     *
     * <p>On {@code allowed} the budget has already been charged; on a refusal nothing was charged, so a
     * caller can retry the same structure later without cleaning up.
     */
    public Verdict tryClaim(int columns) {
        if (columns <= 0) {
            return Verdict.no("a plot that occupies no chunk columns cannot be made to tick");
        }
        if (this.usedPockets + 1 > this.maxPockets) {
            return Verdict.no("already " + this.usedPockets + " pockets ticking, the limit is "
                    + this.maxPockets + " (structureMaxLoadedPockets)");
        }
        if (this.usedColumns + columns > this.maxColumns) {
            return Verdict.no("this structure needs " + columns + " more chunk columns, only "
                    + (this.maxColumns - this.usedColumns) + " of " + this.maxColumns
                    + " are left (structureMaxLoadedColumns)");
        }
        this.usedPockets++;
        this.usedColumns += columns;
        return Verdict.yes();
    }

    /** Gives a claimed pocket back. */
    public void release(int columns) {
        if (this.usedPockets > 0) {
            this.usedPockets--;
        }
        this.usedColumns = Math.max(0, this.usedColumns - Math.max(0, columns));
    }

    public int usedColumns() {
        return this.usedColumns;
    }

    public int usedPockets() {
        return this.usedPockets;
    }

    public int maxColumns() {
        return this.maxColumns;
    }

    public int maxPockets() {
        return this.maxPockets;
    }
}

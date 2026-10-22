package com.jiangyuefengyu.redstonecircuit.structure;

/**
 * <b>How the panel pages: six columns by six rows, filled in the order the entries were added.</b>
 *
 * <p>The arithmetic lives here rather than in the screen because the server also has to speak about pages - an
 * index a client sends is an index into the whole panel, and "which page is this" has to have one answer on both
 * sides. No game class is imported: a page is a division, and a division needs no registry.
 */
public final class PanelGrid {

    public static final int COLUMNS = 6;
    public static final int ROWS = 6;
    public static final int PER_PAGE = COLUMNS * ROWS;

    /**
     * How many pages a panel of this size needs. An empty panel is still one page: a screen with no page has
     * nothing to draw, and "page 1 of 0" is not a thing a player can type into.
     */
    public static int pages(int total) {
        int entries = Math.max(0, total);
        return Math.max(1, (entries + PER_PAGE - 1) / PER_PAGE);
    }

    /** The index of the first entry on a page, which is also where a page that does not exist would start. */
    public static int firstIndex(int page) {
        return page <= 0 ? 0 : page * PER_PAGE;
    }

    /**
     * How many entries a page actually holds. The last page is the one that is short, and a page past the end holds
     * nothing rather than a negative count - a client may ask for a page that the panel no longer has.
     */
    public static int count(int total, int page) {
        int entries = Math.max(0, total);
        int first = firstIndex(page);
        return Math.max(0, Math.min(PER_PAGE, entries - first));
    }

    /** The page an entry's index falls on. */
    public static int pageOf(int index) {
        return index / PER_PAGE;
    }

    private PanelGrid() {
    }
}

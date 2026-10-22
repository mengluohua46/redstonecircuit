package com.jiangyuefengyu.redstonecircuit.structure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The panel's paging arithmetic: 6x6 buttons a page, filled in the order the entries were added.
 *
 * <p>The edges are the whole point of testing a division: an empty panel, the page that is exactly full, and the
 * one entry that starts the next page. A screen that asks "how many pages" and gets 0 draws nothing, and a page
 * that reports -1 entries is a loop that never ends.
 */
class PanelGridTest {

    @Test
    @DisplayName("the grid is six by six, and a page is the whole of it")
    void theGridIsSixBySix() {
        assertEquals(6, PanelGrid.COLUMNS);
        assertEquals(6, PanelGrid.ROWS);
        assertEquals(36, PanelGrid.PER_PAGE);
        assertEquals(PanelGrid.COLUMNS * PanelGrid.ROWS, PanelGrid.PER_PAGE);
    }

    @Test
    @DisplayName("an empty panel is still one page, and so is one that does not exist")
    void anEmptyPanelIsOnePage() {
        assertEquals(1, PanelGrid.pages(0), "a screen with no page has nothing to draw");
        assertEquals(1, PanelGrid.pages(-3), "a negative count is a count of nothing");
    }

    @Test
    @DisplayName("the last page is the one that is short: 35 fits, 36 fits, 37 needs two")
    void thePageCountFollowsTheCount() {
        assertEquals(1, PanelGrid.pages(1));
        assertEquals(1, PanelGrid.pages(35));
        assertEquals(1, PanelGrid.pages(36), "a page that is exactly full is one page, not two");
        assertEquals(2, PanelGrid.pages(37), "the entry after a full page starts a new one");
        assertEquals(2, PanelGrid.pages(72));
        assertEquals(3, PanelGrid.pages(73));
    }

    @Test
    @DisplayName("the first page starts at index zero, and the pages after it start a whole grid apart")
    void theFirstIndexOfAPage() {
        assertEquals(0, PanelGrid.firstIndex(0), "a page number is a zero-based index, so the first page is 0");
        assertEquals(36, PanelGrid.firstIndex(1), "the second page starts after a whole grid");
        assertEquals(72, PanelGrid.firstIndex(2));
        assertEquals(108, PanelGrid.firstIndex(3));
        assertEquals(0, PanelGrid.firstIndex(-2), "there is no page before the first, so it starts at the first");
    }

    @Test
    @DisplayName("a page holds a full grid, except the last one, and a page past the end holds nothing")
    void theCountOnAPage() {
        assertEquals(36, PanelGrid.count(37, 0), "a full page");
        assertEquals(1, PanelGrid.count(37, 1), "and the one entry that did not fit");
        assertEquals(0, PanelGrid.count(37, 2), "nothing is not a negative count");
        assertEquals(0, PanelGrid.count(0, 0), "an empty panel shows an empty grid");
        assertEquals(35, PanelGrid.count(35, 0));
        assertEquals(36, PanelGrid.count(36, 0));
        assertEquals(6, PanelGrid.count(42, 1));
        assertEquals(0, PanelGrid.count(-5, 0));
        assertEquals(36, PanelGrid.count(40, -1), "a page before the first reads as the first");
    }

    @Test
    @DisplayName("an entry's page is its index divided by the page size")
    void thePageOfAnIndex() {
        assertEquals(0, PanelGrid.pageOf(0));
        assertEquals(0, PanelGrid.pageOf(35));
        assertEquals(1, PanelGrid.pageOf(36), "the 37th entry is the first of page two");
        assertEquals(1, PanelGrid.pageOf(71));
        assertEquals(2, PanelGrid.pageOf(72));
    }

    @Test
    @DisplayName("every entry of a panel is on exactly one page, and its pages cover it")
    void everyEntryHasExactlyOnePage() {
        int total = 37;
        assertEquals(PanelGrid.pages(total), 2);
        for (int index = 0; index < total; index++) {
            int page = PanelGrid.pageOf(index);
            int first = PanelGrid.firstIndex(page);
            assertTrue(first <= index && index < first + PanelGrid.count(total, page),
                    "entry " + index + " must lie on the page it reports, page " + page);
        }
    }
}

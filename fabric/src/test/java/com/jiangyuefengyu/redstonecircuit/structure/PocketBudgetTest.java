package com.jiangyuefengyu.redstonecircuit.structure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The two load gates.
 *
 * <p>The point of the columns gate is that "sixteen structures" is not a budget: sixteen 48³ pockets are
 * 256 chunk columns, i.e. every column the design allows. Both halves of that sentence are asserted here,
 * because a budget that only counts structures is exactly the mistake the design warns about.
 */
class PocketBudgetTest {

    @Test
    @DisplayName("sixteen big pockets fill the column budget, whatever the pocket count allows")
    void columnsAreTheRealLimit() {
        PocketBudget budget = new PocketBudget(256, 64);
        int columns = PlotAllocator.columnsNeeded(48, true);

        for (int i = 0; i < 16; i++) {
            assertTrue(budget.tryClaim(columns).allowed(), "48³ number " + (i + 1) + " must fit");
        }
        assertEquals(256, budget.usedColumns());
        assertEquals(16, budget.usedPockets());

        PocketBudget.Verdict refused = budget.tryClaim(columns);
        assertFalse(refused.allowed(), "the seventeenth 48³ structure is 272 columns");
        assertTrue(refused.reason().contains("structureMaxLoadedColumns"), refused.reason());
        assertEquals(16, budget.usedPockets(), "a refusal must not charge anything");
        assertEquals(256, budget.usedColumns());
    }

    @Test
    @DisplayName("the pocket count is a gate of its own, and it is checked first")
    void pocketCountIsItsOwnGate() {
        PocketBudget budget = new PocketBudget(4096, 2);

        assertTrue(budget.tryClaim(1).allowed());
        assertTrue(budget.tryClaim(1).allowed());
        PocketBudget.Verdict refused = budget.tryClaim(1);
        assertFalse(refused.allowed(), "two pockets are the limit here");
        assertTrue(refused.reason().contains("structureMaxLoadedPockets"), refused.reason());
    }

    @Test
    @DisplayName("releasing a pocket gives both counters back")
    void releaseRestoresTheBudget() {
        PocketBudget budget = new PocketBudget(16, 4);
        int columns = PlotAllocator.columnsNeeded(48, true);

        assertTrue(budget.tryClaim(columns).allowed());
        assertFalse(budget.tryClaim(columns).allowed(), "the second one does not fit in 16 columns");

        budget.release(columns);
        assertEquals(0, budget.usedColumns());
        assertEquals(0, budget.usedPockets());
        assertTrue(budget.tryClaim(columns).allowed(), "and after releasing, it fits again");
    }

    @Test
    @DisplayName("a plot that would tick no chunks is refused rather than counted")
    void aPlotWithNoColumnsIsRefused() {
        PocketBudget budget = new PocketBudget(256, 16);
        assertFalse(budget.tryClaim(0).allowed());
        assertEquals(0, budget.usedPockets());
    }

    @Test
    @DisplayName("releasing more than was claimed never goes negative")
    void releaseCannotGoNegative() {
        PocketBudget budget = new PocketBudget(256, 16);
        budget.release(64);
        assertEquals(0, budget.usedColumns());
        assertEquals(0, budget.usedPockets());
    }
}

package com.jiangyuefengyu.redstonecircuit.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The cascade's backoff as arithmetic, with no level and no config file in sight.
 *
 * <p>The rule it pins is the one that keeps the cascade from being a tax on a machine that cannot converge: a
 * tick that reached its fixed point passes the whole limit on, and a tick whose own notifications came back with
 * more work halves what it was allowed. Rounded down, never below one, and never above the limit - so a genuine
 * oscillator is back at the cost it had before the cascade within a tick or two, while a machine that settles
 * keeps the whole budget for ever.
 *
 * <p>{@code previous} is the budget the last tick <em>ran with</em>, not the limit: halving the limit every time
 * would be a constant, not a backoff.
 */
class CascadeBudgetTest {

    @Test
    @DisplayName("a tick that converged gets the whole limit, whatever it was allowed before")
    void convergedGetsTheWholeLimit() {
        assertEquals(8, InnerRedstoneNetwork.budgetAfter(true, 8, 8), "nothing was lost, so nothing is given back slowly");
        assertEquals(8, InnerRedstoneNetwork.budgetAfter(true, 1, 8),
                "a dimension that backed off while it oscillated recovers the moment it converges");
        assertEquals(32, InnerRedstoneNetwork.budgetAfter(true, 1, 32), "and the recovery is to the limit itself");
    }

    @Test
    @DisplayName("a tick that did not converge gets half of the last budget, rounded down")
    void aTickThatDidNotConvergeHalves() {
        assertEquals(4, InnerRedstoneNetwork.budgetAfter(false, 8, 8));
        assertEquals(3, InnerRedstoneNetwork.budgetAfter(false, 7, 8), "7 / 2 rounds down");
        assertEquals(2, InnerRedstoneNetwork.budgetAfter(false, 4, 8));
        assertEquals(1, InnerRedstoneNetwork.budgetAfter(false, 3, 8), "3 / 2 rounds down to one as well");
    }

    @Test
    @DisplayName("halving stops at one, so the backoff never turns the cascade off behind the config's back")
    void halvingStopsAtOne() {
        assertEquals(1, InnerRedstoneNetwork.budgetAfter(false, 1, 8));
        assertEquals(1, InnerRedstoneNetwork.budgetAfter(false, 2, 8));
        assertEquals(1, InnerRedstoneNetwork.budgetAfter(false, 0, 8), "a budget that was never recorded is no budget");
    }

    @Test
    @DisplayName("a previous budget above the limit is clamped to the limit, halved or not")
    void aPreviousBudgetAboveTheLimitIsClamped() {
        assertEquals(8, InnerRedstoneNetwork.budgetAfter(true, 40, 8));
        assertEquals(8, InnerRedstoneNetwork.budgetAfter(false, 40, 8),
                "20 would still be above the limit, so the answer is the limit rather than the half");
        assertEquals(2, InnerRedstoneNetwork.budgetAfter(false, 5, 2),
                "and the limit caps the halved answer, not only the full one");
    }

    @Test
    @DisplayName("a limit of one is always one, converged or not - that is 'the cascade is off'")
    void aLimitOfOneIsAlwaysOne() {
        assertEquals(1, InnerRedstoneNetwork.budgetAfter(true, 8, 1));
        assertEquals(1, InnerRedstoneNetwork.budgetAfter(false, 8, 1));
        assertEquals(1, InnerRedstoneNetwork.budgetAfter(false, 1, 1));
    }

    @Test
    @DisplayName("the limit is clamped to the documented 1..32 range, whatever a caller passes")
    void theLimitIsClampedToItsRange() {
        assertEquals(1, InnerRedstoneNetwork.budgetAfter(true, 8, 0), "zero is not a budget: one pass is the floor");
        assertEquals(1, InnerRedstoneNetwork.budgetAfter(true, 8, -5));
        assertEquals(InnerRedstoneNetwork.MAX_CASCADE_LIMIT,
                InnerRedstoneNetwork.budgetAfter(true, 1, 1000), "and the ceiling is the config range's");
        assertEquals(InnerRedstoneNetwork.MAX_CASCADE_LIMIT,
                InnerRedstoneNetwork.budgetAfter(false, 1000, 1000), "which the halved answer is capped by too");
    }
}

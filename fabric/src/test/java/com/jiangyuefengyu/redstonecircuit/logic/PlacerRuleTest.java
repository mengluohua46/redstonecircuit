package com.jiangyuefengyu.redstonecircuit.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.jiangyuefengyu.redstonecircuit.logic.PlacerRule.Verdict;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 方块放置器's two decisions, without a world.
 *
 * <p>The rule is three lines each, and the only way it can go wrong is by being <em>somewhere else</em> - a loop
 * written twice, an order that depends on which branch is taken first. Both orderings are pinned here:
 * nothing-to-place wins over a blocked front, and a later slot never wins over an earlier one.
 */
class PlacerRuleTest {

    @Test
    @DisplayName("从第一格开始按顺序: the lowest filled slot, whatever is in the others")
    void theLowestFilledSlotWins() {
        assertEquals(-1, PlacerRule.firstFilled(new boolean[] { false, false, false, false, false, false, false, false, false }),
                "an empty box has nothing to place");
        assertEquals(0, PlacerRule.firstFilled(new boolean[] { true, true, true, true, true, true, true, true, true }),
                "a full box starts at the first slot");
        assertEquals(3, PlacerRule.firstFilled(new boolean[] { false, false, false, true, false, false, false, false, true }),
                "and a later slot is not reached while an earlier one holds something - the one rule that differs "
                        + "from the dispenser, which picks a filled slot at random");
    }

    @Test
    @DisplayName("没有方块 / 面前被挡 → 无反应, and nothing is consumed either way")
    void neitherHalfOfTheRulePlacesAnything() {
        assertEquals(Verdict.PLACE, PlacerRule.decide(true, true), "a block in the box and a free cell in front: place");
        assertEquals(Verdict.BLOCKED, PlacerRule.decide(true, false),
                "the cell in front is not free: 面前有方块遮挡则无反应");
        assertEquals(Verdict.NOTHING, PlacerRule.decide(false, true),
                "nothing in the slots: 没有方块则无反应");
    }

    @Test
    @DisplayName("an empty box is reported as empty even when the front is taken")
    void nothingToPlaceIsDecidedBeforeTheFrontIsLookedAt() {
        assertEquals(Verdict.NOTHING, PlacerRule.decide(false, false),
                "both halves are missing, and the machine says the first one - which is also the order the "
                        + "diagnostics print them in, so the line a player reads names the real cause");
    }
}

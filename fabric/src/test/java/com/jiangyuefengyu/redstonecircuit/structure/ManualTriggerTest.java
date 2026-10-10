package com.jiangyuefengyu.redstonecircuit.structure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The rules a manual press is checked against.
 *
 * <p>Four refusals and one permission, all arithmetic: a structure has to be in manual mode, the entry has to
 * exist, the player has to be near enough, and they have to not be pressing again too soon. Each is pinned
 * here, one test per rule, because the alternative - a rule that only exists inside a packet handler - is a
 * rule nobody checks until a player finds a way around it.
 */
class ManualTriggerTest {

    private static final double RANGE = 8.0;
    private static final int COOLDOWN = 2;

    private static ManualTrigger.Verdict check(IoMode ioMode, int entryCount, int entry, double distance,
                                               long lastPress, long now) {
        return ManualTrigger.check(ioMode, entryCount, entry, distance, RANGE, lastPress, now, COOLDOWN);
    }

    @Test
    @DisplayName("a press from close by, on an entry that exists, in manual mode, is allowed")
    void aGoodPressIsAllowed() {
        ManualTrigger.Verdict verdict = check(IoMode.MANUAL, 3, 1, 4.0, -1, 100);

        assertTrue(verdict.allowed(), verdict.refusal());
        assertEquals("", verdict.refusal(), "an allowed press has nothing to explain");
    }

    @Test
    @DisplayName("a structure in activation-face mode has no keys to press")
    void theWrongModeIsRefused() {
        ManualTrigger.Verdict verdict = check(IoMode.ACTIVATION_FACES, 3, 1, 1.0, -1, 100);

        assertFalse(verdict.allowed());
        assertTrue(verdict.refusal().contains("activation-face"), verdict.refusal());
    }

    @Test
    @DisplayName("an entry the panel does not have is refused, and the reason says how many there are")
    void aNamelessEntryIsRefused() {
        assertFalse(check(IoMode.MANUAL, 3, 3, 1.0, -1, 100).allowed(), "index 3 of a 3-entry panel");
        assertFalse(check(IoMode.MANUAL, 3, -1, 1.0, -1, 100).allowed(), "and a negative index");
        assertTrue(check(IoMode.MANUAL, 3, 3, 1.0, -1, 100).refusal().contains("it has 3"),
                "the message has to say how many there are, or a player cannot tell what went wrong");
    }

    @Test
    @DisplayName("pressing from further away than the range is refused, and the edge itself is allowed")
    void theDistanceRuleHasAnEdge() {
        assertFalse(check(IoMode.MANUAL, 3, 0, 8.01, -1, 100).allowed(), "just past the limit");
        assertTrue(check(IoMode.MANUAL, 3, 0, 8.0, -1, 100).allowed(), "and exactly at it is inside it");
        assertFalse(check(IoMode.MANUAL, 3, 0, 40.0, -1, 100).allowed());
    }

    @Test
    @DisplayName("a player whose position is not a number is refused rather than trusted")
    void aBrokenPositionIsRefused() {
        assertFalse(check(IoMode.MANUAL, 3, 0, Double.NaN, -1, 100).allowed(),
                "NaN must not pass a distance check - comparing it the other way round would let it through");
    }

    @Test
    @DisplayName("pressing again inside the cooldown is refused, and the tick the cooldown ends is allowed")
    void theCooldownHasAnEdge() {
        assertFalse(check(IoMode.MANUAL, 3, 0, 1.0, 100, 101).allowed(), "one tick after the last press");
        assertTrue(check(IoMode.MANUAL, 3, 0, 1.0, 100, 102).allowed(), "two ticks after it is fine");
        assertTrue(check(IoMode.MANUAL, 3, 0, 1.0, -1, 102).allowed(),
                "and a player who has never pressed is not rate limited by a press that never happened");
    }

    @Test
    @DisplayName("the refusal names the number the player would have to change")
    void refusalsAreSentences() {
        assertTrue(check(IoMode.MANUAL, 3, 0, 20.0, -1, 100).refusal().contains("8.0"),
                check(IoMode.MANUAL, 3, 0, 20.0, -1, 100).refusal());
        assertTrue(check(IoMode.MANUAL, 3, 0, 1.0, 100, 101).refusal().contains("cooldown is 2"),
                check(IoMode.MANUAL, 3, 0, 1.0, 100, 101).refusal());
    }
}

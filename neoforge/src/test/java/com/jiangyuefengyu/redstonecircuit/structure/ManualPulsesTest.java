package com.jiangyuefengyu.redstonecircuit.structure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The manual pulses: what a key press turns into, and how it ends.
 *
 * <p>The rule that matters is the one the design does not spell out: a press is a <b>pulse</b>, not a hold, so
 * there is no release to lose. Everything else here is bookkeeping around that - a pulse that has not run out
 * is still pushing, one that has run out is a change the pocket has to hear about, and pressing again restarts
 * the clock. A hold would need a release packet, and a client that vanished mid-key would leave a structure
 * pushing with nobody at the controls; a pulse cannot.
 */
class ManualPulsesTest {

    @Test
    @DisplayName("a press pushes until its pulse runs out, and then it is a change")
    void aPressEndsByItself() {
        ManualPulses pulses = new ManualPulses();
        assertTrue(pulses.isEmpty(), "nothing is pressed before anything is pressed");

        pulses.press(2, 100, 10);
        assertEquals(java.util.Set.of(2), pulses.pressed());
        assertFalse(pulses.expire(105), "half way through the pulse it is still running");
        assertEquals(java.util.Set.of(2), pulses.pressed());

        assertTrue(pulses.expire(110), "at the end it is over, and that is a change");
        assertTrue(pulses.isEmpty());
        assertTrue(pulses.pressed().isEmpty());
    }

    @Test
    @DisplayName("pressing the same key again restarts the pulse from now")
    void aSecondPressRestartsThePulse() {
        ManualPulses pulses = new ManualPulses();
        pulses.press(0, 100, 10);
        pulses.press(0, 108, 10);

        assertFalse(pulses.expire(110), "the second press moved the end to 118");
        assertTrue(pulses.expire(118));
    }

    @Test
    @DisplayName("two keys can be held at once, and each ends on its own")
    void twoPulsesEndSeparately() {
        ManualPulses pulses = new ManualPulses();
        pulses.press(0, 100, 5);
        pulses.press(1, 100, 20);

        assertTrue(pulses.expire(105), "the first pulse ends");
        assertEquals(java.util.Set.of(1), pulses.pressed(), "and the second is untouched");
        assertTrue(pulses.expire(120));
        assertTrue(pulses.isEmpty());
    }

    @Test
    @DisplayName("a pulse of nothing is not a state change, and a nameless entry is ignored")
    void nothingToExpireIsNotAChange() {
        ManualPulses pulses = new ManualPulses();

        assertFalse(pulses.expire(1000), "there is nothing to end, so nothing changed");
        pulses.press(-1, 100, 10);
        assertTrue(pulses.isEmpty(), "entry -1 is not an entry");
    }

    @Test
    @DisplayName("a pulse is at least one tick, however it is configured")
    void aPulseAlwaysLastsATick() {
        ManualPulses pulses = new ManualPulses();
        pulses.press(0, 100, 0);

        assertFalse(pulses.expire(100), "a zero-length pulse would be no press at all");
        assertTrue(pulses.expire(101));
    }

    @Test
    @DisplayName("clear() forgets every pulse, as swapping the structure does")
    void clearForgetsEveryPulse() {
        ManualPulses pulses = new ManualPulses();
        pulses.press(0, 100, 10);
        pulses.press(1, 100, 10);

        pulses.clear();

        assertTrue(pulses.isEmpty());
        assertEquals(0, pulses.size());
    }
}

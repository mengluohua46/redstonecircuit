package com.jiangyuefengyu.redstonecircuit.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>The throttle arithmetic, asserted as a boolean sequence rather than as a count of lines.</b>
 *
 * <h2>Why the previous round's numbers were all off by one</h2>
 * Its tests drove a loop and counted how many <em>lines</em> came out. That number is a property of the
 * loop - of where it starts, where it ends, and whether the tick the line was written at is inside the
 * range - and every one of those three was got wrong once: ten windows of a changing line "should" be
 * ten lines and produced eleven, and an unchanged settlement "should" be one visit in 2,400 frames and
 * produced two. The arithmetic was never stated anywhere, so there was nothing to check it against.
 *
 * <p>Here the rules are functions from a clock reading to a boolean, and what is asserted is the
 * <b>sequence of those booleans</b> over a stated range:
 *
 * <ul>
 *   <li>a window opens when {@code now - last >= window}, and the test pins that choice at its boundary -
 *       false at {@code last + window - 1}, true at {@code last + window} - because "is it
 *       {@code >=} or {@code >}" is exactly the kind of thing that used to be assumed rather than
 *       stated;</li>
 *   <li>the first call of a session is always due, and the test pins that too, so "one line per window"
 *       cannot be read as "and none at all on the first frame";</li>
 *   <li>two minutes of an unchanged picture produces <b>exactly one</b> due answer,
 *       {@code false} on every tick from 1 to 2399 and {@code true} at 2400.</li>
 * </ul>
 *
 * <p>Nothing here sleeps, runs a game, or captures a log: every input is a {@code long} and every output
 * is a {@code boolean}.
 */
class CarrierSightThrottleTest {

    /**
     * The convention, at both of its boundaries.
     *
     * <p>A window of 100 ticks opened at tick 0 is false at 1..99 and true at 100 - and it is true
     * because {@code now - last >= window}, not because {@code now % window == 0}. The modulo reading is
     * deliberately not the one taken: it needs every caller to be aligned to the same phase, it makes
     * tick 0 both "the first call" and "the first window" - two rules for one instant - and it would make
     * a client that starts at tick 17 open its first window at 100 rather than 117.
     */
    @Test
    @DisplayName("a window is 100 ticks wide, closed on the left and open at exactly 100")
    void theWindowBoundaryIsStated() {
        assertTrue(CarrierSightThrottle.windowExpired(0L, Long.MIN_VALUE, 100),
                "a window that was never opened is open now");
        for (long tick = 1L; tick < 100L; tick++) {
            assertFalse(CarrierSightThrottle.windowExpired(tick, 0L, 100),
                    "the window is not open at tick " + tick);
        }
        assertTrue(CarrierSightThrottle.windowExpired(100L, 0L, 100),
                "and it is open at exactly window ticks");
        assertFalse(CarrierSightThrottle.windowExpired(105L, 100L, 100),
                "105 - 100 = 5, so the next window has not opened");
        assertTrue(CarrierSightThrottle.windowExpired(200L, 100L, 100),
                "and it has at 200");
    }

    /**
     * <b>Ten windows of a changing line are ten lines, and their boundaries are named.</b>
     *
     * <p>Written as an explicit running {@code last}: the line is due at the start of each window and
     * only then, and the sequence is driven for exactly 1,000 ticks so the answer is a property of the
     * rule rather than of a loop bound. The old test's "ten windows produced eleven lines" came from
     * counting the line written at tick 0 as being inside the loop's range as well; here the written
     * ticks are listed and asserted, so there is nothing to be off by one about.
     */
    @Test
    @DisplayName("a changing line is due once per window: 0, 100, 200, ... 900 in 1,000 ticks")
    void tenWindowsAreTenDueAnswers() {
        final int window = 100;
        long last = Long.MIN_VALUE;
        StringBuilder writtenAt = new StringBuilder();
        int due = 0;
        for (long tick = 0L; tick < 1000L; tick++) {
            if (CarrierSightThrottle.sightDue(tick, last, window)) {
                due++;
                last = tick;
                if (writtenAt.length() > 0) {
                    writtenAt.append(", ");
                }
                writtenAt.append(tick);
            }
        }
        assertEquals(10, due, "a thousand ticks of a five-second window is ten lines: " + writtenAt);
        assertEquals("0, 100, 200, 300, 400, 500, 600, 700, 800, 900", writtenAt.toString(),
                "and they are the ten window starts, listed");
    }

    /**
     * <b>The headline bound: two minutes of an unchanged picture is one due answer.</b>
     *
     * <p>{@code false} on every tick from 1 to 2,399 and {@code true} at 2,400 - asserted tick by tick,
     * because that is the claim. This is the assertion the previous round failed: it counted 2,399
     * frames and expected the heartbeat to be outside them, then found two visits inside 2,400.
     */
    @Test
    @DisplayName("an unchanged picture is due for the first time at exactly 2400 ticks")
    void anUnchangedPictureIsDueAtTheHeartbeat() {
        final int window = 100;
        final int heartbeat = 2400;
        // The first frame of a session reports itself; it is the write the "last" readings start from.
        assertTrue(CarrierSightThrottle.frameDue(true, false, false, 0L, Long.MIN_VALUE, window,
                heartbeat), "the first frame of a session always reports itself");

        int due = 0;
        long dueAt = -1L;
        for (long tick = 1L; tick < heartbeat; tick++) {
            if (CarrierSightThrottle.frameDue(false, false, false, tick, 0L, window, heartbeat)) {
                due++;
                dueAt = tick;
            }
        }
        assertEquals(0, due, "not one line between tick 1 and "
                + (heartbeat - 1) + " (the first one was at " + dueAt + ")");
        assertTrue(CarrierSightThrottle.frameDue(false, false, false, heartbeat, 0L, window, heartbeat),
                "and the heartbeat is due at exactly " + heartbeat + " ticks");
        assertFalse(CarrierSightThrottle.frameDue(false, false, false, heartbeat + 1L, heartbeat, window,
                heartbeat), "then it is quiet again");
    }

    /**
     * <b>A change is due as soon as the window allows it, and an alarm never waits at all.</b>
     *
     * <p>The three rules of the quiet frame summary, each with its own boundary: a change inside the
     * window waits, a change at the window is written, and an alarm is written whatever the window says -
     * because "a pose just went stale" and "a landed host could not be drawn" are findings rather than
     * cadence, and a finding that arrives forty ticks late has already been missed.
     */
    @Test
    @DisplayName("a change waits for the window, an alarm does not")
    void changesWaitAndAlarmsDoNot() {
        final int window = 100;
        final int heartbeat = 2400;
        assertFalse(CarrierSightThrottle.frameDue(false, false, true, 99L, 0L, window, heartbeat),
                "a change one tick inside the window is not written yet");
        assertTrue(CarrierSightThrottle.frameDue(false, false, true, 100L, 0L, window, heartbeat),
                "and it is at the window");
        assertTrue(CarrierSightThrottle.frameDue(false, true, false, 1L, 1L, window, heartbeat),
                "an alarm with an unchanged picture is written at once");
    }

    /**
     * <b>The B1 gate: a change is floored, and an unchanged settlement is bounded by both clocks.</b>
     *
     * <p>Three sequences on one rule, because the rule has three branches and the defect it was written
     * against (59 visits in 2,400 frames) lived in the interaction between them:
     *
     * <ol>
     *   <li>nothing seen yet: due;</li>
     *   <li>a change: due as soon as the 40-tick floor has passed, and not before;</li>
     *   <li>no change: due only when <b>both</b> the 100-tick window and the 2,400-tick heartbeat have
     *       passed - asserted as {@code false} throughout 1..2399 and {@code true} at 2400, which is the
     *       single due answer two minutes of silence is allowed.</li>
     * </ol>
     */
    @Test
    @DisplayName("the sweep is floored on a change and bounded by both clocks otherwise")
    void theSweepGateIsFloored() {
        final int floor = 40;
        final int window = 100;
        final int heartbeat = 2400;
        assertTrue(CarrierSightThrottle.sweepDue(false, true, 0L, Long.MIN_VALUE, Long.MIN_VALUE, floor,
                window, heartbeat), "the first frame of a session reports itself");

        // The floor, at its boundary: a change seen 39 ticks ago is not written, one seen 40 ticks ago is.
        assertFalse(CarrierSightThrottle.sweepDue(true, true, 39L, 0L, 0L, floor, window, heartbeat),
                "a change inside the floor must not be written again");
        assertTrue(CarrierSightThrottle.sweepDue(true, true, 40L, 0L, 0L, floor, window, heartbeat),
                "and it is at the floor");

        // An unchanged settlement: false all the way to the heartbeat, true at it.
        int due = 0;
        for (long tick = 1L; tick < heartbeat; tick++) {
            if (CarrierSightThrottle.sweepDue(true, false, tick, 0L, 0L, floor, window, heartbeat)) {
                due++;
            }
        }
        assertEquals(0, due, "an unchanged settlement must not be visited inside the heartbeat - which is"
                + " the 59-visits-in-2400-frames defect: " + due + " visit(s)");
        assertTrue(CarrierSightThrottle.sweepDue(true, false, heartbeat, 0L, 0L, floor, window,
                heartbeat), "and the heartbeat brings it back, exactly once");
        assertFalse(CarrierSightThrottle.sweepDue(true, false, heartbeat + 1L, heartbeat, heartbeat,
                floor, window, heartbeat), "then it is quiet again");
    }
}

package com.jiangyuefengyu.redstonecircuit.client;

/**
 * <b>When each of the client's throttled lines is due, as pure functions of the clock.</b>
 *
 * <h2>Why this is its own class with its own arithmetic</h2>
 * The previous round of this work wrote its throttle tests against <em>how many lines the log
 * produced</em>, and every one of those tests was off by one: ten windows of a changing line produced
 * eleven lines, and two minutes of an unchanged settlement produced two visits rather than one. Neither
 * number was wrong because the arithmetic was wrong - the arithmetic was never stated anywhere - but
 * because "how many lines" is a property of a loop that already ran, and the loop's first and last
 * iterations are exactly the ones a hand-counted expectation gets wrong.
 *
 * <p>So the rules are here, as <b>functions from a clock reading to a boolean</b>, and the tests assert
 * the boolean sequence rather than a line count. The convention is fixed and stated once:
 *
 * <ul>
 *   <li><b>the first call of a session is always due</b> - a diagnostic that cannot report its own
 *       absence is not a diagnostic, and a session whose log has no line at all has to mean "the pass
 *       never ran";</li>
 *   <li><b>a window opens when {@code now - last >= window}</b> - a single {@code >=} on the tick
 *       difference, <b>not</b> a {@code tick % window == 0} test. Chosen deliberately: the elapsed form
 *       needs nothing to be aligned, survives a caller that starts at tick 17 or after a level change,
 *       and is the same test an alarm makes. A modulo test would also be due at tick 0, which would make
 *       "the first call" and "the first window" two different rules for the same instant;</li>
 *   <li><b>an alarm is never throttled</b>, and a state change is an event when the floor allows it;
 *       see {@link #frameDue} and {@link #sweepDue}, where the two honest exceptions live;</li>
 *   <li><b>every function here is pure</b>. The memory - when each line was last written, and what the
 *       last settlement was - stays in the callers, so a test can state the starting condition instead of
 *       resetting a process-lifetime static and hoping.</li>
 * </ul>
 *
 * <p>Nothing in this class is read by any judgement: it answers "should a line be written", and the
 * picture is decided elsewhere and is unchanged by the answer.
 */
final class CarrierSightThrottle {

    private CarrierSightThrottle() {
    }

    /**
     * Whether a window measured in ticks has opened since {@code last}.
     *
     * <p>The one piece of arithmetic every throttle below shares, exported so it can be asserted on its
     * own rather than only through a caller's loop.
     *
     * @param nowTicks the client's game time
     * @param last the tick the line was last written at; anything negative means "never"
     * @param windowTicks the window's length in ticks; zero or less means "every tick"
     * @return true when at least {@code windowTicks} ticks separate the two readings
     */
    static boolean windowExpired(long nowTicks, long last, int windowTicks) {
        return last < 0 || nowTicks < 0 || nowTicks - last >= windowTicks;
    }

    /**
     * Whether the <b>quiet frame summary</b> is due: the default mode's rule, in one place.
     *
     * <p>The four answers, in the order they are asked, and each of them is a sentence in the log:
     *
     * <ol>
     *   <li><b>nothing has been written yet</b> - due, because the first frame of a session has to report
     *       itself;</li>
     *   <li><b>an alarm</b> (a pose that has just gone stale, a landed host the world path could not
     *       draw) - due at once, whatever the window says: those are findings, not cadence;</li>
     *   <li><b>the counters changed</b> - due once the window has opened. The window is the whole point:
     *       on a moving machine the counters change every frame, and sixty lines a second is exactly the
     *       defect this class exists for;</li>
     *   <li><b>nothing changed</b> - due only on the heartbeat, which is minutes long, because an
     *       unchanged picture still has to prove the pass is running.</li>
     * </ol>
     *
     * @param firstFrame whether no summary has ever been written
     * @param alarm whether this frame carries a finding that must not be throttled
     * @param changed whether any watched counter differs from the last summary's
     * @param nowTicks the client's game time
     * @param lastWriteTick the tick the last summary was written at, or a negative value
     * @param windowTicks the quiet window, in ticks
     * @param heartbeatTicks how long an unchanged picture may stay silent, in ticks
     */
    static boolean frameDue(boolean firstFrame, boolean alarm, boolean changed, long nowTicks,
                            long lastWriteTick, int windowTicks, int heartbeatTicks) {
        if (firstFrame) {
            return true;
        }
        if (alarm) {
            return true;
        }
        if (changed) {
            return windowExpired(nowTicks, lastWriteTick, windowTicks);
        }
        return windowExpired(nowTicks, lastWriteTick, heartbeatTicks);
    }

    /**
     * Whether the <b>B2 sight line</b> is due: the window, and nothing else.
     *
     * <p>The line's own equality test - "never the same text twice" - is the writer's
     * ({@code CarrierSightLog#writeSight}), because it is a property of the text rather than of the
     * clock. This says only whether the window has opened.
     *
     * @param nowTicks the client's game time
     * @param lastWriteTick the tick the last sight line was written at, or a negative value
     * @param windowTicks the window's length, in ticks
     */
    static boolean sightDue(long nowTicks, long lastWriteTick, int windowTicks) {
        return windowExpired(nowTicks, lastWriteTick, windowTicks);
    }

    /**
     * Whether the <b>B1 sweep</b> is due: the change rule and the floor under it, then both clocks.
     *
     * <h2>The rule, and the defect it is written against</h2>
     * The round that first wrote this gate measured <b>59 visits in 2,400 frames</b> of a picture that
     * never changed. The cause was that the gate compared the settlement against the last settlement
     * <em>written</em>, so a visit that was floored or suppressed left the memory stale and every later
     * window looked like a change. The rule is therefore stated in terms of the last visit
     * <b>seen</b> - {@code changed} is "the settlement differs from what the last visit saw" - and the
     * floor applies to the change rule alone:
     *
     * <ol>
     *   <li><b>nothing has been seen yet</b> - due: the first frame of a session reports itself;</li>
     *   <li><b>the settlement changed</b> and the floor has passed - due at once, because a value change
     *       is the event this exists for (a machine appearing, a player crossing the walk's radius);</li>
     *   <li><b>otherwise</b> - due only once <b>both</b> the window and the heartbeat have passed. The
     *       window alone would be 24 visits in two minutes of an unchanged picture; requiring both is
     *       what makes it exactly one, which is what "an unchanged settlement is not swept again" has to
     *       mean if it is to mean anything;</li>
     *   <li><b>otherwise</b> - not due, which costs one integer comparison.</li>
     * </ol>
     *
     * @param seenBefore whether any visit has happened yet
     * @param changed whether the settlement differs from the one the last visit saw
     * @param nowTicks the client's game time
     * @param lastVisitTick the tick of the last visit, whatever came of it, or a negative value
     * @param lastWriteTick the tick the rollup was last <b>written</b> at, or a negative value
     * @param floorTicks the least time between two change-driven visits
     * @param windowTicks the sweep's own window
     * @param heartbeatTicks how long an unchanged settlement may stay silent
     */
    static boolean sweepDue(boolean seenBefore, boolean changed, long nowTicks, long lastVisitTick,
                            long lastWriteTick, int floorTicks, int windowTicks, int heartbeatTicks) {
        if (!seenBefore) {
            return true;
        }
        if (changed) {
            // Floored, and the floor is measured from the last visit rather than from the last write:
            // a change the floor suppressed was still looked at, and charging it again on the next frame
            // is what turned a flickering count into a line per frame.
            return windowExpired(nowTicks, lastVisitTick, floorTicks);
        }
        return windowExpired(nowTicks, lastVisitTick, windowTicks)
                && windowExpired(nowTicks, lastWriteTick, heartbeatTicks);
    }
}

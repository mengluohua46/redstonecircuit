package com.jiangyuefengyu.redstonecircuit.client;

/**
 * When each of the client's throttled lines is due, as pure functions of the clock.
 *
 * <p>The first call of a session is always due; a window opens when {@code now - last >= window}, an elapsed
 * test rather than {@code tick % window == 0}, so nothing has to be aligned and tick 0 is not special; an
 * alarm is never throttled. Every function is pure - the memory stays in the callers.
 */
final class CarrierSightThrottle {

    private CarrierSightThrottle() {
    }

    /**
     * Whether a window measured in ticks has opened since {@code last}. A negative {@code last} means "never",
     * and a window of zero or less means "every tick".
     */
    static boolean windowExpired(long nowTicks, long last, int windowTicks) {
        return last < 0 || nowTicks < 0 || nowTicks - last >= windowTicks;
    }

    /**
     * Whether the quiet frame summary is due: nothing written yet - due; an alarm - due at once, because a
     * finding is not a cadence; a counter changed - due once the window has opened; unchanged - heartbeat.
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
     * Whether the B2 sight line is due: the window, and nothing else. The "never the same text twice" test
     * belongs to the writer, because it is a property of the text and not of the clock.
     */
    static boolean sightDue(long nowTicks, long lastWriteTick, int windowTicks) {
        return windowExpired(nowTicks, lastWriteTick, windowTicks);
    }

    /**
     * Whether the B1 sweep is due: the change rule and the floor under it, then both clocks. {@code changed}
     * compares against what the last visit saw, so a visit that was floored does not leave the memory stale.
     */
    static boolean sweepDue(boolean seenBefore, boolean changed, long nowTicks, long lastVisitTick,
                            long lastWriteTick, int floorTicks, int windowTicks, int heartbeatTicks) {
        if (!seenBefore) {
            return true;
        }
        if (changed) {
            // Floored from the last visit, not the last write: a suppressed change was still looked at.
            return windowExpired(nowTicks, lastVisitTick, floorTicks);
        }
        return windowExpired(nowTicks, lastVisitTick, windowTicks)
                && windowExpired(nowTicks, lastWriteTick, heartbeatTicks);
    }
}

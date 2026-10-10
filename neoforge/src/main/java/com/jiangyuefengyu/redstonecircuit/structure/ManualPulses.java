package com.jiangyuefengyu.redstonecircuit.structure;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Which manual entries are being pushed right now, and until when.
 *
 * <p>A press is a pulse and not a hold: a hold would need a release packet per press, and a release that never arrives
 * - the player closed the game, was kicked, the connection dropped - leaves a structure pushing power forever with
 * nobody at the controls, while a pulse carries its own end. A key press is an edge anyway, and
 * {@code structureManualPulseTicks} (ten ticks, half a second) trips a repeater, a piston or a dispenser while still
 * letting two presses be two pulses.
 *
 * <p>Pure bookkeeping over a game time, so the rule is pinned offline rather than behind a running server.
 */
public final class ManualPulses {

    /** Entry index -> the game time after which that entry stops being pushed. */
    private final Map<Integer, Long> until = new HashMap<>();

    /**
     * Starts a pulse for one entry; restarting one that is already running is the answer that keeps the input honest,
     * since pressing the same key again sends the same thing as holding it. The caller has already passed the cooldown
     * check, so this cannot be used to flood.
     */
    public void press(int entry, long gameTime, int pulseTicks) {
        if (entry < 0) {
            return;
        }
        this.until.put(entry, gameTime + Math.max(1, pulseTicks));
    }

    /** Drops every pulse that has run out; true when anything was dropped, a change the pocket's redstone has to hear about. */
    public boolean expire(long gameTime) {
        return !this.until.isEmpty() && this.until.values().removeIf(end -> end <= gameTime);
    }

    /** The entries being pushed at this moment, for the injection ledger. */
    public Set<Integer> pressed() {
        return Set.copyOf(this.until.keySet());
    }

    public boolean isEmpty() {
        return this.until.isEmpty();
    }

    public int size() {
        return this.until.size();
    }

    public void clear() {
        this.until.clear();
    }
}

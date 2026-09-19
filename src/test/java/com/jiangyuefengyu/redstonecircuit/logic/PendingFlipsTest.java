package com.jiangyuefengyu.redstonecircuit.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests the queue behind every delay in the mod.
 *
 * <p>Two behaviours matter and both are easy to get subtly wrong: a component that already has a
 * re-evaluation pending must not get a second one (that is what keeps a repeater's delay stable and a
 * repeater loop ticking at a fixed rate), and a re-evaluation must only fire once its tick has
 * actually arrived.
 */
class PendingFlipsTest {

    private static final BlockPos A = new BlockPos(1, 2, 3);
    private static final BlockPos B = new BlockPos(4, 5, 6);

    @Test
    @DisplayName("a scheduled change waits until its tick")
    void waitsForItsTick() {
        PendingFlips pending = new PendingFlips();
        assertTrue(pending.schedule(A, 10));

        assertEquals(List.of(), pending.takeDue(9), "not yet");
        assertEquals(List.of(A), pending.takeDue(10), "due on its tick, not after it");
        assertEquals(List.of(), pending.takeDue(11), "and only once");
    }

    @Test
    @DisplayName("a second change while one is pending does not move the tick")
    void doesNotReschedule() {
        PendingFlips pending = new PendingFlips();
        assertTrue(pending.schedule(A, 10));
        assertFalse(pending.schedule(A, 12), "the first schedule stands");

        assertTrue(pending.isPending(A));
        assertEquals(List.of(A), pending.takeDue(10),
                "the component re-evaluates when it was originally told to, not later");
    }

    @Test
    @DisplayName("several components can be waiting at once")
    void severalAtOnce() {
        PendingFlips pending = new PendingFlips();
        pending.schedule(A, 10);
        pending.schedule(B, 5);

        assertEquals(2, pending.size());
        assertEquals(List.of(B), pending.takeDue(7), "the earlier one goes first");
        assertEquals(List.of(A), pending.takeDue(20));
        assertEquals(0, pending.size());
    }

    @Test
    @DisplayName("a component that is gone can be forgotten")
    void forgetDropsThePendingChange() {
        PendingFlips pending = new PendingFlips();
        pending.schedule(A, 10);
        pending.forget(A);

        assertFalse(pending.isPending(A));
        assertEquals(List.of(), pending.takeDue(10),
                "a removed component must not come back to life at its old tick");
    }

    @Test
    @DisplayName("a position scheduled twice through clear() starts fresh")
    void clearEmptiesTheQueue() {
        PendingFlips pending = new PendingFlips();
        pending.schedule(A, 10);
        pending.clear();

        assertEquals(0, pending.size());
        assertEquals(List.of(), pending.takeDue(10));
    }
}

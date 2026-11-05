package com.jiangyuefengyu.redstonecircuit.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.world.ticks.TickPriority;

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

    @Test
    @DisplayName("entries due on one tick come out by priority first, then in the order they were queued")
    void priorityThenQueueOrder() {
        BlockPos c = new BlockPos(7, 8, 9);
        PendingFlips pending = new PendingFlips();
        // Queued in exactly the wrong order: the answer has to come from the priorities, not from the clock.
        pending.schedule(A, 10, TickPriority.NORMAL, 0);
        pending.schedule(c, 10, TickPriority.HIGH, 0);
        pending.schedule(B, 10, TickPriority.EXTREMELY_HIGH, 0);

        assertEquals(List.of(B, c, A), pending.takeDue(10),
                "EXTREMELY_HIGH first, NORMAL last - vanilla's order for one tick's work");
        assertEquals(0, pending.size(), "and every entry left the queue");
    }

    @Test
    @DisplayName("two entries of one priority keep the order they were queued in")
    void queueOrderBreaksTies() {
        BlockPos c = new BlockPos(7, 8, 9);
        PendingFlips one = new PendingFlips();
        one.schedule(c, 10);
        one.schedule(A, 10);
        one.schedule(B, 10);
        assertEquals(List.of(c, A, B), one.takeDue(10),
                "the tie-break is the queue, not a hash of the coordinates");

        // The same three positions inserted the other way round: still the queue order, so the answer is a
        // fact about the queue and not about the coordinates.
        PendingFlips other = new PendingFlips();
        other.schedule(B, 10);
        other.schedule(c, 10);
        other.schedule(A, 10);
        assertEquals(List.of(B, c, A), other.takeDue(10));
    }

    @Test
    @DisplayName("the strength a component was scheduled to take travels with the entry")
    void theTargetTravelsWithTheEntry() {
        PendingFlips pending = new PendingFlips();
        pending.schedule(A, 10, TickPriority.HIGH, 13);

        List<PendingFlips.Flip> due = pending.drainDue(10);
        assertEquals(1, due.size());
        assertEquals(A, due.get(0).pos());
        assertEquals(TickPriority.HIGH, due.get(0).priority());
        assertEquals(13, due.get(0).target(),
                "a pulse that has already gone leaves nothing to read, so the queue carries what it was told");
        assertEquals(0, pending.size());
    }

    @Test
    @DisplayName("a second schedule keeps the first entry's tick, priority and target")
    void theSecondScheduleKeepsTheFirstEntry() {
        PendingFlips pending = new PendingFlips();
        assertTrue(pending.schedule(A, 10, TickPriority.HIGH, 7));
        assertFalse(pending.schedule(A, 20, TickPriority.EXTREMELY_HIGH, 15),
                "the first schedule stands");

        List<PendingFlips.Flip> due = pending.drainDue(10);
        assertEquals(1, due.size());
        assertEquals(7, due.get(0).target());
        assertEquals(TickPriority.HIGH, due.get(0).priority());
    }

    @Test
    @DisplayName("an entry made with no strength is still a valid entry")
    void noTargetIsZero() {
        PendingFlips pending = new PendingFlips();
        pending.schedule(A, 5);
        assertEquals(0, pending.drainDue(5).get(0).target(),
                "a caller with nothing to state - a button's release - leaves 0");
    }
}

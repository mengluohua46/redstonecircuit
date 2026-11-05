package com.jiangyuefengyu.redstonecircuit.logic;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.world.ticks.TickPriority;

/**
 * Components whose output is waiting to follow a change of input.
 *
 * <p>A repeater does not react the moment its input changes: it schedules a re-evaluation a few ticks
 * ahead and only then looks at its input again. Vanilla does exactly this with block ticks, including
 * the rule that a second change while a tick is already pending does <em>not</em> move it - which is
 * what makes a repeater's delay stable and a repeater loop tick at a fixed rate. That rule is the
 * {@link #schedule} method here.
 *
 * <p>Two facts travel with each entry besides its tick. The <b>priority</b> is vanilla's
 * {@code TickPriority}: several components coming due on one tick are applied in that order first and in
 * queue order second, and vanilla orders its own block ticks the same way (priority, then the order they
 * were scheduled in). Without it the order here was a {@code HashMap}'s iteration order, which is a hash
 * of the coordinates - stable within a run and meaningless across them. The <b>target</b> is the output
 * the component was told to take when it was queued, which is what lets a pulse shorter than the delay
 * still come out one delay wide: vanilla's {@code DiodeBlock#tick} turns a repeater that was off on
 * without asking again, and the strength it turns on with is no longer readable at that point.
 *
 * <p>Only positions, game times and two small numbers are stored, so this is plain data that can be
 * tested without a world; the live map lives next to the dirty set in {@link InnerRedstoneNetwork}, one
 * per dimension.
 */
public final class PendingFlips {

    /**
     * One queued re-evaluation.
     *
     * @param target the output this component was scheduled to take, or {@code 0} when the caller did not
     *     state one (a button's release, or an entry made before the target was known)
     */
    public record Flip(BlockPos pos, long dueTick, TickPriority priority, int target) {
    }

    /**
     * Position -&gt; its entry, in the order the entries were queued: the tie-break a priority cannot settle.
     */
    private final Map<BlockPos, Flip> pending = new LinkedHashMap<>();

    /** Queues a re-evaluation at the ordinary priority and with no strength recorded. */
    public boolean schedule(BlockPos pos, long dueTick) {
        return schedule(pos, dueTick, TickPriority.NORMAL, 0);
    }

    /**
     * Queues a re-evaluation, unless one is already pending for that position.
     *
     * @return true when this call scheduled it, false when one was already waiting
     */
    public boolean schedule(BlockPos pos, long dueTick, TickPriority priority, int target) {
        BlockPos key = pos.immutable();
        if (pending.containsKey(key)) {
            return false;
        }
        pending.put(key, new Flip(key, dueTick, priority, target));
        return true;
    }

    public boolean isPending(BlockPos pos) {
        return pending.containsKey(pos);
    }

    /**
     * Removes and returns everything that has come due at {@code now}, in the order it has to be applied.
     *
     * <p>Priority first, queue order second - vanilla's {@code LevelTicks} order, and the reason a long chain
     * of repeaters settles from the head instead of wherever the coordinates happened to hash to.
     */
    public List<Flip> drainDue(long now) {
        List<Flip> ready = new ArrayList<>();
        Iterator<Map.Entry<BlockPos, Flip>> entries = pending.entrySet().iterator();
        while (entries.hasNext()) {
            Flip flip = entries.next().getValue();
            if (flip.dueTick() <= now) {
                ready.add(flip);
                entries.remove();
            }
        }
        // A stable sort, so entries of one priority keep the order they were queued in.
        ready.sort((left, right) -> Integer.compare(left.priority().getValue(), right.priority().getValue()));
        return ready;
    }

    /** The same drain, positions only, for a caller that needs nothing but "who". */
    public List<BlockPos> takeDue(long now) {
        List<Flip> due = drainDue(now);
        List<BlockPos> positions = new ArrayList<>(due.size());
        for (Flip flip : due) {
            positions.add(flip.pos());
        }
        return positions;
    }

    /** Drops a position's pending evaluation, e.g. when its component is removed. */
    public void forget(BlockPos pos) {
        pending.remove(pos);
    }

    public void clear() {
        pending.clear();
    }

    public int size() {
        return pending.size();
    }
}

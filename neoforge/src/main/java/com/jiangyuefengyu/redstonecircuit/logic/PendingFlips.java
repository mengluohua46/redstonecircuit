package com.jiangyuefengyu.redstonecircuit.logic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import net.minecraft.core.BlockPos;

/**
 * Components whose output is waiting to follow a change of input.
 *
 * <p>A repeater does not react the moment its input changes: it schedules a re-evaluation a few ticks
 * ahead and only then looks at its input again. Vanilla does exactly this with block ticks, including
 * the rule that a second change while a tick is already pending does <em>not</em> move it - which is
 * what makes a repeater's delay stable and a repeater loop tick at a fixed rate. That rule is the
 * {@link #schedule} method here.
 *
 * <p>Only positions and game times are stored, so this is plain data that can be tested without a
 * world; the live map lives next to the dirty set in {@link InnerRedstoneNetwork}, one per dimension.
 */
public final class PendingFlips {

    /** Position -> the game tick it should be re-evaluated at. */
    private final Map<BlockPos, Long> dueTicks = new HashMap<>();

    /**
     * Queues a re-evaluation, unless one is already pending for that position.
     *
     * @return true when this call scheduled it, false when one was already waiting
     */
    public boolean schedule(BlockPos pos, long dueTick) {
        return dueTicks.putIfAbsent(pos.immutable(), dueTick) == null;
    }

    public boolean isPending(BlockPos pos) {
        return dueTicks.containsKey(pos);
    }

    /** Removes and returns everything that has come due at {@code now}. */
    public List<BlockPos> takeDue(long now) {
        List<BlockPos> ready = new ArrayList<>();
        Iterator<Map.Entry<BlockPos, Long>> pending = dueTicks.entrySet().iterator();
        while (pending.hasNext()) {
            Map.Entry<BlockPos, Long> entry = pending.next();
            if (entry.getValue() <= now) {
                ready.add(entry.getKey());
                pending.remove();
            }
        }
        return ready;
    }

    /** Drops a position's pending evaluation, e.g. when its component is removed. */
    public void forget(BlockPos pos) {
        dueTicks.remove(pos);
    }

    public void clear() {
        dueTicks.clear();
    }

    public int size() {
        return dueTicks.size();
    }
}

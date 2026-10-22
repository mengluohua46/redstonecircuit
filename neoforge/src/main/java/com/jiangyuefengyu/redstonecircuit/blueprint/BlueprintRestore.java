package com.jiangyuefengyu.redstonecircuit.blueprint;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.jetbrains.annotations.Nullable;

import com.jiangyuefengyu.redstonecircuit.HostRules;
import com.jiangyuefengyu.redstonecircuit.RCConfig;
import com.jiangyuefengyu.redstonecircuit.block.ClockBlock;
import com.jiangyuefengyu.redstonecircuit.block.ExtenderBlock;
import com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneStore;
import com.jiangyuefengyu.redstonecircuit.data.Slot;
import com.jiangyuefengyu.redstonecircuit.logic.ClockRule;
import com.jiangyuefengyu.redstonecircuit.logic.ExtenderRule;
import com.jiangyuefengyu.redstonecircuit.logic.InnerRedstoneNetwork;
import com.jiangyuefengyu.redstonecircuit.network.HostSync;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

/**
 * Where a loaded blueprint's components wait, and the one place they are written into a level.
 *
 * <p>A blueprint is loaded in one go but lands one block at a time - through the instant-build packet, over minutes
 * from a 蓝图加农炮's projectiles, and in several passes because some blocks are deliberately deferred. The world
 * position of a component is therefore known long before the block it belongs to exists, and this class is the
 * hand-off: {@link #remember} takes the whole budget, {@link #settle} is asked once per landed block, and
 * {@link #finish} is the pass for the cells Create never reports at all.
 *
 * <p>Only the store is written. No block, no block state and no block entity is touched: the block is Create's, and a
 * component is data that lives beside a block, which is exactly what it was before the blueprint was taken.
 *
 * <p>Nothing here throws. Every path into it comes from inside Create's own placement loop, where an exception is a
 * half-built machine and a refused blueprint.
 */
public final class BlueprintRestore {

    /**
     * How many blueprints may be mid-print in one dimension at once.
     *
     * <p>Not a limit on play, a limit on forgetting: a blueprint is dropped when its last cell is settled, and a print
     * that is abandoned (a broken cannon, a world nobody comes back to) never settles its last cell. Without a bound
     * that is a leaked map per abandoned blueprint for the life of the server.
     */
    static final int MAX_PENDING_PER_DIMENSION = 8;

    private static final Object LOCK = new Object();

    /** Per dimension, one entry per blueprint still landing, oldest first so eviction can drop the oldest. */
    private static final Map<ResourceKey<Level>, List<Pending>> PENDING = new HashMap<>();

    /** A cannon that has finished printing but still has projectiles in the air, by its own position. */
    private static final Map<ResourceKey<Level>, Map<BlockPos, BlockPos>> AWAITING_LAST = new HashMap<>();

    private static final java.util.Set<String> WARNED = ConcurrentHashMap.newKeySet();

    private BlueprintRestore() {
    }

    /** One blueprint still landing: what it will write, and where it was anchored. */
    private static final class Pending {

        final BlockPos anchor;
        final Map<BlockPos, Slot> nodes;
        final Map<BlockPos, ClockRule.Setting> clocks;
        final Map<BlockPos, ExtenderRule.Setting> extenders;

        Pending(BlockPos anchor, BlueprintPlacement.Budget budget) {
            this.anchor = anchor;
            this.nodes = new LinkedHashMap<>(budget.nodes());
            this.clocks = new LinkedHashMap<>(budget.clocks());
            this.extenders = new LinkedHashMap<>(budget.extenders());
        }

        int size() {
            return this.nodes.size() + this.clocks.size() + this.extenders.size();
        }

        boolean isEmpty() {
            return this.nodes.isEmpty() && this.clocks.isEmpty() && this.extenders.isEmpty();
        }
    }

    // ------------------------------------------------------------- hand-off --

    /** Takes one blueprint's world coordinates. A second blueprint at the same anchor replaces the first. */
    public static void remember(ResourceKey<Level> dimension, BlockPos anchor, BlueprintPlacement.Budget budget) {
        if (dimension == null || anchor == null || budget == null || budget.isEmpty()) {
            return;
        }
        synchronized (LOCK) {
            List<Pending> list = PENDING.computeIfAbsent(dimension, key -> new ArrayList<>());
            list.removeIf(pending -> pending.anchor.equals(anchor));
            while (list.size() >= MAX_PENDING_PER_DIMENSION) {
                Pending dropped = list.remove(0);
                report("evicted", "a blueprint at " + dropped.anchor.toShortString() + " stopped landing with "
                        + dropped.size() + " component(s) still waiting");
            }
            list.add(new Pending(anchor, budget));
        }
    }

    /**
     * The block at {@code pos} has landed: write whatever a blueprint expected there.
     *
     * <p>Every blueprint in the dimension is asked, not just one: two overlapping blueprints is a legal thing to do,
     * and a cell is a cell. The first one that expected this position does the write and the others only forget it, so
     * the cell ends up with one component rather than with whichever write happened to be last.
     */
    public static void settle(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null) {
            return;
        }
        try {
            Slot slot = null;
            ClockRule.Setting clock = null;
            ExtenderRule.Setting extender = null;
            synchronized (LOCK) {
                List<Pending> list = PENDING.get(level.dimension());
                if (list == null) {
                    return;
                }
                for (Pending pending : list) {
                    Slot foundSlot = pending.nodes.remove(pos);
                    ClockRule.Setting foundClock = pending.clocks.remove(pos);
                    ExtenderRule.Setting foundExtender = pending.extenders.remove(pos);
                    if (slot == null && clock == null && extender == null) {
                        slot = foundSlot;
                        clock = foundClock;
                        extender = foundExtender;
                    }
                }
                prune(level.dimension(), list);
            }
            if (slot != null) {
                // The same gate the compensation pass applies, asked here too: a blueprint is somebody else's file,
                // and a cell it names is only honoured where a component may actually live.
                if (hostAccepts(level, pos)) {
                    writeNode(level, pos, slot);
                } else {
                    report("refused-node", "a component was not put back at " + pos.toShortString()
                            + ": that block cannot hold one");
                }
            }
            if (clock != null) {
                if (blockAt(level, pos) instanceof ClockBlock) {
                    writeClock(level, pos, clock);
                } else {
                    report("refused-clock", "a clock's settings were not put back at " + pos.toShortString()
                            + ": no clock stands there");
                }
            }
            if (extender != null) {
                if (blockAt(level, pos) instanceof ExtenderBlock) {
                    writeExtender(level, pos, extender);
                } else {
                    report("refused-extender", "an extender's settings were not put back at "
                            + pos.toShortString() + ": no extender stands there");
                }
            }
        } catch (RuntimeException failed) {
            report("settle", "a component could not be put back at " + pos.toShortString() + ": " + failed);
        }
    }

    /**
     * The blueprint at {@code anchor} has finished: write every cell that is a place it could go, once, and drop what
     * is left.
     *
     * <p>This is the pass for blocks Create never reports. A 蓝图加农炮 skips a block whose target already matches the
     * blueprint, and an instant build does the same, so those cells are never "landed" and their components would
     * otherwise be lost with the blueprint that carried them.
     *
     * <p>A cell whose block is not there yet is left waiting rather than written: while a cannon's projectiles are in
     * the air the target is still air, and a component written into the air is a component lost. That waiting is what
     * {@link #markFinal} and {@link #finishMarked} are for.
     */
    public static void finish(ServerLevel level, BlockPos anchor) {
        if (level == null || anchor == null) {
            return;
        }
        try {
            int left;
            synchronized (LOCK) {
                List<Pending> list = PENDING.get(level.dimension());
                if (list == null) {
                    return;
                }
                Pending found = null;
                for (Pending pending : list) {
                    if (pending.anchor.equals(anchor)) {
                        found = pending;
                        break;
                    }
                }
                if (found == null) {
                    return;
                }
                sweep(level, found);
                left = found.size();
                // Removed either way: a blueprint is finished once, so what is still waiting after the sweep has no
                // block and no later pass that could give it one.
                list.remove(found);
                prune(level.dimension(), list);
            }
            if (left > 0) {
                report("unsent", "a blueprint at " + anchor.toShortString() + " left " + left
                        + " component(s) with no block to live in");
            }
        } catch (RuntimeException failed) {
            report("finish", "a blueprint at " + anchor.toShortString() + " could not be settled: " + failed);
        }
    }

    /**
     * Records that a 蓝图加农炮 has finished printing but still has projectiles in the air, so the last pass waits.
     *
     * <p>Keyed by the cannon rather than by the blueprint because Create clears its printer - and with it the anchor -
     * before those projectiles land, and the cannon's own position is the part still known when they do.
     */
    public static void markFinal(ResourceKey<Level> dimension, BlockPos cannon, BlockPos anchor) {
        if (dimension == null || cannon == null || anchor == null) {
            return;
        }
        synchronized (LOCK) {
            AWAITING_LAST.computeIfAbsent(dimension, key -> new HashMap<>()).put(cannon, anchor);
        }
    }

    /** The anchor a cannon's last pass is waiting for, forgetting it. */
    @Nullable
    public static BlockPos takeFinal(ResourceKey<Level> dimension, BlockPos cannon) {
        if (dimension == null || cannon == null) {
            return null;
        }
        synchronized (LOCK) {
            Map<BlockPos, BlockPos> marks = AWAITING_LAST.get(dimension);
            if (marks == null) {
                return null;
            }
            BlockPos anchor = marks.remove(cannon);
            if (marks.isEmpty()) {
                AWAITING_LAST.remove(dimension);
            }
            return anchor;
        }
    }

    // ---------------------------------------------------------------- writes --

    /** Writes every entry of one blueprint whose block is there and may hold it. */
    private static void sweep(ServerLevel level, Pending pending) {
        for (Map.Entry<BlockPos, Slot> entry : new ArrayList<>(pending.nodes.entrySet())) {
            if (hostAccepts(level, entry.getKey())) {
                pending.nodes.remove(entry.getKey());
                writeNode(level, entry.getKey(), entry.getValue());
            }
        }
        for (Map.Entry<BlockPos, ClockRule.Setting> entry : new ArrayList<>(pending.clocks.entrySet())) {
            if (blockAt(level, entry.getKey()) instanceof ClockBlock) {
                pending.clocks.remove(entry.getKey());
                writeClock(level, entry.getKey(), entry.getValue());
            }
        }
        for (Map.Entry<BlockPos, ExtenderRule.Setting> entry : new ArrayList<>(pending.extenders.entrySet())) {
            if (blockAt(level, entry.getKey()) instanceof ExtenderBlock) {
                pending.extenders.remove(entry.getKey());
                writeExtender(level, entry.getKey(), entry.getValue());
            }
        }
    }

    private static void writeNode(ServerLevel level, BlockPos pos, Slot slot) {
        InnerRedstoneStore store = InnerRedstoneStore.get(level);
        store.getOrCreate(pos).setSlot(slot);
        store.markDirty();
        // A component restored inside a block changes no block state, so the client learns about it no other way -
        // the same reason the structure release broadcasts the slots it writes back.
        HostSync.broadcast(level, pos, slot);
        InnerRedstoneNetwork.markDirtyWithNeighbours(level, pos);
    }

    /**
     * A 时钟源's or 信号延长器's numbers belong to the block that stands there and to that one only: the same numbers
     * parked under a stone block would be a clock the level ticks for nothing.
     */
    private static void writeClock(ServerLevel level, BlockPos pos, ClockRule.Setting setting) {
        InnerRedstoneStore.get(level).putClock(pos, setting);
        InnerRedstoneNetwork.markDirtyWithNeighbours(level, pos);
    }

    private static void writeExtender(ServerLevel level, BlockPos pos, ExtenderRule.Setting setting) {
        InnerRedstoneStore.get(level).putExtender(pos, setting);
        InnerRedstoneNetwork.markDirtyWithNeighbours(level, pos);
    }

    private static boolean hostAccepts(ServerLevel level, BlockPos pos) {
        if (RCConfig.validateHosts()) {
            return HostRules.isValidHost(level, pos);
        }
        // With the host rule switched off a player's own placement accepts any block at all, and a blueprint has to
        // come back the way it went in - but a cell that is still air has no block to come back into.
        return !level.getBlockState(pos).isAir();
    }

    private static Block blockAt(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos).getBlock();
    }

    // -------------------------------------------------------------- plumbing --

    private static void prune(ResourceKey<Level> dimension, List<Pending> list) {
        list.removeIf(Pending::isEmpty);
        if (list.isEmpty()) {
            PENDING.remove(dimension);
        }
    }

    /** How many components are still waiting in a dimension. */
    public static int pending(ResourceKey<Level> dimension) {
        synchronized (LOCK) {
            List<Pending> list = PENDING.get(dimension);
            if (list == null) {
                return 0;
            }
            int total = 0;
            for (Pending pending : list) {
                total += pending.size();
            }
            return total;
        }
    }

    /** Forgets every blueprint waiting in a dimension, writing nothing. */
    public static void forgetAll(ResourceKey<Level> dimension) {
        synchronized (LOCK) {
            PENDING.remove(dimension);
            AWAITING_LAST.remove(dimension);
        }
    }

    /** Reports a condition once per key: one of these happens once per blueprint, not once per tick. */
    private static void report(String key, String message) {
        if (WARNED.add(key)) {
            RCConfig.LOGGER.warn("[redstonecircuit] blueprint placement: {}", message);
        }
    }
}

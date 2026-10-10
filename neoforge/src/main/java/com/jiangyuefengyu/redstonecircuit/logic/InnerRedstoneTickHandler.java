package com.jiangyuefengyu.redstonecircuit.logic;

import com.jiangyuefengyu.redstonecircuit.RCConfig;
import com.jiangyuefengyu.redstonecircuit.block.ClockBlock;
import com.jiangyuefengyu.redstonecircuit.block.ExtenderBlock;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierStore;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierGuards;
import com.jiangyuefengyu.redstonecircuit.carrier.SolveFailureLedger;
import com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneStore;
import com.jiangyuefengyu.redstonecircuit.structure.pocket.PocketDimension;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * Resolves queued inner-redstone power updates once per level tick.
 *
 * <p>批到 tick 末尾，意味着一批放置（或连锁的方块变化）只花一次传播，而不是每个变化一次。被移动结构搬走的 host 方块
 * 必须不求解: 它的元件已经不在世界里，"这个方块另一边是什么"变成了关于搬运内部的提问，所以在有替代答案之前什么都不做
 * 才是诚实的。两个入口因此都丢掉正在被搬运的位置（一次哈希查询，且只在真有东西在飞时才走到）。代价是那个 host 没有功率
 * 变化、没有邻居通知、没有包、没有 {@code PowerSolver} 工作；另一面由搬运层负责 - 元件在机器把它带到的位置上被求解，
 * 并通过投影回答世界的信号查询。
 */
public final class InnerRedstoneTickHandler {

    @SubscribeEvent
    public void onLevelTick(LevelTickEvent.Post event) {
        if (event.getLevel() instanceof ServerLevel level) {
            // END THE PREVIOUS TICK'S SOLVER LOG FIRST, so the per-tick counters the spin warning is stated in
            // really do cover one tick. Diagnostics only: SolverLog counts and never decides.
            SolverLog.flush(level);
            // And the one carrier-read line for the tick that just ended, at the same boundary as the summary, so
            // "which structure read which position" is at most one line per tick however many queries were made.
            // Diagnostics only: the class counts and never decides.
            SignalReadTrace.flush();
            if (CarrierStore.isActive()) {
                // THE TOP-LEVEL NET. An exception here is rethrown by the event bus and stops the server, so the
                // whole pass is guarded. A pass that fails does nothing this tick and is retried on the next one,
                // and the solver below still runs, so a carrier failure cannot also freeze the world's redstone.
                CarrierGuards.OTHER.call(null, () -> {
                    // Poses to clients, and the sweep that rescues the hosts of a structure that died
                    // without ever calling stopMoving. Cheap and empty while nothing is in flight.
                    CarrierStore.tick(level);
                    return Boolean.TRUE;
                }, false);
            }
            // AND THE DEVICES NOTHING EVER NOTIFIES AT THE RIGHT MOMENT: 时钟源 has no input and answers to the world
            // clock, and 信号延长器's hold runs out on that same clock. The pass that owns the level asks every standing
            // one of them, once per tick and before the solver's own pass, so an edge lands in the same tick's settle.
            // The two stored inside a host block are written by InnerRedstoneNetwork#tickTimedDevices, a few lines down.
            ClockBlock.tickStanding(level);
            ExtenderBlock.tickStanding(level);
            InnerRedstoneNetwork.tick(level);
            // AND THE INPUTS THAT NEVER ANNOUNCE THEMSELVES: a turning helm notifies no neighbour at all, so a
            // comparator inside a block would keep its old reading for ever while one on the ground tracked the
            // wheel. This is the poll that closes that hole: comparators only, one read each, and a re-solve only
            // when the number moved.
            InnerRedstoneNetwork.pollAnalogInputs(level, InnerRedstoneStore.get(level));
            // And the products no level ticks: a Sable sub-level's blocks live in an embedded plot outside the
            // level's ticking chunk map, so a physicalised structure's faces would be read by nobody at all. This
            // runs after the level's own block-entity ticks, which is what makes the tick's own "already ticked
            // this tick" latch meaningful. Gated inside the compat half, and free while Sable is absent.
            ServerLevel pocket = PocketDimension.of(level.getServer());
            if (pocket != null) {
                com.jiangyuefengyu.redstonecircuit.compat.physics.PhysicsCompat.tickCarriedProducts(level,
                        pocket);
            }
        }
    }

    /** Re-solves inner redstone when the world around a host block changes. Without this an inner component would never
     * react to the outside world: placing a lever or a redstone torch against a host block changes no state of ours, so
     * nothing would mark the network dirty and the component would keep reporting its old power for ever. */
    @SubscribeEvent
    public void onNeighborNotify(BlockEvent.NeighborNotifyEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        InnerRedstoneStore store = InnerRedstoneStore.get(level);
        if (store.isEmpty()) {
            return;
        }
        boolean carriersPossible = CarrierStore.isActive();

        // The block whose output changed may itself hold a component. A diode (repeater, comparator) and an
        // observer notify the block they output INTO, so when a host block sits on the output side the event
        // arrives with the host as its position, not as a notified neighbour. Only looking at the notified
        // sides missed every one of them.
        BlockPos pos = event.getPos();
        if (holdsComponent(level, store, pos, carriersPossible) && !carried(level, pos, carriersPossible)) {
            // COUNT, AND PRINT ONLY WHEN ASKED: this runs tens of thousands of times a minute in a busy world, so
            // the count is what the 'solver:' summary and the spin warning are built from, while the per-step text
            // is behind verboseSolverLog (off by default).
            SolverLog.change(pos);
            if (SolverLog.verbose()) {
                RCConfig.LOGGER.info(
                        "[redstonecircuit] output change at host {} -> re-solving", pos.toShortString());
            }
            InnerRedstoneNetwork.markDirty(level, pos);
        }

        // The block at event.getPos() is the one that CHANGED. Its neighbours are the ones being
        // notified, so a host block is the *neighbour* here, not the source. Checking getPos()
        // instead would miss every case where the source disappeared: when a lever is broken, the
        // position it occupied is air by the time this event fires.
        for (Direction direction : event.getNotifiedSides()) {
            BlockPos neighbour = pos.relative(direction);
            if (holdsComponent(level, store, neighbour, carriersPossible)
                    && !carried(level, neighbour, carriersPossible)) {
                // The same count, once per notified side; see above.
                SolverLog.change(neighbour);
                if (SolverLog.verbose()) {
                    RCConfig.LOGGER.info(
                            "[redstonecircuit] world change at {} notified host {} -> re-solving",
                            pos.toShortString(), neighbour.toShortString());
                }
                InnerRedstoneNetwork.markDirty(level, neighbour);
            }
        }
    }

    /** Whether this position has a component, counting one that is riding a structure. The world store is asked first and
     * answers in every ordinary case; only when it has nothing and something is in flight is the carried index consulted. */
    private static boolean holdsComponent(ServerLevel level, InnerRedstoneStore store, BlockPos pos,
                                          boolean carriersPossible) {
        if (store.slotAt(pos) != null) {
            return true;
        }
        return carriersPossible && CarrierStore.slotAt(level, pos) != null;
    }

    /** Whether this host's data is riding a structure right now, i.e. whether it must be left alone. This is
     * unconditional: a carried host's <em>world</em> position is not where its component is, whatever
     * {@code carrierFreezeWorldIo} says - the component lives where the machine carried it, and the in-structure solve
     * recomputes it there, while the position it was picked up from is somebody's air. {@code carriersPossible} is
     * checked first, so the cost while nothing is in flight is one boolean per event, not a lookup per host. */
    private static boolean carried(ServerLevel level, BlockPos pos, boolean carriersPossible) {
        if (!carriersPossible) {
            return false;
        }
        if (!CarrierStore.isCarried(level, pos)) {
            return false;
        }
        if (RCConfig.debugLog()) {
            RCConfig.LOGGER.info(
                    "[redstonecircuit] host {} is riding a structure -> not re-solving (it is not at "
                            + "this position any more)", pos.toShortString());
        }
        return true;
    }

    /** Drops queued work when a level unloads, so stale positions cannot leak across reloads. */
    @SubscribeEvent
    public void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            InnerRedstoneNetwork.clear(level);
            CarrierStore.clear(level);
            // Holds run out on the world clock, and a dimension that unloads must not leave a deadline behind for the
            // next world to read as its own.
            ExtenderDeadlines.clear(level.dimension());
            // The solver log's window clock and counters describe one level's ticks, so they are exactly
            // the kind of state that must not survive a reload.
            SolverLog.clear();
            // The notification coalescer holds this dimension's pending coordinates and the last solve's
            // round counts. Pending positions are only ever handed to a level (never stored), but a
            // dimension that unloads must not leave a session open for the next world with the same key.
            SolverNotifications.clear(level.dimension());
            // The deferral ledger is per dimension and holds host coordinates, so it is exactly the kind
            // of state that must not survive a reload: a deferral left behind would release a component
            // into a world that has been rebuilt around it.
            CarrierStore.forgetDeferred(level.dimension());
        }
    }

    @SubscribeEvent
    public void onServerStopped(ServerStoppedEvent event) {
        SolverLog.clear();
        // Every dimension's session and round counts go with the server, for the same reason the log's do.
        SolverNotifications.clearAll();
        // The carrier guards' ledger holds fixed-size text about failures that have already been reported,
        // so it is exactly the kind of state that must not survive into the next world.
        CarrierGuards.clear();
        // And so is the in-carrier solve's failure ledger: counts and first-seen text about one run must
        // not be read as if they belonged to the next one.
        SolveFailureLedger.clear();
        if (RCConfig.debugLog()) {
            RCConfig.LOGGER.info("[redstonecircuit] server stopped, clearing queued redstone updates");
        }
    }
}

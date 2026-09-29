package com.jiangyuefengyu.redstonecircuit.logic;

import com.jiangyuefengyu.redstonecircuit.RCConfig;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierStore;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierGuards;
import com.jiangyuefengyu.redstonecircuit.carrier.SolveFailureLedger;
import com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneStore;

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
 * <p>Batching to the end of the tick means a burst of placements (or a chain reaction of block
 * changes) costs a single propagation pass instead of one per change.
 *
 * <h2>The one thing this stage freezes</h2>
 * A host block that a moving structure has picked up must not be solved. Its component is not in the
 * world any more: the answer to "what is on the other side of this block" is now a question about the
 * inside of a contraption, and until P2 teaches the solver to ask that question, the honest thing to
 * do is nothing at all. Both entry points below therefore drop a position that is currently carried,
 * which is a single hash lookup ({@link CarrierStore#isCarried}) that is only reached when something
 * is actually in flight.
 *
 * <p>What that buys, concretely: no power changes, no neighbour notifications, no packets, and no
 * {@code PowerSolver} work for a host that is riding - so a machine cannot be dragged around by
 * signals that do not apply to it any more. What P4 added is the other side of the same coin: the
 * component is not at this position, it is at the position the machine has carried it to, and there it
 * <em>is</em> solved (by {@code CarrierNetworks}) and it <em>does</em> answer the world's signal
 * queries (through {@code CarrierStore}'s projection).
 */
public final class InnerRedstoneTickHandler {

    @SubscribeEvent
    public void onLevelTick(LevelTickEvent.Post event) {
        if (event.getLevel() instanceof ServerLevel level) {
            // END THE PREVIOUS TICK'S SOLVER LOG FIRST, so the per-tick counters the spin warning is
            // stated in really do cover one tick - and so the summary is written before this tick adds to
            // it. Diagnostics only: SolverLog counts and never decides.
            SolverLog.flush(level);
            // P1.38: and the one carrier-read line for the tick that just ended. Written here - at the
            // same boundary as the summary - so "which structure read which position" is at most one
            // line per tick however many signal queries the tick made. Diagnostics only: the class
            // counts and never decides.
            SignalReadTrace.flush();
            if (CarrierStore.isActive()) {
                // THE TOP-LEVEL NET. The carrier store's own entry points are each guarded where they are
                // (see CarrierGuards), and this one wraps the whole pass on the level tick - the place the
                // author's crash came out of, because an exception here is rethrown by the event bus and
                // stops the server. A pass that fails does nothing this tick and is retried on the next
                // one; the solver below still runs, so a carrier failure cannot also freeze the world's
                // inner redstone.
                CarrierGuards.OTHER.call(null, () -> {
                    // Poses to clients, and the sweep that rescues the hosts of a structure that died
                    // without ever calling stopMoving. Cheap and empty while nothing is in flight.
                    CarrierStore.tick(level);
                    return Boolean.TRUE;
                }, false);
            }
            InnerRedstoneNetwork.tick(level);
        }
    }

    /**
     * Re-solves inner redstone when the world around a host block changes.
     *
     * <p>Without this, an inner component would never react to the outside world: placing a lever or
     * redstone torch against a host block changes no state of ours, so nothing would ever mark the
     * network dirty and the component would keep reporting its old power forever. That was a real
     * bug - levers and torches appeared to do nothing, and power stayed latched after the source was
     * removed.
     */
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

        // The block whose output changed may itself hold a component. A diode (repeater, comparator)
        // and an observer notify the block they output INTO - `neighborChanged(output)` plus
        // `updateNeighborsAtExceptFromFacing(output, ...)` - so when a host block sits on the output
        // side, the notify event arrives with the host as its position, not as a notified neighbour.
        // Only looking at the notified sides missed every one of them.
        BlockPos pos = event.getPos();
        if (holdsComponent(level, store, pos, carriersPossible) && !carried(level, pos, carriersPossible)) {
            // COUNT, AND PRINT ONLY WHEN ASKED. This line fired 77,416 times in one measured minute; the
            // count is what the 'solver:' summary and the spin warning are built from, and the per-step
            // text is behind verboseSolverLog (off by default). See SolverLog.
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
        // position it used to occupy is air by the time this event fires.
        for (Direction direction : event.getNotifiedSides()) {
            BlockPos neighbour = pos.relative(direction);
            if (holdsComponent(level, store, neighbour, carriersPossible)
                    && !carried(level, neighbour, carriersPossible)) {
                // 86,543 lines in that same measured minute, for one line here. See above.
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

    /**
     * Whether this position has a component, counting one that is riding a structure.
     *
     * <p>The world store is asked first and answers in every ordinary case; only when it has nothing
     * and something is in flight is the carried index consulted. That keeps the common path a single
     * hash lookup in the store the mod already had.
     */
    private static boolean holdsComponent(ServerLevel level, InnerRedstoneStore store, BlockPos pos,
                                          boolean carriersPossible) {
        if (store.slotAt(pos) != null) {
            return true;
        }
        return carriersPossible && CarrierStore.slotAt(level, pos) != null;
    }

    /**
     * Whether this host's data is riding a structure right now, i.e. whether it must be left alone.
     *
     * <p>The {@code carriersPossible} flag is checked first so the cost while Sable and Create are
     * absent - or while nothing is in flight - is one boolean per event, not a lookup per host.
     *
     * <p>This is unconditional since P4: a carried host's <em>world</em> position is not where its
     * component is, whatever {@code carrierFreezeWorldIo} says. With the bridge open the component
     * lives at the position the machine has carried it to, and the in-structure solve is what
     * recomputes it there; the position it was picked up from is somebody's air. (Before P4 the switch
     * also gated this, which only ever suppressed a log line: the world store has nothing at the
     * origin either way.)
     */
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

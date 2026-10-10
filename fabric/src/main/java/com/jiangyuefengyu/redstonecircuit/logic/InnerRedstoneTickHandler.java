package com.jiangyuefengyu.redstonecircuit.logic;

import org.jetbrains.annotations.Nullable;

import com.jiangyuefengyu.redstonecircuit.RCConfig;
import com.jiangyuefengyu.redstonecircuit.block.ClockBlock;
import com.jiangyuefengyu.redstonecircuit.block.ExtenderBlock;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierGuards;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierStore;
import com.jiangyuefengyu.redstonecircuit.carrier.SolveFailureLedger;
import com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneStore;
import com.jiangyuefengyu.redstonecircuit.structure.pocket.PocketDimension;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;

/**
 * Resolves queued inner-redstone power updates once per level tick.
 *
 * <p>Batching to the end of the tick means a burst of placements (or a chain reaction of block
 * changes) costs a single propagation pass instead of one per change.
 *
 * <h2>How the three hooks are wired on Fabric</h2>
 * All three used to be NeoForge bus events on one class. Fabric's counterparts are:
 *
 * <ul>
 *   <li>{@code LevelTickEvent.Post} → {@code ServerTickEvents.END_WORLD_TICK}, which hands over the
 *       {@link ServerLevel} directly instead of a {@code Level} to be filtered.</li>
 *   <li>{@code BlockEvent.NeighborNotifyEvent} → mixins into the three places NeoForge fires it
 *       ({@code ServerLevel#updateNeighborsAt}, {@code ServerLevel#updateNeighborsAtExceptFromFacing}
 *       and {@code DiodeBlock#updateNeighborsInFront}), because Fabric has no neighbour-notify event and
 *       the mod genuinely needs one: a lever placed against a host block changes no state of ours, so
 *       without this nothing would ever mark the network dirty. See
 *       {@link com.jiangyuefengyu.redstonecircuit.mixin.ServerLevelNeighborNotifyMixin}.</li>
 *   <li>{@code LevelEvent.Unload} → {@code ServerLifecycleEvents.SERVER_STOPPED}, since Fabric has no
 *       level-unload event. Waiting for the whole server to stop is coarser than dropping one level's
 *       queue, but it is the same guarantee for the case that matters - a stale position must not
 *       survive into the next run - and it costs nothing while the server is up.</li>
 * </ul>
 */
public final class InnerRedstoneTickHandler {

    /** Every direction's bit in the notified-sides mask handed to {@link #onNeighborNotify}. */
    public static final int ALL_SIDES = (1 << 6) - 1;

    private InnerRedstoneTickHandler() {
    }

    /** Registers the per-world tick. Called from the common entrypoint. */
    public static void register() {
        ServerTickEvents.END_WORLD_TICK.register(InnerRedstoneTickHandler::onLevelTick);
    }

    /**
     * Registers the hook that drops the queues when the server goes.
     *
     * <p>Kept apart from {@link #register()} only so the entrypoint can be read as a list: this is the
     * one hook whose Fabric counterpart is a whole server stopping rather than a level unloading.
     */
    public static void registerServerStopped() {
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            for (ServerLevel level : server.getAllLevels()) {
                InnerRedstoneNetwork.clear(level);
                CarrierStore.clear(level);
                // Holds run out on the world clock, and a level that goes must not leave a deadline behind
                // for the next world to read as its own.
                ExtenderDeadlines.clear(level.dimension());
                // The notification coalescer holds this dimension's pending coordinates and the last solve's
                // round counts. Pending positions are only ever handed to a level (never stored), but a
                // dimension that unloads must not leave a session open for the next world with the same key.
                SolverNotifications.clear(level.dimension());
                // The deferral ledger is per dimension and holds host coordinates, so it is exactly the kind
                // of state that must not survive a reload: a deferral left behind would release a component
                // into a world that has been rebuilt around it.
                CarrierStore.forgetDeferred(level.dimension());
            }
            // The solver log's window clock and counters describe one level's ticks, so they are exactly
            // the kind of state that must not survive a reload.
            SolverLog.clear();
            // Every dimension's session and round counts go with the server, for the same reason the log's do.
            SolverNotifications.clearAll();
            // The carrier guards' ledger holds fixed-size text about failures that have already been
            // reported, so it is exactly the kind of state that must not survive into the next world.
            CarrierGuards.clear();
            // And so is the in-carrier solve's failure ledger: counts and first-seen text about one run must
            // not be read as if they belonged to the next one.
            SolveFailureLedger.clear();
            if (RCConfig.debugLog()) {
                RCConfig.LOGGER.info(
                        "[redstonecircuit] server stopped, cleared queued redstone updates");
            }
        });
    }

    /**
     * The bits of the notified-sides mask: one direction, and every side but one.
     *
     * <p>A mask rather than vanilla's {@code EnumSet} because this runs on the notification path of
     * every redstone change in the world, and an {@code EnumSet} there is an allocation per call.
     */
    public static int side(Direction direction) {
        return 1 << direction.ordinal();
    }

    /**
     * Every side except one, which is what {@code updateNeighborsAtExceptFromFacing} notifies.
     *
     * @param skipped the side vanilla was told to leave alone, or {@code null} when it skips none
     */
    public static int allSidesExcept(@Nullable Direction skipped) {
        return skipped == null ? ALL_SIDES : ALL_SIDES & ~side(skipped);
    }

    /**
     * One tick of everything that is nobody else's business: the queued solves, the devices that answer to
     * the world clock rather than to an input, and the inputs that never announce themselves.
     */
    private static void onLevelTick(ServerLevel level) {
        // END THE PREVIOUS TICK'S SOLVER LOG FIRST, so the per-tick counters the spin warning is stated in
        // really do cover one tick. Diagnostics only: SolverLog counts and never decides.
        SolverLog.flush(level);
        // And the one carrier-read line for the tick that just ended, at the same boundary as the summary, so
        // "which structure read which position" is at most one line per tick however many queries were made.
        SignalReadTrace.flush();
        if (CarrierStore.isActive()) {
            // THE TOP-LEVEL NET. An exception here would take the whole tick down, so the pass is guarded: a
            // pass that fails does nothing this tick and is retried on the next one, and the solver below
            // still runs, so a carrier failure cannot also freeze the world's redstone.
            CarrierGuards.OTHER.call(null, () -> {
                // Poses to clients, and the sweep that rescues the hosts of a structure that died without
                // ever calling stopMoving. Cheap and empty while nothing is in flight.
                CarrierStore.tick(level);
                return Boolean.TRUE;
            }, false);
        }
        // AND THE DEVICES NOTHING EVER NOTIFIES AT THE RIGHT MOMENT: 时钟源 has no input and answers to the
        // world clock, and 信号延长器's hold runs out on that same clock. The pass that owns the level asks
        // every standing one of them, once per tick and before the solver's own pass, so an edge lands in the
        // same tick's settle. The two stored inside a host block are written by
        // InnerRedstoneNetwork#tickTimedDevices, a few lines down.
        ClockBlock.tickStanding(level);
        ExtenderBlock.tickStanding(level);
        InnerRedstoneNetwork.tick(level);
        // AND THE INPUTS THAT NEVER ANNOUNCE THEMSELVES: a turning helm notifies no neighbour at all, so a
        // comparator inside a block would keep its old reading for ever while one on the ground tracked the
        // wheel. This is the poll that closes that hole: comparators only, one read each, and a re-solve only
        // when the number moved.
        InnerRedstoneNetwork.pollAnalogInputs(level, InnerRedstoneStore.get(level));
        // And the products no level ticks: a physics sub-level's blocks live in an embedded plot outside the
        // level's ticking chunk map, so a physicalised structure's faces would be read by nobody at all. Gated
        // inside the compat half, and free while no such mod is installed.
        ServerLevel pocket = PocketDimension.of(level.getServer());
        if (pocket != null) {
            com.jiangyuefengyu.redstonecircuit.compat.physics.PhysicsCompat.tickCarriedProducts(level, pocket);
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
     *
     * <p>Called from the mixins with the position that changed and the sides vanilla is notifying
     * around it, which is everything {@code NeighborNotifyEvent#getNotifiedSides} used to carry. The
     * caller also names itself, so {@code logs/latest.log} says which of the three notification paths
     * brought the news in.
     *
     * @param notifiedSides a bit mask of the directions being notified, as built by {@link #side} and
     *                      {@link #allSidesExcept}; the notified set is never empty in practice, and an
     *                      empty mask simply means "the changed block only"
     * @param source        the call site, for the log line only
     */
    public static void onNeighborNotify(ServerLevel level, BlockPos pos, int notifiedSides,
                                        String source) {
        InnerRedstoneStore store = InnerRedstoneStore.get(level);
        if (store == null || store.isEmpty()) {
            return;
        }
        boolean carriersPossible = CarrierStore.isActive();

        // The block whose output changed may itself hold a component. A diode (repeater, comparator)
        // and an observer notify the block they output INTO - `neighborChanged(output)` plus
        // `updateNeighborsAtExceptFromFacing(output, ...)` - so when a host block sits on the output
        // side, the notification arrives with the host as its position, not as a notified neighbour.
        // Only looking at the notified sides missed every one of them.
        if (holdsComponent(level, store, pos, carriersPossible) && !carried(level, pos, carriersPossible)) {
            // COUNT, AND PRINT ONLY WHEN ASKED: this runs tens of thousands of times a minute in a busy
            // world, so the count is what the 'solver:' summary and the spin warning are built from, while
            // the per-step text is behind verboseSolverLog (off by default).
            SolverLog.change(pos);
            if (SolverLog.verbose()) {
                RCConfig.LOGGER.info("[redstonecircuit] output change at host {} ({}) -> re-solving",
                        pos.toShortString(), source);
            }
            InnerRedstoneNetwork.markDirty(level, pos);
        }

        // The block at `pos` is the one that CHANGED. Its neighbours are the ones being notified, so a
        // host block is the *neighbour* here, not the source. Checking `pos` instead would miss every
        // case where the source disappeared: when a lever is broken, the position it used to occupy is
        // air by the time this runs.
        for (Direction direction : Direction.values()) {
            if ((notifiedSides & side(direction)) == 0) {
                continue;
            }
            BlockPos neighbour = pos.relative(direction);
            if (holdsComponent(level, store, neighbour, carriersPossible)
                    && !carried(level, neighbour, carriersPossible)) {
                // The same count, once per notified side; see above.
                SolverLog.change(neighbour);
                if (SolverLog.verbose()) {
                    RCConfig.LOGGER.info(
                            "[redstonecircuit] world change at {} ({}) notified host {} -> re-solving",
                            pos.toShortString(), source, neighbour.toShortString());
                }
                InnerRedstoneNetwork.markDirty(level, neighbour);
            }
        }
    }

    /**
     * Whether this position has a component, counting one that is riding a structure.
     *
     * <p>The world store is asked first and answers in every ordinary case; only when it has nothing and
     * something is in flight is the carried index consulted.
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
     * <p>This is unconditional: a carried host's <em>world</em> position is not where its component is,
     * whatever {@code carrierFreezeWorldIo} says - the component lives where the machine carried it, and the
     * in-structure solve recomputes it there, while the position it was picked up from is somebody's air.
     * {@code carriersPossible} is checked first, so the cost while nothing is in flight is one boolean per
     * call, not a lookup per host.
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
}

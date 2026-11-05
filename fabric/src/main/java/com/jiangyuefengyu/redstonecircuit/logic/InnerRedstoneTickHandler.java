package com.jiangyuefengyu.redstonecircuit.logic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

import com.jiangyuefengyu.redstonecircuit.RCConfig;
import com.jiangyuefengyu.redstonecircuit.block.ClockBlock;
import com.jiangyuefengyu.redstonecircuit.block.ExtenderBlock;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierGuards;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierId;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierPose;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierProjection;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierStore;
import com.jiangyuefengyu.redstonecircuit.carrier.SolveFailureLedger;
import com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneStore;
import com.jiangyuefengyu.redstonecircuit.structure.pocket.PocketDimension;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

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
                // The world changes offered for this level are the other per-dimension queue here, and a
                // dimension that unloads must not leave positions behind for the next world with the same key to map.
                WORLD_CHANGES.remove(level.dimension());
            }
            // The solver log's window clock and counters describe one level's ticks, so they are exactly
            // the kind of state that must not survive a reload.
            SolverLog.clear();
            // Every dimension's session and round counts go with the server, for the same reason the log's do.
            SolverNotifications.clearAll();
            // Every dimension's offered world changes go with the server, for the same reason the sessions do.
            WORLD_CHANGES.clear();
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
            // AND THE WORLD'S CHANGES OFFERED TO THE STRUCTURES WHOSE OWN GRID COVERS THEM - the pass the
            // neighbour hook below feeds. A guarded call of its own rather than one more line in the lambda
            // above, because "the pose pass threw" and "the structure wake threw" are two different faults.
            // It runs after the pose pass, so the projection table it reads is this tick's, and before the
            // solver below, so a structure's own 超导红石粉 that re-evaluates here writes the value its read
            // already answers and this tick's settle resolves whatever that wake set off in the world.
            CarrierGuards.POSE.call(null, () -> {
                wakeStructureFrames(level);
                return Boolean.TRUE;
            }, false);
        }
        // AND THE DEVICES NOTHING EVER NOTIFIES AT THE RIGHT MOMENT: 时钟源 has no input and answers to the
        // world clock, and 信号延长器's hold runs out on that same clock. The pass that owns the level asks
        // every standing one of them, once per tick and before the solver's own pass, so an edge lands in the
        // same tick's settle. The two stored inside a host block are written by
        // InnerRedstoneNetwork#tickTimedDevices, a few lines down.
        // A component a piston carried to another cell: the notifications could not run where the move was
        // decided (they re-entered the piston), so they run here, before this tick's solve.
        PistonHostTransfer.flushDeferred(level);
        ClockBlock.tickStanding(level);
        ExtenderBlock.tickStanding(level);
        // AND THE ONE DEVICE WHOSE ANSWER BELONGS TO A GROUP RATHER THAN TO ITSELF: 同步火把 is lit by any member of
        // its channel and by nothing else, so the pass that can see the whole group runs here - after the two timed
        // devices have written their own edges (a clock's pulse is an input to a group) and before the solver, which
        // must already see the answer this tick. See SyncGroups for why one tick is the whole point.
        SyncGroups.tick(level);
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
            // AND THE GROUP PASS FOR THE PLOT, because nobody else ticks it: the pocket level is not in the server's
            // world loop (this call is what drives it), so a 同步火把 materialised into a product would never be lit -
            // its answer is written by SyncGroups#tick, which otherwise runs once per tick per world. The channel rows
            // are already in that level's store (PocketMaterializer restores them); this is the pass that reads them.
            SyncGroups.tick(pocket);
            notePocketGroups(pocket);
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
        // AND THE OTHER DIRECTION, which is the one nobody owned. Everything below is about the WORLD frame: a
        // block that changed at a world position, and a host that world position holds. A structure's own grid is a
        // SECOND frame over the same level, and a world change at a cell that grid covers reaches nothing there at
        // all - no block of the structure changes, so its own 超导红石粉 never re-evaluates and keeps whatever its
        // last solve wrote. So the changed position is OFFERED here (one set add) and mapped in the per-tick pass
        // ({@link #wakeStructureFrames}), because the mapping is once per distinct position per tick, never once per
        // event.
        if (CarrierStore.isActive()) {
            offerWorldChange(level, pos);
        }
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
     * <b>世界那一格变了, 而某个结构的自己那张网格盖着它</b> - the queued half, per dimension.
     *
     * <p>A busy level produces neighbour notifications by the tens of thousands a minute (a hopper line, an
     * explosion, flowing water), and each one is a world change that <em>might</em> be covered by a structure's own
     * grid. So the position is queued here, once, and the mapping that decides whether it matters - one containment
     * test into the carrier layer, then one inverse projection - runs in the tick pass, once per distinct position
     * per tick. That is the same "collect it, settle it once" shape InnerRedstoneNetwork#DIRTY has, and the reason
     * the world's block-change rate cannot turn into a per-carrier walk: no carrier is consulted here at all.
     */
    private static final Map<ResourceKey<Level>, Set<BlockPos>> WORLD_CHANGES = new HashMap<>();

    /** 上一份说出去的"口袋里有几支、亮了几支", 只在数字变了的时候打一行. */
    private static String pocketGroupsSeen = "";

    /**
     * <b>What the plot actually holds, and whether the group pass is lighting it.</b>
     *
     * <p>Added because "the torch does not work in a product" has two very different causes that look identical in a
     * live view: the channel rows never arrived, or they arrived and the pass that reads them never ran. So this prints
     * six numbers for the pocket level, and only when they change - a quiet plate stays quiet:
     *
     * <ol>
     *   <li>{@code row(s)}: what the pocket holds in its channel table (what the payload brought with it);</li>
     *   <li>{@code in a loaded chunk}: how many of those cells are loaded at all - a chunk nobody holds is a cell the
     *       group pass cannot read, and it answers "no such torch" rather than "dark";</li>
     *   <li>{@code sync torch block(s)}: how many really hold a 同步火把 block - a row over air is a channel with
     *       nothing to write an answer into;</li>
     *   <li>{@code lit}: how many are lit right now;</li>
     *   <li>{@code member(s)} and {@code wrote}: what the pass itself saw and changed (see
     *       {@code SyncGroups#lastPass()}) - zero members means the roster never found the cells, while members with
     *       nothing written means it found them and nobody is feeding them.</li>
     * </ol>
     */
    private static void notePocketGroups(ServerLevel pocket) {
        if (!RCConfig.debugLog()) {
            return;
        }
        InnerRedstoneStore store = InnerRedstoneStore.get(pocket);
        java.util.Set<BlockPos> positions = store.syncTorchPositions();
        int loaded = 0;
        int blocks = 0;
        int lit = 0;
        for (BlockPos pos : positions) {
            // 只问已经加载的那几格, 而且绝不加载: 读一格会顺手把区块读进来, 于是这条诊断就会改变它自己在量的那件事。
            if (!pocket.hasChunkAt(pos)) {
                continue;
            }
            loaded++;
            var state = pocket.getBlockState(pos);
            if (state.getBlock() instanceof com.jiangyuefengyu.redstonecircuit.block.SyncTorchBlock) {
                blocks++;
                if (state.getValue(com.jiangyuefengyu.redstonecircuit.block.SyncTorchBlock.LIT)) {
                    lit++;
                }
            }
        }
        int[] pass = com.jiangyuefengyu.redstonecircuit.logic.SyncGroups.lastPass();
        String now = positions.size() + "/" + loaded + "/" + blocks + "/" + lit + "/" + pass[0] + "/" + pass[1];
        if (now.equals(pocketGroupsSeen)) {
            return;
        }
        pocketGroupsSeen = now;
        RCConfig.LOGGER.info("[redstonecircuit] pocket groups in {}: {} row(s) | {} of them in a loaded chunk"
                        + " | {} sync torch block(s) there | {} lit | that pass saw {} member(s) and wrote {}",
                pocket.dimension().location(), positions.size(), loaded, blocks, lit, pass[0], pass[1]);
    }

    /** Offers one changed world position to this tick's structure-frame pass: one set add, and nothing else. */
    private static void offerWorldChange(ServerLevel level, BlockPos worldPos) {
        if (worldPos == null) {
            return;
        }
        WORLD_CHANGES.computeIfAbsent(level.dimension(), key -> new HashSet<>()).add(worldPos.immutable());
    }

    /**
     * <b>把世界那一格的变化送进结构的自己那一帧: 盖着它的每一个结构, 对应那一格和它六个邻居都该重算。</b>
     *
     * <p>The mirror image of CarrierStore#notifyProjectionChanges. That pass answers "a structure changed, so the
     * world beside it must re-read"; this one answers "the world changed, so the structure it is flying through must
     * re-read" - and until this existed nothing did: the neighbour hook's every coordinate is a <em>world</em>
     * coordinate, a carried structure's blocks are not, and a 超导红石粉 built into a sub-level's own grid therefore
     * kept the strength its last solve wrote for ever. It is a trigger and only a trigger: nothing here reads or
     * writes a component, so it can only cause an extra re-evaluation, and a re-evaluation can only move a value to
     * what the read already says.
     *
     * <p>Three bounds, each of them the reason this can run every tick:
     * <ul>
     *   <li><b>the mapping is the filter</b> - CarrierStore#carrierAt is one lookup, and a position no live
     *       structure's world bounds cover stops the walk there;</li>
     *   <li><b>coalesced per tick</b> - the positions are queued by offerWorldChange and drained here once, and a
     *       structure cell reached twice in one tick is announced once;</li>
     *   <li><b>off with the layer, off with the switch</b> - nothing is queued at all while no carrier layer is
     *       installed, and carrierNotifyStructureNeighbours is the same capability switch the structure half
     *       answers to, read once per pass rather than per change.</li>
     * </ul>
     */
    private static void wakeStructureFrames(ServerLevel level) {
        Set<BlockPos> pending = WORLD_CHANGES.remove(level.dimension());
        if (pending == null || pending.isEmpty()) {
            return;
        }
        if (!CarrierStore.isActive() || !RCConfig.carrierNotifyStructureNeighbours()) {
            return;
        }
        Set<BlockPos> woken = new HashSet<>();
        for (BlockPos worldPos : pending) {
            // ONE LOOKUP, AND IT IS THE FILTER: whether any live structure's world bounds cover this position.
            CarrierId carrier = CarrierStore.carrierAt(level, worldPos);
            if (carrier == null) {
                continue;
            }
            // THE HELPER THE READ SIDE USES, INVERTED. A structure's own cells are drawn through
            // CarrierProjection#project, so a world position becomes a structure cell only through its exact inverse -
            // and a pose that cannot PROVE a coordinate answers null, because a rounding here would file the wake
            // into the cell next door.
            CarrierPose pose = CarrierStore.poseOf(level, carrier);
            BlockPos local = CarrierProjection.localBlockOf(pose, worldPos);
            if (local == null) {
                continue;
            }
            for (BlockPos cell : structureCellsAround(local)) {
                if (!woken.add(cell)) {
                    continue;
                }
                // THE ENTRY POINT A STRUCTURE'S OWN GRID HAS: the one place that answers "does this structure cell
                // have a level coordinate to be woken at", routed through the same coalescing ledger the
                // structure-notify half uses. NOT InnerRedstoneNetwork#markDirty: that queue is resolved against the
                // WORLD store, whose slotAt has nothing at a structure cell.
                CarrierStore.notifyCellChanged(level, carrier, cell);
            }
        }
    }

    /**
     * <b>The structure cells one world change wakes: the cell it maps to, and the six around it.</b>
     *
     * <p>Why the neighbours and not only the cell: a notification at a coordinate wakes the blocks <em>beside</em>
     * it and never the block at the coordinate itself. So waking the mapped cell and its six neighbours reaches the
     * mapped cell itself (through any one of its own neighbours) and everything one step out - which is where the 
     * wire sits: the world block maps to the structure cell beside the carried host, and the 超导红石粉 above
     * that host is a neighbour of the host cell, i.e. two steps from the mapped one. One cell alone would leave
     * exactly that wire frozen.
     *
     * <p>Pure - no level, no carrier, no store - so the geometry is pinned offline instead of only through a live
     * sub-level, the same way CarrierProjection#localBlockOf is.
     */
    static List<BlockPos> structureCellsAround(BlockPos local) {
        List<BlockPos> cells = new ArrayList<>(Direction.values().length + 1);
        cells.add(local);
        for (Direction direction : Direction.values()) {
            cells.add(local.relative(direction));
        }
        return cells;
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

package com.jiangyuefengyu.redstonecircuit.carrier;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.jiangyuefengyu.redstonecircuit.RCConfig;
import com.jiangyuefengyu.redstonecircuit.carrier.CarriedHostIndex.SlotWorld;
import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.Slot;
import com.jiangyuefengyu.redstonecircuit.logic.CarrierLightNetwork;
import com.jiangyuefengyu.redstonecircuit.logic.ExtenderRule;
import com.jiangyuefengyu.redstonecircuit.logic.PowerSolver;

import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import org.jetbrains.annotations.Nullable;

/**
 * Drives the recomputation of every redstone network riding a moving structure.
 *
 * <p>Once per pass it asks the carried index which structures hold host blocks - never which hosts exist
 * in the level - and settles each carrier's own hosts through a {@link CarrierLightNetwork}, writing the
 * changed local coordinates back into the index and sending them over the existing carrier message.
 *
 * <p>A solve that changed nothing is still a solve, and hitting {@code carrierSolveMaxComponentsPerTick}
 * is reported as budget exhaustion rather than as a solve.
 */
public final class CarrierNetworks {

    /**
     * What one solve pass found for one structure; {@code lastTick} is -1 when there has not been a pass.
     */
    public record SolveReport(int nodes, int components, int solves, long lastTick, int lastDiff,
                              int unsentDiff, int pendingFlips, boolean overflowed) {

        /** The state of a structure that has never been solved in this process. */
        public static SolveReport never() {
            return new SolveReport(0, 0, 0, -1L, 0, 0, 0, false);
        }

        /** One line for {@code /rc carrier}, in the same shape as the other diagnostics. */
        public String describe() {
            return "nodes=" + nodes
                    + " solvedComponents=" + components
                    + " solves=" + solves
                    + " lastTick=" + (lastTick < 0 ? "never" : Long.toString(lastTick))
                    + " lastDiff=" + lastDiff
                    + " unsentDiff=" + unsentDiff
                    + " pendingDelays=" + pendingFlips
                    + (overflowed ? " BUDGET-EXHAUSTED" : "");
        }

        /** True when the pass ran and found nothing to change; the expected state of a parked machine. */
        public boolean settledWithNoChange() {
            return solves > 0 && lastDiff == 0 && !overflowed;
        }
    }

    /** Totals for one pass over one level: the answer to "is the in-carrier solve running at all". */
    public record TickSummary(int carriers, int nodes, int components, int solvedChanges,
                              int changes, int budgetExhausted, long tick) {

        static TickSummary nothing() {
            return new TickSummary(0, 0, 0, 0, 0, 0, -1L);
        }

        /** True when there was nothing in flight to solve; an ordinary state, not a failure. */
        public boolean idle() {
            return carriers == 0;
        }

        /** One line for {@code /rc carrier}. */
        public String describe() {
            return "solve: carriers=" + carriers + " nodes=" + nodes + " components=" + components
                    + " solvedChanges=" + solvedChanges + " unsentChanges=" + changes
                    + (budgetExhausted > 0 ? " budgetExhausted=" + budgetExhausted : "")
                    + " tick=" + tick;
        }
    }

    /**
     * Everything remembered about one structure between passes. Changes are kept as differences against
     * what the clients were last told, so a later change to the same coordinate supersedes the earlier one.
     */
    private static final class CarrierState {

        /** local coordinate -> the power it had when the clients were last told about it. */
        final Long2IntMap synced = new Long2IntOpenHashMap();

        /** local coordinate -> the power it has now, for every coordinate that differs. */
        final Long2IntMap changedSinceSync = new Long2IntOpenHashMap();

        CarrierLightNetwork network;

        int nodes;
        int components;
        int solves;
        long lastTick = -1L;
        int lastDiff;
        boolean overflowed;

        CarrierState() {
            synced.defaultReturnValue(Integer.MIN_VALUE);
            changedSinceSync.defaultReturnValue(Integer.MIN_VALUE);
        }

        SolveReport report() {
            return new SolveReport(nodes, components, solves, lastTick, lastDiff,
                    changedSinceSync.size(), network == null ? 0 : network.pendingCount(), overflowed);
        }
    }

    private static final Map<UUID, CarrierState> STATES = new HashMap<>();

    private static final Map<ResourceKey<Level>, TickSummary> LAST_TICK = new HashMap<>();

    /**
     * The level tick each dimension was last solved at. The solve has two drivers - the level tick and
     * Create's own per-actor callback - because neither is available in every situation, and a pass for a
     * dimension that has already run in this tick is skipped.
     */
    private static final Map<ResourceKey<Level>, Long> SOLVED_AT = new HashMap<>();

    private CarrierNetworks() {
    }

    // ------------------------------------------------------------------ tick --

    /**
     * One solve pass over everything a level is carrying; cheap and empty while nothing is in flight.
     */
    public static TickSummary tick(CarrierStore.TickContext ctx, CarrierStore.Clock clock) {
        ResourceKey<Level> dimension = clock.dimension();
        Long last = SOLVED_AT.get(dimension);
        if (last != null && last == clock.gameTime()) {
            // Already solved for this tick, by the other driver.
            return lastTick(dimension);
        }
        SOLVED_AT.put(dimension, clock.gameTime());
        return solveAll(ctx, clock);
    }

    private static TickSummary solveAll(CarrierStore.TickContext ctx, CarrierStore.Clock clock) {
        CarriedHostIndex index = ctx.index();
        if (index.isEmpty()) {
            if (!STATES.isEmpty()) {
                STATES.clear();
            }
            return TickSummary.nothing();
        }

        long tick = clock.gameTime();
        int carriers = 0;
        int nodes = 0;
        int components = 0;
        int solvedChanges = 0;
        int changes = 0;
        int exhausted = 0;
        Set<UUID> seen = new HashSet<>();

        for (CarrierId carrier : index.carriers()) {
            List<CarriedHost> hosts = index.liveHostsOf(carrier);
            if (hosts.isEmpty()) {
                // Everything this structure was holding has been put down; its network is over.
                STATES.remove(carrier.uuid());
                continue;
            }
            seen.add(carrier.uuid());
            CarrierState state = STATES.computeIfAbsent(carrier.uuid(), ignored -> new CarrierState());
            try {
                SolveReport report = solve(ctx, index, carrier, state, tick);
                carriers++;
                nodes += report.nodes();
                components += report.components();
                solvedChanges += report.lastDiff();
                changes += report.unsentDiff();
                if (report.overflowed()) {
                    exhausted++;
                }
            } catch (RuntimeException failed) {
                // One structure's failure must not take the tick down; the ledger counts every failure,
                // warns once per exception kind and is bounded, so one empty host cannot flood the log.
                SolveFailureLedger.fail(carrier, failed);
            }
        }

        STATES.keySet().removeIf(uuid -> !seen.contains(uuid));
        TickSummary summary = new TickSummary(carriers, nodes, components, solvedChanges, changes,
                exhausted, tick);
        LAST_TICK.put(clock.dimension(), summary);
        if (RCConfig.traceCarrierSolve() && carriers > 0) {
            RCConfig.LOGGER.info("[redstonecircuit] carrier {}", summary.describe());
        }
        return summary;
    }

    /**
     * One structure's pass: seed the components that take part in a solve, let the network settle, compare
     * each logic host against what the clients last saw, then write back and sync only what differs.
     */
    private static SolveReport solve(CarrierStore.TickContext ctx, CarriedHostIndex index,
                                     CarrierId carrier, CarrierState state, long tick) {
        SlotWorld world = ctx.slots();
        CarrierSpace space = new CarrierSpace(carrier, index, world, ctx.access(),
                worldIoOf(ctx, carrier));

        int cap = RCConfig.carrierSolveMaxNodesPerCarrier();
        List<BlockPos> seeds = new ArrayList<>();
        int taken = 0;
        for (CarriedHost host : index.liveHostsOf(carrier)) {
            Slot slot = host.slot();
            // An empty host is not a seed and is not an error: the structure carries the block, the block
            // holds nothing.
            if (slot == null || !CarrierLightNetwork.isLogic(slot.type)) {
                continue;
            }
            // A time-driven output is a function of the world clock, so it is written before the network
            // settles and before the diff below.
            if (slot.type.isTimeDriven()) {
                slot.power = slot.clock.answer(tick);
            } else if (slot.type == ComponentType.EXTENDER) {
                // 信号延长器's hold runs out on the same clock, so this pass advances it too. What is arriving at its
                // 正面 is the number the last solve read: a sub-level keeps its blocks outside this level's
                // coordinates, so a world query here would read somebody else's air. The network below then does the
                // following half - with nothing arriving, the output stays exactly where it is.
                int input = slot.lastInput >= 0 ? PowerSolver.clamp(slot.lastInput) : 0;
                slot.extenderDeadline = ExtenderRule.deadline(tick, input, slot.extender.holdTicks(),
                        slot.extenderDeadline);
                slot.power = ExtenderRule.answer(tick, input, slot.power, slot.extenderDeadline);
            }
            long key = host.localPos().asLong();
            if (!state.synced.containsKey(key)) {
                // First seen by the solver: the clients were told its contents when the structure was
                // picked up, so that value is the baseline, not zero.
                state.synced.put(key, slot.power);
            }
            if (taken++ < cap) {
                seeds.add(host.localPos());
            }
        }
        state.nodes = Math.min(taken, cap);

        if (state.network == null) {
            state.network = new CarrierLightNetwork(RCConfig.carrierSolveMaxComponentsPerTick());
        }
        int diff = state.network.solve(space, seeds, tick);
        state.solves++;
        state.lastTick = tick;
        state.overflowed = diff < 0;
        state.lastDiff = state.overflowed ? 0 : diff;
        state.components = state.network.componentCount();

        state.changedSinceSync.clear();
        for (CarriedHost host : index.liveHostsOf(carrier)) {
            Slot slot = host.slot();
            // Same "an empty host carries nothing" rule as the seeds loop: the second walk over the hosts.
            if (slot == null || !CarrierLightNetwork.isLogic(slot.type)) {
                continue;
            }
            long key = host.localPos().asLong();
            int before = state.synced.get(key);
            if (before != Integer.MIN_VALUE && before != slot.power) {
                state.changedSinceSync.put(key, slot.power);
            }
        }
        if (!state.changedSinceSync.isEmpty()) {
            sync(ctx, index, carrier, state);
        }
        if (state.overflowed) {
            RCConfig.LOGGER.warn("[redstonecircuit] carrier {} has more component work than the "
                            + "per-tick budget allows ({} component(s) per pass); the rest settles "
                            + "over the following ticks",
                    carrier.shortId(), RCConfig.carrierSolveMaxComponentsPerTick());
        }
        return state.report();
    }

    /**
     * The bridge one carrier's solve reads the world through, or {@code null} for the frozen behaviour.
     * Resolved once per carrier per pass rather than per query, because the pose is the expensive half: a
     * Create contraption has to be asked for three basis vectors and a Sable sub-level looked up in its
     * container. The carrier and its level are handed over so the world read can refuse <em>this</em>
     * carrier's own projected hosts - the uncharged second copy of a neighbour the inner relationship
     * already delivers - without affecting any other carrier's projection.
     */
    @Nullable
    private static CarrierWorldIo worldIoOf(CarrierStore.TickContext ctx, CarrierId carrier) {
        CarrierWorldBridge bridge = ctx.bridge();
        if (bridge == null || bridge.signals() == null || bridge.poses() == null) {
            return null;
        }
        CarrierPose pose = bridge.poses().poseOf(carrier);
        return pose == null ? null : new CarrierWorldIo(pose, bridge.signals(), carrier, ctx.level());
    }

    /**
     * Records the changed values in the index - so the renderer, {@code /rc dump} and the save all see
     * them - and tells the clients with the existing carrier message: no new message, no protocol bump.
     *
     * <p>The goggles draw whatever the carried record holds, and a machine that is later put down must come
     * home carrying the value it actually had, so it is the record that has to be current.
     */
    private static void sync(CarrierStore.TickContext ctx, CarriedHostIndex index, CarrierId carrier,
                             CarrierState state) {
        boolean wrote = false;
        for (long key : state.changedSinceSync.keySet()) {
            BlockPos local = BlockPos.of(key);
            CarriedHost host = index.at(carrier, local);
            Slot current = host == null ? null : host.slot();
            // An empty host answers exactly like a coordinate the structure does not carry: nothing to
            // write back. `updateSlot` would accept a null slot, but the solve must never be the thing
            // that empties a block, so this is a skip and not a write.
            if (current == null) {
                continue;
            }
            // The slot object is already the one the solver mutated; carrying it through rather than
            // copying it keeps the two representations from disagreeing for even one tick.
            index.updateSlot(carrier, local, current);
            state.synced.put(key, current.power);
            wrote = true;
        }
        if (!wrote) {
            return;
        }
        ctx.writeBack().wrote(carrier);
        // Nothing is left unsent: what the clients were just told is the baseline the unsentDiff count is
        // compared against.
        state.changedSinceSync.clear();
        state.overflowed = false;
    }

    // ------------------------------------------------------------- reporting --

    /** What the last pass over one dimension did, or a zero summary when there has not been one. */
    public static TickSummary lastTick(ResourceKey<Level> dimension) {
        if (dimension == null) {
            return TickSummary.nothing();
        }
        TickSummary summary = LAST_TICK.get(dimension);
        return summary == null ? TickSummary.nothing() : summary;
    }

    /** What the last pass over this level did; the form the diagnostics call. */
    public static TickSummary lastTick(@Nullable Level level) {
        return level == null ? TickSummary.nothing() : lastTick(level.dimension());
    }

    /** What this structure's last pass found. */
    public static SolveReport reportOf(CarrierId carrier) {
        CarrierState state = STATES.get(carrier.uuid());
        return state == null ? SolveReport.never() : state.report();
    }

    /** How many structures are being remembered in this process. */
    public static int trackedCarrierCount() {
        return STATES.size();
    }

    /** Everything about one structure's solve, for {@code /rc carrier}. */
    public static List<String> describe(CarrierId carrier, CarriedHostIndex index) {
        List<String> out = new ArrayList<>();
        List<CarriedHost> hosts = index.hostsOf(carrier);
        int wires = 0;
        int driven = 0;
        int empty = 0;
        for (CarriedHost host : hosts) {
            Slot slot = host.slot();
            // A diagnostics walk must never fail a command: an emptied host counts as holding nothing.
            if (slot == null) {
                empty++;
                continue;
            }
            ComponentType type = slot.type;
            if (type.isWire()) {
                wires++;
            } else if (type.hasInputSide()) {
                driven++;
            }
        }
        out.add("      solve             : " + reportOf(carrier).describe());
        out.add("      solveInputs       : hosts=" + hosts.size() + " logic=" + (wires + driven)
                + " (wire=" + wires + " driven=" + driven + ")"
                + (empty > 0 ? " empty=" + empty : "")
                + "  <- only these take part in a solve");
        return out;
    }

    /** Drops every remembered state; used by {@code CarrierStore#clear} on a level unload. */
    public static void clear() {
        STATES.clear();
        LAST_TICK.clear();
        SOLVED_AT.clear();
    }
}

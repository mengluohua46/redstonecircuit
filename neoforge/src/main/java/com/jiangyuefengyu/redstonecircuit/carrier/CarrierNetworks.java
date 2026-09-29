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

import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import org.jetbrains.annotations.Nullable;

/**
 * Drives the recomputation of every redstone network that is riding a moving structure: the P2 half
 * of "the components travel, and they keep working while they do".
 *
 * <h2>What it does, in one paragraph</h2>
 * Once per pass it asks the carried index which structures are holding host blocks (<b>not</b> which
 * hosts exist in the level - see the performance note), builds a {@link CarrierSpace} for each one,
 * and hands that space plus the carrier's own local coordinates to a {@link CarrierLightNetwork}. The
 * network settles the wires, schedules the driven components, and reports which local coordinates
 * changed. Those are written back into the index, and the clients are told with the same carrier
 * message P1 already sends.
 *
 * <h2>Performance: the index is the whole point</h2>
 * The P1 brief puts the author's world at roughly 840 host blocks. Nothing here may walk them: the
 * loop is over {@code index.carriers()} - a handful of structures in flight - and then over
 * <em>that carrier's own</em> hosts. The level-wide store is never enumerated, and a solved
 * coordinate is remembered so the same component reached from two seeds costs one hash lookup. On top
 * of that there are two ceilings, because a machine can be arbitrarily large:
 *
 * <ul>
 *   <li>{@code carrierSolveMaxComponentsPerTick} bounds how many components one carrier may settle in
 *       one pass. Hitting it is reported as an <em>effect budget</em> exhaustion rather than as a
 *       solve, so "it did not settle" is never mistaken for "it settled and nothing changed".</li>
 *   <li>{@code carrierSolveMaxNodesPerCarrier} bounds how many hosts the seeds are taken from, which
 *       is the only step that touches every host of a structure.</li>
 * </ul>
 *
 * <h2>Two facts the diagnostics have to keep apart</h2>
 * A structure whose components are all still holding exactly the values they were picked up with is
 * <em>solved</em> and has <em>nothing to say</em>: {@code solves} goes up and {@code lastDiff} stays
 * 0 - and with nothing outside it that is the whole of the observable behaviour, because nothing on
 * the machine changes by itself. "The solve ran" and "the solve found something" are different claims;
 * P2 promised the first, and P4 is what makes the second happen without an operator's help, by opening
 * the bridge the other way: a lever on the ground now reaches the machine, so the solve finally has an
 * input that can move. {@code /rc carrier} prints both, separately, for exactly that reason.
 */
public final class CarrierNetworks {

    /**
     * What one solve pass found for one structure.
     *
     * @param nodes how many host blocks of this structure hold a component that takes part in a solve
     * @param components how many components the last pass settled
     * @param solves how many passes this structure has had since it was picked up
     * @param lastTick the level tick of the last pass, or -1 when there has not been one
     * @param lastDiff how many local coordinates changed value in the last pass
     * @param unsentDiff how many local coordinates differ from what the clients were last told
     * @param pendingFlips how many driven components are waiting for their delayed re-evaluation
     * @param overflowed true when the component budget ran out before this structure was fully visited
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
     * Everything remembered about one structure between passes.
     *
     * <p>The changes are kept as <em>differences against what the clients were last told</em> rather
     * than as a list of positions: a later pass that changes the same coordinate again supersedes the
     * earlier entry instead of queueing a second copy, and the number the diagnostics print is then
     * the honest answer to "is there anything left to send".
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
     * The level tick each dimension was last solved at.
     *
     * <p>The solve has two drivers - the level tick and Create's own per-actor callback - because
     * neither is available in every situation (a Sable sub-level has no Create actor, and a Create
     * contraption may not be the only thing riding). Running both would solve twice per tick, which is
     * correct but wasteful, so a pass for a dimension that has already run in this tick is skipped.
     */
    private static final Map<ResourceKey<Level>, Long> SOLVED_AT = new HashMap<>();

    private CarrierNetworks() {
    }

    // ------------------------------------------------------------------ tick --

    /**
     * One solve pass over everything a level is carrying.
     *
     * <p>Cheap and empty while nothing is in flight: the first thing it does is ask a store that
     * answers "nothing" in one boolean. Called once per level tick from the same handler that already
     * ticks the carrier store, so the two halves of the feature cannot drift out of step.
     *
     * @param tick the level's own clock; only ever used to schedule a repeater's delay
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
                // One structure's solve must never take the tick down - or, through it, the server. AND
                // ONE FAILURE MUST NOT FLOOD THE LOG EITHER: this used to warn on every failure, and the
                // author's P1.24 log had 53 identical lines from one emptied host. The ledger counts
                // every failure, warns once per exception KIND (so a later, different defect is still
                // reported), and is bounded; see SolveFailureLedger.
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
     * One structure's pass, and everything that has to happen because of it.
     *
     * <ol>
     *   <li><b>Seeds.</b> The local coordinates of the components that take part in a solve, capped,
     *       with a host seen for the first time recorded as "what the clients already believe" - which
     *       is the honest answer for a block that has just been picked up, because the carrier message
     *       that announced it carried its contents too.</li>
     *   <li><b>Solve.</b> {@link CarrierLightNetwork} does the work.</li>
     *   <li><b>Compare.</b> Every logic host is compared against what the clients last saw. A slot
     *       whose value did not move is left exactly where it is, so "the solve ran and changed
     *       nothing" costs nothing beyond the solve itself.</li>
     *   <li><b>Write back and sync.</b> Only the coordinates that differ are recorded and sent.</li>
     * </ol>
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
            // AN EMPTY HOST IS NOT A SEED, AND IS NOT AN ERROR. The structure still carries the block
            // and the block holds nothing - the state a player leaves behind by taking the component out
            // (CarriedHostIndex#removeSlot) - so the honest answer is the one CarrierSpace#nodeAt already
            // gives for the same coordinate: "there is no component here". This dereference is what threw
            // 53 times in the author's P1.24 log (`Cannot read field "type" because "slot" is null`),
            // once per tick, from inside the per-structure catch below - no crash, but a machine whose
            // internal redstone silently stopped being computed.
            if (slot == null || !CarrierLightNetwork.isLogic(slot.type)) {
                continue;
            }
            long key = host.localPos().asLong();
            if (!state.synced.containsKey(key)) {
                // First time this host is seen by the solver: the clients were told its contents when
                // the structure picked it up, so that value is the baseline, not zero.
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
            // The same "an empty host carries nothing" rule as the seeds loop above, for the same
            // reason: this is the second of the two walks over the structure's hosts, and it read
            // `slot.type` unconditionally as well.
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
     * P4: the bridge one carrier's solve reads the world through, or {@code null} for the frozen
     * behaviour.
     *
     * <p>Resolved once per carrier per pass rather than per query: the pose is the expensive half (a
     * Create contraption has to be asked for three basis vectors, a Sable sub-level looked up in its
     * container), and asking it again for every side of every component would turn a per-tick cost
     * proportional to <em>structures</em> into one proportional to components. A structure whose pose
     * cannot be read is simply frozen for this pass - the honest answer, and the same one the renderer
     * and the landing arithmetic get.
     *
     * <p><b>P1.41: and the reader's own identity.</b> The carrier whose solve this is, plus the level the
     * projection table is indexed by, are handed over so the world read can refuse <em>this</em> carrier's
     * own projected hosts - the uncharged second copy of a neighbour the inner relationship already
     * delivers. Both are in hand here (the carrier is the loop variable, the level is on the context), and
     * no other carrier's projection is affected: see {@code CarrierWorldIo#signalIntoOwnFrame}.
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
     * Records the changed values in the index, so the renderer, {@code /rc dump} and the save all see
     * them, and tells the clients with the message P1 already sends.
     *
     * <p>This is the point of P2. The goggles draw whatever the carried record holds, so a structure
     * whose components were recomputed but whose record was not updated would look exactly like a
     * structure that was never solved. The index is marked dirty for the same reason: a machine that
     * is later put down must come home carrying the value it actually had.
     *
     * <p>The sync reuses {@code CarrierPayload} - no new message and no protocol bump. A carrier
     * message is already "this structure's pose plus its hosts, wholesale", which is precisely the
     * fact that changed.
     */
    private static void sync(CarrierStore.TickContext ctx, CarriedHostIndex index, CarrierId carrier,
                             CarrierState state) {
        boolean wrote = false;
        for (long key : state.changedSinceSync.keySet()) {
            BlockPos local = BlockPos.of(key);
            CarriedHost host = index.at(carrier, local);
            Slot current = host == null ? null : host.slot();
            // An empty host answers exactly like a coordinate the structure does not carry: there is
            // nothing to write back, so there is nothing to record and nothing to send. `updateSlot`
            // would accept a null slot - it is the named form of "the player emptied this block" - but
            // the solve must never be the thing that empties one, so this is a skip and not a write.
            if (current == null) {
                continue;
            }
            // The slot object is already the one the solver mutated; putting it back in the record is
            // what makes the new power the one everything else reads. Carried through rather than
            // copied so the two representations cannot disagree for even one tick.
            index.updateSlot(carrier, local, current);
            state.synced.put(key, current.power);
            wrote = true;
        }
        if (!wrote) {
            return;
        }
        ctx.writeBack().wrote(carrier);
        // Nothing is left unsent: what the clients were just told is now the baseline, so the count
        // the diagnostics print ("unsentDiff") means exactly what it says.
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
            // A diagnostics walk must not be the thing that fails a command: an emptied host is counted
            // as a host that holds nothing, never as a wire and never as a dereference.
            if (slot == null) {
                empty++;
                continue;
            }
            ComponentType type = slot.type;
            if (type.isWire()) {
                wires++;
            } else if (type.isDriven()) {
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

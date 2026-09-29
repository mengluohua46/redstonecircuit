package com.jiangyuefengyu.redstonecircuit.compat.physics;

import java.util.ArrayList;
import java.util.List;

import com.jiangyuefengyu.redstonecircuit.RCConfig;
import com.jiangyuefengyu.redstonecircuit.carrier.CarriedHost;
import com.jiangyuefengyu.redstonecircuit.carrier.CarriedHostIndex;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierId;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierIdentity;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierNetworks;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierPose;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierProjection;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierStore;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierTrace;
import com.jiangyuefengyu.redstonecircuit.carrier.SolveFailureLedger;
import com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneStore;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import org.jetbrains.annotations.Nullable;

/**
 * The "where is everything right now" half of {@code /rc physics} and {@code /rc carrier}: what the
 * carrier store is holding, where each structure is, and what happened to one particular block.
 *
 * <h2>Why this is part of the diagnostics rather than of the feature</h2>
 * The acceptance test for this stage is visual - "the component follows the machine" - so the
 * questions an operator actually asks when it does not are "did anything get picked up at all", "is
 * the block I am looking at carried or just empty", and "where does the mod think the machine is".
 * All three are answered from the server's own bookkeeping, with no reference to what is on screen,
 * and that is what makes them useful: the answer is either consistent with the picture or it is the
 * bug.
 *
 * <h2>Read-only, and never throws</h2>
 * Every entry point is a query. A diagnostic that can change state, or that can take the server down,
 * would be worse than the thing it is diagnosing.
 */
final class CarrierDiagnostics {

    /** How many carriers are printed in full before the list is summarised. */
    private static final int CARRIER_LIMIT = 8;

    /** How many local coordinates of one carrier are printed. */
    private static final int LOCAL_LIMIT = 12;

    private CarrierDiagnostics() {
    }

    /** Everything the carrier store knows about one level. */
    static List<String> describe(ServerLevel level, @Nullable BlockPos focus) {
        List<String> out = new ArrayList<>();
        out.add("carrierStore=" + (CarrierStore.isActive() ? "on" : "off")
                + " (carrierEnabled=" + RCConfig.carrierEnabled()
                + ", freezeWorldIo=" + RCConfig.carrierFreezeWorldIo()
                + ", traceCarrier=" + RCConfig.traceCarrier() + ")");
        // P2, and the two lines the acceptance procedure reads first: is the in-structure solve
        // switched on, and has it actually run. "carriers=0" with nothing in flight is an ordinary
        // state, not a failure - the number to compare against is the one in "carried:" below.
        out.add("carrierSolve=" + (RCConfig.carrierSolveEnabled() ? "on" : "off")
                + " (maxComponentsPerTick=" + RCConfig.carrierSolveMaxComponentsPerTick()
                + ", maxSeedsPerCarrier=" + RCConfig.carrierSolveMaxNodesPerCarrier()
                + ", traceCarrierSolve=" + RCConfig.traceCarrierSolve() + ")");
        out.add("  " + CarrierStore.lastSolve(level).describe()
                + "   (carriers>0 = a structure was solved on the last tick)");
        // P4: is the bridge between the machine and the world open, and is anything actually standing
        // at a world position because of it. "projected=0" with a carrier in flight is the first thing
        // to look at when a machine refuses to light the lamp it is flying over.
        out.add("  " + CarrierStore.describeBridge(level));
        out.add(CarrierStore.describe(level));

        // THE THREE STAGES, one line each, in the order they have to happen. This is the block a
        // reader checks first when "the component does not follow the machine": the claim, then the
        // wire, then the screen. Each line names the code that fills it, so a stage showing zero
        // points at one place rather than at the whole feature.
        CarriedHostIndex index = CarrierStore.indexOf(level);
        int provisional = 0;
        int entityNamed = 0;
        if (index != null) {
            for (CarrierId carrier : index.carriers()) {
                if (CarrierIdentity.isProvisional(carrier)) {
                    provisional++;
                } else {
                    entityNamed++;
                }
            }
        }
        out.add("  --- three stages (claim -> sync -> render) ---");
        out.addAll(CarrierTrace.describe(level.dimension()));
        // The "we drew it, but where" line, beside the stage counters that cannot tell the two apart.
        // `drawnLastFrame` counts emitted geometry; this says whether the geometry was emitted where the
        // structure is, which is the difference between "not drawn" and "drawn somewhere else".
        CarrierTrace.Render render = CarrierTrace.renderIfAny(level.dimension());
        out.add("  " + (render == null
                ? "drawCheck: the client has not reported a frame yet (single-player: same game)"
                : render.drawCheckSummary(level.dimension())));
        // THE GEOMETRY HALF. `drawnLastFrame` counts collected hosts, and the author's "the log says it
        // drew and I see nothing" is exactly a frame where that number is non-zero and no vertex was
        // ever written. These two lines carry the gates that decided it and one worked host.
        out.add("  " + (render == null
                ? "geometry: the client has not reported a frame yet"
                : render.geometrySummary()));
        out.add("  " + (render == null ? "geometry host: -" : render.geometryExample()));
        out.add("  carriers now: entityNamed=" + entityNamed + " provisional=" + provisional
                + (provisional > 0
                        ? "   (provisional = claimed before the contraption entity existed; the next "
                                + "tick reconciles it - look for a 'carrier reconcile' log line)"
                        : ""));
        out.add("  stage 1 is filled by CarrierStore.startMoving, stage 2 by HostSync.broadcastCarrier"
                + "/broadcastPose, stage 3 by ClientHostCache + HostRenderPass");
        // The single-exit rule, made visible. `sent` counts the "this carrier is gone" messages that
        // actually went out, and `suppressed` the ones that were owed to nobody because the client had
        // never been told about that carrier - the two are very different numbers and only one of them
        // means "the client was left holding something".
        out.add("  " + CarrierStore.describeRemovals(level));
        // The world side of the two transfers. "a release happened but the client was never told the
        // component is a world host again" is the shape of the defect that made a component invisible
        // after a structure stopped, so the two counters are printed next to the removal notices.
        out.add("  " + CarrierStore.describeWorldHostTransfer(level));

        if (index == null) {
            out.add("  (no carrier store for this level: it only exists on the server)");
            return out;
        }
        out.add("  displaced world positions=" + index.displacedWorldPositions().size()
                + "  carriers=" + index.carrierCount()
                + "  riding=" + index.carriedCount()
                + "  putDown=" + index.releasedCarrierCount());

        if (index.isEmpty()) {
            out.add("  (nothing has been picked up in this dimension since the world loaded)");
        }

        int shown = 0;
        for (CarrierId carrier : index.carriers()) {
            if (shown++ >= CARRIER_LIMIT) {
                out.add("  ... and " + (index.carrierCount() - CARRIER_LIMIT) + " more carrier(s)");
                break;
            }
            out.addAll(describeCarrier(level, index, carrier));
        }

        if (focus != null) {
            out.addAll(describeTarget(level, index, focus));
        }
        return out;
    }

    /** One carrier: its identity, its pose, and the local coordinates it is holding. */
    static List<String> describeCarrier(ServerLevel level, CarriedHostIndex index, CarrierId carrier) {
        List<String> out = new ArrayList<>();
        List<CarriedHost> hosts = index.hostsOf(carrier);
        int riding = 0;
        for (CarriedHost host : hosts) {
            if (!host.landed()) {
                riding++;
            }
        }
        out.add("  carrier " + carrier.shortId() + " ["
                + (carrier.isContraption() ? "create contraption" : "sable sub-level")
                + "] hosts=" + hosts.size() + " riding=" + riding
                + " uuid=" + carrier.uuid());

        CarrierPose pose = CarrierStore.poseOf(level, carrier);
        out.add("      pose=" + (pose == null
                ? "NONE (the structure is not in this level any more - the sweep will put its hosts back)"
                : pose.describe()));
        // THE POSE THE CLIENT IS ACTUALLY DRAWING, and the pose a release will land on. Since the
        // landing fix this is a first-class value rather than a diagnostic: when a sub-level is removed
        // its own pose is already gone, and this is what the hosts are put down against. Printing it
        // beside `pose=` is what makes "the component came home to the wrong block" answerable.
        CarrierPose last = CarrierStore.lastSentPose(carrier);
        out.add("      lastPose=" + (last == null
                ? "none sent yet (a release with no pose left will put the hosts back where they came "
                        + "from)"
                : last.describe()));
        // THE QUESTION THE AUTHOR'S REPORT NEEDED ANSWERED. A client drawing a four-second-old angle
        // looks exactly like a client drawing a parked machine, and this is the line that separates
        // them: how long ago the last pose actually went out, and whether it was a change or a
        // heartbeat. See CarrierStore#describePoseSync.
        out.add("      " + CarrierStore.describePoseSync(level, carrier));
        // A carrier still filed under a provisional identity is the one case where a pose of NONE is
        // expected rather than a failure: the claim was made before the contraption entity existed and
        // the next tick re-labels it. Saying so here is what stops that state being read as a bug.
        if (CarrierIdentity.isProvisional(carrier)) {
            out.add("      identity=PROVISIONAL (" + PhysicsCompat.provisionalDescription(carrier)
                    + ") - claimed before the contraption entity existed; the level tick reconciles it "
                    + "onto the entity's UUID (watch for a 'carrier reconcile' line)");
        }

        StringBuilder locals = new StringBuilder("      local=[");
        int limit = Math.min(hosts.size(), LOCAL_LIMIT);
        for (int i = 0; i < limit; i++) {
            CarriedHost host = hosts.get(i);
            if (i > 0) {
                locals.append(' ');
            }
            locals.append(host.localPos().toShortString());
            if (host.landed()) {
                locals.append("->").append(host.at().toShortString());
                if (host.conflict()) {
                    locals.append("(conflict)");
                }
            }
        }
        if (hosts.size() > limit) {
            locals.append(" ...+").append(hosts.size() - limit);
        }
        out.add(locals.append(']').toString());

        // P4: where those local coordinates are in the world right now. This is the line that answers
        // "the machine is drawn there, but where does the mod think its blocks are" - and the world
        // position is exactly where a lamp or a piece of wire has to be for the bridge to reach it.
        if (pose != null && riding > 0) {
            out.add("      projected=" + projected(pose, hosts));
        }

        // THE DRAW CHECK. One line, and it is the one that distinguishes "nothing was drawn" from
        // "something was drawn somewhere else" - a distinction no counter in this mod could make
        // before, and the reason a Sable sub-level could report `drawnLastFrame=1` while the player saw
        // nothing at all. `drawn=true` means the pose arithmetic puts the component somewhere; the
        // delta is how far that somewhere is from the structure's own bounds.
        out.add("      " + CarrierStore.drawCheck(level, carrier, index).describe());

        // P2: what the in-structure solve has made of this carrier. Read after "local=[...]" on
        // purpose - the local coordinates are what the solve is *about*, so a reader compares the two
        // lines against each other.
        out.addAll(CarrierNetworks.describe(carrier, index));
        return out;
    }

    /**
     * Every solve failure this process has had, as lines for {@code /rc carrier}.
     *
     * <p>Empty when nothing has failed, which is the ordinary state - so the section's absence is a
     * positive statement rather than silence. When it is not empty it is the only place the <em>count</em>
     * of a repeated failure can be read: the warning itself is written once per exception kind, so the
     * "53 times" behind the author's one line is answered here and nowhere else.
     */
    static List<String> solveFailures() {
        List<String> out = new ArrayList<>();
        List<String> ledger = SolveFailureLedger.report();
        if (ledger.isEmpty()) {
            return out;
        }
        out.add("      --- in-carrier solve failures (each kind warns once; the count is here) ---");
        out.addAll(ledger);
        return out;
    }

    /**
     * "local->world TYPE out=N" for every host still riding, as one line for {@code /rc carrier}.
     *
     * <p>The output strength is printed on purpose: it is the one number that changes when the bridge
     * works, in either direction - the world lighting the machine shows up here, and it is the value
     * the machine is offering the world at the coordinate on the left.
     */
    private static String projected(CarrierPose pose, List<CarriedHost> hosts) {
        StringBuilder sb = new StringBuilder("[");
        int shown = 0;
        for (CarriedHost host : hosts) {
            if (host.landed()) {
                continue;
            }
            if (shown++ >= LOCAL_LIMIT) {
                sb.append(" ...+").append(hosts.size() - LOCAL_LIMIT);
                break;
            }
            if (shown > 1) {
                sb.append(' ');
            }
            sb.append(host.localPos().toShortString()).append("->")
                    .append(CarrierProjection.project(pose, host.localPos()).toShortString())
                    // AN EMPTY HOST SAYS SO. The structure carries the block and the block holds
                    // nothing - the state a player leaves behind by taking the component out - and this
                    // walk must not treat "no component" as an error: the world store has no entry for
                    // such a block either, and this is the same answer.
                    .append(' ').append(host.slot() == null
                            ? "empty"
                            : host.slot().type + " out=" + host.slot().power);
        }
        return sb.append(']').toString();
    }

    /** What the carrier store thinks about one world position - the "why is my block empty" answer. */
    static List<String> describeTarget(ServerLevel level, CarriedHostIndex index, BlockPos pos) {
        List<String> out = new ArrayList<>();
        out.add("  --- target " + pos.toShortString() + " (what the carrier store thinks) ---");
        InnerRedstoneStore store = InnerRedstoneStore.get(level);
        boolean worldEntry = store.slotAt(pos) != null;
        out.add("      world store entry: " + (worldEntry ? "yes" : "no"));
        out.add("      carried right now : " + index.isCarried(pos)
                + "   (true = its data is inside a moving structure)");
        out.add("      displaced here    : " + index.isDisplaced(pos)
                + "   (true = this position is one the carrier store answers for)");
        // P4: is a carried host virtually standing *here* - i.e. would a lamp placed on this block be
        // lit by a machine flying over it.
        CarrierProjection.ProjectedHost projected = CarrierStore.projectedHostAt(level, pos);
        out.add("      projected here    : " + (projected != null)
                + "   (true = a carried component is virtually at this world position)");
        if (projected != null) {
            // What a lamp or a piece of wire here would actually read, which is the thing the operator
            // is trying to explain when they aim at a machine's block.
            out.add("      projected host    : " + projected.carrier().shortId() + " local="
                    + projected.localPos().toShortString() + " "
                    + (projected.slot() == null ? "empty (the structure carries the block and it holds "
                            + "no component)" : projected.slot().describe()));
        }

        CarrierId holder = index.carrierHolding(pos);
        if (holder != null) {
            out.add("      carrier           : " + holder.shortId());
            CarrierPose pose = CarrierStore.poseOf(level, holder);
            out.add("      carrier pose      : " + (pose == null ? "none" : pose.describe()));
        }
        var slot = index.slotAt(pos, com.jiangyuefengyu.redstonecircuit.carrier.CarriedHosts.slots(level));
        out.add("      contents (union)  : " + (slot == null ? "nothing" : slot.describe()));
        return out;
    }

    /** The short line {@code /rc carrier} prints first. */
    static String headline(ServerLevel level) {
        return CarrierStore.describe(level);
    }
}

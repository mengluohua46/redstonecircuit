package com.jiangyuefengyu.redstonecircuit.logic;

import org.jetbrains.annotations.Nullable;

import com.jiangyuefengyu.redstonecircuit.RCConfig;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierDirections;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierProjection;
import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.Slot;

import net.minecraft.core.Direction;

/**
 * Which of the three sources answers a {@code getSignal} query, and with what - one pure rule, so the three
 * cannot drift apart between the mixin that asks it and the tests that pin it.
 *
 * <p>The three are the <b>world store</b> (an ordinary host on the ground, quiet while the mod's own solver
 * reads the world, or a component would read its own output back), the <b>world projection</b> (a host a
 * structure carries, at the world position it occupies - the world sees air there) and the <b>structure
 * frame</b> (the same host at its coordinate inside the structure). Order: structure frame, world projection,
 * world store. The two carrier sources stay reachable under suppression, being other components and the only
 * route by which one physicalised structure reads its neighbour.
 */
public final class BlockSignalSources {

    private BlockSignalSources() {
    }

    /** Which source answered a query. Named, so a log line and a test can say which one fired. */
    public enum Source {
        /** Nothing holds a component at this position: vanilla's own answer stands. */
        NO_SIGNAL,
        /** The world store: an ordinary host block standing on the ground. */
        WORLD_STORE,
        /** The projection table: a host a structure is carrying, at its world position. */
        WORLD_PROJECTION,
        /** The structure's own coordinate: a host a structure is carrying, in its own frame. */
        STRUCTURE_FRAME
    }

    /**
     * One answered query: the source, the component it came from, the host record when a carrier answered,
     * the power, and what the other sources said. {@code answer} is 0-15 <b>before</b> the hop the reading
     * wire charges for it ({@link #hopChargedByReader}), which is a fact about the reader.
     * {@code structureFrameDeclined} is true when the structure frame had a host here and refused it because
     * it belongs to another live sub-level; {@code projectionHit} when the projection table holds a host
     * here, whether or not that source answered.
     */
    public record Read(Source source, @Nullable Slot slot,
                       @Nullable CarrierProjection.ProjectedHost host, int answer,
                       boolean structureFrameDeclined, boolean projectionHit) {

        /** True when a carrier - not the world store - answered. */
        public boolean fromCarrier() {
            return source == Source.WORLD_PROJECTION || source == Source.STRUCTURE_FRAME;
        }
    }

    /** The one answer that is not a source: nothing holds a component here. */
    public static final Read NO_SIGNAL = new Read(Source.NO_SIGNAL, null, null, 0, false, false);

    /** The same, with what the two carrier sources did recorded for the judgement line. */
    private static Read noSignal(boolean structureFrameDeclined, boolean projectionHit) {
        return structureFrameDeclined || projectionHit
                ? new Read(Source.NO_SIGNAL, null, null, 0, structureFrameDeclined, projectionHit)
                : NO_SIGNAL;
    }

    /**
     * True when the world store must stay quiet for this query: the one flag
     * {@code InnerRedstoneNetwork#queryingWorld} sets, kept as a method so the rule is stated once and the
     * diagnostic count has one place to live.
     */
    public static boolean suppressWorldStore() {
        boolean suppressed = InnerRedstoneNetwork.isAnySignalSuppressed();
        if (suppressed) {
            SignalReadTrace.suppressionBlocked();
        }
        return suppressed;
    }

    /**
     * The decision: answers a vanilla {@code getSignal} / {@code getDirectSignal} query from the three
     * sources, in the fixed order documented on this class. {@code worldSlot} is expected to be {@code null}
     * when the store is suppressed; {@code inStructure} arrives ownership-guarded, so a coordinate belonging
     * to another live sub-level is {@code null} here and falls through to {@code projected}, the source that
     * can answer "what stands where". {@code direction} is vanilla's, from the block asking towards the block
     * being asked, and {@code neighbourHoldsComponent} is consulted only for the strong question on the
     * world-store path.
     */
    public static Read select(@Nullable Slot worldSlot,
                              @Nullable CarrierProjection.ProjectedHost projected,
                              @Nullable CarrierProjection.ProjectedHost inStructure,
                              boolean structureFrameDeclined, boolean worldStoreSuppressed,
                              @Nullable Direction direction, boolean strong,
                              boolean neighbourHoldsComponent) {
        if (direction == null) {
            return noSignal(structureFrameDeclined, projected != null);
        }
        // (3) THE STRUCTURE FRAME, asked first for the branch it owns. The query is already in the structure's
        // own coordinates, so no direction conversion: rotating it again would swing the answer by the pose.
        if (inStructure != null) {
            if (inStructure.slot() == null) {
                return noSignal(false, projected != null);
            }
            return new Read(Source.STRUCTURE_FRAME, inStructure.slot(), inStructure,
                    CarrierProjection.emittedTowardInStructureFrame(inStructure, direction, strong,
                            neighbourHoldsComponent),
                    false, projected != null);
        }
        // (2) THE WORLD PROJECTION. A query made from the world names a world position, so the emitted
        // direction is turned back through the carrier's pose first. Reachable while the world is suppressed.
        if (projected != null) {
            if (projected.slot() == null) {
                return noSignal(structureFrameDeclined, true);
            }
            return new Read(Source.WORLD_PROJECTION, projected.slot(), projected,
                    CarrierProjection.emittedToward(projected, direction, strong,
                            neighbourHoldsComponent),
                    structureFrameDeclined, true);
        }
        // (1) THE WORLD STORE, and the suppression that hides it while the solver reads the world.
        Slot slot = worldStoreSuppressed ? null : worldSlot;
        if (slot == null) {
            return noSignal(structureFrameDeclined, false);
        }
        Direction emitted = direction.getOpposite();
        return new Read(Source.WORLD_STORE, slot, null,
                PowerSolver.signalAnswer(slot, emitted, strong, neighbourHoldsComponent,
                        RCConfig.hostConductsStrongPower()),
                structureFrameDeclined, false);
    }

    /**
     * The hop a <em>vanilla redstone wire</em> charges when it is the one asking.
     *
     * <p>Reading a neighbouring vanilla wire is already charged one step, so an outer wire at fifteen lights
     * the dust inside the block beside it to fourteen; the mirror image was never charged, and vanilla cannot
     * charge it for us, because the wire's own scan only recognises a neighbour whose <b>block state</b> is a
     * redstone wire - so it took our host's emission as a source value at full strength while our host read
     * the wire one step lower.
     *
     * <p>A hop is charged to the wire that <b>receives</b> it and only between two wires
     * ({@link PowerSolver#afterHop}); all this decides is <em>who the reader is</em> - a vanilla wire is
     * {@link ComponentType#DUST}, and every other reader reads the host at full strength, as vanilla does.
     */
    public static int hopChargedByReader(@Nullable Slot slot, int answer,
                                         boolean readerIsRedstoneWire) {
        if (!readerIsRedstoneWire || slot == null || answer <= 0) {
            return answer;
        }
        // At a block boundary the inner wire's own cost is what counts: charging the RECEIVER's cost made a
        // lossless superconductor inside a block hand the wire beside it fourteen. Scoped to this boundary
        // only - inside the solve two of our own components still charge the receiver's cost (afterHop).
        if (slot.type.isWire() && slot.type.hopCost() == 0) {
            return answer;
        }
        // Both must be wires for anything to be charged, which keeps a repeater or a torch inside a host
        // block a full-strength source for the wire beside it - vanilla's own behaviour.
        return PowerSolver.afterHop(ComponentType.DUST, slot.type, answer);
    }

    /**
     * The hop a <em>projection-sourced</em> answer owes the block that is reading it.
     *
     * <p>Such an answer reaches the solve through two halves of one merged read: {@code seamHop} charges one,
     * while the vanilla half asks {@code SignalGetter#getSignal}, landing in {@code BlockStateSignalMixin},
     * where {@link #hopChargedByReader}'s gate reads the reader's <b>block state</b>. A seam's reader is a
     * carried host whose world cell is air, so that gate answered "not a wire" and the neighbour arrived at
     * full strength; {@code Math.max} then kept that larger half, and two physicalised blocks one cell apart
     * read each other's dust at fifteen in both directions - a loop of gain one, so every value was a fixed
     * point and only moving the blocks apart brought it down.
     *
     * <p>This only says <em>which</em> receiver the reader is: a component of ours when the world can be
     * asked, and the ordinary vanilla wire its block state says it is when it cannot. It never answers zero.
     */
    public static int projectionChargedByReader(@Nullable CarrierProjection.ProjectedHost host,
                                                int answer, @Nullable ComponentType reader,
                                                boolean readerIsRedstoneWire) {
        if (reader != null) {
            return InnerRedstoneNetwork.seamHop(host, reader, answer);
        }
        return hopChargedByReader(host == null ? null : host.slot(), answer, readerIsRedstoneWire);
    }
}

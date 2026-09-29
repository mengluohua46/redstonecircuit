package com.jiangyuefengyu.redstonecircuit.logic;

import org.jetbrains.annotations.Nullable;

import com.jiangyuefengyu.redstonecircuit.RCConfig;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierDirections;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierProjection;
import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.Slot;

import net.minecraft.core.Direction;

/**
 * <b>Which of the three sources answers a {@code getSignal} query, and with what.</b> One pure rule,
 * so "the world store", "the machine projected here" and "the machine's own block here" cannot drift
 * apart between the mixin that asks it and the tests that pin it.
 *
 * <h2>The three sources, and the one that is not a source at all</h2>
 * A position in the level can hold a component in exactly one of three places:
 *
 * <ol>
 *   <li><b>the world store</b> - an ordinary host block standing on the ground. Suppressible, because
 *       while the mod's own solver is asking, our stored components must not report their own value
 *       back to it (that feedback would make a wire charge itself and the fixed point uncomputable);</li>
 *   <li><b>the world projection</b> - a host a structure is carrying, at the world position it
 *       currently occupies. It is <em>not</em> a world store entry: the block the world sees there is
 *       air, and the value is the {@code CarrierStore} projection table's answer in the carrier's own
 *       pose frame;</li>
 *   <li><b>the structure frame</b> - the same kind of host asked about at its coordinate <em>inside</em>
 *       the structure (P1.36). For a Sable sub-level that is a real level position, so "the piston the
 *       machine carries asks the host block beside it" arrives here.</li>
 * </ol>
 *
 * <h2>Why the two carrier sources are reachable while the world store is suppressed (P1.38)</h2>
 * {@code InnerRedstoneNetwork#queryingWorld} marks a level while the mod's own solver reads the world,
 * and the mixin used to answer <b>zero to every query</b> for that whole window. The world store has to
 * go quiet there - a component must never read its own output back - but the two carrier sources are
 * <em>other</em> components, not this solve's own, and they are the only route by which a component
 * inside one physicalised structure can read one inside its neighbour:
 *
 * <pre>
 *   B's solve (inside its structure)
 *     -&gt; CarrierWorldIo#signalInto   (B's local neighbour, projected into the world)
 *       -&gt; CarrierWorldSignals      (InnerRedstoneNetwork#worldSignals, i.e. under queryingWorld)
 *         -&gt; Level#getSignal -&gt; this rule
 * </pre>
 *
 * <p>Every reader on the carrier side goes through that window, so a window that answers nothing
 * everywhere makes the seam permanently dark: A's block could be projected right against B and B
 * would still read zero, with no way to tell that from "nothing is there". Suppression therefore hides
 * source 1 and no longer hides 2 and 3. That subtraction is the whole behavioural change, and it is
 * safe for the reason suppression exists: a value that comes from another carrier's host is not this
 * solve's own output.
 *
 * <h2>Order</h2>
 * <b>Structure frame, then world projection, then world store</b> - the first two are the branches that
 * are asked first because they only exist when something is at that position, and the world store is
 * the ordinary case that is left last. {@link #select} has the argument for why the P1.37 guard (a
 * coordinate belonging to another sub-level is refused by {@code CarrierStore#structureHostAt}, which
 * answers {@code null}) is what keeps the first two branches unambiguous.
 *
 * <h2>Cost</h2>
 * No allocation on any of the four paths: the record is one small object per answered query, and the
 * arguments are the two lookups the caller had to make anyway.
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
     * One answered query: the source, the component it came from, the host record when the answer came
     * from a carrier, the power, and what each source said.
     *
     * @param source which of the three (or none) answered
     * @param slot the component, or {@code null} when nothing answered
     * @param host the projected host when a carrier answered, or {@code null} for the world store
     * @param answer 0-15, what the component that answered emits - <b>before</b> the hop the reading
     *     wire charges for it (P1.44: {@link #hopChargedByReader}), which is a fact about the reader and
     *     not about which source answered
     * @param structureFrameDeclined true when the structure frame had a host at this coordinate and
     *     <b>refused</b> it because it belongs to another live sub-level (P1.37's guard). Part of the
     *     answer rather than of the caller, because a refused read can end with no answer at all and
     *     that is exactly the state the P1.38 judgement line exists to report
     * @param projectionHit true when the projection table holds a host at this world position, whether
     *     or not that source ended up answering
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
     * True when the world store must stay quiet for this query.
     *
     * <p>Reads the one flag {@code InnerRedstoneNetwork#queryingWorld} sets. Kept as a method so the
     * rule above is stated once rather than as an {@code if} the mixin could lose in a refactor, and so
     * the diagnostic count of "this suppression is what hid a carrier answer" has one place to live.
     */
    public static boolean suppressWorldStore() {
        boolean suppressed = InnerRedstoneNetwork.isAnySignalSuppressed();
        if (suppressed) {
            SignalReadTrace.suppressionBlocked();
        }
        return suppressed;
    }

    /**
     * <b>The decision.</b> Answers a vanilla {@code getSignal} / {@code getDirectSignal} query from the
     * three sources, in the fixed order documented on this class.
     *
     * @param worldSlot the component the <b>world store</b> holds at this position, or {@code null} -
     *     the caller is expected to have passed {@code null} when the store is suppressed
     * @param projected the host the <b>projection table</b> holds at this world position, or {@code null}
     * @param inStructure the host the <b>structure frame</b> holds at this coordinate, or {@code null}.
     *     The caller's lookup is ownership-guarded (P1.37), so a coordinate belonging to another live
     *     sub-level arrives here as {@code null} and falls through to {@code projected} - which is the
     *     source that can answer "what stands where"
     * @param structureFrameDeclined true when that guarded lookup found a host and refused it, which is
     *     the P1.37 decline and the one state a log line has to be able to name
     * @param worldStoreSuppressed true while the mod's own solver is reading the world; it hides the
     *     first source only. See the class comment for why the two carrier sources stay reachable
     * @param direction vanilla's direction, i.e. from the block asking towards the block being asked
     * @param strong whether this is the strong-power question ({@code getDirectSignal})
     * @param neighbourHoldsComponent whether the block doing the asking holds a component of its own -
     *     only consulted for the strong question, and only for the world-store path (the carrier paths
     *     answer it themselves, exactly as they did before)
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
        // (3) THE STRUCTURE FRAME, asked first for the branch it owns. The query is already in the
        // structure's own coordinates, so the one direction conversion the world frame needs is
        // deliberately absent: rotating it again would swing the answer by the machine's orientation.
        // P1.36's ability.
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
        // direction has to be turned back through the carrier's pose before "does this repeater emit
        // that way" is asked. Reachable while the world is suppressed - that is P1.38's fix, and the
        // only path by which one physicalised structure reads another.
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
     * <b>P1.44: the hop a <em>vanilla redstone wire</em> charges when it is the one asking.</b>
     *
     * <h2>What was missing, stated as one asymmetry</h2>
     * Reading a neighbouring vanilla wire is already charged one step - {@code InnerRedstoneNetwork
     * #readSignal} runs every neighbour through {@code neighbourSignal(raw, wire)}, so the author's own
     * first hop measures fourteen: an outer wire at fifteen lights the dust inside the block beside it to
     * fourteen. The mirror image was never charged, and vanilla cannot charge it for us: the wire's own
     * scan ({@code RedStoneWireBlock#getWireSignal}) only recognises a neighbour whose <b>block state</b>
     * is a redstone wire, and a component inside an ordinary host block is a block of stone to it. So the
     * wire took our host's emission as a <em>source</em> value - full strength, like a repeater's - while
     * our host read the wire one step lower. A line of them then held each other up at fifteen, which is
     * exactly what the author measured.
     *
     * <h2>The rule, and why it is this rule and not a new one</h2>
     * A hop is charged to the wire that <b>receives</b> it and only between two wires - {@link
     * PowerSolver#afterHop(ComponentType, ComponentType, int)}, which is the same statement
     * {@code PowerSolver} has always made for our own nodes. All this method decides is <em>who the
     * reader is</em>: a vanilla redstone wire is {@link ComponentType#DUST} (the same component, at cost
     * one), and every other reader - a repeater, a comparator, a lamp, a piston, a superconductor
     * standing on the ground - is not a wire, so it still reads the host at full strength exactly as
     * vanilla does.
     *
     * <p>Deliberately a pure function of three plain values: the defect is one number on one hop, and
     * this way the number is asserted offline instead of only being reachable behind a running server.
     *
     * @param slot the component the host holds, or {@code null} when nothing answered
     * @param answer what that component emits, i.e. what {@link #select} chose
     * @param readerIsRedstoneWire whether the block doing the asking is
     *     {@code minecraft:redstone_wire} - the caller's one block read, and the whole of the new
     *     information here
     * @return 0-15, never negative
     */
    public static int hopChargedByReader(@Nullable Slot slot, int answer,
                                         boolean readerIsRedstoneWire) {
        if (!readerIsRedstoneWire || slot == null || answer <= 0) {
            return answer;
        }
        // P1.53: AT A BLOCK BOUNDARY THE INNER WIRE'S OWN COST IS WHAT COUNTS (author's report,
        // 2026-09-28): "a component inside a block outputs to the wire outside and loses a step; a long
        // connection is fine, but when the component inside IS the source the wire outside should be
        // fifteen as vanilla has it - the charge is too strict."
        //
        // It was: the read charged the RECEIVER's cost (the world wire's one), so a superconductor inside
        // a block - `hopCost()` zero, and whose whole promise is that a run of it carries what it was
        // given all the way - still handed the wire beside the block fourteen. A lossless inner wire is
        // therefore a source to the wire outside, exactly as a lever or a torch inside already is.
        //
        // Deliberately scoped to this boundary and NOT to `PowerSolver#afterHop`: inside the solve two
        // of OUR components still charge the receiver's cost, which is what the author's own GameTest
        // pins (`superconductingDustMixesWithOrdinaryDust`: ordinary dust on the far side of a
        // superconductor fades by one, "as it always does"). The two rules are about two different
        // worlds - the solve's own pair, and a vanilla wire reading a block it cannot see inside.
        if (slot.type.isWire() && slot.type.hopCost() == 0) {
            return answer;
        }
        // The reader is the ordinary wire; the sender is whatever the block holds. Both must be wires
        // for anything to be charged, which is what keeps a repeater or a torch inside a host block a
        // full-strength source for the wire beside it - vanilla's own behaviour.
        return PowerSolver.afterHop(ComponentType.DUST, slot.type, answer);
    }

    /**
     * <b>P1.46: the hop a <em>projection-sourced</em> answer owes the block that is reading it.</b>
     *
     * <h2>The half P1.45 did not reach</h2>
     * A projection-sourced answer reaches the mod's solve through <b>two</b> halves of one merged read,
     * and P1.45 charged only one of them. {@code InnerRedstoneNetwork#projectedSignalAt} runs the answer
     * through {@code InnerRedstoneNetwork#seamHop}, but the other half is the <em>vanilla</em> one:
     * {@code InnerRedstoneNetwork#readSignal} on a {@code ServerLevel} asks
     * {@code SignalGetter#getSignal}, which lands in {@code BlockStateSignalMixin} - whose second source is
     * the very same projection table - and the charge {@link #hopChargedByReader} applies there is gated
     * on the <b>reader's block state</b> being {@code minecraft:redstone_wire}. The reader of a seam is a
     * carried host, whose world cell is air, so that gate answered "not a wire" and handed the neighbour
     * over at full strength; with {@code Math.max} keeping the larger of the two halves, the charged copy
     * never won. Two independently physicalised blocks one world cell apart therefore read each other's
     * dust at fifteen <b>in both directions</b> - a loop whose gain is exactly one, so every value from 0
     * to 15 is a fixed point: the pair held whatever it had when the supply was taken away, and only came
     * down when the blocks were moved apart.
     *
     * <h2>The rule: one arithmetic, and two ways of naming the receiver</h2>
     * A hop is paid by the receiver and only between two wires - {@code PowerSolver#afterHop}, which
     * {@code InnerRedstoneNetwork#seamHop} already states. All this method adds is <em>which</em> receiver
     * the reader is: a component of ours when the world can be asked
     * ({@code InnerRedstoneNetwork#seamReaderAt} - the carrier bridge names it outright, otherwise the
     * asker is the block one step back along the query direction), and the ordinary vanilla wire its block
     * state says it is when it cannot. Both paths end in the same arithmetic, so there is still exactly
     * one hop rule.
     *
     * <h2>What it deliberately does not decide</h2>
     * Which source may answer - P1.38's seam, P1.41/P1.42's refusal and P1.37's structure-frame guard are
     * all untouched - and it never answers zero: an unnamed reader with no wire in sight leaves the answer
     * exactly as it was before this round, the same honest degradation {@code seamHop} documents.
     *
     * @param host the projected host that answered, or {@code null} when nothing is projected there
     * @param answer what that component emits, i.e. what {@link #select} chose - before any hop
     * @param reader the component doing the reading, or {@code null} when the caller cannot name one
     * @param readerIsRedstoneWire whether the block one step back along the query direction is
     *     {@code minecraft:redstone_wire} - P1.44's one block read, and the only name a real vanilla
     *     reader has
     * @return 0-15, never negative
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

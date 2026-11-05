package com.jiangyuefengyu.redstonecircuit.carrier;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.jiangyuefengyu.redstonecircuit.RCConfig;
import com.jiangyuefengyu.redstonecircuit.data.ComparatorMode;
import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.Slot;
import com.jiangyuefengyu.redstonecircuit.logic.CarrierLightNetwork;
import com.jiangyuefengyu.redstonecircuit.logic.InnerRedstoneNetwork;
import com.jiangyuefengyu.redstonecircuit.logic.PowerEnvironment;
import com.jiangyuefengyu.redstonecircuit.logic.PowerSolver;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;

import org.jetbrains.annotations.Nullable;

/**
 * The <b>inside</b> of one moving structure, as a {@link PowerEnvironment}: the blocks a structure is carrying,
 * addressed by their coordinates inside that structure, rather than the world around a host block.
 *
 * <p>Three questions the solver asks. {@code nodeAt(local)} is the carried index for the local coordinate of
 * <em>this</em> carrier, so a component in the machine next door is simply not there and two structures can
 * never leak into one another however close they are in the world. The input on a side is <b>the larger of two
 * neighbourhoods</b>: the <em>world</em> half is what the world pushes into the place the host currently is,
 * read only while the bridge is open, and the <em>structure</em> half is the machine's own blocks, read at the
 * local neighbour coordinate with the same rule - a lever the machine carries travels with it and is not part of
 * the world, so that half is deliberately not closed by the same switch. {@code isRedstoneConductor(local)} is
 * the structure's own block there, through the view the compat half supplies.
 *
 * <p>It keeps no state that outlives a solve and it never walks the whole index: every lookup is for one local
 * coordinate, which is one hash lookup in the carrier's own map - the difference between "the hundreds of hosts
 * in this world" and "the handful in this structure".
 */
public final class CarrierSpace implements PowerEnvironment {

    private final CarrierId carrier;
    private final CarriedHostIndex index;
    private final CarriedHostIndex.SlotWorld world;
    @Nullable
    private final CarrierAccess access;

    /**
     * Whether a structure's own blocks are read as an input to the solve inside it.
     *
     * <p>A field rather than a config read per query, and a constructor argument rather than only the
     * config, so a unit test can pin both settings without a loaded config file.
     */
    private final boolean structureSignals;

    /**
     * The structure's own blocks, resolved <b>once per pass</b>.
     *
     * <p>Resolving it costs a container lookup (Sable) or an entity lookup (Create), which is why the ceiling
     * is "once per carrier per pass" and not "once per side of every component". A failed resolution is
     * remembered as "no view" for the rest of the pass, so one broken structure cannot make every subsequent
     * query throw - and it is never retried in a loop.
     */
    @Nullable
    private BlockGetter structureView;
    private boolean structureViewResolved;

    /**
     * The network the host being asked about belongs to, remembered for that coordinate; see {@link #readerCells()}.
     * Cleared implicitly when the next question is about another component - one pass asks about each of them many
     * times, and the walk itself costs a hash lookup per cell.
     */
    @Nullable
    private Set<BlockPos> readerCellsAt;
    @Nullable
    private BlockPos readerCellsFor;

    /**
     * Whether this pass has already logged the structure half supplying an input: once per pass per carrier,
     * because one per query would be a line per component per side per tick.
     */
    private boolean structureSignalLogged;

    /**
     * Whether this pass has already named the world half's lossless-pair read: once per pass per carrier, the same
     * gate as {@link #structureSignalLogged} and for the same reason. What the line reports - "this neighbour was
     * asked what it would be without me, not what it holds" - is a property of the arrangement, not of the tick, so
     * one line per pass says everything a second line would.
     */
    private boolean worldPairLogged;

    /** Whether this pass has already reported the structure half being skipped, and why. */
    private boolean structureSilentLogged;

    /** Whether this pass has already reported a structure-side read that found a block and said 0. */
    private boolean structureZeroLogged;

    /** Warned once for the whole process; see {@link #noteStructureReadFailure}. */
    private static volatile boolean structureReadWarned;

    /** How many such warnings have been logged; read by the unit test and by nothing else. */
    private static volatile int structureReadWarnings;

    /**
     * The open side to the world, or {@code null} when the bridge is closed.
     *
     * <p>Null is the whole of the {@code carrierFreezeWorldIo} switch as far as this class is concerned, and it
     * is also what a caller passes when there is no compat half to read the world with or no pose to read it by.
     *
     * <p><b>It closes the world half only.</b> A lever the machine is carrying travels with it and is not part of
     * the world, so the structure half reads it whatever this is - see {@link #externalSignal(Direction)}.
     */
    @Nullable
    private final CarrierWorldIo io;

    /**
     * The local coordinate the following {@code externalSignal} queries are about.
     *
     * <p>The interface's directional questions are position-relative and are not handed a position - the solver
     * already knows which component it is looking at and says so through {@link #setQueryPosition}.
     */
    private BlockPos queryPos = BlockPos.ZERO;

    /**
     * @param access how to read the structure's own blocks, or {@code null} when there is no such thing
     *     installed - in which case every conductor answers "no" and the structure half of the input is simply
     *     absent, the world half being unaffected. That is safe: a jar without the compat half has no structures
     *     to be inside of
     * @param io how to reach the world around this structure, or {@code null} for the frozen behaviour, which
     *     closes the world half and nothing else
     */
    public CarrierSpace(CarrierId carrier, CarriedHostIndex index, CarriedHostIndex.SlotWorld world,
                        @Nullable CarrierAccess access, @Nullable CarrierWorldIo io) {
        this(carrier, index, world, access, io, RCConfig.carrierStructureSignals());
    }

    /**
     * The same, with the structure-own-signals switch stated outright, so a unit test can pin <em>both</em>
     * settings without a loaded config file, which a test cannot have. Production always uses the constructor
     * above.
     */
    public CarrierSpace(CarrierId carrier, CarriedHostIndex index, CarriedHostIndex.SlotWorld world,
                        @Nullable CarrierAccess access, @Nullable CarrierWorldIo io,
                        boolean structureSignals) {
        this.carrier = carrier;
        this.index = index;
        this.world = world;
        this.access = access;
        this.io = io;
        this.structureSignals = structureSignals;
    }

    /**
     * <b>The inside of one live structure, wired the way a solve wires it.</b> The one place the plumbing a
     * joined frame needs - the index, the level's slot accessor, the structure's own blocks and the bridge
     * that names this carrier as the reader - is assembled from a level rather than from a tick context.
     *
     * <p>It exists because the merged solve now reaches inside a structure (a carried lossless network joins
     * the world's own solve), and that solve has no {@link CarrierStore.TickContext} to borrow: it runs from
     * {@code InnerRedstoneNetwork#settle}, which owns a level. Everything here is the same wiring
     * {@code CarrierNetworks#worldIoOf} and {@code CarrierStore#contextOf} already do; nothing new is decided.
     */
    public static CarrierSpace forSolve(ServerLevel level, CarrierId carrier) {
        CarriedHostIndex index = CarrierStore.indexOf(level);
        CarrierStore.TickContext context = CarrierStore.contextOf(level, index);
        CarrierPose pose = CarrierStore.poseOf(level, carrier);
        CarrierWorldIo io = pose == null ? null
                : new CarrierWorldIo(pose, InnerRedstoneNetwork.worldSignals(level), carrier, level);
        return new CarrierSpace(carrier, index, context.slots(), context.access(), io);
    }

    /** Which structure this is the inside of. */
    public CarrierId carrier() {
        return carrier;
    }

    /** How many host blocks of this structure currently hold a component. */
    public int nodeCount() {
        return index.hostsOf(carrier).size();
    }

    /** The local coordinates of every host this structure is carrying, in index order. */
    public List<BlockPos> localPositions() {
        List<CarriedHost> hosts = index.hostsOf(carrier);
        List<BlockPos> out = new ArrayList<>(hosts.size());
        for (CarriedHost host : hosts) {
            out.add(host.localPos());
        }
        return out;
    }

    /**
     * Reads one local coordinate's component, or {@code null} when that coordinate carries none.
     *
     * <p>The "no" answer covers both "not one of ours" and "one of ours with nothing in it", because those are
     * the same fact to the solver - and the vast majority of the coordinates it ever asks about are not in the
     * index at all, which is one hash lookup.
     */
    @Override
    @Nullable
    public Node nodeAt(int x, int y, int z) {
        CarriedHost host = index.at(carrier, new BlockPos(x, y, z));
        if (host == null) {
            return null;
        }
        Slot slot = readSlot(host);
        return slot == null ? null : new CarrierNode(slot);
    }

    /**
     * The caller's write access to a host's contents.
     *
     * <p>Exposed because the solve loop mutates the slots the nodes point at, and a caller that wants to prove
     * what happened - a test, a diagnostic - needs the same route in. Handing out the world accessor rather than
     * a second one is what keeps "what the solver changed" and "what gets saved" the same object.
     */
    public CarriedHostIndex.SlotWorld world() {
        return world;
    }

    /**
     * The strongest input reaching this host, from the world <b>and from the structure's own blocks</b>: a host
     * block riding a machine has two neighbourhoods, and the answer is the larger of the two.
     *
     * <p>One loop over {@link #externalSignal(Direction)} rather than a second, separate maximum, so the
     * per-direction path is the only implementation and both forms of the query reach the diagnostics line.
     */
    @Override
    public int externalSignal() {
        int best = 0;
        for (Direction direction : Direction.values()) {
            best = Math.max(best, externalSignal(direction));
            if (best >= PowerSolver.MAX_POWER) {
                return PowerSolver.MAX_POWER;
            }
        }
        return best;
    }

    /**
     * The input reaching this host across one of its sides: the larger of the world's answer and the structure's
     * own answer.
     *
     * <p>The world half is untouched: {@code io == null} - the frozen bridge, a structure whose pose cannot be
     * read, a build without the compat half - still answers zero for it. The structure half is deliberately
     * <b>not</b> gated on that: a lever the machine is carrying is not part of the world the machine has left.
     *
     * <p>The world half is read with <b>this carrier's own projected hosts refused</b>, because the one thing an
     * external supply and an inner neighbour do not have in common is the hop charge: the inner relationship
     * charges it and an external supply does not. Letting the projection answer for a coordinate this carrier
     * holds handed the neighbour over twice, and the maximum kept the unattenuated copy - a run of carried dust
     * stopped fading. Every other projection is still read, which is the seam with another structure.
     *
     * <p>A <b>lossless</b> seam - two wires that both charge nothing for a hop - is asked a different
     * question: not the strength the world block holds, but the strength it would hold <b>without</b> this host.
     * Handing over the neighbour's own strength makes the pair's equations {@code A = B} and {@code B = A}, whose
     * fixed point is every value, so a strength nothing supplies any more would stand there for ever. The choice
     * is {@link com.jiangyuefengyu.redstonecircuit.logic.PowerSolver#losslessSeam}, made by
     * {@link CarrierWorldIo#signalIntoOwnFrame}.
     *
     * <p><b>The world half is where the same rule has to be made again, by hand.</b> The seam choice above
     * recognises a neighbour that is one of <em>our</em> components - a store slot, or a host another structure
     * carries at that world position. It cannot recognise a superconducting wire (超导线) lying on this
     * structure's surface, because such a wire is a <b>block state of the level</b> and no solve of ours owns it:
     * nothing writes its {@code POWER}, so what stands there is whoever last wrote it, and copying it is gain
     * exactly one. See {@link #worldSignal(Direction)} - whenever that wire is beside a <b>lossless</b> host of
     * this structure, the world half asks the wire's own supply instead of what the wire holds, which is the same
     * ordered question the structure half already asks of a wire of the machine's own blocks.
     */
    @Override
    public int externalSignal(Direction direction) {
        if (direction == null) {
            return 0;
        }
        int worldHalf = io == null ? 0 : worldSignal(direction);
        int structureSignal = structureSignal(direction);
        if (structureSignal > worldHalf) {
            logStructureSignal(direction, structureSignal, worldHalf);
        }
        int answer = Math.max(worldHalf, structureSignal);
        logHostInput(direction, worldHalf, structureSignal, answer);
        return answer;
    }

    /**
     * <b>What this host is being fed, on one side, and what it holds.</b>
     *
     * <p>The missing half of every latch report so far: {@code PAIR SIDES} says what the partner's own supply is,
     * {@code HOST INPUT} says which side of <em>this</em> host is feeding it and what the host's own record holds at
     * that moment. A host that reads zero on all six sides while holding fifteen is a solver that never came down;
     * a host that reads fifteen on one side has a real source there, and the report is about that cell instead.
     * Diagnostics only: {@code debugLog}, and at most one line a second across all carriers and sides.
     */
    private void logHostInput(Direction direction, int world, int structure, int answer) {
        if (!RCConfig.debugLog() || !hostInputDue()) {
            return;
        }
        Slot self = selfSlot();
        RCConfig.LOGGER.info("[redstonecircuit] HOST INPUT at {} side={} :: world={} structure={} answer={} "
                        + "stored={} type={}",
                queryPos.toShortString(), direction.getName(), world, structure, answer,
                self == null ? -1 : self.power, self == null ? "none" : self.type);
    }

    /** When the host-input line was last written, for its wall-clock throttle. */
    private static long hostInputAt;

    /** True at most once a second, so one reproduction gives a readable trace. */
    private static boolean hostInputDue() {
        long now = System.currentTimeMillis();
        if (now - hostInputAt < 1000L) {
            return false;
        }
        hostInputAt = now;
        return true;
    }

    /**
     * <b>The world half of one side</b>: what the world offers this host across it, asked the pair's own question
     * when the neighbour is a lossless superconducting wire drawn in the world.
     *
     * <p>两个方向上的无损配对（一个 hopCost()==0 的本体元件 + 一格超导线方块）若各自把对方的<em>存量</em>当供给，
     * 两个等式就是互为拷贝，增益恰为一，于是"谁都不再供的强度"成为不动点 - 这就是那个 latch：结构表面上的
     * 超导线被点一次之后，源拿走了它仍然亮着。修法与结构那一半完全对称：不问"你持有什么"，而问
     * "没有我你会是多少" - 那格线自己六面的供给，把本 host 的格排除掉。最大值里丢掉自己那一项只会<b>降低</b>
     * 答案，所以这不可能点亮任何原本是暗的东西，而线另一侧的真实源（火把、拉杆、另一台机器的方块）仍然
     * 满强度抵达。
     *
     * <p>Everything else keeps the stock read: an ordinary component, a neighbour that is not a wire block at all,
     * a wire belonging to <b>another</b> structure's own block storage (that is the seam, and its own solve owns
     * it), and every failure - no bridge, no pose, a level that will not answer, a read that throws. A failure
     * falls back to the strength the stock read gave, <b>never to zero</b>, because a bridge that cannot answer
     * must look like a jar without it.
     *
     * <p><b>Both frames, exactly as the structure half reads this same cell.</b> A cell of a structure has two
     * neighbourhoods and its value is the larger of what they push into it, so asking one of them is not the wire's
     * own supply but half of it - {@link #ownSupplyOf} is the two-frame statement of that, and this is the same
     * question at the same coordinate, so it has to be the same read. The two frames see different sources and
     * neither can see the other's: the <b>world</b> frame sees a vanilla torch or lever standing beside the drawn
     * wire, a block of another machine across the seam, and <b>nothing</b> of what this machine carries -
     * {@code signalIntoOwnFrame} refuses this carrier's own projected hosts by its own rule, so a carried torch
     * beside the wire is invisible there. The <b>structure</b> frame sees every host this carrier holds, read from
     * its record, and nothing of the level: it is what finds the torch the machine itself carries beside the wire,
     * or another host of this machine standing in that row. Dropping either frame is a darkening, which is the one
     * failure this rule must never cause.
     *
     * <p><b>Recursion.</b> The own-supply read goes through {@link CarrierWorldIo#signalIntoOwnFrame} per side (the
     * world frame) and, in the structure frame, through the carrier index and the structure's own blocks - and
     * neither path comes back through this method. A host reading itself would otherwise be an unbounded walk.
     */
    private int worldSignal(Direction direction) {
        CarrierWorldIo bridge = io;
        if (bridge == null) {
            return 0;
        }
        int stored = bridge.signalIntoOwnFrame(queryPos, direction);
        BlockPos neighbour = queryPos.relative(direction);
        if (!worldPairReadApplies(neighbour)) {
            return stored;
        }
        int ownSupply;
        try {
            BlockGetter view = structureView();
            Set<BlockPos> reader = readerCells();
            // The structure frame is OFFERED only when the view resolved, and the decision is made here rather
            // than inside the read: InnerRedstoneNetwork#readSignal dereferences the view without a guard
            // (getBlockState), so a missing view handed to that frame is an NPE inside the read. A lambda is
            // exactly what lets "cannot be asked" be expressed as "is not offered", and it is what makes the
            // composition below a pure function of two things a test can hand in.
            ownSupply = pairOwnSupply(
                    () -> worldSupplyOf(neighbour, reader),
                    view == null ? null : () -> structureSupplyOf(neighbour, reader, view));
        } catch (RuntimeException | LinkageError unreadable) {
            // The pair read is an extra question, so its failure costs the stock answer and nothing else.
            return stored;
        }
        logWorldPairRead(direction, neighbour, stored, ownSupply);
        return worldHalfAnswer(stored, ownSupply, true);
    }

    /**
     * <b>Whether this host and the world block beside it are a lossless pair</b> - the one condition under which
     * the world half asks the neighbour's own supply instead of copying what it holds.
     *
     * <p>Three facts, and the cheap one first. A host that is not itself a lossless partner can never be half of a
     * pair, and that is every piece of dust, every torch, repeater and comparator in the world - two type
     * comparisons and <b>no level read</b>, which matters because this runs once per side of every host. Only a
     * superconducting host pays for the block read, and only when there really is a wire block beside it does the
     * ownership question (bounded by the number of live sub-levels) get asked at all.
     */
    private boolean worldPairReadApplies(BlockPos neighbour) {
        if (!losslessPartnerHere()) {
            return false;
        }
        CarrierWorldIo bridge = io;
        if (bridge == null) {
            return false;
        }
        try {
            return worldPairRead(bridge.holdsLosslessWireAt(neighbour), carrier,
                    bridge.structureOwnerOf(neighbour));
        } catch (RuntimeException | LinkageError unreadable) {
            // A neighbour that cannot be named keeps the stock read: the seam is safer than a guess.
            return false;
        }
    }

    /**
     * <b>The world half's pair rule, as one question about three facts</b>, so it is pinned offline by a test that
     * cannot build a carrier bridge.
     *
     * <p>{@code losslessWireBeside} is "the world block at that cell is a lossless superconducting wire", read from
     * the level by {@link CarrierWorldIo#holdsLosslessWireAt}; the caller has already established the half that
     * needs no level - that <em>this</em> host is itself a lossless partner ({@link #losslessPartnerHere()}), the
     * same {@code PowerSolver#losslessSeam} test the run side makes.
     *
     * <p>The owner is the seam guard. A wire block standing at a cell that also belongs to <b>another</b> live
     * structure's own block storage is that machine's own block, solved by its solve; rewriting the read into a
     * pair read would ask a question about a grid we do not own and could answer 0 where that machine really holds
     * a strength. So another owner keeps the stock read, while {@code null} (the open world) and this host's own
     * carrier (one machine, one grid - 同一台机器) are the pair's own case.
     */
    static boolean worldPairRead(boolean losslessWireBeside, @Nullable CarrierId self,
                                 @Nullable CarrierId neighbourStorageOwner) {
        if (!losslessWireBeside) {
            return false;
        }
        return neighbourStorageOwner == null || neighbourStorageOwner.equals(self);
    }

    /**
     * <b>The world half's answer for one side, as one function of the two reads and the rule's verdict.</b>
     *
     * <p>Not a maximum. A pair's answer <em>is</em> the other half's own supply: taking {@code max(stored, own)}
     * would keep the copied strength standing whenever the wire happened to hold more than it is being supplied,
     * which is precisely the latch - so the copy is replaced rather than compared. {@code pairRead == false} is
     * the stock answer, unchanged, for every neighbour that is not a lossless partner.
     */
    static int worldHalfAnswer(int stored, int ownSupply, boolean pairRead) {
        return pairRead ? ownSupply : stored;
    }

    /**
     * <b>One frame's answer to "what would the partner hold without me", asked only if the composition needs it.</b>
     *
     * <p>A <em>supplier</em> rather than a number for two reasons this read has always had. The frames are two
     * different tables - the world through the bridge (one level read per side), the structure through the carrier
     * index and the machine's own blocks - so asking a frame that cannot answer would be wasted work, and a frame
     * with no view at all is not "an answer of zero" but a question that must not be put (see
     * {@code InnerRedstoneNetwork#readSignal}, which dereferences the view). And it lets the composition below stay
     * a pure function of two things a test can hand in without a level, a bridge or a pose.
     */
    @FunctionalInterface
    interface FrameSupply {

        /** What that frame says the partner would hold without this host, 0-15. */
        int supply();
    }

    /**
     * <b>The partner's own supply in both frames a cell of a structure really has</b> - the one statement of the
     * pair read's composition, so the two halves of a pair cannot combine the frames differently.
     *
     * <p>A cell of a structure has two neighbourhoods and its value is the larger of what they push into it, so the
     * answer is the <b>larger of the two frames</b> and never one of them. The world frame alone is blind to what
     * this machine carries: a lever the machine holds beside the wire answers only through the carrier index, and
     * the bridge's read refuses this carrier's own projections by its own rule. The structure frame alone is blind
     * to the level: a vanilla torch standing beside the drawn wire is no block of any structure. Dropping either
     * one is a darkening - it answers 0 at a wire that really holds 15, which is the one failure this rule must
     * never cause.
     *
     * <p>{@code null} means <b>that frame cannot be asked at all</b>, and contributes nothing: a structure whose own
     * blocks cannot be resolved keeps the world frame's answer, which is the answer this read made before the second
     * frame existed - so an unreadable structure can never make the read worse than it already was.
     *
     * <p>Pure, static and package-private on purpose. This composition is the decision a review can get wrong and a
     * mutation can silently revert, and a private method behind a live bridge and a level cannot be driven by a
     * test; in this form the two-frame rule is pinned where it is decided.
     */
    static int pairOwnSupply(@Nullable FrameSupply worldFrame, @Nullable FrameSupply structureFrame) {
        int own = worldFrame == null ? 0 : worldFrame.supply();
        return structureFrame == null ? own : Math.max(own, structureFrame.supply());
    }

    /**
     * The comparator half of what the outside offers on one side: the neighbour block's own analogue output,
     * which only an inner comparator asks.
     *
     * <p>Two sources, both of them "the block next to the component where the component is drawn or stored": the
     * <b>world</b> block the local neighbour currently projects onto (a cake on the ground beside a machine's host
     * block), and the block at the neighbour's <b>local</b> coordinate when that coordinate is also a level
     * coordinate. That is true of a Sable sub-level - plot coordinates are real coordinates in the same level -
     * and false of a Create contraption's anchor-relative ones, so that one fact is the gate rather than a guess.
     *
     * <p><b>Known gap, recorded rather than hidden</b>: a cake <em>carried</em> beside a Create contraption's
     * inner comparator still reads 0, because the only view of a contraption's own blocks this class is handed is
     * a {@code BlockGetter} and the vanilla analogue question needs a level.
     */
    @Override
    public int analogOutput(Direction direction) {
        if (direction == null) {
            return 0;
        }
        int worldHalf = io == null ? 0 : io.analogOutputIntoOwnFrame(queryPos, direction);
        return Math.max(worldHalf, structureAnalogOutput(direction));
    }

    /** The analogue output of the structure's own block on one side, or 0 when it cannot be asked. */
    private int structureAnalogOutput(Direction direction) {
        if (io == null || !CarrierDrawCheck.plotSpace(queryPos)) {
            return 0;
        }
        // The direction is handed on, because a block may answer this question differently per side - the
        // machine's own helm does, and a structure's own space is where its local faces are its real faces.
        return io.analogOutputAtLocal(queryPos.relative(direction), direction);
    }

    /**
     * The signal the structure's <b>own</b> block on one side of this host is offering.
     *
     * <p>Read through the mod's one implementation of the read rule at the local neighbour coordinate and in the
     * local direction. That is the whole of what this exists for: the world half projects the local neighbour into
     * the world, which for a machine in flight is the air it is passing over, while the lever the machine is
     * carrying sits at the local coordinate and was never asked.
     *
     * <p>A failure - the structure is gone, its view throws, a sub-level was removed mid-pass - drops this half
     * for the rest of the pass and logs <b>once</b> for the process. It never propagates and is never retried in a
     * loop: the world half keeps working, which is the honest degradation.
     */
    private int structureSignal(Direction direction) {
        if (!structureSignals) {
            logStructureHalfSilent(direction, queryPos.relative(direction),
                    "the structure-own-blocks input is switched off for this carrier");
            return 0;
        }
        BlockGetter blocks = structureView();
        if (blocks == null) {
            logStructureHalfSilent(direction, queryPos.relative(direction),
                    "the structure's own blocks could not be resolved (this structure has no view)");
            return 0;
        }
        BlockPos neighbour = queryPos.relative(direction);
        // OUR OWN CARRIED HOSTS ARE NOT AN EXTERNAL INPUT. The structure half reads the blocks the machine is
        // carrying - vanilla levers, throttle levers, torches, wire - through the level at their own coordinates,
        // and that same read is answered by the carrier index as well, so without this guard a host the machine
        // carries would arrive here as an *external supply* as well as through its own path in the solve. Only
        // the inner neighbour relationship charges the wire hop, so the attenuation between two carried wires
        // would silently disappear; a coordinate this carrier holds is therefore skipped here.
        if (index.at(carrier, neighbour) != null) {
            logStructureHalfSilent(direction, neighbour,
                    "the neighbour is one of this carrier's own hosts");
            return 0;
        }
        try {
            // A WIRE OF THE STRUCTURE'S OWN BLOCKS IS NOT AN EXTERNAL BLOCK - and the shape that matters is the
            // one the index cannot see: a superconducting wire placed in the world and then carried is a block
            // state of the structure, not a host of this carrier, so no solve of ours owns it and nothing of ours
            // ever writes its POWER. Read as its stock strength it is a supply copied at the receiver's own cost,
            // which for a lossless receiver is nothing - and the pair then holds itself up after the lever is off.
            //
            // It must NOT be cut, though: this host and that wire are one machine feeding each other in both
            // directions, which is how a machine on its own surface works at all. So a LOSSLESS pair asks the other
            // question instead - what the wire block's own sides push into it, with THIS host's cell left out. A
            // value is max(own supply, neighbours), so dropping our own term can only lower the answer: a real
            // source on the wire's far side still arrives at full strength, while a pair that supplies nothing but
            // itself comes down to zero. Ordinary dust is not a lossless partner at all - it pays a hop, so a pair
            // with it cannot hold itself up - and neither is a torch or a repeater inside this host.
            if (InnerRedstoneNetwork.structureHoldsLosslessWire(blocks, neighbour) && losslessPartnerHere()) {
                int supply = ownSupplyOf(neighbour, blocks);
                logPairSides(direction, neighbour, blocks, supply);
                logStructureHalfRead(direction, neighbour, blocks, supply);
                return supply;
            }
            int answer = InnerRedstoneNetwork.readSignal(blocks, neighbour, direction);
            logStructureHalfRead(direction, neighbour, blocks, answer);
            return answer;
        } catch (RuntimeException | LinkageError unreadable) {
            // Both halves of "this structure cannot be read": the resolved view is dropped so no later
            // query of this pass touches it again, and the warning is a one-off for the process.
            structureView = null;
            structureViewResolved = true;
            noteStructureReadFailure(unreadable);
            return 0;
        }
    }

    /**
     * <b>Whether the lossless wire block beside this host is this host's own lossless partner.</b>
     *
     * <p>Both coordinates are of <b>one</b> structure here by construction - the wire is read at this carrier's
     * own coordinate through this carrier's own view, and this host is an entry of this carrier's index - which is
     * why the owner is named twice. The component types are not: the wire block is our superconductor (the caller
     * has established that already), while this host may hold anything, and only a lossless partner makes a pair.
     * A torch, a repeater or a comparator is a source and keeps the plain read.
     */
    private boolean losslessPartnerHere() {
        Slot self = selfSlot();
        return InnerRedstoneNetwork.losslessPairInOneStructure(self == null ? null : self.type,
                ComponentType.SUPERCONDUCTOR, carrier, carrier);
    }

    /** This host's own record, or {@code null} when the query coordinate carries no component of this structure. */
    @Nullable
    private Slot selfSlot() {
        CarriedHost host = index.at(carrier, queryPos);
        return host == null ? null : readSlot(host);
    }

    /**
     * <b>The partner's own supply, with this host's own cells left out - in <u>both</u> frames the wire's cell
     * really has.</b> The one statement of the pair read, used by both halves of every pair: the structure half
     * ({@link #structureSignal}) and the world half ({@link #worldSignal}) ask it at the same coordinate for the
     * same side, so neither can be the half-answer the other one is not.
     *
     * <p>Two frames because a cell of a structure has two neighbourhoods and its value is the larger of what they
     * push into it. The <b>structure</b> frame reads the machine's own tables - a host of this carrier beside the
     * wire is a <b>source</b> and answers from its record, everything else is the structure's own block - and it
     * is the only frame that can see what the machine itself carries. The <b>world</b> frame reads the place the
     * wire is drawn, refusing this carrier's own projections, and it is the only frame that can see a vanilla torch
     * or another machine's block standing there. Asking one of them is not the wire's own supply but half of it.
     *
     * <p>Leaving out this host's cells removes the pair and nothing else: a torch or another host further along
     * still feeds the wire, and through it, losslessly, this host.
     *
     * <p>The composition itself is {@link #pairOwnSupply} - the same function the world half uses, so the two
     * halves cannot combine the frames differently. Package-private so a unit test can drive this read with a
     * level-free bridge and an index host, which is the only way it can be pinned offline.
     *
     * <p>{@code blocks} must not be {@code null} - {@code InnerRedstoneNetwork#readSignal} dereferences the view
     * without a guard, so "there is no view" is the caller's decision to make, not something this read can
     * absorb. That decision is {@link #worldSignal}: a host with no view simply does not offer this frame.
     */
    int ownSupplyOf(BlockPos wireCell, BlockGetter blocks) {
        Set<BlockPos> reader = readerCells();
        return pairOwnSupply(() -> worldSupplyOf(wireCell, reader),
                () -> structureSupplyOf(wireCell, reader, blocks));
    }

    /**
     * <b>The network this host belongs to, in the carrier's own frame</b> - what a pair read has to leave out.
     *
     * <p>One cell was only ever the smallest loop: two hosts of one structure reading each other, three of them in a
     * triangle, or a run biting the same host from two cells all survive "without this cell". The set is collected by
     * the walk the carrier solve already uses ({@code CarrierLightNetwork#collectComponent}, from this host's own
     * coordinate) and then <b>narrowed to lossless wires</b>: a lever or a torch beside this host is connected to it
     * but is not part of the network whose echo the exclusion removes, and excluding it would darken the mechanism
     * this all exists for.
     *
     * <p>An empty or unusable answer falls back to this host's own cell, so a failure can only ever exclude less.
     * Remembered per query coordinate, because one pass asks about each component many times.
     */
    private Set<BlockPos> readerCells() {
        if (readerCellsAt != null && queryPos.equals(readerCellsFor)) {
            return readerCellsAt;
        }
        Set<BlockPos> cells = new HashSet<>();
        try {
            for (BlockPos cell : CarrierLightNetwork.collectComponent(this, queryPos)) {
                CarriedHost host = index.at(carrier, cell);
                Slot slot = host == null ? null : readSlot(host);
                if (slot != null && slot.type.isWire() && slot.type.hopCost() == 0) {
                    cells.add(cell.immutable());
                }
            }
        } catch (RuntimeException | LinkageError unreadable) {
            cells.clear();
        }
        cells.add(queryPos.immutable());
        readerCellsFor = queryPos.immutable();
        readerCellsAt = cells;
        return cells;
    }

    /** The wire block's own supply in the frame of the structure's own blocks and hosts. */
    private int structureSupplyOf(BlockPos wireCell, Set<BlockPos> reader, BlockGetter blocks) {
        return InnerRedstoneNetwork.supplyAround(wireCell, queryPos, (side, direction) -> {
            CarriedHost host = index.at(carrier, side);
            Slot slot = host == null ? null : readSlot(host);
            if (slot != null) {
                return PowerSolver.signalAnswer(slot, direction.getOpposite(), false, false,
                        RCConfig.hostConductsStrongPower());
            }
            return InnerRedstoneNetwork.readSignal(blocks, side, direction);
        }, reader);
    }

    /**
     * <b>The wire block's own supply in the other frame it has: the world it is drawn in.</b>
     *
     * <p>The mirror of the run side's world half ({@code InnerRedstoneNetwork#structureSupplyAt}), and for the same
     * reason: a cell of a structure has two neighbourhoods, and the supply that reaches it is the larger of the
     * two. This one answers what the world pushes into the wire's other five sides - a redstone torch standing in
     * the world beside the drawn wire, a block of another structure across the physicalisation seam - read through
     * the one world-signal implementation the solve already uses ({@code CarrierWorldIo#signalIntoOwnFrame}), with
     * this carrier's own projections refused.
     *
     * <p>The host's own cell is left out here too, mapped through the pose: it is the pair's own term, and counting
     * it is the latch the exclusion exists to remove.
     */
    private int worldSupplyOf(BlockPos wireCell, Set<BlockPos> reader) {
        return worldSupplyAround(io, wireCell, queryPos, reader);
    }

    /**
     * <b>The world frame's own supply around one cell, with the reader's own cells left out</b> - the read both
     * halves of a lossless pair make of the other half, stated once.
     *
     * <p>The arithmetic is {@link InnerRedstoneNetwork#supplyAroundExcept}, untouched: a cell's value is
     * {@code max(own supply, what its neighbours send it)}, the six sides are asked with the reader's cells skipped,
     * and the wire's own strength is never among them. Taking one term out of a maximum can only lower the answer,
     * which is why this can never light anything that was dark, while a real source on the far side still arrives -
     * losslessly - at full strength.
     *
     * <p><b>Each side is the block that side names</b>, asked the way vanilla asks: the bridge's read starts at
     * {@code wireCell} and looks one step along the direction, which is exactly the cell the side names, and it
     * passes the direction from the wire towards it - the same convention the structure frame's read above uses.
     * Handing the <em>side</em> to the bridge instead would ask {@code side.relative(direction)}, one cell too far:
     * the exclusion set would then guard a cell the read never touches (so it excluded nothing), and a real source
     * standing <b>beside</b> the wire - the torch on its far side - would be invisible to this frame.
     *
     * <p>{@code bridge} is a parameter, and the method is {@code static}, for one reason: a unit test can hand in a
     * bridge built without a level ({@code CarrierWorldIo#CarrierWorldIo(CarrierPose, CarrierWorldSignals)}), and
     * that is the only way to pin this read's exclusions offline. Production passes the bridge it reads everything
     * else with, so nothing is bypassed: every side still goes through
     * {@link CarrierWorldIo#signalIntoOwnFrame}, and that path ends in the level's own signal query - it never comes
     * back through {@link #externalSignal(Direction)}.
     *
     * <p>The host's own cell is added to the exclusion here as well as through {@code reader}: one caller's set
     * already names it and another need not, and excluding it twice costs nothing.
     */
    static int worldSupplyAround(@Nullable CarrierWorldIo bridge, BlockPos wireCell, @Nullable BlockPos hostCell,
                                 Set<BlockPos> reader) {
        if (bridge == null) {
            return 0;
        }
        // The same set, mapped through the pose: the two frames of one pair exclude the same cells.
        Set<BlockPos> excludedWorld = new HashSet<>(reader.size() + 1);
        for (BlockPos cell : reader) {
            excludedWorld.add(bridge.worldPosOf(cell));
        }
        if (hostCell != null) {
            excludedWorld.add(bridge.worldPosOf(hostCell));
        }
        return InnerRedstoneNetwork.supplyAround(wireCell, hostCell, null, (side, direction) -> {
            if (excludedWorld.contains(bridge.worldPosOf(side))) {
                return 0;
            }
            return bridge.signalIntoOwnFrame(wireCell, direction);
        });
    }

    /**
     * One line, once per pass per carrier, naming the world half's pair read: the host, the wire cell, the strength
     * the wire <b>holds</b> and the strength it would hold <b>without this host</b>.
     *
     * <p>Both numbers, because they are what makes the latch diagnosable at all: the stored one is the value that
     * used to be copied, the own-supply one is the value that replaces it - and it is the <b>larger of the two
     * frames</b> the wire's cell has, so the line reports the supply a pair is finally read with, not half of it.
     * A report of "the machine stayed lit after the lever was off" is answered by seeing that the first is a
     * leftover and the second is zero. Gated on {@code debugLog} and written at most once per pass for one carrier,
     * because a machine with two hundred components would otherwise print two hundred of them.
     */
    private void logWorldPairRead(Direction direction, BlockPos wireCell, int stored, int ownSupply) {
        if (worldPairLogged || !RCConfig.debugLog()) {
            return;
        }
        worldPairLogged = true;
        RCConfig.LOGGER.info("[redstonecircuit] carrier {} host local={} side={} wire local={} (world {}): "
                        + "the world half is a LOSSLESS PAIR - the wire HOLDS {} and would hold {} without this "
                        + "host in BOTH of its frames, so the second number is the answer and the first is not "
                        + "copied. A pair reading either other's stored strength sits at gain exactly one in both "
                        + "directions, and a strength nothing supplies any more would stand there for ever",
                carrier.shortId(), queryPos.toShortString(), direction.getName(), wireCell.toShortString(),
                describeWorldCell(wireCell), stored, ownSupply);
    }

    /**
     * Where a local cell currently is in the world, for the line above. Answered defensively and never thrown from:
     * a diagnostics line must not be able to take a solve down, and a pose is exactly the thing that can be
     * unreadable.
     */
    private String describeWorldCell(BlockPos localPos) {
        CarrierWorldIo bridge = io;
        if (bridge == null) {
            return "unavailable";
        }
        try {
            return bridge.worldPosOf(localPos).toShortString();
        } catch (RuntimeException | LinkageError unreadable) {
            return "unavailable";
        }
    }

    /**
     * The structure's own blocks, resolved once per pass.
     *
     * <p>{@code null} is the ordinary answer for "there is no such view": no compat half installed, or a
     * structure that is not there any more. It is <b>not</b> treated as a failure - see {@link CarrierAccess},
     * where answering {@code null} is documented as the normal case - so it does not warn.
     */
    @Nullable
    private BlockGetter structureView() {
        if (structureViewResolved) {
            return structureView;
        }
        structureViewResolved = true;
        CarrierAccess carrierAccess = access;
        if (carrierAccess == null) {
            return null;
        }
        try {
            structureView = carrierAccess.blockViewOf(carrier);
        } catch (RuntimeException | LinkageError unreadable) {
            structureView = null;
            noteStructureReadFailure(unreadable);
        }
        return structureView;
    }

    /** One warning for the whole process, however many structures fail to be read. */
    private void noteStructureReadFailure(Throwable failed) {
        if (structureReadWarned) {
            return;
        }
        synchronized (CarrierSpace.class) {
            if (structureReadWarned) {
                return;
            }
            structureReadWarned = true;
            structureReadWarnings++;
        }
        RCConfig.LOGGER.warn("[redstonecircuit] carrier {}: the structure's own blocks could not be "
                + "read ({}), so that half of its input is dropped for this pass - the world half is "
                + "unaffected. Logged once.", carrier.shortId(), failed.toString());
    }

    /** How many such warnings have been logged; the unit test's seam, and nothing else's. */
    static int structureReadWarnings() {
        return structureReadWarnings;
    }

    /**
     * One line, once per pass per carrier, when the structure's own blocks were <b>not</b> read at all.
     *
     * <p>"The structure half was never consulted" and "it was consulted and answered 0" look identical in a log
     * that only names a structure-side read when that read <em>wins</em>, and they are different failures with
     * different remedies, so they get different lines. This one names the reason the half was skipped, and the
     * reason is the whole answer: a switched-off setting, a structure with no view, or a neighbour that belongs
     * to this carrier's own hosts.
     */
    private void logStructureHalfSilent(Direction direction, BlockPos neighbour, String why) {
        if (structureSilentLogged || !RCConfig.debugLog()) {
            return;
        }
        structureSilentLogged = true;
        RCConfig.LOGGER.info("[redstonecircuit] carrier {} host local={} side={} neighbour={}: the "
                        + "structure's OWN blocks were NOT read as an input - {} - so this side's answer "
                        + "is the world half's alone; a device of the machine's own blocks here cannot "
                        + "feed the inside solve while that is true",
                carrier.shortId(), queryPos.toShortString(), direction.getName(),
                neighbour.toShortString(), why);
    }

    /**
     * One line, once per pass per carrier, when that read <b>was</b> made, found a real block next door, and still
     * answered zero: a device the machine is carrying is genuinely not supplying the inside solve - a lever that is
     * off, or a diode that does not emit that way - and that has to be tellable apart from the silence above. Air
     * is skipped rather than logged, because most of a machine is air.
     */
    private void logStructureHalfRead(Direction direction, BlockPos neighbour, BlockGetter blocks,
                                     int answer) {
        if (structureZeroLogged || answer != 0 || !RCConfig.debugLog()) {
            return;
        }
        BlockState state;
        try {
            state = blocks.getBlockState(neighbour);
        } catch (RuntimeException | LinkageError unreadable) {
            return;
        }
        if (state == null || state.isAir()) {
            return;
        }
        structureZeroLogged = true;
        RCConfig.LOGGER.info("[redstonecircuit] carrier {} host local={} side={} neighbour={}: the "
                        + "structure's own blocks WERE read - the block there is {} - and the read "
                        + "answered 0, so that device is not feeding the inside solve",
                carrier.shortId(), queryPos.toShortString(), direction.getName(),
                neighbour.toShortString(), state.getBlock());
    }

    /**
     * <b>Every side of a pair read, in both frames, and what was left out.</b>
     *
     * <p>Diagnostics only, and deliberately verbose: the composed number alone cannot say <em>which</em> side of the
     * wire supplied it, and a report of "the inside stays lit after the source is gone" is answered by seeing that
     * one particular side still answers fifteen while the wire itself holds zero. {@code [X]} marks a cell this read
     * excludes (the host itself, or a cell of the host's own lossless network); {@code s=} is the structure frame's
     * answer and {@code w=} the world frame's, both {@code -1} when the read threw.
     *
     * <p>Throttled by wall clock rather than per pass, because the question is asked thousands of times a second and
     * the reporter needs one readable line per second while reproducing by hand.
     */
    private void logPairSides(Direction direction, BlockPos wireCell, BlockGetter blocks, int answer) {
        if (!RCConfig.debugLog() || !pairSidesDue()) {
            return;
        }
        StringBuilder line = new StringBuilder(160);
        for (Direction side : Direction.values()) {
            BlockPos cell = wireCell.relative(side);
            boolean excluded;
            int structure;
            try {
                excluded = cell.equals(queryPos) || readerCells().contains(cell);
                CarriedHost host = index.at(carrier, cell);
                Slot slot = host == null ? null : readSlot(host);
                structure = slot != null
                        ? PowerSolver.signalAnswer(slot, side.getOpposite(), false, false,
                                RCConfig.hostConductsStrongPower())
                        : InnerRedstoneNetwork.readSignal(blocks, cell, side);
            } catch (RuntimeException | LinkageError unreadable) {
                excluded = false;
                structure = -1;
            }
            int world;
            try {
                world = io == null ? 0 : io.signalIntoOwnFrame(wireCell, side);
            } catch (RuntimeException | LinkageError unreadable) {
                world = -1;
            }
            line.append(' ').append(side.getName()).append(excluded ? "[X]" : "")
                    .append(" s=").append(structure).append(" w=").append(world).append(';');
        }
        RCConfig.LOGGER.info("[redstonecircuit] carrier {} PAIR SIDES host local={} side={} wire={}: answer={} ::{}",
                carrier.shortId(), queryPos.toShortString(), direction.getName(), wireCell.toShortString(),
                answer, line);
    }

    /** When the pair-side line was last written, for its wall-clock throttle. */
    private static long pairSidesAt;

    /** True at most once per second, so one reproduction gives a readable trace. */
    private static boolean pairSidesDue() {
        long now = System.currentTimeMillis();
        if (now - pairSidesAt < 1000L) {
            return false;
        }
        pairSidesAt = now;
        return true;
    }

    /**
     * One line, once per pass per carrier, when the structure's own blocks changed the answer: the only place in
     * the log that says a device the machine is carrying was read, and what it gave. Gated on {@code debugLog} and
     * emitted at most once per pass for one carrier, because a machine with two hundred components would otherwise
     * print two hundred of them.
     */
    private void logStructureSignal(Direction direction, int structure, int worldSignal) {
        if (structureSignalLogged || !RCConfig.debugLog()) {
            return;
        }
        structureSignalLogged = true;
        BlockPos neighbour = queryPos.relative(direction);
        RCConfig.LOGGER.info("[redstonecircuit] carrier {} host local={} side={} <- structure signal "
                        + "{} (the world gave {}), source={}: the machine's OWN block is feeding the "
                        + "inside solve",
                carrier.shortId(), queryPos.toShortString(), direction.getName(), structure, worldSignal,
                describeStructureBlock(neighbour));
    }

    /**
     * The block a structure-side read found, for the log line above. Answered defensively and never thrown from: a
     * diagnostics line must not be able to take a solve down, and a structure view is exactly the thing that can be
     * unreadable.
     */
    private String describeStructureBlock(BlockPos localPos) {
        BlockGetter blocks = structureView;
        if (blocks == null) {
            return "unavailable";
        }
        try {
            BlockState state = blocks.getBlockState(localPos);
            return state == null ? "unavailable" : String.valueOf(state.getBlock());
        } catch (RuntimeException | LinkageError unreadable) {
            return "unavailable";
        }
    }

    /**
     * Tells this space which of the structure's local coordinates the next questions are about.
     *
     * <p>The world reads are position-relative - "the block on that side of <em>this</em> host" - and the solver's
     * environment contract does not pass a position to {@code externalSignal}, so the solve loop announces the
     * component it is looking at, exactly as the world implementation is told through its own {@code setQueryPos}.
     */
    @Override
    public void setQueryPosition(BlockPos pos) {
        if (pos != null) {
            this.queryPos = pos;
        }
    }

    /** True while this space can read the world around the structure. Says nothing about the
     * structure's own blocks, which are read whatever this answers - see
     * {@link #externalSignal(Direction)}. */
    public boolean isBridged() {
        return io != null;
    }

    @Override
    public boolean isRedstoneConductor(int x, int y, int z) {
        BlockGetter blocks = structureView();
        if (blocks == null) {
            return false;
        }
        BlockPos local = new BlockPos(x, y, z);
        return blocks.getBlockState(local).isRedstoneConductor(blocks, local);
    }

    /**
     * The slot the host is holding at this moment, preferring the index's own copy.
     *
     * <p>The index is authoritative while a host is riding and the world accessor is authoritative once it has
     * been put down, so the same two-step read the index's own {@code slotAt} makes is made here rather than
     * reaching past it.
     */
    @Nullable
    private Slot readSlot(CarriedHost host) {
        if (!host.landed()) {
            return host.slot();
        }
        Slot live = world.get(host.at());
        return live != null ? live : host.slot();
    }

    /**
     * A solver node that can also name the slot behind it.
     *
     * <p>Written here rather than reusing {@code SlotNode} because of the one extra question: the solve loop
     * needs a repeater's delay and a debug value's injected supply, both of which live on the {@link Slot}
     * rather than in the {@link PowerEnvironment.Node} contract. Handing the slot over costs nothing and saves a
     * second index lookup per component per solve.
     */
    private record CarrierNode(Slot slot) implements CarrierLightNetwork.CarrierNode {

        @Override
        public ComponentType type() {
            return slot.type;
        }

        @Override
        public int power() {
            return slot.power;
        }

        @Override
        public void setPower(int power) {
            slot.power = PowerSolver.clamp(power);
        }

        @Override
        public Direction facing() {
            return slot.facing;
        }

        @Override
        public ComparatorMode mode() {
            return slot.mode;
        }

        @Override
        public com.jiangyuefengyu.redstonecircuit.logic.ControllerRule.Setting controller() {
            return slot.controller;
        }

        @Override
        public int transformer() {
            return slot.transformer;
        }

        @Override
        public void noteInput(int input) {
            slot.lastInput = input;
        }

        @Override
        public boolean open(Direction direction) {
            return slot.isOpen(direction);
        }

        @Override
        public boolean explicitlyCut(Direction direction) {
            return slot.isExplicitlyCut(direction);
        }

        @Override
        public boolean routingLocked() {
            return slot.isRoutingLocked();
        }
    }
}

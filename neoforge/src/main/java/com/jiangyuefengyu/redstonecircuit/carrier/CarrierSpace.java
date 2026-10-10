package com.jiangyuefengyu.redstonecircuit.carrier;

import java.util.ArrayList;
import java.util.List;

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
     * Whether this pass has already logged the structure half supplying an input: once per pass per carrier,
     * because one per query would be a line per component per side per tick.
     */
    private boolean structureSignalLogged;

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
     */
    @Override
    public int externalSignal(Direction direction) {
        if (direction == null) {
            return 0;
        }
        int worldSignal = io == null ? 0 : io.signalIntoOwnFrame(queryPos, direction);
        int structureSignal = structureSignal(direction);
        if (structureSignal > worldSignal) {
            logStructureSignal(direction, structureSignal, worldSignal);
        }
        return Math.max(worldSignal, structureSignal);
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

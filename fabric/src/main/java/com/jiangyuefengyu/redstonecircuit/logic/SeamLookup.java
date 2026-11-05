package com.jiangyuefengyu.redstonecircuit.logic;

import java.util.List;

import com.jiangyuefengyu.redstonecircuit.block.SuperconductingWireBlock;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierPose;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierProjection;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierId;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierSpace;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierStore;
import com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneStore;
import com.jiangyuefengyu.redstonecircuit.data.Slot;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import org.jetbrains.annotations.Nullable;

/**
 * <b>The world half of one merged solve, behind one interface.</b>
 *
 * <p>A merged solve is two things. The arithmetic over nodes is already abstracted - that is
 * {@link PowerEnvironment}, which has two implementations - while the questions about the <em>level</em>
 * (which cell is a run, which cells one run covers, what a host holds, what supplies a run, how a run is
 * written back) were asked of a {@code ServerLevel} from the middle of the solve itself. So the one part
 * of the solve that has no test double was the part the seam defects live in.
 *
 * <p>This interface is those questions and nothing else: no store, no level handle, no "give me the
 * world". Seven methods, each one a call that already stood in {@code InnerRedstoneNetwork} and names the
 * line it came from, so {@link #of} is a transcription rather than a rewrite and no rule moves with it.
 *
 * <p><b>What is deliberately NOT here.</b> Anything the arithmetic of one node needs rather than the
 * union's membership: a lock, a conductor test, a vanilla diode on a side, an analogue output, the merged
 * redstone read of a neighbouring world block. Those belong to {@link PowerEnvironment} or to the caller
 * that builds it, which is where the second implementation ({@code CarrierSpace}) already lives. Pulling
 * them in here would add six more methods that only restate {@code Level}, and a test double would then
 * have to reimplement a whole environment before it could test a union - the opposite of the point.
 */
public interface SeamLookup {

    /**
     * Whether this world cell is part of a superconducting run at all - the gate that decides whether a
     * cell beside a host can join the solve. (Was {@code collectUnion}:1432.)
     */
    boolean runAt(BlockPos cell);

    /**
     * The cells of the run this one belongs to. A run is one node of the solve, so the membership of that
     * node is a world fact the solve cannot derive. (Was {@code collectUnion}:1435.)
     */
    List<BlockPos> runCells(BlockPos origin);

    /**
     * What a host block of ours holds, or {@code null}. <b>The world store, never the carrier index</b>:
     * this is the same lookups the solve has always made, and a union member that rides a structure is a
     * different question (see {@link #carriedAt}). (Was {@code collectUnion}:1447 and the three reads in
     * {@code relax}:1488, :1512, :1540.)
     */
    @Nullable
    Slot slotAt(BlockPos pos);

    /**
     * The strength the world pushes into one cell of a run from anywhere but the run, read once per solve
     * because it cannot change while the solve runs. (Was {@code RunNode#connect}:1330.)
     */
    int supplyAt(BlockPos cell);

    /**
     * Writes a settled run back into the level. The run's own block state is the output of the solve, so
     * this is the one write the union has. (Was {@code relax}:1535.)
     */
    void writeRun(List<BlockPos> cells, int strength);

    /**
     * Every component connected to {@code seed} in the world store - the walk that turns one seed into the
     * union's subjects. (Was {@code collectUnion}:1450, delegating to {@code collectComponent}.)
     */
    List<BlockPos> componentAt(BlockPos seed);

    /**
     * The host another structure is carrying at this world cell, or {@code null}.
     *
     * <p>The consumer is the merged solve: a host carried at a cell of a union is a <b>port</b>, and its whole
     * lossless component joins as one node ({@code InnerRedstoneNetwork#collectUnion}). A seam that cannot be
     * joined - no pose, a released carrier, a component that is not wholly lossless - stays on the narrow rule
     * ({@code worldSignalIntoOwnFrame}) rather than answering zero.
     */
    @Nullable
    CarrierProjection.ProjectedHost carriedAt(BlockPos worldPos);

    /**
     * <b>The host another structure's own storage holds at a structure coordinate, or {@code null}.</b>
     *
     * <p>The second table a component of ours can stand in, and the one {@link #carriedAt} cannot answer:
     * that lookup is keyed by the <b>world position</b> a structure is drawn on, while this one is keyed by a
     * structure's <b>own</b> coordinate - and for a Sable sub-level that coordinate is a real level coordinate
     * (plot 坐标就是 level 坐标), so a cell of a carried network can hold a host of <em>another</em> carrier
     * whose only name is a plot coordinate. Two structures physicalised next to each other in their own frames
     * are that case, and a walk that asks only the projection table never sees them.
     *
     * <p>Answered by the <b>unguarded</b> structure lookup on purpose. The guarded one
     * ({@code CarrierStore#structureHostAt}) compares {@code plotOwnerAt} at the coordinate, and the coordinate
     * asked about lies inside the asking structure's own plot, where that comparison names the asker and
     * refuses exactly the host this question exists to find. The ownership question is not skipped, it is
     * <b>moved</b>: the caller has already established whose frame it is standing in, and the carrier
     * comparison that follows ("is this host a <em>different</em> carrier than the port I am inside") is the
     * guard.
     *
     * <p>{@code null} for every reason a host cannot be named - no carrier layer, no table, a coordinate no
     * structure's storage contains, a lookup that throws - and {@code null} is the refusal that leaves the seam
     * on the narrow rule rather than answering zero: a port that was never joined is still solved by its own
     * structure's pass.
     *
     * <p>A {@code default} method, because the walk is level-free: a fixture that assembles a union over its
     * own tables answers this where those tables are, and every existing implementation keeps compiling with
     * today's answer ("no such host here", so nothing joins and the seam stays on the narrow rule).
     */
    @Nullable
    default CarrierProjection.ProjectedHost structureHostAt(BlockPos structurePos) {
        return null;
    }

    /**
     * One structure the merged solve can reach <b>inside</b>, or {@code null} when it cannot be reached at
     * all: the pose its local sides project through, and the environment its own coordinates are solved in.
     *
     * <p>{@code null} is the refusal that has to fall back to the narrow seam rule rather than to zero - an
     * unloaded structure, one whose pose cannot be read, or one that has already been released. Answering
     * zero there would darken a machine that is working perfectly well.
     *
     * @param carrier the structure a {@link #carriedAt} port belongs to
     */
    @Nullable
    CarriedFrame frameOf(CarrierId carrier);

    /** One structure as a solve can reach it: see {@link #frameOf}. */
    record CarriedFrame(CarrierId carrier, CarrierPose pose, PowerEnvironment space) {
    }

    /**
     * The implementation production uses. Every method of it is the call that stood at the line named in
     * the method above, with the same arguments in the same order, so a solve through this object and a
     * solve through a {@code ServerLevel} cannot answer differently.
     */
    static SeamLookup of(ServerLevel level, InnerRedstoneStore store) {
        return new OfLevel(level, store);
    }
}

/**
 * The world as a {@link SeamLookup}: a level to read block states and runs from, and the store a merged
 * solve was already handed. Kept in this file because it is the transcription of a handful of calls and
 * has no state or policy of its own.
 */
final class OfLevel implements SeamLookup {

    private final ServerLevel level;
    private final InnerRedstoneStore store;

    OfLevel(ServerLevel level, InnerRedstoneStore store) {
        this.level = level;
        this.store = store;
    }

    @Override
    public boolean runAt(BlockPos cell) {
        return SuperconductingWireBlock.isSuperconductor(level.getBlockState(cell));
    }

    @Override
    public List<BlockPos> runCells(BlockPos origin) {
        return SuperconductingWireBlock.collectRun(level, origin);
    }

    @Override
    @Nullable
    public Slot slotAt(BlockPos pos) {
        return store.slotAt(pos);
    }

    @Override
    public int supplyAt(BlockPos cell) {
        return SuperconductingWireBlock.supplyAt(level, cell, store);
    }

    @Override
    public void writeRun(List<BlockPos> cells, int strength) {
        SuperconductingWireBlock.writeRun(level, cells, strength);
    }

    @Override
    public List<BlockPos> componentAt(BlockPos seed) {
        return InnerRedstoneNetwork.collectComponent(level, store, seed);
    }

    @Override
    @Nullable
    public CarrierProjection.ProjectedHost carriedAt(BlockPos worldPos) {
        return CarrierStore.projectedHostAt(level, worldPos);
    }

    /**
     * The other table: a host a structure's <b>own</b> storage holds at a plot coordinate. The unguarded
     * lookup, because the coordinate being asked about belongs to the structure the walk is already inside -
     * see the interface method for why the ownership comparison has to move to the caller.
     *
     * <p>{@code structureHostAtAnyOwner} already answers {@code null} for every failure (no layer, no table,
     * a lookup that throws), which is the refusal this interface asks for.
     */
    @Override
    @Nullable
    public CarrierProjection.ProjectedHost structureHostAt(BlockPos structurePos) {
        return CarrierStore.structureHostAtAnyOwner(level, structurePos);
    }

    @Override
    @Nullable
    public SeamLookup.CarriedFrame frameOf(CarrierId carrier) {
        CarrierPose pose = CarrierStore.poseOf(level, carrier);
        if (pose == null) {
            // No pose is "I cannot say where this structure is", which is a refusal, not a zero.
            return null;
        }
        try {
            return new SeamLookup.CarriedFrame(carrier, pose, CarrierSpace.forSolve(level, carrier));
        } catch (RuntimeException | LinkageError unreadable) {
            // A structure that cannot be resolved behaves exactly like one that is not there: the seam falls
            // back to the narrow rule. Never an exception out of a solve.
            return null;
        }
    }
}

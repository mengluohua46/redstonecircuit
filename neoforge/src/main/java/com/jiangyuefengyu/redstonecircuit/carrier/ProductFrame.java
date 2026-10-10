package com.jiangyuefengyu.redstonecircuit.carrier;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;

import org.jetbrains.annotations.Nullable;

/**
 * Where a placed structure <em>looks like</em> it stands, and what its six faces are asked there.
 *
 * <p>A physicalised product block stands at a <em>local</em> coordinate of whatever carries it and is
 * <em>drawn</em> at the pose that carrier reports, so a signal read at that coordinate asks about a
 * neighbour of the machine's own storage - a different neighbour, and for a contraption not in the level at
 * all. The rule is the one a player would state: read the six faces where the structure is drawn - the
 * neighbour's local coordinate projected by the pose, the local side turned into a world direction. Nothing
 * here writes, notifies or allocates, and a pose that cannot be read answers {@link #inPlace()} instead.
 */
public final class ProductFrame {

    /** The carrier this block is riding, or {@code null} when it is standing in the world. */
    @Nullable
    private final CarrierId carrier;

    /** The carrier's forward transform, or {@code null} for the in-place frame. */
    @Nullable
    private final CarrierPose pose;

    /**
     * Whether the carrier's <b>own storage is the level's own block grid</b> - true for a Sable sub-level, whose
     * blocks really are level blocks at their plot coordinates, and false for a Create contraption.
     *
     * <p>It decides one thing: <b>a neighbour that is itself part of the carrying structure is only visible in
     * the carrier's own storage</b>. Such a block is drawn beside the structure, but the level at that drawn
     * position holds air - it is in the plot - so reading only where the structure is drawn turns a structure
     * that was activated before the assembly into one that goes dark the moment it is picked up. With this true
     * the neighbour is asked in both places and the larger answer wins; the two can never both answer for one
     * neighbour, because a carried block is not in the world and a world block is not in the plot.
     */
    private final boolean storageInLevel;

    /**
     * The carrier's own blocks, for a carrier whose storage is <em>not</em> the level. A Create contraption
     * keeps its blocks in a map, so a block that travelled <em>with</em> the structure is not in the world at
     * any position and no {@code level.getSignal} can see it; the contraption side hands in this lookup
     * instead, and the frame takes the larger of it and the drawn position's answer. {@code null} for every
     * other frame, including a Sable one, where the level <em>is</em> the storage.
     */
    @Nullable
    private final CarriedSignals carriedSignals;

    /** What a carrier's own block emits across one of its local sides, or 0 when it holds nothing there. */
    @FunctionalInterface
    public interface CarriedSignals {

        /** The signal the carrier's block at {@code localNeighbour} answers across {@code localFace}. */
        int signalAt(BlockPos localNeighbour, Direction localFace);
    }

    private static final ProductFrame IN_PLACE = new ProductFrame(null, null, true, null);

    private ProductFrame(@Nullable CarrierId carrier, @Nullable CarrierPose pose, boolean storageInLevel,
                         @Nullable CarriedSignals carriedSignals) {
        this.carrier = carrier;
        this.pose = pose;
        this.storageInLevel = storageInLevel;
        this.carriedSignals = carriedSignals;
    }

    /** The frame of a block standing in the world: every question is asked where it is. */
    public static ProductFrame inPlace() {
        return IN_PLACE;
    }

    /**
     * The frame a carrier's own local coordinates are drawn in: for the one caller handed the carrier outright
     * (Create's actor tick, whose block is not in the level to be looked up). A {@code null} pose is the
     * in-place frame, so a caller never has to branch on it.
     */
    public static ProductFrame of(@Nullable CarrierPose pose) {
        return pose == null ? IN_PLACE : new ProductFrame(null, pose, false, null);
    }

    /**
     * The frame of a structure inside a Create contraption, with that contraption's own blocks readable: the
     * machine's blocks travel in a map, so a neighbour that rode along can only answer through this lookup.
     */
    public static ProductFrame of(@Nullable CarrierPose pose, @Nullable CarriedSignals carriedSignals) {
        return pose == null ? IN_PLACE : new ProductFrame(null, pose, false, carriedSignals);
    }

    /**
     * The frame the product block standing at {@code pos} is drawn in - or {@link #inPlace()}. One question to
     * the carrier layer and one to its source, both of which answer "no" cheaply while nothing is carried; every
     * failure answers the in-place frame, because "I could not tell where this is drawn" must never become "read
     * somewhere else".
     */
    public static ProductFrame carriedAt(@Nullable ServerLevel level, @Nullable BlockPos pos) {
        if (level == null || pos == null || !CarrierStore.isActive()) {
            return IN_PLACE;
        }
        CarrierStore.CarrierSource source = CarrierStore.source();
        if (source == null) {
            return IN_PLACE;
        }
        try {
            CarrierId owner = source.plotOwnerAt(level, pos);
            if (owner == null) {
                return IN_PLACE;
            }
            CarrierPose pose = CarrierStore.poseOf(level, owner);
            return pose == null ? IN_PLACE : new ProductFrame(owner, pose, owner.isSubLevel(), null);
        } catch (RuntimeException | LinkageError unreadable) {
            return IN_PLACE;
        }
    }

    /** True when this block is drawn by a carrier rather than standing where it is. */
    public boolean carried() {
        return this.pose != null;
    }

    /** The carrier this frame belongs to, or {@code null} for the in-place frame. */
    @Nullable
    public CarrierId carrier() {
        return this.carrier;
    }

    /**
     * The world position the block at local coordinate {@code localNeighbour} occupies - that coordinate itself
     * when nothing is carrying the block. The block <em>centre</em> through the pose, rounded to the block grid
     * ({@link CarrierProjection#project}), so "where is this drawn" has one implementation.
     */
    public BlockPos worldPosOf(BlockPos localNeighbour) {
        return this.pose == null ? localNeighbour : CarrierProjection.project(this.pose, localNeighbour);
    }

    /** The world side a local face points along - the face itself when nothing is carrying the block. */
    public Direction worldFace(Direction localFace) {
        return this.pose == null ? localFace : CarrierDirections.worldDirection(this.pose, localFace);
    }

    /**
     * What the block one step along {@code localFace} answers with. Both questions are real in a Sable sub-level:
     * whatever is <b>drawn</b> there - the world position this neighbour projects onto, with the local side turned
     * into a world direction, which is the answer for a redstone block standing on the ground beside a machine -
     * and the <b>carrier's own block</b> at the local coordinate, which is a level position there and is the
     * answer for a neighbour that travelled with the structure.
     *
     * <p>The larger of the two, because for one neighbour at most one of them is a block, and both of vanilla's
     * questions are asked in either frame, so the strong and weak halves cannot drift apart.
     */
    public int signalInto(ServerLevel level, BlockPos localNeighbour, Direction localFace) {
        int drawn = ask(level, worldPosOf(localNeighbour), worldFace(localFace));
        if (this.pose == null) {
            return drawn;
        }
        if (this.storageInLevel) {
            return Math.max(drawn, ask(level, localNeighbour, localFace));
        }
        return this.carriedSignals == null
                ? drawn
                : Math.max(drawn, this.carriedSignals.signalAt(localNeighbour, localFace));
    }

    /** Vanilla's own pair of questions at one position, the larger being what the design counts. */
    private static int ask(ServerLevel level, BlockPos pos, Direction side) {
        return Math.max(level.getSignal(pos, side), level.getDirectSignal(pos, side));
    }

    /** One line for the diagnostics: which carrier, and whether this frame reads anywhere but in place. */
    public String describe() {
        return this.pose == null ? "in-place"
                : "carried=" + (this.carrier == null ? "pose-only" : this.carrier.shortId())
                        + " storage=" + (this.storageInLevel ? "level" : (this.carriedSignals != null
                                ? "carrier+read" : "carrier"))
                        + " pose=" + this.pose.describe();
    }
}

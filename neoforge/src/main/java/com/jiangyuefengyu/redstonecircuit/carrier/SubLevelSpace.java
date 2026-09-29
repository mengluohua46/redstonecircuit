package com.jiangyuefengyu.redstonecircuit.carrier;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;

import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * <b>The one place that decides whether a world block is inside a Sable sub-level, and what its
 * coordinate inside that sub-level is.</b>
 *
 * <h2>Why this is a separate class, and why it names no Sable type</h2>
 * This is the arithmetic the whole Sable-side claim rests on, so it has to be checkable without a game
 * and without Sable installed. It therefore takes plain numbers - a sub-level's UUID, its local-space
 * bounds, its world-space bounds and its pose - instead of a {@code SubLevel}. The compat half is what
 * fills those in from Sable; everything else here is {@code BlockPos} and JOML.
 *
 * <h2>The two spaces, which is the whole subject of this class</h2>
 * A Sable sub-level has <b>two</b> coordinate systems and they are not interchangeable:
 *
 * <ol>
 *   <li><b>Local (plot) space.</b> The coordinate the sub-level's own blocks are stored under. A Sable
 *       plot is a fixed piece of a private grid, so these numbers are enormous - a real one measured at
 *       {@code (20481033, 132, 20485129)}. {@code logicalPose()} is defined on <em>this</em> space:
 *       {@code world = position + orientation * (local - rotationPoint) * scale}.</li>
 *   <li><b>World space.</b> Where the structure currently is, which is what
 *       {@code SubLevel#boundingBox()} reports and what a player's block coordinates mean.</li>
 * </ol>
 *
 * <p>A Create contraption has no such split: its local coordinates are small and relative to its
 * anchor. So the two carriers carry genuinely different kinds of local coordinate, and the only thing
 * that makes either correct is that the local coordinate and the pose came from <em>the same</em>
 * structure. {@link #localFor} and {@link #worldFor} are the pair that guarantees it: one is the exact
 * inverse of the other, both go through the structure's own pose, and {@link #verify} proves it on the
 * spot.
 *
 * <h2>The half block</h2>
 * {@link CarrierPose#blockCentre} is the method that names where a <em>block</em> is (its local
 * coordinate names its corner). Sable's pose transforms block <b>corners</b> - {@code logicalPose} of a
 * block's coordinate lands on that block's corner in the world, which is what makes
 * {@code plot.getBoundingBox()} transformed by the pose equal {@code SubLevel#boundingBox()} - so the
 * conversions here use {@link CarrierPose#transformPosition} rather than
 * {@link CarrierPose#blockCentre}. Using the block-centre form here would put every claimed component
 * half a block away from the block Sable is carrying, which is small enough to look like a rendering
 * wobble and large enough to be wrong.
 *
 * @param uuid the sub-level's own UUID: the stable half of the carrier identity
 * @param localMinX the local-space bounds, inclusive
 * @param localMinY see {@code localMinX}
 * @param localMinZ see {@code localMinX}
 * @param localMaxX the local-space bounds, inclusive
 * @param localMaxY see {@code localMaxX}
 * @param localMaxZ see {@code localMaxX}
 * @param worldMinX the world-space bounds of the structure, i.e. the local bounds through the pose
 * @param worldMinY see {@code worldMinX}
 * @param worldMinZ see {@code worldMinX}
 * @param worldMaxX see {@code worldMinX}
 * @param worldMaxY see {@code worldMinX}
 * @param worldMaxZ see {@code worldMinX}
 * @param pose the structure's own {@code logicalPose()}, or {@code null} when it could not be read
 */
public record SubLevelSpace(java.util.UUID uuid,
                                 int localMinX, int localMinY, int localMinZ,
                                 int localMaxX, int localMaxY, int localMaxZ,
                                 double worldMinX, double worldMinY, double worldMinZ,
                                 double worldMaxX, double worldMaxY, double worldMaxZ,
                                 @Nullable CarrierPose pose) {

    /**
     * The carrier identity for this sub-level.
     *
     * <p>One line, but it is the definition: the UUID alone, never the anchor and never the plot
     * position. A sub-level that is taken apart and rebuilt keeps neither of the latter, and a claim
     * filed under either would be unreachable afterwards.
     */
    public CarrierId carrier() {
        return CarrierId.subLevel(uuid);
    }

    /** True when this sub-level's pose could be read, i.e. when it can carry anything at all. */
    public boolean hasPose() {
        return pose != null;
    }

    /**
     * Whether a world block's <b>centre</b> is inside the structure's world bounds.
     *
     * <p>The centre, not the corner, because a block belongs to the structure if the structure covers
     * the block - and a block whose corner is just outside can still be half inside. Sable's own
     * bounds are reported with the max side plus one (see its {@code updateBoundingBox}), so the box
     * this is given is already a half-open block range, and the centre test is the one that matches it.
     */
    public boolean containsWorld(BlockPos worldPos) {
        if (worldPos == null) {
            return false;
        }
        double x = worldPos.getX() + 0.5;
        double y = worldPos.getY() + 0.5;
        double z = worldPos.getZ() + 0.5;
        return x >= worldMinX && x <= worldMaxX
                && y >= worldMinY && y <= worldMaxY
                && z >= worldMinZ && z <= worldMaxZ;
    }

    /**
     * The structure's own position, as the centre of its world bounds - the point
     * {@link com.jiangyuefengyu.redstonecircuit.carrier.CarrierDrawCheck} compares a draw point
     * against.
     *
     * <p>The centre of the world box rather than the pose's own origin, because the pose origin is a
     * pivot: for a machine whose blocks are all on one side of it the pivot can be a long way from the
     * geometry, and this number's only job is to answer "is the structure roughly here".
     */
    public Vector3d worldCentre() {
        return new Vector3d((worldMinX + worldMaxX) / 2.0, (worldMinY + worldMaxY) / 2.0,
                (worldMinZ + worldMaxZ) / 2.0);
    }

    /**
     * The local coordinate a world block sits at inside this structure, or {@code null} when there is
     * no pose to work it out with.
     *
     * <p><b>Rounded, not floored, and that is a real decision.</b> {@code BlockPos.containing} floors,
     * and flooring is wrong here for a reason that only shows up on a Sable pose: the world coordinates
     * of a sub-level's blocks are genuinely fractional (the measured example is {@code -352.549}), so a
     * block's own corner can land at {@code -319.5}, floor to block {@code -320}, and then transform
     * back to a local coordinate one whole block away from the one that produced it. A round trip
     * through such a mapping is not the identity, which would make every claim fail its own verification
     * and leave the components exactly where they started.
     *
     * <p>Rounding to the nearest integer keeps the sub-block offset instead of discarding it by up to a
     * block. What the carrier index is keyed by is then "the integer coordinate nearest to the block,
     * plus the structure's own fractional offset" - a slight translation of the key, invisible in game
     * (a component is drawn at its key and the block it sits in is half a block wide), and it is what
     * makes {@link #verify} pass for the blocks that really are inside the structure.
     */
    @Nullable
    public BlockPos localFor(BlockPos worldPos) {
        if (pose == null || worldPos == null) {
            return null;
        }
        Vector3d local = pose.transformPositionInverse(
                new Vector3d(worldPos.getX(), worldPos.getY(), worldPos.getZ()), new Vector3d());
        if (!local.isFinite()) {
            return null;
        }
        return new BlockPos((int) Math.round(local.x), (int) Math.round(local.y),
                (int) Math.round(local.z));
    }

    /** The world position a local coordinate sits at, or {@code null} when there is no pose. */
    @Nullable
    public Vector3d worldFor(BlockPos localPos) {
        if (pose == null || localPos == null) {
            return null;
        }
        Vector3d world = pose.transformPosition(
                new Vector3d(localPos.getX(), localPos.getY(), localPos.getZ()), new Vector3d());
        return world.isFinite() ? world : null;
    }

    /**
     * The local coordinate a world position maps to, or {@code null} unless the pose round trip puts
     * it back within {@code tolerance} blocks of where it started.
     *
     * <p>This is the guard the claim runs before it moves anything. A pose that does not round-trip is
     * a pose this code cannot reason about - a scale of zero, a structure whose pose is being written
     * while it is read - and claiming through such a pose would file the component under a local
     * coordinate that lands somewhere else entirely, which is precisely the failure the Sable path is
     * being fixed for. Refusing costs one pass; guessing costs the component.
     *
     * @param tolerance how far the round trip may land from the original world position
     */
    @Nullable
    public BlockPos localForVerified(BlockPos worldPos, double tolerance) {
        BlockPos local = localFor(worldPos);
        if (local == null) {
            return null;
        }
        return verify(worldPos, local, tolerance) ? local : null;
    }

    /**
     * Whether {@code local} really does land on {@code worldPos} through this structure's pose.
     *
     * <p>Compares the corner the pose produces with the block's own corner, because that is the pair
     * the claim is about: the component is filed under {@code local} and drawn at {@code local}, so if
     * that local coordinate's world position is the block the component was standing in, the claim is
     * right by construction.
     */
    public boolean verify(BlockPos worldPos, BlockPos local, double tolerance) {
        Vector3d back = worldFor(local);
        if (back == null || worldPos == null) {
            return false;
        }
        return back.distance(worldPos.getX(), worldPos.getY(), worldPos.getZ()) <= tolerance;
    }

    /**
     * The local coordinate of every block of this structure that currently holds a component.
     *
     * <h2>What it is for, and what it deliberately is not</h2>
     * This is the <b>read-only</b> half of the claim: it answers "if I took this sub-level's blocks,
     * which world positions would stand where a component is?" without moving anything. The claim
     * itself decides separately whether the block has really left the world (see
     * {@code SableCarrierClaim}), because for a sub-level that has merely been allocated over terrain -
     * an airship's balloon created before it lifts - the block is still in the world and taking the
     * component out of it would be a lie the player could see.
     *
     * <p>The walk is over the structure's own local bounds, which is its block count. That is bounded
     * by the plot a sub-level is given and is a one-off per sub-level, not a per-tick cost.
     *
     * @param hasComponent answers "does the block at this world position hold a redstone component?",
     *     so this class never has to know about the store
     * @param limit the most candidates to return, so a pathological structure cannot make one claim
     *     pass unbounded
     */
    public List<BlockPos> worldPositionsWithComponents(ComponentLookup hasComponent, int limit) {
        List<BlockPos> found = new ArrayList<>();
        if (pose == null || hasComponent == null || limit <= 0) {
            return found;
        }
        for (int x = localMinX; x <= localMaxX && found.size() < limit; x++) {
            for (int y = localMinY; y <= localMaxY && found.size() < limit; y++) {
                for (int z = localMinZ; z <= localMaxZ && found.size() < limit; z++) {
                    Vector3d world = worldFor(new BlockPos(x, y, z));
                    if (world == null) {
                        continue;
                    }
                    // The block this local coordinate is *in*, computed from the coordinate the pose
                    // produced rather than by flooring that coordinate: see {@link #nearestWorldBlock}.
                    BlockPos worldPos = nearestWorldBlock(world);
                    if (hasComponent.hasComponent(worldPos)) {
                        found.add(worldPos);
                    }
                }
            }
        }
        return found;
    }

    /**
     * The block a world point falls in, given that a structure's world positions are fractional on
     * every axis.
     *
     * <p>The same rounding {@link #localFor} uses, and for the same reason: a point at {@code -319.5}
     * is nearer the block {@code -319} than the block {@code -320} that flooring would name, and the two
     * conversions have to agree or the round trip is not the identity. A point exactly on a boundary
     * goes to the higher block, which is harmless either way - the two candidates are half a block
     * apart and the tolerance is a whole block.
     */
    public static BlockPos nearestWorldBlock(Vector3dc world) {
        return new BlockPos((int) Math.round(world.x()), (int) Math.round(world.y()),
                (int) Math.round(world.z()));
    }

    /**
     * Whether one world position holds a component; supplied by the caller so this class stays free of
     * the store (and of Minecraft's {@code Level}).
     */
    @FunctionalInterface
    public interface ComponentLookup {

        /** True when the block at this world position holds a redstone component. */
        boolean hasComponent(BlockPos worldPos);
    }

    /**
     * "Where does this structure put a local coordinate right now" - what a pose answers, extracted so a
     * caller can be handed the question without being handed a pose.
     *
     * <p>Exists for one caller: {@code WorldComponentAdoption#originFor}, which has to record an origin
     * for a component that was never taken out of a world block. That decision has to be testable without
     * a pose object and without a sub-level, and this is the smallest interface that makes it so.
     */
    @FunctionalInterface
    public interface WorldLookup {

        /** The world position of a local coordinate, or {@code null} when there is no pose to ask. */
        @Nullable
        Vector3d worldFor(BlockPos localPos);
    }

    /**
     * The centre of an axis-aligned box, as the "where is this structure" answer for a caller that has
     * only numbers.
     *
     * <p>Used by the compat half to build this record from Sable's own two boxes without repeating the
     * arithmetic, and by the tests.
     */
    public static Vector3dc centre(double minX, double minY, double minZ,
                                   double maxX, double maxY, double maxZ) {
        return new Vector3d((minX + maxX) / 2.0, (minY + maxY) / 2.0, (minZ + maxZ) / 2.0);
    }
}

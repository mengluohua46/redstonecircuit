package com.jiangyuefengyu.redstonecircuit.carrier;

import net.minecraft.core.Direction;

import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * Which way a structure's own sides point once it has been turned: local {@link Direction} to world
 * {@link Direction} and back.
 *
 * <p>Every conversion multiplies {@link CarrierPose#orientation()} - the quaternion recovered once from Sable's
 * {@code logicalPose()} or from a Create contraption's three basis answers ({@link CarrierPose#fromBasisColumns})
 * - and nothing else. {@code AbstractContraptionEntity#applyRotation}'s Euler order is not confirmed line by
 * line, so its three basis vectors may be asked for but no angle order invented from it.
 *
 * <p>A structure is rarely turned by a right angle, so a local side points between two world axes and the
 * "which way is that" answer is quantised to one of six by {@link #nearest}; a side's position is never
 * snapped, it is computed from the pose exactly ({@link CarrierProjection#project}).
 */
public final class CarrierDirections {

    private CarrierDirections() {
    }

    /** Where a local side points in the world; {@code local} itself when there is no rotation to apply. */
    public static Direction worldDirection(@Nullable Quaterniondc orientation, Direction local) {
        if (orientation == null || local == null) {
            return local;
        }
        return nearest(rotate(orientation, local, false));
    }

    /**
     * Which local side a world direction corresponds to; {@code world} itself when there is no rotation.
     *
     * <p>A vanilla query arrives in world directions while a component's own rules are written in local ones, so
     * the query has to be turned back before "does this repeater emit that way" is answered.
     */
    public static Direction localDirection(@Nullable Quaterniondc orientation, Direction world) {
        if (orientation == null || world == null) {
            return world;
        }
        return nearest(rotate(orientation, world, true));
    }

    /** {@link #worldDirection(Quaterniondc, Direction)} for a whole pose. */
    public static Direction worldDirection(@Nullable CarrierPose pose, Direction local) {
        return worldDirection(pose == null ? null : pose.orientation(), local);
    }

    /** {@link #localDirection(Quaterniondc, Direction)} for a whole pose. */
    public static Direction localDirection(@Nullable CarrierPose pose, Direction world) {
        return localDirection(pose == null ? null : pose.orientation(), world);
    }

    /**
     * The axis direction a turned vector points along most closely, by largest dot product rather than largest
     * component, so a caller that hands in a non-unit vector still gets the closest axis; a zero or non-finite
     * vector has no direction and answers {@link Direction#NORTH}, as a fresh component's facing defaults to.
     */
    public static Direction nearest(@Nullable Vector3dc vector) {
        if (vector == null || !vector.isFinite() || vector.lengthSquared() < 1.0E-12) {
            return Direction.NORTH;
        }
        Direction best = Direction.NORTH;
        double bestDot = Double.NEGATIVE_INFINITY;
        for (Direction candidate : Direction.values()) {
            double dot = vector.x() * candidate.getStepX()
                    + vector.y() * candidate.getStepY()
                    + vector.z() * candidate.getStepZ();
            if (dot > bestDot) {
                bestDot = dot;
                best = candidate;
            }
        }
        return best;
    }

    /**
     * Rotates one axis direction, in its own frame. JOML's {@code transform(src, dest)} uses {@code dest} as
     * scratch space as well as output, so the same vector must never be passed for both (see
     * {@link CarrierPose#transformPosition}).
     */
    private static Vector3d rotate(Quaterniondc orientation, Direction direction, boolean inverse) {
        Vector3d source = new Vector3d(direction.getStepX(), direction.getStepY(), direction.getStepZ());
        Vector3d out = new Vector3d();
        if (inverse) {
            orientation.transformInverse(source, out);
        } else {
            orientation.transform(source, out);
        }
        return out;
    }
}

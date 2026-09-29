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
 * <h2>This is P4's one arithmetic rule, and it goes through the quaternion</h2>
 * A host block inside a machine has sides, and the solver's rules are written in terms of those sides
 * ({@code PowerSolver#readsFrom} / {@code #emitsToward}). The world around the machine has sides too,
 * and vanilla signal queries are asked in <em>world</em> directions. Turning one into the other is
 * exactly a rotation by the structure's orientation:
 *
 * <pre>
 *   world = orientation * local          (what the pose does to a vector)
 *   local = orientation^-1 * world       (what is needed to read a world query back)
 * </pre>
 *
 * <p><b>The red line this class exists to hold.</b> The orientation used here is
 * {@code CarrierPose#orientation()}, i.e. the quaternion P1 recovered once (Sable's
 * {@code logicalPose()} directly, a Create contraption's three basis answers through
 * {@link CarrierPose#fromBasisColumns}). It is deliberately <b>not</b>
 * {@code AbstractContraptionEntity#applyRotation}'s internal Euler order, whose convention has never
 * been confirmed line by line: asking that black box for three basis vectors is safe, inventing an
 * angle order from it is not. Every direction conversion in this mod therefore multiplies by this
 * quaternion and nothing else.
 *
 * <h2>The block grid can only hold six directions</h2>
 * A structure is very often not turned by a right angle - P1 measured {@code 9.500deg} on a real
 * contraption - so a local side usually points <em>between</em> two world axes. Snapping to the
 * nearest of the six is the only faithful thing a block-grid world can express, and it is what
 * {@link #nearest} does. The <em>position</em> of a side is never snapped: it is computed from the
 * pose exactly (see {@link CarrierProjection#project}), so a machine turned 9.5 degrees still reads
 * the block it is really looking at; only the "which way is that" answer is quantised.
 */
public final class CarrierDirections {

    private CarrierDirections() {
    }

    /**
     * Where a local side points in the world.
     *
     * @param orientation the structure's own orientation, from {@link CarrierPose#orientation()}
     * @param local the side in the structure's coordinates
     * @return the nearest world axis direction, or {@code local} itself when there is no rotation to
     *     apply
     */
    public static Direction worldDirection(@Nullable Quaterniondc orientation, Direction local) {
        if (orientation == null || local == null) {
            return local;
        }
        return nearest(rotate(orientation, local, false));
    }

    /**
     * Which local side a world direction corresponds to.
     *
     * <p>This is the half the "structure inside -> world" bridge needs: a vanilla signal query arrives
     * in world directions, while the component's own rules are written in local ones, so the query has
     * to be turned back before "does this repeater emit that way" is answered.
     *
     * @return the nearest local axis direction, or {@code world} itself when there is no rotation
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
     * The one of the six axis directions a turned vector points along most closely.
     *
     * <p>Largest dot product rather than largest component: the input is a unit vector whenever the
     * orientation is a rotation, but a caller may hand in anything, and the dot product degrades
     * gracefully (it still picks the closest axis) where "compare the raw components" would not.
     * A zero or non-finite vector has no direction at all, and answers {@link Direction#NORTH} - which
     * is the same "no information" answer a fresh component's facing defaults to.
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
     * Rotates one axis direction, in its own frame.
     *
     * <p>The source and the destination are two different vectors on purpose: JOML's
     * {@code transform(src, dest)} uses {@code dest} as scratch space as well as writing into it, so
     * passing the same object for both silently cancels the rotation. That mistake has already cost
     * this project one round of work (see {@link CarrierPose#transformPosition}), and a unit test
     * whose rotation happens to be the identity would not notice it.
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

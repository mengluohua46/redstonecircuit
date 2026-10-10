package com.jiangyuefengyu.redstonecircuit.client;

import java.util.Objects;

import net.minecraft.core.BlockPos;

import org.jetbrains.annotations.Nullable;

/**
 * <b>Specification S1: may a carried component be drawn at all, given what the eye's ray met?</b>
 *
 * <p>A {@link BlockPos} is the block's <b>minimum corner</b>, so "which block is this point in" is
 * {@link BlockPos#containing} - a <b>floor</b> per axis, never a round. Carried geometry is submitted at
 * the tail of the frame with the depth test ignored ({@code HostRenderTypes}), so this decision is the
 * carried path's only occlusion. The rule, first match returns: the eye is in the block or within one
 * block of it; the ray met nothing; the ray met the component's own block, in either frame; otherwise a
 * wall really is in front of it.
 */
final class CarriedSight {

    /** S1 rule 1's "within one block of {@code P}", measured to the block's centre. A rule, not a tolerance. */
    static final double EYE_NEAR = 1.0;

    private CarriedSight() {
    }

    /**
     * The ray's answer: it met a block, or it met nothing. A record rather than a nullable
     * {@link BlockPos}, because "met nothing" is a rule and not a missing value.
     */
    record Ray(@Nullable BlockPos hitBlockPos) {

        /** The ray met nothing before the target. */
        static Ray miss() {
            return new Ray(null);
        }

        /** The ray met {@code block}. */
        static Ray hit(BlockPos block) {
            return new Ray(Objects.requireNonNull(block, "a hit names the block it hit"));
        }

        /** Whether the ray met anything at all. */
        boolean met() {
            return hitBlockPos != null;
        }
    }

    /** S1, as one decision, for a component whose block <b>is</b> a world block (no plot frame involved). */
    static boolean isVisible(double eyeX, double eyeY, double eyeZ, BlockPos target, Ray ray) {
        return isVisible(eyeX, eyeY, eyeZ, target, ray, null);
    }

    /**
     * The same, told the component's own block <b>in the frame the ray reports its hits in</b> ({@code null}
     * for an ordinary host): a sub-level host lives at a plot coordinate while {@code target} is only where
     * it is projected, so rule 3 matches in either frame. A hit on <em>another</em> block of the same
     * structure is still an occluder, so this can only widen.
     */
    static boolean isVisible(double eyeX, double eyeY, double eyeZ, BlockPos target, Ray ray,
                             @Nullable BlockPos ownBlockInItsOwnFrame) {
        // Rule 1: the eye is in P, or within one block of it.
        if (eyeInOrAgainst(eyeX, eyeY, eyeZ, target)) {
            return true;
        }
        // Rule 2: the ray met nothing.
        if (!ray.met()) {
            return true;
        }
        // Rule 3: the ray met P itself - in the world frame, or in the frame the ray reported it in.
        if (ray.hitBlockPos().equals(target)
                || ray.hitBlockPos().equals(ownBlockInItsOwnFrame)) {
            return true;
        }
        // Rule 4: something else is in front of it.
        return false;
    }

    /** S1 rule 1: {@code E} belongs to {@code P}, or the eye is within {@link #EYE_NEAR} of {@code P}. */
    private static boolean eyeInOrAgainst(double eyeX, double eyeY, double eyeZ, BlockPos target) {
        if (BlockPos.containing(eyeX, eyeY, eyeZ).equals(target)) {
            return true;
        }
        double dx = eyeX - (target.getX() + 0.5);
        double dy = eyeY - (target.getY() + 0.5);
        double dz = eyeZ - (target.getZ() + 0.5);
        return dx * dx + dy * dy + dz * dz < EYE_NEAR * EYE_NEAR;
    }
}

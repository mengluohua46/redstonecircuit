package com.jiangyuefengyu.redstonecircuit.client;

import java.util.Objects;

import net.minecraft.core.BlockPos;

import org.jetbrains.annotations.Nullable;

/**
 * <b>Specification S1: may a carried component be drawn at all, given what the eye's ray met?</b>
 *
 * <h2>Coordinate convention, stated once and used everywhere</h2>
 * A {@link BlockPos} is the block's <b>minimum corner</b>: the block at {@code (x, y, z)} occupies
 * {@code [x, x+1) x [y, y+1) x [z, z+1)}. "Which block is this point in" is therefore
 * {@link BlockPos#containing(double, double, double)} - a <b>floor</b> per axis, never a round and never a
 * distance. The near face {@code x = P.x} belongs to {@code P}; the far face {@code x = P.x + 1} belongs
 * to the block behind it.
 *
 * <h2>Why the carried path is the only path this rule matters for</h2>
 * The carried geometry is submitted at the tail of the frame with the depth test ignored - deliberately,
 * see {@code HostRenderTypes}, because the block the component lives in is drawn by the structure's own
 * renderer around it. Nothing on screen can therefore cover a carried component once a host has been
 * collected, so this decision, taken before any geometry is collected, is the <em>only</em> occlusion the
 * carried path has. The world path needs no such rule: it is submitted before the world's own section
 * geometry, so a wall drawn afterwards simply replaces the component's colour.
 *
 * <h2>The rule, in the order it is evaluated (first match returns)</h2>
 * <ol>
 *   <li><b>The eye is in {@code P}, or within one block of it ⇒ visible.</b> The player is inside or
 *       against the block the component lives in, which is not an occlusion. Written as the two
 *       disjunctions S1 names: {@code E} belongs to {@code P}, or {@code |E - centre(P)| < 1}. (The first
 *       implies the second on a block grid - the furthest a point in a cell can be from its centre is
 *       {@code sqrt(3)/2 = 0.866} - but both are written out so this code can be checked against S1 line
 *       by line, and so nobody "simplifies" one of them away without the specification in hand.)</li>
 *   <li><b>The ray met nothing ⇒ visible.</b> This is the known half-fixed case: a sub-level's blocks live
 *       in a plot two million blocks away and are not in the top-level {@code ClientLevel} the ray is cast
 *       against, so for those the ray meets nothing. S1 keeps that passing, and so does this class. Making
 *       that case honest needs "where is this structure being drawn", which the pose does not carry.</li>
 *   <li><b>The ray's block is {@code P} ⇒ visible.</b> The ray hit the component's own block, including its
 *       near face. This is exactly the question the world path asks with
 *       {@code hit.getBlockPos().equals(pos)}: a world host's own block face must not be read as a wall.</li>
 *   <li><b>Otherwise ⇒ not visible.</b> There really is another block in front of it.</li>
 * </ol>
 *
 * <h2>What this class deliberately does not contain</h2>
 * No distance sphere ({@code ON_TARGET} and its like), no "how far is the hit from the target point"
 * comparison, and <b>no floating-point tolerance of ours at all</b> - not even a 1e-6 rounding allowance,
 * because this class performs no point-to-block conversion: the hit's block comes from the ray
 * ({@code hit.getBlockPos()}) and the target's block comes from {@link BlockPos#containing} at the call
 * site. Rule 1's one block is the eye proximity S1 names, not a tolerance on the occlusion test.
 */
final class CarriedSight {

    /**
     * S1 rule 1's "within one block of {@code P}": the distance from the eye to the block's <b>centre</b>.
     *
     * <p>This is the only number in the class besides the block grid itself, and it is a rule rather than a
     * tolerance: a viewpoint this close is inside, or against, the very block the component is in.
     */
    static final double EYE_NEAR = 1.0;

    private CarriedSight() {
    }

    /**
     * The ray's answer, in the two shapes S1 distinguishes: it met a block, or it met nothing.
     *
     * <p>A record rather than a nullable {@link BlockPos} because "met nothing" is a rule (rule 2) and not
     * a missing value: a {@code null} that means "missed" reads exactly like a {@code null} that means
     * "nobody asked", and rule 2 is load-bearing for every sub-level host.
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

    /**
     * S1, as one decision, for a component whose block <b>is</b> a world block. Every argument is a
     * number the caller already has, so the rule is checked without a level, a player or a game.
     *
     * @param eyeX the eye, in the same space as {@code target}
     * @param eyeY see above
     * @param eyeZ see above
     * @param target the block the component is in - {@code P}
     * @param ray what the eye-to-target ray met
     * @return true when the component may be drawn
     */
    static boolean isVisible(double eyeX, double eyeY, double eyeZ, BlockPos target, Ray ray) {
        return isVisible(eyeX, eyeY, eyeZ, target, ray, null);
    }

    /**
     * The same, told <b>which block the component is in, in the frame the ray reports its hits in</b>.
     *
     * <h2>Why there can be two answers to "the component's own block", and how the live log settled it</h2>
     * Rule 3 is "the ray met the component's own block". For an ordinary host that block is the world block
     * at {@code target} and the two questions coincide. For a host riding a Sable sub-level they do not:
     * the component's block lives at a <b>plot</b> coordinate (two million blocks away, and that coordinate
     * is a real coordinate in the same level), while {@code target} is only where that block is
     * <em>projected</em> - and Sable's own ray tracing reports hits on its geometry in the <b>plot</b>
     * frame.
     *
     * <p>The author's log of 2026-09-28 08:45-08:46 is that case in five consecutive frames, with no
     * ambiguity left in it:
     *
     * <pre>
     *   carrier=s:f4e68bf9 local=(20481032, 127, 20483080) eye=(-62, -59, -313)
     *       ray=hit(20481032, 127, 20483080) sight=refused-hit target=(-58, -57, -311)
     * </pre>
     *
     * The ray hit <b>the component's own block</b> - the numbers are the ones the same line prints as
     * {@code local=} - and the decision was "refused", because {@code hit} (plot frame) was compared with
     * {@code target} (world frame) and the two can never be equal. That is the whole of "the component
     * inside a physicalised block is invisible": the machine is its own occluder, in a frame we forgot to
     * ask about.
     *
     * <p>So this parameter carries the host's own coordinate, and rule 3 now matches in <b>either</b>
     * frame. Passing {@code null} is the ordinary host, whose block is the world block at {@code target}.
     *
     * <p>Only that equality was added: a hit on <em>another</em> block of the same structure is still an
     * occluder (the machine's own hull in front of the component really is in front of it), and every rule
     * for world blocks is unchanged. The change may only widen, which
     * {@code CarriedSightTest#theOldRuleIsASubsetOfThisOne} asserts case by case.
     *
     * @param ownBlockInItsOwnFrame the block the component is in, in the frame the ray reports hits in, or
     *     {@code null} when that frame is the same as {@code target}'s - which is every host whose block
     *     is a world block
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

    /**
     * S1 rule 1: {@code E} belongs to {@code P}, or the eye is within {@link #EYE_NEAR} of {@code P}.
     *
     * <p>Two disjunctions because S1 states two; see the class comment for why the first is implied by the
     * second and is still written out.
     */
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

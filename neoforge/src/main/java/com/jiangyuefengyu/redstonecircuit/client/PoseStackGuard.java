package com.jiangyuefengyu.redstonecircuit.client;

import com.mojang.blaze3d.vertex.PoseStack;

/**
 * Puts a pose stack back on the pose its stage was entered on, whatever happened in between.
 *
 * <p>Vanilla's {@code LevelRenderer#checkPoseStack} throws {@code IllegalStateException("Pose stack not
 * empty")} at the end of any frame whose pose stack is not back to its base, so a stage that pushes and
 * does not pop is not a cosmetic defect - it ends the game. That happened here: the world stage lost the
 * pop that matched its push when the per-frame readings were extracted into a method, and the symptom
 * was "placing a redstone torch inside a block crashes the game", because a frame with no hosts returns
 * before the push and only a frame that actually draws reached it.
 *
 * <p>The rule is identity, not a depth. A {@link PoseStack.Pose} is a fresh object on every push, so
 * "the top is the pose we entered on" is exactly "everything we pushed has been given back" - no matter
 * how many pushes a draw path took, or whether one of them threw before its pop. The stack is walked
 * back until that is true, bounded by {@link #MAX_LEVELS} so a stack that somehow disagrees cannot spin.
 *
 * <p>Deliberately a class of its own, and deliberately free of every other type in this package: it has
 * to be testable without a game running, and this package's other classes pull in render types whose
 * class initialisation cannot happen offline. See {@code PoseStackGuardTest}.
 */
final class PoseStackGuard {

    /**
     * How many levels this will ever walk back. A stage is allowed one push; the geometry drawn inside it
     * takes a handful more. Sixteen is far past "a bug" and far short of "a loop that cannot end", which
     * would be a worse defect than the one this repairs.
     */
    static final int MAX_LEVELS = 16;

    private PoseStackGuard() {
    }

    /**
     * Restores {@code pose} to {@code entry} and reports how much had to be undone.
     *
     * @return the number of levels popped: {@code 1} for the single push a stage is expected to have
     *     made, {@code 0} if it pushed nothing, and more than one only when something inside the stage
     *     pushed without popping - which the caller should report, because it is a bug either way.
     */
    static int restore(PoseStack pose, PoseStack.Pose entry) {
        int popped = 0;
        while (pose.last() != entry && popped < MAX_LEVELS) {
            pose.popPose();
            popped++;
        }
        return popped;
    }
}

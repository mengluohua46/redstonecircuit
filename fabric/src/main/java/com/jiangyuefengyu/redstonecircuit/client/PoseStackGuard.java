package com.jiangyuefengyu.redstonecircuit.client;

import com.mojang.blaze3d.vertex.PoseStack;

/**
 * Puts a pose stack back on the pose its stage was entered on, whatever happened in between; free of this package's
 * other types, so it stays testable without a game.
 * <p>Vanilla's {@code LevelRenderer#checkPoseStack} throws on a frame that ends off its base pose, so an unbalanced
 * push and pop ends the game. The rule is identity, not a depth: a {@link PoseStack.Pose} is fresh on every push, so
 * "the top is the pose we entered on" means "everything we pushed has been given back". Bounded by {@link #MAX_LEVELS}.
 */
final class PoseStackGuard {

    /**
     * How many levels this will ever walk back: a stage is allowed one push and the geometry inside it a handful
     * more, so sixteen is far past "a bug" and far short of "a loop that cannot end".
     */
    static final int MAX_LEVELS = 16;

    private PoseStackGuard() {
    }

    /**
     * Restores {@code pose} to {@code entry} and returns how many levels had to be undone: {@code 0} when nothing was
     * pushed, {@code 1} for the single push a stage is expected to have made, and more only when something did not pop.
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

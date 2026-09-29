package com.jiangyuefengyu.redstonecircuit.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.blaze3d.vertex.PoseStack;

import org.joml.Quaternionf;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The pose-stack rule a stage lives by: whatever it pushes, it gives back.
 *
 * <p>This exists because that rule was broken and the game went down for it. Vanilla's
 * {@code LevelRenderer#checkPoseStack} finishes a frame with {@code if (!poseStack.clear()) throw new
 * IllegalStateException("Pose stack not empty")} - the accept/reject decision is literally the return
 * value of {@link PoseStack#clear()}, so the tests below ask vanilla's own question instead of a
 * paraphrase of it. A stage that pushes and never pops leaves the stack deeper than its base, which is
 * the state that fails the check; a frame with no hosts returns before the push, which is why the crash
 * waited for the first frame that actually drew something - the frame a redstone torch is placed inside
 * a block.
 *
 * <p>Driven with a real {@link PoseStack} rather than a stand-in: the whole question is what the real
 * stack does, and {@code last()} is object identity rather than a depth.
 */
class PoseStackGuardTest {

    /**
     * The pose a stage enters on: the frame's own base, i.e. the one level a {@link PoseStack} is built
     * with and the level vanilla's end-of-frame {@code clear()} is happy to see. A stage is handed the
     * stack in exactly this state - it is the stage's own push that has to be given back.
     */
    private static PoseStack entering() {
        PoseStack pose = new PoseStack();
        pose.translate(3.0F, 64.0F, -7.0F);
        return pose;
    }

    @Test
    @DisplayName("without the guard, one unmatched push is the state vanilla throws on")
    void theCrashIsReproduced() {
        PoseStack pose = entering();
        // Exactly the shape of the defect: push, draw, and never pop.
        pose.pushPose();
        pose.translate(-2775.5F, -77.5F, 1394.5F);

        assertFalse(pose.clear(),
                "a stack left deeper than its base is what 'Pose stack not empty' is thrown about");
    }

    @Test
    @DisplayName("the guard gives the stage's push back, and vanilla's own check then passes")
    void theGuardSatisfiesVanillasCheck() {
        PoseStack pose = entering();
        PoseStack.Pose entry = pose.last();
        pose.pushPose();
        pose.translate(-2775.5F, -77.5F, 1394.5F);

        int popped = PoseStackGuard.restore(pose, entry);

        assertEquals(1, popped, "the stage made one push, so one level is given back");
        assertSame(entry, pose.last(), "the stage's own push must be gone");
        assertTrue(pose.clear(), "the frame ends in the state vanilla accepts");
    }

    @Test
    @DisplayName("a stage that already popped its own push is not popped past the frame's own pose")
    void anAlreadyBalancedStageIsLeftAlone() {
        PoseStack pose = entering();
        PoseStack.Pose entry = pose.last();
        pose.pushPose();
        pose.translate(-1200.0F, 0.0F, 0.0F);
        pose.popPose();

        int popped = PoseStackGuard.restore(pose, entry);

        assertEquals(0, popped, "nothing was outstanding");
        assertSame(entry, pose.last());
        assertTrue(pose.clear(), "still the frame's own base - the guard did not pop the pose under it");
    }

    @Test
    @DisplayName("a draw that pushed more than the stage is allowed still leaves the frame straight")
    void extraPushesAreUndoneAndCounted() {
        PoseStack pose = entering();
        PoseStack.Pose entry = pose.last();
        pose.pushPose();
        pose.pushPose();
        pose.mulPose(new Quaternionf().rotateY(0.7F));

        int popped = PoseStackGuard.restore(pose, entry);

        assertEquals(2, popped, "more than one is the number the caller reports as a bug");
        assertSame(entry, pose.last());
        assertTrue(pose.clear());
    }

    @Test
    @DisplayName("the rule is identity, not a remembered depth")
    void identityIsNotDepth() {
        PoseStack pose = entering();
        PoseStack.Pose entry = pose.last();
        for (int i = 0; i < 8; i++) {
            pose.pushPose();
            pose.translate(i, i, i);
            pose.popPose();
        }
        pose.pushPose();

        assertEquals(1, PoseStackGuard.restore(pose, entry));

        assertSame(entry, pose.last(), "the stack is walked back to the entry pose, not to a depth");
        assertEquals(3.0F, entry.pose().m30(), 1.0E-6F,
                "and the entry pose is still the frame's own - untouched by everything above it");
    }
}

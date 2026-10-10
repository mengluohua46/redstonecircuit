package com.jiangyuefengyu.redstonecircuit.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Where the carried geometry is submitted, and with which render type - as a function of the shader path.
 *
 * <p>Under a pack a physicalised block showed its frame and <b>not</b> the component inside it, while the same
 * block standing in the world was whole. The world path and the carried path differ in exactly two things - the
 * stage they are submitted at, and (under a pack) the render type the contents are written with - so both are
 * pinned here.
 *
 * <p>Under a pack the carried geometry rides with the world path at {@code after_entities}, the stage the working
 * world path uses; with no pack it stays at the late stage, with the build's own shell type and a contents type
 * that ignores depth. {@link CarrierGeometry.StagePlan#sameAsBeforeShaders()} is the assertion that says the
 * no-shader answer is unchanged.
 */
class CarrierStagePlanTest {

    // ------------------------------------------------------------- the stage --

    @Test
    @DisplayName("with a shader pack the carried geometry is submitted with the world path")
    void aShaderPackMovesTheCarriedStageToTheWorldStage() {
        CarrierGeometry.StagePlan plan = CarrierGeometry.StagePlan.of(true);

        assertSame(CarrierGeometry.StagePlan.WITH_WORLD, plan);
        assertTrue(plan.withWorld(),
                "the world stage is the one that also collects and draws the hosts standing in the "
                        + "world, so 'with world' is what makes one stage own both halves");
        assertEquals("minecraft:after_entities", plan.stageName(),
                "and it is the stage the world path - the path that works under a pack - "
                        + "has always been flushed at");
    }

    @Test
    @DisplayName("without a shader pack the carried geometry stays at the late stage, as it always was")
    void noShaderPackKeepsTheLateStage() {
        CarrierGeometry.StagePlan plan = CarrierGeometry.StagePlan.of(false);

        assertSame(CarrierGeometry.StagePlan.LATE, plan);
        assertFalse(plan.withWorld());
        assertEquals("minecraft:after_translucent_blocks", plan.stageName(),
                "this is the stage this build has always printed, and a reader "
                        + "comparing logs must find the same word here");
        assertTrue(plan.sameAsBeforeShaders(),
                "the no-shader plan IS the unchanged behaviour: without a shader pack nothing changes, "
                        + "which is what this assertion states");
        assertFalse(CarrierGeometry.StagePlan.of(true).sameAsBeforeShaders(),
                "and the shader plan is not, or the decision would be a no-op");
    }

    @Test
    @DisplayName("the two plans name the two stages the render pass actually dispatches on")
    void thePlansNameRealStages() {
        // Pinning the spellings HostRenderPass prints here stops the plan and the dispatcher drifting apart.
        assertEquals(HostRenderPass.WORLD_STAGE_NAME, CarrierGeometry.StagePlan.WITH_WORLD.stageName());
        assertEquals(HostRenderPass.CARRIED_STAGE_NAME, CarrierGeometry.StagePlan.LATE.stageName());
    }

    @Test
    @DisplayName("the shader plan's reason names the stage it moved to, so the log explains itself")
    void thePlanExplainsItself() {
        assertTrue(CarrierGeometry.StagePlan.WITH_WORLD.why().contains("world stage"),
                "the acceptance line quotes this, so a reader who finds carriedQuadsFlushedAt=after_entities "
                        + "is told which stage that is");
        assertFalse(CarrierGeometry.StagePlan.LATE.why().isBlank());
    }

    // ------------------------------------------------------- the render types --

    @Test
    @DisplayName("a shader pack keeps vanilla's cutout identity for the shell and NOT for the contents")
    void aShaderPackSplitsTheTypeDecision() {
        CarrierGeometry.RenderPlan plan = CarrierGeometry.RenderPlan.of(true);

        assertSame(CarrierGeometry.RenderPlan.SHADER_SHELL, plan);
        assertTrue(plan.vanillaShellType(),
                "Iris selects its program from the render type it is handed, and the shell - pushed "
                        + "outside the block - is the half that has always been visible under a pack");
        assertFalse(plan.vanillaContentsType(),
                "the contents must not take a depth-TESTING type. Inside the block, every "
                        + "fragment of theirs fails against the block's own faces, which the structure's "
                        + "renderer draws around them - that is the component disappearing while the "
                        + "frame, which sits 0.005 blocks outside, survives.");
    }

    @Test
    @DisplayName("without a shader pack both halves use the build's own types, as they always did")
    void noShaderPackKeepsBothOwnTypes() {
        CarrierGeometry.RenderPlan plan = CarrierGeometry.RenderPlan.of(false);

        assertSame(CarrierGeometry.RenderPlan.OWN, plan);
        assertFalse(plan.vanillaShellType(), "the carried shell type is the pre-shader behaviour");
        assertFalse(plan.vanillaContentsType(),
                "and the contents ignore depth on BOTH paths, which is what makes the two paths one "
                        + "behaviour to look at rather than two");
    }

    @Test
    @DisplayName("the contents type is the same on both paths, and only the shell follows the pack")
    void theContentsTypeDoesNotFollowTheShell() {
        assertNotEquals(CarrierGeometry.RenderPlan.of(false).vanillaShellType(),
                CarrierGeometry.RenderPlan.of(true).vanillaShellType(),
                "the shell decision really does depend on the pack");
        assertEquals(CarrierGeometry.RenderPlan.of(false).vanillaContentsType(),
                CarrierGeometry.RenderPlan.of(true).vanillaContentsType(),
                "and the contents decision does not - 'the component behaves the same with "
                        + "and without a pack' is the property that matters here");
    }

    @Test
    @DisplayName("the render plan describes itself for the acceptance line")
    void theRenderPlanExplainsItself() {
        assertTrue(CarrierGeometry.RenderPlan.SHADER_SHELL.describe().contains("depth-ignoring"));
        assertTrue(CarrierGeometry.RenderPlan.OWN.describe().contains("depth-ignoring"));
    }
}

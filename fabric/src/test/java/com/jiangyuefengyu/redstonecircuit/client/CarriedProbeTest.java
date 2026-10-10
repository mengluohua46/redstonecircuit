package com.jiangyuefengyu.redstonecircuit.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jiangyuefengyu.redstonecircuit.carrier.CarrierFrame;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierPose;
import com.mojang.blaze3d.vertex.PoseStack;

import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The carried path's two call sites place the shell and the component at the same point.
 *
 * <p>Both sites emit {@code T(position) · R · S} with the block's corner as a per-vertex base, so there is
 * nothing for the probe to reconcile: {@code shellContentGap} is zero at every rotation, in both coordinate
 * spaces, with both pivot values, and the carried geometry is flushed at a later stage than the world
 * geometry, which is what stops a structure's own blocks from covering its components.
 */
class CarriedProbeTest {

    /** A camera far from the machine, so the camera translation is part of what is asserted. */
    private static final Vector3d CAMERA = new Vector3d(2700.25, 90.5, -1300.75);

    /**
     * Tolerance for the two call sites' <b>relative</b> agreement: a millionth of a block. Both sites run the
     * same camera-relative {@code float} pipeline, so whatever the pipeline loses cancels.
     */
    private static final double EXACT = 1.0E-6;

    /**
     * Tolerance for comparing a {@code float}-pipeline position against the {@code double} contract: at a
     * world coordinate near 2775 the {@code float} spacing is {@code 2.4e-4}, so a tighter bound would be
     * asserting something about IEEE-754 rather than about the renderer.
     */
    private static final double WORLD_TOLERANCE = 1.0E-4;

    /** The three rotations every case is run at: no turn, a third of one, and a right angle. */
    private static final double[] DEGREES = { 0, 30, 90 };

    // ------------------------------------------------------------------ fixtures --

    /**
     * A Create contraption holding one host block: {@code position=(2775.5, 77.5, -1394.5)},
     * {@code pivot=(0.5, 0.5, 0.5)}, {@code local=(0,0,0)}.
     */
    private static CarrierPose contraption(double degrees, double pivot) {
        return new CarrierPose(new Vector3d(2775.5, 77.5, -1394.5), aboutX(degrees),
                new Vector3d(pivot, pivot, pivot), 1.0);
    }

    /**
     * A physics sub-level whose local coordinate is a plot coordinate: {@code 20481032.0f + 0.5f} stays
     * {@code 20481032.0f}, which is the {@code float} narrowing this case catches.
     */
    private static CarrierPose sable(double degrees, double pivot) {
        return new CarrierPose(new Vector3d(2775.5, 78.083, -1394.5), aboutX(degrees),
                new Vector3d(20481032.5, 128.083, 20524040.5), 1.0);
    }

    private static Quaterniond aboutX(double degrees) {
        return new Quaterniond().rotateX(Math.toRadians(degrees));
    }

    /** The pivot values the host block is measured against: the ordinary one, and zero. */
    private static final double[] PIVOTS = { 0.5, 0.0 };

    /** One case: a pose, and the local coordinate its host block sits at. */
    private record Case(String name, CarrierPose pose, double localX, double localY, double localZ) {
    }

    private static Case[] cases() {
        java.util.List<Case> out = new java.util.ArrayList<>();
        for (double degrees : DEGREES) {
            for (double pivot : PIVOTS) {
                out.add(new Case("contraption " + degrees + "deg pivot=" + pivot,
                        contraption(degrees, pivot), 0, 0, 0));
                out.add(new Case("sable plot " + degrees + "deg pivot=" + pivot,
                        sable(degrees, pivot), 20481032, 127, 20524040));
            }
        }
        return out.toArray(new Case[0]);
    }

    // ------------------------------------------------------------------ helpers --

    /** The base a call site emits at: the host block's corner in the carrier's frame. */
    private static float[] baseOf(CarrierFrame frame) {
        return frame.cornerOffset(new float[3]);
    }

    /** A pose stack at the camera, inside the carrier's frame - what both draw paths start from. */
    private static PoseStack frameStack(CarrierFrame frame) {
        PoseStack pose = new PoseStack();
        pose.translate(-CAMERA.x, -CAMERA.y, -CAMERA.z);
        HostRenderPass.applyCarrierFrame(pose, frame);
        return pose;
    }

    /** Where a point emitted in the frame's space lands in the world, camera included. */
    private static Vector3d drawnWorld(PoseStack.Pose pose, float x, float y, float z) {
        Vector3f drawn = pose.pose().transformPosition(x, y, z, new Vector3f());
        return new Vector3d((double) drawn.x + CAMERA.x, (double) drawn.y + CAMERA.y,
                (double) drawn.z + CAMERA.z);
    }

    /**
     * The world point the <b>contents</b> path draws the block's centre at, through the real code: the frame
     * on the stack, then {@code T(base) · transform(visual)}. The model's vertices are {@code 0..1}, so its
     * centre is {@code base + (0.5, 0.5, 0.5)}.
     */
    private static Vector3d contentsCentreDrawn(CarrierFrame frame) {
        float[] base = baseOf(frame);
        float[] anchored = HostRenderPass.carriedModelBase(frame, new float[3]);
        PoseStack pose = frameStack(frame);
        pose.pushPose();
        pose.translate(anchored[0], anchored[1], anchored[2]);
        Vector3d world = drawnWorld(pose.last(), 0.5F, 0.5F, 0.5F);
        pose.popPose();
        assertEquals(base[0], anchored[0], 0.0F, "the contents' base must be the block's corner");
        return world;
    }

    /** The world point the <b>shell</b> path draws the block's centre at. */
    private static Vector3d shellCentreDrawn(CarrierFrame frame) {
        PoseStack pose = frameStack(frame);
        float[] base = baseOf(frame);
        return drawnWorld(pose.last(), base[0] + 0.5F, base[1] + 0.5F, base[2] + 0.5F);
    }

    // ------------------------------------------------------------------ the tests --

    @Test
    @DisplayName("the component lands on the block's centre, at every rotation and in plot space")
    void contentsLandOnTheBlockCentreExactly() {
        for (Case testCase : cases()) {
            CarrierFrame frame = CarrierFrame.forBlock(testCase.pose(),
                    new Vector3d(testCase.localX(), testCase.localY(), testCase.localZ()));
            Vector3d expected = frame.blockCentre(new Vector3d());
            Vector3d drawn = contentsCentreDrawn(frame);
            assertEquals(0.0, expected.distance(drawn), WORLD_TOLERANCE, testCase.name()
                    + ": the component's matrix does not draw the block's centre where the pose says. "
                    + expected + " expected, " + drawn + " drawn");
        }
    }

    @Test
    @DisplayName("shellContentGap is zero: the shell and the component are in one frame, always")
    void shellAndContentsAreInOneFrame() {
        for (Case testCase : cases()) {
            CarrierFrame frame = CarrierFrame.forBlock(testCase.pose(),
                    new Vector3d(testCase.localX(), testCase.localY(), testCase.localZ()));
            Vector3d shell = shellCentreDrawn(frame);
            Vector3d contents = contentsCentreDrawn(frame);
            // sqrt(3): the distance from the block's corner to its centre.
            assertEquals(0.0, shell.distance(contents), EXACT, testCase.name()
                    + ": shellContentGap must be zero. shell " + shell + ", contents " + contents);
        }
    }

    @Test
    @DisplayName("the probe line says OK-matrix, OK-matrix, gap 0.0000 - what a reader greps for")
    void probeLineReportsOkEverywhere() {
        for (Case testCase : cases()) {
            CarrierFrame frame = CarrierFrame.forBlock(testCase.pose(),
                    new Vector3d(testCase.localX(), testCase.localY(), testCase.localZ()));
            float[] base = baseOf(frame);
            HostRenderPass.Counter counter = HostRenderPass.newCounter();

            // The two probes, taken where the renderer takes them: each at its own call site, both after
            // `applyCarrierFrame`, since neither path translates after it.
            HostRenderPass.probeForTest(counter, "shell", "c:test", frameStack(frame).last(), frame,
                    base, CAMERA);
            HostRenderPass.probeForTest(counter, "contents", "c:test", frameStack(frame).last(), frame,
                    base, CAMERA);

            String line = HostRenderPass.probeLineForTest(counter);
            assertNotNull(line, testCase.name() + ": the probes produced no line at all");
            assertTrue(line.contains("shellContentGap=0.0000"), testCase.name()
                    + ": the gap is not zero in the line a reader sees: " + line);
            assertFalseContains(line, "THE MATRIX DOES NOT DRAW", testCase.name());
            assertFalseContains(line, "DIFFERENT FRAMES", testCase.name());
            assertEquals(2, countOf(line, "OK-matrix"), testCase.name()
                    + ": both probes must report OK-matrix: " + line);
        }
    }

    @Test
    @DisplayName("the two halves of the pass are two callbacks, and the world's comes first")
    void theTwoHalvesAreTwoCallbacks() throws java.io.IOException {
        // Why the carried half has a callback of its own: the world path runs at `after_entities`, and the
        // readings the carried half keeps are taken once everything the frame draws has been drawn, so they
        // are registered at two different moments rather than dispatched from one.
        assertEquals(HostRenderPass.WORLD_STAGE_NAME, HostRenderPass.Stage.WORLD.stageName());
        assertEquals(HostRenderPass.CARRIED_STAGE_NAME, HostRenderPass.Stage.CARRIED.stageName());
        assertTrue(HostRenderPass.Stage.CARRIED.order() > HostRenderPass.Stage.WORLD.order(),
                "the carried readings must be taken after the world flush: world order "
                        + HostRenderPass.Stage.WORLD.order() + ", carried order "
                        + HostRenderPass.Stage.CARRIED.order());
        assertTrue(HostRenderPass.Stage.WORLD.collectsEverything(),
                "the world stage draws the hosts standing in the world");
        assertTrue(!HostRenderPass.Stage.CARRIED.collectsEverything(),
                "the carried stage does not draw the world path");

        String source = java.nio.file.Files.readString(java.nio.file.Path.of(
                        "src/main/java/com/jiangyuefengyu/redstonecircuit/client/HostRenderPass.java"))
                .replace("\r\n", "\n");
        int world = source.indexOf("WorldRenderEvents.AFTER_ENTITIES.register(HostRenderPass::render);");
        int carried = source.indexOf("WorldRenderEvents.AFTER_TRANSLUCENT.register(HostRenderPass::renderCarried);");
        assertTrue(world >= 0, "the world path is registered at the entities stage");
        assertTrue(carried >= 0, "the carried readings are registered at the translucent stage");
        assertTrue(world < carried, "and the world's callback is installed first, which is the order they run in");
    }

    @Test
    @DisplayName("a plot coordinate never reaches the pose stack, even now that both paths share a base")
    void plotSpaceStaysSmall() {
        for (Case testCase : cases()) {
            CarrierFrame frame = CarrierFrame.forBlock(testCase.pose(),
                    new Vector3d(testCase.localX(), testCase.localY(), testCase.localZ()));
            for (float value : HostRenderPass.carriedModelBase(frame, new float[3])) {
                assertTrue(Math.abs(value) < 2.0F, testCase.name()
                        + ": a plot coordinate reached the pose stack as a translate: " + value);
            }
        }
    }

    @Test
    @DisplayName("the flush line names both stages, their quad counts, and who flushes the world shell")
    void flushLineNamesBothStagesAndTheirCounts() {
        String line = HostRenderPass.flushStageLine();
        // The world half prints the name the dispatcher uses, printed whether or not it has run, since a stage is a
        // property of the build and the counts are a property of the frame. The carried half prints the stage its
        // geometry was really SUBMITTED at - the world stage on this loader, see CarrierGeometry.StagePlan - which is
        // why the two lines can name the same stage and still be read as two different callbacks.
        assertTrue(line.contains("worldQuadsFlushedAt=" + HostRenderPass.WORLD_STAGE_NAME), line);
        assertTrue(line.contains("carriedQuadsFlushedAt=" + CarrierGeometry.StagePlan.WITH_WORLD.stageName()), line);
        // The ordering rule is guidance rather than a reading, so it lives in the explanation, which is
        // printed once per verdict change instead of on every line.
        assertTrue(HostRenderPass.flushStageExplanation()
                        .contains("If carriedQuadsFlushedAt ever reads as a later stage than the world one"),
                "the ordering rule must still be stated, once, in the explanation");
        // Both halves of each path are reported separately: a shell without its component and neither are
        // different faults with different causes.
        for (String field : new String[] { "worldOwnQuads=", "worldShellQuads=", "worldContentQuads=",
                "carriedQuads=", "carriedShellQuads=", "carriedContentQuads=" }) {
            assertTrue(line.contains(field), "the flush line does not report " + field + ": " + line);
        }
        // The world shell is not ours to submit, and the line says so rather than reading like a stage that
        // never ran.
        assertTrue(line.contains("worldShellHandedOverQuads="), line);
        assertTrue(line.contains("NOT flushed by us"), line);
        // A stage that has not run since the client started must say so, rather than printing a zero
        // that reads exactly like "it ran and wrote nothing".
        assertTrue(line.contains("flushed=NOT-YET-THIS-SESSION"),
                "a stage that has never run must say so, not report a bare zero: " + line);
    }

    @Test
    @DisplayName("the stage names the pass prints are spelled the way the game spells them")
    void thePrintedStageNamesCarryTheNamespace() {
        // The names in the flush lines and in the stage comparison are built the way the game builds them -
        // through a ResourceLocation - so each carries the default namespace. A bare name would be a string
        // that matches nothing, and the diagnostic would silently name a stage that never ran.
        String bare = "after_entities";
        String asTheGameSpellsIt = net.minecraft.resources.ResourceLocation.parse(bare).toString();
        assertEquals("minecraft:" + bare, asTheGameSpellsIt,
                "the prefix is what matters: ResourceLocation adds the default namespace");
        assertNotEquals(bare, asTheGameSpellsIt,
                "a bare stage name must NOT compare equal to what the game produces");
        assertTrue(HostRenderPass.WORLD_STAGE_NAME.endsWith(bare), HostRenderPass.WORLD_STAGE_NAME);
        for (HostRenderPass.Stage stage : HostRenderPass.Stage.values()) {
            assertTrue(stage.stageName().startsWith("minecraft:"),
                    "a printed stage name must be namespaced: " + stage.stageName());
            assertNotEquals(stage.stageName().substring("minecraft:".length()), stage.stageName(),
                    "and must not compare equal to its bare form: " + stage.stageName());
        }
    }

    @Test
    @DisplayName("a session that never flushed says so, in the flush line the summary carries")
    void sessionThatNeverFlushedSaysSo() {
        // The line carries the two facts that name a session which never flushed: which stage each half goes
        // to, and that neither has run yet.
        String line = HostRenderPass.flushStageLine();
        assertTrue(line.contains("worldQuadsFlushedAt=" + HostRenderPass.WORLD_STAGE_NAME), line);
        assertTrue(line.contains("carriedQuadsFlushedAt=" + CarrierGeometry.StagePlan.WITH_WORLD.stageName()), line);
        assertTrue(line.contains("flushed=NOT-YET-THIS-SESSION"),
                "a pass that has flushed nothing must say so in the summary: " + line);
    }

    private static void assertFalseContains(String haystack, String needle, String caseName) {
        if (haystack.contains(needle)) {
            throw new AssertionError(caseName + ": the probe line still carries '" + needle + "': "
                    + haystack);
        }
    }

    private static int countOf(String haystack, String needle) {
        int count = 0;
        int at = haystack.indexOf(needle);
        while (at >= 0) {
            count++;
            at = haystack.indexOf(needle, at + needle.length());
        }
        return count;
    }
}

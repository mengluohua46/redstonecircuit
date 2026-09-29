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
 * <b>The carried path's two call sites place the shell and the component at the same point.</b>
 *
 * <h2>The defect these pin, and why no earlier test could see it</h2>
 * The author's log carried, on 74 consecutive frames, a line like
 *
 * <pre>
 *   shellQuads=30 contentQuads=12 ... OK-position
 *   matrixProbe[shell]    matrixDelta=0.0000 OK-matrix
 *   matrixProbe[contents] matrixDelta=1.7320 *** THE MATRIX DOES NOT DRAW THE COMPONENT WHERE THE
 *       POSE SAYS: off by 1.7320 block(s) ***
 *   shellContentGap=1.7321 *** THE SHELL AND THE COMPONENT INSIDE IT ARE DRAWN IN DIFFERENT FRAMES ***
 * </pre>
 *
 * <p>{@code 1.7321} is {@code sqrt(3)}: the diagonal of a unit cube, i.e. the distance from a block's
 * <b>corner</b> to its <b>centre</b>. The two call sites build their matrices differently - the shell
 * adds its base to each vertex, the contents path pushed it as a {@code pose.translate} - and the probe
 * added {@code BLOCK_CENTRE} to the centre probe unconditionally, which is right for one of them and
 * wrong for the other. Every other diagnostic in the mod agreed the frame was fine, because the
 * geometry was in fact on the block; only the probe's own bookkeeping was off, and it was the one line
 * the investigation had been told to trust.
 *
 * <p>The fix is for both call sites to be the same expression - {@code T(position) · R · S} with the
 * block's corner as a base added per vertex - so the probe has nothing to reconcile. These tests hold
 * that: <b>four independent checks</b>, at three rotations, in both coordinate spaces, with both pivot
 * values.
 *
 * <ul>
 *   <li>{@link #contentsLandOnTheBlockCentreExactly()} - the component's matrix and the double
 *       arithmetic put the block's centre at the same world point.</li>
 *   <li>{@link #shellAndContentsAreInOneFrame()} - {@code shellContentGap == 0}: the number the log
 *       reported as 1.7321.</li>
 *   <li>{@link #probeLineReportsOkEverywhere()} - the three verdicts a reader greps for
 *       ({@code matrixDelta}, {@code cornerDelta}, {@code shellContentGap}) are all OK in the rendered
 *       line, not merely in the arithmetic behind it.</li>
 *   <li>{@link #carriedFlushIsLaterThanTheWorldFlush()} - the carried geometry really is flushed at a
 *       later render stage than the world geometry, which is what stops a structure's own blocks from
 *       covering it.</li>
 * </ul>
 */
class CarriedProbeTest {

    /** A camera far from the machine, so the camera translation is part of what is asserted. */
    private static final Vector3d CAMERA = new Vector3d(2700.25, 90.5, -1300.75);

    /**
     * The tolerance for the two call sites' <b>relative</b> agreement: a millionth of a block.
     *
     * <p>Appropriate here, and not for the comparison against the double arithmetic below, because both
     * call sites run the same camera-relative {@code float} pipeline with the same base - so whatever
     * the pipeline loses, it loses identically on both sides and cancels. See {@link #WORLD_TOLERANCE}.
     */
    private static final double EXACT = 1.0E-6;

    /**
     * The tolerance for comparing a {@code float}-pipeline position against the {@code double} contract.
     *
     * <p>Not {@link #EXACT}: the world coordinate is about 2775, where {@code float} spacing is
     * {@code 2.4e-4} - so a rendered position simply cannot agree with a {@code double} one more closely
     * than that, and a test demanding otherwise would be asserting something about IEEE-754 rather than
     * about the renderer. A ten-thousandth of a block is still four hundred times finer than a pixel at
     * any view distance this feature is legible from.
     */
    private static final double WORLD_TOLERANCE = 1.0E-4;

    /** The three rotations every case is run at: no turn, a third of one, and a right angle. */
    private static final double[] DEGREES = { 0, 30, 90 };

    // ------------------------------------------------------------------ fixtures --

    /**
     * A Create contraption holding one host block, with the pivot the author's log names.
     *
     * <p>{@code position=(2775.5, 77.5, -1394.5)}, {@code pivot=(0.5, 0.5, 0.5)}, {@code local=(0,0,0)} -
     * every number here is quoted from the {@code carrier claim} line of the author's log.
     */
    private static CarrierPose contraption(double degrees, double pivot) {
        return new CarrierPose(new Vector3d(2775.5, 77.5, -1394.5), aboutX(degrees),
                new Vector3d(pivot, pivot, pivot), 1.0);
    }

    /**
     * A Sable sub-level whose local coordinate is a plot coordinate.
     *
     * <p>{@code local=(20481032, 127, 20524040)} is the author's, and is the case that catches a
     * {@code float} narrowing: {@code 20481032.0f + 0.5f} is {@code 20481032.0f}.
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
     * The world point the <b>contents</b> path draws the block's centre at, through the real code.
     *
     * <p>This is the carried path's own arithmetic: {@code applyCarrierFrame} on the stack, then
     * {@code T(base) · transform(visual)} - see {@link HostRenderPass#carriedModelBase} and
     * {@code drawModel}. The block model's vertices are the usual {@code 0..1}, so its centre is at
     * {@code base + (0.5, 0.5, 0.5)}; the identity visual transform stands in for
     * {@code InnerComponentVisuals.Visual#transform}, which rotates about the block's centre and so
     * leaves it fixed.
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
            // The number the author's log reported as 1.7321 - sqrt(3), corner to centre.
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

            // The two probes, taken exactly where the renderer takes them: the shell's at its own call
            // site, the contents' at its own. Both after `applyCarrierFrame`, because the fixed form of
            // the contents path no longer translates after it.
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
    @DisplayName("the carried geometry is flushed at a later stage than the world geometry")
    void carriedFlushIsLaterThanTheWorldFlush() {
        // The whole reason the carried path has a stage of its own. `after_entities` is where the world
        // path runs and must keep running; a contraption is drawn after it - Flywheel's visual pass runs
        // at `blockentities` - so anything flushed at the world stage is covered by the machine's own
        // blocks. These two names are also what the frame summary prints, so a reader can check them
        // against the game's own dispatcher.
        assertEquals(HostRenderPass.WORLD_STAGE_NAME, HostRenderPass.Stage.WORLD.stageName());
        assertEquals(HostRenderPass.CARRIED_STAGE_NAME, HostRenderPass.Stage.CARRIED.stageName());
        assertTrue(HostRenderPass.Stage.CARRIED.order() > HostRenderPass.Stage.WORLD.order(),
                "the carried flush must happen after the world flush: world order "
                        + HostRenderPass.Stage.WORLD.order() + ", carried order "
                        + HostRenderPass.Stage.CARRIED.order());
        assertTrue(HostRenderPass.Stage.WORLD.collectsEverything(),
                "the world stage draws the hosts standing in the world");
        assertTrue(!HostRenderPass.Stage.CARRIED.collectsEverything(),
                "the carried stage does not draw the world path");
        // Dispatch is by Stage OBJECT rather than by name - see `dispatchIsByStageObjectNotByName` for why
        // that is not a style preference. These two objects stand in for the two real stages.
        Object world = new Object();
        Object carried = new Object();
        assertTrue(HostRenderPass.isStage(world, world));
        assertTrue(HostRenderPass.isStage(carried, carried));
        assertFalse(HostRenderPass.isStage(carried, world),
                "a dispatcher event for one stage must not be taken for the other");
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
        // The two stages the pass owns, by the name the dispatcher uses - printed whether or not either
        // has run, because the stage is a property of the build and the counts are a property of the
        // frame. A reader compares them: the carried one must be later, or a structure's own blocks
        // cover its components however many quads it emitted.
        assertTrue(line.contains("worldQuadsFlushedAt=" + HostRenderPass.WORLD_STAGE_NAME), line);
        assertTrue(line.contains("carriedQuadsFlushedAt=" + HostRenderPass.CARRIED_STAGE_NAME), line);
        // The ordering rule itself is guidance rather than a reading, so it lives in the explanation -
        // which is printed once per verdict change instead of on every line. See CarrierFrameLogTest,
        // and see the explanation for why the heavy text moved out of the per-frame line.
        assertTrue(HostRenderPass.flushStageExplanation().contains("must be the LATER"),
                "the ordering rule must still be stated, once, in the explanation");
        // Both halves of each path are reported separately, because "the shell was drawn and the
        // component was not" and "neither was" are different defects with different causes.
        for (String field : new String[] { "worldOwnQuads=", "worldShellQuads=", "worldContentQuads=",
                "carriedQuads=", "carriedShellQuads=", "carriedContentQuads=" }) {
            assertTrue(line.contains(field), "the flush line does not report " + field + ": " + line);
        }
        // The world shell is not ours to submit, and the line says so rather than looking like a stage
        // that never ran - which is exactly the misreading this diagnostic exists to prevent, and the
        // false alarm it produced 305 times.
        assertTrue(line.contains("worldShellHandedOverQuads="), line);
        assertTrue(line.contains("NOT flushed by us"), line);
        // A stage that has not run since the client started must say so, rather than printing a zero
        // that reads exactly like "it ran and wrote nothing".
        assertTrue(line.contains("flushed=NOT-YET-THIS-SESSION"),
                "a stage that has never run must say so, not report a bare zero: " + line);
    }

    @Test
    @DisplayName("dispatch is by Stage object; the printed name carries the minecraft: namespace")
    void dispatchIsByStageObjectNotByName() {
        // THE REGRESSION THIS PINS, and it is offline-testable precisely because it was a string bug.
        //
        // `RenderLevelStageEvent.Stage` builds its name through a ResourceLocation:
        //   private static Stage register(String name, RenderType t) {
        //       return register(ResourceLocation.parse(name), t); }
        //   private static Stage register(ResourceLocation name, RenderType t) {
        //       new Stage(name.toString()); }
        // `ResourceLocation.parse("after_entities")` has no namespace, so it gains the default one and
        // `toString()` is "minecraft:after_entities". An earlier revision dispatched on
        // `event.getStage().toString()` compared with the bare "after_entities": false on every frame,
        // forever, and the whole overlay - including its own diagnostics - silently stopped. That is the
        // failure this test makes impossible to reintroduce.
        //
        // The real `Stage` constants cannot be loaded here (`RenderType` -> `ItemRenderer` -> the vanilla
        // registries throw "Not bootstrapped"), so the trap is reproduced with the same Minecraft class
        // the game itself uses to build that name.
        String bare = "after_entities";
        String asTheGameSpellsIt = net.minecraft.resources.ResourceLocation.parse(bare).toString();
        assertEquals("minecraft:" + bare, asTheGameSpellsIt,
                "the prefix is the whole defect: ResourceLocation adds the default namespace");
        assertNotEquals(bare, asTheGameSpellsIt,
                "a bare stage name must NOT compare equal to what Stage#toString produces");

        // And the guard the fix introduced: identity on the Stage object, which cannot go wrong this way.
        Object allowed = new Object();
        Object other = new Object();
        assertTrue(HostRenderPass.isStage(allowed, allowed), "the same Stage object is our stage");
        assertFalse(HostRenderPass.isStage(other, allowed),
                "a different Stage object is not, however it prints");
        assertFalse(HostRenderPass.isStage(allowed, null),
                "a stage this build could not load must not match anything");
    }

    @Test
    @DisplayName("a session that never flushed says so, in the flush line the summary carries")
    void sessionThatNeverFlushedSaysSo() {
        // The shape of the author's regression log: no frame summary at all, the counters appearing only
        // in the `carrier gone` line. This asserts the line now carries the two facts that would name it
        // - which stage each half goes to, and that neither has run yet.
        String line = HostRenderPass.flushStageLine();
        assertTrue(line.contains("worldQuadsFlushedAt=" + HostRenderPass.WORLD_STAGE_NAME), line);
        assertTrue(line.contains("carriedQuadsFlushedAt=" + HostRenderPass.CARRIED_STAGE_NAME), line);
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

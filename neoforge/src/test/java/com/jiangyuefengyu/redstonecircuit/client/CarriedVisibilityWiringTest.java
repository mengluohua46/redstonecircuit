package com.jiangyuefengyu.redstonecircuit.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>The wiring behind S1 and S2, pinned in the source.</b>
 *
 * <p>两条规则都落在裸 JVM 上走不到的调用点（{@code RenderType} 的类初始化需要原版注册表，{@code ClientHostCache}
 * 会碰到 {@code RCConfig} 和 {@code Minecraft}），所以这里断言源码里连线的<em>形状</em>，用大括号计数限定到方法体，
 * 并在失败信息里说清形状错了屏幕上会付出什么代价。入口是原点遍历与姿态遍历的并集（姿态遍历只做加法、用自己的半径），
 * 视线规则交给 {@code CarriedSight}，调用点自己不做任何距离算术。
 */
class CarriedVisibilityWiringTest {

    // ------------------------------------------------------------------ S2 --

    @Test
    @DisplayName("S2: the entry point is the UNION of the origin walk and the pose walk")
    void theEntryPointWalksBothAndTheOriginWalkIsUnconditional() throws IOException {
        String source = readMainSource("carrier", "CarrierIndex.java");

        String body = bodyOf(source, "public List<Drawable> drawablesNear");
        assertTrue(body.contains("forEachNear(dim"),
                "THE ORIGIN WALK MUST STILL BE CALLED. It is what guarantees the entry point never hands "
                        + "over less than it did, and making the pose walk the only source renders nothing");
        assertTrue(body.contains("forEachCarrierNearPose("),
                "and the pose walk is the addition that finds a machine which has travelled beyond the "
                        + "origin walk's radius");
        assertTrue(body.contains("putIfAbsent(identityOf(view), view)"),
                "the pose walk may only ADD: `putIfAbsent` means it cannot even overwrite what the origin "
                        + "walk produced, so the union is monotone by construction");
        assertTrue(body.indexOf("forEachNear(dim") < body.indexOf("forEachCarrierNearPose("),
                "and the origin walk comes first, so it populates the fold the pose walk then adds to");

        assertTrue(body.contains("POSE_ANCHOR_RADIUS"),
                "the pose walk keeps its own radius: it is the renderer's reach expressed in the carrier's "
                        + "frame, not the caller's radius");
        assertTrue(source.contains("static final int POSE_ANCHOR_RADIUS = 7"),
                "S2 fixes that radius at seven sections - the same 112 blocks HostRenderPass#DRAW_DISTANCE "
                        + "uses, which is why the two must change together");

        String poseWalk = bodyOf(source, "public void forEachCarrierNearPose");
        assertTrue(poseWalk.contains("anchorsByPose."),
                "the pose walk reads the pose anchor, which is the only thing that moves");
        assertFalse(poseWalk.contains("view.origin()"),
                "and it must never fall back to the origin, which is the anchor the defect was in");
    }

    @Test
    @DisplayName("S2: the pose anchor is keyed and compared exactly as the origin walk is")
    void thePoseAnchorUsesTheSameFrameAsTheOriginWalk() throws IOException {
        String source = readMainSource("carrier", "CarrierIndex.java");

        assertTrue(bodyOf(source, "private void put").contains("SectionPos.asLong(host.origin())"),
                "the origin bucket's key: SectionPos.asLong of a block position");
        assertTrue(bodyOf(source, "private static long poseSectionKey")
                        .contains("SectionPos.asLong(BlockPos.containing("),
                "and the pose anchor's key is the same call shape on the pose's block position - one "
                        + "coordinate system, so the two walks cannot disagree about what 'near' means. "
                        + "(What it cannot promise is that a pose's position is a world position at all; "
                        + "that is the pose's business, and it is why this walk is additive.)");
        assertTrue(bodyOf(source, "public void forEachCarrierNearPose")
                        .contains("SectionPos.blockToSectionCoord(center.getX())"),
                "both walks compare against the camera's own section, taken the same way");
        assertTrue(source.contains("anchorsByPose.clear()"),
                "and the anchor is cleared with the dimension it belongs to");
    }

    @Test
    @DisplayName("S2: the pose anchor is maintained wherever a pose or a host set changes")
    void theAnchorIsKeptHonest() throws IOException {
        String source = readMainSource("carrier", "CarrierIndex.java");

        assertTrue(bodyOf(source, "public void applySnapshot").contains("anchor("),
                "both halves of a snapshot must be filed, whatever order they arrived in");
        assertTrue(bodyOf(source, "public void applyPose").contains("anchor("),
                "a pose update is the one event that moves a carrier, so the anchor is refiled there - in "
                        + "the same call that stores the pose, which is what keeps the two in step");
        assertTrue(bodyOf(source, "public void applyHost").contains("anchor("),
                "a host that arrives later must still be reachable through the anchor");
        assertTrue(bodyOf(source, "public void drop").contains("unanchor("),
                "a carrier that is let go of must leave no anchor behind - a stale entry would keep the "
                        + "extra walk visiting a structure that no longer exists");
        assertTrue(bodyOf(source, "public void clear").contains("anchorsByPose.clear()"),
                "and a dimension change starts from nothing");
    }

    // ------------------------------------------------------------------ S1 --

    @Test
    @DisplayName("S1: the sight rule is the testable predicate, and the call site holds no arithmetic")
    void theSightRuleIsOneTestableDecision() throws IOException {
        String source = readMainSource("client", "HostRenderPass.java");
        String canSeePoint = bodyOf(source, "private static boolean canSeePoint");
        String castSightRay = bodyOf(source, "private static boolean castSightRay");

        // `canSeePoint` is the boolean face and holds no rule of its own: it hands the point and the block
        // it is in to the one method that casts the ray.
        assertTrue(canSeePoint.contains("castSightRay("),
                "the boolean the picture depends on must be the one the diagnostic reports, or the log and "
                        + "the screen are two answers to one question");
        assertTrue(canSeePoint.contains("BlockPos.containing(target)"),
                "the target block P is the block the component's centre is in - the game's own floor, so "
                        + "this mod adds no tolerance of its own");
        // The rule itself is `CarriedSight`'s, and it is called from exactly one place - the method that
        // casts the ray - so the predicate the unit tests drive is the one production runs.
        assertTrue(castSightRay.contains("CarriedSight.isVisible("),
                "the predicate the unit tests drive must be the one production uses, or the tests assert a "
                        + "rule nobody runs");
        assertTrue(castSightRay.contains("CarriedSight.Ray.miss()")
                        && castSightRay.contains("CarriedSight.Ray.hit("),
                "and the ray's two answers are handed over as S1's two cases: 'met nothing' is a rule, not "
                        + "a missing block");
        assertFalse(castSightRay.contains("0.25"),
                "no tolerance: a slack around the target's squared distance would hide a component behind it");
        assertFalse(castSightRay.contains("distanceToSqr"),
                "and no distance comparison of any kind - S1 rule 4 is 'the ray met some other block'");
        assertFalse(canSeePoint.contains("0.25") || canSeePoint.contains("distanceToSqr"),
                "the face of the rule holds no arithmetic either");

        // The world path is a different rule: its hosts are real blocks whose own face answers the ray.
        assertTrue(bodyOf(source, "private static boolean canSee(").contains("hit.getBlockPos().equals(pos)"),
                "the world path still decides by 'the ray hit the host's own block', which must not change");
    }

    @Test
    @DisplayName("S1: the two call sites ask their own question, and neither can be swapped in")
    void theTwoCallSitesAskTheirOwnQuestion() throws IOException {
        String source = readMainSource("client", "HostRenderPass.java");
        String collect = bodyOf(source, "private static CarriedResult collectCarried");

        // The ray caster takes the host's own coordinate, in the frame the ray answers in, and hands it to
        // the one predicate - that is what makes "the ray hit the component's own block" answerable at all.
        assertTrue(bodyOf(source, "private static boolean castSightRay")
                        .contains("ownBlockInItsOwnFrame"),
                "the one ray caster must be told which block the component is in, in the ray's own frame");
        // The carried path builds it from the host's local coordinate, and only when that coordinate is a
        // plot coordinate: an anchor-relative local coordinate is a small number that a world hit could
        // coincide with, and comparing those would be a false "own block".
        assertTrue(collect.contains("CarrierDrawCheck.plotSpace(view.localPos())"),
                "only a plot-space coordinate may be compared against a hit in its own frame");
        assertTrue(collect.contains("? view.localPos()") && collect.contains(": null"),
                "the carried path passes its own coordinate when it has one, and nothing when it does not");
        // A fallback's block IS the world block it is drawn at, so it has only one frame and passes null.
        assertTrue(collect.contains("fallback, null, new SightRay()"),
                "a fallback host's block is the world block it is drawn at: one frame, nothing to compare");
    }

    @Test
    @DisplayName("S1: the predicate itself carries no distance sphere and no tolerance")
    void thePredicateCarriesNoTolerance() throws IOException {
        String source = readMainSource("client", "CarriedSight.java");
        // The IMPLEMENTING overload, not the delegating one: both declarations start the same way, and a guard
        // that reads the wrong one stops guarding without failing. The trailing comma tells them apart.
        String body = bodyOf(source,
                "static boolean isVisible(double eyeX, double eyeY, double eyeZ, BlockPos target, "
                        + "Ray ray,");
        // And the short form is the ordinary one: the component's block is the world block at the target.
        assertTrue(bodyOf(source, "static boolean isVisible(double eyeX, double eyeY, double eyeZ, "
                        + "BlockPos target, Ray ray)").contains("ray, null)"),
                "the five-argument form must stay the ordinary, one-frame question");

        assertFalse(source.contains("ON_TARGET ="),
                "no 'within N blocks of the target point' rule: a block's face is half a block from its "
                        + "centre, so no such radius can admit the component's own block - hence this checks "
                        + "for the declaration rather than the word");
        assertFalse(body.contains("0.25"), "no tolerance in the decision");
        assertTrue(body.contains("ray.hitBlockPos().equals(target)"),
                "rule 3 is an equality of block coordinates - the same question the world path asks");
    }

    @Test
    @DisplayName("the carried geometry is still submitted at the tail of the frame with depth ignored")
    void theRenderPathItselfIsUnchanged() throws IOException {
        String types = readMainSource("client", "HostRenderTypes.java");
        String contents = bodyOf(types, "private static RenderType createCutoutIgnoringDepth");

        assertTrue(contents.contains("RenderStateShard.NO_DEPTH_TEST"),
                "the reason the sight rule is load-bearing: the carried contents still ignore the depth "
                        + "buffer, because the block the component lives in is drawn by the structure's own "
                        + "renderer around it, so the rule is enforced before collection instead");
        assertTrue(contents.contains("RenderStateShard.CULL"), "face culling is unchanged too");
        assertTrue(types.contains("CARRIED_FLUSH_ORDER = { CARRIED_CONTENTS, CARRIED_SHELL }"),
                "and the flush order - content first, shell second - is untouched");
        assertTrue(readMainSource("client", "HostRenderPass.java")
                        .contains("HostRenderTypes.CARRIED_BUFFERS"),
                "as is the separate carried buffer source");
    }

    // ------------------------------------------------------------- helpers --

    /** Finds one of the mod's main sources, failing loudly rather than silently skipping the guard. */
    private static String readMainSource(String packageName, String fileName) throws IOException {
        Path relative = Path.of("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit",
                packageName, fileName);
        Path absolute = Path.of("neoforge").resolve(relative);
        for (Path candidate : List.of(relative, absolute)) {
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.UTF_8);
            }
        }
        throw new IOException("could not find " + fileName + " from " + Path.of("").toAbsolutePath()
                + " (tried " + relative + " and " + absolute + "); this guard reads the source on purpose, "
                + "so it must not be skipped silently");
    }

    /** The body of the first member whose declaration starts with {@code signature}, scoped by brace counting: a
     * line window would still pass once the method grows, which is how a guard quietly stops guarding. */
    private static String bodyOf(String source, String signature) {
        int at = source.indexOf(signature);
        assertTrue(at >= 0, "the source no longer contains '" + signature + "'; the guard is stale");
        int open = source.indexOf('{', at);
        assertTrue(open > 0, "no body found for '" + signature + "'");
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return source.substring(at, i);
                }
            }
        }
        throw new IllegalStateException("unbalanced braces after '" + signature + "'");
    }
}

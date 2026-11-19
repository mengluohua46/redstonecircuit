package com.jiangyuefengyu.redstonecircuit.block;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * <b>Every 贴面 family asks "is that face sturdy" through one read, and that read looks at both frames.</b>
 *
 * <p>The question used to be {@code level.getBlockState(support).isFaceSturdy(...)} in each family, which answers air for
 * a block a machine is carrying - so a face on a machine was refused, while the same cell accepted it whenever another
 * face of it already stood on real ground (作者 2026-11-16: 墙地交界处要先放地上再放墙上才行).
 *
 * <p>A source guard rather than a behaviour test, because the mistake is a <em>second</em> copy of the read: one family
 * left on the world-only form breaks only on a moving machine, which a headless test cannot build for every family.
 * {@code CarriedSupportGameTest} proves the shared read really answers through a carrier.
 */
class CarriedSupportWiringTest {

    @Test
    @DisplayName("七族贴面的支撑判定都走 CarriedSupport，没有谁自己读世界那一格")
    void everyFamilyAsksTheSharedSupportRead() throws IOException {
        List<String> files = List.of(
                "SurfaceWireBlock.java", "SurfaceDiodeBlock.java", "SurfacePressurePlateBlock.java",
                "SurfaceTorchBlock.java", "SurfaceTriggerBlock.java", "WeatherDetectorBlock.java",
                "RcPlateBlock.java");
        for (String name : files) {
            assertTrue(source(name).contains("CarriedSupport.sturdy"),
                    name + " 没走 CarriedSupport：被搬走的方块在世界那一格是空气，这一族就贴不上去");
        }
        // Nothing in the wire reads a face from the world alone any more: the support question and the two
        // connection questions ("is that a wire", "does that block accept a connection") all go through the shared
        // carrier-aware read, because a carried neighbour answered air and the junction went dark (作者 2026-11-16).
        String wire = source("SurfaceWireBlock.java");
        assertEquals(0, occurrences(wire, "isFaceSturdy"),
                "SurfaceWireBlock 还有世界单帧的面读：物理化那一侧会被答成「不结实」");
    }

    private static int occurrences(String source, String needle) {
        int found = 0;
        for (int at = source.indexOf(needle); at >= 0; at = source.indexOf(needle, at + needle.length())) {
            found++;
        }
        return found;
    }

    /** The body of the first member whose declaration starts with {@code signature}, by brace counting. */
    private static String bodyOf(String source, String signature) {
        int at = source.indexOf(signature);
        assertTrue(at >= 0, "the source no longer contains '" + signature + "'; the guard is stale");
        int open = source.indexOf('{', at);
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

    private static String source(String name) throws IOException {
        Path relative = Path.of("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "block", name);
        if (Files.isRegularFile(relative)) {
            return Files.readString(relative, StandardCharsets.UTF_8);
        }
        throw new IOException("could not find " + name + " from " + Path.of("").toAbsolutePath());
    }
}

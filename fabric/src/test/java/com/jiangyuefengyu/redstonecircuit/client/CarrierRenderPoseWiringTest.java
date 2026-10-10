package com.jiangyuefengyu.redstonecircuit.client;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A sub-level is drawn with the pose Sable is itself drawing it at, not with a reconstruction of it.
 *
 * <p>Sable's client sub-level samples its own snapshot buffer by time ({@code ClientSubLevel#renderPose}), so
 * its pose is its own timeline; "the last two poses the server sent, interpolated by the partial tick" is a
 * different one, and the two beat against each other - a component flickering between the leading position and
 * the block's. The two links of that chain that live in this build's own tree are pinned from its source: the
 * carried pass asks the compat gate for a render pose and prefers it, and the gate answers {@code null} - never
 * an exception, and without loading a mod type - when there is no backend, no Sable, or no client sub-level.
 *
 * <p>The half that reads Sable's own pose belongs to the optional compat source set, which is not compiled into
 * this build at all, so no test here can read it or check the names the gate reflects on against it.
 */
class CarrierRenderPoseWiringTest {

    @Test
    @DisplayName("the carried pass asks the compat gate for a Sable render pose and prefers it")
    void theRenderPassPrefersSablesOwnPose() throws IOException {
        String source = read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "client", "HostRenderPass.java");
        String body = bodyOf(source, "private static CarriedResult collectCarried");

        assertTrue(body.contains("PhysicsCompat.renderPoseOf("),
                "the frame must ASK Sable where it is drawing the structure: without this the components "
                        + "are drawn from the network's timeline and cross the machine");
        assertTrue(body.contains("live != null ? live : drawable.pose()"),
                "and the answer is the pose, with the (interpolated) network pose only as the fallback - "
                        + "a Create contraption, no Sable, or a sub-level the client does not hold");
        assertTrue(body.contains("computeIfAbsent("),
                "asked once per carrier per frame: the pass is per host, and a machine's hosts share one "
                        + "pose");
    }

    @Test
    @DisplayName("the gate is gated: no backend, no Sable, no sub-level answers null without loading it")
    void theGateAnswersNullRatherThanLoadingSable() throws IOException {
        String body = bodyOf(read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "compat", "physics", "PhysicsCompat.java"),
                "public static com.jiangyuefengyu.redstonecircuit.carrier.CarrierPose renderPoseOf");

        assertTrue(body.contains("backendClass == null"),
                "a jar without the compat half answers null rather than touching the backend class");
        assertTrue(body.contains("!sableLoaded()"),
                "and so does a game without Sable");
        assertTrue(body.contains("!carrier.isSubLevel()"),
                "a Create contraption is not asked of Sable at all");
        assertTrue(body.contains("result instanceof"),
                "and a backend that cannot answer is null, never an exception on the render thread");
    }

    // ------------------------------------------------------------- helpers --

    private static String read(String... parts) throws IOException {
        // The optional compat source set is not compiled into this build, so the only root is this tree's own.
        Path direct = Path.of("", parts);
        if (Files.isRegularFile(direct)) {
            return Files.readString(direct, StandardCharsets.UTF_8);
        }
        throw new IOException("could not find " + String.join("/", parts) + " from "
                + Path.of("").toAbsolutePath() + "; this guard reads the source on purpose");
    }

    /** The body of the first member whose declaration contains {@code signature}, by brace counting. */
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

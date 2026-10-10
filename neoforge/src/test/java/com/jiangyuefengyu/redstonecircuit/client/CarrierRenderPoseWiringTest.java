package com.jiangyuefengyu.redstonecircuit.client;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A Sable sub-level is drawn with Sable's own render pose, not with a reconstruction of it.
 *
 * <p>Sable's client sub-level samples its own snapshot buffer by time ({@code ClientSubLevel#renderPose}), so
 * its pose is its own timeline; "the last two poses the server sent, interpolated by the partial tick" is a
 * different one, and the two beat against each other - a component flickering between the leading position
 * and the block's. The assertions below are the three links of that chain, read out of the source.
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

    @Test
    @DisplayName("the compat half answers with Sable's OWN render pose for the frame")
    void theCompatHalfUsesSablesRenderPose() throws IOException {
        String source = read("src", "compat", "java", "com", "jiangyuefengyu", "redstonecircuit", "compat", "physics", "CarrierResolver.java");
        String body = bodyOf(source, "public static CarrierPose renderPoseOf");

        assertTrue(body.contains("SubLevelContainer.getContainer("),
                "the client container is how a sub-level is looked up off the wire");
        assertTrue(body.contains("instanceof ClientSubLevel"),
                "and only a CLIENT sub-level can answer where Sable is drawing it");
        assertTrue(body.contains("renderPose(partialTick)"),
                "the pose Sable draws with, sampled from its own snapshot buffer - not a logical pose, and "
                        + "not a pose rebuilt from the packets we receive");
        assertTrue(body.contains("fromPose3d("),
                "converted by the one shared conversion, so a claim checked against the logical pose and "
                        + "the frame drawn with the render pose cannot disagree about the arithmetic");
    }

    @Test
    @DisplayName("every name the gate reflects on is declared on the backend class it reflects on")
    void everyGatedNameExistsOnTheBackend() throws IOException {
        String gate = read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "compat",
                "physics", "PhysicsCompat.java");
        String backend = read("src", "compat", "java", "com", "jiangyuefengyu", "redstonecircuit", "compat",
                "physics", "PhysicsCompatBackend.java");

        // The gate reaches the backend by name, so a name it does not declare fails at every call and the log fills with warnings.
        java.util.regex.Matcher names = java.util.regex.Pattern
                .compile("invoke\\((?:backendClass|backend), \"([A-Za-z0-9_]+)\"")
                .matcher(gate);
        int checked = 0;
        while (names.find()) {
            String name = names.group(1);
            checked++;
            boolean declared = false;
            for (String line : backend.split("\n")) {
                if (line.contains(name + "(") && line.contains("public static")) {
                    declared = true;
                    break;
                }
            }
            assertTrue(declared, "PhysicsCompat reflects on " + name
                    + "(), but PhysicsCompatBackend does not declare it: the call fails every frame it is "
                    + "made, the feature it feeds silently does nothing, and the log fills with one warning "
                    + "per call");
        }
        assertTrue(checked >= 6, "the scan found only " + checked + " gated call(s); the pattern is stale");
    }

    // ------------------------------------------------------------- helpers --

    private static String read(String... parts) throws IOException {
        // The working directory differs between the test task and a build from the repository root, so both spellings are tried.
        Path underNeoforge = Path.of("neoforge").resolve(Path.of("", parts));
        Path direct = Path.of("", parts);
        for (Path candidate : List.of(direct, underNeoforge)) {
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.UTF_8);
            }
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

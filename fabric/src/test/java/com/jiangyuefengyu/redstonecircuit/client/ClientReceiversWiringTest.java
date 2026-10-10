package com.jiangyuefengyu.redstonecircuit.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every payload the server sends has exactly one receiver on the client, and every receiver has a payload.
 *
 * <h2>Why this is a test and not a habit</h2>
 * A receiver that was never registered is invisible in every other way: the payload type is registered, the
 * server sends it, the client's registry simply has nothing to call, and the symptom is a feature that quietly
 * does nothing - no error, no log line, nothing to grep for. That is exactly how the preview border shipped
 * once without its receiver: the borders were sent and cached on the server, the renderer asked the client's
 * cache, and the cache was never told.
 *
 * <p>It is also the one list in the client half that has to be complete rather than merely correct: Fabric's
 * registry keeps the FIRST registration for an id and silently drops the rest, so a receiver named twice is as
 * wrong as one named never - and it would be dropped in whichever order the entrypoint happened to run.
 *
 * <p>Both directions are checked, so the two failure modes cannot hide behind each other: the client-bound
 * types are read out of {@code RCNetwork}, the receivers out of the entrypoint and the three screens that open
 * themselves, and the two sets must be equal.
 */
class ClientReceiversWiringTest {

    /** {@code PayloadTypeRegistry.playS2C().register(HostSnapshotPayload.TYPE, ...)} in RCNetwork. */
    private static final Pattern DIRECTION = Pattern.compile("playS2C\\(\\)\\.register\\((\\w+)\\.TYPE,");

    /** {@code ClientPlayNetworking.registerGlobalReceiver(RCNetwork.HostSnapshotPayload.TYPE, ...)}. */
    private static final Pattern RECEIVER =
            Pattern.compile("registerGlobalReceiver\\((?:RCNetwork\\.)?(\\w+)\\.TYPE,");

    /** Every file that may register a client-bound receiver, entrypoint first. */
    private static final String[] CLIENT_FILES = {
            "RedstoneCircuitClient.java",
            "DeviceScreens.java",
            "LogicIntegratorScreens.java",
            "StructureScreens.java",
    };

    @Test
    @DisplayName("every server-to-client payload has exactly one receiver, and every receiver a payload")
    void everyOutgoingPayloadIsReceivedExactlyOnce() throws IOException {
        Set<String> sent = new TreeSet<>(names(readMain("network", "RCNetwork.java"), DIRECTION));
        // A regex that stopped matching would make the comparison below vacuous; the mod ships eleven
        // client-bound payloads today, so anything close to nothing is a broken guard, not a wire change.
        assertTrue(sent.size() >= 8, "read too few client-bound payloads to judge anything: " + sent);

        Map<String, Integer> registered = new LinkedHashMap<>();
        for (String file : CLIENT_FILES) {
            for (String name : names(readMain("client", file), RECEIVER)) {
                registered.merge(name, 1, Integer::sum);
            }
        }

        Set<String> missing = new TreeSet<>(sent);
        missing.removeAll(registered.keySet());
        assertTrue(missing.isEmpty(),
                "these payloads are sent to the client and nothing handles them: " + missing);

        Set<String> stray = new TreeSet<>(registered.keySet());
        stray.removeAll(sent);
        assertTrue(stray.isEmpty(),
                "these receivers wait for a payload the server never sends: " + stray);

        List<String> twice = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : registered.entrySet()) {
            if (entry.getValue() > 1) {
                twice.add(entry.getKey() + " x" + entry.getValue());
            }
        }
        assertTrue(twice.isEmpty(),
                "and Fabric keeps only the first registration for an id, silently dropping the rest: " + twice);

        assertEquals(sent.size(), registered.size(),
                "one receiver per payload, no more and no fewer: sent=" + sent + " registered="
                        + registered.keySet());
    }

    /** The type names a pattern finds, in the order they appear. */
    private static List<String> names(String source, Pattern pattern) {
        List<String> found = new ArrayList<>();
        Matcher matcher = pattern.matcher(source);
        while (matcher.find()) {
            found.add(matcher.group(1));
        }
        return found;
    }

    /** One of the mod's main sources, addressed from this project's own directory. */
    private static String readMain(String packagePath, String fileName) throws IOException {
        Path relative = Path.of("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit",
                packagePath, fileName);
        if (Files.isRegularFile(relative)) {
            return Files.readString(relative, StandardCharsets.UTF_8);
        }
        throw new IOException("could not find " + fileName + " from " + Path.of("").toAbsolutePath()
                + " (tried " + relative + "); this guard reads the source on purpose, so it must not be "
                + "skipped silently");
    }
}

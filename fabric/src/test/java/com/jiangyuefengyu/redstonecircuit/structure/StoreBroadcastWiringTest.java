package com.jiangyuefengyu.redstonecircuit.structure;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every change to the inner-redstone store has to be named on the wire.
 *
 * <p><b>The client's copy of a component is a copy of the store, not of the block</b>, so a store change nobody
 * announces leaves the client drawing something the server does not have - and the mirror case, a component the server
 * has and the client does not, leaves the host invisible until some unrelated event touches it. The rule is one
 * sentence: <b>a component that appears or disappears has to be broadcast</b>.
 *
 * <p>A source guard rather than a game test, because a game test cannot see the <em>wire</em>: it runs with no client,
 * so a missing broadcast is invisible to it. The call sites are pinned here, where a deleted line shows up as a red
 * test.
 */
class StoreBroadcastWiringTest {

    @Test
    @DisplayName("取走（剪切捕获）与放回（释放）都在线上说了话")
    void bothDirectionsOfAStoreChangeAreAnnounced() throws IOException {
        String capture = read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "structure",
                "StructureCapture.java");
        assertTrue(capture.contains("InnerRedstoneInteraction.forget(level, pos);"),
                "a cut capture takes the components into its payload, so nothing is dropped - but the removal still "
                        + "has to be announced, or the client keeps drawing a component the server no longer has");
        assertFalse(capture.contains("store.remove(pos);"),
                "and it must not remove from the store by hand: that is the line whose missing broadcast left the "
                        + "client drawing a component the server no longer has");

        String release = read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "structure",
                "StructureRelease.java");
        assertTrue(release.contains("HostSync.broadcast(job.level(), pos, node.slot());"),
                "and the other direction: a component put back into the world by a release has to be announced, or the "
                        + "server has it and the client does not");
    }

    @Test
    @DisplayName("从 store 里取走元件只有一处实现，而且那一处会广播")
    void theRemovalIsSpelledOutOnce() throws IOException {
        String interaction = read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit",
                "InnerRedstoneInteraction.java");

        String forget = interaction.substring(interaction.indexOf("public static InnerRedstoneNode forget(ServerLevel"),
                interaction.indexOf("// ----------------------------------------------------------------- utils --"));
        assertTrue(forget.contains("HostSync.broadcast(level, pos, null);"),
                "forget is the one place a component leaves a position without being dropped, and it names the "
                        + "position on the wire");
        assertTrue(forget.contains("InnerRedstoneNetwork.markDirtyWithNeighbours(level, pos);")
                        && forget.contains("notifyNeighbours(level, pos);")
                        && forget.contains("WrenchSelection.clearIf(level, pos);"),
                "and it is the whole sequence the other two callers would otherwise spell out for themselves, which is "
                        + "how one of them can come to be missing a line");

        assertTrue(interaction.contains("InnerRedstoneNode node = forget(level, pos);"),
                "cleanup - breaking a host - is forget plus the drop");
        assertTrue(interaction.contains("InnerRedstoneNode removed = forget(level, pos);"),
                "and taking a component back out with the wrench is forget plus the hand-off to the player");
    }

    /** Reads a source file of this project, addressed from its own directory, e.g. {@code src/main/java/...}. */
    private static String read(String... parts) throws IOException {
        Path path = Path.of("", parts);
        if (Files.isRegularFile(path)) {
            return Files.readString(path, StandardCharsets.UTF_8);
        }
        throw new IOException("could not find " + String.join("/", parts) + " from "
                + Path.of("").toAbsolutePath() + "; this guard reads the file on purpose");
    }
}

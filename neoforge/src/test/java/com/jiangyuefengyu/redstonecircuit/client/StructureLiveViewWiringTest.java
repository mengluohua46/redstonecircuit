package com.jiangyuefengyu.redstonecircuit.client;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The two rules the client's live view stands on, asserted where they are written.
 *
 * <p>Both are one line inside a loop, which is exactly what a later refactor drops without noticing: a placeholder
 * written into the view is a hole exactly where the piston is, and a view emptied on a guess is a miniature that
 * silently falls back to the captured blueprint. The class they live in cannot be reached by a plain unit test - it
 * needs the block state registry and a level - so this guard reads the source.
 */
class StructureLiveViewWiringTest {

    private static String liveViews() throws IOException {
        Path relative = Path.of("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "client",
                "StructureLiveViews.java");
        Path absolute = Path.of("neoforge").resolve(relative);
        for (Path candidate : new Path[] {relative, absolute}) {
            if (Files.exists(candidate)) {
                return Files.readString(candidate, StandardCharsets.UTF_8).replace("\r\n", "\n");
            }
        }
        throw new IOException("could not find StructureLiveViews.java from " + Path.of("").toAbsolutePath());
    }

    @Test
    @DisplayName("a moving-piston placeholder is refused BEFORE it can be written into the view")
    void aPlaceholderNeverEntersTheView() throws IOException {
        String source = liveViews();
        int refusal = source.indexOf("is(Blocks.MOVING_PISTON)");
        int write = source.indexOf("cells.put(indices[i], state);");
        assertTrue(refusal > 0, "the view has to refuse a moving-piston placeholder at all");
        assertTrue(write > 0, "and it still has to write the cells that are real");
        assertTrue(refusal < write,
                "the refusal has to come first: a placeholder renders as nothing, so writing one punches a hole where "
                        + "the piston - or the block it is pushing - is standing");
    }

    @Test
    @DisplayName("the view is emptied only when the server says a whole picture begins")
    void theViewIsEmptiedOnTheFlagAndNotOnAGuess() throws IOException {
        String source = liveViews();
        assertTrue(source.contains("if (restart) {"), "the restart flag is what empties the view");
        assertTrue(!source.contains("indices[0] == 0"),
                "and never a guess about the first index: a structure's own corner cell changes on its own, and a diff "
                        + "that happened to start there would empty the whole view");
    }
}

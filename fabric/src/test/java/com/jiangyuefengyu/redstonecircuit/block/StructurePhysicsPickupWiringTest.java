package com.jiangyuefengyu.redstonecircuit.block;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The physics assembler's pickup - the lines that decide whether a physicalised structure is still a structure.
 *
 * <p>Sable clears the world side of a block before any sub-level bounds cover that position, so a removal
 * cannot be classified by asking who holds the block at that instant; the only thing knowable there is whether
 * a player asked for it. Those rules sit in methods that need a running server, a pocket dimension and a live
 * sub-level to reach, so the guard reads the source.
 */
class StructurePhysicsPickupWiringTest {

    @Test
    @DisplayName("a removal no player asked for stamps the plot travelling instead of giving it back")
    void aRemovalNobodyAskedForWaitsForTheBlock() throws IOException {
        String block = read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "block",
                "StructureBlock.java");
        String body = bodyOf(block, "private static void releasePlot(");

        assertTrue(body.contains("boolean byPlayer"),
                "the caller is the only thing at that instant that can tell an assembler's pickup from a break, so it "
                        + "has to be told which one it was");
        int nonPlayer = body.indexOf("if (!byPlayer) {");
        int stamped = body.indexOf("noteTravelling(", nonPlayer);
        int released = body.indexOf("PocketMaterializer.release(server, plot)");
        assertTrue(nonPlayer >= 0 && stamped > nonPlayer && released > stamped,
                "the non-player path stamps the plot travelling (that stamp is what keeps the sweep off a plot whose "
                        + "owner record is momentarily pointing at air - PocketSpace#ownerIsGone) and returns before "
                        + "the release below can run");
        assertTrue(bodyOf(block, "public void playerDestroy(").contains("releasePlot(server, product, true)"),
                "a break is the player's, and only a break may free the plot");
        assertTrue(bodyOf(block, "protected void onRemove(").contains("releasePlot(server, product, false)"),
                "every other removal - the assembler, a piston, an explosion, a command - comes through onRemove and "
                        + "must not be read as a break");
    }

    @Test
    @DisplayName("a product standing in a plot IS re-owned, to the coordinate it really stands at")
    void aProductInsideAPlotIsStillReowned() throws IOException {
        String body = bodyOf(readStructureBlockEntity(), "public static void serverTick(");

        assertTrue(body.contains("PocketSpace.of(pocket).reown(product.plotIndex, server.dimension(), pos, "
                        + "product.structureId);"),
                "a Sable sub-level keeps the level's own blocks in a reserved plot region, so the block's own position "
                        + "is a real coordinate of this level - and reown's landing branch is what moves the record "
                        + "there and clears the travelling stamp");
        assertFalse(body.contains("if (!frame.carried()) {"),
                "the record must move to the block's own position: leaving it at the world position the "
                        + "assembler has emptied lets the lease run out on a structure that is still standing "
                        + "inside the sub-level");
    }

    @Test
    @DisplayName("the carried tick releases a rate-limited input exactly as the standing tick does")
    void theCarriedTickFlushesAHeldInjection() throws IOException {
        String body = bodyOf(readStructureBlockEntity(), "public static void carriedTick(");

        assertTrue(body.contains("product.flushWaitingInjection(pocket);"),
                "the injection limit holds an input that arrives faster than it and lets it through when its "
                        + "window opens; the standing tick asks for that every tick, and a carried structure - "
                        + "the one place a piston's two ticks matter most - has to ask for it too");
        int flush = body.indexOf("flushWaitingInjection(pocket)");
        int manual = body.indexOf("product.ioMode == IoMode.MANUAL");
        assertTrue(manual > flush,
                "and it stands above both \"nothing to read\" returns, for the reason the standing tick gives for "
                        + "its own placement: each of them returns early on exactly the tick a held change waits for");
    }

    @Test
    @DisplayName("a removal nobody asked for keeps the plot index, so the block can own it again")
    void anUnaskedRemovalKeepsThePlotIndex() throws IOException {
        String block = read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "block",
                "StructureBlock.java");
        String body = bodyOf(block, "private static void releasePlot(");

        int nonPlayer = body.indexOf("if (!byPlayer) {");
        int stamped = body.indexOf("noteTravelling(", nonPlayer);
        int cleared = body.indexOf("product.setPlotIndex(-1);");
        int released = body.indexOf("PocketMaterializer.release(server, plot)");
        assertTrue(nonPlayer >= 0 && stamped > nonPlayer && cleared > stamped && released > cleared,
                "only a player's break ends the claim, and the index is cleared only there. Clearing it before "
                        + "the carrier branch lets Sable's assembler read the block entity after the removal "
                        + "and carry a copy with plotIndex=-1 into the sub-level: a copy that can never reown "
                        + "can never clear the stamp, so the plot waits out the whole lease for a block that is "
                        + "standing in it");
        assertTrue(body.contains("if (!byPlayer) {") && body.indexOf("PocketSpace.awayFromHome(server, plot)") > nonPlayer,
                "and the carrier question is asked on the path where the answer changes anything - a break is not a "
                        + "pickup, whatever the record says at that instant");
    }

    // ------------------------------------------------------------- helpers --

    private static String readStructureBlockEntity() throws IOException {
        return read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "block",
                "StructureBlockEntity.java");
    }

    /** Reads a source file of this project, addressed from its own directory, e.g. {@code src/main/java/...}. */
    private static String read(String... parts) throws IOException {
        Path path = Path.of("", parts);
        if (Files.isRegularFile(path)) {
            return Files.readString(path, StandardCharsets.UTF_8);
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

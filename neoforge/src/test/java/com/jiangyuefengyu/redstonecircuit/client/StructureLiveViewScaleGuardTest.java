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
 * The two ends of "a live view follows a coarsened preview", asserted where they are written.
 *
 * <p>Neither end can be reached by a plain unit test - one needs a server level and a plot, the other a level and
 * the block state registry - and both failures are silent: a server that stops sending because the preview got
 * coarser leaves a miniature frozen at capture time, while a client that attributes the cells it is sent turns the
 * answer into a second opinion, which is how a decorated wall came to be drawn as the solid layer behind it.
 *
 * <p>So the source is read here: the client has no attribution left to do, the server does it, and both ends ask the
 * <b>same</b> rule about what a cell of the picture stands for.
 */
class StructureLiveViewScaleGuardTest {

    private static String geometry() throws IOException {
        return read("client", "StructurePreviewGeometry.java");
    }

    private static String liveViews() throws IOException {
        return read("client", "StructureLiveViews.java");
    }

    @Test
    @DisplayName("a coarsened preview is still sent its live view")
    void theServerNoLongerRefusesACoarsenedPreview() throws IOException {
        String body = bodyOf(read("block", "StructureBlockEntity.java"),
                "void sendLiveView(ServerLevel server, ServerLevel pocket, BlockPos drawnPos)");

        assertFalse(body.contains("scale() != 1"),
                "a preview drawn coarser than one cell per block is exactly the case this follows: refusing it "
                        + "leaves every placed structure bigger than 4,096 cells frozen at capture time");
        assertTrue(body.contains("if (this.size == null || this.preview == null) {"),
                "and the two cases that cannot be sent at all are still refused");
        assertTrue(body.contains("StructureLiveView.sampleIntervalScale(cells)"),
                "while the throttle that made it affordable stays exactly as it was");
    }

    @Test
    @DisplayName("the client attributes nothing: a cell of the view is a cell of the picture")
    void theClientTakesTheServersAnswer() throws IOException {
        String source = geometry();
        String state = bodyOf(source, "private static BlockState stateOf(");

        assertTrue(state.contains("StructureLiveViews.stateAt(liveAt, index)"),
                "the lookup is the view's own, by the index the payload named: a coarsened picture is read exactly like "
                        + "a full-detail one");
        assertFalse(source.contains("StructurePreviewCells.previewIndex("),
                "and nothing here converts a structure cell into a preview cell: that decision is the server's, because "
                        + "the answer is 'what does this cell of the picture look like', which one packet per changed cell "
                        + "cannot express");
        assertFalse(source.contains("liveStates"),
                "which is what the per-frame mapping table was for, and it is gone");
    }

    @Test
    @DisplayName("the server answers per picture cell, with the rule the capture samples by")
    void theServerAttributesWithTheCapturesOwnRule() throws IOException {
        String entity = read("block", "StructureBlockEntity.java");
        String send = bodyOf(entity, "void sendLiveView(ServerLevel server, ServerLevel pocket, BlockPos drawnPos)");

        assertTrue(send.contains("previewCellsOf(changed)"),
                "the changed structure cells become picture cells once each, deduplicated: several of them share one "
                        + "cell of a coarsened picture");
        assertTrue(bodyOf(entity, "private int[] previewCellsOf(").contains("StructurePreviewCells.previewIndex("),
                "attributed by the one piece of arithmetic that says which picture cell a structure cell is in");
        String representative = bodyOf(entity, "private int representativeId(");
        assertTrue(representative.contains("StructurePreviewCells.representative("),
                "and described by the same rule the capture samples by, so the picture a player sees does not change "
                        + "when a live view takes over from the capture");
        assertTrue(representative.contains("if (this.preview.scale() <= 1) {"),
                "with the full-detail case answered by the cell's own state and not re-decided");
        assertTrue(bodyOf(entity, "private int[] claimSent(").contains("StructurePreviewCells.claim("),
                "while what a packet claimed is every structure cell behind each picture cell it named - otherwise the "
                        + "same picture cell is reported changed on every pass for ever");
        assertTrue(bodyOf(read("structure", "StructurePreview.java"), "static StructurePreview build(")
                        .contains("StructurePreviewCells.representative("),
                "one rule, two callers: the capture and the live view cannot drift apart about what a cell stands for");
    }

    @Test
    @DisplayName("a move that does not change preview cell is neither slid nor hidden")
    void anInvisibleMoveIsDropped() throws IOException {
        String slides = bodyOf(geometry(), "private static Slides slides(");

        assertTrue(slides.contains("dx == 0 && dy == 0 && dz == 0"),
                "at scale 3 a piston travels one structure cell and often no preview cell at all: sliding it would "
                        + "move a block inside a cell that stands for nine of them");
        assertTrue(slides.contains("raw.getX()"),
                "and the move's own index is a structure cell, so it has to be read with the structure's size");
    }

    // ------------------------------------------------------------- helpers --

    private static String read(String... parts) throws IOException {
        Path relative = Path.of("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit");
        for (String part : parts) {
            relative = relative.resolve(part);
        }
        Path absolute = Path.of("neoforge").resolve(relative);
        for (Path candidate : List.of(relative, absolute)) {
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.UTF_8).replace("\r\n", "\n");
            }
        }
        throw new IOException("could not find " + String.join("/", parts) + " from "
                + Path.of("").toAbsolutePath());
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

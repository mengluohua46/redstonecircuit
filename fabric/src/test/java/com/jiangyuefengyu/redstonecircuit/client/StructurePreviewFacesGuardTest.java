package com.jiangyuefengyu.redstonecircuit.client;

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
 * What a miniature costs, pinned where it is decided: which faces are left out, and which places draw a smaller picture.
 *
 * <p>Both are invisible in a screenshot when they work, and a face skipped for a neighbour that is not there is a hole,
 * and an icon drawn from the wrong picture is a picture of another structure. So the shape of the rule - one direction
 * at a time, only for a plain model, never for a cell that holds another structure - is read off the source, and the
 * arithmetic beside it is tested as arithmetic.
 */
class StructurePreviewFacesGuardTest {

    private static final String GEOMETRY = "StructurePreviewGeometry.java";

    @Test
    @DisplayName("a face is skipped only for a cell drawn as a plain model, and never for a nested picture")
    void onlyAPlainModelHasFacesSkipped() throws IOException {
        String cell = bodyOf(readClient(GEOMETRY), "private static void drawCell(");
        assertTrue(cell.contains("cell.hidden() == 0 || cell.nested() != null"),
                "the fallback covers the cell with nothing hidden and the cell that is really another structure: a nested"
                        + " picture is drawn smaller than its cell and hides nothing, so skipping its neighbour's face would"
                        + " leave a hole where the gap around it shows through");
        assertTrue(cell.contains("state.getRenderShape() != RenderShape.MODEL"),
                "and anything that is not a plain model - a fluid, an animated block - goes through vanilla unchanged");
        assertTrue(cell.contains("dispatcher.renderSingleBlock(state, pose, buffers, LightTexture.FULL_BRIGHT,"),
                "which is the call the miniature made before any of this existed");
        String quads = bodyOf(readClient(GEOMETRY), "private static void putQuads(");
        assertTrue(quads.contains("putBulkData(pose.last(), quad,")
                        && quads.contains("LightTexture.FULL_BRIGHT"),
                "and the faces that are emitted are emitted the way vanilla emits a model, at the brightness every other"
                        + " cell of a miniature is drawn at");
        assertTrue(quads.contains("isTinted()"),
                "including vanilla's rule that only a tinted quad takes the block's colour");
    }

    @Test
    @DisplayName("the mask is worked out with the cells, once, and never in the draw")
    void theMaskIsPartOfTheResolvedCells() throws IOException {
        String cull = bodyOf(readClient(GEOMETRY), "private static List<Cell> cull(");
        assertTrue(cull.contains("StructurePreviewVoxels.faces(cell.x(), cell.y(), cell.z(), size, blocking)"),
                "the walk that decides which cells are buried decides which faces are hidden, from the same grid");
        assertTrue(cull.contains("RCConfig.structurePreviewFaces()"), "behind its own switch, like the cell culling");
        String inside = bodyOf(readClient(GEOMETRY), "private static void drawInsideCube(");
        assertFalse(inside.contains("blocking"),
                "and nothing in the draw asks a neighbour question: a cell-by-cell draw walks the whole picture every"
                        + " frame when the cache cannot serve it");
    }

    @Test
    @DisplayName("an icon is drawn from the smaller picture and a placed structure from the whole one")
    void theIconIsTheSmallerPicture() throws IOException {
        String item = bodyOf(readClient("StructureItemRenderer.java"), "public void renderByItem(");
        assertTrue(item.contains("effective(StructurePreviewGeometry.Detail.ICON)"),
                "the hand and every slot ask for the icon: a few dozen pixels do not need the whole picture");
        assertEquals(1, countOf(item, "effective("),
                "and they ask once: the detail is resolved in one place and the same answer is handed to the cells and "
                        + "to the draw, because two resolutions is how an icon was drawn in one box and measured in another");
        assertTrue(item.contains("cells(level, preview, detail)") && item.contains("null, null, site,"),
                "both calls take that one answer");
        String entity = bodyOf(readClient("StructureBlockEntityRenderer.java"), "public void render(");
        assertTrue(entity.contains("Detail.FULL"), "while a placed structure is the structure itself");
        String geometry = readClient(GEOMETRY);
        assertTrue(countOf(geometry, "PreviewLod.half(") >= 2,
                "the resolution change happens in one place: the size of the smaller picture and the sampling into it");
        assertTrue(geometry.contains("PreviewLod.half(ids, 0, size, half)"),
                "through the rule a capture already uses, so an icon is the same reading of the same structure");
    }

    @Test
    @DisplayName("the switch is read in one place, so the cells and the box they are drawn in cannot disagree")
    void theDetailIsResolvedOnce() throws IOException {
        String geometry = readClient(GEOMETRY);
        String cells = bodyOf(geometry, "static List<Cell> cells(Level level, StructurePreview preview, Detail detail)");
        assertFalse(cells.contains("RCConfig"),
                "the cells answer the detail they are handed: the switch is not theirs to read, and reading it here while "
                        + "the transform read the requested one is the bug this guards");
        String effective = bodyOf(geometry, "static Detail effective(");
        assertTrue(effective.contains("RCConfig.structurePreviewIconDetail()"),
                "the one place that reads the switch is the one place that answers what a place draws at");
        assertTrue(effective.contains("? Detail.ICON : Detail.FULL"), "and it answers one of the two, never a third");
    }

    @Test
    @DisplayName("the picture is measured by the cells it draws, so an icon fills the block like the block does")
    void theTransformIsMeasuredByTheDrawnCells() throws IOException {
        String geometry = readClient(GEOMETRY);
        String size = bodyOf(geometry, "static Vec3i drawnSize(");
        assertTrue(size.contains("PreviewLod.half(preview.size())"),
                "an icon's box is half the preview's: that is the size its cells are numbered in");
        assertTrue(size.contains(": preview.size()"), "and everything else is measured by the preview itself");
        String inside = bodyOf(geometry, "private static void drawInsideCube(");
        assertTrue(inside.contains("Vec3i size = drawnSize(preview, detail);"),
                "the walk measures by that, not by the preview");
        assertTrue(inside.contains("miniatureScale(size)") && inside.contains("-size.getX() / 2.0"),
                "both the scale and the recentring: measuring an icon by the preview drew it at half size in the corner"
                        + " of its block, which is what a player sees as a miniature that shrank when it was optimised");
        assertFalse(inside.contains("miniatureScale(preview.size())"),
                "and nothing in the walk may reach for the preview's own size again");
        assertTrue(inside.contains("cell.nested(), detail, dispatcher"),
                "a nested picture is drawn at the same detail, so its own box is the one it was resolved in");
    }

    @Test
    @DisplayName("both switches exist, default on, and are read through an accessor with the same fallback")
    void bothSwitchesExistAndDefaultOn() throws IOException {
        String config = read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "RCConfig.java");
        for (String option : new String[] {"structurePreviewFaces", "structurePreviewIconDetail"}) {
            assertTrue(config.contains("DEFAULTS.put(\"" + option + "\", true)"),
                    option + " is defined and on by default");
        }
        for (String accessor : new String[] {"public static boolean structurePreviewFaces()",
                "public static boolean structurePreviewIconDetail()"}) {
            String body = bodyOf(config, accessor);
            assertTrue(body.contains("bool(\"") && body.contains(", true)"),
                    accessor + " falls back to the same default, so a client whose config is not loaded yet still works");
        }
    }

    // ------------------------------------------------------------- helpers --

    private static String readClient(String name) throws IOException {
        return read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "client", name);
    }

    private static String read(String... parts) throws IOException {
        Path underFabric = Path.of("fabric").resolve(Path.of("", parts));
        Path direct = Path.of("", parts);
        for (Path candidate : List.of(direct, underFabric)) {
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.UTF_8);
            }
        }
        throw new IOException("could not find " + String.join("/", parts) + " from " + Path.of("").toAbsolutePath());
    }

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

    private static int countOf(String source, String needle) {
        return source.split(java.util.regex.Pattern.quote(needle), -1).length - 1;
    }
}

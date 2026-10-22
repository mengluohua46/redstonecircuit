package com.jiangyuefengyu.redstonecircuit.structure;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A structure inside a structure both works and can be seen - the rules that make that so.
 *
 * <p>The two halves are fixed in places no unit test can run (the pocket dimension, and client rendering), so each rule
 * is read out of the source and each one puts a symptom back if it is moved or dropped:
 *
 * <ul>
 *   <li><b>works</b>: a product block standing in a plot must be given its plot by the materialise rather than waiting
 *       for its own block's tick ({@code materializeNested}), the pocket must not hand a nested plot back before
 *       anything else, and a plot claimed a moment ago must not count as idle;</li>
 *   <li><b>can be seen</b>: a product block draws nothing by itself, so a cell that holds one is described by the
 *       picture inside it ({@code NestedPreviews}) and drawn one level down ({@code drawInsideCube}); a nested structure
 *       whose picture did not travel gets the stand-in rather than a hole.</li>
 * </ul>
 */
class NestedStructureWiringTest {

    @Test
    @DisplayName("materialising a structure materialises the structure inside it, in the same breath")
    void theNestedStructureIsMaterialisedWithIt() throws IOException {
        String materializer = read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "structure",
                "pocket", "PocketMaterializer.java");
        String publicDoor = bodyOf(materializer, "public static Result materialize(ServerLevel world");
        assertTrue(publicDoor.contains("return materializeInto(pocket, payload, shell, ownerDimension, ownerPos, 0);"),
                "the door that looks the pocket up must hand straight over to the one that takes it, so a test (or "
                        + "anything without the datapack dimension) can drive the same code");

        String into = bodyOf(materializer, "public static Result materializeInto(");
        int forced = into.indexOf("product.setPlotIndex(-1);");
        int nested = into.indexOf("materialiseNested(pocket, product, shell, depth);");
        assertTrue(nested > forced && forced > 0,
                "a copy never inherits a plot number and is given one of its own right afterwards - in that "
                        + "order, or the number it inherited would come straight back");

        String nestedBody = bodyOf(materializer, "private static void materialiseNested(");
        int cap = nestedBody.indexOf("depth >= MAX_NESTED_MATERIALIZE");
        int readFile = nestedBody.indexOf("loadOrNull(product.structureId())");
        int goDeeper = nestedBody.indexOf("materializeInto(pocket, inner, shell, pocket.dimension()");
        assertTrue(cap > 0 && cap < readFile && readFile < goDeeper,
                "and it is a depth-first walk with a ceiling: the depth is checked before the file is read, and the "
                        + "inner structure is materialised into the pocket the block stands in (not into a pocket "
                        + "looked up again, which is the call that cannot run without the dimension)");
        assertTrue(nestedBody.contains("if (inner == null) {"),
                "a missing file is left to the block's own retry, which is the only path that says why");
    }

    @Test
    @DisplayName("the pocket hands a nested plot back last, and a plot it just made is not idle")
    void aNestedPlotIsTheLastThingGivenBack() throws IOException {
        String space = read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "structure",
                "pocket", "PocketSpace.java");

        String claim = bodyOf(space, "public Plotted claim(");
        int made = claim.indexOf("this.plots.put(index, plot);");
        int touched = claim.indexOf("touch(index);");
        assertTrue(touched > made && made > 0,
                "lastUse starts at zero, so a plot claimed a moment ago counts as untouched for ever and may be "
                        + "handed back to the very next claim - which is what a materialise writing its cells looks "
                        + "like from inside");

        String handBack = bodyOf(space, "public boolean handBackOnePlot()");
        int outer = handBack.indexOf("this.pickReclaimable(false)");
        int all = handBack.indexOf("this.pickReclaimable(true)");
        assertTrue(outer > 0 && all > outer,
                "the first pass may not take a plot whose owner stands inside the pocket - that owner is a structure "
                        + "inside a structure, and taking its plot wipes the machine a player built - so the nested "
                        + "ones are only asked for when there is nothing else left");

        String pick = bodyOf(space, "private Integer pickReclaimable(boolean nestedOwnersAllowed)");
        int skip = pick.indexOf("!nestedOwnersAllowed && claim.ownerDimension().equals(this.level.dimension())");
        int firstBucket = pick.indexOf("if (claim.travellingSince() != 0) {");
        assertTrue(skip > 0 && skip < firstBucket,
                "and that is one rule in one place: the skip stands above all four buckets, so a nested plot cannot "
                        + "be picked as dead, travelling, unloaded or idle by the first pass");
        assertTrue(pick.contains("if (dead == null && !this.writing.contains(index)) {"),
                "and 'nothing answers for it' is not 'nobody is using it': a materialise claims its plot and only then "
                        + "does the block it writes learn the number, so for the whole of that call the owner looks "
                        + "like a block that has moved on - and the structure nested inside the payload asks for a plot "
                        + "inside exactly that window, which would let it be written over the plot the outer one is "
                        + "half way through. THE WINDOW IS THE WRITE AND NOT AN IDLE TIMEOUT: an owner that is genuinely "
                        + "gone is the first thing a full pocket hands back, and it must not wait a hundred ticks for a "
                        + "block that no longer exists");
        assertTrue(space.contains("private final Set<Integer> writing = new HashSet<>();"),
                "so the marker is a fact about this instant and not a timeout");
        String into = bodyOf(read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "structure",
                "pocket", "PocketMaterializer.java"), "public static Result materializeInto(");
        int begin = into.indexOf("space.beginWriting(claimed.plot().index());");
        int firstBlock = into.indexOf("pocket.setBlock(origin.offset(entry.relativePos()), state, 3);");
        int end = into.indexOf("space.endWriting(claimed.plot().index());");
        assertTrue(begin > 0 && begin < firstBlock && end > begin,
                "and the materialiser opens that window before the first block goes in and closes it after the last "
                        + "one: every plot a structure nested in the payload asks for is asked for between the two");
        assertTrue(bodyOf(space, "public Plotted claim(").contains("this.writing.remove(index);")
                        && bodyOf(space, "public boolean release(int index)").contains("this.writing.remove(index);"),
                "while both ends of a plot's life clear it, so a write that threw cannot leave a plot that can never "
                        + "be handed back: the next claim of that index starts a new, honest window");
    }

    @Test
    @DisplayName("the miniature draws the structure inside a nested cell, one level down")
    void theMiniatureDrawsANestedStructure() throws IOException {
        String geometry = read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "client",
                "StructurePreviewGeometry.java");

        String cells = bodyOf(geometry, "private static List<Cell> cells(Level level, StructurePreview preview, "
                + "@Nullable BlockPos liveAt,");
        int product = cells.indexOf("state.getBlock() == RCRegistry.STRUCTURE");
        int recurse = cells.indexOf("cells(level, inner, null, null, depth + 1, detail)");
        int standIn = cells.indexOf("cells.add(new Cell(NESTED_STAND_IN, x, y, z));");
        assertTrue(product > 0 && recurse > product && standIn > recurse,
                "a cell that holds a product block is the one case that is treated differently: the structure inside "
                        + "it is built as its own list of cells - and when that picture is not here, the stand-in goes "
                        + "in instead, because a hole is what a missing picture would leave");
        assertTrue(cells.indexOf("depth < MAX_NESTED_DEPTH") > 0,
                "with a ceiling, so a payload that describes itself cannot be drawn for ever");
        assertTrue(cells.indexOf("depth == 0 && liveAt != null") > 0,
                "and the live view belongs to the outermost miniature only: nothing in the world ticks the cells of a "
                        + "structure inside a structure, so a nested one is drawn from its own blueprint");

        String inside = bodyOf(geometry, "private static void drawInsideCube(");
        assertTrue(inside.contains("drawInsideCube(pose, buffers, cell.inside(), cell.nested(), detail, dispatcher, null"),
                "the drawing recurses through the same transform, which is what makes a nested structure fill its own "
                        + "cell instead of the whole block");
        assertTrue(bodyOf(geometry, "static void draw(PoseStack pose").contains("drawInsideCube(pose, buffers, cells"),
                "and the recentring stays one level up: it belongs to the block the miniature is drawn on");

        String palette = bodyOf(geometry, "private static BlockState[] resolvePalette(");
        assertTrue(palette.contains("NestedPreviews.stateTag(preview.palette().get(i))"),
                "the nested key is stripped before the entry is read as a block state - the state readers outside this "
                        + "class must never see it");
    }

    @Test
    @DisplayName("a payload's preview is built from the palette that describes its nested structures")
    void theOuterPreviewCarriesTheNestedOnes() throws IOException {
        String preview = read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "structure",
                "StructurePreview.java");
        String ofPayload = bodyOf(preview, "public static StructurePreview ofPayload(");
        assertTrue(ofPayload.contains("NestedPreviews.expand(payload, maxBytes)"),
                "one call, in the one place a payload becomes a picture - so both the item in the hand and the "
                        + "miniature on a placed block describe nesting the same way");

        String nested = read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "structure",
                "NestedPreviews.java");
        assertTrue(nested.contains("public static final String KEY = \"rc_nested\";"),
                "the key lives in one constant, because two ends of a packet have to agree on it");
        assertTrue(nested.contains("private static final int MAX_ENTRIES = 32;"),
                "and the number of nested pictures is capped, because a structure can hold many structures");
        assertTrue(nested.contains("preview = StructurePreview.of(preview.size(), preview.palette(), preview.cells(),")
                        && nested.contains("perEntry)"),
                "a nested picture that does not fit its share is drawn smaller rather than dropped: the player asked "
                        + "to see the structure inside, not a cell that stays empty");
    }

    // ------------------------------------------------------------- helpers --

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

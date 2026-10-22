package com.jiangyuefengyu.redstonecircuit.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The screen's own refresh: a page showing the block's data has to redraw when that data changes, and only then.
 *
 * <p>None of it can be reached by a plain unit test - it needs a client level, a controller and a tick - and both ways of
 * getting it wrong are visible to a player rather than to a test: a page that does not refresh makes an edit look lost
 * (the symptom this exists for: add a key, and the panel only changes when the tab is pressed again), and a page that
 * refreshes on every tick rebuilds its widgets twenty times a second under the player's cursor. So the source is read
 * here, and the arithmetic it leans on is tested in {@link StructurePageSignatureTest}.
 */
class StructurePanelRefreshGuardTest {

    private static final String TICK = "public void tick() {";
    private static final String SCREEN = "StructureBlockScreen.java";

    @Test
    @DisplayName("a tick that finds nothing changed rebuilds nothing")
    void aQuietTickRebuildsNothing() throws IOException {
        String tick = bodyOf(readClient(SCREEN), TICK);

        assertTrue(tick.contains("this.builtRelease != isRelease()"),
                "the mode the page was built for is still compared - it decides whether the size row exists");
        assertTrue(tick.contains("this.builtRows != pageRows()"),
                "and so is the length of the list the page shows, which is the cheap half of the comparison");
        assertTrue(tick.contains("this.builtSignature != pageSignature()"),
                "with the fingerprint of the list as the other half: only a list whose length still matches is folded");
        assertTrue(tick.indexOf("this.builtRows != pageRows()") < tick.indexOf("this.builtSignature != pageSignature()"),
                "length first, contents second - folding a list that changed length is work for nothing");
        assertEquals(1, countOf(tick, "rebuildWidgets()"),
                "one rebuild site in the tick, inside the guard: a second one would be a page rebuilt on every tick, "
                        + "which is the stutter this design exists to avoid");
        assertTrue(tick.indexOf("if (!stale) {") < tick.indexOf("rebuildWidgets()"),
                "and the rebuild is behind that guard, so an idle tick costs two comparisons and one fold and nothing "
                        + "else");
    }

    @Test
    @DisplayName("the page is rebuilt from what it was built with, which is recorded when it is built")
    void whatThePageWasBuiltFromIsRecorded() throws IOException {
        String source = readClient(SCREEN);
        String init = bodyOf(source, "protected void init() {");

        assertTrue(init.contains("this.builtRows = pageRows();"),
                "recorded at the end of a build, because that is the moment the widgets and the data agree");
        assertTrue(init.contains("this.builtSignature = pageSignature();"), "both halves, from the same build");
        assertTrue(init.indexOf("this.builtRows = pageRows();") > init.indexOf("switch (this.page) {"),
                "after the page's own widgets are built, so a build that reads the data once records what it read");
    }

    @Test
    @DisplayName("all three live pages are covered, not only the keys")
    void everyLivePageIsCovered() throws IOException {
        String source = readClient(SCREEN);
        String rows = bodyOf(source, "private int pageRows()");
        String signature = bodyOf(source, "private long pageSignature()");

        for (String page : new String[] {"PAGE_PANEL", "PAGE_FACES", "PAGE_OUTPUT"}) {
            assertTrue(rows.contains(page), page + " has a list the block can change under the screen");
            assertTrue(signature.contains(page), "and a fingerprint that notices it: " + page + " is folded row by row");
        }
        assertTrue(rows.contains("case PAGE_PANEL -> panelEntries().size()"),
                "the keys are read straight from the entity, as the page above already draws them");
        assertTrue(signature.contains("ActivationTable.entries(found.faces(), direction)"),
                "the face table is folded in the order the page draws it: face by face, as one flat list");
        assertTrue(signature.contains("for (ActivationTable.OutputEntry entry : found.output())"),
                "and the output table in its own list order, which is the order its rows are indexed by");
    }

    @Test
    @DisplayName("the selection, the grid page and the scroll are fitted to the list before it is redrawn")
    void staleIndicesAreClampedBeforeTheRebuild() throws IOException {
        String tick = bodyOf(readClient(SCREEN), TICK);
        String clamp = bodyOf(readClient(SCREEN), "private void clampToData()");

        assertTrue(tick.contains("clampToData();"), "the tick clamps");
        assertTrue(tick.indexOf("clampToData();") < tick.indexOf("rebuildWidgets()"),
                "before the rebuild, because the rebuild is what reads them");
        assertTrue(clamp.contains("if (this.selected >= entries) {") && clamp.contains("this.selected = -1;"),
                "a selection the list no longer has is no selection: the row below would otherwise edit nothing while "
                        + "its button still offers to update it");
        assertTrue(clamp.contains("PanelGrid.pages(entries) - 1"),
                "the grid page is fitted to the pages the panel actually has");
        assertTrue(clamp.contains("Math.max(0, longest - ROWS)"),
                "and the scroll to the rows the longest of the three lists has: a scroll past the end draws an empty "
                        + "table above a list that is not empty");
    }

    @Test
    @DisplayName("a rebuild leaves the row the player is typing alone")
    void theTypedRowSurvivesTheRefresh() throws IOException {
        String source = readClient(SCREEN);

        assertTrue(bodyOf(source, "private void fillFromSelection()").contains("if (this.cellEdited) {"),
                "the row's name, cell and strength are refilled from the selection, so a row the player has typed in is "
                        + "not refilled at all - the mechanism the screen already had, extended to the boxes a refresh "
                        + "now arrives on top of");
        assertTrue(bodyOf(source, "private void fillCellFields()").contains("this.cellEdited"),
                "the cell row keeps its own guard, unchanged");
        assertTrue(bodyOf(source, "private EditBox rowBox(").contains("this.cellEdited = true"),
                "and the name and strength boxes are marked as typed-in the way the cell boxes are");
        assertEquals(1, countOf(source, "this.buttonName = rowBox("),
                "the key panel's name box is a row box");
        assertEquals(3, countOf(source, "this.strengthBox = rowBox("),
                "as is the strength box on all three pages that have one");
        assertTrue(bodyOf(source, "private void select(int index)").contains("this.cellEdited = false;"),
                "while picking another row starts the row afresh: the text belonged to the row it was typed for");
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
                return Files.readString(candidate, StandardCharsets.UTF_8).replace("\r\n", "\n");
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

    private static int countOf(String source, String needle) {
        return source.split(java.util.regex.Pattern.quote(needle), -1).length - 1;
    }
}

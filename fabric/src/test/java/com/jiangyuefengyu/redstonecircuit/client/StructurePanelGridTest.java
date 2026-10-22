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
 * The key panel is a 6×6 grid of named buttons, and an empty panel is an empty grid.
 *
 * <h2>What this pins</h2>
 * A structure's keys are a row of buttons on the placed block, so the editor draws them as one: one button per entry,
 * named with the name the player typed, six across and six down, and thirty-six on a page. Two things follow from that
 * and are pinned here because either alone leaves the panel half-old:
 * <ul>
 *   <li>an <b>empty</b> panel draws <b>no</b> buttons at all - a lattice of thirty-six greyed cells would be thirty-six
 *       buttons that mean nothing;</li>
 *   <li>a grid button <b>selects</b> its entry instead of firing it. The editor is not where a press happens (the press
 *       travels to the placed structure, where the key is), so the old row of "按" buttons could only ever answer with a
 *       sentence; the button now has to mean "this is the one the row below edits".</li>
 * </ul>
 *
 * <p>Both screens are checked: the editor and the placed structure's own screen, because a player who arranged a panel
 * here and finds a different arrangement there would be looking at two panels.
 */
class StructurePanelGridTest {

    @Test
    @DisplayName("the editor draws one button per entry, and every page holds 6x6 of them")
    void theEditorIsAGrid() throws IOException {
        String screen = read("StructureBlockScreen.java");

        assertTrue(screen.contains("PanelGrid.COLUMNS") && screen.contains("PanelGrid.firstIndex(page)")
                        && screen.contains("PanelGrid.count(manual.size(), page)"),
                "the grid is the panel's own arithmetic, not ROWS: a page is 6x6 and the class that says so is one "
                        + "class, shared with the placed structure's screen");
        assertTrue(screen.contains("for (int cell = 0; cell < count; cell++)"),
                "one button per entry the page holds - which is why an empty panel draws an empty grid");
        assertFalse(screen.contains("ly(\"panel.rows\")"),
                "and not one row per entry: the list of rows this page used to draw is what the grid replaces");
        assertFalse(screen.contains("\"panel.press\""),
                "a press cannot happen here - the editor has no product block to press, so a button that only "
                        + "explains that is a button that does nothing");
    }

    @Test
    @DisplayName("a grid button selects its entry, and the row below it updates or removes that one")
    void aCellIsSelected() throws IOException {
        String screen = read("StructureBlockScreen.java");

        assertTrue(screen.contains("pressed -> select(index)"),
                "clicking a cell has to say which entry the boxes are about");
        assertTrue(screen.contains("private ActivationTable.ManualEntry selectedEntry()"),
                "and one place answers that question, so the render, the labels and the two verbs cannot disagree");
        assertTrue(screen.contains("text(selectedEntry() == null ? \"panel.add\" : \"panel.update\")"),
                "the one button under the grid says which of its two verbs it would do");
        assertTrue(screen.contains("RCNetwork.StructureEditPayload.manualReplace(this.controller, this.selected"),
                "writing it back is a replace <b>by index</b>: a rename is a different (key, cell), so a remove and an "
                        + "add would lose the entry between the two edits");
        assertTrue(screen.contains("private void fillFromSelection()"),
                "and selecting fills the boxes from the entry, so the row shows what is being edited");
    }

    @Test
    @DisplayName("the placed structure's screen is the same grid, and a page turns there too")
    void theProductIsTheSameGrid() throws IOException {
        String product = read("StructureProductScreen.java");

        assertTrue(product.contains("PanelGrid.COLUMNS") && product.contains("PanelGrid.count(count, page)"),
                "the same arithmetic as the editor: one page is 6x6 wherever it is drawn");
        assertTrue(product.contains("pressed -> pressEntry(index)"),
                "and here a button <b>does</b> fire: this is the block a press arrives at");
        assertTrue(product.contains("private void turn(int delta)"),
                "a panel of forty is two pages, here as in the editor");
    }

    /** One of the mod's own files, addressed from this project's directory. */
    private static String read(String... parts) throws IOException {
        Path relative = Path.of("", parts);
        if (Files.isRegularFile(relative)) {
            return Files.readString(relative, StandardCharsets.UTF_8);
        }
        Path source = Path.of("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "client");
        Path beside = source.resolve(Path.of(String.join("/", parts)));
        if (Files.isRegularFile(beside)) {
            return Files.readString(beside, StandardCharsets.UTF_8);
        }
        throw new IOException("could not find " + String.join("/", parts) + " from "
                + Path.of("").toAbsolutePath() + "; this guard reads the file on purpose");
    }
}

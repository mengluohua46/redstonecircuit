package com.jiangyuefengyu.redstonecircuit.structure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;

/**
 * 一个格子, 一个坐标系: the cell a player types and the cell a table stores have to be the same cell.
 *
 * <p>They used to be counted from two different origins - the screen's cell row from the controller, the tables from
 * the selection's first cell - and the difference between the two is {@code controller - region.min()}. A selection
 * that starts one cell east of its controller therefore stored what was typed minus one and read it back one short, so
 * a filled row appeared to move by itself; a capture made it visible only because a capture is what takes the border -
 * and with it the world position - away.
 *
 * <p>Each convention had a test of its own - {@link RegionEditorTest} pinned the reading the row was typed in, the game
 * tests pinned the stored frame - and nothing joined the two, which is how they stayed apart. These tests are that
 * join, on the geometry of the session that failed: the controller at {@code 10,-60,10}, the selection starting at
 * {@code 11,-60,10}.
 */
class CellFrameTest {

    /** The controller of the failing session: one cell west of the selection's first cell. */
    private static final BlockPos CONTROLLER = new BlockPos(10, -60, 10);

    /** The selection the border was drawing: 3x4x3, from {@code 11,-60,10} to {@code 13,-57,12}. */
    private static final PreviewRegion SELECTION =
            PreviewRegion.of(new BlockPos(11, -60, 10), new BlockPos(13, -57, 12));

    @Test
    @DisplayName("typed 2,1,1 -> world -> subtract(min) is 2,1,1: the row and the table are one frame")
    void aTypedCellRoundTripsThroughTheStoredFrame() {
        Vec3i typed = new Vec3i(2, 1, 1);

        BlockPos world = RegionEditor.cellIn(SELECTION, typed);
        assertEquals(new BlockPos(13, -59, 11), world,
                "the world position the server checks a row against: the selection's first cell plus the typed cell");

        // The server's own line, six times over in StructureEdits: a world position becomes a stored cell by
        // subtracting the region's first cell. A screen and a table are a round trip through it, so it has to be the
        // identity - and it is the identity only if the row is typed in the frame the table is written in.
        BlockPos stored = world.subtract(SELECTION.min());
        assertEquals(new BlockPos(2, 1, 1), stored,
                "the cell stored is the cell typed - this is the assertion that was 1,1,1 while the row counted from "
                        + "the controller");

        // The same origin read the other way round: 0,0,0 is the selection's first cell, which is the row a fresh page
        // fills in (StructureBlockScreen#fillCellFields).
        assertEquals(SELECTION.min(), RegionEditor.cellIn(SELECTION, Vec3i.ZERO),
                "and 0,0,0 names the selection's first cell");
    }

    @Test
    @DisplayName("a controller west of the selection never turns 2,1,1 into 1,1,1")
    void theControllerIsNotTheOrigin() {
        Vec3i typed = new Vec3i(2, 1, 1);
        assertNotEquals(SELECTION.min(), CONTROLLER, "the geometry this pins: the controller is not the first cell");

        BlockPos stored = RegionEditor.cellIn(SELECTION, typed).subtract(SELECTION.min());
        assertEquals(new BlockPos(2, 1, 1), stored, "what the player typed, and what the table has to hold");
        assertNotEquals(new BlockPos(1, 1, 1), stored,
                "one cell short on every axis the two origins disagree - the cell that looked like it had moved by "
                        + "itself once the border was gone");

        // The frame that produced it, spelled out: counting from the controller shifts a cell by controller - min, and
        // a selection is free to put that shift anywhere, including nowhere at all - which is why the bug survived.
        assertEquals(new BlockPos(1, 1, 1), CONTROLLER.offset(typed).subtract(SELECTION.min()),
                "the controller frame is off by exactly the difference of the two origins");
    }

    @Test
    @DisplayName("the cell row is read from the selection's first cell, in the source of both loaders")
    void theScreenCountsCellsFromTheSelection() throws IOException {
        for (Path screen : screens()) {
            String source = read(screen);

            String typedCell = bodyOf(source, "private BlockPos typedCell()", screen);
            assertFalse(typedCell.contains("RegionEditor.cell(this.controller"), why(screen,
                    "this is the line that shifted every typed cell by controller - min: a row that said 2,1,1 was "
                            + "stored as 1,1,1"));
            assertTrue(typedCell.contains("RegionEditor.cellIn("), why(screen,
                    "the world position a row names comes from the selection's first cell"));

            String cellAsTyped = bodyOf(source, "private String cellAsTyped(Vec3i local)", screen);
            assertTrue(cellAsTyped.contains("local.toShortString()"), why(screen,
                    "a stored cell is shown as it is stored: no border needed, so a capture cannot change the row"));
            assertTrue(cellAsTyped.contains("shown.min().offset(local)"), why(screen,
                    "while the absolute reading still places the cell in the world through the border"));

            assertFalse(source.contains("- this.controller.getX()"), why(screen,
                    "the controller compensation is gone rather than moved: every relative branch that names a cell - "
                            + "the input row, the field fill, the selection fill, the row text - reads the selection's "
                            + "first cell now"));
        }
    }

    // ------------------------------------------------------------- helpers --

    /**
     * Every copy of the screen this build can reach.
     *
     * <p>The two loaders keep a verbatim mirror of it, and the working directory differs between a module's own test
     * task and a run from the repository root, so every spelling that exists is checked rather than the one that was
     * in mind when this was written.
     */
    private static List<Path> screens() {
        Path relative = Path.of("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "client",
                "StructureBlockScreen.java");
        List<Path> found = new ArrayList<>();
        for (Path root : List.of(Path.of(""), Path.of("fabric"), Path.of("neoforge"),
                Path.of("..").resolve("fabric"), Path.of("..").resolve("neoforge"))) {
            Path candidate = root.resolve(relative).normalize().toAbsolutePath();
            if (Files.isRegularFile(candidate) && !found.contains(candidate)) {
                found.add(candidate);
            }
        }
        assertFalse(found.isEmpty(), "no StructureBlockScreen.java found from " + Path.of("").toAbsolutePath()
                + "; this guard reads the source on purpose");
        return found;
    }

    private static String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private static String why(Path origin, String reason) {
        return origin + ": " + reason;
    }

    /** The body of the first member whose declaration contains {@code signature}, by brace counting. */
    private static String bodyOf(String source, String signature, Path origin) {
        int at = source.indexOf(signature);
        assertTrue(at >= 0, origin + " no longer contains '" + signature + "'; the guard is stale");
        int open = source.indexOf('{', at);
        assertTrue(open > 0, origin + " has no body for '" + signature + "'");
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
        throw new IllegalStateException(origin + ": unbalanced braces after '" + signature + "'");
    }
}

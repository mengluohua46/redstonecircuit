package com.jiangyuefengyu.redstonecircuit.structure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.function.IntPredicate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;

/**
 * What one cell of a coarsened preview stands for - the rule that decides whether a decorated wall is drawn as itself or
 * as the solid layer behind it.
 *
 * <p>A miniature is a picture of a structure's <b>surface</b>. The majority vote a preview used to be built by is not:
 * a one-cell-thick decorated wall has four cells to the eight of a {@code scale}³ group, so the second, solid layer
 * behind it wins the group and the player is shown the back of the wall. These tests pin the rule that replaced it -
 * exposed cells first, the whole group only when nothing in it is exposed - on the shape an author's real build has, and
 * on the two ends that have to agree about it: the capture ({@code StructurePreview#build}) and the live view
 * ({@code StructurePreviewCells#representative} as the server calls it).
 */
class StructurePreviewSamplingTest {

    /** The box the author reported on: 32³, a one-cell-thick wall, a solid layer behind it, air inside. */
    private static final int SIDE = 32;

    /** Palette index / state id of nothing. */
    private static final int AIR = 0;

    /** The decorated wall: the block a player is meant to see. */
    private static final int WALL = 1;

    /** The solid layer behind it, which a player is not meant to see at all. */
    private static final int BACK = 2;

    private static final IntPredicate EMPTY = value -> value == AIR;

    @Test
    @DisplayName("a coarsened cell shows the exposed layer, not the thicker layer behind it")
    void theExposedLayerWinsTheGroup() {
        Vec3i size = new Vec3i(SIDE, SIDE, SIDE);
        int[] cells = decoratedShell(size);
        StructurePreviewCells.RawCells raw = (x, y, z) -> cells[StructureLiveView.index(x, y, z, size)];

        // The -X face: group (0, py, pz) covers structure cells x=0 and x=1 for two cells of y and z each. The wall is
        // the x=0 half of it, one cell in four of which is a hole; the x=1 half is the solid layer behind the wall.
        for (int py = 0; py < SIDE / 2; py++) {
            for (int pz = 0; pz < SIDE / 2; pz++) {
                assertEquals(WALL, StructurePreviewCells.representative(size, raw, EMPTY, AIR, 0, py, pz, 2),
                        "the group at (0," + py + "," + pz + ") is a piece of the wall, so the picture shows the wall: "
                                + "the solid layer behind it has more cells in the group and used to win this vote");
            }
        }
    }

    @Test
    @DisplayName("a group with nothing exposed is still its own majority, and an empty group is empty")
    void anInteriorGroupIsItsMajority() {
        Vec3i size = new Vec3i(8, 8, 8);
        int[] cells = new int[8 * 8 * 8];
        // A solid block of BACK with a single WALL cell buried in the middle of the group at preview (2,2,2), which
        // covers x,y,z = 4..5: nothing of it can be seen, so nothing of it is exposed.
        for (int i = 0; i < cells.length; i++) {
            cells[i] = BACK;
        }
        cells[StructureLiveView.index(5, 5, 5, size)] = WALL;
        StructurePreviewCells.RawCells raw = (x, y, z) -> cells[StructureLiveView.index(x, y, z, size)];
        assertEquals(BACK, StructurePreviewCells.representative(size, raw, EMPTY, AIR, 2, 2, 2, 2),
                "a buried group has no surface to show, so the majority is the honest answer there");

        int[] empty = new int[8 * 8 * 8];
        StructurePreviewCells.RawCells nothing = (x, y, z) -> empty[StructureLiveView.index(x, y, z, size)];
        assertEquals(AIR, StructurePreviewCells.representative(size, nothing, EMPTY, AIR, 2, 2, 2, 2),
                "and a group that holds nothing is the empty value, whatever the empty value is");
    }

    @Test
    @DisplayName("a 32³ decorated box is drawn at scale 2 as its wall on all six faces")
    void theAuthorsBoxKeepsItsWall() {
        Vec3i size = new Vec3i(SIDE, SIDE, SIDE);
        int[] cells = decoratedShell(size);
        List<CompoundTag> palette = List.of(named("minecraft:air"), named("minecraft:terracotta"),
                named("minecraft:dark_oak_planks"));
        StructurePreview preview = StructurePreview.of(size, palette, IndexArray.of(cells, palette.size()),
                Integer.MAX_VALUE, 4096);
        assertEquals(2, preview.scale(), "the default cell budget of 4,096 cells is met by two cells per block");
        assertEquals(SIDE / 2, preview.size().getX(), "so the picture is 16³ cells");

        int wall = 0;
        int planks = 0;
        int air = 0;
        for (int cell = 0; cell < preview.cellCount(); cell++) {
            int entry = preview.cells().get(cell);
            String name = preview.palette().get(entry).getString("Name");
            if (name.endsWith("terracotta")) {
                wall++;
            } else if (name.endsWith("planks")) {
                planks++;
            } else {
                air++;
            }
        }
        assertEquals(16 * 16 * 16 - 14 * 14 * 14, wall,
                "every cell of the picture's surface is the wall: " + wall + " of " + (16 * 16 * 16) + " cell(s)");
        assertEquals(0, planks,
                "and not one cell of it is the layer behind the wall, which is what the picture used to be made of");
        assertEquals(14 * 14 * 14, air, "while the cavity inside it is empty, as it is in the structure");

        // The six faces of the coarse picture, each 16×16 cells, as the number of cells that hold the wall. Written out
        // as well as asserted: this is the acceptance number for the picture an author reported as "two sides missing",
        // and a run that prints it is the answer to "what does it look like now".
        System.out.println("[sampling] 32³ decorated box at scale 2: " + preview.filledCells() + " filled cell(s), wall="
                + wall + " back=" + planks + " air=" + air);
        for (int axis = 0; axis < 3; axis++) {
            for (int side = 0; side < 2; side++) {
                int coordinate = side == 0 ? 0 : SIDE / 2 - 1;
                int onTheWall = 0;
                for (int a = 0; a < SIDE / 2; a++) {
                    for (int b = 0; b < SIDE / 2; b++) {
                        int x = axis == 0 ? coordinate : a;
                        int y = axis == 1 ? coordinate : (axis == 0 ? a : b);
                        int z = axis == 2 ? coordinate : b;
                        int entry = preview.cells().get(preview.index(x, y, z));
                        if (preview.palette().get(entry).getString("Name").endsWith("terracotta")) {
                            onTheWall++;
                        }
                    }
                }
                System.out.println("[sampling] face axis " + axis + (side == 0 ? "-" : "+") + ": " + onTheWall
                        + "/256 cell(s) are the wall");
                assertEquals(16 * 16, onTheWall,
                        "face axis " + axis + (side == 0 ? "-" : "+") + " is " + onTheWall + " of 256 cell(s) of the "
                                + "wall: every one of them has to be the wall, because that is the face the player sees");
            }
        }
    }

    @Test
    @DisplayName("the server's answer for a changed cell is the preview cell, by the capture's own rule")
    void theServerAttributesAChangeToThePreviewCellItStandsFor() {
        Vec3i size = new Vec3i(SIDE, SIDE, SIDE);
        Vec3i previewSize = new Vec3i(SIDE / 2, SIDE / 2, SIDE / 2);
        int[] cells = decoratedShell(size);
        StructurePreviewCells.RawCells raw = (x, y, z) -> cells[StructureLiveView.index(x, y, z, size)];

        // Two structure cells of one group - the wall cell at the corner and the second-layer cell behind it - are one
        // cell of the picture.
        int wallCell = StructureLiveView.index(0, 0, 0, size);
        int behind = StructureLiveView.index(1, 0, 0, size);
        int preview = StructurePreviewCells.previewIndex(wallCell, size, 2, previewSize);
        assertEquals(preview, StructurePreviewCells.previewIndex(behind, size, 2, previewSize),
                "both are drawn in the same cell of the preview, so a change to either speaks about that one cell");
        assertEquals(0, preview, "which is the picture's own corner");

        // And what is sent for it is what the capture would have put there: one rule, two callers.
        assertEquals(WALL, StructurePreviewCells.representative(size, raw, EMPTY, AIR, 0, 0, 0, 2),
                "the wall, because it has a face pointing out of the structure - and the capture puts the same value in "
                        + "the same cell");

        // What a packet naming that cell claims for the next diff: every structure cell behind it, and no others.
        int[] now = cells.clone();
        int[] remembered = new int[cells.length];
        java.util.Arrays.fill(remembered, -1);
        StructurePreviewCells.claim(now, remembered, new int[] {preview}, size, previewSize, 2);
        int claimed = 0;
        for (int at = 0; at < remembered.length; at++) {
            if (remembered[at] != -1) {
                claimed++;
            }
        }
        assertEquals(8, claimed, "the group is 2³ = 8 structure cells, and all of them were sent by that one cell");
        for (int y = 0; y <= 1; y++) {
            for (int z = 0; z <= 1; z++) {
                for (int x = 0; x <= 1; x++) {
                    int at = StructureLiveView.index(x, y, z, size);
                    assertEquals(now[at], remembered[at],
                            "cell " + x + "," + y + "," + z + " is inside the group and must be remembered as sent");
                }
            }
        }
    }

    /**
     * A 32³ box: a one-cell-thick decorated wall, one cell in four of it a hole, a solid layer immediately behind the
     * wall on every face, and air inside - the shape the author's structure has.
     */
    private static int[] decoratedShell(Vec3i size) {
        int[] cells = new int[size.getX() * size.getY() * size.getZ()];
        for (int y = 0; y < size.getY(); y++) {
            for (int z = 0; z < size.getZ(); z++) {
                for (int x = 0; x < size.getX(); x++) {
                    boolean far = x == size.getX() - 1 || y == size.getY() - 1 || z == size.getZ() - 1;
                    boolean first = x == 0 || y == 0 || z == 0;
                    boolean second = x == 1 || y == 1 || z == 1 || x == size.getX() - 2
                            || y == size.getY() - 2 || z == size.getZ() - 2;
                    int value = AIR;
                    if (first || far) {
                        // One hole in every four cells of the wall, always in the same place in a 2×2 block of it.
                        value = (x + y + z) % 4 == 0 ? AIR : WALL;
                    } else if (second) {
                        value = BACK;
                    }
                    cells[StructureLiveView.index(x, y, z, size)] = value;
                }
            }
        }
        return cells;
    }

    private static CompoundTag named(String name) {
        CompoundTag tag = new CompoundTag();
        tag.putString("Name", name);
        return tag;
    }
}

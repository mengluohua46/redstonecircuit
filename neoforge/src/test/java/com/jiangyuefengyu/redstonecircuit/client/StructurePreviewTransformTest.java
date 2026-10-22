package com.jiangyuefengyu.redstonecircuit.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.jiangyuefengyu.redstonecircuit.structure.IndexArray;
import com.jiangyuefengyu.redstonecircuit.structure.StructurePreview;

import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;

/**
 * Whether a miniature can leave the block it is drawn in, and whether a hollow box's walls all reach the picture.
 *
 * <p>Both are ways for surfaces to go missing without anything being culled, and neither needs a renderer to answer: the
 * transform the miniature is drawn through is {@link StructurePreviewVoxels#miniatureScale} and a handful of
 * translations, and where its own corners land is arithmetic. A corner outside {@code [0,1]³} is a wall inside the
 * neighbouring block's geometry, which draws over it - a picture that looks like it has lost two of its sides.
 *
 * <p>The pictures are built by the same {@code StructurePreview.of} a capture goes through. {@code 32³ at scale 2} is
 * the case the author's log names (a 16³ picture of 1,405 cells), so it is the case pinned here: a 32³ box with a
 * one-block shell has to arrive as a 16³ picture whose every wall is present, at one cell per {@code [0,1]³} corner and
 * inside the block.
 */
class StructurePreviewTransformTest {

    @Test
    @DisplayName("no corner of the miniature leaves the block's own cube")
    void thePictureStaysInsideItsBlock() {
        for (int side : new int[] {1, 8, 16, 48}) {
            Vec3i size = new Vec3i(side, side, side);
            for (int x = 0; x <= side; x++) {
                for (int y = 0; y <= side; y++) {
                    for (int z = 0; z <= side; z++) {
                        float[] corner = StructurePreviewVoxels.miniatureCorner(size, x, y, z);
                        assertTrue(corner[0] >= 0.0F && corner[0] <= 1.0F,
                                "a " + side + "-cell picture puts corner " + x + "," + y + "," + z + " at x=" + corner[0]
                                        + ": outside the block a wall is inside the next block's geometry, which is a "
                                        + "wall drawn over");
                        assertTrue(corner[1] >= 0.0F && corner[1] <= 1.0F,
                                "and at y=" + corner[1] + " (the picture stands on the block's floor, so 0 is legal)");
                        assertTrue(corner[2] >= 0.0F && corner[2] <= 1.0F, "and at z=" + corner[2]);
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("the picture is centred across the block and stands on its floor")
    void thePictureIsCentredAndStandsOnTheFloor() {
        Vec3i size = new Vec3i(16, 16, 16);
        float[] low = StructurePreviewVoxels.miniatureCorner(size, 0, 0, 0);
        float[] high = StructurePreviewVoxels.miniatureCorner(size, 16, 16, 16);
        assertEquals(0.0F, low[1], 1.0e-6F, "the bottom of the picture is the block's floor");
        assertEquals(1.0F - low[0], high[0], 1.0e-6F,
                "and the two horizontal margins are equal, which is what 'centred' means: an off-centre picture leans "
                        + "and its near wall ends up under the block's own faces");
        assertEquals(1.0F - low[2], high[2], 1.0e-6F, "on both axes");
        assertTrue(high[1] < 1.0F, "while the top stops short of the block's lid: " + high[1]);
        assertEquals(1.0F / 16.5F, StructurePreviewVoxels.miniatureScale(size), 1.0e-6F,
                "half a cell of margin on the longest axis, which is the scale every cell of the picture is drawn at");
    }

    @Test
    @DisplayName("a 32³ box at scale 2 arrives as a 16³ picture with all six walls")
    void everyWallOfTheAuthorsBoxIsInThePicture() {
        StructurePreview preview = hollow(32, 4096);
        assertEquals(2, preview.scale(), "the default cell budget of 4,096 cells is met by sampling two cells per block");
        assertEquals(16, preview.size().getX(), "so the picture is 16³ cells");
        assertEquals(16 * 16 * 16 - 14 * 14 * 14, preview.filledCells(),
                "a 32³ box with a one-block shell is a 16³ box with a one-block shell: 1,352 cells, which is the "
                        + "author's picture");

        boolean[] wall = wallsOf(preview.size());
        boolean[] filled = filledOf(preview);
        for (int cell = 0; cell < filled.length; cell++) {
            if (wall[cell]) {
                assertTrue(filled[cell], "cell " + cell + " is on the picture's own surface, so the capture has it - if "
                        + "this fails the wall never existed in the picture and no drawing fix could help");
            } else {
                assertFalse(filled[cell], "and cell " + cell + " is the cavity, which is air in the capture");
            }
        }

        boolean[] kept = StructurePreviewVoxels.kept(preview.size(), filled, true);
        for (int cell = 0; cell < kept.length; cell++) {
            if (filled[cell]) {
                assertTrue(kept[cell], "no wall cell is dropped: every one of them has a face pointing out of the box");
            }
        }
    }

    @Test
    @DisplayName("coarsening samples the last row of a size that does not divide by the scale")
    void theLastRowIsSampled() {
        Vec3i size = new Vec3i(5, 5, 5);
        int[] cells = new int[125];
        // One block in the far corner of every axis: the cell a sampled row would drop if it stopped one short.
        cells[(4) + 5 * (4 + 5 * 4)] = 1;
        List<CompoundTag> palette = List.of(named("minecraft:air"), named("minecraft:stone"));
        StructurePreview preview = StructurePreview.of(size, palette, IndexArray.of(cells, palette.size()),
                Integer.MAX_VALUE, 27);
        assertEquals(2, preview.scale(), "a budget of 27 cells needs two cells per block");
        assertEquals(3, preview.size().getX(), "which makes a 3-wide picture of a 5-wide structure: the last row is a "
                + "half row, and it is the one a sampling loop that assumes a multiple of the scale drops");
        assertEquals(1, preview.filledCells(), "the block in the far corner is in the picture");

        boolean[] filled = filledOf(preview);
        int at = preview.index(2, 2, 2);
        assertTrue(filled[at], "and it is in the picture's own far corner, not smeared elsewhere");
    }

    // ------------------------------------------------------------- helpers --

    /** The preview of a box with a one-block shell, cut to {@code maxCells} cells. */
    private static StructurePreview hollow(int side, int maxCells) {
        Vec3i size = new Vec3i(side, side, side);
        int[] cells = new int[side * side * side];
        for (int y = 0; y < side; y++) {
            for (int z = 0; z < side; z++) {
                for (int x = 0; x < side; x++) {
                    boolean wall = x == 0 || y == 0 || z == 0 || x == side - 1 || y == side - 1 || z == side - 1;
                    cells[x + side * (z + side * y)] = wall ? 1 : 0;
                }
            }
        }
        List<CompoundTag> palette = List.of(named("minecraft:air"), named("minecraft:stone"));
        return StructurePreview.of(size, palette, IndexArray.of(cells, palette.size()), Integer.MAX_VALUE, maxCells);
    }

    /** Whether each cell of a picture's own grid is on its outer surface. */
    private static boolean[] wallsOf(Vec3i size) {
        boolean[] wall = new boolean[size.getX() * size.getY() * size.getZ()];
        for (int y = 0; y < size.getY(); y++) {
            for (int z = 0; z < size.getZ(); z++) {
                for (int x = 0; x < size.getX(); x++) {
                    wall[x + size.getX() * (z + size.getZ() * y)] = x == 0 || y == 0 || z == 0
                            || x == size.getX() - 1 || y == size.getY() - 1 || z == size.getZ() - 1;
                }
            }
        }
        return wall;
    }

    /** Whether each cell of a picture holds a block, which is what the culling rule and the drawing both read. */
    private static boolean[] filledOf(StructurePreview preview) {
        Vec3i size = preview.size();
        boolean[] filled = new boolean[size.getX() * size.getY() * size.getZ()];
        for (int cell = 0; cell < filled.length; cell++) {
            int entry = preview.cells().get(cell);
            filled[cell] = entry >= 0 && entry < preview.palette().size()
                    && !StructurePreview.isAir(preview.palette().get(entry));
        }
        return filled;
    }

    private static CompoundTag named(String name) {
        CompoundTag tag = new CompoundTag();
        tag.putString("Name", name);
        return tag;
    }
}

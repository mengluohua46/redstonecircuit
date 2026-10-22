package com.jiangyuefengyu.redstonecircuit.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.jiangyuefengyu.redstonecircuit.structure.IndexArray;
import com.jiangyuefengyu.redstonecircuit.structure.StructurePreview;
import com.jiangyuefengyu.redstonecircuit.structure.StructurePreviewCells;

import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;

/**
 * Why a miniature can look like it is missing its surface, pinned on the two rules that decide what is drawn.
 *
 * <p>Two things can take a block out of the picture. The <b>culling rule</b> drops a cell whose six neighbours all
 * block - and a cell dropped from the surface is a hole, which is the complaint - and the <b>live view</b> can put air
 * where the capture has a block, which at a coarsened scale erases a cell that nine others were standing in for.
 *
 * <p>The pictures here are built by the same {@code StructurePreview.of} a capture goes through, from a real palette
 * and a real cell array, so the arithmetic under test is the arithmetic the game runs: an 8³ box with a one-block
 * shell, a solid 8³ cube as the control that culling is supposed to bite on, and the same box at a scale the cell
 * budget would pick. The one thing standing in for the game is the "does this hide what is behind it" answer, which
 * the capture gives as a plain stone-or-air fact - exactly what {@code canOcclude} plus {@code isSolidRender} say for
 * these two blocks.
 */
class StructurePreviewSurfaceTest {

    /** The box's side, in structure cells: 296 cells of shell around a 6³ cavity. */
    private static final int SIDE = 8;

    /** The volume at one cell per block, which is also the preview's cell budget for that scale. */
    private static final int CELLS = SIDE * SIDE * SIDE;

    /** How many cells the shell of the box has: everything but the cavity. */
    private static final int SHELL_CELLS = CELLS - (SIDE - 2) * (SIDE - 2) * (SIDE - 2);

    @Test
    @DisplayName("every cell of a hollow box's shell survives culling")
    void theShellOfAHollowBoxIsKept() {
        StructurePreview preview = box(true, CELLS);
        assertEquals(1, preview.scale(), "the budget asks for one cell per block, so the picture is the box itself");

        Vec3i size = preview.size();
        boolean[] shell = shellMask(size);
        boolean[] blocking = blockingOf(preview);
        boolean[] kept = StructurePreviewVoxels.kept(size, blocking, true);

        int onTheShell = 0;
        for (int y = 0; y < size.getY(); y++) {
            for (int z = 0; z < size.getZ(); z++) {
                for (int x = 0; x < size.getX(); x++) {
                    int cell = StructurePreviewVoxels.index(x, y, z, size);
                    String at = x + "," + y + "," + z;
                    if (shell[cell]) {
                        onTheShell++;
                        assertTrue(blocking[cell], "the capture holds a block at " + at);
                        assertTrue(kept[cell], "the cell at " + at + " is on the outside of the box, so it has a face "
                                + "pointing at the player: dropping it is a hole in the picture");
                    } else {
                        assertFalse(blocking[cell], "the cavity at " + at + " is air, so it is not a cell the miniature "
                                + "has to draw in the first place - and culling is not what removed it");
                    }
                }
            }
        }
        assertEquals(SHELL_CELLS, onTheShell, "the box is one block thick, so this is its whole shell");
    }

    @Test
    @DisplayName("culling drops the inside of a solid cube and nothing that can be seen")
    void aSolidCubeLosesOnlyItsInterior() {
        StructurePreview preview = box(false, CELLS);
        Vec3i size = preview.size();
        boolean[] kept = StructurePreviewVoxels.kept(size, blockingOf(preview), true);

        int dropped = 0;
        for (int y = 0; y < size.getY(); y++) {
            for (int z = 0; z < size.getZ(); z++) {
                for (int x = 0; x < size.getX(); x++) {
                    boolean inside = x > 0 && y > 0 && z > 0
                            && x < size.getX() - 1 && y < size.getY() - 1 && z < size.getZ() - 1;
                    if (inside) {
                        assertFalse(kept[StructurePreviewVoxels.index(x, y, z, size)],
                                "the cell at " + x + "," + y + "," + z + " has six solid neighbours and no face "
                                        + "pointing anywhere, which is the only thing culling may drop");
                        dropped++;
                    } else {
                        assertTrue(kept[StructurePreviewVoxels.index(x, y, z, size)],
                                "the cell at " + x + "," + y + "," + z + " is on the cube's surface, so it stays");
                    }
                }
            }
        }
        assertEquals((SIDE - 2) * (SIDE - 2) * (SIDE - 2), dropped,
                "a solid 8³ cube gives up exactly its 6³ interior");
    }

    @Test
    @DisplayName("with structurePreviewCull off no cell is dropped at all")
    void theSwitchOffKeepsEverything() {
        StructurePreview preview = box(false, CELLS);
        Vec3i size = preview.size();
        boolean[] kept = StructurePreviewVoxels.kept(size, blockingOf(preview), false);

        for (int cell = 0; cell < kept.length; cell++) {
            assertTrue(kept[cell], "cell " + cell + " must be drawn with culling off, whatever its neighbours are - "
                    + "the switch exists so that a miniature drawn wrongly can be bisected from the config file");
        }
    }

    @Test
    @DisplayName("a coarsened box keeps its cavity, and culling drops nothing of it")
    void aCoarsenedBoxKeepsItsCavity() {
        StructurePreview preview = box(true, (SIDE / 2) * (SIDE / 2) * (SIDE / 2));
        assertEquals(2, preview.scale(), "a budget of 64 cells is met by sampling two cells per block");
        Vec3i size = preview.size();
        assertEquals(4, size.getX(), "so the picture is 4³ cells");

        assertEquals(4 * 4 * 4 - 2 * 2 * 2, preview.filledCells(),
                "sampled two cells per block, an 8³ box with a one-block shell is still a box with a one-block shell - "
                        + "the capture itself kept the cavity, so nothing culling does can be blamed for the shape of "
                        + "the picture at this scale");

        boolean[] blocking = blockingOf(preview);
        boolean[] kept = StructurePreviewVoxels.kept(size, blocking, true);
        int drawn = 0;
        for (int y = 0; y < size.getY(); y++) {
            for (int z = 0; z < size.getZ(); z++) {
                for (int x = 0; x < size.getX(); x++) {
                    int cell = StructurePreviewVoxels.index(x, y, z, size);
                    if (!blocking[cell]) {
                        continue;
                    }
                    drawn++;
                    assertTrue(kept[cell], "the cell at " + x + "," + y + "," + z + " is on the coarsened box's own "
                            + "shell, so it has a face pointing out of the picture and stays");
                }
            }
        }
        assertEquals(preview.filledCells(), drawn,
                "and every cell the capture holds is drawn: at this scale culling drops nothing at all, because the "
                        + "cavity's cells do not block what is behind them");
    }

    @Test
    @DisplayName("the live view speaks about preview cells, so no cell of it is attributed here")
    void theLiveViewIsAlreadyInPreviewCells() {
        assertTrue(StructurePreviewCells.previewCoordinate(4, 2, 16) == 2,
                "a structure cell is attributed to a preview cell by one division, and the answer is the server's: it "
                        + "has the pocket and the scale, and it sends one answer per cell of the picture rather than one "
                        + "per cell behind it (see StructurePreviewSamplingTest)");
        assertEquals(1, StructurePreviewCells.previewCoordinate(3, 2, 16),
                "two structure cells that fall in the same preview cell answer with the same coordinate");
    }

    // ------------------------------------------------------------- helpers --

    /** The preview of an 8³ box: hollow (a one-block shell) or solid, cut to {@code maxCells} cells. */
    private static StructurePreview box(boolean hollow, int maxCells) {
        Vec3i size = new Vec3i(SIDE, SIDE, SIDE);
        boolean[] shell = shellMask(size);
        int[] cells = new int[CELLS];
        for (int cell = 0; cell < CELLS; cell++) {
            // 0 is air (the palette's first entry) and 1 the block: the same numbering build() puts air at.
            cells[cell] = shell[cell] || !hollow ? 1 : 0;
        }
        List<CompoundTag> palette = List.of(named("minecraft:air"), named("minecraft:stone"));
        return StructurePreview.of(size, palette, IndexArray.of(cells, palette.size()), Integer.MAX_VALUE, maxCells);
    }

    /** Whether each cell of the 8³ grid is one of the box's six walls. */
    private static boolean[] shellMask(Vec3i size) {
        boolean[] shell = new boolean[CELLS];
        for (int y = 0; y < size.getY(); y++) {
            for (int z = 0; z < size.getZ(); z++) {
                for (int x = 0; x < size.getX(); x++) {
                    shell[StructurePreviewVoxels.index(x, y, z, size)] = x == 0 || y == 0 || z == 0
                            || x == size.getX() - 1 || y == size.getY() - 1 || z == size.getZ() - 1;
                }
            }
        }
        return shell;
    }

    /**
     * Which cells a preview holds a block in, in the form the culling rule reads.
     *
     * <p>The renderer asks each state {@code canOcclude} and {@code isSolidRender}; a palette of stone and air answers
     * those two questions with "not air" and nothing else, so this is that answer without a block registry.
     */
    private static boolean[] blockingOf(StructurePreview preview) {
        Vec3i size = preview.size();
        boolean[] blocking = new boolean[size.getX() * size.getY() * size.getZ()];
        for (int cell = 0; cell < blocking.length; cell++) {
            int entry = preview.cells().get(cell);
            blocking[cell] = entry >= 0 && entry < preview.palette().size()
                    && !StructurePreview.isAir(preview.palette().get(entry));
        }
        return blocking;
    }

    private static CompoundTag named(String name) {
        CompoundTag tag = new CompoundTag();
        tag.putString("Name", name);
        return tag;
    }
}

package com.jiangyuefengyu.redstonecircuit.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;

/**
 * The rule that decides whether a cell of a miniature is buried, without a game.
 *
 * <p>It is pure arithmetic over a grid of flags, so it is tested as arithmetic rather than read off the source. The
 * cases are the ones that go wrong quietly: the middle of a solid block, a cell with exactly one open side, and a
 * cell on the preview's own edge - where there is nothing to count as a neighbour and the cell must therefore
 * survive, because a culled surface cell is a hole in the picture.
 */
class StructurePreviewVoxelsTest {

    @Test
    @DisplayName("a cell is hidden only when all six of its neighbours are blocking")
    void aCellNeedsAllSixNeighboursToBeHidden() {
        Vec3i size = new Vec3i(3, 3, 3);
        boolean[] all = blocking(size, true);

        assertTrue(StructurePreviewVoxels.hidden(1, 1, 1, size, all),
                "the middle of a solid block of cells is the one case that may be dropped");

        for (int axis = 0; axis < 3; axis++) {
            for (int side = -1; side <= 1; side += 2) {
                boolean[] open = blocking(size, true);
                int[] at = {1, 1, 1};
                at[axis] += side;
                open[StructurePreviewVoxels.index(at[0], at[1], at[2], size)] = false;
                assertFalse(StructurePreviewVoxels.hidden(1, 1, 1, size, open),
                        "one open neighbour on axis " + axis + " is a face that can be seen through, so the cell "
                                + "stays - this is the case that matters, because a culled surface cell is a hole");
            }
        }
    }

    @Test
    @DisplayName("nothing outside the preview counts as a neighbour")
    void theOutsideIsNotSolid() {
        Vec3i size = new Vec3i(3, 3, 3);
        boolean[] all = blocking(size, true);

        assertFalse(StructurePreviewVoxels.hidden(0, 1, 1, size, all),
                "a cell on the edge of the preview has five neighbours at most, and the missing one is air");
        assertFalse(StructurePreviewVoxels.hidden(0, 0, 0, size, all), "the corner is a corner of the picture");
        assertTrue(StructurePreviewVoxels.hidden(1, 1, 1, size, all), "while the same grid still hides its middle");
    }

    @Test
    @DisplayName("a face is hidden one direction at a time, and never the ones that point outside the preview")
    void everyFaceIsJudgedOnItsOwn() {
        Vec3i size = new Vec3i(3, 3, 3);
        boolean[] all = blocking(size, true);

        assertEquals(0x3F, StructurePreviewVoxels.faces(1, 1, 1, size, all),
                "the middle of a solid block has all six faces hidden - the same cell hidden() drops, asked one "
                        + "direction at a time");

        boolean[] wall = blocking(size, false);
        // A wall standing in the x = 1 plane: everything beside it in y and z is solid, nothing in x is.
        for (int y = 0; y < size.getY(); y++) {
            for (int z = 0; z < size.getZ(); z++) {
                wall[StructurePreviewVoxels.index(1, y, z, size)] = true;
            }
        }
        int lateral = (1 << Direction.DOWN.ordinal()) | (1 << Direction.UP.ordinal())
                | (1 << Direction.NORTH.ordinal()) | (1 << Direction.SOUTH.ordinal());
        assertEquals(lateral, StructurePreviewVoxels.faces(1, 1, 1, size, wall),
                "the four lateral faces of a cell inside a wall are the hidden ones - they point at the wall beside "
                        + "them - so only the two that look out of it (west and east) are emitted");

        assertEquals(0, StructurePreviewVoxels.faces(0, 0, 0, size, blocking(size, false)),
                "a cell with nothing beside it hides nothing, which is every cell of a one-cell structure");
    }

    private static boolean[] blocking(Vec3i size, boolean value) {
        boolean[] blocking = new boolean[size.getX() * size.getY() * size.getZ()];
        Arrays.fill(blocking, value);
        return blocking;
    }
}

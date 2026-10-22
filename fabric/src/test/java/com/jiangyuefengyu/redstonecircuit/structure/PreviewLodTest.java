package com.jiangyuefengyu.redstonecircuit.structure;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import net.minecraft.core.Vec3i;

/**
 * The picture an icon is drawn from, without a game.
 *
 * <p>Half the resolution is worth nothing if it is a different reading of the same WALL: the case that matters is the
 * one that was already got wrong once at capture time - a decorated one-cell wall with a solid layer behind it - so the
 * cases here are a wall, a buried group, an empty group and a size that does not divide by two.
 */
class PreviewLodTest {

    @Test
    @DisplayName("a decorated wall is still the wall at half the resolution, not the layer behind it")
    void theWallStaysInFront() {
        Vec3i size = new Vec3i(4, 4, 4);
        int wall = 1;
        int behind = 2;
        int[] cells = new int[size.getX() * size.getY() * size.getZ()];
        // A one-cell wall on x = 0 and a solid layer behind it on x = 1: three cells of wall against four of layer in
        // every 2x2x2 group, which is exactly the vote a plain majority loses.
        for (int y = 0; y < size.getY(); y++) {
            for (int z = 0; z < size.getZ(); z++) {
                cells[StructureLiveView.index(0, y, z, size)] = wall;
                cells[StructureLiveView.index(1, y, z, size)] = behind;
            }
        }
        Vec3i half = PreviewLod.half(size);
        int[] reduced = PreviewLod.half(cells, 0, size, half);

        for (int y = 0; y < half.getY(); y++) {
            for (int z = 0; z < half.getZ(); z++) {
                assertEquals(wall, reduced[StructureLiveView.index(0, y, z, half)],
                        "the first column of the icon is the wall: it is the picture's skin, so the rule prefers it to "
                                + "the layer that outnumbers it");
            }
        }
    }

    @Test
    @DisplayName("an empty group stays empty and a buried one answers its majority")
    void emptyStaysEmptyAndBuriedAnswersItsMajority() {
        Vec3i size = new Vec3i(4, 4, 4);
        Vec3i half = PreviewLod.half(size);
        assertEquals(0, PreviewLod.half(new int[size.getX() * size.getY() * size.getZ()], 0, size, half)[0],
                "nothing is nothing at any resolution");

        int[] solid = new int[size.getX() * size.getY() * size.getZ()];
        java.util.Arrays.fill(solid, 7);
        int[] reduced = PreviewLod.half(solid, 0, size, half);
        for (int value : reduced) {
            assertEquals(7, value, "a block of one block stays that block, however small the icon is");
        }
    }

    @Test
    @DisplayName("one cell stays one cell, and an odd size rounds up")
    void anOddSizeRoundsUp() {
        assertEquals(new Vec3i(1, 1, 1), PreviewLod.half(new Vec3i(1, 1, 1)),
                "a single cell structure has an icon of one cell, not of none");
        assertEquals(new Vec3i(2, 2, 2), PreviewLod.half(new Vec3i(3, 3, 3)), "three cells become two, not one");
        assertEquals(new Vec3i(16, 16, 16), PreviewLod.half(new Vec3i(32, 32, 32)), "and 32 becomes 16");
    }

    @Test
    @DisplayName("the far corner of an odd size is not lost")
    void theFarCornerSurvives() {
        Vec3i size = new Vec3i(3, 3, 3);
        Vec3i half = PreviewLod.half(size);
        int[] cells = new int[size.getX() * size.getY() * size.getZ()];
        cells[StructureLiveView.index(2, 2, 2, size)] = 4;
        int[] reduced = PreviewLod.half(cells, 0, size, half);
        assertEquals(4, reduced[StructureLiveView.index(1, 1, 1, half)],
                "the last cell of an odd structure belongs to the last cell of its icon: an icon that drops it is a "
                        + "structure with a corner missing");
    }
}
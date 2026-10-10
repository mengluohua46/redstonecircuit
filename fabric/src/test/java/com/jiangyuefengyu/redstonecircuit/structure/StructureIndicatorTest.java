package com.jiangyuefengyu.redstonecircuit.structure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;

/**
 * The geometry of the visible indicator.
 *
 * <p>What this pins down is which cells the indicator is read from, and that is pure geometry, so it can be settled
 * without a world. If these answers are wrong the indicator is wrong in a way no screenshot would show: it would be
 * watching a corner instead of a face.
 */
class StructureIndicatorTest {

    @Test
    @DisplayName("every face is watched at its own centre, in Direction order")
    void theSixCentres() {
        List<BlockPos> cells = StructureIndicator.faceCentres(new Vec3i(10, 10, 10));
        assertEquals(Direction.values().length, cells.size(), "one cell per face, and no more");
        assertEquals(new BlockPos(5, 0, 5), cells.get(Direction.DOWN.ordinal()));
        assertEquals(new BlockPos(5, 9, 5), cells.get(Direction.UP.ordinal()));
        assertEquals(new BlockPos(5, 5, 0), cells.get(Direction.NORTH.ordinal()));
        assertEquals(new BlockPos(5, 5, 9), cells.get(Direction.SOUTH.ordinal()));
        assertEquals(new BlockPos(0, 5, 5), cells.get(Direction.WEST.ordinal()));
        assertEquals(new BlockPos(9, 5, 5), cells.get(Direction.EAST.ordinal()));
    }

    @Test
    @DisplayName("a size of one watches the single cell from all six faces")
    void aOneByOneStructure() {
        List<BlockPos> cells = StructureIndicator.faceCentres(new Vec3i(1, 1, 1));
        for (BlockPos cell : cells) {
            assertEquals(BlockPos.ZERO, cell, "a single cell has one answer, whichever way it is looked at");
        }
    }

    @Test
    @DisplayName("a non-cubic structure keeps the middle of each face, per axis")
    void aNonCubicStructure() {
        List<BlockPos> cells = StructureIndicator.faceCentres(new Vec3i(3, 4, 5));
        assertEquals(new BlockPos(1, 0, 2), cells.get(Direction.DOWN.ordinal()));
        assertEquals(new BlockPos(1, 3, 2), cells.get(Direction.UP.ordinal()));
        assertEquals(new BlockPos(1, 2, 0), cells.get(Direction.NORTH.ordinal()));
        assertEquals(new BlockPos(1, 2, 4), cells.get(Direction.SOUTH.ordinal()));
        assertEquals(new BlockPos(0, 2, 2), cells.get(Direction.WEST.ordinal()));
        assertEquals(new BlockPos(2, 2, 2), cells.get(Direction.EAST.ordinal()));
    }

    @Test
    @DisplayName("the cells a table drives are watched too, and only once each")
    void tableCellsAreWatched() {
        Vec3i size = new Vec3i(10, 10, 10);
        List<BlockPos> table = List.of(new BlockPos(1, 2, 3), new BlockPos(5, 5, 5), new BlockPos(1, 2, 3));
        List<BlockPos> watched = StructureIndicator.watchedCells(size, table);
        // Six face centres, the two distinct table cells, and the face centre (5,5,5) that DOWN/UP/NORTH/... share
        // with the table is not added twice.
        assertEquals(8, watched.size(), "six faces plus two new table cells: " + watched);
        assertTrue(watched.contains(new BlockPos(1, 2, 3)), "the cell a press writes into must be watched");
        assertEquals(watched.size(), new HashSet<>(watched).size(), "no cell may be watched twice");
        for (int i = 0; i < 6; i++) {
            assertEquals(StructureIndicator.faceCentres(size).get(i), watched.get(i),
                    "the face centres come first and keep their order");
        }
    }

    @Test
    @DisplayName("a structure with no tables watches exactly its six faces")
    void noTablesMeansSixCells() {
        assertEquals(6, StructureIndicator.watchedCells(new Vec3i(4, 4, 4), List.of()).size());
    }

    @Test
    @DisplayName("each watched cell sits on the face it is watched for")
    void everyCellIsOnItsOwnFace() {
        for (int size : new int[] {1, 2, 3, 5, 16}) {
            Vec3i box = new Vec3i(size, size, size);
            List<BlockPos> cells = StructureIndicator.faceCentres(box);
            for (Direction face : Direction.values()) {
                BlockPos cell = cells.get(face.ordinal());
                int along = switch (face.getAxis()) {
                    case X -> cell.getX();
                    case Y -> cell.getY();
                    case Z -> cell.getZ();
                };
                boolean onTheFarSide = face.getAxisDirection() == Direction.AxisDirection.POSITIVE;
                assertEquals(onTheFarSide ? size - 1 : 0, along,
                        "size " + size + ": the " + face + " cell must lie on that face, got " + cell);
            }
        }
    }

    @Test
    @DisplayName("an odd structure watches six different cells, an even one may share the middle")
    void theSixCellsAreDistinctWhenTheMiddleIsWellDefined() {
        // With an even side there is no middle cell, so two faces can legitimately land on the same one - which is
        // why this is asserted for odd sides only, and why the property that always holds is "on its own face".
        for (int size : new int[] {3, 5, 15}) {
            List<BlockPos> cells = StructureIndicator.faceCentres(new Vec3i(size, size, size));
            assertEquals(cells.size(), new HashSet<>(cells).size(), "size " + size + " must watch six cells");
        }
    }

    @Test
    @DisplayName("every watched cell is inside the structure it describes")
    void everyCellIsInside() {
        Vec3i size = new Vec3i(7, 3, 9);
        for (BlockPos cell : StructureIndicator.faceCentres(size)) {
            assertTrue(cell.getX() >= 0 && cell.getX() < size.getX()
                            && cell.getY() >= 0 && cell.getY() < size.getY()
                            && cell.getZ() >= 0 && cell.getZ() < size.getZ(),
                    "cell " + cell + " must be inside " + size);
        }
    }
}

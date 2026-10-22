package com.jiangyuefengyu.redstonecircuit.structure;

import java.util.List;
import java.util.function.IntPredicate;

import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;

/**
 * The outermost layer of a structure on each of its six faces, as a flat two-dimensional grid.
 *
 * <p>"The surface is missing" cannot be settled from a picture, because a structure that never had a surface
 * and a structure whose surface was lost look the same. It can be settled from the outer layer itself: one
 * grid per face, every cell of it, counted. That is what this class hands out.
 *
 * <p>The grid is the layer's own plane, so {@code rows * columns} is exactly the number of cells a complete
 * face holds, and a grid that comes back short is the defect the class exists to expose - a layer index that
 * stops one short, or a division that loses the remainder, shows up as a missing row rather than as a subtly
 * wrong picture. The two in-plane extents are always taken from the structure's size, never from the cell
 * count, so a payload whose cells stop early loses cells, not rows.
 *
 * <p>Rows run top down wherever the face has a vertical axis (row 0 is the layer's highest {@code y}), so a
 * printed face stands up the same way the world does; the two horizontal faces are read in the structure's
 * own {@code z} order because they have no up. Columns always ascend from zero.
 *
 * <p>Pure arithmetic over an {@link IndexArray}: no level, no blocks, no packet.
 */
public final class OuterLayers {

    /** The six faces in the order a report prints them: the four sides, then the horizontal pair. */
    public static final List<Direction> FACES =
            List.of(Direction.WEST, Direction.EAST, Direction.DOWN, Direction.UP, Direction.NORTH,
                    Direction.SOUTH);

    /**
     * One face's outermost layer, {@code rows} × {@code columns} cells in row-major order.
     *
     * <p>{@code layer} is the coordinate of the layer along the face's own axis - the number a reader checks
     * against the structure's size to see that the outermost plane really was the one taken.
     * {@code firstRowCoordinate} and {@code rowStride} say which grid line is which: a side face starts at the
     * highest {@code y} and counts down, a horizontal face starts at {@code z} zero and counts up.
     */
    public record Layer(Direction face, int layer, int rows, int columns, int[] cells,
                        int firstRowCoordinate, int rowStride) {

        public Layer {
            cells = cells.clone();
        }

        /** One cell's palette index, or {@code -1} where the payload has no cell. */
        public int at(int row, int column) {
            return this.cells[row * this.columns + column];
        }

        /** How many cells of the grid match - the count a "is the surface here" question is asked with. */
        public int count(IntPredicate matches) {
            int found = 0;
            for (int cell : this.cells) {
                if (matches.test(cell)) {
                    found++;
                }
            }
            return found;
        }

        /** The {@code y} (side faces) or {@code z} (horizontal faces) grid line {@code row} stands on. */
        public int rowCoordinate(int row) {
            return this.firstRowCoordinate + row * this.rowStride;
        }
    }

    /**
     * The outermost layer of {@code size} on {@code face}, every cell of it.
     *
     * <p>An empty size is treated as one cell wide rather than as a reason to throw: this is reachable from a
     * payload read out of a file somebody edited, and a face that cannot be cut is still a face to report on.
     */
    public static Layer of(Vec3i size, IndexArray cells, Direction face) {
        int sideX = Math.max(1, size.getX());
        int sideY = Math.max(1, size.getY());
        int sideZ = Math.max(1, size.getZ());
        boolean positive = face.getAxisDirection() == Direction.AxisDirection.POSITIVE;
        int layer;
        int rows;
        int columns;

        switch (face.getAxis()) {
            case X -> {
                layer = positive ? sideX - 1 : 0;
                rows = sideY;
                columns = sideZ;
                int[] grid = new int[rows * columns];
                for (int row = 0; row < rows; row++) {
                    int y = sideY - 1 - row;
                    for (int column = 0; column < columns; column++) {
                        grid[row * columns + column] = cells.get(index(sideX, sideZ, layer, y, column));
                    }
                }
                return new Layer(face, layer, rows, columns, grid, sideY - 1, -1);
            }
            case Z -> {
                layer = positive ? sideZ - 1 : 0;
                rows = sideY;
                columns = sideX;
                int[] grid = new int[rows * columns];
                for (int row = 0; row < rows; row++) {
                    int y = sideY - 1 - row;
                    for (int column = 0; column < columns; column++) {
                        grid[row * columns + column] = cells.get(index(sideX, sideZ, column, y, layer));
                    }
                }
                return new Layer(face, layer, rows, columns, grid, sideY - 1, -1);
            }
            default -> {
                layer = positive ? sideY - 1 : 0;
                rows = sideZ;
                columns = sideX;
                int[] grid = new int[rows * columns];
                for (int row = 0; row < rows; row++) {
                    for (int column = 0; column < columns; column++) {
                        grid[row * columns + column] = cells.get(index(sideX, sideZ, column, layer, row));
                    }
                }
                return new Layer(face, layer, rows, columns, grid, 0, 1);
            }
        }
    }

    /** The payload's own cell numbering: {@code x} fastest, then {@code z}, then {@code y}. */
    public static int index(int sideX, int sideZ, int x, int y, int z) {
        return x + Math.max(1, sideX) * (z + Math.max(1, sideZ) * y);
    }

    /** The face as a report names it: {@code +X}, {@code -Z}, and so on. */
    public static String label(Direction face) {
        String axis = switch (face.getAxis()) {
            case X -> "X";
            case Y -> "Y";
            case Z -> "Z";
        };
        return (face.getAxisDirection() == Direction.AxisDirection.POSITIVE ? "+" : "-") + axis;
    }

    /** The coordinate a face's layer is taken at: {@code x}, {@code y} or {@code z}, lower case. */
    public static String axisName(Direction face) {
        return switch (face.getAxis()) {
            case X -> "x";
            case Y -> "y";
            case Z -> "z";
        };
    }

    /** The coordinate a grid's rows run along: {@code y} on a side face, {@code z} on a horizontal one. */
    public static String rowAxisName(Direction face) {
        return face.getAxis() == Direction.Axis.Y ? "z" : "y";
    }

    private OuterLayers() {
    }
}

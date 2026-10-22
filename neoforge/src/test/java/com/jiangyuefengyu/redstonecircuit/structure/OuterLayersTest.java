package com.jiangyuefengyu.redstonecircuit.structure;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;

/**
 * The outermost layer of each of a structure's six faces, cut out of its cells.
 *
 * <p>What is pinned here is the whole of the diagnostic this class serves: a face is the plane at its own
 * extreme coordinate - never one cell inside it, which would show the second layer and read as a missing
 * surface - and it never loses a row or a column, in particular not the last one of a side whose length the
 * preview's scale does not divide.
 *
 * <p>Pure arithmetic over an {@link IndexArray}, so it is decided here without a level.
 */
class OuterLayersTest {

    /** The cells of a {@code size} structure: palette 1 wherever {@code filled} says a block stands. */
    private static IndexArray cells(Vec3i size, Predicate<BlockPos> filled) {
        int[] indices = new int[size.getX() * size.getY() * size.getZ()];
        for (int y = 0; y < size.getY(); y++) {
            for (int z = 0; z < size.getZ(); z++) {
                for (int x = 0; x < size.getX(); x++) {
                    indices[OuterLayers.index(size.getX(), size.getZ(), x, y, z)] =
                            filled.test(new BlockPos(x, y, z)) ? 1 : 0;
                }
            }
        }
        return IndexArray.of(indices, 2);
    }

    /** A filled cell array - the cube case. */
    private static IndexArray solid(Vec3i size) {
        return cells(size, pos -> true);
    }

    /** Only the cells on the outside of the box, which is what a captured shell leaves behind. */
    private static IndexArray hollow(Vec3i size) {
        return cells(size, pos -> pos.getX() == 0 || pos.getX() == size.getX() - 1
                || pos.getY() == 0 || pos.getY() == size.getY() - 1
                || pos.getZ() == 0 || pos.getZ() == size.getZ() - 1);
    }

    /** The cells of the plane a face is taken at, in no particular order - what that face rearranges. */
    private static List<Integer> plane(Vec3i size, IndexArray cells, Direction face) {
        List<Integer> values = new ArrayList<>();
        int extreme = switch (face.getAxis()) {
            case X -> size.getX() - 1;
            case Y -> size.getY() - 1;
            case Z -> size.getZ() - 1;
        };
        for (int y = 0; y < size.getY(); y++) {
            for (int z = 0; z < size.getZ(); z++) {
                for (int x = 0; x < size.getX(); x++) {
                    int along = switch (face.getAxis()) {
                        case X -> x;
                        case Y -> y;
                        case Z -> z;
                    };
                    boolean outermost = face.getAxisDirection() == Direction.AxisDirection.POSITIVE
                            ? along == extreme
                            : along == 0;
                    if (outermost) {
                        values.add(cells.get(OuterLayers.index(size.getX(), size.getZ(), x, y, z)));
                    }
                }
            }
        }
        values.sort(Integer::compareTo);
        return values;
    }

    private static List<Integer> grid(OuterLayers.Layer layer) {
        List<Integer> values = new ArrayList<>();
        for (int row = 0; row < layer.rows(); row++) {
            for (int column = 0; column < layer.columns(); column++) {
                values.add(layer.at(row, column));
            }
        }
        values.sort(Integer::compareTo);
        return values;
    }

    private static int expectedRows(Vec3i size, Direction face) {
        return face.getAxis() == Direction.Axis.Y ? size.getZ() : size.getY();
    }

    private static int expectedColumns(Vec3i size, Direction face) {
        return face.getAxis() == Direction.Axis.X ? size.getZ() : size.getX();
    }

    @Test
    @DisplayName("a solid cube is filled on all six of its outermost layers")
    void everyFaceOfASolidCubeIsFull() {
        Vec3i size = new Vec3i(3, 3, 3);
        IndexArray cells = solid(size);

        for (Direction face : OuterLayers.FACES) {
            OuterLayers.Layer layer = OuterLayers.of(size, cells, face);
            assertEquals(3, layer.rows(), face + " rows");
            assertEquals(3, layer.columns(), face + " columns");
            assertEquals(9, layer.count(index -> index > 0), face + " is the whole 3x3 face");
        }
    }

    @Test
    @DisplayName("a hollow cube has all six faces present, which is the reading a missing surface contradicts")
    void everyFaceOfAHollowCubeIsFull() {
        Vec3i size = new Vec3i(3, 3, 3);
        IndexArray cells = hollow(size);

        for (Direction face : OuterLayers.FACES) {
            OuterLayers.Layer layer = OuterLayers.of(size, cells, face);
            assertEquals(9, layer.count(index -> index > 0), face + " of a 1-thick shell is a full face");
        }
    }

    @Test
    @DisplayName("a face is the plane at its own extreme coordinate, never the one behind it")
    void aFaceIsTheOutermostPlane() {
        Vec3i size = new Vec3i(4, 3, 5);
        // Every cell names its own x, so a plane taken one cell inside it would carry other values.
        int[] offsets = new int[size.getX() * size.getY() * size.getZ()];
        for (int y = 0; y < size.getY(); y++) {
            for (int z = 0; z < size.getZ(); z++) {
                for (int x = 0; x < size.getX(); x++) {
                    offsets[OuterLayers.index(size.getX(), size.getZ(), x, y, z)] = x + 1;
                }
            }
        }
        IndexArray numbered = IndexArray.of(offsets, size.getX() + 1);

        OuterLayers.Layer west = OuterLayers.of(size, numbered, Direction.WEST);
        OuterLayers.Layer east = OuterLayers.of(size, numbered, Direction.EAST);
        assertEquals(0, west.layer(), "-X is x=0");
        assertEquals(size.getX() - 1, east.layer(), "+X is the last column");
        assertEquals(west.rows() * west.columns(), west.count(index -> index == 1),
                "the -X face is every x=0 cell and nothing else");
        assertEquals(4, east.at(0, 0), "and the +X face is the x=3 ones");

        for (Direction face : OuterLayers.FACES) {
            assertEquals(plane(size, numbered, face), grid(OuterLayers.of(size, numbered, face)),
                    face + " must be exactly the cells of its own extreme plane, in any order");
        }
    }

    @Test
    @DisplayName("a side whose length the scale does not divide keeps its last row and column")
    void noRowOrColumnIsDropped() {
        // 7, 5 and 3 are the sizes a coarsened preview has to round up to reach.
        Vec3i size = new Vec3i(7, 5, 3);
        IndexArray cells = hollow(size);

        for (Direction face : OuterLayers.FACES) {
            OuterLayers.Layer layer = OuterLayers.of(size, cells, face);
            assertEquals(expectedRows(size, face), layer.rows(), face + " rows");
            assertEquals(expectedColumns(size, face), layer.columns(), face + " columns");
            assertEquals(layer.rows() * layer.columns(), layer.count(index -> index > 0),
                    face + " of a hollow box is full to its last row and column");
            assertEquals(plane(size, cells, face), grid(layer), face + " keeps every cell of the plane");
        }
    }

    @Test
    @DisplayName("the rows are labelled top down on a side face and in order on a horizontal one")
    void rowsAreLabelledTheWayTheFaceIsRead() {
        Vec3i size = new Vec3i(4, 6, 2);
        IndexArray cells = solid(size);

        OuterLayers.Layer east = OuterLayers.of(size, cells, Direction.EAST);
        assertEquals(5, east.rowCoordinate(0), "the first row of a side face is its highest y");
        assertEquals(0, east.rowCoordinate(east.rows() - 1), "and the last is y=0");

        OuterLayers.Layer up = OuterLayers.of(size, cells, Direction.UP);
        assertEquals(0, up.rowCoordinate(0), "a horizontal face has no up, so its rows are z in order");
        assertEquals(1, up.rowCoordinate(up.rows() - 1));
        assertEquals("z", OuterLayers.rowAxisName(Direction.UP));
        assertEquals("y", OuterLayers.rowAxisName(Direction.EAST));
        assertEquals("+X", OuterLayers.label(Direction.EAST));
        assertEquals("-Z", OuterLayers.label(Direction.NORTH));
    }

    @Test
    @DisplayName("a layer whose cells stop short shows as few cells, not as missing rows")
    void aShortPayloadLosesCellsNotRows() {
        Vec3i size = new Vec3i(3, 3, 3);
        IndexArray nothing = IndexArray.empty();

        OuterLayers.Layer east = OuterLayers.of(size, nothing, Direction.EAST);
        assertEquals(3, east.rows(), "the grid's shape comes from the structure's size");
        assertEquals(3, east.columns());
        assertEquals(9, east.count(index -> index < 0), "and the cells that are not there say so");
    }
}

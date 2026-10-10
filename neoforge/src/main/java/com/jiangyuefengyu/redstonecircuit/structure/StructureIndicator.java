package com.jiangyuefengyu.redstonecircuit.structure;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;

/**
 * Which cells of a structure say how it is doing - the block's glow is a product block ({@code StructureBlock.LIGHT}),
 * and what this class decides is <em>which internal cells are looked at</em>. Pure geometry, so the answer can be
 * checked without a world.
 *
 * <p>A structure is read from outside, so the cells worth watching are on its shell, and of the shell one per face: the
 * middle of each face. Six cells, one lookup each, and the number is stable while the structure is edited from the
 * inside (a re-capture changes the size, and the centres move with it). It is not "every powered cell", which would
 * scan the whole structure every tick to answer a question about a lamp.
 *
 * <p>The six cells are selection-local, the same coordinates the injection tables use.
 */
public final class StructureIndicator {

    /** The six face-centre cells of a structure of this size, in {@link Direction} order (down, up, north, ...). */
    public static List<BlockPos> faceCentres(Vec3i size) {
        List<BlockPos> cells = new ArrayList<>(6);
        int midX = size.getX() / 2;
        int midY = size.getY() / 2;
        int midZ = size.getZ() / 2;
        for (Direction face : Direction.values()) {
            int x = midX;
            int y = midY;
            int z = midZ;
            switch (face) {
                case DOWN -> y = 0;
                case UP -> y = Math.max(0, size.getY() - 1);
                case NORTH -> z = 0;
                case SOUTH -> z = Math.max(0, size.getZ() - 1);
                case WEST -> x = 0;
                case EAST -> x = Math.max(0, size.getX() - 1);
                default -> throw new IllegalStateException("unreachable: " + face);
            }
            cells.add(new BlockPos(x, y, z));
        }
        return cells;
    }

    /**
     * The six face centres <b>plus the cells the tables drive</b>: the face centres answer "is the shell alive" but not
     * "did <em>my press</em> land", and the cells the tables name are exactly the cells a press writes into, so
     * watching them as well makes the glow follow the player's own action.
     *
     * <p>Deduplicated and stable-ordered (face centres first, then the table cells that are not already there): the
     * caller takes a maximum over the list and does not care about order, while a test does.
     */
    public static List<BlockPos> watchedCells(Vec3i size, List<BlockPos> tableCells) {
        List<BlockPos> cells = faceCentres(size);
        for (BlockPos cell : tableCells) {
            if (cell != null && !cells.contains(cell)) {
                cells.add(cell);
            }
        }
        return cells;
    }

    private StructureIndicator() {
    }
}

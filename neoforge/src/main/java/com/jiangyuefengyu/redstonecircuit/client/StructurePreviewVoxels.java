package com.jiangyuefengyu.redstonecircuit.client;

import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;

/**
 * Index arithmetic over a preview's cell grid, and the rule that decides whether a cell is buried.
 *
 * <p>Both decide what a player sees, and the second is one multiplication away from being wrong at the preview's
 * edge: a cell that is dropped because the rule looked past the boundary is a hole in a miniature that a player
 * reads as a broken structure. Kept free of block states, GL and the game, so the arithmetic - and the six-way
 * conjunction itself - are testable without one.
 */
final class StructurePreviewVoxels {

    private StructurePreviewVoxels() {
    }

    /** A cell's index in a grid of the preview's own size - the same numbering the payload uses. */
    static int index(int x, int y, int z, Vec3i size) {
        return x + size.getX() * (z + size.getZ() * y);
    }

    /**
     * Whether a cell is completely buried: every one of its six neighbours must be a cell {@code blocking} records
     * as hiding what is behind it.
     *
     * <p>There is nothing outside the preview, so a cell on its surface is never buried - which is what keeps the
     * outer face of every structure visible.
     */
    static boolean hidden(int x, int y, int z, Vec3i size, boolean[] blocking) {
        return blockingAt(x - 1, y, z, size, blocking) && blockingAt(x + 1, y, z, size, blocking)
                && blockingAt(x, y - 1, z, size, blocking) && blockingAt(x, y + 1, z, size, blocking)
                && blockingAt(x, y, z - 1, size, blocking) && blockingAt(x, y, z + 1, size, blocking);
    }

    /**
     * The faces of a cell that a neighbour inside the preview hides, as the bits of {@link Direction#ordinal()}.
     *
     * <p>{@link #hidden} asks all six at once and drops the cell; this asks one at a time and keeps the cell, which is
     * what a picture of a wall needs: four of a wall cell's six faces point at the wall next to it and are drawn today
     * only to be hidden again by the cell beside them. The same two-part test decides both - only a neighbour that
     * hides what is behind it <em>and</em> renders as a full cube is counted - and nothing outside the preview counts,
     * so the outer surface of every structure keeps every face it had.
     */
    static int faces(int x, int y, int z, Vec3i size, boolean[] blocking) {
        int mask = 0;
        for (Direction side : Direction.values()) {
            if (blockingAt(x + side.getStepX(), y + side.getStepY(), z + side.getStepZ(), size, blocking)) {
                mask |= 1 << side.ordinal();
            }
        }
        return mask;
    }

    /**
     * The scale a miniature is drawn at: a structure of this size fits one block with half a cell to spare.
     *
     * <p>Here rather than beside the drawing because everything about "does the picture stay inside its block" is this
     * number and the two translations around it - and a picture that leaves its block is a picture with surfaces
     * missing, since what sticks out lies inside the neighbouring block's geometry, which draws over it. Arithmetic
     * that decides what a player sees belongs where it can be tested without a game.
     */
    static float miniatureScale(Vec3i size) {
        int longest = Math.max(size.getX(), Math.max(size.getY(), size.getZ()));
        return 1.0f / (longest + 0.5f);
    }

    /**
     * Where a corner of the miniature's own box lands in the block's unit cube: the half block that centres the
     * picture, the scale above, and the recentring that moves the box's own centre to the block's - the same three
     * steps {@code StructurePreviewGeometry#drawInsideCube} pushes on the pose.
     *
     * <p>Horizontally the picture is centred; vertically it stands on the block's floor, because a miniature of a
     * structure standing on the ground belongs on the ground of the block it is drawn in.
     */
    static float[] miniatureCorner(Vec3i size, int x, int y, int z) {
        float scale = miniatureScale(size);
        return new float[] {
            0.5F + scale * (x - size.getX() / 2.0F),
            scale * y,
            0.5F + scale * (z - size.getZ() / 2.0F)
        };
    }

    /**
     * Which cells of a grid must still be drawn, as a mask over every cell of it: {@code true} wherever the cell is
     * visible from outside, or too close to the preview's own edge to be called buried.
     *
     * <p>{@code culling} is handed in rather than read here, so the switch that decides whether anything is dropped
     * at all is testable as arithmetic: off, every cell survives whatever its neighbours say.
     *
     * <p>A cell the mask keeps is a cell the miniature draws, so the one outcome this may not produce is a hole: a
     * cell on the surface of the picture has at least one face pointing out of it, and a face pointing out of it is
     * what a player sees.
     */
    static boolean[] kept(Vec3i size, boolean[] blocking, boolean culling) {
        boolean[] kept = new boolean[size.getX() * size.getY() * size.getZ()];
        for (int y = 0; y < size.getY(); y++) {
            for (int z = 0; z < size.getZ(); z++) {
                for (int x = 0; x < size.getX(); x++) {
                    kept[index(x, y, z, size)] = !culling || !hidden(x, y, z, size, blocking);
                }
            }
        }
        return kept;
    }

    private static boolean blockingAt(int x, int y, int z, Vec3i size, boolean[] blocking) {
        if (x < 0 || y < 0 || z < 0 || x >= size.getX() || y >= size.getY() || z >= size.getZ()) {
            return false;
        }
        return blocking[index(x, y, z, size)];
    }
}

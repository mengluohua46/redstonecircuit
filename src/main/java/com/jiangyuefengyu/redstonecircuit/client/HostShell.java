package com.jiangyuefengyu.redstonecircuit.client;

/**
 * The geometry of the translucent shell drawn around a host block.
 *
 * <p>Kept apart from the renderer - and free of any client class - because this is the part that
 * cannot be checked by looking at a test run: get the winding backwards and every face is culled,
 * get the expansion wrong and the shell either z-fights with the block or hides behind it. Here it is
 * plain arithmetic that {@code HostShellTest} can pin down.
 *
 * <p>The shell is the block's unit cube pushed out by {@link #EXPAND} on both sides, so it wraps the
 * vanilla cube instead of sharing a plane with it. Each face is emitted as up to five rectangles: a
 * centre panel plus four rims of {@link #FRAME} width, which tile the face exactly once - two of our
 * own quads on the same plane would z-fight with each other just like they would with the block.
 */
public final class HostShell {

    /**
     * How far the shell sits outside the block, in blocks.
     *
     * <p>It has to be outside: coplanar with the vanilla cube it would z-fight, and inset it would be
     * hidden behind it. Five millimetres is invisible at any sane view distance and still puts the
     * shell decisively in front of the block.
     */
    public static final float EXPAND = 0.005F;

    /** Length of one edge of the expanded cube. */
    public static final float SPAN = 1.0F + 2 * EXPAND;

    /** Width of the border, as a fraction of a face: one pixel of a 16x16 texture. */
    public static final float FRAME = 1.0F / 16.0F;

    /** Number of faces of the cube. */
    public static final int FACE_COUNT = 6;

    /**
     * Per face: the origin corner, then the two edge vectors.
     *
     * <p>Ordered so that the quad {@code (0,0) (1,0) (1,1) (0,1)} is wound counter-clockwise seen from
     * outside, which is what vanilla's own block quads use and what the chunk renderer's back-face
     * culling expects. The outward normal is the cross product of the two edge vectors, so listing it
     * separately would only be a chance to get it inconsistent.
     */
    private static final float[][][] FACES = {
            { { 0, 1, 0 }, { 0, 0, 1 }, { 1, 0, 0 } },   // up
            { { 0, 0, 0 }, { 1, 0, 0 }, { 0, 0, 1 } },   // down
            { { 0, 0, 0 }, { 0, 1, 0 }, { 1, 0, 0 } },   // north
            { { 1, 0, 1 }, { 0, 1, 0 }, { -1, 0, 0 } },  // south
            { { 0, 0, 1 }, { 0, 1, 0 }, { 0, 0, -1 } },  // west
            { { 1, 0, 0 }, { 0, 1, 0 }, { 0, 0, 1 } },   // east
    };

    /**
     * The five rectangles that tile a face, each as {@code {a0, b0, a1, b1}} in face coordinates.
     *
     * <p>The centre panel comes first (it is the translucent tint), the four rims after it (they are
     * the border). Together they cover the unit square exactly once.
     */
    private static final float[][] TILES = {
            { FRAME, FRAME, 1 - FRAME, 1 - FRAME },
            { 0, 0, 1, FRAME },
            { 0, 1 - FRAME, 1, 1 },
            { 0, FRAME, FRAME, 1 - FRAME },
            { 1 - FRAME, FRAME, 1, 1 - FRAME },
    };

    private HostShell() {
    }

    /** The number of quads a face needs: one if there is no border, otherwise the whole tile set. */
    public static int quadsPerFace(boolean withFrame) {
        return withFrame ? TILES.length : 1;
    }

    /** The sub-rectangle of the given quad of a face, as {@code {a0, b0, a1, b1}}. */
    public static float[] tile(int index, boolean withFrame) {
        return withFrame ? TILES[index] : new float[] { 0, 0, 1, 1 };
    }

    /** Outward normal of a face, written into {@code out}. */
    public static void normal(int face, float[] out) {
        float[] alongU = FACES[face][1];
        float[] alongV = FACES[face][2];
        out[0] = alongU[1] * alongV[2] - alongU[2] * alongV[1];
        out[1] = alongU[2] * alongV[0] - alongU[0] * alongV[2];
        out[2] = alongU[0] * alongV[1] - alongU[1] * alongV[0];
    }

    /**
     * The point of a face at face coordinates {@code (a, b)}, in block-local space, written into
     * {@code out}. {@code (0,0)} is the face's origin corner and {@code (1,1)} the opposite one.
     */
    public static void corner(int face, float a, float b, float[] out) {
        float[] origin = FACES[face][0];
        float[] alongU = FACES[face][1];
        float[] alongV = FACES[face][2];
        float u = a * SPAN;
        float v = b * SPAN;
        out[0] = expand(origin[0]) + alongU[0] * u + alongV[0] * v;
        out[1] = expand(origin[1]) + alongU[1] * u + alongV[1] * v;
        out[2] = expand(origin[2]) + alongU[2] * u + alongV[2] * v;
    }

    /** A face corner of the unit cube (0 or 1) moved to the expanded cube. */
    private static float expand(float corner) {
        return corner == 0 ? -EXPAND : 1 + EXPAND;
    }
}

package com.jiangyuefengyu.redstonecircuit.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.core.Direction;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests the shell geometry.
 *
 * <p>This is the part of the rendering that cannot be checked by looking at a test run, and every
 * way of getting it wrong is silent: a reversed winding makes the whole shell invisible through
 * back-face culling, an inset shell hides behind the block, a coplanar one z-fights with it, and
 * overlapping tiles z-fight with each other. All of it is arithmetic, so all of it is asserted here.
 */
class HostShellTest {

    private static final float EPSILON = 1.0E-5F;

    private static float[] corner(int face, float a, float b) {
        float[] out = new float[3];
        HostShell.corner(face, a, b, out);
        return out;
    }

    private static float[] normal(int face) {
        float[] out = new float[3];
        HostShell.normal(face, out);
        return out;
    }

    /** {@code (v1 - v0) x (v2 - v0)}, the winding direction of the first triangle of a quad. */
    private static float[] winding(int face) {
        float[] v0 = corner(face, 0, 0);
        float[] v1 = corner(face, 1, 0);
        float[] v2 = corner(face, 1, 1);
        float[] e1 = { v1[0] - v0[0], v1[1] - v0[1], v1[2] - v0[2] };
        float[] e2 = { v2[0] - v0[0], v2[1] - v0[1], v2[2] - v0[2] };
        return new float[] {
                e1[1] * e2[2] - e1[2] * e2[1],
                e1[2] * e2[0] - e1[0] * e2[2],
                e1[0] * e2[1] - e1[1] * e2[0]
        };
    }

    private static void assertClose(String what, float expected, float actual) {
        assertEquals(expected, actual, EPSILON, what);
    }

    @Test
    @DisplayName("every face covers all six directions, exactly once")
    void facesCoverEveryDirection() {
        boolean[] seen = new boolean[Direction.values().length];
        for (int face = 0; face < HostShell.FACE_COUNT; face++) {
            float[] normal = normal(face);
            Direction direction = Direction.fromDelta(Math.round(normal[0]), Math.round(normal[1]),
                    Math.round(normal[2]));
            assertTrue(direction != null, "face " + face + " has a diagonal normal " + normal[0] + ","
                    + normal[1] + "," + normal[2]);
            assertTrue(!seen[direction.ordinal()], "two faces both face " + direction);
            seen[direction.ordinal()] = true;
        }
        assertEquals(HostShell.FACE_COUNT, Direction.values().length,
                "the cube must have exactly one face per direction");
    }

    @Test
    @DisplayName("the quad winding matches the outward normal a renderer would cull by")
    void windingFollowsTheNormal() {
        for (int face = 0; face < HostShell.FACE_COUNT; face++) {
            float[] normal = normal(face);
            float[] winding = winding(face);
            // A positive dot product means the first triangle is wound counter-clockwise as seen from
            // outside, which is the front face the chunk renderer keeps.
            float dot = normal[0] * winding[0] + normal[1] * winding[1] + normal[2] * winding[2];
            assertTrue(dot > 0,
                    "face " + face + " is wound backwards: normal " + normal[0] + "," + normal[1] + ","
                            + normal[2] + " but winding " + winding[0] + "," + winding[1] + ","
                            + winding[2]);
            // And the face has to be a whole expanded square, not a degenerate sliver: the edge
            // vectors span the expanded cube, so the parallelogram they span is SPAN by SPAN.
            assertClose("face " + face + " area", HostShell.SPAN * HostShell.SPAN,
                    (float) Math.sqrt(winding[0] * winding[0] + winding[1] * winding[1]
                            + winding[2] * winding[2]));
        }
    }

    @Test
    @DisplayName("the shell wraps the block instead of sharing a plane with it")
    void shellSitsOutsideTheBlock() {
        // The block itself occupies 0..1, so every corner of the shell is either just below 0 or just
        // above 1: never on the block's own faces, which would z-fight, and never inside it, which
        // would hide the shell behind the block.
        for (int face = 0; face < HostShell.FACE_COUNT; face++) {
            for (float a : new float[] { 0, 1 }) {
                for (float b : new float[] { 0, 1 }) {
                    for (float coordinate : corner(face, a, b)) {
                        boolean below = Math.abs(coordinate - (-HostShell.EXPAND)) < EPSILON;
                        boolean above = Math.abs(coordinate - (1 + HostShell.EXPAND)) < EPSILON;
                        assertTrue(below || above,
                                "face " + face + " corner (" + a + "," + b + ") has a coordinate of "
                                        + coordinate + ", which is not on the expanded cube");
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("each face's own axis is the one it is pushed out along")
    void eachFaceIsPushedOutAlongItsNormal() {
        for (int face = 0; face < HostShell.FACE_COUNT; face++) {
            float[] normal = normal(face);
            float[] centre = corner(face, 0.5F, 0.5F);
            for (int axis = 0; axis < 3; axis++) {
                // The centre of a face is at 0.5 on the two axes it spans and outside the cube on the
                // axis it faces.
                if (normal[axis] == 0) {
                    assertClose("face " + face + " centre on axis " + axis, 0.5F, centre[axis]);
                } else if (normal[axis] > 0) {
                    assertClose("face " + face + " centre on axis " + axis, 1 + HostShell.EXPAND,
                            centre[axis]);
                } else {
                    assertClose("face " + face + " centre on axis " + axis, -HostShell.EXPAND,
                            centre[axis]);
                }
            }
        }
    }

    @Test
    @DisplayName("the centre panel and the four borders tile a face exactly once")
    void tilesCoverTheFaceWithoutOverlapping() {
        float covered = 0;
        float[][] tiles = new float[HostShell.quadsPerFace(true)][];
        for (int i = 0; i < tiles.length; i++) {
            tiles[i] = HostShell.tile(i, true);
            float width = tiles[i][2] - tiles[i][0];
            float height = tiles[i][3] - tiles[i][1];
            assertTrue(width > 0 && height > 0, "tile " + i + " is empty");
            covered += width * height;
        }
        assertClose("total covered area", 1.0F, covered);

        for (int i = 0; i < tiles.length; i++) {
            for (int j = i + 1; j < tiles.length; j++) {
                boolean disjoint = tiles[i][2] <= tiles[j][0] + EPSILON
                        || tiles[j][2] <= tiles[i][0] + EPSILON
                        || tiles[i][3] <= tiles[j][1] + EPSILON
                        || tiles[j][3] <= tiles[i][1] + EPSILON;
                assertTrue(disjoint, "tiles " + i + " and " + j
                        + " overlap, which would make them z-fight with each other");
            }
        }
    }

    @Test
    @DisplayName("without a border a face is a single full quad")
    void withoutFrameAFaceIsOneQuad() {
        assertEquals(1, HostShell.quadsPerFace(false));
        float[] tile = HostShell.tile(0, false);
        assertEquals(0.0F, tile[0], EPSILON);
        assertEquals(0.0F, tile[1], EPSILON);
        assertEquals(1.0F, tile[2], EPSILON);
        assertEquals(1.0F, tile[3], EPSILON);
    }
}

package com.jiangyuefengyu.redstonecircuit.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.List;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The sight rule, row by row. A {@link BlockPos} is the block's <b>minimum corner</b>, so {@code P = (5, 64,
 * 5)} occupies {@code [5,6) x [64,65) x [5,6)}, and which block a point is in is a floor per axis.
 *
 * <p>The hit column of the table is a <b>point</b>, converted with {@link BlockPos#containing} - the same
 * floor the ray itself reports - so there is exactly one point-to-block conversion and the table cannot drift
 * away from the rule.
 */
class CarriedSightTest {

    /** S1's target block. A minimum corner, so it occupies [5,6) x [64,65) x [5,6). */
    private static final BlockPos P = new BlockPos(5, 64, 5);

    /** An eye that is neither in P nor within one block of it, so rule 1 is false and the ray decides. */
    private static final double EYE_X = 0.5;
    private static final double EYE_Y = 64.5;
    private static final double EYE_Z = 5.5;

    /**
     * One row of the table: the eye, whether the ray met anything, where it met it, the expected answer, and
     * which rule the row is about.
     */
    private record Row(String what, double eyeX, double eyeY, double eyeZ, boolean met,
                       double hitX, double hitY, double hitZ, boolean visible, int rule) {
    }

    /** A row with the far eye. */
    private static Row row(String what, boolean met, double hitX, double hitY, double hitZ,
                           boolean visible, int rule) {
        return new Row(what, EYE_X, EYE_Y, EYE_Z, met, hitX, hitY, hitZ, visible, rule);
    }

    /** S1's numeric table, verbatim. */
    private static final List<Row> S1_TABLE = List.of(
            // Rule 4: 4.5 belongs to the block at (4, ...) - P's near face is exactly x = 5.0 - so this is
            // ANOTHER block, in front of the component.
            row("hit the face of the block in front (4.5)", true, 4.5, 64.5, 5.5, false, 4),
            // Rule 3: the ray landed inside P.
            row("hit P's own near face (5.0)", true, 5.0, 64.5, 5.5, true, 3),
            row("hit inside P (5.4)", true, 5.4, 64.5, 5.5, true, 3),
            row("hit just inside P's far face (5.99)", true, 5.99, 64.5, 5.5, true, 3),
            // Rule 4: 6.0 is the far face, which belongs to the block behind P.
            row("hit P's far face (6.0), which is the next block", true, 6.0, 64.5, 5.5, false, 4),
            // Rule 2.
            row("the ray met nothing", false, 0, 0, 0, true, 2),
            // Rule 1: the eye is inside P.
            new Row("the eye is inside P", 5.5, 64.5, 5.5, false, 0, 0, 0, true, 1),
            // Rule 1: not in P, but within one block of it - and rule 1 is checked BEFORE the ray, so a
            // hit that rule 4 would refuse still passes.
            new Row("the eye is against P (0.9 from its centre)", 4.6, 64.5, 5.5,
                    true, 4.5, 64.5, 5.5, true, 1));

    @Test
    @DisplayName("S1's table, row by row, each row naming the rule it exercises")
    void theTableHolds() {
        for (Row row : S1_TABLE) {
            CarriedSight.Ray ray = row.met()
                    ? CarriedSight.Ray.hit(BlockPos.containing(row.hitX(), row.hitY(), row.hitZ()))
                    : CarriedSight.Ray.miss();
            boolean actual = CarriedSight.isVisible(row.eyeX(), row.eyeY(), row.eyeZ(), P, ray);
            assertEquals(row.visible(), actual, "S1 rule " + row.rule() + ": " + row.what());
        }
    }

    @Test
    @DisplayName("every rule of the table is exercised at least once, so no rule is unpinned")
    void theTableCoversAllFourRules() {
        for (int rule = 1; rule <= 4; rule++) {
            int wanted = rule;
            assertTrue(S1_TABLE.stream().anyMatch(row -> row.rule() == wanted),
                    "S1 rule " + rule + " has no row in the table");
        }
    }

    @Test
    @DisplayName("a hit anywhere inside the component's own block is visible: the cell, not a radius")
    void theComponentOwnBlockIsNotAWall() {
        // P spans [5,6) x [64,65) x [5,6). Every hit below reports P, however far it is from the point the
        // eye aimed at - which is what the world path relies on too (`hit.getBlockPos().equals(pos)`), and
        // what a "distance from the hit to the target point" rule could not express.
        assertTrue(CarriedSight.isVisible(0.5, 64.5, 5.5, P,
                CarriedSight.Ray.hit(new BlockPos(5, 64, 5))), "the block P itself");
        assertTrue(CarriedSight.isVisible(0.5, 64.5, 5.5, P,
                CarriedSight.Ray.hit(BlockPos.containing(5.0, 64.0, 5.0))),
                "P's near corner, which is the same block");
    }

    @Test
    @DisplayName("the rule is per block on every axis: a neighbour up, down or sideways is a wall")
    void neighboursOnEveryAxisAreRefused() {
        assertFalse(CarriedSight.isVisible(0.5, 64.5, 5.5, P,
                CarriedSight.Ray.hit(new BlockPos(4, 64, 5))), "the block towards the eye");
        assertFalse(CarriedSight.isVisible(0.5, 64.5, 5.5, P,
                CarriedSight.Ray.hit(new BlockPos(6, 64, 5))), "the block beyond the component");
        assertFalse(CarriedSight.isVisible(0.5, 64.5, 5.5, P,
                CarriedSight.Ray.hit(new BlockPos(5, 65, 5))), "the block above it");
        assertFalse(CarriedSight.isVisible(0.5, 64.5, 5.5, P,
                CarriedSight.Ray.hit(new BlockPos(5, 63, 5))), "the block below it");
        assertFalse(CarriedSight.isVisible(0.5, 64.5, 5.5, P,
                CarriedSight.Ray.hit(new BlockPos(5, 64, 4))), "the block to one side of it");
        assertTrue(CarriedSight.isVisible(0.5, 64.5, 5.5, P,
                CarriedSight.Ray.hit(new BlockPos(5, 64, 5))), "and the component's own block");
    }

    @Test
    @DisplayName("rule 1 is the eye's proximity, checked before the ray")
    void ruleOneComesFirst() {
        // The same wall hit that rule 4 refuses, with the eye close enough to be against P instead.
        assertFalse(CarriedSight.isVisible(0.5, 64.5, 5.5, P,
                CarriedSight.Ray.hit(new BlockPos(4, 64, 5))), "far eye: refused");
        assertTrue(CarriedSight.isVisible(4.6, 64.5, 5.5, P,
                CarriedSight.Ray.hit(new BlockPos(4, 64, 5))),
                "eye 0.9 blocks from P's centre: S1 rule 1, so the ray is never consulted");
        assertTrue(CarriedSight.isVisible(5.5, 64.5, 5.5, P,
                CarriedSight.Ray.hit(new BlockPos(4, 64, 5))), "eye inside P");
    }

    @Test
    @DisplayName("S1 rule 1's boundary: one block from the centre, exclusive")
    void ruleOnesBoundary() {
        // The eye is exactly one block from P's centre here, so rule 1 does NOT apply - and the two
        // assertions below separate the two rules that remain.
        assertTrue(CarriedSight.isVisible(4.5, 64.5, 5.5, P, CarriedSight.Ray.miss()),
                "rule 1 does not apply at exactly one block, but a miss is rule 2: visible");
        assertFalse(CarriedSight.isVisible(4.5, 64.5, 5.5, P,
                CarriedSight.Ray.hit(new BlockPos(4, 64, 5))),
                "with a block in front and the eye not in P: refused - which also proves the boundary is "
                        + "strict, because rule 1 would have said visible");
        assertTrue(CarriedSight.isVisible(4.51, 64.5, 5.5, P,
                CarriedSight.Ray.hit(new BlockPos(4, 64, 5))),
                "0.99 from the centre: rule 1 applies and the block in front does not matter");
    }

    @Test
    @DisplayName("a miss is a rule, not a missing value: Ray.hit cannot be handed null")
    void aMissIsNotANullBlock() {
        assertThrows(NullPointerException.class, () -> CarriedSight.Ray.hit(null),
                "'the ray met nothing' has its own shape (Ray.miss()); a null block would be "
                        + "indistinguishable from 'nobody asked'");
        assertFalse(CarriedSight.Ray.miss().met());
        assertTrue(CarriedSight.Ray.hit(P).met());
    }

    /**
     * <b>The union principle: this rule may only widen what the rule it replaced drew.</b> Every geometry the
     * replaced rule drew, this one must draw as well - and at least one it refused is now drawn.
     */
    @Test
    @DisplayName("the union principle: everything the old rule drew, this rule draws too")
    void theOldRuleIsASubsetOfThisOne() {
        double cx = P.getX() + 0.5;
        double cy = P.getY() + 0.5;
        double cz = P.getZ() + 0.5;
        List<BlockPos> blocks = List.of(P, P.east(), P.west(), P.above(), P.below(), P.north(),
                P.south());
        double[][] eyes = {
                {5.4, 64.5, 5.5},   // 0.1 from P's centre: rule 1, and the old rule's own early exit
                {5.1, 64.5, 5.5},   // 0.4
                {5.0, 64.5, 5.5},   // 0.5, exactly on the old rule's boundary case
                {4.6, 64.5, 5.5},   // 0.9: rule 1 still, old rule still passes
                {3.5, 64.5, 5.5},   // 2.0, axis aligned
                {0.5, 64.5, 5.5},   // 5.0, the eye of the table above
                {5.5, 65.5, 5.5},   // 1.0 above the centre, looking down
                {5.5, 60.5, 5.5},   // below it
                {5.5, 64.5, 0.5},   // along Z
                {8.5, 64.5, 5.5},   // from the other side
                {4.0, 64.5, 4.0},   // oblique, entering near an edge
                {2.5, 66.5, 8.5},   // oblique, three axes at once
        };
        int examined = 0;
        int oldDrew = 0;
        int widened = 0;
        for (double[] eye : eyes) {
            double ex = eye[0];
            double ey = eye[1];
            double ez = eye[2];
            double dx = cx - ex;
            double dy = cy - ey;
            double dz = cz - ez;
            double targetD2 = dx * dx + dy * dy + dz * dz;
            double targetD = Math.sqrt(targetD2);
            if (targetD < 1.0E-9) {
                continue;
            }
            double ux = dx / targetD;
            double uy = dy / targetD;
            double uz = dz / targetD;
            for (BlockPos block : blocks) {
                double t = entryDistance(ex, ey, ez, ux, uy, uz, block);
                if (Double.isNaN(t) || t > targetD) {
                    continue;
                }
                double hx = ex + t * ux;
                double hy = ey + t * uy;
                double hz = ez + t * uz;
                if (!BlockPos.containing(hx, hy, hz).equals(block)) {
                    // The entry point landed on the far face of `block`, which belongs to the next block:
                    // the ray would report that one, not this one.
                    continue;
                }
                examined++;
                double hitD2 = (hx - ex) * (hx - ex) + (hy - ey) * (hy - ey) + (hz - ez) * (hz - ez);
                boolean oldDrewThis = targetD2 < 1.0E-4 || hitD2 >= targetD2 - 0.25;
                boolean nowDrawn = CarriedSight.isVisible(ex, ey, ez, P, CarriedSight.Ray.hit(block));
                // The carried form adds one equality and nothing else, so it can only draw more: passing a
                // host's own coordinate must not refuse anything the ordinary question drew.
                if (nowDrawn) {
                    BlockPos ownFrame = new BlockPos(20481032, 127, 20483080);
                    assertTrue(CarriedSight.isVisible(ex, ey, ez, P, CarriedSight.Ray.hit(block), ownFrame),
                            "the carried form refused a geometry the ordinary form drew - the one "
                                    + "direction a visibility change is never allowed to move");
                }
                if (oldDrewThis) {
                    oldDrew++;
                    assertTrue(nowDrawn, "the old rule drew this geometry and S1 refuses it: eye=(" + ex
                            + ", " + ey + ", " + ez + ") hit " + block + " at " + hx + ", " + hy + ", "
                            + hz + " (targetD=" + targetD + ", hitD=" + Math.sqrt(hitD2) + ")");
                } else if (nowDrawn) {
                    widened++;
                }
            }
        }
        assertTrue(examined > 0, "the sweep examined no geometry at all");
        assertTrue(oldDrew > 0, "no geometry in the sweep was drawn by the old rule, so the subset "
                + "assertion above proves nothing");
        assertTrue(widened > 0, "no geometry in the sweep changed from refused to drawn, so this test "
                + "would also pass on a rule that is merely the old one renamed");
    }

    /**
     * <b>"The component's own block" has two names for a Sable host.</b> The ray reports a sub-level hit in the
     * structure's own frame while the target is a projected world block, so the machine was its own occluder.
     *
     * <p>The component's own block in <b>either</b> frame passes (rule 3), and a <b>different</b> block of the
     * same structure - the machine's hull in front of the component - still refuses (rule 4).
     */
    @Test
    @DisplayName("the component's own block passes in either frame; another block of the structure still refuses")
    void theComponentsOwnBlockIsRecognisedInItsOwnFrame() {
        double ex = -61.5;
        double ey = -58.5;
        double ez = -312.5;                              // the eye, in the frame the ray reports in
        BlockPos projected = new BlockPos(-58, -57, -311);      // the target the projection names
        BlockPos ownPlot = new BlockPos(20481032, 127, 20483080);   // the component in the structure's frame
        BlockPos ownNeighbour = new BlockPos(20481032, 128, 20483080);

        // The ray's own frame: refused by the world frame alone, drawn now.
        assertFalse(CarriedSight.isVisible(ex, ey, ez, projected, CarriedSight.Ray.hit(ownPlot)),
                "asked in the world frame alone, the component's own block is an unknown block");
        assertTrue(CarriedSight.isVisible(ex, ey, ez, projected, CarriedSight.Ray.hit(ownPlot), ownPlot),
                "asked in the frame the ray answered in, it is the component's own block - rule 3");

        // The other half, so this cannot be mistaken for "anything in plot space passes": a DIFFERENT
        // block of the same structure is in front of the component and must still hide it.
        assertFalse(CarriedSight.isVisible(ex, ey, ez, projected,
                        CarriedSight.Ray.hit(ownNeighbour), ownPlot),
                "the machine's own hull in front of the component is still in front of it");

        // And the world frame still works exactly as before, for the hosts that have only that one.
        assertTrue(CarriedSight.isVisible(ex, ey, ez, projected,
                CarriedSight.Ray.hit(projected), ownPlot),
                "a hit on the projected block is rule 3 in the other frame");
    }

    /**
     * The distance from {@code (ox, oy, oz)} along the unit direction {@code (ux, uy, uz)} at which the ray
     * enters {@code block}, or {@link Double#NaN} when it never does: the slab method, so geometry decides.
     */
    private static double entryDistance(double ox, double oy, double oz, double ux, double uy, double uz,
                                        BlockPos block) {
        double[] origin = {ox, oy, oz};
        double[] unit = {ux, uy, uz};
        double[] low = {block.getX(), block.getY(), block.getZ()};
        double enter = 0.0;
        double exit = Double.POSITIVE_INFINITY;
        for (int axis = 0; axis < 3; axis++) {
            if (Math.abs(unit[axis]) < 1.0E-12) {
                if (origin[axis] < low[axis] || origin[axis] >= low[axis] + 1.0) {
                    return Double.NaN;
                }
                continue;
            }
            double first = (low[axis] - origin[axis]) / unit[axis];
            double second = (low[axis] + 1.0 - origin[axis]) / unit[axis];
            if (first > second) {
                double swap = first;
                first = second;
                second = swap;
            }
            enter = Math.max(enter, first);
            exit = Math.min(exit, second);
            if (enter > exit) {
                return Double.NaN;
            }
        }
        return enter;
    }

    @Test
    @DisplayName("no tolerance of ours: the class declares exactly one double, and it is the eye rule")
    void noDistanceSphereAndNoTolerance() throws Exception {
        // The "no floating-point allowance" requirement as a property of the class: the only double it
        // declares is S1 rule 1's one block, so an added tolerance fails this.
        List<String> doubles = new java.util.ArrayList<>();
        for (Field field : CarriedSight.class.getDeclaredFields()) {
            if (field.getType() == double.class) {
                doubles.add(field.getName());
            }
        }
        assertEquals(List.of("EYE_NEAR"), doubles,
                "the only double in the rule is S1 rule 1's eye proximity; any other is a tolerance");
        assertEquals(1.0, CarriedSight.EYE_NEAR, 0.0, "S1 says one block");
    }
}

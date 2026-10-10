package com.jiangyuefengyu.redstonecircuit.carrier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import net.minecraft.core.BlockPos;

import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests the one measurement that separates "nothing was drawn" from "something was drawn somewhere else".
 *
 * <p>这个类出现之前，每个计数器在"组件看不见"时都报一次<em>成功</em>的绘制: quad 确实发出去了，只是在两千万格外的
 * plot 坐标上。所以要分清三种答案: 画在结构所在处（无论结构飞得多远）都不是故障；画在离结构几百万格处是故障；姿态
 * 还没到则两者都不是 - "无法比较"是另一个答案，把它当成故障会让每次组装都报警。
 */
class CarrierDrawCheckTest {

    private static final UUID SUB_LEVEL = UUID.fromString("4e5560da-0000-0000-0000-000000000001");

    /** A Create-shaped carrier: small local coordinates, anchor-relative. */
    private static CarrierDrawCheck contraption(Vector3d drawWorld, Vector3d structureWorld) {
        return CarrierDrawCheck.of(CarrierId.contraption(UUID.randomUUID()), new BlockPos(1, 2, 3),
                drawWorld, structureWorld);
    }

    /** The same, with the age of the pose the comparison was made from. */
    private static CarrierDrawCheck aged(Vector3d drawWorld, Vector3d structureWorld, long ageTicks) {
        return CarrierDrawCheck.of(CarrierId.contraption(UUID.randomUUID()), new BlockPos(1, 2, 3),
                drawWorld, structureWorld, ageTicks);
    }

    @Test
    @DisplayName("a host drawn at its structure's own position is not a fault, however far that is")
    void drawnWhereTheStructureIsIsNotAFault() {
        // The structure is a thousand blocks from the world origin, and the host is inside it. The
        // question this class asks is about the gap between the two, never about distance from zero -
        // a machine that has flown across the map is not a bug.
        Vector3d structure = new Vector3d(2775.5, 77.5, -1394.5);
        CarrierDrawCheck check = contraption(new Vector3d(2776.0, 77.5, -1394.0), structure);

        assertNotNull(check.deltaBlocks());
        assertEquals(Math.sqrt(0.5), check.deltaBlocks(), 1.0E-9);
        assertFalse(check.drawnFarFromStructure());
        assertFalse(check.explainsInvisibleComponent());
        assertTrue(check.describe().contains("OK-position (the component is drawn where the pose says"),
                check.describe());
    }

    @Test
    @DisplayName("a zero delta from a four-second-old pose is called out, not reported as OK")
    void aStalePoseIsNamedEvenThoughTheDeltaIsZero() {
        // The bearing was turning on the spot: the pivot never moved, so every frame's `delta` was 0.000 and
        // every line said OK - while the client drew an angle the server had stopped believing in four seconds
        // earlier. A `delta` of zero describes the pose's internal consistency, not its age.
        Vector3d structure = new Vector3d(2775.5, 77.5, -1394.5);
        CarrierDrawCheck stale = aged(structure, structure, 80L);

        assertEquals(0.0, stale.deltaBlocks(), 1.0E-9);
        assertFalse(stale.drawnFarFromStructure(), "the arithmetic is right; that is the whole problem");
        assertTrue(stale.poseIsStale(), "80 ticks is four seconds and must be called stale");
        assertEquals(4.0, stale.poseAgeSeconds(), 1.0E-9);

        String line = stale.describe();
        assertTrue(line.contains("delta=0.000"), line);
        assertTrue(line.contains("poseAge=80t/"), line);
        assertTrue(line.contains("STALE POSE"), line);
        assertTrue(line.contains("does not mean the component is following"), line);
    }

    @Test
    @DisplayName("the staleness threshold is above the heartbeat, so a parked machine never trips it")
    void freshAndUnknownPosesAreNotCalledStale() {
        Vector3d structure = new Vector3d(1.0, 2.0, 3.0);
        // The default heartbeat is twenty ticks, so a parked machine's pose is at most that old. The
        // warning threshold has to sit above it or every parked machine would shout.
        assertFalse(aged(structure, structure, 0L).poseIsStale(), "the pose arrived this tick");
        assertFalse(aged(structure, structure, 20L).poseIsStale(),
                "20 ticks IS the default heartbeat - a parked machine must not be called stale");
        assertFalse(aged(structure, structure, CarrierDrawCheck.POSE_AGE_WARN_TICKS).poseIsStale(),
                "the boundary itself is still fresh");
        assertTrue(aged(structure, structure, CarrierDrawCheck.POSE_AGE_WARN_TICKS + 1).poseIsStale());
        assertTrue(CarrierDrawCheck.POSE_AGE_WARN_TICKS > 20,
                "the warning must not fire on an ordinary heartbeat");
        assertTrue(CarrierDrawCheck.POSE_AGE_WARN_TICKS < 80,
                "and it has to fire well before four seconds of silence, or a stale pose would be missed");

        // "We do not know the age" is a third answer and must not be read as either.
        CarrierDrawCheck unknownAge = CarrierDrawCheck.of(CarrierId.subLevel(SUB_LEVEL),
                new BlockPos(1, 2, 3), structure, structure);
        assertEquals(-1L, unknownAge.poseAgeTicks());
        assertFalse(unknownAge.poseIsStale());
        assertTrue(unknownAge.describe().contains("poseAge=?"), unknownAge.describe());
    }

    @Test
    @DisplayName("a host drawn at a plot coordinate is a fault, and the line says which one")
    void drawnAtAPlotCoordinateIsAFault() {
        // The local coordinate is a Sable plot coordinate and the pose that was sent is not the one it belongs
        // to, so the arithmetic lands twenty million blocks away - the state in which `drawnLastFrame=1` while
        // the player sees nothing at all.
        CarrierDrawCheck check = new CarrierDrawCheck(CarrierId.subLevel(SUB_LEVEL),
                new BlockPos(20_481_033, 132, 20_485_129),
                new Vector3d(20_481_033.5, 132.5, 20_485_129.5),
                new Vector3d(-352.5, 171.5, 92.8), -1L);

        assertTrue(check.drawnFarFromStructure());
        assertTrue(check.explainsInvisibleComponent());
        assertTrue(check.plotSpaceLocal());
        double delta = check.deltaBlocks();
        assertTrue(delta > 20_000_000.0, "delta was " + delta);
        String line = check.describe();
        assertTrue(line.contains("computedWorld=(20481033.500, 132.500, 20485129.500)"), line);
        assertTrue(line.contains("structureWorld=(-352.500, 171.500, 92.800)"), line);
        assertTrue(line.contains("plotSpaceLocal=true"), line);
        assertTrue(line.contains("OFF-BY-MORE-THAN-4.000-BLOCKS"), line);
    }

    @Test
    @DisplayName("a Sable carrier drawn where it is reports plotSpaceLocal without being called a fault")
    void plotSpaceIsAFactNotAFault() {
        // This is the healthy Sable case, and it has to read as healthy: the local coordinate really is
        // in the plot range, and the pose that came with it really does put it back on the machine.
        // `plotSpace=true, offStructure=0` is the pair an operator should expect to see for a sub-level.
        CarrierDrawCheck check = new CarrierDrawCheck(CarrierId.subLevel(SUB_LEVEL),
                new BlockPos(20_481_033, 132, 20_485_129),
                new Vector3d(-352.3, 171.6, 92.9),
                new Vector3d(-352.5, 171.5, 92.8), 3L);

        assertTrue(check.plotSpaceLocal());
        assertFalse(check.drawnFarFromStructure());
        assertFalse(check.explainsInvisibleComponent());
        assertFalse(check.poseIsStale());
        assertTrue(check.describe().contains("plotSpaceLocal=true"));
        assertTrue(check.describe().contains("OK-position"), check.describe());
    }

    @Test
    @DisplayName("no pose and no bounds are both 'cannot compare', never a fault")
    void unknownIsNotAFault() {
        CarrierDrawCheck noPose = new CarrierDrawCheck(CarrierId.subLevel(SUB_LEVEL),
                new BlockPos(20_481_033, 132, 20_485_129), null, new Vector3d(), -1L);
        assertNull(noPose.deltaBlocks());
        assertFalse(noPose.drawnFarFromStructure());
        assertFalse(noPose.explainsInvisibleComponent());
        assertTrue(noPose.describe().contains("cannot compare: no pose"));

        CarrierDrawCheck noBounds = contraption(new Vector3d(1.0, 1.0, 1.0), null);
        assertNull(noBounds.deltaBlocks());
        assertFalse(noBounds.drawnFarFromStructure());
        assertTrue(noBounds.describe().contains("the structure did not report a position"));

        CarrierDrawCheck nothing = CarrierDrawCheck.unknown(null);
        assertNull(nothing.carrier());
        assertNull(nothing.localPos());
        assertFalse(nothing.plotSpaceLocal());
        assertTrue(nothing.describe().contains("carrier=-"));
        assertTrue(nothing.describe().contains("local=-"));
    }

    @Test
    @DisplayName("the tolerance is a whole-machine tolerance: four blocks is fine, five is not")
    void toleranceSeparatesABigMachineFromAWrongSpace() {
        // The tolerance has to be generous enough that a component a few blocks from a big machine's
        // pivot is not called a fault - otherwise the check would cry wolf on every large contraption
        // and the one number that matters would be lost in the noise.
        Vector3d origin = new Vector3d(10.0, 64.0, 10.0);
        assertFalse(contraption(new Vector3d(14.0, 64.0, 10.0), origin).drawnFarFromStructure());
        assertTrue(contraption(new Vector3d(14.1, 64.0, 10.0), origin).drawnFarFromStructure());
        assertEquals(4.0, CarrierDrawCheck.OFF_TOLERANCE_BLOCKS, 1.0E-9);
    }

    @Test
    @DisplayName("the plot-space test is about the coordinates, and a small local coordinate is not it")
    void plotSpaceThreshold() {
        // A Create contraption's local coordinates are bounded by the machine's own size, so the
        // threshold only has to sit between "a very large machine" and "a plot". One hundred thousand
        // is nine orders of magnitude from a plot coordinate and three from any real machine.
        CarrierDrawCheck small = contraption(new Vector3d(), new Vector3d());
        assertFalse(small.plotSpaceLocal());

        CarrierDrawCheck boundary = new CarrierDrawCheck(CarrierId.subLevel(SUB_LEVEL),
                new BlockPos(CarrierDrawCheck.PLOT_SPACE_THRESHOLD, 0, 0), new Vector3d(),
                new Vector3d(), -1L);
        assertTrue(boundary.plotSpaceLocal());

        CarrierDrawCheck negative = new CarrierDrawCheck(CarrierId.subLevel(SUB_LEVEL),
                new BlockPos(-CarrierDrawCheck.PLOT_SPACE_THRESHOLD, 0, 0), new Vector3d(),
                new Vector3d(), -1L);
        assertTrue(negative.plotSpaceLocal(), "a mirrored plot coordinate is still a plot coordinate");

        // The `y` and `z` axes count too: a plot is big in all three, and a check that only looked at
        // `x` would miss the case where the huge number happens to be the vertical one.
        CarrierDrawCheck vertical = new CarrierDrawCheck(CarrierId.subLevel(SUB_LEVEL),
                new BlockPos(0, CarrierDrawCheck.PLOT_SPACE_THRESHOLD + 1, 0), new Vector3d(),
                new Vector3d(), -1L);
        assertTrue(vertical.plotSpaceLocal());
    }

    @Test
    @DisplayName("a rotated pose is compared through the same arithmetic the renderer uses")
    void rotationIsAccountedFor() {
        // The comparison must be of world points, not of coordinates: a machine turned ninety degrees
        // puts a host at a different world point for the same local coordinate, and the draw check has
        // to follow the pose rather than the axes.
        Quaterniond turned = new Quaterniond().rotateY(Math.PI / 2.0);
        CarrierPose pose = new CarrierPose(new Vector3d(0.5, 0.5, 0.5), turned,
                new Vector3d(0.5, 0.5, 0.5), 1.0);
        Vector3d drawn = CarrierPose.blockCentreOf(pose, new BlockPos(3, 0, 0));
        CarrierDrawCheck check = new CarrierDrawCheck(CarrierId.contraption(UUID.randomUUID()),
                new BlockPos(3, 0, 0), drawn, new Vector3d(0.5, 0.5, 0.5), 1L);

        // The block at local (3, 0, 0) has its corner three blocks along local +X and its centre half a
        // block further; the pose's pivot is that same half block, so the centre is 3.0 blocks from the
        // pivot. A quarter turn about Y sends +X to -Z, and the pose origin sits at +0.5 on every axis,
        // which puts the drawn point at z = 0.5 - 3.0. Allocating this by hand rather than calling the
        // same method the check calls is the point - it pins the convention.
        assertEquals(0.5, drawn.x, 1.0E-6);
        assertEquals(0.5, drawn.y, 1.0E-6);
        assertEquals(-2.5, drawn.z, 1.0E-6);

        // The check's own delta is that displacement measured from the pose origin the caller handed it:
        // three blocks, which is inside the tolerance, so the verdict is "not far".
        assertEquals(3.0, check.deltaBlocks(), 1.0E-6);
        assertFalse(check.drawnFarFromStructure());
    }
}

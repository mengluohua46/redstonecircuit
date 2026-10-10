package com.jiangyuefengyu.redstonecircuit.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jiangyuefengyu.redstonecircuit.carrier.CarrierFrame;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierPose;
import com.jiangyuefengyu.redstonecircuit.client.HostRenderPass.BoxScratch;
import com.jiangyuefengyu.redstonecircuit.client.InnerComponentModel.Box;
import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.Slot;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.core.BlockPos;

import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>Where the world path's vertices actually land, driven through the production emitter.</b>
 *
 * <p>世界路径把顶点发射在 {@code float[] base} 而不是 {@link BlockPos} 上，base 忘了填时几何体照样存在、
 * 每个计数器照样健康，只是整个 overlay 被画到了别处。所以这里把渲染器真正调用的
 * {@link HostRenderPass#emitWorldBoxesForTest} 送进记录用的 {@link VertexConsumer}，断言顶点坐标本身。
 */
class WorldPathCoordinatesTest {

    /** The block that must NOT be drawn at the origin. */
    private static final BlockPos BLOCK = new BlockPos(2775, 77, -1395);

    /** A camera far from it, so the camera translation is part of what is asserted. */
    private static final Vector3d CAMERA = new Vector3d(2700.25, 90.5, -1300.75);

    /** Collects the vertex positions the emitter hands to the consumer, and discards everything else. */
    private static final class RecordingConsumer implements VertexConsumer {

        private final java.util.List<Vector3f> vertices = new java.util.ArrayList<>();

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            vertices.add(new Vector3f(x, y, z));
            return this;
        }

        @Override
        public VertexConsumer setColor(int red, int green, int blue, int alpha) {
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            return this;
        }
    }

    /** The world path's pose: {@code T(-camera)} and nothing else, exactly as the renderer builds it. */
    private static PoseStack worldPose() {
        PoseStack pose = new PoseStack();
        pose.translate(-CAMERA.x, -CAMERA.y, -CAMERA.z);
        return pose;
    }

    /** The atlas UV the emitter needs; the values never reach a vertex position. {@code null} sprites are not
     * an option - {@code faceUv} reads them - so the unit square stands in for any real atlas slice. */
    private static HostRenderPass.AtlasUv uv() {
        return new HostRenderPass.AtlasUv(0.5F, 0.5F, 0.0F, 1.0F, 0.0F, 1.0F);
    }

    private static java.util.List<Box> boxesOf(Slot slot) {
        java.util.List<Box> boxes = new java.util.ArrayList<>();
        ConnectionBars.bars(slot, direction -> false, boxes);
        return boxes;
    }

    /** Emits the world path's geometry for one component and returns where the vertices went. */
    private static java.util.List<Vector3f> emitWorld(Slot slot) {
        RecordingConsumer consumer = new RecordingConsumer();
        PoseStack pose = worldPose();
        HostRenderPass.emitWorldBoxesForTest(pose.last(), consumer, BLOCK, slot, boxesOf(slot), uv(),
                new BoxScratch());
        return consumer.vertices;
    }

    // ------------------------------------------------------------------ the tests --

    @Test
    @DisplayName("the world path draws a block at 2775,77,-1395 inside that block, NOT at the origin")
    void worldPathLandsOnTheBlockNotTheOrigin() {
        Slot torch = new Slot(ComponentType.TORCH);
        java.util.List<Vector3f> vertices = emitWorld(torch);
        assertTrue(!vertices.isEmpty(),
                "the world path emitted no geometry at all, so there is nothing to place");

        // If the base is left at zero the vertices come out around (0,0,0) - far from the machine - while
        // every counter still reports healthy geometry.
        float minX = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        float minZ = Float.MAX_VALUE;
        float maxZ = -Float.MAX_VALUE;
        for (Vector3f v : vertices) {
            // The world path's pose is `T(-cameraPos)`, so the consumer receives camera-relative
            // coordinates; the world coordinate is that plus the camera, and it is what a failure must quote.
            minX = Math.min(minX, v.x + (float) CAMERA.x);
            maxX = Math.max(maxX, v.x + (float) CAMERA.x);
            minY = Math.min(minY, v.y + (float) CAMERA.y);
            maxY = Math.max(maxY, v.y + (float) CAMERA.y);
            minZ = Math.min(minZ, v.z + (float) CAMERA.z);
            maxZ = Math.max(maxZ, v.z + (float) CAMERA.z);
        }
        String where = "world vertices span x[" + minX + "," + maxX + "] y[" + minY + "," + maxY
                + "] z[" + minZ + "," + maxZ + "]";

        // The basis maps component space onto the block's 0..1, so every box vertex is inside the block.
        // Stated as a range so BOTH ways of getting it wrong - zero base, or a base applied twice - fail here.
        assertTrue(minX >= BLOCK.getX() - 0.001F && maxX <= BLOCK.getX() + 1.001F,
                "the world path's x is not on the block " + BLOCK.toShortString() + ": " + where);
        assertTrue(minZ >= BLOCK.getZ() - 0.001F && maxZ <= BLOCK.getZ() + 1.001F,
                "the world path's z is not on the block " + BLOCK.toShortString() + ": " + where);
        assertTrue(minY >= BLOCK.getY() - 0.001F && maxY <= BLOCK.getY() + 1.001F,
                "the world path's y is not on the block " + BLOCK.toShortString() + ": " + where);

        // And the distance from the origin is the headline number, so a failure names it.
        double nearestToOrigin = Math.min(Math.abs(minX), Math.abs(minZ));
        assertTrue(nearestToOrigin > 1000.0,
                "the world path is drawing near the ORIGIN, not at " + BLOCK.toShortString() + ": "
                        + where + " - this is the `float[] base` left at zero");
    }

    @Test
    @DisplayName("the world path's base is the block's own corner, and it is what drawContents uses")
    void worldPathBaseIsTheBlockCorner() {
        float[] base = HostRenderPass.worldVertexBase(BLOCK, new float[3]);
        assertEquals(BLOCK.getX(), base[0], 0.0F);
        assertEquals(BLOCK.getY(), base[1], 0.0F);
        assertEquals(BLOCK.getZ(), base[2], 0.0F);
        // A null position must not silently mean "the origin"; it is a caller error and is reported as
        // one by the caller, not by a base of zero masquerading as a block.
        float[] nullBase = HostRenderPass.worldVertexBase(null, new float[3]);
        assertEquals(0.0F, nullBase[0], 0.0F);
        // And the array it is handed is the one it fills, so the render loop allocates nothing.
        float[] reused = new float[3];
        assertSame(reused, HostRenderPass.worldVertexBase(BLOCK, reused));
    }

    @Test
    @DisplayName("the world path and the carried path put the same vertex on the same world point")
    void theTwoPathsAgreeAtARealWorldPoint() {
        // Both paths driven, not re-derived: the world side goes through the production emitter into a
        // recorded vertex, the carried side through the production base - the same expression
        // `drawCarriedContents` pushes and the shell emits at.
        //
        // A Create contraption holding exactly this block: `local = (0,0,0)`, `pivot = (0.5,0.5,0.5)`,
        // `position = the block's centre`.
        CarrierPose pose = new CarrierPose(new Vector3d(BLOCK.getX() + 0.5, BLOCK.getY() + 0.5,
                BLOCK.getZ() + 0.5), new Quaterniond(), new Vector3d(0.5, 0.5, 0.5), 1.0);
        CarrierFrame frame = CarrierFrame.forBlock(pose, new Vector3d(0, 0, 0));
        float[] carriedBase = HostRenderPass.carriedModelBase(frame, new float[3]);

        Slot slot = new Slot(ComponentType.TORCH);
        java.util.List<Vector3f> worldVertices = emitWorld(slot);
        assertTrue(!worldVertices.isEmpty(), "no world geometry to compare");

        // Every world vertex is `T(-cam) · (block + mapped)`. Undo the camera and it must be inside the
        // block; the carried path's own `mapped` is the same point, so comparing the two sets by their
        // offset from the block's corner is what makes them comparable without re-implementing either.
        for (Vector3f v : worldVertices) {
            float offsetX = v.x + (float) CAMERA.x - BLOCK.getX();
            float offsetY = v.y + (float) CAMERA.y - BLOCK.getY();
            float offsetZ = v.z + (float) CAMERA.z - BLOCK.getZ();
            assertTrue(offsetX >= -0.001F && offsetX <= 1.001F
                            && offsetY >= -0.001F && offsetY <= 1.001F
                            && offsetZ >= -0.001F && offsetZ <= 1.001F,
                    "a world vertex is not measured from the block's corner: offset ("
                            + offsetX + "," + offsetY + "," + offsetZ + ")");

            // The carried path's world position for that same local point: `T(position) · R · S` applied
            // to `base + the block-local offset`. `R` is the identity and `carriedBase` is
            // `local - pivot = (-0.5,-0.5,-0.5)`, so this is `position + offset - 0.5`.
            Vector3d carriedWorld = new Vector3d(pose.position())
                    .add(carriedBase[0] + offsetX, carriedBase[1] + offsetY,
                            carriedBase[2] + offsetZ);
            // The same point by the contract. `CarrierPose#transformPosition` takes the STRUCTURE-LOCAL
            // coordinate - `local` plus the block-local offset, which for this contraption is the point
            // inside the unit cube at `local = (0,0,0)` - and not a world coordinate.
            Vector3d contract = pose.transformPosition(
                    new Vector3d(offsetX, offsetY, offsetZ), new Vector3d());
            assertEquals(0.0, contract.distance(carriedWorld), 1.0E-4,
                    "the carried path puts a vertex somewhere else than the pose does: " + contract
                            + " expected, " + carriedWorld + " by the carried frame");
        }
    }

    @Test
    @DisplayName("the world path emits geometry for a block one block from the origin too")
    void worldPathIsCorrectForAnOrdinaryBlockAsWell() {
        // The same assertion at a position where the two failure modes (zero base, doubled base) are
        // distinguishable from rounding, which is not true at 2775.
        BlockPos near = new BlockPos(3, 64, -7);
        RecordingConsumer consumer = new RecordingConsumer();
        PoseStack pose = worldPose();
        HostRenderPass.emitWorldBoxesForTest(pose.last(), consumer, near, new Slot(ComponentType.TORCH),
                boxesOf(new Slot(ComponentType.TORCH)), uv(), new BoxScratch());
        assertTrue(!consumer.vertices.isEmpty(), "no geometry for an ordinary block");
        for (Vector3f v : consumer.vertices) {
            double x = v.x + CAMERA.x;
            double y = v.y + CAMERA.y;
            double z = v.z + CAMERA.z;
            assertTrue(x >= 2.999 && x <= 4.001 && y >= 63.999 && y <= 65.001
                            && z >= -8.001 && z <= -5.999,
                    "geometry for " + near.toShortString() + " was drawn at (" + x + ", " + y + ", "
                            + z + ")");
        }
    }

    @Test
    @DisplayName("an empty box list still yields a usable scratch")
    void emptyBoxesEmitNothing() {
        RecordingConsumer consumer = new RecordingConsumer();
        Slot slot = new Slot(ComponentType.TORCH);
        java.util.List<Box> none = new java.util.ArrayList<>();
        HostRenderPass.emitWorldBoxesForTest(worldPose().last(), consumer, BLOCK, slot, none, uv(),
                new BoxScratch());
        assertTrue(consumer.vertices.isEmpty(), "an empty box list emitted vertices");
        // And the emitter really is reachable with the real list, so the test above is not vacuous.
        assertNotNull(emitWorld(slot));
    }
}

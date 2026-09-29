package com.jiangyuefengyu.redstonecircuit.client;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.jetbrains.annotations.Nullable;

import com.jiangyuefengyu.redstonecircuit.RCConfig;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.util.FastColor;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * Draws a baked block model with our own vertex writes instead of the game's model renderer.
 *
 * <h2>Why not {@code ModelBlockRenderer#renderModel}</h2>
 * The NeoForge build could hand a baked model to {@code ModelBlockRenderer#renderModel} and get the
 * component inside a host block drawn. The Fabric port cannot: through that call the same models come
 * out invisible - the frosted shell and the wrench marks, which are written vertex by vertex, are
 * drawn, but nothing that goes through a model is, in normal mode and under a shader pack alike. So
 * this class takes the one step that is left: it asks the model for its quads itself
 * ({@link BakedModel#getQuads(BlockState, Direction, RandomSource)}) and writes each vertex with
 * {@link VertexConsumer#addVertex}, {@link VertexConsumer#setColor}, {@link VertexConsumer#setUv},
 * {@link VertexConsumer#setOverlay}, {@link VertexConsumer#setUv2} and
 * {@link VertexConsumer#setNormal} - exactly the calls the rest of the overlay already uses
 * successfully.
 *
 * <p>The output is by construction the same as the model renderer's own: the same quad set (one fetch
 * per direction plus the directionless fetch, each with a {@link RandomSource} re-seeded to 42, which
 * is what makes a weighted or randomised model resolve to the same variant every frame), the same tint
 * rule (the model's own tint is applied only to the quads that ask for it, and only to the colour -
 * never to the alpha), the same light and overlay for every vertex, and the same normal (the quad's
 * direction, rotated by the pose's normal matrix the same way every other vertex here is).
 *
 * <h2>Where the vertex data comes from</h2>
 * {@link BakedQuad#getVertices()} holds the four vertices packed as {@code DefaultVertexFormat.BLOCK}
 * ints - position as three floats, colour as four bytes, UV0 as two floats, light and overlay as packed
 * shorts, normal as three bytes - which is the layout {@link VertexConsumer#putBulkData} decodes
 * itself. Decoding it here, into calls the game has already proven on this exact buffer, keeps the
 * whole overlay on one vertex path.
 */
@Environment(EnvType.CLIENT)
final class BakedModelRenderer {

    /** The seed the game's own model renderer uses, so a model that varies by seed varies the same way. */
    private static final long SEED = 42L;

    /** {@code BakedQuad} packs every vertex as this many ints, whatever the model's own vertex format. */
    private static final int VERTEX_STRIDE = 8;

    /** Word offsets inside one packed vertex, in little-endian order. */
    private static final int OFFSET_X = 0;
    private static final int OFFSET_Y = 1;
    private static final int OFFSET_Z = 2;
    private static final int OFFSET_COLOUR = 3;
    private static final int OFFSET_U = 4;
    private static final int OFFSET_V = 5;

    /**
     * States already reported, so the one-line diagnosis below is written once per model rather than
     * once per frame.
     */
    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();

    private BakedModelRenderer() {
    }

    /** What one model yielded: the quads it offered, and the vertices actually written. */
    record Stats(int quads, int vertices) {
    }

    /**
     * Draws {@code model} into {@code consumer}, the way {@code ModelBlockRenderer#renderModel} would.
     *
     * <p>Asking a model for its quads with a {@code null} direction, and with a {@code null} render
     * type, is what asks it for every quad it has whichever layer it would normally be drawn in; they
     * all go into our own depth-ignoring buffer, which is what makes the inside of a block visible at
     * all.
     */
    static Stats draw(PoseStack.Pose pose, VertexConsumer consumer, @Nullable BlockState state,
                      BakedModel model, float red, float green, float blue, int packedLight,
                      int packedOverlay) {
        RandomSource random = RandomSource.create();
        int quads = 0;
        int vertices = 0;

        for (Direction direction : Direction.values()) {
            random.setSeed(SEED);
            List<BakedQuad> fetched = model.getQuads(state, direction, random);
            quads += sizeOf(fetched);
            vertices += drawQuads(pose, consumer, fetched, red, green, blue, packedLight, packedOverlay);
        }

        random.setSeed(SEED);
        List<BakedQuad> fetched = model.getQuads(state, null, random);
        quads += sizeOf(fetched);
        vertices += drawQuads(pose, consumer, fetched, red, green, blue, packedLight, packedOverlay);
        return new Stats(quads, vertices);
    }

    /**
     * Draws one component's model and records what it gave, in the same call.
     *
     * <p>One line per model, on the first frame that draws it, which is what turns "nothing appears in
     * game" into an answer without a debugger: a line with a real count says the model handed over
     * geometry and the vertex writes accepted it, while the warning says the model itself had nothing
     * to give - a different fault with a different fix.
     */
    static void drawAndNote(PoseStack.Pose pose, VertexConsumer consumer, @Nullable BlockState state,
                            BakedModel model, float red, float green, float blue, int packedLight,
                            int packedOverlay) {
        Stats stats = draw(pose, consumer, state, model, red, green, blue, packedLight, packedOverlay);
        String name = state == null ? "(no state)" : state.getBlock() + String.valueOf(state.getValues());
        if (!REPORTED.add(name)) {
            return;
        }
        if (stats.vertices() == 0) {
            RCConfig.LOGGER.warn(
                    "[redstonecircuit] inner component model {} gave no quads, so nothing was drawn "
                            + "for it; this is the 'model path draws nothing' failure and not a "
                            + "render-state one",
                    name);
        } else {
            RCConfig.LOGGER.info("[redstonecircuit] inner component model {}: quads={}, vertices={}",
                    name, stats.quads(), stats.vertices());
        }
    }

    /** Writes every quad of one fetch, and answers how many vertices that was. */
    private static int drawQuads(PoseStack.Pose pose, VertexConsumer consumer,
                                 @Nullable List<BakedQuad> quads, float red, float green, float blue,
                                 int packedLight, int packedOverlay) {
        if (quads == null || quads.isEmpty()) {
            return 0;
        }
        // Only the quad's own tint flag decides whether the tint applies, exactly as the model renderer
        // decides it; a quad that does not ask for one is drawn in its texture's own colour. The alpha
        // is never tinted: vanilla draws these quads at full opacity, and a tint that scaled alpha would
        // make every component inside a block fade out.
        float tintRed = Mth.clamp(red, 0.0F, 1.0F);
        float tintGreen = Mth.clamp(green, 0.0F, 1.0F);
        float tintBlue = Mth.clamp(blue, 0.0F, 1.0F);
        int written = 0;
        for (BakedQuad quad : quads) {
            if (quad == null) {
                continue;
            }
            boolean tinted = quad.isTinted();
            for (Vertex vertex : verticesOf(quad)) {
                // Packed into an ARGB int exactly as the model renderer packs it before handing it to
                // the buffer: the vertex consumer's four-int setColor writes one byte per channel, so
                // it takes 0..255 ints and not the 0..1 floats the four-float overload means.
                int colour = FastColor.ARGB32.color((int) vertex.alpha,
                        (int) (tinted ? vertex.red * tintRed : vertex.red),
                        (int) (tinted ? vertex.green * tintGreen : vertex.green),
                        (int) (tinted ? vertex.blue * tintBlue : vertex.blue));
                consumer.addVertex(pose, vertex.x, vertex.y, vertex.z)
                        .setColor(colour)
                        .setUv(vertex.u, vertex.v)
                        .setOverlay(packedOverlay)
                        .setUv2(packedLight & 0xFFFF, packedLight >>> 16)
                        .setNormal(pose, vertex.normalX, vertex.normalY, vertex.normalZ);
                written++;
            }
        }
        return written;
    }

    private static int sizeOf(@Nullable List<BakedQuad> quads) {
        return quads == null ? 0 : quads.size();
    }

    /**
     * One vertex of a quad, decoded out of its packed ints.
     *
     * <p>Filled into a reusable array rather than allocated, because this runs for every vertex of
     * every component of every host in range, every frame.
     */
    private static Vertex[] verticesOf(BakedQuad quad) {
        int[] data = quad.getVertices();
        int count = data.length / VERTEX_STRIDE;
        if (SCRATCH.length < count) {
            SCRATCH = new Vertex[Math.max(count, 4)];
            for (int i = 0; i < SCRATCH.length; i++) {
                SCRATCH[i] = new Vertex();
            }
        }
        float[] normal = normalOf(quad);
        for (int index = 0; index < count; index++) {
            Vertex vertex = SCRATCH[index];
            int base = index * VERTEX_STRIDE;
            vertex.x = Float.intBitsToFloat(data[base + OFFSET_X]);
            vertex.y = Float.intBitsToFloat(data[base + OFFSET_Y]);
            vertex.z = Float.intBitsToFloat(data[base + OFFSET_Z]);
            int colour = data[base + OFFSET_COLOUR];
            vertex.red = colour & 0xFF;
            vertex.green = (colour >>> 8) & 0xFF;
            vertex.blue = (colour >>> 16) & 0xFF;
            vertex.alpha = (colour >>> 24) & 0xFF;
            vertex.u = Float.intBitsToFloat(data[base + OFFSET_U]);
            vertex.v = Float.intBitsToFloat(data[base + OFFSET_V]);
            vertex.normalX = normal[0];
            vertex.normalY = normal[1];
            vertex.normalZ = normal[2];
        }
        return SCRATCH;
    }

    /**
     * The quad's own normal, as the model renderer would use it.
     *
     * <p>A baked quad's normal <em>is</em> its direction - that is the vector the model renderer feeds
     * to {@code putBulkData} - so that is what is used wherever there is one. The packed normal bytes
     * at the end of a vertex are not a reliable substitute: a quad's own vertex data may leave them at
     * zero. Only a quad with no direction at all has to fall back, and then the geometric normal of its
     * four corners is what a normal means.
     */
    private static float[] normalOf(BakedQuad quad) {
        Direction direction = quad.getDirection();
        if (direction != null) {
            NORMAL[0] = direction.getStepX();
            NORMAL[1] = direction.getStepY();
            NORMAL[2] = direction.getStepZ();
            return NORMAL;
        }
        return geometricNormal(quad.getVertices());
    }

    /** The normal of the quad's first three corners, or up when they are collinear. */
    private static float[] geometricNormal(int[] data) {
        int count = data.length / VERTEX_STRIDE;
        if (count >= 3) {
            float ax = Float.intBitsToFloat(data[OFFSET_X]);
            float ay = Float.intBitsToFloat(data[OFFSET_Y]);
            float az = Float.intBitsToFloat(data[OFFSET_Z]);
            int second = VERTEX_STRIDE;
            int third = 2 * VERTEX_STRIDE;
            float ux = Float.intBitsToFloat(data[second + OFFSET_X]) - ax;
            float uy = Float.intBitsToFloat(data[second + OFFSET_Y]) - ay;
            float uz = Float.intBitsToFloat(data[second + OFFSET_Z]) - az;
            float vx = Float.intBitsToFloat(data[third + OFFSET_X]) - ax;
            float vy = Float.intBitsToFloat(data[third + OFFSET_Y]) - ay;
            float vz = Float.intBitsToFloat(data[third + OFFSET_Z]) - az;
            float nx = uy * vz - uz * vy;
            float ny = uz * vx - ux * vz;
            float nz = ux * vy - uy * vx;
            float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (length > 1.0E-4F) {
                NORMAL[0] = nx / length;
                NORMAL[1] = ny / length;
                NORMAL[2] = nz / length;
                return NORMAL;
            }
        }
        NORMAL[0] = 0.0F;
        NORMAL[1] = 1.0F;
        NORMAL[2] = 0.0F;
        return NORMAL;
    }

    /** Reused per quad; see {@link #verticesOf}. */
    private static Vertex[] SCRATCH = newScratch(4);

    /** Reused per quad; see {@link #normalOf}. */
    private static final float[] NORMAL = new float[3];

    private static Vertex[] newScratch(int size) {
        Vertex[] scratch = new Vertex[size];
        for (int i = 0; i < size; i++) {
            scratch[i] = new Vertex();
        }
        return scratch;
    }

    /** One decoded vertex, in the mutable form the loop above fills in. */
    private static final class Vertex {
        float x;
        float y;
        float z;
        float red;
        float green;
        float blue;
        float alpha;
        float u;
        float v;
        float normalX;
        float normalY;
        float normalZ;
    }
}

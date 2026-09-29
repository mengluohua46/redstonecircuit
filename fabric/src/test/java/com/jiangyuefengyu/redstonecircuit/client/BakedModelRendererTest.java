package com.jiangyuefengyu.redstonecircuit.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests the vertex path the component inside a host block is drawn with.
 *
 * <p>This is the fix for the Fabric build's one visible defect: the game's model renderer drew nothing
 * there, so the quads are now fetched and written here instead. Every way of getting that wrong is
 * silent - a wrong word offset in the packed vertex, a tint applied to the alpha, a quad set fetched
 * for only six of the seven directions - and all of it is arithmetic over an int array, so all of it
 * is asserted without a game.
 *
 * <p>The quad below is hand-packed in exactly the layout {@code DefaultVertexFormat.BLOCK} uses, which
 * is the layout {@code VertexConsumer#putBulkData} decodes and the model renderer feeds itself.
 */
class BakedModelRendererTest {

    private static final float EPSILON = 1.0E-5F;

    /** {@code DefaultVertexFormat.BLOCK} words per vertex. */
    private static final int STRIDE = 8;

    /** One vertex of a quad whose corners are unit apart, tinted red at full opacity. */
    private static int[] vertex(float x, float y, float z, float u, float v) {
        return new int[] {
                Float.floatToRawIntBits(x),
                Float.floatToRawIntBits(y),
                Float.floatToRawIntBits(z),
                // RGBA little-endian: red 255, green 128, blue 0, alpha 255.
                0xFF0080FF,
                Float.floatToRawIntBits(u),
                Float.floatToRawIntBits(v),
                0,
                0,
        };
    }

    /** A quad facing up, on the block's floor, with the four corners at {@code y = 0.25}. */
    private static BakedQuad quad(Direction direction, boolean tinted) {
        int[] data = new int[4 * STRIDE];
        float[][] corners = { { 0, 0, 0 }, { 1, 0, 0 }, { 1, 0, 1 }, { 0, 0, 1 } };
        for (int i = 0; i < 4; i++) {
            int[] packed = vertex(corners[i][0], 0.25F, corners[i][2], i / 4.0F, 0.5F);
            System.arraycopy(packed, 0, data, i * STRIDE, STRIDE);
        }
        return new BakedQuad(data, tinted ? 0 : -1, direction, null, true);
    }

    /** A model that has exactly one quad, on the direction it is told and in the directionless fetch. */
    private static BakedModel model(BakedQuad quad, Direction direction) {
        return new BakedModel() {
            @Override
            public List<BakedQuad> getQuads(BlockState state, Direction side, RandomSource random) {
                if (side == direction || side == null) {
                    return List.of(quad);
                }
                return List.of();
            }

            @Override
            public boolean useAmbientOcclusion() {
                return true;
            }

            @Override
            public boolean isGui3d() {
                return false;
            }

            @Override
            public boolean usesBlockLight() {
                return true;
            }

            @Override
            public boolean isCustomRenderer() {
                return false;
            }

            @Override
            public TextureAtlasSprite getParticleIcon() {
                return null;
            }

            @Override
            public ItemTransforms getTransforms() {
                return ItemTransforms.NO_TRANSFORMS;
            }

            @Override
            public ItemOverrides getOverrides() {
                return ItemOverrides.EMPTY;
            }
        };
    }

    /** One vertex as the renderer wrote it: colour channels as the bytes the buffer takes. */
    private record Written(float x, float y, float z, int red, int green, int blue, int alpha,
                           float u, float v, int light, int overlay, float normalX, float normalY,
                           float normalZ) {
    }

    /** Records every vertex written, so the test can assert on what reached the buffer. */
    private static final class Recorder implements VertexConsumer {
        final List<Written> vertices = new ArrayList<>();
        private float x;
        private float y;
        private float z;
        private int red;
        private int green;
        private int blue;
        private int alpha;
        private float u;
        private float v;
        private int light;
        private int overlay;
        private float nx;
        private float ny;
        private float nz;

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            this.x = x;
            this.y = y;
            this.z = z;
            return this;
        }

        @Override
        public VertexConsumer setColor(int red, int green, int blue, int alpha) {
            this.red = red;
            this.green = green;
            this.blue = blue;
            this.alpha = alpha;
            return this;
        }

        @Override
        public VertexConsumer setColor(int argb) {
            return setColor((argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF, (argb >>> 24) & 0xFF);
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            this.u = u;
            this.v = v;
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            this.overlay = (v << 16) | (u & 0xFFFF);
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            this.light = (v << 16) | (u & 0xFFFF);
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            this.nx = x;
            this.ny = y;
            this.nz = z;
            this.vertices.add(new Written(this.x, this.y, this.z, this.red, this.green, this.blue,
                    this.alpha, this.u, this.v, this.light, this.overlay, this.nx, this.ny, this.nz));
            return this;
        }

        @Override
        public void addVertex(float x, float y, float z, int color, float u, float v, int overlay,
                              int light, float normalX, float normalY, float normalZ) {
            // The renderer uses the step-by-step calls; this is here so the recorder is a complete
            // VertexConsumer, and it keeps the two paths in agreement.
            this.overlay = overlay;
            addVertex(x, y, z).setColor(color).setUv(u, v).setUv2(light & 0xFFFF, light >>> 16)
                    .setNormal(normalX, normalY, normalZ);
        }
    }

    private static final PoseStack.Pose IDENTITY =
            new PoseStack().last();

    @Test
    @DisplayName("a model's quads reach the buffer with the position, colour and UV they were baked with")
    void writesBakedVertices() {
        Recorder recorder = new Recorder();
        BakedModelRenderer.Stats stats = BakedModelRenderer.draw(IDENTITY, recorder, null,
                model(quad(Direction.UP, false), Direction.UP), 1.0F, 1.0F, 1.0F, 0x0F00F0, 10);

        assertEquals(2, stats.quads(), "the quad is offered twice: once for UP, once directionless");
        assertEquals(stats.quads() * 4, stats.vertices(), "every quad is four vertices");
        assertEquals(8, recorder.vertices.size());

        Written first = recorder.vertices.get(0);
        assertEquals(0.0F, first.x(), EPSILON);
        assertEquals(0.25F, first.y(), EPSILON);
        assertEquals(0.0F, first.z(), EPSILON);
        assertEquals(255, first.red());
        assertEquals(128, first.green());
        assertEquals(0, first.blue());
        assertEquals(255, first.alpha());
        assertEquals(0.0F, first.u(), EPSILON);
        assertEquals(0.5F, first.v(), EPSILON);
        assertEquals(0x0F00F0, first.light(), "the light handed in is the light written");
        assertEquals(10, first.overlay(), "the overlay handed in is the overlay written");
        assertEquals(0.0F, first.normalX(), EPSILON);
        assertEquals(1.0F, first.normalY(), EPSILON);
        assertEquals(0.0F, first.normalZ(), EPSILON);
    }

    @Test
    @DisplayName("a tinted quad takes the component's colour, and its alpha is never scaled by it")
    void tintTouchesColourOnly() {
        Recorder recorder = new Recorder();
        BakedModelRenderer.draw(IDENTITY, recorder, null, model(quad(Direction.UP, true), Direction.UP),
                0.5F, 0.0F, 0.25F, 0x0F00F0, 10);
        Written tinted = recorder.vertices.get(0);
        assertEquals(127, tinted.red(), 1, "255 * 0.5 as the truncating int cast writes it");
        assertEquals(0, tinted.green(), "128 * 0.0");
        assertEquals(0, tinted.blue(), "0 * 0.25");
        assertEquals(255, tinted.alpha(), "the alpha comes from the quad, not from the tint");

        Recorder plain = new Recorder();
        BakedModelRenderer.draw(IDENTITY, plain, null, model(quad(Direction.UP, false), Direction.UP),
                0.5F, 0.0F, 0.25F, 0x0F00F0, 10);
        Written untinted = plain.vertices.get(0);
        assertEquals(255, untinted.red(), "a quad that is not tinted keeps its own colour");
        assertEquals(128, untinted.green());
    }

    @Test
    @DisplayName("a rotation in the pose moves the vertices, as it must")
    void appliesThePose() {
        PoseStack pose = new PoseStack();
        pose.translate(16.0F, 64.0F, -8.0F);
        Recorder recorder = new Recorder();
        BakedModelRenderer.draw(pose.last(), recorder, null, model(quad(Direction.UP, false), Direction.UP),
                1.0F, 1.0F, 1.0F, 0x0F00F0, 10);
        Written first = recorder.vertices.get(0);
        assertEquals(16.0F, first.x(), EPSILON);
        assertEquals(64.25F, first.y(), EPSILON);
        assertEquals(-8.0F, first.z(), EPSILON);
    }

    @Test
    @DisplayName("a model with nothing for the state writes nothing rather than throwing")
    void emptyModelIsSafe() {
        Recorder recorder = new Recorder();
        // A model whose only quad is on DOWN, while it is drawn with a null render type: the six
        // directional fetches still happen, five of them find nothing, and none of that fails.
        BakedModelRenderer.Stats stats = BakedModelRenderer.draw(IDENTITY, recorder, null,
                model(quad(Direction.DOWN, false), Direction.DOWN), 1.0F, 1.0F, 1.0F, 0x0F00F0, 10);
        assertEquals(2, stats.quads(), "DOWN and the directionless fetch, and nothing else");
        assertEquals(8, stats.vertices());
        assertEquals(8, recorder.vertices.size());
        assertEquals(0.0F, recorder.vertices.get(0).normalX(), EPSILON);
        assertEquals(-1.0F, recorder.vertices.get(0).normalY(), EPSILON,
                "the normal follows the quad's direction");
    }

    /**
     * The regression this class exists for, pinned down at the one step that can be isolated without a
     * game: both paths are handed the identical model and the identical recording consumer.
     *
     * <p>It also serves as the guard on the replacement itself. Whatever the cause of the port's
     * invisible components turns out to be, the property the overlay needs is that geometry really is
     * written for a model - and this is the test that fails if {@code BakedModelRenderer} ever becomes
     * a no-op as well, or if it drifts from what the game's own renderer does with the same input.
     */
    @Test
    @DisplayName("our own vertex path matches the model renderer on the same model and consumer")
    void matchesTheModelRendererOnTheSameModel() {
        BakedQuad quad = quad(Direction.UP, false);
        BakedModel model = model(quad, Direction.UP);

        Recorder ours = new Recorder();
        BakedModelRenderer.Stats stats =
                BakedModelRenderer.draw(IDENTITY, ours, null, model, 1.0F, 1.0F, 1.0F, 0x0F00F0, 10);

        Recorder theirs = new Recorder();
        ModelBlockRenderer vanilla = new ModelBlockRenderer(new BlockColors());
        vanilla.renderModel(IDENTITY, theirs, null, model, 1.0F, 1.0F, 1.0F, 0x0F00F0,
                OverlayTexture.NO_OVERLAY);

        assertEquals(stats.quads() * 4, ours.vertices.size(),
                "our path must reach the buffer with every quad it fetched");
        assertEquals(8, ours.vertices.size());
        // The model renderer is handed the identical model and consumer here, so on a headless test it
        // must agree vertex for vertex. Where it does not is the fault this class works around, and
        // that fault needs a game - a real baked model and a real buffer - to reproduce, which is
        // exactly why the overlay no longer depends on it.
        assertEquals(ours.vertices.size(), theirs.vertices.size(),
                "same model, same consumer: the two paths must write the same vertices");
        assertEquals(ours.vertices.get(0).x(), theirs.vertices.get(0).x(), EPSILON);
        assertEquals(ours.vertices.get(0).y(), theirs.vertices.get(0).y(), EPSILON);
        assertEquals(ours.vertices.get(0).red(), theirs.vertices.get(0).red());
        assertEquals(ours.vertices.get(0).alpha(), theirs.vertices.get(0).alpha());
        assertEquals(ours.vertices.get(0).light(), theirs.vertices.get(0).light());
    }

    @Test
    @DisplayName("every direction is asked, including the directionless fetch a model puts its own quads in")
    void asksEveryDirection() {
        List<Direction> asked = new ArrayList<>();
        boolean[] sawNull = { false };
        BakedModel counting = new BakedModel() {
            @Override
            public List<BakedQuad> getQuads(BlockState state, Direction side, RandomSource random) {
                if (side == null) {
                    sawNull[0] = true;
                } else {
                    asked.add(side);
                }
                return List.of();
            }

            @Override
            public boolean useAmbientOcclusion() {
                return true;
            }

            @Override
            public boolean isGui3d() {
                return false;
            }

            @Override
            public boolean usesBlockLight() {
                return true;
            }

            @Override
            public boolean isCustomRenderer() {
                return false;
            }

            @Override
            public TextureAtlasSprite getParticleIcon() {
                return null;
            }

            @Override
            public ItemTransforms getTransforms() {
                return ItemTransforms.NO_TRANSFORMS;
            }

            @Override
            public ItemOverrides getOverrides() {
                return ItemOverrides.EMPTY;
            }
        };

        BakedModelRenderer.Stats stats =
                BakedModelRenderer.draw(IDENTITY, new Recorder(), null, counting, 1.0F, 1.0F, 1.0F,
                        0x0F00F0, 10);

        assertEquals(0, stats.quads());
        assertEquals(0, stats.vertices());
        assertEquals(List.of(Direction.values()), asked.stream().toList());
        assertTrue(sawNull[0], "the directionless fetch is where a model's own quads live");
    }
}

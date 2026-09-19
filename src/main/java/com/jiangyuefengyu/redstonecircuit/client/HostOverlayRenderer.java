package com.jiangyuefengyu.redstonecircuit.client;

import java.util.List;

import com.jiangyuefengyu.redstonecircuit.RCConfig;
import com.jiangyuefengyu.redstonecircuit.RedstoneCircuit;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.BlockAndTintGetter;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.AddSectionGeometryEvent;

/**
 * Draws a translucent shell around every block that hides redstone.
 *
 * <h2>Why this hook</h2>
 * The shell has to look like part of the block - sorted, occluded and culled like part of the block -
 * so it is written straight into the section's own translucent vertex buffer by
 * {@link AddSectionGeometryEvent} instead of being drawn as an overlay every frame. That also means
 * no extra draw call, and it survives modded chunk renderers: Sodium dispatches this event too (it
 * calls {@code ClientHooks.gatherAdditionalRenderers} while compiling a section).
 *
 * <p>The alternatives were checked and rejected: {@code IBakedModelExtension#getRenderTypes} is never
 * given a position, so it cannot vary per block, and a {@code BlockState} property cannot be added to
 * an already-registered vanilla block.
 *
 * <h2>Why a shell and not real transparency</h2>
 * This event can only <em>add</em> geometry; nothing lets a mod remove the vanilla cube that is
 * already there. The block keeps its own model and gains a frosted, framed shell on top of it. What
 * is inside is drawn separately, and only for players wearing the goggles.
 *
 * <h2>Threading</h2>
 * The event fires on the client main thread; the renderer it registers runs on a section-building
 * worker thread. Only immutable values cross that boundary - the atlas lookup and the config reads
 * both happen before {@code addRenderer}. The geometry itself is in {@link HostShell}.
 */
@EventBusSubscriber(modid = RedstoneCircuit.MODID, value = Dist.CLIENT)
public final class HostOverlayRenderer {

    /**
     * A plain white block texture, used as the shell's colour source.
     *
     * <p>The chunk translucent layer samples the block atlas, so the shell needs <em>some</em> sprite
     * and is then tinted through the vertex colour. Any fully uniform sprite works; white wool is one
     * of the few guaranteed to be in the atlas and to carry no shading of its own.
     */
    private static final ResourceLocation OVERLAY_SPRITE =
            ResourceLocation.withDefaultNamespace("block/white_wool");

    private HostOverlayRenderer() {
    }

    @SubscribeEvent
    public static void onAddSectionGeometry(AddSectionGeometryEvent event) {
        int tint = RCConfig.hostOverlayColor();
        int frame = RCConfig.hostOverlayFrameColor();
        if (tint == 0 && frame == 0) {
            return;
        }

        BlockPos origin = event.getSectionOrigin();
        List<BlockPos> hosts = ClientHostCache.hostsInSection(event.getLevel().dimension(), origin);
        if (hosts.isEmpty()) {
            // Adding no renderer at all is what lets sections without hosts keep the fast path.
            return;
        }

        float[] texel = overlayTexel();
        event.addRenderer(context -> {
            VertexConsumer buffer = context.getOrCreateChunkBuffer(RenderType.translucent());
            PoseStack.Pose pose = context.getPoseStack().last();
            BlockAndTintGetter region = context.getRegion();
            for (BlockPos pos : hosts) {
                int light = LevelRenderer.getLightColor(region, pos);
                emitShell(buffer, pose,
                        pos.getX() - origin.getX(),
                        pos.getY() - origin.getY(),
                        pos.getZ() - origin.getZ(),
                        tint, frame, light, texel[0], texel[1]);
            }
        });
    }

    // -------------------------------------------------------------- geometry --

    /** Writes the shell of one host block, at an offset of {@code (x, y, z)} inside the section. */
    private static void emitShell(VertexConsumer buffer, PoseStack.Pose pose, float x, float y, float z,
                                  int tint, int frame, int light, float texU, float texV) {
        float[] point = new float[3];
        float[] normal = new float[3];
        boolean withFrame = frame != 0;

        for (int face = 0; face < HostShell.FACE_COUNT; face++) {
            HostShell.normal(face, normal);
            for (int quad = 0; quad < HostShell.quadsPerFace(withFrame); quad++) {
                float[] tile = HostShell.tile(quad, withFrame);
                // Quad 0 is the centre panel, the rest are the border.
                int argb = withFrame && quad > 0 ? frame : tint;
                emitQuad(buffer, pose, x, y, z, face, tile, argb, light, texU, texV, normal, point);
            }
        }
    }

    private static void emitQuad(VertexConsumer buffer, PoseStack.Pose pose, float x, float y, float z,
                                 int face, float[] tile, int argb, int light,
                                 float texU, float texV, float[] normal, float[] point) {
        vertex(buffer, pose, x, y, z, face, tile[0], tile[1], argb, light, texU, texV, normal, point);
        vertex(buffer, pose, x, y, z, face, tile[2], tile[1], argb, light, texU, texV, normal, point);
        vertex(buffer, pose, x, y, z, face, tile[2], tile[3], argb, light, texU, texV, normal, point);
        vertex(buffer, pose, x, y, z, face, tile[0], tile[3], argb, light, texU, texV, normal, point);
    }

    private static void vertex(VertexConsumer buffer, PoseStack.Pose pose, float x, float y, float z,
                               int face, float a, float b, int argb, int light,
                               float texU, float texV, float[] normal, float[] point) {
        HostShell.corner(face, a, b, point);
        buffer.addVertex(pose, x + point[0], y + point[1], z + point[2])
                .setColor(argb)
                .setUv(texU, texV)
                .setLight(light)
                .setNormal(pose, normal[0], normal[1], normal[2]);
    }

    /**
     * UV of a plain white texel of the block atlas.
     *
     * <p>The centre of the sprite is used for every vertex: the colour is uniform there, and a
     * constant UV makes the sampler resolve the base mip level instead of a blurred one.
     */
    private static float[] overlayTexel() {
        TextureAtlasSprite sprite = Minecraft.getInstance()
                .getTextureAtlas(TextureAtlas.LOCATION_BLOCKS)
                .apply(OVERLAY_SPRITE);
        return new float[] {
                (sprite.getU0() + sprite.getU1()) / 2,
                (sprite.getV0() + sprite.getV1()) / 2
        };
    }
}

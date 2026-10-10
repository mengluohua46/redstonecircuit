package com.jiangyuefengyu.redstonecircuit.client;

import java.util.List;

import com.jiangyuefengyu.redstonecircuit.network.StructurePreviews;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;

/**
 * The preview border: the wireframe box the preview stage asks for, drawn from the server's snapshots.
 *
 * <p>Vanilla's structure block outlines a selection with particles, which drift, stop at the view distance and
 * cannot say "this box is a different state from that one"; a line box is drawn exactly where the blocks are.
 *
 * <p>预览中 is cyan, a single hairline box, and 将生效 is amber - red when a release would overwrite something -
 * with a second, slightly larger box. The border uses {@code RenderType.lines()}, which writes and tests depth,
 * because an X-ray border would be a wallhack.
 *
 * <p>The pass is registered on {@code WorldRenderEvents.AFTER_TRANSLUCENT}, which is the point in the frame the
 * border belongs at and the one NeoForge's {@code AFTER_TRANSLUCENT_BLOCKS} stage named: after the translucent
 * terrain layer and before particles and weather, reached once per frame with the world's own pose stack, and
 * dispatched from the same place the overlay {@link HostRenderPass} draws in. That stage hands over no shared
 * vertex consumer, which costs nothing here - the lines go into this pass's own immediate buffer and are flushed
 * before the call returns.
 */
@Environment(EnvType.CLIENT)
public final class StructurePreviewRenderer {

    /** 预览中: nothing has happened to the world yet. */
    private static final float[] SHOWN = { 0.30F, 0.85F, 1.00F, 0.85F };

    /** 将生效: the next operation captures the region. */
    private static final float[] ARMED_CAPTURE = { 1.00F, 0.70F, 0.10F, 1.00F };

    /** 将生效: the next operation writes into an empty target. */
    private static final float[] ARMED_RELEASE = { 0.35F, 1.00F, 0.35F, 1.00F };

    /** 将生效: the next operation writes over blocks that are already there. */
    private static final float[] ARMED_OVERWRITE = { 1.00F, 0.25F, 0.20F, 1.00F };

    /** The confirmed state's second box sits this far outside the region, in blocks. */
    private static final double ARMED_MARGIN = 0.04D;

    /**
     * Binds the border pass; called once, from the client entrypoint.
     *
     * <p>Fabric announces one callback per stage, so the pass is registered on the one it wants rather than being
     * handed every stage and picking its own out of them: {@link #render(WorldRenderContext)} therefore runs
     * exactly once per frame and has no stage left to test.
     */
    public static void register() {
        WorldRenderEvents.AFTER_TRANSLUCENT.register(StructurePreviewRenderer::render);
    }

    /** The border pass itself: one box per border the server has said is up, in the player's own dimension. */
    public static void render(WorldRenderContext context) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null) {
            return;
        }
        List<StructurePreviews.Entry> borders = StructurePreviewClient.borders(level.dimension());
        if (borders.isEmpty()) {
            return;
        }

        PoseStack pose = context.matrixStack();
        if (pose == null) {
            // Only reachable through a third-party renderer that does not provide one; skipping the border
            // degrades the preview instead of throwing every frame.
            return;
        }
        PoseStack.Pose entry = pose.last();
        Vec3 camera = context.camera().getPosition();
        MultiBufferSource.BufferSource buffers = MultiBufferSource.immediate(new ByteBufferBuilder(1536));
        try {
            VertexConsumer lines = buffers.getBuffer(RenderType.lines());
            pose.pushPose();
            pose.translate(-camera.x, -camera.y, -camera.z);
            for (StructurePreviews.Entry border : borders) {
                draw(pose, lines, border);
            }
        } finally {
            // A stage that pushes and does not pop ends the game (`Pose stack not empty`); see PoseStackGuard.
            PoseStackGuard.restore(pose, entry);
            buffers.endBatch();
        }
    }

    /** One border: the region's own box, plus the confirmation's second box when it is armed. */
    private static void draw(PoseStack pose, VertexConsumer lines, StructurePreviews.Entry border) {
        float[] colour = colourOf(border);
        AABB box = boxOf(border, 0.0D);
        LevelRenderer.renderLineBox(pose, lines, box, colour[0], colour[1], colour[2], colour[3]);
        if (border.armed()) {
            LevelRenderer.renderLineBox(pose, lines, boxOf(border, ARMED_MARGIN), colour[0], colour[1],
                    colour[2], colour[3]);
        }
    }

    /** Which of the four looks this border gets. */
    private static float[] colourOf(StructurePreviews.Entry border) {
        if (!border.armed()) {
            return SHOWN;
        }
        if (!border.release()) {
            return ARMED_CAPTURE;
        }
        return border.overwrite() > 0 ? ARMED_OVERWRITE : ARMED_RELEASE;
    }

    /**
     * The region as a box in world coordinates. {@code max()} is the last cell, so the box ends one block past
     * it: a border drawn on the cell's own boundary would be hidden inside the block it is drawing around.
     */
    static AABB boxOf(StructurePreviews.Entry border, double margin) {
        return new AABB(border.region().min().getX() - margin, border.region().min().getY() - margin,
                border.region().min().getZ() - margin, border.region().max().getX() + 1 + margin,
                border.region().max().getY() + 1 + margin, border.region().max().getZ() + 1 + margin);
    }

    private StructurePreviewRenderer() {
    }
}

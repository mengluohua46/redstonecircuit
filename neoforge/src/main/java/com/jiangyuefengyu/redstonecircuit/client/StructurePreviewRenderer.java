package com.jiangyuefengyu.redstonecircuit.client;

import java.util.List;

import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import com.jiangyuefengyu.redstonecircuit.RedstoneCircuit;
import com.jiangyuefengyu.redstonecircuit.network.StructurePreviews;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * The preview border: the wireframe box the preview stage asks for, drawn from the server's snapshots.
 *
 * <p>Vanilla's structure block outlines a selection with particles, which drift, stop at the view distance and
 * cannot say "this box is a different state from that one"; a line box is drawn exactly where the blocks are.
 *
 * <p>预览中 is cyan, a single hairline box, and 将生效 is amber - red when a release would overwrite something -
 * with a second, slightly larger box. The border uses {@code RenderType.lines()}, which writes and tests depth,
 * because an X-ray border would be a wallhack.
 */
@OnlyIn(Dist.CLIENT)
@EventBusSubscriber(modid = RedstoneCircuit.MODID, value = Dist.CLIENT)
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

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        // By object, never by name: a Stage's toString carries a `minecraft:` namespace and would match nothing.
        if (!isStage(event.getStage(), RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS)) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null) {
            return;
        }
        List<StructurePreviews.Entry> borders = StructurePreviewClient.borders(level.dimension());
        if (borders.isEmpty()) {
            return;
        }

        PoseStack pose = event.getPoseStack();
        PoseStack.Pose entry = pose.last();
        Vec3 camera = event.getCamera().getPosition();
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

    /** Whether a dispatched stage is the one we meant, by object - repeated here so the two passes agree. */
    static boolean isStage(Object dispatched, Object wanted) {
        return wanted != null && wanted.equals(dispatched);
    }

    private StructurePreviewRenderer() {
    }
}

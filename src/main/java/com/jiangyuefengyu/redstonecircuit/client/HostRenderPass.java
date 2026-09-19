package com.jiangyuefengyu.redstonecircuit.client;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import com.jiangyuefengyu.redstonecircuit.RCConfig;
import com.jiangyuefengyu.redstonecircuit.RCRegistry;
import com.jiangyuefengyu.redstonecircuit.RedstoneCircuit;
import com.jiangyuefengyu.redstonecircuit.client.InnerComponentModel.Basis;
import com.jiangyuefengyu.redstonecircuit.client.InnerComponentModel.Box;
import com.jiangyuefengyu.redstonecircuit.client.InnerComponentModel.Neighbours;
import com.jiangyuefengyu.redstonecircuit.data.Slot;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.ClipContext;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * Draws what a block hides: the frosted, framed cube, and the component inside it.
 *
 * <h2>Once per frame, not once per chunk</h2>
 * The previous build wrote the shell into the section's own vertex buffer through
 * {@code AddSectionGeometryEvent}. That was faster - sort, occlude and cull for free - but it made the
 * shell part of the world's geometry, and geometry cannot be switched off again without rebuilding the
 * section. The design needs exactly that switch (R6: the effect appears when the goggles go on and
 * disappears when they come off), and it needs the component drawn <em>on top</em> of the block, which
 * no section buffer can do. So the whole thing moved here: drawn every frame, from the client's cached
 * copy of what is where, and gated on the goggles without a packet or a rebuild.
 *
 * <h2>What is drawn, and in what order</h2>
 * <ol>
 *   <li>For every host in range that the player can see: the shell, from {@link HostShell}, in the
 *       depth-tested {@link HostRenderTypes#SHELL} type. The line of sight is checked first so a host
 *       behind a wall is not drawn at all - without that, a translucent shell would be visible through
 *       the mountain in front of it.</li>
 *   <li>The component itself, in the depth-ignoring {@link HostRenderTypes#CONTENTS} type, so it shows
 *       through the block's own face. See {@link InnerComponentModel} for the shapes.</li>
 *   <li>The wrench highlight: the frame of the block the wrench has picked, and of the one being
 *       pointed at.</li>
 * </ol>
 * Asking for a {@link HostRenderTypes#SHELL} buffer before any {@link HostRenderTypes#CONTENTS} one is
 * what fixes the order between the two; within a type, quads are sorted against the camera on upload.
 */
@EventBusSubscriber(modid = RedstoneCircuit.MODID, value = Dist.CLIENT)
public final class HostRenderPass {

    /**
     * How far hosts are looked for, in sections.
     *
     * <p>Six sections is ninety-six blocks, which is past the point where a block's inside is legible;
     * the loop itself is a walk over the occupied sections, so this only bounds how much is drawn.
     */
    private static final int SECTION_RADIUS = 6;

    /** A plain white block texture, used as the colour source for every quad drawn here. */
    private static final ResourceLocation OVERLAY_SPRITE =
            ResourceLocation.withDefaultNamespace("block/white_wool");

    /** The block the wrench picked. */
    private static final int SELECTED_FRAME = 0xE0FFD23F;
    /** The block being pointed at with the wrench in hand. */
    private static final int HOVER_FRAME = 0x90FFFFFF;

    private HostRenderPass() {
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        // Dispatched from LevelRenderer.renderLevel itself, which is the one place that runs the same
        // way with and without a modded chunk renderer (Sodium included). AFTER_ENTITIES is also
        // before the translucent chunk layer, so water in front of a host still blends over it.
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        LocalPlayer player = minecraft.player;
        if (level == null || player == null || ClientHostCache.size() == 0) {
            return;
        }

        RCConfig.OverlayMode mode = RCConfig.overlayMode();
        if (mode == RCConfig.OverlayMode.OFF) {
            return;
        }
        if (mode == RCConfig.OverlayMode.GOGGLES && !wearingGoggles(player)) {
            return;
        }

        Camera camera = event.getCamera();
        List<Visible> visible = collect(level, player, camera.getPosition());
        BlockPos selected = WrenchClientState.selection();
        BlockPos hovered = hoveredHost(minecraft, player);
        if (visible.isEmpty() && selected == null && hovered == null) {
            return;
        }

        float[] texel = overlayTexel();
        PoseStack pose = event.getPoseStack();
        pose.pushPose();
        Vec3 cameraPos = camera.getPosition();
        pose.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);
        PoseStack.Pose last = pose.last();

        drawShells(last, level, visible, texel);
        if (RCConfig.showInnerComponents()) {
            drawContents(last, level, visible, texel);
        }
        drawHighlight(last, level, selected, SELECTED_FRAME, texel);
        drawHighlight(last, level, hovered, HOVER_FRAME, texel);

        HostRenderTypes.BUFFERS.endBatch();
        pose.popPose();
    }

    // --------------------------------------------------------- what to draw --

    /** One host that is close enough and has nothing solid between it and the camera. */
    private record Visible(BlockPos pos, Slot slot) {
    }

    private static List<Visible> collect(ClientLevel level, LocalPlayer player, Vec3 cameraPos) {
        BlockPos origin = BlockPos.containing(cameraPos);
        List<Visible> visible = new ArrayList<>();
        ClientHostCache.forEachNear(level.dimension(), origin, SECTION_RADIUS, (pos, slot) -> {
            if (slot == null || level.getBlockState(pos).isAir()) {
                // The server has not told us this block is gone (yet); drawing a shell in mid-air
                // would look like a bug in the world rather than in the data.
                return;
            }
            if (canSee(level, player, cameraPos, pos)) {
                visible.add(new Visible(pos, slot));
            }
        });
        return visible;
    }

    /**
     * Whether the player can see this block, i.e. whether the component inside may be drawn over it.
     *
     * <p>A ray from the eye to the block's centre, against collision shapes: a solid block in the way
     * hides the host, while glass and other non-colliding decoration does not. With
     * {@code seeInnerComponentsThroughWalls} the check is skipped and every host in range is drawn,
     * which is the X-ray version of the feature.
     */
    private static boolean canSee(ClientLevel level, LocalPlayer player, Vec3 eye, BlockPos pos) {
        if (RCConfig.seeInnerComponentsThroughWalls()) {
            return true;
        }
        Vec3 target = Vec3.atCenterOf(pos);
        if (pos.equals(BlockPos.containing(eye))) {
            return true;
        }
        BlockHitResult hit = level.clip(new ClipContext(eye, target, ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, player));
        return hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(pos);
    }

    /** The host the player's wrench is pointing at, or {@code null}. */
    @Nullable
    private static BlockPos hoveredHost(Minecraft minecraft, LocalPlayer player) {
        if (!RCRegistry.isWrench(player.getMainHandItem())
                && !RCRegistry.isWrench(player.getOffhandItem())) {
            return null;
        }
        if (!(minecraft.hitResult instanceof BlockHitResult hit)) {
            return null;
        }
        if (hit.getType() != HitResult.Type.BLOCK) {
            return null;
        }
        BlockPos pos = hit.getBlockPos();
        return ClientHostCache.slotAt(minecraft.level.dimension(), pos) != null ? pos : null;
    }

    // -------------------------------------------------------------- drawing --

    /** The frosted cube: a translucent panel per face plus a border around it. */
    private static void drawShells(PoseStack.Pose pose, ClientLevel level, List<Visible> hosts,
                                   float[] texel) {
        int tint = RCConfig.hostOverlayColor();
        int frame = RCConfig.hostOverlayFrameColor();
        if (tint == 0 && frame == 0) {
            return;
        }
        VertexConsumer buffer = HostRenderTypes.BUFFERS.getBuffer(HostRenderTypes.SHELL);
        boolean withFrame = frame != 0;
        float[] normal = new float[3];
        float[] point = new float[3];

        for (Visible host : hosts) {
            int light = lightFor(level, host.pos());
            for (int face = 0; face < HostShell.FACE_COUNT; face++) {
                HostShell.normal(face, normal);
                for (int quad = 0; quad < HostShell.quadsPerFace(withFrame); quad++) {
                    float[] tile = HostShell.tile(quad, withFrame);
                    int argb = withFrame && quad > 0 ? frame : tint;
                    if (argb == 0) {
                        continue;
                    }
                    emitShellQuad(buffer, pose, host.pos(), face, tile, argb, light, texel,
                            normal, point);
                }
            }
        }
    }

    private static void emitShellQuad(VertexConsumer buffer, PoseStack.Pose pose, BlockPos pos,
                                      int face, float[] tile, int argb, int light, float[] texel,
                                      float[] normal, float[] point) {
        shellVertex(buffer, pose, pos, face, tile[0], tile[1], argb, light, texel, normal, point);
        shellVertex(buffer, pose, pos, face, tile[2], tile[1], argb, light, texel, normal, point);
        shellVertex(buffer, pose, pos, face, tile[2], tile[3], argb, light, texel, normal, point);
        shellVertex(buffer, pose, pos, face, tile[0], tile[3], argb, light, texel, normal, point);
    }

    private static void shellVertex(VertexConsumer buffer, PoseStack.Pose pose, BlockPos pos,
                                    int face, float a, float b, int argb, int light, float[] texel,
                                    float[] normal, float[] point) {
        HostShell.corner(face, a, b, point);
        buffer.addVertex(pose, pos.getX() + point[0], pos.getY() + point[1], pos.getZ() + point[2])
                .setColor(argb)
                .setUv(texel[0], texel[1])
                .setLight(light)
                .setNormal(pose, normal[0], normal[1], normal[2]);
    }

    /** The component inside each host, drawn over the block's own face. */
    private static void drawContents(PoseStack.Pose pose, ClientLevel level, List<Visible> hosts,
                                     float[] texel) {
        VertexConsumer buffer = HostRenderTypes.BUFFERS.getBuffer(HostRenderTypes.CONTENTS);
        List<Box> boxes = new ArrayList<>();
        float[][] corners = new float[8][3];
        float[] mapped = new float[3];
        float[] faceNormal = new float[3];

        for (Visible host : hosts) {
            boxes.clear();
            InnerComponentModel.boxes(host.slot(), neighboursOf(level, host.pos()), boxes);
            Basis basis = Basis.of(host.slot().facing);
            int light = lightFor(level, host.pos());

            for (Box box : boxes) {
                float[][] local = InnerComponentModel.corners(box);
                for (int i = 0; i < local.length; i++) {
                    basis.point(local[i][0], local[i][1], local[i][2], mapped);
                    corners[i][0] = host.pos().getX() + mapped[0];
                    corners[i][1] = host.pos().getY() + mapped[1];
                    corners[i][2] = host.pos().getZ() + mapped[2];
                }
                for (int face = 0; face < InnerComponentModel.faceCount(); face++) {
                    int[] indices = InnerComponentModel.face(face);
                    normalOf(corners, indices, faceNormal);
                    int argb = shade(box.argb(), faceNormal);
                    for (int index : indices) {
                        buffer.addVertex(pose, corners[index][0], corners[index][1], corners[index][2])
                                .setColor(argb)
                                .setUv(texel[0], texel[1])
                                .setLight(light)
                                .setNormal(pose, faceNormal[0], faceNormal[1], faceNormal[2]);
                    }
                }
            }
        }
    }

    /** Which of the six neighbours of {@code pos} hold a component of their own. */
    private static Neighbours neighboursOf(ClientLevel level, BlockPos pos) {
        return direction -> ClientHostCache.slotAt(level.dimension(), pos.relative(direction)) != null;
    }

    /**
     * The border of one block, in a highlight colour.
     *
     * <p>Only the rims of the shell are drawn - the frame without the frosted panel - so the block
     * underneath stays readable and the mark reads as a selection rather than a paint job.
     */
    private static void drawHighlight(PoseStack.Pose pose, ClientLevel level, @Nullable BlockPos pos,
                                      int argb, float[] texel) {
        if (pos == null || level == null) {
            return;
        }
        VertexConsumer buffer = HostRenderTypes.BUFFERS.getBuffer(HostRenderTypes.SHELL);
        int light = lightFor(level, pos);
        float[] normal = new float[3];
        float[] point = new float[3];
        for (int face = 0; face < HostShell.FACE_COUNT; face++) {
            HostShell.normal(face, normal);
            // Quad 0 is the centre panel; the rest are the border.
            for (int quad = 1; quad < HostShell.quadsPerFace(true); quad++) {
                float[] tile = HostShell.tile(quad, true);
                emitShellQuad(buffer, pose, pos, face, tile, argb, light, texel, normal, point);
            }
        }
    }

    // --------------------------------------------------------------- helpers --

    /** The light at a block, with the block light floored so a buried component is never black. */
    private static int lightFor(BlockAndTintGetter level, BlockPos pos) {
        int light = LevelRenderer.getLightColor(level, pos);
        int block = (light >> 4) & 0xF;
        return block >= 8 ? light : (light & ~0xF0) | (8 << 4);
    }

    /** Writes the outward normal of a face into {@code out}, from its four corners. */
    private static void normalOf(float[][] corners, int[] indices, float[] out) {
        float[] first = corners[indices[0]];
        float[] second = corners[indices[1]];
        float[] third = corners[indices[2]];
        float ux = second[0] - first[0];
        float uy = second[1] - first[1];
        float uz = second[2] - first[2];
        float vx = third[0] - first[0];
        float vy = third[1] - first[1];
        float vz = third[2] - first[2];
        float nx = uy * vz - uz * vy;
        float ny = uz * vx - ux * vz;
        float nz = ux * vy - uy * vx;
        float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (length < 1.0E-4F) {
            out[0] = 0;
            out[1] = 1;
            out[2] = 0;
            return;
        }
        out[0] = nx / length;
        out[1] = ny / length;
        out[2] = nz / length;
    }

    /**
     * Applies vanilla's face shading to a colour.
     *
     * <p>The same factors the block renderer uses - a top face is full brightness, a bottom face is
     * half - so the small boxes read as solid rather than as flat stickers.
     */
    private static int shade(int argb, float[] normal) {
        float factor;
        float ax = Math.abs(normal[0]);
        float ay = Math.abs(normal[1]);
        float az = Math.abs(normal[2]);
        if (ay >= ax && ay >= az) {
            factor = normal[1] > 0 ? 1.0F : 0.5F;
        } else if (ax >= az) {
            factor = 0.6F;
        } else {
            factor = 0.8F;
        }
        int alpha = (argb >>> 24) & 0xFF;
        int red = (int) (((argb >> 16) & 0xFF) * factor);
        int green = (int) (((argb >> 8) & 0xFF) * factor);
        int blue = (int) ((argb & 0xFF) * factor);
        return (alpha << 24) | (red << 16) | (green << 8) | blue;
    }

    private static boolean wearingGoggles(LocalPlayer player) {
        return RCRegistry.isGoggles(player.getItemBySlot(EquipmentSlot.HEAD));
    }

    /**
     * UV of a plain white texel of the block atlas.
     *
     * <p>Every vertex uses the centre of the sprite: the colour there is uniform, and a constant UV
     * makes the sampler resolve the base mip level instead of a blurred one.
     */
    private static float[] overlayTexel() {
        TextureAtlasSprite sprite = Minecraft.getInstance()
                .getTextureAtlas(TextureAtlas.LOCATION_BLOCKS)
                .apply(OVERLAY_SPRITE);
        return new float[] {
                (sprite.getU0() + sprite.getU1()) / 2,
                (sprite.getV0() + sprite.getV1()) / 2,
        };
    }
}

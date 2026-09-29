package com.jiangyuefengyu.redstonecircuit.client;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import org.jetbrains.annotations.Nullable;

import com.jiangyuefengyu.redstonecircuit.RCConfig;
import com.jiangyuefengyu.redstonecircuit.RCRegistry;
import com.jiangyuefengyu.redstonecircuit.client.InnerComponentModel.Box;
import com.jiangyuefengyu.redstonecircuit.client.InnerComponentModel.Neighbours;
import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.Slot;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.ClipContext;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;

/**
 * Draws what a block hides: the block's own inside, the component in it, and a frame around it.
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
 *   <li>For every host in range that the player can see, <b>the block's own model, inside out</b> -
 *       drawn from vanilla's baked model for its state, with the pose mirrored in X so back-face
 *       culling keeps exactly the inside walls of the cube ({@link #drawHostInteriors}). This is what
 *       stands in for the block, because the block has been taken out of the chunk mesh by
 *       {@code BlockRenderDispatcherMixin} / {@code SodiumBlockRendererMixin} so that anything inside
 *       it can be seen at all.</li>
 *   <li>The component itself, from vanilla's own baked model where one fits and from our own boxes
 *       where none does, in vanilla's {@link RenderType#cutout()}. See {@link InnerComponentModel} for
 *       the shapes.</li>
 *   <li>The routing markers: which sides a component talks to, which the wrench pinned and which it
 *       cut.</li>
 *   <li>The frame around each host, and the wrench highlight: the frame of the block the wrench has
 *       picked and of the one being pointed at.</li>
 * </ol>
 *
 * <h2>One path, and only vanilla's render types</h2>
 * There is no render type of this mod's own anywhere in the overlay, and no branch on whether a shader
 * pack is drawing. Both of those were tried and both were wrong for the same reason: Iris picks a
 * program - and the extra vertex attributes a pack's shaders expect - from the <em>identity</em> of the
 * render type it is handed, so a type of ours gets no lighting and no surface to be lit, and a deferred
 * pack drops it. A type that skips the depth test, which is what made the inside visible in the first
 * place, can never be a type a pack knows.
 *
 * <p>Taking the block out of the mesh removes the reason for that type at all, and the two ends then
 * look the same: vanilla {@code cutout()} for everything solid, vanilla {@code translucent()} for the
 * highlight. The line of sight is checked before a host is drawn or hidden, so a hidden host that is
 * not then drawn - a hole in the world - cannot happen; see {@link #collect}.
 *
 * <h2>How the pass is hooked on Fabric</h2>
 * NeoForge dispatched {@code RenderLevelStageEvent} for every stage and this class checked for
 * {@code AFTER_ENTITIES}. Fabric has one callback per stage, so the check is gone and
 * {@code RedstoneCircuitClient} registers {@link #render} on
 * {@code WorldRenderEvents.AFTER_ENTITIES} directly. The stage means the same thing on both loaders -
 * after entities, before the translucent terrain layer - and hands over the same pose stack and camera,
 * so the body below is the NeoForge one.
 */
@Environment(EnvType.CLIENT)
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

    /** The wire art the connection lines are drawn with, so a line reads as redstone. */
    private static final ResourceLocation WIRE_SPRITE =
            ResourceLocation.withDefaultNamespace("block/redstone_dust_line0");

    /**
     * The slice of the wire sprite that holds the line itself.
     *
     * <p>The sprite is a 16x16 canvas with a one-pixel jogged line down the middle and nothing else, so
     * taking the whole width would spread that line across a sixteenth of a bar - a hairline. Narrowing
     * the slice to the columns the line actually uses is what gives a line of a believable thickness.
     */
    private static final float WIRE_U0 = 5F / 16F;
    private static final float WIRE_U1 = 11F / 16F;

    /** The block the wrench picked. */
    private static final int SELECTED_FRAME = 0xE0FFD23F;
    /** The block being pointed at with the wrench in hand. */
    private static final int HOVER_FRAME = 0x90FFFFFF;

    private HostRenderPass() {
    }

    /**
     * The per-frame overlay pass, registered on {@code WorldRenderEvents.AFTER_ENTITIES}.
     *
     * <p>{@code AFTER_ENTITIES} is dispatched from {@code LevelRenderer.renderLevel} itself, which is
     * the one place that runs the same way with and without a modded chunk renderer (Sodium included),
     * and it is before the translucent chunk layer, so water in front of a host still blends over it.
     */
    public static void render(WorldRenderContext context) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        LocalPlayer player = minecraft.player;
        if (level == null || player == null) {
            return;
        }

        RCConfig.OverlayMode mode = RCConfig.overlayMode();
        boolean active = mode == RCConfig.OverlayMode.ALWAYS
                || (mode == RCConfig.OverlayMode.GOGGLES && wearingGoggles(player));

        Camera camera = context.camera();
        List<Visible> visible = active ? collect(level, player, camera.getPosition()) : List.of();

        // One drawing path, always: the host block is taken out of the chunk mesh and drawn by the
        // pass itself, inside out, while the components and the route markers are drawn with vanilla's
        // own render types. That is what makes the effect identical with and without a shader pack -
        // and it is why nothing here asks ShaderCompat which mode it is in. There used to be two paths;
        // the branch was the bug, because the "shader" one was reached with a pack merely *selected*
        // (Iris answers "a pack is in use" even when iris.properties has enableShaders=false) and it
        // then drew the host as a frame only, leaving the block's own cube in the mesh to hide
        // everything inside it.
        //
        // The one thing switching the block out of the mesh must not do is leave a hole in the world,
        // so it is tied to the overlay actually drawing the inside: with the inner components switched
        // off in the config there is nothing to reveal and the block stays where it is.
        boolean reveal = active && RCConfig.showInnerComponents();

        // The mesher has to know exactly which blocks are drawn by hand: one that is hidden from the
        // mesh and then not drawn is a hole in the world, and one that is drawn while still in the mesh
        // fights it. Publishing every frame also covers the overlay being switched off, which is what
        // puts the blocks back.
        List<BlockPos> drawn = new ArrayList<>();
        if (reveal) {
            for (Visible host : visible) {
                drawn.add(host.pos());
            }
        }
        ShaderCompat.publishHosts(drawn, reveal);

        if (visible.isEmpty()) {
            HostRenderTypes.BUFFERS.endBatch();
            return;
        }

        BlockPos selected = WrenchClientState.selection();
        BlockPos hovered = hoveredHost(minecraft, player);

        AtlasUv uv = atlasUv();
        PoseStack pose = context.matrixStack();
        if (pose == null) {
            // Only reachable through a third-party renderer that does not provide one; skipping the
            // frame degrades the overlay instead of throwing every frame.
            HostRenderTypes.BUFFERS.endBatch();
            return;
        }
        pose.pushPose();
        Vec3 cameraPos = camera.getPosition();
        pose.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);
        PoseStack.Pose last = pose.last();

        // Order matters, and it matters more now that depth is honest everywhere. The block's inside-out
        // model goes first: it is the surface the components are seen against, and it is what writes the
        // depth that keeps the world behind the block from showing through. The components follow, in
        // front of those walls. A component drawn first would be *behind* the wall it sits in front of
        // as far as the depth buffer is concerned, and would disappear.
        if (reveal) {
            drawHostInteriors(pose, level, visible);
            drawContents(pose, level, visible, uv);
        }
        drawFrames(last, level, visible, uv);
        drawHighlight(last, level, selected, SELECTED_FRAME, uv);
        drawHighlight(last, level, hovered, HOVER_FRAME, uv);

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
     * <p>{@code seeInnerComponentsThroughWalls} skips the ray, which is the X-ray version of the
     * feature. Note what it does <em>not</em> do: it cannot make a host behind a wall disappear from
     * the world. Every host that is hidden from the chunk mesh is drawn here by construction - the two
     * lists are the same list - so a host the ray never reached keeps its block in the mesh and simply
     * is not revealed.
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

    /**
     * The border around each host: the frame without the frosted panel, so the block's inside stays
     * readable and the mark reads as a marker rather than a paint job over the whole face.
     *
     * <p>There used to be a translucent centre panel as well. It cannot survive the single-path design:
     * it is drawn after the components into a type that does not write depth, so it would blend over
     * whatever is inside the block and mute it - which is exactly the effect the overlay exists to
     * provide. Only the frame is drawn, in every mode, which also makes the block's border the one
     * thing that reads the same on every machine.
     *
     * <p>{@code hostOverlayColor} is therefore only used for the wrench highlight's background now;
     * the frame colour and the superconducting wire's colour are what this draws, and a host whose
     * {@code type} is superconducting gets the wire colour because "which of these two blocks is the
     * lossless one" is the first thing a player asks of a row of them.
     */
    private static void drawFrames(PoseStack.Pose pose, ClientLevel level, List<Visible> hosts,
                                   AtlasUv uv) {
        int frame = RCConfig.hostOverlayFrameColor();
        int wireFrame = RCConfig.superconductorFrameColor();
        if (frame == 0 && wireFrame == 0) {
            return;
        }
        VertexConsumer buffer = solidBuffer();
        float[] normal = new float[3];
        float[] point = new float[3];

        for (Visible host : hosts) {
            boolean superconducting = host.slot().type == ComponentType.SUPERCONDUCTOR;
            int hostFrame = superconducting ? wireFrame : frame;
            if (hostFrame == 0) {
                continue;
            }
            int light = lightFor(level, host.pos());
            for (int face = 0; face < HostShell.FACE_COUNT; face++) {
                HostShell.normal(face, normal);
                // Quad 0 is the centre panel; the rest are the border.
                for (int quad = 1; quad < HostShell.quadsPerFace(true); quad++) {
                    float[] tile = HostShell.tile(quad, true);
                    emitShellQuad(buffer, pose, host.pos(), face, tile, hostFrame, light, uv,
                            normal, point);
                }
            }
        }
    }

    private static void emitShellQuad(VertexConsumer buffer, PoseStack.Pose pose, BlockPos pos,
                                      int face, float[] tile, int argb, int light, AtlasUv uv,
                                      float[] normal, float[] point) {
        shellVertex(buffer, pose, pos, face, tile[0], tile[1], argb, light, uv, normal, point);
        shellVertex(buffer, pose, pos, face, tile[2], tile[1], argb, light, uv, normal, point);
        shellVertex(buffer, pose, pos, face, tile[2], tile[3], argb, light, uv, normal, point);
        shellVertex(buffer, pose, pos, face, tile[0], tile[3], argb, light, uv, normal, point);
    }

    private static void shellVertex(VertexConsumer buffer, PoseStack.Pose pose, BlockPos pos,
                                    int face, float a, float b, int argb, int light, AtlasUv uv,
                                    float[] normal, float[] point) {
        HostShell.corner(face, a, b, point);
        buffer.addVertex(pose, pos.getX() + point[0], pos.getY() + point[1], pos.getZ() + point[2])
                .setColor(argb)
                .setUv(uv.flatU(), uv.flatV())
                .setLight(light)
                .setNormal(pose, normal[0], normal[1], normal[2]);
    }

    /**
     * The buffer every solid part of the overlay is written into: the block's inside-out model, the
     * components, the route markers and the frame.
     *
     * <p>It is vanilla's own {@link RenderType#cutout()}, fetched by reference rather than built to
     * look like it, because a shader pack recognises a render type by identity - a type of ours that
     * copies every field is still a stranger to Iris and gets no program, which is how the components
     * came to be dropped by the deferred packs. Cutout rather than translucent because these are
     * vanilla's block textures and vanilla draws them as cutout: the transparent pixels around a torch
     * have to be discarded, not blended, or they sort against each other and smear.
     */
    private static VertexConsumer solidBuffer() {
        return HostRenderTypes.BUFFERS.getBuffer(HostRenderTypes.SOLID);
    }

    /** The type the wrench highlight is drawn with: vanilla's translucent, through the same source. */
    private static VertexConsumer highlightBuffer() {
        return HostRenderTypes.BUFFERS.getBuffer(HostRenderTypes.HIGHLIGHT);
    }

    /**
     * Draws each host's own block model inside out, with vanilla's cutout type.
     *
     * <p>This is what stands in for the block: the block has been taken out of the chunk mesh (see
     * {@code BlockRenderDispatcherMixin} and {@code SodiumBlockRendererMixin}) so that anything inside
     * it can be seen at all, so the surface seen when looking at it has to come from somewhere. Drawing
     * the model with a mirrored pose turns every quad around, and with back-face culling on, what
     * survives is exactly the inside walls of the block - a closed, textured box seen from within. So
     * nothing behind the block shows through, and the components inside sit in front of those walls.
     *
     * <p>The pose mirrors in X about the block's centre, which maps the unit cube onto itself: the
     * block's own shape is preserved and only its winding - and the texture, which is symmetric on
     * almost every block a player would hide redstone in - changes.
     */
    private static void drawHostInteriors(PoseStack pose, ClientLevel level, List<Visible> hosts) {
        VertexConsumer buffer = solidBuffer();
        BlockRenderDispatcher dispatcher = Minecraft.getInstance().getBlockRenderer();
        for (Visible host : hosts) {
            var state = level.getBlockState(host.pos());
            var model = dispatcher.getBlockModelShaper().getBlockModel(state);
            if (model == null) {
                continue;
            }
            int light = lightFor(level, host.pos());
            pose.pushPose();
            pose.translate(host.pos().getX(), host.pos().getY(), host.pos().getZ());
            pose.translate(1.0, 0.0, 0.0);
            pose.scale(-1.0F, 1.0F, 1.0F);
            // Our own quad writes rather than ModelBlockRenderer#renderModel, for the reason spelled out
            // in BakedModelRenderer: through the model renderer this geometry never reached the screen.
            BakedModelRenderer.draw(pose.last(), buffer, state, model, 1.0F, 1.0F, 1.0F, light,
                    OverlayTexture.NO_OVERLAY);
            pose.popPose();
        }
    }

    /** The component inside each host, drawn over the block's own face. */
    private static void drawContents(PoseStack pose, ClientLevel level, List<Visible> hosts,
                                     AtlasUv uv) {
        VertexConsumer buffer = solidBuffer();
        BlockRenderDispatcher dispatcher = Minecraft.getInstance().getBlockRenderer();
        List<Box> boxes = new ArrayList<>();
        BoxScratch scratch = new BoxScratch();

        for (Visible host : hosts) {
            Slot slot = host.slot();
            Neighbours neighbours = neighboursOf(level, host.pos());
            int light = lightFor(level, host.pos());

            // The component itself: vanilla's own model wherever one fits (everywhere except the
            // pressure plate, which cannot be placed yet), our coloured boxes where none does.
            InnerComponentVisuals.Visual visual = InnerComponentVisuals.of(slot, neighbours);
            if (visual == null) {
                boxes.clear();
                InnerComponentModel.boxes(slot, neighbours, boxes);
                drawBoxes(pose.last(), buffer, host.pos(), slot, boxes, uv, light, scratch);
                continue;
            }
            drawModel(pose, buffer, host.pos(), visual, light, dispatcher);

            // Then the routing: which sides this component talks to, which the wrench pinned and which
            // it cut. Vanilla has no such idea, so those markers stay ours - and for a wire the flat
            // lines of an ordinary connection are already part of the model drawn above.
            boxes.clear();
            ConnectionBars.bars(slot, neighbours, boxes);
            drawBoxes(pose.last(), buffer, host.pos(), slot, boxes, uv, light, scratch);
        }
    }

    /**
     * Draws one component from vanilla's baked model for its state.
     *
     * <p>The model is looked up per frame rather than cached, because the state carries the whole
     * picture - which way a repeater reads, its delay, whether it is lit, which of a wire's sides are
     * drawn - and the lookup is a map hit on a state the game has already baked. The rotation comes
     * with it: a variant's rotation is applied when the model is baked, so nothing here has to know
     * that a repeater reading from the north is drawn with a half turn.
     *
     * <p>The quads themselves are written by {@link BakedModelRenderer} rather than handed to
     * {@code ModelBlockRenderer#renderModel}: through the model renderer this geometry never reached
     * the screen on this loader, while our own vertex writes into the very buffer that already draws
     * the frame do.
     */
    private static void drawModel(PoseStack pose, VertexConsumer buffer, BlockPos pos,
                                  InnerComponentVisuals.Visual visual, int light,
                                  BlockRenderDispatcher dispatcher) {
        BakedModel model = dispatcher.getBlockModelShaper().getBlockModel(visual.state());
        if (model == null) {
            // Nothing baked for this state (a resource pack mid-reload, or a state the game has no
            // model for): skipping the component degrades the overlay instead of throwing every frame.
            return;
        }
        pose.pushPose();
        pose.translate(pos.getX(), pos.getY(), pos.getZ());
        pose.mulPose(visual.transform());
        BakedModelRenderer.drawAndNote(pose.last(), buffer, visual.state(), model,
                visual.red(), visual.green(), visual.blue(), light, OverlayTexture.NO_OVERLAY);
        pose.popPose();
    }

    /**
     * Emits our own boxes: a length of wire per connection, the wrench's markers, and the fallback
     * shape for any component no vanilla model fits.
     *
     * <p>A box marked {@link Box#textured()} is drawn with the wire art, spread over its faces, so a
     * connection reads as redstone; the art is mostly transparent, and cutout throws the transparent
     * pixels away, which is what turns the box into a thin line. Everything else is a flat colour.
     */
    private static void drawBoxes(PoseStack.Pose pose, VertexConsumer buffer, BlockPos pos, Slot slot,
                                  List<Box> boxes, AtlasUv uv, int light, BoxScratch scratch) {
        // The boxes are built in component space, so they have to be carried into the block by the same
        // frame the component is drawn in - see InnerComponentModel.Basis#of(Slot).
        InnerComponentModel.Basis basis = InnerComponentModel.Basis.of(slot);
        for (Box box : boxes) {
            float[][] local = InnerComponentModel.corners(box);
            for (int i = 0; i < local.length; i++) {
                basis.point(local[i][0], local[i][1], local[i][2], scratch.mapped);
                scratch.corners[i][0] = pos.getX() + scratch.mapped[0];
                scratch.corners[i][1] = pos.getY() + scratch.mapped[1];
                scratch.corners[i][2] = pos.getZ() + scratch.mapped[2];
            }
            for (int face = 0; face < InnerComponentModel.faceCount(); face++) {
                int[] indices = InnerComponentModel.face(face);
                normalOf(scratch.corners, indices, scratch.normal);
                int argb = shade(box.argb(), scratch.normal);
                for (int index : indices) {
                    if (box.textured()) {
                        faceUv(box, face, local[index], uv, scratch.uv);
                    } else {
                        scratch.uv[0] = uv.flatU();
                        scratch.uv[1] = uv.flatV();
                    }
                    buffer.addVertex(pose, scratch.corners[index][0], scratch.corners[index][1],
                                    scratch.corners[index][2])
                            .setColor(argb)
                            .setUv(scratch.uv[0], scratch.uv[1])
                            .setLight(light)
                            .setNormal(pose, scratch.normal[0], scratch.normal[1], scratch.normal[2]);
                }
            }
        }
    }

    /**
     * Where one corner of a textured box samples the wire art.
     *
     * <p>The art is a line running along the sprite's length, so that length is laid along the box's
     * longest axis: across it, the line would come out as a stripe rather than as a wire.
     */
    private static void faceUv(Box box, int face, float[] corner, AtlasUv uv, float[] out) {
        // Face order is down, up, north, south, west, east in component space; a face's two tangent axes
        // are the two that are not its normal, given here as 0 = a, 1 = b, 2 = c.
        int uAxis;
        int vAxis;
        switch (face) {
            case 0, 1 -> {
                uAxis = 0;
                vAxis = 2;
            }
            case 2, 3 -> {
                uAxis = 0;
                vAxis = 1;
            }
            default -> {
                uAxis = 2;
                vAxis = 1;
            }
        }
        float[] half = { box.ha(), box.hb(), box.hc() };
        float[] centre = { box.a(), box.b(), box.c() };
        if (half[uAxis] > half[vAxis]) {
            int swap = uAxis;
            uAxis = vAxis;
            vAxis = swap;
        }
        float u = fraction(corner[uAxis], centre[uAxis], half[uAxis]);
        float v = fraction(corner[vAxis], centre[vAxis], half[vAxis]);
        out[0] = uv.wireU0() + u * (uv.wireU1() - uv.wireU0());
        out[1] = uv.wireV0() + v * (uv.wireV1() - uv.wireV0());
    }

    /** Where {@code value} sits inside a span of {@code 2 * half} centred on {@code centre}; 0.5 when
     * the span is empty, which a zero-thickness box would otherwise divide by. */
    private static float fraction(float value, float centre, float half) {
        return half <= 0F ? 0.5F : (value - (centre - half)) / (2F * half);
    }

    /** Reused for a whole frame, so drawing does not allocate once per host block. */
    private static final class BoxScratch {
        final float[][] corners = new float[8][3];
        final float[] mapped = new float[3];
        final float[] normal = new float[3];
        final float[] uv = new float[2];
    }

    /** Which of the six neighbours of {@code pos} hold a component of their own. */
    private static Neighbours neighboursOf(ClientLevel level, BlockPos pos) {
        return direction -> ClientHostCache.slotAt(level.dimension(), pos.relative(direction)) != null;
    }

    /**
     * The border of one block, in a highlight colour.
     *
     * <p>Only the rims are drawn - the frame without the panel - so the block underneath stays readable
     * and the mark reads as a selection rather than a paint job.
     *
     * <p>This is the one part of the overlay still drawn with {@code translucent()}: the colour passed
     * in carries a real alpha (see the two constants above), and blending is the whole point of a
     * highlight. Vanilla's own translucent type is still a type every shader pack knows by name, so it
     * costs nothing under a pack, and it is flushed here rather than in the level renderer's
     * translucent pass because it is written into this pass's own buffer source.
     */
    private static void drawHighlight(PoseStack.Pose pose, ClientLevel level, @Nullable BlockPos pos,
                                      int argb, AtlasUv uv) {
        if (pos == null || level == null) {
            return;
        }
        VertexConsumer buffer = highlightBuffer();
        int light = lightFor(level, pos);
        float[] normal = new float[3];
        float[] point = new float[3];
        for (int face = 0; face < HostShell.FACE_COUNT; face++) {
            HostShell.normal(face, normal);
            // Quad 0 is the centre panel; the rest are the border.
            for (int quad = 1; quad < HostShell.quadsPerFace(true); quad++) {
                float[] tile = HostShell.tile(quad, true);
                emitShellQuad(buffer, pose, pos, face, tile, argb, light, uv, normal, point);
            }
        }
    }

    // --------------------------------------------------------------- helpers --

    /**
     * The light at a block, floored so a buried component is never black.
     *
     * <p>Without a shader pack the floor is on the block light alone. The component sits in the shade
     * of its own block, and the block's inside-out model drawn behind it carries the world's own
     * lighting, so a low floor still looks like part of the world.
     *
     * <p>With a pack selected the floor is raised on both halves. The block's own faces are gone from
     * the mesh, so this number is most of what lights the inside - and the packs that lean hardest on
     * shadows and ambient occlusion (Eclipse, for one) darken geometry further, because the extra
     * vertex data their shaders use for that is filled by their own terrain pipeline and never by code
     * drawing by hand. Twelve rather than full brightness on purpose - at fifteen the inside glows and
     * stops reading as part of the world.
     *
     * <p>This is the one thing the shader check is still for. It is a brightness adjustment, not a
     * choice of drawing path: with a pack merely <em>selected</em> (Iris answers yes even when
     * {@code iris.properties} has {@code enableShaders=false}) the worst case is an inside that is a
     * little brighter than it needed to be, instead of a whole different renderer.
     */
    private static int lightFor(BlockAndTintGetter level, BlockPos pos) {
        int light = LevelRenderer.getLightColor(level, pos);
        int block = (light >> 4) & 0xF;
        if (ShaderCompat.vanillaGeometry()) {
            int sky = light & 0xF;
            return (Math.max(block, 12) << 4) | Math.max(sky, 12);
        }
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

    /**
     * Whether this player is looking through the goggles, and so may see inside a block.
     *
     * <p>The question itself is answered by {@link RCRegistry#isWearingGoggles}, which is where it
     * moved so that the Jade overlay can ask the same one: the two have to agree, or a player is shown
     * the contents of a block that is not being drawn specially (or the other way round).
     */
    private static boolean wearingGoggles(LocalPlayer player) {
        return RCRegistry.isWearingGoggles(player);
    }

    /**
     * UV of a plain white texel of the block atlas, plus the rectangle the wire art lives in.
     *
     * <p>Every vertex of a solid box uses the centre of the white sprite: the colour there is uniform,
     * and a constant UV makes the sampler resolve the base mip level instead of a blurred one. A box
     * that is a length of wire uses {@code wireU0..wireV1} instead, spread over the box's faces.
     */
    private static AtlasUv atlasUv() {
        Function<ResourceLocation, TextureAtlasSprite> atlas =
                Minecraft.getInstance().getTextureAtlas(TextureAtlas.LOCATION_BLOCKS);
        TextureAtlasSprite white = atlas.apply(OVERLAY_SPRITE);
        TextureAtlasSprite wire = atlas.apply(WIRE_SPRITE);
        float wireWidth = wire.getU1() - wire.getU0();
        return new AtlasUv(
                (white.getU0() + white.getU1()) / 2,
                (white.getV0() + white.getV1()) / 2,
                wire.getU0() + wireWidth * WIRE_U0,
                wire.getU0() + wireWidth * WIRE_U1,
                wire.getV0(),
                wire.getV1());
    }

    /** The white texel, and the wire art's rectangle, in the block atlas. */
    private record AtlasUv(float flatU, float flatV, float wireU0, float wireU1, float wireV0,
                           float wireV1) {
    }
}

package com.jiangyuefengyu.redstonecircuit.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;

import com.jiangyuefengyu.redstonecircuit.RCConfig;
import com.jiangyuefengyu.redstonecircuit.RCRegistry;
import com.jiangyuefengyu.redstonecircuit.RedstoneCircuit;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierDrawCheck;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierFrame;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierHostCheck;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierId;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierIndex;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierPose;
import com.jiangyuefengyu.redstonecircuit.compat.physics.PhysicsCompat;
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
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.resources.ResourceKey;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.model.data.ModelData;

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
 *   <li>For every host in range that the player can see: the shell, from {@link HostShell}, in vanilla's
 *       own {@link RenderType#translucent()} - the very type glass and water use, fetched from the
 *       vanilla buffer source so the game flushes it in its usual translucent pass. The line of sight is
 *       checked first so a host behind a wall is not drawn at all - without that, a translucent shell
 *       would be visible through the mountain in front of it.</li>
 *   <li>The component itself, in the depth-ignoring {@link HostRenderTypes#CONTENTS} type, so it shows
 *       through the block's own face. See {@link InnerComponentModel} for the shapes.</li>
 *   <li>The wrench highlight: the frame of the block the wrench has picked, and of the one being
 *       pointed at.</li>
 * </ol>
 *
 * <h2>Why the shell is not one of our own render types</h2>
 * It used to be, with the same state vanilla's translucent type has, and shader packs treated it as a
 * stranger: Complementary Unbound drew the components and dropped the shell entirely. Iris picks a
 * program - and the extra vertex attributes a pack's shaders expect - from the render type it is handed,
 * by identity, so a type that merely looks like vanilla's is not enough. Glass and water came through
 * untouched, which is the whole argument for using that instance rather than a copy of it.
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

    /**
     * How far a carried host is drawn, in blocks; a little past the section radius above.
     *
     * <p>A carried host has to be measured from where it actually is, and that is the carrier's
     * position - not its origin, which may be anywhere. The number is the section radius in blocks
     * with a block of slack, so the two paths reach roughly as far as each other and neither surprises
     * the player by drawing further than the other.
     */
    private static final double DRAW_DISTANCE = SECTION_RADIUS * 16.0 + 16.0;

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

    /**
     * <b>The stage names this build PRINTS - and why dispatch must never key on them.</b>
     *
     * <h2>The regression these two constants caused, and the rule that replaces it</h2>
     * An earlier revision of this file dispatched on {@code event.getStage().toString()} compared with
     * these strings - and the whole overlay vanished: no world shell, no components, no carried path, and
     * <b>no frame summary either</b>, because {@code onRenderLevel} returned on its first line of every
     * call. The two names are <em>not</em> the dispatch tokens, because
     * {@code RenderLevelStageEvent.Stage} is built through a {@code ResourceLocation}:
     *
     * <pre>
     *   private static Stage register(String name, RenderType t) { return register(ResourceLocation.parse(name), t); }
     *   private static Stage register(ResourceLocation name, RenderType t) { new Stage(name.toString()); }
     *   public String toString() { return this.name; }
     * </pre>
     *
     * <p>{@code ResourceLocation.parse("after_entities")} has no namespace, so it gains the default one
     * and {@code toString()} is <b>{@code "minecraft:after_entities"}</b>. Comparing that with
     * {@code "after_entities"} is false for every frame, forever - a silent, total failure that no
     * in-game diagnostic could report, because the diagnostic was behind the same comparison.
     *
     * <p><b>So these constants are for the log and for unit tests only.</b> Dispatch is
     * {@link #isStage}, which compares the {@code Stage} object itself: {@code Stage} is a plain class
     * with identity equality, so that is exact and free. The {@code minecraft:} prefix is included in the
     * constants so that the printed line is the literal string a reader will see in the game's own code.
     */
    static final String WORLD_STAGE_NAME = "minecraft:after_entities";

    static final String CARRIED_STAGE_NAME = "minecraft:after_translucent_blocks";

    /**
     * Whether a dispatched stage is the one we meant, <b>by object</b> - never by name.
     *
     * <p>{@code RenderLevelStageEvent.Stage} does not override {@code equals}, so this is identity, which
     * is what makes it immune to the spelling trap above. A {@code null} target gives {@code false}
     * rather than throwing, so a caller that could not load the stage is simply "not our stage".
     */
    static boolean isStage(Object dispatched, Object wanted) {
        return wanted != null && wanted.equals(dispatched);
    }

    /**
     * Whether the names above still describe the stage objects this build dispatches on.
     *
     * <p>Called from the render pass itself, once per frame, where loading
     * {@code RenderLevelStageEvent} is free - a running game has long since loaded it. This is what stops
     * the printed copy from becoming a lie: if NeoForge renames a stage, the next frame logs a warning
     * naming both spellings. It is deliberately <b>not</b> a gate on anything: the pass draws whether or
     * not this returns true, because a diagnostic must never be able to switch the feature off.
     */
    static boolean stageNamesMatchTheDispatcher() {
        boolean matches = WORLD_STAGE_NAME.equals(RenderLevelStageEvent.Stage.AFTER_ENTITIES.toString())
                && CARRIED_STAGE_NAME.equals(
                        RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS.toString());
        if (!matches) {
            RCConfig.LOGGER.warn("[redstonecircuit] client: the stage names this build PRINTS "
                            + "({}, {}) no longer match what NeoForge's Stage reports ({}, {}). The pass "
                            + "still draws - dispatch compares the Stage object, not the name - but the "
                            + "frame summary's worldQuadsFlushedAt/carriedQuadsFlushedAt are the stale "
                            + "spelling.",
                    WORLD_STAGE_NAME, CARRIED_STAGE_NAME,
                    RenderLevelStageEvent.Stage.AFTER_ENTITIES,
                    RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS);
        }
        return matches;
    }

    /**
     * The fact that the world path's translucent shell is <b>not</b> flushed by us, and by whom instead.
     *
     * <p>Printed beside the two stages we do own, because a reader who finds {@code worldShellQuads} and
     * looks for a matching "flushed at" has to be told that there is none: the world shell deliberately
     * keeps vanilla's translucent render type identity, so the level renderer's own translucent section
     * layer flushes it, after the two stages below. Quoted from {@code LevelRenderer.renderLevel}'s
     * profiler push rather than from the enum, because the level renderer is what actually does it.
     */
    static final String WORLD_SHELL_STAGE = "handed to LevelRenderer, which flushes it in the same frame; "
            + "type identity kept for Iris, NOT flushed by us and never alarmed on";

    private HostRenderPass() {
    }

    /**
     * The client's own tick, which is where the carried picture is allowed to decay.
     *
     * <p>Registered on the <b>game</b> bus rather than the mod bus because it is a game event. It does
     * one thing: {@link ClientHostCache#tickCarriers()} lets go of any carrier whose pose the server
     * has stopped refreshing, and hands its hosts back to the world path. Twenty times a second is
     * plenty for something whose threshold is measured in seconds, and it costs a walk over the
     * carriers the client knows about - a handful - only when one of them is actually stale.
     */
    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (Minecraft.getInstance().level == null) {
            return;
        }
        ClientHostCache.tickCarriers();
    }

    /**
     * <b>Where the pass runs, and why the carried path runs somewhere else.</b>
     *
     * <p>Both stages are dispatched from {@code LevelRenderer.renderLevel} itself, which is the one
     * place that runs the same way with and without a modded chunk renderer (Sodium included).
     *
     * <pre>
     *   Stage.WORLD    = AFTER_ENTITIES
     *   Stage.CARRIED  = AFTER_TRANSLUCENT_BLOCKS
     * </pre>
     *
     * <p><b>The world path stays at {@code AFTER_ENTITIES} and must not be moved.</b> That stage is
     * before the translucent chunk layer, so water in front of a host still blends over it, and it is
     * before the block-entity pass, so a component is not painted over a chest it is standing next to.
     * The whole world path - shell and contents - is written there.
     *
     * <p><b>The carried path is written after everything the structure itself draws.</b> A contraption
     * is drawn through Flywheel: {@code VisualizationManager.RenderDispatcher.afterEntities} is invoked
     * from {@code LevelRendererMixin.flywheel$beforeBlockEntities}, i.e. immediately <em>after</em>
     * {@code AFTER_ENTITIES} has been dispatched and before the {@code blockentities} section, and a
     * Sable sub-level is drawn even earlier, by
     * {@code SubLevelRenderDispatcher.renderAfterSections} injected just before
     * {@code LevelRenderer.renderLevel} calls {@code DimensionSpecialEffects.constantAmbientLight()}.
     * Both are therefore after {@code AFTER_ENTITIES}, and geometry flushed there for a host riding a
     * structure is covered by that structure's own opaque blocks - which is the defect this split fixes.
     */
    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        // BY OBJECT, NEVER BY NAME. See WORLD_STAGE_NAME: the Stage's `toString` carries a
        // `minecraft:` namespace, and an earlier revision dispatched on that string, matched nothing,
        // and silently switched the whole overlay off - including its own diagnostics. This comparison
        // cannot fail that way: `Stage` has no `equals` override, so it is identity.
        Stage stage = isStage(event.getStage(), RenderLevelStageEvent.Stage.AFTER_ENTITIES) ? Stage.WORLD
                : isStage(event.getStage(), RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS)
                        ? Stage.CARRIED
                        : null;
        if (stage == null) {
            return;
        }
        // The canary: the two names the log prints really are what NeoForge's Stage reports. Free
        // here - a running game has loaded that class long ago - and it cannot switch anything off.
        stageNamesMatchTheDispatcher();
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        LocalPlayer player = minecraft.player;
        if (level == null || player == null) {
            return;
        }

        boolean goggles = wearingGoggles(player);
        CarrierGeometry.Gates gates = CarrierGeometry.Gates.of(RCConfig.overlayMode(), goggles);
        boolean active = gates.active();
        // The gates go into the trace before anything is collected, so the frame summary - which is
        // written while collecting - names this frame's configuration rather than the last one's.
        CarrierRenderTrace.gates(level.dimension(), gates.describeWithVerdict() + effectiveColours(gates));
        // ONE log line when the pass is off, and only on the transition. A player who is not wearing
        // the goggles and a player whose config says OFF see the same empty screen, and neither of them
        // is a rendering bug - but until this line existed the log could not tell them from one.
        logInactiveOnce(level.dimension(), gates);

        Camera camera = event.getCamera();
        Vec3 cameraPos = camera.getPosition();
        runStage(stage, minecraft, level, player, event, cameraPos, gates, active);
    }

    /**
     * <b>P1.57: which pose a carrier is being drawn from, written when that changes and never per frame.</b>
     *
     * <p>The two sources are indistinguishable on screen until a machine moves - which is why the round
     * that first asked Sable for its render pose could fail silently for the whole session while the log
     * filled with 3625 per-frame reflections warnings elsewhere. One line per carrier per transition
     * answers "is the fix in effect at all" without measuring anything.
     */
    private static final java.util.Set<com.jiangyuefengyu.redstonecircuit.carrier.CarrierId>
            LIVE_POSE_CARRIERS = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static void notePoseSource(com.jiangyuefengyu.redstonecircuit.carrier.CarrierId carrier,
                                       @Nullable CarrierPose live) {
        if (live == null) {
            if (LIVE_POSE_CARRIERS.remove(carrier)) {
                RCConfig.LOGGER.info("[redstonecircuit] client: carrier {} is drawn from the NETWORK pose "
                        + "(Sable answers no render pose for it: a Create contraption, no Sable, or a "
                        + "sub-level the client does not hold)", carrier.shortId());
            }
        } else if (LIVE_POSE_CARRIERS.add(carrier)) {
            RCConfig.LOGGER.info("[redstonecircuit] client: carrier {} is drawn from Sable's own "
                    + "renderPose(partialTick) - the components are at the machine's own position in every "
                    + "frame", carrier.shortId());
        }
    }

    /**
     * One of the two stages, with everything that differs between them decided here.
     *
     * <p>The alternative - one method that collects both lists and draws whichever halves its stage
     * owns - would have to keep the carried list alive across two callbacks and would answer "which
     * stage is this" in five places. Splitting the method means each stage collects exactly what it
     * draws, and the only state carried between them is the counter the frame summary needs.
     *
     * <h2>Which stage the carried geometry is submitted at, and why it depends on the shader path</h2>
     * See {@link CarrierGeometry.StagePlan}: without a shader pack the carried path keeps the late stage
     * it has always used (so that a machine's own opaque blocks cannot cover a host riding it), and with
     * one it is submitted here, with the world path, because our contents are written with a vanilla -
     * therefore depth-<em>testing</em> - render type and the late stage is after the carried block's own
     * faces are in the depth buffer. The whole of the "without a shader pack nothing changes" claim is
     * that this branch is taken only when {@code vanillaTypes} is true; when it is false, not one stage,
     * type, buffer or submission order differs from the build before this decision existed.
     */
    private static void runStage(Stage stage, Minecraft minecraft, ClientLevel level, LocalPlayer player,
                                 RenderLevelStageEvent event, Vec3 cameraPos,
                                 CarrierGeometry.Gates gates, boolean active) {
        // With a shader pack drawing, the block itself has to leave the chunk mesh and everything the
        // overlay draws has to use vanilla's own render types: a pack picks its program, and the extra
        // vertex attributes its shaders expect, from the render type it is handed, and it applies its
        // own depth state per program. See ShaderCompat and the two mixins.
        boolean vanillaTypes = ShaderCompat.vanillaGeometry();
        CarrierGeometry.StagePlan plan = CarrierGeometry.StagePlan.of(vanillaTypes);
        if (!stage.collectsEverything()) {
            runCarriedStage(level, player, event, cameraPos, gates, active, plan);
            return;
        }

        List<Visible> visible = active ? collect(level, player, cameraPos) : List.of();
        // BOTH COLLECTIONS HAPPEN HERE, AND SO DOES THE FRAME SUMMARY.
        //
        // The summary used to be written from the carried stage, which made it depend on a second
        // callback arriving - and when dispatch was keyed on a stage name that never matched, the
        // *diagnostic* went silent along with the feature, so the log could not report its own failure.
        // A diagnostic must hang on the unconditional path: this stage is dispatched once per frame
        // whenever the level renders, carries the heartbeat in CarrierRenderTrace#frameSummary, and is
        // therefore the only place a summary can be written from and still be trusted to appear.
        //
        // The carried list costs one walk over the occupied sections and is the same list the carried
        // stage draws, so collecting it here changes nothing about the picture.
        CarriedResult collected = active
                ? collectCarried(level, player, cameraPos, event.getPartialTick().getGameTimeDeltaPartialTick(false))
                : CarriedResult.empty();
        lastCarried = collected;
        reportFrame(level, collected, gates, active);

        // The mesher has to know exactly which blocks the overlay draws: one that is hidden from the
        // mesh and then not drawn is a hole in the world, and one that is drawn while still in the mesh
        // fights it. Publishing every frame also covers the overlay being switched off, which is what
        // puts the blocks back.
        // Without shaders nothing leaves the mesh, so nothing is published: tracking the drawn set then
        // would only mark sections dirty as the player looks around.
        // Only world hosts are published: a carried host's block is not in the chunk mesh at all (its
        // chunk has no such block any more), so there is nothing to hide.
        List<BlockPos> drawn = new ArrayList<>();
        if (vanillaTypes) {
            for (Visible host : visible) {
                drawn.add(host.pos());
            }
        }
        ShaderCompat.publishHosts(drawn, vanillaTypes);

        // THE CARRIED HALF, when the plan says this is its stage. Collected above; drawn here, before
        // anything this stage submits, so that whatever submits first is not disturbed by geometry
        // written into another source.
        CarriedResult carriedHere = carriedFor(plan, active, collected);
        boolean carriedDrawnHere = plan.withWorld();

        if (visible.isEmpty() && !carriedDrawnHere) {
            // The world half of this frame is empty. There is nothing for this stage to flush, so the
            // carried types must NOT be ended here: their vertices have not been written yet, and an
            // endBatch now would throw the frame's carried geometry away.
            //
            // The readings are still RECORDED, with a zero counter, and that is not tidiness: leaving the
            // previous frame's numbers in place made `worldEmittedQuads` (42) disagree with a freshly
            // flushed ledger (0) on every frame in which only the carried path had anything to draw, and
            // the module then reported its own stale bookkeeping as "WORLD STAGE WROTE NOTHING INTO ITS
            // OWN BUFFER" 305 times. A per-frame reading must be written every frame or it is not a
            // reading.
            recordWorldStage(new Counter());
            return;
        }

        BlockPos selected = WrenchClientState.selection();
        BlockPos hovered = hoveredHost(minecraft, player);

        AtlasUv uv = atlasUv();
        PoseStack pose = event.getPoseStack();
        // The pose the stage was entered on. Everything below runs inside a try/finally that puts the
        // stack back on exactly this object, because vanilla's own LevelRenderer#checkPoseStack throws
        // "Pose stack not empty" at the end of a frame whose stack is crooked - and that is how placing
        // a torch inside a block crashed the game: the first frame with a host reached this push, and its
        // pop had been lost when the readings were extracted into recordWorldStage. Identity, not a
        // guess at the depth: a pose is only ever shared between a push and its matching pop.
        PoseStack.Pose entry = pose.last();
        pose.pushPose();
        pose.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);
        PoseStack.Pose last = pose.last();

        // WHAT WAS ACTUALLY EMITTED, counted where it is emitted. Every one of these numbers used to be
        // inferred from `carried.size()`, which counts collected hosts - see CarrierGeometry.
        Counter counter = new Counter();
        // The carried half's own counter, when this stage owns it. Declared out here because the
        // readings are written after the pose is unwound, exactly as the late stage writes them.
        Counter carriedHereCounter = new Counter();

        try {
            // Order matters. The components are drawn first: with a shader pack they are opaque cutout
            // geometry that writes depth, and the block's own inside-out model is then drawn behind them.
            if (vanillaTypes) {
                drawHostInteriors(pose, level, visible, counter);
            }
            if (gates.drawsContents()) {
                drawContents(pose, level, visible, uv, counter);
            }
            drawShells(last, level, visible, uv, gates, counter);
            drawHighlight(last, level, selected, SELECTED_FRAME, uv);
            drawHighlight(last, level, hovered, HOVER_FRAME, uv);

            if (carriedDrawnHere) {
                CarrierGeometry.RenderPlan render = CarrierGeometry.RenderPlan.of(vanillaTypes);
                // The shell first, so the contents are crisp on top of it - the order the carried stage
                // has always used and the reason `CARRIED_FLUSH_ORDER` exists.
                drawCarriedShellsInto(pose, level, carriedHere.hosts(), uv, gates, carriedHereCounter,
                        cameraPos, render);
                if (gates.drawsContents()) {
                    drawCarriedContentsInto(pose, level, carriedHere.hosts(), uv, carriedHereCounter,
                            cameraPos, render);
                }
                carriedHereCounter.hostsCollected = carriedHere.hosts().size();
                carriedHereCounter.hostsDrawn = Math.max(carriedHereCounter.hostsWithShell,
                        carriedHereCounter.hostsWithContent);
                carriedHereCounter.acceptProbe();
                recordCarriedStage(carriedHereCounter);
            }

            // Submit this stage's own sources BEFORE the pose is unwound, so the counter is the one that
            // was filled.
            recordWorldStage(counter);
        } finally {
            restorePose(pose, entry);
        }
        if (carriedDrawnHere) {
            // The flush lines are written after the submission so they describe this frame's flush; the
            // geometry line is the same pairing the late stage writes, and the stage name in it is the
            // one this frame really used (see CarrierGeometry.StagePlan).
            CarrierRenderTrace.flushStages(level.dimension(), flushStageReport());
            CarrierRenderTrace.matrixProbe(level.dimension(), carriedHereCounter.matrixProbeLine(),
                    level.getGameTime());
            CarrierRenderTrace.geometry(level.dimension(),
                    CarrierGeometry.Emitted.of(
                            worldShellEmittedQuads + carriedHereCounter.shellQuads,
                            worldOwnEmittedQuads + carriedHereCounter.contentQuads,
                            worldCounter.hostsWithShell + carriedHereCounter.hostsWithShell,
                            worldCounter.hostsWithContent + carriedHereCounter.hostsWithContent,
                            worldCounter.hostsCollected + carriedHereCounter.hostsCollected,
                            gateReason(gates, true), gateReason(gates, false),
                            carriedHereCounter.zeroShellReason().isEmpty()
                                    ? worldCounter.zeroShellReason()
                                    : carriedHereCounter.zeroShellReason()),
                    gates.describeWithVerdict() + effectiveColours(gates),
                    carriedHereCounter.example(List.of(), carriedHere.hosts(), gates),
                    carriedHereCounter.matrixProbeLine());
        }
    }

    /**
     * The carried collection the world stage should draw, when the plan makes it that stage's job.
     *
     * <p>One answer, and the reason it is a method rather than an expression at the call site: the list
     * is only ever non-empty when the pass is active, and "the overlay is switched off" has to reach the
     * drawing code as an empty list rather than as "the list that happened to be collected last frame".
     */
    private static CarriedResult carriedFor(CarrierGeometry.StagePlan plan, boolean active,
                                            CarriedResult collected) {
        if (!plan.withWorld()) {
            return CarriedResult.empty();
        }
        return active ? collected : CarriedResult.empty();
    }

    /**
     * <b>Submits the world source and records this frame's world readings - always, from one place.</b>
     *
     * <p>One method because the readings are a per-frame statement: {@code worldEmittedQuads} is what the
     * drawing loops handed out <em>this</em> frame and the flushed numbers are what the source held at
     * <em>this</em> frame's submission. Writing them from two call sites is how they came apart - the
     * empty-world path submitted the batch but left last frame's emitted count behind, and the alarm read
     * the difference as a defect. There is now exactly one place that can set them, and it is called on
     * every path through the world stage.
     */
    private static void recordWorldStage(Counter counter) {
        HostRenderTypes.endWorldBatch();
        worldCounter = counter;
        worldFlushedThisSession = true;
        // What the loops emitted for OUR source: the contents, plus the block interiors the shader path
        // draws by hand. The shell is not here - see `worldShellEmittedQuads`.
        worldOwnEmittedQuads = counter.contentQuads + counter.interiorQuads;
        worldOwnFlushedQuads = HostBufferTally.flushedQuads(HostBufferTally.Sink.WORLD,
                HostBufferTally.Kind.CONTENTS)
                + HostBufferTally.flushedQuads(HostBufferTally.Sink.WORLD,
                        HostBufferTally.Kind.INTERIOR);
        worldContentQuadsFlushed = HostBufferTally.flushedQuads(HostBufferTally.Sink.WORLD,
                HostBufferTally.Kind.CONTENTS);
        // The shell, reported apart because its submitter is not us. Under a shader pack it really is in
        // our source and lands in `worldOwnFlushedQuads` above as a third kind; without one it is a
        // hand-over we can only count on the way out.
        worldShellEmittedQuads = counter.shellQuads;
        worldShellHandedOverQuads = HostBufferTally.handedOverQuads(HostBufferTally.Sink.RENDERER,
                HostBufferTally.Kind.SHELL);
    }

    /**
     * Puts the pose stack back on the pose a stage was entered on, whatever happened in between.
     *
     * <p>Vanilla's {@code LevelRenderer#checkPoseStack} throws {@code IllegalStateException("Pose stack
     * not empty")} at the end of a frame whose pose stack is not back to its base, and that check is not
     * a warning: it takes the game down. The world stage pushed a pose and lost its pop when the
     * per-frame readings were extracted into {@link #recordWorldStage}, so the first frame that actually
     * had a host to draw - the frame a redstone torch is placed inside a block - crashed on the spot.
     *
     * <p>The try/finally at both stage call sites is the fix; this is the guarantee behind it. The stack
     * is walked back until the top <em>is</em> the pose the stage entered on, which is identity rather
     * than a count of pushes, so a draw call that throws mid-way - or a future edit that adds a push
     * without a pop - cannot leave the frame crooked. Anything beyond the single push a stage is allowed
     * is reported, because a stage that had to pop more than once did something it should not have.
     *
     * <p>The rule itself lives in {@link PoseStackGuard} - a class free of this package's other types so
     * that it can be tested without a game running. This wrapper is only here to report.
     */
    private static void restorePose(PoseStack pose, PoseStack.Pose entry) {
        int popped = PoseStackGuard.restore(pose, entry);
        if (popped > 1) {
            RCConfig.LOGGER.warn("[redstonecircuit] client: the pose stack was {} level(s) deeper than "
                    + "this stage left it, so {} extra level(s) were popped - without that, vanilla's own "
                    + "end-of-frame check throws 'Pose stack not empty' and takes the game down. A push "
                    + "without a pop is a bug, not a tidiness issue", popped, popped - 1);
        }
    }

    /**
     * The carried stage: the hosts riding a moving structure, drawn and flushed as one frame.
     *
     * <p>Deliberately thin about reporting: the frame summary is written by the <b>world</b> stage (see
     * {@code runStage}), so that it appears whether or not this stage ever runs. All this stage adds is
     * the flush counts, which only exist once it has drawn.
     *
     * <h2>When this stage is only a flush</h2>
     * With a shader pack the carried geometry was already drawn and submitted by the world stage - see
     * {@link CarrierGeometry.StagePlan} - and the reason this stage must then still <b>run</b> is that a
     * flush line is a per-frame reading: a stage that silently stopped writing its readings when the
     * shader path came on would be the same class of defect as the diagnostic that went silent with the
     * feature it reported (see {@code CarrierRenderTrace}). So it writes the same lines with the same
     * numbers, which is exactly what makes {@code carriedQuadsFlushedAt} trustworthy in both modes.
     */
    private static void runCarriedStage(ClientLevel level, LocalPlayer player,
                                        RenderLevelStageEvent event, Vec3 cameraPos,
                                        CarrierGeometry.Gates gates, boolean active,
                                        CarrierGeometry.StagePlan plan) {
        // THE EARLY MODE: the world stage already drew and flushed this frame's carried geometry, so
        // there is nothing left to draw and drawing it again would put a second copy of every component
        // into a batch that has been submitted.
        if (plan.withWorld()) {
            CarrierRenderTrace.flushStages(level.dimension(), flushStageReport());
            CarrierRenderTrace.matrixProbe(level.dimension(), carriedCounter.matrixProbeLine(),
                    level.getGameTime());
            return;
        }

        CarriedResult collected = lastCarried == null
                ? (active ? collectCarried(level, player, cameraPos, event.getPartialTick().getGameTimeDeltaPartialTick(false))
                        : CarriedResult.empty())
                : lastCarried;
        List<Carried> carried = collected.hosts();

        if (carried.isEmpty()) {
            // Nothing was written, but the buffer is submitted anyway: a frame whose carried hosts all
            // disappeared must not leave the previous frame's vertices queued for the next endBatch. The
            // readings are recorded here too, for the same reason the world stage records them on its
            // empty path - a per-frame reading that is only written sometimes is not a reading.
            recordCarriedStage(new Counter());
            CarrierRenderTrace.flushStages(level.dimension(), flushStageReport());
            return;
        }

        AtlasUv uv = atlasUv();
        PoseStack pose = event.getPoseStack();
        PoseStack.Pose entry = pose.last();
        pose.pushPose();
        pose.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);

        Counter counter = new Counter();
        CarrierGeometry.RenderPlan render = CarrierGeometry.RenderPlan.of(ShaderCompat.vanillaGeometry());
        try {
            // The shell is drawn first so that its own call site can take the matrix probe before the
            // contents do - see Counter.probe, which takes the first probe from the contents call site
            // when it gets there. A frame drawn the other way round would report the contents' matrix
            // with no shell matrix to compare it against, and the shellContentGap line would go quiet
            // exactly when the two call sites disagreeing is the thing worth knowing.
            drawCarriedShellsInto(pose, level, carried, uv, gates, counter, cameraPos, render);
            if (gates.drawsContents()) {
                // Both carried types ignore depth, so their order in the buffer is their order on
                // screen: drawing the contents last keeps the component crisp instead of washing it out
                // under the frosted panel that is there to frame it.
                drawCarriedContentsInto(pose, level, carried, uv, counter, cameraPos, render);
            }
        } finally {
            // Same guarantee as the world stage's: this stage's push is matched on every way out, even a
            // throwing draw call. See restorePose.
            restorePose(pose, entry);
        }
        carriedCounter = counter;

        counter.hostsCollected = carried.size();
        counter.hostsDrawn = Math.max(counter.hostsWithShell, counter.hostsWithContent);
        counter.acceptProbe();

        // THE FLUSH ITSELF, and the reason the carried path has a stage of its own: everything the
        // structure draws is already in the frame by now, so these quads land on top of it instead of
        // under it. Content first, shell second - both types ignore depth, so this order is the whole of
        // the answer to "which of the two is visible where they overlap". See HostRenderTypes.
        recordCarriedStage(counter);
        // Recorded after the submission, so the line the next frame summary carries describes this
        // frame's flush rather than the one before it.
        CarrierRenderTrace.flushStages(level.dimension(), flushStageReport());

        CarrierRenderTrace.matrixProbe(level.dimension(), counter.matrixProbeLine(),
                level.getGameTime());
        CarrierRenderTrace.geometry(level.dimension(),
                CarrierGeometry.Emitted.of(
                        worldShellEmittedQuads + counter.shellQuads,
                        worldOwnEmittedQuads + counter.contentQuads,
                        worldCounter.hostsWithShell + counter.hostsWithShell,
                        worldCounter.hostsWithContent + counter.hostsWithContent,
                        worldCounter.hostsCollected + counter.hostsCollected,
                        gateReason(gates, true), gateReason(gates, false),
                        counter.zeroShellReason().isEmpty() ? worldCounter.zeroShellReason()
                                : counter.zeroShellReason()),
                gates.describeWithVerdict() + effectiveColours(gates),
                counter.example(List.of(), carried, gates), counter.matrixProbeLine());
    }

    /**
     * <b>The compact numbers, plus the guidance only when this frame's shape differs from the last.</b>
     *
     * <p>The one call the renderer makes. {@link #flushStageLine()} is the per-frame half;
     * {@link #flushStageExplanation()} is the three paragraphs of guidance, appended here only when
     * {@link #frameExplanationDue()} says the frame's shape changed - the first frame of a session, a frame
     * whose stage or flush state moved, or any frame with an alarm. The author's 5.8 MB session was mostly
     * those paragraphs, byte-identical, re-printed every frame the counters moved.
     */
    static String flushStageReport() {
        return flushStageLine() + (frameExplanationDue() ? flushStageExplanation() : "");
    }

    /**
     * <b>The answer to "was the carried geometry flushed after the structure was drawn".</b>
     *
     * <p>Built from the numbers this pass recorded at each {@code endBatch}, and naming the NeoForge
     * stage each one ran at. A reader compares the carried stage against the world stage: the carried
     * one must be the later, or a host riding a machine is covered by the machine's own blocks however
     * many quads it emitted.
     *
     * <p><b>Which stage that is depends on the shader path</b>, and this line reports the one that was
     * really used: with a pack the carried geometry is submitted with the world path (see
     * {@link CarrierGeometry.StagePlan}), so {@code carriedQuadsFlushedAt} reads
     * {@code minecraft:after_entities} and the fix is confirmed from the log rather than from the
     * picture. Without one it reads the late stage, exactly as before.
     *
     * <p><b>Numbers only, and that is deliberate.</b> This line is written for every frame summary - once
     * a second in the author's session - so it carries nothing that does not change between frames. The
     * three paragraphs of {@code *** … ***} guidance that used to be appended here (the carried-stage
     * ordering rule, the equality rule, the world-shell exception) are how to <em>read</em> these numbers;
     * they live in {@link #flushStageExplanation()} and are appended only when
     * {@link #frameExplanationDue()} says the frame's shape changed. Those paragraphs, unchanged and
     * re-printed every second, were the bulk of a 5.8 MB log.
     *
     * <p>The stage names are properties of the build and are printed whether or not the stage has run;
     * the counts and one word, {@code flushed=fresh|carried-over}, are properties of the frame. A stage
     * that has not run since the client started says so rather than printing a zero that reads exactly
     * like "it ran and wrote nothing" - which is the misreading this whole diagnostic exists to prevent.
     *
     * <p>The world shell has no stage of ours to name, and says so rather than looking like a stage that
     * never ran: it keeps vanilla's translucent render type identity on purpose, so the level renderer's
     * own translucent section layer flushes it - after both of ours.
     */
    static String flushStageLine() {
        CarrierGeometry.StagePlan plan =
                CarrierGeometry.StagePlan.of(ShaderCompat.vanillaGeometry());
        return "worldQuadsFlushedAt=" + Stage.WORLD.stageName() + " worldOwnQuads=" + worldOwnFlushedQuads
                + " (worldOwnEmittedQuads=" + worldOwnEmittedQuads + " worldContentQuads="
                + worldContentQuadsFlushed + ")"
                + (worldFlushedThisSession ? "" : " flushed=NOT-YET-THIS-SESSION(0 is 'not yet', not 'wrote "
                        + "nothing')")
                + " worldShellQuads=" + worldShellEmittedQuads + " worldShellHandedOverQuads="
                + worldShellHandedOverQuads + " (" + WORLD_SHELL_STAGE + ")"
                + " carriedQuadsFlushedAt=" + plan.stageName() + " carriedQuads="
                + carriedQuadsFlushed + " (carriedShellQuads=" + carriedShellQuadsFlushed
                + " carriedContentQuads=" + carriedContentQuadsFlushed + " carriedEmittedQuads="
                + carriedEmittedQuads + ")"
                + (carriedFlushedThisSession ? ""
                        : " flushed=NOT-YET-THIS-SESSION(0 is 'not yet', not 'wrote nothing')")
                + " " + HostBufferTally.describe();
    }

    /**
     * <b>The guidance, as one string, printed when the frame's shape changes rather than every frame.</b>
     *
     * <p>Three facts a reader needs in order not to misread the line above, none of which changes between
     * two frames of one session:
     *
     * <ul>
     *   <li>the carried stage must be the <b>later</b> of the two unless a shader pack is drawing, in
     *       which case it is the same stage on purpose;</li>
     *   <li>{@code worldOwnQuads} must equal {@code worldOwnEmittedQuads}, and {@code carriedQuads} must
     *       equal {@code carriedEmittedQuads} - where they differ, the geometry went into a batch that had
     *       already been submitted;</li>
     *   <li>the world <b>shell</b> is deliberately outside that equation: it is handed to
     *       {@code LevelRenderer}, so it has a handed-over count and no flush of ours.</li>
     * </ul>
     *
     * <p>Appended by the caller only when {@link #frameExplanationDue()} says so - the first frame of a
     * session, a frame whose shape differs from the previous one, and any frame with an alarm
     * ({@link #flushAlarms()} is never gated).
     */
    static String flushStageExplanation() {
        CarrierGeometry.StagePlan plan =
                CarrierGeometry.StagePlan.of(ShaderCompat.vanillaGeometry());
        return " *** the carried stage must be the LATER of the two unless a shader pack is drawing, "
                + "where it is the SAME stage as the world path on purpose: " + plan.why()
                + ". If carriedQuadsFlushedAt reads as the world stage without a pack, a host riding a "
                + "structure is covered by that structure's own blocks. worldOwnQuads must equal "
                + "worldOwnEmittedQuads and carriedQuads must equal carriedEmittedQuads; where they "
                + "differ the geometry was written into a batch that had already been submitted. The "
                + "world SHELL is deliberately outside that equation: it is handed to LevelRenderer, so "
                + "it has a handed-over count and no flush of ours ***";
    }

    /**
     * Whether this frame's explanation has to be printed: the first frame of a session, a frame whose
     * shape differs from the previous one, or a frame carrying an alarm.
     *
     * <p>One string comparison per frame in the ordinary case, and a bounded one: the shape string is a
     * handful of names and booleans the renderer already has. The state is process-lifetime and
     * fixed-size, so nothing here grows with the session.
     */
    static boolean frameExplanationDue() {
        String shape = frameShape();
        boolean due = !shape.equals(lastFrameShape);
        lastFrameShape = shape;
        return due;
    }

    /**
     * The part of a frame the explanation is about: which stage each half reached, whether each half has
     * flushed at all this session, and the alarm signature.
     *
     * <p>Deliberately <b>not</b> the quad counts: a machine turning on the spot changes its quad counts
     * every frame, and a gate keyed on those would print the guidance at frame rate - the defect this
     * exists to fix, in the other direction.
     */
    private static String frameShape() {
        return Stage.WORLD.stageName() + '|' + Stage.CARRIED.stageName()
                + "|worldFlushed=" + worldFlushedThisSession
                + "|carriedFlushed=" + carriedFlushedThisSession
                + "|alarms=" + flushAlarms();
    }

    /** The last frame shape the explanation was printed for; see {@link #frameExplanationDue()}. */
    private static String lastFrameShape = "";

    /** Forgets the gate, so a test (or a new session) explains itself from the first frame again. */
    static void resetFrameLogForTest() {
        lastFrameShape = "";
    }

    /**
     * The gate's seam: makes the previous frame look like a different verdict, so a test can drive the
     * transition without a render thread.
     *
     * <p>A changed stage or an alarm is what really re-opens the gate, and both are produced by the
     * renderer; this stands in for "the previous frame's verdict was different" and nothing else.
     */
    static final java.util.function.Consumer<String> NOTE_FOR_TEST =
            shape -> lastFrameShape = shape == null ? "" : shape;

    /**
     * <b>The alarms: geometry that was emitted and never submitted.</b>
     *
     * <p>Two independent facts, either of which is a defect on its own, and which together name the
     * parent defect exactly:
     *
     * <ul>
     *   <li>a stage emitted quads and flushed none of its own - {@link HostBufferTally#nothingFlushedWhileEmitting};</li>
     *   <li>vertices were written into a source <em>after</em> that source was flushed, which is the
     *       signature of a draw point aiming at a finished batch.</li>
     * </ul>
     *
     * <p>Empty on every healthy frame, so its presence in a log is the finding rather than a hint.
     */
    static String flushAlarms() {
        StringBuilder sb = new StringBuilder();
        String carriedAlarm = HostBufferTally.nothingFlushedWhileEmitting(HostBufferTally.Sink.CARRIED,
                carriedEmittedQuads, carriedOwnFlushedQuads);
        if (carriedAlarm != null) {
            sb.append(' ').append(carriedAlarm);
        }
        // The wrong-buffer detector, for either of our sources. A draw point writing into a source we have
        // already submitted is the §17 defect, and this catches it even on a frame where the stage also
        // submitted something of its own.
        if (HostBufferTally.postFlushWrites(HostBufferTally.Sink.WORLD) > 0) {
            sb.append(" *** WRONG BUFFER: vertices were written into the WORLD source after this build "
                    + "submitted it at ").append(Stage.WORLD.stageName()).append(", which is where a draw "
                    + "point that should have used CARRIED_BUFFERS ends up ***")
                    .append(HostBufferTally.postFlushSentence());
        }
        if (HostBufferTally.postFlushWrites(HostBufferTally.Sink.CARRIED) > 0) {
            sb.append(" *** WRONG BUFFER: vertices were written into the CARRIED source after this build "
                    + "submitted it at ").append(Stage.CARRIED.stageName()).append(" ***")
                    .append(HostBufferTally.postFlushSentence());
        }
        // ONLY the world geometry this build submits. The world shell is excluded by construction - the
        // pair passed here is contents+interiors against the same two kinds' flush - so a frame that drew
        // only the shell can no longer be read as a defect. See HostBufferTally.Sink#RENDERER.
        String worldAlarm = HostBufferTally.nothingFlushedWhileEmitting(HostBufferTally.Sink.WORLD,
                worldOwnEmittedQuads, worldOwnFlushedQuads);
        if (worldAlarm != null) {
            sb.append(' ').append(worldAlarm);
        }
        return sb.toString();
    }

    /**
     * Submits the carried source and records this frame's carried readings; see {@link #recordWorldStage}.
     *
     * <p>Both kinds in this source are ours to submit, so the pair here covers the whole carried picture:
     * {@code carriedEmittedQuads} is what the loops handed out and {@code carriedOwnFlushedQuads} is what
     * the source held when it was submitted. They agree on every healthy frame.
     */
    private static void recordCarriedStage(Counter counter) {
        HostRenderTypes.endCarriedBatch();
        carriedCounter = counter;
        carriedFlushedThisSession = true;
        carriedEmittedQuads = counter.shellQuads + counter.contentQuads;
        carriedOwnFlushedQuads = HostBufferTally.flushedQuads(HostBufferTally.Sink.CARRIED,
                HostBufferTally.Kind.SHELL)
                + HostBufferTally.flushedQuads(HostBufferTally.Sink.CARRIED,
                        HostBufferTally.Kind.CONTENTS);
        carriedQuadsFlushed = carriedOwnFlushedQuads;
        carriedShellQuadsFlushed = HostBufferTally.flushedQuads(HostBufferTally.Sink.CARRIED,
                HostBufferTally.Kind.SHELL);
        carriedContentQuadsFlushed = HostBufferTally.flushedQuads(HostBufferTally.Sink.CARRIED,
                HostBufferTally.Kind.CONTENTS);
    }

    /**
     * The frame counters, recorded from the carried stage where the carried hosts are known.
     *
     * <p>Split out of {@code collectCarried} so that the collection pass has no logging side effect:
     * a collector that also writes the frame summary cannot be called twice in a frame, which is
     * exactly what the two-stage split requires.
     */
    private static void reportFrame(ClientLevel level, CarriedResult collected,
                                    CarrierGeometry.Gates gates, boolean active) {
        if (!active) {
            // THE PASS IS OFF, AND THE LOG SAYS SO. This used to return in silence, which made "the
            // overlay is switched off" and "the overlay is on and nothing reaches the screen" the same
            // log: a session with no frame summary at all. The author's 2026-09-28 log is that shape -
            // the machine was carried for twelve seconds with no summary written - so it could not say
            // which of the two it was. Same throttle, same clock, zeros, and the gates' own sentence,
            // which names the mode, the goggles and which of them closed the pass.
            CarrierRenderTrace.frameSummaryInactive(level.dimension(),
                    gates.describeWithVerdict() + effectiveColours(gates), level.getGameTime());
            return;
        }
        int drawn = collected.hosts().size();
        // THE CLIENT'S OWN CLOCK, passed down so the diagnostics can space their lines out in ticks rather
        // than in frames: "at most one line per second" has to mean a second whatever frame rate the
        // player's machine runs at, and the frame-counted throttle this class used to rely on is what
        // turned one parked machine into 26,668 identical log lines (see CarrierRenderTrace).
        long nowTicks = level.getGameTime();
        CarrierRenderTrace.frame(level.dimension(), drawn, collected.skippedNoPose(),
                collected.skippedFar(), collected.skippedStalePose(), collected.landedWorldHost(),
                collected.landedDeferred(), collected.carriedWorldHost(),
                collected.drawnOffStructure(), collected.plotSpaceHosts(),
                collected.worstPoseAgeTicks(), collected.sight().tally());
        CarrierRenderTrace.frameSummary(level.dimension(), drawn, collected.skippedNoPose(),
                collected.skippedFar(), collected.skippedStalePose(), collected.landedWorldHost(),
                collected.landedDeferred(), collected.carriedWorldHost(),
                collected.worstPoseAgeTicks(), nowTicks);
        // The worked example, logged on a change and otherwise at most once a second - the same
        // throttle the frame summary uses. See CarrierDrawCheck for how to read the pair of positions,
        // and `poseAge` for whether the pose they were computed from is current.
        CarrierRenderTrace.drawCheck(level.dimension(), collected.example(),
                collected.drawnOffStructure(), collected.plotSpaceHosts(),
                collected.worstPoseAgeTicks(), nowTicks);
        // The worked B2 example: recorded every frame, written when the window opens. The line is built
        // by the collector (see CarriedResult#sight), so a frame that asked no sight question records
        // nothing rather than reusing the last line it did ask.
        CarrierRenderTrace.sightDetail(level.dimension(), collected.sight().describe());
        CarrierRenderTrace.sight(level.dimension(), nowTicks);
        // B1 LAST, and never before the two calls above: the origin walk's rollup reports the renderer's
        // own skippedStalePose/skippedFar, which the `frame` call has just recorded, so the rollup and
        // the frame summary it sits beside are two views of one frame rather than two frames.
        CarrierRenderTrace.originSweep(level.dimension(), nowTicks, SECTION_RADIUS,
                sweepShift(collected));
    }

    /**
     * The point the origin walk's radius is measured from, for the B1 sweep.
     *
     * <p>The renderer's own answer is {@code BlockPos.containing(cameraPos)}, and the eye the sight
     * question was asked from is the same point - so the sweep is handed the eye the frame already
     * recorded rather than a fresh camera read that could be a frame newer. A frame that asked no sight
     * question (nothing was carried) has no eye to hand and the sweep falls back to the camera entity's
     * position, which is what the collector would have used.
     */
    @Nullable
    private static BlockPos sweepShift(CarriedResult collected) {
        CarrierHostCheck example = collected.sight().example();
        if (example != null && example.eye() != null) {
            return example.eye();
        }
        var camera = Minecraft.getInstance().getCameraEntity();
        return camera == null ? null : BlockPos.containing(camera.getEyePosition());
    }

    /** The last carried collection, kept so the frame report can be written from the same numbers. */
    @Nullable
    private static CarriedResult lastCarriedResult;

    /** The counter the last frame's world stage filled, for the frame summary. */
    private static Counter worldCounter = new Counter();

    /** A fresh counter, for tests that drive the matrix probes without a level. */
    static Counter newCounter() {
        return new Counter();
    }

    /** Takes one probe, for tests that drive the matrix probes without a level. */
    static void probeForTest(Counter counter, String label, String carrier, PoseStack.Pose pose,
                             CarrierFrame frame, float[] base, Vector3d camera) {
        counter.probe(label, carrier, pose, frame, base, camera);
    }

    /** The probe line a counter has accumulated, for tests that drive the matrix probes. */
    static String probeLineForTest(Counter counter) {
        counter.acceptProbe();
        return counter.matrixProbeLine();
    }

    /** The counter the last frame's carried stage filled, for the frame summary. */
    private static Counter carriedCounter = new Counter();

    /**
     * The carried collection the world stage made this frame, for the carried stage to draw.
     *
     * <p>The world stage collects both lists so that the frame summary - the diagnostic - hangs on the
     * one stage that is certain to run; the carried stage then draws that same list rather than
     * collecting a second, possibly different, one. Null only before the first frame of a session, in
     * which case the carried stage collects for itself.
     */
    @Nullable
    private static CarriedResult lastCarried;

    /**
     * Quads of world geometry this build is responsible for submitting, and how many it submitted.
     *
     * <p>The pair that must agree. {@code worldOwnEmittedQuads} counts the world <b>contents</b> (and the
     * block interiors under a shader pack) - everything the loops wrote into our own source - and
     * {@code worldOwnFlushedQuads} is what that source held when we submitted it. The world <b>shell</b>
     * is deliberately not in either: without a shader pack it goes into the level renderer's source,
     * which we never submit, and holding it to this equation is what produced 305 false alarms in one
     * session. See {@link HostBufferTally.Sink}.
     */
    private static int worldOwnEmittedQuads;
    private static int worldOwnFlushedQuads;

    /** Quads of world contents only, for a reader who wants the component number on its own. */
    private static int worldContentQuadsFlushed;

    /**
     * The world shell: emitted, and handed over.
     *
     * <p>Reported as its own pair because its submitter is not this build. Without a shader pack the
     * handed-over count is what we gave {@code LevelRenderer}; with one, the shell is in our own source
     * after all, and the handed-over count is zero while the shell's quads appear in the flushed figure
     * above.
     */
    private static int worldShellEmittedQuads;
    private static int worldShellHandedOverQuads;

    /**
     * The stage the world path <em>is</em> flushed at, and whether it has run yet.
     *
     * <p>Two separate facts on purpose. The stage name is a property of the build - the pass always runs
     * at {@code after_entities} - so a diagnostic can print it before the first frame; the quad count
     * and the "already flushed" flag are properties of the frame. Collapsing them into one string made
     * the line unable to say "this is where it will flush" and "this is what it flushed" at the same
     * time, which is the pair a reader needs on the very first frame of a session.
     */
    private static boolean worldFlushedThisSession;

    /** How many quads the carried stage flushed, and at which stage. */
    private static int carriedQuadsFlushed;
    private static int carriedShellQuadsFlushed;
    private static int carriedContentQuadsFlushed;

    /**
     * Quads the carried stage's drawing loops handed out, and what its source held when submitted.
     *
     * <p>Both sink kinds are ours here, so this pair covers the whole of the carried picture.
     */
    private static int carriedEmittedQuads;
    private static int carriedOwnFlushedQuads;

    /** Whether the carried stage has run since the client started; see {@link #worldFlushedThisSession}. */
    private static boolean carriedFlushedThisSession;

    /**
     * The two stages this pass draws in, and the only place that knows which event belongs to which.
     *
     * <p>A small enum rather than two comparisons at the call site because the tests have to be able to
     * ask the same question without a {@code RenderLevelStageEvent}, which cannot be constructed
     * outside the game: the mapping is the contract, and it is checked by name.
     *
     * <p>Deliberately <b>without</b> the NeoForge constants as enum arguments: an enum's arguments are
     * evaluated in the class initialiser, and reading {@code RenderLevelStageEvent.Stage} there drags
     * in {@code RenderType} and the whole vanilla registry bootstrap. See {@link #stageNamesMatchTheDispatcher()}.
     */
    enum Stage {

        /** The world path: {@code AFTER_ENTITIES}, before the translucent chunk layer. */
        WORLD(WORLD_STAGE_NAME, 1),

        /**
         * The carried path: after every structure the game draws has been drawn.
         *
         * <p>{@code AFTER_TRANSLUCENT_BLOCKS} is dispatched after {@code renderSectionLayer(translucent)}
         * returns, so the whole opaque and translucent terrain - the contraption included, because
         * Flywheel's visual pass runs at {@code blockentities} - is already in the frame.
         */
        CARRIED(CARRIED_STAGE_NAME, 6);

        private final String stageName;
        private final int order;

        Stage(String stageName, int order) {
            this.stageName = stageName;
            this.order = order;
        }

        /** The name this build <b>prints</b> for the stage, namespace and all; see {@link #WORLD_STAGE_NAME}. */
        String stageName() {
            return stageName;
        }

        /**
         * Where this stage sits in {@code LevelRenderer.renderLevel}, as an explicit rank.
         *
         * <p>The numbers are the order of the {@code RenderLevelStageEvent.Stage} constants as
         * {@code Stage}'s own class initialiser registers them - sky, solid, cutout-mipped, cutout,
         * entities, block entities, translucent, tripwire, particles, weather, level - and they exist
         * so that "the carried flush really is later" is an assertion a unit test can make with no game
         * running. Only their order is a contract; the values are not.
         */
        int order() {
            return order;
        }

        /** Whether this stage draws the hosts standing in the world, as opposed to the carried ones. */
        boolean collectsEverything() {
            return this == WORLD;
        }
    }

    /**
     * Why the shell could not be drawn, for the diagnostics; {@code null} when it could.
     *
     * <p>The same gates the drawing loop consults, asked once more so the frame summary's zero can be
     * read without guessing which of them did it.
     */
    @Nullable
    private static String gateReason(CarrierGeometry.Gates gates, boolean shell) {
        return shell ? gates.shellEmptiness(null) : gates.contentsEmptiness();
    }

    /** The gates as they really applied, for the diagnostics: the colours a forced frame substituted. */
    private static String effectiveColours(CarrierGeometry.Gates gates) {
        if (!gates.forceEmit()) {
            return "";
        }
        return " (forceOverlayDraw is ON: the shell is drawn with tint="
                + CarrierGeometry.hex(gates.effectiveTint()) + " frame="
                + CarrierGeometry.hex(gates.effectiveFrame(null)) + " so it cannot be mistaken for a "
                + "configuration)";
    }

    /** The last dimension whose "the overlay is off" line was written, so it is a transition. */
    @Nullable
    private static ResourceKey<Level> inactiveLogged;

    /** One line when the overlay is not running, and one when it starts again. */
    private static void logInactiveOnce(ResourceKey<Level> dimension, CarrierGeometry.Gates gates) {
        String reason = gates.inactiveReason();
        if (reason == null) {
            if (inactiveLogged != null) {
                inactiveLogged = null;
                RCConfig.LOGGER.info("[redstonecircuit] client: host overlay ACTIVE again - {}",
                        gates.describe());
            }
            return;
        }
        if (dimension.equals(inactiveLogged)) {
            return;
        }
        inactiveLogged = dimension;
        RCConfig.LOGGER.info("[redstonecircuit] client: host overlay is NOT drawing - {}. {}",
                reason, gates.describe());
    }

    /**
     * What one frame actually wrote into the vertex buffers.
     *
     * <p>The distinction this type exists for: {@code hostsCollected} is what the old
     * {@code drawnLastFrame} counted, and {@code shellQuads}/{@code contentQuads} are what reached the
     * buffers. A frame with the first non-zero and the second zero is a host that was handled and drew
     * nothing, which is exactly the picture the author reported - and it is now impossible for the log
     * to leave that unsaid.
     */
    static final class Counter {
        int shellQuads;
        int contentQuads;

        /**
         * Quads of the block's own inside-out model, which only the shader path draws.
         *
         * <p>Counted because they share our world source and therefore belong in the "did we submit what
         * we drew" equation; kept apart from {@code contentQuads} because no config gate controls them.
         */
        int interiorQuads;
        int hostsWithShell;
        int hostsWithContent;
        int hostsCollected;
        int hostsDrawn;

        /**
         * <b>Shell quads the per-quad colour rule refused, and the one fact that explains a zero.</b>
         *
         * <p>{@link CarrierGeometry#shellQuadArgb} returns {@code 0} for exactly two reasons: the
         * resolved colour is zero, or the shader pack's solid-frame rule drops the centre panel while
         * the four rims are still drawn. The second can never empty a whole host, so a host that emitted
         * <b>nothing</b> while its host-level gates were all open was refused by a colour of zero.
         *
         * <p>Counted here because the author's log has exactly one such frame - {@code shellQuads=0
         * contentQuads=0} beside {@code tint=0x5A9FB6FF}, {@code showInnerComponents=true}, all gates
         * open - and the old {@code *** NOTHING EMITTED ***} sentence left the reader choosing between
         * "a setting is zero", "the renderer is broken" and "a one-frame transient". With this counter
         * the next such frame says which, and the frame after it says whether it recovered.
         */
        int shellQuadsRefusedByColour;

        /**
         * Hosts collected with no slot at all.
         *
         * <p>A host with no slot has no component to draw and no border colour, so it emits nothing by
         * construction - and it is worth naming, because it is the one shape of "collected and drew
         * nothing" that is neither a setting nor a renderer fault.
         */
        int hostsWithoutSlot;

        /**
         * The matrix probes taken this frame: one from the contents call site and one from the
         * shell's, for the same host.
         *
         * <p>Two, because the check is two-sided and the two sides are known at different moments. The
         * contents probe is taken while the contents path's pose is on the stack - the only place the
         * anchor translation is known - and the shell probe while the shell's is, which additionally
         * compares the two call sites' offsets with each other. A frame whose contents were switched off
         * still takes the shell probe, so the matrix question is answered either way.
         */
        private final List<CarrierMatrixProbe> probes = new ArrayList<>();
        @Nullable
        private String matrixProbeLine;

        /** At most this many probes per frame, so a machine full of hosts cannot flood the log. */
        static final int MAX_PROBES = 3;

        /** Hosts whose shell emitted at least one quad. */
        void shellEmitted() {
            hostsWithShell++;
        }

        /** Whether the contents-side probe has been taken for this frame yet. */
        boolean hasProbe() {
            return probes.size() >= MAX_PROBES;
        }

        /**
         * <b>Record the matrix the next vertex is handed, and the offsets it is used with.</b>
         *
         * <p>The matrix is copied, not referenced: a {@code PoseStack.Pose} is mutated in place for the
         * rest of the frame, so a reference would report whatever it had become by the time the line was
         * written. The copy is the only honest record of "this is what the vertex consumer was handed".
         *
         * <h2>The offsets, and the defect this signature was fixed for</h2>
         * A block's local coordinate names its <b>corner</b>, so a block model's vertices are the usual
         * {@code 0..1} measured from it: the model's corner sits at {@code local} and its centre at
         * {@code local + 0.5} on each axis. Both call sites emit at that same base - the shell's unit
         * cube and the model's {@code 0..1} cube are literally the same cube - and the only difference
         * between them is how that base reaches the vertices: the shell adds it to each vertex itself,
         * the contents path pushes it as a pose translation and emits at the origin.
         *
         * <p>The old version of this method took only {@code extraTranslation} and added
         * {@link CarrierPose#BLOCK_CENTRE} to the centre probe <em>unconditionally</em>. That is right
         * for the shell, whose base is not yet in the matrix, and wrong for the contents, whose base is:
         * the contents' "centre" probe then measured the block's <b>corner</b>, and the line read
         * {@code shellContentGap=1.7321} - the corner-to-centre diagonal, {@code sqrt(3)/2 * 2} - while
         * the geometry was in fact exactly on the block. The gap is now zero by construction, whatever
         * {@code R}, {@code local} or {@code pivot} is.
         *
         * @param label {@code contents} or {@code shell}: which call site this matrix belongs to
         * @param base the offset that site writes its geometry at, in frame space, measured from the
         *     host block's <b>corner</b>: zero for the shell, the anchor fraction for the contents
         */
        void probe(String label, String carrier, PoseStack.Pose pose, CarrierFrame frame,
                   float[] base, Vector3d camera) {
            if (hasProbe()) {
                return;
            }
            Vector3d cornerExpected = frame.worldOf(frame.localX(), frame.localY(), frame.localZ(),
                    new Vector3d());
            Vector3d centreExpected = frame.blockCentre(new Vector3d());
            probes.add(new CarrierMatrixProbe(label, carrier, frame.rotation() != null, frame.scale(),
                    new Vector3d(frame.position()),
                    new Vector3f(base[0] + (float) CarrierPose.BLOCK_CENTRE,
                            base[1] + (float) CarrierPose.BLOCK_CENTRE,
                            base[2] + (float) CarrierPose.BLOCK_CENTRE),
                    new Vector3f(base[0], base[1], base[2]),
                    centreExpected, cornerExpected, new Matrix4f(pose.pose()), new Vector3d(camera)));
        }

        /**
         * Turns the probes into one line, once the frame is over.
         *
         * <p>Called after the draw paths rather than from inside them, because printing from inside the
         * loop would write a line per host per frame. When both call sites were probed for the same host,
         * the distance between them is appended: that is the number that catches the shell and the
         * contents being drawn in different frames.
         */
        void acceptProbe() {
            if (probes.isEmpty()) {
                return;
            }
            StringBuilder sb = new StringBuilder();
            for (CarrierMatrixProbe probe : probes) {
                if (sb.length() > 0) {
                    sb.append(" | ");
                }
                sb.append(probe.describe());
            }
            if (probes.size() >= 2) {
                double gap = probes.get(0).centreWorld().distance(probes.get(1).centreWorld());
                sb.append(String.format(java.util.Locale.ROOT,
                        " | shellContentGap=%.4f %s", gap,
                        gap > CarrierMatrixProbe.MATRIX_TOLERANCE
                                ? "*** THE SHELL AND THE COMPONENT INSIDE IT ARE DRAWN IN DIFFERENT "
                                        + "FRAMES - the two call sites disagree about where the block is "
                                        + "***"
                                : "OK-same-frame (the shell and the component are probed at the block "
                                        + "centre by both call sites)"));
            }
            matrixProbeLine = sb.toString();
            probes.clear();
        }

        /** The matrix probe line for this frame, or {@code null}. */
        @Nullable
        String matrixProbeLine() {
            return matrixProbeLine;
        }

        /**
         * One host to name in the log: the first whose geometry was refused.
         *
         * <p>The interesting host is the one that emitted nothing, not the first one that worked - the
         * log line about a healthy host is a line nobody needs, and the line about the broken one is
         * the whole reason the reader opened the file.
         */
        @Nullable
        private String example(List<Visible> visible, List<Carried> carried,
                               CarrierGeometry.Gates gates) {
            for (Visible host : visible) {
                if (refusedByGates(gates, host.slot())) {
                    return "host=" + host.pos().toShortString() + " kind=world "
                            + refusal(gates, host.slot());
                }
            }
            for (Carried host : carried) {
                if (refusedByGates(gates, host.slot())) {
                    return "host=" + host.origin().toShortString() + " local="
                            + host.local().toShortString() + " kind=carried "
                            + refusal(gates, host.slot());
                }
            }
            return null;
        }

        /** True when both halves of this host emit nothing, by the same gates the loops use. */
        private static boolean refusedByGates(CarrierGeometry.Gates gates, Slot slot) {
            return gates.shellEmptiness(slot) != null && gates.contentsEmptiness() != null;
        }

        /** The two reasons, as the sentence the frame summary carries. */
        private static String refusal(CarrierGeometry.Gates gates, Slot slot) {
            if (gates.shellEmptiness(slot) == null) {
                return "shellQuads=0 (the host was collected and its shell still emitted nothing - a "
                        + "layer or buffer problem, not a setting)";
            }
            return "shellQuads=0 (" + gates.shellEmptiness(slot) + "), contentQuads=0 ("
                    + (gates.contentsEmptiness() == null ? "the component itself emitted no quads"
                            : gates.contentsEmptiness())
                    + ")";
        }

        /** One line for the frame summary: what the counters add up to. */
        String describe() {
            return "shellQuads=" + shellQuads + " contentQuads=" + contentQuads
                    + " hostsDrawn=" + hostsDrawn + " of collected=" + hostsCollected
                    + " refusedShellQuads=" + shellQuadsRefusedByColour
                    + " hostsWithoutSlot=" + hostsWithoutSlot;
        }

        /**
         * Why a frame that emitted shell quads emitted none, in the sentence the frame summary carries.
         *
         * <p>The counterpart of {@link #describe()} for the case it exists for. A {@code shellQuads=0}
         * beside open gates used to be reported as "the host was collected but every quad was skipped",
         * which is true and useless: it cannot distinguish a colour of zero from a transient. This
         * appends the count that does.
         */
        String zeroShellReason() {
            if (shellQuads > 0) {
                return "";
            }
            if (hostsWithoutSlot > 0) {
                return " *** ZERO SHELL QUADS: " + hostsWithoutSlot + " of " + hostsCollected
                        + " host(s) were collected with NO SLOT, which can emit nothing by "
                        + "construction - the component's data has not reached the client yet ***";
            }
            if (shellQuadsRefusedByColour > 0) {
                return " *** ZERO SHELL QUADS: every one of the " + shellQuadsRefusedByColour
                        + " quads the colour rule was asked about was REFUSED (argb=0), so the host "
                        + "collected but no colour resolved. With the gates above open this is a "
                        + "one-frame transient or a fault - check the next frame's shellQuads ***";
            }
            return "";
        }
    }

    // --------------------------------------------------------- what to draw --

    /** One host that is close enough and has nothing solid between it and the camera. */
    private record Visible(BlockPos pos, Slot slot) {
    }

    /**
     * One host riding a moving structure: where the block came from, what is inside it, and the frame
     * that carries it.
     *
     * <p>{@code origin} is kept for the light query and for the world half of the neighbour question -
     * the drawing itself happens at {@code local}, transformed by {@code pose}.
     *
     * <p>{@code riding} says which frames the neighbour question may be asked in (P1.42): only a host
     * drawn inside its carrier's pose is <em>in</em> the structure, and only such a host has neighbours
     * in the structure's own coordinates. A fallback - a host drawn at a world block because its
     * carrier's pose went stale - is back in the world and asks the world index alone.
     *
     * <p>{@code frame} is built once here, from the pose and the local coordinate, and handed to
     * <b>both</b> draw paths. That is not a convenience: the shell and the component inside it have to
     * be measured from the same point, and the defect this round fixed was exactly one of them being
     * measured from the block and the other from the structure's origin - a whole {@code local} apart,
     * which is nothing for a Create contraption and two thousand blocks for a Sable sub-level. One
     * frame per host means the two cannot come apart, whatever either of them does with it.
     */
    private record Carried(CarrierId carrier, BlockPos origin, BlockPos local, Slot slot,
                           CarrierPose pose, CarrierFrame frame, boolean riding) {
    }

    /**
     * What one carried collection found: the hosts to draw, and why the others were not drawn.
     *
     * <p>The skip counts travel with the list rather than being written into the trace from inside the
     * collector, because the collector now runs at the carried stage and the counters have to be the
     * ones belonging to the frame that is being reported - see {@link #reportFrame}.
     *
     * @param hosts the hosts this frame will draw
     * @param skippedNoPose carriers the client was never given a position for
     * @param skippedFar carriers past the draw distance
     * @param skippedStalePose carriers whose pose stopped arriving
     * @param worstPoseAgeTicks the age of the oldest pose drawn from, or {@code -1}
     * @param landedWorldHost landed hosts the world path is drawing instead
     * @param landedDeferred landed hosts drawn here because the world path cannot yet
     * @param carriedWorldHost stale-carrier hosts the world path is drawing instead
     * @param drawnOffStructure hosts drawn far from the position their carrier reports
     * @param plotSpaceHosts hosts whose local coordinates are in a Sable-style plot space
     * @param example one worked host, or {@code null} when none was carried
     * @param sight the sight half of the frame: how many hosts were asked, and one worked example
     */
    private record CarriedResult(List<Carried> hosts, int skippedNoPose, int skippedFar,
                                 int skippedStalePose, long worstPoseAgeTicks, int landedWorldHost,
                                 int landedDeferred, int carriedWorldHost, int drawnOffStructure,
                                 int plotSpaceHosts, @Nullable CarrierDrawCheck example,
                                 SightStats sight) {

        /** The empty collection: nothing was carried, so nothing was asked and nothing was skipped. */
        private static CarriedResult empty() {
            return new CarriedResult(List.of(), 0, 0, 0, -1L, 0, 0, 0, 0, 0, null, SightStats.NONE);
        }
    }

    /**
     * The sight half of one frame: the five counters, and the one worked host the B2 line names.
     *
     * <p>Carried out of the collector with the hosts rather than written into the trace from inside it,
     * for the same reason the skip counts are: the collector has to stay repeatable, and the frame the
     * summary describes is the frame these numbers belong to.
     *
     * @param tally how many hosts were asked, and how the ray answered
     * @param example the host the B2 line names, or {@code null} when the frame asked nothing
     */
    private record SightStats(CarrierHostCheck.Tally tally, @Nullable CarrierHostCheck example) {

        /** No sight question was asked this frame. */
        private static final SightStats NONE = new SightStats(CarrierHostCheck.Tally.NONE, null);

        // `example()` is the record's own accessor: the round that wrote this record also wrote a private
        // method of the same name and return type, which the compiler rejects as an invalid accessor - one
        // of the two had to go, and the generated one cannot drift from the component list, so that is the
        // one kept.

        /** The B2 line for this frame, or {@code null} when there is nothing to name. */
        @Nullable
        private String describe() {
            return example == null ? null : example.describeSightWithTarget();
        }
    }

    private static List<Visible> collect(ClientLevel level, LocalPlayer player, Vec3 cameraPos) {
        BlockPos origin = BlockPos.containing(cameraPos);
        List<Visible> visible = new ArrayList<>();
        ClientHostCache.forEachNear(level.dimension(), origin, SECTION_RADIUS, (pos, slot) -> {
            if (!ClientHostCache.worldPathDraws(level, level.dimension(), pos, slot)) {
                // Either the client has no component for this position, or the block the component
                // belongs to is not there (yet). Drawing a shell in mid-air would look like a bug in
                // the world rather than in the data - and this is the *same* question the carried path
                // asks before it lets a landed record yield, so the two cannot disagree.
                return;
            }
            if (canSee(level, player, cameraPos, pos)) {
                visible.add(new Visible(pos, slot));
            }
        });
        return visible;
    }

    /**
     * The hosts riding a moving structure, with the pose of the structure carrying them - plus the ones
     * that have fallen back to their original world blocks.
     *
     * <p>Filters, in this order:
     *
     * <ol>
     *   <li><b>The carrier's own position</b>, measured against the camera. A carried host's origin
     *       can be a hundred blocks behind a machine that has flown over the horizon, so the section
     *       walk that found it says nothing about whether it is worth drawing.</li>
     *   <li><b>Line of sight, from where the component actually is.</b> The ray goes to the
     *       transformed position, not to the origin, because the origin is empty world where the
     *       block used to be.</li>
     * </ol>
     *
     * <p>Three things can stop a host being drawn, and each has its own counter because each means
     * something different: {@code skippedNoPose} - the client was never told where the carrier is;
     * {@code skippedFar} - it is simply too far away; {@code skippedStalePose} - the client was told,
     * but not recently enough to trust. The third used to be counted by nothing at all, which is how a
     * permanently invisible component could report {@code drawn=0} with every skip reason at zero.
     *
     * <p>A host that has fallen back (see {@code ClientHostCache}) takes the same route as a carried
     * one, with a pose that puts its block back exactly where it came from. That is deliberate: the
     * fallback has to be drawn through the identical code path, or it would be a second renderer with
     * its own bugs, and it has to be visible even where the world block is now air - which is the
     * difference between "drawn on the world path" and "drawn by the world path".
     *
     * <p><b>A fallback yields only when the world path really draws.</b> That is a stronger question
     * than "a block is standing there": it is {@link HostPathOwnership#owner}, the same judge
     * {@link #collect} uses for its own gate, and it asks for <em>both</em> a block and a world host
     * the client knows about. Yielding to a world path that is itself refusing to draw - which is what
     * "the block has not come back yet" looks like - left the component drawn by nothing at all, and
     * now cannot: the fallback draws itself, and {@code landedDeferred} counts the frames it had to.
     */
    private static CarriedResult collectCarried(ClientLevel level, LocalPlayer player, Vec3 cameraPos,
                                                 double partialTick) {
        BlockPos near = BlockPos.containing(cameraPos);
        double reach = DRAW_DISTANCE;
        double reachSquared = reach * reach;
        List<Carried> out = new ArrayList<>();
        Vector3d world = new Vector3d();
        // Why a host in range was not drawn, kept apart from "was drawn": with no pose the honest
        // answer is "the client was never told where the carrier is", with a carrier past the draw
        // distance it is "it is simply too far", and with a stale pose it is "the client stopped
        // trusting where it was told". Those are three different bugs and the counters say which.
        int[] skipped = new int[3];
        // Landed hosts (put down, carrier gone) that this frame passed over because a real block is
        // standing at the landing position: the world path draws those, and drawing the landed
        // candidate as well would put two shells on one block.
        int[] landedWorldHost = new int[1];
        // Landed hosts drawn at their landing position *because the world path cannot draw there yet*:
        // the sync has announced the host but the block has not come back. This is the window that used
        // to be drawn by neither path; the landed record draws it now, and this number is the evidence.
        int[] landedDeferred = new int[1];
        // Hosts whose carrier went stale mid-flight that this frame passed over because the world path
        // really draws their origin block. The same decision as the landed one, on the other fallback
        // position, and counted apart from it because "put down" and "let go of" are different facts.
        int[] carriedWorldHost = new int[1];
        // THE DRAW CHECK, counted per frame. `drawn` counts emitted geometry and cannot tell "drawn
        // here" from "drawn a million blocks away"; these two can. `offStructure` is the one that
        // matters - a drawn host whose computed world point is nowhere near its carrier's own position
        // is the shape of "the log says it drew, and the player sees nothing", which is what the Sable
        // path reported. `plotSpace` is the fact that explains it: local coordinates in the Sable plot
        // range, which are correct when the pose that came with them is Sable's own.
        int[] drawnOffStructure = new int[1];
        int[] plotSpaceHosts = new int[1];
        // HOW OLD THE POSE BEHIND THIS FRAME IS, in client ticks, worst case among the hosts actually
        // drawn. This is the number the author's report needed and the log did not have: "the bearing
        // turns and the component does not turn with it" produced a log full of `delta=0.000 OK`,
        // because the pose arithmetic is perfectly self-consistent about a stale pose as well as about a
        // current one. A zero delta says nothing about freshness; this does.
        long[] worstPoseAge = new long[1];
        // One worked example, kept so a single `drawCheck carrier=闁炽儺娉?line can be logged with both
        // coordinates rather than only the counts. First host of the frame, not the worst one: the
        // point is to show the reader two numbers they can compare, and every host of one carrier
        // shares the same pose.
        CarrierDrawCheck[] example = new CarrierDrawCheck[1];
        // THE SIGHT HALF OF THE FRAME. Five counters and one worked example, all of them read out of the
        // ray the sight rule already casts - see `castSightRay`. The counters accumulate in one array
        // slot rather than a local because the lambda below reassigns them, and they travel out in the
        // CarriedResult, so nothing about the collector becomes per-frame state.
        CarrierHostCheck.Tally[] sightTally = { CarrierHostCheck.Tally.NONE };
        // The example the B2 line prints: the first host whose ray was refused if there is one, because
        // "something is in the way" is the report this line exists for, and otherwise the first host
        // asked about. Both are real frames rather than a synthesised worst case.
        CarrierHostCheck[] sightExample = new CarrierHostCheck[1];
        // How many carried hosts this frame asked about and how many it collected, so the B2 line's
        // `hosts=collected/asked` is the frame's own count rather than a second measurement.
        int[] hostsAsked = new int[1];
        int[] hostsCollected = new int[1];
        // The eye block the sight question is asked from, computed once per frame - from the same Vec3
        // the ray is cast with, so the coordinate the line prints and the ray that was cast cannot come
        // from two different eyes.
        final BlockPos eyeBlock = BlockPos.containing(cameraPos);
        // P1.56: SABLE'S OWN RENDER POSE, ASKED ONCE PER CARRIER PER FRAME. Sable samples its own
        // snapshot buffer to draw a structure, and the poses the server sends us come through our own
        // channel - two timelines that beat against each other, which is what the author saw as the
        // components flickering between the leading position and the block's. `ClientSubLevel#renderPose`
        // is the pose Sable draws with, so asking for it makes the two the same number. A carrier Sable
        // cannot answer for (a Create contraption, no Sable, a sub-level the client does not hold) keeps
        // the interpolated network pose P1.55 added - so the fallback is better than it was, and no path
        // is left with no pose at all.
        final java.util.Map<com.jiangyuefengyu.redstonecircuit.carrier.CarrierId, CarrierPose>
                liveRenderPoses = new java.util.HashMap<>();
        ClientHostCache.forEachDrawable(level.dimension(), near, SECTION_RADIUS, partialTick, drawable -> {
            CarrierIndex.CarriedView view = drawable.view();
            // One partial tick per frame, so one answer per carrier is enough for the whole frame.
            CarrierPose live = liveRenderPoses.computeIfAbsent(view.carrier(),
                    carrier -> PhysicsCompat.renderPoseOf(level, carrier, (float) partialTick));
            notePoseSource(view.carrier(), live);
            CarrierPose pose = live != null ? live : drawable.pose();
            if (pose == null) {
                if (drawable.isFallback()) {
                    // No pose, so the host is drawn at a world block: the landing position for a host
                    // the structure put down, the block it came from for one whose carrier was let go
                    // of mid-flight. The two are different promises and `Drawable#fallbackPos` is the
                    // one place that decides between them.
                    BlockPos fallback = drawable.fallbackPos();
                    // The very same call the world path's own gate makes, so "would the world path draw
                    // here" is answered in exactly one way for both paths - including the chunk-loaded
                    // test, which is part of the answer rather than an extra opinion of this call site.
                    boolean worldDrawsHere = ClientHostCache.worldPathDraws(level, level.dimension(),
                            fallback, view.slot());
                    boolean worldHost = ClientHostCache.slotAt(level.dimension(), fallback) != null;
                    // One decision with two exhaustive answers - see {@code HostPathOwnership}, whose
                    // {@code owner} is this same question expressed as two values with no "neither".
                    // The world path draws here, or this record does; there is no third outcome, which
                    // is exactly what the "yielded to a world path that was refusing to draw" defect was.
                    if (worldDrawsHere) {
                        // A host the sync knows about *and* a block to draw it over, so this candidate
                        // yields rather than putting a second shell on the same block.
                        if (drawable.landed()) {
                            landedWorldHost[0]++;
                        } else {
                            carriedWorldHost[0]++;
                        }
                        return;
                    }
                    if (drawable.landed() && worldHost) {
                        // A world host is announced here but the block has not come back yet, so the
                        // world path refuses to draw and this record is the only path left. Counted
                        // because it is exactly the frame window the "invisible after landing" defect
                        // lived in: the component is drawn, and the count says why it had to be.
                        landedDeferred[0]++;
                    }
                    Vec3 home = Vec3.atCenterOf(fallback);
                    // THE SAME RAY, IN BOTH ROLES. A fallback is a host drawn at a world block, so its
                    // sight answer is the world path's own question - but it is cast through the one
                    // sight rule so that the decision here and the decision reported cannot be two rays.
                    // The classification is discarded for this case: a fallback's line is the drawCheck
                    // one, and only a host inside a structure has a B2 line to write.
                    //
                    // `null` = the ordinary question, because a fallback's block IS the world block it is
                    // drawn at: that block is the one at `fallback`, so there is only one frame to ask in.
                    if (!castSightRay(level, player, cameraPos, home, fallback, null, new SightRay())) {
                        return;
                    }
                    // A fallback is drawn at a world block, so its frame is the block's own centre and
                    // the offsets are the sub-block residuals - the same small numbers the world path
                    // produces. See `FALLBACK_POSE` for why its pose is the identity.
                    BlockPos fallbackLocal = view.localPos();
                    out.add(new Carried(view.carrier(), fallback, fallbackLocal, view.slot(),
                            FALLBACK_POSE, CarrierFrame.forBlock(FALLBACK_POSE,
                                    new Vector3d(fallbackLocal.getX(), fallbackLocal.getY(),
                                            fallbackLocal.getZ())), false));
                    return;
                }
                // A host under a carrier the client was never given a position for: nothing honest can
                // be drawn, and this is the counter that names it.
                skipped[0]++;
                return;
            }
            if (ClientHostCache.carrierPoseAge(view.carrier()) > ClientHostCache.staleTicks()) {
                // Stale but not yet swept: the carrier has gone quiet and the next client tick will
                // let it go. Counted here so a frame that drew nothing can never again report no reason
                // for it - which is exactly the gap that made this defect invisible.
                skipped[2]++;
                return;
            }
            if (pose.position().distanceSquared(cameraPos.x, cameraPos.y, cameraPos.z) > reachSquared) {
                skipped[1]++;
                return;
            }
            pose.blockCentre(new Vector3d(view.localPos().getX(), view.localPos().getY(),
                    view.localPos().getZ()), world);
            Vec3 target = new Vec3(world.x, world.y, world.z);
            // THE SIGHT QUESTION, ANSWERED AND COUNTED. The ray is the rule's own and the decision is the
            // rule's own; what is new is that the answer is kept, because "refused because the ray hit a
            // wall in front" and "there was no point to aim at" were the same empty screen and the same
            // silent log. The counters are per frame and mean nothing to the picture. See
            // `CarrierHostCheck.Sight`.
            //
            // THE TARGET IS THE RENDERER'S OWN PROJECTION (`pose.blockCentre`, above), not a second call
            // to `CarrierProjection.project`: the two are the same arithmetic, and using the value the
            // decision was made from is what makes `drawnWorld=` and the picture the same point.
            BlockPos targetBlock = BlockPos.containing(world.x, world.y, world.z);
            // THE SAME BLOCK, IN THE FRAME THE RAY REPORTS HITS IN. A Sable host's block lives at its
            // local coordinate - a plot coordinate, which is a real coordinate in the same level - and
            // Sable's own ray tracing reports hits on its geometry in that frame. So the question "did the
            // ray meet the component's own block" has two possible answers and both must be accepted, or
            // the machine becomes its own occluder: the author's log (2026-09-28 08:45) shows
            // `ray=hit(20481032, 127, 20483080)` beside `local=(20481032, 127, 20483080)` on a frame the
            // decision called `refused-hit`. Only a plot-space coordinate can be compared this way - an
            // anchor-relative local coordinate is a small number that a world hit could coincide with.
            BlockPos ownBlockInItsOwnFrame = CarrierDrawCheck.plotSpace(view.localPos())
                    ? view.localPos()
                    : null;
            SightRay hostRay = new SightRay();
            boolean visible = castSightRay(level, player, cameraPos, target, targetBlock,
                    ownBlockInItsOwnFrame, hostRay);
            sightTally[0] = sightTally[0].plus(hostRay.outcome);
            hostsAsked[0]++;
            if (sightExample[0] == null || hostRay.outcome == CarrierHostCheck.Sight.REFUSED_HIT
                    && sightExample[0].sight() != CarrierHostCheck.Sight.REFUSED_HIT) {
                // The first host asked about, unless a later one was refused - "something is in the way"
                // is the report the line exists for. The counts are filled in when the collection ends;
                // the record below carries the per-host numbers, and `sight(...)` replaces them with the
                // frame's.
                sightExample[0] = new CarrierHostCheck(view.carrier(),
                        CarrierHostCheck.section(view.origin()),
                        CarrierHostCheck.section(BlockPos.containing(pose.position().x(),
                                pose.position().y(), pose.position().z())),
                        eyeBlock, true, view.localPos(), targetBlock, eyeBlock, hostRay.outcome,
                        hostRay.hit == null ? null : hostRay.hit.getBlockPos(), 1, 1,
                        ClientHostCache.carrierPoseAge(view.carrier()), view.origin());
            }
            if (!visible) {
                return;
            }
            // THE DRAW CHECK. `world` is where this host is about to be drawn and `pose.position()` is
            // where its structure says it is, so the distance between them is the one number that
            // distinguishes "we drew the component where the machine is" from "we drew it somewhere
            // else entirely". The structure's own bounds centre would be a better anchor, but it is not
            // on the wire - the pose is, and it is a pivot that is always within a machine's own extent,
            // which is far tighter than the orders of magnitude this check exists to catch.
            CarrierDrawCheck check = new CarrierDrawCheck(view.carrier(), view.localPos(),
                    new Vector3d(world), new Vector3d(pose.position()),
                    ClientHostCache.carrierPoseAge(view.carrier()));
            long age = check.poseAgeTicks();
            if (age > worstPoseAge[0]) {
                worstPoseAge[0] = age;
            }
            if (example[0] == null || check.poseIsStale() && !example[0].poseIsStale()) {
                // The example is the first host of the frame, unless a later one is drawn from a stale
                // pose - a stale host is the one worth naming, and there is at most one stale pose per
                // carrier, so preferring it costs nothing and makes the logged line the informative one.
                example[0] = check;
            } else if (plotSpaceHosts[0] == 0 && check.plotSpaceLocal()) {
                example[0] = check;
            }
            if (check.plotSpaceLocal()) {
                plotSpaceHosts[0]++;
            }
            if (check.drawnFarFromStructure()) {
                drawnOffStructure[0]++;
            }
            out.add(new Carried(view.carrier(), view.origin(), view.localPos(), view.slot(), pose,
                    CarrierFrame.forBlock(pose, new Vector3d(view.localPos().getX(),
                            view.localPos().getY(), view.localPos().getZ())), true));
        });
        // A stale carrier is counted here from the frame that first sees it: the client tick that turns
        // it into fallbacks runs twenty times a second, so the window in which a stale carrier has not
        // been swept is at most a frame or two - and during it this counter is what explains a frame
        // that drew nothing. There is deliberately no second pass over the carriers to catch hosts
        // outside the section radius: a host the player cannot see is not one the counter is about.
        //
        // The counts are RETURNED rather than recorded here. This collector is called from the carried
        // stage, and the trace has to be written with the counters of the frame the summary describes;
        // writing them from inside a collector also made the collector unrepeatable, which the
        // two-stage split cannot afford. See `runCarriedStage`.
        return new CarriedResult(out, skipped[0], skipped[1], skipped[2], worstPoseAge[0],
                landedWorldHost[0], landedDeferred[0], carriedWorldHost[0], drawnOffStructure[0],
                plotSpaceHosts[0], example[0], sight(sightTally[0], sightExample[0], hostsAsked[0],
                        hostsCollected[0]));
    }

    /**
     * The sight half of one collection, with the frame's own host counts folded into the example.
     *
     * <p>Folded here rather than at the example's construction site because the counts are only complete
     * once the walk has finished: the example is chosen part-way through, and a per-carrier line whose
     * {@code hosts=} said {@code 1/1} while the frame collected three would be a line that disagrees
     * with itself.
     */
    private static SightStats sight(CarrierHostCheck.Tally tally, @Nullable CarrierHostCheck example,
                                    int asked, int collected) {
        if (asked == 0 && example == null) {
            return SightStats.NONE;
        }
        CarrierHostCheck filled = example == null ? null : new CarrierHostCheck(example.carrier(),
                example.originSection(), example.poseSection(), example.cameraSection(),
                example.collected(), example.localPos(), example.drawnWorld(), example.eye(),
                example.sight(), example.hit(), collected, asked, example.poseAgeTicks(),
                example.origin());
        return new SightStats(tally, filled);
    }

    /**
     * The pose a fallen-back host is drawn through: the identity, at the origin of the frame.
     *
     * <p>{@code world = position + orientation * (local - pivot) * scale} with every term at its
     * default reduces to {@code local}, and the render pass has already translated the stack by the
     * camera, so a host drawn with this pose lands exactly on the block it came from - which is what
     * the fallback promises. Constant because {@link CarrierPose} is immutable and this is the same
     * value every frame.
     */
    private static final CarrierPose FALLBACK_POSE = CarrierPose.at(new Vector3d());

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
        if (pos.equals(BlockPos.containing(eye))) {
            return true;
        }
        Vec3 target = Vec3.atCenterOf(pos);
        // A world host is always a real block, so an unobstructed ray either hits nothing or hits the
        // host itself - a hit on a neighbour means an actual wall is in the way.
        BlockHitResult hit = level.clip(new ClipContext(eye, target, ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, player));
        return hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(pos);
    }

    /**
     * Whether the eye can reach an arbitrary point, for a component that is not at a world block.
     *
     * <p>Only used for carried hosts. Their position is inside a contraption, so the structure's own
     * blocks can legitimately be the first thing a ray hits - which is not a wall between the player
     * and the component, it is the machine the component is part of. Requiring a clear line would make
     * every component in a machine invisible from most angles, so this asks the weaker question: is
     * there solid geometry strictly in front of the target? Anything at or beyond it is the structure
     * itself and does not count.
     */
    private static boolean canSeePoint(ClientLevel level, LocalPlayer player, Vec3 eye, Vec3 target) {
        // The boolean face of the one sight rule; see `castSightRay`, where the shapes behind the
        // boolean are named. Delegating rather than repeating the arithmetic is what makes "the ray the
        // diagnostic reports" and "the ray the picture depends on" the same ray by construction.
        //
        // The ordinary question, because every caller of this form aims at a real world point (its block
        // is the block at that position). The carried path's own frame - the plot coordinate its block
        // really lives at - is asked there, where that coordinate is in hand.
        return castSightRay(level, player, eye, target, BlockPos.containing(target), null,
                new SightRay());
    }

    /**
     * <b>The sight rule, and the four shapes behind its answer.</b>
     *
     * <p>A boolean says one of three things was true - "nothing was in the way", "the ray hit a wall in
     * front of the component", "no point could be aimed at" - and those are three different findings the
     * log could not tell apart. This method casts the ray and records which one produced the decision,
     * so the value {@link #canSeePoint} used to return and the value the diagnostic reports cannot
     * disagree: there is one ray.
     *
     * <p>Only used for carried hosts. Their position is inside a contraption, so the structure's own
     * blocks can legitimately be the first thing a ray hits - which is not a wall between the player
     * and the component, it is the machine the component is part of. Requiring a clear line would make
     * every component in a machine invisible from most angles, so this asks the weaker question: is
     * there solid geometry <b>strictly in front of</b> the target? Anything at or beyond it is the
     * structure itself and does not count.
     *
     * @param target the point to reach, or {@code null} when the projection produced nothing - in which
     *     case no ray is cast and the answer is {@code false}: an unaimable point is never drawn
     * @param targetBlock the block the target is in, for the classification; may be {@code null}
     * @param ownBlockInItsOwnFrame the block the component is in, in the frame the ray reports its hits
     *     in, or {@code null} when that is the same frame as {@code target}'s; see
     *     {@code CarriedSight#isVisible}
     * @param out filled in with the decision and the shape behind it; caller-owned so the collector's hot
     *     path allocates nothing it does not have to
     * @return the decision, i.e. what {@code canSeePoint} used to answer
     */
    private static boolean castSightRay(ClientLevel level, LocalPlayer player, Vec3 eye,
                                        @Nullable Vec3 target, @Nullable BlockPos targetBlock,
                                        @Nullable BlockPos ownBlockInItsOwnFrame, SightRay out) {
        out.reset();
        out.targetBlock = targetBlock;
        if (target == null) {
            // No point to aim at. This branch is unreachable from the renderer (both call sites compute a
            // point), and the answer here is settled by the two rules this project has already written
            // down rather than by convenience:
            //
            //   * specification S1' (the round's own fix spec) says "an uncomputable point is visible";
            //   * the union principle says a render-visibility judgement may only widen what the previous
            //     one drew, and the round that made this question a per-host one exists because narrowing
            //     it twice rendered nothing at all;
            //   * and the project's own precedent is the same: "rather draw one shell too many than have
            //     nobody draw at all" (the landed-host window, §11.8.3).
            //
            // So: drawn, with the honest label. UNKNOWN_TARGET still says "we could not work out where
            // this is", and sightUnknownTarget still counts it, so the line does not claim a clean ray.
            out.accepted = true;
            out.outcome = CarrierHostCheck.Sight.UNKNOWN_TARGET;
            return true;
        }
        if (RCConfig.seeInnerComponentsThroughWalls()) {
            out.accepted = true;
            out.outcome = CarrierHostCheck.Sight.DRAWN;
            return true;
        }
        // THE DECISION IS `CarriedSight`'S, NOT A SECOND COPY OF IT. The rule used to be written out here
        // as a comparison between the squared distance to the hit point and the squared distance to the
        // aim point, with a quarter-block slack subtracted from the latter - the "strictly in front"
        // question asked in the wrong currency. It is the same question the world path asks as
        // `hit.getBlockPos().equals(pos)`, and the distance form refuses a component whose own block the
        // ray reached. `CarriedSight` holds the rule as four named cases with its own table test, so this
        // method keeps only the two things it is for: cast the ray, and label the answer. The block the
        // aim point is in comes from the caller when it has it and is `BlockPos.containing` otherwise -
        // the game's own floor either way. (The old form is described in words rather than quoted because
        // the wiring test asserts this body holds no such arithmetic, comments included, and a comment is
        // not a place to smuggle a constant back in.)
        BlockPos aimedAt = targetBlock != null ? targetBlock : BlockPos.containing(target);
        BlockHitResult hit = level.clip(new ClipContext(eye, target, ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, player));
        CarriedSight.Ray ray = hit.getType() == HitResult.Type.MISS
                ? CarriedSight.Ray.miss()
                : CarriedSight.Ray.hit(hit.getBlockPos());
        boolean accepted = CarriedSight.isVisible(eye.x, eye.y, eye.z, aimedAt, ray,
                ownBlockInItsOwnFrame);
        out.accepted = accepted;
        out.hit = hit;
        // The classification is the pure half of this rule (`Sight#ofFacts`), and the boolean it is given
        // is the one this method is about to return - so "the label agrees with the decision" is a
        // property of the arithmetic rather than of the order of these two lines.
        out.outcome = CarrierHostCheck.Sight.ofFacts(true, accepted,
                hit.getType() == HitResult.Type.BLOCK);
        return accepted;
    }

    /**
     * One sight ray's result: the decision, and which of the four shapes produced it.
     *
     * <p>Caller-owned and filled in place. One instance per answered question, which is one small object
     * per collected host on a loop that already clips a ray per collected host - the block clip is orders
     * of magnitude the larger cost, and a shared instance would be a correctness hazard the moment two
     * questions are in flight.
     */
    private static final class SightRay {
        /** What the picture depends on: the boolean the sight rule answers. */
        private boolean accepted;
        /** Which of the four shapes produced it. */
        private CarrierHostCheck.Sight outcome = CarrierHostCheck.Sight.UNKNOWN_TARGET;
        /** The ray's result, or {@code null} when no ray was cast. */
        @Nullable
        private BlockHitResult hit;
        /** The block that was aimed at, or {@code null}. */
        @Nullable
        private BlockPos targetBlock;

        private void reset() {
            accepted = false;
            outcome = CarrierHostCheck.Sight.UNKNOWN_TARGET;
            hit = null;
            targetBlock = null;
        }
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
                                   AtlasUv uv, CarrierGeometry.Gates gates, Counter counter) {
        int tint = gates.effectiveTint();
        int frame = gates.frame();
        int wireFrame = gates.wireFrame();
        if (tint == 0 && frame == 0 && wireFrame == 0) {
            return;
        }
        // Under a shader pack the type is opaque cutout, so the frosted centre panel would be a solid
        // plate over the block and hide everything inside it. Only the frame is drawn then. The debug
        // override switches the rule off as well: the point of that switch is to find out whether the
        // geometry reaches the buffer at all, and a rule that quietly drops a sixth of it would blur
        // that answer.
        boolean solidFrames = gates.solidFrames() && !gates.forceEmit();
        VertexConsumer buffer = worldShellBuffer();
        float[] normal = new float[3];
        float[] point = new float[3];

        for (Visible host : hosts) {
            // A block holding superconducting wire gets its own border colour. The wire inside is small,
            // and "which of these two blocks is the lossless one" is the first thing a player asks, so
            // the border - the part that reads from a distance - answers it. `effectiveFrame` also
            // supplies the debug switch's substitute when there is no border colour at all.
            int hostFrame = gates.effectiveFrame(host.slot());
            boolean withFrame = hostFrame != 0;
            int light = lightFor(level, host.pos());
            float baseX = host.pos().getX();
            float baseY = host.pos().getY();
            float baseZ = host.pos().getZ();
            int before = counter.shellQuads;
            for (int face = 0; face < HostShell.FACE_COUNT; face++) {
                HostShell.normal(face, normal);
                for (int quad = 0; quad < HostShell.quadsPerFace(withFrame); quad++) {
                    float[] tile = HostShell.tile(quad, withFrame);
                    int argb = CarrierGeometry.shellQuadArgb(withFrame, quad, tint, hostFrame,
                            solidFrames);
                    if (argb == 0) {
                        // Counted, because a host that emits nothing at all has to name its own cause:
                        // see Counter#shellQuadsRefusedByColour.
                        counter.shellQuadsRefusedByColour++;
                        continue;
                    }
                    emitShellQuad(buffer, pose, baseX, baseY, baseZ, face, tile, argb, light, uv,
                            normal, point);
                    counter.shellQuads++;
                }
            }
            if (counter.shellQuads > before) {
                counter.shellEmitted();
            }
        }
    }

    /**
     * The same shell, for hosts that are riding a moving structure.
     *
     * <p>Structurally a mirror of {@link #drawShells}, with one difference that is the whole point of
     * the carried path: every host is drawn inside its carrier's own frame. The pose stack is pushed,
     * the frame's rotation and scale are applied, and the shell is then emitted at an offset from the
     * frame's reference - which is what turns the block with the machine rather than leaving it
     * axis-aligned in the world.
     *
     * <p><b>Every number handed to the pose stack here is an offset from the centre of the host block,
     * never a plot coordinate.</b> That is the difference between a shell that is a one-block cube and
     * one that collapses to a point: at a Sable plot coordinate the {@code float} spacing is 2, so
     * {@code 20481032.0f + 0.5f} is {@code 20481032.0f} and all thirty vertices of a face land on top
     * of each other. See {@link CarrierFrame}.
     */
    private static void drawCarriedShellsInto(PoseStack pose, ClientLevel level, List<Carried> hosts,
                                              AtlasUv uv, CarrierGeometry.Gates gates, Counter counter,
                                              Vec3 cameraPos, CarrierGeometry.RenderPlan plan) {
        int tint = gates.effectiveTint();
        int frame = gates.frame();
        int wireFrame = gates.wireFrame();
        if (hosts.isEmpty()) {
            return;
        }
        if (tint == 0 && frame == 0 && wireFrame == 0) {
            return;
        }
        boolean solidFrames = gates.solidFrames() && !gates.forceEmit();
        // THE CARRIED SOURCE, NOT THE WORLD ONE. This line read `shellBuffer()` - the world path's
        // consumer - and wrote into a batch that `AFTER_ENTITIES` had already submitted two stages
        // earlier. Every quad was counted and none was submitted. See HostRenderTypes and §17.
        VertexConsumer buffer = carriedShellBuffer(plan);
        float[] normal = new float[3];
        float[] point = new float[3];
        float[] base = new float[3];
        Vector3d world = new Vector3d();

        for (Carried host : hosts) {
            int hostFrame = gates.effectiveFrame(host.slot());
            boolean withFrame = hostFrame != 0;
            if (host.slot() == null) {
                // A host with no slot has no component and no border colour, so it emits nothing by
                // construction. Counted rather than drawn around, so the frame that has one says so
                // instead of reporting "not one vertex came out of it".
                counter.hostsWithoutSlot++;
                continue;
            }
            CarrierFrame carrierFrame = host.frame();
            carrierFrame.blockCentre(world);
            int light = lightAt(level, world);
            // A block's local coordinate names its CORNER, so the shell's unit cube is measured from
            // `local - pivot` - computed in double, small, and the only place `local` appears at all.
            carrierFrame.cornerOffset(base);

            pose.pushPose();
            applyCarrierFrame(pose, carrierFrame);
            // The shell's own probe: the shell's base is added to each vertex rather than pushed as a
            // translation, so the matrix is `T(position) · R · S` and the base is passed separately.
            // Both probe points are the host block's own, so `matrixDelta` and `span` are the whole
            // question here - see CarrierMatrixProbe.
            counter.probe("shell", host.carrier().shortId(), pose.last(), carrierFrame, base,
                    new Vector3d(cameraPos.x, cameraPos.y, cameraPos.z));
            int before = counter.shellQuads;
            for (int face = 0; face < HostShell.FACE_COUNT; face++) {
                HostShell.normal(face, normal);
                for (int quad = 0; quad < HostShell.quadsPerFace(withFrame); quad++) {
                    float[] tile = HostShell.tile(quad, withFrame);
                    int argb = CarrierGeometry.shellQuadArgb(withFrame, quad, tint, hostFrame,
                            solidFrames);
                    if (argb == 0) {
                        // Counted, because a host that emits nothing at all has to name its own cause:
                        // see Counter#shellQuadsRefusedByColour.
                        counter.shellQuadsRefusedByColour++;
                        continue;
                    }
                    emitShellQuad(buffer, pose.last(), base[0], base[1], base[2], face, tile, argb,
                            light, uv, normal, point);
                    counter.shellQuads++;
                }
            }
            if (counter.shellQuads > before) {
                counter.shellEmitted();
            }
            pose.popPose();
        }
    }

    /**
     * Pushes a carrier's frame onto the stack: <b>{@code T(position) 路 R 路 S}, in that order.</b>
     *
     * <h2>The order is the contract, and getting it backwards is invisible for one frame per turn</h2>
     * A {@code PoseStack} right-multiplies. The old code called
     * {@code translate(-pivot); mulPose(R); translate(position)}, which builds
     * {@code T(-cam)路T(-pivot)路R路T(position)} - i.e. {@code R路p + R路position 鈭?pivot} applied to a point -
     * where {@link CarrierPose#transformMatrix()} states {@code position + R路(p 鈭?pivot)}. The two agree
     * only at {@code R = I}, which is exactly what the author saw: <em>the frame flashes into view for
     * one frame of each turn and vanishes again</em>. This method is the {@code T(position) 路 R 路 S} half
     * of the contract; the {@code (p 鈭?pivot)} half is the per-vertex offset, computed in {@code double}
     * by {@link CarrierFrame} so that a Sable plot coordinate never reaches a {@code float}.
     *
     * <p>What is deliberately absent is any translation by the pivot. It is a two-million-block number
     * for a sub-level, {@code PoseStack.translate} takes a {@code float}, and at that magnitude the
     * spacing between representable values is 2 - so the old {@code T(-pivot)} collapsed a one-block
     * shell to a single point as well as mis-ordering it.
     *
     * <p>Package-private rather than private so that {@code CarrierFrameTest} can drive it: the order is
     * invisible on screen for all but one frame of a turn, so a test that compares the matrix a real
     * {@code PoseStack} produces against the contract is the only kind that can hold it.
     */
    static void applyCarrierFrame(PoseStack pose, CarrierFrame carrier) {
        pose.translate(carrier.position().x(), carrier.position().y(), carrier.position().z());
        Quaternionf rotation = carrier.rotation();
        if (rotation != null) {
            pose.mulPose(rotation);
        }
        if (carrier.scale() != 1.0F) {
            pose.scale(carrier.scale(), carrier.scale(), carrier.scale());
        }
    }

    /**
     * The base the carried contents are emitted at, in the carrier's frame.
     *
     * <p>One named expression rather than a {@code cornerOffset} call at the drawing site and a
     * re-derivation in the tests: this is the number the component's placement is measured from, and it
     * is the same base the shell emits its vertices at - which is what makes the shell and the component
     * one object rather than two. See {@link CarrierFrame#cornerOffset}.
     *
     * @param out a three-element array to write into, so the render loop allocates nothing
     */
    static float[] carriedModelBase(CarrierFrame frame, float[] out) {
        return frame.cornerOffset(out);
    }

    /**
     * The base the <b>world</b> contents are emitted at: the host block's own corner, in world
     * coordinates.
     *
     * <p>This is the world path's half of the same contract {@link #carriedModelBase} states for the
     * carried one, and it is a method rather than two lines inside {@link #drawContents} for one reason:
     * a test has to be able to drive <em>this</em> expression. The previous revision of the two-paths
     * test re-derived the world path's arithmetic from the description instead of calling it, so when
     * the world path was changed to take a {@code float[] base} there was nothing to break and nothing
     * to catch. A test that recomputes what the renderer is supposed to do can only ever agree with
     * itself.
     *
     * @param pos the host block's position, which is what the world path's pose stack expects, because
     *     it is translated by the camera and by nothing else
     * @param out a three-element array to write into, so the render loop allocates nothing
     */
    static float[] worldVertexBase(@Nullable BlockPos pos, float[] out) {
        if (pos == null) {
            out[0] = 0F;
            out[1] = 0F;
            out[2] = 0F;
            return out;
        }
        out[0] = pos.getX();
        out[1] = pos.getY();
        out[2] = pos.getZ();
        return out;
    }

    /**
     * The world path's drawing loop, for the tests, with the client-derived parts supplied.
     *
     * <p>Everything {@code drawContents} derives from a live {@code ClientLevel} / {@code Minecraft} is
     * passed in: the boxes come from the component model, which is pure arithmetic, and the lightmap
     * value never reaches a vertex position. That is what lets a unit test emit real geometry through
     * the world path - the exporter is the same {@link #emitBoxes} the renderer uses - instead of
     * asserting a formula twice. See {@code WorldPathCoordinatesTest}.
     */
    static void emitWorldBoxesForTest(PoseStack.Pose pose, VertexConsumer buffer, BlockPos pos, Slot slot,
                                      List<Box> boxes, AtlasUv uv, BoxScratch scratch) {
        emitBoxes(pose, buffer, worldVertexBase(pos, new float[3]), slot, boxes, uv, 0, scratch);
    }

    /** The component inside each carried host, drawn in the carrier's own frame. */
    private static void drawCarriedContentsInto(PoseStack pose, ClientLevel level, List<Carried> hosts,
                                                AtlasUv uv, Counter counter, Vec3 cameraPos,
                                                CarrierGeometry.RenderPlan plan) {
        if (hosts.isEmpty()) {
            return;
        }
        // THE CARRIED SOURCE, NOT THE WORLD ONE. This line read `contentsBuffer()` - which returns a
        // consumer from `BUFFERS`, the source `AFTER_ENTITIES` ends - so every carried component quad
        // was written into a batch that had already been submitted. The component was absent on screen
        // and `contentQuads=12` in the log, for 431 frames. See §17.
        VertexConsumer buffer = carriedContentsBuffer(plan);
        BlockRenderDispatcher dispatcher = Minecraft.getInstance().getBlockRenderer();
        List<Box> boxes = new ArrayList<>();
        BoxScratch scratch = new BoxScratch();
        Vector3d world = new Vector3d();
        float[] anchorOffset = new float[3];
        // ONE PER FRAME, NOT ONE PER SIDE PER HOST (P1.42). "Which structure's own coordinate holds a
        // component" is the same question for every host of the frame, and the carrier index answers it
        // by walking its sections - asked per side it would be six walks of it per host.
        Map<Long, CarrierId> inStructure = ClientHostCache.carriedLocalIndex(level.dimension());

        for (Carried host : hosts) {
            Slot slot = host.slot();
            // The neighbours are asked in TWO frames, and only one of them used to be asked: the
            // structure's own coordinates (a wire's neighbours inside the same machine - the arms the
            // author was missing) and the world index at the block's origin. See `neighboursOf`.
            Neighbours neighbours = neighboursOf(level, host, inStructure);
            CarrierFrame carrierFrame = host.frame();
            carrierFrame.blockCentre(world);
            int light = lightAt(level, world);

            // THE COMPONENT IS PLACED BY THE SAME FRAME THE SHELL USES, and this is where that matters.
            //
            // A block model's vertices are the usual 0..1, so they are measured from the block's CORNER,
            // and the frame's vertex space is measured from the pivot. One small offset reconciles the
            // two: `local - pivot`, computed in double, which for a Sable sub-level cancels two
            // two-million-block numbers down to about half a block. After it, everything the model
            // renderer sees is a fraction of a block.
            //
            // The offset is ADDED TO EACH VERTEX rather than pushed as a `pose.translate`, which is the
            // form the shell uses and therefore the form the matrix probe can compare: the contents'
            // matrix is then `T(position) · R · S`, literally the shell's matrix, and the two call sites
            // differ only in the base they emit at - a point the probe now measures directly. See
            // Counter#probe for the defect the old shared-translation form hid.
            //
            // The defect this replaced: the frame used to place the structure's *origin* (the old
            // `T(-pivot) · R · T(position)`), the component was then drawn at `BlockPos.ZERO`, and the
            // `local` coordinate was never added at all. For a Create contraption `local` is (0,0,0) and
            // the omission was invisible; for a Sable sub-level it is 2.05e7, so the component was drawn
            // two thousand blocks from the block it lives in while the shell - which does pass `local` -
            // was drawn in the right place.
            carriedModelBase(carrierFrame, anchorOffset);

            pose.pushPose();
            applyCarrierFrame(pose, carrierFrame);

            // THE MATRIX THE CONTENTS ARE HANDED. See CarrierMatrixProbe: paired with the shell's probe
            // for the same host, this is the number that says whether the shell and the component are in
            // one frame - and with the base passed here it must be zero for every pose, rotation and
            // local coordinate.
            if (!counter.hasProbe()) {
                counter.probe("contents", host.carrier().shortId(), pose.last(), carrierFrame,
                        anchorOffset, new Vector3d(cameraPos.x, cameraPos.y, cameraPos.z));
            }

            int emitted = 0;
            InnerComponentVisuals.Visual visual = InnerComponentVisuals.of(slot, neighbours);
            if (visual == null) {
                boxes.clear();
                InnerComponentModel.boxes(slot, neighbours, boxes);
                emitted += emitBoxes(pose.last(), buffer, anchorOffset, slot, boxes, uv, light,
                        scratch);
                pose.popPose();
                counter.contentQuads += emitted / 4;
                if (emitted > 0) {
                    counter.hostsWithContent++;
                }
                continue;
            }
            emitted += drawModel(pose, buffer, anchorOffset, visual, light, dispatcher);

            boxes.clear();
            ConnectionBars.bars(slot, neighbours, boxes);
            emitted += emitBoxes(pose.last(), buffer, anchorOffset, slot, boxes, uv, light, scratch);
            pose.popPose();
            counter.contentQuads += emitted / 4;
            if (emitted > 0) {
                counter.hostsWithContent++;
            }
        }
    }

    /**
     * Emits one quad of a shell face, at {@code (baseX, baseY, baseZ)}.
     *
     * <p>Takes the three offsets as plain floats rather than a {@link BlockPos} because the three
     * callers measure from different things: the world path and the wrench highlight from a block's
     * world corner, the carried path from the host block's corner inside its carrier's frame. All of
     * them end up as the {@code base + corner} a vertex needs, and none has to pretend to be another -
     * which is also why the carried contents path takes the same kind of base rather than pushing a
     * second {@code translate}: one expression, two frames, and a probe that can compare them.
     *
     * <p><b>Both terms are small, and that is a requirement rather than a coincidence.</b> The sum is
     * computed in {@code double} and narrowed once, so a carried shell in a Sable plot space cannot
     * lose its own block: see {@link CarrierFrame}.
     */
    private static void emitShellQuad(VertexConsumer buffer, PoseStack.Pose pose, float baseX,
                                      float baseY, float baseZ, int face, float[] tile, int argb,
                                      int light, AtlasUv uv, float[] normal, float[] point) {
        shellVertex(buffer, pose, baseX, baseY, baseZ, face, tile[0], tile[1], argb, light, uv, normal,
                point);
        shellVertex(buffer, pose, baseX, baseY, baseZ, face, tile[2], tile[1], argb, light, uv, normal,
                point);
        shellVertex(buffer, pose, baseX, baseY, baseZ, face, tile[2], tile[3], argb, light, uv, normal,
                point);
        shellVertex(buffer, pose, baseX, baseY, baseZ, face, tile[0], tile[3], argb, light, uv, normal,
                point);
    }

    private static void shellVertex(VertexConsumer buffer, PoseStack.Pose pose, float baseX,
                                    float baseY, float baseZ, int face, float a, float b, int argb,
                                    int light, AtlasUv uv, float[] normal, float[] point) {
        HostShell.corner(face, a, b, point);
        buffer.addVertex(pose, (float) ((double) baseX + point[0]),
                        (float) ((double) baseY + point[1]),
                        (float) ((double) baseZ + point[2]))
                .setColor(argb)
                .setUv(uv.flatU(), uv.flatV())
                .setLight(light)
                .setNormal(pose, normal[0], normal[1], normal[2]);
    }

    /**
     * <b>The WORLD path's shell buffer.</b> Used by {@link #drawShells} and {@link #drawHighlight} - the
     * hosts standing in the world - and by nothing else.
     *
     * <p>Without shaders: vanilla's own translucent type, through the vanilla buffer source, which the
     * level renderer flushes in the ordinary translucent pass. That is deliberate: a shader pack
     * recognises a render type by identity, and the world shell keeps vanilla's instance. (The carried
     * shell cannot do that - see {@code carriedShellBuffer} - because it is drawn two stages later and
     * needs its own batch.)
     *
     * <p>With a shader pack: vanilla's cutout type instead, in our own buffer. Translucent geometry is
     * the water program, and a pack's water shader reads the extra vertex attributes that only its own
     * terrain pipeline fills - geometry drawn by hand has none, which is why the shell used to vanish
     * under Complementary Unbound. Cutout is the plainest terrain program there is. What is lost is the
     * transparency of the panel and the frame, which the caller leaves out or draws solid accordingly.
     *
     * <p><b>The name is the contract.</b> It used to be {@code shellBuffer()}, and the carried shell drew
     * through it - into a source whose batch is ended at {@code AFTER_ENTITIES}, two stages before the
     * carried stage runs. Every quad was counted and none was submitted, for 431 frames. A method named
     * after the <em>path it belongs to</em> cannot be borrowed by the other path without the mistake
     * being visible in the diff.
     */
    private static VertexConsumer worldShellBuffer() {
        if (ShaderCompat.vanillaGeometry()) {
            // Under a shader pack the world shell deliberately moves into our own source, because a pack's
            // translucent program needs vertex attributes hand-built geometry does not have. Then it IS
            // ours to submit, and it is counted as such.
            return tally(HostRenderTypes.BUFFERS.getBuffer(RenderType.cutout()),
                    HostBufferTally.Sink.WORLD, HostBufferTally.Kind.SHELL);
        }
        // Otherwise it is the level renderer's own source, submitted by its translucent section layer
        // later in the frame. That is the RENDERER sink, not the WORLD one: counting it as ours produced
        // 305 false "WORLD STAGE WROTE NOTHING" alarms in one session whose world path was working
        // perfectly. See HostBufferTally.Sink.
        return tally(Minecraft.getInstance().renderBuffers().bufferSource()
                .getBuffer(RenderType.translucent()),
                HostBufferTally.Sink.RENDERER, HostBufferTally.Kind.SHELL);
    }

    /**
     * <b>The WORLD path's contents buffer.</b> Used by {@link #drawContents} and by nothing else.
     *
     * <p>The type is ours normally and vanilla's cutout under a shader pack; either way the source is
     * ours, ended at {@code AFTER_ENTITIES}.
     */
    private static VertexConsumer worldContentsBuffer() {
        return tally(HostRenderTypes.BUFFERS.getBuffer(ShaderCompat.vanillaGeometry()
                ? RenderType.cutout()
                : HostRenderTypes.CONTENTS),
                HostBufferTally.Sink.WORLD, HostBufferTally.Kind.CONTENTS);
    }

    /**
     * <b>The CARRIED path's shell buffer</b>: the same source the carried stage flushes, and only that.
     *
     * <p>Without shaders this is our own {@link HostRenderTypes#CARRIED_SHELL} - vanilla's translucent
     * state minus the depth test and minus the depth write. The carried shell is placed from the
     * carrier's last known pose, which the client may be a tick behind on, so an honest depth test
     * rejects it the moment the structure has turned past it; that is the author's "转速一快，外框也没了".
     *
     * <p>With a shader pack it is vanilla's own cutout, because a pack picks its program from the render
     * type's identity - but still through {@code CARRIED_BUFFERS}, so that the carried stage's
     * {@code endBatch} is what submits it. The <b>contents</b> deliberately do not follow it there: see
     * {@link CarrierGeometry.RenderPlan} for why the shell takes vanilla's identity and the component
     * inside it keeps the depth-ignoring type it has always had.
     */
    private static VertexConsumer carriedShellBuffer(CarrierGeometry.RenderPlan plan) {
        return tally(HostRenderTypes.CARRIED_BUFFERS.getBuffer(plan.vanillaShellType()
                ? RenderType.cutout()
                : HostRenderTypes.CARRIED_SHELL),
                HostBufferTally.Sink.CARRIED, HostBufferTally.Kind.SHELL);
    }

    /**
     * <b>The CARRIED path's contents buffer</b>: the same source the carried stage flushes.
     *
     * <p>Identical state to {@link HostRenderTypes#CONTENTS} - cutout, block sheet, depth ignored, depth
     * written - but a different instance in a different source, because the world path's was ended two
     * stages earlier. This is the line the parent defect was missing.
     *
     * <p><b>The type does not change under a shader pack, and that is the shader defect's fix.</b> It
     * used to become vanilla's {@code cutout} - which <em>tests</em> depth - so every fragment of a
     * component inside a carried block failed against the block's own faces, which are drawn around it by
     * the structure's renderer. The frame survived because it is pushed outside the block; the component
     * is inside it, so it vanished, and only with a pack drawing. See
     * {@link CarrierGeometry.RenderPlan#vanillaContentsType()}.
     */
    private static VertexConsumer carriedContentsBuffer(CarrierGeometry.RenderPlan plan) {
        return tally(HostRenderTypes.CARRIED_BUFFERS.getBuffer(plan.vanillaContentsType()
                ? RenderType.cutout()
                : HostRenderTypes.CARRIED_CONTENTS),
                HostBufferTally.Sink.CARRIED, HostBufferTally.Kind.CONTENTS);
    }

    /**
     * Wraps a consumer so every vertex written through it is counted for one sink and kind.
     *
     * <p>The count has to be taken at the vertex, not at the decision, which is the whole lesson of §17:
     * a {@code VertexConsumer} obtained from a source that has already been flushed still accepts
     * vertices, and the only difference between it and a live one is what happens to them afterwards.
     */
    private static VertexConsumer tally(VertexConsumer delegate, HostBufferTally.Sink sink,
                                        HostBufferTally.Kind kind) {
        return new CountingVertexConsumer(delegate, sink, kind);
    }

    /**
     * Draws each host's own block model inside out, with vanilla's cutout type.
     *
     * <p>This is what stands in for the block while a shader pack is drawing: the block has been left
     * out of the chunk mesh, so the surface seen when looking at it has to come from somewhere. Drawing
     * the model with a mirrored pose turns every quad around, and with back-face culling on, what
     * survives is exactly the inside walls of the block - a closed, textured box seen from within. So
     * nothing behind the block shows through, and the components inside sit in front of those walls.
     *
     * <p>The pose mirrors in X about the block's centre, which maps the unit cube onto itself: the
     * block's own shape is preserved and only its winding - and the texture, which is symmetric on
     * almost every block a player would hide redstone in - changes.
     */
    private static void drawHostInteriors(PoseStack pose, ClientLevel level, List<Visible> hosts,
                                         Counter counter) {
        // The block's own surface, drawn by hand because a shader pack needs it to come from somewhere
        // once the block has left the chunk mesh. It shares the world source with the world contents, and
        // is tallied apart because no config gate controls it.
        CountingVertexConsumer buffer = new CountingVertexConsumer(
                HostRenderTypes.BUFFERS.getBuffer(RenderType.cutout()),
                HostBufferTally.Sink.WORLD, HostBufferTally.Kind.INTERIOR);
        BlockRenderDispatcher dispatcher = Minecraft.getInstance().getBlockRenderer();
        for (Visible host : hosts) {
            var state = level.getBlockState(host.pos());
            var model = dispatcher.getBlockModelShaper().getBlockModel(state);
            int light = lightFor(level, host.pos());
            pose.pushPose();
            pose.translate(host.pos().getX(), host.pos().getY(), host.pos().getZ());
            pose.translate(1.0, 0.0, 0.0);
            pose.scale(-1.0F, 1.0F, 1.0F);
            dispatcher.getModelRenderer().renderModel(pose.last(), buffer, state, model,
                    1.0F, 1.0F, 1.0F, light, OverlayTexture.NO_OVERLAY, ModelData.EMPTY, null);
            pose.popPose();
            // Counted from the same vertices the tally saw, so the "did we submit what we drew" equation
            // covers this geometry too. See `recordWorldStage`.
            counter.interiorQuads = buffer.vertices() / 4;
        }
    }

    /**
     * The component inside each host, drawn over the block's own face.
     *
     * <p>Unlike the carried path, this one is drawn through the pass's own pose - which is
     * {@code T(-cameraPos)} and nothing else - so its vertices have to be <b>world coordinates</b>: the
     * block's position plus the block-local {@code 0..1} the model and the boxes are built in. That is
     * the same form the shell uses, and the same form the carried path arrives at by a different route
     * (a frame whose translation already carries the block). Getting it wrong in either direction puts
     * the component a block away from its own shell, in one axis, for every host in the world.
     */
    private static void drawContents(PoseStack pose, ClientLevel level, List<Visible> hosts, AtlasUv uv,
                                     Counter counter) {
        VertexConsumer buffer = worldContentsBuffer();
        BlockRenderDispatcher dispatcher = Minecraft.getInstance().getBlockRenderer();
        List<Box> boxes = new ArrayList<>();
        BoxScratch scratch = new BoxScratch();
        // The world path's base: the block's own corner, in world coordinates, because its pose stack
        // sits at the camera and names nothing else. The carried path's base is the same expression
        // measured in the carrier's frame - which is the point, and why both are a plain float triple.
        float[] base = new float[3];

        for (Visible host : hosts) {
            Slot slot = host.slot();
            Neighbours neighbours = neighboursOf(level, host.pos());
            int light = lightFor(level, host.pos());
            worldVertexBase(host.pos(), base);

            // The component itself: vanilla's own model wherever one fits (everywhere except the
            // pressure plate, which cannot be placed yet), our coloured boxes where none does.
            InnerComponentVisuals.Visual visual = InnerComponentVisuals.of(slot, neighbours);
            if (visual == null) {
                boxes.clear();
                InnerComponentModel.boxes(slot, neighbours, boxes);
                int fallback = emitBoxes(pose.last(), buffer, base, slot, boxes, uv, light, scratch);
                counter.contentQuads += fallback / 4;
                if (fallback > 0) {
                    counter.hostsWithContent++;
                }
                continue;
            }
            int emitted = drawModel(pose, buffer, base, visual, light, dispatcher);

            // Then the routing: which sides this component talks to, which the wrench pinned and which
            // it cut. Vanilla has no such idea, so those markers stay ours - and for a wire the flat
            // lines of an ordinary connection are already part of the model drawn above.
            boxes.clear();
            ConnectionBars.bars(slot, neighbours, boxes);
            emitted += emitBoxes(pose.last(), buffer, base, slot, boxes, uv, light, scratch);
            counter.contentQuads += emitted / 4;
            if (emitted > 0) {
                counter.hostsWithContent++;
            }
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
     * <p>A {@code null} render type asks the model for every quad it has, whichever layer it would
     * normally be drawn in; they all go into our own depth-ignoring buffer, which is what makes the
     * inside of a block visible at all.
     *
     * <p>The pose stack has already been placed by the caller - at the camera for the world path, at
     * the carrier's frame for the carried one - so the model needs only {@code base}: the block's
     * corner in whichever of those two spaces the stack is currently in. A block model's vertices are
     * the usual {@code 0..1} measured from the corner, so {@code base + vertex} is the world (or frame)
     * position of that vertex, exactly as it is for the shell's own unit cube.
     *
     * @return how many vertices the model's renderer wrote, so the caller can count real geometry
     *     rather than a host it hoped had produced some
     */
    private static int drawModel(PoseStack pose, VertexConsumer buffer, float[] base,
                                 InnerComponentVisuals.Visual visual, int light,
                                 BlockRenderDispatcher dispatcher) {
        BakedModel model = dispatcher.getBlockModelShaper().getBlockModel(visual.state());
        CountingVertexConsumer measured = new CountingVertexConsumer(buffer);
        pose.pushPose();
        pose.translate(base[0], base[1], base[2]);
        pose.mulPose(visual.transform());
        dispatcher.getModelRenderer().renderModel(pose.last(), measured, visual.state(), model,
                visual.red(), visual.green(), visual.blue(), light, OverlayTexture.NO_OVERLAY,
                ModelData.EMPTY, null);
        pose.popPose();
        return measured.vertices();
    }

    /**
     * Emits our own boxes: a length of wire per connection, the wrench's markers, and the fallback
     * shape for any component no vanilla model fits.
     *
     * <p>A box marked {@link Box#textured()} is drawn with the wire art, spread over its faces, so a
     * connection reads as redstone; the art is mostly transparent, and cutout throws the transparent
     * pixels away, which is what turns the box into a thin line. Everything else is a flat colour.
     *
     * <p>The corners are the block-local {@code 0..1} ones {@link InnerComponentModel.Basis#point}
     * produced, translated by {@code pos}. The two callers pass different things and that is the whole
     * of the difference between them: the world path passes the block's world position, because the pose
     * stack is at the camera; the carried path passes {@link BlockPos#ZERO}, because its frame's
     * translation is already the block's own - {@code T(position)} in the pose stack plus the
     * {@code local - pivot} offset it pushed. Neither ever puts a plot coordinate into a {@code float}:
     * the carried path's offset was computed in {@code double} and is at most a couple of blocks.
     *
     * @return how many vertices were written
     */
    private static int emitBoxes(PoseStack.Pose pose, VertexConsumer buffer, float[] base, Slot slot,
                                 List<Box> boxes, AtlasUv uv, int light, BoxScratch scratch) {
        // The boxes are built in component space, so they have to be carried into the block by the same
        // frame the component is drawn in - see InnerComponentModel.Basis#of(Slot).
        InnerComponentModel.Basis basis = InnerComponentModel.Basis.of(slot);
        int vertices = 0;
        for (Box box : boxes) {
            float[][] local = InnerComponentModel.corners(box);
            for (int i = 0; i < local.length; i++) {
                basis.point(local[i][0], local[i][1], local[i][2], scratch.mapped);
                scratch.corners[i][0] = base[0] + scratch.mapped[0];
                scratch.corners[i][1] = base[1] + scratch.mapped[1];
                scratch.corners[i][2] = base[2] + scratch.mapped[2];
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
                    vertices++;
                }
            }
        }
        return vertices;
    }

    /**
     * A vertex consumer that forwards everything and counts what went through it.
     *
     * <p>The one way to answer "was any geometry actually emitted" without parsing the vertex buffer:
     * the model renderer writes through whatever it is handed, so handing it a counter is exact. It is
     * one small object per host per frame, next to the {@code Vector3f} the {@code addVertex} overload
     * already allocates per vertex.
     *
     * <h2>Two jobs, and the second one is the important one</h2>
     * With no sink it is a <b>local</b> counter, used by {@code drawModel} to learn how many vertices a
     * model produced. With a sink it also reports every vertex to {@link HostBufferTally} - and that is
     * the count the frame summary prints, because it is taken where the vertex is written rather than
     * where the quad was decided. A consumer for a batch that has already been flushed still accepts
     * vertices; only this count can tell the difference.
     */
    private static final class CountingVertexConsumer implements VertexConsumer {

        private final VertexConsumer delegate;
        /** Which source this consumer was obtained from, or {@code null} for a local count only. */
        @Nullable
        private final HostBufferTally.Sink sink;
        @Nullable
        private final HostBufferTally.Kind kind;
        private int vertices;

        /** A local counter: counts for the caller, reports nothing to the session tally. */
        CountingVertexConsumer(VertexConsumer delegate) {
            this(delegate, null, null);
        }

        CountingVertexConsumer(VertexConsumer delegate, @Nullable HostBufferTally.Sink sink,
                               @Nullable HostBufferTally.Kind kind) {
            this.delegate = delegate;
            this.sink = sink;
            this.kind = kind;
        }

        int vertices() {
            return vertices;
        }

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            vertices++;
            if (sink != null && kind != null) {
                HostBufferTally.written(sink, kind, 1);
            }
            return delegate.addVertex(x, y, z);
        }

        @Override
        public VertexConsumer setColor(int red, int green, int blue, int alpha) {
            delegate.setColor(red, green, blue, alpha);
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            delegate.setUv(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            delegate.setUv1(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            delegate.setUv2(u, v);
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            delegate.setNormal(x, y, z);
            return this;
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
    static final class BoxScratch {
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
     * <b>Which of a riding host's six neighbours hold a component of their own - in both frames
     * (P1.42).</b>
     *
     * <h2>The defect this replaces, stated exactly</h2>
     * This asked {@code neighboursOf(level, host.origin())} - the ordinary world host index, at the
     * world block the component was picked up from. That index holds the hosts <em>standing in the
     * world</em>, and a carried host's data has been <b>moved out</b> of it into the carrier index,
     * which is keyed by the structure's own coordinate. So inside a structure the answer was "no
     * neighbour" on all six sides: every inner wire was drawn as a bare dot however it was actually
     * wired - the author's "physicalised, and every block is just a dot" - while the identical wiring on
     * the ground drew its arms, because there the world index and the block's own position are the same
     * frame and the same key.
     *
     * <p>The solver never had this defect: {@code CarrierSpace#nodeAt} asks the carrier index at the
     * local coordinate, which is why {@code PowerSolver} charges the hop between two carried wires. The
     * drawing and the solving disagreeing about the same question is exactly what this method exists to
     * stop.
     *
     * <p>So a riding host asks both frames: the structure's own (a neighbour inside the same machine)
     * and the world index at the block's origin (an ordinary host block still standing where the machine
     * came from). A fallback host - drawn at a world block because its carrier's pose went stale - asks
     * the world frame only: it is not inside the structure any more.
     *
     * @param inStructure per-frame "local coordinate -&gt; the carrier holding a component there", from
     *     {@link ClientHostCache#carriedLocalIndex}
     */
    private static Neighbours neighboursOf(ClientLevel level, Carried host,
                                           Map<Long, CarrierId> inStructure) {
        BlockPos local = host.local();
        BlockPos origin = host.origin();
        CarrierId carrier = host.carrier();
        boolean riding = host.riding();
        return direction -> carriedNeighbourHolds(
                riding && local != null ? inStructure.get(local.relative(direction).asLong()) : null,
                carrier,
                ClientHostCache.slotAt(level.dimension(), origin.relative(direction)) != null);
    }

    /**
     * <b>The one rule behind a carried host's connection arms (P1.42), pure so it can be pinned without
     * a level.</b> A neighbour is either another host of <em>the same structure</em>, one block away in
     * the structure's own coordinates, or a host the world index still holds where the block came from.
     *
     * <p>The carrier identity is compared rather than assumed. A plot coordinate names one sub-level's
     * block, but a Create contraption's local coordinates are 0..15 around its anchor, so two
     * contraptions can hold a host at the same local coordinate - and that is a coincidence, not a
     * connection. Comparing it can only ever refuse an arm, never invent one.
     *
     * @param inStructure the carrier holding a component at the neighbouring local coordinate, or
     *     {@code null} when that coordinate is empty (or the host is not riding)
     * @param self the carrier the host being drawn belongs to
     * @param worldIndex whether the world host index holds a component at the neighbour's world position
     */
    static boolean carriedNeighbourHolds(@Nullable CarrierId inStructure, @Nullable CarrierId self,
                                         boolean worldIndex) {
        return worldIndex || inStructure != null && self != null && self.equals(inStructure);
    }

    /**
     * The border of one block, in a highlight colour.
     *
     * <p>Only the rims of the shell are drawn - the frame without the frosted panel - so the block
     * underneath stays readable and the mark reads as a selection rather than a paint job.
     */
    private static void drawHighlight(PoseStack.Pose pose, ClientLevel level, @Nullable BlockPos pos,
                                      int argb, AtlasUv uv) {
        if (pos == null || level == null) {
            return;
        }
        VertexConsumer buffer = worldShellBuffer();
        int light = lightFor(level, pos);
        float[] normal = new float[3];
        float[] point = new float[3];
        for (int face = 0; face < HostShell.FACE_COUNT; face++) {
            HostShell.normal(face, normal);
            // Quad 0 is the centre panel; the rest are the border.
            for (int quad = 1; quad < HostShell.quadsPerFace(true); quad++) {
                float[] tile = HostShell.tile(quad, true);
                emitShellQuad(buffer, pose, pos.getX(), pos.getY(), pos.getZ(), face, tile, argb,
                        light, uv, normal, point);
            }
        }
    }

    // --------------------------------------------------------------- helpers --

    /**
     * The light at a block, floored so a buried component is never black.
     *
     * <p>Without shaders the floor is on the block light alone. The component sits in the shade of its
     * own block, which looks right because vanilla draws the block around it as well.
     *
     * <p>With a shader pack the block's own faces are gone from the mesh, so this number is the only
     * thing lighting the inside - and the packs that lean hardest on shadows and ambient occlusion
     * (Eclipse, for one) darken geometry further, because the extra vertex data their shaders use for
     * that is filled by their own terrain pipeline and never by code drawing by hand. The floor is
     * therefore raised on both halves while a pack is drawing: a component in a dark hole stays
     * readable instead of turning into a black square. Twelve rather than full brightness on purpose -
     * at fifteen the inside glows and stops reading as part of the world.
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

    /**
     * The light at an arbitrary point in the world, for a component that is not at a whole block.
     *
     * <p>Used by the carried path. Two differences from {@link #lightFor}, both deliberate:
     *
     * <ul>
     *   <li>The query is <b>block light only</b>, with a floor. A component inside a machine is in the
     *       shade of the machine, and the sky value at a point in mid-air is a poor description of
     *       what a player can read anyway. Taking only the block half keeps a machine that flies into
     *       the dark legible instead of turning into a black silhouette.</li>
     *   <li>The point is rounded to a block, because that is what the light map is indexed by.</li>
     * </ul>
     */
    private static int lightAt(ClientLevel level, Vector3d point) {
        int light = LevelRenderer.getLightColor(level, BlockPos.containing(point.x, point.y, point.z));
        int block = (light >> 4) & 0xF;
        int floor = ShaderCompat.vanillaGeometry() ? 12 : 8;
        return (Math.max(block, floor) << 4) | (light & 0xF);
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
    record AtlasUv(float flatU, float flatV, float wireU0, float wireU1, float wireV0,
                           float wireV1) {
    }
}

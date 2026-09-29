package com.jiangyuefengyu.redstonecircuit.client;

import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;

import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import com.jiangyuefengyu.redstonecircuit.RedstoneCircuit;

/**
 * The render types the mod's own geometry is drawn with, and the buffers each of them is written into.
 *
 * <h2>Two paths, two buffers, and why the split is the fix</h2>
 * The world path and the carried path do not reach the screen at the same moment, so they cannot share
 * one flush. A host standing in the world is drawn <em>before</em> the block it lives in has been drawn
 * by anything else, so depth may be ignored; a host riding a contraption or a sub-level is drawn
 * <em>after</em> that structure's own blocks have been written, and geometry flushed any earlier is
 * simply covered by them. That is the defect this split exists for:
 *
 * <pre>
 *   world path   -> flushed at RenderLevelStageEvent.Stage.AFTER_ENTITIES          (ours)
 *   carried path -> flushed at RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS (ours)
 *   world shell  -> flushed by LevelRenderer's own translucent section layer
 * </pre>
 *
 * <h2>Why exactly one custom cutout, and the carried copies</h2>
 * {@link #CONTENTS} is block-like: it samples the block atlas, carries a lightmap and is tinted per
 * vertex, which is what makes it blend with the world instead of looking pasted on. It <em>ignores</em>
 * depth, because the block's own opaque cube was drawn long before this pass and geometry that honestly
 * tested depth from inside the block would be rejected by the block's own front face - which is exactly
 * what stopped the inside from being visible in the first build. That bypass is only safe because the
 * pass checks the line of sight to each host before drawing it (unless X-ray vision is asked for in the
 * config), so "drawn on top" only ever happens for a block the player can actually see.
 *
 * <p>The frosted shell of a <b>world</b> host is <b>not</b> here: it is drawn with vanilla's own
 * {@link RenderType#translucent()} through the vanilla buffer source, because a shader pack recognises a
 * render type by identity. A type built to look exactly like vanilla's is still a stranger to Iris -
 * Complementary Unbound drew the components but dropped our shell - while glass and water, which use
 * that very instance, came through untouched.
 *
 * <p>The carried path cannot use the vanilla translucent instance at all, and cannot use
 * {@link #CONTENTS} either:
 * <ul>
 *   <li>{@link #CARRIED_SHELL} has to be flushed by us at a known moment, and vanilla's translucent
 *       instance is flushed by the level renderer's own section pass, which is the wrong moment and the
 *       wrong buffer to end early. It ignores depth and does <em>not</em> write it: a carried host is
 *       drawn at the tail of the frame, so a depth write there would reject the water, the weather and
 *       the entities drawn after it with no way for the player to tell why.</li>
 *   <li>{@link #CARRIED_CONTENTS} is the same state as {@link #CONTENTS}, in its own buffer, for the same
 *       reason the shell has one: two types sharing a buffer would sort over each other's half-written
 *       data.</li>
 * </ul>
 *
 * <p>Under a shader pack both carried types are replaced by vanilla's own cutout, exactly as the world
 * path's contents are - the render type identity is what Iris selects its program by, and that rule is
 * not relaxed here.
 */
@OnlyIn(Dist.CLIENT)
final class HostRenderTypes {

    /**
     * The components inside the block: vanilla's own cutout, except that depth is ignored.
     *
     * <p>Cutout rather than translucent because these are vanilla's block textures, and vanilla draws
     * them as cutout: the transparent pixels around a torch or a lever have to be discarded, not
     * blended, or they would sort against each other and smear. Depth is ignored for the one reason
     * this whole pass exists - the block's own opaque cube was drawn long before it, so geometry that
     * honestly tested depth from inside the block would be rejected by the block's own front face, which
     * is exactly what stopped the inside from being visible in the first build.
     *
     * <p>Depth <em>is</em> written, even though it is not tested. Shader packs that light the world
     * from a deferred buffer read the fragment's depth back out to find where it is; a fragment that
     * wrote colour alone has no depth to be lit with, and the packs that do this drop it - which is why
     * the components were missing under Bliss, Eclipse and Photon while the shell still showed. Depth
     * is still not written as a *test*, so a component cannot hide the water or the entities drawn
     * after it unless it is genuinely in front of them.
     */
    static final RenderType CONTENTS = createCutoutIgnoringDepth(
            RedstoneCircuit.MODID + "_contents", RenderStateShard.COLOR_DEPTH_WRITE);

    /**
     * The same components, for a host riding a moving structure: flushed by us at the carried stage.
     *
     * <p>Identical state to {@link #CONTENTS} and deliberately a second instance, because it needs a
     * second buffer: the world path's buffer is ended at {@code AFTER_ENTITIES}, and a carried vertex
     * written into it after that end would either be flushed at the wrong moment or dropped.
     */
    static final RenderType CARRIED_CONTENTS = createCutoutIgnoringDepth(
            RedstoneCircuit.MODID + "_carried_contents", RenderStateShard.COLOR_DEPTH_WRITE);

    /**
     * The frosted shell of a carried host, without a shader pack: the panel <em>and</em> the border.
     *
     * <p>Translucent (it is a frosted panel, so it blends), depth-ignoring (see below) and colour-only:
     * no depth write, because this is drawn at the tail of the frame and a depth write there would veto
     * the water and the entities that come after it.
     *
     * <p><b>Why depth is ignored here, when the world shell tests it.</b> The world shell is emitted
     * from the same tick as the block it covers, so its faces are coplanar with the block's and pass the
     * test. A carried shell is placed from the carrier's last known pose, which the client may be one
     * tick behind on - and at speed that is enough for the structure's blocks to have moved past the
     * shell's faces, at which point an honest depth test rejects the whole shell and it flickers out.
     * The author's report is exactly that: the frame visible at rest, gone when the bearing spins. The
     * line of sight is checked before a carried host is collected, which is the same guarantee
     * {@link #CONTENTS} relies on, so the shell still cannot be drawn through a mountain.
     */
    static final RenderType CARRIED_SHELL = create(CarriedTypes.SHELL_NAME);

    /** The names of the two carried types, so the diagnostics can print them without repeating them. */
    static final class CarriedTypes {
        static final String SHELL_NAME = RedstoneCircuit.MODID + "_carried_shell";

        private CarriedTypes() {
        }
    }

    /**
     * One buffer per render type, plus a shared one nothing uses.
     *
     * <p>Per type rather than shared because translucent batches are sorted on upload, and sorting
     * writes into the buffer it is handed - so a type sorting into the buffer another type is still
     * filling would corrupt both.
     */
    private static final Object2ObjectLinkedOpenHashMap<RenderType, ByteBufferBuilder> BUILDERS =
            new Object2ObjectLinkedOpenHashMap<>();

    /** The buffer source the world path is written into, ended at {@code AFTER_ENTITIES}. */
    static final MultiBufferSource.BufferSource BUFFERS = MultiBufferSource.immediateWithBuffers(
            BUILDERS, new ByteBufferBuilder(1536));

    /**
     * The buffer source the carried path is written into, ended at the carried stage.
     *
     * <p>Separate from {@link #BUFFERS} rather than merely a later {@code endBatch} on it, because the
     * two are ended at different stages and a vertex written after its source was ended is gone. The
     * compat binaries for the carried types live here rather than in {@link #BUILDERS} for the same
     * reason: "which batch was this vertex in" has to be answerable from the type alone.
     */
    private static final Object2ObjectLinkedOpenHashMap<RenderType, ByteBufferBuilder> CARRIED_BUILDERS =
            new Object2ObjectLinkedOpenHashMap<>();

    static final MultiBufferSource.BufferSource CARRIED_BUFFERS = MultiBufferSource.immediateWithBuffers(
            CARRIED_BUILDERS, new ByteBufferBuilder(1536));

    static {
        BUILDERS.put(CONTENTS, new ByteBufferBuilder(8192));
        CARRIED_BUILDERS.put(CARRIED_CONTENTS, new ByteBufferBuilder(4096));
        CARRIED_BUILDERS.put(CARRIED_SHELL, new ByteBufferBuilder(4096));
    }

    /**
     * The carried types, in the order they must be flushed: <b>content first, shell second.</b>
     *
     * <p>Both types ignore depth, so within the carried stage their order on screen is their order in
     * the frame. The shell is the panel that covers the block, so drawing it last would wash the
     * component it exists to frame; drawing it first leaves the component crisp on top, which is the
     * order the world path arrives at by accident of the level renderer's own schedule. Exposed as an
     * array so the pass and the tests agree on the sequence instead of each restating it.
     */
    static final RenderType[] CARRIED_FLUSH_ORDER = { CARRIED_CONTENTS, CARRIED_SHELL };

    /**
     * Submits the world source, and tells the tally that this is what got flushed.
     *
     * <p>The two things have to happen together, in this order, and they are a method rather than two
     * lines at the call site because forgetting the second is invisible: the batch still goes to the
     * screen, and only the diagnostic would be wrong. See {@link HostBufferTally}.
     *
     * <p>It also <b>hands over</b> the level renderer's source, which is where the world shell goes when no
     * shader pack is drawing. That is a different fact from a flush - see
     * {@link HostBufferTally.Sink#RENDERER} - and keeping the two apart is what stopped a per-frame false
     * "WORLD STAGE WROTE NOTHING" alarm.
     */
    static void endWorldBatch() {
        BUFFERS.endBatch();
        HostBufferTally.flush(HostBufferTally.Sink.WORLD);
        HostBufferTally.handOver(HostBufferTally.Sink.RENDERER);
    }

    /**
     * Submits the carried source, and tells the tally. See {@link #endWorldBatch()}.
     *
     * <p>This is the {@code endBatch} the whole carried split exists for: it runs at
     * {@code AFTER_TRANSLUCENT_BLOCKS}, after every structure the game draws. A vertex a carried draw
     * point wrote anywhere else is not in this batch and will never be submitted.
     */
    static void endCarriedBatch() {
        CARRIED_BUFFERS.endBatch();
        HostBufferTally.flush(HostBufferTally.Sink.CARRIED);
    }

    /** The cutout state, with the given write mask: {@code COLOR_DEPTH_WRITE} or {@code COLOR_WRITE}. */
    private static RenderType createCutoutIgnoringDepth(String name, RenderStateShard.WriteMaskStateShard write) {
        return RenderType.create(
                name,
                DefaultVertexFormat.BLOCK,
                VertexFormat.Mode.QUADS,
                8192,
                false,
                false,
                RenderType.CompositeState.builder()
                        .setShaderState(RenderStateShard.RENDERTYPE_CUTOUT_SHADER)
                        .setTextureState(RenderStateShard.BLOCK_SHEET)
                        .setDepthTestState(RenderStateShard.NO_DEPTH_TEST)
                        .setWriteMaskState(write)
                        .setLightmapState(RenderStateShard.LIGHTMAP)
                        .setCullState(RenderStateShard.CULL)
                        .createCompositeState(true));
    }

    /**
     * The carried shell: vanilla's translucent state, minus the depth test and minus the depth write.
     *
     * <p>Vanilla's own {@code translucent()} composite is quoted here rather than reused because the
     * two states it has that this one must not are exactly the two that make a carried shell flicker:
     * the depth test, and {@code COLOR_DEPTH_WRITE}. Everything else is vanilla's - the same shader,
     * the same block sheet, the same transparency state - so the panel looks like the world path's
     * panel wherever it does appear.
     */
    private static RenderType create(String name) {
        return RenderType.create(
                name,
                DefaultVertexFormat.BLOCK,
                VertexFormat.Mode.QUADS,
                1536,
                false,
                true,
                RenderType.CompositeState.builder()
                        .setShaderState(RenderStateShard.RENDERTYPE_TRANSLUCENT_SHADER)
                        .setTextureState(RenderStateShard.BLOCK_SHEET_MIPPED)
                        .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                        .setDepthTestState(RenderStateShard.NO_DEPTH_TEST)
                        .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                        .setLightmapState(RenderStateShard.LIGHTMAP)
                        .setCullState(RenderStateShard.CULL)
                        .createCompositeState(true));
    }

    private HostRenderTypes() {
    }
}

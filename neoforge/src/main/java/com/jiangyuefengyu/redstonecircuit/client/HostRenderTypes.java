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
 * <p>世界路径在结构画完之前结束、可以忽略深度；搬运中的 host 画在结构方块之后，早刷的几何体会被它们盖住，所以两条
 * 路径各有自己的缓冲和刷新时刻。内容用忽略深度的 cutout（方块自己的不透明立方体早就画过了，而每个 host 在收集前都
 * 查过视线，所以"画在上面"只发生在玩家真能看到的方块上）；搬运的壳由我们自己刷新且不写深度，否则会挡掉之后画的水
 * 和实体。有光影包时两种搬运类型都换成原版 cutout，因为 Iris 按 render type 的身份选程序。
 */
@OnlyIn(Dist.CLIENT)
final class HostRenderTypes {

    /** The components inside the block: vanilla's own cutout, except that depth is ignored. Cutout rather than
     * translucent because these are vanilla's block textures - the transparent pixels around a torch or a lever have to
     * be discarded, not blended. Depth <em>is</em> written, because shader packs that light the world from a deferred
     * buffer read the fragment's depth back out, and it is not written as a <em>test</em>, so a component cannot hide
     * the water or the entities drawn after it. */
    static final RenderType CONTENTS = createCutoutIgnoringDepth(
            RedstoneCircuit.MODID + "_contents", RenderStateShard.COLOR_DEPTH_WRITE);

    /** The same components, for a host riding a moving structure: flushed by us at the carried stage. A second
     * instance, because it needs a second buffer - the world path's buffer is ended at {@code AFTER_ENTITIES}. */
    static final RenderType CARRIED_CONTENTS = createCutoutIgnoringDepth(
            RedstoneCircuit.MODID + "_carried_contents", RenderStateShard.COLOR_DEPTH_WRITE);

    /** The frosted shell of a carried host, without a shader pack: the panel <em>and</em> the border. Translucent,
     * depth-ignoring and colour-only - no depth write, because this is drawn at the tail of the frame and a write there
     * would veto the water and the entities after it. Depth is ignored because the shell is placed from the carrier's
     * last known pose, which the client may be one tick behind on: at speed the structure's blocks move past the shell's
     * faces and an honest depth test rejects the whole shell. The line of sight is checked first, as for {@link #CONTENTS}. */
    static final RenderType CARRIED_SHELL = create(CarriedTypes.SHELL_NAME);

    /** The names of the two carried types, so the diagnostics can print them without repeating them. */
    static final class CarriedTypes {
        static final String SHELL_NAME = RedstoneCircuit.MODID + "_carried_shell";

        private CarriedTypes() {
        }
    }

    /** One buffer per render type, plus a shared one nothing uses. Per type because translucent batches are sorted on
     * upload and sorting writes into the buffer it is handed, so a type sorting into a buffer another is still filling
     * would corrupt both. */
    private static final Object2ObjectLinkedOpenHashMap<RenderType, ByteBufferBuilder> BUILDERS =
            new Object2ObjectLinkedOpenHashMap<>();

    /** The buffer source the world path is written into, ended at {@code AFTER_ENTITIES}. */
    static final MultiBufferSource.BufferSource BUFFERS = MultiBufferSource.immediateWithBuffers(
            BUILDERS, new ByteBufferBuilder(1536));

    /** The buffer source the carried path is written into, ended at the carried stage. Separate from {@link #BUFFERS}
     * because the two are ended at different stages and a vertex written after its source was ended is gone; the compat
     * binaries live here for the same reason - "which batch was this vertex in" has to be answerable from the type. */
    private static final Object2ObjectLinkedOpenHashMap<RenderType, ByteBufferBuilder> CARRIED_BUILDERS =
            new Object2ObjectLinkedOpenHashMap<>();

    static final MultiBufferSource.BufferSource CARRIED_BUFFERS = MultiBufferSource.immediateWithBuffers(
            CARRIED_BUILDERS, new ByteBufferBuilder(1536));

    static {
        BUILDERS.put(CONTENTS, new ByteBufferBuilder(8192));
        CARRIED_BUILDERS.put(CARRIED_CONTENTS, new ByteBufferBuilder(4096));
        CARRIED_BUILDERS.put(CARRIED_SHELL, new ByteBufferBuilder(4096));
    }

    /** The carried types, in the order they must be flushed: <b>content first, shell second.</b> Both ignore depth, so
     * within the carried stage their order in the frame is their order on screen: drawing the shell last would wash the
     * component it exists to frame. An array so the pass and the tests agree on the sequence. */
    static final RenderType[] CARRIED_FLUSH_ORDER = { CARRIED_CONTENTS, CARRIED_SHELL };

    /** Submits the world source and tells the tally that this is what got flushed. A method rather than two lines at
     * the call site because forgetting the second is invisible - the batch still reaches the screen and only the
     * diagnostic is wrong. It also <b>hands over</b> the level renderer's source, which is where the world shell goes
     * when no shader pack is drawing. */
    static void endWorldBatch() {
        BUFFERS.endBatch();
        HostBufferTally.flush(HostBufferTally.Sink.WORLD);
        HostBufferTally.handOver(HostBufferTally.Sink.RENDERER);
    }

    /** Submits the carried source and tells the tally; see {@link #endWorldBatch()}. This is the {@code endBatch} the
     * whole carried split exists for: it runs at {@code AFTER_TRANSLUCENT_BLOCKS}, after every structure the game draws. */
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

    /** The carried shell: vanilla's translucent state, minus the depth test and minus the depth write - exactly the two
     * states that make a carried shell flicker. Everything else is vanilla's, so the panel looks like the world path's
     * panel wherever it does appear. */
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

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
 * The render types the mod draws with.
 *
 * <h2>Why custom ones</h2>
 * Both are block-like: they sample the block atlas, carry a lightmap and are tinted per vertex, which
 * is what makes them blend with the world instead of looking pasted on. They differ in exactly one
 * respect, and it is the whole trick behind "see the component inside the block":
 *
 * <ul>
 *   <li>{@link #SHELL} tests depth normally. A wall in front of a host block hides it like it hides
 *       anything else. It cannot write depth, because it is translucent.</li>
 *   <li>{@link #CONTENTS} <em>ignores</em> depth. The block's own cube is opaque and was drawn long
 *       before this pass, so geometry that honestly tested depth from inside the block would be
 *       rejected by the block's own front face - which is exactly what stopped the inside from being
 *       visible in the first build. Drawing without the test puts the component on top of the block it
 *       is buried in.</li>
 * </ul>
 *
 * <p>That bypass is only safe because the pass checks the line of sight to each host before drawing it
 * (unless X-ray vision is asked for in the config), so "drawn on top" only ever happens for a block the
 * player can actually see.
 */
@OnlyIn(Dist.CLIENT)
final class HostRenderTypes {

    /**
     * The frosted shell: depth-tested, alpha-blended, no depth write.
     *
     * <p>The same state vanilla's own translucent block type uses for water and glass.
     */
    static final RenderType SHELL = create("shell", RenderStateShard.LEQUAL_DEPTH_TEST);

    /** The component inside the block: everything the shell has, except that depth is ignored. */
    static final RenderType CONTENTS = create("contents", RenderStateShard.NO_DEPTH_TEST);

    /**
     * One buffer per render type, plus a shared one nothing uses.
     *
     * <p>Per type rather than shared because translucent batches are sorted on upload, and sorting
     * writes into the buffer it is handed - so a type sorting into the buffer another type is still
     * filling would corrupt both.
     */
    private static final Object2ObjectLinkedOpenHashMap<RenderType, ByteBufferBuilder> BUILDERS =
            new Object2ObjectLinkedOpenHashMap<>();

    /** The buffer source everything is written into, reused across frames. */
    static final MultiBufferSource.BufferSource BUFFERS = MultiBufferSource.immediateWithBuffers(
            BUILDERS, new ByteBufferBuilder(1536));

    static {
        BUILDERS.put(SHELL, new ByteBufferBuilder(4096));
        BUILDERS.put(CONTENTS, new ByteBufferBuilder(8192));
    }

    private HostRenderTypes() {
    }

    private static RenderType create(String name, RenderStateShard.DepthTestStateShard depth) {
        return RenderType.create(
                RedstoneCircuit.MODID + "_" + name,
                DefaultVertexFormat.BLOCK,
                VertexFormat.Mode.QUADS,
                1536,
                false,
                true,
                RenderType.CompositeState.builder()
                        .setShaderState(RenderStateShard.RENDERTYPE_TRANSLUCENT_SHADER)
                        .setTextureState(RenderStateShard.BLOCK_SHEET_MIPPED)
                        .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                        .setDepthTestState(depth)
                        .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                        .setLightmapState(RenderStateShard.LIGHTMAP)
                        .setCullState(RenderStateShard.CULL)
                        .createCompositeState(false));
    }
}

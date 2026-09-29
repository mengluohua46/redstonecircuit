package com.jiangyuefengyu.redstonecircuit.client;

import com.mojang.blaze3d.vertex.ByteBufferBuilder;

import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * Where the overlay's own geometry is written. Deliberately contains no render type of its own.
 *
 * <h2>What used to be here, and why it is gone</h2>
 * The components inside a block used to be drawn with a private {@code RenderType} that ignored the
 * depth test: the host block's own cube was in the chunk mesh and had already written depth, so
 * geometry that honestly tested depth from inside the block was rejected by the block's own front
 * face. A type that skips the depth test is the obvious answer and it worked, but it is a type no
 * shader pack has ever heard of - and Iris picks a program, and the extra vertex attributes a pack's
 * shaders expect, from the <em>identity</em> of the render type it is handed. What the player saw was
 * the worst possible presentation of that: the frosted shell, drawn with vanilla's own
 * {@code translucent()} because that one <em>is</em> recognised, kept appearing while everything drawn
 * with our type silently did not - "only the outer frame is visible".
 *
 * <p>The fault was that the block was in the mesh at all. So it is taken out instead
 * ({@code BlockRenderDispatcherMixin}, {@code SodiumBlockRendererMixin}) and every vertex the overlay
 * draws goes into a vanilla render type taken by reference from {@link RenderType} itself: cutout for
 * the block's inside-out model, the components, the route markers and the frame, and translucent for
 * the wrench highlight. There is no type of ours left to be a stranger to a pack, nothing to keep in
 * step with vanilla's state, and - the point of the exercise - one drawing path whether or not a pack
 * is selected.
 *
 * <h2>What this class is still for</h2>
 * The overlay runs during {@code WorldRenderEvents.AFTER_ENTITIES}, outside the vanilla level
 * renderer's own immediate buffers, so it needs a buffer source of its own to write into and flush.
 * Cutout is where all the solid geometry goes, so it is given a buffer up front and the same one is
 * reused every frame; the translucent type the wrench highlight uses is allocated by the source itself
 * the first time it is asked for. Both are keyed by render type rather than shared, because a
 * translucent batch is sorted on upload and sorting writes into the buffer it is handed.
 */
@Environment(EnvType.CLIENT)
final class HostRenderTypes {

    /** The type every solid part of the overlay is drawn with: vanilla's own, taken by reference. */
    static final RenderType SOLID = RenderType.cutout();

    /** The type the wrench highlight is drawn with: vanilla's translucent, so water blends over it. */
    static final RenderType HIGHLIGHT = RenderType.translucent();

    private static final Object2ObjectLinkedOpenHashMap<RenderType, ByteBufferBuilder> BUILDERS =
            new Object2ObjectLinkedOpenHashMap<>();

    /** The buffer source everything the overlay draws is written into, reused across frames. */
    static final MultiBufferSource.BufferSource BUFFERS = MultiBufferSource.immediateWithBuffers(
            BUILDERS, new ByteBufferBuilder(1536));

    static {
        // Enough for a screenful of hosts' insides; the source grows a type's buffer on demand.
        BUILDERS.put(SOLID, new ByteBufferBuilder(8192));
    }

    private HostRenderTypes() {
    }
}

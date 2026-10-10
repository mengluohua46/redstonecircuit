package com.jiangyuefengyu.redstonecircuit.client;

import com.mojang.blaze3d.vertex.ByteBufferBuilder;

import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import com.jiangyuefengyu.redstonecircuit.RedstoneCircuit;

/**
 * Where each half of the overlay's geometry is written, and what submits it.
 *
 * <h2>Every type here is vanilla's own, taken by reference</h2>
 * The components inside a block used to be drawn with a private {@code RenderType} that ignored the depth
 * test: the host block's own cube was in the chunk mesh and had already written depth, so geometry that
 * honestly tested depth from inside the block was rejected by the block's own front face. A type that skips
 * the depth test is the obvious answer and it worked, but it is a type no shader pack has ever heard of -
 * and Iris picks a program, and the extra vertex attributes a pack's shaders expect, from the
 * <em>identity</em> of the render type it is handed. What the player saw was the worst possible
 * presentation of that: the frosted shell, drawn with vanilla's own {@code translucent()} because that one
 * <em>is</em> recognised, kept appearing while everything drawn with our type silently did not.
 *
 * <p>The fault was that the block was in the mesh at all. So it is taken out instead
 * ({@code BlockRenderDispatcherMixin}, {@code SodiumBlockRendererMixin}) and every vertex the overlay
 * draws goes into a vanilla render type taken by reference from {@link RenderType} itself: {@code cutout}
 * for the block's inside-out model, the components, the route markers and the frames, and
 * {@code translucent} for the shell's own colour and the wrench highlight, whose colours carry a real
 * alpha. There is no type of ours left to be a stranger to a pack, nothing to keep in step with vanilla's
 * state, and - the point of the exercise - one drawing path whether or not a pack is selected.
 *
 * <h2>Two sources, because the two halves are submitted at different moments</h2>
 * The overlay runs outside the vanilla level renderer's own immediate buffers, so it needs a buffer source
 * of its own to write into and flush. The world path - the block's inside, the component in it and the
 * frame around it - is written into {@link #BUFFERS} and ended at {@code AFTER_ENTITIES}; the hosts riding
 * a moving structure are written into {@link #CARRIED_BUFFERS}, which is ended further on in the frame. A
 * vertex written after its source was ended is gone, so which batch a vertex belongs to has to be
 * answerable from the type it was written with - which is why the carried halves have entries of their own
 * here even where the type is vanilla's.
 *
 * <p>One buffer per render type, plus the shared one the source grows types from: per type because a
 * translucent batch is sorted on upload and sorting writes into the buffer it is handed, so a type sorting
 * into a buffer another is still filling would corrupt both.
 */
@Environment(EnvType.CLIENT)
final class HostRenderTypes {

    /** The type every solid part of the overlay is drawn with: vanilla's own cutout, taken by reference. */
    static final RenderType SOLID = RenderType.cutout();

    /** The components inside the block, and the block's own inside-out model with them: vanilla's cutout.
     * Cutout rather than translucent because these are vanilla's block textures - the transparent pixels
     * around a torch or a lever have to be discarded, not blended. */
    static final RenderType CONTENTS = SOLID;

    /** The same components, for a host riding a moving structure. The same vanilla type in a source of its
     * own, because the world path's source was submitted earlier in the same frame. */
    static final RenderType CARRIED_CONTENTS = SOLID;

    /** The wrench highlight: vanilla's own translucent, taken by reference. Blending is the whole point of a
     * highlight, whose colour carries a real alpha. */
    static final RenderType HIGHLIGHT = RenderType.translucent();

    /** The world path's frame: the same vanilla translucent, handed to the level renderer's own source
     * rather than written into ours, so that water in front of a host blends over it in the ordinary
     * translucent pass. See {@link HostBufferTally.Sink#RENDERER}. */
    static final RenderType SHELL = HIGHLIGHT;

    /** The carried frame: the same vanilla translucent again, in the carried source, because the carried
     * path's geometry is written in a different frame and submitted by this build. */
    static final RenderType CARRIED_SHELL = HIGHLIGHT;

    /** The names of the carried types, so the diagnostics can print them without repeating them. */
    static final class CarriedTypes {
        static final String SHELL_NAME = RedstoneCircuit.MODID + "_carried_shell";

        private CarriedTypes() {
        }
    }

    /** One buffer per render type, plus a shared one nothing uses. Per type because translucent batches are
     * sorted on upload and sorting writes into the buffer it is handed, so a type sorting into a buffer
     * another is still filling would corrupt both. */
    private static final Object2ObjectLinkedOpenHashMap<RenderType, ByteBufferBuilder> BUILDERS =
            new Object2ObjectLinkedOpenHashMap<>();

    /** The buffer source the world path is written into, ended at {@code AFTER_ENTITIES}. */
    static final MultiBufferSource.BufferSource BUFFERS = MultiBufferSource.immediateWithBuffers(
            BUILDERS, new ByteBufferBuilder(1536));

    /** The buffer source the carried path is written into, ended later in the same frame. Separate from
     * {@link #BUFFERS} because the two are ended at different moments and a vertex written after its source
     * was ended is gone; the accounting lives here for the same reason - "which batch was this vertex in"
     * has to be answerable from the type. */
    private static final Object2ObjectLinkedOpenHashMap<RenderType, ByteBufferBuilder> CARRIED_BUILDERS =
            new Object2ObjectLinkedOpenHashMap<>();

    static final MultiBufferSource.BufferSource CARRIED_BUFFERS = MultiBufferSource.immediateWithBuffers(
            CARRIED_BUILDERS, new ByteBufferBuilder(1536));

    static {
        // Enough for a screenful of hosts' insides; the source grows a type's buffer on demand.
        BUILDERS.put(CONTENTS, new ByteBufferBuilder(8192));
        CARRIED_BUILDERS.put(CARRIED_CONTENTS, new ByteBufferBuilder(4096));
        CARRIED_BUILDERS.put(CARRIED_SHELL, new ByteBufferBuilder(4096));
    }

    /** The carried types, in the order they are submitted: content first, frame second. An array rather than
     * two constants at the call site so that the sequence is one fact the pass and the tests share - a
     * submitted batch cannot be reordered afterwards, and the two halves are written into the same source. */
    static final RenderType[] CARRIED_FLUSH_ORDER = { CARRIED_CONTENTS, CARRIED_SHELL };

    /** Submits the world source and tells the tally that this is what got flushed. A method rather than two
     * lines at the call site because forgetting the second is invisible - the batch still reaches the screen
     * and only the diagnostic is wrong. It also <b>hands over</b> the level renderer's source, which is where
     * the world path's frame goes: that source is submitted by the renderer's own translucent section layer,
     * so what can be recorded about it is how much was given to it. */
    static void endWorldBatch() {
        BUFFERS.endBatch();
        HostBufferTally.flush(HostBufferTally.Sink.WORLD);
        HostBufferTally.handOver(HostBufferTally.Sink.RENDERER);
    }

    /** Submits the carried source and tells the tally; see {@link #endWorldBatch()}. This is the
     * {@code endBatch} the whole carried split exists for, and it submits the two types in
     * {@link #CARRIED_FLUSH_ORDER} rather than relying on the order they happened to be written in. */
    static void endCarriedBatch() {
        CARRIED_BUFFERS.endLastBatch();
        for (RenderType type : CARRIED_FLUSH_ORDER) {
            CARRIED_BUFFERS.endBatch(type);
        }
        HostBufferTally.flush(HostBufferTally.Sink.CARRIED);
    }

    private HostRenderTypes() {
    }
}

package com.jiangyuefengyu.redstonecircuit.client;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.lwjgl.system.MemoryUtil;

import com.jiangyuefengyu.redstonecircuit.RCConfig;
import com.jiangyuefengyu.redstonecircuit.structure.StructurePreview;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * A preview's miniature emitted once and kept as <b>bytes</b>, so drawing it again is a copy and an upload.
 *
 * <p>What a miniature costs is the emission, not the pixels: a 1,405-cell structure is about 0.5 ms of block model
 * geometry per draw, and the same picture is drawn for the block in the world, for the stack in the hand and for the
 * slot that stack sits in. So the walk is done once, into one {@link BufferBuilder} per {@link RenderType}, and the
 * bytes it produced are copied out and kept.
 *
 * <p><b>Why bytes and not the {@link MeshData} the builder produced.</b> A mesh is not a value that can be held: it is
 * one {@code ByteBufferBuilder.Result} - a view of an offset inside a native arena - and the ordinary draw path frees
 * it. {@code VertexBuffer.upload(MeshData)} closes the mesh it is handed on every path
 * ({@code VertexBuffer.java:35-73}: line 64 {@code p_350419_.close()} after a successful upload, line 71 on the
 * invalid-buffer path, line 54 inside the catch), and {@code BufferUploader.drawWithShader} uploads through exactly
 * that method ({@code BufferUploader.java:33}); so <b>the first draw frees the mesh it drew</b>. Closing the last
 * result of an arena also moves what is left of it and bumps the arena's generation
 * ({@code ByteBufferBuilder.java:93-108}: {@code freeResult} -> {@code discardResults} ->
 * {@code this.generation++}), after which every result ever taken from that arena throws
 * {@code IllegalStateException("Buffer is no longer valid")} the moment anything reads it
 * ({@code ByteBufferBuilder.java:138-144}). Vanilla therefore keeps no mesh across frames - its batch is
 * build, draw, closed - and holding one here failed on the <b>second</b> draw of every picture, which is what sent both
 * previews back to the cell-by-cell path with one line in the log each.
 *
 * <p><b>What is kept instead.</b> The vertex bytes and the {@code MeshData.DrawState} describing them; each draw copies
 * the bytes into a fresh {@code ByteBufferBuilder} and builds a fresh {@code MeshData} from that
 * ({@code new MeshData(result, drawState)}). The copy is what a draw costs and it is one {@code memcpy} per render
 * type - the picture is not re-emitted, which is the expensive half. One arena serves every type of one picture: each
 * finished mesh is closed by its upload, which hands the arena back for the next type, and that is the same reuse
 * {@code MultiBufferSource.BufferSource} relies on with its one shared builder.
 *
 * <p><b>What the key is.</b> The picture: the preview's own hash, plus the live view's revision, which is unique across
 * every structure this client knows and never repeats. So the hand, the icon and a placed structure with nothing live
 * around it share one entry, and a running circuit gets one entry per revision with every older one dropped.
 *
 * <p><b>Where the vertices are.</b> In the block's own unit cube: the half block that centres the picture, the scale
 * that fits it in a block and every nested picture are baked in, and nothing of the caller's pose is. That pose is
 * applied by the model view stack, as it is for everything else in a frame - {@code current × pose} - so one kept copy
 * serves the block, the hand and the icon. The one thing this costs is fog: vanilla measures it from the vertex as
 * written, which here is the block's own cube, so a placed structure far away fades late.
 *
 * <p><b>What this may not do.</b> No {@code VertexBuffer} of ours, no VAO of ours, no shader of ours: vertices are
 * emitted by the game's own {@link BufferBuilder} and drawn by the game's own {@link BufferUploader} with the render
 * type's own state, which is the path a shader pack is written against. Nothing thrown from here may reach the render
 * loop - a failure is caught, written down once for that picture, and the picture at that revision is drawn one cell at
 * a time from then on.
 *
 * <p>Render thread only, and only from a drawing pass: it uploads.
 */
@OnlyIn(Dist.CLIENT)
final class StructurePreviewMesh {

    /** How many pictures are kept. Several places share one; a running circuit moves through revisions. */
    static final int LIMIT = 16;

    /**
     * How many failures are remembered, so a picture that cannot be drawn does not retry - and log - on every frame.
     * Bounded because what it is keyed by is the player's structures, not ours.
     */
    private static final int REMEMBERED = 64;

    /**
     * How much room a builder starts with. It grows as it fills - a large miniature is far more than this - and one
     * buffer per render type means that growth is paid by the type that needed it.
     */
    private static final int BUILD_BYTES = 1536;

    /**
     * The kept pictures, least recently drawn first out. An entry owns direct buffers, which the collector returns once
     * the entry is dropped, so evicting one is dropping a reference and nothing else.
     */
    private static final Map<Key, Uploaded> CACHE = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Key, Uploaded> eldest) {
            return size() > LIMIT;
        }
    };

    /**
     * The revision each picture last failed at, or nothing while it has not failed.
     *
     * <p>Remembered <b>per revision</b> rather than per picture: a picture that could not be emitted is given up where
     * it stands, but a live view that has moved on is a different picture and is worth one more attempt - which is the
     * difference between "this one is broken" and "the renderer was in a bad state for a moment".
     */
    private static final Map<Integer, Integer> FAILED_AT = new LinkedHashMap<>(16, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, Integer> eldest) {
            return size() > REMEMBERED;
        }
    };

    /** The pictures whose failure has already been written down, so a circuit that re-revisions cannot flood the log. */
    private static final Set<Integer> REPORTED = new LinkedHashSet<>();

    private StructurePreviewMesh() {
    }

    /**
     * What one kept picture is filed under: the picture's own hash, and the live revision it was resolved from
     * ({@code 0} for the blueprint).
     *
     * <p>Two equal keys are the same picture and nothing else, so a cache hit can never draw another structure's
     * cells, and a live view that has moved on can never be answered with what it looked like before.
     */
    record Key(int hash, int revision, int detail) {

        static Key of(StructurePreview preview, int revision, int detail) {
            return new Key(preview.hash(), revision, detail);
        }
    }

    /**
     * Whether a kept piece of geometry has anything in it.
     *
     * <p>A render type no cell ever reached - the one a block model uses for its translucent parts, when nothing in the
     * picture is translucent - is skipped before its state is touched: a state change that draws nothing is paid for by
     * the rest of the frame.
     */
    static boolean drawable(int vertexCount) {
        return vertexCount > 0;
    }

    /**
     * Draws {@code preview}'s miniature from the kept copy, and answers whether it did.
     *
     * <p>{@code false} means "not drawn": the caller must draw the cells one at a time. It is the answer for the switch
     * being off, for this picture having failed <em>at this revision</em>, and for a picture that could not be emitted.
     * It is never a throwable - see the class.
     *
     * @param pose     where the block's own unit cube is; the picture is placed by it
     * @param revision the live revision {@code cells} were resolved from, {@code 0} for the blueprint
     * @param cells    the resolved cells, exactly what the cell-by-cell path would draw
     */
    static boolean draw(Matrix4f pose, StructurePreview preview, int revision,
                        List<StructurePreviewGeometry.Cell> cells, BlockRenderDispatcher dispatcher,
                        StructurePreviewGeometry.Detail detail) {
        if (!RCConfig.structurePreviewCached()) {
            // The switch is also the way out of a build that crashes, so what it finds already kept goes with it:
            // nobody can see a picture this path will not draw again, and those buffers are not small.
            clear();
            return false;
        }
        Integer failedAt = FAILED_AT.get(preview.hash());
        if (failedAt != null && failedAt.intValue() == revision) {
            return false;
        }
        Key key = Key.of(preview, revision, detail.ordinal());
        Uploaded picture = CACHE.get(key);
        if (picture == null) {
            picture = emit(preview, revision, cells, dispatcher, detail);
            if (picture == null) {
                return false;
            }
            CACHE.put(key, picture);
        }
        return submit(pose, preview, revision, picture);
    }

    /**
     * Emits the whole miniature and copies out what each render type produced, before anything can free it.
     *
     * <p>{@code null} when the emission threw or nothing was emitted: the picture is given up in the first case, and
     * the arenas go back in the {@code finally} either way - a half-built picture is of no use to anybody.
     */
    @Nullable
    private static Uploaded emit(StructurePreview preview, int revision,
                                 List<StructurePreviewGeometry.Cell> cells, BlockRenderDispatcher dispatcher,
                                 StructurePreviewGeometry.Detail detail) {
        Sink sink = new Sink();
        try {
            StructurePreviewGeometry.bake(sink, cells, preview, detail, dispatcher);
            Map<RenderType, Geometry> geometries = new LinkedHashMap<>();
            int largest = 0;
            for (Map.Entry<RenderType, BufferBuilder> entry : sink.builders.entrySet()) {
                MeshData mesh = entry.getValue().build();
                if (mesh == null) {
                    // A type that was asked for a buffer and got nothing emitted into it.
                    continue;
                }
                try {
                    // Copied out before anything closes it: the bytes are what survives, the mesh is a view.
                    Geometry geometry = Geometry.copyOf(mesh);
                    geometries.put(entry.getKey(), geometry);
                    largest = Math.max(largest, geometry.bytes().remaining());
                } finally {
                    mesh.close();
                }
            }
            return geometries.isEmpty() ? null : new Uploaded(geometries, largest);
        } catch (Throwable failed) {
            giveUp(preview, revision, failed);
            return null;
        } finally {
            sink.close();
        }
    }

    /**
     * Draws one kept picture at the caller's pose: for each render type the bytes are copied back into the arena and a
     * fresh mesh is built from them, because the upload that draws a mesh closes it (see the class).
     *
     * <p>The pose is applied by the model view stack - {@code current × pose} - and both that stack and the matrix the
     * shaders read are given back in the {@code finally}: a pass that leaves the model view moved draws the rest of the
     * frame from the wrong place, which is a crash in vanilla's own terms. Each render type sets its own state up, gets
     * the draw and clears it again, because the state is the type's and not ours.
     */
    private static boolean submit(Matrix4f pose, StructurePreview preview, int revision, Uploaded picture) {
        Matrix4fStack stack = RenderSystem.getModelViewStack();
        stack.pushMatrix();
        try {
            stack.mul(pose);
            RenderSystem.applyModelViewMatrix();
            // One arena for the whole picture, sized for its largest type: a draw closes its own mesh, which hands the
            // arena back for the next type - the reuse MultiBufferSource.BufferSource itself relies on.
            ByteBufferBuilder arena = new ByteBufferBuilder(picture.bytes());
            try {
                for (Map.Entry<RenderType, Geometry> entry : picture.geometries().entrySet()) {
                    Geometry geometry = entry.getValue();
                    if (!drawable(geometry.state().vertexCount())) {
                        continue;
                    }
                    RenderType type = entry.getKey();
                    MeshData mesh = meshOf(arena, geometry);
                    if (mesh == null) {
                        continue;
                    }
                    try {
                        type.setupRenderState();
                        try {
                            BufferUploader.drawWithShader(mesh);
                        } finally {
                            type.clearRenderState();
                        }
                    } finally {
                        // Already closed by the upload; this covers the path where the state or the draw threw first.
                        mesh.close();
                    }
                }
            } finally {
                arena.close();
            }
            return true;
        } catch (Throwable failed) {
            giveUp(preview, revision, failed);
            // This frame keeps what was already drawn: drawing the cells on top of it would be a second picture in the
            // same place, and from the next frame on this picture at this revision takes the cell-by-cell path again.
            return true;
        } finally {
            stack.popMatrix();
            RenderSystem.applyModelViewMatrix();
        }
    }

    /**
     * One mesh, valid for one draw: the kept bytes written back into {@code arena}, with the state that describes them.
     *
     * <p>{@code reserve} hands out a range of the arena and {@code build} takes a result over everything written since
     * the last one, which is how {@code BufferBuilder} itself puts vertices into a {@code ByteBufferBuilder}; the copy
     * is the only step that is ours.
     */
    @Nullable
    private static MeshData meshOf(ByteBufferBuilder arena, Geometry geometry) {
        ByteBuffer bytes = geometry.bytes();
        int length = bytes.remaining();
        if (length <= 0) {
            return null;
        }
        long target = arena.reserve(length);
        MemoryUtil.memCopy(MemoryUtil.memAddress(bytes), target, (long) length);
        ByteBufferBuilder.Result result = arena.build();
        return result == null ? null : new MeshData(result, geometry.state());
    }

    /**
     * Stops drawing a picture at this revision from a kept copy, and forgets everything kept for it.
     *
     * <p>Remembered per revision, not for ever: a live view that moves on is a different picture and gets one more
     * attempt (see {@link #FAILED_AT}). One line and no stack trace, written once per picture - this runs on the render
     * thread inside another block's pass, so the useful facts are that it happened and which switch brings the old path
     * back for everybody.
     */
    private static void giveUp(StructurePreview preview, int revision, Throwable failure) {
        Iterator<Map.Entry<Key, Uploaded>> entries = CACHE.entrySet().iterator();
        while (entries.hasNext()) {
            Map.Entry<Key, Uploaded> entry = entries.next();
            if (entry.getKey().hash() == preview.hash()) {
                entries.remove();
            }
        }
        FAILED_AT.put(preview.hash(), revision);
        boolean first = REPORTED.add(preview.hash());
        if (REPORTED.size() > REMEMBERED) {
            Iterator<Integer> eldest = REPORTED.iterator();
            eldest.next();
            eldest.remove();
        }
        if (first) {
            RCConfig.LOGGER.warn("[redstonecircuit] structure preview: drawing a kept miniature failed ({}); that "
                    + "picture at that revision is drawn one cell at a time now. structurePreviewCached=false turns "
                    + "this path off.", failure.toString());
        }
    }

    /**
     * Drops every kept picture and forgets what failed.
     *
     * <p>Kept geometry belongs to the world it was emitted for, so a client that leaves one takes none of it along; the
     * failure record goes with it for the same reason, and a picture that failed in the world that just ended gets its
     * first attempt again in the next one.
     */
    static void clear() {
        CACHE.clear();
        FAILED_AT.clear();
        REPORTED.clear();
    }

    /**
     * One builder per render type, with the buffer it builds into kept beside it.
     *
     * <p>Every {@link RenderType} has its own {@link ByteBufferBuilder} rather than sharing one: what is copied out is
     * that type's bytes, and sharing an arena would let one type's growth reallocate memory another type is still
     * reading a mesh of.
     */
    private static final class Sink implements MultiBufferSource {

        private final Map<RenderType, ByteBufferBuilder> buffers = new LinkedHashMap<>();

        private final Map<RenderType, BufferBuilder> builders = new LinkedHashMap<>();

        @Override
        public VertexConsumer getBuffer(RenderType type) {
            return this.builders.computeIfAbsent(type, fresh -> {
                ByteBufferBuilder buffer = new ByteBufferBuilder(BUILD_BYTES);
                this.buffers.put(fresh, buffer);
                return new BufferBuilder(buffer, fresh.mode(), fresh.format());
            });
        }

        /** Gives back every arena this walk allocated, whatever happened in it. */
        private void close() {
            for (ByteBufferBuilder buffer : this.buffers.values()) {
                buffer.close();
            }
            this.buffers.clear();
            this.builders.clear();
        }
    }

    /**
     * One render type's geometry of one picture: the vertex bytes, copied out of the arena they were built in, and the
     * {@code DrawState} that says how to read them.
     *
     * <p>The bytes are ours and nothing else frees them, which is the whole point: what a mesh is a view of is freed by
     * the upload that draws it, so a draw builds a new mesh from these bytes rather than reusing one.
     */
    private record Geometry(ByteBuffer bytes, MeshData.DrawState state) {

        /** Copies the emission out of the builder's arena, after which both the mesh and the arena can go. */
        static Geometry copyOf(MeshData mesh) {
            ByteBuffer source = mesh.vertexBuffer();
            ByteBuffer copy = ByteBuffer.allocateDirect(source.remaining()).order(ByteOrder.nativeOrder());
            copy.put(source);
            copy.flip();
            return new Geometry(copy, mesh.drawState());
        }
    }

    /** One kept picture: its geometry per render type, and the largest of them, which sizes the arena of a draw. */
    private record Uploaded(Map<RenderType, Geometry> geometries, int bytes) {
    }
}

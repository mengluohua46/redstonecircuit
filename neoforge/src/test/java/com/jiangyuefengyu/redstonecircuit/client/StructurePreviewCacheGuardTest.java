package com.jiangyuefengyu.redstonecircuit.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The rules the uploaded-miniature cache has to keep, asserted on the source that keeps them.
 *
 * <p>None of this can run in a unit test - it needs a render thread, a GL context and a driver - and every one of the
 * failures is expensive: a buffer closed while a mesh still points into it, a model view left moved for the rest of the
 * frame, an exception raised from inside another block's draw, a picture drawn from the cell-by-cell path and the
 * uploaded one at once. Twice already this mod has crashed inside {@code VertexBuffer.draw()} on a shader-packed
 * client, so the two things that made those crashes possible - a buffer of our own and a VAO of our own - are pinned
 * out by name.
 */
class StructurePreviewCacheGuardTest {

    private static final String MESH = "StructurePreviewMesh.java";
    private static final String GEOMETRY = "StructurePreviewGeometry.java";
    private static final String CONFIG = "RCConfig.java";
    private static final String SINK = "private static final class Sink implements MultiBufferSource {";
    private static final String SUBMIT = "private static boolean submit(";

    @Test
    @DisplayName("nothing here opens a buffer, a vertex array or a shader of its own")
    void noBufferOfOurOwn() throws IOException {
        String mesh = readClient(MESH);
        for (String forbidden : new String[] {"new VertexBuffer", "VertexArray", "glGenBuffers", "glGenVertexArrays",
                "glBindVertexArray", "GL11", "GL46", "glDrawArrays", "glDrawElements", ".apply()"}) {
            assertFalse(mesh.contains(forbidden),
                    forbidden + " is what was removed after the native crashes in a shader-packed client: the vertices "
                            + "go into the game's own BufferBuilder and the draw through the game's own uploader, "
                            + "because that is the path a shader pack is written against");
        }
        assertTrue(mesh.contains("class Sink implements MultiBufferSource"),
                "the emission is a MultiBufferSource: the same call that fills a vanilla batch fills this");
        assertTrue(mesh.contains("new BufferBuilder(buffer, fresh.mode(), fresh.format())"),
                "with one vanilla builder per render type, built from that type's own mode and format");
        assertTrue(mesh.contains("BufferUploader.drawWithShader(mesh)"),
                "and the submission is the method BufferSource.endBatch itself uses");
    }

    @Test
    @DisplayName("each render type sets its state up, is drawn, and its state is cleared again")
    void setupDrawAndClearArePaired() throws IOException {
        String submit = bodyOf(readClient(MESH), SUBMIT);
        int setup = submit.indexOf("type.setupRenderState();");
        int draw = submit.indexOf("BufferUploader.drawWithShader(mesh)");
        int clear = submit.indexOf("type.clearRenderState();");
        assertTrue(setup > 0 && setup < draw && draw < clear,
                "in that order: the state belongs to the render type, and a draw made without it is what a shader pack "
                        + "reads as a draw of the wrong thing");
        assertTrue(clear > 0 && submit.lastIndexOf("finally", clear) > draw
                        && submit.lastIndexOf("finally", clear) < clear,
                "and the clear is in a finally, so a draw that throws cannot leave the type's state set for the rest "
                        + "of the frame");
        assertTrue(submit.contains("if (!drawable(geometry.state().vertexCount())) {"),
                "while a type with no vertices is skipped before its state is touched at all");
    }

    @Test
    @DisplayName("the model view is multiplied by the caller's pose and given back, whatever happens")
    void thePoseIsAppliedAndBothStacksAreRestored() throws IOException {
        String submit = bodyOf(readClient(MESH), SUBMIT);
        assertTrue(submit.contains("RenderSystem.getModelViewStack()"), "the stack is the model view stack");
        int push = submit.indexOf("stack.pushMatrix();");
        int mul = submit.indexOf("stack.mul(pose);");
        int apply = submit.indexOf("RenderSystem.applyModelViewMatrix();");
        assertTrue(push > 0 && push < mul && mul < apply,
                "current x pose, pushed and applied before anything is drawn: the vertices are in the block's own unit "
                        + "cube, so the pose is what places that cube");

        int restore = submit.lastIndexOf("finally");
        assertTrue(restore > 0, "there is a finally after everything that can throw");
        String restored = submit.substring(restore);
        assertTrue(restored.contains("stack.popMatrix()"), "the stack is popped there");
        assertTrue(restored.contains("RenderSystem.applyModelViewMatrix()"),
                "and the matrix the shaders read is refreshed from it, or every block drawn after this one is drawn "
                        + "from the wrong place");
        assertTrue(submit.indexOf("return true;") < restore,
                "the restore is not on a path of its own: it runs for the draw, for the failure and for the throw");
    }

    @Test
    @DisplayName("a throwable is caught, the picture is given up at that revision, and one line is written")
    void aFailureIsGivenUpAndNotRaised() throws IOException {
        String mesh = readClient(MESH);
        for (String entry : new String[] {SUBMIT, "private static Uploaded emit("}) {
            assertTrue(bodyOf(mesh, entry).contains("catch (Throwable failed)"),
                    entry + " has to catch Throwable: it runs inside another block's render pass, and an exception on "
                            + "the render thread ends the game");
            assertTrue(bodyOf(mesh, entry).contains("giveUp(preview, revision, failed)"), "and gives the picture up");
        }
        String giveUp = bodyOf(mesh, "private static void giveUp(");
        assertTrue(giveUp.contains("FAILED_AT.put(preview.hash(), revision)"),
                "which is remembered per picture AND per revision: a failure that was a moment of bad state must not "
                        + "cost the picture for the rest of the session");
        String draw = bodyOf(mesh, "static boolean draw(");
        assertTrue(draw.contains("failedAt.intValue() == revision"),
                "so a later revision of the same picture is tried once more");
        assertTrue(draw.indexOf("failedAt") < draw.indexOf("CACHE.get(key)"),
                "and the answer is given before anything is emitted, which is what keeps a broken picture from being "
                        + "re-emitted every frame");
        assertEquals(1, giveUp.split("LOGGER\\.warn", -1).length - 1,
                "one line and no stack trace: this runs on the render thread inside somebody else's pass");
        assertTrue(giveUp.contains("boolean first = REPORTED.add(preview.hash());")
                        && giveUp.contains("if (first) {"),
                "written once per picture rather than once per revision, so a running circuit that re-revisions "
                        + "cannot flood the log with the same failure");
        assertTrue(giveUp.contains("structurePreviewCached=false"),
                "and it says which switch brings the old path back for everybody");
    }

    @Test
    @DisplayName("the cache is bounded")
    void theCacheIsBounded() throws IOException {
        String mesh = readClient(MESH);
        assertTrue(mesh.contains("new LinkedHashMap<>(16, 0.75f, true)"),
                "least recently drawn first out: several places draw the same picture, and the one in use is the one "
                        + "that has to survive");
        assertTrue(bodyOf(mesh, "protected boolean removeEldestEntry(").contains("size() > LIMIT"),
                "bounded by the constant, and eviction is dropping a reference: what an entry holds is a direct buffer "
                        + "on the Java heap side, which the collector returns once nobody points at it");
        assertTrue(bodyOf(mesh, "static void clear()").contains("CACHE.clear()"),
                "while leaving a world drops all of them, because a picture belongs to the world it was emitted for");
        assertTrue(bodyOf(readClient("StructureScreens.java"), "public static void onLoggingOut(")
                        .contains("StructurePreviewMesh.clear();"),
                "which is where it is called from");
    }

    @Test
    @DisplayName("what is kept is bytes: a MeshData is freed by the upload that draws it")
    void theKeptThingIsBytesAndNotAMesh() throws IOException {
        String mesh = readClient(MESH);
        assertFalse(mesh.contains("Map<RenderType, MeshData>"),
                "a mesh may not be kept: VertexBuffer.upload(MeshData) closes the mesh it is handed on every path "
                        + "(VertexBuffer.java:64, :71, :54), and BufferUploader.drawWithShader uploads through exactly "
                        + "that call - so the first draw frees the mesh it drew, and the second draw of the same picture "
                        + "throws 'Buffer is no longer valid' (ByteBufferBuilder.java:138-144, because closing the last "
                        + "result bumps the arena's generation at :107)");
        String geometry = bodyOf(mesh, "private record Geometry(");
        assertTrue(geometry.contains("MeshData.DrawState state)"),
                "what is kept is the byte buffer plus the state that says how to read it");
        String copy = bodyOf(mesh, "static Geometry copyOf(");
        assertTrue(copy.contains("ByteBuffer.allocateDirect(source.remaining())"),
                "the bytes are copied out of the builder's arena into memory of our own");
        assertTrue(copy.indexOf("copy.put(source)") < copy.indexOf("return new Geometry"),
                "before anything can free the arena they were built in");
        String emit = bodyOf(mesh, "private static Uploaded emit(");
        assertTrue(emit.indexOf("Geometry.copyOf(mesh)") > 0
                        && emit.indexOf("Geometry.copyOf(mesh)") < emit.indexOf("mesh.close()"),
                "and the mesh of the emission is closed after the copy, not before it");
        assertTrue(emit.contains("finally {") && emit.contains("sink.close()"),
                "while the emission's own arenas go back in a finally, whatever happened");
        String meshOf = bodyOf(mesh, "private static MeshData meshOf(");
        assertTrue(meshOf.contains("arena.reserve(length)") && meshOf.contains("MemoryUtil.memCopy("),
                "a draw writes the bytes back into a fresh arena - reserve is how BufferBuilder itself puts vertices "
                        + "into one - and copies only; the picture is not re-emitted");
        assertTrue(meshOf.contains("new MeshData(result, geometry.state())"),
                "and builds a mesh valid for that draw, which the upload then frees");
        assertTrue(bodyOf(mesh, "private static boolean submit(").contains("arena.close()"),
                "the arena of a draw is closed with the draw, in a finally");
    }

    @Test
    @DisplayName("the switch is on by default, and off means the old path and nothing uploaded")
    void theSwitchIsOnByDefaultAndOffMeansOff() throws IOException {
        String config = read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", CONFIG);
        assertTrue(config.contains(".define(\"structurePreviewCached\", true)"),
                "on by default: the emission is what the frame was paying for");
        assertTrue(bodyOf(config, "public static boolean structurePreviewCached()")
                        .contains("boolOr(STRUCTURE_PREVIEW_CACHED, true)"),
                "whose fallback is the same default, so a client whose config is not loaded yet still caches");
        assertTrue(config.contains(".define(\"structurePreviewCull\", true)"), "and culling stays on by default");
        assertTrue(bodyOf(config, "public static boolean structurePreviewCull()")
                        .contains("boolOr(STRUCTURE_PREVIEW_CULL, true)"), "with the same fallback");

        String draw = bodyOf(readClient(MESH), "static boolean draw(");
        assertTrue(draw.indexOf("RCConfig.structurePreviewCached()") > 0, "and it is read at the top of a draw");
        assertTrue(draw.indexOf("RCConfig.structurePreviewCached()") < draw.indexOf("CACHE.get(key)"),
                "before anything is looked up, so switching it off cannot draw half a picture");
        assertTrue(draw.contains("clear();"), "what was uploaded goes with it");
        assertTrue(draw.contains("return false;"), "and the answer is 'not drawn', which is the cell-by-cell path");
    }

    @Test
    @DisplayName("the miniature is emitted once, and the cell-by-cell path is still there")
    void oneEmissionAndAFallback() throws IOException {
        String geometry = readClient(GEOMETRY);
        String draw = bodyOf(geometry, "static void draw(PoseStack pose");
        int uploaded = draw.indexOf("StructurePreviewMesh.draw(pose.last().pose(), preview, liveRevision(liveAt), cells");
        int cells = draw.indexOf("drawInsideCube(pose, buffers, cells");
        assertTrue(uploaded > 0, "the picture is offered to the cache first");
        assertTrue(cells > uploaded, "and the cells are drawn after it, on the branch that says it was not uploaded");
        assertTrue(draw.contains("if (uploaded) {") && draw.contains("} else {"),
                "the two are alternatives: drawing both would be two pictures in the same place");

        String bake = bodyOf(geometry, "static void bake(");
        assertTrue(bake.contains("new PoseStack()"),
                "the emission starts from an identity pose, which is what makes one copy serve the block, the hand and "
                        + "the icon");
        assertTrue(bake.contains("pose.translate(0.5, 0.0, 0.5)"),
                "with the half block that centres the miniature - the picture is in the block's own unit cube, so the "
                        + "only thing a draw applies is the caller's pose");
        assertTrue(bake.contains("drawInsideCube(pose, sink, cells, preview, detail, dispatcher, null, 0L, 0.0F)"),
                "and it is the same walk, with no slides: a cell in the middle of a move is where it is THIS frame");
        assertTrue(draw.contains("drawInsideCube(pose, buffers, List.of(), preview, detail, dispatcher, slides"),
                "so the moving cells are still drawn, through the same transform, on the uploaded branch");
    }

    @Test
    @DisplayName("the hand, the icon and every placed structure share one picture")
    void theSamePictureIsShared() throws IOException {
        String entity = bodyOf(readClient("StructureBlockEntityRenderer.java"), "public void render(StructureBlockEntity");
        assertTrue(entity.contains("product.getBlockPos(), \"placed\", StructurePreviewGeometry.Detail.FULL);"),
                "a placed structure says where it stands, which is what makes its picture live, and names itself as the"
                        + " site the probe counts it under");
        String item = bodyOf(readClient("StructureItemRenderer.java"), "public void renderByItem(");
        assertTrue(item.contains("StructurePreviewGeometry.draw(pose, buffers, cells, preview, dispatcher, null, 0L, "
                        + "0.0F, null, null, site,"),
                "the hand and the icon have no position, so they land on the blueprint's own entry (revision 0) with "
                        + "every placed structure that has nothing live around it");
        assertTrue(bodyOf(readClient(GEOMETRY), "static int liveRevision(")
                        .contains("StructureLiveViews.revisionAt(liveAt)"),
                "and the revision in the key is the one the live view is at, asked in one place so the cells and the "
                        + "vertices cannot be cached under different answers");
    }

    // ------------------------------------------------------------- helpers --

    private static String readClient(String name) throws IOException {
        return read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "client", name);
    }

    private static String read(String... parts) throws IOException {
        // The working directory differs between the test task and a build from the repository root, so both spellings
        // are tried.
        Path underNeoforge = Path.of("neoforge").resolve(Path.of("", parts));
        Path direct = Path.of("", parts);
        for (Path candidate : List.of(direct, underNeoforge)) {
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.UTF_8).replace("\r\n", "\n");
            }
        }
        throw new IOException("could not find " + String.join("/", parts) + " from "
                + Path.of("").toAbsolutePath() + "; this guard reads the source on purpose");
    }

    /** The body of the first member whose declaration contains {@code signature}, by brace counting. */
    private static String bodyOf(String source, String signature) {
        int at = source.indexOf(signature);
        assertTrue(at >= 0, "the source no longer contains '" + signature + "'; the guard is stale");
        int open = source.indexOf('{', at);
        assertTrue(open > 0, "no body found for '" + signature + "'");
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return source.substring(at, i);
                }
            }
        }
        throw new IllegalStateException("unbalanced braces after '" + signature + "'");
    }
}

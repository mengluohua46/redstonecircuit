package com.jiangyuefengyu.redstonecircuit.client;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.jiangyuefengyu.redstonecircuit.RCConfig;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * Whether a shader pack is drawing the world, and what that changes about the overlay.
 *
 * <h2>What the answer is still for</h2>
 * Not which renderer draws. The overlay has one drawing path now: the host block always leaves the chunk
 * mesh ({@code BlockRenderDispatcherMixin}, {@code SodiumBlockRendererMixin}) and everything it draws uses
 * vanilla render types taken by reference - see {@link HostRenderTypes}. A type of ours was the bug: Iris
 * picks the program, and the extra vertex attributes a pack's shaders expect, from the <em>identity</em> of
 * the render type it is handed, so a type of ours was a stranger to every pack, while the shell drawn with
 * vanilla's own {@code translucent()} was recognised - "only the outer frame is visible" is exactly what
 * that looks like.
 *
 * <p>Two things still depend on the answer. The <b>light floor</b> ({@code HostRenderPass#lightFor}): with
 * a pack drawing, the block's own faces are drawn by hand rather than by the pack's terrain pipeline, so
 * the inside of a block needs a floor under it or the packs that lean hardest on shadow darken it to
 * nothing. And the <b>diagnostics</b>, which name the arrangement a log's numbers came from, so that a
 * report can be read without a second run.
 *
 * <h2>Finding out without depending on it</h2>
 * Iris publishes {@code net.irisshaders.iris.api.v0.IrisApi}, and Oculus implements the same interface, but
 * hard-depending on either to ask one boolean would put a shader mod in everyone's jar. The class is looked
 * up reflectively instead, once, and the answer is cached; when neither is installed the lookup simply
 * fails and the answer is "no shaders".
 *
 * <h2>Two questions, not one</h2>
 * Iris's {@code isShaderPackInUse()} answers "a pack is <em>selected</em>", which is not the same as "those
 * shaders are running": it stays true while {@code enableShaders} is false in {@code iris.properties}. The
 * second question is part of the same public API ({@code IrisApi#getConfig()}, whose
 * {@code areShadersEnabled()} is that flag), and a pack is drawing only when both answers are yes. Nothing
 * about the drawing branches on either answer any more, which is deliberate - a question that reads true
 * with the shaders off is not a thing to branch a renderer on, and branching on it is how a block ended up
 * drawn as a bare frame with its own cube still in the mesh hiding everything inside it.
 */
@Environment(EnvType.CLIENT)
public final class ShaderCompat {

    /** How long an answer from Iris is trusted, in milliseconds. Switching a pack is not a per-frame event. */
    private static final long CHECK_INTERVAL_MS = 1000L;

    private static boolean lookedUp;
    private static Method getInstance;
    private static Method isShaderPackInUse;
    private static Method getConfig;
    private static Method areShadersEnabled;
    /** Iris's {@code IrisApiConfig}, which answers the second question. One instance for the whole game. */
    private static Object irisConfig;
    /** Iris's own answer: a pack is <em>selected</em>. Not on its own a reason to change a renderer. */
    private static boolean packInUse;
    /** The configuration's answer: those shaders are switched <em>on</em>. Also not enough on its own. */
    private static boolean shadersEnabled;
    private static long checkedAt;
    /** The verdict already written to the log, so the one-line diagnosis appears once per change. */
    private static Boolean reportedPath;

    /**
     * Positions whose block must not be meshed, because the overlay draws them itself.
     *
     * <p>Written by the render pass on the client thread, read by the chunk mesher on its worker
     * threads, hence the copy-on-write set: a half-updated set would hide or reveal the wrong blocks.
     */
    private static volatile Set<Long> hidden = Set.of();

    /**
     * Whether the pass is currently drawing hosts itself, which is what the mesher has to know.
     *
     * <p>Volatile and static rather than passed around: the mesher is handed a block state and a
     * position, nothing else, and asking it to thread a flag through would mean a mixin on half the
     * mesher.
     */
    private static volatile boolean hidingHostCubes;

    /** Frames since the first host was published, for the "no hook ran at all" warning. */
    private static int publishedFrames;

    /**
     * How many frames to wait before declaring that no mesh hook is going to run.
     *
     * <p>The mesher is asked to rebuild the section in the same frame the set is published, and the
     * build runs on a worker thread, so a handful of frames is generous on any machine and still fast
     * enough to be useful while the player is looking at the block.
     */
    private static final int HOOK_GRACE_FRAMES = 20;

    private ShaderCompat() {
    }

    /**
     * True while a shader pack is actually drawing the world. Cheap: the answer is cached for a second.
     *
     * <p><b>Two questions, not one.</b> Iris's {@code isShaderPackInUse()} answers "a pack is
     * <em>selected</em>", which is not the same as "those shaders are running" and is not a thing to branch
     * a renderer on: it stays true while {@code enableShaders} is false. The second question is part of the
     * same public API ({@code IrisApi#getConfig()}, whose {@code areShadersEnabled()} is that flag), and the
     * pack is drawing only when both answers are yes.
     *
     * <p>Still pure reflection, still one lookup, still one answer per second. If Iris is absent, or its API
     * cannot answer either question - an older build without {@code getConfig}, a lookup that fails, a call
     * that throws - the answer is "no shaders", which is the arrangement every machine can be read as.
     */
    public static boolean shaderPackInUse() {
        long now = System.currentTimeMillis();
        if (!lookedUp) {
            lookedUp = true;
            lookUpIris();
            checkedAt = now;
            reportPath();
            return shaderPath();
        }
        if (getInstance == null || isShaderPackInUse == null || getConfig == null
                || areShadersEnabled == null) {
            return false;
        }
        if (now - checkedAt < CHECK_INTERVAL_MS) {
            return shaderPath();
        }
        checkedAt = now;
        askIris();
        reportPath();
        return shaderPath();
    }

    /** The verdict: a pack is selected <em>and</em> switched on. */
    private static boolean shaderPath() {
        return packInUse && shadersEnabled;
    }

    /**
     * Resolves Iris's public API, once.
     *
     * <p>A missing piece is not an error to report: it is the answer "no shaders", and the failure is
     * already carried by the log line {@link #reportPath()} writes.
     */
    private static void lookUpIris() {
        try {
            Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            getInstance = api.getMethod("getInstance");
            isShaderPackInUse = api.getMethod("isShaderPackInUse");
            getConfig = api.getMethod("getConfig");
            areShadersEnabled = Class.forName("net.irisshaders.iris.api.v0.IrisApiConfig")
                    .getMethod("areShadersEnabled");
        } catch (Throwable absent) {
            // No Iris and no Oculus, or an API too old to be asked about the configuration.
            getInstance = null;
            isShaderPackInUse = null;
            getConfig = null;
            areShadersEnabled = null;
        }
        packInUse = false;
        shadersEnabled = false;
    }

    /** One round of questions. Anything that cannot answer answers "no shaders". */
    private static void askIris() {
        try {
            Object api = getInstance.invoke(null);
            Object inUse = isShaderPackInUse.invoke(api);
            packInUse = inUse instanceof Boolean selected && selected;
            if (irisConfig == null) {
                irisConfig = getConfig.invoke(api);
            }
            Object enabled = irisConfig == null ? null : areShadersEnabled.invoke(irisConfig);
            shadersEnabled = enabled instanceof Boolean on && on;
        } catch (Throwable broken) {
            // A pack that cannot answer is treated as "no shaders": the overlay's own arrangement always
            // works.
            packInUse = false;
            shadersEnabled = false;
        }
    }

    /**
     * Writes, once and then only when it changes, which arrangement the pass is running in.
     *
     * <p>This is the line that answers "why does it look like this" from a log without a second run: it
     * names both inputs, so "I installed Iris" can be told apart from "Iris is drawing", and a pack
     * switched on or off in game produces a new line because the answer really changed.
     */
    private static void reportPath() {
        boolean on = shaderPath();
        if (reportedPath != null && reportedPath == on) {
            return;
        }
        reportedPath = on;
        RCConfig.LOGGER.info("[redstonecircuit] shader path: {} (pack in use={}, shaders enabled={})",
                on ? "on" : "off", packInUse, shadersEnabled);
    }

    /**
     * Whether a shader pack's own lighting is worth compensating for.
     *
     * <p>No longer a switch between two ways of drawing. The overlay has one path now: the host block
     * always leaves the chunk mesh and everything it draws uses vanilla render types taken by
     * reference. What is left to ask is whether to lift the light floor, because the block's own faces are
     * drawn by hand rather than by the pack's terrain pipeline, and the packs that lean hardest on shadows
     * and ambient occlusion darken geometry drawn by hand.
     *
     * <p>Worth knowing when reading a log: the answer is "a pack is selected <em>and</em> switched on",
     * because {@code areShadersEnabled} is asked as well - a pack chosen with
     * {@code enableShaders=false} in {@code iris.properties} is not drawing and must not brighten
     * anything. Nothing about the drawing depends on the answer, which is deliberate: an answer that reads
     * true with the shaders off is not a thing to branch a renderer on.
     */
    public static boolean vanillaGeometry() {
        return shaderPackInUse();
    }

    /** Whether the chunk mesher should skip a block at this position. Called from mesher threads. */
    public static boolean hidesHostCube(BlockPos pos) {
        return hidingHostCubes && hidden.contains(pos.asLong());
    }

    /** Hook names that have already been reported, so a diagnosis does not flood the log. */
    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();

    /** Hook names whose injection point has been entered at least once, for the entry diagnostic. */
    private static final Set<String> ENTERED = ConcurrentHashMap.newKeySet();

    /** Set once the "no hook ran at all" warning has been written. */
    private static final Set<String> MISSING_REPORTED = ConcurrentHashMap.newKeySet();

    /** Set once the "the other coordinate reading matched" line has been written. */
    private static final Set<String> LOCAL_READING_REPORTED = ConcurrentHashMap.newKeySet();

    /**
     * Set once Sodium's own mesher has been seen running, by the probe that cannot fail to resolve.
     *
     * <p>This is what separates the two causes of "the Sodium hook never ran". If the probe has fired,
     * Sodium is definitely the renderer building these sections, so the fault can only be the hook's
     * injection point - and the warning below says so instead of sending the reader back to the mixin
     * configs. If it has not fired, the render path itself is the question.
     */
    private static volatile boolean sodiumMesherSeen;

    /**
     * Records that a mesh hook's injected method body was entered, whatever it then decided.
     *
     * <p><b>This is the one line that separates the two faults that look identical in game.</b> The
     * overlay draws the shell and the components itself, so "nothing is visible" happens either
     * because the mesh hook was never applied (the block is still in the chunk mesh, its own cube
     * drawn over everything inside it) or because it was applied and the position it was handed never
     * matched a host. The first fault leaves no trace at all in the log; the second leaves this line
     * and no "is hiding host blocks" line after it. Written unconditionally, once per hook, from the
     * very first statement of the injected body, so it also answers "is the mesher even reaching this
     * call" on a machine where no host has been published yet.
     *
     * @param hook which mixin ran - {@code "vanilla"} or {@code "sodium"}
     */
    public static void noteHookEntered(String hook, Object pos) {
        if (ENTERED.add(hook)) {
            RCConfig.LOGGER.info(
                    "[redstonecircuit] mesh hook '{}' entered at {} (the injection point exists and "
                            + "was reached; whether the block is then skipped is a separate line)",
                    hook, pos);
        }
    }

    /**
     * The Sodium hook's entry line, with the coordinates that decide the "entered but never matched"
     * case.
     *
     * <p>It has its own method rather than reusing {@link #noteHookEntered} because Sodium hands over
     * <em>two</em> positions and their meaning is the whole question: read out of {@code
     * ChunkBuilderMeshingTask#execute} of {@code sodium-fabric 0.8.13+mc1.21.1}, {@code pos} is
     * section-local ({@code world & 15}) and {@code origin} is that section's world corner, so the
     * block being baked is {@code origin + pos}. Printing all three lets this line be compared, by eye,
     * with the "drawing N host block(s) ourselves, out of the mesh: ..." line that published the set -
     * which is exactly the comparison that says whether a missing reveal is a position problem or an
     * injection problem.
     */
    public static void noteSodiumHookEntered(BlockPos pos, BlockPos origin) {
        if (ENTERED.add("sodium")) {
            RCConfig.LOGGER.info(
                    "[redstonecircuit] mesh hook 'sodium' entered: pos={} (section-local), origin={} "
                            + "(that section's world origin), so the block being baked is origin+pos={} "
                            + "- compare it with the positions listed by 'drawing N host block(s)'",
                    xyz(pos), xyz(origin), xyz(origin.offset(pos)));
        }
    }

    /**
     * Says once that the <em>other</em> reading of Sodium's two positions is the one that matched.
     *
     * <p>The section-local reading is not expected - the bytecode says the world position is {@code
     * origin + pos} - but a future Sodium could swap which argument is which, and this hook accepts both
     * so that such a change costs a log line rather than the feature. This line firing is that log
     * line, and it is worth acting on: it means the coordinates are not what this build was written
     * against.
     */
    public static void noteSodiumLocalReading(BlockPos pos, BlockPos origin) {
        if (LOCAL_READING_REPORTED.add("sodium-local")) {
            RCConfig.LOGGER.info(
                    "[redstonecircuit] mesh hook 'sodium' matched on the section-local reading "
                            + "(pos={}, origin={}): the block was skipped, but this is not the coordinate "
                            + "space this build was written against - the world position is expected to "
                            + "be origin+pos = {}",
                    xyz(pos), xyz(origin), xyz(origin.offset(pos)));
        }
    }

    /**
     * Says once that Sodium's own chunk mesher has been seen, from the probe that carries no Minecraft
     * type in its method spec and therefore cannot fail to resolve.
     *
     * <p>Its only job is to make the warning below - or the absence of it - unambiguous: with this line
     * present, "the Sodium hook never ran" can only mean the hook's injection point, and the reader does
     * not have to wonder whether Sodium was building these meshes at all.
     */
    public static void noteSodiumMesher() {
        if (!sodiumMesherSeen) {
            sodiumMesherSeen = true;
            RCConfig.LOGGER.info(
                    "[redstonecircuit] Sodium's chunk meshing pipeline is running (the probe on "
                            + "BlockRenderCache#getBlockRenderer was entered): the per-block bake this mod "
                            + "hooks, BlockRenderer#renderModel, is what builds these sections");
        }
    }

    /**
     * Records that a mesh hook recognised one of our hosts and cancelled the block.
     *
     * <p>Logged once per hook, and it is the positive half of the pair above: entry without this line
     * means the hook ran but {@link #hidesHostCube} never matched, which points at the published set
     * (positions, coordinate space, or the section never being meshed again) rather than at Mixin.
     */
    public static void noteHookUsed(String hook) {
        if (REPORTED.add(hook)) {
            RCConfig.LOGGER.info("[redstonecircuit] mesh hook '{}' is hiding host blocks", hook);
        }
    }

    /**
     * Says, once, that no mesh hook has been entered although host blocks have been published.
     *
     * <p>Called after {@link #HOOK_GRACE_FRAMES} frames of publication, which is far longer than the
     * mesher needs on any machine: by then either a hook has run, or none will. This turns a silent
     * failure into a line that names the likely cause instead of leaving the author to guess.
     */
    public static void noteHookApplied() {
        if (!ENTERED.isEmpty() || !MISSING_REPORTED.add("report")) {
            return;
        }
        if (sodiumMesherSeen) {
            // The probe fired, so Sodium really is meshing these sections and the vanilla hook is
            // expected to be silent. That leaves exactly one candidate, and naming it saves the reader
            // from re-checking the mixin configs that are demonstrably fine.
            RCConfig.LOGGER.warn(
                    "[redstonecircuit] {} host block(s) have been published for several frames but no "
                            + "mesh hook has been entered, although Sodium's mesher is running (see the "
                            + "'chunk meshing pipeline is running' line above). The Sodium hook's "
                            + "injection point is therefore the fault: its method spec did not resolve "
                            + "against BlockRenderer, which is what a missing or stale refmap looks "
                            + "like. Fix it where it is checkable - the build - not on this machine.",
                    hidden.size());
            return;
        }
        RCConfig.LOGGER.warn(
                "[redstonecircuit] {} host block(s) have been published for several frames but no mesh "
                        + "hook has been entered: neither the vanilla nor the Sodium hook is applied, so "
                        + "those blocks are still in the chunk mesh and their own cube hides what is "
                        + "inside them. Check the mixin configs (redstonecircuit.mixins.json and "
                        + "redstonecircuit.sodium.mixins.json) and any 'Mixin apply failed' line above.",
                hidden.size());
    }

    /**
     * Publishes the set of hosts the overlay draws itself, and marks the sections whose contents
     * changed so the game rebuilds exactly those meshes - and no others.
     *
     * @return true when the set changed, in which case the caller's own drawing is out of date too
     */
    public static boolean publishHosts(List<BlockPos> hosts, boolean hiding) {
        Set<Long> next = new HashSet<>(hosts.size() * 2);
        for (BlockPos pos : hosts) {
            next.add(pos.asLong());
        }
        Set<Long> previous = hidden;
        boolean hidingChanged = hiding != hidingHostCubes;
        if (!hidingChanged && next.equals(previous)) {
            return false;
        }
        if (hiding && !hidingHostCubes) {
            if (RCConfig.LOGGER.isInfoEnabled()) {
                RCConfig.LOGGER.info(
                        "[redstonecircuit] drawing {} host block(s) ourselves, out of the mesh: {}",
                        next.size(), positions(next));
            }
        } else if (!hiding && hidingHostCubes) {
            RCConfig.LOGGER.info("[redstonecircuit] host blocks are back in the mesh");
        }
        hidden = Set.copyOf(next);
        hidingHostCubes = hiding;

        // The one diagnostic for "the hooks never ran": a few frames after the first publication, with
        // hosts still published, no injected body has been entered at all. Checked here rather than on
        // a timer because publication is the event the hooks are supposed to answer.
        if (hiding) {
            if (++publishedFrames >= HOOK_GRACE_FRAMES) {
                noteHookApplied();
            }
        } else {
            publishedFrames = 0;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.levelRenderer == null) {
            return true;
        }
        for (long packed : next) {
            if (!previous.contains(packed)) {
                markSectionDirty(minecraft, BlockPos.of(packed));
            }
        }
        for (long packed : previous) {
            if (!next.contains(packed)) {
                markSectionDirty(minecraft, BlockPos.of(packed));
            }
        }
        return true;
    }

    /** The packed positions of a published set, spelled out so a log line can be checked by eye. */
    private static String positions(Set<Long> packed) {
        StringBuilder text = new StringBuilder();
        for (long value : packed) {
            BlockPos pos = BlockPos.of(value);
            if (text.length() > 0) {
                text.append(", ");
            }
            text.append(pos.getX()).append(',').append(pos.getY()).append(',').append(pos.getZ());
        }
        return text.toString();
    }

    /** One position in the same spelling {@link #positions} uses, so two lines can be compared. */
    private static String xyz(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    /** Rebuilds every mesh, for the one event that changes every block at once: the goggles going on. */
    public static void rebuildEverything() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.levelRenderer != null) {
            minecraft.levelRenderer.allChanged();
        }
    }

    private static void markSectionDirty(Minecraft minecraft, BlockPos pos) {
        minecraft.levelRenderer.setSectionDirty(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4);
    }
}

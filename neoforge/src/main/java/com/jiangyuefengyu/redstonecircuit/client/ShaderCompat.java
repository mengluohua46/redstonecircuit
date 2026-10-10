package com.jiangyuefengyu.redstonecircuit.client;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.jiangyuefengyu.redstonecircuit.RCConfig;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Whether a shader pack is drawing the world, and what that changes about the overlay.
 *
 * <p>Without shaders the components inside a block are drawn by a render type of our own that ignores the depth test,
 * because the block's own opaque cube was drawn first. A shader pack picks the program - and the extra vertex
 * attributes its shaders expect - from the <em>identity</em> of the render type, so a type of ours is a stranger to it,
 * and one that ignores depth has no surface for a deferred lighting pass to light.
 *
 * <p>With shaders on, the overlay therefore switches to vanilla render types, which means the block's own cube has to
 * go: the block is skipped while its section is being meshed (see {@code BlockRenderDispatcherMixin}) and the pass
 * draws the block itself - both sides of it, so nothing behind shows through - plus the components, all with render
 * types a pack knows by name.
 *
 * <p>Iris is reached reflectively ({@code net.irisshaders.iris.api.v0.IrisApi}, which Oculus also implements), so no
 * shader mod is a hard dependency: when neither is installed the lookup fails and the overlay stays on its own render
 * types. One question is not enough - see {@link #shaderPackInUse()}.
 */
@OnlyIn(Dist.CLIENT)
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
    /** Iris's own answer: a pack is <em>selected</em>. Not on its own a reason to change the renderer. */
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

    private ShaderCompat() {
    }

    /**
     * True while a shader pack is actually drawing the world. Cheap: the answer is cached for a second.
     *
     * <p><b>Two questions, not one.</b> Iris's {@code isShaderPackInUse()} answers "a pack is <em>selected</em>", which
     * is not the same as "those shaders are running" and is not a thing to branch a renderer on: it stays true while
     * {@code enableShaders} is false. The second question is part of the same public API
     * ({@code IrisApi#getConfig()}, whose {@code areShadersEnabled()} is that flag), and the pack is drawing only when
     * both answers are yes.
     *
     * <p>Still pure reflection, still one lookup, still one answer per second. If Iris is absent, or its API cannot
     * answer either question - an older build without {@code getConfig}, a lookup that fails, a call that throws - the
     * answer is "no shaders": the safe half, because the overlay's own render types always work.
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
            // A pack that cannot answer is treated as "no shaders": our own render types still work.
            packInUse = false;
            shadersEnabled = false;
        }
    }

    /**
     * Writes, once and then only when it changes, which of the two render arrangements is in use.
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

    /** Whether the overlay should use vanilla render types and hide the host blocks' own cubes. */
    public static boolean vanillaGeometry() {
        return shaderPackInUse();
    }

    /** Whether the chunk mesher should skip a block at this position. Called from mesher threads. */
    public static boolean hidesHostCube(BlockPos pos) {
        return hidingHostCubes && hidden.contains(pos.asLong());
    }

    /** Hook names that have already been reported, so a diagnosis does not flood the log. */
    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();

    /**
     * Records that a mesh hook really ran and recognised one of our hosts.
     *
     * <p>Logged once per hook. "Nothing happens in game" has two very different causes - the overlay
     * never switched to vanilla geometry, or it did and the mesher is not listening - and this is what
     * tells them apart from a log without guessing.
     */
    public static void noteHookUsed(String hook) {
        if (REPORTED.add(hook)) {
            RCConfig.LOGGER.info("[redstonecircuit] shader-mode mesh hook '{}' is hiding host blocks",
                    hook);
        }
    }

    /**
     * Publishes the set of hosts the overlay draws itself, and marks the sections whose contents changed so the game
     * rebuilds exactly those meshes - and no others. Returns true when the set changed, in which case the caller's own
     * drawing is out of date too.
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
            RCConfig.LOGGER.info(
                    "[redstonecircuit] shader mode: drawing {} host blocks ourselves, out of the mesh",
                    next.size());
        } else if (!hiding && hidingHostCubes) {
            RCConfig.LOGGER.info("[redstonecircuit] shader mode off: host blocks are back in the mesh");
        }
        hidden = Set.copyOf(next);
        hidingHostCubes = hiding;
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

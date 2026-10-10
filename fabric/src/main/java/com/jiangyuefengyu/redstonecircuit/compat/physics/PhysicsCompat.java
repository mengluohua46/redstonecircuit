package com.jiangyuefengyu.redstonecircuit.compat.physics;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.jiangyuefengyu.redstonecircuit.RCConfig;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

import org.jetbrains.annotations.Nullable;

/**
 * The single gate between this mod and the optional physics-structure mods (Sable, Create, Create
 * Aeronautics).
 *
 * <p>This is the only member of the compat layer the rest of the mod may name, and the only one that runs
 * when none of those mods is installed: everything that names a Sable or Create type lives in a separate
 * Gradle source set that is left out of the build entirely when the nested jars it compiles against are
 * missing, so the mod builds on a machine that has never seen those mods.
 *
 * <p>The two halves never reference each other at compile time: this one reaches the other by name through
 * {@link #backend()}. {@code Class.forName} resolves a class <em>and its supertypes</em>, which is why the
 * backend implements no Sable or Create interface - with the mods absent it loads, checks the mod list and
 * does nothing, so {@code NoClassDefFoundError} cannot happen. Nothing here registers a listener, a mixin,
 * a network channel or a data structure.
 */
public final class PhysicsCompat {

    /** Mod id of the Sable sub-level library ({@code dev.ryanhcode.sable}). */
    public static final String MOD_SABLE = "sable";

    /** Mod id of Create ({@code com.simibubi.create}). */
    public static final String MOD_CREATE = "create";

    /** Mod id of the Create Aeronautics <em>bundled</em> wrapper jar. */
    public static final String MOD_AERONAUTICS_BUNDLED = "aeronautics_bundled";

    /** Mod id of the real Create Aeronautics code inside that wrapper. */
    public static final String MOD_AERONAUTICS = "aeronautics";

    /** Mod id of Create Simulated, also inside the wrapper. */
    public static final String MOD_SIMULATED = "simulated";

    /** Fully qualified name of the compat half; kept as a string so no compile-time link exists. */
    private static final String BACKEND = "com.jiangyuefengyu.redstonecircuit.compat.physics.PhysicsCompatBackend";

    private static boolean initialised;
    private static boolean backendLookupDone;
    @Nullable
    private static Class<?> backendClass;

    private PhysicsCompat() {
    }

    // ---------------------------------------------------------------- public --

    /**
     * Wires up whatever optional compatibility is available; called once, from the mod constructor, and safe
     * before the mod list is populated.
     */
    public static void init() {
        if (initialised) {
            return;
        }
        initialised = true;

        Class<?> backend = backend();
        if (backend == null) {
            // Either none of the optional mods is installed, or this jar was built without the optional
            // compat source set. Both are ordinary, supported states.
            return;
        }
        invoke(backend, "init");
    }

    /**
     * The one compat registration that needs the block registry, so it is asked for once every registry is
     * full and this mod's blocks exist.
     *
     * <p>Gated exactly like {@link #hostBlockStored}: a no-op with no physics mod installed, and it never
     * loads a Create type on a server that has none.
     */
    public static void installProductCarrier() {
        if (backendClass == null || !createLoaded()) {
            return;
        }
        invoke(backendClass, "installProductCarrier");
    }

    /**
     * Runs the tick of every placed structure a level does not tick, once per level tick and after the
     * level's own block-entity ticks.
     *
     * <p>A Sable sub-level's blocks live in an embedded plot the level does not tick, so nothing else would
     * ever read a physicalised structure's faces again. Gated like every other entry point: no backend class
     * or no Sable answers without loading a single Sable type.
     */
    public static void tickCarriedProducts(ServerLevel level, ServerLevel pocket) {
        if (backendClass == null || !sableLoaded() || level == null || pocket == null) {
            return;
        }
        invoke(backendClass, "tickCarriedProducts",
                new Class<?>[] {ServerLevel.class, ServerLevel.class}, level, pocket);
    }

    /**
     * What a carrier's drawn block answers a signal query with, at a world position.
     *
     * <p>A sub-level draws blocks where its pose puts them while the blocks themselves stand in a reserved
     * plot, so the world position the player sees holds air and answers nothing unless the block drawn there
     * is asked. Turning a world position back into a sub-level's own coordinates is a Sable question, which
     * is why it lives behind this entry point; a world without Sable answers 0 without loading a Sable type.
     *
     * <p>{@code worldDirection} is vanilla's direction: the way the query travelled, from the asker towards
     * the queried position.
     */
    public static int carriedSignalAt(ServerLevel level, BlockPos pos,
                                      net.minecraft.core.Direction worldDirection, boolean strong) {
        if (backendClass == null || !sableLoaded() || level == null || pos == null || worldDirection == null) {
            return 0;
        }
        Object answer = invoke(backendClass, "carriedSignalAt",
                new Class<?>[] {ServerLevel.class, BlockPos.class, net.minecraft.core.Direction.class, boolean.class},
                level, pos, worldDirection, strong);
        return answer instanceof Integer value ? value : 0;
    }

    /**
     * What a carrier's drawn block answers a comparator with, at a world position: the twin of
     * {@link #carriedSignalAt} for the analogue output, which is what a comparator's back input is made of
     * and the only thing a steering angle can travel in. The same reserved-plot reasoning applies, so it is
     * gated the same way: no backend class or no Sable answers 0 without loading a Sable type.
     *
     * <p>The direction travels with the question: the compat walk turns the asker's world direction into the
     * sub-level's own, because a directional answer is the one a steering wheel gives.
     */
    public static int carriedAnalogAt(ServerLevel level, BlockPos pos,
                                      net.minecraft.core.Direction worldDirection) {
        if (backendClass == null || !sableLoaded() || level == null || pos == null) {
            return 0;
        }
        Object answer = invoke(backendClass, "carriedAnalogAt",
                new Class<?>[] {ServerLevel.class, BlockPos.class, net.minecraft.core.Direction.class},
                level, pos, worldDirection);
        return answer instanceof Integer value ? value : 0;
    }

    /**
     * Tells the compat half that the block type at {@code pos} now holds an inner component, so it can
     * attach its diagnostic hook to that block type; does nothing at all unless the backend exists and
     * Sable or Create is installed.
     */
    /**
     * Places a single-block physics structure where the machine's own carrier frame draws the cell in front
     * of it, through the same vanilla placement the world path uses, so the two halves of the machine cannot
     * disagree about what placing a block means.
     *
     * <p>False is the ordinary answer on a machine with no physics installed - or no Sable, or no container
     * in this level - and it means nothing was placed: the item is not spent.
     */
    public static boolean placeSingleBlockStructure(ServerLevel level, BlockPos drawnTarget, Direction out,
                                                    ItemStack stack) {
        if (backendClass == null || !sableLoaded() || level == null || drawnTarget == null || out == null
                || stack == null || stack.isEmpty()) {
            return false;
        }
        Object answer = invoke(backendClass, "placeSingleBlockStructure",
                new Class<?>[] {ServerLevel.class, BlockPos.class, Direction.class, ItemStack.class},
                level, drawnTarget, out, stack);
        return answer instanceof Boolean placed && placed;
    }

    /**
     * Drops one chunk's saved sub-level record: the repair for a structure Sable filed but cannot load. The
     * record goes from disk <b>and</b> from Sable's in-memory copy, because Sable writes that copy back out at
     * the next save; no live structure, plot or world block is touched.
     *
     * <p>Check first that nothing still wanted is filed under that chunk. {@code null} means the backend or
     * Sable is missing.
     */
    @Nullable
    public static String purgeHoldingChunk(ServerLevel level, int chunkX, int chunkZ) {
        if (backendClass == null || !sableLoaded() || level == null) {
            return null;
        }
        Object answer = invoke(backendClass, "purgeHoldingChunk",
                new Class<?>[] {ServerLevel.class, int.class, int.class}, level, chunkX, chunkZ);
        return answer instanceof String text ? text : null;
    }

    public static void hostBlockStored(ServerLevel level, BlockPos pos, Block block) {
        if (backendClass == null || !(sableLoaded() || createLoaded())) {
            return;
        }
        invoke(backendClass, "hostBlockStored", new Class<?>[] {ServerLevel.class, BlockPos.class, Block.class},
                level, pos, block);
    }

    /**
     * The pose Sable is drawing a sub-level at in this frame, or {@code null} when the carrier is not an
     * answerable Sable sub-level - the caller then keeps the pose the network gave it.
     *
     * <p>Gated like the rest of this class, so a missing mod can never throw {@code NoClassDefFoundError}.
     */
    @Nullable
    public static com.jiangyuefengyu.redstonecircuit.carrier.CarrierPose renderPoseOf(
            Level level, com.jiangyuefengyu.redstonecircuit.carrier.CarrierId carrier,
            float partialTick) {
        if (backendClass == null || !sableLoaded() || level == null || carrier == null
                || !carrier.isSubLevel()) {
            return null;
        }
        Object result = invoke(backendClass, "renderPoseOf",
                new Class<?>[] {Level.class, com.jiangyuefengyu.redstonecircuit.carrier.CarrierId.class,
                        float.class},
                level, carrier, partialTick);
        return result instanceof com.jiangyuefengyu.redstonecircuit.carrier.CarrierPose pose ? pose : null;
    }

    /**
     * Every gated call that could not be made, counted and warned about <em>once per name</em>: this path
     * runs per carried host per frame, so a warning per call would be a warning per frame.
     */
    private static final Set<String> REPORTED_BACKEND_FAILURES = ConcurrentHashMap.newKeySet();

    /** How many gated calls have failed in total. */
    private static final AtomicInteger BACKEND_FAILURES = new AtomicInteger();

    /** Successful reflective lookups, one per name: this path runs per host per frame. */
    private static final Map<String, Method> BACKEND_METHODS = new ConcurrentHashMap<>();

    /** How many gated backend calls have failed, for the once-per-name warning above. */
    public static int backendFailureCount() {
        return BACKEND_FAILURES.get();
    }

    /**
     * {@code /rc physics}: everything the decision needs about one world position; empty when there is
     * nothing to report here, which is no mods or a jar built without the compat half.
     */
    public static List<String> physicsReport(ServerLevel level, BlockPos pos) {
        if (backendClass == null) {
            return List.of();
        }
        Object result = invoke(backendClass, "physicsReport", new Class<?>[] {ServerLevel.class, BlockPos.class},
                level, pos);
        if (result instanceof List<?> lines) {
            return lines.stream().map(String::valueOf).toList();
        }
        return List.of();
    }

    /**
     * {@code /rc carrier}: what every moving structure is carrying, and where it is - the same information
     * {@link #physicsReport} prints, minus the parts that need a block to look at, so it can be run anywhere.
     */
    public static List<String> carrierReport(ServerLevel level) {
        if (backendClass == null) {
            return List.of();
        }
        Object result = invoke(backendClass, "carrierReport", new Class<?>[] {ServerLevel.class}, level);
        if (result instanceof List<?> lines) {
            return lines.stream().map(String::valueOf).toList();
        }
        return List.of();
    }

    /**
     * One line about a carrier that is still filed under a provisional identity: which structure the fallback
     * id belongs to - anchor, block count, whether an entity has appeared - which is what an operator needs to
     * match a provisional claim against the machine in front of them.
     */
    public static String provisionalDescription(com.jiangyuefengyu.redstonecircuit.carrier.CarrierId carrier) {
        if (backendClass == null) {
            return "(compat half not built into this jar)";
        }
        Object result = invoke(backendClass, "provisionalDescription",
                new Class<?>[] {com.jiangyuefengyu.redstonecircuit.carrier.CarrierId.class}, carrier);
        return result instanceof String text ? text : "(unknown provisional identity)";
    }

    /** True when Sable is installed. */
    public static boolean sableLoaded() {
        return loaded(MOD_SABLE);
    }

    /** True when Create is installed. */
    public static boolean createLoaded() {
        return loaded(MOD_CREATE);
    }

    /** True when the bundled Aeronautics jar is present, under either of the ids it can use. */
    public static boolean aeronauticsLoaded() {
        return loaded(MOD_AERONAUTICS_BUNDLED) || loaded(MOD_AERONAUTICS) || loaded(MOD_SIMULATED);
    }

    /** True when the optional compat half was compiled into this jar and can be reached. */
    public static boolean backendAvailable() {
        return backend() != null;
    }

    /** True when the compat gate is doing something, i.e. at least one of the mods is installed. */
    public static boolean active() {
        return backendAvailable() && (sableLoaded() || createLoaded());
    }

    /** One line describing the gate's state, for logs and for {@code /rc physics}. */
    public static String describe() {
        String mods = "sable=" + sableLoaded() + ", create=" + createLoaded()
                + ", aeronautics=" + aeronauticsLoaded();
        if (!backendAvailable()) {
            return "physics-compat=not-built-into-this-jar (" + mods + ")";
        }
        return "physics-compat=" + (active() ? "on" : "off") + " (" + mods + ")";
    }

    /** The friendly answer {@code /rc physics} gives when there is nothing to report. */
    public static String unavailableReason() {
        if (!backendAvailable()) {
            return "physics compatibility is not built into this jar (no Sable/Create backend was "
                    + "available when it was built) - the mod's own features do not depend on it";
        }
        return "neither Sable nor Create is installed - " + describe();
    }

    /**
     * Mod-list lookup that tolerates being asked before there is a game at all: a unit-test JVM has no
     * loader, and "not installed" is the honest answer there.
     */
    public static boolean loaded(String modId) {
        try {
            return net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded(modId);
        } catch (Throwable noLoader) {
            return false;
        }
    }

    // --------------------------------------------------------------- helpers --

    /** The compat half, or {@code null} when it is not in this jar. Resolved at most once. */
    @Nullable
    private static Class<?> backend() {
        if (!backendLookupDone) {
            backendLookupDone = true;
            if (sableLoaded() || createLoaded()) {
                try {
                    // Only the compat half's static initialiser runs here, and it checks the mod list too.
                    backendClass = Class.forName(BACKEND, true, PhysicsCompat.class.getClassLoader());
                } catch (ClassNotFoundException | LinkageError absentOrUnusable) {
                    RCConfig.LOGGER.info("[redstonecircuit] physics compat: {} is not available ({})",
                            BACKEND, absentOrUnusable.toString());
                    backendClass = null;
                }
            } else {
                RCConfig.LOGGER.info("[redstonecircuit] physics compat: {} (no optional physics mod installed)",
                        describe());
            }
        }
        return backendClass;
    }

    /**
     * Calls a static method on the compat half. A failure here is a bug in this mod rather than a missing
     * dependency - the backend was found and a mod is installed - so it is logged rather than swallowed; but
     * it must still never propagate, because a diagnostic must not take the server down.
     */
    @Nullable
    private static Object invoke(Class<?> owner, String name, Class<?>[] parameterTypes, Object... args) {
        try {
            Method method = BACKEND_METHODS.get(name);
            if (method == null) {
                method = owner.getMethod(name, parameterTypes);
                BACKEND_METHODS.put(name, method);
            }
            return method.invoke(null, args);
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException
                 | RuntimeException | LinkageError failed) {
            BACKEND_FAILURES.incrementAndGet();
            if (REPORTED_BACKEND_FAILURES.add(name)) {
                RCConfig.LOGGER.warn("[redstonecircuit] physics compat: {}.{} failed ({}); further failures "
                        + "of this call are counted rather than logged - see backendFailureCount()",
                        BACKEND, name, failed);
            }
            return null;
        }
    }

    /** Convenience for the no-argument calls. */
    @Nullable
    private static Object invoke(Class<?> owner, String name) {
        return invoke(owner, name, new Class<?>[0]);
    }
}

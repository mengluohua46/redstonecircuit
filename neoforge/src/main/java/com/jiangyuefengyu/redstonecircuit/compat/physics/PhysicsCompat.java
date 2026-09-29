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
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

import org.jetbrains.annotations.Nullable;

/**
 * The single gate between this mod and the optional physics-structure mods (Sable, Create,
 * Create Aeronautics).
 *
 * <p>This class is the <em>only</em> member of the compat layer the rest of the mod may name, and
 * the only one that ever runs when none of those mods is installed. Everything that names a Sable or
 * Create type lives in {@code src/compat/java}, a separate Gradle source set that is left out of the
 * build entirely when the two nested jars it compiles against are not available - so the mod builds
 * on a machine that has never seen those mods.
 *
 * <h2>How the two halves meet</h2>
 * There is no compile-time reference in either direction across the two halves except this class
 * being named by {@code RedstoneCircuit} and by {@code RCCommand}:
 * <ul>
 *   <li>{@code compat} depends on {@code main} (its classpath is main's output plus the optional mod
 *       jars), so it may call anything here;</li>
 *   <li>{@code main} never links against {@code compat}. It reaches it by name, through
 *       {@link #backend()}, and only after a real mod-list check has passed.</li>
 * </ul>
 * That is what keeps the promise from P0: <b>with none of the optional mods installed, no class of
 * theirs is ever loaded</b>, so {@code NoClassDefFoundError} cannot happen, and behaviour is
 * identical to a build without any of this code.
 *
 * <h2>Why a reflection call rather than a direct one</h2>
 * {@code Class.forName("...PhysicsCompatBackend")} resolves the class <em>and its supertypes</em>.
 * The backend implements no Sable or Create interface - that is the P0 rule this code still obeys -
 * so on a machine without those mods it loads, runs its mod-list check, and does nothing. If the
 * source set was not compiled at all, the lookup simply fails and
 * {@link #backendAvailable()} stays false, which is
 * {@link #describe()}'s "not built into this jar" answer.
 *
 * <h2>What this class does not do</h2>
 * <p>P0's promise is still intact: nothing here registers an event listener, a mixin, a network
 * channel or a data structure. The only things the compat half registers are diagnostics,
 * and every one of them is a log line.
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

    /** Fully qualified name of the compat half. Kept as a string on purpose - see the class doc. */
    private static final String BACKEND = "com.jiangyuefengyu.redstonecircuit.compat.physics.PhysicsCompatBackend";

    private static boolean initialised;
    private static boolean backendLookupDone;
    @Nullable
    private static Class<?> backendClass;

    private PhysicsCompat() {
    }

    // ---------------------------------------------------------------- public --

    /**
     * Wires up whatever optional compatibility is available. Called once, from the mod constructor.
     *
     * <p>Safe to call before the mod list is populated: an unavailable mod list is treated as
     * "nothing is installed".
     */
    public static void init() {
        if (initialised) {
            return;
        }
        initialised = true;

        Class<?> backend = backend();
        if (backend == null) {
            // Either none of the optional mods is installed, or this jar was built without the
            // optional compat source set. Both are ordinary, supported states.
            return;
        }
        invoke(backend, "init");
    }

    /**
     * Tells the compat half that the block type at {@code pos} now holds an inner component, so it
     * can attach its diagnostic hook to that block type.
     *
     * <p>Called from the placement paths. Does nothing at all - not even a class lookup - unless the
     * backend exists and Sable or Create is installed, so it is free when the mods are absent.
     */
    public static void hostBlockStored(ServerLevel level, BlockPos pos, Block block) {
        if (backendClass == null || !(sableLoaded() || createLoaded())) {
            return;
        }
        invoke(backendClass, "hostBlockStored", new Class<?>[] {ServerLevel.class, BlockPos.class, Block.class},
                level, pos, block);
    }

    /**
     * <b>P1.56: the pose Sable is drawing a sub-level at, in this frame - or {@code null}.</b>
     *
     * <p>Asked by the carried render pass, once per carrier per frame. The compat half answers it from
     * Sable's own client sub-level ({@code ClientSubLevel#renderPose(float)}), which is the pose Sable
     * itself draws the structure with; {@code null} means "not a Sable sub-level, or not answerable",
     * and the caller then keeps the pose it already had from the network.
     *
     * <p>Gated exactly like the rest of this class: no backend class, no Sable, or a carrier that is not
     * a sub-level answers {@code null} without loading a single Sable type - a missing mod must never be
     * able to throw {@code NoClassDefFoundError}.
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
     * <b>Every gated call that could not be made, counted - and warned about <em>once per name</em>.</b>
     *
     * <p>P1.57. The first version of the render-pose round added its query to the compat half but not to
     * the backend class this file reflects on, and the author's log grew to 21 MB: {@code invoke} warned
     * <b>per call</b>, and this call is made once per carried host per frame - 3625 warnings on the render
     * thread, while the render silently went on using the placeholder. Both halves of that are defects:
     * the missing entry point, and a warning whose rate is the frame rate.
     *
     * <p>So a failure is now reported once by name and only counted afterwards, and the count is readable
     * ({@link #backendFailureCount()}) - which is the same shape {@code CarrierWorldIo#unfilteredReads}
     * uses for the other "cannot do it, must not be silent" case in this mod.
     */
    private static final Set<String> REPORTED_BACKEND_FAILURES = ConcurrentHashMap.newKeySet();

    /** How many gated calls have failed in total; read by the test, and by nothing else. */
    private static final AtomicInteger BACKEND_FAILURES = new AtomicInteger();

    /** Successful reflective lookups, one per name: this path runs per host per frame. */
    private static final Map<String, Method> BACKEND_METHODS = new ConcurrentHashMap<>();

    /** How many gated backend calls have failed; read by the test, and by nothing else. */
    public static int backendFailureCount() {
        return BACKEND_FAILURES.get();
    }

    /**
     * {@code /rc physics}: everything the P1 decision needs about one world position.
     *
     * @return lines to print, or an empty list when there is nothing to report here (no mods, or a
     *     jar built without the compat half; {@link #describe()} says which).
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
     * {@code /rc carrier}: what every moving structure is carrying, and where it is.
     *
     * <p>The same information {@link #physicsReport} prints, minus the parts that need a block to look
     * at - so it can be run at any time, including while standing nowhere in particular.
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
     * One line about a carrier that is still filed under a provisional identity.
     *
     * <p>Part of the two-phase identity's diagnostics: it says which structure the fallback id belongs
     * to - anchor, block count, and whether an entity has appeared - which is what an operator needs to
     * match a provisional claim in {@code /rc carrier} against the machine in front of them. Answered
     * by the compat half, which is the only place that can look a contraption up.
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
            return "physics compatibility is not built into this jar (the nested Sable/Create jars "
                    + "were not found at build time) - see neoforge/libs/README.md";
        }
        return "neither Sable nor Create is installed - " + describe();
    }

    /**
     * Mod-list lookup that tolerates being called before the list exists.
     *
     * <p>The class is named in the signature so callers do not have to import FML; the actual check
     * is a plain {@code ModList} query.
     */
    public static boolean loaded(String modId) {
        net.neoforged.fml.ModList mods = net.neoforged.fml.ModList.get();
        return mods != null && mods.isLoaded(modId);
    }

    // --------------------------------------------------------------- helpers --

    /** The compat half, or {@code null} when it is not in this jar. Resolved at most once. */
    @Nullable
    private static Class<?> backend() {
        if (!backendLookupDone) {
            backendLookupDone = true;
            if (sableLoaded() || createLoaded()) {
                try {
                    // Only the constructor/static initialiser of the compat half runs here; it too
                    // checks the mod list before it touches anything mod-specific.
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
     * Calls a static method on the compat half.
     *
     * <p>A failure here is a bug in this mod, not a missing dependency - the backend was found and a
     * mod is installed - so it is logged rather than swallowed silently. It must still never
     * propagate: a diagnostic must not be able to take the server down.
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

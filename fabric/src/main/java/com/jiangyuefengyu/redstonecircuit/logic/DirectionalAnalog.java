package com.jiangyuefengyu.redstonecircuit.logic;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.jetbrains.annotations.Nullable;

import com.jiangyuefengyu.redstonecircuit.RCConfig;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The analogue output of a block that answers differently depending on which side is asked.
 *
 * <p>机械动力：航空学's helm overrides {@code hasAnalogOutputSignal} but not vanilla's
 * {@code getAnalogOutputSignal(state, level, pos)}, so that question answers 0 for it; the steering angle comes
 * from Simulated's own direction-taking interface instead, which lives in a jar this project does not compile
 * against and is therefore reached by name, without initialising it.
 *
 * <p>{@code direction} is vanilla's own: {@code state.getValue(FACING)}, from the comparator towards the block.
 */
public final class DirectionalAnalog {

    /** The interface, by name: this has to compile without the mod that defines it. */
    private static final String INTERFACE = "dev.simulated_team.simulated.api.IDirectionalAnalogOutput";

    /** Its one method - state, level, position, direction, in that order. */
    private static final String METHOD = "getAnalogOutputSignalFrom";

    /** The resolved method per block class, or {@link #NONE}: resolved once, because this is asked per solve. */
    private static final Map<Class<?>, Object> METHODS = new ConcurrentHashMap<>();

    private static final Object NONE = new Object();

    private static volatile boolean interfaceResolved;
    @Nullable
    private static volatile Class<?> interfaceClass;
    private static volatile boolean failureLogged;

    private DirectionalAnalog() {
    }

    /**
     * The analogue output of {@code state} asked from the given side, or 0 - for a block that does not implement
     * the API, for a mod that is not installed, and for anything that goes wrong while asking. Clamped to 0-15.
     */
    public static int analogOutputFrom(@Nullable BlockState state, @Nullable Level level, @Nullable BlockPos pos,
                                       @Nullable Direction direction) {
        if (state == null || level == null || pos == null || direction == null) {
            return 0;
        }
        Block block = state.getBlock();
        Object resolved = methodFor(block);
        if (!(resolved instanceof Method method)) {
            return 0;
        }
        try {
            Object answer = method.invoke(block, state, level, pos, direction);
            return answer instanceof Integer value ? Math.max(0, Math.min(15, value)) : 0;
        } catch (IllegalAccessException | InvocationTargetException | RuntimeException | LinkageError failed) {
            if (!failureLogged) {
                failureLogged = true;
                RCConfig.LOGGER.warn("[redstonecircuit] asking {} for its directional analogue output failed, so "
                        + "inner comparators read the plain question instead: {}", block, failed.toString());
            }
            return 0;
        }
    }

    /**
     * Whether this block answers the directional question at all, so "the block is not a directional source" and
     * "the answer was 0" can be told apart.
     */
    public static boolean answersDirectionally(@Nullable Block block) {
        return block != null && methodFor(block) != NONE;
    }

    /** The method for this block class, resolved once: the API's method, or {@link #NONE}. */
    private static Object methodFor(Block block) {
        return METHODS.computeIfAbsent(block.getClass(), key -> {
            Class<?> api = interfaceType();
            if (api == null || !api.isInstance(block)) {
                return NONE;
            }
            try {
                return api.getMethod(METHOD, BlockState.class, Level.class, BlockPos.class, Direction.class);
            } catch (NoSuchMethodException | RuntimeException | LinkageError missing) {
                return NONE;
            }
        });
    }

    /**
     * The API's class, looked up once and without initialising it, so a mod that ships it but has not been started
     * cannot be woken by a comparator query. Absent is a normal state, cached as {@code null}.
     */
    @Nullable
    private static Class<?> interfaceType() {
        if (!interfaceResolved) {
            synchronized (DirectionalAnalog.class) {
                if (!interfaceResolved) {
                    Class<?> found = null;
                    try {
                        found = Class.forName(INTERFACE, false, DirectionalAnalog.class.getClassLoader());
                    } catch (ClassNotFoundException | LinkageError absent) {
                        found = null;
                    }
                    interfaceClass = found;
                    interfaceResolved = true;
                }
            }
        }
        return interfaceClass;
    }
}

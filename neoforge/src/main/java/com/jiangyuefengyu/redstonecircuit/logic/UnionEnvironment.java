package com.jiangyuefengyu.redstonecircuit.logic;

import java.util.Map;

import com.jiangyuefengyu.redstonecircuit.data.ComponentType;

import net.minecraft.core.BlockPos;

import org.jetbrains.annotations.Nullable;

/**
 * A {@link PowerEnvironment} that a <b>merged solve</b> can drive.
 *
 * <p>Two calls the fixpoint makes are not part of the solver's own environment contract, because they are
 * about <em>this</em> solve rather than about one component: which component is asking (only a wire takes a
 * neighbouring node as a hop, everything else reads it as a supply) and which world-side runs the solve
 * owns. Both used to be reachable only by the concrete implementation, which is why the fixpoint could not
 * be run anywhere but inside {@code InnerRedstoneNetwork}.
 *
 * <p>Two methods, and no visibility: this interface is package-private because the only implementations are
 * {@code SolverEnvironment} (production) and a test double, exactly as {@link PowerEnvironment} already has
 * two implementations - {@code SolverEnvironment} and {@code CarrierSpace}.
 */
interface UnionEnvironment extends PowerEnvironment {

    /**
     * Names the component the next questions are about. {@link PowerEnvironment#setQueryPosition} says the
     * same position without saying who is asking, and the difference is load-bearing: a wire must not read
     * an owned run's strength as a supply, while every other component must.
     */
    void setQuery(BlockPos pos, @Nullable ComponentType reader);

    /**
     * Every node this solve owns <b>by the world cell it stands on</b>: the world-side runs, cell by cell,
     * and the carried networks a seam has joined, at the cell each of them projects onto. A node in here is
     * an output of the solve, not an input to it.
     */
    void owning(Map<BlockPos, PowerEnvironment.Node> cells);
}

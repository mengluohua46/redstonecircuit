package com.jiangyuefengyu.redstonecircuit.logic;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/**
 * The answer a device block is giving, kept <em>beside</em> the block instead of inside its state.
 *
 * <p>The four settings <b>must</b> stay in the block state - a piston push, a {@code /clone}, our own capture and
 * release and a Sable plot all carry it along, and a block entity would cost exactly that. The answer does not have to:
 * it is derived from the settings and the neighbourhood and recomputed on every neighbour change, so keeping it in the
 * state only multiplied the state space (and the startup cost) of every device block.
 *
 * <p>Server side only, and lazily filled: a client's copy of the neighbourhood is whatever the last packet said, so a
 * cached answer could only disagree with the server. Nothing is saved - the answer is a fact about the block's
 * surroundings, not about the block.
 */
public final class BlockAnswerCache {

    /** No entry. A strength is never negative, so this cannot collide with a real answer. */
    public static final int ABSENT = -1;

    /**
     * One map per dimension, keyed by {@link BlockPos#asLong()} - hand-keyed like this mod's other position-keyed
     * tables, because a boxed {@code Long} per query is not a cost beside the neighbour reads the answer came from.
     */
    private static final Map<ResourceKey<Level>, Map<Long, Integer>> ANSWERS = new HashMap<>();

    private BlockAnswerCache() {
    }

    /** The answer stored for this position, or {@link #ABSENT} when nothing has asked yet. */
    public static int get(ServerLevel level, BlockPos pos) {
        Map<Long, Integer> answers = ANSWERS.get(level.dimension());
        if (answers == null) {
            return ABSENT;
        }
        Integer answer = answers.get(pos.asLong());
        return answer == null ? ABSENT : answer;
    }

    /** Stores an answer - the one place a device's answer is written down. */
    public static void put(ServerLevel level, BlockPos pos, int power) {
        ANSWERS.computeIfAbsent(level.dimension(), key -> new HashMap<>()).put(pos.asLong(), power);
    }

    /** Drops a position's entry: a cell that no longer holds a device must not keep answering. */
    public static void forget(ServerLevel level, BlockPos pos) {
        Map<Long, Integer> answers = ANSWERS.get(level.dimension());
        if (answers != null) {
            answers.remove(pos.asLong());
        }
    }

    /** Forgets everything about one dimension - a world being unloaded, and the tests. */
    public static void clear(ResourceKey<Level> dimension) {
        ANSWERS.remove(dimension);
    }

    /** How many answers this dimension is holding, for the diagnostics and the tests. */
    public static int size(ServerLevel level) {
        Map<Long, Integer> answers = ANSWERS.get(level.dimension());
        return answers == null ? 0 : answers.size();
    }
}

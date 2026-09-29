package com.jiangyuefengyu.redstonecircuit.carrier;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * "What does the vanilla world push out of this position, towards this direction?"
 *
 * <h2>Why this is an interface in the main source set</h2>
 * P4's world-to-structure half has to read world signals at positions that were computed from a pose,
 * and that read is the one part of the bridge that needs a {@code ServerLevel}: the rules themselves
 * live in {@code InnerRedstoneNetwork}, which is the only implementation in the mod, and they are the
 * same rules the world solver already uses for a host block standing on the ground
 * ({@code hostAcceptsStrongPower}, and the one-hop charge for a neighbouring vanilla wire).
 *
 * <p>Keeping the <em>call</em> behind this interface is what lets the whole bridge be reasoned about
 * without a running game: a unit test hands in a lambda that answers from a map, and the projection,
 * the direction conversion and the "which side is this machine reading" arithmetic are all exercised
 * for real.
 *
 * <h2>Directions are in the world's frame</h2>
 * {@code worldDirection} points from the block <b>asking</b> towards the block being asked - the same
 * convention vanilla's {@code Level#getSignal(BlockPos, Direction)} uses (it is called as
 * {@code getSignal(neighbour, askerToNeighbour)}), so an implementation can forward it unchanged.
 */
@FunctionalInterface
public interface CarrierWorldSignals {

    /**
     * The signal the world pushes out of {@code worldPos} towards {@code worldDirection}.
     *
     * @param worldPos where the neighbour is, in world coordinates
     * @param worldDirection from the block asking to that neighbour, in the same frame
     * @return 0-15; 0 is the ordinary answer for air, for a plain solid block and for a neighbour that
     *     does not signal that way
     */
    int externalSignal(BlockPos worldPos, Direction worldDirection);
}

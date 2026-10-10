package com.jiangyuefengyu.redstonecircuit.carrier;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * "What does the vanilla world push out of this position, towards this direction?"
 * <p>Reading world signals at positions computed from a pose is the one part of the bridge that needs a
 * {@code ServerLevel}; the rules live in {@code InnerRedstoneNetwork} and are the same ones the world solver uses for
 * a host block standing on the ground. Keeping the <em>call</em> behind this interface lets the whole bridge be
 * reasoned about without a running game - a unit test hands in a lambda answering from a map.
 * <p>{@code worldDirection} points from the block <b>asking</b> to the block asked, the same convention vanilla's
 * {@code Level#getSignal(BlockPos, Direction)} uses, so an implementation can forward it unchanged.
 */
@FunctionalInterface
public interface CarrierWorldSignals {

    /**
     * The signal the world pushes out of {@code worldPos} towards {@code worldDirection}: 0-15, where 0 is the
     * ordinary answer for air, for a plain solid block and for a neighbour that does not signal that way.
     */
    int externalSignal(BlockPos worldPos, Direction worldDirection);
}

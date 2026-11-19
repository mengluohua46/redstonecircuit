package com.jiangyuefengyu.redstonecircuit.block;

import com.jiangyuefengyu.redstonecircuit.RCRegistry;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 红石粉（贴面）'s block entity. It holds nothing at all, and that is the point.
 *
 * <p>A block entity renderer is the only place that gets both the position and the level while drawing, so it is the
 * one place that can draw something which depends on the <b>neighbours</b> without storing that dependency in the
 * block state. See {@code client.SurfaceWireRenderer} for why that matters: a neighbour-dependent state value made the
 * two cells of a junction judge each other differently, and the resulting shape-update ping-pong is what vanilla
 * reports as Too many chained neighbor updates.
 */
public class SurfaceWireEntity extends BlockEntity {

    public SurfaceWireEntity(BlockPos pos, BlockState state) {
        super(RCRegistry.SURFACE_WIRE_ENTITY.get(), pos, state);
    }
}

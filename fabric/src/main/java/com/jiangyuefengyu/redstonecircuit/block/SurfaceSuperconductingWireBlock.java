package com.jiangyuefengyu.redstonecircuit.block;

import com.jiangyuefengyu.redstonecircuit.RCRegistry;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 超导红石粉（贴面）: the superconducting dust, laid on any face of one cell - and on more than one face of it.
 *
 * <p>Geometry, gestures and drops are {@link SurfaceWireBlock}'s, unchanged: six face booleans, one power per cell, the
 * one-layer click, the per-face drop. The single difference is <b>who decides the strength</b>. An ordinary sheet
 * works out its own; this one belongs to the superconducting network and never works anything out at all - every
 * change asks {@link SuperconductingWireBlock#updateComponent} to re-solve the run, and that run is not local: it
 * spans the floor dust and every sheet hanging on it, so a wall, a ceiling and the floor beside them are one run and
 * hold one strength (作者 2026-11-15「一个网络，跨面零损耗」).
 *
 * <p>Two consequences worth naming, because both are load-bearing:
 *
 * <p><b>It answers nothing while the solve runs.</b> The network reads its neighbours to find its supply, and a cell
 * of the run that answered its own stored strength would supply itself - the pair at gain exactly one that never comes
 * back down. The floor dust already goes silent for the duration; a sheet is a cell of the same run, so it goes silent
 * with it.
 *
 * <p><b>Being one run is what keeps it from latching.</b> A lossless sheet next to a lossless floor wire, each reading
 * the other at full strength, is a fixed point; being the same network means there is no seam to read across.
 */
public class SurfaceSuperconductingWireBlock extends SurfaceWireBlock {

    public SurfaceSuperconductingWireBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    /** The network owns the strength: this only tells it that one of its cells moved. */
    @Override
    protected void updateStrength(Level level, BlockPos pos, BlockState state) {
        SuperconductingWireBlock.updateComponent(level, pos);
    }

    @Override
    protected int getSignal(BlockState state, BlockGetter level, BlockPos pos, net.minecraft.core.Direction side) {
        if (SuperconductingWireBlock.isSuppressed()) {
            return 0;
        }
        return super.getSignal(state, level, pos, side);
    }

    @Override
    protected int getDirectSignal(BlockState state, BlockGetter level, BlockPos pos,
                                  net.minecraft.core.Direction side) {
        if (SuperconductingWireBlock.isSuppressed()) {
            return 0;
        }
        return super.getDirectSignal(state, level, pos, side);
    }

    @Override
    protected ItemStack sheetItem() {
        return new ItemStack(RCRegistry.SUPERCONDUCTING_REDSTONE);
    }

    @Override
    public String stripPrefix() {
        return "block/superconducting_dust_";
    }
}

package com.jiangyuefengyu.redstonecircuit.compat.physics;

import com.jiangyuefengyu.redstonecircuit.RCConfig;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierAccess;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierId;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.Contraption;

import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;

import net.minecraft.world.level.BlockGetter;

import org.jetbrains.annotations.Nullable;

/**
 * The compat half's answer to "let me read the machine's own blocks": {@link CarrierAccess},
 * implemented for Create contraptions and Sable sub-levels.
 *
 * <p>Create already exposes a contraption's blocks as a {@code BlockGetter} in the coordinate space the
 * carried index is keyed by (the contraption's {@code anchor.offset(localPos)} is the world position), so
 * the Create half is one call. The Sable half reads the level at the plot region's own coordinates - see
 * {@link #subLevelPlot}. Reached only from {@link CarrierResolver}, which loads after a mod-list check.
 */
final class CarrierStructureAccess implements CarrierAccess {

    /** The pose reader, reused for the two entity/sub-level lookups both halves need. */
    private final CarrierResolver resolver;

    CarrierStructureAccess(CarrierResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    @Nullable
    public BlockGetter blockViewOf(CarrierId carrier) {
        try {
            return carrier.isContraption() ? contraptionWorld(carrier) : subLevelPlot(carrier);
        } catch (RuntimeException | LinkageError failed) {
            // A read that can take the server down is worse than a machine that cannot answer one
            // conductor query - which is the only thing this value is used for.
            RCConfig.LOGGER.warn("[redstonecircuit] carrier {} structure read failed: {}",
                    carrier.shortId(), failed.toString());
            return null;
        }
    }

    /** Create's own view of a contraption's blocks, in the contraption's local coordinates. */
    @Nullable
    private BlockGetter contraptionWorld(CarrierId carrier) {
        if (!PhysicsCompat.createLoaded()) {
            return null;
        }
        AbstractContraptionEntity entity = resolver.contraptionEntityOf(carrier);
        if (entity == null || entity.getContraption() == null) {
            return null;
        }
        Contraption contraption = entity.getContraption();
        return contraption.getContraptionWorld();
    }

    /**
     * Sable's view of a sub-level's blocks, <b>in the coordinate frame the carried index uses</b>: the level
     * read at the local coordinate itself, not relative to the plot's centre. A sub-level's local
     * coordinates are the level's own block coordinates inside the reserved plot region, which is also the
     * coordinate Sable hands its own block-change handler.
     */
    @Nullable
    private BlockGetter subLevelPlot(CarrierId carrier) {
        if (!PhysicsCompat.sableLoaded()) {
            return null;
        }
        SubLevel subLevel = resolver.subLevelOf(carrier);
        if (subLevel == null) {
            return null;
        }
        LevelPlot plot = subLevel.getPlot();
        if (plot == null) {
            return null;
        }
        net.minecraft.world.level.Level level = subLevel.getLevel();
        if (level == null) {
            return null;
        }
        // The sub-level's own blocks, at the sub-level's own (plot = level) coordinates. A read of an
        // unloaded chunk answers air, which is the same "nothing here" a missing structure gives.
        return new LocalView(level);
    }

    /**
     * A {@link net.minecraft.world.level.SignalGetter} over one level - the space a Sable sub-level's own
     * coordinates live in. A named class rather than a lambda, so the frame choice above is visible where a
     * block is actually read. It is a signal getter and not merely a {@code BlockGetter} because the shared
     * read rule asks for the strong power a solid neighbour carries; {@code getSignal} goes to the level.
     */
    private record LocalView(net.minecraft.world.level.Level level)
            implements net.minecraft.world.level.SignalGetter {

        @Override
        public net.minecraft.world.level.block.state.BlockState getBlockState(
                net.minecraft.core.BlockPos pos) {
            return level.getBlockState(pos);
        }

        @Override
        @Nullable
        public net.minecraft.world.level.block.entity.BlockEntity getBlockEntity(
                net.minecraft.core.BlockPos pos) {
            return level.getBlockEntity(pos);
        }

        @Override
        public net.minecraft.world.level.material.FluidState getFluidState(
                net.minecraft.core.BlockPos pos) {
            return level.getFluidState(pos);
        }

        @Override
        public int getSignal(net.minecraft.core.BlockPos pos, net.minecraft.core.Direction direction) {
            return level.getSignal(pos, direction);
        }

        @Override
        public int getHeight() {
            return level.getHeight();
        }

        @Override
        public int getMinBuildHeight() {
            return level.getMinBuildHeight();
        }
    }
}

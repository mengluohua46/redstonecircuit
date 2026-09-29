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
 * <h2>Why the two cases are genuinely different</h2>
 * A Create contraption keeps its blocks in a plain map, and Create already exposes that map as a
 * {@code BlockGetter}:
 *
 * <pre>
 *   ContraptionWorld#getBlockState(local) -&gt; Contraption#getBlocks().get(local).state()
 * </pre>
 *
 * which - verified with {@code javap -c}, not assumed - is <em>exactly</em> the coordinate space the
 * carried index is keyed by: P1 records {@code Contraption#anchor.offset(localPos)} as the world
 * position, so a local coordinate means the same thing to both halves. The Create half is therefore
 * one call. The Sable half is one call too, but a different one: the sub-level's own blocks are the
 * level's blocks at the plot region's coordinates, so the view is a thin {@code SignalGetter} over
 * that level - see {@link #subLevelPlot}, which is where the P1.19 frame correction is written down.
 *
 * <h2>Why this is separate from {@link CarrierResolver}</h2>
 * They answer different questions and are asked at different rates - a pose once per tick per carrier,
 * this once per solve - and keeping them apart means the P1 diagnostics can be given a pose reader
 * without a block reader, which is what {@code /rc physics} still does.
 *
 * <h2>Nothing here runs unless the mods are installed</h2>
 * Reached only from {@link CarrierResolver}, which is itself only loaded after a {@code ModList} check.
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
     * Sable's view of a sub-level's blocks, <b>in the coordinate frame the carried index uses</b>.
     *
     * <h2>The frame correction (P1.19), and why it matters now</h2>
     * This used to hand back {@code plot.getEmbeddedLevelAccessor()}. That accessor is real, but it
     * answers in a <b>different</b> frame from the one every other half of this mod uses:
     * {@code EmbeddedPlotLevelAccessor#getBlockState(pos)} reads
     * {@code level.getBlockState(pos.offset(plot.getCenterBlock()))} - coordinates <em>relative to the
     * plot's centre</em>, a few hundred blocks around zero.
     *
     * <p>The carried index, the pose and the plot's own bounds all live in the other frame. Sable's
     * {@code LevelPlot.localBounds} is built from each plot chunk holder's own bounding box moved to
     * that holder's <b>level</b> chunk position, and {@code SubLevel#updateBoundingBox()} transforms
     * exactly that box by the pose to get the structure's world bounds - so a sub-level's local
     * coordinates are the level's own block coordinates inside the reserved plot region (the twenty
     * million the P1 diagnostics measured), and the pose maps those to the world. A block in a plot
     * therefore lives at its local coordinate <em>in the level</em>, which is also what
     * {@code SableCommonEvents#handleBlockChange} is handed by Sable itself.
     *
     * <p>Nothing noticed while the only caller was {@code isRedstoneConductor}, which the solve loop
     * never asks. P1.19 reads the structure's own signal sources through this view, so the frame has to
     * be right or a carried lever would be looked for in an unloaded region and read as air. The
     * correction is one offset: read the level at the local coordinate itself.
     *
     * <p>Verified against Sable 2.0.5's bytecode, not assumed: {@code LevelPlot.getBoundingBox()},
     * {@code PlotChunkHolder#buildBoundingBox} (chunk-local x/z, absolute y), {@code LevelPlot#toLocal}
     * (a chunk-position difference against {@code plotPos << logSize}), {@code SubLevel#updateBoundingBox}
     * (that box through the pose) and {@code EmbeddedPlotLevelAccessor}'s {@code +center} on every read.
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
     * A {@link net.minecraft.world.level.SignalGetter} over one level, which is what a Sable
     * sub-level's own coordinates are.
     *
     * <p>Written as a named class rather than a lambda so the frame decision above is visible at the
     * one place a block is actually read. It is a {@code SignalGetter} - and not merely a
     * {@code BlockGetter} - on purpose: the shared read rule asks a signal getter for the strong power
     * a solid neighbour carries ({@code hostAcceptsStrongPower}), so a view that only implemented
     * {@code BlockGetter} would silently answer the {@code hostAcceptsStrongPower = false} value for a
     * structure's own blocks. {@code getSignal} is delegated to the level itself, so a machine's own
     * blocks are read exactly as the same blocks would be if they stood in the world.
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

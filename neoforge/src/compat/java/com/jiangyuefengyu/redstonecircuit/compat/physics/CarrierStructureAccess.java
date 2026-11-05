package com.jiangyuefengyu.redstonecircuit.compat.physics;

import java.util.Map;

import com.jiangyuefengyu.redstonecircuit.RCConfig;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierAccess;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierId;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierStore;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.Contraption;

import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;

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

    /**
     * <b>Write one of a contraption's own blocks</b> - the half {@link CarrierAccess} does not have, and the reason
     * {@code CarrierSource#setCarriedBlock} exists at all.
     *
     * <p>Reached only for a carrier whose storage is <b>not</b> the level: the criterion is
     * {@link CarrierStore#storageInLevel}, and the sub-level half of the write lives there, beside the sub-level
     * half of the read. That is why there is no fork here at all, and why nothing on this path may touch the level:
     * a contraption's local coordinate is anchor-relative, so in the level it is somebody else's block.
     *
     * <p>A failure is reported and answered {@code false} rather than thrown: this runs inside a tick, and a machine
     * that cannot be written is a machine that does not light, which is far less than a tick that dies.
     *
     * @return whether the block really changed
     */
    boolean setBlock(CarrierId carrier, BlockPos local, BlockState state) {
        if (carrier == null || local == null || state == null || CarrierStore.storageInLevel(carrier)) {
            return false;
        }
        try {
            return setInContraption(carrier, local, state);
        } catch (RuntimeException | LinkageError failed) {
            RCConfig.LOGGER.warn("[redstonecircuit] carrier {} structure write failed: {}",
                    carrier.shortId(), failed.toString());
            return false;
        }
    }

    /**
     * A contraption's own block, written into the contraption's block map - the only place it exists - <b>and the
     * clients told about it</b>, both in Create's own single call. The captured block entity data is carried over
     * unchanged: this changes a block's <em>state</em>, not which block it is.
     *
     * <p>{@code AbstractContraptionEntity#setBlock} is exactly "write one block of this contraption and publish it":
     * it puts the {@code StructureBlockInfo} into {@code Contraption#getBlocks()} and then sends
     * {@code ContraptionBlockChangedPacket(entityId, localPos, newState)} to the clients tracking that entity -
     * verified against the Create 6.0.10 jar this module compiles against, whose body is {@code blocks.put(...)}
     * followed by {@code CatnipServices.NETWORK.sendToClientsTrackingEntity(this, packet)}. Writing the {@code put}
     * here and hand-rolling the packet would be the same two steps with a second copy of the ordering rule; by
     * delegating, the packet cannot go out before the write, and nothing here has to name a packet type at all.
     *
     * <p>That call is made <b>only</b> on this path: {@link #setBlock} is reached only for a carrier whose storage is
     * not the level, so a Sable sub-level never sends it. And the equality test above is the gate that keeps it to
     * <b>one packet per real change</b>: a group pass that re-asserts the same {@code LIT} state sends nothing.
     */
    private boolean setInContraption(CarrierId carrier, BlockPos local, BlockState state) {
        if (!PhysicsCompat.createLoaded()) {
            return false;
        }
        AbstractContraptionEntity entity = resolver.contraptionEntityOf(carrier);
        Contraption contraption = entity == null ? null : entity.getContraption();
        Map<BlockPos, StructureBlockInfo> blocks = contraption == null ? null : contraption.getBlocks();
        StructureBlockInfo info = blocks == null ? null : blocks.get(local);
        if (info == null || info.state().equals(state)) {
            return false;
        }
        entity.setBlock(local, new StructureBlockInfo(local, state, info.nbt()));
        // "Whether it really changed" is asked of the map itself rather than assumed: Create's carriage override
        // stands down when the carriage is not bound, and a claimed change nobody can see would wake the world for
        // nothing.
        StructureBlockInfo written = blocks.get(local);
        return written != null && !written.state().equals(info.state());
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

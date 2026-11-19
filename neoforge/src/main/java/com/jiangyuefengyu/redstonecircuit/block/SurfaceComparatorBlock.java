package com.jiangyuefengyu.redstonecircuit.block;

import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.SignalGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.ComparatorMode;
import net.minecraft.world.level.block.state.properties.EnumProperty;

/**
 * 比较器（贴面）: vanilla's comparator, hung on any of the six faces of its cell and reading any direction inside that
 * face - up and down included.
 *
 * <p>Behaviour is vanilla's: the back input (analog outputs included) against the stronger of the two sides, compare
 * or subtract, fifteen at most, two ticks of delay, right-click to flip the mode, never locked. The one structural
 * difference is that vanilla keeps the answer in a block entity; this twin derives it from the inputs live, which is
 * what lets the answer move when a strength changes without an on/off edge (see the base's {@code neighborChanged}).
 */
public class SurfaceComparatorBlock extends SurfaceDiodeBlock {

    public static final EnumProperty<ComparatorMode> MODE = BlockStateProperties.MODE_COMPARATOR;

    private static final MapCodec<SurfaceComparatorBlock> CODEC = simpleCodec(SurfaceComparatorBlock::new);

    public SurfaceComparatorBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaults(stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(MOUNT, Direction.DOWN)
                .setValue(MODE, ComparatorMode.COMPARE)
                .setValue(POWERED, false));
    }

    @Override
    protected MapCodec<SurfaceComparatorBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(MODE);
    }

    @Override
    protected int getDelay(BlockState state) {
        return 2;
    }

    @Override
    protected int getOutputSignal(BlockGetter level, BlockPos pos, BlockState state) {
        return level instanceof Level realLevel ? calculateOutputSignal(realLevel, pos, state) : 0;
    }

    /** Vanilla's comparison, read live: nothing at the back answers nothing; the sides can cancel or subtract. */
    protected int calculateOutputSignal(Level level, BlockPos pos, BlockState state) {
        int input = getInputSignal(level, pos, state);
        if (input == 0) {
            return 0;
        }
        int sides = getAlternateSignal(level, pos, state);
        int answer = sides > input ? 0
                : state.getValue(MODE) == ComparatorMode.SUBTRACT ? input - sides : input;
        if (com.jiangyuefengyu.redstonecircuit.RCConfig.debugLog()
                && level instanceof net.minecraft.server.level.ServerLevel serverLevel) {
            // One line that answers "why is sides 0": every side cell, the block standing in it, and the three
            // numbers that could have been read from it.
            StringBuilder detail = new StringBuilder();
            for (Direction side : lockSides(state.getValue(FACING))) {
                BlockPos cell = pos.relative(side);
                BlockState there = level.getBlockState(cell);
                com.jiangyuefengyu.redstonecircuit.data.Slot inner =
                        com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneStore.get(level).slotAt(cell);
                // 宿主也可能住在结构里（搬运中的方块）：那份数据在 carrier 的索引里，世界里查不到。
                com.jiangyuefengyu.redstonecircuit.data.Slot carried =
                        com.jiangyuefengyu.redstonecircuit.carrier.CarrierStore.slotAt(serverLevel, cell);
                com.jiangyuefengyu.redstonecircuit.carrier.CarriedBlockRemoval.Hit hit =
                        com.jiangyuefengyu.redstonecircuit.carrier.CarrierStore.hitAt(serverLevel, cell);
                detail.append(" [").append(side).append(' ').append(there.getBlock())
                        .append(" weak=").append(level.getSignal(cell, side))
                        .append(" direct=").append(level.getDirectSignal(cell, side))
                        .append(" mod=").append(RcPlateBlock.readingInto(level, pos, side))
                        .append(" store=").append(com.jiangyuefengyu.redstonecircuit.logic.InnerRedstoneNetwork
                                .worldStoreSignalAt(serverLevel, cell, side))
                        .append(" inner=").append(inner == null ? "-" : inner.type + "/" + inner.power)
                        .append(" carrierSlot=").append(carried == null ? "-" : carried.type + "/" + carried.power)
                        .append(" carrier=").append(hit == null ? "-" : hit.carrier().shortId().toString())
                        .append(']');
            }
            com.jiangyuefengyu.redstonecircuit.RCConfig.LOGGER.info(
                    "[redstonecircuit] surface comparator {}: facing={} mount={} mode={} input={} sides={} answer={}{}",
                    pos.toShortString(), state.getValue(FACING), state.getValue(MOUNT), state.getValue(MODE),
                    input, sides, answer, detail);
        }
        return answer;
    }

    @Override
    protected boolean shouldTurnOn(Level level, BlockPos pos, BlockState state) {
        int input = getInputSignal(level, pos, state);
        if (input == 0) {
            return false;
        }
        int sides = getAlternateSignal(level, pos, state);
        return input > sides || (input == sides && state.getValue(MODE) == ComparatorMode.COMPARE);
    }

    /** Vanilla's back input, plus an analog source (a container, a detector) standing in front. */
    @Override
    protected int getInputSignal(Level level, BlockPos pos, BlockState state) {
        int signal = super.getInputSignal(level, pos, state);
        Direction facing = state.getValue(FACING);
        BlockPos front = pos.relative(facing);
        BlockState frontState = level.getBlockState(front);
        if (frontState.hasAnalogOutputSignal()) {
            return frontState.getAnalogOutputSignal(level, front);
        }
        return signal;
    }

    /**
     * The side inputs, read as plain signals rather than only from diodes: a comparator's sides take any strong power,
     * which is the one place its reading differs from a repeater's.
     */
    @Override
    protected int getAlternateSignal(SignalGetter level, BlockPos pos, BlockState state) {
        int strongest = 0;
        for (Direction side : lockSides(state.getValue(FACING))) {
            // The mod's own read, so that a side inside a host block (redstone stored in a block) counts exactly as a
            // wire lying against the side does - vanilla's getSignal cannot see inside a host block.
            strongest = Math.max(strongest, neighbourSignal((Level) level, pos, side));
        }
        return strongest;
    }

    /** A comparator is never locked. */
    @Override
    public boolean isLocked(LevelReader level, BlockPos pos, BlockState state) {
        return false;
    }

    @Override
    protected BlockState cycleOnUse(BlockState state) {
        return state.cycle(MODE);
    }

    /** Vanilla's own click when a comparator's mode is flipped: same sound, same two pitches. */
    @Override
    protected void afterCycle(Level level, BlockPos pos, net.minecraft.world.entity.player.Player player,
                              BlockState next) {
        float pitch = next.getValue(MODE) == ComparatorMode.SUBTRACT ? 0.55F : 0.5F;
        level.playSound(player, pos, net.minecraft.sounds.SoundEvents.COMPARATOR_CLICK,
                net.minecraft.sounds.SoundSource.BLOCKS, 0.3F, pitch);
        level.gameEvent(player, net.minecraft.world.level.gameevent.GameEvent.BLOCK_CHANGE, pos);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction mount = context.getClickedFace().getOpposite();
        return defaultBlockState()
                .setValue(MOUNT, mount)
                .setValue(FACING, placementFacing(mount, context.getNearestLookingDirection().getOpposite(),
                        context.getHorizontalDirection().getOpposite()));
    }

    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        if (state.getValue(POWERED)) {
            dustParticle(state, level, pos, random, particleSlide(state, random, 1));
        }
    }

    @Override
    public ItemStack cloneAs() {
        return new ItemStack(Items.COMPARATOR);
    }

    @Override
    public ItemStack getCloneItemStack(LevelReader level, BlockPos pos, BlockState state) {
        return cloneAs();
    }
}
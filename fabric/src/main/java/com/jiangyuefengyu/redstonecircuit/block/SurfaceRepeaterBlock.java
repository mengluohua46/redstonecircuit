package com.jiangyuefengyu.redstonecircuit.block;

import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.SignalGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;

/**
 * 中继器（贴面）: vanilla's repeater, hung on any of the six faces of its cell and reading any of the four directions
 * inside that face - up and down included, which vanilla's horizontal-only property cannot express.
 *
 * <p>{@link SurfaceDiodeBlock} carries the mounting, the shapes and the whole diode mechanism; this class adds what
 * makes a repeater a repeater - the 1-4 delay, twelve ticks at most, fifteen out of the back, and a lock from the
 * diodes on its sides.
 */
public class SurfaceRepeaterBlock extends SurfaceDiodeBlock {

    public static final BooleanProperty LOCKED = BlockStateProperties.LOCKED;
    public static final IntegerProperty DELAY = BlockStateProperties.DELAY;

    private static final MapCodec<SurfaceRepeaterBlock> CODEC = simpleCodec(SurfaceRepeaterBlock::new);

    public SurfaceRepeaterBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaults(stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(MOUNT, Direction.DOWN)
                .setValue(DELAY, 1)
                .setValue(LOCKED, false)
                .setValue(POWERED, false));
    }

    @Override
    protected MapCodec<SurfaceRepeaterBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(DELAY, LOCKED);
    }

    @Override
    protected int getDelay(BlockState state) {
        return state.getValue(DELAY) * 2;
    }

    @Override
    protected int getOutputSignal(BlockGetter level, BlockPos pos, BlockState state) {
        return 15;
    }

    @Override
    protected boolean shouldTurnOn(Level level, BlockPos pos, BlockState state) {
        return getInputSignal(level, pos, state) > 0;
    }

    /** A repeater is held still by a diode on either side - vanilla's, or another plate like this one. */
    @Override
    public boolean isLocked(LevelReader level, BlockPos pos, BlockState state) {
        return level instanceof SignalGetter getter && getAlternateSignal(getter, pos, state) > 0;
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction direction, BlockState neighbourState,
                                     LevelAccessor level, BlockPos pos, BlockPos neighbourPos) {
        if (direction == state.getValue(MOUNT)) {
            return canStay(state, level, pos) ? state : net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
        }
        if (!level.isClientSide() && direction.getAxis() != state.getValue(FACING).getAxis()) {
            return state.setValue(LOCKED, isLocked(level, pos, state));
        }
        return state;
    }

    @Override
    protected BlockState cycleOnUse(BlockState state) {
        return state.cycle(DELAY);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction mount = context.getClickedFace().getOpposite();
        BlockState state = defaultBlockState()
                .setValue(MOUNT, mount)
                .setValue(FACING, placementFacing(mount, context.getNearestLookingDirection().getOpposite(),
                        context.getHorizontalDirection().getOpposite()));
        return state.setValue(LOCKED, isLocked(context.getLevel(), context.getClickedPos(), state));
    }

    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        if (state.getValue(POWERED)) {
            dustParticle(state, level, pos, random, particleSlide(state, random, state.getValue(DELAY)));
        }
    }

    @Override
    public ItemStack cloneAs() {
        return new ItemStack(Items.REPEATER);
    }

    @Override
    public ItemStack getCloneItemStack(LevelReader level, BlockPos pos, BlockState state) {
        return cloneAs();
    }
}

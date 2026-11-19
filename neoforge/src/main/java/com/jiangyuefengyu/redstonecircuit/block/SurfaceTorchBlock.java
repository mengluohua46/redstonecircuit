package com.jiangyuefengyu.redstonecircuit.block;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SupportType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 红石火把（贴底面）- the one torch vanilla has no form for: it hangs from the bottom face of a block.
 *
 * <p><b>Only the bottom face.</b> Vanilla already has the standing torch and the wall torch; this block never takes
 * their clicks (作者 2026-11-12「不要动原版本来有的东西」), and its state is therefore just {@link #LIT} - no mounting
 * property, because there is only one mounting.
 *
 * <p>Both answers are vanilla's, with the support being the block above:
 *
 * <ul>
 *   <li>{@code getSignal}: {@code LIT && side != DOWN ? 15 : 0} - the block it hangs on (the asker above gives
 *       {@code side == DOWN}) is refused, everything else gets 15. That is the standing torch's own rule
 *       ({@code side != UP} with its support below).</li>
 *   <li>{@code getDirectSignal}: strong power only towards the open side ({@code side == UP}, the asker below), never
 *       towards the block it hangs on - 作者 2026-11-12.</li>
 * </ul>
 */
public class SurfaceTorchBlock extends Block {

    public static final BooleanProperty LIT = BlockStateProperties.LIT;

    /** How long after the world changes under it the torch looks again: vanilla's own two ticks. */
    private static final int RECHECK = 2;

    /** The torch, hanging: a two pixel column from the ceiling down to ten sixteenths above the floor. */
    private static final VoxelShape SHAPE = Block.box(6.0, 6.0, 6.0, 10.0, 16.0, 10.0);

    public SurfaceTorchBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(LIT, true));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(LIT);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        return canStay(state, level, pos);
    }

    /** The placement path asks it before it writes anything. */
    public boolean canStay(BlockState state, LevelReader level, BlockPos pos) {
        return com.jiangyuefengyu.redstonecircuit.carrier.CarriedSupport.sturdy(
                level, pos.above(), Direction.DOWN, SupportType.CENTER);
    }

    /** Whether the block it hangs on is powered - the condition that turns a torch off. */
    private static boolean supportIsPowered(Level level, BlockPos pos) {
        return level.getSignal(pos.above(), Direction.DOWN) > 0;
    }

    @Override
    protected boolean isSignalSource(BlockState state) {
        return true;
    }

    @Override
    protected int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction side) {
        return state.getValue(LIT) && side != Direction.DOWN ? 15 : 0;
    }

    @Override
    protected int getDirectSignal(BlockState state, BlockGetter level, BlockPos pos, Direction side) {
        return side == Direction.UP ? state.getSignal(level, pos, side) : 0;
    }

    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        boolean lit = !supportIsPowered(level, pos);
        if (lit != state.getValue(LIT)) {
            level.setBlock(pos, state.setValue(LIT, lit), 2);
            level.updateNeighborsAt(pos, this);
        }
    }

    private static void recheck(Level level, BlockPos pos) {
        if (!level.isClientSide && !level.getBlockTicks().hasScheduledTick(pos, level.getBlockState(pos).getBlock())) {
            level.scheduleTick(pos, level.getBlockState(pos).getBlock(), RECHECK);
        }
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean isMoving) {
        if (!level.isClientSide) {
            recheck(level, pos);
        }
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction direction, BlockState neighbourState,
                                     LevelAccessor level, BlockPos pos, BlockPos neighbourPos) {
        if (direction == Direction.UP && !canStay(state, level, pos)) {
            return Blocks.AIR.defaultBlockState();
        }
        return state;
    }

    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block block, BlockPos fromPos,
                                   boolean isMoving) {
        if (level.isClientSide) {
            return;
        }
        if (!canStay(state, level, pos)) {
            dropResources(state, level, pos);
            level.removeBlock(pos, false);
            return;
        }
        recheck(level, pos);
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockState state = defaultBlockState();
        return context.getClickedFace() == Direction.DOWN && canStay(state, context.getLevel(), context.getClickedPos())
                ? state : null;
    }
}

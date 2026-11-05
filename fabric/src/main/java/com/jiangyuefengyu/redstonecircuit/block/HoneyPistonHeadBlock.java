package com.jiangyuefengyu.redstonecircuit.block;

import com.jiangyuefengyu.redstonecircuit.RCRegistry;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonHeadBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 蜂蜜活塞的杆头 - vanilla's piston head, hanging off a {@link HoneyPistonBlock} instead of off a piston.
 *
 * <p>The parent decides "is the base still there?" in a private method that only ever answers for the two vanilla
 * blocks, so every place that asks it is repeated here against {@code honey_piston} and the vanilla base is left to its
 * own copy. Everything else - the shape, {@code SHORT}, the neighbour rules - is inherited, and no property is added:
 * {@code PistonHeadBlock#getShape} reads {@code FACING} and {@code SHORT} straight off the state, and the extension path
 * sets those two on whatever state this block hands it.
 */
public class HoneyPistonHeadBlock extends PistonHeadBlock {

    public HoneyPistonHeadBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    /** The parent's own {@code isFittingBase}, against 蜂蜜活塞: retracted or turned away is not a base. */
    private boolean fitsHoneyBase(BlockState head, BlockState base) {
        return base.is(RCRegistry.HONEY_PISTON)
                && base.getValue(HoneyPistonBlock.EXTENDED)
                && base.getValue(FACING) == head.getValue(FACING);
    }

    @Override
    protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        BlockState base = level.getBlockState(pos.relative(state.getValue(FACING).getOpposite()));
        return this.fitsHoneyBase(state, base)
                || base.is(Blocks.MOVING_PISTON) && base.getValue(FACING) == state.getValue(FACING);
    }

    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide && player.getAbilities().instabuild) {
            BlockPos basePos = pos.relative(state.getValue(FACING).getOpposite());
            if (this.fitsHoneyBase(state, level.getBlockState(basePos))) {
                level.destroyBlock(basePos, false);
            }
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock())) {
            super.onRemove(state, level, pos, newState, movedByPiston);
            BlockPos basePos = pos.relative(state.getValue(FACING).getOpposite());
            if (this.fitsHoneyBase(state, level.getBlockState(basePos))) {
                // A head without its base is a block floating in the air, and vanilla takes the base with it: the
                // same here, and only here - a retraction has already turned the base into a MOVING_PISTON by the time
                // it drops the head, so this never fires on the piston's own account.
                level.destroyBlock(basePos, true);
            }
        }
    }

    @Override
    public ItemStack getCloneItemStack(LevelReader level, BlockPos pos, BlockState state) {
        return new ItemStack(RCRegistry.HONEY_PISTON_ITEM);
    }
}

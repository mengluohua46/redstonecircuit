package com.jiangyuefengyu.redstonecircuit.block;

import org.jetbrains.annotations.Nullable;

import com.jiangyuefengyu.redstonecircuit.RCRegistry;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Places this mod's three repeaters on the faces their own code cannot use - the same rule the vanilla twins follow,
 * one line wide: <b>clicking the top face is the floor version's click, untouched</b>.
 *
 * <p>Every other face is a face a floor repeater has no answer for (it needs a floor under it), so those are the ones
 * this takes over. Nothing about a repeater on the floor moves: same item, same block, same code.
 *
 * <p>Called from the {@code BlockItem#useOn} hook, which runs on both sides, so the placement below writes on the
 * client as well: the client's prediction and the server's answer end up the same block.
 */
public final class SurfaceVariableRepeaterPlacement {

    private SurfaceVariableRepeaterPlacement() {
    }

    /** The layout for this click, or {@code null} when this is not a click the twin should take. */
    @Nullable
    public static InteractionResult tryPlace(UseOnContext context) {
        ItemStack stack = context.getItemInHand();
        if (!(stack.getItem() instanceof BlockItem item)) {
            return null;
        }
        SurfaceVariableRepeaterBlock block = twinFor(item.getBlock());
        if (block == null) {
            return null;
        }
        Direction clicked = context.getClickedFace();
        if (clicked == Direction.UP) {
            return null;
        }
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos().relative(clicked);
        if (!level.getBlockState(pos).canBeReplaced()) {
            return null;
        }
        Direction mount = clicked.getOpposite();
        BlockState state = block.defaultBlockState()
                .setValue(SurfaceDiodeBlock.MOUNT, mount)
                // A plate on a wall reads along the wall, and where the player looks decides which way: aiming into
                // the wall snaps to the nearest direction that mounting can honour.
                .setValue(SurfaceDiodeBlock.FACING, SurfaceDiodeBlock.placementFacing(mount,
                        directionToward(context), context.getHorizontalDirection().getOpposite()));
        if (!block.canStay(state, level, pos)) {
            // Nothing to hang on: the floor version's click decides, exactly as it would without this class.
            return null;
        }
        state = state.setValue(SurfaceRepeaterBlock.LOCKED, block.isLocked(level, pos, state));
        level.setBlock(pos, state, 3);
        if (!level.isClientSide && (context.getPlayer() == null || !context.getPlayer().getAbilities().instabuild)) {
            stack.shrink(1);
        }
        SoundType sound = state.getSoundType();
        level.playSound(context.getPlayer(), pos, sound.getPlaceSound(), SoundSource.BLOCKS,
                (sound.getVolume() + 1.0F) / 2.0F, sound.getPitch() * 0.8F);
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    /** Which twin a given item places, or {@code null} for every other item in the game. */
    @Nullable
    private static SurfaceVariableRepeaterBlock twinFor(Block block) {
        if (block == RCRegistry.RETAINING_REPEATER) {
            return RCRegistry.SURFACE_RETAINING_REPEATER;
        }
        if (block == RCRegistry.DOUBLE_REPEATER) {
            return RCRegistry.SURFACE_DOUBLE_REPEATER;
        }
        if (block == RCRegistry.DOUBLE_RETAINING_REPEATER) {
            return RCRegistry.SURFACE_DOUBLE_RETAINING_REPEATER;
        }
        return null;
    }

    /** Where the plate's front should point: back at the player, read in three dimensions. */
    private static Direction directionToward(UseOnContext context) {
        if (context.getPlayer() == null) {
            return context.getHorizontalDirection().getOpposite();
        }
        net.minecraft.world.phys.Vec3 look = context.getPlayer().getLookAngle();
        return Direction.getNearest(-look.x, -look.y, -look.z);
    }
}

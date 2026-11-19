package com.jiangyuefengyu.redstonecircuit.block;

import org.jetbrains.annotations.Nullable;

import com.jiangyuefengyu.redstonecircuit.RCRegistry;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Places {@link SurfaceSuperconductingWireBlock} from the superconducting dust, and only where that dust cannot lie.
 *
 * <p>The same line the ordinary dust draws: <b>clicking a top face is the dust's own click, untouched</b>. The
 * superconducting dust needs a floor under it, so every other face is a click its own code cannot answer - those are
 * the ones this takes over. Nothing about dust on a floor moves: same item, same block, same code.
 *
 * <p>Clicking a face of a cell that already carries a sheet <b>adds that face</b> instead of placing a new block: one
 * cell holds one sheet, on as many of its six faces as the player asked for.
 *
 * <p>A cell that already holds the floor dust is left alone rather than converted: the two are one network, so a sheet
 * there would add no connection - only the surprise of the player's dust turning into a different block.
 */
public final class SurfaceSuperconductingPlacement {

    private SurfaceSuperconductingPlacement() {
    }

    /** The placement for this click, or {@code null} when the click is the dust's own to handle. */
    @Nullable
    public static InteractionResult tryPlace(UseOnContext context) {
        ItemStack stack = context.getItemInHand();
        if (!(stack.getItem() instanceof BlockItem item) || item.getBlock() != RCRegistry.SUPERCONDUCTING_WIRE) {
            return null;
        }
        Direction clicked = context.getClickedFace();
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos().relative(clicked);
        Direction face = clicked.getOpposite();
        SurfaceSuperconductingWireBlock block = RCRegistry.SURFACE_SUPERCONDUCTING_WIRE;
        BlockState target = level.getBlockState(pos);
        if (target.getBlock() instanceof SurfaceWireBlock) {
            // Before the top-face guard, and on every face including UP: a cell that already carries a sheet takes one
            // more layer whether the player clicked the wall or the floor. Getting this order wrong is what made
            // 「先贴墙上再贴地上」 impossible: the click went back to the dust, which could not place into an occupied
            // cell, so the floor face was simply never added (作者 2026-11-15).
            return SurfaceWireBlock.hasFace(target, face) ? InteractionResult.sidedSuccess(level.isClientSide)
                    : SurfaceWirePlacement.addFace(level, pos, target, face, stack, context);
        }
        if (clicked == Direction.UP) {
            // An empty cell on the floor is the dust's own click, untouched.
            return null;
        }
        if (target.is(RCRegistry.SUPERCONDUCTING_WIRE)) {
            // 点墙时落到的这一格正是地板上那根粉：它占的是「地面那一面」，再点墙就是给同一格加第二个面。
            // 粉换成贴面、强度照抄 —— 这也是把地板上的超导粉接上墙的唯一手势（贴面必须挂在实心方块上，
            // 粉不是，所以两者永远不会在相邻两格里贴着）。
            return convert(level, pos, target, face, stack, context);
        }
        if (!target.canBeReplaced()) {
            return null;
        }
        BlockState state = SurfaceWireBlock.withFace(block.defaultBlockState(), face);
        if (!block.canStay(state, level, pos)) {
            return null;
        }
        level.setBlock(pos, state, 3);
        SurfaceWirePlacement.consume(level, stack, context);
        SurfaceWirePlacement.playPlaceSound(level, pos, state, context);
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    /** The floor dust in the cell becomes a sheet on its own floor face, plus the face just clicked. */
    private static InteractionResult convert(Level level, BlockPos pos, BlockState target, Direction face,
                                             ItemStack stack, UseOnContext context) {
        SurfaceSuperconductingWireBlock block = RCRegistry.SURFACE_SUPERCONDUCTING_WIRE;
        BlockState converted = SurfaceWireBlock.withFace(SurfaceWireBlock.withFace(block.defaultBlockState(),
                Direction.DOWN), face).setValue(SurfaceWireBlock.POWER, target.getValue(SurfaceWireBlock.POWER));
        if (!block.canStay(converted, level, pos)) {
            return InteractionResult.PASS;
        }
        level.setBlock(pos, converted, 3);
        SurfaceWirePlacement.consume(level, stack, context);
        SurfaceWirePlacement.playPlaceSound(level, pos, converted, context);
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
}

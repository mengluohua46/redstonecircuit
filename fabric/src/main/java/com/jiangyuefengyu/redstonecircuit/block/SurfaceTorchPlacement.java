package com.jiangyuefengyu.redstonecircuit.block;

import org.jetbrains.annotations.Nullable;

import com.jiangyuefengyu.redstonecircuit.RCRegistry;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Places {@link SurfaceTorchBlock} from vanilla's own redstone torch item, on the faces vanilla's torch cannot use -
 * the **bottom** face first (作者 2026-11-12). A click on a top face stays vanilla's own placement, line for line.
 */
public final class SurfaceTorchPlacement {

    private SurfaceTorchPlacement() {
    }

    @Nullable
    public static InteractionResult tryPlace(UseOnContext context) {
        ItemStack stack = context.getItemInHand();
        if (!(stack.getItem() instanceof BlockItem item) || item.getBlock() != Blocks.REDSTONE_TORCH) {
            return null;
        }
        Direction clicked = context.getClickedFace();
        // **只有底面归我们**：地板上的立式火把、墙上的火把原版本来就有，一行都不动（作者 2026-11-12）。
        if (clicked != Direction.DOWN) {
            return null;
        }
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos().relative(clicked);
        if (!level.getBlockState(pos).canBeReplaced()) {
            return null;
        }
        SurfaceTorchBlock block = RCRegistry.SURFACE_TORCH;
        BlockState state = block.defaultBlockState();
        if (!block.canStay(state, level, pos)) {
            return null;
        }
        level.setBlock(pos, state, 3);
        if (!level.isClientSide && (context.getPlayer() == null || !context.getPlayer().getAbilities().instabuild)) {
            stack.shrink(1);
        }
        SoundType sound = state.getSoundType();
        level.playSound(context.getPlayer(), pos, sound.getPlaceSound(), SoundSource.BLOCKS,
                (sound.getVolume() + 1.0F) / 2.0F, sound.getPitch() * 0.8F);
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
}

package com.jiangyuefengyu.redstonecircuit.block;

import org.jetbrains.annotations.Nullable;

import com.jiangyuefengyu.redstonecircuit.RCRegistry;

import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Places 红石触发器（贴面）from the trigger item, and only where the floor version refuses: a click on a side face.
 *
 * <p>One line wide, the same rule the four families before it use: <b>the floor and the ceiling are the floor version's
 * clicks, untouched</b>. Those are the two faces it can already stand on; every other face is one its own code answers
 * with "this item does not go here", and those are the ones this takes over.
 */
public final class SurfaceTriggerPlacement {

    private SurfaceTriggerPlacement() {
    }

    /** The placement for this click, or {@code null} when the click is the floor version's to handle. */
    @Nullable
    public static InteractionResult tryPlace(UseOnContext context) {
        ItemStack stack = context.getItemInHand();
        if (!(stack.getItem() instanceof BlockItem item) || item.getBlock() != RCRegistry.TRIGGER) {
            return null;
        }
        if (!context.getClickedFace().getAxis().isHorizontal()) {
            return null;
        }
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos().relative(context.getClickedFace());
        if (!level.getBlockState(pos).canBeReplaced()) {
            return null;
        }
        SurfaceTriggerBlock block = RCRegistry.SURFACE_TRIGGER;
        BlockState state = block.getStateForPlacement(new net.minecraft.world.item.context.BlockPlaceContext(context));
        if (state == null) {
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

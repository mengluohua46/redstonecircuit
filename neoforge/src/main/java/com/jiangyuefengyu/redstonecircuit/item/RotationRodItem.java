package com.jiangyuefengyu.redstonecircuit.item;

import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

/**
 * 方向转向杆 - the rotation rod.
 *
 * <h2>Why this is an empty shell</h2>
 * The same reason the wrench is: a block click is decided in one place by
 * {@code InnerRedstoneInteraction}, which is the only code that knows about the main-hand/off-hand
 * double dispatch and about giving a click back to vanilla when it was not ours. The work itself lives
 * in {@code com.jiangyuefengyu.redstonecircuit.logic.RotationRod}, so the command and the game tests can
 * drive it without a player.
 */
public class RotationRodItem extends Item {

    public RotationRodItem(Item.Properties properties) {
        super(properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip,
                                TooltipFlag flag) {
        tooltip.add(Component.translatable("item.redstonecircuit.rotation_rod.tip.input")
                .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("item.redstonecircuit.rotation_rod.tip.output")
                .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("item.redstonecircuit.rotation_rod.tip.wire")
                .withStyle(ChatFormatting.DARK_GRAY));
        tooltip.add(Component.translatable("item.redstonecircuit.rotation_rod.tip.requires")
                .withStyle(ChatFormatting.DARK_RED));
    }
}

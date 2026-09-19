package com.jiangyuefengyu.redstonecircuit.item;

import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

/**
 * 超导红石粉 - the superconducting dust item.
 *
 * <p>The item exists to be placed inside a block, so the only thing it adds over a plain {@link Item} is
 * the line that says what makes it different: an ordinary wire fades a step per block, and this one does
 * not. That is not visible from the outside of the block it is buried in, so it has to be said
 * somewhere.
 */
public class SuperconductingRedstoneItem extends Item {

    public SuperconductingRedstoneItem(Item.Properties properties) {
        super(properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip,
                                TooltipFlag flag) {
        tooltip.add(Component.translatable("item.redstonecircuit.superconducting_redstone.tip.place")
                .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("item.redstonecircuit.superconducting_redstone.tip.lossless")
                .withStyle(ChatFormatting.GOLD));
    }
}

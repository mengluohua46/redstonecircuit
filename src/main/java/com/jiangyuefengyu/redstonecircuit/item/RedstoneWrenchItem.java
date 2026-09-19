package com.jiangyuefengyu.redstonecircuit.item;

import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

/**
 * 红石扳手 - the redstone wrench.
 *
 * <h2>为什么这是个空壳</h2>
 * The wrench acts on blocks, and a block click is already handled in one place by
 * {@code InnerRedstoneInteraction}: it is the same handler that decides whether a shift-right-click
 * means "put a component in", "take one out" or "link these two", and it is the only place that knows
 * about the main-hand/off-hand double dispatch. Splitting the wrench's behaviour into
 * {@code Item#useOn} would give block clicks two entry points that could disagree - and both would
 * have to repeat the goggle check.
 *
 * <p>So the item exists to be held and recognised, and the work lives in
 * {@code com.jiangyuefengyu.redstonecircuit.logic.WrenchLinks}, which the command and the game tests
 * use as well.
 */
public class RedstoneWrenchItem extends Item {

    public RedstoneWrenchItem(Item.Properties properties) {
        super(properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip,
                                TooltipFlag flag) {
        tooltip.add(Component.translatable("item.redstonecircuit.redstone_wrench.tip.select")
                .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("item.redstonecircuit.redstone_wrench.tip.link")
                .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("item.redstonecircuit.redstone_wrench.tip.requires")
                .withStyle(ChatFormatting.DARK_RED));
    }
}

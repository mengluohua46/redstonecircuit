package com.jiangyuefengyu.redstonecircuit.item;

import com.jiangyuefengyu.redstonecircuit.RcTooltips;

import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

/**
 * 红石扳手 - the redstone wrench: an item that exists to be held and recognised.
 *
 * <p>The wrench acts on blocks, and a block click is already handled in one place
 * ({@code InnerRedstoneInteraction}), which decides whether a shift-right-click means "put a component in", "take one
 * out" or "link these two" and is the only place that knows about the main-hand/off-hand double dispatch. Splitting
 * that into an {@code Item#useOn} would give block clicks two entry points that could disagree; the work lives in
 * {@code logic.WrenchLinks}, which the command and the game tests use as well.
 */
public class RedstoneWrenchItem extends Item {

    public RedstoneWrenchItem(Item.Properties properties) {
        super(properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip,
                                TooltipFlag flag) {
        // One line by default, the how-to behind [Shift], like Create's wrench (RcTooltips).
        RcTooltips.append(tooltip, flag, "item.redstonecircuit.redstone_wrench.tip",
                RcTooltips.detail("item.redstonecircuit.redstone_wrench.tip.select"),
                RcTooltips.detail("item.redstonecircuit.redstone_wrench.tip.link"),
                RcTooltips.required("item.redstonecircuit.redstone_wrench.tip.requires"));
    }
}

package com.jiangyuefengyu.redstonecircuit.item;

import com.jiangyuefengyu.redstonecircuit.RcTooltips;

import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

/**
 * 方向转向杆 - the rotation rod.
 *
 * <p>An empty shell, the same as the wrench: a block click is decided in one place by
 * {@code InnerRedstoneInteraction}, the only code that knows about the main-hand/off-hand double dispatch and about
 * giving a click back to vanilla when it was not ours. The work lives in {@code logic.RotationRod}, so the command and
 * the game tests can drive it without a player.
 */
public class RotationRodItem extends Item {

    public RotationRodItem(Item.Properties properties) {
        super(properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip,
                                TooltipFlag flag) {
        // One line by default, the how-to behind [Shift], like Create's wrench (RcTooltips).
        RcTooltips.append(tooltip, flag, "item.redstonecircuit.rotation_rod.tip",
                RcTooltips.detail("item.redstonecircuit.rotation_rod.tip.input"),
                RcTooltips.detail("item.redstonecircuit.rotation_rod.tip.output"),
                RcTooltips.detail("item.redstonecircuit.rotation_rod.tip.wire"),
                RcTooltips.required("item.redstonecircuit.rotation_rod.tip.requires"));
    }
}

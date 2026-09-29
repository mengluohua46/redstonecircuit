package com.jiangyuefengyu.redstonecircuit.item;

import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterial;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

/**
 * 红石眼镜 - the redstone goggles.
 *
 * <h2>Why this is an armour item</h2>
 * 1.21.1 has no "equippable" data component (it arrives in a later version), so the only way for an
 * item to occupy the head slot, be swapped in with a right-click and show up in the helmet slot of the
 * inventory is to be an {@link ArmorItem}. The design asks for a head-slot item with no protection,
 * and {@code ArmorMaterial} answers both halves: the slot comes from
 * {@link ArmorItem.Type#HELMET}, and a defence value of zero means the armour bar shows nothing and
 * nothing is absorbed.
 *
 * <p>Nothing else is overridden except the tooltip: what the goggles <em>do</em> is decided by the
 * client renderer, which asks "is this stack in the head slot" once per frame. That keeps the effect
 * instant - put them on and the world changes on the next frame, take them off and it changes back -
 * with no packets and no server round trip.
 *
 * <p>The tooltip is the only place the effect is ever described, since a helmet with no armour points
 * would otherwise show nothing but the useless "+0 armour" line.
 */
public class RedstoneGogglesItem extends ArmorItem {

    public RedstoneGogglesItem(Holder<ArmorMaterial> material, Item.Properties properties) {
        super(material, ArmorItem.Type.HELMET, properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip,
                                TooltipFlag flag) {
        tooltip.add(Component.translatable("item.redstonecircuit.redstone_goggles.tip.wear")
                .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("item.redstonecircuit.redstone_goggles.tip.inside")
                .withStyle(ChatFormatting.AQUA));
        tooltip.add(Component.translatable("item.redstonecircuit.redstone_goggles.tip.wrench")
                .withStyle(ChatFormatting.DARK_RED));
    }
}

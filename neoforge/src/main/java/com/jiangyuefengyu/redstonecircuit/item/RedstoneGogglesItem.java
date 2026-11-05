package com.jiangyuefengyu.redstonecircuit.item;

import com.jiangyuefengyu.redstonecircuit.RcTooltips;

import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterial;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

/**
 * 红石眼镜 - the redstone goggles: a head-slot item with no protection, whose effect the client renderer applies by
 * asking once per frame whether the stack is in the head slot, so it takes hold on the next frame with no packets.
 * <p>An {@link ArmorItem} because 1.21.1 has no "equippable" data component: {@link ArmorItem.Type#HELMET} gives the
 * slot, a defence of zero means the armour bar shows nothing, and the tooltip is then the only place the effect is
 * described.
 */
public class RedstoneGogglesItem extends ArmorItem {

    public RedstoneGogglesItem(Holder<ArmorMaterial> material, Item.Properties properties) {
        super(material, ArmorItem.Type.HELMET, properties);
    }


    /**
     * <b>不挂任何属性修饰符: 眼镜不是护甲.</b>
     *
     * <p>An armour item always publishes one modifier for its slot, and <b>a modifier of zero is still a modifier</b> -
     * so vanilla draws its {@code 戴在头上时：} section and one {@code +0 护甲} line under it. Those two lines are the
     * only ones on this item that are not ours, and they say "armour" about a pair of goggles that protects against
     * nothing. Emptying the list removes the section; the head slot itself comes from the armour type, not from here,
     * so wearing them works exactly as before.
     */
    @Override
    public ItemAttributeModifiers getDefaultAttributeModifiers() {
        return ItemAttributeModifiers.EMPTY;
    }
    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip,
                                TooltipFlag flag) {
        // One line by default, the how-to behind [Shift], like Create's wrench (RcTooltips).
        RcTooltips.append(tooltip, flag, "item.redstonecircuit.redstone_goggles.tip",
                RcTooltips.detail("item.redstonecircuit.redstone_goggles.tip.wear"),
                RcTooltips.detail("item.redstonecircuit.redstone_goggles.tip.inside", ChatFormatting.AQUA),
                RcTooltips.detail("item.redstonecircuit.redstone_goggles.tip.fitted"),
                RcTooltips.required("item.redstonecircuit.redstone_goggles.tip.wrench"));
    }
}

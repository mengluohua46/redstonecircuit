package com.jiangyuefengyu.redstonecircuit.item;

import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.block.Block;

/**
 * 超导红石粉 - the superconducting dust item.
 *
 * <h2>Two ways to place it, like redstone</h2>
 * A plain right-click puts the wire on the ground, exactly as vanilla redstone does - that is
 * {@link BlockItem}'s own behaviour, and the mod deliberately leaves that click alone. Shift-right-click
 * puts it <em>inside</em> a block, which the interaction handler claims first. So the two placements
 * follow the same rule the design gives every component, and this item adds nothing to either.
 *
 * <p>What it does add is the line that says what makes it different: an ordinary wire fades a step per
 * block, and this one does not. That is not visible from the outside of a block, so it has to be said
 * somewhere.
 */
public class SuperconductingRedstoneItem extends BlockItem {

    public SuperconductingRedstoneItem(Block block, Properties properties) {
        super(block, properties);
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

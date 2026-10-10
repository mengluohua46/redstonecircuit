package com.jiangyuefengyu.redstonecircuit.item;

import com.jiangyuefengyu.redstonecircuit.RcTooltips;

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
 * <p>A plain right-click puts the wire on the ground, exactly as vanilla redstone does ({@link BlockItem}'s own
 * behaviour), and a shift-right-click puts it <em>inside</em> a block, which the interaction handler claims first; the
 * item adds nothing to either. What it does add is the line saying that an ordinary wire fades a step per block and
 * this one does not - not visible from outside a block.
 */
public class SuperconductingRedstoneItem extends BlockItem {

    public SuperconductingRedstoneItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip,
                                TooltipFlag flag) {
        // One line by default, the how-to behind [Shift], like Create's wrench (RcTooltips).
        RcTooltips.append(tooltip, flag, "item.redstonecircuit.superconducting_redstone.tip",
                RcTooltips.detail("item.redstonecircuit.superconducting_redstone.tip.place"),
                RcTooltips.detail("item.redstonecircuit.superconducting_redstone.tip.lossless", ChatFormatting.GOLD));
    }
}

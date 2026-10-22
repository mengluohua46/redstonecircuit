package com.jiangyuefengyu.redstonecircuit.client;

import com.jiangyuefengyu.redstonecircuit.RCRegistry;
import com.jiangyuefengyu.redstonecircuit.RedstoneCircuit;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

/**
 * <b>一行"眼镜在里面"，说在别人的物品上。</b>
 *
 * <p>The fitted item is not ours: it is whatever helmet the player brought, possibly from a mod this build has never
 * seen, so its tooltip cannot be overridden and a mixin for one grey line would be a poor bargain. The game fires
 * {@link ItemTooltipEvent} for every tooltip it draws, which is the same effect without touching anybody's item.
 */
@EventBusSubscriber(modid = RedstoneCircuit.MODID, value = Dist.CLIENT)
public final class GoggleTooltipEvents {

    @SubscribeEvent
    public static void onTooltip(ItemTooltipEvent event) {
        if (!event.getItemStack().has(RCRegistry.GOGGLES.get())) {
            return;
        }
        event.getToolTip().add(Component.translatable("item.redstonecircuit.redstone_goggles.fitted")
                .withStyle(ChatFormatting.GRAY));
    }

    private GoggleTooltipEvents() {
    }
}

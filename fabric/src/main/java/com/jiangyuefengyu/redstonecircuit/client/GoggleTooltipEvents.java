package com.jiangyuefengyu.redstonecircuit.client;

import com.jiangyuefengyu.redstonecircuit.RCRegistry;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

/**
 * <b>一行"眼镜在里面"，说在别人的物品上。</b>
 *
 * <p>The fitted item is not ours: it is whatever helmet the player brought, possibly from a mod this build has never
 * seen, so its tooltip cannot be overridden and a mixin for one grey line would be a poor bargain. The game fires
 * {@link ItemTooltipCallback} for every tooltip it draws, which is the same effect without touching anybody's item.
 *
 * <p>The one thing the loader changes is who subscribes: NeoForge's event bus found this class by its annotation, while
 * Fabric's callbacks are registered by hand from {@code RedstoneCircuitClient} - hence {@link #register()} and the
 * {@link Environment} annotation, which keep a dedicated server from ever loading a class that names a tooltip.
 */
@Environment(EnvType.CLIENT)
public final class GoggleTooltipEvents {

    /** Wires the line up. Called once from the client entrypoint, which is the only place a client callback may run. */
    public static void register() {
        ItemTooltipCallback.EVENT.register((stack, context, flag, lines) -> {
            if (!stack.has(RCRegistry.GOGGLES)) {
                return;
            }
            lines.add(Component.translatable("item.redstonecircuit.redstone_goggles.fitted")
                    .withStyle(ChatFormatting.GRAY));
        });
    }

    private GoggleTooltipEvents() {
    }
}

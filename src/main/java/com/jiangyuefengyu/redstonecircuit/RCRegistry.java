package com.jiangyuefengyu.redstonecircuit;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.CreativeModeTab;

/**
 * Central registry holders for the mod.
 *
 * <p>Stages 0-2 only need the creative tab (so the mod shows up neatly in the creative
 * inventory). The goggles and the wrench are registered in later stages.
 */
public final class RCRegistry {

    public static final DeferredRegister.Items ITEMS =
            DeferredRegister.createItems(RedstoneCircuit.MODID);

    public static final DeferredRegister<CreativeModeTab> CREATIVE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, RedstoneCircuit.MODID);

    /** Our own creative tab; items are appended to it as later stages add them. */
    public static final net.neoforged.neoforge.registries.DeferredHolder<CreativeModeTab, CreativeModeTab> MAIN_TAB =
            CREATIVE_TABS.register("main", () -> CreativeModeTab.builder()
                    .title(net.minecraft.network.chat.Component.translatable("itemGroup.redstonecircuit"))
                    .icon(() -> new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.REDSTONE))
                    .displayItems((parameters, output) -> {
                        // Stage 5+ will add the redstone goggles and the redstone wrench here.
                    })
                    .build());

    private RCRegistry() {
    }

    public static void register(IEventBus modEventBus) {
        ITEMS.register(modEventBus);
        CREATIVE_TABS.register(modEventBus);
    }
}

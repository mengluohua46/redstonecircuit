package com.jiangyuefengyu.redstonecircuit.client;

import com.jiangyuefengyu.redstonecircuit.RCRegistry;
import com.jiangyuefengyu.redstonecircuit.RedstoneCircuit;

import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;

/**
 * Tells the client how to draw a structure: a renderer for the placed block, and one for the item.
 *
 * <p>The mod bus, because that is where both registries live, and {@code Dist.CLIENT}, so a dedicated server never
 * loads this class. The world and the hand both go through {@link StructurePreviewGeometry}, so a structure cannot
 * look like two different objects.
 */
@EventBusSubscriber(modid = RedstoneCircuit.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class StructureClientSetup {

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(RCRegistry.STRUCTURE_BLOCK_ENTITY.get(),
                StructureBlockEntityRenderer::new);
    }

    /**
     * The item side: every display context of {@code redstonecircuit:structure} is drawn by
     * {@link StructureItemRenderer}, which is the inventory icon, the hotbar, the hand and the ground.
     */
    @SubscribeEvent
    public static void registerItemExtensions(RegisterClientExtensionsEvent event) {
        event.registerItem(new IClientItemExtensions() {
            @Override
            public BlockEntityWithoutLevelRenderer getCustomRenderer() {
                return StructureItemRenderer.instance();
            }
        }, RCRegistry.STRUCTURE_ITEM.get());
    }

    private StructureClientSetup() {
    }
}

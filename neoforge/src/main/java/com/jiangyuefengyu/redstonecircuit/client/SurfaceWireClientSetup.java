package com.jiangyuefengyu.redstonecircuit.client;

import java.util.List;

import com.jiangyuefengyu.redstonecircuit.RCRegistry;
import com.jiangyuefengyu.redstonecircuit.RedstoneCircuit;
import com.jiangyuefengyu.redstonecircuit.block.SurfaceWireBlock;

import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.ModelEvent;

/**
 * 红石粉（贴面）'s renderer and the strip models it draws, registered on the mod bus under {@code Dist.CLIENT} so a
 * dedicated server never loads them.
 */
@EventBusSubscriber(modid = RedstoneCircuit.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class SurfaceWireClientSetup {

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(RCRegistry.SURFACE_WIRE_ENTITY.get(), SurfaceWireRenderer::new);
    }

    /**
     * The 24 strip models are drawn by the block entity renderer, so no blockstate names them - and a model nothing
     * names is <b>never baked</b>, so asking for it returned the missing model (作者 2026-11-12 看到的无效贴图).
     * Registering them here puts them in the baking set under the standalone variant the renderer asks for.
     */
    @SubscribeEvent
    public static void registerStripModels(ModelEvent.RegisterAdditional event) {
        // Every dust that draws strips, asked of the block so a new dust cannot forget to be registered.
        for (SurfaceWireBlock sheet : List.of(RCRegistry.SURFACE_WIRE.get(),
                RCRegistry.SURFACE_SUPERCONDUCTING_WIRE.get())) {
            String prefix = sheet.stripPrefix();
            for (Direction face : Direction.values()) {
                for (int slot = 0; slot < 4; slot++) {
                    event.register(new ModelResourceLocation(
                            ResourceLocation.fromNamespaceAndPath(RedstoneCircuit.MODID,
                                    prefix + face.getName() + "_flat_" + slot),
                            ModelResourceLocation.STANDALONE_VARIANT));
                }
            }
        }
    }
}

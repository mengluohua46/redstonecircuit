package com.jiangyuefengyu.redstonecircuit.client;

import java.util.ArrayList;
import java.util.List;

import com.jiangyuefengyu.redstonecircuit.RCRegistry;
import com.jiangyuefengyu.redstonecircuit.RedstoneCircuit;
import com.jiangyuefengyu.redstonecircuit.block.SurfaceWireBlock;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.fabricmc.fabric.api.client.rendering.v1.BlockEntityRendererRegistry;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;

/**
 * 红石粉（贴面）'s renderer and the strip models it draws, registered on the client entrypoint so a
 * dedicated server never loads them.
 *
 * <h2>How it is registered on Fabric</h2>
 * NeoForge announced both halves on the mod bus, under {@code Dist.CLIENT}, and the class subscribed to them itself
 * ({@code EntityRenderersEvent.RegisterRenderers} and {@code ModelEvent.RegisterAdditional}). Fabric has no such
 * events: both are registries that have to be filled while the client is starting up, which is why {@link #register()}
 * is called from {@link RedstoneCircuitClient#onInitializeClient()}. That is also why this class is client-only,
 * hence {@link Environment}.
 */
@Environment(EnvType.CLIENT)
public final class SurfaceWireClientSetup {

    /** Fills both registries. Called once, from the client entrypoint. */
    public static void register() {
        registerRenderers();
        registerStripModels();
    }

    /** The block side: the sheet's strips are drawn by {@link SurfaceWireRenderer}. */
    public static void registerRenderers() {
        BlockEntityRendererRegistry.register(RCRegistry.SURFACE_WIRE_ENTITY, SurfaceWireRenderer::new);
    }

    /**
     * The 24 strip models are drawn by the block entity renderer, so no blockstate names them - and a model nothing
     * names is <b>never baked</b>, so asking for it returned the missing model (作者 2026-11-12 看到的无效贴图).
     * Registering them here puts them in the baking set, where {@link SurfaceWireRenderer} asks for them by id.
     */
    public static void registerStripModels() {
        ModelLoadingPlugin.register(context -> {
            List<ResourceLocation> models = new ArrayList<>();
            // Every dust that draws strips, asked of the block so a new dust cannot forget to be registered.
            for (SurfaceWireBlock sheet : List.of(RCRegistry.SURFACE_WIRE,
                    RCRegistry.SURFACE_SUPERCONDUCTING_WIRE)) {
                String prefix = sheet.stripPrefix();
                for (Direction face : Direction.values()) {
                    for (int slot = 0; slot < 4; slot++) {
                        models.add(ResourceLocation.fromNamespaceAndPath(RedstoneCircuit.MODID,
                                prefix + face.getName() + "_flat_" + slot));
                    }
                }
            }
            context.addModels(models);
        });
    }

    private SurfaceWireClientSetup() {
    }
}

package com.jiangyuefengyu.redstonecircuit.client;

import com.jiangyuefengyu.redstonecircuit.RCRegistry;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.rendering.v1.BlockEntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.BuiltinItemRendererRegistry;

/**
 * Tells the client how to draw a structure: a renderer for the placed block, and one for the item.
 *
 * <p>Client-only, so a dedicated server never loads this class. Both hooks are registries that have to be filled
 * while the client is starting up, which is why {@link #register()} is called from the client entrypoint instead
 * of the class subscribing to an event of its own. The world and the hand both go through
 * {@link StructurePreviewGeometry}, so a structure cannot look like two different objects.
 */
@Environment(EnvType.CLIENT)
public final class StructureClientSetup {

    /** Fills both registries. Called once, from the client entrypoint. */
    public static void register() {
        registerRenderers();
        registerItemExtensions();
    }

    /** The block side: a placed structure is drawn by {@link StructureBlockEntityRenderer}. */
    public static void registerRenderers() {
        BlockEntityRendererRegistry.register(RCRegistry.STRUCTURE_BLOCK_ENTITY,
                StructureBlockEntityRenderer::new);
    }

    /**
     * The item side: every display context of {@code redstonecircuit:structure} is drawn by
     * {@link StructureItemRenderer}, which is the inventory icon, the hotbar, the hand and the ground.
     *
     * <p>The custom-item-renderer extension of the NeoForge build has no counterpart on this loader. What Fabric
     * has instead is {@link BuiltinItemRendererRegistry}, and it takes a renderer as a callback rather than as a
     * class to extend - so the item is drawn by the one {@link StructureItemRenderer} through a lambda that keeps
     * its {@code renderByItem} the drawing entry point and needs no second copy of the drawing code. The registry
     * is asked for a renderer for every context the item appears in, and it is consulted at all only because the
     * item's model asks for it: {@code models/item/structure.json} parents {@code builtin/entity}.
     */
    public static void registerItemExtensions() {
        BuiltinItemRendererRegistry.INSTANCE.register(RCRegistry.STRUCTURE_ITEM,
                (stack, context, pose, buffers, light, overlay) ->
                        StructureItemRenderer.instance().renderByItem(stack, context, pose, buffers, light,
                                overlay));
    }

    private StructureClientSetup() {
    }
}

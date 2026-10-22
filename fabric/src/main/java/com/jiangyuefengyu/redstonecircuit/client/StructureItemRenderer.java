package com.jiangyuefengyu.redstonecircuit.client;

import java.util.List;

import com.jiangyuefengyu.redstonecircuit.RCRegistry;
import com.jiangyuefengyu.redstonecircuit.structure.StructurePreview;
import com.jiangyuefengyu.redstonecircuit.structure.StructureProductData;
import com.mojang.blaze3d.vertex.PoseStack;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * The structure in the player's hand - and, because this is the same code path, the structure in the inventory slot.
 *
 * <p>A placed structure draws itself from its block entity; an item has no block entity, so the whole description
 * travels in the item's data component, which is why the preview lives in {@link StructureProductData}. All this class
 * does is hand that preview to the same {@link StructurePreviewGeometry} the placed block uses, so a structure looks the
 * same in the hand and in the world and one mesh serves both - and the pose handed in is already the block-item pose,
 * because the item's model is the block's.
 *
 * <p>A structure whose preview is missing - an item from another save, or one whose payload could not be drawn - is the
 * design's "missing placeholder", and it must still be an object in the hand rather than an empty slot: the fallback
 * draws the product block's own model, i.e. the empty box.
 *
 * <p>The class is reached through {@code BuiltinItemRendererRegistry}, filled in {@link StructureClientSetup}: the
 * registry is asked for a renderer per item, and the item's model declaring {@code builtin/entity} is what sends
 * every display context here. The drawing below is unchanged by that - the object is still built on first use, and
 * still the only place a structure item is drawn.
 */
@Environment(EnvType.CLIENT)
public final class StructureItemRenderer extends BlockEntityWithoutLevelRenderer {

    /** The one instance every stack of this item shares; built on first use, because a client may not exist yet. */
    private static StructureItemRenderer instance;

    public static StructureItemRenderer instance() {
        if (instance == null) {
            Minecraft minecraft = Minecraft.getInstance();
            instance = new StructureItemRenderer(minecraft.getBlockEntityRenderDispatcher(),
                    minecraft.getEntityModels());
        }
        return instance;
    }

    private StructureItemRenderer(BlockEntityRenderDispatcher blockEntities, EntityModelSet models) {
        super(blockEntities, models);
    }

    /**
     * Where this stack is being drawn from, in the probe's vocabulary: fp-r for the hand, gui for a slot in a screen.
     *
     * <p>It is read from the context and not guessed from a counter, because this one class draws the stack in the
     * player's hand and in every slot of every screen - the difference between "the hand is expensive" and "the same
     * picture is drawn several times a frame" cannot be seen from inside the draw, and it is the whole question.
     */
    private static String site(ItemDisplayContext context) {
        return switch (context) {
            case FIRST_PERSON_RIGHT_HAND -> "fp-r";
            case FIRST_PERSON_LEFT_HAND -> "fp-l";
            case THIRD_PERSON_RIGHT_HAND -> "tp-r";
            case THIRD_PERSON_LEFT_HAND -> "tp-l";
            case GUI -> "gui";
            case GROUND -> "ground";
            case FIXED -> "fixed";
            case HEAD -> "head";
            case NONE -> "none";
        };
    }

    @Override
    public void renderByItem(ItemStack stack, ItemDisplayContext context, PoseStack pose,
                             MultiBufferSource buffers, int packedLight, int packedOverlay) {
        BlockRenderDispatcher dispatcher = Minecraft.getInstance().getBlockRenderer();
        StructureProductData data = RCRegistry.structureOf(stack);
        StructurePreview preview = data == null ? null : data.preview();
        Level level = Minecraft.getInstance().level;
        if (preview == null || preview.cellCount() == 0 || level == null) {
            // Nothing this description can draw: the empty box, so the item is still visible - the missing-placeholder case.
            dispatcher.renderSingleBlock(RCRegistry.STRUCTURE.defaultBlockState(), pose, buffers,
                    packedLight, packedOverlay);
            return;
        }
        StructurePreviewGeometry.Detail detail =
                StructurePreviewGeometry.effective(StructurePreviewGeometry.Detail.ICON);
        List<StructurePreviewGeometry.Cell> cells = StructurePreviewGeometry.cells(level, preview, detail);
        if (cells.isEmpty()) {
            dispatcher.renderSingleBlock(RCRegistry.STRUCTURE.defaultBlockState(), pose, buffers,
                    packedLight, packedOverlay);
            return;
        }
        // No moves here: an item is a picture of what the structure is, not of what it is doing - and no position
        // either, so the hand and the icon share the blueprint's picture with every placed structure whose cells are
        // not live.
        String site = site(context);
        StructurePreviewGeometry.draw(pose, buffers, cells, preview, dispatcher, null, 0L, 0.0F, null, null, site,
                detail);
        StructureRenderLog.drew(preview, cells.size(), site);
    }
}

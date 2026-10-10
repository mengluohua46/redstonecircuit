package com.jiangyuefengyu.redstonecircuit.client;

import java.util.List;

import com.jiangyuefengyu.redstonecircuit.block.StructureBlockEntity;
import com.jiangyuefengyu.redstonecircuit.structure.StructurePreview;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.level.Level;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * The view: a placed structure draws a miniature of the blocks it stands for, inside its own block.
 *
 * <p>The blocks themselves are in a pocket plot, where vanilla runs their redstone; what the player sees is the
 * structure's own {@link StructurePreview}, shrunk to fit - so a client with no access to the save's files, and a
 * structure in an unloaded chunk, still draw. What the miniature looks like and how the cache works is
 * {@link StructurePreviewGeometry}'s business; this class is the block entity end of it.
 */
@Environment(EnvType.CLIENT)
public final class StructureBlockEntityRenderer implements BlockEntityRenderer<StructureBlockEntity> {

    private final BlockRenderDispatcher dispatcher;

    public StructureBlockEntityRenderer(BlockEntityRendererProvider.Context context) {
        this.dispatcher = context.getBlockRenderDispatcher();
    }

    @Override
    public void render(StructureBlockEntity product, float partialTick, PoseStack pose, MultiBufferSource buffers,
                       int packedLight, int packedOverlay) {
        Level level = product.getLevel();
        StructurePreview preview = product.preview();
        if (level == null || preview == null || preview.cellCount() == 0) {
            // An empty product is invisible but still occludes, which reads as a hole in the world; the item renderer
            // draws the crate in exactly this case, so draw it here too.
            this.dispatcher.renderSingleBlock(product.getBlockState(), pose, buffers, packedLight, packedOverlay);
            return;
        }
        List<StructurePreviewGeometry.Cell> cells = StructurePreviewGeometry.cells(level, preview, product.getBlockPos());
        if (cells.isEmpty()) {
            this.dispatcher.renderSingleBlock(product.getBlockState(), pose, buffers, packedLight, packedOverlay);
            return;
        }
        StructurePreviewGeometry.draw(pose, buffers, cells, preview, this.dispatcher,
                StructureLiveViews.movesAt(product.getBlockPos()), level.getGameTime(), partialTick);
        StructureRenderLog.drew(preview, cells.size(), "inside the placed block");
    }
}

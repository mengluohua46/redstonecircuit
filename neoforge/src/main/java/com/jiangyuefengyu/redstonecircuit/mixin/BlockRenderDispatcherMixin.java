package com.jiangyuefengyu.redstonecircuit.mixin;

import com.jiangyuefengyu.redstonecircuit.client.ShaderCompat;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;

import net.neoforged.neoforge.client.model.data.ModelData;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Leaves the block out of the chunk mesh while a shader pack is drawing the overlay.
 * <p>A block's own cube is meshed first and writes depth, so geometry drawn inside it would never be seen; shader
 * packs pick their program from the render type's identity and drop unknown types, so with shaders on the overlay
 * draws the block itself (both sides) - which only works if the block is not in the mesh twice.
 * <p>{@code require = 0}: a future NeoForge reshaping must degrade the overlay, not crash startup.
 */
@Mixin(BlockRenderDispatcher.class)
public abstract class BlockRenderDispatcherMixin {

    @Inject(
            method = "renderBatched(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/BlockAndTintGetter;Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;ZLnet/minecraft/util/RandomSource;Lnet/neoforged/neoforge/client/model/data/ModelData;Lnet/minecraft/client/renderer/RenderType;)V",
            at = @At("HEAD"),
            cancellable = true,
            require = 0)
    private void redstonecircuit$skipDrawnHost(BlockState state, BlockPos pos, BlockAndTintGetter level,
                                               PoseStack poseStack, VertexConsumer consumer,
                                               boolean checkSides, RandomSource random, ModelData data,
                                               RenderType renderType, CallbackInfo callback) {
        if (ShaderCompat.hidesHostCube(pos)) {
            callback.cancel();
        }
    }
}

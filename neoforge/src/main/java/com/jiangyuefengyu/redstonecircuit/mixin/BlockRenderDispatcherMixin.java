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
 *
 * <h2>Why the block has to go</h2>
 * A component is drawn inside its block, and the block's own cube is meshed first and writes depth, so
 * geometry that honestly tested depth would never be seen. Without shaders that is solved by drawing
 * with a render type of our own that ignores the depth test - but shader packs choose their program,
 * and the vertex attributes their shaders expect, from the identity of the render type, so a type of
 * ours gets no lighting and no surface to be lit: the deferred packs simply drop it.
 *
 * <p>With shaders on, the overlay draws the block itself instead (both sides of it, so nothing behind
 * shows through) and the components with vanilla render types. That only works if the block is not in
 * the mesh twice, which is what this mixin is for: the position is looked up in the set of hosts the
 * overlay is drawing, and the call is cancelled when it is one of them.
 *
 * <p>This is the vanilla mesher's entry point - {@code SectionCompiler} calls it for every block, and
 * the shorter overload delegates here - so a replacement chunk renderer that does not route through it
 * keeps its own meshes and simply does not get the shader-friendly path.
 *
 * <p>{@code require = 0}: if a future NeoForge reshapes that method, the overlay should lose its
 * shader-friendly path, not crash the game on startup. The overlay keeps drawing components with its
 * own render type as well, so a mixin that does nothing degrades instead of disappearing.
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

package com.jiangyuefengyu.redstonecircuit.mixin;

import com.jiangyuefengyu.redstonecircuit.client.ShaderCompat;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Leaves the host block out of the chunk mesh, so the overlay can draw what is inside it.
 *
 * <h2>Why the block has to go</h2>
 * A component is drawn inside its block, and the block's own cube is meshed first and writes depth, so
 * geometry that honestly tested depth would never be seen. The overlay used to solve that by drawing
 * with a render type of its own that ignored the depth test, but shader packs choose their program -
 * and the vertex attributes their shaders expect - from the identity of the render type, so a type of
 * ours got no lighting and no surface to be lit, and the deferred packs simply dropped it. The shell,
 * which was already drawn with vanilla's own type, kept showing, which is exactly what "only the outer
 * frame is visible" looks like.
 *
 * <p>So the block leaves the mesh instead, and the overlay draws the block itself - inside out, so
 * nothing behind it shows through - with vanilla's own {@code RenderType.cutout()}. That is one path
 * for every player, with or without a shader pack, and no render type of our own anywhere.
 *
 * <p>This is the vanilla mesher's entry point: {@code SectionCompiler#compile} calls the seven
 * argument {@code renderBatched} for every non-fluid block in the section, which is confirmed by
 * disassembling the 1.21.1 client - the descriptor below is that call, not a guess. A replacement
 * chunk renderer that does not route through it keeps its own meshes; that is what the Sodium half of
 * this pair is for.
 *
 * <p>{@code require = 0}: if a future Minecraft reshapes that method, the overlay should lose its
 * drawn block rather than crash the game on startup - and {@code ShaderCompat} writes a warning when
 * no hook has run, so a silent failure of this kind still leaves a line in the log.
 */
@Mixin(BlockRenderDispatcher.class)
public abstract class BlockRenderDispatcherMixin {

    @Inject(
            method = "renderBatched(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/BlockAndTintGetter;Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;ZLnet/minecraft/util/RandomSource;)V",
            at = @At("HEAD"),
            cancellable = true,
            require = 0)
    private void redstonecircuit$skipDrawnHost(BlockState state, BlockPos pos, BlockAndTintGetter level,
                                               PoseStack poseStack, VertexConsumer consumer,
                                               boolean checkSides, RandomSource random,
                                               CallbackInfo callback) {
        // Unconditional and first, once per hook: this is the line that tells "the mixin was never
        // applied" apart from "it was applied and never matched a host". Nothing else in the log can.
        ShaderCompat.noteHookEntered("vanilla", pos);
        if (ShaderCompat.hidesHostCube(pos)) {
            ShaderCompat.noteHookUsed("vanilla");
            callback.cancel();
        }
    }
}

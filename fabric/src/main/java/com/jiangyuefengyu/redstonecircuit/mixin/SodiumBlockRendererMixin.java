package com.jiangyuefengyu.redstonecircuit.mixin;

import com.jiangyuefengyu.redstonecircuit.client.ShaderCompat;

import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The Sodium half of "leave the host block out of the chunk mesh".
 * <p>Sodium has its own chunk renderer and never calls vanilla's {@code BlockRenderDispatcher.renderBatched}, so the
 * vanilla hook does nothing for the players most likely to run a shader pack; its per-block entry point is
 * {@code BlockRenderer#renderModel}, which is handed the position, and that is what this cancels.
 * <p>The target is a string and the parameters are all vanilla types, so the descriptor needs no Sodium on the compile
 * classpath; whether the mixin is applied at all is decided by {@link SodiumMixinPlugin}.
 */
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.BlockRenderer")
public abstract class SodiumBlockRendererMixin {

    @Inject(
            method = "renderModel(Lnet/minecraft/client/resources/model/BakedModel;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/BlockPos;)V",
            at = @At("HEAD"),
            cancellable = true,
            require = 0)
    private void redstonecircuit$skipDrawnHost(BakedModel model, BlockState state, BlockPos pos,
                                               BlockPos origin, CallbackInfo callback) {
        // Sodium may hand in a section-local position while the set the overlay publishes is in world coordinates;
        // both are tried rather than guessing which - the wrong interpretation simply never matches anything.
        if (ShaderCompat.hidesHostCube(pos) || ShaderCompat.hidesHostCube(origin.offset(pos))) {
            ShaderCompat.noteHookUsed("sodium");
            callback.cancel();
        }
    }
}

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
 *
 * <h2>Why a second mixin</h2>
 * Sodium does not build its meshes through vanilla's {@code BlockRenderDispatcher.renderBatched} - it
 * has its own chunk renderer, and none of its classes reference that method at all - so the vanilla
 * hook does nothing for the people who are most likely to be running a shader pack in the first place.
 * Its per-block entry point is {@code BlockRenderer#renderModel}, which is handed the position, and
 * that is what this cancels.
 *
 * <h2>How it can name a class it does not depend on</h2>
 * The target is a string, not a class literal, and the four parameters are all vanilla types, so the
 * descriptor can be written without Sodium anywhere on the compile classpath. Whether the mixin is
 * applied at all is decided by {@link SodiumMixinPlugin}, which asks the loader's mod list - so a
 * player without Sodium never loads this class and never sees the target resolved.
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
        // Sodium bakes a section in its own frame, so the position handed in may be local to the
        // section while the set the overlay publishes is in world coordinates. Both are tried rather
        // than guessing which: the wrong interpretation simply never matches anything.
        if (ShaderCompat.hidesHostCube(pos) || ShaderCompat.hidesHostCube(origin.offset(pos))) {
            ShaderCompat.noteHookUsed("sodium");
            callback.cancel();
        }
    }
}

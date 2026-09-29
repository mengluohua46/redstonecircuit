package com.jiangyuefengyu.redstonecircuit.mixin;

import com.jiangyuefengyu.redstonecircuit.client.ShaderCompat;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A probe on Sodium's own chunk mesher: it logs and cancels nothing.
 *
 * <h2>What question it answers</h2>
 * "The Sodium hook did not run" has exactly two causes, and they look identical in a log that only
 * watches the hook itself: the injection point was never resolved, or Sodium's <em>own</em> mesher
 * never called it. {@link SodiumBlockRendererMixin} can only report the first of those, because it is
 * the thing under suspicion - if it never fires, it cannot say why.
 *
 * <p>This probe is deliberately somewhere else: {@code BlockRenderCache#getBlockRenderer()} is called
 * by {@code ChunkBuilderMeshingTask#execute} once per section bake, immediately before the per-block
 * loop that calls {@code BlockRenderer#renderModel} (both read out of the bytecode of
 * {@code sodium-fabric 0.8.13+mc1.21.1}). It has no arguments and its descriptor mentions no Minecraft
 * type, so there is nothing for a mapping to get wrong - it fires if, and only if, Sodium's mesher is
 * the thing building the chunk meshes.
 *
 * <p>In other words: this line present and the "mesh hook 'sodium' entered" line absent means the
 * injection point is the fault, not the render path - which is the one conclusion a real-machine run
 * has to be able to reach in a single pass.
 *
 * <h2>Why it does not cancel, and why {@code require = 0}</h2>
 * Nothing about it may change what is drawn, and nothing about a diagnosis may crash a player: an
 * unresolvable probe should cost a log line, not a startup. The build is where this has to be loud -
 * Loom's Mixin annotation processor resolves this target while compiling, and refuses the descriptor if
 * it is wrong.
 *
 * <h2>Why {@code remap = false} is correct here rather than lazy</h2>
 * This is the one mixin of the four that has nothing to translate: its target is not obfuscated and its
 * method spec names only Sodium types, so the string Mixin resolves is already the string the game
 * runs with, in a dev client and in a release alike. Saying so is also what keeps the annotation
 * processor honest - it refuses a {@code @Inject} spec whose descriptor contains no Minecraft type,
 * because such a spec yields no obfuscation mapping and it cannot tell "nothing needs remapping" from
 * "the reader made a typo". Here the difference is known, so it is stated.
 */
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.BlockRenderCache",
        remap = false)
public abstract class SodiumMesherProbeMixin {

    @Inject(
            method = "getBlockRenderer()Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/pipeline/BlockRenderer;",
            at = @At("HEAD"),
            require = 0)
    private void redstonecircuit$noteMesher(CallbackInfo callback) {
        ShaderCompat.noteSodiumMesher();
    }
}

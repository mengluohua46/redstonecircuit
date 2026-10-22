package com.jiangyuefengyu.redstonecircuit.mixin;

import com.jiangyuefengyu.redstonecircuit.client.ShaderCompat;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

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
 * <h2>Why the callback is a {@code CallbackInfoReturnable}, and why its type argument is {@code Object}</h2>
 * The target is not void: {@code javap} on {@code sodium-mc1.21.1-0.8.13-fabric} prints
 * {@code public BlockRenderer getBlockRenderer()}, so Mixin refuses a {@code CallbackInfo} parameter -
 * "CallbackInfoReturnable is required!" - and the whole mixin fails to apply, which is exactly what a
 * real-machine log showed before this line was corrected. {@code CallbackInjector} compares the
 * <em>erased</em> descriptor, so the type argument is documentation and nothing else; it is written as
 * {@code Object} rather than {@code BlockRenderer} because that keeps this file free of any Sodium
 * reference, which is the rule the other three Mixins in this package follow (the alternative is
 * {@code modCompileOnly}, i.e. a build that can no longer be compiled without Sodium).
 *
 * <h2>Why it does not cancel, and why {@code require = 0}</h2>
 * Nothing about it may change what is drawn, and nothing about a diagnosis may crash a player: an
 * unresolvable probe should cost a log line, not a startup. Nothing in the build catches this class of
 * mistake, and that is a measured fact rather than a worry: release 1.1.1 was compiled and packaged -
 * {@code BUILD SUCCESSFUL}, with the {@code CallbackInfo} parameter this file used to declare - and the
 * error surfaced only on a real machine, when Mixin applied the mixin at startup:
 * {@code InvalidInjectionException ... CallbackInfoReturnable is required!}. So the compiler does not
 * hold a callback parameter against the method it is injected into, and
 * {@code SodiumMesherProbeInjectionTest} is the guard that does.
 *
 * <h2>Why {@code remap = false} is correct here rather than lazy</h2>
 * This is the one mixin of the four that has nothing to translate: its target is not obfuscated and its
 * method spec names only Sodium types, so the string Mixin resolves is already the string the game
 * runs with, in a dev client and in a release alike: {@code net.caffeinemc.mods.sodium...} is Sodium's
 * own package and {@code getBlockRenderer} is its own method name, and no mapping touches either.
 *
 * <p>What Loom's Mixin annotation processor makes of a {@code @Inject} spec that names no Minecraft
 * type is <b>not verified here</b> and is therefore not claimed. The one thing this build does know
 * about that processor is the paragraph above: it passed a callback whose type was wrong.
 */
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.BlockRenderCache",
        remap = false)
public abstract class SodiumMesherProbeMixin {

    @Inject(
            method = "getBlockRenderer()Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/pipeline/BlockRenderer;",
            at = @At("HEAD"),
            require = 0)
    private void redstonecircuit$noteMesher(CallbackInfoReturnable<Object> callback) {
        ShaderCompat.noteSodiumMesher();
    }
}

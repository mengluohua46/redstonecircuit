package com.jiangyuefengyu.redstonecircuit.mixin.client;

import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.jiangyuefengyu.redstonecircuit.RCRegistry;
import com.jiangyuefengyu.redstonecircuit.block.HoneyPistonBlock;
import com.jiangyuefengyu.redstonecircuit.block.HoneyPistonHeadBlock;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.PistonHeadRenderer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 蜂蜜活塞's two moving frames, drawn with 蜂蜜活塞's own head instead of vanilla's.
 *
 * <p>The piston animation is not in {@code PistonMovingBlockEntity} - it is here, redrawn every frame from the block
 * being carried. Vanilla asks two questions of one field:
 *
 * <ul>
 *   <li>line 39 {@code movedState.is(Blocks.PISTON_HEAD)} picks the "arm shrinks as it slides out" branch, which only
 *       ever recognises vanilla's head - so ours slides out as a plain block, with no arm at all;
 *   <li>line 44 {@code Blocks.PISTON_HEAD.defaultBlockState()} builds the arm drawn <b>inside the base's cell</b>
 *       during a retraction, and it builds vanilla's head - so a honey piston retracting shows vanilla's arm for the
 *       ~100 ms of the pull, which at redstone-clock speed is a permanent flicker.
 * </ul>
 *
 * <p>Both are the same field read, so both are answered the same way: the frame records the state it is drawing, and
 * each read substitutes our head <b>only</b> when that state belongs to 蜂蜜活塞 - every vanilla piston, 粘性活塞
 * included, gets {@code Blocks.PISTON_HEAD} back and is drawn exactly as before.
 *
 * <p>Why a per-frame slot instead of the moving entity: a field {@code @Redirect} handler receives nothing but the
 * field's own value, and Mixin's parameter capture for redirects lives in MixinExtras, which NeoForge ships jarjar'd
 * inside its own jar and therefore does not put on the compile classpath (verified: no
 * {@code WrapOperation.class} entry in moddev's neoforge jars, and MixinExtras is absent from the game test server's
 * legacy classpath). The one thing that can be read without leaving plain Mixin is the argument of
 * {@code render}, so the frame remembers it. One entry is written per call before either read runs, and it is
 * written by the render thread alone.
 */
@Mixin(PistonHeadRenderer.class)
public abstract class PistonHeadRendererMixin {

    /** The one method that draws a moving piston - named in full because the interface also produces a bridge. */
    private static final String RENDER =
            "render(Lnet/minecraft/world/level/block/piston/PistonMovingBlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;"
                    + "Lnet/minecraft/client/renderer/MultiBufferSource;II)V";

    private static final String VANILLA_HEAD =
            "Lnet/minecraft/world/level/block/Blocks;PISTON_HEAD:Lnet/minecraft/world/level/block/Block;";

    /**
     * What this frame is drawing, when it is 蜂蜜活塞's - {@code null} for every other block, which is what keeps the
     * vanilla branch untouched.
     */
    @Unique
    private static final ThreadLocal<BlockState> DRAWING = new ThreadLocal<>();

    @Inject(method = RENDER, at = @At("HEAD"))
    private void redstonecircuit$rememberWhatThisFrameDraws(PistonMovingBlockEntity moving, float partialTick,
            PoseStack pose, MultiBufferSource buffers, int packedLight, int packedOverlay, CallbackInfo callback) {
        BlockState moved = moving.getMovedState();
        boolean ours = moved.getBlock() instanceof HoneyPistonBlock || moved.getBlock() instanceof HoneyPistonHeadBlock;
        DRAWING.set(ours ? moved : null);
    }

    /**
     * Line 39: whether the state being drawn is a head, i.e. whether the arm shrinks as the extension slides out. Ours
     * is a head too, so it answers for itself; vanilla's head and everything else answer through the untouched value.
     */
    @Redirect(method = RENDER,
            at = @At(value = "FIELD", opcode = Opcodes.GETSTATIC, target = VANILLA_HEAD, ordinal = 0))
    private Block redstonecircuit$isTheHeadSlidingOut() {
        BlockState moved = DRAWING.get();
        return moved != null && moved.getBlock() instanceof HoneyPistonHeadBlock
                ? RCRegistry.HONEY_PISTON_HEAD.get()
                : Blocks.PISTON_HEAD;
    }

    /**
     * Line 44: the arm drawn in the base's own cell while it retracts. Vanilla builds vanilla's head there; a honey
     * piston's retraction has to build ours, with the same TYPE/FACING/SHORT set on it straight afterwards (our head
     * inherits all three, so the chain there needs no change either).
     */
    @Redirect(method = RENDER,
            at = @At(value = "FIELD", opcode = Opcodes.GETSTATIC, target = VANILLA_HEAD, ordinal = 1))
    private Block redstonecircuit$headTheBaseCellDraws() {
        BlockState moved = DRAWING.get();
        return moved != null && moved.getBlock() instanceof HoneyPistonBlock
                ? RCRegistry.HONEY_PISTON_HEAD.get()
                : Blocks.PISTON_HEAD;
    }
}

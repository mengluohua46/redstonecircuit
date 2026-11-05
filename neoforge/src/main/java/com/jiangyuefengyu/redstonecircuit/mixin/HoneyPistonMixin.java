package com.jiangyuefengyu.redstonecircuit.mixin;

import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.jiangyuefengyu.redstonecircuit.RCRegistry;
import com.jiangyuefengyu.redstonecircuit.block.HoneyPistonBlock;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 蜂蜜活塞's three questions, asked of vanilla's piston in four places - and not one line of the movement itself.
 *
 * <p>Everything this mod's piston does differently lives inside methods it cannot override - {@code moveBlocks} is
 * private, {@code isSticky} is private final and set at construction - so the questions with a per-block answer are
 * answered here instead: <b>which head</b> an extension leaves and how a retraction recognises it (one field, read
 * twice), <b>whether a retraction drags</b> (vanilla reads a boolean there, this asks the block state), and <b>whether
 * a retracted one may be pushed</b> (vanilla answers that only for its own two blocks). The two retractions, the drag,
 * the twelve-block limit and the carried blocks stay vanilla's.
 *
 * <p>Every handler below is reached only for a {@link HoneyPistonBlock}: every other piston gets its own value back.
 */
@Mixin(PistonBaseBlock.class)
public abstract class HoneyPistonMixin {

    @Shadow @Final private boolean isSticky;

    /**
     * The head recognition in a retraction: vanilla clears the block in front of the piston when it <b>is</b> its head,
     * and that {@code is()} comparison names {@code Blocks.PISTON_HEAD} - which is not the block this piston left there,
     * so the head would survive as an obstacle and the drag would resolve against it instead of moving it out of the way.
     */
    @Redirect(
            method = "moveBlocks",
            at = @At(value = "FIELD", opcode = Opcodes.GETSTATIC,
                    target = "Lnet/minecraft/world/level/block/Blocks;PISTON_HEAD:Lnet/minecraft/world/level/block/Block;",
                    ordinal = 0))
    private Block redstonecircuit$recogniseHoneyHead() {
        return (Object) this instanceof HoneyPistonBlock ? RCRegistry.HONEY_PISTON_HEAD.get() : Blocks.PISTON_HEAD;
    }

    /**
     * The head itself: the one place in {@code moveBlocks} where a head state is built, and the state is then handed to
     * the moving-block entity that carries it out and puts it down. Ours inherits {@code FACING} and {@code TYPE}, which
     * vanilla sets on it straight after, so nothing else here has to know the block changed.
     */
    @Redirect(
            method = "moveBlocks",
            at = @At(value = "FIELD", opcode = Opcodes.GETSTATIC,
                    target = "Lnet/minecraft/world/level/block/Blocks;PISTON_HEAD:Lnet/minecraft/world/level/block/Block;",
                    ordinal = 1))
    private Block redstonecircuit$honeyHead() {
        return (Object) this instanceof HoneyPistonBlock ? RCRegistry.HONEY_PISTON_HEAD.get() : Blocks.PISTON_HEAD;
    }

    /**
     * Whether the retraction about to run drags a block back.
     *
     * <p>The second read of {@code isSticky} in {@code triggerEvent} is the {@code if} that chooses between vanilla's
     * two retractions; the first builds the {@code MOVING_PISTON} flavour and is left alone. 蜂蜜活塞 answers with the
     * bit it recorded when it extended, and every other piston - 粘性活塞 included - gets {@code isSticky} as it was.
     */
    @Redirect(
            method = "triggerEvent",
            at = @At(value = "FIELD", opcode = Opcodes.GETFIELD,
                    target = "Lnet/minecraft/world/level/block/piston/PistonBaseBlock;isSticky:Z",
                    ordinal = 1))
    private boolean redstonecircuit$dragOnlyAfterReachingIntoAir(PistonBaseBlock piston) {
        Boolean drag = HoneyPistonBlock.drag(piston);
        return drag != null ? drag : this.isSticky;
    }

    /**
     * Whether this block may be shoved by another piston, which vanilla answers for its own two pistons and nobody
     * else's: a retracted one travels, an extended one is what its head is attached to.
     *
     * <p>The bounds and the world border are checked here as well, because cancelling at HEAD skips vanilla's own copy
     * of them - the one place in this mixin where the answer is not simply "our block, our rule".
     */
    @Inject(method = "isPushable", at = @At("HEAD"), cancellable = true)
    private static void redstonecircuit$honeyPistonTravelsWhileRetracted(BlockState state, Level level, BlockPos pos,
            Direction direction, boolean allowDestroy, Direction pushDirection,
            CallbackInfoReturnable<Boolean> callback) {
        if (!(state.getBlock() instanceof HoneyPistonBlock)) {
            return;
        }
        boolean outOfTheWorld = pos.getY() < level.getMinBuildHeight() || pos.getY() > level.getMaxBuildHeight() - 1
                || !level.getWorldBorder().isWithinBounds(pos);
        callback.setReturnValue(!outOfTheWorld && !state.getValue(HoneyPistonBlock.EXTENDED) && !state.hasBlockEntity());
    }
}

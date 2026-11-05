package com.jiangyuefengyu.redstonecircuit.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.jiangyuefengyu.redstonecircuit.logic.PistonHostTransfer;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.piston.PistonBaseBlock;

/**
 * Brackets the one piston method that actually moves blocks, so a <b>resolve</b> can be told apart from a <b>move</b>.
 *
 * <p>Vanilla resolves twice per push: {@code checkIfExtend} resolves a structure as a yes/no question about whether the
 * push is possible at all, and only {@code moveBlocks} resolves the one it then carries out. Both go through the same
 * {@code PistonStructureResolver#resolve}, so a hook on the resolver alone cannot tell them apart - and treating the
 * question as a move shifted every component one cell for nothing, twice per push, which is how a long push lost the
 * components nearest the piston. This flag is what the resolver hook asks.
 */
@Mixin(PistonBaseBlock.class)
public abstract class PistonBaseBlockMixin {

    @Inject(method = "moveBlocks", at = @At("HEAD"))
    private void redstonecircuit$beforeMove(Level level, BlockPos pos, Direction direction, boolean extending,
                                            CallbackInfoReturnable<Boolean> callback) {
        PistonHostTransfer.enteredMove();
    }

    @Inject(method = "moveBlocks", at = @At("RETURN"))
    private void redstonecircuit$afterMove(Level level, BlockPos pos, Direction direction, boolean extending,
                                           CallbackInfoReturnable<Boolean> callback) {
        PistonHostTransfer.leftMove();
    }
}

package com.jiangyuefengyu.redstonecircuit.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.jiangyuefengyu.redstonecircuit.block.SurfaceWireBlock;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 左键打掉**一层**：一格上挂了几个面，就按你对着的那个面拆一次，掉一颗。
 *
 * <p>原版一次拆掉整格，没有「只拆一层」的位置；这里在 {@code handleBlockBreakAction} 的 START 上先接手：
 * 多面的粉就拆掉被指着的那一层并取消原版流程，单面的粉一行不动（照常挖掉整格）。拿东西右键拆一层是同一个
 * 入口（{@code SurfaceWireBlock#removeFace}），两条路行为一致。
 */
@Mixin(ServerPlayerGameMode.class)
public abstract class SurfaceWireMiningMixin {

    @Shadow
    @Final
    private ServerLevel level;

    @Inject(method = "handleBlockBreakAction", at = @At("HEAD"), cancellable = true)
    private void redstonecircuit$breakOneFace(BlockPos pos, ServerboundPlayerActionPacket.Action action,
                                              Direction face, int maxBuildHeight, int sequence,
                                              CallbackInfo callback) {
        if (action != ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK) {
            return;
        }
        BlockState state = this.level.getBlockState(pos);
        if (!(state.getBlock() instanceof SurfaceWireBlock) || SurfaceWireBlock.faces(state).size() < 2) {
            return;
        }
        if (SurfaceWireBlock.removeFace(this.level, pos, state, face.getOpposite())) {
            this.level.levelEvent(null, 2001, pos, Block.getId(state));
            callback.cancel();
        }
    }
}

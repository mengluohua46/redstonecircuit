package com.jiangyuefengyu.redstonecircuit.mixin;

import com.jiangyuefengyu.redstonecircuit.blueprint.BlueprintDeploy;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The write point: a schematic block has just landed, at a position that is already the world's.
 *
 * <p>Every path that puts a blueprint block into a world ends here - the instant build, a cannon's projectile that
 * arrived, and the deferred second pass some blocks need - so one hook catches all three without knowing which one it
 * is serving. The position is handed in as an argument, which is the whole reason the transform happened at load time
 * instead: nothing is transformed here, one map is asked.
 *
 * <p>{@code require = 0} as everywhere in this package: the injection point is another mod's internal method.
 */
@Mixin(targets = "com.simibubi.create.foundation.utility.BlockHelper")
public abstract class CreateSchematicPlaceMixin {

    @Inject(method = "placeSchematicBlock", at = @At("TAIL"), require = 0)
    private static void redstonecircuit$settle(Level level, BlockState state, BlockPos pos, ItemStack stack,
                                               CompoundTag blockEntityData, CallbackInfo callback) {
        BlueprintDeploy.blockLanded(level, pos);
    }
}

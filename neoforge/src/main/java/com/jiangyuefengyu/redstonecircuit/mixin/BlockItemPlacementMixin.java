package com.jiangyuefengyu.redstonecircuit.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.jiangyuefengyu.redstonecircuit.block.SurfaceRepeaterPlacement;
import com.jiangyuefengyu.redstonecircuit.block.SurfacePressurePlatePlacement;
import com.jiangyuefengyu.redstonecircuit.block.SurfaceTorchPlacement;
import com.jiangyuefengyu.redstonecircuit.block.SurfaceSuperconductingPlacement;
import com.jiangyuefengyu.redstonecircuit.block.SurfaceTriggerPlacement;
import com.jiangyuefengyu.redstonecircuit.block.SurfaceVariableRepeaterPlacement;
import com.jiangyuefengyu.redstonecircuit.block.SurfaceWirePlacement;

import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.UseOnContext;

/**
 * Gives vanilla's repeater item a second placement, without touching the first one.
 *
 * <p>Vanilla's repeaters need a floor: every face except the top one is a click its own code cannot answer. Those
 * clicks are exactly what {@link SurfaceRepeaterPlacement} takes over, and a click on the top face - the one vanilla
 * does handle - is left completely alone: nothing is cancelled, nothing is rewritten, the original method runs as it
 * always did. See {@code SurfaceRepeaterBlock}.
 */
@Mixin(BlockItem.class)
public abstract class BlockItemPlacementMixin {

    @Inject(method = "useOn", at = @At("HEAD"), cancellable = true)
    private void redstonecircuit$placeSurfaceRepeater(UseOnContext context,
                                                      CallbackInfoReturnable<InteractionResult> callback) {
        InteractionResult placed = SurfaceRepeaterPlacement.tryPlace(context);
        if (placed == null) {
            // The same rule for vanilla's wire: a click on a face a wire cannot lie on becomes a sheet on that face.
            placed = SurfaceWirePlacement.tryPlace(context);
        }
        if (placed == null) {
            // And for vanilla's torch: the bottom face first, which vanilla's torch has no form for at all.
            placed = SurfaceTorchPlacement.tryPlace(context);
        }
        if (placed == null) {
            // The pressure plate: every face but the top one.
            placed = SurfacePressurePlatePlacement.tryPlace(context);
        }
        if (placed == null) {
            // And the superconducting dust's own sheet, on the same rule as vanilla's: every face but the top one.
            placed = SurfaceSuperconductingPlacement.tryPlace(context);
        }
        if (placed == null) {
            // This mod's own three repeaters, on the same rule again: the three items, their three twins.
            placed = SurfaceVariableRepeaterPlacement.tryPlace(context);
        }
        if (placed == null) {
            // And 红石触发器: the four side faces, which its floor form refuses outright.
            placed = SurfaceTriggerPlacement.tryPlace(context);
        }
        if (placed != null) {
            callback.setReturnValue(placed);
        }
    }
}

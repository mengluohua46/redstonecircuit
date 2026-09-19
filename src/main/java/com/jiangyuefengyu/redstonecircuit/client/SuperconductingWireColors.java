package com.jiangyuefengyu.redstonecircuit.client;

import com.jiangyuefengyu.redstonecircuit.RCRegistry;
import com.jiangyuefengyu.redstonecircuit.RedstoneCircuit;
import com.jiangyuefengyu.redstonecircuit.block.SuperconductingWireBlock;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;

/**
 * Tints the placed superconducting wire orange.
 *
 * <h2>Why a tint and not a texture</h2>
 * Redstone wire is drawn with a greyscale dust texture and coloured by strength in code - vanilla
 * registers its own handler for exactly that - so recolouring it means registering one, not drawing new
 * art. The same ramp the goggles draw with is used here
 * ({@link InnerComponentModel#superconductorColor}), so the wire on the ground and the wire inside a
 * block are the same colour at the same strength, which matters because they are the same material.
 */
@EventBusSubscriber(modid = RedstoneCircuit.MODID, value = Dist.CLIENT)
public final class SuperconductingWireColors {

    private SuperconductingWireColors() {
    }

    @SubscribeEvent
    public static void onRegisterBlockColors(RegisterColorHandlersEvent.Block event) {
        event.register((state, level, pos, tintIndex) -> InnerComponentModel.superconductorColor(
                state.getValue(SuperconductingWireBlock.POWER)), RCRegistry.SUPERCONDUCTING_WIRE.get());
    }
}

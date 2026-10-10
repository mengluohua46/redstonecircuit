package com.jiangyuefengyu.redstonecircuit.client;

import com.jiangyuefengyu.redstonecircuit.RCRegistry;
import com.jiangyuefengyu.redstonecircuit.RedstoneCircuit;
import com.jiangyuefengyu.redstonecircuit.block.SuperconductingWireBlock;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;

/**
 * Brightens the placed superconducting wire as its signal rises.
 * <p>Vanilla's wire art is greyscale and its red comes entirely from a tint applied in code; the superconductor's art
 * is that same art multiplied by orange, so the colour is already in the texture and the tint only says how bright.
 * <p>{@code RegisterColorHandlersEvent} is a <b>mod bus</b> event: subscribing on the game bus leaves the handler
 * registered nowhere, and a wire with no tint at all is not merely duller - it looks wrong in every state.
 */
@EventBusSubscriber(modid = RedstoneCircuit.MODID, value = Dist.CLIENT,
        bus = EventBusSubscriber.Bus.MOD)
public final class SuperconductingWireColors {

    private SuperconductingWireColors() {
    }

    @SubscribeEvent
    public static void onRegisterBlockColors(RegisterColorHandlersEvent.Block event) {
        event.register((state, level, pos, tintIndex) -> InnerComponentModel.wireBrightness(
                state.getValue(SuperconductingWireBlock.POWER)), RCRegistry.SUPERCONDUCTING_WIRE.get());
    }
}

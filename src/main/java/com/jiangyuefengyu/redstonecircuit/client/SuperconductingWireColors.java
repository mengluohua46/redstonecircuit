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
 *
 * <h2>Why only brightness, when vanilla tints the whole colour</h2>
 * Vanilla's wire art is greyscale and its red comes entirely from a tint applied in code. The
 * superconductor's art is that same art multiplied by orange, so the colour is already in the texture -
 * which also means a placed wire is unmistakably orange even before this handler runs, and the tint
 * only has to say how bright it is.
 *
 * <h2>Why the mod bus</h2>
 * {@code RegisterColorHandlersEvent} is a <b>mod bus</b> event. Subscribing to the game bus - which is
 * what this did at first - leaves the handler registered nowhere, and a wire with no tint at all is not
 * merely duller: with none of vanilla's red applied it looks wrong in every state. The handler is
 * therefore declared with {@code bus = Bus.MOD}, and the block also carries its colour in its art.
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

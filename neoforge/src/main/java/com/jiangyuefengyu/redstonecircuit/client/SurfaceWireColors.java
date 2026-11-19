package com.jiangyuefengyu.redstonecircuit.client;

import com.jiangyuefengyu.redstonecircuit.RCRegistry;
import com.jiangyuefengyu.redstonecircuit.RedstoneCircuit;
import com.jiangyuefengyu.redstonecircuit.block.SurfaceWireBlock;

import net.minecraft.world.level.block.RedStoneWireBlock;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;

/**
 * 红石粉（贴面） gets vanilla's wire colour for its strength.
 *
 * <p>Vanilla's wire art is greyscale and all of its red comes from a tint applied in code, so a sheet with no tint
 * registered is not merely duller - it has no colour at all. The rule is vanilla's own function, not a copy of it.
 */
@EventBusSubscriber(modid = RedstoneCircuit.MODID, value = Dist.CLIENT,
        bus = EventBusSubscriber.Bus.MOD)
public final class SurfaceWireColors {

    private SurfaceWireColors() {
    }

    @SubscribeEvent
    public static void onRegisterBlockColors(RegisterColorHandlersEvent.Block event) {
        event.register((state, level, pos, tintIndex) -> tintIndex < 0
                        ? -1
                        : RedStoneWireBlock.getColorForPower(state.getValue(SurfaceWireBlock.POWER)),
                RCRegistry.SURFACE_WIRE.get());
    }
}

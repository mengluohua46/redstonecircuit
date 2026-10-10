package com.jiangyuefengyu.redstonecircuit.compat.physics;

import com.jiangyuefengyu.redstonecircuit.RedstoneCircuit;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;

/**
 * Compat work that has to wait until the registries are full.
 * <p>Everything else in this package is armed from the mod constructor, which only needs the mod list. The product's
 * carrier registration is the exception: it hands Create's registries <em>our own block</em>, which does not exist yet
 * then, so the hook must run after registration rather than null-check - a registration that silently did not happen
 * would look exactly like a block that could not be carried.
 * <p>The event bus is the mod bus, and the call is gated inside {@code PhysicsCompat} so nothing Create-specific loads.
 */
@EventBusSubscriber(modid = RedstoneCircuit.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class StructureCompatSetup {

    @SubscribeEvent
    public static void onCommonSetup(FMLCommonSetupEvent event) {
        com.jiangyuefengyu.redstonecircuit.compat.physics.PhysicsCompat.installProductCarrier();
    }

    private StructureCompatSetup() {
    }
}

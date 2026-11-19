package com.jiangyuefengyu.redstonecircuit.client;

import com.jiangyuefengyu.redstonecircuit.RCRegistry;
import com.jiangyuefengyu.redstonecircuit.block.SurfaceWireBlock;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.rendering.v1.ColorProviderRegistry;
import net.minecraft.world.level.block.RedStoneWireBlock;

/**
 * 红石粉（贴面） gets vanilla's wire colour for its strength.
 *
 * <p>Vanilla's wire art is greyscale and all of its red comes from a tint applied in code, so a sheet with no tint
 * registered is not merely duller - it has no colour at all. The rule is vanilla's own function, not a copy of it.
 *
 * <h2>How it is registered on Fabric</h2>
 * NeoForge announced a <b>mod-bus</b> event that this class could subscribe to itself. Fabric has no such
 * event: the tint goes into a registry that has to be filled while the client is starting up, so
 * {@link #register()} is called from {@link RedstoneCircuitClient#onInitializeClient()}. That is also why
 * this class is client-only, hence {@link Environment}.
 */
@Environment(EnvType.CLIENT)
public final class SurfaceWireColors {

    private SurfaceWireColors() {
    }

    /** Fills the block colour registry. Called once, from the client entrypoint. */
    public static void register() {
        ColorProviderRegistry.BLOCK.register((state, level, pos, tintIndex) -> tintIndex < 0
                        ? -1
                        : RedStoneWireBlock.getColorForPower(state.getValue(SurfaceWireBlock.POWER)),
                RCRegistry.SURFACE_WIRE);
    }
}

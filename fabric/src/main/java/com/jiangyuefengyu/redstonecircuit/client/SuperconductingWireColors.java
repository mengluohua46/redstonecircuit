package com.jiangyuefengyu.redstonecircuit.client;

import com.jiangyuefengyu.redstonecircuit.RCRegistry;
import com.jiangyuefengyu.redstonecircuit.block.SuperconductingWireBlock;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.rendering.v1.ColorProviderRegistry;

/**
 * Brightens the placed superconducting wire as its signal rises.
 *
 * <h2>Why only brightness, when vanilla tints the whole colour</h2>
 * Vanilla's wire art is greyscale and its red comes entirely from a tint applied in code. The
 * superconductor's art is that same art multiplied by orange, so the colour is already in the texture -
 * which also means a placed wire is unmistakably orange even before this handler runs, and the tint
 * only has to say how bright it is.
 *
 * <h2>How it is registered on Fabric</h2>
 * NeoForge announced a <b>mod-bus</b> event ({@code RegisterColorHandlersEvent.Block}) that this class
 * could subscribe to itself. Fabric has no such event: the tint goes into a registry that has to be
 * filled while the client is starting up, so {@link #register()} is called from
 * {@link RedstoneCircuitClient#onInitializeClient()}. That is also why this class is client-only, hence
 * {@link Environment}.
 *
 * <p>Registering it in the wrong place fails in a way that is easy to misread: a wire with no tint at
 * all is not merely duller. With none of vanilla's red applied it looks wrong in every state - which is
 * what happened on NeoForge when the handler was on the game bus instead of the mod bus. The block also
 * carries its colour in its own art, so the failure is "duller than intended", not "invisible".
 */
@Environment(EnvType.CLIENT)
public final class SuperconductingWireColors {

    private SuperconductingWireColors() {
    }

    /** Fills the block colour registry. Called once, from the client entrypoint. */
    public static void register() {
        ColorProviderRegistry.BLOCK.register((state, level, pos, tintIndex) ->
                        InnerComponentModel.wireBrightness(state.getValue(SuperconductingWireBlock.POWER)),
                RCRegistry.SUPERCONDUCTING_WIRE);
    }
}

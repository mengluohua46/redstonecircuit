package com.jiangyuefengyu.redstonecircuit;

import com.jiangyuefengyu.redstonecircuit.logic.InnerRedstoneTickHandler;
import com.jiangyuefengyu.redstonecircuit.logic.WrenchSelection;
import com.jiangyuefengyu.redstonecircuit.network.HostSync;
import com.jiangyuefengyu.redstonecircuit.network.RCNetwork;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.resources.ResourceLocation;

/**
 * 《红石电路》 - put redstone components inside blocks, then look at them with redstone goggles.
 *
 * <p>Target: Minecraft 1.21.1 / Fabric Loader 0.16.5 / Fabric API 0.102.0.
 *
 * <h2>What changed from the NeoForge build</h2>
 * NeoForge drove everything from its two event buses, so one {@code @Mod} constructor could subscribe
 * the registry, the network, the game events and the command in one place. Fabric has no bus: every
 * hook is a callback that has to be registered from an entrypoint, and the ones a dedicated server must
 * not touch live in {@link com.jiangyuefengyu.redstonecircuit.client.RedstoneCircuitClient} instead.
 * The routing is therefore:
 *
 * <ul>
 *   <li>here (common entrypoint): registry, payload types, block interaction, server tick, server
 *       lifecycle, player connection, command;</li>
 *   <li>{@code RedstoneCircuitClient}: the overlay pass, the wire tint, the client receivers and the
 *       client-side click gate.</li>
 * </ul>
 *
 * <p>The two share nothing but the classes they call, which is exactly the split Fabric asks for.
 */
public final class RedstoneCircuit implements ModInitializer {

    public static final String MODID = "redstonecircuit";

    @Override
    public void onInitialize() {
        // Blocks, items, the armour material and the creative tab. Static registration, so this only
        // has to run before the registries freeze.
        RCRegistry.register();

        // The three client-bound payloads. Only their types: a dedicated server never handles one.
        RCNetwork.register();

        // Everything below is server-authoritative: placement, retrieval, switches and the host sync.
        InnerRedstoneInteraction.register();
        InnerRedstoneTickHandler.register();
        HostSync.register();

        // A wrench selection is a half-finished gesture, not data: it is dropped when the player leaves.
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                WrenchSelection.forget(handler.getPlayer()));

        // Queued redstone work is per level, so it is dropped when the server goes.
        InnerRedstoneTickHandler.registerServerStopped();

        // Debug/ops command: /rc dump|place|connect|solve|clear|list|rules
        CommandRegistrationCallback.EVENT.register(
                (dispatcher, registryAccess, environment) -> RCCommand.register(dispatcher));
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MODID, path);
    }
}

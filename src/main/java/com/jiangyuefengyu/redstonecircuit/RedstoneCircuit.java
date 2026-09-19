package com.jiangyuefengyu.redstonecircuit;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import net.minecraft.resources.ResourceLocation;

/**
 * 《红石电路》 - put redstone components inside blocks, then look at them with redstone goggles.
 *
 * <p>Target: Minecraft 1.21.1 / NeoForge 21.1.250.
 */
@Mod(RedstoneCircuit.MODID)
public final class RedstoneCircuit {

    public static final String MODID = "redstonecircuit";

    public RedstoneCircuit(IEventBus modEventBus, ModContainer modContainer) {
        RCRegistry.register(modEventBus);

        // Everything a client needs to be told about the server's inner redstone: which blocks hold a
        // component, what is inside them, and which block the player's wrench picked.
        modEventBus.addListener(com.jiangyuefengyu.redstonecircuit.network.RCNetwork::register);

        // Everything below is server-authoritative: placement, retrieval and the host sync.
        // Game events therefore go on the NeoForge bus.
        NeoForge.EVENT_BUS.register(new InnerRedstoneInteraction());
        NeoForge.EVENT_BUS.register(new com.jiangyuefengyu.redstonecircuit.logic.InnerRedstoneTickHandler());

        // Debug/ops command: /rc dump|place|connect|solve|clear|list|rules
        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);

        modContainer.registerConfig(ModConfig.Type.COMMON, RCConfig.SPEC);
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        RCCommand.register(event.getDispatcher());
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MODID, path);
    }
}

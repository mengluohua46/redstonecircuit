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

        // Everything a client has to be told about the server's inner redstone: hosts, contents, the wrench's pick.
        modEventBus.addListener(com.jiangyuefengyu.redstonecircuit.network.RCNetwork::register);

        // Everything below is server-authoritative - placement, retrieval, the host sync - so game events go on the
        // NeoForge bus.
        NeoForge.EVENT_BUS.register(new InnerRedstoneInteraction());
        // A block a structure is CARRYING is broken and its component has to drop. A listener of its own rather than
        // a branch inside the one above, so the world's break path stays as it was; it does nothing for a break that
        // is not about a carried host. See CarrierBlockDrops.
        NeoForge.EVENT_BUS.register(new CarrierBlockDrops());
        // The wrench and the rotation rod on a host a structure is CARRYING, for the same reason: this listener exits
        // on its first statement for every click that is not about a carried host, so the world's use path sees
        // exactly the events it always saw. See CarrierInteraction.
        NeoForge.EVENT_BUS.register(new com.jiangyuefengyu.redstonecircuit.carrier.CarrierInteraction());
        NeoForge.EVENT_BUS.register(new com.jiangyuefengyu.redstonecircuit.logic.InnerRedstoneTickHandler());

        // The pocket dimension (红石结构方块): mirrors the overworld's time and weather into it, and drops the plot
        // table when a server stops; it returns on its first statement for every level that is not the pocket.
        NeoForge.EVENT_BUS.register(
                new com.jiangyuefengyu.redstonecircuit.structure.pocket.PocketTickHandler());

        // A release writes its budget of blocks per level tick: 48³ in one tick would freeze the server.
        NeoForge.EVENT_BUS.register(
                new com.jiangyuefengyu.redstonecircuit.structure.StructureReleaseTickHandler());

        // Debug/ops command: /rc dump|place|connect|solve|clear|list|rules
        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);

        // Optional compatibility with block-moving physics mods (Sable / Create / Create Aeronautics), which are
        // compileOnly, so their jars are absent unless the player installed them. This checks the mod list before any
        // mod-specific class loads, so "installed" and "not installed" behave identically.
        com.jiangyuefengyu.redstonecircuit.compat.physics.PhysicsCompat.init();

        modContainer.registerConfig(ModConfig.Type.COMMON, RCConfig.SPEC);
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        RCCommand.register(event.getDispatcher());
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MODID, path);
    }
}

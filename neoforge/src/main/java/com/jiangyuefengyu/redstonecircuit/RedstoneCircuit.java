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
        // P1.19 defect one: a block a structure is CARRYING is broken and its component has to drop.
        // A listener of its own rather than a branch inside the one above, so the world's break path
        // stays byte-for-byte what it was; it does nothing at all for a break that is not about a
        // carried host. See CarrierBlockDrops.
        NeoForge.EVENT_BUS.register(new CarrierBlockDrops());
        // P1.20 ability three: the wrench and the rotation rod, on a host a structure is CARRYING. A
        // listener of its own for the same reason as the one above, and a stronger one here: P1.18 put
        // this branch inside the world's shared handler and had to be withdrawn whole (a lever that
        // could not be pulled, components that stopped dropping, a shutdown loop). This listener exits
        // on its first statement for every click that is not about a carried host, so the world's use
        // path sees exactly the events it always saw. See CarrierInteraction.
        NeoForge.EVENT_BUS.register(new com.jiangyuefengyu.redstonecircuit.carrier.CarrierInteraction());
        NeoForge.EVENT_BUS.register(new com.jiangyuefengyu.redstonecircuit.logic.InnerRedstoneTickHandler());

        // Debug/ops command: /rc dump|place|connect|solve|clear|list|rules
        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);

        // Optional compatibility with block-moving physics mods (Sable / Create / Create Aeronautics).
        // Sable and Create are compileOnly, so their jars are absent unless the player installed them.
        // This call is the one and only thing the rest of the mod knows about them: it checks the mod
        // list first and only then lets a mod-specific class load, so "installed" and "not installed"
        // behave identically. See com.jiangyuefengyu.redstonecircuit.compat.physics.
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

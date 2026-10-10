package com.jiangyuefengyu.redstonecircuit;

import com.jiangyuefengyu.redstonecircuit.logic.InnerRedstoneTickHandler;
import com.jiangyuefengyu.redstonecircuit.logic.WrenchSelection;
import com.jiangyuefengyu.redstonecircuit.network.HostSync;
import com.jiangyuefengyu.redstonecircuit.network.RCNetwork;
import com.jiangyuefengyu.redstonecircuit.network.StructureManualTrigger;
import com.jiangyuefengyu.redstonecircuit.network.StructurePreviews;
import com.jiangyuefengyu.redstonecircuit.structure.StructureReleaseTickHandler;
import com.jiangyuefengyu.redstonecircuit.structure.pocket.PocketTickHandler;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;

/**
 * 《红石电路》 - put redstone components inside blocks, then look at them with redstone goggles.
 *
 * <p>Target: Minecraft 1.21.1 / Fabric Loader / Fabric API.
 *
 * <h2>What changed from the NeoForge build</h2>
 * NeoForge drove everything from its two event buses, so one {@code @Mod} constructor could subscribe
 * the registry, the network, the game events and the command in one place. Fabric has no bus: every
 * hook is a callback that has to be registered from an entrypoint, and the ones a dedicated server must
 * not touch live in {@link com.jiangyuefengyu.redstonecircuit.client.RedstoneCircuitClient} instead.
 * The routing is therefore:
 *
 * <ul>
 *   <li>here (common entrypoint): registry, payload types and their server-side receivers, block
 *       interaction, the carried-interaction half, server tick, server lifecycle, player connection,
 *       the structure previews and the command;</li>
 *   <li>{@code RedstoneCircuitClient}: the overlay pass, the wire tint, the client receivers and the
 *       client-side click gate.</li>
 * </ul>
 *
 * <p>The two share nothing but the classes they call, which is exactly the split Fabric asks for. Each
 * module registers its own hooks, so this method reads as a list of the mod's parts rather than as the
 * one place every event is named.
 */
public final class RedstoneCircuit implements ModInitializer {

    public static final String MODID = "redstonecircuit";

    @Override
    public void onInitialize() {
        // Blocks, the block entities, the structure item's component, the armour material and the
        // creative tab. Static registration, so this only has to run before the registries freeze.
        RCRegistry.register();

        // The payload types, on both sides, and the receivers for the four a client sends.
        RCNetwork.register();
        RCNetwork.registerServerReceivers();

        // Everything below is server-authoritative: placement, retrieval, switches, the host sync and
        // the clicks that belong to a component riding a moving structure.
        InnerRedstoneInteraction.register();
        com.jiangyuefengyu.redstonecircuit.carrier.CarrierInteraction.register();

        // A block a structure is CARRYING is broken and its component has to drop. A callback of its own
        // rather than a branch inside the one above, so the world's break path stays as it was; it does
        // nothing for a break that is not about a carried host. See CarrierBlockDrops.
        CarrierBlockDrops.register();

        // The per-level tick (queued solves, the devices that answer to the world clock, the inputs that
        // never announce themselves) and the sweep that drops all of it when the server stops.
        InnerRedstoneTickHandler.register();
        InnerRedstoneTickHandler.registerServerStopped();

        // Which blocks hide redstone, and what is inside them: sent on arrival, then on every change.
        HostSync.register();

        // The wrench's pick, echoed to its owner so the client can frame the block; one tick per level and
        // one disconnect sweep, both registered by the class itself.
        WrenchSelection.register();

        // The structure block: every preview border, told to whoever may have missed a broadcast, and the
        // manual panel's per-player rate limit.
        StructurePreviews.register();
        StructureManualTrigger.register();

        // A release writes its budget of blocks per level tick: 48³ in one tick would freeze the server.
        StructureReleaseTickHandler.register();

        // The pocket dimension: mirrors the overworld's time and weather into it, and drops the plot table
        // when a server stops; it returns on its first statement for every level that is not the pocket.
        PocketTickHandler.register();

        // Debug/ops command: /rc dump|place|connect|solve|clear|list|rules
        CommandRegistrationCallback.EVENT.register(
                (dispatcher, registryAccess, environment) -> RCCommand.register(dispatcher));

        // Optional compatibility with block-moving physics mods (Sable / Create / Create Aeronautics). The
        // half that names their types is not part of this build, so this finds no backend and every entry
        // point below answers "not installed" without loading a mod-specific class.
        com.jiangyuefengyu.redstonecircuit.compat.physics.PhysicsCompat.init();
    }

    public static net.minecraft.resources.ResourceLocation id(String path) {
        return net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(MODID, path);
    }
}

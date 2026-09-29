package com.jiangyuefengyu.redstonecircuit.client;

import com.jiangyuefengyu.redstonecircuit.network.RCNetwork;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;

/**
 * The client half of the Fabric entrypoint, named in {@code fabric.mod.json}.
 *
 * <p>NeoForge registered client behaviour from the mod class through its mod bus, so the port needs this
 * separate entrypoint: on Fabric the client-only hooks are callbacks, and this is where they are
 * registered. A dedicated server never loads this class, which is the point - everything below touches
 * {@code net.minecraft.client}.
 *
 * <h2>What is registered here</h2>
 * <ul>
 *   <li>{@code WorldRenderEvents.AFTER_ENTITIES} - the overlay pass (the block's revealed inside, the
 *       component in it, and the frame around it). This replaces NeoForge's {@code RenderLevelStageEvent}
 *       at its {@code AFTER_ENTITIES} stage: the callback hands over the same pose stack and camera,
 *       which is why {@link HostRenderPass} only had to change its signature.</li>
 *   <li>{@code ColorProviderRegistry.BLOCK} - the superconducting wire's tint, which brightens it as
 *       its signal rises. NeoForge did this from a mod-bus event; Fabric has a registry for it, and it
 *       has to be filled while the client is initialising, before any chunk is baked.</li>
 *   <li>The three server-to-client receivers. The payload <em>types</em> are registered in
 *       {@code RCNetwork.register} on both sides; the handling is registered only here, because the
 *       caches they write to are client-only.</li>
 *   <li>{@code ClientPlayConnectionEvents.DISCONNECT} - dropping every cached host when the player
 *       leaves the world, so the next world does not start with the previous one's blocks.</li>
 * </ul>
 *
 * <h2>What is deliberately not here</h2>
 * {@code ClientTickEvents.END_CLIENT_TICK} was expected to carry the "do not predict this placement"
 * guard. It turned out not to need a tick at all: Fabric's {@code UseBlockCallback} runs on the client
 * <em>inside</em> the interaction, before the local prediction, so the guard answers the same question
 * the server asks, there and then, and returns {@code SUCCESS} to skip the prediction while still
 * sending the packet. See {@link ClientPlacementGuard} and {@code InnerRedstoneInteraction}.
 */
@Environment(EnvType.CLIENT)
public class RedstoneCircuitClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        // The overlay: one pass per frame, gated on the goggles.
        WorldRenderEvents.AFTER_ENTITIES.register(HostRenderPass::render);

        // The wire tint. Registered from the class that owns the handler, so the "why this and not a
        // render type of our own" notes stay next to the code they explain.
        SuperconductingWireColors.register();

        // The client half of the host sync. Both receivers already run on the client thread: Fabric
        // dispatches play payloads through Minecraft#execute, so no extra hop is needed.
        ClientPlayNetworking.registerGlobalReceiver(RCNetwork.HostSnapshotPayload.TYPE,
                (payload, context) ->
                        ClientHostCache.applySnapshot(payload.dimension(), payload.entries()));
        ClientPlayNetworking.registerGlobalReceiver(RCNetwork.HostSlotPayload.TYPE,
                (payload, context) ->
                        ClientHostCache.applyChanges(payload.dimension(), payload.entries()));
        ClientPlayNetworking.registerGlobalReceiver(RCNetwork.WrenchSelectionPayload.TYPE,
                (payload, context) -> WrenchClientState.setSelection(
                        payload.hasSelection() ? payload.pos() : null));

        // A new world starts with nothing cached; the server's snapshot will fill it in.
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            ClientHostCache.clear();
            WrenchClientState.clear();
        });
    }
}

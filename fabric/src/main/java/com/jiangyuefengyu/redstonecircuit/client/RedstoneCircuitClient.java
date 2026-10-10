package com.jiangyuefengyu.redstonecircuit.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.jiangyuefengyu.redstonecircuit.carrier.CarrierId;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierIndex;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierPose;
import com.jiangyuefengyu.redstonecircuit.logic.ShiftKey;
import com.jiangyuefengyu.redstonecircuit.network.RCNetwork;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.screens.Screen;

/**
 * The client half of the Fabric entrypoint, named in {@code fabric.mod.json}.
 *
 * <p>NeoForge registered client behaviour from the mod class through its mod bus, so the port needs this
 * separate entrypoint: on Fabric the client-only hooks are callbacks, and this is where they are
 * registered. A dedicated server never loads this class, which is the point - everything below touches
 * {@code net.minecraft.client}.
 *
 * <h2>What is registered here</h2>
 * Three kinds of thing, in three groups:
 *
 * <ul>
 *   <li><b>Renderers and their passes</b>, each registered by the class that owns it, so the "why this
 *       stage" and "why this render type" notes stay next to the code they explain:
 *       {@link HostRenderPass#register} (the overlay pass, the carried pass and the client tick that
 *       ages carrier poses), {@link StructurePreviewRenderer#register} (the preview borders),
 *       {@link StructureClientSetup#register} (the structure block's block-entity renderer and the
 *       structure item's own renderer) and {@link SuperconductingWireColors#register} (the wire tint,
 *       which has to be filled while the client is initialising, before any chunk is baked).</li>
 *   <li><b>The server-to-client receivers.</b> The payload <em>types</em> are registered in
 *       {@code RCNetwork.register} on both sides; the handling is registered only here, because the
 *       caches they write to are client-only - and a dedicated server must not even load those classes.
 *       Three of the eleven receivers install themselves from the class that handles them
 *       ({@link DeviceScreens#register}, {@link LogicIntegratorScreens#register},
 *       {@link StructureScreens#register}); the other eight are named below, where the whole list of
 *       what a client is told can be read at once.</li>
 *   <li><b>Leaving the world</b>: every cache is dropped when the connection ends, so the next world does
 *       not start with the previous one's blocks, borders or boxes.</li>
 * </ul>
 *
 * <p>Fabric dispatches a play payload on the client thread, so no receiver here needs a hop of its own.
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
        // The overlay: one pass per frame, gated on the goggles, plus the carried pass and the tick that
        // ages every carrier pose. Registered from the class that draws, so the whole picture is described
        // in one place.
        HostRenderPass.register();

        // The wire tint, and the two renderers the structure block needs (its own block entity, and the
        // structure as an item).
        SuperconductingWireColors.register();
        StructureClientSetup.register();
        StructurePreviewRenderer.register();

        // The one thing the shared half cannot ask for itself: whether [Shift] is held, which the tooltips
        // read. Published to a plain boolean once per client tick - see ShiftKey for why not the tooltip flag.
        ClientTickEvents.END_CLIENT_TICK.register(client -> ShiftKey.set(Screen.hasShiftDown()));

        // The receiver that belongs to the preview border's own cache, and the one that drops it when the
        // player leaves.
        StructurePreviewClient.register();

        // The three screens that answer a payload by opening themselves. They register their own receivers
        // because the opening call is theirs; registering one twice would be silently dropped by Fabric's
        // receiver registry rather than reported, so each of them owns exactly one.
        DeviceScreens.register();
        LogicIntegratorScreens.register();
        StructureScreens.register();

        // ---- the host sync: which blocks hide redstone, and what is inside them ----
        //
        // A snapshot carries a whole dimension (sent on arrival, because that is the moment the client has
        // nothing), a slot update carries the positions that changed (the common case: a wire steps, a
        // repeater relays, a lever is thrown), and a wrench selection carries only what one player's own
        // client is meant to frame.
        ClientPlayNetworking.registerGlobalReceiver(RCNetwork.HostSnapshotPayload.TYPE,
                (payload, context) ->
                        ClientHostCache.applySnapshot(payload.dimension(), payload.entries()));
        ClientPlayNetworking.registerGlobalReceiver(RCNetwork.HostSlotPayload.TYPE,
                (payload, context) ->
                        ClientHostCache.applyChanges(payload.dimension(), payload.entries()));
        ClientPlayNetworking.registerGlobalReceiver(RCNetwork.WrenchSelectionPayload.TYPE,
                (payload, context) -> WrenchClientState.setSelection(
                        payload.hasSelection() ? payload.pos() : null, payload.liveHost(),
                        payload.carried(), payload.carriedLocal()));

        // ---- components riding a moving structure: one structure's pose and contents, or all of them ----
        ClientPlayNetworking.registerGlobalReceiver(RCNetwork.CarrierPayload.TYPE,
                (payload, context) -> ClientHostCache.applyCarrier(payload.dimension(),
                        payload.carrier(), payload.pose(), payload.hosts(), payload.removed()));
        ClientPlayNetworking.registerGlobalReceiver(RCNetwork.CarrierSnapshotPayload.TYPE,
                (payload, context) -> {
                    // A snapshot is a list of the same per-carrier messages, so it is folded into the two
                    // shapes the cache takes: every host in one list, and every pose by its structure. A
                    // carrier with no pose has gone, and its hosts are not added either.
                    List<CarrierIndex.CarriedView> hosts = new ArrayList<>();
                    Map<CarrierId, CarrierPose> poses = new HashMap<>();
                    for (RCNetwork.CarrierPayload carrier : payload.carriers()) {
                        if (carrier.pose() != null) {
                            poses.put(carrier.carrier(), carrier.pose());
                        }
                        hosts.addAll(carrier.hosts());
                    }
                    ClientHostCache.applyCarrierSnapshot(payload.dimension(), hosts, poses);
                });

        // ---- the structure block's live view: which cells are doing what, and which are mid-move ----
        ClientPlayNetworking.registerGlobalReceiver(RCNetwork.StructureLivePayload.TYPE,
                (payload, context) -> StructureLiveViews.apply(payload.pos(), payload.indices(),
                        payload.blockIds(), payload.restart()));
        ClientPlayNetworking.registerGlobalReceiver(RCNetwork.StructureMovePayload.TYPE,
                (payload, context) -> StructureLiveViews.applyMoves(payload.pos(), payload.indices(),
                        payload.states(), payload.from()));
        // The preview borders. One message carries a whole dimension's list, so the cache is replaced
        // rather than edited - see StructurePreviewClient. A receiver is easy to leave out here, which is
        // why ClientReceiversWiringTest checks every client-bound payload against this file.
        ClientPlayNetworking.registerGlobalReceiver(RCNetwork.PreviewSnapshotPayload.TYPE,
                (payload, context) -> StructurePreviewClient.accept(payload.dimension(), payload.entries()));

        // A new world starts with nothing cached; the server's snapshot will fill it in. The live views and
        // the screens' remembered pages go with it: both describe blocks of the world that has just been
        // left, and a stale live view from a previous world would keep drawing.
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            ClientHostCache.clear();
            WrenchClientState.clear();
            StructureLiveViews.clear();
            StructureScreenMemory.clear();
        });
    }
}

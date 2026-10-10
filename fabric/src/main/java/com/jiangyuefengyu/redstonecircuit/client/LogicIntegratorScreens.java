package com.jiangyuefengyu.redstonecircuit.client;

import com.jiangyuefengyu.redstonecircuit.network.RCNetwork;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;

/**
 * Opening and feeding 逻辑整合器's screen, on the client only.
 * <p>{@link #show}'s signature mentions no client-only type, so the Minecraft types stay in a class a dedicated server
 * never loads; otherwise the verifier would resolve {@code Screen} and the server would refuse to start the mod.
 * <p>One message type does both jobs: {@code open} means "start showing this", without it the message answers an edit
 * or a refresh; a screen that is not open drops it.
 */
@Environment(EnvType.CLIENT)
public final class LogicIntegratorScreens {

    /**
     * Connects the logic view message to {@link #show}.
     *
     * <p>Fabric registers a payload's <em>type</em> once, on both sides, and its <em>handling</em> only where the
     * message is received. The handling therefore belongs here rather than in the shared network class: a dedicated
     * server never loads this class, and never has an opinion about opening a screen. Called once, at client startup.
     *
     * <p>This is the only receiver for {@link RCNetwork.LogicViewPayload}: registering a second one would be a second
     * answer to the same message.
     */
    public static void register() {
        ClientPlayNetworking.registerGlobalReceiver(RCNetwork.LogicViewPayload.TYPE,
                (payload, context) -> show(payload));
    }

    /** Shows, or updates, the screen for the block this message is about. */
    public static void show(RCNetwork.LogicViewPayload message) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof LogicIntegratorScreen showing && showing.shows(message.pos())) {
            showing.accept(message);
            return;
        }
        if (message.open()) {
            minecraft.setScreen(new LogicIntegratorScreen(message));
        }
    }

    private LogicIntegratorScreens() {
    }
}

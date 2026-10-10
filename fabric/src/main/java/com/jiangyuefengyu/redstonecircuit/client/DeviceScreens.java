package com.jiangyuefengyu.redstonecircuit.client;

import com.jiangyuefengyu.redstonecircuit.logic.DeviceKind;
import com.jiangyuefengyu.redstonecircuit.network.RCNetwork;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;

/**
 * Opening and feeding a device's control screen, on the client only.
 * <p>{@link #show}'s signature mentions no client-only type, so the Minecraft types stay in a class a dedicated server
 * never loads; otherwise the verifier would resolve {@code Screen} and the server would refuse to start the mod.
 * <p>One message type does both jobs: {@code open} means "start showing this", without it the message answers an edit
 * or a refresh; a screen that is not open drops it. The kind picks the screen, an already-open one takes it first.
 */
@Environment(EnvType.CLIENT)
public final class DeviceScreens {

    /**
     * Connects the device view message to {@link #show}.
     *
     * <p>Fabric registers a payload's <em>type</em> once, on both sides, and its <em>handling</em> only on the side
     * that receives it. The handling belongs here rather than in the shared network class, which runs on a dedicated
     * server too: that side has no devices screen to open. Called once, at client startup.
     *
     * <p>This is the only receiver for {@link RCNetwork.DeviceViewPayload}: a device answer is handed to exactly one
     * screen, so a second receiver would be a second opinion about what the player is looking at.
     */
    public static void register() {
        ClientPlayNetworking.registerGlobalReceiver(RCNetwork.DeviceViewPayload.TYPE,
                (payload, context) -> show(payload));
    }

    /** Shows, or updates, the screen for the device this message is about. */
    public static void show(RCNetwork.DeviceViewPayload message) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof ControllerScreen showing && showing.shows(message.pos())) {
            showing.accept(message);
            return;
        }
        if (minecraft.screen instanceof TransformerScreen showing && showing.shows(message.pos())) {
            showing.accept(message);
            return;
        }
        if (minecraft.screen instanceof ClockScreen showing && showing.shows(message.pos())) {
            showing.accept(message);
            return;
        }
        if (minecraft.screen instanceof ExtenderScreen showing && showing.shows(message.pos())) {
            showing.accept(message);
            return;
        }
        if (!message.open()) {
            return;
        }
        // One arm per device that has a screen.
        DeviceKind kind = DeviceKind.byOrdinal(message.kind());
        if (kind == DeviceKind.CONTROLLER) {
            minecraft.setScreen(new ControllerScreen(message));
        } else if (kind == DeviceKind.TRANSFORMER) {
            minecraft.setScreen(new TransformerScreen(message));
        } else if (kind == DeviceKind.CLOCK) {
            minecraft.setScreen(new ClockScreen(message));
        } else if (kind == DeviceKind.EXTENDER) {
            minecraft.setScreen(new ExtenderScreen(message));
        }
    }

    private DeviceScreens() {
    }
}

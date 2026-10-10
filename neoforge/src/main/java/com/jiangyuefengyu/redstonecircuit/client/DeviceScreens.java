package com.jiangyuefengyu.redstonecircuit.client;

import com.jiangyuefengyu.redstonecircuit.logic.DeviceKind;
import com.jiangyuefengyu.redstonecircuit.network.RCNetwork;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Opening and feeding a device's control screen, on the client only.
 * <p>{@link #show}'s signature mentions no client-only type, so the Minecraft types stay in a class a dedicated server
 * never loads; otherwise the verifier would resolve {@code Screen} and the server would refuse to start the mod.
 * <p>One message type does both jobs: {@code open} means "start showing this", without it the message answers an edit
 * or a refresh; a screen that is not open drops it. The kind picks the screen, an already-open one takes it first.
 */
@OnlyIn(Dist.CLIENT)
public final class DeviceScreens {

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

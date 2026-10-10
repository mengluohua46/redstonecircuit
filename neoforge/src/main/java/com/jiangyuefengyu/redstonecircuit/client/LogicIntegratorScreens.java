package com.jiangyuefengyu.redstonecircuit.client;

import com.jiangyuefengyu.redstonecircuit.network.RCNetwork;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Opening and feeding 逻辑整合器's screen, on the client only.
 * <p>{@link #show}'s signature mentions no client-only type, so the Minecraft types stay in a class a dedicated server
 * never loads; otherwise the verifier would resolve {@code Screen} and the server would refuse to start the mod.
 * <p>One message type does both jobs: {@code open} means "start showing this", without it the message answers an edit
 * or a refresh; a screen that is not open drops it.
 */
@OnlyIn(Dist.CLIENT)
public final class LogicIntegratorScreens {

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

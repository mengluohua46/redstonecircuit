package com.jiangyuefengyu.redstonecircuit.client;

import com.jiangyuefengyu.redstonecircuit.network.RCNetwork;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;

/**
 * Opening the controller's screen, on the client only.
 *
 * <p>A {@code level.isClientSide} branch inside the block class is not enough: a GameTest server refused to start the
 * mod, because {@code Minecraft.getInstance().setScreen(new StructureBlockScreen(pos))} makes the <b>verifier</b> check
 * that the new object is acceptable to {@code setScreen}, and that check resolves {@code StructureBlockScreen} and with
 * it its client-only superclass {@code Screen}, which a dedicated server cannot load. A plain {@code invokestatic} to a
 * class of ours is different, and that is the difference this class keeps: the common side names {@link #open}, whose
 * signature mentions nothing client-only, and the Minecraft types stay in here.
 *
 * <p>So the sequence is: right-click while sneaking, the <b>server</b> answers "open your editor" with a payload, and
 * this method runs on that client. The server decides, the client draws.
 */
@Environment(EnvType.CLIENT)
public final class StructureScreens {

    /**
     * Connects this class to the two moments the client has to notice: the message that asks for a screen, and the
     * disconnect that takes every screen of this world away.
     *
     * <p>Fabric registers a payload's <em>type</em> once, on both sides, and its <em>handling</em> only on the side that
     * receives it, so the receiver belongs here rather than in the shared network class. The disconnect is a callback of
     * its own on Fabric rather than a subscribable logout event, which is why the two are installed together: both are
     * "what this client does about this world", and both have to be gone with it. Called once, at client startup.
     *
     * <p>This is the only receiver for {@link RCNetwork.OpenStructureScreenPayload}: the message names one block, and two
     * receivers would be two screens for it.
     */
    public static void register() {
        ClientPlayNetworking.registerGlobalReceiver(RCNetwork.OpenStructureScreenPayload.TYPE,
                (payload, context) -> open(payload));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> onDisconnect());
    }

    /**
     * Opens the screen for whatever stands at {@code pos}: the editor for a controller, the structure's own screen for
     * a placed structure. One payload and one branch, because the server's side of both is the same sentence, and the
     * client is the only side that knows what kind of block it turned out to be.
     */
    public static void open(RCNetwork.OpenStructureScreenPayload message) {
        Minecraft minecraft = Minecraft.getInstance();
        boolean placed = minecraft.level != null
                && minecraft.level.getBlockEntity(message.pos())
                        instanceof com.jiangyuefengyu.redstonecircuit.block.StructureBlockEntity;
        minecraft.setScreen(placed ? new StructureProductScreen(message.pos())
                : new StructureBlockScreen(message.pos(), message.structuresPath(), message.exportPath()));
    }

    /**
     * Nothing of ours survives a disconnect: a border drawn for a world that is not loaded is exactly the kind of "is
     * this a bug?" a player would be right to report.
     */
    private static void onDisconnect() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof StructureBlockScreen) {
            minecraft.setScreen(null);
        }
        // The screen's own memory goes with the world it belonged to (see StructureScreenMemory).
        StructureScreenMemory.clear();
    }

    private StructureScreens() {
    }
}

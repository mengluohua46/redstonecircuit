package com.jiangyuefengyu.redstonecircuit.client;

import com.jiangyuefengyu.redstonecircuit.RedstoneCircuit;
import com.jiangyuefengyu.redstonecircuit.network.RCNetwork;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;

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
@OnlyIn(Dist.CLIENT)
@EventBusSubscriber(modid = RedstoneCircuit.MODID, value = Dist.CLIENT)
public final class StructureScreens {

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
    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
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

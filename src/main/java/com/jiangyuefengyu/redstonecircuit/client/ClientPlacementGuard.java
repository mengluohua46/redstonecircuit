package com.jiangyuefengyu.redstonecircuit.client;

import com.jiangyuefengyu.redstonecircuit.HostRules;
import com.jiangyuefengyu.redstonecircuit.RedstoneCircuit;
import com.jiangyuefengyu.redstonecircuit.data.Slot;

import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * Stops the client predicting a block placement for a click the mod is about to intercept.
 *
 * <h2>The problem this solves</h2>
 * Clicking a block runs on the client first: {@code MultiPlayerGameMode} calls {@code ItemStack#useOn}
 * locally and only then waits for the server's answer, so that placing a block feels instant. When the
 * mod stores the held item <em>inside</em> the block instead, that local placement is a lie: the
 * redstone (or repeater, torch, ...) is drawn as a freshly placed block in the world for one round
 * trip, and then taken away again. That is the red flash that used to appear on the ground beside the
 * block being filled.
 *
 * <p>Nothing short of not predicting it removes the flash - a corrective block update from the server
 * would arrive a round trip later, which is exactly how long the flash lasts. So this asks the same
 * question the server asks, using {@link HostRules#claimsClick}, and cancels the click locally when the
 * answer is "ours".
 *
 * <h2>Why cancelling does not break the interaction</h2>
 * The packet is sent either way: NeoForge runs this event inside the action whose result is handed back
 * to be sent as {@code ServerboundUseItemOnPacket}, so the server still receives the click and still
 * stores the component. Answering {@link InteractionResult#SUCCESS} rather than the default also makes
 * the client swing the arm and stop there, instead of falling through to the off hand.
 *
 * <p>Answering "ours" when the server disagrees would cost one round trip before the block appears,
 * which is a far smaller artefact than the flash this prevents.
 */
@EventBusSubscriber(modid = RedstoneCircuit.MODID, value = Dist.CLIENT)
public final class ClientPlacementGuard {

    private ClientPlacementGuard() {
    }

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        Level level = event.getLevel();
        if (!level.isClientSide()) {
            return;
        }
        Player player = event.getEntity();
        ItemStack stack = player.getItemInHand(event.getHand());
        // The client's copy of "what is inside this block", which is what the server's store holds a
        // round trip later. A click that turns out not to be ours is simply not cancelled.
        Slot existing = ClientHostCache.slotAt(level.dimension(), event.getPos());
        if (!HostRules.claimsClick(level.getBlockState(event.getPos()), existing, stack,
                player.isSecondaryUseActive())) {
            return;
        }
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
    }
}

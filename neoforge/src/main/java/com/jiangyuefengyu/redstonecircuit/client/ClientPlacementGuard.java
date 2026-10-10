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
 * <p>Clicking a block runs on the client first, so when the mod stores the held item <em>inside</em> the block instead,
 * that local placement is a lie: the component is drawn as a freshly placed block for one round trip and then taken
 * away again. Nothing short of not predicting it removes that flash - a corrective block update would arrive a round
 * trip later, exactly how long the flash lasts - so this asks the same question the server asks
 * ({@link HostRules#claimsClick}) and cancels the click when the answer is "ours".
 *
 * <p>The packet is sent either way, because NeoForge runs this event inside the action whose result becomes
 * {@code ServerboundUseItemOnPacket}, and answering {@link InteractionResult#SUCCESS} makes the client swing the arm
 * and stop there rather than falling through to the off hand.
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

package com.jiangyuefengyu.redstonecircuit.client;

import com.jiangyuefengyu.redstonecircuit.HostRules;
import com.jiangyuefengyu.redstonecircuit.data.Slot;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;

/**
 * The client half of {@code InnerRedstoneInteraction}'s right-click: decides whether the client should
 * predict anything for this click.
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
 * question the server asks, using {@link HostRules#claimsClick}, and answers {@code SUCCESS} locally
 * when the answer is "ours".
 *
 * <h2>Why answering here does not break the interaction</h2>
 * Fabric's {@code UseBlockCallback} is invoked inside {@code MultiPlayerGameMode#interactBlock}, before
 * the local prediction and before the packet is written - and returning {@code SUCCESS} makes it send
 * {@code ServerboundUseItemOnPacket} anyway while skipping the prediction. So the server still receives
 * the click and still stores the component, and the client simply stops lying about what happened.
 *
 * <p>Answering "ours" when the server disagrees would cost one round trip before the block appears,
 * which is a far smaller artefact than the flash this prevents.
 *
 * <h2>What the two sides can each see</h2>
 * The block state, the held stack, whether the player is sneaking and this side's copy of what is
 * inside the block - for the client, {@link ClientHostCache}, which the server keeps up to date. The
 * one thing only the server knows is whether it already has a component at this position that the
 * client has not been told about yet; that case costs the client one prediction, and is the same
 * limitation the NeoForge build had.
 */
@Environment(EnvType.CLIENT)
public final class ClientPlacementGuard {

    private ClientPlacementGuard() {
    }

    /**
     * Whether this click is the mod's, from the client's point of view.
     *
     * @return {@code SUCCESS} to skip the local prediction and still send the packet, {@code PASS} to
     *         let vanilla predict normally
     */
    public static InteractionResult filterUse(Player player, Level level, InteractionHand hand,
                                              BlockHitResult hit) {
        if (!level.isClientSide()) {
            // Defensive only: the caller splits by side before getting here.
            return InteractionResult.PASS;
        }
        ItemStack stack = player.getItemInHand(hand);
        // The client's copy of "what is inside this block", which is what the server's store holds a
        // round trip later. A click that turns out not to be ours is simply not claimed.
        Slot existing = ClientHostCache.slotAt(level.dimension(), hit.getBlockPos());
        if (!HostRules.claimsClick(level.getBlockState(hit.getBlockPos()), existing, stack,
                player.isSecondaryUseActive())) {
            return InteractionResult.PASS;
        }
        return InteractionResult.SUCCESS;
    }
}

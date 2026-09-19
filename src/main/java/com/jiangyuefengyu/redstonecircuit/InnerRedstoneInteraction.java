package com.jiangyuefengyu.redstonecircuit;

import org.jetbrains.annotations.Nullable;

import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneNode;
import com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneStore;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;

/**
 * Placement and retrieval of inner redstone.
 *
 * <ul>
 *   <li>{@code shift + right-click} a block holding a redstone component - store it inside that
 *       block. A block holds at most one component.</li>
 *   <li>{@code shift + right-click} with an empty hand - take the component back out.</li>
 * </ul>
 *
 * <p>Anything that does not qualify falls through untouched, so vanilla behaviour (including
 * vanilla shift-right-click functions) is preserved.
 */
public final class InnerRedstoneInteraction {

    /**
     * Tick/hand/position of the last processed interaction.
     *
     * <p>Right-clicking a block dispatches once for the main hand and once for the off hand, in the
     * same tick and at the same position. Acting on both would place a component with one hand and
     * immediately take it back out with the other, so the first hand to be processed "owns" the
     * click and the second hand is ignored - <em>regardless of whether the first hand succeeded</em>,
     * because the dispatch order between the two hands is not something we should depend on.
     */
    private long lastHandledTick = Long.MIN_VALUE;
    private BlockPos lastHandledPos = BlockPos.ZERO;
    private InteractionHand lastHandledHand = null;

    // ------------------------------------------------------------- placement --

    @SubscribeEvent
    public void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        ServerPlayer player = serverPlayer(event.getEntity());
        Level level = event.getLevel();
        if (player == null || !(level instanceof ServerLevel serverLevel)) {
            return;
        }
        // Only the dedicated sneak + right-click gesture, and only while not using an item.
        if (!player.isSecondaryUseActive()) {
            return;
        }

        BlockPos pos = event.getPos();
        Direction face = event.getFace();
        if (face == null) {
            return;
        }

        // Second hand for the same click: the first hand already decided what this click means.
        long tick = serverLevel.getGameTime();
        if (tick == lastHandledTick && pos.equals(lastHandledPos)
                && lastHandledHand != null && lastHandledHand != event.getHand()) {
            debug("skipping {} pass at {}: this click was already handled by {}",
                    event.getHand(), pos.toShortString(), lastHandledHand);
            event.setCanceled(true);
            return;
        }
        markHandled(tick, pos, event.getHand());

        ItemStack stack = player.getItemInHand(event.getHand());

        if (stack.isEmpty()) {
            if (tryRetrieve(serverLevel, player, pos)) {
                event.setCanceled(true);
            }
            return;
        }

        ComponentType type = HostRules.componentFor(stack);
        if (type == null) {
            return;
        }
        if (tryPlace(serverLevel, player, stack, pos, type)) {
            event.setCanceled(true);
        }
    }

    private void markHandled(long tick, BlockPos pos, InteractionHand hand) {
        lastHandledTick = tick;
        lastHandledPos = pos.immutable();
        lastHandledHand = hand;
    }

    private boolean tryPlace(ServerLevel level, ServerPlayer player, ItemStack stack,
                             BlockPos pos, ComponentType type) {
        if (RCConfig.validateHosts() && !HostRules.isValidHost(level.getBlockState(pos))) {
            debug("refused {} at {}: block is not a valid host", type, pos.toShortString());
            return false;
        }

        InnerRedstoneStore store = InnerRedstoneStore.get(level);
        InnerRedstoneNode existing = store.get(pos);
        if (existing != null && !existing.isEmpty()) {
            // One component per block - keep vanilla behaviour instead of silently replacing.
            debug("refused {} at {}: block already holds {}",
                    type, pos.toShortString(), existing.type());
            return false;
        }

        InnerRedstoneNode node = store.getOrCreate(pos);
        node.setSlot(new com.jiangyuefengyu.redstonecircuit.data.Slot(type));
        store.markDirty();

        if (!player.getAbilities().instabuild) {
            stack.shrink(1);
        }
        // Reuse the host block's own sound so the feedback matches what the player just clicked.
        BlockState hostState = level.getBlockState(pos);
        SoundType sound = hostState.getSoundType();
        level.playSound(null, pos, sound.getPlaceSound(), SoundSource.BLOCKS,
                (sound.getVolume() + 1.0F) / 2.0F, sound.getPitch() * 0.8F);

        debug("placed {} at {}", type, pos.toShortString());
        logNode(store, pos);
        return true;
    }

    // ------------------------------------------------------------- retrieval --

    private boolean tryRetrieve(ServerLevel level, ServerPlayer player, BlockPos pos) {
        InnerRedstoneStore store = InnerRedstoneStore.get(level);
        InnerRedstoneNode node = store.get(pos);
        if (node == null || node.isEmpty()) {
            return false;
        }

        InnerRedstoneNode removed = store.remove(pos);
        if (removed == null || removed.isEmpty()) {
            return false;
        }

        if (!player.getAbilities().instabuild) {
            ItemStack back = HostRules.itemFor(removed.type());
            if (!player.getInventory().add(back)) {
                Block.popResource(level, pos, back);
            }
        }

        BlockState hostState = level.getBlockState(pos);
        SoundType sound = hostState.getSoundType();
        level.playSound(null, pos, sound.getBreakSound(), SoundSource.BLOCKS,
                (sound.getVolume() + 1.0F) / 2.0F, sound.getPitch() * 0.8F);

        debug("retrieved {} at {}", removed.type(), pos.toShortString());
        return true;
    }

    // -------------------------------------------------------- store lifecycle --

    /**
     * Drops everything stored inside a block that is about to be removed, so nothing is lost or
     * dupeable. Runs before the block actually changes.
     */
    @SubscribeEvent
    public void onBlockBreak(BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        cleanup(level, event.getPos());
    }

    /** Clears the store for a position and drops its contents into the world. */
    public static void cleanup(ServerLevel level, BlockPos pos) {
        InnerRedstoneStore store = InnerRedstoneStore.get(level);
        InnerRedstoneNode node = store.remove(pos);
        if (node == null || node.isEmpty()) {
            return;
        }
        Block.popResource(level, pos, HostRules.itemFor(node.type()));
        debug("dropped {} from broken host {}", node.type(), pos.toShortString());
    }

    // ----------------------------------------------------------------- utils --

    @Nullable
    private static ServerPlayer serverPlayer(Player player) {
        return player instanceof ServerPlayer serverPlayer ? serverPlayer : null;
    }

    private static void debug(String message, Object... args) {
        if (RCConfig.debugLog()) {
            RCConfig.LOGGER.info("[redstonecircuit] " + message, args);
        }
    }

    private static void logNode(InnerRedstoneStore store, BlockPos pos) {
        if (!RCConfig.debugLog()) {
            return;
        }
        InnerRedstoneNode node = store.get(pos);
        if (node != null) {
            RCConfig.LOGGER.info("[redstonecircuit] {}", node.describe(pos));
        }
    }
}

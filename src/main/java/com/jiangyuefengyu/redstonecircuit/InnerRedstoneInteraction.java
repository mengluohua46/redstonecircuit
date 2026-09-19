package com.jiangyuefengyu.redstonecircuit;

import org.jetbrains.annotations.Nullable;

import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneNode;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
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
 *   <li>{@code shift + right-click} with a redstone component in hand - store it inside the
 *       clicked block (on the clicked face).</li>
 *   <li>{@code shift + right-click} with an empty hand - take the most recently stored component
 *       back out.</li>
 * </ul>
 *
 * <p>Anything that does not qualify falls through untouched, so vanilla behaviour (including
 * vanilla shift-right-click functions) is preserved.
 */
public final class InnerRedstoneInteraction {

    /** Tick of the last handled interaction; guards against the main-hand and off-hand passes. */
    private long lastHandledTick = Long.MIN_VALUE;
    private BlockPos lastHandledPos = BlockPos.ZERO;

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

        ItemStack stack = player.getItemInHand(event.getHand());

        if (stack.isEmpty()) {
            if (tryRetrieve(serverLevel, player, pos, face)) {
                event.setCanceled(true);
            }
            return;
        }

        ComponentType type = HostRules.componentFor(stack);
        if (type == null) {
            return;
        }
        if (tryPlace(serverLevel, player, stack, pos, face, type)) {
            event.setCanceled(true);
        }
    }

    private boolean tryPlace(ServerLevel level, ServerPlayer player, ItemStack stack,
                             BlockPos pos, Direction face, ComponentType type) {
        if (!claim(player, pos)) {
            return false;
        }
        if (RCConfig.validateHosts() && !HostRules.isValidHost(level.getBlockState(pos))) {
            return false;
        }

        var store = com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneStore.get(level);
        InnerRedstoneNode node = store.get(pos);
        if (node != null && node.has(face)) {
            // That face is already occupied - keep vanilla behaviour rather than silently replacing.
            return false;
        }
        if (node != null && node.size() >= RCConfig.maxComponentsPerBlock()) {
            return false;
        }

        node = store.getOrCreate(pos);
        node.put(face, type);
        store.markDirty();

        if (!player.getAbilities().instabuild) {
            stack.shrink(1);
        }
        // Reuse the placed block's own sound so the feedback matches what the player just clicked.
        BlockState hostState = level.getBlockState(pos);
        SoundType sound = hostState.getSoundType();
        level.playSound(null, pos, sound.getPlaceSound(), SoundSource.BLOCKS,
                (sound.getVolume() + 1.0F) / 2.0F, sound.getPitch() * 0.8F);

        debug("placed {} on face {} at {}", type, face.getName(), pos.toShortString());

        if (RCConfig.debugLog()) {
            logNode(store, pos);
        }
        return true;
    }

    // ------------------------------------------------------------- retrieval --

    private boolean tryRetrieve(ServerLevel level, ServerPlayer player, BlockPos pos, Direction face) {
        if (!claim(player, pos)) {
            return false;
        }

        var store = com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneStore.get(level);
        InnerRedstoneNode node = store.get(pos);
        if (node == null || node.isEmpty()) {
            return false;
        }

        // Take back whatever was put in last.
        Direction newest = node.newestFace();
        if (newest == null) {
            return false;
        }
        var removed = store.removeSlot(pos, newest);
        if (removed == null) {
            return false;
        }

        if (!player.getAbilities().instabuild) {
            ItemStack back = HostRules.itemFor(removed.type);
            if (!player.getInventory().add(back)) {
                Block.popResource(level, pos, back);
            }
        }

        BlockState hostState = level.getBlockState(pos);
        SoundType sound = hostState.getSoundType();
        level.playSound(null, pos, sound.getBreakSound(), SoundSource.BLOCKS,
                (sound.getVolume() + 1.0F) / 2.0F, sound.getPitch() * 0.8F);

        debug("retrieved {} from face {} at {}", removed.type, newest.getName(), pos.toShortString());

        if (RCConfig.debugLog()) {
            if (store.get(pos) == null) {
                RCConfig.LOGGER.info("[redstonecircuit] host {} is now empty", pos.toShortString());
            } else {
                logNode(store, pos);
            }
        }
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
        var store = com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneStore.get(level);
        InnerRedstoneNode node = store.remove(pos);
        if (node == null || node.isEmpty()) {
            return;
        }
        for (var slot : node.slots().values()) {
            Block.popResource(level, pos, HostRules.itemFor(slot.type));
        }
        debug("dropped {} component(s) from broken host {}", node.size(), pos.toShortString());
    }

    // ----------------------------------------------------------------- utils --

    @Nullable
    private static ServerPlayer serverPlayer(Player player) {
        return player instanceof ServerPlayer serverPlayer ? serverPlayer : null;
    }

    /** Guards against handling the same interaction twice (main hand + off hand). */
    private boolean claim(ServerPlayer player, BlockPos pos) {
        long tick = player.level().getGameTime();
        if (tick == lastHandledTick && pos.equals(lastHandledPos)) {
            return false;
        }
        lastHandledTick = tick;
        lastHandledPos = pos.immutable();
        return true;
    }

    private static void debug(String message, Object... args) {
        if (RCConfig.debugLog()) {
            RCConfig.LOGGER.info("[redstonecircuit] " + message, args);
        }
    }

    private static void logNode(com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneStore store, BlockPos pos) {
        InnerRedstoneNode node = store.get(pos);
        if (node != null) {
            RCConfig.LOGGER.info("[redstonecircuit] {}", node.describe(pos));
        }
    }
}

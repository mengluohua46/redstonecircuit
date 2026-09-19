package com.jiangyuefengyu.redstonecircuit;

import org.jetbrains.annotations.Nullable;

import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneNode;
import com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneStore;
import com.jiangyuefengyu.redstonecircuit.data.Slot;
import com.jiangyuefengyu.redstonecircuit.logic.InnerRedstoneNetwork;
import com.jiangyuefengyu.redstonecircuit.logic.InnerSwitches;
import com.jiangyuefengyu.redstonecircuit.logic.WrenchLinks;
import com.jiangyuefengyu.redstonecircuit.logic.WrenchSelection;
import com.jiangyuefengyu.redstonecircuit.network.HostSync;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
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
 * Placement, retrieval and operation of inner redstone.
 *
 * <ul>
 *   <li>{@code shift + right-click} a block holding a component - store a component inside it. A block
 *       holds at most one component, and the clicked side becomes the component's input side.</li>
 *   <li>{@code shift + right-click} with an empty hand - take the component back out.</li>
 *   <li>{@code right-click} with an empty hand on a block holding a lever or button - work it.</li>
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

    // ---------------------------------------------------------------- wrench --

    /**
     * The redstone wrench: pick a component, then pin the link to the one next to it (R7).
     *
     * <ul>
     *   <li>{@code right-click} a block holding a component - make it the selected one.</li>
     *   <li>{@code shift + right-click} a component next to the selected one - step the link between
     *       the two through connect / cut / automatic. Repeating it on the same pair keeps stepping, and
     *       using a different neighbour re-routes the connection.</li>
     *   <li>{@code shift + right-click} the selected block itself - drop every override on it, so it
     *       goes back to deciding for itself.</li>
     * </ul>
     *
     * @return whether the click was ours to consume; {@code false} lets vanilla have it, which is what
     *     happens when the block holds nothing
     */
    private boolean useWrench(ServerLevel level, ServerPlayer player, BlockPos pos,
                              @Nullable Direction face) {
        Slot slot = WrenchLinks.slotAt(level, pos);
        if (slot == null) {
            // Nothing here: not our click. Right-clicking a chest with the wrench still opens it.
            return false;
        }
        if (RCConfig.wrenchNeedsGoggles() && !wearsGoggles(player)) {
            message(player, Component.translatable("message.redstonecircuit.wrench.need_goggles")
                    .withStyle(ChatFormatting.RED));
            return true;
        }

        if (!player.isSecondaryUseActive()) {
            WrenchSelection.select(player, pos);
            message(player, Component.translatable("message.redstonecircuit.wrench.selected",
                    Component.literal(pos.toShortString()),
                    Component.literal(WrenchLinks.describe(slot))).withStyle(ChatFormatting.YELLOW));
            return true;
        }

        BlockPos selected = WrenchSelection.get(player);
        if (pos.equals(selected)) {
            if (!RCConfig.wrenchClearOnSameBlock()) {
                return true;
            }
            WrenchLinks.Result result = WrenchLinks.clear(level, pos);
            message(player, Component.translatable("message.redstonecircuit.wrench.cleared",
                    Component.literal(pos.toShortString())).withStyle(ChatFormatting.AQUA));
            debug("wrench clear at {} -> {}", pos.toShortString(), result);
            return true;
        }
        if (selected == null) {
            message(player, Component.translatable("message.redstonecircuit.wrench.no_selection")
                    .withStyle(ChatFormatting.RED));
            return true;
        }

        WrenchLinks.Result result = WrenchLinks.link(level, selected, pos);
        message(player, describeLink(result, selected, pos));
        debug("wrench link {} -> {} : {}", selected.toShortString(), pos.toShortString(), result);
        return true;
    }

    private static Component describeLink(WrenchLinks.Result result, BlockPos a, BlockPos b) {
        String direction = WrenchLinks.directionBetween(a, b)
                .map(Direction::getName)
                .orElse("?");
        return switch (result) {
            case LINKED_ON -> Component.translatable("message.redstonecircuit.wrench.linked_on",
                    Component.literal(direction)).withStyle(ChatFormatting.GREEN);
            case LINKED_OFF -> Component.translatable("message.redstonecircuit.wrench.linked_off",
                    Component.literal(direction)).withStyle(ChatFormatting.RED);
            case LINKED_AUTO -> Component.translatable("message.redstonecircuit.wrench.linked_auto",
                    Component.literal(direction)).withStyle(ChatFormatting.GRAY);
            case NOT_ADJACENT -> Component.translatable(
                    "message.redstonecircuit.wrench.not_adjacent").withStyle(ChatFormatting.RED);
            case NO_COMPONENT -> Component.translatable(
                    "message.redstonecircuit.wrench.no_component").withStyle(ChatFormatting.RED);
            case CLEARED -> Component.translatable("message.redstonecircuit.wrench.cleared",
                    Component.literal(a.toShortString())).withStyle(ChatFormatting.AQUA);
        };
    }

    /** True when the player is actually wearing the goggles in the head slot. */
    private static boolean wearsGoggles(ServerPlayer player) {
        return RCRegistry.isGoggles(player.getItemBySlot(EquipmentSlot.HEAD));
    }

    private static void message(ServerPlayer player, Component text) {
        player.displayClientMessage(text, true);
    }

    // ------------------------------------------------------------- placement --

    @SubscribeEvent
    public void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        ServerPlayer player = serverPlayer(event.getEntity());
        Level level = event.getLevel();
        if (player == null || !(level instanceof ServerLevel serverLevel)) {
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

        // The wrench takes the whole click, both halves of it, before anything else looks at it.
        if (RCRegistry.isWrench(stack)) {
            if (useWrench(serverLevel, player, pos, event.getFace())) {
                event.setCanceled(true);
            }
            return;
        }

        if (!player.isSecondaryUseActive()) {
            // Plain right-click stays vanilla's, with one exception: an empty hand can work the switch
            // or setting hidden inside the block. That is safe precisely because vanilla has no
            // empty-hand action on a full opaque block - every block that does have one (chest,
            // crafting table, bed, ...) is already excluded from being a host.
            if (stack.isEmpty() && tryToggle(serverLevel, player, pos)) {
                event.setCanceled(true);
            }
            return;
        }

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
        if (tryPlace(serverLevel, player, stack, pos, type, face)) {
            event.setCanceled(true);
        }
    }

    private void markHandled(long tick, BlockPos pos, InteractionHand hand) {
        lastHandledTick = tick;
        lastHandledPos = pos.immutable();
        lastHandledHand = hand;
    }

    private boolean tryPlace(ServerLevel level, ServerPlayer player, ItemStack stack,
                             BlockPos pos, ComponentType type, Direction face) {
        if (RCConfig.validateHosts() && !HostRules.isValidHost(level.getBlockState(pos))) {
            debug("refused {} at {}: block is not a valid host", type, pos.toShortString());
            return false;
        }

        InnerRedstoneStore store = InnerRedstoneStore.get(level);
        InnerRedstoneNode existing = store.get(pos);
        if (existing != null && !existing.isEmpty()) {
            if (!RCConfig.allowReplace()) {
                // One component per block - keep vanilla behaviour instead of silently replacing.
                debug("refused {} at {}: block already holds {}",
                        type, pos.toShortString(), existing.type());
                return false;
            }
            // Replacing is opt-in, and even then the old component is not destroyed: it drops in the
            // world exactly like a retrieval, so nothing can be lost by a mis-click.
            Block.popResource(level, pos, HostRules.itemFor(existing.type()));
            debug("replacing {} at {}", existing.type(), pos.toShortString());
        }

        Slot slot = new Slot(type);
        slot.power = type.initialPower();
        // The side the player clicked is the component's input side. For a diode that means "it reads
        // from where I clicked and drives the far side", and for a torch "it hangs on that side".
        slot.facing = face;

        InnerRedstoneNode node = store.getOrCreate(pos);
        node.setSlot(slot);
        store.markDirty();

        // Adding a component changes the surrounding network, so queue a propagation pass.
        InnerRedstoneNetwork.markDirtyWithNeighbours(level, pos);
        // ...tell vanilla consumers to re-check, since no block state changed...
        notifyNeighbours(level, pos);
        // ...and tell clients, whose renderer draws this block differently from now on.
        HostSync.broadcast(level, pos, slot);

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

    // --------------------------------------------------------------- switches --

    /**
     * Works whatever is hidden inside a block: toggles a lever, presses a button, steps a repeater's
     * delay, or swaps a comparator's mode.
     *
     * <p>Only ever reached with a bare hand, so a player holding anything still gets vanilla's
     * behaviour. It is safe to take the bare-hand click because a host block is always a full opaque
     * cube, and vanilla has no bare-hand action on one - every block that does have an interaction
     * (chest, crafting table, bed, ...) is already excluded from holding redstone.
     *
     * <p>The setting is reported on the action bar because nothing on the outside of the block changes:
     * without the goggles on, "the delay is now three" is otherwise invisible.
     */
    private boolean tryToggle(ServerLevel level, ServerPlayer player, BlockPos pos) {
        // The state change itself lives in InnerSwitches so that the player click, the /rc toggle
        // command and the game tests cannot drift apart.
        InnerSwitches.Result result = InnerSwitches.toggle(level, pos);
        if (result == InnerSwitches.Result.NONE) {
            return false;
        }
        Slot slot = WrenchLinks.slotAt(level, pos);
        if (slot != null) {
            message(player, InnerSwitches.describe(slot).withStyle(ChatFormatting.YELLOW));
        }
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

        // Removing a component can cut power to its neighbours, so re-derive the local network.
        InnerRedstoneNetwork.markDirtyWithNeighbours(level, pos);
        notifyNeighbours(level, pos);
        HostSync.broadcast(level, pos, null);
        // A block that no longer holds anything cannot be either half of a wrench link.
        WrenchSelection.clearIf(level, pos);

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
        // The host is going away, so whatever coupled to it must re-derive its power.
        InnerRedstoneNetwork.markDirtyWithNeighbours(level, pos);
        notifyNeighbours(level, pos);
        HostSync.broadcast(level, pos, null);
        // A block that no longer holds anything cannot be either half of a wrench link.
        WrenchSelection.clearIf(level, pos);
        Block.popResource(level, pos, HostRules.itemFor(node.type()));
        debug("dropped {} from broken host {}", node.type(), pos.toShortString());
    }

    // ----------------------------------------------------------------- utils --

    /** Tells the surrounding blocks that this position's redstone output may have changed. */
    public static void notifyNeighbours(ServerLevel level, BlockPos pos) {
        BlockState hostState = level.getBlockState(pos);
        Block hostBlock = hostState.getBlock();
        // Two calls on purpose: updateNeighborsAt covers the vanilla notification path, and the
        // explicit neighbourChanged loop makes sure each neighbour's own handler runs even when the
        // world is mid-setup (as it is inside a game test, where the host block may have been placed
        // in the same tick).
        level.updateNeighborsAt(pos, hostBlock);
        for (Direction direction : Direction.values()) {
            BlockPos neighbour = pos.relative(direction);
            level.getBlockState(neighbour)
                    .handleNeighborChanged(level, neighbour, hostBlock, pos, false);
        }
    }

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

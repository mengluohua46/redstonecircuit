package com.jiangyuefengyu.redstonecircuit;

import org.jetbrains.annotations.Nullable;

import com.jiangyuefengyu.redstonecircuit.client.ClientPlacementGuard;
import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneNode;
import com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneStore;
import com.jiangyuefengyu.redstonecircuit.data.Slot;
import com.jiangyuefengyu.redstonecircuit.logic.InnerRedstoneNetwork;
import com.jiangyuefengyu.redstonecircuit.logic.InnerSwitches;
import com.jiangyuefengyu.redstonecircuit.logic.RotationRod;
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
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;

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
 *
 * <h2>How the two clicks are hooked on Fabric</h2>
 * <ul>
 *   <li>{@code PlayerInteractEvent.RightClickBlock} → {@code UseBlockCallback}. It fires on <b>both</b>
 *       sides, at the head of {@code ServerPlayerInteractionManager#interactBlock} on the server and
 *       inside {@code MultiPlayerGameMode#interactBlock} on the client, before the local prediction.
 *       That is exactly the split {@link com.jiangyuefengyu.redstonecircuit.client.ClientPlacementGuard}
 *       needed, so the guard became the client half of this same callback rather than a separate
 *       event - and its answer is an {@link InteractionResult}, which is what the callback wants.
 *       {@code SUCCESS} means "this click is ours": on the server it stops vanilla, on the client it
 *       skips the predicted placement <em>and</em> still sends the packet, which is how the two stay
 *       in step. {@code PASS} hands the click back.</li>
 *   <li>{@code BlockEvent.BreakEvent} → {@code PlayerBlockBreakEvents.BEFORE}, which is likewise before
 *       the block actually goes, so the component is still there to be dropped.</li>
 * </ul>
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
     *
     * <p>Only the server copy of the handler carries this state: the client half answers with
     * {@code claimsClick} alone and never places anything, so it has nothing to dedupe.
     */
    private long lastHandledTick = Long.MIN_VALUE;
    private BlockPos lastHandledPos = BlockPos.ZERO;
    private InteractionHand lastHandledHand = null;

    /**
     * Wires up both clicks. Called once from {@code RedstoneCircuit.onInitialize}.
     *
     * <p>The instance created here is the only one, which is what keeps the main-hand/off-hand
     * bookkeeping above meaningful - two callbacks with two instances would each see only half of a
     * click.
     */
    public static void register() {
        InnerRedstoneInteraction interaction = new InnerRedstoneInteraction();
        UseBlockCallback.EVENT.register(interaction::onUseBlock);
        PlayerBlockBreakEvents.BEFORE.register(interaction::onBlockBreak);
    }

    // ---------------------------------------------------------------- wrench --

    /**
     * The redstone wrench: pick a component, then point the line at a neighbour (R7).
     *
     * <ul>
     *   <li>{@code right-click} a block holding a component - make it the selected one. Only a block with
     *       redstone inside can be the near end: it is the one being routed.</li>
     *   <li>{@code shift + right-click} a neighbour - step the line between the two through locked / cut /
     *       automatic. The neighbour may be a piston, a lamp, a door or a plain block: it does not have to
     *       hold redstone itself, and if it does not, only the selected end is pinned.</li>
     *   <li>{@code shift + right-click} the selected block itself - drop every override on it, so it goes
     *       back to deciding for itself.</li>
     * </ul>
     *
     * <p>A click that cannot be about the wrench is handed back to vanilla, so a chest still opens and a
     * door still works: the second half of the gesture is claimed when the clicked block is next to the
     * selection (or holds a component, so a mistaken far-away click still says why it did nothing).
     *
     * @return whether the click was ours to consume
     */
    private boolean useWrench(ServerLevel level, ServerPlayer player, BlockPos pos,
                              @Nullable Direction face) {
        Slot slot = WrenchLinks.slotAt(level, pos);
        boolean holdsComponent = slot != null;

        if (!player.isSecondaryUseActive()) {
            if (!holdsComponent) {
                // Nothing here: not our click. Right-clicking a chest with the wrench still opens it.
                return false;
            }
            if (refuseWithoutGoggles(player)) {
                return true;
            }
            WrenchSelection.select(player, pos);
            message(player, Component.translatable("message.redstonecircuit.wrench.selected",
                    Component.literal(pos.toShortString()),
                    Component.literal(WrenchLinks.describe(slot))).withStyle(ChatFormatting.YELLOW));
            return true;
        }

        BlockPos selected = WrenchSelection.get(player);
        boolean adjacent = selected != null
                && WrenchLinks.directionBetween(selected, pos).isPresent();
        if (!adjacent && !holdsComponent) {
            // A shift-click somewhere unrelated: leave it to the block (a door, a chest, a button).
            return false;
        }
        if (refuseWithoutGoggles(player)) {
            return true;
        }

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

        boolean farEndHoldsComponent = WrenchLinks.slotAt(level, pos) != null;
        WrenchLinks.Result result = WrenchLinks.link(level, selected, pos);
        message(player, describeLink(result, selected, pos, farEndHoldsComponent));
        debug("wrench link {} -> {} : {}", selected.toShortString(), pos.toShortString(), result);
        return true;
    }

    /** True when the wrench must be refused because the goggles are not being worn (and says so). */
    private static boolean refuseWithoutGoggles(ServerPlayer player) {        if (!RCConfig.wrenchNeedsGoggles() || wearsGoggles(player)) {
            return false;
        }
        message(player, Component.translatable("message.redstonecircuit.wrench.need_goggles")
                .withStyle(ChatFormatting.RED));
        return true;
    }

    /**
     * 方向转向杆 - the rotation rod: point the component at the face that was clicked.
     *
     * <p>One click, one turn, and the turn itself is decided in {@code logic.RotationRod}: input side on
     * the clicked face, then output side on it, then back again. A block holding nothing is not our
     * click at all, so a door or a chest still works with the rod in hand.
     *
     * @return whether the click was ours to consume
     */
    private boolean useRotationRod(ServerLevel level, ServerPlayer player, BlockPos pos, Direction face) {
        Slot slot = WrenchLinks.slotAt(level, pos);
        if (slot == null) {
            return false;
        }
        if (RCConfig.rotationRodNeedsGoggles() && !wearsGoggles(player)) {
            message(player, Component.translatable("message.redstonecircuit.rotation_rod.need_goggles")
                    .withStyle(ChatFormatting.RED));
            return true;
        }

        RotationRod.Result result = RotationRod.turn(level, pos, face);
        message(player, describeTurn(result, face));
        debug("rotation rod at {} face {} -> {}", pos.toShortString(), face, result);
        return true;
    }

    private static Component describeTurn(RotationRod.Result result, Direction face) {
        Component side = Component.literal(face.getName());
        return switch (result) {
            case TURNED_TO_INPUT -> Component.translatable(
                    "message.redstonecircuit.rotation_rod.to_input", side)
                    .withStyle(ChatFormatting.YELLOW);
            case TURNED_TO_OUTPUT -> Component.translatable(
                    "message.redstonecircuit.rotation_rod.to_output", side)
                    .withStyle(ChatFormatting.YELLOW);
            case NO_DIRECTION -> Component.translatable(
                    "message.redstonecircuit.rotation_rod.no_direction").withStyle(ChatFormatting.RED);
            case NOTHING_THERE -> Component.translatable(
                    "message.redstonecircuit.rotation_rod.nothing_there").withStyle(ChatFormatting.RED);
        };
    }

    private static Component describeLink(WrenchLinks.Result result, BlockPos a, BlockPos b,
                                          boolean farEndHoldsComponent) {
        String direction = WrenchLinks.directionBetween(a, b)
                .map(Direction::getName)
                .orElse("?");
        return switch (result) {
            case LINKED_ON -> Component.translatable(farEndHoldsComponent
                            ? "message.redstonecircuit.wrench.linked_on"
                            : "message.redstonecircuit.wrench.linked_on_device",
                    Component.literal(direction)).withStyle(ChatFormatting.GREEN);
            case LINKED_OFF -> Component.translatable("message.redstonecircuit.wrench.linked_off",
                    Component.literal(direction)).withStyle(ChatFormatting.RED);
            case LINKED_AUTO -> Component.translatable("message.redstonecircuit.wrench.linked_auto",
                    Component.literal(direction)).withStyle(ChatFormatting.GRAY);
            case NOT_ADJACENT -> Component.translatable(
                    "message.redstonecircuit.wrench.not_adjacent").withStyle(ChatFormatting.RED);
            case NO_COMPONENT -> Component.translatable(
                    "message.redstonecircuit.wrench.no_component").withStyle(ChatFormatting.RED);
            case NOTHING_THERE -> Component.translatable(
                    "message.redstonecircuit.wrench.nothing_there").withStyle(ChatFormatting.RED);
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

    /**
     * Handles the clicks the mod owns.
     *
     * <p>The same question - "is this click ours?" - is answered by {@link HostRules#claimsClick} and by
     * {@link com.jiangyuefengyu.redstonecircuit.client.ClientPlacementGuard}, which uses it to stop the
     * client predicting a block placement for a click that will not place one. That gate is applied here
     * too, so the two sides cannot drift: a click the client decided against is a click the server
     * leaves to vanilla, and vice versa.
     *
     * @return {@code SUCCESS} when the click was the mod's and vanilla must not see it, {@code PASS}
     *         to hand it back
     */
    private InteractionResult onUseBlock(Player player, Level level, InteractionHand hand,
                                         BlockHitResult hit) {
        if (!level.isClientSide()) {
            return serverUseBlock(player, level, hand, hit);
        }
        // The client half only decides whether to predict; the server half below does the work, and
        // receives the packet either way when this answers SUCCESS.
        return ClientPlacementGuard.filterUse(player, level, hand, hit);
    }

    // ---------------------------------------------------------------- server --

    private InteractionResult serverUseBlock(Player actor, Level world, InteractionHand hand,
                                             BlockHitResult hit) {
        ServerPlayer player = serverPlayer(actor);
        if (player == null || !(world instanceof ServerLevel serverLevel)) {
            return InteractionResult.PASS;
        }

        BlockPos pos = hit.getBlockPos();
        Direction face = hit.getDirection();
        if (face == null) {
            return InteractionResult.PASS;
        }
        ItemStack stack = player.getItemInHand(hand);

        // The wrench takes the whole click, both halves of it, before anything else looks at it. It is
        // not part of the gate above because it is not a block item: nothing is ever predicted for it.
        // The rotation rod is the same kind of tool and is let through the same way.
        boolean wrench = RCRegistry.isWrench(stack);
        boolean rod = RCRegistry.isRotationRod(stack);
        Slot existing = wrench || rod ? null : WrenchLinks.slotAt(serverLevel, pos);
        if (!wrench && !rod && !HostRules.claimsClick(serverLevel.getBlockState(pos), existing, stack,
                player.isSecondaryUseActive())) {
            return InteractionResult.PASS;
        }

        // Second hand for the same click: the first hand already decided what this click means.
        long tick = serverLevel.getGameTime();
        if (tick == lastHandledTick && pos.equals(lastHandledPos)
                && lastHandledHand != null && lastHandledHand != hand) {
            debug("skipping {} pass at {}: this click was already handled by {}",
                    hand, pos.toShortString(), lastHandledHand);
            return InteractionResult.SUCCESS;
        }
        markHandled(tick, pos, hand);

        // The wrench takes the whole click, both halves of it, before anything else looks at it.
        if (wrench) {
            return useWrench(serverLevel, player, pos, face)
                    ? InteractionResult.SUCCESS : InteractionResult.PASS;
        }

        // The rotation rod does too: one click, one turn.
        if (rod) {
            return useRotationRod(serverLevel, player, pos, face)
                    ? InteractionResult.SUCCESS : InteractionResult.PASS;
        }

        if (!player.isSecondaryUseActive()) {
            // Plain right-click stays vanilla's, with one exception: an empty hand can work the switch
            // or setting hidden inside the block. That is safe precisely because vanilla has no
            // empty-hand action on a full opaque block - every block that does have one (chest,
            // crafting table, bed, ...) is already excluded from being a host.
            return stack.isEmpty() && tryToggle(serverLevel, player, pos)
                    ? InteractionResult.SUCCESS : InteractionResult.PASS;
        }

        if (stack.isEmpty()) {
            return tryRetrieve(serverLevel, player, pos)
                    ? InteractionResult.SUCCESS : InteractionResult.PASS;
        }

        ComponentType type = HostRules.componentFor(stack);
        if (type == null) {
            return InteractionResult.PASS;
        }
        return tryPlace(serverLevel, player, stack, pos, type, face)
                ? InteractionResult.SUCCESS : InteractionResult.PASS;
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
     * dupeable. Runs before the block actually changes - hence {@code BEFORE}, not {@code AFTER}: the
     * host state and the store entry are both still there to read.
     *
     * @return always true: this is a cleanup, never a veto on breaking a block
     */
    private boolean onBlockBreak(Level world, Player player, BlockPos pos, BlockState state,
                                 net.minecraft.world.level.block.entity.BlockEntity entity) {
        if (world instanceof ServerLevel level) {
            cleanup(level, pos);
        }
        return true;
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

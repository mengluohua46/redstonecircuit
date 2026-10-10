package com.jiangyuefengyu.redstonecircuit;

import org.jetbrains.annotations.Nullable;

import com.jiangyuefengyu.redstonecircuit.carrier.CarriedSelection;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierClickPairing;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierClickPriority;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierGesture;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierStore;
import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneNode;
import com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneStore;
import com.jiangyuefengyu.redstonecircuit.data.Slot;
import com.jiangyuefengyu.redstonecircuit.logic.InnerAccess;
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
 * Placement, retrieval and operation of inner redstone: shift + right-click a block stores the held
 * component inside it with the clicked side as its input side, shift + right-click with an empty hand takes
 * it back out, and a plain empty-hand click works a lever or a button.
 *
 * <p>Anything that does not qualify falls through untouched, so vanilla behaviour is preserved.
 */
public final class InnerRedstoneInteraction {

    /**
     * Tick/hand/position of the last processed interaction.
     *
     * <p>A right-click dispatches once for the main hand and once for the off hand, in the same tick and at
     * the same position. Acting on both would place a component with one hand and immediately take it back
     * out with the other, so the first hand processed owns the click regardless of whether it succeeded.
     */
    private long lastHandledTick = Long.MIN_VALUE;
    private BlockPos lastHandledPos = BlockPos.ZERO;
    private InteractionHand lastHandledHand = null;

    // ---------------------------------------------------------------- wrench --

    /**
     * What the world's own wrench did with one click - three answers, because there is a third one that is
     * neither "mine" nor "not mine".
     *
     * @param KEEP the click is not about this wrench at all: the event is left exactly as it arrived, and
     *     vanilla and every other listener keep it (a chest still opens, a door still works)
     * @param CONSUMED the world's own wrench did the work: the caller cancels the event
     * @param CARRIER this shift-click belongs to the <b>carrier</b> ledger's pick, so the world stands down
     *     - both action fields FALSE, and <b>not</b> cancelled, so the carrier's own listener answers it
     */
    private enum WrenchOutcome {
        KEEP,
        CONSUMED,
        CARRIER
    }

    /**
     * The redstone wrench: pick a component, then point the line at a neighbour.
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
     * <p>The carrier layer keeps <b>its own</b> near end ({@code CarriedSelection}, a host riding a
     * structure) and this listener runs <b>before</b> the carrier's, so the "whose click is this" question
     * has to be asked here - see the handover below.
     *
     * @return what the caller should do with the event - see {@link WrenchOutcome}
     */
    private WrenchOutcome useWrench(ServerLevel level, ServerPlayer player, InteractionHand hand,
                                    BlockPos pos, @Nullable Direction face) {
        Slot slot = WrenchLinks.slotAt(level, pos);
        boolean holdsComponent = slot != null;

        if (!player.isSecondaryUseActive()) {
            if (!holdsComponent) {
                // Nothing here: not our click. Right-clicking a chest with the wrench still opens it.
                return WrenchOutcome.KEEP;
            }
            if (refuseWithoutGoggles(player)) {
                return WrenchOutcome.CONSUMED;
            }
            WrenchSelection.select(player, pos);
            // One live pick per player: the world's select half names a new near end, so the carrier's ledger
            // has to forget its own - otherwise a pick made inside a machine would keep answering the wrench's
            // shift half and the world's own line would be unreachable. Only this direction is needed: a
            // carrier pick is by construction the most recent one.
            CarriedSelection.clear(player);
            // The player-facing name is the block label from translation keys, not the component's enum
            // constant; the log keeps the constant.
            message(player, Component.translatable("message.redstonecircuit.wrench.selected",
                    Component.literal(pos.toShortString()),
                    WrenchLinks.label(slot)).withStyle(ChatFormatting.YELLOW));
            return WrenchOutcome.CONSUMED;
        }

        BlockPos selected = WrenchSelection.get(player);
        boolean adjacent = selected != null
                && WrenchLinks.directionBetween(selected, pos).isPresent();
        if (!adjacent && !holdsComponent) {
            // A shift-click somewhere unrelated: leave it to the block (a door, a chest, a button).
            return WrenchOutcome.KEEP;
        }
        if (CarrierClickPriority.theCarrierPickOwnsThisShiftClick(player,
                RCRegistry.isWrench(player.getItemInHand(hand)), RCConfig.carriedWrench())) {
            // The near end is the carrier's, so this shift-click is the carrier's: the world stands down and
            // lets the carrier's listener answer it (both action fields FALSE as well, so nothing acts on the
            // click underneath).
            return WrenchOutcome.CARRIER;
        }
        if (refuseWithoutGoggles(player)) {
            return WrenchOutcome.CONSUMED;
        }

        if (pos.equals(selected)) {
            if (!RCConfig.wrenchClearOnSameBlock()) {
                return WrenchOutcome.CONSUMED;
            }
            WrenchLinks.Result result = WrenchLinks.clear(level, pos);
            message(player, Component.translatable("message.redstonecircuit.wrench.cleared",
                    Component.literal(pos.toShortString())).withStyle(ChatFormatting.AQUA));
            debug("wrench clear at {} -> {}", pos.toShortString(), result);
            return WrenchOutcome.CONSUMED;
        }
        if (selected == null) {
            message(player, Component.translatable("message.redstonecircuit.wrench.no_selection")
                    .withStyle(ChatFormatting.RED));
            return WrenchOutcome.CONSUMED;
        }

        boolean farEndHoldsComponent = WrenchLinks.slotAt(level, pos) != null;
        WrenchLinks.Result result = WrenchLinks.link(level, selected, pos);
        message(player, describeLink(result, selected, pos, farEndHoldsComponent));
        debug("wrench link {} -> {} : {}", selected.toShortString(), pos.toShortString(), result);
        return WrenchOutcome.CONSUMED;
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
     * True when what is inside a block may not be worked because the goggles are not being worn: the rule is
     * {@code InnerAccess}, one decision shared with the render question, and it is <b>silent</b> because a
     * lever or a button is worked without the goggles anyway and a message about the rest would be about a
     * value the player cannot read. The click is still consumed.
     *
     * <p>{@code handSwitch} is the whitelist and comes from {@code InnerSwitches#isHandSwitch}, the same
     * classification {@code InnerSwitches#toggle} acts on, so exemption and action cannot drift apart. Asked
     * by both bare-hand doors there are: this class's world host and {@code CarrierInteraction}'s carried one.
     */
    public static boolean refuseInnerAdjust(ServerPlayer player, boolean handSwitch) {
        return !InnerAccess.mayAdjust(RCConfig.innerAdjustNeedsGoggles(), wearsGoggles(player), handSwitch);
    }

    /**
     * 方向转向杆 - the rotation rod: point the component at the clicked face. One click, one turn, and the
     * turn itself ({@code logic.RotationRod}) cycles the clicked face through input side, output side, input
     * side again. A block holding nothing is not our click, so a door or a chest still works with the rod
     * in hand.
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
     * {@code ClientPlacementGuard}, which uses it to stop the client predicting a block placement for a
     * click that will not place one. That gate is applied here too, so the two sides cannot drift.
     */
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
        ItemStack stack = player.getItemInHand(event.getHand());

        // ---------------------------------------------------------------- the one exception --
        //
        // A coordinate a structure OWNS belongs to the carrier side, and the world side stands down before
        // it claims anything: the world path does not merely skip its action, it never claims the click, so
        // nothing can write the world store at a structure's own coordinate.
        //
        // WHY IT HAS TO EXIST. A Sable sub-level's blocks are level blocks at their own local coordinates,
        // so a click on a physicalised block arrives here carrying a position that is both "a level
        // position" and "a host's coordinate inside a structure", and this handler cannot tell those apart;
        // writing the component into the world store there leaves something Jade shows, the overlay does not
        // draw, and nothing outside the machine can connect to.
        //
        // HOW NARROW IT IS. Only "the carrier layer can name a live host at this position" declines
        // anything - one read-only query, no write, no drop, nothing consumed. Every other world position
        // (including a carried host's origin, and a host that has been put down) answers null and behaves
        // exactly as it did before.
        //
        // OWNING THE COORDINATE IS NOT ENOUGH: what has to be decided is whether this CLICK is ours, and the
        // two differ. Declining every click on a structure's cell also stops vanilla's own `Item#useOn` -
        // the act of placing a block - so a plain placement (a piston, a plank, an unsneaked lever) must
        // still reach vanilla. `CarrierClickPriority#weOwnThisClick` decides it from the gesture and the
        // clicked cell's own contents.
        //
        // Standing down now includes the item: with a component in hand the NeoForge server path would
        // otherwise run vanilla's own `Item#useOn` on the same click and place a block at the plot
        // coordinate. `standDown` sets both action fields to FALSE, which stops `onItemUseFirst`, the
        // block's use AND the item's useOn, while leaving the event UNCANCELLED so the carrier's listener -
        // a default `receiveCanceled=false` listener - is still called; cancelling here would delete that
        // delivery, which is the one thing this gate exists to hand over.
        CarrierGesture.Kind carrierGesture = CarrierGesture.of(player, event.getHand());
        CarrierClickPriority.Owned carrierOwns = CarrierClickPriority.owner(serverLevel, pos);
        if (carrierOwns != null) {
            // The clicked block's own rule is part of the click: this side copies the world path's answer for
            // that block (whether it may hold a component at all), so the two halves agree and vanilla puts
            // the component down as an ordinary block instead of predicting a placement that never runs.
            CarrierClickPriority.StandDown standDown = CarrierClickPriority.standDown(carrierOwns,
                    carrierGesture, CarrierGesture.Hold.of(stack), player.isSecondaryUseActive(),
                    CarrierStore.hostHasComponent(serverLevel, carrierOwns.carrier(),
                            carrierOwns.localPos()),
                    CarrierStore.hostAcceptsComponent(carrierOwns.carrier(), carrierOwns.localPos()),
                    RCConfig.carriedInsert(), RCConfig.carriedWrench(), RCConfig.carriedSwitches());
            if (standDown.useBlock() && standDown.useItem()) {
                // NOT OURS: the event is left exactly as it arrived, which is what puts a piston, a plank
                // or a grass block onto a physicalised block again - and what leaves an ordinary world
                // coordinate untouched, byte for byte, as it was before this class existed.
                return;
            }
            event.setUseBlock(net.neoforged.neoforge.common.util.TriState.FALSE);
            event.setUseItem(net.neoforged.neoforge.common.util.TriState.FALSE);
            long tick = serverLevel.getGameTime();
            // The record and the line, in that order: the record is what lets the carrier's own trace say
            // "the world side declined this very delivery", and the line carries the hand, the item and the
            // tick.
            CarrierClickPairing.noteWorldDecline(tick, pos, event.getHand(), stack);
            CarrierClickPairing.logDecline(carrierOwns.describe(), pos, tick, event.getHand(), stack,
                    standDown.useBlock(), standDown.useItem(), event.isCanceled());
            return;
        }

        // The wrench takes the whole click, both halves of it, before anything else looks at it. It is
        // not part of the gate above because it is not a block item: nothing is ever predicted for it.
        // The rotation rod is the same kind of tool and is let through the same way.
        boolean wrench = RCRegistry.isWrench(stack);
        boolean rod = RCRegistry.isRotationRod(stack);
        Slot existing = wrench || rod ? null : WrenchLinks.slotAt(serverLevel, pos);
        if (!wrench && !rod && !HostRules.claimsClick(serverLevel.getBlockState(pos), existing, stack,
                player.isSecondaryUseActive())) {
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

        // The wrench takes the whole click, both halves of it, before anything else looks at it.
        if (wrench) {
            WrenchOutcome outcome = useWrench(serverLevel, player, event.getHand(), pos, event.getFace());
            if (outcome == WrenchOutcome.CONSUMED) {
                event.setCanceled(true);
            } else if (outcome == WrenchOutcome.CARRIER) {
                // The carrier's own shift-click: both action fields FALSE and NOT cancelled, so the carrier's
                // listener - registered after this one and `receiveCanceled=false` - is still called and is
                // the one that answers the click. "Not mine" and "nobody's" are different answers.
                event.setUseBlock(net.neoforged.neoforge.common.util.TriState.FALSE);
                event.setUseItem(net.neoforged.neoforge.common.util.TriState.FALSE);
            }
            return;
        }

        // The rotation rod does too: one click, one turn.
        if (rod) {
            if (useRotationRod(serverLevel, player, pos, face)) {
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

        // Diagnostic only, and only once per block type: tell the optional physics-compat layer that
        // this block type now really holds components, so it can attach its contraption hook to it.
        // With Sable and Create both absent this is a no-op that loads none of their classes.
        com.jiangyuefengyu.redstonecircuit.compat.physics.PhysicsCompat
                .hostBlockStored(level, pos, hostState.getBlock());
        return true;
    }

    // --------------------------------------------------------------- switches --

    /**
     * Works whatever is hidden inside a block: toggles a lever, presses a button, steps a repeater's delay,
     * or swaps a comparator's mode.
     *
     * <p>Only ever reached with a bare hand, so a player holding anything still gets vanilla's behaviour. It
     * is safe to take the bare-hand click because a host block is always a full opaque cube, and vanilla has
     * no bare-hand action on one - every block that does have an interaction (chest, crafting table, bed,
     * ...) is already excluded from holding redstone. The setting is reported on the action bar because
     * nothing on the outside of the block changes: without the goggles on, "the delay is now three" is
     * otherwise invisible.
     *
     * <p>The goggles gate stands here, above the state change, because this method is the only door a bare
     * hand has into a world host: every component this mod ships, and every one it ever adds, is worked
     * through it - see {@link #refuseInnerAdjust}. It is asked only for a component that has a bare-hand
     * action at all - a host holding a wire must stay "not our click", or another mod's interaction on it
     * would be eaten by a rule about a delay setting that is not there.
     */
    private boolean tryToggle(ServerLevel level, ServerPlayer player, BlockPos pos) {
        Slot slot = WrenchLinks.slotAt(level, pos);
        if (slot != null && InnerSwitches.isWorkable(slot.type)) {
            if (refuseInnerAdjust(player, InnerSwitches.isHandSwitch(slot.type))) {
                // The click was ours to take - a host block has no vanilla bare-hand action - so it is
                // consumed and answered with nothing: the two whitelisted components never reach this line.
                return true;
            }
            // 控制器's bare-hand gesture IS its screen - opening it is the adjustment - so it is claimed here
            // rather than stepped by InnerSwitches#toggle, which has nothing to step for it.
            if (slot.type == com.jiangyuefengyu.redstonecircuit.data.ComponentType.CONTROLLER) {
                com.jiangyuefengyu.redstonecircuit.block.ControllerBlock.sendHostView(player, level, pos, true);
                return true;
            }
            // 红石变压器's gesture is the same screen with one number on it.
            if (slot.type == com.jiangyuefengyu.redstonecircuit.data.ComponentType.TRANSFORMER) {
                com.jiangyuefengyu.redstonecircuit.block.TransformerBlock.sendHostView(player, level, pos, true);
                return true;
            }
            // 时钟源's is 秒 / tick / 强度 on the same screen, and the same open-not-step shape.
            if (slot.type == com.jiangyuefengyu.redstonecircuit.data.ComponentType.CLOCK) {
                com.jiangyuefengyu.redstonecircuit.block.ClockBlock.sendHostView(player, level, pos, true);
                return true;
            }
            // 信号延长器's is 秒 / 刻 on the same screen, opened the same way.
            if (slot.type == com.jiangyuefengyu.redstonecircuit.data.ComponentType.EXTENDER) {
                com.jiangyuefengyu.redstonecircuit.block.ExtenderBlock.sendHostView(player, level, pos, true);
                return true;
            }
        }
        // The state change itself lives in InnerSwitches so that the player click, the /rc toggle
        // command and the game tests cannot drift apart.
        InnerSwitches.Result result = InnerSwitches.toggle(level, pos);
        if (result == InnerSwitches.Result.NONE) {
            return false;
        }
        Slot after = WrenchLinks.slotAt(level, pos);
        if (after != null) {
            message(player, InnerSwitches.describe(after).withStyle(ChatFormatting.YELLOW));
        }
        return true;
    }

    // ------------------------------------------------------------- retrieval --

    private boolean tryRetrieve(ServerLevel level, ServerPlayer player, BlockPos pos) {
        InnerRedstoneNode removed = forget(level, pos);
        if (removed == null) {
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
        InnerRedstoneNode node = forget(level, pos);
        if (node == null) {
            return;
        }
        Block.popResource(level, pos, HostRules.itemFor(node.type()));
        debug("dropped {} from broken host {}", node.type(), pos.toShortString());
    }

    /**
     * <b>Taking a component out of a position without dropping it - and telling the clients.</b>
     *
     * <p>The one place that answers "something left this block, nobody is picking it up, and the client
     * still believes it is there". The client's copy is a copy of the store, not of the payload, so a
     * removal nobody names on the wire leaves a component that is invisible in air, does nothing, drops
     * nothing when that block is broken, and survives until the player relogs. The mirror half of the rule
     * is the broadcast when a component is <em>put</em> back (see {@code StructureRelease}).
     *
     * @return what was there, or {@code null} when the position held nothing - so a caller can drop it, hand it to a
     *     player, or (a cut capture) simply leave it in the payload it was copied into
     */
    @Nullable
    public static InnerRedstoneNode forget(ServerLevel level, BlockPos pos) {
        InnerRedstoneNode node = InnerRedstoneStore.get(level).remove(pos);
        if (node == null || node.isEmpty()) {
            return null;
        }
        // Removing a component can cut power to its neighbours, so re-derive the local network.
        InnerRedstoneNetwork.markDirtyWithNeighbours(level, pos);
        notifyNeighbours(level, pos);
        HostSync.broadcast(level, pos, null);
        // A block that no longer holds anything cannot be either half of a wrench link.
        WrenchSelection.clearIf(level, pos);
        return node;
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

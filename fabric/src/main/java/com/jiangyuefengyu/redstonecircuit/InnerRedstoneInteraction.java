package com.jiangyuefengyu.redstonecircuit;

import org.jetbrains.annotations.Nullable;

import com.jiangyuefengyu.redstonecircuit.carrier.CarriedSelection;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierClickPairing;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierClickPriority;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierGesture;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierStore;
import com.jiangyuefengyu.redstonecircuit.client.ClientPlacementGuard;
import com.jiangyuefengyu.redstonecircuit.compat.physics.PhysicsCompat;
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
 * <p>The one exception is a coordinate a structure owns: a click there belongs to the carrier layer, so it
 * is stood down here and answered by that layer instead. A physicalised block's position is both a level
 * coordinate and a host coordinate inside a structure, and only the carrier's own ledger can tell which
 * click means what - see {@code serverUseBlock}.
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
     * What the world's own wrench did with one click - three answers, because there is a third one that is
     * neither "mine" nor "not mine".
     *
     * @param KEEP the click is not about this wrench at all: it is left exactly as it arrived, and vanilla
     *     and every other listener keep it (a chest still opens, a door still works)
     * @param CONSUMED the world's own wrench did the work: the caller consumes the click
     * @param CARRIER this shift-click belongs to the <b>carrier</b> ledger's pick, so the world does not act
     *     on it at all - the carrier's own callback, which answers this click before the world's is reached,
     *     is the one that has it
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
     * structure), and a shift-click with one live is that layer's gesture rather than this one's - see
     * {@link WrenchOutcome#CARRIER}.
     *
     * @return what the caller should do with the click - see {@link WrenchOutcome}
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
            // One live pick per player: the world's select half names a new near end, so the carrier's
            // ledger has to forget its own - otherwise a pick made inside a machine would keep answering the
            // wrench's shift half and the world's own line would be unreachable. Only this direction is
            // needed: a carrier pick is by construction the most recent one.
            CarriedSelection.clear(player);
            // The name a player reads is the block label from the translation keys, not the component's
            // enum constant; the log keeps the constant.
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
            // The near end is the carrier's, so this shift-click is the carrier's: the world does not act on
            // it and does not answer for it.
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
    private static boolean refuseWithoutGoggles(ServerPlayer player) {
        if (!RCConfig.wrenchNeedsGoggles() || wearsGoggles(player)) {
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
     * classification {@code InnerSwitches#toggle} acts on, so exemption and action cannot drift apart.
     * Asked by both bare-hand doors there are: this class's world host and
     * {@code CarrierInteraction}'s carried one.
     */
    public static boolean refuseInnerAdjust(ServerPlayer player, boolean handSwitch) {
        return !InnerAccess.mayAdjust(RCConfig.innerAdjustNeedsGoggles(), wearsGoggles(player), handSwitch);
    }

    /**
     * 方向转向杆 - the rotation rod: one click steps the component one place around the fixed cycle in
     * {@code logic.RotationRod} (clockwise through the four horizontal sides, then up and down). The clicked
     * face is not consulted: the same click always means the same step, so the rod can be used without
     * aiming at a particular side. A block holding nothing is not our click, so a door or a chest still
     * works with the rod in hand.
     *
     * @return whether the click was ours to consume
     */
    private boolean useRotationRod(ServerLevel level, ServerPlayer player, BlockPos pos) {
        Slot slot = WrenchLinks.slotAt(level, pos);
        if (slot == null) {
            return false;
        }
        if (RCConfig.rotationRodNeedsGoggles() && !wearsGoggles(player)) {
            message(player, Component.translatable("message.redstonecircuit.rotation_rod.need_goggles")
                    .withStyle(ChatFormatting.RED));
            return true;
        }

        RotationRod.Result result = RotationRod.turn(level, pos);
        // The step was written into the slot the store hands out, so re-reading it answers where the rod points now.
        Slot turned = WrenchLinks.slotAt(level, pos);
        Direction now = turned == null ? null : turned.facing;
        message(player, describeTurn(result, now));
        debug("rotation rod at {} -> {} ({})", pos.toShortString(), result, now);
        return true;
    }

    private static Component describeTurn(RotationRod.Result result, Direction facing) {
        return switch (result) {
            case TURNED -> Component.translatable(
                    "message.redstonecircuit.rotation_rod.to_input",
                    Component.literal(facing == null ? "?" : facing.getName()))
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

        // ---------------------------------------------------------------- the one exception --
        //
        // A coordinate a structure OWNS belongs to the carrier side, and the world side stands down before
        // it claims anything: the world path does not merely skip its action, it never claims the click, so
        // nothing can write the world store at a structure's own coordinate.
        //
        // WHY IT HAS TO EXIST. A Sable sub-level's blocks are level blocks at their own local coordinates,
        // so a click on a physicalised block arrives here carrying a position that is both "a level
        // position" and "a host's coordinate inside a structure", and this handler cannot tell those apart;
        // writing the component into the world store there leaves something the overlay does not draw and
        // that nothing outside the machine can connect to.
        //
        // HOW NARROW IT IS. Only "the carrier layer can name a live host at this position" declines
        // anything - one read-only query, no write, no drop, nothing consumed. Every other world position
        // (including a carried host's origin, and a host that has been put down) answers null and behaves
        // exactly as it did before.
        //
        // OWNING THE COORDINATE IS NOT ENOUGH: what has to be decided is whether this CLICK is ours, and the
        // two differ. Declining every click on a structure's cell also stops vanilla's own item use - the act
        // of placing a block - so a plain placement (a piston, a plank, an unsneaked lever) must still reach
        // vanilla. CarrierClickPriority decides it from the gesture and the clicked cell's own contents.
        //
        // STANDING DOWN STILL INCLUDES THE ITEM: with a component in hand, leaving the click to vanilla's
        // own item use would put the carried block down at the structure's own coordinate. The decision is
        // therefore acted on below, and every click that is not the carrier layer's is left untouched.
        CarrierGesture.Kind carrierGesture = CarrierGesture.of(player, hand);
        CarrierClickPriority.Owned carrierOwns = CarrierClickPriority.owner(serverLevel, pos);
        if (carrierOwns != null) {
            // The clicked cell's own rule is part of the click: this side copies the carrier path's answer
            // for that cell (whether it may hold a component at all), so the two halves agree and vanilla
            // puts an ordinary block down instead of a placement that was never the carrier's.
            CarrierClickPriority.StandDown standDown = CarrierClickPriority.standDown(carrierOwns,
                    carrierGesture, CarrierGesture.Hold.of(stack), player.isSecondaryUseActive(),
                    CarrierStore.hostHasComponent(serverLevel, carrierOwns.carrier(), carrierOwns.localPos()),
                    CarrierStore.hostAcceptsComponent(carrierOwns.carrier(), carrierOwns.localPos()),
                    RCConfig.carriedInsert(), RCConfig.carriedWrench(), RCConfig.carriedSwitches());
            if (standDown.useBlock() && standDown.useItem()) {
                // NOT OURS: the click is left exactly as it arrived, which is what puts a piston, a plank
                // or a grass block onto a physicalised block again - and what leaves an ordinary world
                // coordinate untouched, byte for byte, as it was before this gate existed.
                return InteractionResult.PASS;
            }
            long tick = serverLevel.getGameTime();
            // The record and the line, in that order: the record is what lets the carrier's own trace say
            // "the world side declined this very delivery", and the line carries the hand, the item and the
            // tick. The carrier's callback answers a click in a phase ordered before this one, so a decline
            // here is only ever read beside a carrier line that has already run.
            CarrierClickPairing.noteWorldDecline(tick, pos, hand, stack);
            CarrierClickPairing.logDecline(carrierOwns.describe(), pos, tick, hand, stack,
                    standDown.useBlock(), standDown.useItem(), false);
            // THE STAND-DOWN ITSELF: the coordinate is a structure's and the click is the carrier layer's
            // gesture, so nothing underneath may act on it - no world store write and no vanilla item use at
            // a coordinate that belongs to the structure. {@code FAIL} is that answer rather than
            // {@code PASS}: PASS would hand the click back to vanilla, which places the held block there.
            // Nothing is claimed as done either, because the world did nothing.
            return InteractionResult.FAIL;
        }

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
            WrenchOutcome outcome = useWrench(serverLevel, player, hand, pos, face);
            if (outcome == WrenchOutcome.CONSUMED) {
                return InteractionResult.SUCCESS;
            }
            // CARRIER and KEEP are both "not the world's click": the carrier's own callback answers the
            // first, and vanilla the second (a door, a chest, a button).
            return InteractionResult.PASS;
        }

        // The rotation rod does too: one click, one turn.
        if (rod) {
            return useRotationRod(serverLevel, player, pos)
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

        // Diagnostic only, and only once per block type: tell the optional physics-compat layer that
        // this block type now really holds components, so it can attach its contraption hook to it.
        // With Sable and Create both absent this is a no-op that loads none of their classes.
        PhysicsCompat.hostBlockStored(level, pos, hostState.getBlock());
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
     *
     * <p>The goggles gate stands here, above the state change, because this method is the one door a bare
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
            // 控制器's bare-hand gesture IS its screen - opening it is the adjustment - so it is claimed
            // here rather than stepped by InnerSwitches#toggle, which has nothing to step for it.
            if (slot.type == ComponentType.CONTROLLER) {
                com.jiangyuefengyu.redstonecircuit.block.ControllerBlock.sendHostView(player, level, pos, true);
                return true;
            }
            // 红石变压器's gesture is the same screen with one number on it.
            if (slot.type == ComponentType.TRANSFORMER) {
                com.jiangyuefengyu.redstonecircuit.block.TransformerBlock.sendHostView(player, level, pos, true);
                return true;
            }
            // 时钟源's is 秒 / tick / 强度 on the same screen, and the same open-not-step shape.
            if (slot.type == ComponentType.CLOCK) {
                com.jiangyuefengyu.redstonecircuit.block.ClockBlock.sendHostView(player, level, pos, true);
                return true;
            }
            // 信号延长器's is 秒 / 刻 on the same screen, opened the same way.
            if (slot.type == ComponentType.EXTENDER) {
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
     * is the broadcast when a component is <em>put</em> back.
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

package com.jiangyuefengyu.redstonecircuit.carrier;

import java.util.List;
import java.util.UUID;

import com.jiangyuefengyu.redstonecircuit.HostRules;
import com.jiangyuefengyu.redstonecircuit.InnerRedstoneInteraction;
import com.jiangyuefengyu.redstonecircuit.RCConfig;
import com.jiangyuefengyu.redstonecircuit.RCRegistry;
import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.ConnectionState;
import com.jiangyuefengyu.redstonecircuit.data.HostEntry;
import com.jiangyuefengyu.redstonecircuit.data.Slot;
import com.jiangyuefengyu.redstonecircuit.logic.CarriedInsert;
import com.jiangyuefengyu.redstonecircuit.logic.CarriedSwitch;
import com.jiangyuefengyu.redstonecircuit.logic.CarriedTool;
import com.jiangyuefengyu.redstonecircuit.logic.InnerRedstoneNetwork;
import com.jiangyuefengyu.redstonecircuit.logic.InnerSwitches;
import com.jiangyuefengyu.redstonecircuit.logic.WrenchLinks;
import com.jiangyuefengyu.redstonecircuit.network.HostSync;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

import org.jetbrains.annotations.Nullable;

/**
 * The carrier-only entry point for everything a player does to a host block a structure is carrying: the
 * redstone wrench, the rotation rod, and putting a component in or taking one out.
 *
 * <p><b>Nothing here is reached for a click on a world block.</b> Every entry point first resolves the
 * clicked position to a host a structure is carrying, and answers {@code null} for everything else - an
 * ordinary host, an ordinary block, thin air. A {@code null} target returns before the held item is even
 * looked at, so the world's own paths stay untouched: they are a <em>different listener</em> and see exactly
 * the events they saw before, because this one never cancels anything it did not act on. Every click that
 * does reach an entry point is reported before anything can return, so "the hook was never called" and "the
 * hook was called and the gesture rule said this is nobody's click" never print the same nothing.
 *
 * <p><b>Two entries.</b> A Sable sub-level delivers an ordinary block click at the structure's own
 * coordinate ({@code BlockGetterMixin#clip} rewrites the clip into the sub-level's pose frame, so the
 * {@code BlockHitResult} carries the plot coordinate the carried index is keyed by). A Create contraption
 * delivers a packet instead, and is served by a {@code MovingInteractionBehaviour} registered for the host's
 * block type - keyed by block, the same shape as the movement registry - which is {@code CarrierToolHook}.
 *
 * <p><b>What one click does.</b> Plain click with the wrench picks a carried host (the near end of a line);
 * shift-click with the wrench steps a line to a neighbouring host, or clears every override when the picked
 * host itself is clicked again; the rotation rod turns a component so that the clicked face is its input
 * side; shift-click with a component in hand stores it in the carried block with the clicked face as its
 * input side; shift-click with both hands empty takes it back out; and a plain click with both hands empty
 * works what is inside the block exactly as it does on a block standing in the world - a lever flips, a
 * repeater's delay steps, a comparator's mode swaps, a button is refused by name (see {@link CarriedSwitch}).
 * <b>Both hands</b> matter: one right-click is delivered twice, and the delivery for an empty off hand must
 * not work the switch while the main hand holds a tool (see {@link CarrierGesture}).
 *
 * <p>Every action writes the carrier index - never the world - then broadcasts the carrier and the host so
 * the client's overlay redraws at once, and logs one line naming the carrier, the host's local coordinate,
 * the action, the direction and which entry point saw it. The switches {@code carriedWrench},
 * {@code carriedRotationRod}, {@code carriedInsert} and {@code carriedSwitches} are all on by default, and
 * turning one off restores the behaviour before that switch existed.
 *
 * <p><b>The judgement line, and how to tell the three failures apart.</b> Every click that gets as far as an
 * action writes a {@code riding <carrier> local=... action=...} line; see {@link #trace}. A click that
 * reaches an entry point but resolves to nothing writes one too, saying {@code MISS} and naming the raw
 * coordinate, the hand, the held item and what it was looked up with. So the next log answers, without
 * further instrumentation, which of these happened: <b>no line at all</b> - neither entry point was called,
 * so the click did not arrive (Sable's clip override not in effect, or the contraption behaviour not
 * registered for that block type); <b>a {@code NONE} line</b> - the hook ran and the gesture rule dropped the
 * click, and the line says what each hand held and which branch answered; <b>a {@code MISS} line</b> - the
 * coordinate did not resolve to a carried host, and the line says which frame arrived and what the resolver
 * saw; <b>a {@code riding} line naming the wrong {@code local=}</b> - it resolved, in the wrong coordinate
 * frame; <b>a {@code riding} line with the right {@code local=}, a refusal, and no visible change</b> - the
 * action ran and was refused by a config or a rule the line names; <b>no action line for the second of two
 * deliveries of one click</b> - {@code ClickOnce} already had it, which is reported by name.
 *
 * <p>The format, the three outcomes, the throttling and the bounds live in {@link CarrierEntryTrace}, so the
 * two entry points here, the break path and the self-healing sweep all write one shape.
 */
public final class CarrierInteraction {

    /**
     * Tick/hand/position of the last processed click.
     *
     * <p>A right-click on a block dispatches once for the main hand and once for the off hand, in the same
     * tick and at the same position - the same fact {@code InnerRedstoneInteraction} handles, and the reason
     * a rotation rod would otherwise turn a component twice per click. The first hand processed owns the
     * click; the second is ignored whatever the first did.
     *
     * <p>Static because both entry points are static and share one gesture: the event listener and the Create
     * behaviour are two deliveries of <em>one</em> click, and a per-instance guard would let both act on it.
     */
    private static long lastHandledTick = Long.MIN_VALUE;
    private static BlockPos lastHandledPos = BlockPos.ZERO;
    @Nullable
    private static InteractionHand lastHandledHand;

    /**
     * The same question for the placement gestures, keyed on <b>the host they resolved to</b>.
     *
     * <p>See {@link ClickOnce}: the two deliveries of one click on a contraption are expressed in different
     * coordinate frames, so the position the event carried cannot identify the gesture - the carrier and the
     * host's local coordinate can, and both deliveries name those identically. One static instance, for the
     * same reason the hand guard above is static.
     */
    private static final ClickOnce PLACEMENT = new ClickOnce();

    /**
     * The instance the mod registers for the block-click entry point.
     *
     * <p>Public and stateless: the only per-instance thing an event listener has ever carried here is the
     * "second hand of one click" guard, and that is <b>static</b> - the event listener and
     * the contraption behaviour are two deliveries of one click, and a per-instance guard would let both
     * act on it.
     */
    public CarrierInteraction() {
    }

    /** Keeps the selection ledger honest when a player leaves, as the world's selection does. */
    @SubscribeEvent
    public void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        CarriedSelection.forget(event.getEntity().getUUID());
        lastHandledHand = null;
        lastHandledPos = BlockPos.ZERO;
        lastHandledTick = Long.MIN_VALUE;
        PLACEMENT.clear();
        // The same reason as the two ledgers above: a record of "which click the world declined" must not
        // outlive the session it describes. It is two fields and a short list, and forgetting it costs
        // nothing on a path that runs once per logout.
        CarrierClickPairing.forget();
    }

    /**
     * <b>Entry one: the click the structure's owner delivered as an ordinary block click.</b>
     *
     * <p>Sable sub-levels arrive here, at the host's own local coordinate (see the class comment). A Create
     * contraption never does - its blocks are not level blocks - and is served by {@link #handleToolClick}
     * through {@code CarrierToolHook} instead.
     */
    @SubscribeEvent
    public void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        ServerPlayer player = event.getEntity() instanceof ServerPlayer serverPlayer
                ? serverPlayer : null;
        Level level = event.getLevel();
        if (player == null || !(level instanceof ServerLevel serverLevel)) {
            return;
        }
        // THE FIRST THING THIS LISTENER DOES WITH THE EVENT. A click whose gesture the rule does not own has
        // to say so: the position, the face, the held item and the resolver are all read after the gesture
        // gate, so without this line the gate's own answer would be the one fact the log loses.
        CarrierGesture.Kind gesture = CarrierGesture.of(player, event.getHand());
        noteGesture(player, event.getHand(), event.getPos(), event.getFace(), gesture,
                "RIGHT_CLICK_BLOCK");
        if (gesture == CarrierGesture.Kind.NONE) {
            // THE SECOND DELIVERY OF ONE CLICK. When this delivery is nobody's *because the other hand's
            // gesture owns the click* - a wrench, the rod, or a component in the other hand - the event is
            // consumed exactly as the world side consumes it (see InnerRedstoneInteraction's own
            // second-hand guard), so nothing runs underneath it. A plain block in the other hand is a
            // different case: it owns no click of ours, so this delivery is simply left to vanilla.
            if (CarrierGesture.suppressedByTheOtherHand(player, event.getHand())) {
                event.setCanceled(true);
            }
            return;
        }
        BlockPos pos = event.getPos();
        Direction face = event.getFace();
        if (pos == null) {
            trace("RIGHT_CLICK_BLOCK", player, event.getHand(), null, face, null,
                    CarrierEntryTrace.Outcome.REFUSED, "refused: the event carried no position");
            return;
        }
        if (face == null) {
            // A click with no face is not a click on a block face; nothing here can act on it, and the
            // event is not consumed. Reported because "the hook was called and refused" and "the hook was
            // never called" have to be distinguishable.
            trace("RIGHT_CLICK_BLOCK", player, event.getHand(), pos, null, null,
                    CarrierEntryTrace.Outcome.REFUSED,
                    "refused: the event carried no direction; nothing to turn");
            return;
        }
        ComponentType stored = CarrierGesture.componentOf(player, event.getHand());
        try {
            // Sable answers in the structure's own frame, so this is the carrier's own coordinate. The
            // resolver still asks both frames: the event position is a *level* position either way, and
            // whether it is a plot coordinate or a world one is exactly what it works out.
            CarrierEntryTrace.Miss miss = RCConfig.debugLog() ? new CarrierEntryTrace.Miss() : null;
            CarriedBlockRemoval.Hit hit = CarrierStore.hitAt(serverLevel, event.getPos(), miss);
            // WHICH CLICKS MAY CREATE A CARRIER RECORD. Only a placement that is really going to store a
            // component, and that answer is the placement rule's own - see #mayCreateRecord. A wrench, the
            // rotation rod, a bare hand and every item the carrier layer has no placement for are all "no",
            // and each then falls through to its own refusal below, by name.
            //
            // AND THE BLOCK ITSELF HAS TO BE ABLE TO HOLD ONE: a physicalised block's state is not read from
            // the level at all, so the world's own answer was never asked about it. This asks it, of the
            // structure's own view, through the same `HostRules.isValidHost` - last, and only while there is
            // no record yet, so the everyday right-click does not pay for the lookup.
            if (hit == null && mayCreateRecord(gesture, stored, player.isSecondaryUseActive(), face,
                    RCConfig.carriedInsert(), RCConfig.allowReplace(),
                    CarrierStore.hostAcceptsComponent(serverLevel, event.getPos(), miss))) {
                // THE CLICK THAT IS AIMED AT A BLOCK INSIDE A STRUCTURE AND HAS NO RECORD YET. A Sable
                // sub-level's blocks are the level's blocks at their own local coordinates, and a record is
                // only ever created by taking a component out of a WORLD position - so a block that was
                // already inside the structure when it was assembled has none, and it is exactly the block a
                // player is trying to put a component into. `resolveForPlacement` files the missing record
                // (the coordinate itself, no pose conversion) and hands back the host; it answers null for
                // everything else, so the world's own clicks are untouched.
                //
                // THE RECORD AND THE COMPONENT LAND TOGETHER OR NOT AT ALL: the gate above is the
                // placement's own decision, so if it is false nothing is written here, and if it is true the
                // store that follows cannot be refused (the host it creates is empty and may hold a
                // component, which is the state the decision was computed against). Filing the record for a
                // wrench or rod shift-click would leave an EMPTY host record behind, and that record would
                // then change what every later judgement about that block said.
                hit = CarrierStore.resolveForPlacement(serverLevel, event.getPos(), miss, true);
            }
            if (hit == null) {
                // THE TWO FAR ENDS OF ONE GESTURE. Both are shift-clicks with the wrench while a carried host
                // is picked, and both are decided here, before the miss is reported:
                //
                //   * the far end is a block INSIDE the picked structure (a piston, a lamp, a plain block with
                //     no component). The ownership question answers the SELECTED structure, and the decision is
                //     `planLinkFromPick`;
                //   * the far end is a block of the WORLD, owned by no structure at all. This is `planOutside`,
                //     and it is asked second so a plot cell of the picked structure is never mistaken for
                //     "outside".
                //
                // The world side cannot serve either one: its wrench is written against
                // `InnerRedstoneStore`, which holds nothing at a carried host's position.
                OutsideDecision inside = planLinkFromPick(serverLevel, player, event.getPos());
                if (inside.handled()) {
                    // Both entrances write the trace for every lock attempt, acted on or not: a successful
                    // lock returns right here, so a trace written only at the end of this block would be
                    // missing exactly for the case it is about.
                    lockAttempt(player, event.getHand(), event.getPos(), gesture,
                            CarrierGesture.componentOf(player, event.getHand()) != null, null, inside,
                            pickedPosForTrace(player, true));
                    reportOutside(serverLevel, player, inside, "RIGHT_CLICK_BLOCK");
                    // THE CLICK IS OURS AND IT WAS ACTED ON, so it is consumed - which is what
                    // OutsideDecision#handled documents ("the one case that consumes the event") and what
                    // keeps a second listener from writing anything at the same coordinate. The world side
                    // has already stood down for it (`useBlock`/`useItem` FALSE, uncancelled), so nothing
                    // runs underneath this either way; cancelling states it once.
                    event.setCanceled(true);
                    return;
                }
                OutsideDecision outside = planOutside(serverLevel, player, event.getPos());
                if (outside.handled()) {
                    lockAttempt(player, event.getHand(), event.getPos(), gesture,
                            CarrierGesture.componentOf(player, event.getHand()) != null, null, outside,
                            pickedPosForTrace(player, false));
                    reportOutside(serverLevel, player, outside, "RIGHT_CLICK_BLOCK");
                    event.setCanceled(true);
                    return;
                }
                // The reason the line carries is whichever gesture got as far as answering; the two never both
                // act, and NOTHING_PICKED / NOT_INSIDE are "this was the other gesture's click".
                //
                // AND NOTHING THAT COULD HAVE BEEN A LOCK ENDS WITHOUT AN ANSWER: every status that is not
                // "the other gesture's click" gets a named refusal line, including a far end owned by a
                // DIFFERENT structure, which `reportOutside` is called for here.
                OutsideDecision named = inside.status() != OutsideStatus.NOTHING_PICKED
                                && inside.status() != OutsideStatus.NOT_INSIDE
                        ? inside : outside;
                boolean insideGesture = named == inside;
                if (named.status() == OutsideStatus.FAR_END_NOT_THE_PICKED_STRUCTURE) {
                    // A click on a physicalised block of another structure: no line to draw, and the player
                    // is told so instead of being left with the previous click's message on the bar.
                    reportOutside(serverLevel, player, named, "RIGHT_CLICK_BLOCK");                }
                traceMiss("RIGHT_CLICK_BLOCK", player, event.getHand(), event.getPos(), face,
                        () -> (miss == null ? "no explanation" : miss.describe()) + " "
                                // A refused lock click is reported here rather than on a second line: the
                                // trace sink throttles, and a second `MISS` for one click would be a double
                                // line. What this adds is the one fact the resolver cannot know - that a host
                                // was picked, so the click WAS the lock-towards-a-block gesture, which far end
                                // it meant, and which question refused it.
                                + (named.status() == OutsideStatus.NOTHING_PICKED ? ""
                                        : (insideGesture ? "INSIDE: " : "OUTSIDE: ") + named.status()
                                                + " (a carried host IS picked, so this was the "
                                                + "lock-towards-a-block gesture) "
                                                + "blocked-by=" + named.blockedBy() + " ")
                                + CarrierEntryTrace.lookupAt(serverLevel,
                                        "CarrierStore.hitAt(level, rawPos)", event.getPos()));
                // THE ONE LINE THAT ANSWERS "what became of a lock attempt" in one grep, written here -
                // exactly once, at the end - so a lock attempt can never be silent and never prints twice.
                lockAttempt(player, event.getHand(), event.getPos(), gesture,
                        CarrierGesture.componentOf(player, event.getHand()) != null, null, named,
                        pickedPosForTrace(player, insideGesture));
                return;
            }
            if (handleResolvedClick(serverLevel, player, event.getHand(), hit, face, gesture, stored,
                    "RIGHT_CLICK_BLOCK")) {
                event.setCanceled(true);
            }
        } catch (RuntimeException | LinkageError failed) {
            // The world's click path is untouched by this listener; whatever went wrong here must not
            // take the click down, and one warn line is enough to report it. The guard is the shared one
            // (see CarrierGuards), so this hook's failure and every other carrier hook's failure are
            // reported the same way and counted in the same place.
            CarrierGuards.BLOCK_CLICK.fail(pos, failed);
        }
    }

    /**
     * <b>Entry two: the contraption behaviour's callback - a packet, not a block click.</b>
     *
     * <p>Called by {@code CarrierToolHook}, which Create reaches through
     * {@code Contraption.interactors} - the map it builds at assembly time from
     * {@code MovingInteractionBehaviour.REGISTRY}. The coordinate is the contraption's own local
     * coordinate, the same one the movement hook claims hosts with, so the host is named outright; the
     * world position the player sees is that coordinate through the carrier's pose.
     *
     * <p>The round trip through the world direction is not redundant: it is the same quaternion snapping
     * every other direction conversion in this mod uses ({@code CarrierDirections#nearest}), so a structure
     * turned by a fraction of a degree gets the same answer on both entry points instead of "the raw local
     * face" here and "the snapped one" there.
     *
     * <p>The <b>held item decides which gesture this is</b>: a wrench or the rod is a tool click, a
     * redstone component (or a bare hand while sneaking) is a placement click. Nothing else reaches the
     * carrier layer at all, which is what keeps a Create machine's own blocks behaving as they did.
     *
     * @return true when the click was acted on, i.e. when the gesture belongs to the carrier layer
     */
    public static boolean handleContraptionClick(ServerPlayer player, UUID entityUuid,
                                                 BlockPos localPos, Direction localFace,
                                                 InteractionHand hand) {
        if (player == null || entityUuid == null || localPos == null || hand == null) {
            return false;
        }
        if (!(player.level() instanceof ServerLevel level)) {
            return false;
        }
        // The same rule as the block-click entry, read the same way - see CarrierGesture. A contraption and
        // a sub-level differ in how the click arrives and in nothing else, so this must not answer
        // differently about the same hand - or about the other one: the rule is asked over both hands.
        CarrierGesture.Kind gesture = CarrierGesture.of(player, hand);
        // The same report, in the same order, for the same reason: a Create contraption delivers this as a
        // packet rather than a block click, so "the packet never arrived" and "the rule dropped it" would
        // be the same silence here too. See #noteGesture.
        noteGesture(player, hand, localPos, localFace, gesture, "CONTRAPTION_PACKET");
        if (gesture == CarrierGesture.Kind.NONE) {
            // The other hand's gesture owns this click: consumed, nothing run, exactly like the world
            // side's own second-hand guard. A plain block in the other hand owns nothing of ours, so this
            // answer is "not handled" and the packet is left to Create and to vanilla.
            return CarrierGesture.suppressedByTheOtherHand(player, hand);
        }
        ComponentType stored = CarrierGesture.componentOf(player, hand);
        try {
            CarrierId carrier = CarrierStore.indexOf(level) == null ? null
                    : CarrierStore.indexOf(level).carrierForUuid(entityUuid);
            CarrierPose pose = carrier == null ? null : CarrierStore.poseOf(level, carrier);
            BlockPos worldPos = carrier == null || pose == null ? null
                    : CarrierProjection.project(pose, localPos);
            CarriedBlockRemoval.Hit hit = CarrierStore.hitInStructure(level, carrier, localPos, worldPos);
            if (hit == null) {
                // WHAT THE PACKET ACTUALLY CARRIED, all of it: the contraption's own local coordinate, the
                // world position the pose projects it to (when there is one), and the entity the packet
                // named. A miss in this entry is answered by comparing those three against the index.
                traceMiss("CONTRAPTION_PACKET", player, hand, localPos, localFace,
                        () -> "entity=" + entityUuid + " projectedWorld="
                                + CarrierEntryTrace.posText(worldPos) + " "
                                + (carrier == null
                                        ? "MISS: no structure of this client's is filed under that entity"
                                        : "MISS: the structure carries no live host at this local "
                                                + "coordinate")
                                // THE IDENTITY GUESS, CHECKED. This entry names the carrier from the
                                // entity UUID, so "the UUID lookup failed" and "the UUID was right and
                                // the coordinate was not" are different defects - and the scan answers
                                // which of the two it was without naming the entity again.
                                + " recordsAt(" + localPos.toShortString() + "): "
                                + CarrierEntryTrace.localVerdict(
                                        CarrierStore.scanLocalCoordinate(
                                                CarrierStore.indexOf(level), localPos), localPos)
                                + " " + CarrierEntryTrace.lookupAt(level,
                                        "CarrierStore.hitInStructure(level, carrierForUuid(entity), "
                                                + "localPos, projectedWorld)", localPos));
                return false;
            }
            Direction face = CarrierDirections.worldDirection(pose, localFace);
            return handleResolvedClick(level, player, hand, hit, face, gesture, stored,
                    "CONTRAPTION_PACKET");
        } catch (RuntimeException | LinkageError failed) {
            CarrierGuards.CONTRAPTION_PACKET.fail(localPos, failed);
            return false;
        }
    }

    /**
     * The whole carrier half of one click, once the host has been identified.
     *
     * <p>Every early return here is a click the world keeps: the gesture's switch is off, the carrier
     * layer is not installed, or the click is the second delivery of one already handled. Everything
     * above this point is "which host is this click about", and it is the only thing that
     * differs between the two entry points; which gesture the click <em>is</em> was decided by
     * {@link CarrierGesture} before either of them got here.
     *
     * @param hit the host, already resolved
     * @param face the clicked face, as a <b>world</b> direction
     * @param gesture what the held item and the sneak state make this click
     * @param stored the component in hand, when the gesture is a placement
     * @param entry which entry point saw this click, for the trace line
     * @return whether the click was acted on, i.e. whether the caller should cancel it
     */
    private static boolean handleResolvedClick(ServerLevel level, ServerPlayer player,
                                               InteractionHand hand, CarriedBlockRemoval.Hit hit,
                                               Direction face, CarrierGesture.Kind gesture,
                                               @Nullable ComponentType stored, String entry) {
        return switch (gesture) {
            case WRENCH, ROD -> handleToolClick(level, player, hand, hit, face,
                    gesture == CarrierGesture.Kind.WRENCH, entry);
            case PLACEMENT -> handlePlacementClick(level, player, hand, hit, face, stored, entry);
            // A plain right-click with an empty hand: the world's switch gesture, on the structure's record.
            // Its own rule lives in CarriedSwitch. Reachable only for a hand that really is empty - see
            // CarrierGesture.
            case SWITCH -> handleSwitchClick(level, player, hand, hit, face, entry);
            // Filtered before the coordinate was even resolved; kept total so a future gesture cannot
            // silently fall through to "handled".
            case NONE -> false;
        };
    }

    /**
     * <b>A bare-hand click on a carried host: the world's switch gesture, on the structure's record.</b>
     *
     * <p>Reached only when <b>both hands are empty</b> and the player is not sneaking (see
     * {@link CarrierGesture}: a wrench, the rod, a component or any ordinary block in <em>either</em> hand
     * means this is not the bare-hand gesture), which is exactly the click the world's own handler gives to
     * {@code InnerSwitches}. Everything the decision turns on is in {@link CarriedSwitch} - a pure function
     * over the index - so this method is only "check the switch, hand it over, broadcast, report".
     *
     * <p><b>What is not consumed.</b> A host holding nothing workable, a component with no bare-hand action
     * and a button all return {@code false}: the click is left to whatever would have had it, which keeps a
     * bare-hand click on a carried block from swallowing gestures this layer does not own.
     */
    private static boolean handleSwitchClick(ServerLevel level, ServerPlayer player,
                                             InteractionHand hand, CarriedBlockRemoval.Hit hit,
                                             Direction face, String entry) {
        CarriedHostIndex index = CarrierStore.indexOf(level);
        if (!RCConfig.carriedSwitches()) {
            trace(entry, player, hand, hit.localPos(), face, hit, CarrierEntryTrace.Outcome.REFUSED,
                    "refused: carriedSwitches=false (the switch is off)");
            return false;
        }
        if (index == null) {
            trace(entry, player, hand, hit.localPos(), face, hit, CarrierEntryTrace.Outcome.REFUSED,
                    "refused: the carrier layer is not installed");
            return false;
        }
        // An EMPTY carried host is first-class here too: the structure carries the block and the block holds
        // nothing, which is the state a player leaves behind by taking the component out. The world's own
        // click on an empty host does nothing at all (InnerSwitches#toggle answers NONE), so this must be a
        // refusal too - not an exception, and not a silent "handled".
        if (!CarrierStore.hostHasComponent(level, hit.carrier(), hit.localPos())) {
            trace(entry, player, hand, hit.localPos(), face, hit, CarrierEntryTrace.Outcome.REFUSED,
                    "refused: this carried host is EMPTY (the structure carries the block, and the block "
                            + "holds no component), so a bare hand has nothing to work - the world's own "
                            + "empty host does nothing either");
            return false;
        }
        // Nothing workable here: the click is not ours. Reported as a refusal rather than a miss, because
        // the host was resolved and this layer decided not to act on it.
        if (!CarriedSwitch.workable(index, hit.carrier(), hit.localPos())) {
            trace(entry, player, hand, hit.localPos(), face, hit, CarrierEntryTrace.Outcome.REFUSED,
                    "refused: nothing at this carried host has a bare-hand action ("
                            + (CarriedInsert.slotOf(index, hit.carrier(), hit.localPos()) == null
                                    ? "the host is empty" : "the component inside is not workable")
                            + ")");
            return false;
        }
        // The same "one gesture, one action" guard the placement path uses: two deliveries of one click
        // must not step a repeater twice.
        long tick = level.getGameTime();
        if (!PLACEMENT.claim(tick, hit.carrier(), hit.localPos(), hand)) {
            return true;
        }
        Slot before = CarriedInsert.slotOf(index, hit.carrier(), hit.localPos());
        // AND THE GOGGLES GATE, THE SAME ONE THE WORLD'S OWN BARE HAND ASKS FOR: a host riding a machine is
        // worked by the same gesture as one on the ground, so it is gated by the same rule, with the same
        // whitelist (InnerSwitches#isHandSwitch: a lever yes, a delay or a mode no) and the same silence. It
        // stands above CarriedSwitch#apply because a refusal that arrived after the record was written would
        // be a refusal of the message rather than of the change, and the one-gesture guard above has already
        // claimed this click, so nothing below it may run either.
        if (before != null && InnerRedstoneInteraction.refuseInnerAdjust(player,
                InnerSwitches.isHandSwitch(before.type))) {
            trace(entry, player, hand, hit.localPos(), face, hit, CarrierEntryTrace.Outcome.REFUSED,
                    "refused: adjusting a setting needs the goggles (refused silently, by design)");
            return true;
        }
        // A controller's bare-hand gesture IS its screen - opened rather than stepped, because there is
        // nothing to step: the four settings are typed on the screen. Its edits go through
        // ControllerBlock#applyHostEdit, which knows both stores and both coordinate frames.
        if (before != null && before.type == ComponentType.CONTROLLER) {
            com.jiangyuefengyu.redstonecircuit.block.ControllerBlock.sendHostView(player, level, hit.localPos(), true);
            trace(entry, player, hand, hit.localPos(), face, hit, CarrierEntryTrace.Outcome.MATCH,
                    "screen: opened the controller's own control screen");
            return true;
        }
        // 红石变压器's gesture is the same screen with one number on it.
        if (before != null && before.type == ComponentType.TRANSFORMER) {
            com.jiangyuefengyu.redstonecircuit.block.TransformerBlock.sendHostView(player, level, hit.localPos(), true);
            trace(entry, player, hand, hit.localPos(), face, hit, CarrierEntryTrace.Outcome.MATCH,
                    "screen: opened the transformer's own control screen");
            return true;
        }
        // 时钟源's gesture is 秒 / tick / 强度 on the same screen, at the same addressing - the plot coordinate
        // the click arrived at.
        if (before != null && before.type == ComponentType.CLOCK) {
            com.jiangyuefengyu.redstonecircuit.block.ClockBlock.sendHostView(player, level, hit.localPos(), true);
            trace(entry, player, hand, hit.localPos(), face, hit, CarrierEntryTrace.Outcome.MATCH,
                    "screen: opened the clock's own control screen");
            return true;
        }
        // 信号延长器's gesture is 秒 / 刻 on the same screen, at the same addressing.
        if (before != null && before.type == ComponentType.EXTENDER) {
            com.jiangyuefengyu.redstonecircuit.block.ExtenderBlock.sendHostView(player, level, hit.localPos(), true);
            trace(entry, player, hand, hit.localPos(), face, hit, CarrierEntryTrace.Outcome.MATCH,
                    "screen: opened the extender's own control screen");
            return true;
        }
        CarriedSwitch.Action action = CarriedSwitch.apply(index, hit.carrier(), hit.localPos());
        if (!action.acted()) {
            trace(entry, player, hand, hit.localPos(), face, hit, CarrierEntryTrace.Outcome.REFUSED,
                    "refused: " + action.describe());
            return false;
        }
        // The record changed, so the in-structure solve has to re-derive; the client is told, and the
        // world around the projected position re-reads the signal this component offers, by the one
        // guarded broadcast helper all three gestures use (see #broadcastHostChange).
        CarriedHosts.get(level).setDirty();
        Slot after = CarriedInsert.slotOf(index, hit.carrier(), hit.localPos());
        playSound(level, hit, switchSound(action));
        // The world's own wording, reused verbatim: "the delay is now three" has to mean the same thing
        // inside a machine as it does on the ground, and this is the one place that text is written.
        message(player, InnerSwitches.describe(after == null ? before : after));
        trace(entry, player, hand, hit.localPos(), face, hit, CarrierEntryTrace.Outcome.MATCH,
                "switch " + action.describe() + " " + describeSwitch(before, after));
        logAction(entry, hit, face, "switch " + action.describe(),
                CarrierGesture.toolLabel(CarrierGesture.Kind.SWITCH), poseOf(level, hit), null);
        // Same three writes, same order, same guard as the tools: this is a click that changed a record,
        // so the client and the world both have to be told, and a failure to tell them is a reported
        // failure rather than a crash.
        CarrierGuards.SWITCH.call(hit.localPos(), () -> {
            broadcastHostChange(level, hit, index);
            return Boolean.TRUE;
        }, false);
        return true;
    }

    /** A switch that changes a component's own state makes a sound, as it does in the world. */
    private static net.minecraft.sounds.SoundEvent switchSound(CarriedSwitch.Action action) {
        return action == CarriedSwitch.Action.ADJUSTED
                ? SoundEvents.COMPARATOR_CLICK
                : SoundEvents.LEVER_CLICK;
    }

    /** "before -> after", so the line says what the setting became and not only that it changed. */
    private static String describeSwitch(@Nullable Slot before, @Nullable Slot after) {
        return (before == null ? "empty" : before.describe()) + " -> "
                + (after == null ? "empty" : after.describe());
    }

    /** The wrench and the rotation rod, the tool half of the click. */
    private static boolean handleToolClick(ServerLevel level, ServerPlayer player,
                                           InteractionHand hand, CarriedBlockRemoval.Hit hit,
                                           Direction face, boolean wrench, String entry) {
        if (wrench ? !RCConfig.carriedWrench() : !RCConfig.carriedRotationRod()) {
            // Switched off: the click falls through to whatever would have handled it - the world's own
            // wrench or rod path.
            trace(entry, player, hand, hit.localPos(), face, hit, CarrierEntryTrace.Outcome.REFUSED,
                    "refused: the tool's switch is off ("
                            + (wrench ? "carriedWrench" : "carriedRotationRod") + "=false)");
            return false;
        }
        CarriedHostIndex index = CarrierStore.indexOf(level);
        if (index == null) {
            trace(entry, player, hand, hit.localPos(), face, hit, CarrierEntryTrace.Outcome.REFUSED,
                    "refused: the carrier layer is not installed");
            return false;
        }
        // AN EMPTY HOST, NAMED BEFORE ANYTHING IS DEREFERENCED. The structure still carries this block and it
        // holds nothing - the player took the component out earlier (CarriedHostIndex#removeSlot). "There is
        // no component here" is an answer, not a crash: the click is not ours (return false, so the world and
        // any other mod keep it), the player is told what the world's own empty host says, and the trace line
        // names which of the two it was.
        if (!CarrierStore.hostHasComponent(level, hit.carrier(), hit.localPos())) {
            // THE SAME GESTURE, WHEN THE BLOCK ON THIS SIDE HAS NOTHING IN IT. A player who has already built
            // a component into a machine can shift-click a block of that same machine that holds no component
            // of its own - a piston, a lamp, a plain block - and the gesture is the same one: "pin my picked
            // line towards that". The clicked host is empty, so nothing here acts on it; what decides the
            // click is the PICK. The goggles gate is asked here as well, because this is still the wrench
            // doing the work and one gate is the rule (`gogglesRequired`).
            if (wrench && player.isSecondaryUseActive()
                    && (wearsGoggles(player) || !gogglesRequired(true))) {
                // UNLESS THE CLICKED BLOCK IS THE PICKED ONE. The world's own gesture for "shift-right-click
                // the block you selected" is "drop every override on it", and a line runs between TWO blocks -
                // so this can never be the lock-towards-a-device gesture, because the device is the block
                // BESIDE this one. Asked before the device lock, and through the same predicate the non-empty
                // path asks through (#planWrench), so the two entrances cannot disagree about which gesture
                // this is. An emptied host reaches `clearChecked` and answers `NoComponent` - "there is no
                // component here to route" - which is the honest answer, and not a complaint about a device
                // that is not there.
                CarriedSelection.Selection picked = CarriedSelection.get(player);
                if (isThePickedHostItself(picked, hit.carrier(), hit.localPos())) {
                    CarriedTool.Action itself = CarriedTool.clearChecked(index, hit.carrier(),
                            hit.localPos());
                    CarrierGuards.OTHER.call(hit.localPos(), () -> {
                        reportTool(level, player, hit, face, itself, wrench, entry, hand);
                        return Boolean.TRUE;
                    }, false);
                    return true;
                }
                OutsideDecision inside = planLinkFromPick(level, player, hit.localPos());
                if (inside.handled()) {
                    // "I pointed at an empty host of the picked structure" is a lock attempt by definition, so
                    // the diagnostic that says what became of a lock attempt is written here too - the same
                    // call, the same shape, so the two entrances to this gesture read the same in a log.
                    lockAttempt(player, hand, hit.localPos(), CarrierGesture.Kind.WRENCH, false, hit,
                            inside, hit.localPos());
                    reportOutside(level, player, inside, entry);
                    return true;
                }
                lockAttempt(player, hand, hit.localPos(), CarrierGesture.Kind.WRENCH, false, hit, inside,
                        hit.localPos());
                trace(entry, player, hand, hit.localPos(), face, hit, CarrierEntryTrace.Outcome.REFUSED,
                        "refused: this carried host is EMPTY and the picked host is not this one, so "
                                + "there is nothing to pin towards it either (INSIDE: "
                                + inside.status() + " blocked-by=" + inside.blockedBy() + ")");
                return false;
            }
            CarriedTool.Action empty = new CarriedTool.Action.NoComponent(hit.carrier(), hit.localPos(),
                    wrench ? "wrench" : "rod");
            message(player, describe(empty, index));
            trace(entry, player, hand, hit.localPos(), face, hit, CarrierEntryTrace.Outcome.REFUSED,
                    "refused: this carried host is EMPTY (the structure carries the block, and the block "
                            + "holds no component), so the " + (wrench ? "wrench" : "rotation rod")
                            + " has nothing to act on - the world's own empty host behaves the same way");
            return false;
        }
        // The second hand of one click: the first hand already acted, so this one is not a second turn.
        long tick = level.getGameTime();
        if (tick == lastHandledTick && hit.localPos().equals(lastHandledPos)
                && lastHandledHand != null && lastHandledHand != hand) {
            return true;
        }
        markHandled(tick, hit.localPos(), hand);

        if (!wearsGoggles(player) && gogglesRequired(wrench)) {
            // The same gate the world's tools have, and one gate rather than two: a player who needs the
            // goggles needs them on a machine as well.
            message(player, Component.translatable(wrench
                            ? "message.redstonecircuit.wrench.need_goggles"
                            : "message.redstonecircuit.rotation_rod.need_goggles")
                    .withStyle(ChatFormatting.RED));
            trace(entry, player, hand, hit.localPos(), face, hit, CarrierEntryTrace.Outcome.REFUSED,
                    "refused: the goggles are required and not worn");
            return true;
        }

        boolean shift = player.isSecondaryUseActive();
        CarrierPose pose = poseOf(level, hit);
        CarriedTool.Action action = wrench
                ? wrenchAction(level, player, shift, pose, index, hit)
                : CarriedTool.turn(index, hit.carrier(), hit.localPos());
        // THE REPORTING IS ITS OWN GUARDED STEP. The action itself is a pure function over the index and cannot
        // fail on a record's contents; what follows it - the carrier broadcast, the single-host broadcast, the
        // world notification - is the half that touches a live record, and that is the half that can fail (a
        // pose broadcast on a host whose component has just been taken out). So it is guarded on its own, with
        // the trace line still written: the log has to say which step failed, not only that something did.
        CarrierGuards.OTHER.call(hit.localPos(), () -> {
            reportTool(level, player, hit, face, action, wrench, entry, hand);
            return Boolean.TRUE;
        }, false);
        return true;
    }

    /**
     * <b>Whether this click may create a carrier record at all.</b>
     *
     * <p>A record at a plot coordinate is the only thing that makes a block inside a structure a
     * <em>carried host</em>: the overlay draws from it, the solve reads it, the world bridge projects it, and
     * every later decision about that coordinate is taken against it. So it may only be filed by a placement
     * that is really going to store something, which needs all four of: <b>a placement gesture</b> - a
     * redstone component in the dispatched hand, or both hands empty while sneaking (see
     * {@link CarrierGesture}); <b>the switch on</b>, since {@code carriedInsert=false} makes the placement
     * itself refuse and it must not leave a record behind either; <b>{@link CarriedInsert#wouldInsert}</b>,
     * which is {@link CarriedInsert#preview} for the empty host this filing would create and therefore the
     * very decision {@link CarriedInsert#apply} runs a moment later; and <b>the block itself able to host a
     * component</b> - the same {@code HostRules#isValidHost} the world's placement asks, read from the
     * structure's own view by {@link CarrierStore#hostAcceptsComponent}, or a physicalised pane of glass gets
     * filed as a host by the click that is about to be refused for it.
     *
     * <p><b>The invariant:</b> the record is filed only when this answers {@code true}, and when it does,
     * {@code CarriedInsert#apply} is asked about a host that exists and holds nothing - exactly the
     * {@code (hostExists=true, slot=null)} case this decision was computed from. Nothing else writes this
     * coordinate in between, so there is no interleaving in which the record exists and the slot does not.
     *
     * @param gesture what the click is; only {@link CarrierGesture.Kind#PLACEMENT} can file
     * @param stored the component in the dispatched hand, or {@code null}
     * @param sneaking {@code player.isSecondaryUseActive()}
     * @param face the face the event carried - its <b>value</b> never decides anything here (it becomes the
     *     component's facing, and the local face is the same direction turned through the pose), only whether
     *     there was one
     * @param carriedInsert {@code RCConfig.carriedInsert()}
     * @param allowReplace {@code RCConfig.allowReplace()}
     * @param hostAcceptsComponent whether the block this cell of the structure holds may hold a component at
     *     all; {@code false} answers {@code false} here, so nothing is filed for it
     * @return true only when this click is a placement that will really store a component in an empty host
     */
    static boolean mayCreateRecord(CarrierGesture.Kind gesture, @Nullable ComponentType stored,
                                   boolean sneaking, @Nullable Direction face, boolean carriedInsert,
                                   boolean allowReplace, boolean hostAcceptsComponent) {
        if (gesture != CarrierGesture.Kind.PLACEMENT || !carriedInsert) {
            return false;
        }
        return CarriedInsert.wouldInsert(hostAcceptsComponent, face, sneaking, stored, allowReplace);
    }

    /**
     * <b>What one placement click is going to do, decided with no level, no player and no packet.</b>
     *
     * <p>A decision that can end without a record must still be readable as such: the second delivery of one
     * click ({@link ClickOnce} already answered the gesture), the config switch, and the host's own state
     * can each stop the placement, and none of them may print the same nothing as a decision that never ran.
     * So the decision is a pure function of six facts and returns a record that <b>always</b> says which of
     * them answered - {@code secondDelivery}, the switch, the action and a one-line note - and the entry
     * point's only job is to run it, report it, and consume the item when the plan says so. The rule itself
     * stays where it always was, in {@link CarriedInsert#apply}, which is the same function the world's own
     * placement is written against: one more input (whether the block may hold a component at all) rather
     * than a second copy of the rule, so the two halves cannot drift apart.
     *
     * @param index the carrier index; {@code null} means the carrier layer is not installed
     * @param carrier the structure the click resolved to
     * @param localPos the host's coordinate inside it
     * @param localFace the clicked face in the structure's own frame, or {@code null}
     * @param gesture what the click is; only {@code PLACEMENT} can act
     * @param stored the component in hand, or {@code null} for the retrieval half
     * @param sneaking {@code player.isSecondaryUseActive()}
     * @param hostAcceptsComponent whether the block itself may hold a component - the world's own rule
     *     asked of this structure's own view; {@code false} refuses an insert and never a retrieval
     * @param secondDelivery whether the other delivery of this same click already claimed the gesture
     */
    public record PlacementPlan(CarriedInsert.Action action, boolean acted, boolean consumesItem,
                                boolean secondDelivery, String note) {

        /** A plan that wrote nothing, with the reason in one phrase. */
        static PlacementPlan refused(CarriedInsert.Action action, String note) {
            return new PlacementPlan(action, false, false, false, note);
        }

        /** The plan the second delivery of one click gets: nothing is written, and the line says so. */
        static PlacementPlan duplicate(CarriedInsert.Action action) {
            return new PlacementPlan(action, false, false, true,
                    "the other delivery of this same click already claimed the gesture, so this one is "
                            + "dropped by ClickOnce rather than by the placement rule");
        }

        /** One phrase for the trace line and the ledger. */
        public String describe() {
            return note;
        }
    }

    /**
     * The decision; see {@link PlacementPlan} for why it is a public, level-free function.
     *
     * @return the plan, or {@code null} when there is no index to write to at all
     */
    @Nullable
    static PlacementPlan planPlacement(@Nullable CarriedHostIndex index, CarrierId carrier,
                                       BlockPos localPos, @Nullable Direction localFace,
                                       CarrierGesture.Kind gesture, @Nullable ComponentType stored,
                                       boolean sneaking, boolean hostAcceptsComponent,
                                       boolean secondDelivery) {
        if (index == null || carrier == null || localPos == null) {
            return null;
        }
        if (gesture != CarrierGesture.Kind.PLACEMENT) {
            return PlacementPlan.refused(CarriedInsert.Action.REFUSED_POSITION,
                    "not a placement gesture (" + gesture + ")");
        }
        // The guard first, and the LINE is the point: the record is the state (an insert replaces it, a
        // retrieve removes it), so a second delivery would find "nothing to take out" or "already holds a
        // component" and refuse on its own terms anyway - but a refusal that looks like "the click was never
        // seen" is exactly what must not happen.
        if (secondDelivery) {
            return PlacementPlan.duplicate(stored == null ? CarriedInsert.Action.RETRIEVE
                    : CarriedInsert.Action.INSERT);
        }
        if (!RCConfig.carriedInsert()) {
            return PlacementPlan.refused(CarriedInsert.Action.REFUSED_POSITION,
                    "carriedInsert=false (the switch is off)");
        }
        CarriedInsert.Action action = CarriedInsert.apply(index, carrier, localPos, hostAcceptsComponent,
                localFace, sneaking, stored, RCConfig.allowReplace());
        if (!action.acted()) {
            return PlacementPlan.refused(action, action.describe());
        }
        return new PlacementPlan(action, true, action.consumesItem(), false, action.describe());
    }

    /**
     * <b>Putting a component into a carried block, and taking one out: the world's gesture, with the store
     * changed.</b> Shift + a component in hand stores it, shift + a bare hand takes it out. The decision
     * itself is {@link #planPlacement} over {@link CarriedInsert#apply} - a pure function over the carrier
     * index - so the entry point is only "resolve, report, decide, hand it over, broadcast", and the rules
     * cannot drift from the world's by being written twice.
     *
     * <p><b>Exactly once, and the item.</b> The gesture is claimed from {@link ClickOnce} before anything is
     * written, so the second delivery of one click - the Create packet and the ordinary block click, in
     * either order, in two different coordinate frames - cannot act twice, and it is reported as well,
     * because a click that leaves no line is indistinguishable from a click that never arrived. The record
     * is the state (a retrieve removes it, an insert replaces it), so a second delivery that got past the
     * guard would find "nothing to take out" or "already holds a component" and refuse on its own terms. The
     * item is spent <b>only</b> when the plan says {@link CarriedInsert.Action#INSERT} came back, and the
     * guard is claimed <b>before</b> the decision, so a refused placement consumes nothing - the rule the
     * world's path has too.
     */
    private static boolean handlePlacementClick(ServerLevel level, ServerPlayer player,
                                                InteractionHand hand, CarriedBlockRemoval.Hit hit,
                                                Direction face, @Nullable ComponentType stored,
                                                String entry) {
        CarriedHostIndex index = CarrierStore.indexOf(level);
        long tick = level.getGameTime();
        // The gesture is claimed HERE and reported even when the claim fails: `ClickOnce` is the guard, and
        // "the guard had this click" has to be a line and not an absence.
        boolean secondDelivery = !PLACEMENT.claim(tick, hit.carrier(), hit.localPos(), hand);
        Direction localFace = face == null ? null : CarrierDirections.localDirection(poseOf(level, hit), face);
        // What is in there BEFORE the decision: a retrieve has to be able to say what it took out, and after
        // the record has been replaced there is nothing left to read it from.
        Slot before = index == null ? null : CarriedInsert.slotOf(index, hit.carrier(), hit.localPos());
        boolean sneaking = player.isSecondaryUseActive();
        // THE SAME QUESTION THE WORLD ASKS, asked of the block the structure is carrying: its state is not in
        // the level (a plot coordinate, or a contraption's own block map), so the only thing that changes is
        // WHERE the state is read from - the structure's own view. HostRules then answers unchanged, and a
        // physicalised pane of glass refuses a component exactly as it did standing still.
        boolean hostAccepts = CarrierStore.hostAcceptsComponent(hit.carrier(), hit.localPos());
        PlacementPlan plan = planPlacement(index, hit.carrier(), hit.localPos(), localFace,
                CarrierGesture.Kind.PLACEMENT, stored, sneaking, hostAccepts, secondDelivery);
        // THE LINE THAT SEPARATES THE THREE SILENCES. Its outcome is decided by four inputs, three of which
        // appear nowhere else - whether the player was sneaking, what the hand held, which local face the
        // click named, and what the host held before - so they are printed beside the action. Reached only
        // after the click has already resolved to a live carried host, so an unrelated click can never
        // produce it.
        CarrierEntryTrace.insert(level, entry, player, hit.localPos(), face, hand,
                player.getItemInHand(hand),
                plan != null && plan.acted() ? CarrierEntryTrace.Outcome.MATCH
                        : CarrierEntryTrace.Outcome.REFUSED,
                "carrier=" + hit.carrier().shortId()
                        + " local=" + CarrierEntryTrace.posText(hit.localPos())
                        + " world=" + CarrierEntryTrace.posText(hit.worldPos())
                        + " sneaking=" + sneaking
                        + " held=" + (stored == null ? "nothing to store" : stored.name())
                        + " localFace=" + (localFace == null ? "?" : localFace.getName())
                        + " slotBefore=" + (before == null ? "empty" : before.describe())
                        + " hostAccepts=" + hostAccepts
                        + " secondDelivery=" + secondDelivery
                        // THE PAIRING BRACKET ON THE ONE LINE THAT MATTERS MOST. This is the line that says
                        // a placement was decided; the world's own decline line for the same delivery carries
                        // the same tick, and this names it outright. Together they answer whether the world
                        // stood down and the carrier took over, or the world stood down and nothing else
                        // happened at all.
                        + " | " + CarrierClickPairing.carrierNote(tick, hit.localPos(), hand)
                        + " -> " + (plan == null ? "refused: the carrier layer is not installed"
                                : plan.describe()));
        if (plan == null) {
            trace(entry, player, hand, hit.localPos(), face, hit, CarrierEntryTrace.Outcome.REFUSED,
                    "refused: the carrier layer is not installed");
            return false;
        }
        if (secondDelivery) {
            // The same host, the same tick, already handled: nothing is written, nothing is consumed, and
            // the click still belongs to the carrier layer - so it is not handed to the block underneath
            // either.
            return true;
        }
        if (!plan.acted()) {
            // A refusal is reported and nothing else happens: no record written, no item taken. Note
            // that this is also where an ordinary world click ends up (REFUSED_POSITION), which is why
            // the caller must NOT cancel the event for it.
            trace(entry, player, hand, hit.localPos(), face, hit, CarrierEntryTrace.Outcome.REFUSED,
                    "refused: " + plan.describe());
            return false;
        }

        // GUARDED: everything from here to the return value touches a live record's contents - the
        // retrieve writes a null slot, the item goes back to the player, the broadcasts walk the index -
        // which is the half that can fail on a host that has been emptied by the other hand. A failure is
        // contained and warned about once (see CarrierGuards); the record's own state is what it is, and
        // the client is corrected by the next carrier payload.
        CarrierGuards.INSERT.call(hit.localPos(), () -> {
            applyPlacementPlan(level, player, hand, hit, face, plan, entry, index, before);
            return Boolean.TRUE;
        }, false);
        return true;
    }

    /** The write-back half of {@link #handlePlacementClick}; see the guard there. */
    private static void applyPlacementPlan(ServerLevel level, ServerPlayer player,
                                           InteractionHand hand, CarriedBlockRemoval.Hit hit,
                                           Direction face, PlacementPlan plan, String entry,
                                           CarriedHostIndex index, @Nullable Slot before) {
        // Adding or removing a component changes the structure's own network, so the in-structure solve
        // has to re-derive. There is no world position to mark dirty - the component is not in the world
        // at all - and none is needed: the solve walks the index every pass and takes a host it has not
        // seen before as a fresh seed. The carried file is marked dirty so a machine put down later comes
        // home holding what a player put in it.
        CarriedHosts.get(level).setDirty();
        if (plan.action() == CarriedInsert.Action.INSERT) {
            if (!player.getAbilities().instabuild) {
                player.getItemInHand(hand).shrink(1);
            }
            playPlacementSound(level, hit);
        } else {
            retrieveIntoInventory(level, player, hit, before);
            playRetrievalSound(level, hit);
        }
        // THE LINE THAT SEPARATES THE TWO HALVES OF A PLACEMENT. "It went in" and "the carrier layer knows
        // about it" are different facts, and only the second one makes the component visible and connected to
        // the world: the overlay draws from the index, the world bridge reads the projection built from it,
        // and a component written somewhere else is invisible and inert towards the world while still solving
        // perfectly inside the structure. So this says which of the two happened, once per placement, and it
        // is written whether or not the record is where it should be.
        logRegistration(level, index, hit, plan.action(), entry);
        reportPlacement(level, player, hit, face, plan.action(), entry, hand);
    }

    /**
     * <b>"Registered as a riding host" versus "the slot was written and nothing else".</b>
     *
     * <p>A host a structure is carrying has to be in the carrier index at its own local coordinate, with the
     * world position the pose computes - that is what the overlay draws from and what the world bridge reads
     * - and both facts are read back here from the index rather than assumed from the write that was just
     * made.
     */
    private static void logRegistration(ServerLevel level, @Nullable CarriedHostIndex index,
                                        CarriedBlockRemoval.Hit hit, CarriedInsert.Action action,
                                        String entry) {
        if (index == null) {
            RCConfig.LOGGER.warn("[redstonecircuit] carrier insert at {} did NOT reach the carrier index "
                    + "(no index for this level): the component is written but is neither drawn nor "
                    + "connected to the world", hit.localPos().toShortString());
            return;
        }
        CarriedHost host = index.at(hit.carrier(), hit.localPos());
        if (host == null || host.landed()) {
            RCConfig.LOGGER.warn("[redstonecircuit] carrier insert at {} wrote the slot but the host is "
                    + "NOT registered as riding (record={}): the component will not be drawn and the "
                    + "world bridge cannot read it",
                    hit.localPos().toShortString(), host == null ? "absent" : "landed");
            return;
        }
        BlockPos world = CarrierStore.currentWorldPos(level, hit.carrier(), hit.localPos());
        RCConfig.LOGGER.info("[redstonecircuit] carrier {}: {} local={} origin={} registered as a RIDING "
                        + "host; projected at {} (the projection table and the world bridge read this "
                        + "position, never the structure's own coordinate) entry={}",
                hit.carrier().shortId(), action == CarriedInsert.Action.INSERT ? "insert" : "retrieve",
                hit.localPos().toShortString(), host.origin().toShortString(),
                world == null ? "unknown (no pose)" : world.toShortString(), entry);
    }

    /**
     * Gives a retrieved component back to the player, or drops it where the block is - the world's rule
     * exactly. The position it falls at is the one the projection gave, the block the player is looking at,
     * rather than the structure's own storage coordinate, which for a Sable sub-level is two million blocks
     * away from anywhere a player could pick it up.
     */
    private static void retrieveIntoInventory(ServerLevel level, ServerPlayer player,
                                              CarriedBlockRemoval.Hit hit, @Nullable Slot removed) {
        if (player.getAbilities().instabuild || removed == null) {
            return;
        }
        ItemStack back = HostRules.itemFor(removed.type);
        if (!player.getInventory().add(back)) {
            Block.popResource(level, dropPos(hit), back);
        }
    }

    /** Where a component taken out of a carried host appears if the inventory is full. */
    private static BlockPos dropPos(CarriedBlockRemoval.Hit hit) {
        return hit.worldPos() == null ? hit.localPos() : hit.worldPos();
    }

    /**
     * The feedback for a placement: a sound, and nothing that can take the server down.
     *
     * <p>Not the host block's own sound type (which is what the world's path reuses): that needs the block's
     * state, and the structure's own block view is exactly the kind of access that can fail during a level
     * stop or a sub-level removal. A fixed vanilla sound played at the host's local coordinate is the whole of
     * the feedback, and it is wrapped so that a sound engine that dislikes a plot coordinate cannot fail a
     * placement that has already been written.
     */
    private static void playPlacementSound(ServerLevel level, CarriedBlockRemoval.Hit hit) {
        playSound(level, hit, SoundEvents.ITEM_FRAME_ADD_ITEM);
    }

    /** The feedback for a retrieval; see {@link #playPlacementSound}. */
    private static void playRetrievalSound(ServerLevel level, CarriedBlockRemoval.Hit hit) {
        playSound(level, hit, SoundEvents.ITEM_FRAME_REMOVE_ITEM);
    }

    private static void playSound(ServerLevel level, CarriedBlockRemoval.Hit hit,
                                  net.minecraft.sounds.SoundEvent sound) {
        try {
            level.playSound(null, dropPos(hit), sound, SoundSource.BLOCKS, 0.6F, 1.0F);
        } catch (RuntimeException | LinkageError ignored) {
            // Feedback is optional; the record write is not.
        }
    }

    /** True when this tool is configured to require the goggles. */
    private static boolean gogglesRequired(boolean wrench) {
        return wrench ? RCConfig.wrenchNeedsGoggles() : RCConfig.rotationRodNeedsGoggles();
    }

    private static boolean wearsGoggles(ServerPlayer player) {
        return RCRegistry.isGoggles(player.getItemBySlot(EquipmentSlot.HEAD));
    }

    /**
     * The wrench's two gestures, in the structure's own frame.
     *
     * <p>The selection is the carrier-layer one ({@link CarriedSelection}), which keeps the local coordinate
     * for adjacency and the world position for the client's highlight. Adjacency is decided between
     * <b>local</b> coordinates, which is where the components are: a structure turned by ninety degrees has
     * its local east pointing at the world's south, and the two blocks the player sees side by side are
     * neighbours locally.
     *
     * <p>The shift state is a parameter rather than read from the player here, so the Create entry point -
     * which reaches this through a packet and not through a player event - cannot be given a different answer
     * than the block-click entry point.
     */
    private static CarriedTool.Action wrenchAction(ServerLevel level, ServerPlayer player, boolean shift,
                                                    @Nullable CarrierPose pose,
                                                    CarriedHostIndex index,
                                                    CarriedBlockRemoval.Hit hit) {
        CarriedSelection.Selection selected = CarriedSelection.get(player);
        if (!shift) {
            CarriedTool.Action action = CarriedTool.select(index, hit.carrier(), hit.localPos());
            if (action instanceof CarriedTool.Action.Selected) {
                // No highlight is sent from here: the pick is recorded in both frames, and
                // WrenchSelection#refresh - the one sender - reads it on the next level tick, projects the
                // local coordinate through this carrier's pose and tells the client where to draw the box.
                CarriedSelection.select(player, hit.carrier(), hit.localPos(), hit.worldPos());
            }
            return action;
        }
        CarriedTool.Action action = planWrench(level, selected, index, hit.carrier(), hit.localPos(),
                hit.worldPos(), pose);
        if (action instanceof CarriedTool.Action.Cleared) {
            // The pick has done its job: a cleared host is not the near end of a line any more, and the
            // world's wrench behaves the same way (its selection is dropped by the clear).
            CarriedSelection.clear(player);
        }
        return action;
    }

    /**
     * <b>Whether this shift-click is the world's own "click the block you picked again" gesture.</b>
     *
     * <p>One predicate, two entrances, and that is the point: the wrench reaches this decision with a host
     * that holds a component ({@link #planWrench}'s first branch) and, when the host has been emptied, with
     * one that does not ({@link #handleToolClick}'s empty-host branch). Asking it in one entrance only tells a
     * player who shift-clicked their own <em>emptied</em> host that there is nothing to pin towards it - a
     * sentence about a device that is not there, when the only thing wrong with the click was that the block
     * it landed on was the one already picked. Both entrances ask this, and the answer is the existing
     * same-host gesture: drop every override, or say there was nothing to drop
     * ({@link CarriedTool.Action.Cleared} / {@link CarriedTool.Action.AlreadyClear}).
     *
     * <p>A line runs between <b>two</b> neighbouring blocks, which is why this is not the
     * lock-towards-a-device gesture: the device the player means is the block <em>beside</em> the pick.
     *
     * @param selected the pick the player made earlier; {@code null} when there is none
     */
    static boolean isThePickedHostItself(@Nullable CarriedSelection.Selection selected, CarrierId carrier,
                                         BlockPos localPos) {
        return selected != null && carrier != null && localPos != null
                && carrier.equals(selected.carrier()) && localPos.equals(selected.localPos());
    }

    private static CarriedTool.Action planWrench(@Nullable CarriedSelection.Selection selected,
                                                 CarriedHostIndex index, CarrierId carrier,
                                                 BlockPos localPos, BlockPos worldPos,
                                                 @Nullable CarrierPose pose) {
        return planWrench(null, selected, index, carrier, localPos, worldPos, pose);
    }

    /**
     * <b>The shift-click half of the wrench, as a pure function over a pick.</b>
     *
     * <p>The pick is per player and held in a static ledger, and a unit test cannot build a
     * {@code ServerPlayer}, so the lookup stays in {@link #wrenchAction} and the <em>rule</em> lives here.
     *
     * <p>The answers, in the order they are asked: <b>the same host again</b> - {@code clear}, the world
     * wrench's "shift-click the selected block itself drops every override", and the pick ends with it (see
     * {@link #wrenchAction}); <b>no pick at all</b> - {@link CarriedTool#linkFrom}'s {@code NoPick}, which is
     * the only case that answer is for; <b>a pick in another structure whose block is the world neighbour of
     * this click</b> - the cross-structure line, which is the shape of two separately physicalised blocks
     * standing side by side, where both records are written in their own frame (see {@link #crossLink} and
     * {@link #linkAcross}); <b>a pick in this structure</b> - the ordinary local-frame link, whose adjacency
     * is decided inside the structure; and <b>anything else</b> - {@link CarriedTool#linkFrom} again, which
     * names the pick and says whether the two blocks are neighbours <em>where they stand</em>, so "wrong
     * machine" and "not connected anywhere" are different sentences.
     *
     * @param level the level, or {@code null} in a unit test; used only to read the picked structure's pose
     * @param selected the near end the player picked earlier; {@code null} when there is none
     */
    static CarriedTool.Action planWrench(@Nullable ServerLevel level,
                                         @Nullable CarriedSelection.Selection selected,
                                         CarriedHostIndex index, CarrierId carrier, BlockPos localPos,
                                         BlockPos worldPos, @Nullable CarrierPose pose) {
        if (isThePickedHostItself(selected, carrier, localPos)) {
            // THE SAME HOST AGAIN, and the two halves of that gesture are different. With an override to drop
            // this is the world's own "shift-click the selected block itself drops every override", and the
            // pick ends with it (see #wrenchAction). With nothing to drop the click did nothing and the pick
            // has to SURVIVE: a player who clicked twice has not asked for their pick to be forgotten.
            return CarriedTool.clearChecked(index, carrier, localPos);
        }
        if (selected == null) {
            return CarriedTool.linkFrom(index, carrier, localPos, false, null, null, false);
        }
        // THE CROSS-STRUCTURE LINE, BEFORE ANYTHING CALLS IT A REFUSAL. Two blocks that really do stand next
        // to each other in the world are a line the player is drawing, whatever structure each one is filed
        // under - and "the two blocks ARE neighbours where they stand" is this mod's own sentence for that
        // case. It is asked first so the answer is a line and not a paragraph about structures.
        CrossLink across = crossLink(level, index, selected, carrier, localPos, worldPos, pose);
        if (across != null) {
            return linkAcross(index, across);
        }
        // EVERYTHING ELSE GOES THROUGH `linkFrom`, and the difference between the cases is decided there
        // rather than here: a pick in this structure that shares a face with the click is the ordinary line;
        // one that does not is `NotAdjacent` (the world's own refusal); a pick in another structure that is
        // not a world neighbour is `PickedElsewhere` and names both structures. The one fact this method adds
        // is whether the two blocks are neighbours where they stand, which a reader needs in order to tell
        // "wrong machine" from "not connected anywhere".
        CarriedTool.Action action = CarriedTool.linkFrom(index, carrier, localPos, true,
                selected.carrier(), selected.localPos(), worldAdjacent(selected, carrier, localPos, pose));
        if (action instanceof CarriedTool.Action.PickedElsewhere elsewhere) {
            BlockPos pickedWorld = selected.worldPos() == null ? BlockPos.ZERO : selected.worldPos();
            BlockPos clickWorld = worldPos == null ? BlockPos.ZERO : worldPos;
            return new CarriedTool.Action.PickedElsewhere(elsewhere.carrier(), elsewhere.localPos(),
                    elsewhere.pickedCarrier(), elsewhere.pickedLocalPos(), elsewhere.worldAdjacent(),
                    pickedWorld, clickWorld);
        }
        return action;
    }

    /** The local face from one coordinate to another, or {@code null} when they share none. */
    @Nullable
    private static Direction localDirectionTo(BlockPos from, BlockPos to) {
        return CarriedTool.localDirectionBetween(from, to).orElse(null);
    }

    // ------------------------------------------- locking a line towards the outside world --

    /**
     * <b>Why one shift-click on a world block can end in several different ways, each with its own name.</b>
     *
     * <p>The gesture: pick a component inside a machine with the wrench, then shift-right-click a block of the
     * world standing beside it, and pin that line. It is the carrier-side twin of the world's
     * {@code linked_on_device} - "the far end holds no redstone of its own, so only this end is pinned, and
     * every other direction is cut" - and the world's own handler cannot serve it because the component being
     * pinned is <b>not in the world's store at all</b>: it lives in the carrier index under
     * {@code (carrier, localPos)}.
     *
     * <p>Most of the outcomes are refusals, and they are refusals for different reasons - which is exactly why
     * they are a value and not a boolean. A click that came here and was refused and a click that never came
     * here must not print the same nothing.
     */
    enum OutsideStatus {

        /** The line was pinned on the side facing the world block; every other side is now cut. */
        LOCKED,
        /**
         * <b>The line was pinned on the side facing a block that is INSIDE this same structure and holds
         * no component</b> - a piston, a lamp, a plain block the player built into the machine. The
         * structure's own frame is where the direction is measured, so no conversion is involved; the
         * pinned side and the cuts are the same fact {@link #LOCKED} produces.
         *
         * <p>A separate value from {@link #LOCKED}: the two gestures answer the same question
         * about two different far ends (the world's, and this machine's own), and the judgement lines have
         * to be distinguishable at a glance from each other and from a cross-structure line.
         */
        LOCKED_INSIDE,
        /** No carried host is picked at all: there is nothing to pin. Silent - the world's own gesture. */
        NOTHING_PICKED,
        /**
         * The clicked position is a coordinate a structure owns (a live host, a landed record, or a plot
         * cell of a living sub-level) - so this is NOT "outside the structure" and this path refuses to
         * guess. A plot cell that simply holds no component yet is the case that matters: taking it for a
         * world block would write a lock towards a coordinate two million blocks away.
         */
        NOT_OUTSIDE,
        /**
         * <b>For the inside gesture only: the clicked plot cell does not belong to the picked structure.</b>
         * It is either nobody's (the world's, which is the outside gesture) or another structure's (which
         * is the cross-structure path). Silent, like {@link #NOT_OUTSIDE}.
         */
        NOT_INSIDE,
        /** The clicked block shares no face with the picked host's current position. */
        NOT_ADJACENT,
        /** The picked structure's pose cannot be read, so the world direction cannot be converted. */
        UNREADABLE_POSE,
        /** The pose projects the picked host to no world position at all. */
        UNREADABLE_POSITION,
        /** The picked host is gone, was put down, or holds no component. */
        NO_COMPONENT,
        /**
         * <b>For the inside gesture only: there is no block at all on that side.</b> The structure's own
         * view says the coordinate is air, so there is nothing to pin a line towards - the world's
         * {@code nothing_there}, on the structure's own record.
         */
        NO_SUCH_BLOCK,
        /**
         * <b>The clicked coordinate is a plot cell, but of a structure that is NOT the picked one.</b> A line
         * runs inside one structure, so this is nobody's line - and unlike {@link #NOT_INSIDE} it is
         * <b>named</b>, because the player did point at a physicalised block.
         *
         * <p>The two other "not this gesture's click" answers are silent: {@link #NOTHING_PICKED} is a plain
         * select click on a world block, and {@link #NOT_INSIDE} with nothing owning the coordinate is the
         * OUTSIDE gesture, which is decided and reported a moment later by the same wiring. This one is
         * different, because the clicked cell belongs to another sub-level - Sable's granularity, one
         * sub-level per assembled block - and both far ends of the gesture would otherwise decline it
         * silently: no lock, no refusal, no trace line. So it is reported with the same shape as
         * {@code NOT_ADJACENT}.
         */
        FAR_END_NOT_THE_PICKED_STRUCTURE;

        /** True when the outcome wrote something: two of the eleven do. */
        public boolean acted() {
            return this == LOCKED || this == LOCKED_INSIDE;
        }
    }

    /**
     * One shift-click with the wrench on a coordinate no carried-host lookup resolved, as a value.
     *
     * @param status which of the eleven answers this click gets
     * @param action what was written; non-{@code null} exactly when {@link OutsideStatus#acted()}
     * @param pickedWorld where the picked host stands now, when it could be worked out
     * @param clickWorld the block the player pointed at
     * @param blockedBy <b>which question in the chain answered, and with what</b> - the planner writes this
     *     as it walks, so the lock-attempt trace can say <em>where</em> a click was stopped instead of a
     *     reader guessing from the status. It is a diagnostic string and never a decision: nothing reads it,
     *     and it addresses no level
     */
    record OutsideDecision(OutsideStatus status, @Nullable CarriedTool.Action action,
                           @Nullable BlockPos pickedWorld, BlockPos clickWorld, String blockedBy) {

        /** True when this click was ours to act on - the one case that consumes the event. */
        boolean handled() {
            return status.acted();
        }

        /** A decision that wrote nothing, with its reason and the step that answered. */
        static OutsideDecision refused(OutsideStatus status, @Nullable BlockPos pickedWorld,
                                       BlockPos clickWorld, String blockedBy) {
            return new OutsideDecision(status, null, pickedWorld, clickWorld, blockedBy);
        }

        /** A decision the chain never reached, with the step that stopped it. */
        static OutsideDecision refused(OutsideStatus status, String blockedBy) {
            return refused(status, null, BlockPos.ZERO, blockedBy);
        }
    }

    /**
     * <b>The production wiring: "is this block outside every structure", asked once, then the decision.</b>
     *
     * <p>The ownership question is {@link CarrierClickPriority#owner}, which is the <b>same</b> question,
     * asked by the <b>same</b> code, that decides whether the world side stands down for a click. Reusing
     * it is what makes "outside" mean one thing: a position the world side would have been allowed to
     * handle. Asking {@code hitAt} instead would answer "does this block hold a component I can act on",
     * which is a different question - a plot cell of a living structure with nothing inside it yet is ours
     * by ownership and empty by contents, and taking it for a world block would pin a line towards the
     * plot's own coordinate.
     *
     * @return the decision; {@link OutsideStatus#NOTHING_PICKED} for every click that is not this gesture
     */
    static OutsideDecision planOutside(ServerLevel level, ServerPlayer player, BlockPos clickPos) {
        return planPickGesture(level, player, clickPos, false);
    }

    /**
     * <b>The same wiring, for the gesture that pins a line towards a block <em>inside</em> the picked
     * structure.</b> One gesture with two far ends, so it is the same production wiring and the same
     * decision - see {@link #planOutside(CarriedHostIndex, CarriedSelection.Selection, BlockPos,
     * CarrierId, CarrierPose, BlockPos)}, which both forms are.
     *
     * @return the decision; {@link OutsideStatus#NOTHING_PICKED} for every click that is not this gesture
     */
    static OutsideDecision planLinkFromPick(ServerLevel level, ServerPlayer player, BlockPos clickPos) {
        return planPickGesture(level, player, clickPos, true);
    }

    /**
     * The production wiring both shift-click gestures share: read the pick, gate on the switch and the
     * shift, ask the ONE ownership question, and hand the answer to {@link #planLink}.
     *
     * <p>Written once: the two gestures differ in <b>which far end they mean</b>, and nothing
     * else - gating them separately is how "the inside gesture works but the outside one stopped" would
     * become possible.
     */
    private static OutsideDecision planPickGesture(ServerLevel level, ServerPlayer player,
                                                   BlockPos clickPos, boolean inside) {
        CarriedSelection.Selection selected = CarriedSelection.get(player);
        // THE SILENT HALF: a wrench shift-click is delivered twice (once per hand), and a player who simply
        // clicks two world blocks together is making the WORLD's gesture - this listener must cost that click
        // nothing and say nothing about it.
        if (selected == null) {
            return OutsideDecision.refused(OutsideStatus.NOTHING_PICKED, "no-pick (nothing selected: this "
                    + "is the world's own wrench gesture, or the first click of one)");
        }
        if (level == null || player == null || !RCConfig.carriedWrench()) {
            return OutsideDecision.refused(OutsideStatus.NOTHING_PICKED, "carrier tool disabled (either "
                    + "there is no level to read or carriedWrench=false)");
        }
        if (!player.isSecondaryUseActive()) {
            // The select half of the gesture is the world's own on a world block, and it must stay so.
            return OutsideDecision.refused(OutsideStatus.NOTHING_PICKED, "not-sneaking (this is the "
                    + "SELECT half: pick the near end first, then shift-click the far end)");
        }
        // ONE READ-ONLY OWNERSHIP QUESTION, the same one the world side asks - see the note above.
        CarrierClickPriority.Owned owned;
        try {
            owned = CarrierClickPriority.owner(level, clickPos);
        } catch (RuntimeException | LinkageError failed) {
            owned = null;
        }
        CarriedHostIndex index = CarrierStore.indexOf(level);
        CarrierPose pose = poseForOutside(level, selected);
        CarrierId ownedBy = owned == null ? null : owned.carrier();
        if (inside) {
            // BOTH ENDS IN THE STRUCTURE'S OWN FRAME. The pick's local coordinate (not its world
            // projection) is what the clicked plot coordinate is a neighbour of - see `planLink`.
            return planLink(index, selected, clickPos, ownedBy, pose, selected.localPos(), true,
                    click -> CarrierStore.structureViewState(selected.carrier(), click));
        }
        return planLink(index, selected, clickPos, ownedBy, pose,
                currentWorldPosForOutside(level, selected), false, null);
    }

    /** The picked structure's pose, or {@code null} when it cannot be read - never a guess. */
    @Nullable
    private static CarrierPose poseForOutside(ServerLevel level, CarriedSelection.Selection selected) {
        try {
            return CarrierStore.poseOf(level, selected.carrier());
        } catch (RuntimeException | LinkageError unreadable) {
            // "Which way is that" has one safe failure answer - "I cannot tell" - and answering the world
            // direction instead would silently write the lock on a random side of a turned machine.
            return null;
        }
    }

    /**
     * Where the picked host stands <b>right now</b>, preferring the live projection.
     *
     * <p>The recorded pick position is the fallback and not the first answer, and the difference is not
     * cosmetic: the pick is where the block was <em>when it was picked</em>, the projection is where the
     * structure has carried it to, and "which world block is beside it" is a question about now. When the
     * pose cannot be read at all, the recorded position is still the honest answer - it is the position the
     * client is highlighting, and it is what makes this gesture work on a structure whose pose query
     * happens to be unreadable at this instant.
     */
    @Nullable
    private static BlockPos currentWorldPosForOutside(ServerLevel level,
                                                      CarriedSelection.Selection selected) {
        try {
            BlockPos live = CarrierStore.currentWorldPos(level, selected.carrier(), selected.localPos());
            return live != null ? live : selected.worldPos();
        } catch (RuntimeException | LinkageError unreadable) {
            return selected.worldPos();
        }
    }

    /**
     * <b>The whole decision, as a pure function over the index.</b>
     *
     * <p>The order of the questions is the definition: <b>is there a pick?</b> - no pick means this is not
     * the lock gesture at all; <b>does a structure own the clicked coordinate?</b> - if it does, this click is
     * <em>not</em> about the outside world, so the honest answers are "an empty host" or "a plot cell with
     * nothing in it yet", and both are refused rather than pinned towards; <b>is the world block adjacent to
     * the picked host?</b> - "lock the line towards that block" is only meaningful when there is a face
     * between them, and the world's own refusal for this is {@code not_adjacent}, reported and never silent;
     * <b>can the pose be read?</b> - the world direction has to be turned into the structure's own frame by
     * {@link CarrierDirections#localDirection}, which needs the structure's orientation, and a {@code null}
     * pose is the one answer that is certainly not "no rotation" ({@code CarrierDirections} answers the world
     * direction unchanged for a null orientation, which would be the silent wrong side); and <b>does the
     * picked host still hold a component?</b> - there has to be something to route.
     *
     * <p>The state the side takes is {@link WrenchLinks#next} of what it holds now - the same
     * {@code AUTO -> ON -> OFF -> AUTO} cycle the world's wrench and the in-structure link both step through,
     * because it is the same method.
     *
     * @param index the carrier index; {@code null} means nothing can be written
     * @param selected the pick the player made earlier
     * @param clickPos the coordinate the click carried - a world block, if this decision is reached
     * @param ownedByAStructure whether {@link CarrierClickPriority#owner} claimed that coordinate
     * @param pose the picked structure's pose, or {@code null} when it cannot be read
     * @param pickedWorld where the picked host stands now, or {@code null}
     */
    static OutsideDecision planOutside(@Nullable CarriedHostIndex index,
                                       CarriedSelection.Selection selected, BlockPos clickPos,
                                       boolean ownedByAStructure, @Nullable CarrierPose pose,
                                       @Nullable BlockPos pickedWorld) {
        return planLink(index, selected, clickPos,
                ownedByAStructure && selected != null ? selected.carrier() : null, pose,
                pickedWorld, false, null);
    }

    /**
     * The same, for the gesture whose far end is a block <b>inside the picked structure</b> - one gesture
     * with two far ends, and therefore the same decision rather than a second one.
     *
     * <p>Both start from a picked host, both need a face between the pick and the click, both need the
     * structure's orientation to name a side, and both write exactly one record - the pick's - through the
     * same state cycle. The only difference is <b>which coordinate frame the two positions are compared
     * in</b>: the world block is compared in the <b>world</b> frame, against where the pick is projected to
     * now ({@code pickedWorld}), with the shared face turned into the structure's own direction through
     * {@link CarrierDirections#localDirection}; a block inside the structure arrives <b>already</b> in the
     * structure's own frame (a Sable sub-level's plot coordinate <em>is</em> its local coordinate), so the two
     * positions are compared directly and no conversion happens at all. {@code inside == true} adds one
     * question the outside gesture has no answer for: is there anything on that side at all - {@code farEnd}
     * answers it from the structure's own view, and air is {@link OutsideStatus#NO_SUCH_BLOCK} (the world's
     * own {@code nothing_there}, on the structure's own record).
     *
     * <p><b>The far end is judged by whether it holds a COMPONENT, never by whether it has a record.</b>
     * {@code ownedByCarrier} is this structure's own answer to "is that coordinate mine", and it is the same
     * answer for a plot cell the index holds a record for and for one it has never heard of: either way the
     * block is the structure's, and either way it may hold nothing. So nothing here asks the far end for a
     * record - what the far end holds decides only <em>which write</em> that side gets, and that is decided one
     * step earlier, by {@code CarrierStore#hitAt}: a far end that really holds a component resolves as an
     * ordinary host and never reaches this method, so every click that arrives here is one where the far block
     * has no component of its own - a piston, a lamp, a plain block - which is the case
     * {@link OutsideStatus#LOCKED_INSIDE} exists for, and an <b>empty host record</b> on that side is that
     * same case.
     *
     * <p>The one same-block case that is <em>not</em> this gesture is the clicked coordinate being the picked
     * one, answered before this method is reached in both entrances - see {@link #isThePickedHostItself}.
     *
     * @param index the carrier index; {@code null} means nothing can be written
     * @param selected the pick the player made earlier
     * @param clickPos the coordinate the click carried - a world block when {@code inside} is false, and a
     *     coordinate inside the structure when it is true
     * @param ownedByCarrier which structure owns the clicked coordinate, or {@code null} when none does
     * @param pose the picked structure's pose, or {@code null} when it cannot be read
     * @param pickedPos where the picked host stands: its projection when {@code inside} is false, and its
     *     own local coordinate when it is true
     * @param inside which of the two gestures this is
     * @param farEnd what the structure's own view says about the clicked coordinate; asked only when
     *     {@code inside} is true
     */
    static OutsideDecision planLink(@Nullable CarriedHostIndex index,
                                    CarriedSelection.Selection selected, BlockPos clickPos,
                                    @Nullable CarrierId ownedByCarrier, @Nullable CarrierPose pose,
                                    @Nullable BlockPos pickedPos, boolean inside,
                                    @Nullable java.util.function.Function<BlockPos,
                                            CarriedBlockRemoval.StructureViewState> farEnd) {
        BlockPos click = clickPos == null ? BlockPos.ZERO : clickPos;
        if (selected == null || selected.carrier() == null || selected.localPos() == null) {
            return OutsideDecision.refused(OutsideStatus.NOTHING_PICKED, pickedPos, click,
                    "no-pick (nothing selected: there is no near end to pin from)");
        }
        if (ownedByCarrier != null && !ownedByCarrier.equals(selected.carrier())) {
            // A coordinate another structure owns: two records could be written there, so this is the
            // cross-structure path's business, never this one. The inside gesture distinguishes it from
            // NOT_OUTSIDE so it can answer for its OWN structure without swallowing the world's.
            //
            // AND IT IS NAMED, because the player really did point at a physicalised block: two separately
            // assembled blocks ARE two sub-levels (Sable's granularity), so a shift-click on the block beside
            // the pick belongs to a structure that is not the picked one - and a silent NOT_INSIDE plus a
            // silent NOT_OUTSIDE would leave the click with no answer and no trace at all.
            // See OutsideStatus#FAR_END_NOT_THE_PICKED_STRUCTURE.
            return OutsideDecision.refused(OutsideStatus.FAR_END_NOT_THE_PICKED_STRUCTURE, pickedPos, click,
                    "far-end-owned-by-another-structure (clicked " + ownedByCarrier.shortId()
                            + ", picked " + selected.carrier().shortId() + ": a line runs inside ONE "
                            + "structure, and this is the cross-structure gesture's click)");
        }
        if (inside) {
            if (ownedByCarrier == null) {
                // Nobody owns it: this is the outside gesture's click, not this one's. Silent - the same
                // wiring decides it a moment later and reports THAT decision.
                return OutsideDecision.refused(OutsideStatus.NOT_INSIDE, pickedPos, click,
                        "not-a-plot-cell (nothing owns this coordinate: this is the OUTSIDE gesture's "
                                + "click, decided and reported right after this one)");
            }
        } else if (ownedByCarrier != null) {
            return OutsideDecision.refused(OutsideStatus.NOT_OUTSIDE, pickedPos, click,
                    "inside-the-picked-structure (this coordinate belongs to the picked structure, so it is "
                            + "the INSIDE gesture's click, decided right before this one)");
        }
        if (pickedPos == null) {
            return OutsideDecision.refused(OutsideStatus.UNREADABLE_POSITION, null, click,
                    "no-picked-position (the pose names no world position for the pick, and the recorded "
                            + "pick position is absent too)");
        }
        if (inside) {
            // BOTH ENDS IN THE STRUCTURE'S OWN FRAME, and the side is read from the pick's LOCAL coordinate -
            // which is where the two ends of an in-structure line are. The world adjacency test below is only
            // a precondition here; the side is computed from the same two coordinates the ordinary
            // in-structure link uses.
            if (WrenchLinks.directionBetween(pickedPos, click).isEmpty()) {
                return OutsideDecision.refused(OutsideStatus.NOT_ADJACENT, pickedPos, click,
                        "not-adjacent (" + pickedPos.toShortString() + " and " + click.toShortString()
                                + " share no face)");
            }
            if (pose == null) {
                // The one answer that is certainly not "no rotation": CarrierDirections answers the input
                // direction unchanged for a null orientation, which is the silent wrong side this refuses.
                return OutsideDecision.refused(OutsideStatus.UNREADABLE_POSE, pickedPos, click,
                        "unreadable-pose (the picked structure's orientation could not be read, and the "
                                + "side is never guessed)");
            }
            CarriedHost host = CarriedTool.hostAt(index, selected.carrier(), selected.localPos());
            if (host == null || host.slot() == null) {
                return OutsideDecision.refused(OutsideStatus.NO_COMPONENT, pickedPos, click,
                        "no-component-at-the-pick (there is nothing to route: the picked host is gone, put "
                                + "down, or empty)");
            }
            CarriedBlockRemoval.StructureViewState view = farEnd == null ? null : farEnd.apply(click);
            if (view == CarriedBlockRemoval.StructureViewState.GONE) {
                return OutsideDecision.refused(OutsideStatus.NO_SUCH_BLOCK, pickedPos, click,
                        "no-such-block (the structure's own view says that cell is air)");
            }
            Direction side = WrenchLinks.directionBetween(selected.localPos(), click).orElse(null);
            if (side == null) {
                return OutsideDecision.refused(OutsideStatus.NOT_ADJACENT, pickedPos, click,
                        "not-adjacent (the pick's local coordinate " + selected.localPos().toShortString()
                                + " and the clicked cell " + click.toShortString() + " share no face)");
            }
            return lockInside(index, selected.carrier(), selected.localPos(), side, pickedPos, click);
        }
        if (WrenchLinks.directionBetween(pickedPos, click).isEmpty()) {
            return OutsideDecision.refused(OutsideStatus.NOT_ADJACENT, pickedPos, click,
                    "not-adjacent (" + pickedPos.toShortString() + " and " + click.toShortString()
                            + " share no face in the world)");
        }
        if (pose == null) {
            return OutsideDecision.refused(OutsideStatus.UNREADABLE_POSE, pickedPos, click,
                    "unreadable-pose (the picked structure's orientation could not be read, and the side is "
                            + "never guessed)");
        }
        CarriedHost host = CarriedTool.hostAt(index, selected.carrier(), selected.localPos());
        if (host == null || host.slot() == null) {
            return OutsideDecision.refused(OutsideStatus.NO_COMPONENT, pickedPos, click,
                    "no-component-at-the-pick (there is nothing to route: the picked host is gone, put "
                            + "down, or empty)");
        }
        Direction worldSide = WrenchLinks.directionBetween(pickedPos, click).orElse(null);
        // The ONE direction conversion this mod has, and the same call `crossLink` makes for the near end.
        Direction side = worldSide == null ? null : CarrierDirections.localDirection(pose, worldSide);
        if (side == null) {
            return OutsideDecision.refused(OutsideStatus.NOT_ADJACENT, pickedPos, click,
                    "not-adjacent (the shared world face could not be turned into the structure's own frame)");
        }
        return lockOne(index, selected.carrier(), selected.localPos(), side, pickedPos, click, false);
    }

    /** The stand-in a caller with only a boolean uses; never equal to a real carrier. */
    private static final CarrierId PLACEHOLDER_OWNER =
            CarrierId.contraption(new java.util.UUID(0L, 0L));

    /**
     * <b>The write, shared by both far ends: one side is pinned, and because a pin cuts every side that is
     * not pinned, the rest of the component goes dead.</b>
     *
     * <p>Exactly one record is touched - the pick's, in the carrier index - and the world's own store is
     * <b>not</b> written at either coordinate: there is no component to write at the far block, and inventing
     * an entry for it would create a second source of truth for a block that has none.
     *
     * <p>The lock takes effect without anything else being written because {@code Slot#isClosed} derives
     * "every side that is not pinned is cut" from {@code forcedOn} alone, and every reader of a carried
     * component reads that same {@code Slot} object - the in-structure solve through
     * {@code CarrierSpace#nodeAt}'s {@code open}/{@code explicitlyCut}/{@code routingLocked}, and the world
     * bridge through {@code CarrierProjection#signal} → {@code PowerSolver#signalAnswer} →
     * {@code PowerSolver.Locks}. So "the record says a side is pinned" and "the signal really stops going the
     * other way" are one fact.
     *
     * <p><b>{@code inside} picks only the action the ledger describes</b>: the state written, the side it is
     * written on and the record it is written to are identical for both gestures by construction.
     */
    private static OutsideDecision lockOne(@Nullable CarriedHostIndex index, CarrierId carrier,
                                           BlockPos localPos, Direction side, BlockPos pickedPos,
                                           BlockPos click, boolean inside) {
        CarriedHost host = CarriedTool.hostAt(index, carrier, localPos);
        if (host == null || host.slot() == null) {
            return OutsideDecision.refused(OutsideStatus.NO_COMPONENT, pickedPos, click,
                    "no-component-at-the-pick (the write found nothing to route)");
        }
        Slot slot = host.slot();
        ConnectionState state = WrenchLinks.next(WrenchLinks.stateOf(slot, side));
        slot.setConnection(side, state);
        index.updateSlot(carrier, localPos, slot);
        return new OutsideDecision(inside ? OutsideStatus.LOCKED_INSIDE : OutsideStatus.LOCKED,
                inside
                        ? new CarriedTool.Action.LockedInside(carrier, localPos, side, state, pickedPos,
                                click)
                        : new CarriedTool.Action.LockedOutside(carrier, localPos, side, state, pickedPos,
                                click),
                pickedPos, click,
                "locked (the side towards the far end is pinned and every other side is cut; state="
                        + state + ")");
    }

    /**
     * The write for a far end inside the same structure; the side is already local.
     */
    private static OutsideDecision lockInside(@Nullable CarriedHostIndex index, CarrierId carrier,
                                              BlockPos localPos, Direction side, BlockPos pickedLocal,
                                              BlockPos clickLocal) {
        return lockOne(index, carrier, localPos, side, pickedLocal, clickLocal, true);
    }

    /**
     * <b>What the player is told, and the line a report is grepped for.</b>
     *
     * <p>The success line says {@code OUTSIDE} or {@code INSIDE} for the far end, which is the whole point of
     * it: {@code LINKED_ACROSS} means "two structures, both records written", while these mean "one structure,
     * the other block is the world's (or this machine's own) and nothing was written at it". The world-space
     * pair {@code (world A at B)} sits beside the structure's own {@code local=}/{@code side=} so a reader can
     * check the ONE conversion this gesture performs: {@code side} is the world side turned into the picked
     * structure's own frame.
     *
     * <p><b>The refusals</b> {@code NOT_ADJACENT}, {@code UNREADABLE_POSE}, {@code UNREADABLE_POSITION},
     * {@code NO_SUCH_BLOCK} and {@code FAR_END_NOT_THE_PICKED_STRUCTURE} are named on their own lines, because
     * none of them may be silent: the first is the world's own {@code not_adjacent}, the second is the one
     * case where the direction could only be answered by guessing, and the last is a click on a physicalised
     * block that belongs to a structure other than the picked one. What is left silent is exactly the clicks
     * that <b>are</b> somebody else's gesture and are decided by the same wiring a moment later: no pick at
     * all ({@code NOTHING_PICKED}), the twin far end ({@code NOT_INSIDE} / {@code NOT_OUTSIDE}), and a picked
     * host emptied between the pick and the click ({@code NO_COMPONENT}). Telling the player about those would
     * put a sentence on every wrench click.
     *
     * <p>Whether a status is named - and therefore speaks on the action bar <em>and</em> writes a line - is
     * decided by {@link #needsANamedAnswer}, read by both halves, so "the bar says nothing the log does not"
     * is checkable rather than promised. The action bar gets translation keys only ({@link CarrierMessages}):
     * the local side's name, never a coordinate, never an enum constant.
     */
    private static void reportOutside(ServerLevel level, ServerPlayer player,
                                      OutsideDecision decision, String entry) {
        CarriedTool.Action action = decision.action();
        CarrierId carrier = action == null ? null : lockedCarrier(action);
        OutsideStatus status = decision.status();
        if (needsANamedAnswer(status)) {
            // ONE CONDITION FOR THE WORDS AND FOR THE LINE: written as two separate lists - an `else if` chain
            // for the action bar and a `||` chain for the ledger line - a status could be added to one and not
            // the other, which is an action-bar sentence with no line behind it. Both halves read this one
            // predicate.
            message(player, CarrierMessages.refused(refusalMessage(status)));
        }
        if (action instanceof CarriedTool.Action.LockedOutside outside) {
            message(player, lockMessage(outside.state(), outside.side(), false));
        } else if (action instanceof CarriedTool.Action.LockedInside inside) {
            message(player, lockMessage(inside.state(), inside.side(), true));
        }
        if (action != null) {
            // The picked host's record changed, so the client is told and the world around the position the
            // host is projected onto re-reads the signal it offers. The broadcast is a write to a live
            // record, so it is guarded on its own - with the log line still written outside the guard, the
            // way every other action path here does it.
            CarrierGuards.OTHER.call(decision.clickWorld(), () -> {
                CarrierStore.broadcastCarrierNow(level, carrier);
                HostEntry entrySlot = CarriedTool.entryOf(CarrierStore.indexOf(level), carrier,
                        lockedLocal(action));
                if (entrySlot != null && decision.pickedWorld() != null) {
                    HostSync.publishWorldEntry(level, decision.pickedWorld());
                }
                if (decision.pickedWorld() != null) {
                    InnerRedstoneNetwork.notifyOutputChanged(level, decision.pickedWorld());
                }
                return Boolean.TRUE;
            }, false);
        }
        if (!RCConfig.debugLog()) {
            return;
        }
        if (action != null) {
            RCConfig.LOGGER.info("[redstonecircuit] carrier {}: {} entry={}", carrier.shortId(),
                    action instanceof CarriedTool.Action.LockedInside
                            ? CarrierLinkLedger.insideLine(action, CarrierStore.indexOf(level))
                            : CarrierLinkLedger.outsideLine(action, CarrierStore.indexOf(level)),
                    entry);
            return;
        }
        if (needsANamedAnswer(status)) {
            RCConfig.LOGGER.info("[redstonecircuit] carrier {}: {} entry={}",
                    carrier == null ? "-" : carrier.shortId(),
                    outsideRefusalLine(decision, carrier), entry);
        }
    }

    /**
     * <b>The statuses whose answer is BOTH a sentence on the action bar and a line on the ledger.</b>
     *
     * <p>Five of the eleven are named refusals: the click came here, the chain answered it, and the player has
     * to be told which question said no (and the log has to say the same thing). The other six say nothing on
     * either half: {@link OutsideStatus#LOCKED} and {@link OutsideStatus#LOCKED_INSIDE} are not refusals -
     * they wrote something, and their own line and sentence are built from the record ({@code outsideLine} /
     * {@code insideLine} / {@code lockMessage}); {@link OutsideStatus#NOTHING_PICKED},
     * {@link OutsideStatus#NOT_OUTSIDE} and {@link OutsideStatus#NOT_INSIDE} are clicks that belong to
     * <b>another</b> gesture, decided and reported a moment later; and {@link OutsideStatus#NO_COMPONENT} is
     * the picked host emptied between the pick and the click, which the world's own wrench says nothing about
     * either.
     *
     * <p>The two sentences reused for {@link OutsideStatus#UNREADABLE_POSITION} and
     * {@link OutsideStatus#NO_SUCH_BLOCK} are the world's own: a machine whose position cannot be read is the
     * same fact as one whose orientation cannot ({@code outside_no_pose}), and "there is no block on that
     * side" is literally {@code nothing_there}. Reusing them keeps the player-facing rule "no new words
     * without a reason".
     */
    static boolean needsANamedAnswer(OutsideStatus status) {
        return switch (status) {
            case NOT_ADJACENT, UNREADABLE_POSE, UNREADABLE_POSITION, NO_SUCH_BLOCK,
                 FAR_END_NOT_THE_PICKED_STRUCTURE -> true;
            case LOCKED, LOCKED_INSIDE, NOTHING_PICKED, NOT_OUTSIDE, NOT_INSIDE, NO_COMPONENT -> false;
        };
    }

    /**
     * The sentence a named refusal wears, in the world's own words where it has them.
     *
     * <p>Only ever asked when {@link #needsANamedAnswer} is true; every other status gets nothing rather
     * than a placeholder, so a future status cannot acquire an empty sentence by accident.
     */
    private static Component refusalMessage(OutsideStatus status) {
        return switch (status) {
            case NOT_ADJACENT, FAR_END_NOT_THE_PICKED_STRUCTURE -> Component.translatable(
                    "message.redstonecircuit.wrench.not_adjacent_carrier");
            case UNREADABLE_POSE, UNREADABLE_POSITION -> Component.translatable(
                    "message.redstonecircuit.wrench.outside_no_pose");
            case NO_SUCH_BLOCK -> Component.translatable(
                    "message.redstonecircuit.wrench.nothing_there");
            default -> Component.empty();
        };
    }

    /** The carrier a lock wrote into - the near end, which is the only end there is. */
    private static CarrierId lockedCarrier(CarriedTool.Action action) {
        return action instanceof CarriedTool.Action.LockedInside inside ? inside.carrier()
                : ((CarriedTool.Action.LockedOutside) action).carrier();
    }

    /** The coordinate inside that structure. */
    private static BlockPos lockedLocal(CarriedTool.Action action) {
        return action instanceof CarriedTool.Action.LockedInside inside ? inside.localPos()
                : ((CarriedTool.Action.LockedOutside) action).localPos();
    }

    /**
     * The refusal's own line, for the two cases that are named rather than silent.
     *
     * <p>A separate shape from {@link CarrierLinkLedger#outsideLine}: there is no record to
     * describe, and the one fact a reader needs is <b>which question refused the click</b>, beside the two
     * positions that were compared. The other reason it says {@code OUTSIDE} as well is grepability: a
     * reader who wants every click of this gesture, acted on or not, greps one word.
     */
    private static String outsideRefusalLine(OutsideDecision decision, @Nullable CarrierId carrier) {
        return "link refused OUTSIDE the structure status=" + decision.status()
                + (carrier == null ? "" : " picked=" + carrier.shortId())
                + " click=" + decision.clickWorld().toShortString()
                + " pickedWorld=" + (decision.pickedWorld() == null ? "?"
                        : decision.pickedWorld().toShortString())
                + " blocked-by=" + decision.blockedBy()
                + " - nothing was written (see CarrierInteraction#planOutside for which question answered "
                + "this)";
    }

    /**
     * The action bar sentence for a locked line: the world's own words, once per far end - the two key sets
     * say the same thing about the same rule and differ only in <b>where the far end is</b>, which is the one
     * fact the world's own sentence for this rule cannot express.
     */
    private static Component lockMessage(ConnectionState state, Direction side, boolean inside) {
        Component text = Component.translatable(
                switch (state) {
                    case ON -> inside ? "message.redstonecircuit.wrench.linked_inside_on"
                            : "message.redstonecircuit.wrench.linked_outside_on";
                    case OFF -> inside ? "message.redstonecircuit.wrench.linked_inside_off"
                            : "message.redstonecircuit.wrench.linked_outside_off";
                    case AUTO -> inside ? "message.redstonecircuit.wrench.linked_inside_auto"
                            : "message.redstonecircuit.wrench.linked_outside_auto";
                },
                Component.literal(side.getName()));
        return switch (state) {
            case ON -> text.copy().withStyle(ChatFormatting.GREEN);
            case OFF -> text.copy().withStyle(ChatFormatting.RED);
            case AUTO -> text.copy().withStyle(ChatFormatting.GRAY);
        };
    }

    /** Two hosts and the structure each one lives in: what a cross-structure line needs. */
    public record CrossLink(CarrierId pickedCarrier, BlockPos pickedLocal, BlockPos pickedWorld,
                            CarrierId clickCarrier, BlockPos clickLocal, BlockPos clickWorld,
                            Direction worldSide, Direction pickedLocalSide,
                            Direction clickLocalSide) {

        /** True when the two blocks really do stand next to each other in the world. */
        public boolean adjacent() {
            return worldSide != null && pickedLocalSide != null && clickLocalSide != null;
        }
    }

    /**
     * <b>The two structures behind one shift-click, or {@code null} when a cross-structure line is not what
     * this click is.</b>
     *
     * <p>A cross-structure link is allowed because <b>both records live in the same
     * {@link CarriedHostIndex}, keyed by {@code (carrier, localPos)}</b>, so writing both is one call to the
     * same method the single-structure case uses - and two separately physicalised blocks standing side by
     * side really are two structures (Sable's granularity, one sub-level per assembled block).
     *
     * <p>What the coordinates have to be is the part that is not obvious, and it is why this record exists:
     * the shape of the connection is a fact about <b>where the two blocks stand</b> (one shared world face),
     * and it has to be written into <b>each structure's own frame</b>, because the near end's coordinate and
     * the far end's are in two different spaces. So the shared face is computed once, in the world, and then
     * turned into each structure's own direction through that structure's own pose.
     *
     * @param index the carrier index both hosts live in, or {@code null}
     * @param selected the pick the player made earlier, or {@code null}
     * @param here the structure the shift-click landed on
     * @param herePos its coordinate inside that structure
     * @param hereWorld where that host stands right now
     * @param herePose that structure's pose, or {@code null} when it cannot be read
     * @return the two ends, or {@code null} when this is not a cross-structure line (no pick, the pick is
     *     in this structure, or the two blocks are not neighbours in the world)
     */
    @Nullable
    static CrossLink crossLink(@Nullable ServerLevel level, CarriedHostIndex index,
                               @Nullable CarriedSelection.Selection selected,
                               CarrierId here, BlockPos herePos, BlockPos hereWorld,
                               @Nullable CarrierPose herePose) {
        if (index == null || selected == null || here == null || herePos == null || hereWorld == null) {
            return null;
        }
        if (selected.carrier() == null || selected.carrier().equals(here)) {
            // The same structure is the ordinary local-frame link, and no pick at all is the ordinary
            // "nothing picked yet" - neither is a cross-structure line.
            return null;
        }
        CarriedHost picked = CarriedTool.hostAt(index, selected.carrier(), selected.localPos());
        if (picked == null || picked.slot() == null) {
            // The picked host is gone or holds nothing: the ordinary refusal names that, and this must not
            // turn it into a complaint about two structures.
            return null;
        }
        BlockPos pickedWorld = selected.worldPos();
        if (pickedWorld == null) {
            return null;
        }
        // THE SHARED FACE, ONCE, IN THE WORLD. `directionBetween` answers the direction from the first
        // position to the second when they are neighbours, so the two ends are "one block apart along a real
        // axis" and nothing else.
        Direction worldSide = WrenchLinks.directionBetween(pickedWorld, hereWorld).orElse(null);
        if (worldSide == null) {
            return null;
        }
        CarrierPose pickedPose = null;
        if (level != null) {
            try {
                pickedPose = CarrierStore.poseOf(level, selected.carrier());
            } catch (RuntimeException | LinkageError unreadable) {
                pickedPose = null;
            }
        }
        return new CrossLink(selected.carrier(), selected.localPos(), pickedWorld, here, herePos,
                hereWorld, worldSide,
                CarrierDirections.localDirection(pickedPose, worldSide),
                // THE FAR END'S SIDE IS THE SAME LINE SEEN FROM THE OTHER END. `worldSide` runs from the
                // pick towards the click, so the direction that leaves the clicked block towards the pick
                // is its opposite - and it is that one which has to be turned into the clicked structure's
                // own frame, exactly as the world's own wrench records the opposite direction on the far
                // end (see `WrenchLinks#link`).
                CarrierDirections.localDirection(herePose, worldSide.getOpposite()));
    }

    /**
     * <b>The cross-structure link, written into both structures' records in each structure's own frame.</b>
     *
     * <p>The state the line takes is {@link WrenchLinks#next} of the state the near end already has on its own
     * side, the same cycle the single-structure link uses, so a player clicks through
     * {@code AUTO -> ON -> OFF -> AUTO} on a line between two machines exactly as on a line between two blocks
     * of one machine. The far end is given the same state on the opposite local side, because "the connection
     * between these two blocks" is one fact seen from two ends.
     *
     * <p><b>Power does not cross the gap:</b> each structure's solve walks only the records it carries
     * ({@code CarrierSpace#nodeAt} is keyed by this carrier), so the two solves stay independent - only one
     * record being written must not be left readable as a half-connection, which is why <b>both</b> ends are
     * written and a later join of the two blocks into one structure reads a line that is already consistent.
     *
     * @return what was written, named for the ledger
     */
    static CarriedTool.Action linkAcross(CarriedHostIndex index, CrossLink plan) {
        if (index == null || plan == null || !plan.adjacent()) {
            return new CarriedTool.Action.NotAdjacent(plan == null ? null : plan.clickCarrier(),
                    plan == null ? BlockPos.ZERO : plan.clickLocal(), BlockPos.ZERO);
        }
        CarriedHost near = CarriedTool.hostAt(index, plan.pickedCarrier(), plan.pickedLocal());
        if (near == null || near.slot() == null) {
            return new CarriedTool.Action.NoComponent(plan.pickedCarrier(), plan.pickedLocal(),
                    "wrench");
        }
        Slot first = near.slot();
        com.jiangyuefengyu.redstonecircuit.data.ConnectionState after =
                WrenchLinks.next(WrenchLinks.stateOf(first, plan.pickedLocalSide()));
        first.setConnection(plan.pickedLocalSide(), after);
        index.updateSlot(plan.pickedCarrier(), plan.pickedLocal(), first);

        CarriedHost far = CarriedTool.hostAt(index, plan.clickCarrier(), plan.clickLocal());
        if (far == null || far.slot() == null) {
            return new CarriedTool.Action.CrossFarEndEmpty(plan.pickedCarrier(), plan.pickedLocal(),
                    plan.clickCarrier(), plan.clickLocal(), plan.pickedLocalSide(), after);
        }
        Slot second = far.slot();
        second.setConnection(plan.clickLocalSide(), after);
        index.updateSlot(plan.clickCarrier(), plan.clickLocal(), second);
        return new CarriedTool.Action.CrossLinked(plan.pickedCarrier(), plan.pickedLocal(),
                plan.clickCarrier(), plan.clickLocal(), plan.pickedLocalSide(), plan.clickLocalSide(),
                after);
    }

    /**
     * Whether the picked block and this click are <b>neighbours in the world</b> - asked only to word a
     * refusal, since "you clicked the wrong machine" and "these two blocks are not connected anywhere" are
     * different problems. The pick's position is the one recorded when it was picked and this host's is the
     * one its pose computes now, which is the honest pair of answers for "where do these two stand".
     */
    private static boolean worldAdjacent(CarriedSelection.Selection selected, CarrierId carrier,
                                         BlockPos localPos, @Nullable CarrierPose pose) {
        BlockPos here = pose == null ? localPos : CarrierProjection.project(pose, localPos);
        return here != null && WrenchLinks.directionBetween(selected.worldPos(), here).isPresent();
    }

    /** The carrier's pose, from the record when the hit has one and from the store when it does not. */
    @Nullable
    private static CarrierPose poseOf(ServerLevel level, CarriedBlockRemoval.Hit hit) {
        CarrierPose pose = hit.pose();
        return pose != null ? pose : CarrierStore.poseOf(level, hit.carrier());
    }

    /**
     * <b>The three writes every carrier action makes, in the one order that works.</b>
     *
     * <p>The carrier payload first (so the client's host list is replaced wholesale), then the single host
     * entry (so the overlay redraws the routing and the facing at once, since no block state changed), then
     * the world notification (so whatever is reading this host's signal at the position it is projected onto
     * re-reads it). Written once because the wrench, the rotation rod and the bare-hand switch all need
     * exactly these three, and a second copy is how one of them ends up skipping the second.
     *
     * <p><b>Every step tolerates an empty host.</b> {@code CarriedTool#entryOf} answers {@code null} for a
     * host that holds nothing, and then the single-host broadcast is not sent: there is nothing to draw
     * there, and sending "this position holds nothing" would fight the world's own host sync for a block that
     * is still riding.
     */
    private static void broadcastHostChange(ServerLevel level, CarriedBlockRemoval.Hit hit,
                                            @Nullable CarriedHostIndex index) {
        CarrierStore.broadcastCarrierNow(level, hit.carrier());
        HostEntry entrySlot = CarriedTool.entryOf(index, hit.carrier(), hit.localPos());
        if (entrySlot != null) {
            // The world-side entry for a position a carried host is only DRAWN at is cleared, not written -
            // HostSync#publishWorldEntry is that rule, and the device screens' write path uses it too.
            HostSync.publishWorldEntry(level, hit.worldPos());
        }
        // The world-side entry is named beside both coordinates, so a report of a component left standing at
        // a world position can say which of the two it is: a skipped broadcast, or a world position that is
        // not the one the client drew that host at.
        if (com.jiangyuefengyu.redstonecircuit.RCConfig.debugLog()) {
            com.jiangyuefengyu.redstonecircuit.RCConfig.LOGGER.info(
                    "[redstonecircuit] carried host change: carrier={} local={} world={} worldEntry={}",
                    hit.carrier(), hit.localPos().toShortString(), hit.worldPos().toShortString(),
                    entrySlot == null ? "skipped (nothing held there)" : "cleared");
        }
        // Best-effort by nature - the solve re-derives on the next change - but counted by the guard that
        // wraps this method, so a failure here is in the ledger and not only in the log.
        InnerRedstoneNetwork.notifyOutputChanged(level, hit.worldPos());
    }

    /**
     * Broadcasts what changed, writes the judgement line, and traces the click.
     *
     * <p>The broadcast is the carrier payload the pick-up and put-down paths already use, plus the single
     * host entry: the connection bars and the component's facing are drawn from the host records the client
     * holds, and no block state changed, so this is the only thing that can stop the overlay showing the old
     * routing.
     */
    private static void reportTool(ServerLevel level, ServerPlayer player, CarriedBlockRemoval.Hit hit,
                                   Direction face, @Nullable CarriedTool.Action action, boolean wrench,
                                   String entry, InteractionHand hand) {
        // THE BROADCAST IS ITS OWN GUARDED STEP: the trace line has to be written whether or not the broadcast
        // succeeded, because it is the only evidence that this hook was reached - and a reporting step that
        // throws must not also erase the record of the click.
        CarrierGuards.SWITCH.call(hit.localPos(), () -> {
            broadcastHostChange(level, hit, CarrierStore.indexOf(level));
            // A LINE ACROSS TWO STRUCTURES CHANGED TWO RECORDS, so both structures have to be re-sent:
            // the near one to redraw the routing the lock just changed, and the far one because its own
            // record's override changed too. The far end is named by the ACTION, which is the one place
            // that knows which structure the pick is really in.
            CarrierId far = farCarrierOf(action);
            if (far != null && !far.equals(hit.carrier())) {
                CarrierStore.broadcastCarrierNow(level, far);
                BlockPos farLocal = farLocalOf(action);
                HostEntry farEntry = farLocal == null ? null
                        : CarriedTool.entryOf(CarrierStore.indexOf(level), far, farLocal);
                if (farEntry != null) {
                    BlockPos farWorld = CarrierStore.currentWorldPos(level, far, farLocal);
                    if (farWorld != null) {
                        HostSync.publishWorldEntry(level, farWorld);
                    }
                }
            }
            return Boolean.TRUE;
        }, false);
        message(player, describe(action, CarrierStore.indexOf(level)));
        trace(entry, player, hand, hit.localPos(), face, hit, CarrierEntryTrace.Outcome.MATCH,
                action == null ? null : action.describe());
        logAction(entry, hit, face, action == null ? "no action" : action.describe(),
                CarrierGesture.toolLabel(wrench ? CarrierGesture.Kind.WRENCH : CarrierGesture.Kind.ROD),
                poseOf(level, hit),
                wrench ? linkLine(action, CarrierStore.indexOf(level), player, hit) : null);
    }

    /** The structure at the other end of a cross-structure line, or {@code null} for every other action. */
    @Nullable
    private static CarrierId farCarrierOf(@Nullable CarriedTool.Action action) {
        if (action instanceof CarriedTool.Action.CrossLinked linked) {
            return linked.farCarrier();
        }
        if (action instanceof CarriedTool.Action.CrossFarEndEmpty far) {
            return far.farCarrier();
        }
        return null;
    }

    /** The far end's coordinate inside its own structure, or {@code null}. */
    @Nullable
    private static BlockPos farLocalOf(@Nullable CarriedTool.Action action) {
        if (action instanceof CarriedTool.Action.CrossLinked linked) {
            return linked.farLocalPos();
        }
        if (action instanceof CarriedTool.Action.CrossFarEndEmpty far) {
            return far.farLocalPos();
        }
        return null;
    }

    /**
     * The link's own judgement line, built from the pick as this click saw it.
     *
     * <p>Reads the pick from the ledger rather than from an argument, because the ledger is per player
     * and this is the only place a log line is written: what it prints is therefore exactly what the
     * decision was made from. See {@link CarrierLinkLedger} for why every one of the four facts has to be
     * on the line.
     */
    @Nullable
    private static CarrierLinkLedger.LinkLine linkLine(@Nullable CarriedTool.Action action,
                                                       @Nullable CarriedHostIndex index,
                                                       ServerPlayer player,
                                                       CarriedBlockRemoval.Hit hit) {
        CarriedSelection.Selection picked = CarriedSelection.get(player);
        return CarrierLinkLedger.of(action, index,
                picked == null ? null : picked.carrier(),
                picked == null ? null : picked.localPos(),
                hit.carrier(), hit.localPos());
    }
    /**
     * The placement half of the same reporting: one broadcast pair, one action line - the same three writes as
     * {@link #reportTool} in the same order, because the client's picture is assembled from exactly those and
     * a placement that skipped one of them leaves a component in the index and not on screen.
     */
    private static void reportPlacement(ServerLevel level, ServerPlayer player,
                                        CarriedBlockRemoval.Hit hit, Direction face,
                                        CarriedInsert.Action action, String entry,
                                        InteractionHand hand) {
        // GUARDED STEP, AND THE TRACE LINE IS OUTSIDE IT. The broadcasts touch a live record's contents -
        // and on a retrieval that record now holds nothing - so a failure has to be contained; the trace
        // line has to be written either way, because it is the only evidence the click was seen at all.
        CarrierGuards.INSERT.call(hit.localPos(), () -> {
            broadcastPlacementChange(level, hit, action);
            return Boolean.TRUE;
        }, false);
        message(player, describePlacement(action));
        trace(entry, player, hand, hit.localPos(), face, hit, CarrierEntryTrace.Outcome.MATCH,
                action.describe());
        logAction(entry, hit, face, action.describe(),
                CarrierGesture.toolLabel(CarrierGesture.Kind.PLACEMENT), poseOf(level, hit), null);
    }

    /**
     * The placement's three writes, with the one difference from {@link #broadcastHostChange}: a retrieval has
     * to <b>say</b> that the position holds nothing.
     *
     * <p>The world's own retrieval sends exactly that - a world-host removal at the position the host is
     * projected onto - and it is sent <b>after</b> the carrier payload, so the carrier message cannot put the
     * old host back. What that world-side write cannot do is name the <em>carried</em> host: it is keyed by
     * the projected world position, which is rebuilt every tick from the pose, while the client's record of a
     * carried host is keyed by the structure's own coordinate - which is what the removal notice is for.
     *
     * <p><b>A retrieval says it the way a break does, because the carrier message cannot.</b> The carrier
     * payload reports the hosts that are still riding <b>with their components</b>, and an emptied host is not
     * one of them ({@code HostSync#carrierPayload}). On the wire, "not in the list" is how a host that has been
     * <b>put down</b> travels, and the client keeps those as a landed record so a component put down over air
     * stays visible: an emptied host that simply left the list would be read as a landing, with the client
     * stowing the record it was holding - <b>component and all</b> - and drawing it where the block came from.
     * The notice {@link CarriedInsert#removalNotice} answers is the one the break path already sends (the
     * {@code removed} list, filled by {@code CarrierStore#dropCandidate}): the same field, the same
     * client-side handling, nothing new on the wire.
     */
    private static void broadcastPlacementChange(ServerLevel level, CarriedBlockRemoval.Hit hit,
                                                 CarriedInsert.Action action) {
        // THE NOTICE FIRST, AND IT IS PART OF THE SAME MESSAGE. A retrieval leaves the block riding and
        // holding nothing, so the carrier payload's host list cannot express it (see the javadoc above);
        // naming the local coordinate is what stops the client inventing a landing for it.
        List<BlockPos> removed = CarriedInsert.removalNotice(action, hit.localPos());
        CarrierStore.broadcastCarrierNow(level, hit.carrier(), removed);
        HostEntry entrySlot = CarriedTool.entryOf(CarrierStore.indexOf(level), hit.carrier(),
                hit.localPos());
        if (entrySlot != null) {
            HostSync.broadcast(level, hit.worldPos(), entrySlot.slot());
        } else {
            HostSync.broadcast(level, hit.worldPos(), null);
        }
        InnerRedstoneNetwork.notifyOutputChanged(level, hit.worldPos());
    }

    /**
     * The acceptance line: carrier, local coordinate, world position, action, face and tool.
     *
     * <p>{@code tool=} is the gesture that <b>actually ran</b>, named through
     * {@link CarrierGesture#toolLabel} rather than derived from a boolean at the call site - otherwise a
     * bare-hand switch and a retrieval are both printed as {@code tool=rotation rod}, and the log claims a rod
     * was involved in actions that no rod had anything to do with.
     */
    private static void logAction(String entry, CarriedBlockRemoval.Hit hit, Direction face,
                                  String action, String tool, @Nullable CarrierPose pose,
                                  @Nullable CarrierLinkLedger.LinkLine link) {
        RCConfig.LOGGER.info("[redstonecircuit] carrier {}: {} riding {} local={} world={} face={} "
                        + "tool={} entry={}",
                hit.carrier().shortId(), action,
                hit.carrier().shortId(), hit.localPos().toShortString(), hit.worldPos().toShortString(),
                face == null ? "?" : CarrierDirections.localDirection(pose, face).getName(),
                tool, entry);
        // THE LINK'S OWN LINE, written whenever the gesture was the wrench, success or refusal: "it linked"
        // and "it refused, for this reason" are the two halves of one question, and a line that only appeared
        // on success would be silent about the failures. See CarrierLinkLedger.
        if (link != null && RCConfig.debugLog()) {
            RCConfig.LOGGER.info("[redstonecircuit] carrier {}: {} entry={}",
                    hit.carrier().shortId(), link.describe(), entry);
        }
    }

    /** The hand a delivery is not for; the same helper {@link CarrierGesture} uses. */
    private static CarrierGesture.Hold holdOf(ServerPlayer player, @Nullable InteractionHand hand) {
        return hand == null ? CarrierGesture.Hold.SOMETHING_ELSE
                : CarrierGesture.Hold.of(player.getItemInHand(hand));
    }

    // ------------------------------------------------------------- the test seams --

    /**
     * <b>{@link #noteGesture}'s rule, driven by two hands instead of a player - tests only.</b>
     *
     * <p>The production form reads the two hands off a {@link ServerPlayer}, which a unit test cannot build.
     * Everything else about the report is the same code - the same rule ({@link CarrierGesture#of}), the same
     * formatting ({@link #describeHold} and {@link #gestureNote}) and the same emission
     * ({@link CarrierEntryTrace}) - so the assertion is about the production path and not about a copy of it.
     *
     * @return what the gesture rule answered, which is what the caller would have gated on
     */
    static CarrierGesture.Kind noteGestureForTests(CarrierGesture.Hold hand, CarrierGesture.Hold other,
                                                   boolean sneaking, @Nullable BlockPos pos,
                                                   @Nullable Direction face) {
        CarrierGesture.Kind gesture = CarrierGesture.of(hand, other, sneaking);
        // A null level and a null player are the shape the trace takes for an event no player caused: the
        // line is still formatted and still emitted, which is the whole point of the seam.
        CarrierEntryTrace.entry(null, "RIGHT_CLICK_BLOCK", null, pos, face, null, null,
                CarrierEntryTrace.Outcome.NONE, null,
                "gesture=" + gesture
                        + " by=" + CarrierGesture.toolLabel(gesture)
                        + " hand=[" + describeHold(hand) + "]"
                        + " other=[" + describeHold(other) + "]"
                        + " sneaking=" + sneaking
                        + " " + gestureNote(gesture, hand, other, null, sneaking));
        return gesture;
    }

    /**
     * <b>One method's own source text, so a rule about ORDER can be asserted - tests only.</b>
     *
     * <p>An ordering defect is not a wrong answer, it is a line written <em>too late</em>: if the gesture were
     * asked before the report, "the hook was never called" and "the hook said no" would produce the same empty
     * log, and <b>every other assertion in the suite would still hold</b>, because the behaviour - which action
     * runs - is identical either way. So the one test that pins the order reads this method's body and
     * compares positions.
     *
     * <p>Development tree only, by construction: the source file is beside the class file. A production jar
     * answers an empty string.
     */
    public static String sourceOf(String method) {
        try {
            String relative = CarrierInteraction.class.getName().replace('.', '/') + ".java";
            java.io.File own = new java.io.File(CarrierInteraction.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            // The class file sits in `build/classes/java/main` in a development tree and inside the jar in
            // a release, so the source is found by walking a bounded number of parent directories and
            // asking for the one relative path that can exist - never by trusting one layout.
            java.io.File directory = own.isDirectory() ? own : own.getParentFile();
            java.io.File source = null;
            for (int up = 0; up < 8 && directory != null && source == null; up++) {
                for (String candidate : new String[] {relative, "src/main/java/" + relative,
                        "neoforge/src/main/java/" + relative}) {
                    java.io.File file = new java.io.File(directory, candidate);
                    if (file.isFile()) {
                        source = file;
                        break;
                    }
                }
                directory = directory.getParentFile();
            }
            if (source == null) {
                return "";
            }
            String text = java.nio.file.Files.readString(source.toPath());
            int at = text.indexOf(method + "(");
            if (at < 0) {
                return "";
            }
            int open = text.indexOf('{', at);
            if (open < 0) {
                return "";
            }
            int depth = 0;
            for (int i = open; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '{') {
                    depth++;
                } else if (c == '}' && --depth == 0) {
                    return text.substring(open, i + 1);
                }
            }
            return text.substring(open);
        } catch (RuntimeException | java.net.URISyntaxException | java.io.IOException unreadable) {
            return "";
        }
    }

    /**
     * <b>The first statement of both entry points: what the gesture rule made of this click, written before
     * anything can return.</b>
     *
     * <p>Two opposite conclusions fit a click that leaves no trace at all - it never reached this listener, or
     * it reached it and {@link CarrierGesture} answered {@code NONE} and the entry point returned before its
     * first trace line - and the log cannot choose between them. This makes the choice mechanical: every click
     * that reaches an entry point writes one {@code -> NONE gesture=…} line naming <b>both hands</b> and the
     * branch that answered, and only a click that never arrived writes nothing. It is a report, not a
     * decision: nothing here reads the world, resolves a host or touches a record, and the gesture it is handed
     * is the one the caller already computed, so the line and the rule cannot disagree.
     *
     * @param player the player behind the click
     * @param hand the hand this delivery was for
     * @param pos the position the event carried, or {@code null} when it carried none
     * @param face the face the event carried, or {@code null}
     * @param gesture what {@link CarrierGesture#of(ServerPlayer, InteractionHand)} answered
     * @param entry which entry point this is, for the line
     */
    static void noteGesture(ServerPlayer player, @Nullable InteractionHand hand, @Nullable BlockPos pos,
                            @Nullable Direction face, CarrierGesture.Kind gesture, String entry) {
        if (player == null || gesture == null) {
            return;
        }
        try {
            CarrierGesture.Hold mine = holdOf(player, hand);
            CarrierGesture.Hold theirs = hand == null ? CarrierGesture.Hold.NOTHING
                    : CarrierGesture.Hold.of(player.getItemInHand(CarrierGesture.otherHand(hand)));
            boolean sneaking = player.isSecondaryUseActive();
            long tick = player.level() instanceof ServerLevel serverLevel ? serverLevel.getGameTime() : -1L;
            String detail = "gesture=" + gesture
                    + " by=" + CarrierGesture.toolLabel(gesture)
                    + " hand=[" + describeHold(mine) + "]"
                    + " other=[" + describeHold(theirs) + "]"
                    + " sneaking=" + sneaking
                    + " " + gestureNote(gesture, mine, theirs, hand, sneaking)
                    // THE OTHER HALF OF THIS CLICK. The world's listener declines a coordinate a structure owns
                    // and prints its own line for it; this bracket says whether that line belongs to this
                    // delivery - same tick, same position, same hand - so the two halves of one click can be
                    // paired in a log instead of being counted. `worldDecline=none` is a statement, not an
                    // absence: it means the world side did not own this delivery.
                    + " | " + CarrierClickPairing.carrierNote(tick, pos, hand);
            trace(entry, player, hand, pos, face, null, CarrierEntryTrace.Outcome.NONE, detail);
        } catch (RuntimeException | LinkageError failed) {
            // A report is never allowed to take a click down; the guard is the shared one, so a failure
            // here is counted beside every other carrier hook's.
            CarrierGuards.BLOCK_CLICK.fail(pos, failed);
        }
    }

    /** What one hand holds, as four plain words - the whole input of the rule, on the line. */
    private static String describeHold(CarrierGesture.Hold hold) {
        if (hold == null) {
            return "?";
        }
        if (hold.empty()) {
            return "empty";
        }
        StringBuilder sb = new StringBuilder();
        if (hold.wrench()) {
            sb.append("wrench");
        }
        if (hold.rod()) {
            sb.append(sb.length() > 0 ? "+rod" : "rod");
        }
        if (hold.component()) {
            sb.append(sb.length() > 0 ? "+component" : "component");
        }
        if (hold.wrench() || hold.rod() || hold.component()) {
            // A component's own name is worth more than the word "component": `held=` says what would go in.
            return sb.toString();
        }
        return "other item";
    }

    /**
     * <b>Which branch of the rule answered, in the rule's own words.</b>
     *
     * <p>One sentence per branch, so the line can be grepped for the branch rather than read: "a tool in
     * this hand owns the click", "the other hand's tool owns it", "this hand holds nothing the carrier
     * layer knows", and so on. It is written from the same facts the rule used, so a later change to the
     * rule that is not reflected here is visible as a line that contradicts itself.
     */
    private static String gestureNote(CarrierGesture.Kind gesture, CarrierGesture.Hold mine,
                                      CarrierGesture.Hold theirs, @Nullable InteractionHand hand,
                                      boolean sneaking) {
        if (gesture != CarrierGesture.Kind.NONE) {
            return "this click belongs to the carrier layer";
        }
        if (theirs.wrench() || theirs.rod()) {
            return "the OTHER hand's tool owns the whole click (see CarrierGesture)";
        }
        if (theirs.component()) {
            return "the OTHER hand's component owns the placement";
        }
        if (!mine.empty()) {
            return "this hand holds nothing the carrier layer has a gesture for: no tool, no component, "
                    + "and not an empty hand";
        }
        if (!theirs.empty()) {
            return "the player is holding something in the other hand, so this is not the bare-hand "
                    + "gesture (no switch, no retrieval)";
        }
        return "two empty hands, sneaking=" + sneaking + " - this should have been a gesture";
    }

    private static void trace(String entry, ServerPlayer player, @Nullable InteractionHand hand,
                              @Nullable BlockPos rawPos, @Nullable Direction face,
                              @Nullable CarriedBlockRemoval.Hit hit,
                              CarrierEntryTrace.Outcome outcome, @Nullable String note) {
        ServerLevel level = player.level() instanceof ServerLevel serverLevel ? serverLevel : null;
        CarrierEntryTrace.entry(level, entry, player, rawPos, face, hand,
                hand == null ? null : player.getItemInHand(hand), outcome, hit, note);
    }

    /**
     * A miss, whose "what did I look it up with" text is only built when the diagnostics are on.
     *
     * <p>{@link CarrierEntryTrace#lookupAt} reads the index to say how much it held, and every one of these
     * call sites is on a click a player can make all day (a bare hand on any block), so the gate is applied
     * <b>before</b> the supplier runs: with {@code debugLog} off this costs one boolean and nothing else.
     */
    private static void traceMiss(String entry, ServerPlayer player, @Nullable InteractionHand hand,
                                  @Nullable BlockPos rawPos, @Nullable Direction face,
                                  java.util.function.Supplier<String> detail) {
        if (!RCConfig.debugLog()) {
            return;
        }
        trace(entry, player, hand, rawPos, face, null, CarrierEntryTrace.Outcome.MISS, detail.get());
    }

    /**
     * <b>The lock-attempt line: "this click could have been a lock, and here is what became of it".</b>
     *
     * <p>A pick followed by a shift-click on the block beside it can end with no lock, no refusal and no trace
     * line at all - and "the log says nothing" is unreadable, because every answer the chain can give would
     * look the same. So this prints the chain in the order the decision asks it, with the value each question
     * returned and the step that stopped it.
     *
     * <p>Written only when all three of these hold, which keeps it rare on a normal click and <b>exactly
     * once</b> on the click that matters: {@code debugLog} is on - the same switch every other per-click line
     * is behind; the dispatched hand holds a <b>wrench</b>, without which this gesture does not exist; and a
     * carried host <b>is picked</b>, because without a pick there is no near end and the click is not a lock
     * attempt at all.
     *
     * <p>Diagnostics only, and bounded by construction: it reads the selection ledger (a map lookup), the
     * ownership answer the caller already computed, and one adjacency comparison on two {@code BlockPos}s. It
     * resolves nothing, writes nothing, and touches no structure.
     *
     * @param hand the hand this delivery is for
     * @param clickPos the position the event carried, unchanged
     * @param gesture what the click is, as {@link CarrierGesture} answered it
     * @param componentInHand whether that same hand holds a component - printed beside the gesture so the
     *     two can never look like they disagree
     * @param hit the carried host this click resolved to, or {@code null} when it resolved to none
     * @param decision the decision the chain reached, or {@code null} when the resolver answered first
     * @param pickedPos where the pick stands in the frame the gesture that answered compares in, or
     *     {@code null} when it could not be named
     */
    private static void lockAttempt(ServerPlayer player, @Nullable InteractionHand hand,
                                    @Nullable BlockPos clickPos, CarrierGesture.Kind gesture,
                                    boolean componentInHand,
                                    @Nullable CarriedBlockRemoval.Hit hit,
                                    @Nullable OutsideDecision decision, @Nullable BlockPos pickedPos) {
        if (!RCConfig.debugLog() || player == null) {
            return;
        }
        try {
            if (gesture != CarrierGesture.Kind.WRENCH) {
                // Not the wrench: there is no lock gesture here to have been lost.
                return;
            }
            CarriedSelection.Selection selected = CarriedSelection.get(player);
            if (selected == null || selected.carrier() == null) {
                // No near end: this is a select click or a stray shift-click, not a lock attempt.
                return;
            }
            BlockPos click = clickPos == null ? BlockPos.ZERO : clickPos;
            Direction side = pickedPos == null ? null
                    : WrenchLinks.directionBetween(pickedPos, click).orElse(null);
            StringBuilder chain = new StringBuilder("pick, tool+shift, ");
            chain.append(decision == null ? "resolver-hit" : "ownership");
            chain.append(side == null ? ", not-adjacent" : ", adjacent(" + side.getName() + ")");
            chain.append(", pose, component");
            if (decision != null) {
                chain.append(", ").append(decision.status().acted() ? "WRITE" : "refuse");
            }
            RCConfig.LOGGER.info("[redstonecircuit] carrier lock attempt?: entry=RIGHT_CLICK_BLOCK by={} "
                            + "raw={} hand={} item={} tool={} -> pick={} local={} ({}) "
                            + "hit={} isThePickItself={} pickedAt={} clickAt={} side={} "
                            + "sneaking={} goggles={} | chain=[{}] blocked-by={} -> status={} {}",
                    CarrierEntryTrace.whoOf(player), CarrierEntryTrace.posText(clickPos),
                    hand, CarrierEntryTrace.itemText(hand == null ? null : player.getItemInHand(hand)),
                    CarrierEntryTrace.toolText(hand == null ? null : player.getItemInHand(hand)),
                    selected.carrier().shortId(), CarrierEntryTrace.posText(selected.localPos()),
                    componentInHand ? "component in hand" : "no component in hand",
                    hit == null ? "none" : hit.carrier().shortId() + " local="
                            + CarrierEntryTrace.posText(hit.localPos()),
                    hitIsThePickItself(selected, hit),
                    CarrierEntryTrace.posText(pickedPos), CarrierEntryTrace.posText(click),
                    side == null ? "?" : side.getName(),
                    player.isSecondaryUseActive(), wearsGoggles(player) || !gogglesRequired(true),
                    chain,
                    decision == null ? "the resolver answered before the chain ran" : decision.blockedBy(),
                    decision == null ? "RESOLVED (the tool path acted on it)"
                            : decision.status(),
                    decision == null ? "so the action line, not this one, carries the outcome"
                            : (decision.handled() ? "the line was locked"
                                    : "nothing was written: the chain refused it, and reportOutside wrote "
                                            + "the named refusal if it has one"));
        } catch (RuntimeException | LinkageError failed) {
            // A diagnostic is never allowed to take a click down; the guard is the shared one so a failure
            // here is counted beside every other carrier hook's.
            CarrierGuards.BLOCK_CLICK.fail(clickPos, failed);
        }
    }

    /** True when the click resolved to the pick itself - the one host for which this is not a lock. */
    private static boolean hitIsThePickItself(CarriedSelection.Selection selected,
                                              @Nullable CarriedBlockRemoval.Hit hit) {
        return hit != null && selected.carrier() != null && selected.localPos() != null
                && selected.carrier().equals(hit.carrier()) && selected.localPos().equals(hit.localPos());
    }

    /**
     * Where the pick stands, in the frame the gesture that answered compares in.
     *
     * <p>The two far ends of this one gesture are compared in two different frames and the pick is named in
     * both: an inside click is a cell of the structure's own storage, so the comparison is against the
     * pick's <b>local</b> coordinate, while an outside click is a world block and is compared against where
     * the pose projects the pick to. Printing the wrong one would make the line's own {@code side=}
     * disagree with the decision, which is the kind of contradiction these diagnostics exist to prevent.
     */
    @Nullable
    private static BlockPos pickedPosForTrace(ServerPlayer player, boolean inside) {
        CarriedSelection.Selection selected = CarriedSelection.get(player);
        if (selected == null) {
            return null;
        }
        if (inside) {
            return selected.localPos();
        }
        BlockPos projected = player.level() instanceof ServerLevel level
                ? currentWorldPosForOutside(level, selected) : null;
        return projected != null ? projected : selected.worldPos();
    }

    private static void markHandled(long tick, BlockPos pos, @Nullable InteractionHand hand) {
        lastHandledTick = tick;
        lastHandledPos = pos.immutable();
        lastHandledHand = hand;
    }

    /**
     * The player-facing message, built through {@link CarrierMessages}.
     *
     * <p><b>No internal coordinate is ever shown, and every sentence is short.</b> A plot coordinate - the
     * structure's own key, two million blocks from anywhere a player can stand - must not reach the action
     * bar, so a block is named by what it is and where it is relative to the click, and never by a number.
     * The wording is otherwise the world's own wherever the world has words for the same thing ("locked this
     * line", "cut it", "back to automatic"), because "the delay is now three" has to mean the same thing
     * inside a machine as on the ground; the keys with {@code _carrier} in the name exist because the world's
     * versions of those take a <b>position</b> as an argument, and the carrier side must not.
     */
    private static Component describe(@Nullable CarriedTool.Action action,
                                      @Nullable CarriedHostIndex index) {
        if (action == null) {
            return CarrierMessages.refused(
                    Component.translatable("message.redstonecircuit.wrench.no_selection"));
        }
        if (action instanceof CarriedTool.Action.Selected selected) {
            Slot slot = CarriedInsert.slotOf(index, selected.carrier(), selected.localPos());
            return Component.translatable("message.redstonecircuit.wrench.selected_carrier",
                    CarrierMessages.blockLabel(slot, CarrierMessages.componentName(slot == null ? null
                            : slot.type))).withStyle(ChatFormatting.YELLOW);
        }
        if (action instanceof CarriedTool.Action.Cleared) {
            return Component.translatable("message.redstonecircuit.wrench.cleared_carrier")
                    .withStyle(ChatFormatting.AQUA);
        }
        if (action instanceof CarriedTool.Action.CrossLinked linked) {
            return crossLinkMessage(linked.state(), linked.localSide(), index, linked.farCarrier(),
                    linked.farLocalPos());
        }
        if (action instanceof CarriedTool.Action.CrossFarEndEmpty far) {
            return crossLinkMessage(far.state(), far.localSide(), index, far.farCarrier(),
                    far.farLocalPos());
        }
        if (action instanceof CarriedTool.Action.Linked linked) {
            Component side = Component.literal(linked.localSide().getName());
            return switch (linked.state()) {
                case ON -> Component.translatable("message.redstonecircuit.wrench.linked_on", side)
                        .withStyle(ChatFormatting.GREEN);
                case OFF -> Component.translatable("message.redstonecircuit.wrench.linked_off", side)
                        .withStyle(ChatFormatting.RED);
                case AUTO -> Component.translatable("message.redstonecircuit.wrench.linked_auto", side)
                        .withStyle(ChatFormatting.GRAY);
            };
        }
        if (action instanceof CarriedTool.Action.FarEndEmpty far) {
            return Component.translatable("message.redstonecircuit.wrench.linked_on_device",
                    Component.literal(WrenchLinks.directionBetween(far.localPos(), far.towards())
                            .map(Direction::getName).orElse("?")))
                    .withStyle(ChatFormatting.GREEN);
        }
        if (action instanceof CarriedTool.Action.LockedOutside outside) {
            // The world's own sentence for the world's own situation, in a key of its own: "the far end
            // holds no component, so only this side is pinned and every other side is cut" - which is
            // exactly `linked_on_device` - plus the one fact that sentence cannot carry, that the far side
            // is a block of the world rather than a host of ours (so nothing was written over there).
            return lockMessage(outside.state(), outside.side(), false);
        }
        if (action instanceof CarriedTool.Action.LockedInside inside) {
            // The twin of the outside lock: the same rule, the far end inside this very structure. The key
            // set is separate so the two gestures are told apart in words as well as in the log.
            return lockMessage(inside.state(), inside.side(), true);
        }
        if (action instanceof CarriedTool.Action.NotAdjacent) {
            return CarrierMessages.refused(Component.translatable(
                    "message.redstonecircuit.wrench.not_adjacent_carrier"));
        }
        if (action instanceof CarriedTool.Action.AlreadyClear) {
            return Component.translatable("message.redstonecircuit.wrench.same_block")
                    .withStyle(ChatFormatting.YELLOW);
        }
        if (action instanceof CarriedTool.Action.NoPick) {
            return CarrierMessages.refused(Component.translatable(
                    "message.redstonecircuit.wrench.no_selection_carrier"));
        }
        if (action instanceof CarriedTool.Action.PickedElsewhere picked) {
            Slot mine = CarriedInsert.slotOf(index, picked.pickedCarrier(), picked.pickedLocalPos());
            Slot theirs = CarriedInsert.slotOf(index, picked.carrier(), picked.localPos());
            // The position on this line is a fact about the WORLD - where the two blocks stand relative to
            // each other - and never a coordinate. See CarrierMessages#whereRelativeTo.
            return CarrierMessages.refused(Component.translatable(
                    "message.redstonecircuit.wrench.picked_elsewhere",
                    CarrierMessages.blockLabel(mine, CarrierMessages.componentName(mine == null ? null
                            : mine.type)),
                    CarrierMessages.whereRelativeTo(picked.pickedWorld(), picked.clickWorld()),
                    CarrierMessages.blockLabel(theirs, CarrierMessages.componentName(theirs == null ? null
                            : theirs.type))));
        }
        if (action instanceof CarriedTool.Action.NoDirection) {
            return CarrierMessages.refused(Component.translatable(
                    "message.redstonecircuit.rotation_rod.no_direction"));
        }
        if (action instanceof CarriedTool.Action.NoComponent noComponent) {
            return "rod".equals(noComponent.tool())
                    ? CarrierMessages.refused(Component.translatable(
                            "message.redstonecircuit.rotation_rod.nothing_there"))
                    : CarrierMessages.refused(Component.translatable(
                            "message.redstonecircuit.wrench.no_component"));
        }
        if (action instanceof CarriedTool.Action.Turned turned) {
            // The component is reported in the structure's own frame, which is where the next click will read it.
            return Component.translatable("message.redstonecircuit.rotation_rod.to_input",
                    Component.literal(turned.localFacing().getName())).withStyle(ChatFormatting.YELLOW);
        }
        // THE LAST PLACE AN ACTION COULD REACH THE ACTION BAR AS ITS OWN LOG TEXT. Every branch above names
        // what happened in words a player reads; falling back to the action's own description text would put the
        // JUDGEMENT-LINE text - an internal coordinate and a debug phrase - on the action bar. The neutral
        // sentence below says the same thing without either, and the judgement line keeps the detail.
        return Component.translatable("message.redstonecircuit.carried.handled")
                .withStyle(ChatFormatting.RED);
    }

    /**
     * <b>A line across two structures: the two blocks, and the fact that this is allowed.</b>
     *
     * <p>It has to say three things and no more: the line was drawn, it was drawn between <b>two</b>
     * structures (so the player is not left wondering which machine the click landed in), and where the other
     * block is relative to this one. The other block is named by its component and by "the block beside it",
     * which is exact and stays true while the machine moves - a coordinate would be neither.
     */
    private static Component crossLinkMessage(
            com.jiangyuefengyu.redstonecircuit.data.ConnectionState state, Direction side,
            @Nullable CarriedHostIndex index, CarrierId farCarrier, BlockPos farLocal) {
        Slot far = index == null ? null : CarriedInsert.slotOf(index, farCarrier, farLocal);
        Component other = CarrierMessages.blockLabel(far,
                CarrierMessages.componentName(far == null ? null : far.type));
        Component text = Component.translatable(
                switch (state) {
                    case ON -> "message.redstonecircuit.wrench.linked_across_on";
                    case OFF -> "message.redstonecircuit.wrench.linked_across_off";
                    case AUTO -> "message.redstonecircuit.wrench.linked_across_auto";
                },
                Component.literal(side.getName()), other);
        return switch (state) {
            case ON -> text.copy().withStyle(ChatFormatting.GREEN);
            case OFF -> text.copy().withStyle(ChatFormatting.RED);
            case AUTO -> text.copy().withStyle(ChatFormatting.GRAY);
        };
    }

    /**
     * What a placement gesture reports on the action bar.
     *
     * <p>Two new translation keys rather than the world's, because the world's placement shows itself by
     * the component appearing inside the block, and a carried component is not drawn until the overlay
     * has been re-sent - so the player needs one line that says what just happened.
     */
    private static Component describePlacement(CarriedInsert.Action action) {
        return switch (action) {
            case INSERT -> Component.translatable("message.redstonecircuit.carried.inserted")
                    .withStyle(ChatFormatting.GREEN);
            case RETRIEVE -> Component.translatable("message.redstonecircuit.carried.retrieved")
                    .withStyle(ChatFormatting.AQUA);
            // Not reachable today - `applyPlacementPlan` only reports INSERT and RETRIEVE - but the fallback is
            // a sentence and not the action's own description text, which is the judgement-line text with an
            // internal coordinate in it: a branch that cannot be reached must still not print a coordinate if
            // the reachability ever changes.
            default -> Component.translatable("message.redstonecircuit.carried.handled")
                    .withStyle(ChatFormatting.RED);
        };
    }

    /**
     * The carried pick's box has no sender here: there is one sender, {@code WrenchSelection#refresh}, once per
     * level tick, which reads both ledgers, projects the carried coordinate through the carrier's current pose,
     * and sends only when the answer changes. An answer sent once at click time cannot follow a machine that
     * moves afterwards.
     */

    private static void message(ServerPlayer player, Component text) {
        player.displayClientMessage(text, true);
    }
}

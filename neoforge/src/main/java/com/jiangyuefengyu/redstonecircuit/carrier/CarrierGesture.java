package com.jiangyuefengyu.redstonecircuit.carrier;

import com.jiangyuefengyu.redstonecircuit.HostRules;
import com.jiangyuefengyu.redstonecircuit.RCRegistry;
import com.jiangyuefengyu.redstonecircuit.data.ComponentType;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import org.jetbrains.annotations.Nullable;

/**
 * <b>Which gesture one click is, decided from what <em>both</em> hands hold and the sneak state - and from
 * nothing else.</b>
 *
 * <h2>The defect this exists for, and the second half of it</h2>
 * The carrier side used to ask "did I recognise a component in this hand?" and treat <em>no</em> as "the
 * hand is empty". A wrench is not a component, so a click holding one answered <em>no</em> and the switch
 * gesture thought the hand was bare. The first fix made the single dispatched hand ask vanilla's own
 * {@code stack.isEmpty()} instead - and <b>the author's bug survived it</b>, because that is only half the
 * question. Vanilla delivers one right-click <b>twice</b> - main hand then off hand, same tick, same
 * position - and the second delivery comes from a hand that genuinely <em>is</em> empty:
 *
 * <pre>
 *   carrier entry trace: … hand=MAIN_HAND item=redstonecircuit:redstone_wrench x1 tool=wrench -&gt; MATCH … clear …
 *   carrier entry trace: … hand=OFF_HAND  item=empty                            tool=bare hand -&gt; MATCH
 *   carrier s:aa684475: switch adjusted … tool=rotation rod entry=RIGHT_CLICK_BLOCK
 * </pre>
 *
 * <p>So a wrench click <em>also</em> stepped a repeater's delay, and a rod click <em>also</em> did, which is
 * exactly what the author reported: <em>"手上拿扳手或转向杆右键也会调档位，转向杆同时也会调方向，扳手则完全
 * 没有自己的用处"</em>.
 *
 * <h2>The rule, in one place, over both hands</h2>
 * The world side never had the defect: {@code InnerRedstoneInteraction} owns the whole click with the
 * <b>first hand that can claim it</b>, and its second-hand guard cancels the second delivery whatever the
 * first one did. The carrier layer cannot copy that guard verbatim - it may see the two deliveries in
 * either order, and its own comment says the dispatch order must not be depended on - so the same
 * semantics are stated <b>order-independently</b>, as a priority over the two hands:
 *
 * <ol>
 *   <li><b>a tool in the dispatched hand</b> is that tool's gesture (the wrench and the rod each replace
 *       the whole click, exactly as they do in the world);</li>
 *   <li><b>a tool in the other hand</b> means this delivery is not the bare-hand one: that hand's own
 *       delivery owns the click - the tool beats everything, which is the author's "拿扳手就是扳手";</li>
 *   <li><b>a component in the dispatched hand</b> is a placement (store / take out);</li>
 *   <li><b>a component in the other hand</b> - same as (2), one step down the priority;</li>
 *   <li><b>anything else in the dispatched hand</b> (a plank, a sword, a block) is nobody's;</li>
 *   <li><b>anything at all in the other hand</b> means the bare-hand action does not apply: the player is
 *       holding something, so <em>no</em> switch and <em>no</em> retrieval - the author's rule, verbatim:
 *       "手持扳手/转向杆/元件/任意方块都不得调档";</li>
 *   <li>only <b>both hands empty</b> reaches the switch gesture, and the sneak key then chooses between
 *       working what is inside the block and taking it back out.</li>
 * </ol>
 *
 * <p>Steps 2, 4 and 6 are the part that was missing. Step 6 is deliberately <em>stricter</em> than the
 * world side's per-hand test, and the divergence is stated rather than hidden: the world side reaches the
 * same answer through its own two-delivery hand guard (which is order-dependent), and this rule gets there
 * without depending on order. The direction of the difference is the safe one - a bare-hand action is never
 * invented for a player who is holding something.
 *
 * <h2>One rule, read by both entries - and testable without a game</h2>
 * A Sable sub-level delivers an ordinary block click and a Create contraption delivers a packet, and those
 * are the only two things that differ; which gesture the click <em>is</em> must not depend on which one
 * arrived. So the decision is a pure function here and both entries call it. The testing form takes the two
 * hands as {@link Hold} records of plain booleans rather than {@code ItemStack}s, because a unit test has
 * no item registry - and the author's bug was precisely a rule that had never been written down in a
 * testable place, twice: once for the hand it was looking at, and once for the hand it was not.
 */
public final class CarrierGesture {

    /**
     * What a click means.
     *
     * <p>Ordered as the dispatch tests them, because the order <em>is</em> part of the rule: a tool in the
     * dispatched hand owns the click whatever else is true, and an item the carrier layer does not know is
     * nobody's.
     */
    public enum Kind {
        /** The wrench (plain or shift) - the world's wrench, on the structure's record. */
        WRENCH,
        /** The rotation rod - the world's rod, on the structure's record. */
        ROD,
        /** A redstone component in hand, or a bare hand while sneaking: a placement. */
        PLACEMENT,
        /** Both hands empty, not sneaking: work what is inside the carried block. */
        SWITCH,
        /**
         * Anything else: a plank, a sword, a block, or a delivery whose click belongs to the other hand.
         * Nothing the carrier layer owns, and deliberately <em>not</em> a refusal line either - the entry
         * point filters this before the coordinate is even resolved (see
         * {@code CarrierInteraction#onRightClickBlock}), because there is nothing to report about a click
         * that could never be ours.
         */
        NONE
    }

    /**
     * <b>What one hand holds</b>, as four plain facts - the whole input of the rule, and the reason it can
     * be asserted offline.
     *
     * <p>{@code empty} is <em>not</em> derivable from the other three: "no wrench, no rod, no component" is
     * also true of a plank, and telling those two apart is the defect this class was written for the first
     * time.
     *
     * @param wrench a wrench is in this hand
     * @param rod the rotation rod is in this hand
     * @param component a redstone component is in this hand
     * @param empty the hand holds <b>nothing at all</b> - vanilla's own {@code stack.isEmpty()}
     */
    public record Hold(boolean wrench, boolean rod, boolean component, boolean empty) {

        /** A hand holding nothing: the state the switch gesture requires of <em>both</em> hands. */
        public static final Hold NOTHING = new Hold(false, false, false, true);

        /** A hand holding something the carrier layer has no gesture for: a plank, a sword, a block. */
        public static final Hold SOMETHING_ELSE = new Hold(false, false, false, false);

        /** The same, read off a stack. One read of the stack for all four facts, so they cannot disagree. */
        public static Hold of(@Nullable ItemStack stack) {
            if (stack == null) {
                return SOMETHING_ELSE;
            }
            boolean wrench = RCRegistry.isWrench(stack);
            boolean rod = RCRegistry.isRotationRod(stack);
            ComponentType component = wrench || rod ? null : HostRules.componentFor(stack);
            return new Hold(wrench, rod, component != null, stack.isEmpty());
        }
    }

    private CarrierGesture() {
    }

    /**
     * <b>The one rule.</b> What a click means, given what the dispatched hand holds, what the
     * <em>other</em> hand holds, and whether the sneak key is down.
     *
     * <p>The sneak state only ever chooses <em>between</em> gestures that exist, and it never turns one
     * gesture into another: a component in hand is a placement either way (the sneak state decides which
     * half of it), and two empty hands are a switch while not sneaking and a retrieval while sneaking -
     * both of which live in {@link com.jiangyuefengyu.redstonecircuit.logic.CarriedInsert} and
     * {@link com.jiangyuefengyu.redstonecircuit.logic.CarriedSwitch}.
     *
     * @param hand what the hand this delivery is for holds
     * @param other what the other hand holds - <b>the fact the previous fix was missing</b>
     * @param sneaking whether the player is holding the sneak key
     */
    public static Kind of(Hold hand, Hold other, boolean sneaking) {
        if (hand.wrench()) {
            return Kind.WRENCH;
        }
        if (hand.rod()) {
            return Kind.ROD;
        }
        if (other.wrench() || other.rod()) {
            // A TOOL ANYWHERE OWNS THE WHOLE CLICK, and this delivery is not it. The world side says the
            // same thing - "the wrench takes the whole click, both halves of it, before anything else looks
            // at it" - and asking it over both hands rather than per hand is what makes the answer
            // independent of the order the two deliveries arrive in.
            return Kind.NONE;
        }
        if (hand.component()) {
            return Kind.PLACEMENT;
        }
        if (other.component()) {
            // The other hand's placement owns the click: the same statement, one step down the priority.
            return Kind.NONE;
        }
        if (!hand.empty()) {
            // THE LINE THAT WAS MISSING THE FIRST TIME. `empty` is vanilla's own test
            // ({@code stack.isEmpty()}), not "no component was recognised": a plank is not a component
            // either, and neither is a sword, and neither may be treated as a bare hand.
            return Kind.NONE;
        }
        if (!other.empty()) {
            // THE LINE THAT WAS MISSING THE SECOND TIME - the author's actual report. The player is holding
            // something in the other hand (anything at all), so this click is not the bare-hand action:
            // no switch, no retrieval, whatever the config says.
            return Kind.NONE;
        }
        return emptyHandGesture(sneaking);
    }

    /**
     * The single-hand view of the same rule, for callers and tests that only have one hand to talk about.
     *
     * <p>Equivalent to {@link #of(Hold, Hold, boolean)} with an empty other hand, which is exactly the
     * situation of "both hands are empty" - the only situation in which the bare-hand gestures exist.
     */
    public static Kind of(boolean isWrench, boolean isRod, boolean holdsComponent,
                          boolean handIsEmpty, boolean sneaking) {
        return of(new Hold(isWrench, isRod, holdsComponent, handIsEmpty), Hold.NOTHING, sneaking);
    }

    /**
     * Which of the two empty-hand gestures a pair of empty hands makes, given the sneak state.
     *
     * <p>Exists so {@link com.jiangyuefengyu.redstonecircuit.logic.CarriedInsert} and
     * {@link com.jiangyuefengyu.redstonecircuit.logic.CarriedSwitch} can be pointed at the right half
     * without repeating the sneak rule, and so a test can assert the two together rather than one branch
     * of one entry point.
     */
    public static Kind emptyHandGesture(boolean sneaking) {
        return sneaking ? Kind.PLACEMENT : Kind.SWITCH;
    }

    /**
     * True when the other hand holds a gesture of its own, so <em>its</em> delivery is the one that owns
     * this click.
     *
     * <p>Used by the entry points to answer the second delivery the way the world side does: the click is
     * consumed (nothing runs underneath it) but nothing happens here. A plain block in the other hand is
     * deliberately <b>not</b> "a gesture of its own" - it is covered by the weaker rule below, "the player
     * is holding something, so this is not the bare-hand action" - because it owns no click of ours.
     */
    public static boolean ownsTheClick(Hold hand) {
        return hand.wrench() || hand.rod() || hand.component();
    }

    /**
     * The production form: the same rule, with both hands read off the player.
     *
     * <p>One read of each stack for both of its questions - "which tool" and "which component" - so the two
     * can never disagree, which is the shape the old dispatcher had and the reason a tool could look like
     * an empty hand there. The other hand is read from the <b>same</b> player at the <b>same</b> moment, so
     * "what is in the other hand" cannot be a stale fact from another tick.
     */
    public static Kind of(@Nullable Player player, @Nullable InteractionHand hand) {
        if (player == null || hand == null) {
            return Kind.NONE;
        }
        return of(Hold.of(player.getItemInHand(hand)), Hold.of(player.getItemInHand(otherHand(hand))),
                player.isSecondaryUseActive());
    }

    /** True when this delivery is nobody's <em>because the other hand's gesture owns the click</em>. */
    public static boolean suppressedByTheOtherHand(@Nullable Player player,
                                                   @Nullable InteractionHand hand) {
        if (player == null || hand == null) {
            return false;
        }
        Hold mine = Hold.of(player.getItemInHand(hand));
        Hold theirs = Hold.of(player.getItemInHand(otherHand(hand)));
        return mine.empty() && ownsTheClick(theirs);
    }

    /** The hand a delivery is not for. Both entries pass the hand the event or packet named. */
    public static InteractionHand otherHand(InteractionHand hand) {
        return hand == InteractionHand.MAIN_HAND ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
    }

    /**
     * The component a placement would store, or {@code null} when this hand holds none.
     *
     * <p>Read beside {@link #of(Player, InteractionHand)} rather than on its own, so a tool can never be
     * taken for a component either - the same confusion, one step later.
     */
    @Nullable
    public static ComponentType componentOf(@Nullable Player player, @Nullable InteractionHand hand) {
        if (player == null || hand == null) {
            return null;
        }
        ItemStack stack = player.getItemInHand(hand);
        if (stack == null || stack.isEmpty() || RCRegistry.isWrench(stack)
                || RCRegistry.isRotationRod(stack)) {
            return null;
        }
        return HostRules.componentFor(stack);
    }

    /**
     * Whether this gesture may only ever have been started by a hand that holds <b>nothing</b>.
     *
     * <p>Only the tool gestures may hold something, by definition. Everything else - including the switch
     * gesture, the one the author caught being reachable with a tool in hand - requires an empty hand, and
     * this is the assertion the tests make about the rule rather than about one branch of it.
     */
    public static boolean requiresEmptyHand(Kind kind) {
        return kind == Kind.SWITCH || kind == Kind.PLACEMENT;
    }

    /**
     * <b>What the action line must call the gesture that actually ran.</b>
     *
     * <p>{@code tool=} used to be a boolean at the call site ({@code wrench ? "wrench" : "rotation rod"}),
     * so every non-wrench action - a bare-hand switch, a retrieval - was printed as
     * <b>{@code tool=rotation rod}</b>. The author's own log has
     * {@code switch adjusted … tool=rotation rod} and {@code retrieve … tool=rotation rod} on clicks where
     * no rod existed at all, which is how one column of the judgement line came to lie about a bug that was
     * really there. The label now comes from the gesture that was dispatched, so the line and the action
     * cannot disagree.
     */
    public static String toolLabel(Kind kind) {
        return switch (kind) {
            case WRENCH -> "wrench";
            case ROD -> "rotation rod";
            case PLACEMENT -> "placement";
            case SWITCH -> "bare hand";
            case NONE -> "none";
        };
    }
}

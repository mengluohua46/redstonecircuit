package com.jiangyuefengyu.redstonecircuit.carrier;

import com.jiangyuefengyu.redstonecircuit.HostRules;
import com.jiangyuefengyu.redstonecircuit.RCRegistry;
import com.jiangyuefengyu.redstonecircuit.data.ComponentType;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import org.jetbrains.annotations.Nullable;

/**
 * Which gesture one click is, decided from what <em>both</em> hands hold and the sneak state - and from nothing
 * else.
 *
 * <p>Vanilla delivers one right-click <b>twice</b> - main hand then off hand, same tick, same position - and the
 * second delivery comes from a hand that genuinely is empty, so a rule that asks only the dispatched hand lets a
 * tool click also step a repeater's delay. Hence, order-independently: a tool in either hand owns the whole click;
 * a component in the dispatched hand is a placement; anything at all in the other hand means this is not the
 * bare-hand action, so no switch and no retrieval; and only two empty hands reach the switch gesture, where the
 * sneak key picks between working what is inside the block and taking it back out.
 *
 * <p>A pure function, read by both entry points (a Sable sub-level's block click and a Create contraption's
 * packet), and it takes the hands as {@link Hold} records of plain booleans so it is testable without a game.
 */
public final class CarrierGesture {

    /**
     * What a click means.
     *
     * <p>Ordered as the dispatch tests them, because the order <em>is</em> part of the rule: a tool in the
     * dispatched hand owns the click whatever else is true, and an item the carrier layer does not know is nobody's.
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
         * Anything else: a plank, a sword, a block, or a delivery whose click belongs to the other hand. Nothing the
         * carrier layer owns, and not a refusal line either - the entry point filters this before the coordinate is
         * even resolved (see {@code CarrierInteraction#onRightClickBlock}), because there is nothing to report about
         * a click that could never be ours.
         */
        NONE
    }

    /**
     * What one hand holds, as four plain facts - the whole input of the rule, and the reason it can be asserted
     * offline.
     *
     * <p>{@code empty} is <em>not</em> derivable from the other three: "no wrench, no rod, no component" is also true
     * of a plank, and telling those two apart is half of what this class exists for.
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
     * The one rule: what a click means, given what the dispatched hand holds, what the <em>other</em> hand holds, and
     * whether the sneak key is down.
     *
     * <p>The sneak state only ever chooses <em>between</em> gestures that exist and never turns one gesture into
     * another: a component in hand is a placement either way (the sneak state decides which half of it), and two empty
     * hands are a switch while not sneaking and a retrieval while sneaking - both of which live in
     * {@link com.jiangyuefengyu.redstonecircuit.logic.CarriedInsert} and
     * {@link com.jiangyuefengyu.redstonecircuit.logic.CarriedSwitch}.
     */
    public static Kind of(Hold hand, Hold other, boolean sneaking) {
        if (hand.wrench()) {
            return Kind.WRENCH;
        }
        if (hand.rod()) {
            return Kind.ROD;
        }
        if (other.wrench() || other.rod()) {
            // A tool anywhere owns the whole click, and this delivery is not it - the world side says the same
            // thing over both hands rather than per hand, which is what makes the answer independent of the order
            // the two deliveries arrive in.
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
            // `empty` is vanilla's own test ({@code stack.isEmpty()}), not "no component was recognised": a plank
            // is not a component either, and neither is a sword, and neither may be treated as a bare hand.
            return Kind.NONE;
        }
        if (!other.empty()) {
            // Anything at all in the other hand means this click is not the bare-hand action: no switch and no
            // retrieval, whatever the config says.
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
     * True when the other hand holds a gesture of its own, so <em>its</em> delivery is the one that owns this click.
     *
     * <p>Used by the entry points to answer the second delivery the way the world side does: the click is consumed
     * (nothing runs underneath it) but nothing happens here. A plain block in the other hand is <b>not</b> "a gesture
     * of its own" - it is covered by the weaker rule, "the player is holding something, so this is not the bare-hand
     * action" - because it owns no click of ours.
     */
    public static boolean ownsTheClick(Hold hand) {
        return hand.wrench() || hand.rod() || hand.component();
    }

    /**
     * The production form: the same rule, with both hands read off the player.
     *
     * <p>One read of each stack for both of its questions - "which tool" and "which component" - so the two can never
     * disagree. The other hand is read from the <b>same</b> player at the <b>same</b> moment, so "what is in the other
     * hand" cannot be a stale fact from another tick.
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
     * <p>Only the tool gestures may hold something, by definition. Everything else - the switch and the placement -
     * requires an empty hand, and this is the assertion the tests make about the rule rather than about one branch of
     * it.
     */
    public static boolean requiresEmptyHand(Kind kind) {
        return kind == Kind.SWITCH || kind == Kind.PLACEMENT;
    }

    /**
     * What the action line must call the gesture that actually ran.
     *
     * <p>The label comes from the gesture that was dispatched rather than from a per-call-site boolean, so the line
     * and the action cannot disagree: a bare-hand switch or a retrieval must never be printed as
     * {@code tool=rotation rod}.
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

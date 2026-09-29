package com.jiangyuefengyu.redstonecircuit.logic;

import com.jiangyuefengyu.redstonecircuit.carrier.CarriedHost;
import com.jiangyuefengyu.redstonecircuit.carrier.CarriedHostIndex;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierId;
import com.jiangyuefengyu.redstonecircuit.data.ComparatorMode;
import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.Slot;

import net.minecraft.core.BlockPos;

import org.jetbrains.annotations.Nullable;

/**
 * <b>Working a component inside a block a structure is CARRYING, with a bare hand.</b>
 *
 * <h2>The gap this closes</h2>
 * Standing in the world, a plain right-click with a bare hand works what is hidden inside a block: a
 * lever flips, a repeater's delay steps, a comparator's mode swaps. On a physicalised block that gesture
 * did nothing at all, because the world's handler reads {@code InnerRedstoneStore} by a <b>world</b>
 * position and a carried component is not in that store - claiming is a move, not a copy. The author's
 * list of "things that do not work on a physicalised block" therefore still had this one entry on it.
 *
 * <h2>The rule, and where it comes from</h2>
 * The rule is {@link InnerSwitches}: <em>the same component states, changed the same way</em>. This class
 * does not restate it - it is not a copy of that switch statement, it is the carrier-side half of one
 * rule, and the two must not be allowed to drift. That is why the state change is written once here and
 * {@link InnerSwitches#describe} is reused verbatim for the player-facing text: "the delay is now three"
 * means one thing whether it came from a world block or a carried one.
 *
 * <h2>Why it takes only the index</h2>
 * Exactly the reason {@link CarriedInsert} and {@link CarriedTool} do: the whole behaviour is a function
 * of {@code (carrier, localPos)} and the record there, so it names no level, no packet and no player, and
 * every rule below is pinned by offline unit tests. The entry point is left with "resolve, hand it over,
 * broadcast", and the broadcast is the same write set the tools and the placement path use.
 *
 * <h2>What is deliberately not included: the button</h2>
 * A button's press is not the whole gesture - vanilla schedules its release one second later, and the
 * world's path does that through {@link InnerRedstoneNetwork#scheduleRelease} keyed by a <b>level</b>
 * position. A carried block's level position is the projection, and that moves with the machine: a
 * release scheduled there would fire somewhere the block has since left, or not at all. Getting that
 * right needs a mechanism of its own (a release keyed by the carrier and the local coordinate), and
 * inventing one inside a round whose whole purpose is to stop guessing would be the wrong trade. So a
 * button is refused <b>by name</b>, with a reason, rather than left silently dead.
 */
public final class CarriedSwitch {

    /** What one bare-hand gesture on a carried host did. */
    public enum Action {

        /** A lever flicked up, or a button pressed - see the class doc for why buttons are refused. */
        ENGAGED(true),
        /** A lever flicked down. */
        RELEASED(true),
        /** A setting changed: a repeater's delay, or a comparator's mode. */
        ADJUSTED(true),
        /** No live carried host at this coordinate - an ordinary click, or a stale packet. */
        REFUSED_POSITION(false),
        /** The host is live and holds nothing, so there is nothing to work. */
        NOTHING_THERE(false),
        /** The component inside has no bare-hand gesture at all (wire, a torch, ...). */
        NOT_WORKABLE(false),
        /**
         * A button. Its release has to be scheduled against a position that does not move, and a carried
         * block's does - see the class doc.
         */
        BUTTON_NEEDS_A_STABLE_POSITION(false);

        private final boolean acted;

        Action(boolean acted) {
            this.acted = acted;
        }

        /** True for the three outcomes that changed the record and consumed the gesture. */
        public boolean acted() {
            return acted;
        }

        /** A few words for the action line and the trace. */
        public String describe() {
            return switch (this) {
                case ENGAGED -> "engaged";
                case RELEASED -> "released";
                case ADJUSTED -> "adjusted";
                case REFUSED_POSITION -> "refused: no carried host at this coordinate";
                case NOTHING_THERE -> "refused: the carried host holds nothing to work";
                case NOT_WORKABLE -> "refused: the component inside has no bare-hand action";
                case BUTTON_NEEDS_A_STABLE_POSITION -> "refused: a button's release has to be scheduled "
                        + "at a position that does not move, and a carried block's moves with the machine";
            };
        }
    }

    private CarriedSwitch() {
    }

    /**
     * Whether a bare hand can do something with this component at all.
     *
     * <p>The world's {@link InnerSwitches#isWorkable} plus one deliberate exclusion: a button, for the
     * reason in the class doc. Kept here rather than read from that class so the two answers can be
     * compared in one place when the button's mechanism arrives.
     */
    public static boolean isWorkable(ComponentType type) {
        return type == ComponentType.LEVER || type == ComponentType.BUTTON || type.isRepeater()
                || type == ComponentType.COMPARATOR;
    }

    /**
     * One bare-hand gesture against a host a structure is carrying.
     *
     * <p>Mutates the record's slot in place - the index's contract for a slot is that the record holds
     * the slot it was given (see {@link CarriedHostIndex#updateSlot}) - and answers what happened. The
     * caller writes the record back, broadcasts it and tells the world, so this method has no side effect
     * outside the object it was handed.
     *
     * @param index the carrier index; {@code null} or a miss answers {@link Action#REFUSED_POSITION}
     * @param carrier the structure the click resolved to
     * @param localPos the host's coordinate <b>inside that structure</b>
     */
    public static Action apply(@Nullable CarriedHostIndex index, @Nullable CarrierId carrier,
                              @Nullable BlockPos localPos) {
        CarriedHost host = CarriedTool.hostAt(index, carrier, localPos);
        if (host == null) {
            // Not a carried host at all: an ordinary world click, or a packet naming a coordinate this
            // structure never claimed. Either way the world owns it and nothing here is touched.
            return Action.REFUSED_POSITION;
        }
        Slot slot = host.slot();
        if (slot == null) {
            return Action.NOTHING_THERE;
        }
        switch (slot.type) {
            case LEVER -> {
                slot.powered = !slot.powered;
                slot.power = slot.powered ? PowerSolver.MAX_POWER : 0;
                index.updateSlot(carrier, localPos, slot);
                return slot.powered ? Action.ENGAGED : Action.RELEASED;
            }
            case BUTTON -> {
                return Action.BUTTON_NEEDS_A_STABLE_POSITION;
            }
            case REPEATER, RETAINING_REPEATER, DOUBLE_REPEATER, DOUBLE_RETAINING_REPEATER -> {
                // The world's rule, verbatim: 1 -> 2 -> 3 -> 4 -> 1, and the output is deliberately left
                // alone so an in-flight pulse is not cut short.
                slot.delay = slot.delay >= 4 ? 1 : slot.delay + 1;
                index.updateSlot(carrier, localPos, slot);
                return Action.ADJUSTED;
            }
            case COMPARATOR -> {
                slot.mode = slot.mode == ComparatorMode.SUBTRACT
                        ? ComparatorMode.COMPARE : ComparatorMode.SUBTRACT;
                index.updateSlot(carrier, localPos, slot);
                return Action.ADJUSTED;
            }
            default -> {
                return Action.NOT_WORKABLE;
            }
        }
    }

    /**
     * The read half: whether a bare-hand gesture here would change anything.
     *
     * <p>Deliberately separate from {@link #apply}, and deliberately <b>not</b> used by it: the entry
     * point needs to know whether to claim the click <em>before</em> it changes a record, and a predicate
     * that also decided would have to be the same code twice.
     */
    public static boolean workable(@Nullable CarriedHostIndex index, @Nullable CarrierId carrier,
                                   @Nullable BlockPos localPos) {
        CarriedHost host = CarriedTool.hostAt(index, carrier, localPos);
        if (host == null || host.slot() == null) {
            return false;
        }
        ComponentType type = host.slot().type;
        return isWorkable(type) && type != ComponentType.BUTTON;
    }
}

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
 * <p>The rule is {@link InnerSwitches}: the same component states, changed the same way - claimed here
 * rather than copied, so the two halves cannot drift, and {@link InnerSwitches#describe} is reused
 * verbatim for the player-facing text.
 *
 * <p>A button is refused by name: its release is scheduled through
 * {@link InnerRedstoneNetwork#scheduleRelease} against a <b>level</b> position, and a carried block's
 * level position is the projection, which moves with the machine.
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
        /** A button: its release would have to be scheduled against a position that does not move. */
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
     * The world's {@link InnerSwitches#isWorkable} plus one exclusion: a button, whose release cannot be
     * scheduled against a moving position.
     */
    public static boolean isWorkable(ComponentType type) {
        return type == ComponentType.LEVER || type == ComponentType.BUTTON || type.isRepeater()
                || type == ComponentType.COMPARATOR
                // 控制器 and 红石变压器 and 时钟源 and 信号延长器 open their own screen and are never steppable.
                || type == ComponentType.CONTROLLER
                || type == ComponentType.TRANSFORMER
                || type == ComponentType.CLOCK
                || type == ComponentType.EXTENDER;
    }

    /**
     * One bare-hand gesture against a host a structure is carrying: mutates the record's slot in place and
     * answers what happened; the caller writes the record back and broadcasts it.
     */
    public static Action apply(@Nullable CarriedHostIndex index, @Nullable CarrierId carrier,
                              @Nullable BlockPos localPos) {
        CarriedHost host = CarriedTool.hostAt(index, carrier, localPos);
        if (host == null) {
            // Not a carried host: an ordinary world click, or a coordinate this structure never claimed.
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
                // The world's rule, verbatim: 1 -> 2 -> 3 -> 4 -> 1. The output is left alone so that an
                // in-flight pulse is not cut short.
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
     * The read half: whether a bare-hand gesture here would change anything. Kept apart from
     * {@link #apply} because the entry point must decide whether to claim the click before anything changes.
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

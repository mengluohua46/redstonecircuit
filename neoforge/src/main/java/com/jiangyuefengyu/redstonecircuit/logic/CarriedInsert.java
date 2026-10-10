package com.jiangyuefengyu.redstonecircuit.logic;

import java.util.List;

import com.jiangyuefengyu.redstonecircuit.carrier.CarriedHost;
import com.jiangyuefengyu.redstonecircuit.carrier.CarriedHostIndex;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierId;
import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.Slot;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import org.jetbrains.annotations.Nullable;

/**
 * Putting a component into - and taking one out of - a block a structure is <b>carrying</b>.
 *
 * <p>A separate class because the two stores are keyed differently: a carried host is keyed by
 * {@code (carrier, localPos)} in {@link CarriedHostIndex}, and the world position is only where the
 * projection currently draws it. Nothing here names a level or writes the world store.
 *
 * <p>Every successful action replaces the record, so a second delivery of the same click finds the new
 * state and refuses on its own terms.
 */
public final class CarriedInsert {

    /** What one carrier-side placement click meant, and what actually happened. */
    public enum Action {

        /** The host was empty and now holds the component; the caller consumes one item. */
        INSERT(true),
        /** The host held a component and the caller's hand was empty: it has been taken out. */
        RETRIEVE(true),
        /** No live carried host at this coordinate - an ordinary world click, or a stale packet. */
        REFUSED_POSITION(false),
        /** The hand held nothing to store and the host held nothing to take. */
        NOTHING_TO_RETRIEVE(false),
        /** The player is not sneaking, so plain right-click stays the block's own. */
        NOT_SNEAKING(false),
        /** The hand holds neither a component nor nothing at all (a tool, a block, ...). */
        NOT_A_COMPONENT(false),
        /**
         * <b>The block itself may not hold a component</b> - the world's own host rule refused it, asked of
         * the structure's own view. A retrieval is not refused by this: the world's own take-it-back-out
         * path asks nothing about the host either, so a component can always be recovered.
         */
        NOT_A_HOST(false),
        /** The clicked face did not resolve to a direction, so no input side can be named. */
        REFUSED_DIRECTION(false),
        /** The host already holds a component and replacing is switched off. */
        BLOCKED(false);

        private final boolean acted;

        Action(boolean acted) {
            this.acted = acted;
        }

        /** True only for the two actions that changed a record and consumed the gesture. */
        public boolean acted() {
            return acted;
        }

        /** Whether the caller's held item is spent by this outcome: an insert is the only one that does. */
        public boolean consumesItem() {
            return this == INSERT;
        }

        /** A few words for the diagnostic line. */
        public String describe() {
            return switch (this) {
                case INSERT -> "insert";
                case RETRIEVE -> "retrieve";
                case REFUSED_POSITION -> "refused: no carried host at this coordinate";
                case NOTHING_TO_RETRIEVE -> "refused: the carried host holds nothing to take out";
                case NOT_SNEAKING -> "refused: not sneaking (plain right-click is the block's own)";
                case NOT_A_COMPONENT -> "refused: the held item is not a redstone component";
                case NOT_A_HOST -> "refused: this block cannot host a component (transparent, a "
                        + "workstation, or not a full cube)";
                case REFUSED_DIRECTION -> "refused: the click carried no face to read from";
                case BLOCKED -> "refused: the carried host already holds a component";
            };
        }
    }

    private CarriedInsert() {
    }

    /**
     * One placement gesture against a host a structure is carrying: sneak with a component stores it, sneak
     * with a bare hand takes it out, anything else is vanilla's. Only {@link Action#INSERT} spends the item.
     */
    public static Action apply(CarriedHostIndex index, CarrierId carrier, BlockPos localPos,
                               boolean hostAcceptsComponent, @Nullable Direction localFace,
                               boolean sneaking, @Nullable ComponentType held,
                               boolean replaceExisting) {
        CarriedHost host = CarriedTool.hostAt(index, carrier, localPos);
        Action action = preview(host != null, hostAcceptsComponent, host == null ? null : host.slot(),
                localFace, sneaking, held, replaceExisting);
        // The write happens for exactly the two actions that act; everything that decides is in `preview`,
        // so a caller can ask what a placement would do before filing a record for it.
        if (action == Action.RETRIEVE) {
            index.updateSlot(carrier, localPos, null);
        } else if (action == Action.INSERT && held != null) {
            Slot slot = new Slot(held);
            slot.power = held.initialPower();
            slot.facing = localFace;
            index.updateSlot(carrier, localPos, slot);
        }
        return action;
    }

    /**
     * The same gesture, for a caller that has not read the host's own block state: the host is treated as
     * accepting a component, which is the answer the world's path gives when host validation is off.
     */
    public static Action apply(CarriedHostIndex index, CarrierId carrier, BlockPos localPos,
                               @Nullable Direction localFace, boolean sneaking,
                               @Nullable ComponentType held, boolean replaceExisting) {
        return apply(index, carrier, localPos, true, localFace, sneaking, held, replaceExisting);
    }

    /**
     * The host-level removal notice a placement owes the client, or an empty list: an emptied host looks on
     * the wire like a put-down, so a retrieval has to say it while an insert adds the host to the payload.
     */
    public static List<BlockPos> removalNotice(@Nullable Action action, @Nullable BlockPos localPos) {
        if (action != Action.RETRIEVE || localPos == null) {
            return List.of();
        }
        return List.of(localPos.immutable());
    }

    /**
     * What one placement gesture <em>would</em> do to a host whose contents are {@code slot}, writing
     * nothing. The order is the definition: host, sneaking, empty hand, host rule, face, replace.
     */
    public static Action preview(boolean hostExists, boolean hostAcceptsComponent, @Nullable Slot slot,
                                 @Nullable Direction localFace, boolean sneaking,
                                 @Nullable ComponentType held, boolean replaceExisting) {
        if (!hostExists) {
            // Not a carried host: an ordinary world click, or a packet naming a coordinate this structure
            // never claimed. Either way the world owns it and nothing here is touched.
            return Action.REFUSED_POSITION;
        }
        if (!sneaking) {
            return Action.NOT_SNEAKING;
        }
        if (held == null) {
            // A bare hand takes the component back out, and the click needs nothing but that fact. An empty
            // host has nothing to take, and the world's own retrieval asks nothing about the host either.
            return slot == null ? Action.NOTHING_TO_RETRIEVE : Action.RETRIEVE;
        }
        if (!hostAcceptsComponent) {
            // A physicalised block is the same block, so "may this hold redstone" is the world's own answer,
            // computed by the world's own function on the same state - only read from the structure.
            return Action.NOT_A_HOST;
        }
        if (localFace == null) {
            // The component's input side IS the clicked face, so a click with no face has nothing to name.
            // Refused rather than guessed at, and nothing is written.
            return Action.REFUSED_DIRECTION;
        }
        if (slot != null && !replaceExisting) {
            return Action.BLOCKED;
        }
        return Action.INSERT;
    }

    /** The same decision for a caller that has not read the host's own block state; see {@link #apply}'s overload. */
    public static Action preview(boolean hostExists, @Nullable Slot slot, @Nullable Direction localFace,
                                 boolean sneaking, @Nullable ComponentType held,
                                 boolean replaceExisting) {
        return preview(hostExists, true, slot, localFace, sneaking, held, replaceExisting);
    }

    /**
     * Whether a placement gesture would store a component into a host that does not exist yet - the
     * pre-flight for filing a record, so "the record is created" and "the slot is written" are one decision.
     */
    public static boolean wouldInsert(boolean hostAcceptsComponent, @Nullable Direction localFace,
                                      boolean sneaking, @Nullable ComponentType held,
                                      boolean replaceExisting) {
        return preview(true, hostAcceptsComponent, null, localFace, sneaking, held, replaceExisting).acted();
    }

    /**
     * The same, for a caller that has not read the host's own block state; see {@link #apply}'s overload.
     */
    public static boolean wouldInsert(@Nullable Direction localFace, boolean sneaking,
                                      @Nullable ComponentType held, boolean replaceExisting) {
        return wouldInsert(true, localFace, sneaking, held, replaceExisting);
    }

    /**
     * Whether a host still holds what an action put there. One method rather than two lookups at the call
     * sites, so "what the log says is there" and "what the client is told" cannot come from different records.
     */
    @Nullable
    public static Slot slotOf(CarriedHostIndex index, CarrierId carrier, BlockPos localPos) {
        CarriedHost host = CarriedTool.hostAt(index, carrier, localPos);
        return host == null ? null : host.slot();
    }
}

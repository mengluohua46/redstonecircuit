package com.jiangyuefengyu.redstonecircuit.carrier;

import com.jiangyuefengyu.redstonecircuit.data.Slot;
import com.jiangyuefengyu.redstonecircuit.logic.CarriedTool;
import com.jiangyuefengyu.redstonecircuit.logic.WrenchLinks;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import org.jetbrains.annotations.Nullable;

/**
 * The judgement line for a wrench link, written whether the link succeeded or was refused.
 *
 * <p>Four facts have to be on the line for a log to be readable without a second run: <b>the pick</b> - which
 * structure and coordinate the near end is in - <b>the target</b>, the side the line runs along in the
 * structure's own frame (or why no side exists), and <b>the value</b> the connection became, so that "it linked"
 * and "it linked to the state it already had" are different lines. It is a class rather than a few formats at
 * the call site because "which refusal is which" is arithmetic over {@link CarriedTool.Action} with no level,
 * player or packet in it, and the unit tests pin every reason. Nothing here writes.
 */
public final class CarrierLinkLedger {

    /** How a refusal is named, so the line can be grepped for one reason at a time. */
    public enum Reason {

        /** The line changed: both ends were written. */
        LINKED("linked"),
        /**
         * <b>The line changed across two structures, and BOTH records were written.</b> Two separately
         * physicalised blocks standing side by side are two sub-levels, so this is the ordinary case for them.
         */
        LINKED_ACROSS("linked ACROSS STRUCTURES (both records were written, each in its own frame)"),
        /** A cross-structure line whose far end holds nothing: only the near end could be pinned. */
        LINKED_ACROSS_EMPTY("linked ACROSS STRUCTURES - the far end holds no component, so only the near "
                + "end is pinned"),
        /** The clicked coordinate is not a live carried host. */
        NO_TARGET("no live carried host at the clicked coordinate"),
        /** The picked host holds no component, so there is nothing to route from. */
        PICKED_HOST_EMPTY("the picked host holds no component"),
        /** The clicked host holds no component: only the near end can be pinned. */
        TARGET_HOST_EMPTY("the clicked host holds no component, so only the near end can be pinned"),
        /** Not one click's business at all: {@code CarriedInsert.Action.REFUSED_POSITION}. */
        NO_POSITION("the coordinate is not one this structure carries"),
        /** The two hosts share no face inside the structure, or the far end is undefined. */
        NOT_ADJACENT("the two hosts are not neighbours inside this structure"),
        /** No pick at all - the genuine "pick something first". */
        NOTHING_PICKED("nothing has been picked yet"),
        /** A pick exists, and it is in another structure. */
        PICKED_ELSEWHERE("the pick is in another structure"),
        /**
         * <b>The two ends are the same host, and there was nothing left to clear.</b> The pick is kept and the line
         * says what the click actually did, because "already automatic" and "cleared" are two different answers.
         */
        SAME_HOST("the pick and the click are the same host, and there was nothing left to clear on it "
                + "(every side already decides for itself)"),
        /**
         * <b>The click dropped every override on the host - not a line, the opposite of one.</b> Shift-right-clicking
         * the picked block is the world's own "let it decide for itself again", so the line says so.
         */
        CLEARED("cleared: every override on this block is gone, so it decides for itself again"),
        /**
         * <b>Not a refusal at all: this click simply picked the near end of a line.</b> A plain wrench click on a
         * carried host is the world's own <em>select</em> gesture, so the line is written without the "refused"
         * prefix - it neither wrote nor refused anything.
         */
        SELECTED("picked the near end of a line"),
        /** The click landed on a host the wrench is not routed to (no case observed; kept total). */
        OTHER("not a link");

        private final String text;

        Reason(String text) {
            this.text = text;
        }

        /** The phrase that goes on the line. */
        public String text() {
            return text;
        }
    }

    /**
     * One link, as the log line needs it: whether a line was really written to both ends, the reason, the pick
     * (structure, coordinate and component), the clicked end, the side the line runs along in the structure's own
     * frame, the connection state now standing on it, and {@code farSide} - the <b>other</b> structure's own
     * direction for the same line, which is null for a line inside one structure.
     */
    public record LinkLine(boolean linked, Reason reason, @Nullable CarrierId pickedCarrier,
                           @Nullable BlockPos pickedLocalPos, @Nullable String pickedComponent,
                           CarrierId targetCarrier, BlockPos targetLocalPos, @Nullable String side,
                           @Nullable String state, @Nullable String farSide) {

        /**
         * One line, in a fixed field order, with every one of the four facts present: {@code reason=} names the
         * decision, {@code picked=} and {@code on=} name the two ends, and {@code side=} is {@code ?} exactly when no
         * side could be named. The head is three-valued, because {@link Reason#SELECTED} is not a verdict.
         */
        public String describe() {
            return head()
                    + reason.text()
                    + (pickedCarrier == null ? "" : " picked=" + pickedCarrier.shortId()
                            + " local=" + pickedLocalPos.toShortString()
                            + (pickedComponent == null ? " (empty)" : " (" + pickedComponent + ")"))
                    + " on=" + targetCarrier.shortId() + " local=" + targetLocalPos.toShortString()
                    + " side=" + (side == null ? "?" : side)
                    + (farSide == null ? "" : " farSide=" + farSide)
                    + (state == null ? "" : " state=" + state)
                    + tail();
        }

        /** The verdict's own first words: written, refused, or neither. */
        private String head() {
            if (linked) {
                return "link ";
            }
            return reason == Reason.SELECTED ? "" : "link refused: ";
        }

        /** What the click was, when it was not a link attempt at all; empty for every verdict. */
        private String tail() {
            return reason == Reason.SELECTED
                    ? " - this click only picked the near end, so no line was written; shift-right-click a "
                            + "block beside it to route the line, or shift-right-click this one again to "
                            + "drop every override on it"
                    : "";
        }
    }

    private CarrierLinkLedger() {
    }

    /**
     * The judgement line for the third kind of line: one end inside a structure, the other a block of the world.
     *
     * <p>{@link #of} is written against a click that resolved to a carried host, so every line it builds has an
     * {@code on=<carrier>} end; this gesture is the one case where there is none. So the line names the same four
     * facts in the same order and adds the two that make it distinguishable from a cross-structure line - the two
     * <b>world</b> positions, because the end that was pinned and the end that was pointed at are one face apart in
     * the world and in two different frames. {@code OUTSIDE} is the word a reader greps; only a
     * {@link CarriedTool.Action.LockedOutside} produces a line here.
     */
    @Nullable
    public static String outsideLine(@Nullable CarriedTool.Action action,
                                     @Nullable CarriedHostIndex index) {
        if (!(action instanceof CarriedTool.Action.LockedOutside outside)) {
            return null;
        }
        Slot slot = slotAt(index, outside.carrier(), outside.localPos());
        // Written out rather than through LinkLine#describe: the first three words of a judgement line are what a
        // reader greps, and a LinkLine would have printed an `on=<carrier>` end for a block no carrier owns.
        return "locked OUTSIDE the structure"
                + " picked=" + outside.carrier().shortId() + " local=" + outside.localPos().toShortString()
                + (slot == null ? " (empty)" : " (" + WrenchLinks.describe(slot) + ")")
                + " side=" + outside.side().getName()
                + " (world " + outside.pickedWorld().toShortString() + " at "
                + outside.clickWorld().toShortString() + ")"
                + " state=" + outside.state().name()
                + ": no carrier owns that block and it holds no embedded component, so only this end is "
                + "pinned and every other side is cut";
    }

    /**
     * The judgement line for the inside lock: one end inside a structure, the other a block of the same structure
     * that holds no component.
     *
     * <p>Its twin is {@link #outsideLine}; the two are the same rule - <em>nothing is embedded on that side, so
     * only this end is pinned</em> - with the far end in two different places. {@code locked INSIDE the structure}
     * is the phrase a reader greps, and the pair printed is {@code (local A at B)} rather than a world pair,
     * because both ends are in the structure's own frame here.
     */
    @Nullable
    public static String insideLine(@Nullable CarriedTool.Action action,
                                    @Nullable CarriedHostIndex index) {
        if (!(action instanceof CarriedTool.Action.LockedInside inside)) {
            return null;
        }
        Slot slot = slotAt(index, inside.carrier(), inside.localPos());
        return "locked INSIDE the structure"
                + " picked=" + inside.carrier().shortId() + " local=" + inside.localPos().toShortString()
                + (slot == null ? " (empty)" : " (" + WrenchLinks.describe(slot) + ")")
                + " side=" + inside.side().getName()
                + " (local " + inside.pickedLocal().toShortString() + " at "
                + inside.clickLocal().toShortString() + ")"
                + " state=" + inside.state().name()
                + ": the far end is a device inside this structure with no component of its own, so only "
                + "this end is pinned and every other side is cut";
    }

    /**
     * What one wrench action did, in the form the log needs - a pure function of the action. {@code picked} and
     * {@code pickedLocalPos} are the pick as <em>this click</em> saw it, and {@code index} names the connection
     * state the line reports.
     */
    public static LinkLine of(@Nullable CarriedTool.Action action, @Nullable CarriedHostIndex index,
                              @Nullable CarrierId picked, @Nullable BlockPos pickedLocalPos,
                              CarrierId carrier, BlockPos localPos) {
        Slot pickedSlot = slotAt(index, picked, pickedLocalPos);
        CarrierId pickedCarrier = action instanceof CarriedTool.Action.NoPick ? null : picked;
        BlockPos pickedAt = pickedCarrier == null ? null : pickedLocalPos;
        String pickedDescription = pickedSlot == null ? null : WrenchLinks.describe(pickedSlot);

        if (action instanceof CarriedTool.Action.Linked linked) {
            return new LinkLine(true, Reason.LINKED, pickedCarrier, pickedAt, pickedDescription, carrier,
                    localPos, linked.localSide().getName(), linked.state().name(), null);
        }
        if (action instanceof CarriedTool.Action.CrossLinked linked) {
            // Four coordinates: two structures, each with the coordinate the line was written into, plus each
            // structure's OWN direction for it - the two directions are in two different frames.
            return new LinkLine(true, Reason.LINKED_ACROSS, linked.carrier(), linked.localPos(),
                    pickedComponent(index, linked.carrier(), linked.localPos()), linked.farCarrier(),
                    linked.farLocalPos(), linked.localSide().getName(), linked.state().name(),
                    linked.farLocalSide().getName());
        }
        if (action instanceof CarriedTool.Action.CrossFarEndEmpty far) {
            return new LinkLine(true, Reason.LINKED_ACROSS_EMPTY, far.carrier(), far.localPos(),
                    pickedComponent(index, far.carrier(), far.localPos()), far.farCarrier(),
                    far.farLocalPos(), far.localSide().getName(), far.state().name(), null);
        }
        if (action instanceof CarriedTool.Action.FarEndEmpty far) {
            // The side is asked between the two coordinates the ACTION named, not between the click and an
            // argument that may name another structure.
            return new LinkLine(true, Reason.TARGET_HOST_EMPTY, pickedCarrier, pickedAt,
                    pickedDescription, carrier, localPos,
                    localDirection(far.localPos(), far.towards()),
                    connectionState(index, carrier, far.localPos(), far.towards()), null);
        }        if (action instanceof CarriedTool.Action.Selected selected) {
            // Not a link attempt and not a refusal: the near end of the next line is this host, and both ends are
            // named from the ACTION, so the line cannot disagree with it.
            Slot slot = slotAt(index, selected.carrier(), selected.localPos());
            return new LinkLine(false, Reason.SELECTED, selected.carrier(), selected.localPos(),
                    slot == null ? null : WrenchLinks.describe(slot), selected.carrier(),
                    selected.localPos(), null, null, null);
        }
        if (action instanceof CarriedTool.Action.Cleared cleared) {
            // The clear DID something: every override is gone, and the pick ends with it.
            return new LinkLine(false, Reason.CLEARED, null, null, null, carrier, localPos,
                    null, null, null);
        }
        if (action instanceof CarriedTool.Action.AlreadyClear) {
            // Two clicks on one block: the first one cleared, this one has nothing to clear and the pick
            // is STILL this host - which is why it is named on the line rather than denied.
            return new LinkLine(false, Reason.SAME_HOST, pickedCarrier, pickedAt, pickedDescription,
                    carrier, localPos, null, null, null);
        }
        if (action instanceof CarriedTool.Action.NotAdjacent) {
            return new LinkLine(false, Reason.NOT_ADJACENT, pickedCarrier, pickedAt, pickedDescription,
                    carrier, localPos, null, null, null);
        }
        if (action instanceof CarriedTool.Action.PickedElsewhere elsewhere) {
            // Named from the ACTION, the one place that knows which structure the pick is really in.
            Slot slot = slotAt(index, elsewhere.pickedCarrier(), elsewhere.pickedLocalPos());
            return new LinkLine(false, Reason.PICKED_ELSEWHERE, elsewhere.pickedCarrier(),
                    elsewhere.pickedLocalPos(), slot == null ? null : WrenchLinks.describe(slot),
                    carrier, localPos, null, null, null);
        }
        if (action instanceof CarriedTool.Action.NoPick) {
            return new LinkLine(false, Reason.NOTHING_PICKED, null, null, null, carrier, localPos,
                    null, null, null);
        }
        if (action instanceof CarriedTool.Action.NoComponent) {
            return new LinkLine(false,
                    pickedCarrier == null ? Reason.NO_TARGET : Reason.PICKED_HOST_EMPTY, pickedCarrier,
                    pickedAt, pickedDescription, carrier, localPos, null, null, null);
        }
        return new LinkLine(false, Reason.OTHER, pickedCarrier, pickedAt, pickedDescription, carrier,
                localPos, null, null, null);
    }

    /** The component at one end of a cross-structure line, for the line's {@code (DUST)} half. */
    @Nullable
    private static String pickedComponent(@Nullable CarriedHostIndex index, CarrierId carrier,
                                          BlockPos localPos) {
        Slot slot = slotAt(index, carrier, localPos);
        return slot == null ? null : WrenchLinks.describe(slot);
    }

    /** The component one host holds, or {@code null} when there is no such live host. */
    @Nullable
    private static Slot slotAt(@Nullable CarriedHostIndex index, @Nullable CarrierId carrier,
                               @Nullable BlockPos localPos) {
        if (index == null || carrier == null || localPos == null) {
            return null;
        }
        CarriedHost host = CarriedTool.hostAt(index, carrier, localPos);
        return host == null ? null : host.slot();
    }

    /** The side between two local coordinates, or {@code null} when they share no face. */
    @Nullable
    private static String localDirection(BlockPos from, BlockPos to) {
        // Lower case like every other face name in this mod's lines and messages (`Direction#getName`),
        // so a reader greps `side=east` and not `side=EAST`.
        return CarriedTool.localDirectionBetween(from, to).map(Direction::getName).orElse(null);
    }

    /** The state that stands on the line after the action, asked through the one implementation. */
    @Nullable
    private static String connectionState(@Nullable CarriedHostIndex index, CarrierId carrier,
                                          BlockPos from, BlockPos to) {
        if (index == null) {
            return null;
        }
        com.jiangyuefengyu.redstonecircuit.data.ConnectionState state =
                CarriedTool.stateTowards(index, carrier, from, to);
        return state == null ? null : state.name();
    }
}

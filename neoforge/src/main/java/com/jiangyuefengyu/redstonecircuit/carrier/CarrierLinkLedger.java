package com.jiangyuefengyu.redstonecircuit.carrier;

import com.jiangyuefengyu.redstonecircuit.data.Slot;
import com.jiangyuefengyu.redstonecircuit.logic.CarriedTool;
import com.jiangyuefengyu.redstonecircuit.logic.WrenchLinks;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import org.jetbrains.annotations.Nullable;

/**
 * <b>The judgement line for a wrench link, written whether the link succeeded or was refused.</b>
 *
 * <h2>The defect this answers</h2>
 * The author's report was <em>"the wrench is unusable - there is a display but it cannot connect"</em>,
 * and the log's answer to every attempt was one sentence:
 *
 * <pre>
 *   carrier s:f3b2d036: link refused: nothing picked yet, at local=20481032, 128, 20483080
 * </pre>
 *
 * <p>That line names neither <b>which</b> block was picked, nor <b>which</b> block the click was on, nor
 * <b>why</b> the pair was rejected - and in that particular run the pick was not missing at all: it was
 * in another structure. Four facts have to be on the line for the next log to be readable without a
 * second run:
 *
 * <ol>
 *   <li><b>the pick</b>: which structure and which coordinate the near end is in;</li>
 *   <li><b>the target</b>: which structure and coordinate this click landed on;</li>
 *   <li><b>the side</b>: which face of the structure's own frame the line runs along - or why no side
 *       exists (not neighbours, no pick, another structure, an empty host);</li>
 *   <li><b>the value</b>: what the connection became when it did change, so "it linked" and "it linked to
 *       the same state it already had" are different lines.</li>
 * </ol>
 *
 * <h2>Why this is a class and not a few {@code String.format}s at the call site</h2>
 * The rule "which refusal is which" is exactly the rule that was wrong, and it is arithmetic over
 * {@link CarriedTool.Action} - so it lives here, with no level, no player and no packet, and the unit
 * tests pin every reason. The production caller passes both ends in and logs whatever comes back.
 *
 * <p>Nothing here writes anything: it is a description of a decision that has already been made.
 */
public final class CarrierLinkLedger {

    /** How a refusal is named, so the line can be grepped for one reason at a time. */
    public enum Reason {

        /** The line changed: both ends were written. */
        LINKED("linked"),
        /**
         * <b>The line changed across two structures, and BOTH records were written.</b>
         *
         * <p>The author's own machine: two separately physicalised blocks standing side by side are two
         * sub-levels, so every connection they tried was this case. Since P1.27 it is drawn rather than
         * refused, and this reason is what a reader greps for - {@code link linked ACROSS STRUCTURES}
         * with four coordinates, two per structure.
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
         * <b>The two ends are the same host, and there was nothing left to clear.</b>
         *
         * <p>The world's own gesture for shift-right-clicking the selected block is "drop every override on
         * it", and when there was nothing left to drop the carrier side keeps the pick and says so rather
         * than denying it (see {@code CarriedTool#clearChecked}). The first half of the sentence is
         * unchanged - {@code CarrierCrossLinkTest} greps for it - and the second half now says what the
         * click actually did, because "already automatic" and "cleared" are two different answers to one
         * gesture and the author has to be able to tell them apart on the line.
         */
        SAME_HOST("the pick and the click are the same host, and there was nothing left to clear on it "
                + "(every side already decides for itself)"),
        /**
         * <b>The click dropped every override on the host - not a line, the opposite of one.</b>
         *
         * <p>Shift-right-clicking the picked block itself is the world's own "let it decide for itself
         * again", so the line says that rather than "linked": a reader who greps for {@code link linked}
         * must not find a clear among them.
         */
        CLEARED("cleared: every override on this block is gone, so it decides for itself again"),
        /**
         * <b>Not a refusal at all: this click simply picked the near end of a line.</b>
         *
         * <p>A plain (non-shift) wrench click on a carried host is the world's own <em>select</em> gesture,
         * and the ledger had no case for it - so it fell through to {@link #OTHER} and every such click
         * printed {@code link refused: not a link}, which reads like a failure and is not one. The author's
         * own log has eight of those lines, one per click, each with the picked coordinate and the clicked
         * coordinate identical (the same block clicked over and over): the tool was working exactly as it
         * should and the judgement line said it was not. This is the honest name for what happened, and it
         * is greppable as its own phrase.
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
     * One link, as the log line needs it.
     *
     * @param linked whether a line was really written to both ends
     * @param reason how it is named
     * @param pickedCarrier the near end's structure, or {@code null} when nothing was picked
     * @param pickedLocalPos the near end's coordinate inside it, or {@code null}
     * @param pickedComponent a short description of the near end's component, or {@code null}
     * @param targetCarrier the structure the click landed on
     * @param targetLocalPos the clicked coordinate inside it
     * @param side the side the line runs along, in the structure's own frame, or {@code null}
     * @param state the connection state that now stands on that side, or {@code null}
     * @param farSide the <b>other</b> structure's own direction for the same line, when the line runs
     *     across two structures; {@code null} for a line inside one structure, where the two ends' sides
     *     are each other's opposites and printing both would be noise
     */
    public record LinkLine(boolean linked, Reason reason, @Nullable CarrierId pickedCarrier,
                           @Nullable BlockPos pickedLocalPos, @Nullable String pickedComponent,
                           CarrierId targetCarrier, BlockPos targetLocalPos, @Nullable String side,
                           @Nullable String state, @Nullable String farSide) {

        /**
         * One line, in a fixed field order, with every one of the four facts present.
         *
         * <p>Written so that a reader can tell the five failures apart by eye: {@code reason=} names the
         * decision, {@code picked=} and {@code on=} name the two ends, and {@code side=} is
         * {@code ?} exactly when no side could be named.
         *
         * <p><b>The head is three-valued, because {@link Reason#SELECTED} is not a verdict.</b>
         * {@code link } means a line was written, {@code link refused: } means the gesture was refused, and
         * a select click - which neither wrote nor refused anything - is written without either prefix, so
         * the line reads as the statement it is. Before P1.33 a select click fell through to
         * {@link Reason#OTHER} and printed {@code link refused: not a link}, which said a working gesture
         * had failed.
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
     * <b>The judgement line for the third kind of line: one end inside a structure, the other a block of
     * the world.</b>
     *
     * <h2>Why this is its own writer rather than a branch of {@link #of}</h2>
     * {@link #of} is written against a click that resolved to a <b>carried host</b>, so every line it
     * builds has an {@code on=<carrier>} end. This gesture is the one case where there is no such end: the
     * player shift-right-clicked an <b>ordinary world block</b> while a carried host was picked, and the
     * whole point is that nothing at that coordinate belongs to us. Printing {@code on=<some carrier>}
     * would be a lie about which carrier owns the block, and printing nothing would put this gesture back
     * into the silence P1.27 removed.
     *
     * <p>So the line names the same four facts in the same order and adds the two that make the gesture
     * distinguishable from a cross-structure line - the two <b>world</b> positions, because the end that was
     * pinned and the end that was pointed at are one face apart in the world and in two different frames:
     *
     * <pre>
     *   carrier s:d315200a: locked OUTSIDE the structure picked=s:d315200a local=1, 0, 0 (DUST) side=east
     *       (world -12, -60, -11 at -13, -60, -11) state=ON: no carrier owns that block and it holds no
     *       embedded component, so only this end is pinned and every other side is cut
     * </pre>
     *
     * <p><b>{@code OUTSIDE} is the word a reader greps.</b> {@code LINKED_ACROSS} means "two structures,
     * both records written"; this means "one structure, the world block is not a record anywhere, only the
     * near side was pinned" - two completely different facts that used to be the same nothing. There is no
     * {@code on=} end, and that is the honest shape: there is no carrier at the clicked coordinate.
     *
     * @param action the action the decision returned; only a {@link CarriedTool.Action.LockedOutside}
     *     produces a line here
     * @param index the carrier index, for the component's name
     */
    @Nullable
    public static String outsideLine(@Nullable CarriedTool.Action action,
                                     @Nullable CarriedHostIndex index) {
        if (!(action instanceof CarriedTool.Action.LockedOutside outside)) {
            return null;
        }
        Slot slot = slotAt(index, outside.carrier(), outside.localPos());
        // WRITTEN OUT RATHER THAN THROUGH `LinkLine#describe`, because the first three words of a judgement
        // line are what a reader greps and this line's are `locked OUTSIDE the structure` - where a
        // `LinkLine` would have had to print an `on=<carrier>` end for a block that belongs to no carrier
        // and would then have said `link not a link` in front of it (Reason.OTHER's text). The four facts
        // are the same four, in the same order, plus the two world positions.
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
     * <b>The judgement line for P1.32's inside lock: one end inside a structure, the other a block of the
     * same structure that holds no component.</b>
     *
     * <p>Its twin is {@link #outsideLine}; the two are the same rule (<em>nothing is embedded on that side,
     * so only this end is pinned</em>) with the far end in two different places, and the ledger has to make
     * them, and a cross-structure line, distinguishable at a glance:
     *
     * <pre>
     *   carrier s:c22611c6: locked INSIDE the structure picked=s:c22611c6 local=20481032, 127, 20481032 (DUST)
     *       side=east (local 20481032, 127, 20481032 at 20481032, 127, 20481033) state=ON: the far end is a
     *       device inside this structure with no component of its own, so only this end is pinned and every
     *       other side is cut
     * </pre>
     *
     * <p><b>{@code locked INSIDE the structure}</b> is the phrase a reader greps, and the pair printed is
     * {@code (local A at B)} rather than {@code (world A at B)} - both ends are in the structure's own frame
     * here, so a world position would be a second conversion of numbers the reader can already check against
     * {@code local=}. There is no {@code on=} end for the same reason {@code outsideLine} has none: the far
     * block holds no component, so no record was written at it.
     *
     * @param action the action the decision returned; only a {@link CarriedTool.Action.LockedInside}
     *     produces a line here
     * @param index the carrier index, for the component's name
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
     * What one wrench action did, in the form the log needs - as a pure function.
     *
     * @param action what the wrench decided; {@code null} means no action was produced at all
     * @param index the carrier index, for the connection state the line should report
     * @param picked the near end's structure, or {@code null} when nothing was picked
     * @param pickedLocalPos the near end's coordinate, or {@code null}
     * @param carrier the structure the click landed on
     * @param localPos the clicked coordinate
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
            // THE AUTHOR'S OWN CASE, and the line has to carry FOUR coordinates: two structures, each
            // with the coordinate the line was written into, plus each structure's OWN direction for it -
            // because the two directions are in two different frames and comparing them is the only way a
            // reader can check the conversion.
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
            // The side is asked between the two coordinates the ACTION named, which is the pair the line
            // was actually drawn between - not between the click and an argument that may name another
            // structure.
            return new LinkLine(true, Reason.TARGET_HOST_EMPTY, pickedCarrier, pickedAt,
                    pickedDescription, carrier, localPos,
                    localDirection(far.localPos(), far.towards()),
                    connectionState(index, carrier, far.localPos(), far.towards()), null);
        }        if (action instanceof CarriedTool.Action.Selected selected) {
            // A PLAIN SELECT CLICK (P1.33 commit C). Not a link attempt and not a refusal: the near end of
            // the next line is this host. Both ends are therefore named from the ACTION - the click's own
            // host - rather than from the ledger, which has just been written with it; a line built from
            // the action cannot disagree with the action.
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
            // The near end is named from the ACTION, which is the one place that knows which structure
            // the pick is really in - the argument above is only "the pick as this click saw it".
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

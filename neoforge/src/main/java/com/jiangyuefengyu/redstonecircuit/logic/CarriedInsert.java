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
 * <b>Putting a component into - and taking one out of - a block a structure is CARRYING.</b>
 *
 * <h2>The defect this exists for</h2>
 * After a block is physicalised, placing a redstone component inside it showed nothing at all: no
 * frame, no component. The reason is a coordinate-frame mismatch and not a renderer at all -
 * {@code InnerRedstoneInteraction} looks the clicked position up with
 * {@code WrenchLinks.slotAt(level, pos)}, which reads the <b>world</b> store by a <b>world</b>
 * coordinate. A physicalised block's position in the level is the structure's <em>own</em> coordinate
 * (Sable's plot coordinate; a Create contraption's {@code localPos}), and the world store has never
 * had an entry there - claiming is a move, not a copy. So the click was neither claimed nor recorded
 * anywhere, and the host never entered the carrier picture the overlay draws from. Hence "nothing,
 * not even a frame".
 *
 * <h2>Why this is a separate class and not a branch in the world's logic</h2>
 * Exactly the reason {@link CarriedTool} is one. The world's placement and retrieval are keyed by
 * {@code InnerRedstoneStore} and a {@code ServerLevel}; a carried host is keyed by
 * {@code (carrier, localPos)} in {@link CarriedHostIndex}, and the world position is only where the
 * projection table currently draws it. A previous round answered a request like this by adding a
 * branch to the shared world path and had to withdraw it whole (P1.18). So:
 *
 * <ul>
 *   <li>this class <b>names no level</b> - it takes the index and nothing else, so the whole of the
 *       behaviour is pinned by offline unit tests;</li>
 *   <li>the entry points resolve the click <b>first</b> and return immediately for an ordinary world
 *       coordinate, so the world's own paths see exactly the events they saw before;</li>
 *   <li>nothing here writes the world store. The record in the index is the only place a carried
 *       component lives, and the in-structure solve reads that same record.</li>
 * </ul>
 *
 * <h2>The rule, side by side with the world's</h2>
 * <table>
 *   <caption>One rule, two stores</caption>
 *   <tr><th></th><th>world block</th><th>carried block</th></tr>
 *   <tr><td>where the record is</td><td>{@code InnerRedstoneStore}, keyed by the world position</td>
 *       <td>{@code CarriedHostIndex}, keyed by {@code (carrier, localPos)}</td></tr>
 *   <tr><td>what "the clicked face" means</td><td>a world direction</td>
 *       <td>the structure's own direction, converted by the caller through
 *       {@code CarrierDirections#localDirection} - the same one conversion the signal paths use</td></tr>
 *   <tr><td>one component per block</td><td>{@code !existing.isEmpty() && !allowReplace} refuses</td>
 *       <td>the same, on the carried record</td></tr>
 *   <tr><td>empty hand</td><td>takes the component out</td><td>the same, on the carried record</td></tr>
 * </table>
 *
 * <h2>"Exactly once", and why it is structural rather than a guard</h2>
 * Every successful action <b>replaces the record</b> (or removes it), and a second delivery of the
 * same click then finds the new state and refuses on its own terms: a retrieve finds nothing left to
 * take, an insert finds the host already holding a component. On top of that the click itself is
 * deduplicated by {@code ClickOnce}, because one Create click can be delivered twice - once as
 * {@code ContraptionInteractionPacket} and once as an ordinary block click - and the two deliveries
 * name the same host. See {@code CarrierInteraction} for that guard.
 *
 * <h2>When the item must not be consumed</h2>
 * Only a successful {@link Action#INSERT} consumes it, and only the caller does the consuming. Every
 * refusal - no host, no component to store, the block already holds one, an unknown local face -
 * returns without touching the record, so the caller has nothing to shrink. The one case worth
 * stating: {@link Action#REFUSED_POSITION} is a click the carrier layer did not resolve at all, and
 * it is the world's click, not ours.
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

        /**
         * Whether the caller's held item is spent by this outcome.
         *
         * <p>Deliberately a separate question from {@link #acted()}: an insert is the only outcome
         * that spends an item, and stating it here means no call site has to remember which one it is.
         */
        public boolean consumesItem() {
            return this == INSERT;
        }

        /** A few words for the acceptance line. */
        public String describe() {
            return switch (this) {
                case INSERT -> "insert";
                case RETRIEVE -> "retrieve";
                case REFUSED_POSITION -> "refused: no carried host at this coordinate";
                case NOTHING_TO_RETRIEVE -> "refused: the carried host holds nothing to take out";
                case NOT_SNEAKING -> "refused: not sneaking (plain right-click is the block's own)";
                case NOT_A_COMPONENT -> "refused: the held item is not a redstone component";
                case REFUSED_DIRECTION -> "refused: the click carried no face to read from";
                case BLOCKED -> "refused: the carried host already holds a component";
            };
        }
    }

    private CarriedInsert() {
    }

    /**
     * One placement gesture against a host a structure is carrying.
     *
     * <p>The world's entry point is written as "sneak with a component in hand stores it, sneak with a
     * bare hand takes it out, anything else is vanilla's"; this is the same sentence with the store
     * changed. Everything the world's version refuses is refused here for the same reason, so the two
     * cannot drift into different rules.
     *
     * @param index the carrier index; {@code null} or a miss answers {@link Action#REFUSED_POSITION}
     * @param carrier the structure the click resolved to
     * @param localPos the host's coordinate <b>inside that structure</b>
     * @param localFace the clicked face <b>as the structure's own direction</b>, or {@code null}
     * @param sneaking {@code player.isSecondaryUseActive()}
     * @param held the component to store, or {@code null} for an empty hand
     * @param replaceExisting {@code RCConfig.allowReplace()}: whether an occupied host may be replaced
     * @return which action ran; only {@link Action#INSERT} spends the held item
     */
    public static Action apply(CarriedHostIndex index, CarrierId carrier, BlockPos localPos,
                               @Nullable Direction localFace, boolean sneaking,
                               @Nullable ComponentType held, boolean replaceExisting) {
        CarriedHost host = CarriedTool.hostAt(index, carrier, localPos);
        Action action = preview(host != null, host == null ? null : host.slot(), localFace, sneaking,
                held, replaceExisting);
        // THE WRITE, and it happens for exactly the two actions that act: a retrieve empties the record,
        // an insert replaces it. Everything that decides is in `preview`, so a caller can ask what a
        // placement would do - which is what makes a record and its contents land in one decision
        // (P1.33: `CarrierStore#resolveForPlacement` must not file a host the placement then refuses).
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
     * <b>The host-level removal notice a placement owes the client, or an empty list.</b>
     *
     * <h2>The defect this answers</h2>
     * Taking a component out leaves the block <b>riding the structure and holding nothing</b>, and the
     * carrier payload deliberately leaves an empty host out of its host list (see
     * {@code HostSync#carrierPayload}: "the view is left out, and the carrier message says the host no
     * longer carries a component"). That is right for the host list - there is nothing to draw - but it
     * is the <em>same shape</em> on the wire as a host that has been <b>put down</b>, and the two mean
     * opposite things to the client:
     *
     * <ul>
     *   <li><b>put down</b> - the block is back in the world, so the record is kept as a
     *       {@code LandedHosts} entry and drawn where it landed, even over air. That is the fix for
     *       "a component put down over nothing is invisible" and it must stay;</li>
     *   <li><b>emptied</b> - the block is still riding and holds nothing, so nothing may be drawn at
     *       that coordinate <b>in any of the three shapes</b> a client can draw a component from.</li>
     * </ul>
     *
     * <p>The client cannot tell them apart from absence alone, so a retrieval has to say it - exactly as
     * a break does (P1.21's removal notice). The consequence of not saying it is the author's report:
     * after a bare-hand retrieval the client stowed the host it was holding as <em>landed</em> and went
     * on drawing the frame <b>and</b> the component it had just been told was taken out, at the position
     * the block came from, for as long as the structure lived.
     *
     * <h2>Why an insert owes nothing</h2>
     * An insert <em>adds</em> the host to the payload rather than removing it: the carrier message names
     * it with its contents, so the client replaces its record and the decision it makes about the host
     * list never arises. A notice there would name a coordinate the client has just been told is
     * present - the contradictory message {@code LandedHosts#forget} resolves the safe way (gone wins),
     * which would delete a component the player had just stored.
     *
     * <p>Pure, and therefore pinned by an offline test rather than by a game: the answer is a function of
     * the action this class just returned.
     *
     * @param action what the placement did, as {@link #apply} answered it
     * @param localPos the host's coordinate <b>inside the structure</b>, i.e. the coordinate the client's
     *     own record is keyed by (never the world position the host is projected onto)
     * @return the local coordinate to name as removed, or an empty list when the message owes nothing
     */
    public static List<BlockPos> removalNotice(@Nullable Action action, @Nullable BlockPos localPos) {
        if (action != Action.RETRIEVE || localPos == null) {
            return List.of();
        }
        return List.of(localPos.immutable());
    }

    /**
     * <b>What one placement gesture WOULD do to a host whose contents are {@code slot}, writing
     * nothing.</b>
     *
     * <h2>Why the decision had to become its own method</h2>
     * {@link #apply} used to decide and write in one body, so the only way to ask "would this placement
     * store anything" was to run it - and a caller that had already filed a carrier record for the click
     * could not take that answer back. P1.33's first defect is exactly that pair: a wrench click filed an
     * <em>empty</em> host record through the placement path's filing helper and then the placement rule
     * refused, so the structure's index kept a record nothing had ever written into. Splitting the
     * decision out lets the filing ask the placement's own rule <b>before</b> it writes, so the two
     * cannot disagree - and it is the same code that decides, not a second copy of it.
     *
     * <p>The order of the questions is the definition and is unchanged from the body this replaces:
     * "is there a host at all" → "is the player sneaking" → "is the hand empty" → "did the click carry a
     * face" → "does replacing apply".
     *
     * @param hostExists whether the structure carries a live host at this coordinate; {@code false} is the
     *     ordinary world click, or a packet naming a coordinate this structure never claimed
     * @param slot what the host holds, or {@code null} for an empty host - <b>and for the host a filing
     *     is about to create, which holds nothing by definition</b>
     * @param localFace the clicked face as the structure's own direction, or {@code null}
     * @param sneaking {@code player.isSecondaryUseActive()}
     * @param held the component to store, or {@code null} for an empty hand
     * @param replaceExisting {@code RCConfig.allowReplace()}
     * @return which action <em>would</em> run; only {@link Action#INSERT} and {@link Action#RETRIEVE}
     *     write anything, and only {@link Action#INSERT} spends the held item
     */
    public static Action preview(boolean hostExists, @Nullable Slot slot, @Nullable Direction localFace,
                                 boolean sneaking, @Nullable ComponentType held,
                                 boolean replaceExisting) {
        if (!hostExists) {
            // Not a carried host at all: an ordinary world click, or a packet naming a coordinate this
            // structure never claimed. Either way the world owns it and nothing here is touched.
            return Action.REFUSED_POSITION;
        }
        if (!sneaking) {
            return Action.NOT_SNEAKING;
        }
        if (held == null) {
            // A bare hand: the world's rule is "take the component back out", and it needs nothing from
            // the click but the fact that the hand is empty. An empty host has nothing to take, so a bare
            // hand can never be the reason a record gets created.
            return slot == null ? Action.NOTHING_TO_RETRIEVE : Action.RETRIEVE;
        }
        if (localFace == null) {
            // The component's input side IS the clicked face, so a click with no face has nothing to
            // name. Refused rather than guessed at, and nothing is written.
            return Action.REFUSED_DIRECTION;
        }
        if (slot != null && !replaceExisting) {
            return Action.BLOCKED;
        }
        return Action.INSERT;
    }

    /**
     * <b>Whether a placement gesture would store a component into a host that does not exist yet.</b>
     *
     * <p>The pre-flight for one exact situation: the click landed on a plot cell of a live structure that
     * the index holds no record for, and the carrier layer is deciding whether to file one. The record it
     * would create holds nothing ({@code slot == null} - that is what "the index has never heard of this
     * block" means), and this is {@link #preview}'s answer for that host, through the same code the
     * placement itself will run a moment later.
     *
     * <p>So "the record is created" and "the slot is written" are one decision rather than two: a bare
     * hand, a tool, a click with no face, {@code carriedInsert=false} and a non-sneaking click all answer
     * {@code false} here, and none of them can leave an empty host behind.
     *
     * @return true only for a store: sneaking, a component in hand, a face on the click
     */
    public static boolean wouldInsert(@Nullable Direction localFace, boolean sneaking,
                                      @Nullable ComponentType held, boolean replaceExisting) {
        return preview(true, null, localFace, sneaking, held, replaceExisting).acted();
    }

    /**
     * Whether a host still holds what an action put there - the read the diagnostics and the
     * acceptance procedure both use.
     *
     * <p>One method rather than two {@code index.at(...)} calls at the call sites, so "what the log
     * says is there" and "what the client is about to be told" cannot be answered from different
     * records.
     */
    @Nullable
    public static Slot slotOf(CarriedHostIndex index, CarrierId carrier, BlockPos localPos) {
        CarriedHost host = CarriedTool.hostAt(index, carrier, localPos);
        return host == null ? null : host.slot();
    }
}

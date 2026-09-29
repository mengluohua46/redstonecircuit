package com.jiangyuefengyu.redstonecircuit.logic;

import java.util.Optional;

import com.jiangyuefengyu.redstonecircuit.data.ConnectionState;
import com.jiangyuefengyu.redstonecircuit.data.HostEntry;
import com.jiangyuefengyu.redstonecircuit.data.Slot;
import com.jiangyuefengyu.redstonecircuit.carrier.CarriedHost;
import com.jiangyuefengyu.redstonecircuit.carrier.CarriedHostIndex;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierId;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import org.jetbrains.annotations.Nullable;

/**
 * <b>P1.20: the redstone wrench and the rotation rod, on a host a structure is carrying.</b>
 *
 * <h2>Why this is a separate class and not a branch in the world's logic</h2>
 * The author asked, reasonably, whether a physicalised block can be operated with the wrench and the
 * rotation rod. It can - but not through {@code WrenchLinks}, {@code RotationRod} or
 * {@code InnerRedstoneInteraction}, all three of which are written against {@code InnerRedstoneStore}
 * and {@code ServerLevel}. Every entry point in them is keyed by a <em>world</em> position, and for a
 * carried host that position is the one thing that is not true: the component lives in the carrier
 * index under {@code (carrier, localPos)}, and the world position is only where the projection table
 * currently draws it.
 *
 * <p>A previous round (P1.18) answered the same request by adding a branch to the shared world paths.
 * That broke the world: the lever could not be pulled, components stopped dropping, and shutting the
 * game down looped. It was withdrawn whole. So this class exists instead: <b>it names no level at all</b>,
 * it touches only the index, and the world's own logic is not reached from here in any direction.
 *
 * <h2>What it is, exactly</h2>
 * The same three actions the world's wrench performs, and the same two-state turn the rod performs,
 * written against {@link CarriedHostIndex} instead of {@code InnerRedstoneStore}:
 *
 * <ul>
 *   <li><b>wrench, select</b> - remember which host the player picked, so the second half of the
 *       gesture has something to link from. Held server-side, per player, in
 *       {@code CarrierInteraction}, exactly as {@code WrenchSelection} holds the world's.</li>
 *   <li><b>wrench, link</b> - step the line between two <em>neighbouring</em> hosts through locked /
 *       cut / automatic. Neighbours in the structure's own frame, because that is where the components
 *       are: a machine that has been turned by 90 degrees has its local east pointing at the world's
 *       south, and the two blocks the player sees next to each other are neighbours locally.</li>
 *   <li><b>wrench, clear</b> - drop every override on one host, as the world's wrench does when the
 *       selected block is clicked again.</li>
 *   <li><b>rod</b> - turn the component towards the clicked face, which arrives as a <em>world</em>
 *       direction and is converted into the structure's own frame by the caller through
 *       {@link com.jiangyuefengyu.redstonecircuit.carrier.CarrierDirections#localDirection} - the one
 *       conversion this mod has, shared with the signal path so the two cannot disagree.</li>
 * </ul>
 *
 * <h2>What it deliberately does not do</h2>
 * It does not touch the world: no {@code Level}, no packet, no notification. It writes the index and
 * returns what happened, and the caller (which does have a level) broadcasts and logs. That split is
 * what makes the whole of the behaviour - the three-state cycle, the adjacency rule, the turn - pinned
 * by offline unit tests rather than by assembling a machine.
 */
public final class CarriedTool {

    /** What one wrench or rod action did, for the caller's message and for the tests. */
    public sealed interface Action {

        /** The carrier whose host was acted on. */
        CarrierId carrier();

        /** The host's coordinate inside that structure. */
        BlockPos localPos();

        /** One phrase for the log line: which action, on what. */
        String describe();

        // ------------------------------------------------------------- wrench --

        /** A host was picked as the near end of a line. */
        record Selected(CarrierId carrier, BlockPos localPos, String component) implements Action {

            @Override
            public String describe() {
                return "select " + component + " at local=" + localPos.toShortString();
            }
        }

        /** A line between two neighbouring hosts stepped to its next state. */
        record Linked(CarrierId carrier, BlockPos localPos, BlockPos towards, Direction localSide,
                      ConnectionState state) implements Action {

            @Override
            public String describe() {
                return "link local=" + localPos.toShortString() + " side=" + localSide.getName()
                        + " -> " + localPos.relative(localSide).toShortString() + " state=" + state;
            }
        }

        /** Every override on one host was dropped. */
        record Cleared(CarrierId carrier, BlockPos localPos) implements Action {

            @Override
            public String describe() {
                return "clear local=" + localPos.toShortString();
            }
        }

        /**
         * <b>The pick was on this very host and there was nothing left to clear.</b>
         *
         * <p>Two clicks on one block: the first clears every override and ends the pick (see
         * {@link CarriedTool#clearChecked}), the second finds nothing to clear and keeps the pick - so the
         * answer is "the pick is this block, and a line needs two blocks" rather than the old, untrue
         * "nothing has been picked yet".
         */
        record AlreadyClear(CarrierId carrier, BlockPos localPos) implements Action {

            @Override
            public String describe() {
                return "link refused: this is the block that was picked (there was nothing left to "
                        + "clear), and a line runs between TWO neighbouring blocks - shift-right-click the "
                        + "one beside it";
            }
        }

        // ---------------------------------------------------------------- rod --

        /** The component's input side now faces the clicked face. */
        record TurnedToInput(CarrierId carrier, BlockPos localPos, Direction localFace)
                implements Action {

            @Override
            public String describe() {
                return "rod local=" + localPos.toShortString() + " -> input side "
                        + localFace.getName();
            }
        }

        /** The component's output side now faces the clicked face. */
        record TurnedToOutput(CarrierId carrier, BlockPos localPos, Direction localFace)
                implements Action {

            @Override
            public String describe() {
                return "rod local=" + localPos.toShortString() + " -> output side "
                        + localFace.getName();
            }
        }

        // ------------------------------------------------------------ refusals --

        /** There is no component here, so there is nothing to act on. */
        record NoComponent(CarrierId carrier, BlockPos localPos, String tool) implements Action {

            @Override
            public String describe() {
                return tool + " refused: no component at local=" + localPos.toShortString();
            }
        }

        /** Nothing has been picked as the near end of a line yet - or the pick is in another structure. */
        record NoPick(CarrierId carrier, BlockPos localPos) implements Action {

            @Override
            public String describe() {
                return "link refused: nothing picked yet, at local=" + localPos.toShortString();
            }
        }

        /**
         * <b>A host WAS picked, and it belongs to another structure - and the two clicks are not
         * neighbours in the world either.</b>
         *
         * <h2>What this answer is for, and what it is not</h2>
         * The author's run reported <em>"the wrench cannot connect"</em>, and the log said
         * {@code link refused: nothing picked yet} on every attempt - which was untrue. The pick existed,
         * it was simply in <b>another carrier</b>: two clicks on two blocks that are next to each other in
         * the world landed in two different sub-levels ({@code s:d315200a} and {@code s:f3b2d036}), and
         * the two carriers are the only thing the difference consists of. So a player reading
         * "nothing picked yet" goes looking for a remembered click that is right there in the ledger,
         * while the real reason - <em>your pick is in that machine over there, not this one</em> - is the
         * one fact the line did not print.
         *
         * <p><b>Since P1.27 this is the answer only for two blocks that are NOT world neighbours</b>:
         * when the pick and the click really do stand beside each other, the line is drawn across the two
         * structures instead ({@link CrossLinked}), because that is the gesture the author was making.
         * What is left here is the genuine "wrong machine, and nowhere near this one", and the sentence
         * says exactly that.
         *
         * <p><b>The two world positions are carried, and they are not for the log.</b> They are what lets
         * the <b>message</b> say "the one two blocks north of it" instead of naming a coordinate: the only
         * positional fact about two blocks that is both true and useful is where one stands relative to the
         * other, and that is a direction, not a number. Both are in the world frame, which is the only
         * frame in which "next to" is a fact when the two ends are in different structures.
         */
        record PickedElsewhere(CarrierId carrier, BlockPos localPos, CarrierId pickedCarrier,
                               BlockPos pickedLocalPos, boolean worldAdjacent, BlockPos pickedWorld,
                               BlockPos clickWorld) implements Action {

            @Override
            public String describe() {
                return "link refused: the pick is in ANOTHER structure (" + pickedCarrier.shortId()
                        + " local=" + pickedLocalPos.toShortString() + ", this click is on "
                        + carrier.shortId() + " local=" + localPos.toShortString() + ")"
                        + (worldAdjacent
                                ? " and the two blocks ARE neighbours where they stand, so they look like "
                                        + "one machine: they have to be part of ONE structure for the wrench "
                                        + "to route between them"
                                : " and the two blocks are not neighbours anywhere either, so there is "
                                        + "nothing to route between them");
            }
        }

        /**
         * <b>A line between two neighbouring hosts that live in TWO structures - and it was written.</b>
         *
         * <h2>Why this replaced a refusal</h2>
         * P1.26 answered this case with {@link PickedElsewhere}, on the argument that "a line lives on the
         * two hosts' own records, each structure solves only its own, and nothing in the protocol lets one
         * carrier write into another's index". The last clause was an implementation choice rather than a
         * fact: both records are entries of the same {@code CarriedHostIndex}, keyed by
         * {@code (carrier, localPos)}, so writing both is one call to the same method the single-structure
         * case uses. Meanwhile the author's own machine <b>was</b> this case - two separately physicalised
         * blocks standing side by side are two sub-levels - so the refusal was answering the only gesture
         * they were making with a paragraph about structures.
         *
         * <p>What is stored, honestly: the near end gets {@code state} on
         * {@code pickedLocalSide}, the far end gets the same state on {@code clickLocalSide}, and each of
         * those two directions is a direction <em>in its own structure's frame</em>, derived once from the
         * one shared world face. Power still does not cross the gap - each structure's solve walks only
         * the records it carries - but nothing is written on one side and silently dropped on the other,
         * and the lock takes effect on both records.
         */
        record CrossLinked(CarrierId carrier, BlockPos localPos, CarrierId farCarrier,
                           BlockPos farLocalPos, Direction localSide, Direction farLocalSide,
                           ConnectionState state) implements Action {

            @Override
            public String describe() {
                return "link ACROSS STRUCTURES local=" + localPos.toShortString() + " side="
                        + localSide.getName() + " <-> " + farCarrier.shortId() + " local="
                        + farLocalPos.toShortString() + " side=" + farLocalSide.getName()
                        + " state=" + state;
            }
        }

        /**
         * A cross-structure line whose far end holds no component: only the near end could be pinned,
         * exactly as {@link FarEndEmpty} says for one structure.
         */
        record CrossFarEndEmpty(CarrierId carrier, BlockPos localPos, CarrierId farCarrier,
                                BlockPos farLocalPos, Direction localSide, ConnectionState state)
                implements Action {

            @Override
            public String describe() {
                return "link ACROSS STRUCTURES local=" + localPos.toShortString() + " side="
                        + localSide.getName() + " -> " + farCarrier.shortId() + " local="
                        + farLocalPos.toShortString() + ": the far end holds no component, so only this "
                        + "end is pinned";
            }
        }

        /** The component has no orientation, so a turn would mean nothing. */
        record NoDirection(CarrierId carrier, BlockPos localPos) implements Action {

            @Override
            public String describe() {
                return "rod refused: no orientation at local=" + localPos.toShortString();
            }
        }

        /** The two hosts do not share a face in the structure's own frame. */
        record NotAdjacent(CarrierId carrier, BlockPos localPos, BlockPos towards)
                implements Action {

            @Override
            public String describe() {
                return "link refused: local=" + localPos.toShortString() + " and "
                        + towards.toShortString() + " are not neighbours inside the structure";
            }
        }

        /** The far end of the line holds no component, so there is nowhere to write the other half. */
        record FarEndEmpty(CarrierId carrier, BlockPos localPos, BlockPos towards)
                implements Action {

            @Override
            public String describe() {
                return "link local=" + localPos.toShortString() + " side -> "
                        + towards.toShortString() + ": the far end holds no component, so only this end "
                        + "is pinned";
            }
        }

        /**
         * <b>The line was locked towards a block that stands OUTSIDE every structure, and only this end
         * exists to write it on.</b>
         *
         * <h2>Why this is not {@link CrossFarEndEmpty}</h2>
         * That one names a <b>second structure</b> whose host holds nothing, so the ledger can print the
         * other structure's id and its own coordinate. This one names a block that belongs to <b>the world
         * itself</b>: no carrier owns that coordinate, so there is no record to write at the far end - and
         * the world's own store must not be touched either, because writing the world store at a position
         * a player clicked is the very "two sources of truth" defect P1.26-P1.30 exist to remove. So the
         * action carries the two <b>world</b> positions instead: where the picked host stands now, and the
         * world block the player pointed at.
         *
         * <p>The semantics are the world's own {@code linked_on_device}: the pick is pinned on the side
         * that faces that block, and because a pin is a statement about the whole component
         * ({@code Slot#isClosed} cuts every side that is not pinned), <b>every other direction is cut</b>.
         * That is what makes "lock this machine's line towards the lamp standing beside it" possible
         * without the machine being merged into the world block's structure.
         *
         * @param side the side in the <b>structure's own frame</b>, computed once from the shared world
         *     face and turned into this frame through this mod's one conversion
         * @param state what that side's override became
         * @param pickedWorld where the picked host stands right now
         * @param clickWorld the world block the player pointed at, one face away from {@code pickedWorld}
         */
        record LockedOutside(CarrierId carrier, BlockPos localPos, Direction side,
                             ConnectionState state, BlockPos pickedWorld, BlockPos clickWorld)
                implements Action {

            @Override
            public String describe() {
                return "locked OUTSIDE the structure local=" + localPos.toShortString() + " side="
                        + side.getName() + " (world " + pickedWorld.toShortString() + " at "
                        + clickWorld.toShortString() + ") state=" + state
                        + ": no carrier owns that block and it holds no embedded component, so only this "
                        + "end is pinned and every other side is cut";
            }
        }

        /**
         * <b>The pick's line pinned towards a block that stands inside this same structure and holds no
         * component</b> - a piston, a lamp, a plain block the player built into the machine.
         *
         * <h2>Why this exists beside {@link LockedOutside}</h2>
         * The two are the same gesture with two far ends, and the world's own rule treats both the same
         * way: {@code linked_on_device} - <em>nothing is embedded on that side, so only this end is
         * pinned</em>. What differs is only where the far end is, and therefore what the ledger can say
         * about it:
         *
         * <ul>
         *   <li>{@code LockedOutside} names <b>two world positions</b>, because the far end belongs to the
         *       world and is identified by where it stands;</li>
         *   <li>this names <b>two coordinates inside the structure's own frame</b>, because that is the
         *       only frame the far end has - a Sable sub-level's plot coordinate <em>is</em> its local
         *       coordinate (see {@code CarrierStore.CarrierSource#plotOwnerAt}) - and the reader can check
         *       the adjacency that produced the side without any conversion at all.</li>
         * </ul>
         *
         * <p>Nothing is written at the far end: it holds no component, so there is no record to write, and
         * the structure's own solve reads a block it has no record for as an ordinary block.
         *
         * @param side the side in the <b>structure's own frame</b>; with both ends already in that frame
         *     this is the shared face itself, with no conversion involved
         * @param state what that side's override became
         * @param pickedLocal where the picked host sits inside the structure
         * @param clickLocal the coordinate inside the structure the player pointed at, one face away
         */
        record LockedInside(CarrierId carrier, BlockPos localPos, Direction side,
                            ConnectionState state, BlockPos pickedLocal, BlockPos clickLocal)
                implements Action {

            @Override
            public String describe() {
                return "locked INSIDE the structure local=" + localPos.toShortString() + " side="
                        + side.getName() + " (local " + pickedLocal.toShortString() + " at "
                        + clickLocal.toShortString() + ") state=" + state
                        + ": the far end is a device inside this structure with no component of its own, so "
                        + "only this end is pinned and every other side is cut";
            }
        }
    }

    private CarriedTool() {
    }

    /**
     * The host at {@code localPos} of {@code carrier}, or {@code null} when this structure carries none
     * there (or has already put it down).
     *
     * <p>The one read every action starts with, so "is this host still ours" cannot be answered two
     * different ways by the wrench and the rod.
     */
    @Nullable
    public static CarriedHost hostAt(CarriedHostIndex index, CarrierId carrier, BlockPos localPos) {
        if (index == null || carrier == null || localPos == null) {
            return null;
        }
        CarriedHost host = index.at(carrier, localPos);
        return host == null || host.landed() ? null : host;
    }

    /**
     * The direction from one host to a neighbouring one, in the structure's own frame.
     *
     * <p>Deliberately the same shape as {@code WrenchLinks#directionBetween}, and deliberately
     * <em>not</em> shared with it: that one answers about world positions and this one about local
     * coordinates, and a single method taking "two positions" would be wrong for one of the two callers
     * whichever frame it chose.
     */
    public static Optional<Direction> localDirectionBetween(BlockPos from, BlockPos to) {
        if (from == null || to == null) {
            return Optional.empty();
        }
        for (Direction direction : Direction.values()) {
            if (from.relative(direction).equals(to)) {
                return Optional.of(direction);
            }
        }
        return Optional.empty();
    }

    // ---------------------------------------------------------------- wrench --

    /**
     * Picks a host as the near end of a line - the first half of the gesture.
     *
     * <p>Refuses when the host holds nothing: the near end has to be the component being routed, which is
     * exactly the world wrench's rule.
     */
    public static Action select(CarriedHostIndex index, CarrierId carrier, BlockPos localPos) {
        CarriedHost host = hostAt(index, carrier, localPos);
        if (host == null || host.slot() == null) {
            return new Action.NoComponent(carrier, localPos, "wrench");
        }
        return new Action.Selected(carrier, localPos, WrenchLinks.describe(host.slot()));
    }

    /**
     * Pins the line between two neighbouring hosts of one structure, or moves it on to the next state.
     *
     * <p>The cycle is {@code AUTO -> ON -> OFF -> AUTO}, the same one {@link WrenchLinks#next} defines -
     * the same method, in fact, so the two halves of the wrench cannot drift into different orders.
     *
     * <p>Both notes are written when the far end holds a component, so neither end can be re-routed
     * without the other being re-routed too - the world wrench's rule, and a lock means the same thing
     * here: once a host has any locked side, every side that is not locked is cut
     * ({@code Slot#isClosed} derives that, so it holds inside a structure exactly as it does in the
     * world).
     *
     * @param towards the far end's local coordinate; must share a face with {@code localPos}
     */
    public static Action link(CarriedHostIndex index, CarrierId carrier, BlockPos localPos,
                              BlockPos towards) {
        CarriedHost near = hostAt(index, carrier, localPos);
        if (near == null || near.slot() == null) {
            return new Action.NoComponent(carrier, localPos, "wrench");
        }
        Optional<Direction> side = localDirectionBetween(localPos, towards);
        if (side.isEmpty()) {
            return new Action.NotAdjacent(carrier, localPos, towards);
        }
        Direction direction = side.get();
        Slot first = near.slot();
        ConnectionState after = WrenchLinks.next(WrenchLinks.stateOf(first, direction));
        first.setConnection(direction, after);
        index.updateSlot(carrier, localPos, first);

        CarriedHost far = hostAt(index, carrier, towards);
        if (far != null && far.slot() != null) {
            Slot second = far.slot();
            second.setConnection(direction.getOpposite(), after);
            index.updateSlot(carrier, towards, second);
            return new Action.Linked(carrier, localPos, towards, direction, after);
        }
        return new Action.FarEndEmpty(carrier, localPos, towards);
    }

    /**
     * The world wrench's "shift-right-click the selected block itself" gesture.
     */
    public static Action clear(CarriedHostIndex index, CarrierId carrier, BlockPos localPos) {
        CarriedHost host = hostAt(index, carrier, localPos);
        if (host == null || host.slot() == null) {
            return new Action.NoComponent(carrier, localPos, "wrench");
        }
        Slot slot = host.slot();
        slot.clearConnections();
        index.updateSlot(carrier, localPos, slot);
        return new Action.Cleared(carrier, localPos);
    }

    /**
     * <b>The same gesture, with the one case that must not silently forget the player's pick told
     * apart.</b>
     *
     * <h2>Why "cleared" is not always "cleared something"</h2>
     * The author's log has the same host clicked twice in a row, and the second click answered
     * {@code link refused: nothing picked yet} - a sentence about a pick that existed a moment earlier
     * and was dropped by the first click's clear. The world's own wrench does not drop its selection on a
     * clear, and the world's own answer to shift-clicking an already-automatic block is
     * {@code CLEARED} again - so both statements are true there and the player is never told anything
     * false.
     *
     * <p>The carrier side cannot copy that verbatim without one addition, because a pick is what the
     * <em>second half</em> of the gesture reads: so the clear only ends the pick when it really removed an
     * override. Clicking the same host a second time with nothing left to drop is answered
     * {@link Action.AlreadyClear} - "nothing to clear, and the pick is still this block" - which is the
     * honest name for what the click did.
     */
    public static Action clearChecked(CarriedHostIndex index, CarrierId carrier, BlockPos localPos) {
        CarriedHost host = hostAt(index, carrier, localPos);
        if (host == null || host.slot() == null) {
            return new Action.NoComponent(carrier, localPos, "wrench");
        }
        Slot slot = host.slot();
        boolean hadOverrides = !slot.forcedOn.isEmpty() || !slot.forcedOff.isEmpty();
        if (!hadOverrides) {
            return new Action.AlreadyClear(carrier, localPos);
        }
        slot.clearConnections();
        index.updateSlot(carrier, localPos, slot);
        return new Action.Cleared(carrier, localPos);
    }

    /**
     * <b>The link half of the wrench, decided against a pick that may be in another structure.</b>
     *
     * <h2>What this fixes, exactly</h2>
     * The author's log has fifteen {@code select} lines and every {@code link} attempt answered
     * {@code nothing picked yet}. The pick was recorded - {@link com.jiangyuefengyu.redstonecircuit.carrier.CarriedSelection}
     * had it, per player, from the moment of the select - and the link step could not see it, because the
     * two clicks had resolved to <b>two different carriers</b> and the caller's rule was "a line only ever
     * runs inside one structure". The rule is right; the <em>answer</em> was wrong: it said "nothing
     * picked" about a pick that existed, and it said it about the one case where the reason is worth
     * printing.
     *
     * <p>So the pick is the argument, and the three cases are three answers:
     *
     * <ol>
     *   <li><b>no pick</b> - {@link Action#NoPick}: the genuine "pick something first";</li>
     *   <li><b>a pick in another structure</b> - {@link Action#PickedElsewhere}, naming both structures and
     *       both coordinates, and saying whether the two blocks are neighbours <em>where they stand</em>.
     *       The last part is the difference between "you clicked the wrong machine" and "these two blocks
     *       are simply not connected";</li>
     *   <li><b>a pick in this structure</b> - exactly the behaviour that already existed:
     *       {@link #link}, which decides adjacency in the structure's <b>own</b> frame and refuses a
     *       diagonal or a distant second click by name ({@link Action.NotAdjacent}).</li>
     * </ol>
     *
     * <h2>Why a world-adjacent cross-structure line is still refused</h2>
     * Two neighbours can genuinely live in two structures - two separately physicalised blocks standing
     * side by side is exactly what the author's world contained - and the refusal is then a real limit
     * and not a bug: a line is stored on the two hosts' own records ({@code forcedOn}/{@code forcedOff}
     * on a {@link Slot}), each structure solves only the records it carries, and nothing in the protocol
     * lets one carrier write into another's index. Answering "linked" there would write one end and
     * silently do nothing to the other, which is the failure mode this whole round is about. So it is
     * refused, with both sides named, and the actionable message is the one the record describes:
     * <b>they have to be part of one structure for the wrench to route between them</b>.
     *
     * @param index the carrier index
     * @param carrier the structure the second click resolved to
     * @param localPos its coordinate inside that structure
     * @param picked the near end the player picked earlier; {@code null} when there is none
     * @param pickedCarrier the structure that pick is in, when there is one
     * @param pickedLocalPos that pick's coordinate inside its structure
     * @param worldAdjacent whether the pick and this click are neighbours <b>in the world</b>, computed
     *     by the caller from both hosts' current positions; only used for the refusal's wording
     */
    public static Action linkFrom(@Nullable CarriedHostIndex index, CarrierId carrier, BlockPos localPos,
                                  boolean picked, @Nullable CarrierId pickedCarrier,
                                  @Nullable BlockPos pickedLocalPos, boolean worldAdjacent) {
        if (!picked || pickedCarrier == null || pickedLocalPos == null) {
            return new Action.NoPick(carrier, localPos);
        }
        if (!pickedCarrier.equals(carrier)) {
            return new Action.PickedElsewhere(carrier, localPos, pickedCarrier, pickedLocalPos,
                    worldAdjacent, BlockPos.ZERO, BlockPos.ZERO);
        }
        return link(index, carrier, pickedLocalPos, localPos);
    }

    /** The state the wrench's next click would install on one side; for the tests and the log. */
    @Nullable
    public static ConnectionState stateTowards(CarriedHostIndex index, CarrierId carrier,
                                               BlockPos localPos, BlockPos towards) {
        CarriedHost host = hostAt(index, carrier, localPos);
        Optional<Direction> side = localDirectionBetween(localPos, towards);
        if (host == null || host.slot() == null || side.isEmpty()) {
            return null;
        }
        return WrenchLinks.stateOf(host.slot(), side.get());
    }

    // ------------------------------------------------------------------- rod --

    /**
     * Turns the component in a carried host towards the clicked face, <b>given in the structure's own
     * frame</b>.
     *
     * <p>{@link RotationRod#turn} is the world's implementation of the same gesture, and it is written
     * against a level; the arithmetic that decides input-versus-output is one line and is repeated here
     * rather than shared, because sharing it would mean changing the world's class - and the rule for
     * the two states is the whole of it:
     *
     * <pre>
     *   click the face the component should read from  -&gt; input side = that face
     *   click the same face again                     -&gt; output side = that face
     * </pre>
     *
     * <p>The <b>caller</b> is responsible for the world-to-local conversion of the clicked face (see
     * {@code CarrierDirections#localDirection}); this method never sees a world direction, which is what
     * keeps it free of a pose and therefore unit-testable.
     */
    public static Action turn(CarriedHostIndex index, CarrierId carrier, BlockPos localPos,
                              @Nullable Direction localFace) {
        CarriedHost host = hostAt(index, carrier, localPos);
        if (host == null || host.slot() == null) {
            return new Action.NoComponent(carrier, localPos, "rod");
        }
        Slot slot = host.slot();
        if (!RotationRod.hasDirection(slot.type)) {
            return new Action.NoDirection(carrier, localPos);
        }
        if (localFace == null) {
            return new Action.NoDirection(carrier, localPos);
        }
        boolean toInput = slot.facing != localFace;
        slot.facing = toInput ? localFace : localFace.getOpposite();
        index.updateSlot(carrier, localPos, slot);
        return toInput ? new Action.TurnedToInput(carrier, localPos, localFace)
                : new Action.TurnedToOutput(carrier, localPos, localFace);
    }

    /**
     * The one host entry a broadcast should carry after an action: the record as it now stands.
     *
     * <p>Handed to {@code HostSync} so the client's overlay redraws immediately - the connection bars and
     * the component's facing are both drawn from this, and nothing else would tell the client they
     * changed, because no block state did.
     */
    @Nullable
    public static HostEntry entryOf(CarriedHostIndex index, CarrierId carrier, BlockPos localPos) {
        CarriedHost host = hostAt(index, carrier, localPos);
        return host == null || host.slot() == null ? null
                : new HostEntry(localPos.immutable(), host.slot().copy());
    }
}

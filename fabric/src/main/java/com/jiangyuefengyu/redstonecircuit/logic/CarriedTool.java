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
 * The redstone wrench and the rotation rod, on a host a structure is carrying.
 *
 * <p>The world's own machinery - the wrench links, the rotation rod and the bare-hand interaction - is written
 * against the level's own store, and every entry point in it is keyed by a <em>world</em> position. That is the
 * one thing that is not true of a carried host: the component lives in the carrier index under
 * (carrier, localPos), and the world position is only where the projection table currently draws it. So this
 * class names no level at all - it touches only the index, writes it, and returns what happened, and the caller
 * (which does have a level) broadcasts and logs.
 *
 * <p>It offers the same three actions the world's wrench performs and the same two-state turn the rod performs:
 * a pick, a line between two <em>neighbouring</em> hosts stepped through locked / cut / automatic, a clear, and a
 * turn. Neighbours are neighbours in the structure's own frame, because that is where the components are, and a
 * world direction is converted on the way in by the caller through this mod's one conversion, so the two cannot
 * disagree. That split is also what makes the behaviour pinnable by offline unit tests.
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
         * <p>The first click clears every override and ends the pick, so the second finds nothing to clear and
         * keeps it - and the answer is "the pick is this block, and a line needs two blocks" rather than "nothing
         * has been picked yet".
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

        /** The component stepped one place around the cycle; {@code localFacing} is where it points now. */
        record Turned(CarrierId carrier, BlockPos localPos, Direction localFacing)
                implements Action {

            @Override
            public String describe() {
                return "rod local=" + localPos.toShortString() + " -> " + localFacing.getName();
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
         * <b>A host WAS picked, and it belongs to another structure - and the two clicks are not neighbours in
         * the world either.</b>
         *
         * <p>"Nothing picked yet" would be untrue: the pick exists, it is simply in <b>another carrier</b> - two
         * clicks on two blocks that are next to each other in the world can land in two different sub-levels, and
         * the two carriers are the only thing the difference consists of. So a player reading the refusal is told
         * the real reason instead of looking for a remembered click that is right there.
         *
         * <p>The two world positions are carried, and they are not for the log: they are what lets the message say
         * "the one two blocks north of it" instead of naming a coordinate, which is the only positional fact about
         * two blocks that is both true and useful when the two ends are in different structures.
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
         * <p>Refusing this case was an implementation choice rather than a fact: both records are entries of the
         * same {@link CarriedHostIndex}, keyed by (carrier, localPos), so writing both is one call to the method
         * the single-structure case uses - and two separately physicalised blocks standing side by side really are
         * two sub-levels.
         *
         * <p>What is stored, honestly: the near end gets the state on its own side and the far end gets the same
         * state on its own side, each of those two directions being a direction <em>in its own structure's
         * frame</em>, derived once from the one shared world face. Power still does not cross the gap - each
         * structure's solve walks only the records it carries - but nothing is written on one side and silently
         * dropped on the other, and the lock takes effect on both records.
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
         * <b>The line was locked towards a block that stands OUTSIDE every structure, and only this end exists
         * to write it on.</b>
         *
         * <p>No carrier owns that coordinate, so there is no record to write at the far end - and the world's own
         * store must not be touched either, because writing the world store at a position a player clicked is the
         * very "two sources of truth" trap that has to stay shut. So the action carries the two <b>world</b>
         * positions instead: where the picked host stands now, and the world block the player pointed at.
         *
         * <p>The semantics are the world's own: the pick is pinned on the side that faces that block, and because a
         * pin is a statement about the whole component ({@code Slot#isClosed} cuts every side that is not pinned),
         * <b>every other direction is cut</b> - which is what makes "lock this machine's line towards the lamp
         * standing beside it" possible without merging the machine into the world block's structure.
         *
         * @param side the side in the <b>structure's own frame</b>, computed once from the shared world face
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
         * <p>The world's own rule treats both far ends the same way: nothing is embedded on that side, so only this
         * end is pinned. What differs is only where the far end is, and therefore what the ledger can say about
         * it. {@link LockedOutside} names <b>two world positions</b>, because the far end belongs to the world;
         * this names <b>two coordinates inside the structure's own frame</b>, because that is the only frame the far
         * end has - a Sable sub-level's plot coordinate <em>is</em> its local coordinate - and a reader can check the
         * adjacency that produced the side without any conversion at all.
         *
         * <p>Nothing is written at the far end: it holds no component, so there is no record to write, and the
         * structure's own solve reads a block it has no record for as an ordinary block.
         *
         * @param side the side in the <b>structure's own frame</b>; with both ends already in that frame this is
         *     the shared face itself, with no conversion involved
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
     * The host at {@code localPos} of {@code carrier}, or {@code null} when this structure carries none there (or
     * has already put it down). The one read every action starts with, so "is this host still ours" cannot be
     * answered two different ways by the wrench and the rod.
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
     * <p>Deliberately the same shape as the world wrench's own {@code directionBetween}, and deliberately
     * <em>not</em> shared with it: that one answers about world positions and this one about local coordinates,
     * and a single method taking "two positions" would be wrong for one of the two callers whichever frame it
     * chose.
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
     * Picks a host as the near end of a line - the first half of the gesture. Refuses when the host holds nothing:
     * the near end has to be the component being routed, which is exactly the world wrench's rule.
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
     * <p>The cycle is {@code AUTO -> ON -> OFF -> AUTO}, the same one the world wrench's own {@code next} defines -
     * the same method, in fact, so the two halves of the wrench cannot drift into different orders. Both notes are
     * written when the far end holds a component, so neither end can be re-routed without the other being
     * re-routed too; a lock means the same thing here as in the world: once a host has any locked side, every side
     * that is not locked is cut.
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
     * <b>The same gesture, with the one case that must not silently forget the player's pick told apart.</b>
     *
     * <p>The world's own wrench does not drop its selection when it clears, and it answers shift-clicking an
     * already-automatic block with "cleared" again - both statements are true there. The carrier side cannot copy
     * that verbatim, because a pick is what the <em>second half</em> of the gesture reads: so the clear only ends
     * the pick when it really removed an override, and a second click on the same host with nothing left to drop is
     * answered {@link Action.AlreadyClear} - "nothing to clear, and the pick is still this block" - which is the
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
     * <p>The rule "a line only ever runs inside one structure" is right, but answering it with "nothing picked yet"
     * was not: the pick existed and was only in another carrier, which is the one case where the reason is worth
     * printing. So the pick is the argument, and the three cases are three answers:
     *
     * <ol>
     *   <li><b>no pick</b> - {@link Action#NoPick}: the genuine "pick something first";</li>
     *   <li><b>a pick in another structure</b> - {@link Action#PickedElsewhere}, naming both structures and both
     *       coordinates, and saying whether the two blocks are neighbours <em>where they stand</em>, which is the
     *       difference between "you clicked the wrong machine" and "these two blocks are simply not connected";</li>
     *   <li><b>a pick in this structure</b> - {@link #link}, which decides adjacency in the structure's
     *       <b>own</b> frame and refuses a diagonal or a distant second click by name.</li>
     * </ol>
     *
     * <p>A world-adjacent cross-structure line is then a real limit and not a bug: a line is stored on the two
     * hosts' own records, each structure solves only the records it carries, and nothing in the protocol lets one
     * carrier write into another's index. Answering "linked" there would write one end and silently do nothing to
     * the other.
     *
     * @param index the carrier index
     * @param carrier the structure the second click resolved to
     * @param localPos its coordinate inside that structure
     * @param picked the near end the player picked earlier; {@code null} when there is none
     * @param pickedCarrier the structure that pick is in, when there is one
     * @param pickedLocalPos that pick's coordinate inside its structure
     * @param worldAdjacent whether the pick and this click are neighbours <b>in the world</b>, computed by the
     *     caller from both hosts' current positions; only used for the refusal's wording
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
     * Steps the component in a carried host one place around the cycle the world's rod walks: north, east, south,
     * west, then up, then down, then north again.
     *
     * <p>The clicked face is not consulted - the step is read off {@link RotationRod#nextFacing}, so a machine's
     * component and the same component on the ground cannot drift apart. Nothing here needs a pose, which is what
     * keeps it unit-testable, and the caller no longer has a face to convert.
     */
    public static Action turn(CarriedHostIndex index, CarrierId carrier, BlockPos localPos) {
        CarriedHost host = hostAt(index, carrier, localPos);
        if (host == null || host.slot() == null) {
            return new Action.NoComponent(carrier, localPos, "rod");
        }
        Slot slot = host.slot();
        if (!RotationRod.hasDirection(slot.type)) {
            return new Action.NoDirection(carrier, localPos);
        }
        slot.facing = RotationRod.nextFacing(slot.facing);
        index.updateSlot(carrier, localPos, slot);
        return new Action.Turned(carrier, localPos, slot.facing);
    }

    /**
     * The one host entry a broadcast should carry after an action: the record as it now stands.
     *
     * <p>Handed to the sync layer so the client's overlay redraws immediately - the connection bars and the
     * component's facing are both drawn from this, and nothing else would tell the client they changed, because no
     * block state did.
     */
    @Nullable
    public static HostEntry entryOf(CarriedHostIndex index, CarrierId carrier, BlockPos localPos) {
        CarriedHost host = hostAt(index, carrier, localPos);
        return host == null || host.slot() == null ? null
                : new HostEntry(localPos.immutable(), host.slot().copy());
    }
}

package com.jiangyuefengyu.redstonecircuit.logic;

import java.util.Optional;

import org.jetbrains.annotations.Nullable;

import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.ConnectionState;
import com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneNode;
import com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneStore;
import com.jiangyuefengyu.redstonecircuit.data.Slot;
import com.jiangyuefengyu.redstonecircuit.network.HostSync;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;

/**
 * What the redstone wrench does to two neighbouring components (R7).
 *
 * <h2>The rule</h2>
 * Pick a component with a plain right-click, then shift-right-click a component next to it. Repeating
 * that pair walks the link through its three states, so one gesture covers "connect these two",
 * "keep these two apart" and "let the network decide again":
 *
 * <pre>
 *   AUTO --(wrench)--> ON --(wrench)--> OFF --(wrench)--> AUTO
 * </pre>
 *
 * <p>Both ends are pinned, not just the one that was clicked first: a connection is a property of the
 * pair, and pinning one side only would leave the other free to be re-routed by a later placement -
 * which is exactly what the design says must not happen ("在旁边放红石也不会影响连接").
 *
 * <h2>Why the state is stored per direction</h2>
 * A host block holds one component, and its neighbours are exactly the six positions around it, so
 * "which components talk to each other" reduces to a per-direction override. {@code PowerSolver} reads
 * those overrides for both emitting and reading, and {@code forcedOff} also cuts a comparator's side
 * input, so the three states mean the same thing everywhere.
 *
 * <p>Deliberately free of any player, item or packet: the wrench item, the interaction handler, the
 * {@code /rc connect} command and the game tests all go through this, so none of them can drift.
 */
public final class WrenchLinks {

    /** What {@link #link} or {@link #clear} did. */
    public enum Result {
        /** The link was not pinned before and now is. */
        LINKED_ON,
        /** The link was pinned on and is now cut. */
        LINKED_OFF,
        /** The link was cut and is now back to automatic. */
        LINKED_AUTO,
        /** The block's overrides were all cleared. */
        CLEARED,
        /** The two blocks are not next to each other, so there is no link to speak of. */
        NOT_ADJACENT,
        /** One of the two blocks holds nothing. */
        NO_COMPONENT
    }

    private WrenchLinks() {
    }

    /** The direction from {@code a} to {@code b}, if they share a face. */
    public static Optional<Direction> directionBetween(BlockPos a, BlockPos b) {
        for (Direction direction : Direction.values()) {
            if (a.relative(direction).equals(b)) {
                return Optional.of(direction);
            }
        }
        return Optional.empty();
    }

    /**
     * Pins the link between two neighbouring components, or moves it on to the next state.
     *
     * @return what happened, for the message the player gets
     */
    public static Result link(ServerLevel level, BlockPos a, BlockPos b) {
        Optional<Direction> towards = directionBetween(a, b);
        if (towards.isEmpty()) {
            return Result.NOT_ADJACENT;
        }
        InnerRedstoneStore store = InnerRedstoneStore.get(level);
        Slot first = store.slotAt(a);
        Slot second = store.slotAt(b);
        if (first == null || second == null) {
            return Result.NO_COMPONENT;
        }

        Direction direction = towards.get();
        ConnectionState before = stateOf(first, direction);
        ConnectionState after = next(before);

        first.setConnection(direction, after);
        second.setConnection(direction.getOpposite(), after);

        innerChanged(level, store, a, first);
        innerChanged(level, store, b, second);

        return switch (after) {
            case ON -> Result.LINKED_ON;
            case OFF -> Result.LINKED_OFF;
            case AUTO -> Result.LINKED_AUTO;
        };
    }

    /**
     * Sets one direction's override on one component, and does the bookkeeping that follows.
     *
     * <p>The {@code /rc connect} command goes through here too, so "the wrench pinned it" and "the
     * command pinned it" cannot end up meaning two different things.
     */
    public static void setState(ServerLevel level, BlockPos pos, Direction direction,
                                ConnectionState state) {
        InnerRedstoneStore store = InnerRedstoneStore.get(level);
        Slot slot = store.slotAt(pos);
        if (slot == null) {
            return;
        }
        slot.setConnection(direction, state);
        innerChanged(level, store, pos, slot);
    }

    /** Drops every override on one component, putting it back to automatic on all six sides. */
    public static Result clear(ServerLevel level, BlockPos pos) {
        InnerRedstoneStore store = InnerRedstoneStore.get(level);
        Slot slot = store.slotAt(pos);
        if (slot == null) {
            return Result.NO_COMPONENT;
        }
        slot.clearConnections();
        innerChanged(level, store, pos, slot);
        return Result.CLEARED;
    }

    /** The state of one direction of one component, as the wrench sees it. */
    public static ConnectionState stateOf(Slot slot, Direction direction) {
        if (slot.forcedOn.contains(direction)) {
            return ConnectionState.ON;
        }
        if (slot.forcedOff.contains(direction)) {
            return ConnectionState.OFF;
        }
        return ConnectionState.AUTO;
    }

    /** The next state in the wrench's cycle. */
    public static ConnectionState next(ConnectionState current) {
        return switch (current) {
            case AUTO -> ConnectionState.ON;
            case ON -> ConnectionState.OFF;
            case OFF -> ConnectionState.AUTO;
        };
    }

    /** A short description of what a component is, for the "selected" message. */
    public static String describe(@Nullable Slot slot) {
        if (slot == null) {
            return "nothing";
        }
        ComponentType type = slot.type;
        if (type.isDiode()) {
            return type.name() + " facing " + slot.facing.getName();
        }
        return type.name();
    }

    /**
     * The bookkeeping every override change needs, in one place.
     *
     * <p>An override changes which neighbours a component talks to, so the network has to be re-solved
     * (which is what turns the pin into an actual signal), the surrounding world has to be told
     * because no block state changed, and clients have to be told because the drawing shows the
     * connections.
     */
    private static void innerChanged(ServerLevel level, InnerRedstoneStore store, BlockPos pos,
                                     Slot slot) {
        store.markDirty();
        InnerRedstoneNetwork.markDirtyWithNeighbours(level, pos);
        InnerRedstoneNetwork.notifyOutputChanged(level, pos);
        HostSync.broadcast(level, pos, slot);
    }

    /** The component at {@code pos}, or {@code null}; used by the interaction for its messages. */
    @Nullable
    public static Slot slotAt(ServerLevel level, BlockPos pos) {
        InnerRedstoneNode node = InnerRedstoneStore.get(level).get(pos);
        return node == null || node.isEmpty() ? null : node.slot();
    }
}

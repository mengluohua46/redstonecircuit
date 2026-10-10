package com.jiangyuefengyu.redstonecircuit.logic;

import java.util.Optional;

import org.jetbrains.annotations.Nullable;

import com.jiangyuefengyu.redstonecircuit.carrier.CarrierMessages;
import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.ConnectionState;
import com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneNode;
import com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneStore;
import com.jiangyuefengyu.redstonecircuit.data.Slot;
import com.jiangyuefengyu.redstonecircuit.network.HostSync;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;

/**
 * What the redstone wrench does to two neighbouring components: plain right-click one, then
 * shift-right-click the one next to it, and the same gesture cycles {@code AUTO -> ON -> OFF -> AUTO}.
 *
 * <p>锁定意味着这一整块只连被锁的面，其余各面全部切断（切断由锁推出，见 {@code Slot#isClosed}），所以可以
 * 锁多个方向；另一端不必带元件。状态按方向存，因为一个 host 只放一个元件，邻居就是周围六个位置。
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
        /** The clicked block holds nothing, so there is nothing to route from. */
        NO_COMPONENT,
        /** There is no block at all on that side - the player is pointing at thin air. */
        NOTHING_THERE
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

    /** Pins the link between two neighbouring components, or moves it on to the next state. The far end does
     * <b>not</b> have to hold anything - requiring that would mean burying redstone in a piston to point at it. */
    public static Result link(ServerLevel level, BlockPos a, BlockPos b) {
        Optional<Direction> towards = directionBetween(a, b);
        if (towards.isEmpty()) {
            return Result.NOT_ADJACENT;
        }
        InnerRedstoneStore store = InnerRedstoneStore.get(level);
        Slot first = store.slotAt(a);
        if (first == null) {
            return Result.NO_COMPONENT;
        }
        if (level.getBlockState(b).isAir()) {
            return Result.NOTHING_THERE;
        }
        Slot second = store.slotAt(b);

        Direction direction = towards.get();
        ConnectionState before = stateOf(first, direction);
        ConnectionState after = next(before);

        first.setConnection(direction, after);
        innerChanged(level, store, a, first);
        if (second != null) {
            // A neighbour with a component of its own is pinned back, so neither end can be re-routed alone.
            second.setConnection(direction.getOpposite(), after);
            innerChanged(level, store, b, second);
        }

        return switch (after) {
            case ON -> Result.LINKED_ON;
            case OFF -> Result.LINKED_OFF;
            case AUTO -> Result.LINKED_AUTO;
        };
    }

    /** Sets one direction's override on one component and does the bookkeeping that follows. The
     * {@code /rc connect} command goes through here too, so "the wrench pinned it" and "the command pinned
     * it" cannot end up meaning two different things. */
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

    /** The stored state of one direction, which is what the wrench's cycle steps through. The <em>stored</em>
     * state and not {@code Slot#isConnected}: a side a lock cut by implication has no override of its own, so it
     * reads as automatic - which is what makes several locked directions possible. */
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

    /** A short description of what a component is, for the log lines: the enum's own words, because a log line
     * is read against the code. Never a player-facing string - see {@link #label}. */
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

    /** The same description as a player reads it: the component's translated name and its facing in words.
     * Built from translation keys only - never an enum name, a registry id or {@code Block#toString}. */
    public static Component label(@Nullable Slot slot) {
        if (slot == null) {
            return CarrierMessages.blockLabel((Component) null, (Direction) null);
        }
        return CarrierMessages.blockLabel(slot, CarrierMessages.componentName(slot.type));
    }

    /** The bookkeeping every override change needs, in one place: an override changes which neighbours a
     * component talks to, so the network is re-solved, the world is told because no block state changed, and
     * clients are told because the drawing shows the connections. */
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

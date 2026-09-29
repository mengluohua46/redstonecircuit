package com.jiangyuefengyu.redstonecircuit.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.ConnectionState;
import com.jiangyuefengyu.redstonecircuit.data.Slot;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests the rules the redstone wrench applies, without a level.
 *
 * <p>The interesting half of the wrench is the state machine and the way a link is pinned on both
 * sides; the other half is a click, which a game test covers. Getting the cycle wrong would be easy to
 * miss in game - a player would just click again and get a state they did not expect - so it is pinned
 * here, including the fact that it returns to where it started after three clicks.
 */
class WrenchLinksTest {

    private static Slot dust() {
        return new Slot(ComponentType.DUST);
    }

    @Test
    @DisplayName("the cycle is automatic -> connected -> cut -> automatic")
    void cycleOrder() {
        assertEquals(ConnectionState.ON, WrenchLinks.next(ConnectionState.AUTO));
        assertEquals(ConnectionState.OFF, WrenchLinks.next(ConnectionState.ON));
        assertEquals(ConnectionState.AUTO, WrenchLinks.next(ConnectionState.OFF));
    }

    @Test
    @DisplayName("a fresh component reads as automatic on every side")
    void freshSlotIsAutomatic() {
        Slot slot = dust();
        for (Direction direction : Direction.values()) {
            assertEquals(ConnectionState.AUTO, WrenchLinks.stateOf(slot, direction),
                    direction + " starts unlocked");
        }
    }

    @Test
    @DisplayName("the state the wrench sees is the state the solver uses")
    void stateMatchesTheSolver() {
        Slot slot = dust();
        slot.setConnection(Direction.UP, ConnectionState.ON);
        slot.setConnection(Direction.DOWN, ConnectionState.OFF);

        assertEquals(ConnectionState.ON, WrenchLinks.stateOf(slot, Direction.UP));
        assertEquals(ConnectionState.OFF, WrenchLinks.stateOf(slot, Direction.DOWN));
        assertTrue(slot.isConnected(Direction.UP), "a pinned side is connected");
        assertFalse(slot.isConnected(Direction.DOWN), "and a cut one is not");
    }

    @Test
    @DisplayName("directionBetween only answers for blocks that share a face")
    void directionBetweenIsStrict() {
        BlockPos origin = new BlockPos(10, 20, 30);
        assertEquals(Optional.of(Direction.EAST), WrenchLinks.directionBetween(origin, origin.east()));
        assertEquals(Optional.of(Direction.DOWN), WrenchLinks.directionBetween(origin, origin.below()));
        assertEquals(Optional.of(Direction.NORTH), WrenchLinks.directionBetween(origin, origin.north()));

        assertEquals(Optional.empty(), WrenchLinks.directionBetween(origin, origin.offset(1, 1, 0)),
                "a diagonal is not a link");
        assertEquals(Optional.empty(), WrenchLinks.directionBetween(origin, origin.offset(2, 0, 0)),
                "and neither is a block two away");
        assertEquals(Optional.empty(), WrenchLinks.directionBetween(origin, origin),
                "a block is not adjacent to itself");
    }

    /**
     * The design says a lock means "this line and nothing else", so once a side is locked every other
     * side is cut. One-sided cutting would leave the far end free to be re-routed by whatever is put
     * beside it, which is the exact thing the wrench exists to prevent.
     */
    @Test
    @DisplayName("locking leaves only the locked line alive")
    void lockingCutsEveryOtherSide() {
        Slot slot = dust();
        slot.setConnection(Direction.EAST, ConnectionState.ON);

        assertTrue(slot.isRoutingLocked(), "the component is now routed by hand");
        assertTrue(slot.isOpen(Direction.EAST), "the locked side is the line");
        assertFalse(slot.isClosed(Direction.EAST), "and it is not cut");
        for (Direction direction : Direction.values()) {
            if (direction != Direction.EAST) {
                assertTrue(slot.isClosed(direction), direction + " is left out of the lock, so it is cut");
                assertFalse(slot.isConnected(direction), direction + " must carry nothing");
            }
        }
    }

    /**
     * The point of deriving those cuts: a second locked direction must be one click away.
     *
     * <p>This is what a materialised lock gets wrong - writing the cuts down makes the next lock start
     * from "cut" and take three clicks to become a lock, which reads as "I can only ever have one
     * locked direction".
     */
    @Test
    @DisplayName("a second direction locks in one step, because the first lock's cuts are derived")
    void severalDirectionsCanBeLocked() {
        Slot slot = dust();
        // The wrench's own cycle, applied to two different neighbours of the same component.
        slot.setConnection(Direction.EAST, WrenchLinks.next(stateOf(slot, Direction.EAST)));
        assertEquals(ConnectionState.ON, stateOf(slot, Direction.EAST), "the first line is locked");

        assertEquals(ConnectionState.AUTO, WrenchLinks.stateOf(slot, Direction.SOUTH),
                "a side cut by the lock still reads as automatic, so one click locks it");
        slot.setConnection(Direction.SOUTH, WrenchLinks.next(stateOf(slot, Direction.SOUTH)));

        assertTrue(slot.isOpen(Direction.EAST) && slot.isOpen(Direction.SOUTH),
                "both lines are locked");
        assertFalse(slot.isClosed(Direction.EAST) || slot.isClosed(Direction.SOUTH),
                "and neither of them is cut");
        assertTrue(slot.isClosed(Direction.NORTH), "while everything else stays cut");
    }

    /** An explicit cut is a different thing from a side a lock merely left out. */
    @Test
    @DisplayName("an explicit cut freezes one side without freezing the whole component")
    void explicitCutIsNotALock() {
        Slot slot = dust();
        slot.setConnection(Direction.NORTH, ConnectionState.OFF);

        assertFalse(slot.isRoutingLocked(), "cutting one side is not locking the component");
        assertTrue(slot.isClosed(Direction.NORTH), "that side is dead");
        assertFalse(slot.isClosed(Direction.SOUTH), "while the others keep deciding for themselves");
        assertTrue(slot.isConnected(Direction.SOUTH));
    }

    /** What {@link WrenchLinks#stateOf} reports for a direction with no override at all. */
    private static ConnectionState stateOf(Slot slot, Direction direction) {
        return WrenchLinks.stateOf(slot, direction);
    }

    /**
     * A lock must not stop a driven component reading its own input, or locking anything on a repeater
     * would switch the repeater off - which is not what "route this line" means.
     */
    @Test
    @DisplayName("a lock keeps a diode's input alive but cuts everything else")
    void aLockDoesNotStarveADiode() {
        Slot repeater = new Slot(ComponentType.REPEATER);
        repeater.facing = Direction.WEST;
        repeater.setConnection(Direction.SOUTH, ConnectionState.ON);

        assertTrue(PowerSolver.readsFrom(repeater, Direction.WEST),
                "the input side is the component's own feed, not one of the routed lines");
        assertFalse(PowerSolver.readsFrom(repeater, Direction.NORTH),
                "every other side is cut, so nothing else can feed it");
        assertTrue(PowerSolver.emitsToward(repeater, Direction.SOUTH),
                "and the output goes where it was locked to");
        assertFalse(PowerSolver.emitsToward(repeater, Direction.EAST),
                "not out of the side it would have used on its own");
    }

    /** Clearing puts everything back, including the sides a lock had cut. */
    @Test
    @DisplayName("clearing a locked component restores automatic behaviour everywhere")
    void clearingUndoesTheWholeLock() {
        Slot slot = dust();
        slot.setConnection(Direction.EAST, ConnectionState.ON);
        assertTrue(slot.isClosed(Direction.NORTH));

        slot.clearConnections();

        assertFalse(slot.isRoutingLocked());
        for (Direction direction : Direction.values()) {
            assertEquals(ConnectionState.AUTO, WrenchLinks.stateOf(slot, direction));
            assertFalse(slot.isClosed(direction), direction + " is free again");
        }
    }

    @Test
    @DisplayName("clearing a component puts every side back to automatic")
    void clearingResetsEverySide() {
        Slot slot = dust();
        slot.setConnection(Direction.NORTH, ConnectionState.ON);
        slot.setConnection(Direction.SOUTH, ConnectionState.OFF);

        slot.clearConnections();

        for (Direction direction : Direction.values()) {
            assertEquals(ConnectionState.AUTO, WrenchLinks.stateOf(slot, direction));
            assertFalse(slot.isLocked(direction));
        }
    }

    @Test
    @DisplayName("describe names a diode's facing, since that is what makes it readable in chat")
    void describeMentionsFacing() {
        Slot repeater = new Slot(ComponentType.REPEATER);
        repeater.facing = Direction.WEST;
        assertEquals("REPEATER facing west", WrenchLinks.describe(repeater));
        assertEquals("DUST", WrenchLinks.describe(dust()));
    }
}

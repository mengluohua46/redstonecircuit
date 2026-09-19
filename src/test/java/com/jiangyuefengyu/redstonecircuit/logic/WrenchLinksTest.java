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
     * The design says a pin must survive a later placement next door, so the link is pinned at both
     * ends. One-sided pinning would leave the far end free to be re-routed by whatever is put beside
     * it, which is the exact thing the wrench exists to prevent.
     */
    @Test
    @DisplayName("a link is pinned at both ends, in opposite directions")
    void linkingPinsBothEnds() {
        Slot a = dust();
        Slot b = dust();

        // What WrenchLinks.link does to the data, done by hand here so the bookkeeping stays testable
        // without a ServerLevel.
        ConnectionState after = WrenchLinks.next(WrenchLinks.stateOf(a, Direction.EAST));
        a.setConnection(Direction.EAST, after);
        b.setConnection(Direction.WEST, after);

        assertEquals(ConnectionState.ON, after);
        assertTrue(a.forcedOn.contains(Direction.EAST), "the clicked end emits towards the neighbour");
        assertTrue(b.forcedOn.contains(Direction.WEST), "and the neighbour emits back");
        assertTrue(a.isConnected(Direction.EAST) && b.isConnected(Direction.WEST));
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

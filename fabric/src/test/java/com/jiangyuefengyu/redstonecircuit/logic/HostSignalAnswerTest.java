package com.jiangyuefengyu.redstonecircuit.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jiangyuefengyu.redstonecircuit.carrier.CarrierDirections;
import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.Slot;

import net.minecraft.core.Direction;

import org.joml.Quaterniond;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests the rule behind both halves of the host-signal bridge: what one host block answers to a vanilla signal
 * query.
 *
 * <p>{@link PowerSolver#signalAnswer} decides whether a machine's repeater drives the lamp it flies over, and the
 * mixin has to hand it a direction in the <em>component's own frame</em>, so both the rule and the conversion have
 * to be provable without a game. The last test states it the way a player would: turn the machine 180 degrees and
 * the same repeater answers the opposite world side.
 */
class HostSignalAnswerTest {

    private static Slot slot(ComponentType type) {
        Slot slot = new Slot(type);
        slot.power = 11;
        return slot;
    }

    @Test
    @DisplayName("dust answers weakly on every side, and never strongly")
    void dustIsAWeakSourceEverywhere() {
        Slot dust = slot(ComponentType.DUST);
        for (Direction direction : Direction.values()) {
            assertEquals(11, PowerSolver.signalAnswer(dust, direction, false, false, false),
                    "weak towards " + direction);
            assertEquals(0, PowerSolver.signalAnswer(dust, direction, true, false, false),
                    "dust gives no strong power - that is what stops a host block behaving like a "
                            + "redstone block");
        }
    }

    @Test
    @DisplayName("a repeater answers only the side it drives, weakly and strongly")
    void aRepeaterIsADiode() {
        Slot repeater = slot(ComponentType.REPEATER);
        repeater.facing = Direction.SOUTH; // reads from the south, drives north

        assertEquals(11, PowerSolver.signalAnswer(repeater, Direction.NORTH, false, false, false));
        assertEquals(11, PowerSolver.signalAnswer(repeater, Direction.NORTH, true, false, false),
                "a diode does charge the block in front of it");
        for (Direction other : new Direction[] {Direction.SOUTH, Direction.EAST, Direction.UP}) {
            assertEquals(0, PowerSolver.signalAnswer(repeater, other, false, false, false),
                    "and nothing towards " + other);
            assertEquals(0, PowerSolver.signalAnswer(repeater, other, true, false, false));
        }
    }

    @Test
    @DisplayName("a lever answers on every side, but only weakly")
    void aLeverIsAWeakSourceEverywhere() {
        Slot lever = slot(ComponentType.LEVER);
        for (Direction direction : Direction.values()) {
            assertEquals(11, PowerSolver.signalAnswer(lever, direction, false, false, false));
            assertEquals(0, PowerSolver.signalAnswer(lever, direction, true, false, false));
        }
    }

    @Test
    @DisplayName("the wrench can close a side outright, and a lock closes every other one")
    void theWrenchOverridesEverything() {
        Slot repeater = slot(ComponentType.REPEATER);
        repeater.facing = Direction.SOUTH;

        repeater.forcedOff.add(Direction.NORTH);
        assertEquals(0, PowerSolver.signalAnswer(repeater, Direction.NORTH, false, false, false),
                "a cut side carries nothing");

        repeater.forcedOff.clear();
        repeater.forcedOn.add(Direction.EAST);
        assertTrue(PowerSolver.signalAnswer(repeater, Direction.EAST, false, false, false) > 0,
                "a locked side is forced open, even though a repeater would not emit that way");
        assertEquals(0, PowerSolver.signalAnswer(repeater, Direction.NORTH, false, false, false),
                "and locking anywhere makes the routing explicit, so the rest is cut");
    }

    @Test
    @DisplayName("strong power is not conducted into a neighbour that holds a component")
    void strongPowerStopsAtAnotherHost() {
        Slot repeater = slot(ComponentType.REPEATER);
        repeater.facing = Direction.SOUTH;

        assertEquals(0, PowerSolver.signalAnswer(repeater, Direction.NORTH, true, true, false),
                "the component in front is what was fed, not the stone around it");
        assertEquals(11, PowerSolver.signalAnswer(repeater, Direction.NORTH, true, true, true),
                "and strict vanilla block semantics is the switch that changes that");
        assertEquals(11, PowerSolver.signalAnswer(repeater, Direction.NORTH, false, true, false),
                "the weak query is not affected - it is only ever seen by the block it is asked about");
    }

    @Test
    @DisplayName("nothing at all answers nothing")
    void anEmptyHostAnswersNothing() {
        assertEquals(0, PowerSolver.signalAnswer(null, Direction.NORTH, false, false, false));
        assertEquals(0, PowerSolver.signalAnswer(slot(ComponentType.DUST), null, false, false, false));
    }

    /** The same component and local side answering differently in the world because the structure has turned. */
    @Test
    @DisplayName("a turned machine answers the world side it now faces")
    void turningTheMachineMovesTheAnswer() {
        Slot repeater = slot(ComponentType.REPEATER);
        repeater.facing = Direction.SOUTH;

        Quaterniond halfTurn = new Quaterniond().rotateY(Math.PI);
        // A query from the world's south is the turned structure's own north, the side this repeater drives.
        Direction local = CarrierDirections.localDirection(halfTurn, Direction.SOUTH);
        assertEquals(Direction.NORTH, local);
        assertEquals(11, PowerSolver.signalAnswer(repeater, local, true, false, false));

        // And the query from the world's north now lands on the repeater's input side.
        assertFalse(PowerSolver.emitsToward(repeater,
                CarrierDirections.localDirection(halfTurn, Direction.NORTH)));
        assertEquals(0, PowerSolver.signalAnswer(repeater,
                CarrierDirections.localDirection(halfTurn, Direction.NORTH), false, false, false));
    }
}

package com.jiangyuefengyu.redstonecircuit.compat.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 方块放置器's two run-wide assembly rules, tested as rules rather than as lines.
 *
 * <p>One structure per server tick, and a run in which assembly can be switched off: both are state that outlives
 * a single placement, so both are checked here without a level, without Sable and without a game. What is pinned:
 * a second placement in the same tick is refused and the next tick lets it through; once assembly is switched off
 * the rationing stops (there is nothing left to ration, and deferring a placement for an assembly that will never
 * happen would be a lie); the first reason is the one that is kept, because it is the one that names the structure;
 * and the body verdict names every fact that is wrong with the structure it was given.
 */
class PlacerAssemblyGateTest {

    @BeforeEach
    void aFreshlyStartedServer() {
        PlacerAssemblyGate.forgetForTests();
    }

    @Test
    @DisplayName("一个服务器 tick 只放行一次组装，同一个 tick 的其余放置要等下一个 tick")
    void oneAssemblyPerServerTick() {
        assertTrue(PlacerAssemblyGate.claim(41), "the first placement of a tick gets the tick's one assembly");
        assertFalse(PlacerAssemblyGate.claim(41), "the second one of the same tick does not");
        assertFalse(PlacerAssemblyGate.claim(41), "and neither does the third");
        assertTrue(PlacerAssemblyGate.claim(42), "the next tick is a new budget, so the wait is one tick and no more");
        assertFalse(PlacerAssemblyGate.claim(42), "while the same tick is still spent");
    }

    @Test
    @DisplayName("停用之后不再节流（已经没有组装可等），而且第一条原因就是留下的那条")
    void switchingOffStopsTheRationingAndKeepsTheFirstReason() {
        assertTrue(PlacerAssemblyGate.assemblyAllowed(), "assembly starts allowed");

        assertTrue(PlacerAssemblyGate.switchOff("mass=0.0"), "the first structure with no body is the one that says why");
        assertFalse(PlacerAssemblyGate.switchOff("mass=0.0 again"), "and only that one writes the line");
        assertFalse(PlacerAssemblyGate.assemblyAllowed());
        assertEquals("mass=0.0", PlacerAssemblyGate.switchedOffReason());

        assertTrue(PlacerAssemblyGate.claim(9),
                "with nothing left to assemble there is nothing to ration, so placements are not held back: the "
                        + "machine keeps placing its blocks into the world");
        assertTrue(PlacerAssemblyGate.claim(9), "in the same tick as well");
    }

    @Test
    @DisplayName("判决：四项都好的结构没有问题，坏一项就点名一项，坏几项就都点名")
    void theVerdictNamesWhateverIsWrong() {
        assertNull(PlacerAssemblyGate.bodyProblem(3.5, true, false, true),
                "a structure with mass, a centre of mass, a plot and a readable pose is a body");

        assertTrue(PlacerAssemblyGate.bodyProblem(0.0, true, false, true).contains("mass=0.0"),
                "no mass is the fact the whole rule is about");
        assertTrue(PlacerAssemblyGate.bodyProblem(Double.NaN, true, false, true).contains("mass=NaN"),
                "a mass that is not a number is no better");
        assertTrue(PlacerAssemblyGate.bodyProblem(Double.POSITIVE_INFINITY, true, false, true).contains("mass="),
                "and neither is one that is infinite");

        assertEquals("centreOfMass=absent", PlacerAssemblyGate.bodyProblem(1.0, false, false, true),
                "the centre of mass is what Sable uploads, so its absence is reported by itself");
        assertEquals("plotBounds=empty", PlacerAssemblyGate.bodyProblem(1.0, true, true, true),
                "the plot's own box is the cheap early signal: it is empty exactly when nothing was carried in");
        assertEquals("pose=unreadable", PlacerAssemblyGate.bodyProblem(1.0, true, false, false),
                "and a pose that cannot be read is a structure nothing can be drawn or carried from");

        String all = PlacerAssemblyGate.bodyProblem(0.0, false, true, false);
        assertTrue(all.contains("mass=0.0") && all.contains("centreOfMass=absent")
                        && all.contains("plotBounds=empty") && all.contains("pose=unreadable"),
                "one line with every fact, so the log cannot lose the one that mattered");
    }

    @Test
    @DisplayName("两样状态都是本次运行的，可以被清掉，像刚启动的服务器")
    void theStateIsPerRunAndCanBeForgotten() {
        assertTrue(PlacerAssemblyGate.claim(7));
        assertFalse(PlacerAssemblyGate.claim(7));
        PlacerAssemblyGate.switchOff("why");

        PlacerAssemblyGate.forgetForTests();

        assertTrue(PlacerAssemblyGate.claim(7), "nothing of the previous run survives");
        assertTrue(PlacerAssemblyGate.assemblyAllowed());
        assertNull(PlacerAssemblyGate.switchedOffReason());
    }
}

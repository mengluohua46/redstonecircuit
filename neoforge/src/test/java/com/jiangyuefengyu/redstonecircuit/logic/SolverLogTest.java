package com.jiangyuefengyu.redstonecircuit.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>The solver's log, counted instead of printed - and the one warning that survives.</b>
 *
 * <h2>The measurement these tests encode</h2>
 * The author's P1.22 log was 52.5 MB, and 242,588 of its lines were four per-step solver lines
 * ({@code world change at …} 86,543; {@code output change at host …} 77,416; {@code recompute seed=…}
 * 31,045; {@code <TYPE> at … power n -> n-1 (supply=…)} 47,584) - about 400 lines a second for a
 * one-minute test. So there are two things to pin: that those four become <b>one counted line per
 * window</b>, and that "a host recomputed dozens of times inside one tick" becomes <b>visible</b>
 * instead of being averaged away by the counting.
 */
class SolverLogTest {

    private static final BlockPos HOST = new BlockPos(-79, -60, 16);
    private static final BlockPos OTHER = new BlockPos(20481030, 128, 20491272);

    @BeforeEach
    void reset() {
        SolverLog.clear();
    }

    @AfterEach
    void tearDown() {
        SolverLog.clear();
    }

    /**
     * Runs a whole summary window of level ticks and answers what the last one wrote.
     *
     * <p>The window is counted in ticks this code saw rather than against a level clock, so a test can
     * drive it without a level - and that counting is itself what makes "in the last N ticks" true.
     */
    private static List<String> endWindow() {
        List<String> last = List.of();
        for (int i = 1; i <= SolverLog.SUMMARY_WINDOW_TICKS; i++) {
            last = SolverLog.tickEnd(i, null);
        }
        return last;
    }

    // ------------------------------------------------------------------- the summary --

    @Test
    @DisplayName("the four per-step lines become one counted line per window")
    void theSummaryCountsInsteadOfPrinting() {
        SolverLog.change(HOST);
        SolverLog.change(HOST);
        SolverLog.change(OTHER);
        SolverLog.recompute(HOST);
        SolverLog.recompute(HOST);
        SolverLog.recompute(HOST);
        SolverLog.componentChanged();
        SolverLog.componentChanged();
        SolverLog.componentChanged();
        SolverLog.componentChanged();

        List<String> lines = endWindow();

        assertEquals(1, lines.size(), "ten events, one line: " + lines);
        String line = lines.get(0);
        assertTrue(line.contains("solver: 3 change(s) on 2 host(s)"), line);
        assertTrue(line.contains("3 recompute(s)"), line);
        assertTrue(line.contains("4 component change(s)"), line);
        assertTrue(line.contains("in the last " + SolverLog.SUMMARY_WINDOW_TICKS + " tick(s)"), line);
        assertTrue(line.contains("worst host=" + HOST.toShortString() + " at 3 recompute(s)"), line);
        assertTrue(line.contains("verboseSolverLog"),
                "the line says where the detail went, so nobody goes looking for it: " + line);
    }

    @Test
    @DisplayName("a window inside the summary period writes nothing at all")
    void aWindowInsideThePeriodWritesNothing() {
        SolverLog.change(HOST);
        SolverLog.recompute(HOST);
        for (int tick = 1; tick < SolverLog.SUMMARY_WINDOW_TICKS; tick++) {
            assertTrue(SolverLog.tickEnd(tick, null).isEmpty(), "tick " + tick + " is inside the window");
        }
        assertEquals(1, SolverLog.tickEnd(SolverLog.SUMMARY_WINDOW_TICKS, null).size(),
                "and at the end of it the totals are printed once");
    }

    @Test
    @DisplayName("a quiet window writes nothing, so an idle world is silent")
    void aQuietWindowWritesNothing() {
        assertTrue(endWindow().isEmpty());
        assertTrue(endWindow().isEmpty());
    }

    @Test
    @DisplayName("the counters reset with the window, so one window is not added to the next")
    void theWindowResetsAfterASummary() {
        SolverLog.change(HOST);
        String first = endWindow().get(0);
        assertTrue(first.contains("1 change(s)"), first);

        SolverLog.change(HOST);
        SolverLog.change(HOST);
        String second = endWindow().get(0);
        assertTrue(second.contains("2 change(s)"), "the window starts again: " + second);
    }

    // ------------------------------------------------------------------- the spin warning --

    @Test
    @DisplayName("a host recomputed past the threshold in one tick is named once, and only once")
    void theSpinWarningNamesTheWorstHostOnce() {
        int threshold = 8;
        for (int i = 0; i < threshold + 1; i++) {
            SolverLog.recompute(HOST);
        }

        List<String> lines = SolverLog.tickEnd(1L, null);
        assertEquals(1, lines.size(), "one warning, and no summary yet: " + lines);
        String warning = lines.get(0);
        assertTrue(warning.contains("solver spin: host " + HOST.toShortString()), warning);
        assertTrue(warning.contains("was recomputed " + (threshold + 1) + " time(s) in one tick"), warning);
        assertTrue(warning.contains("threshold=" + threshold), warning);
        assertTrue(warning.contains("re-fed by its own neighbour notifications"), warning);
        assertTrue(warning.contains("this summary is not"),
                "the warning is deliberately not behind verboseSolverLog: " + warning);

        // The next tick the host is quiet: no second warning, and no summary either (nothing counted).
        assertTrue(SolverLog.tickEnd(2L, null).isEmpty());
    }

    @Test
    @DisplayName("a host merely recomputed a few times per tick is not called a spin")
    void aBusyHostIsNotCalledASpin() {
        for (int i = 0; i < 8; i++) {
            SolverLog.recompute(HOST);
        }
        assertTrue(SolverLog.tickEnd(1L, null).isEmpty(),
                "the threshold is 'more than', not 'at least'");
    }

    @Test
    @DisplayName("the warning is capped per tick, and says how many hosts it did not name")
    void theSpinWarningsAreCapped() {
        int hosts = SolverLog.MAX_SPIN_WARNINGS_PER_TICK * 3;
        for (int i = 0; i < hosts; i++) {
            for (int j = 0; j < 9; j++) {
                SolverLog.recompute(new BlockPos(i, 0, 0));
            }
        }
        List<String> lines = SolverLog.tickEnd(1L, null);
        assertEquals(SolverLog.MAX_SPIN_WARNINGS_PER_TICK + 1, lines.size(),
                "a bounded number of warnings plus one line saying how many were left out: " + lines);
        assertTrue(lines.get(lines.size() - 1).contains("more host(s) were recomputed more than"),
                lines.get(lines.size() - 1));
    }

    @Test
    @DisplayName("the counter maps are bounded, and the pooled recomputes are still counted")
    void theCountersAreBounded() {
        for (int i = 0; i < SolverLog.MAX_TRACKED_HOSTS * 3; i++) {
            SolverLog.recompute(new BlockPos(i, 0, 0));
        }
        assertTrue(SolverLog.trackedHosts() <= SolverLog.MAX_TRACKED_HOSTS,
                "the map cannot grow with the world: " + SolverLog.trackedHosts());

        String summary = endWindow().get(0);
        assertTrue(summary.contains("recompute(s) were on hosts beyond the first "
                + SolverLog.MAX_TRACKED_HOSTS + " counted"),
                "and what was not tracked is pooled into a number rather than lost: " + summary);
    }

    // ------------------------------------------------------------------- the switches --

    @Test
    @DisplayName("the per-step detail is off by default; the mode needs both switches")
    void thePerStepDetailIsOffByDefault() {
        // RCConfig falls back to `false` for an unloaded config, which is what these tests have - and that
        // fallback is the documented default, so this assertion is about the shipped default too.
        assertFalse(SolverLog.verbose(),
                "the default must be the summary: this is the one place in the mod that can print hundreds "
                        + "of lines a second");
    }

    @Test
    @DisplayName("several loaded dimensions still end one game tick once")
    void oneFlushPerGameTick() {
        // The tick handler runs for every loaded level, and a server ticks all of them with the same game
        // time. Ending the tick per level would split one tick's recomputes three ways - and the spin
        // threshold is stated per tick - so the first level to tick a game time ends it and the rest are
        // no-ops.
        SolverLog.clear();
        assertTrue(SolverLog.claimTick(100L), "the overworld ends tick 100");
        assertFalse(SolverLog.claimTick(100L), "the nether's event for the same tick does not end it again");
        assertFalse(SolverLog.claimTick(100L), "nor does the end's");
        assertTrue(SolverLog.claimTick(101L), "and the next tick ends normally");

        SolverLog.clear();
        assertTrue(SolverLog.claimTick(100L), "a reload starts over rather than staying silent");
    }

    @Test
    @DisplayName("clear forgets the counters and the window clock")
    void clearForgetsEverything() {
        SolverLog.recompute(HOST);
        SolverLog.change(HOST);
        SolverLog.clear();
        assertTrue(endWindow().isEmpty(), "nothing is left to report");
        assertEquals(0, SolverLog.trackedHosts());
    }
}

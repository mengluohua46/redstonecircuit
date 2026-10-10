package com.jiangyuefengyu.redstonecircuit.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jiangyuefengyu.redstonecircuit.RCConfig;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierDrawCheck;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The log's <em>frequency</em>, not its line length: one parked machine must not produce tens of thousands
 * of identical lines. The lines that dominated a session were all cadence - a frame summary throttled in
 * <em>frames</em> and a matrix probe and draw check that printed whenever their watched value moved.
 * <p>Asserted: one summary per window with the counting in ticks, a heartbeat minutes apart that still
 * exists, alarms never throttled out, and a verbose switch that is off by default; game time is an argument,
 * so all of it runs on plain longs - no game, no clock, no sleeping.
 */
class CarrierRenderLogFrequencyTest {

    private static final ResourceKey<Level> DIMENSION =
            ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
                    ResourceLocation.parse("redstonecircuit:log_frequency_test"));

    /** The ceiling the three client lines share, read from the production constant. */
    private static final int WINDOW = CarrierRenderTrace.QUIET_LOG_INTERVAL_TICKS;

    /** The settled heartbeat's period, read from the production constant. */
    private static final int HEARTBEAT = CarrierRenderTrace.SETTLED_HEARTBEAT_TICKS;

    /** One worked draw-check example, so the "there is something to say" branch is the one taken. */
    private static CarrierDrawCheck example() {
        return new CarrierDrawCheck(null, new BlockPos(1, 2, 3), null, null, -1L);
    }

    @BeforeEach
    void reset() {
        CarrierRenderTrace.resetLogThrottleForTest();
    }

    /**
     * Puts the recorded geometry back to "nothing at all was emitted", then forgets the throttle: the
     * render record is process-lifetime, so a test about the settled state must say what it starts from. */
    private static void settledGeometry() {
        CarrierRenderTrace.geometry(DIMENSION,
                CarrierGeometry.Emitted.of(0, 0, 0, 0, 0, "nothing carried", "nothing carried"),
                "gates", null);
        CarrierRenderTrace.resetLogThrottleForTest();
    }

    /** One frame of "nothing is carried and nothing is happening". */
    private static boolean quietFrame(long tick) {
        return CarrierRenderTrace.frameSummary(DIMENSION, 0, 0, 0, 0, 0, 0, 0, -1L, tick);
    }

    /** One frame of a moving machine: a host is carried and drawn, and the counters move every frame. */
    private static boolean movingFrame(long tick) {
        CarrierRenderTrace.geometry(DIMENSION,
                CarrierGeometry.Emitted.of(30, 24, 1, 1, 1, "shell", "content"), "gates", "host detail");
        // The drawn count alternates, which is what a machine in flight looks like to this counter: the
        // hosts in range come and go as it moves.
        int drawn = 1 + (int) (tick % 2);
        // A constant pose age: an age that walked past the warn threshold would fire an alarm of its own.
        return CarrierRenderTrace.frameSummary(DIMENSION, drawn, 0, 0, 0, 0, 0, 0, 5L, tick);
    }

    // ------------------------------------------------------------ the summary --

    /** The headline number: twenty seconds of frames at 60 fps are one line, not 1200 - and the heartbeat
     * still arrives, on its own much longer period, so the diagnostic can report its own absence. */
    @Test
    @DisplayName("a settled picture is written once per heartbeat, not once per frame or per second")
    void aSettledPictureIsWrittenOnTheHeartbeat() {
        settledGeometry();
        // The first frame of a session always reports itself: "the pass ran" has to be visible.
        assertTrue(quietFrame(0L), "the first frame of a session must report itself");

        // The heartbeat is two minutes of ticks, and this loop is bounded BY it: the claim is that the
        // settled picture says nothing at all until then.
        int written = 0;
        for (long tick = 1; tick < HEARTBEAT; tick++) {
            if (quietFrame(tick)) {
                written++;
            }
        }
        assertEquals(0, written, "a settled picture must not repeat while the heartbeat is not due: "
                + written + " line(s) in " + (HEARTBEAT - 1) + " ticks - a repeat here is the flood of"
                + " identical lines this rule exists to prevent");

        // ...and it does come back, exactly on the heartbeat.
        assertTrue(quietFrame(HEARTBEAT), "the heartbeat is due at " + HEARTBEAT + " ticks");
        long afterHeartbeat = 0;
        for (long tick = HEARTBEAT + 1; tick < HEARTBEAT * 2; tick++) {
            if (quietFrame(tick)) {
                afterHeartbeat++;
            }
        }
        assertEquals(0, afterHeartbeat, "and then it is quiet again for another whole period");
        assertTrue(quietFrame(HEARTBEAT * 2L), "the next heartbeat is due");
    }

    /** A state change is worth a line - but the counters change on <em>every</em> frame while a machine
     * moves, so without the window that is sixty lines a second. Ten windows, ten lines. */
    @Test
    @DisplayName("a moving machine writes one summary per window, not one per frame")
    void aMovingMachineWritesOneSummaryPerWindow() {
        // The first frame of a session reports itself, whatever the window says, so it is asserted here
        // and the loop below counts only the windows that follow it.
        assertTrue(movingFrame(0L), "the first frame of a session reports itself");
        int written = 0;
        for (long tick = 1; tick < WINDOW * 10L; tick++) {
            if (movingFrame(tick)) {
                written++;
            }
        }
        assertEquals(9, written,
                "nine further windows of a changing picture are nine further lines, so the ten windows"
                        + " from tick 0 to 999 are exactly ten lines, not one per frame: " + written);
    }

    /** A pass that is switched off reports itself, because silence cannot be read: "the overlay is off"
     * and "the overlay is on and nothing reaches the screen" would otherwise be the same log, and the log
     * could not answer the question being asked. Same throttle, same clock, one line saying which it was. */
    @Test
    @DisplayName("a pass that is not active reports itself once, then on its heartbeat")
    void anInactivePassStillReportsItself() {
        String gates = "overlayMode=GOGGLES goggles=false active=false -> hostOverlayMode=GOGGLES and the "
                + "player is NOT wearing the redstone goggles";
        assertTrue(CarrierRenderTrace.frameSummaryInactive(DIMENSION, gates, 0L),
                "the first frame of a session must say the pass is off, or a session that draws nothing "
                        + "has no frame summary in it at all");
        for (long tick = 1; tick < HEARTBEAT; tick++) {
            assertFalse(CarrierRenderTrace.frameSummaryInactive(DIMENSION, gates, tick),
                    "and it must not repeat before the heartbeat: tick " + tick);
        }
        assertTrue(CarrierRenderTrace.frameSummaryInactive(DIMENSION, gates, HEARTBEAT),
                "the heartbeat brings it back, so a whole hour of a closed pass is a handful of lines");
    }

    /** An alarm is a finding, not cadence: it prints the moment it happens, whatever the window says. */
    @Test
    @DisplayName("an alarm is written immediately even inside a window")
    void anAlarmIsNeverThrottledOut() {
        settledGeometry();
        assertTrue(quietFrame(0L), "the first frame reports itself");
        assertFalse(quietFrame(1L), "an unchanged frame one tick later does not");

        // A landed host the world path could not draw: an alarm, and it must not wait for the window.
        assertTrue(CarrierRenderTrace.frameSummary(DIMENSION, 1, 0, 0, 0, 0, 1, 0, -1L, 2L),
                "landedDeferred>0 is a finding and prints at once, " + (WINDOW - 2) + " ticks early");
    }

    // ----------------------------------------------- the other two client lines --

    /** The matrix probe, printed on a change and at most once per window. */
    @Test
    @DisplayName("the matrix probe is written on a change, at most once per window")
    void theMatrixProbeIsThrottledToTheWindow() {
        assertTrue(CarrierRenderTrace.matrixProbe(DIMENSION, "matrixProbe[shell] matrixDelta=0.0001", 0L),
                "the first probe of a session reports itself");

        // A different matrix every frame for ten windows: one line per window, not 200.
        int written = 0;
        for (long tick = 1; tick < WINDOW * 10L; tick++) {
            if (CarrierRenderTrace.matrixProbe(DIMENSION, "matrixProbe[shell] matrixDelta=" + tick,
                    tick)) {
                written++;
            }
        }
        assertEquals(9, written,
                "a moving matrix is reported once per window, not once per frame: " + written);
    }

    /** An unchanged matrix is never repeated, whatever the window says. */
    @Test
    @DisplayName("an unchanged matrix probe is not repeated even after the window")
    void anUnchangedMatrixProbeIsNeverRepeated() {
        assertTrue(CarrierRenderTrace.matrixProbe(DIMENSION, "matrixProbe[shell] matrixDelta=0.0000", 0L));
        for (long tick = WINDOW; tick < WINDOW * 20L; tick++) {
            assertFalse(CarrierRenderTrace.matrixProbe(DIMENSION,
                            "matrixProbe[shell] matrixDelta=0.0000", tick),
                    "the same matrix says the same thing, so it is not written again");
        }
    }

    /** The draw check: the same ceiling, and the same "a change is what is worth a line" rule. */
    @Test
    @DisplayName("the draw check is written on a change, at most once per window")
    void theDrawCheckIsThrottledToTheWindow() {
        int written = 0;
        for (long tick = 0; tick < WINDOW * 10L; tick++) {
            // A different count every frame: the "something was drawn somewhere else" shape changing
            // constantly, which is what a moving machine produces.
            if (CarrierRenderTrace.drawCheck(DIMENSION, example(), (int) (tick % 3), 0, -1L, tick)) {
                written++;
            }
        }
        assertEquals(10, written, "a changing draw check is written once per window: " + written);
    }

    /** A pose that has just gone stale is the one transition this line exists for: it is never throttled. */
    @Test
    @DisplayName("a draw check whose pose just went stale prints immediately")
    void aStalePosePrintsImmediately() {
        // A healthy frame first, then a stale one one tick later - well inside the window.
        long staleAge = CarrierDrawCheck.POSE_AGE_WARN_TICKS + 1;
        assertTrue(CarrierRenderTrace.drawCheck(DIMENSION, example(), 0, 0, 0L, 0L),
                "the first draw check reports itself");
        assertFalse(CarrierRenderTrace.drawCheck(DIMENSION, example(), 0, 0, 0L, 1L),
                "the same counts one tick later are silent");
        assertTrue(CarrierRenderTrace.drawCheck(DIMENSION, example(), 0, 0, staleAge, 2L),
                "but a pose that just went stale is a finding and prints at once");
    }

    // -------------------------------------------------------------- the switch --

    /** The switch: one key restores every frame, and changes nothing else. The config file is not loaded
     * here, so the switch answers its own documented default - which must be off - and with it on every
     * loop above would produce one line per frame, i.e. the previous behaviour exactly. */
    @Test
    @DisplayName("verboseRenderLog is off by default, and is the one key that brings the frames back")
    void theVerboseSwitchDefaultsOffAndIsTheOneKey() {
        assertFalse(RCConfig.verboseRenderLog(),
                "the default must be OFF: with it on, one parked machine is 32 MB of log again");

        // The two numbers the size claim rests on: a five-second ceiling and a two-minute heartbeat.
        assertEquals(100, WINDOW, "the shared ceiling is five seconds of ticks");
        assertEquals(2400, HEARTBEAT, "the settled heartbeat is two minutes of ticks");

        // A caller with no clock keeps the frame-counted behaviour, so the legacy overloads are untouched.
        settledGeometry();
        int legacy = 0;
        for (long tick = 0; tick < CarrierRenderTrace.FRAME_LOG_INTERVAL * 3L; tick++) {
            if (CarrierRenderTrace.frameSummary(DIMENSION, 0, 0, 0, 0, 0, 0, 0, -1L)) {
                legacy++;
            }
        }
        assertTrue(legacy >= 2,
                "the no-clock form still heartbeats on the frame window it always used: " + legacy);
    }

    // ------------------------------------------------------- the size claim --

    /** The size claim, as a worked projection over the session shape that produced the flood: 38 seconds
     * of a settled picture whose recorded geometry still oscillates from frame to frame - which is what
     * made the old rule print on every frame instead of on every window, and which the window never got a
     * say in - then 51 seconds of a machine in flight with its host counts moving.
     *
     * <p>The projection replays that shape through the rule and counts what would be written, so a report
     * can quote a measurement rather than an estimate. The ceiling is one line per window per line kind:
     * four kinds over 89 seconds is at most four times eighty-nine lines.
     */
    @Test
    @DisplayName("a session of that shape, projected through the rule, is under a megabyte")
    void theAuthorsSessionShapeIsUnderAMegabyte() {
        final int tps = 20;
        final int settledSeconds = 38;
        final int movingSeconds = 51;
        final int sessionSeconds = settledSeconds + movingSeconds;

        // Phase 1: nothing carried, nothing drawn - but the recorded geometry oscillates, which is what
        // made the old rule print on every frame instead of on every window.
        int settledLines = 0;
        for (int tick = 0; tick < settledSeconds * tps; tick++) {
            boolean odd = (tick & 1) == 0;
            CarrierRenderTrace.geometry(DIMENSION, odd
                            ? CarrierGeometry.Emitted.of(30, 24, 0, 0, 0, "shell", "content")
                            : CarrierGeometry.Emitted.of(0, 0, 0, 0, 0, "none", "none"),
                    "gates", null);
            if (CarrierRenderTrace.frameSummary(DIMENSION, 0, 0, 0, 0, 0, 0, 0, -1L, tick)) {
                settledLines++;
            }
        }

        // Phase 2: a machine in flight, drawn, with the host counts moving as it travels.
        int movingLines = 0;
        int matrixLines = 0;
        int drawCheckLines = 0;
        for (int tick = settledSeconds * tps; tick < sessionSeconds * tps; tick++) {
            CarrierRenderTrace.geometry(DIMENSION,
                    CarrierGeometry.Emitted.of(30, 24, 1, 1, 1, "shell", "content"), "gates", null);
            if (CarrierRenderTrace.frameSummary(DIMENSION, 1 + (tick & 1), 0, 0, 0, 0, 0, 0, tick, tick)) {
                movingLines++;
            }
            if (CarrierRenderTrace.matrixProbe(DIMENSION, "matrixProbe[shell] matrixDelta=" + tick, tick)) {
                matrixLines++;
            }
            if (CarrierRenderTrace.drawCheck(DIMENSION, example(), tick & 3, 0, -1L, tick)) {
                drawCheckLines++;
            }
        }

        int total = settledLines + movingLines + matrixLines + drawCheckLines;
        long bytes = (long) settledLines * 1258 + (long) movingLines * 1330
                + (long) matrixLines * 1290 + (long) drawCheckLines * 620;
        System.out.println("[redstonecircuit] log frequency: " + sessionSeconds + " s session -> marked"
                + " settled=" + settledLines + " moving=" + movingLines + " matrixProbe=" + matrixLines
                + " drawCheck=" + drawCheckLines + " total=" + total + " lines, ~" + (bytes / 1024)
                + " KiB (against 26,668 + 1,900 + 2,289 + 1,317 = 32,174 lines for the same shape)");

        // The ceiling is one line per window per line-kind: four kinds over 89 seconds is at most 4x89.
        int ceiling = sessionSeconds * tps / WINDOW;
        assertTrue(settledLines <= ceiling,
                "the settled summary is bounded by the window: " + settledLines + " > " + ceiling);
        assertTrue(movingLines <= ceiling,
                "and so is the moving one: " + movingLines + " > " + ceiling);
        assertTrue(matrixLines <= ceiling,
                "and the matrix probe: " + matrixLines + " > " + ceiling);
        assertTrue(drawCheckLines <= ceiling,
                "and the draw check: " + drawCheckLines + " > " + ceiling);
        assertTrue(bytes < 1024 * 1024,
                "the whole of it has to fit in a megabyte: " + (bytes / 1024) + " KiB");
        // And it is not merely "small": it is two orders of magnitude smaller than the flood it replaces.
        assertTrue(total * 100 < 32174, "the reduction has to be large: " + total + " vs 32,174 in that flood");
    }
}

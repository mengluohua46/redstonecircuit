package com.jiangyuefengyu.redstonecircuit.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.List;
import java.util.UUID;

import com.jiangyuefengyu.redstonecircuit.RCConfig;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierHostCheck;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierId;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The B1 and B2 diagnostics' <em>timing</em> and <em>wording</em>, driven with plain longs.
 *
 * <p>Every cadence assertion goes through {@link CarrierSightLog}, whose rules are the pure functions of
 * {@link CarrierSightThrottle}, and each is stated as a sequence of {@code due} answers over a named range of
 * ticks: a window opens when the elapsed time reaches it, not on a tick multiple, and the first frame of a
 * session is always due.
 *
 * <p>The lines themselves are asserted by <b>which keys they carry</b> and the values those keys may take,
 * never character for character, so a reworded sentence or a reordered field cannot turn this red while the
 * information a reader greps for is still there - and a field that disappears must.
 */
class CarrierSightLogTest {

    /** A carrier of each kind, so the {@code c:} / {@code s:} spelling is exercised. */
    private static final CarrierId CONTRAPTION = CarrierId.contraption(UUID.randomUUID());
    private static final CarrierId SUB_LEVEL = CarrierId.subLevel(UUID.randomUUID());

    @BeforeEach
    void reset() {
        CarrierSightLog.resetForTest();
    }

    // ------------------------------------------------------------- the gate --

    /**
     * The headline bound, as a sequence: an unchanged settlement answers false on every tick from 1 to 2399
     * and true at 2400.
     *
     * <p>Asserted with one statement per tick rather than by counting, so a failure names the tick that
     * disagreed.
     */
    @Test
    @DisplayName("an unchanged settlement is due at the first frame and then not until 2400")
    void anUnchangedSettlementIsVisitedOnce() {
        ClientHostCache.CarrierSweep parked = sweep(1, 1, 0, 0);
        // The first call names the SAME settlement the loop then compares against; passing `null` here would
        // make the very next visit a change, which is the branch this test is not about.
        assertTrue(CarrierSightLog.sweepDue(0L, parked, 1), "the first frame of a session always reports");
        for (long tick = 1L; tick < CarrierSightLog.SWEEP_HEARTBEAT_TICKS; tick++) {
            assertFalse(CarrierSightLog.sweepDue(tick, parked, 1),
                    "an unchanged settlement must not be visited again at tick " + tick);
        }
        assertTrue(CarrierSightLog.sweepDue(CarrierSightLog.SWEEP_HEARTBEAT_TICKS, parked, 1),
                "and the heartbeat brings it back, because a diagnostic must be able to report its own"
                        + " absence");
        assertFalse(CarrierSightLog.sweepDue(CarrierSightLog.SWEEP_HEARTBEAT_TICKS + 1L, parked, 1),
                "then it is quiet again");
    }

    /**
     * A changed settlement is visited at once, and then floored: a count that changes frame after frame - a
     * machine crossing the walk's radius, a player walking around it - is one event per floor, not one line
     * per frame.
     */
    @Test
    @DisplayName("a changed settlement is visited at once, and then floored")
    void aChangedSettlementIsFloored() {
        ClientHostCache.CarrierSweep parked = sweep(1, 1, 0, 0);
        assertTrue(CarrierSightLog.sweepDue(0L, null, 1), "the first visit");
        assertTrue(CarrierSightLog.sweepDue(CarrierSightLog.SETTLEMENT_MIN_INTERVAL_TICKS, parked, 2),
                "one more carrier is a change, and the floor has passed");
        long floor = CarrierSightLog.SETTLEMENT_MIN_INTERVAL_TICKS;
        for (long tick = floor + 1; tick < floor * 2; tick++) {
            assertFalse(CarrierSightLog.sweepDue(tick, parked, 2),
                    "a change inside the floor must not be visited again at tick " + tick);
        }
    }

    /** Both lines are gated on the mod's ordinary debug switch, which defaults on. */
    @Test
    @DisplayName("the switches default to the values these lines need")
    void theSwitchesDefaultTheRightWay() {
        assertTrue(RCConfig.debugLog(),
                "debugLog must default ON: these two lines have to be visible without a switch");
        assertFalse(RCConfig.verboseRenderLog(),
                "verboseRenderLog must default OFF: these two lines do not depend on it");
        assertTrue(CarrierSightLog.sweepDue(0L, null, 1));
        assertTrue(CarrierSightLog.sightDue(0L));
    }

    /**
     * The B2 window, as a sequence of ten: the first frame is due, and after that a window start every 100
     * ticks. The list of due ticks is asserted rather than their count, so "ten" cannot be an accident of a
     * loop bound.
     */
    @Test
    @DisplayName("the B2 line is due at 0, 100, ... 900 and nowhere else in 1,000 ticks")
    void theSightLineIsDueOncePerWindow() {
        assertTrue(CarrierSightLog.sightDue(0L), "the first frame of a session is due");
        StringBuilder dueAt = new StringBuilder("0");
        int due = 1;
        for (long tick = 1L; tick < CarrierSightLog.SIGHT_INTERVAL_TICKS * 10L; tick++) {
            if (CarrierSightLog.sightDue(tick)) {
                due++;
                dueAt.append(", ").append(tick);
            }
        }
        assertEquals(10, due, "ten windows are ten due answers: " + dueAt);
        assertEquals("0, 100, 200, 300, 400, 500, 600, 700, 800, 900", dueAt.toString(),
                "and they are the ten window starts");
    }

    // ---------------------------------------------------------- the writer --

    /**
     * A settlement that has not changed writes one line, not one per visit.
     *
     * <p>The gate may let a visit through for its own reasons - the heartbeat - and the writer is what keeps an
     * unchanged picture quiet. The two memories, "what the last look saw" and "what was last written", are
     * different fields.
     */
    @Test
    @DisplayName("an unchanged settlement writes one line, not one per visit")
    void anUnchangedSettlementWritesOnce() {
        ClientHostCache.CarrierSweep parked = sweep(1, 1, 0, 0);
        BlockPos camera = new BlockPos(0, 64, 0);
        assertEquals(1, CarrierSightLog.writeSweep("dim", parked, 0, 0, 0L, camera,
                CarrierSightLog.SWEEP_HEARTBEAT_TICKS));
        for (long tick = 1; tick < CarrierSightLog.SWEEP_HEARTBEAT_TICKS; tick++) {
            assertEquals(0, CarrierSightLog.writeSweep("dim", parked, 0, 0, tick, camera,
                            CarrierSightLog.SWEEP_HEARTBEAT_TICKS),
                    "an unchanged settlement must not be re-written at tick " + tick);
        }
        assertEquals(1, CarrierSightLog.writeSweep("dim", parked, 0, 0,
                CarrierSightLog.SWEEP_HEARTBEAT_TICKS, camera, CarrierSightLog.SWEEP_HEARTBEAT_TICKS),
                "the heartbeat is due, because a diagnostic must be able to report its own absence");
        // The two memories answer different questions: the gate compares against what was last seen, not
        // against what was last written.
        assertEquals(CarrierSightLog.settlementOf(parked, 1), CarrierSightLog.lastSweepSeen(),
                "the last visit's memory is what the change rule compares against");
        assertNotNull(CarrierSightLog.lastSweepSignature(), "and the writer remembers its own line");
    }

    /**
     * The B2 line is written once per window, and an identical one is never repeated whatever the window says.
     *
     * <p>Two rules, both needed: the window bounds a <em>changing</em> line, and the equality test stops a
     * <em>constant</em> one - the component that has not moved, which is the ordinary case while a player
     * stares at a machine.
     */
    @Test
    @DisplayName("the B2 line is written once per window, and never twice with the same text")
    void theSightLineIsWrittenOncePerWindow() {
        String line = "carrier=c:1 local=(1, 0, 0) drawnWorld=(2, 3, 4)";
        assertTrue(CarrierSightLog.sightDue(0L), "the first frame of a session is due");
        assertTrue(CarrierSightLog.writeSight(line, true), "and it writes");
        assertFalse(CarrierSightLog.writeSight(line, true),
                "the same text is never written twice, even in the same window");
        assertEquals(line, CarrierSightLog.lastSightLine(), "the memory is the exact line");

        // A different line every tick: nine more windows here, which with the first write are the ten the
        // sequence test above asserts.
        int written = 0;
        for (long tick = 1; tick < CarrierSightLog.SIGHT_INTERVAL_TICKS * 10L; tick++) {
            String changed = "carrier=c:1 local=" + tick;
            if (CarrierSightLog.writeSight(changed, CarrierSightLog.sightDue(tick))) {
                written++;
            }
        }
        assertEquals(9, written,
                "the windows after the first are nine, so a thousand ticks are ten lines, not eleven: "
                        + written);
    }

    /**
     * A settlement, so the gate can be driven with five numbers and no client index: not empty, so the
     * "unchanged" branch is the one under test, and not degenerate, so a change is a change in one number.
     */
    private static ClientHostCache.CarrierSweep sweep(int carriers, int collected, int onlySkipped,
                                                      int noPose) {
        return new ClientHostCache.CarrierSweep(true, carriers, collected, onlySkipped, noPose,
                List.of());
    }

    // ------------------------------------------------------------- the lines --

    /** One carried host, as the B2 line describes it. */
    private static CarrierHostCheck host(CarrierId carrier, CarrierHostCheck.Sight sight,
                                         BlockPos drawnWorld, BlockPos hit) {
        return new CarrierHostCheck(carrier, "(8, 4, -3)", "(9, 4, 3)", new BlockPos(9, 64, 3),
                sight == CarrierHostCheck.Sight.DRAWN, new BlockPos(1, 0, 0), drawnWorld,
                new BlockPos(2773, 78, -1390), sight, hit, 1, 1, 3L, new BlockPos(2775, 77, -1395));
    }

    /**
     * B1's per-carrier line: the field names, and the two sections the line is read for.
     *
     * <p>The <b>sections</b> - pose beside the camera, origin seven chunks away - and the <b>reason</b> have to
     * be readable from this one line, worded so "the origin bucket is out of radius" cannot be confused with
     * "there was no pose" or "the pose was stale". The numbers are section coordinates (block &gt;&gt; 4),
     * which makes "is the pose in the camera's own section" a comparison a reader can do by eye.
     */
    @Test
    @DisplayName("the B1 line names every field the acceptance procedure looks for")
    void theOriginLineReads() {
        CarrierHostCheck check = new CarrierHostCheck(SUB_LEVEL, "(1, -5, 12)", "(1, -5, 5)",
                new BlockPos(1, -80, 5), false, new BlockPos(20481032, 127, 20481032),
                new BlockPos(40, -72, 90), new BlockPos(33, -75, 85), null, null, 0, 1, 4L,
                new BlockPos(20481030, -79, 87));
        String line = check.describe();

        for (String field : new String[] {
                "carrier=", "originSection=", "poseSection=", "cameraSection=", "collected=",
                "hosts=", "poseAge=", "firstHostProjected=", "origin=" }) {
            assertTrue(line.contains(field), "the B1 line lost '" + field + "': " + line);
        }
        assertTrue(line.startsWith("carrier=" + SUB_LEVEL.shortId()), line);
        assertTrue(line.contains("originSection=(1, -5, 12)"), line);
        assertTrue(line.contains("poseSection=(1, -5, 5)"), line);
        assertTrue(line.contains("cameraSection=(0, -5, 0)"), line);
        assertTrue(line.contains("collected=no(origin-bucket-outside-radius)"), line);
        assertTrue(line.contains("hosts=0/1"), line);
        assertTrue(line.contains("poseAge=4t"), line);
        // The projection and the origin: the evidence that the pose the client holds is good and the host was
        // never offered to the renderer.
        assertTrue(line.contains("firstHostProjected=(40, -72, 90)"), line);
        assertTrue(line.contains("origin=(20481030, -79, 87)"), line);
    }

    /** No pose at all is a different reason, and the line says so rather than claiming a radius fault. */
    @Test
    @DisplayName("a carrier with no pose is not reported as a radius fault")
    void noPoseIsItsOwnReason() {
        CarrierHostCheck check = new CarrierHostCheck(CONTRAPTION, "(1, 4, 12)", "none",
                new BlockPos(1, 64, 5), false, new BlockPos(1, 4, 12), null, new BlockPos(1, 66, 5),
                null, null, 0, 2, -1L, new BlockPos(1, 4, 12));
        String line = check.describe();
        assertTrue(line.contains("poseSection=none"), line);
        assertTrue(line.contains("collected=no(no-pose)"), line);
        assertTrue(line.contains("poseAge=?"), line);
        assertFalse(line.contains("origin-bucket-outside-radius"),
                "a pose-less carrier must not be blamed on the radius: " + line);
    }

    /**
     * B2's line: the field names, and the set of values {@code ray=} may take.
     *
     * <p>Three shapes have to be told apart, each asserted by its fields rather than by its sentence: no target
     * at all, a ray that hit something else (with the hit's own coordinates, so they can be compared against
     * {@code target=}), and a ray that hit nothing. Each of the eight field names is asserted to appear <b>at
     * most once</b>, the same invariant the frame row is held to: a field written twice is a line a reader
     * cannot count from.
     */
    @Test
    @DisplayName("the B2 line distinguishes unknown target, refused hit and miss, each field once")
    void theSightLineReads() {
        String unknown = host(CONTRAPTION, CarrierHostCheck.Sight.UNKNOWN_TARGET, null, null)
                .describeSightWithTarget();
        assertTrue(unknown.contains("drawnWorld=unknown"), unknown);
        assertTrue(unknown.contains("ray=unknown-target"), unknown);
        assertTrue(unknown.contains("sight=unknown-target"), unknown);
        assertTrue(unknown.contains("target=unknown"), unknown);
        assertTrue(unknown.contains("eye=(2773, 78, -1390)"), unknown);
        assertTrue(unknown.contains("local=(1, 0, 0)"), unknown);

        String hit = host(CONTRAPTION, CarrierHostCheck.Sight.REFUSED_HIT,
                new BlockPos(2776, 77, -1394), new BlockPos(2776, 78, -1394)).describeSightWithTarget();
        assertTrue(hit.contains("drawnWorld=(2776, 77, -1394)"), hit);
        assertTrue(hit.contains("ray=hit(2776, 78, -1394)"), hit);
        assertTrue(hit.contains("sight=refused-hit"), hit);
        assertTrue(hit.contains("target=(2776, 77, -1394)"), hit);

        String miss = host(CONTRAPTION, CarrierHostCheck.Sight.MISS, new BlockPos(2776, 77, -1394),
                null).describeSightWithTarget();
        assertTrue(miss.contains("ray=miss"), miss);
        assertTrue(miss.contains("sight=miss"), miss);

        // The plot-coordinate shape: the projection produced a coordinate nobody can see, which is a
        // different fault from "no pose at all" and has to read differently.
        String plot = host(CONTRAPTION, CarrierHostCheck.Sight.MISS,
                new BlockPos(20481032, 127, 20481032), null).describeSightWithTarget();
        assertTrue(plot.contains("drawnWorld=(20481032, 127, 20481032)"), plot);

        // Every field name at most once, on each of the four lines.
        for (String line : new String[] { unknown, hit, miss, plot }) {
            for (String field : new String[] {
                    "local=", "drawnWorld=", "eye=", "ray=", "sight=", "target=" }) {
                assertEquals(1, occurrences(line, field),
                        "'" + field + "' is written once per line: " + line);
            }
        }
        // The value of `ray=` is always one of the three shapes, never a sentence.
        for (String line : new String[] { unknown, hit, miss, plot }) {
            assertTrue(isOneOfTheRayShapes(valueOf(line, "ray=")),
                    "ray= is miss, hit(...) or unknown-target, never anything else: " + line);
        }
    }

    // ------------------------------------------------- the classification --

    /**
     * The classification agrees with the boolean it is given, in both directions.
     *
     * <p>{@code accepted} always wins, because it <em>is</em> the renderer's decision - so a ray that hit the
     * structure's own blocks classifies as {@code DRAWN} and not as a refusal. Both entry points are checked,
     * the pure one the renderer uses and the one that takes the ray's result, because they have to agree on
     * every combination or the line and the picture could be classified two ways.
     */
    @Test
    @DisplayName("the sight classification never contradicts the decision it was given")
    void theClassificationNeverContradictsTheDecision() {
        BlockPos target = new BlockPos(10, 70, 10);
        assertEquals(CarrierHostCheck.Sight.UNKNOWN_TARGET,
                CarrierHostCheck.Sight.of(false, null, null),
                "no target is its own answer, whatever the boolean says");
        assertEquals(CarrierHostCheck.Sight.DRAWN,
                CarrierHostCheck.Sight.of(true, null, target),
                "a miss that was accepted is a draw, not a miss");
        assertEquals(CarrierHostCheck.Sight.DRAWN,
                CarrierHostCheck.Sight.of(true, blockHit(10, 70, 10), target),
                "a hit on the target itself was accepted, so it is a draw");
        assertEquals(CarrierHostCheck.Sight.MISS,
                CarrierHostCheck.Sight.of(false, missHit(), target),
                "a refused question whose ray hit nothing is a miss");
        assertEquals(CarrierHostCheck.Sight.REFUSED_HIT,
                CarrierHostCheck.Sight.of(false, blockHit(10, 72, 10), target),
                "a refused question whose ray hit a block is a wall in front");

        // The renderer's own entry point: the same four answers from three booleans, and the accepted
        // flag wins in every case.
        assertEquals(CarrierHostCheck.Sight.UNKNOWN_TARGET,
                CarrierHostCheck.Sight.ofFacts(false, false, false));
        assertEquals(CarrierHostCheck.Sight.UNKNOWN_TARGET,
                CarrierHostCheck.Sight.ofFacts(false, true, true),
                "not aiming at anything is not a draw, even if a boolean says otherwise");
        assertEquals(CarrierHostCheck.Sight.DRAWN, CarrierHostCheck.Sight.ofFacts(true, true, false));
        assertEquals(CarrierHostCheck.Sight.DRAWN, CarrierHostCheck.Sight.ofFacts(true, true, true));
        assertEquals(CarrierHostCheck.Sight.MISS, CarrierHostCheck.Sight.ofFacts(true, false, false));
        assertEquals(CarrierHostCheck.Sight.REFUSED_HIT,
                CarrierHostCheck.Sight.ofFacts(true, false, true));
    }

    /** A ray that hit a block. */
    private static net.minecraft.world.phys.BlockHitResult blockHit(int x, int y, int z) {
        BlockPos pos = new BlockPos(x, y, z);
        return new net.minecraft.world.phys.BlockHitResult(
                net.minecraft.world.phys.Vec3.atCenterOf(pos), net.minecraft.core.Direction.UP, pos,
                false);
    }

    /** A ray that hit nothing. */
    private static net.minecraft.world.phys.BlockHitResult missHit() {
        return net.minecraft.world.phys.BlockHitResult.miss(net.minecraft.world.phys.Vec3.ZERO,
                net.minecraft.core.Direction.UP, BlockPos.ZERO);
    }

    // ---------------------------------------------------- the counters --

    /**
     * The tally's arithmetic: five counters, one per answer, with {@code sightChecked} as their sum, since a
     * counter that could drift from the classification would make the frame summary lie about what it counts.
     */
    @Test
    @DisplayName("the sight tally adds up, one counter per answer")
    void theTallyAddsUp() {
        CarrierHostCheck.Tally tally = CarrierHostCheck.Tally.NONE
                .plus(CarrierHostCheck.Sight.DRAWN)
                .plus(CarrierHostCheck.Sight.REFUSED_HIT)
                .plus(CarrierHostCheck.Sight.MISS)
                .plus(CarrierHostCheck.Sight.MISS)
                .plus(CarrierHostCheck.Sight.UNKNOWN_TARGET);

        assertEquals(5, tally.checked());
        assertEquals(1, tally.drawn());
        assertEquals(1, tally.refused());
        assertEquals(2, tally.miss());
        assertEquals(1, tally.unknownTarget());
        assertEquals(tally.checked(),
                tally.drawn() + tally.refused() + tally.miss() + tally.unknownTarget(),
                "the parts have to be the whole");

        String line = tally.describe();
        assertTrue(line.contains("sightChecked=5"), line);
        assertTrue(line.contains("sightDrawn=1"), line);
        assertTrue(line.contains("sightRefusedHit=1"), line);
        assertTrue(line.contains("sightMiss=2"), line);
        assertTrue(line.contains("sightUnknownTarget=1"), line);
        assertEquals(line, tally.frameTail(), "the line and the phrase must be one spelling");
    }

    /**
     * B1's rollup, in the wording a reader greps for.
     *
     * <p>{@code skippedStale} and {@code skippedFar} have to be <b>separate</b> numbers: "the walk never
     * offered it" and "the walk offered it and the renderer then refused it" are opposite conclusions.
     */
    @Test
    @DisplayName("the carrier rollup separates not-offered from offered-and-refused")
    void theCarrierRollupReads() {
        String line = CarrierHostCheck.Tally.describeCarriers(3, 1, 1, 1, 4, 2);
        for (String field : new String[] {
                "carriers=", "collected=", "skippedOriginOnly=", "skippedNoPose=", "skippedStale=",
                "skippedFar=" }) {
            assertEquals(1, occurrences(line, field), "one '" + field + "' on the rollup: " + line);
        }
        assertTrue(line.contains("carriers=3"), line);
        assertTrue(line.contains("collected=1"), line);
        assertTrue(line.contains("skippedOriginOnly=1"), line);
        assertTrue(line.contains("skippedNoPose=1"), line);
        assertTrue(line.contains("skippedStale=4"), line);
        assertTrue(line.contains("skippedFar=2"), line);
    }

    // ------------------------------------------------------- the settlement --

    /**
     * The gate compares the settlement, not the line: two settlements that differ in any of the five numbers
     * have to compare unequal and the same five numbers have to compare equal, or a change is missed or a
     * non-change is reported. The carrier count is part of it, so a machine appearing is a change at once.
     */
    @Test
    @DisplayName("the settlement signature and the walk-only guard are exact")
    void theSettlementSignatureIsExact() {
        ClientHostCache.CarrierSweep base = sweep(2, 1, 1, 0);
        String signature = CarrierSightLog.settlementOf(base, 2);
        assertEquals(CarrierSightLog.settlement(2, 1, 1, 0, 1), signature);
        assertEquals("none/2", CarrierSightLog.settlementOf(null, 2),
                "nothing seen yet is its own signature, so the first frame cannot be suppressed");

        // The first visit records what it saw, so the same numbers are not a change a second time.
        assertTrue(CarrierSightLog.sweepDue(0L, base, 2));
        assertFalse(CarrierSightLog.settlementChanged(signature),
                "the visit remembers the settlement it saw");
        assertTrue(CarrierSightLog.settlementChanged(CarrierSightLog.settlementOf(base, 3)),
                "one more carrier is a change");
        assertTrue(CarrierSightLog.settlementChanged(
                        CarrierSightLog.settlementOf(sweep(2, 0, 1, 1), 2)),
                "a carrier moving into the no-pose column is a change");
    }

    // ----------------------------------------------------------------- helpers --

    /**
     * The value of one field of a line, or an empty string when it is not there. Parenthesis aware, because a
     * value may contain spaces: a naive "up to the next space" reading would cut a hit shape in half and then
     * report that the line names no hit.
     */
    private static String valueOf(String line, String field) {
        int at = line.indexOf(field);
        if (at < 0) {
            return "";
        }
        int from = at + field.length();
        if (line.startsWith("(", from)) {
            int close = line.indexOf(')', from);
            return close < 0 ? line.substring(from) : line.substring(from, close + 1);
        }
        int to = line.indexOf(' ', from);
        return to < 0 ? line.substring(from) : line.substring(from, to);
    }

    /** Whether a {@code ray=} value is one of the shapes the line uses. */
    private static boolean isOneOfTheRayShapes(String value) {
        return "miss".equals(value) || "unknown-target".equals(value)
                || "drawn".equals(value) || "refused-hit".equals(value)
                || value.startsWith("hit(");
    }

    /** How many times {@code needle} occurs in {@code haystack}. */
    private static int occurrences(String haystack, String needle) {
        int count = 0;
        int at = haystack.indexOf(needle);
        while (at >= 0) {
            count++;
            at = haystack.indexOf(needle, at + needle.length());
        }
        return count;
    }
}

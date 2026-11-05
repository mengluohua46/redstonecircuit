package com.jiangyuefengyu.redstonecircuit.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * "The client holds N carrier(s) but the server last reported M" must fire on a removal that PERSISTS, not on a race.
 *
 * <p><b>The defect this pins (一帧的滞后被当成了故障).</b> {@code checkConsistency} compares two numbers read a
 * moment apart on two threads: what the client holds, and how many carriers the server last said it had (载波计数).
 * On the most ordinary event in the mod - a structure destroyed in the very tick the client notices it (同一 tick 内
 * 被破坏的结构) - they disagree for a frame, and the removal packet that heals them is already on the wire. A log 
 * of it is four lines: the warning, then "carrier ... gone; kept 0 host(s) as landed", then the server's
 * "removal announced to the client" 5 ms later. A warning on the first disagreeing frame therefore cried wolf on every
 * destruction, and a warning that fires on the normal case is a warning that gets filtered out on the abnormal one.
 *
 * <p><b>The rule, and why it is a state.</b> {@code CarrierRenderTrace}'s own javadoc says the shape is "a state rather
 * than an event", so the honest test of it is duration: stamp the first sighting, report only once the mismatch has
 * lasted {@link CarrierRenderTrace#MISMATCH_PERSIST_MILLIS}, forget the stamp the moment the counts agree again, and
 * keep the once-per-dimension gate on top so a genuine unannounced removal is reported once rather than every frame.
 * Two seconds is longer than a round trip and shorter than the pose-staleness sweep that would eventually hide the
 * evidence, so the production path is not deleted, only delayed: a structure removed without a packet still produces the
 * warning after the threshold.
 *
 * <p><b>Pinned offline, through the production functions.</b> {@code MismatchAge} is the clock and the decision as pure
 * functions of wall-clock milliseconds ({@code Util#getMillis()} at the call site, because this runs on the render thread
 * and has no guaranteed level to read a game time from), the memory staying in the two static fields of
 * {@code CarrierRenderTrace} - the same idiom as {@code CarrierSpace#worldPairRead} and {@code CarrierSightThrottle},
 * which is what lets a test assert the rule without a client, a level or a log. One assertion is on the source text,
 * deliberately: the composition inside {@code checkConsistency} needs a live {@code CarrierTrace.Render} and a client
 * cache, so a revert of the one call site that reports would leave every behavioural assertion above as green as it is
 * now - the way to make that revert red is to pin the site where it lives.
 */
class CarrierMismatchPersistenceTest {

    /** The threshold as production states it, so a test cannot quietly pin a different one. */
    private static final long THRESHOLD = CarrierRenderTrace.MISMATCH_PERSIST_MILLIS;

    @Test
    @DisplayName("(i) a mismatch seen for the first time is not reportable")
    void firstSightingOfAMismatchIsNotReportable() {
        long now = 1_000_000L;
        // The first disagreeing frame STAMPS the clock; nothing about a single frame is a finding.
        long firstSeen = CarrierRenderTrace.MismatchAge.firstSeen(
                CarrierRenderTrace.MismatchAge.NEVER, true, now);

        assertEquals(now, firstSeen, "the first sighting must be remembered as the clock's start, "
                + "not reported: the client and the server disagree for a frame on every ordinary destruction");
        assertFalse(CarrierRenderTrace.MismatchAge.mismatchPersists(now, firstSeen, THRESHOLD),
                "an age of zero has not persisted, so the state is not yet worth a line");
        assertFalse(CarrierRenderTrace.MismatchAge.mismatchReportable(now, firstSeen, THRESHOLD, false),
                "and a one-frame lag must stay silent - this is the assertion the old first-frame warning "
                        + "failed, on every structure the player broke");
    }

    @Test
    @DisplayName("(ii) one millisecond before the threshold is silent, at the threshold it reports")
    void theThresholdIsTheReportBoundary() {
        long now = 250_000L;
        long firstSeen = CarrierRenderTrace.MismatchAge.firstSeen(
                CarrierRenderTrace.MismatchAge.NEVER, true, now);

        assertFalse(CarrierRenderTrace.MismatchAge.mismatchPersists(now + THRESHOLD - 1, firstSeen, THRESHOLD),
                "one millisecond short of the threshold is still a race, not a finding");
        assertFalse(CarrierRenderTrace.MismatchAge.mismatchReportable(now + THRESHOLD - 1, firstSeen, THRESHOLD,
                false), "so nothing is written one millisecond before it");

        assertTrue(CarrierRenderTrace.MismatchAge.mismatchPersists(now + THRESHOLD, firstSeen, THRESHOLD),
                "AT the threshold the mismatch has persisted: a removal that was never announced is a real "
                        + "defect and must still be reported, which is why the check is delayed rather than "
                        + "deleted");
        assertTrue(CarrierRenderTrace.MismatchAge.mismatchReportable(now + THRESHOLD, firstSeen, THRESHOLD, false),
                "and that is the line the operator sees");
        assertTrue(CarrierRenderTrace.MismatchAge.mismatchPersists(now + THRESHOLD * 5, firstSeen, THRESHOLD),
                "a threshold reached is not a one-frame window: the state keeps holding");
    }

    @Test
    @DisplayName("(iii) once the counts agree again the stamp is forgotten, so a new mismatch starts over")
    void agreementForgetsTheStampSoALaterMismatchStartsItsOwnClock() {
        long now = 500L;
        long first = CarrierRenderTrace.MismatchAge.firstSeen(CarrierRenderTrace.MismatchAge.NEVER, true, now);
        assertEquals(now, first);

        // The removal packet arrives (or the staleness sweep lets the extra carrier go): the state is gone.
        long healed = CarrierRenderTrace.MismatchAge.firstSeen(first, false, now + 400);
        assertEquals(CarrierRenderTrace.MismatchAge.NEVER, healed,
                "agreeing again must FORGET the stamp: an inherited age would let the next mismatch report on "
                        + "the frame it appears, which is exactly the race this fix exists to silence");

        // A different structure is broken later: its own clock, not the healed one's.
        long later = now + 1_000L;
        long second = CarrierRenderTrace.MismatchAge.firstSeen(healed, true, later);
        assertEquals(later, second, "the new mismatch is stamped when it is seen, not when the old one was");
        assertFalse(CarrierRenderTrace.MismatchAge.mismatchReportable(later + THRESHOLD - 1, second, THRESHOLD,
                false), "and it must wait out its own threshold: the previous mismatch's age is not on loan");

        // A mismatch that heals on its own, before the threshold, leaves nothing behind either.
        assertEquals(CarrierRenderTrace.MismatchAge.NEVER,
                CarrierRenderTrace.MismatchAge.firstSeen(second, false, later + THRESHOLD - 1),
                "healing before the threshold is silence, and the bookkeeping must not survive it");
    }

    @Test
    @DisplayName("(iv) the once-per-dimension gate still applies on top of the timing")
    void aPersistedMismatchIsReportedOncePerDimension() {
        long now = 7L;
        long firstSeen = CarrierRenderTrace.MismatchAge.firstSeen(
                CarrierRenderTrace.MismatchAge.NEVER, true, now);

        assertTrue(CarrierRenderTrace.MismatchAge.mismatchReportable(now + THRESHOLD, firstSeen, THRESHOLD, false),
                "the frame that first crosses the threshold reports");
        assertFalse(CarrierRenderTrace.MismatchAge.mismatchReportable(now + THRESHOLD, firstSeen, THRESHOLD, true),
                "a dimension already told is not told again - otherwise a real mismatch is one line per frame, "
                        + "which is how a finding gets filtered out of the log");
        assertFalse(CarrierRenderTrace.MismatchAge.mismatchReportable(now + THRESHOLD * 30, firstSeen, THRESHOLD,
                true), "and never again, however long the state lasts");
    }

    @Test
    @DisplayName("(v) checkConsistency still reports, and its sentence names the threshold rather than a fault")
    void theWarningSurvivesAndStatesTheThreshold() throws IOException {
        String body = bodyOf(read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "client",
                "CarrierRenderTrace.java"), "private static void checkConsistency");

        assertTrue(body.contains("mismatchReportable(nowMs, firstSeenMs, MISMATCH_PERSIST_MILLIS"),
                "the production path must go through the timed decision; a revert that warns on the first "
                        + "disagreeing frame again is what this line makes red");
        assertTrue(body.contains("WARNED_MISMATCH.contains(dimension)"),
                "and the once-per-dimension gate is still handed in, not replaced by the timing");
        assertTrue(body.contains("WARNED_MISMATCH.add(dimension)"),
                "the gate is set when the line is written, so the state is reported once");
        assertTrue(body.contains("no removal was announced within {} ms"),
                "the sentence must say the removal was not announced WITHIN the threshold: claiming one "
                        + "frame's lag is a fault is what made the alarm cry wolf");
        assertFalse(body.contains("a removal was not announced;"),
                "the old wording asserted a defect with no duration behind it, and must not survive");

        assertEquals(2_000L, CarrierRenderTrace.MISMATCH_PERSIST_MILLIS,
                "the sentence prints this number, so the threshold the operator reads is this one");
    }

    // ------------------------------------------------------------- helpers --

    private static String read(String... parts) throws IOException {
        // The working directory differs between the test task and a build from the repository root, so both spellings are tried.
        Path underNeoforge = Path.of("neoforge").resolve(Path.of("", parts));
        Path direct = Path.of("", parts);
        for (Path candidate : List.of(direct, underNeoforge)) {
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.UTF_8);
            }
        }
        throw new IOException("could not find " + String.join("/", parts) + " from "
                + Path.of("").toAbsolutePath() + "; this guard reads the source on purpose");
    }

    /** The body of the first member whose declaration contains {@code signature}, by brace counting. */
    private static String bodyOf(String source, String signature) {
        int at = source.indexOf(signature);
        assertTrue(at >= 0, "the source no longer contains '" + signature + "'; the guard is stale");
        int open = source.indexOf('{', at);
        assertTrue(open > 0, "no body found for '" + signature + "'");
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return source.substring(at, i);
                }
            }
        }
        throw new IllegalStateException("unbalanced braces after '" + signature + "'");
    }
}

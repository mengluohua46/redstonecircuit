package com.jiangyuefengyu.redstonecircuit.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.jiangyuefengyu.redstonecircuit.RCConfig;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierHostCheck;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierTrace;

import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>The frame summary's two rows, asserted on their <em>keys</em> rather than on their text.</b>
 *
 * <h2>The two failures this test exists for, and why it is written this way</h2>
 * The previous two rounds of this work both failed the same way: a test asserted that a counter appeared
 * <b>exactly once</b>, and the line wrote it <b>twice</b>. Round one wrote {@code hostsDrawn=1of1} on the
 * default row and the same name again inside the verbose row's {@code frameCounters()}. Round two fixed
 * that and left {@code skipped(…)} on both. Neither was an arithmetic error - each was a
 * <em>wording</em> collision between two strings assembled in two different places, and no test that
 * greps a finished line can say which place wrote what.
 *
 * <p>So this test does not grep. It asks the builder for the keys it wrote
 * ({@link CarrierFrameRow#build}), which is the same call the line comes from, and asserts the
 * properties a reader cares about:
 *
 * <ol>
 *   <li><b>the default row's keys and the counter group's keys are disjoint</b> - by intersecting the two
 *       declared lists, not by counting occurrences and hoping;</li>
 *   <li><b>no key appears twice on one row</b> - asserted on the key set, and enforced by the builder
 *       itself, which throws rather than overwriting;</li>
 *   <li><b>each of the thirteen counters is on the verbose row exactly once</b>, and on the default row
 *       not at all;</li>
 *   <li><b>the key set and the line agree</b>, so "the keys are clean" cannot be true of a set that the
 *       text does not use.</li>
 * </ol>
 *
 * <h2>What is deliberately not asserted</h2>
 * Spacing, wording and field order are <b>not</b> pinned anywhere: the row is a set of keys, and a
 * reordering or a reworded sentence must not be able to turn this test red while the invariants hold.
 * The two exceptions are the ones a reader greps for by name - {@code frame drew}, {@code poseAge=},
 * {@code ray=} - and those are asserted through the one class that words them.
 */
class CarrierFrameRowTest {

    private static final ResourceKey<Level> DIMENSION =
            ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
                    ResourceLocation.parse("redstonecircuit:frame_row_test"));

    @BeforeEach
    void reset() {
        CarrierRenderTrace.resetLogThrottleForTest();
    }

    /**
     * The render record, filled with numbers that are recognisable in the row and, deliberately,
     * <b>non-degenerate</b>.
     *
     * <p>{@code drawn=1, shellQuads=30, contentQuads=24, hostsDrawn=1} keeps the geometry verdict empty,
     * so the counters on the verbose row are written by the counter group and by nothing else - which is
     * what makes "exactly once" a statement about the builder rather than about a sentence.
     */
    private static CarrierTrace.Render recorded() {
        CarrierTrace.Render render = CarrierTrace.render(DIMENSION);
        render.setKnown(1, 4);
        render.setServerCarriers(1);
        render.setFrame(1, 0, 0, 0);
        render.setClientHeld(0, 0, 0);
        render.setLanded(2, 1, 0, 0);
        render.setDrawCheck(0, 0);
        render.setPoseAge(3L);
        render.setGates("overlayMode=ALWAYS goggles=true active=true showInnerComponents=true "
                + "forceOverlayDraw=false tint=0x8040FF40 frame=0xFF000000 wireFrame=0xFF00FFFF "
                + "solidFrames=false");
        render.setFlushStages("worldStage=AFTER_ENTITIES carriedStage=AFTER_TRANSLUCENT_BLOCKS");
        render.setGeometry(30, 24, 1, "host");
        render.setSight(CarrierHostCheck.Tally.NONE, null);
        return render;
    }

    /** One frame, built the way the render pass builds it. */
    private static CarrierFrameRow.Frame frame() {
        return CarrierFrameRow.Frame.of(recorded(), 1, 0, 0, 0, 3L);
    }

    // -------------------------------------------------- the constructive core --

    /**
     * <b>The headline invariant: the default row and the counter group share no key.</b>
     *
     * <p>This is the defect of both previous rounds stated as a set intersection. {@code hostsDrawn} was
     * on the default row <em>and</em> in {@code frameCounters()}; {@code skipped(…)} was written by the
     * row and the counters wrote {@code skippedNoPose}. Intersecting the two declarations is a statement
     * about names, so it holds before a single character is formatted - and it is also checked at
     * class-initialisation of the builder, so a future round that moves a key is told at once.
     */
    @Test
    @DisplayName("the default row's keys and the frame counters' keys are disjoint")
    void theTwoKeySetsAreDisjoint() {
        Set<String> both = new LinkedHashSet<>(CarrierFrameRow.WHITELIST_KEYS);
        both.retainAll(CarrierFrameRow.COUNTER_KEYS);
        assertTrue(both.isEmpty(),
                "a key on both the default row and the counter group is written twice on the verbose "
                        + "row, which is exactly how the previous two rounds failed: " + both);
        // The same statement, as the builder computes it - so the test and the runtime check cannot
        // disagree about what "disjoint" means.
        assertTrue(CarrierFrameRow.overlappingKeys().isEmpty(),
                "the builder's own disjointness check must agree: " + CarrierFrameRow.overlappingKeys());
        // And the counter list is the command's list, not a copy that could drift from it.
        assertEquals(CarrierTrace.Render.COUNTERS, CarrierFrameRow.COUNTER_KEYS,
                "the verbose row's counters must be the very list /rc carrier prints");
        assertEquals(13, CarrierFrameRow.COUNTER_KEYS.size(), "thirteen per-frame counters, no more");
    }

    /**
     * <b>The builder refuses a repeated key.</b>
     *
     * <p>Asserted because it is what makes the invariant constructive rather than observational: a
     * duplicate cannot be written, so no test has to be clever enough to notice one. The one escaped path
     * is the group parser ({@code addAllIfAbsent}), which folds another class's already formatted
     * {@code key=value} group in <b>by name</b> and drops a name the line already carries, rather than
     * throwing on it; see {@link #theVerboseRowWritesEveryCounterExactlyOnce}.
     */
    @Test
    @DisplayName("the row builder throws rather than writing the same key twice")
    void aRepeatedKeyCannotBeConstructed() {
        CarrierFrameRow.LogKeys keys = new CarrierFrameRow.LogKeys();
        keys.add("sightChecked", "1");
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> keys.add("sightChecked", "2"),
                "a second 'sightChecked' is the defect this class exists to make impossible");
        assertTrue(thrown.getMessage().contains("sightChecked"), thrown.getMessage());
    }

    // ------------------------------------------------------------ the default row --

    /**
     * <b>The default row carries the useful few and none of the per-frame counters.</b>
     *
     * <p>{@code verboseRenderLog} is off on the author's machine and in this JVM (no loaded config file,
     * so the documented default applies), which is exactly the configuration the size claim is about.
     */
    @Test
    @DisplayName("the default row carries the useful few and none of the thirteen counters")
    void theDefaultRowIsTheSmallOne() {
        assertFalse(RCConfig.verboseRenderLog(),
                "this test is about the DEFAULT row, so the switch must default off");
        String row = CarrierFrameRow.render(frame(), false);

        for (String key : CarrierFrameRow.WHITELIST_KEYS) {
            assertEquals(1, keyCount(row, key),
                    "the default row must carry '" + key + "' exactly once: " + row);
        }
        for (String counter : CarrierFrameRow.COUNTER_KEYS) {
            assertEquals(0, keyCount(row, counter),
                    "'" + counter + "' belongs behind verboseRenderLog, not on the default row: " + row);
        }
        // The one reader-facing shape that has to survive, stated through the class that words it. The
        // phrase is the row's HEAD (a sentence, not a `frame=` key - see the builder) and the age is the
        // fixture's own 3 ticks; the comma is what makes the pair after it read as the same sentence.
        assertTrue(row.startsWith("frame drew 1, poseAge=3t/0.15s"), row);
        // AND IT IS ACTUALLY SHORT. The target from the measurement is "a few hundred bytes a line"; 450
        // characters is the working ceiling, asserted rather than described so a new field has to justify
        // itself against it.
        assertTrue(row.length() <= 450,
                "the default frame row must stay small: " + row.length() + " chars: " + row);
    }

    /** A frame that skipped nothing does not print the skip group; one that skipped something does. */
    @Test
    @DisplayName("a clean frame does not print the skip group, a skipping one does")
    void aCleanFrameDoesNotPrintZeroes() {
        assertFalse(CarrierFrameRow.render(frame(), false).contains("skipped("),
                "counters at zero are weight, not information: "
                        + CarrierFrameRow.render(frame(), false));
        CarrierFrameRow.Frame skipping = CarrierFrameRow.Frame.of(recorded(), 1, 2, 3, 4, -1L);
        assertTrue(CarrierFrameRow.render(skipping, false).contains("skipped(noPose=2 stale=4 far=3)"),
                CarrierFrameRow.render(skipping, false));
    }

    /** The five sight counts are this round's diagnosis, so they stay on the small row. */
    @Test
    @DisplayName("the sight counts are on the default row")
    void theSightCountsAreOnTheDefaultRow() {
        CarrierHostCheck.Tally tally = CarrierHostCheck.Tally.NONE
                .plus(CarrierHostCheck.Sight.DRAWN)
                .plus(CarrierHostCheck.Sight.MISS)
                .plus(CarrierHostCheck.Sight.UNKNOWN_TARGET);
        CarrierTrace.Render render = recorded();
        render.setSight(tally, null);
        String row = CarrierFrameRow.render(
                CarrierFrameRow.Frame.of(render, 1, 0, 0, 0, 3L), false);
        assertTrue(row.contains("sightChecked=3"), row);
        assertTrue(row.contains("sightDrawn=1"), row);
        assertTrue(row.contains("sightMiss=1"), row);
        assertTrue(row.contains("sightUnknownTarget=1"), row);
        assertTrue(row.contains("sightRefusedHit=0"), row);
        // ...and the phrase the counters write is the phrase the command's own line writes.
        assertEquals(tally.describe(), "sightChecked=3 sightDrawn=1 sightRefusedHit=0 sightMiss=1 "
                + "sightUnknownTarget=1");
    }

    // ------------------------------------------------------------ the verbose row --

    /**
     * <b>The verbose row carries every counter exactly once, and every default key too.</b>
     *
     * <p>This is the "information is not lost" half, asserted in both directions at once - the counters
     * are all there, and none of them is there twice. The second half is what the first round failed:
     * removing the duplicate from the default row is right, removing it from the verbose row is not.
     */
    @Test
    @DisplayName("the verbose row carries every counter exactly once, and the default keys as well")
    void theVerboseRowWritesEveryCounterExactlyOnce() {
        String row = CarrierFrameRow.render(frame(), true);
        for (String counter : CarrierFrameRow.COUNTER_KEYS) {
            assertEquals(1, keyCount(row, counter),
                    "'" + counter + "' must appear exactly once on the verbose row: " + row);
        }
        for (String key : CarrierFrameRow.WHITELIST_KEYS) {
            assertEquals(1, keyCount(row, key),
                    "the verbose row is the default row plus detail, so '" + key + "' is once: " + row);
        }
        assertTrue(row.length() > CarrierFrameRow.render(frame(), false).length(),
                "the verbose row has to be the larger one");
    }

    /**
     * <b>The command's own line is untouched, and carries all thirteen counters.</b>
     *
     * <p>{@code /rc carrier} is the on-demand reader: it wants everything in one place, and this round's
     * change is to the frame <em>log</em>, not to the command. Asserting the set of names rather than the
     * sentence is what makes "untouched" a statement about information rather than about punctuation.
     */
    @Test
    @DisplayName("the command's line still carries every counter, and the compact form repeats none")
    void theCommandLineStillCarriesEverything() {
        CarrierTrace.Render render = recorded();
        String command = render.describe();
        for (String counter : CarrierFrameRow.COUNTER_KEYS) {
            assertEquals(1, keyCount(command, counter),
                    "the command's line must carry '" + counter + "' exactly once: " + command);
        }
        // The compact form is what the verbose row appends: it must add only names the row does not have.
        String compact = render.describeWithoutFrameCounters();
        for (String counter : CarrierFrameRow.COUNTER_KEYS) {
            assertEquals(0, keyCount(compact, counter),
                    "the compact form must not repeat the row's counters: '" + counter + "' in " + compact);
        }
        for (String key : CarrierFrameRow.WHITELIST_KEYS) {
            assertEquals(0, keyCount(compact, key),
                    "the compact form must not repeat a default-row key: '" + key + "' in " + compact);
        }
    }

    // ----------------------------------------------------- keys against the text --

    /**
     * <b>The key set and the line are the same row.</b>
     *
     * <p>Both come from one call of the builder, so "the keys are clean" cannot be true of a key set the
     * text does not use. Asserted by rebuilding the line from the key set and comparing, which is a
     * statement about the builder rather than about the test's ability to count.
     */
    @Test
    @DisplayName("the keys the builder reports are exactly the keys the line writes")
    void theKeySetAndTheLineAgree() {
        CarrierFrameRow.Frame frame = frame();
        String row = CarrierFrameRow.render(frame, true);
        CarrierFrameRow.LogKeys keys = CarrierFrameRow.build(frame, true);
        String pairs = keys.asLine();
        assertTrue(row.equals(pairs) || row.startsWith(pairs + " "),
                "the line is the pairs, then the sentences - nothing else, in no other order");
        // This frame has nothing to warn about, so the two are the same string; a frame that DOES have a
        // verdict is asserted below.
        assertEquals(pairs, row, "one call, one row: the key set and the text cannot disagree");

        List<String> inOrder = new ArrayList<>(keys.keys());
        assertTrue(inOrder.containsAll(CarrierFrameRow.WHITELIST_KEYS),
                "every declared default key is on the row: " + inOrder);
        assertTrue(inOrder.containsAll(CarrierFrameRow.COUNTER_KEYS),
                "every declared counter is on the verbose row: " + inOrder);
        assertEquals(inOrder.size(), new LinkedHashSet<>(inOrder).size(),
                "a row is a set of keys: no name twice, in the order or in the text: " + inOrder);

        // A frame that collected a host and emitted no geometry: the verdict becomes a sentence, and it is
        // carried after the pairs rather than as a key - so the key set is unchanged and the sentence is
        // still exactly where a reader looks for it. The fixture has to state that shape itself (one host
        // collected, zero quads written): `recorded()` is a healthy frame, and a healthy frame has no
        // verdict at all. The comparison is against the row built at the SAME width - the default row is
        // what is rendered here, so it is the default row's pairs the sentence follows.
        CarrierTrace.Render warnedRender = recorded();
        warnedRender.setFrame(1, 0, 0, 0);
        warnedRender.setGeometry(0, 0, 1, null);
        CarrierFrameRow.Frame warned = CarrierFrameRow.Frame.of(warnedRender, 1, 0, 0, 0, -1L);
        String warnedRow = CarrierFrameRow.render(warned, false);
        assertTrue(warnedRow.startsWith(CarrierFrameRow.build(warned, false).asLine() + " "), warnedRow);
        assertTrue(warnedRow.contains(
                "*** NOTHING EMITTED - 1 host(s) were collected but every quad was skipped"), warnedRow);
        for (String key : CarrierFrameRow.WHITELIST_KEYS) {
            assertEquals(1, keyCount(warnedRow, key),
                    "a warning must not add or remove a key: '" + key + "' in " + warnedRow);
        }
    }

    /**
     * The line's size against the author's own session, as a worked projection.
     *
     * <p>The measurement: ~3,840 lines of this summary at 1-2 KB each. The fix is two factors, and this
     * test states both so a report can quote a number rather than an adjective.
     */
    @Test
    @DisplayName("the projection: an hour of the same session is hundreds of KB, not megabytes")
    void theSizeProjectionIsHundredsOfKilobytes() {
        int windowTicks = CarrierRenderTrace.QUIET_LOG_INTERVAL_TICKS;
        assertEquals(100, windowTicks, "P1.48 moved the window to five seconds of ticks");
        long linesPerHour = 20L * 60 * 60 / windowTicks;
        long bytes = linesPerHour * CarrierFrameRow.render(frame(), false).length();
        System.out.println("[redstonecircuit] frame row: default="
                + CarrierFrameRow.render(frame(), false).length() + " chars, verbose="
                + CarrierFrameRow.render(frame(), true).length() + " chars, window=" + windowTicks
                + "t, lines/hour=" + linesPerHour + ", ~" + (bytes / 1024) + " KiB/hour"
                + " (the measured session was 8.73 MB over 4,808 lines at 1-2 KB a line)");
        assertTrue(linesPerHour <= 800, "the window bounds the line count: " + linesPerHour);
        assertTrue(bytes <= 400L * 1024, "an hour must fit in hundreds of KiB: " + (bytes / 1024)
                + " KiB");
    }

    // ------------------------------------------------------------------ helpers --

    /**
     * How many times a key is written as a field of the row.
     *
     * <p>Counted by <b>splitting on whitespace and matching {@code key=} as a whole token</b>, not by
     * searching for the substring {@code key=}: {@code landedHosts=2} contains {@code landed=}, and a
     * substring count would report the wrong field - which is precisely the kind of near-miss that made
     * the previous rounds' assertions look right and be wrong. A token that merely <em>contains</em> the
     * key, such as the coordinate {@code landed=...} inside a longer value, is not a field of this row.
     */
    private static int keyCount(String row, String key) {
        String prefix = key + "=";
        int count = 0;
        for (String token : row.trim().split("\\s+")) {
            if (token.startsWith(prefix)) {
                count++;
            }
        }
        return count;
    }
}

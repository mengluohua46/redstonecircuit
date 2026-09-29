package com.jiangyuefengyu.redstonecircuit.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>The counters that measure what a buffer really took, and the alarms for when it took nothing.</b>
 *
 * <h2>The defect these exist for, in numbers</h2>
 * The author's contraption showed its frosted frame and no components, while the log said
 * {@code carriedShellQuads=30 carriedContentQuads=12} - on 431 frames. Both numbers were counted in the
 * drawing loops, i.e. they measured how many quads were <em>handed to a consumer</em>, and the two carried
 * draw points had asked for that consumer from the <b>world</b> buffer source, whose batch is submitted
 * at {@code AFTER_ENTITIES}. The carried stage runs two stages later. So every vertex went into a batch
 * that had already been sent, nothing reached the screen, and every counter agreed the frame was perfect.
 *
 * <p>These tests hold the two facts that make that impossible to repeat silently:
 * {@link HostBufferTally#flushedQuads} only counts what was in the source at its flush, and
 * {@link HostBufferTally#postFlushWrites} is non-zero exactly when something aimed at a finished batch.
 */
class HostBufferTallyTest {

    @AfterEach
    void clearTally() {
        HostBufferTally.resetForTest();
    }

    /** Writes {@code quads} quads - four vertices each - as a draw point would. */
    private static void writeQuads(HostBufferTally.Sink sink, HostBufferTally.Kind kind, int quads) {
        HostBufferTally.written(sink, kind, quads * 4);
    }

    // ------------------------------------------------------------------ accounting --

    @Test
    @DisplayName("a write is attributed to the sink and kind it was handed, and to no other")
    void writesGoToTheSinkTheyWereGiven() {
        writeQuads(HostBufferTally.Sink.CARRIED, HostBufferTally.Kind.SHELL, 30);
        writeQuads(HostBufferTally.Sink.CARRIED, HostBufferTally.Kind.CONTENTS, 12);

        assertEquals(120, HostBufferTally.writtenVertices(HostBufferTally.Sink.CARRIED,
                HostBufferTally.Kind.SHELL));
        assertEquals(48, HostBufferTally.writtenVertices(HostBufferTally.Sink.CARRIED,
                HostBufferTally.Kind.CONTENTS));
        // The world source must be untouched by a carried write: this is the assertion whose absence let
        // the defect through, because the defect was precisely a carried write landing in the world sink.
        assertEquals(0, HostBufferTally.writtenVertices(HostBufferTally.Sink.WORLD,
                HostBufferTally.Kind.SHELL));
        assertEquals(0, HostBufferTally.writtenVertices(HostBufferTally.Sink.WORLD,
                HostBufferTally.Kind.CONTENTS));
    }

    @Test
    @DisplayName("flush moves pending to flushed, and restarts the post-flush window")
    void flushSnapshotsAndRestartsTheWindow() {
        writeQuads(HostBufferTally.Sink.CARRIED, HostBufferTally.Kind.SHELL, 30);
        assertTrue(HostBufferTally.postFlushWrites(HostBufferTally.Sink.CARRIED) > 0,
                "writes before the flush are still 'after the last flush' until one happens");

        HostBufferTally.flush(HostBufferTally.Sink.CARRIED);

        assertEquals(30, HostBufferTally.flushedQuads(HostBufferTally.Sink.CARRIED,
                HostBufferTally.Kind.SHELL));
        assertEquals(0, HostBufferTally.writtenVertices(HostBufferTally.Sink.CARRIED,
                HostBufferTally.Kind.SHELL),
                "the pending window is emptied by the flush, so the next frame starts from zero");
        assertEquals(0, HostBufferTally.postFlushWrites(HostBufferTally.Sink.CARRIED),
                "a healthy frame ends with nothing written after its flush");
        // And the world sink's window is its own: flushing one sink must not clear the other's evidence.
        writeQuads(HostBufferTally.Sink.WORLD, HostBufferTally.Kind.SHELL, 30);
        HostBufferTally.flush(HostBufferTally.Sink.CARRIED);
        assertEquals(120, HostBufferTally.postFlushWrites(HostBufferTally.Sink.WORLD),
                "flushing the carried source must not launder the world source's stray writes");
    }

    /**
     * <b>The semantic the parent asked for, stated as the defect's own signature.</b>
     *
     * <p>The world source is flushed at {@code AFTER_ENTITIES}. Vertices handed to it afterwards are not
     * "flushed" and never can be - they are in a batch nobody submits this frame. This is what the old
     * counters could not express, and it is the assertion that would have failed on the broken build.
     */
    @Test
    @DisplayName("a write after a source was flushed is not counted as flushed, and is flagged")
    void writesAfterAFlushAreNotFlushed() {
        HostBufferTally.flush(HostBufferTally.Sink.WORLD);   // the world stage's endBatch
        assertEquals(0, HostBufferTally.flushedQuads(HostBufferTally.Sink.WORLD,
                HostBufferTally.Kind.CONTENTS));

        // The carried contents draw point, aiming at the world source - the defect, in one call.
        writeQuads(HostBufferTally.Sink.WORLD, HostBufferTally.Kind.CONTENTS, 12);

        assertEquals(0, HostBufferTally.flushedQuads(HostBufferTally.Sink.WORLD,
                HostBufferTally.Kind.CONTENTS),
                "a vertex written after its source was flushed must NOT be reported as flushed");
        assertEquals(48, HostBufferTally.postFlushWrites(HostBufferTally.Sink.WORLD),
                "and it must be recorded as a stray write, which is how the defect is detected");
    }

    // ------------------------------------------------------------------ the alarms --

    @Test
    @DisplayName("the defect's exact signature trips both alarms")
    void theDefectSignatureTripsTheAlarms() {
        // BEFORE THE FIX. Reproduced from the author's log shape: 30 shell quads and 12 content quads
        // emitted for one carried host, all of them handed to the world consumer.
        int emitted = 30 + 12;
        HostBufferTally.flush(HostBufferTally.Sink.WORLD);                 // AFTER_ENTITIES: endWorldBatch
        writeQuads(HostBufferTally.Sink.WORLD, HostBufferTally.Kind.SHELL, 30);
        writeQuads(HostBufferTally.Sink.WORLD, HostBufferTally.Kind.CONTENTS, 12);
        HostBufferTally.flush(HostBufferTally.Sink.CARRIED);               // the carried stage's endBatch

        assertEquals(0, HostBufferTally.flushedQuads(HostBufferTally.Sink.CARRIED,
                HostBufferTally.Kind.SHELL), "before the fix, NOTHING reached the carried source");
        assertEquals(0, HostBufferTally.flushedQuads(HostBufferTally.Sink.CARRIED,
                HostBufferTally.Kind.CONTENTS), "before the fix, NOTHING reached the carried source");

        String alarm = HostBufferTally.nothingFlushedWhileEmitting(HostBufferTally.Sink.CARRIED, emitted,
                0);
        assertNotNull(alarm, "a stage that emitted " + emitted + " quads and flushed none must alarm");
        assertTrue(alarm.contains("WROTE NOTHING INTO ITS OWN BUFFER"), alarm);
        assertTrue(alarm.contains("WRONG-BUFFER defect"), alarm);
        // And the second fact, which names the fix: where the vertices went instead.
        assertTrue(HostBufferTally.postFlushWrites(HostBufferTally.Sink.WORLD) > 0,
                "the stray writes into the finished world batch are the evidence");
        assertTrue(HostBufferTally.postFlushSentence().contains("AFTER we were done with it"),
                HostBufferTally.postFlushSentence());
    }

    @Test
    @DisplayName("the fixed wiring reaches the carried source, and trips nothing")
    void theFixedWiringFlushesEverythingItEmitted() {
        // AFTER THE FIX. Same 30 + 12 quads, now through the carried consumer.
        int emitted = 30 + 12;
        HostBufferTally.flush(HostBufferTally.Sink.WORLD);                 // AFTER_ENTITIES: endWorldBatch
        writeQuads(HostBufferTally.Sink.CARRIED, HostBufferTally.Kind.SHELL, 30);
        writeQuads(HostBufferTally.Sink.CARRIED, HostBufferTally.Kind.CONTENTS, 12);
        HostBufferTally.flush(HostBufferTally.Sink.CARRIED);               // the carried stage's endBatch

        assertEquals(30, HostBufferTally.flushedQuads(HostBufferTally.Sink.CARRIED,
                HostBufferTally.Kind.SHELL), "after the fix, the shell reaches CARRIED_BUFFERS");
        assertEquals(12, HostBufferTally.flushedQuads(HostBufferTally.Sink.CARRIED,
                HostBufferTally.Kind.CONTENTS), "after the fix, the contents reach CARRIED_BUFFERS");
        assertEquals(0, HostBufferTally.postFlushWrites(HostBufferTally.Sink.WORLD),
                "and nothing is written into the world source after it was flushed");
        assertEquals(0, HostBufferTally.postFlushWrites(HostBufferTally.Sink.CARRIED));
        assertNull(HostBufferTally.nothingFlushedWhileEmitting(HostBufferTally.Sink.CARRIED, emitted, emitted),
                "a stage that flushed everything it emitted must not alarm");
        assertEquals("", HostRenderPass.flushAlarms(),
                "a healthy frame's flush alarms must be empty, so their presence in a log is a finding");
    }

    @Test
    @DisplayName("a stage that emitted nothing does not alarm, so an idle frame stays quiet")
    void noAlarmWhenNothingWasEmitted() {
        HostBufferTally.flush(HostBufferTally.Sink.CARRIED);
        assertNull(HostBufferTally.nothingFlushedWhileEmitting(HostBufferTally.Sink.CARRIED, 0, 0),
                "emitted=0 with flushed=0 is an empty frame, not a defect");
    }

    @Test
    @DisplayName("the flush line reports flushed counts, emitted counts and the sources' own numbers")
    void flushLineCarriesBothReadings() {
        String line = HostRenderPass.flushStageLine();
        assertTrue(line.contains("worldShellQuads="), line);
        assertTrue(line.contains("worldOwnEmittedQuads="), line);
        assertTrue(line.contains("worldShellHandedOverQuads="), line);
        assertTrue(line.contains("carriedShellQuads="), line);
        assertTrue(line.contains("carriedEmittedQuads="), line);
        // The two readings are named and explained, so a reader who sees them disagree knows what it means
        // and knows which of the two is outside the equation. The explanation is the gated half: the
        // per-frame line carries the numbers, and the guidance is printed when the verdict changes
        // (see CarrierFrameLogTest for why that split exists and what it saves).
        assertTrue(HostRenderPass.flushStageExplanation()
                        .contains("The world SHELL is deliberately outside that equation"),
                HostRenderPass.flushStageExplanation());
        // And the tally's per-source ledger is part of it, under names that cannot be confused with the
        // frame summary's own counts.
        assertTrue(line.contains("worldBufFlushedQuads="), line);
        assertTrue(line.contains("carriedBufFlushedQuads="), line);
        assertTrue(line.contains("rendererBufHandedOverQuads="), line);
    }

    @Test
    @DisplayName("the tally describes every source and its kinds, and flags a stray write")
    void tallyDescriptionNamesSourcesAndKinds() {
        writeQuads(HostBufferTally.Sink.CARRIED, HostBufferTally.Kind.SHELL, 30);
        HostBufferTally.flush(HostBufferTally.Sink.CARRIED);
        String described = HostBufferTally.describe();
        assertTrue(described.contains("carriedBufFlushedQuads=30"), described);
        assertTrue(described.contains("carriedBufShellQuads=30"), described);
        assertTrue(described.contains("carriedBufContentQuads=0"), described);
        assertTrue(described.contains("worldBufFlushedQuads=0"), described);
        assertTrue(described.contains("rendererBufHandedOverQuads="), described);
        assertFalse(described.contains("BufPostFlushVertices"), described);

        writeQuads(HostBufferTally.Sink.WORLD, HostBufferTally.Kind.CONTENTS, 1);
        assertTrue(HostBufferTally.describe().contains("worldBufPostFlushVertices=4"),
                HostBufferTally.describe());
    }

    // ------------------------------------------------- the false alarm of §18 --

    @Test
    @DisplayName("a frame that drew only the world shell does NOT alarm: that source is not ours")
    void shellOnlyFrameDoesNotAlarm() {
        // The author's session, reproduced: the world path was working perfectly, and the module still
        // reported "WORLD STAGE WROTE NOTHING INTO ITS OWN BUFFER" 305 times. The cause was two different
        // facts sharing one ledger - the world shell contributes quads to the picture and none to our
        // flush, because the level renderer submits its source - so "emitted 42, flushed 0" was true and
        // meant nothing.
        writeQuads(HostBufferTally.Sink.RENDERER, HostBufferTally.Kind.SHELL, 30);
        HostBufferTally.flush(HostBufferTally.Sink.WORLD);        // contents: nothing this frame
        HostBufferTally.handOver(HostBufferTally.Sink.RENDERER);  // the shell: handed over, not flushed

        // The shell is reported as handed over, and is in nobody's flush equation.
        assertEquals(30, HostBufferTally.handedOverQuads(HostBufferTally.Sink.RENDERER,
                HostBufferTally.Kind.SHELL));
        assertEquals(0, HostBufferTally.flushedQuads(HostBufferTally.Sink.WORLD,
                HostBufferTally.Kind.CONTENTS));
        // Our own pair: nothing emitted for our source, nothing flushed. No alarm - and no alarm is the
        // only correct answer for a frame whose world contents were simply not drawn.
        assertNull(HostBufferTally.nothingFlushedWhileEmitting(HostBufferTally.Sink.WORLD, 0, 0));
        assertFalse(HostBufferTally.describe().contains("WROTE NOTHING"), HostBufferTally.describe());
    }

    @Test
    @DisplayName("the renderer sink is never alarmed on, and flush/handOver are not interchangeable")
    void rendererSinkIsNeverHeldToOurFlush() {
        // Even with 30 quads "emitted" and nothing flushed, the renderer's source is not our business.
        assertNull(HostBufferTally.nothingFlushedWhileEmitting(HostBufferTally.Sink.RENDERER, 30, 0),
                "a source this build hands to LevelRenderer cannot fail a flush it never performs");
        // And the two operations are named apart on purpose, so the mistake cannot be made quietly.
        assertThrows(IllegalArgumentException.class,
                () -> HostBufferTally.flush(HostBufferTally.Sink.RENDERER));
        assertThrows(IllegalArgumentException.class,
                () -> HostBufferTally.handOver(HostBufferTally.Sink.WORLD));
    }

    @Test
    @DisplayName("the stale-reading bug: an empty frame must not be judged on the last frame's numbers")
    void anEmptyFrameIsJudgedOnItsOwnNumbers() {
        // THE 305-ALARM BUG, as arithmetic. Frame N drew 12 quads for our world source and flushed them
        // all. Frame N+1 had nothing to draw.
        //
        // Old shape: the empty path submitted the batch but left `worldEmittedQuads` at frame N's value,
        // so the alarm compared a stale 42 against a freshly flushed 0 and reported a defect that did not
        // exist. New shape: the empty path records its own zeroed counter, and both numbers belong to
        // frame N+1.
        writeQuads(HostBufferTally.Sink.WORLD, HostBufferTally.Kind.CONTENTS, 12);
        HostBufferTally.flush(HostBufferTally.Sink.WORLD);
        assertEquals(12, HostBufferTally.flushedQuads(HostBufferTally.Sink.WORLD,
                HostBufferTally.Kind.CONTENTS), "frame N flushed what it drew");
        assertNull(HostBufferTally.nothingFlushedWhileEmitting(HostBufferTally.Sink.WORLD, 12, 12));

        // Frame N+1: an empty world. Its own numbers are 0 and 0.
        HostBufferTally.flush(HostBufferTally.Sink.WORLD);
        assertEquals(0, HostBufferTally.flushedQuads(HostBufferTally.Sink.WORLD,
                HostBufferTally.Kind.CONTENTS), "the empty frame's reading is its own, not frame N's");
        assertNull(HostBufferTally.nothingFlushedWhileEmitting(HostBufferTally.Sink.WORLD, 0, 0),
                "an empty frame is not a defect");
        // The defect this alarm is FOR still alarms when the pair really disagrees.
        assertNotNull(HostBufferTally.nothingFlushedWhileEmitting(HostBufferTally.Sink.WORLD, 42, 0),
                "a frame that emitted 42 for our source and flushed none is still a defect");
    }

    // ------------------------------------------------------------------ the wiring --

    /**
     * <b>The wiring guard, and why it reads the source.</b>
     *
     * <p>The defect was one method call in each of two drawing methods choosing the wrong accessor. The
     * honest way to test that would be to run {@code drawCarriedShells} against a real buffer source -
     * but {@code RenderType}'s class initialiser reaches {@code ItemRenderer}, {@code Items} and the
     * vanilla registries, which throw {@code Not bootstrapped} on a bare JVM, so no test can touch a
     * render type at all. That is not a reason to leave the wiring unguarded: the accessors are named
     * after the path they belong to precisely so the mistake is visible in the source, and this asserts
     * exactly that, which is the whole of what the defect was.
     *
     * <p>A deliberate, narrow exception to "no test reads the source": it is the only offline observation
     * that distinguishes {@code carriedShellBuffer()} from {@code worldShellBuffer()}, and getting that
     * wrong is not a style problem - it is 431 frames of invisible geometry.
     */
    @Test
    @DisplayName("each draw point uses its own path's buffer accessor, never the other path's")
    void drawPointsUseTheirOwnPathsBuffer() throws IOException {
        String source = readMainSource("HostRenderPass.java");

        // The carried draw points: the bug was these two lines. Searched by DECLARATION, not by name -
        // `source.indexOf("drawCarriedShells")` would find the call site in `runCarriedStage` first and
        // then brace-match that method's body instead.
        //
        // The accessor names lost their empty argument list when the shader-path decision arrived (each
        // now takes the RenderPlan it must obey), so the guard matches the name and its opening bracket:
        // what it is here to catch - one draw point borrowing the other path's accessor - is unchanged.
        assertBodyUses(source, "private static void drawCarriedShellsInto", "carriedShellBuffer(",
                "worldShellBuffer(");
        assertBodyUses(source, "private static void drawCarriedContentsInto", "carriedContentsBuffer(",
                "worldContentsBuffer(");
        // The world draw points, which must not drift the other way.
        assertBodyUses(source, "private static void drawShells", "worldShellBuffer()",
                "carriedShellBuffer(");
        assertBodyUses(source, "private static void drawContents", "worldContentsBuffer()",
                "carriedContentsBuffer(");
        assertBodyUses(source, "private static void drawHighlight", "worldShellBuffer()",
                "carriedShellBuffer(");

        // And the path-specific accessors really do name different sources, which is the property that
        // makes using the wrong one fatal rather than merely untidy.
        assertTrue(source.contains("HostRenderTypes.CARRIED_BUFFERS.getBuffer"),
                "the carried accessors must obtain from CARRIED_BUFFERS");
        assertTrue(source.contains("HostRenderTypes.BUFFERS.getBuffer"),
                "the world accessors must obtain from the world source");

        // And the carried stage is the only thing that submits the carried source.
        String hostRenderTypes = readMainSource("HostRenderTypes.java");
        assertTrue(hostRenderTypes.contains("CARRIED_BUFFERS.endBatch()"),
                "the carried source is submitted by endCarriedBatch");
    }

    /** Finds one of the mod's main sources, failing loudly rather than silently skipping the guard. */
    private static String readMainSource(String fileName) throws IOException {
        Path relative = Path.of("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit",
                "client", fileName);
        Path absolute = Path.of("neoforge").resolve(relative);
        for (Path candidate : List.of(relative, absolute)) {
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.UTF_8);
            }
        }
        throw new IOException("could not find " + fileName + " from " + Path.of("").toAbsolutePath()
                + " (tried " + relative + " and " + absolute + "); this guard reads the source on "
                + "purpose - see its javadoc - so it must not be skipped silently");
    }

    /**
     * Asserts the method starting at {@code signature} calls {@code wanted} and never {@code forbidden}.
     *
     * <p>Scoped to the method body by brace counting, not to a line window: a line window would still pass
     * once the method grew, which is how a guard quietly stops guarding.
     */
    private static void assertBodyUses(String source, String signature, String wanted,
                                       String forbidden) {
        int at = source.indexOf(signature);
        assertTrue(at >= 0, "the source no longer contains '" + signature + "'; the guard is stale");
        int open = source.indexOf('{', at);
        assertTrue(open > 0, "no body found for '" + signature + "'");
        int depth = 0;
        int end = source.length();
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    end = i;
                    break;
                }
            }
        }
        String body = source.substring(at, end);
        assertTrue(body.contains(wanted),
                signature + " does not use " + wanted + " - its geometry would go to the wrong batch");
        assertFalse(body.contains(forbidden),
                signature + " uses " + forbidden + " - that is the wrong buffer source for this path, "
                        + "and the geometry it writes will never be submitted");
    }
}

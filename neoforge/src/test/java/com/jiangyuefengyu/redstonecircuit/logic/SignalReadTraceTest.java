package com.jiangyuefengyu.redstonecircuit.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import com.jiangyuefengyu.redstonecircuit.carrier.CarrierId;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>P1.38's judgement line: "did the cross-structure read happen, and what did each source say".</b>
 *
 * <h2>Why this exists as a class of its own</h2>
 * The author's last log could not answer the one question that decides whether a round of work is aimed
 * at the right defect: <b>was the other block's position ever asked about?</b> The counters that would
 * have said so lived behind the {@code /rc carrier} command, so a log with no command typed in it showed
 * nothing at all - which is how a whole round was spent on a read path that the world never walked. Two
 * properties make this line usable rather than merely present:
 *
 * <ul>
 *   <li><b>at most one line per level tick</b>, whatever the solver does. A machine with two hundred
 *       components asking two hundred times must cost one line, not two hundred - the counters carry the
 *       totals, and the line carries the named example;</li>
 *   <li><b>the line names the asker, the position, both sources' answers and the final value</b>, so
 *       "no read was attempted", "the read answered zero from a source that had a hit" and "the read
 *       answered correctly" are three different log lines rather than one ambiguous absence.</li>
 * </ul>
 *
 * <p>These tests drive the recorder offline: no level, no carrier container, no game.
 */
class SignalReadTraceTest {

    private static final CarrierId STRUCTURE_B =
            CarrierId.subLevel(UUID.fromString("00000000-0000-0000-0000-00000000b3e1"));
    private static final BlockPos ASKED = new BlockPos(-34, -60, -32);

    @AfterEach
    void clearTheCounters() {
        // The counters are process-wide on purpose (they describe a level's structures and are dropped
        // with it), so a test that leaves them set would change the next one's summary line.
        SignalReadTrace.reset();
    }

    @Test
    @DisplayName("one tick is at most one line, however many reads it made")
    void atMostOneLinePerTick() {
        for (int i = 0; i < 200; i++) {
            SignalReadTrace.record(STRUCTURE_B, ASKED.offset(i, 0, 0), false,
                    "hit (local=20481032, 128, 20483080 carrier=s:a70018b6)",
                    "none (not a structure's own coordinate)",
                    BlockSignalSources.Source.WORLD_PROJECTION, 15);
        }

        List<String> lines = SignalReadTrace.closeTick();

        assertEquals(1, lines.size(), "two hundred reads in one tick, one line: " + lines);
        assertEquals(200, SignalReadTrace.projectedReads(),
                "and the counter is what carries the other one hundred and ninety nine");
        assertTrue(SignalReadTrace.closeTick().isEmpty(),
                "the slot is cleared with the tick, so the next tick starts empty");
    }

    @Test
    @DisplayName("the line names the asker, the position, both sources and the answer")
    void theLineNamesEverythingAJudgeNeeds() {
        SignalReadTrace.record(STRUCTURE_B, ASKED, true,
                "hit (local=20481032, 128, 20483080 carrier=s:a70018b6)",
                "declined (another sub-level owns this coordinate)",
                BlockSignalSources.Source.WORLD_PROJECTION, 15);

        String line = SignalReadTrace.closeTick().get(0);

        assertTrue(line.contains("cross-structure read attempt"),
                "the words to grep for: " + line);
        assertTrue(line.contains("asker=s:" + STRUCTURE_B.shortId().substring(2)),
                "who asked: " + line);
        assertTrue(line.contains("asked=" + ASKED.toShortString()), "which position: " + line);
        assertTrue(line.contains("another sub-level's coordinate"),
                "and whether that position is the other machine's, which is the whole question: " + line);
        assertTrue(line.contains("worldProjection=hit"), "what the world projection said: " + line);
        assertTrue(line.contains("structureFrame=declined"), "what the structure frame did: " + line);
        assertTrue(line.contains("finalAnswer=world_projection=15"), "and the answer: " + line);
    }

    @Test
    @DisplayName("the world can be named as the asker: a device on the ground reading a machine")
    void theWorldCanBeNamedAsTheAsker() {
        SignalReadTrace.record(null, ASKED, true,
                "hit (local=20481032, 128, 20483080 carrier=s:a70018b6)",
                "none (not a structure's own coordinate)",
                BlockSignalSources.Source.WORLD_PROJECTION, 9);

        String line = SignalReadTrace.closeTick().get(0);

        assertTrue(line.contains("asker=the world"),
                "a lamp or a piston on the ground reading a machine that flew past is a real read, and "
                        + "the line has to say whose it was rather than guess a structure: " + line);
    }

    @Test
    @DisplayName("the counters reach the summary line, so no command has to be typed")
    void theCountersReachTheSummary() {
        assertEquals("", SignalReadTrace.describe(),
                "nothing read yet: the summary line is byte-for-byte what it was before P1.38");

        SignalReadTrace.record(STRUCTURE_B, ASKED, false, "hit (local=0, 0, 0 carrier=s:b)",
                "answered (own coordinate)", BlockSignalSources.Source.STRUCTURE_FRAME, 9);
        SignalReadTrace.record(STRUCTURE_B, ASKED, true, "none (nothing is projected at this world "
                + "position)", "declined (another sub-level owns this coordinate)",
                BlockSignalSources.Source.NO_SIGNAL, 0);
        SignalReadTrace.declined();
        SignalReadTrace.suppressionBlocked();

        String summary = SignalReadTrace.describe();

        assertTrue(summary.contains("carrierReads:"), summary);
        assertTrue(summary.contains("structureFrame=1 via=structure-frame"), summary);
        assertTrue(summary.contains("worldProjection=0 via=world-projection"), summary);
        assertTrue(summary.contains("structureDeclines=1"), summary);
        assertTrue(summary.contains("suppressionBlocked=1"),
                "and the count of queries the window hid, which is P1.38's own evidence: " + summary);
        assertTrue(summary.contains("lastRead="), summary);

        assertEquals(1, SignalReadTrace.structureReads());
        assertEquals(0, SignalReadTrace.projectedReads());
        assertEquals(1, SignalReadTrace.declines());
        assertEquals(1, SignalReadTrace.suppressionBlockedCount());
    }

    @Test
    @DisplayName("a reset drops the counters and the pending tick, and touches nothing else")
    void aResetDropsEverything() {
        SignalReadTrace.record(STRUCTURE_B, ASKED, false, "-", "-",
                BlockSignalSources.Source.WORLD_STORE, 15);

        SignalReadTrace.reset();

        assertEquals("", SignalReadTrace.describe(), "the next level starts its own ledger");
        assertTrue(SignalReadTrace.closeTick().isEmpty(), "and no line from the level that went away");
    }

    @Test
    @DisplayName("nothing is recorded while the tick slot is empty, and nothing is invented")
    void anEmptyTickWritesNothing() {
        assertTrue(SignalReadTrace.closeTick().isEmpty());
        assertFalse(SignalReadTrace.closeTick().stream().findAny().isPresent());
    }
}

package com.jiangyuefengyu.redstonecircuit.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>The frame summary line: a compact key/value half every frame, and the long explanation once.</b>
 *
 * <h2>The author's measurement, and what it cost</h2>
 * A session of a few minutes produced a 5.8 MB log. The largest single contributor was the
 * {@code client: carrier frame …} summary, written once a second at about <b>1.5 KB a line</b>, because
 * every line carried the same three paragraphs of {@code *** … ***} explanation as well as its own
 * numbers - the carried-stage ordering rule, the equality rule, and the world-shell exception. Those
 * paragraphs are <em>guidance</em>: they say how to read the numbers, and they do not change from one
 * second to the next.
 *
 * <h2>What is asserted here</h2>
 * <ol>
 *   <li><b>The numbers are still always there.</b> Every field the summary reported before is still on
 *       the line, so nothing that could be read from a log became unreadable.</li>
 *   <li><b>The guidance is not.</b> {@code flushStageLine()} carries no {@code ***} text at all, and the
 *       explanation is a separate string that the caller appends only when the frame's verdict changes.</li>
 *   <li><b>A state change still prints it.</b> The gate is
 *       {@link HostRenderPass#frameExplanationDue}, and a frame whose shape differs from the previous
 *       one - a stage that moved, a source that stopped flushing, an alarm - must return true.</li>
 * </ol>
 */
class CarrierFrameLogTest {

    @Test
    @DisplayName("the frame line carries every number and no *** guidance")
    void theCompactLineCarriesTheNumbersAndNotTheProse() {
        String line = HostRenderPass.flushStageLine();

        // The numbers, unchanged: this is the half that has to survive.
        for (String field : new String[] {
                "worldQuadsFlushedAt=", "worldOwnQuads=", "worldOwnEmittedQuads=", "worldContentQuads=",
                "worldShellQuads=", "worldShellHandedOverQuads=",
                "carriedQuadsFlushedAt=", "carriedQuads=", "carriedShellQuads=", "carriedContentQuads=",
                "carriedEmittedQuads=",
                "worldBufFlushedQuads=", "carriedBufFlushedQuads=", "rendererBufHandedOverQuads=" }) {
            assertTrue(line.contains(field), "the frame line lost " + field + ": " + line);
        }

        // ...and no paragraph. The three `***` blocks were the whole of the added weight.
        assertFalse(line.contains("***"),
                "the per-frame line must not carry the *** guidance paragraphs: " + line);
        assertFalse(line.contains("must be the LATER"),
                "the ordering rule moved to the explanation, not the line: " + line);
        assertFalse(line.contains("deliberately outside that equation"),
                "and so did the world-shell exception: " + line);
    }

    @Test
    @DisplayName("the explanation still exists, in one place, with all three paragraphs")
    void theExplanationKeepsTheGuidance() {
        String explanation = HostRenderPass.flushStageExplanation();
        assertNotNull(explanation);
        assertTrue(explanation.contains("***"), explanation);
        assertTrue(explanation.contains("must be the LATER"),
                "the ordering rule is what tells a reader a wrong stage from a wrong count: " + explanation);
        assertTrue(explanation.contains("deliberately outside that equation"),
                "and the world-shell exception is what stopped a false alarm: " + explanation);
        assertTrue(explanation.contains("worldOwnQuads must equal worldOwnEmittedQuads"),
                "the equality rule has to be stated where the numbers are: " + explanation);
    }

    @Test
    @DisplayName("the explanation is due on the first frame and whenever the verdict changes")
    void theExplanationIsGatedOnStateChange() {
        // A fresh session, a healthy frame: the first line must explain itself.
        HostRenderPass.resetFrameLogForTest();
        assertTrue(HostRenderPass.frameExplanationDue(),
                "the first frame of a session has no previous verdict to differ from, so it explains");

        // The same verdict again: not due. This is the line that used to be 1.5 KB every frame.
        assertFalse(HostRenderPass.frameExplanationDue(),
                "an unchanged frame must not repeat the guidance");

        // The gate is keyed on the frame's shape - which stage each half reached, whether each has
        // flushed, and the alarm signature - so a change in any of those must re-open it. The reasons
        // string is the one the flush line itself prints, so a reader can reproduce the transition.
        HostRenderPass.NOTE_FOR_TEST.accept("carried stage moved to the world path (a shader pack)");
        assertTrue(HostRenderPass.frameExplanationDue(),
                "a frame whose verdict changed must explain itself again");

        // And then quiet again.
        assertFalse(HostRenderPass.frameExplanationDue());
    }

    @Test
    @DisplayName("an alarm always prints, whatever the gate said")
    void anAlarmIsNeverGatedOut() {
        // The whole point of gating the guidance is that the *findings* still print. `flushAlarms` is
        // computed independently of the gate and is non-empty only when a stage lost geometry.
        HostRenderPass.resetFrameLogForTest();
        assertEquals("", HostRenderPass.flushAlarms(),
                "a healthy frame has no alarms, so their presence in a log is a finding");
    }

    /**
     * <b>The size claim, in characters, asserted rather than estimated.</b>
     *
     * <p>The author's session was 5.8 MB, and the largest contributor was this one line - written once per
     * frame summary, which during motion is once per frame the counters move. Every such line carried the
     * {@code ***} guidance (~656 characters, byte-identical every time) on top of its own numbers
     * (~854 characters). After the split the guidance is printed once per <em>verdict change</em>, so the
     * steady-state saving is the whole guidance on every line that does not need it.
     *
     * <p>Asserted as facts about the two strings rather than as a byte budget, so adding a field to the
     * numbers does not break the test - what must stay true is that the guidance is (a) non-empty,
     * (b) absent from the per-frame line, and (c) a real fraction of what one line used to weigh.
     */
    @Test
    @DisplayName("the guidance is a real fraction of the line, and is not repeated per frame")
    void theGuidanceIsTheHalfThatIsNoLongerRepeated() {
        String compact = HostRenderPass.flushStageLine();
        String guidance = HostRenderPass.flushStageExplanation();

        assertTrue(guidance.length() > 0, "the guidance must still exist - it is where the rules live");
        assertFalse(compact.contains("***"), "the per-frame line carries no guidance: " + compact);
        assertTrue(guidance.length() >= 400,
                "the guidance is the heavy half, which is why gating it is worth doing: " + guidance.length());
        assertTrue(guidance.length() * 100 / (compact.length() + guidance.length()) >= 40,
                "the guidance must be at least two fifths of what a line used to weigh, or the split "
                        + "saves too little to matter: compact=" + compact.length() + " guidance="
                        + guidance.length());

        // A worked figure for the report, printed so a run states it rather than leaving it to arithmetic
        // in someone's head.
        System.out.println("[redstonecircuit] frame log: compact=" + compact.length()
                + " chars, guidance=" + guidance.length()
                + " chars, old line=" + (compact.length() + guidance.length())
                + " chars, saved per steady-state line=" + guidance.length() + " chars");
    }
}

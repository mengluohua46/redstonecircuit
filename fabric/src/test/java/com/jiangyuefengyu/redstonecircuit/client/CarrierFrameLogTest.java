package com.jiangyuefengyu.redstonecircuit.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The frame summary line: a compact key/value half every frame, and the long explanation once.
 *
 * <p>The {@code *** … ***} paragraphs - the carried-stage ordering rule, the equality rule and the world-shell
 * exception - are guidance: they say how to read the numbers and do not change from one second to the next, so
 * they are not printed per frame. Every field the summary reports is still on the line, and the explanation is a
 * separate string the caller appends only when the frame's verdict changes
 * ({@link HostRenderPass#frameExplanationDue}).
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

        // ...and no paragraph: the three `***` blocks were the whole of the added weight.
        assertFalse(line.contains("***"),
                "the per-frame line must not carry the *** guidance paragraphs: " + line);
        assertFalse(line.contains("ever reads as a later stage than the world one"),
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
        assertTrue(explanation.contains("carriedQuadsFlushedAt ever reads as a later stage than the world one"),
                "the ordering rule is what tells a reader a wrong stage from a wrong count, and on this loader "
                        + "it reads the other way round: the carried geometry is submitted with the world path, "
                        + "so a carried stage reading LATER than the world's is the fault: " + explanation);
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

        // The same verdict again: not due, so an unchanged frame does not repeat the guidance.
        assertFalse(HostRenderPass.frameExplanationDue(),
                "an unchanged frame must not repeat the guidance");

        // The gate is keyed on the frame's shape - each half's stage, whether each has flushed, the alarm signature.
        HostRenderPass.NOTE_FOR_TEST.accept("carried stage moved to the world path (a shader pack)");
        assertTrue(HostRenderPass.frameExplanationDue(),
                "a frame whose verdict changed must explain itself again");

        // And then quiet again.
        assertFalse(HostRenderPass.frameExplanationDue());
    }

    @Test
    @DisplayName("an alarm always prints, whatever the gate said")
    void anAlarmIsNeverGatedOut() {
        // Gating the guidance must not gate the findings: `flushAlarms` is computed independently of the gate.
        HostRenderPass.resetFrameLogForTest();
        assertEquals("", HostRenderPass.flushAlarms(),
                "a healthy frame has no alarms, so their presence in a log is a finding");
    }

    /**
     * The guidance is a real fraction of a line's weight, and the per-frame line does not carry it.
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
                "the guidance must be at least two fifths of one full line's weight, or the split "
                        + "saves too little to matter: compact=" + compact.length() + " guidance="
                        + guidance.length());

        // A worked figure for the report, printed so a run states it rather than leaving it to arithmetic.
        System.out.println("[redstonecircuit] frame log: compact=" + compact.length()
                + " chars, guidance=" + guidance.length()
                + " chars, old line=" + (compact.length() + guidance.length())
                + " chars, saved per steady-state line=" + guidance.length() + " chars");
    }
}

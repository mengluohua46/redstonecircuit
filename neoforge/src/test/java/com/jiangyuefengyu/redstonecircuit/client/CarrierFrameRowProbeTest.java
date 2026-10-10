package com.jiangyuefengyu.redstonecircuit.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jiangyuefengyu.redstonecircuit.carrier.CarrierHostCheck;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierTrace;

import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * One worked frame, through both rows, checked end to end.
 *
 * <p>The rest of the frame row's tests assert the <em>keys</em>; this one asserts the <em>text</em> of a single frame,
 * because a key written twice and a group that appeared on the small row are both visible only in the text. The lines
 * are printed, so a reader can compare them against what the code produced rather than against a hand-written sample.
 *
 * <p>A non-degenerate frame: {@code shellQuads=30 contentQuads=24 hostsDrawn=1} keeps the geometry verdict empty, so
 * every counter on the verbose row is written by the counter group and by nothing else, and the five sight counts are
 * non-zero so their values can be read back.
 */
class CarrierFrameRowProbeTest {

    private static final ResourceKey<Level> DIMENSION =
            ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
                    ResourceLocation.parse("redstonecircuit:frame_row_probe"));

    @Test
    @DisplayName("one worked frame: the default row is small, the verbose row loses nothing")
    void printBothRows() {
        CarrierRenderTrace.resetLogThrottleForTest();
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
        render.setSight(CarrierHostCheck.Tally.NONE
                .plus(CarrierHostCheck.Sight.DRAWN)
                .plus(CarrierHostCheck.Sight.REFUSED_HIT)
                .plus(CarrierHostCheck.Sight.MISS)
                .plus(CarrierHostCheck.Sight.UNKNOWN_TARGET), null);

        CarrierFrameRow.Frame frame = CarrierFrameRow.Frame.of(render, 3, 0, 0, 0, 3L);
        String compact = CarrierFrameRow.render(frame, false);
        String verbose = CarrierFrameRow.render(frame, true);

        System.out.println("PROBE default-row (" + compact.length() + " chars): " + compact);
        System.out.println("PROBE verbose-row (" + verbose.length() + " chars): " + verbose);

        // The small row: the useful few, no per-frame counter, and a real ceiling.
        assertTrue(compact.length() <= 450, "the default row must fit the ceiling: " + compact);
        for (String counter : CarrierFrameRow.COUNTER_KEYS) {
            assertFalse(compact.contains(counter + "="), "'" + counter + "' is verbose-only");
        }
        assertTrue(compact.contains("frame drew 3"), compact);
        assertTrue(compact.contains("poseAge=3t/0.15s"), compact);
        // The fixture tallies four answers - one drawn, one refused hit, one miss, one unknown target - so the sum is
        // four, and each of the five single counters below stays readable.
        assertTrue(compact.contains("sightChecked=4"), compact);
        assertTrue(compact.contains("sightDrawn=1"), compact);
        assertTrue(compact.contains("sightRefusedHit=1"), compact);
        assertTrue(compact.contains("sightMiss=1"), compact);
        assertTrue(compact.contains("sightUnknownTarget=1"), compact);
        assertFalse(compact.contains("skipped("), "nothing was skipped, so nothing is printed");

        // The big row: every counter, and the default row's own keys too.
        assertTrue(verbose.length() > compact.length(), verbose);
        for (String counter : CarrierFrameRow.COUNTER_KEYS) {
            assertTrue(verbose.contains(counter + "="), "the verbose row lost '" + counter + "'");
        }
        assertTrue(verbose.contains("[overlayMode=ALWAYS"), verbose);
        assertTrue(verbose.contains("flushStages=worldStage=AFTER_ENTITIES"), verbose);
        assertEquals(1, count(verbose, "shellQuads="),
                "the counter group writes each name exactly once: " + verbose);
        assertEquals(1, count(verbose, "hostsDrawn="),
                "and hostsDrawn is that name and no other: " + verbose);
    }

    /** How many times a token occurs, so "exactly once" is a statement about the group. */
    private static int count(String haystack, String needle) {
        int found = 0;
        int at = haystack.indexOf(needle);
        while (at >= 0) {
            found++;
            at = haystack.indexOf(needle, at + needle.length());
        }
        return found;
    }
}

package com.jiangyuefengyu.redstonecircuit.carrier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The frame summary's zero-quad verdict must compare <em>one</em> frame's numbers.
 *
 * <p>The summary and the carried geometry are recorded by <b>different render stages</b>: with no shader pack the
 * summary is written from the world stage ({@code AFTER_ENTITIES}) and the quads are emitted two stages later
 * ({@code AFTER_TRANSLUCENT_BLOCKS}). The summary of frame <i>N</i> therefore carries frame <i>N</i>'s collection
 * beside frame <i>N-1</i>'s quads, so the verdict - "N hosts were collected and not one quad came out" - fires on
 * every frame in which a host first appeared, because the previous frame had nothing to draw yet, and reads as "the
 * renderer emitted nothing", which is not true.
 *
 * <p>The fix is that the collected count travels with the geometry it produced
 * ({@code CarrierTrace.Render#setGeometry(..., hostsCollected, ...)}), so the verdict always compares a frame against
 * itself; the live numbers are unchanged.
 */
class CarrierGeometryVerdictTest {

    private static final ResourceKey<Level> DIMENSION =
            ResourceKey.create(Registries.DIMENSION,
                    ResourceLocation.parse("redstonecircuit:geometry_verdict_test"));

    @Test
    @DisplayName("the geometry that came from an empty frame does not report NOTHING EMITTED")
    void theVerdictComparesOneFrameWithItself() {
        CarrierTrace.Render render = CarrierTrace.render(DIMENSION);

        // This frame collected one host ...
        render.setFrame(1, 0, 0, 0);
        // ... and the geometry being reported was produced by a frame whose collection was empty, which is
        // the ordinary one-frame lag between the summary and the carried stage.
        render.setGeometry(0, 0, 0, 0, null);
        assertEquals("", render.geometryVerdict(),
                "the previous frame had nothing to draw, so 'nothing was emitted' is not a finding about "
                        + "this one - it is the two stages' bookkeeping");
    }

    @Test
    @DisplayName("the real zero-quad frame still speaks, and names its own count")
    void aGenuineZeroStillSpeaks() {
        CarrierTrace.Render render = CarrierTrace.render(DIMENSION);
        render.setFrame(1, 0, 0, 0);
        // The defect shape proper: the collection the quads came from held a host, and no quad was written.
        render.setGeometry(0, 0, 0, 1, null);
        String verdict = render.geometryVerdict();
        assertTrue(verdict.contains("NOTHING EMITTED"), verdict);
        assertTrue(verdict.contains("1 host(s)"), "the sentence names the count it judged: " + verdict);
    }

    @Test
    @DisplayName("a frame that emitted something says nothing, and a partial emission says which")
    void theVerdictIsQuietWhenThereIsNothingToSay() {
        CarrierTrace.Render render = CarrierTrace.render(DIMENSION);
        render.setFrame(1, 0, 0, 0);
        render.setGeometry(30, 24, 1, 1, null);
        assertEquals("", render.geometryVerdict(), "quads came out: no verdict");

        // Two hosts collected, one of them emitted: the parenthetical, not the alarm.
        render.setFrame(2, 0, 0, 0);
        render.setGeometry(30, 24, 1, 2, null);
        String partial = render.geometryVerdict();
        assertTrue(partial.contains("of 2 collected, 1 emitted geometry"), partial);
    }
}

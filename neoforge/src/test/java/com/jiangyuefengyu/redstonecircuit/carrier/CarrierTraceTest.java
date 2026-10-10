package com.jiangyuefengyu.redstonecircuit.carrier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests the three-stage counter: claim, sync, render.
 *
 * <p>The counts have to be separable and per level, so a reader of {@code /rc carrier} can tell which of
 * the three stages stopped instead of seeing "nothing happened".
 */
class CarrierTraceTest {

    private static final ResourceKey<Level> OVERWORLD =
            ResourceKey.create(Registries.DIMENSION, ResourceLocation.withDefaultNamespace("overworld"));
    private static final ResourceKey<Level> NETHER =
            ResourceKey.create(Registries.DIMENSION, ResourceLocation.withDefaultNamespace("the_nether"));

    @BeforeEach
    @AfterEach
    void resetCounters() {
        CarrierTrace.clearAll();
    }

    @Test
    @DisplayName("the three stages are counted separately and printed in order")
    void stagesAreSeparate() {
        CarrierTrace.noteProvisionalClaim(OVERWORLD);
        CarrierTrace.noteProvisionalClaim(OVERWORLD);
        CarrierTrace.noteReconcile(OVERWORLD, true);
        CarrierTrace.noteCarrierSent(OVERWORLD, 2);
        CarrierTrace.notePoseSent(OVERWORLD);
        CarrierTrace.noteClientIndex(OVERWORLD, 1, 2);
        CarrierTrace.noteRender(OVERWORLD, 2, 0, 0, 0);

        CarrierTrace.Claims claims = CarrierTrace.claims(OVERWORLD);
        assertEquals(2, claims.provisionalClaims());
        assertEquals(2, claims.claimed(), "claimed counts both phases");
        assertEquals(1, claims.reconciled());

        CarrierTrace.Sync sync = CarrierTrace.sync(OVERWORLD);
        assertEquals(1, sync.carriersSent());
        assertEquals(2, sync.hostsSent(), "hostsSent follows what the carrier message carried");
        assertEquals(1, sync.posesSent());

        CarrierTrace.Render render = CarrierTrace.render(OVERWORLD);
        assertEquals(1, render.carriersKnown());
        assertEquals(2, render.hostsKnown());
        assertEquals(2, render.drawn());

        List<String> lines = CarrierTrace.describe(OVERWORLD);
        assertEquals(3, lines.size());
        assertTrue(lines.get(0).startsWith("claim:"), lines.get(0));
        assertTrue(lines.get(1).startsWith("sync:"), lines.get(1));
        assertTrue(lines.get(2).startsWith("render:"), lines.get(2));
    }

    @Test
    @DisplayName("a claimed host that was never sent is visibly a stage-2 gap, not a stage-1 one")
    void claimWithoutSyncIsVisible() {
        CarrierTrace.noteEntityClaim(OVERWORLD);

        List<String> lines = CarrierTrace.describe(OVERWORLD);
        assertEquals("claim: claimed=1 (entity=1 provisional=0) reconciled=0 emptyBlocks=0", lines.get(0),
                "stage 1 says the host was claimed");
        assertTrue(lines.get(1).contains("nothing sent"), lines.get(1));
        assertTrue(lines.get(2).contains("has not reported"), lines.get(2));

        // Once something is sent the same line reports it, so the two states are not both silence.
        CarrierTrace.noteCarrierSent(OVERWORLD, 1);
        assertTrue(CarrierTrace.describe(OVERWORLD).get(1).contains("carriersSent=1"),
                CarrierTrace.describe(OVERWORLD).get(1));
    }

    @Test
    @DisplayName("the counts are per level, so two dimensions cannot be confused")
    void countersArePerLevel() {
        CarrierTrace.noteProvisionalClaim(OVERWORLD);
        CarrierTrace.noteProvisionalClaim(OVERWORLD);
        CarrierTrace.noteProvisionalClaim(NETHER);

        assertEquals(2, CarrierTrace.claims(OVERWORLD).claimed());
        assertEquals(1, CarrierTrace.claims(NETHER).claimed());
        assertNotEquals(CarrierTrace.describe(OVERWORLD).get(0),
                CarrierTrace.describe(NETHER).get(0));
    }

    @Test
    @DisplayName("a level with no counters says so rather than reporting zeroes that look like success")
    void untouchedLevelSaysNothingHappened() {
        List<String> lines = CarrierTrace.describe(OVERWORLD);
        assertEquals(3, lines.size());
        assertTrue(lines.get(0).contains("nothing picked up"), lines.get(0));
        assertTrue(lines.get(1).contains("nothing sent"), lines.get(1));
        assertTrue(lines.get(2).contains("has not reported"), lines.get(2));
    }

    @Test
    @DisplayName("reconcile failures are counted and named, never silently absorbed")
    void reconcileFailuresAreVisible() {
        CarrierTrace.noteReconcileFailure(OVERWORLD);

        String line = CarrierTrace.claims(OVERWORLD).describe();
        assertTrue(line.contains("RECONCILE-FAILED=1"), line);
    }

    @Test
    @DisplayName("a reconciliation that moved nothing is counted apart from one that did")
    void reconcileNoOpsAreVisible() {
        CarrierTrace.noteReconcile(OVERWORLD, false);

        CarrierTrace.Claims claims = CarrierTrace.claims(OVERWORLD);
        assertEquals(0, claims.reconciled());
        assertEquals(1, claims.reconcileNoOps());
        assertTrue(claims.describe().contains("reconcileNoOp=1"));
    }

    @Test
    @DisplayName("the landed half of the frame report is counted, including the deferred yield")
    void landedCountersAreCountedAndPrinted() {
        // A landed record skipped because the world path had no block to draw over; expected to stay zero.
        CarrierTrace.render(OVERWORLD).setLanded(2, 1, 0, 3);

        CarrierTrace.Render render = CarrierTrace.render(OVERWORLD);
        assertEquals(2, render.landedHosts());
        assertEquals(1, render.landedSkippedBlock());
        assertEquals(0, render.landedDeferred());
        assertEquals(3, render.carriedSkippedBlock());

        String line = render.describe();
        assertTrue(line.contains("landed=2"), line);
        assertTrue(line.contains("landedWorldHost=1"), line);
        assertTrue(line.contains("landedDeferred=0"), line);
        assertTrue(line.contains("carriedWorldHost=3"), line);
    }

    @Test
    @DisplayName("a nonzero deferred count is readable, because it means the two yield gates drifted")
    void aNonzeroDeferredCountIsReadable() {
        CarrierTrace.render(OVERWORLD).setLanded(4, 0, 4, 0);

        String line = CarrierTrace.render(OVERWORLD).describe();
        assertTrue(line.contains("landedDeferred=4"), line);
        assertTrue(line.contains("landedWorldHost=0"), line);
    }

    @Test
    @DisplayName("clearing one level leaves the others alone")
    void clearIsPerLevel() {
        CarrierTrace.noteEntityClaim(OVERWORLD);
        CarrierTrace.noteEntityClaim(NETHER);

        CarrierTrace.clear(OVERWORLD);

        // Read the description first: asking for the counters would create an empty record and hide "no record".
        assertTrue(CarrierTrace.describe(OVERWORLD).get(0).contains("nothing picked up"),
                CarrierTrace.describe(OVERWORLD).get(0));
        assertEquals(0, CarrierTrace.claims(OVERWORLD).claimed());
        assertEquals(1, CarrierTrace.claims(NETHER).claimed(), "the other level is untouched");
    }
}

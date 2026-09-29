package com.jiangyuefengyu.redstonecircuit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Properties;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>P1.58: a release package logs lightly by default, a development build keeps the detailed log.</b>
 *
 * <p>The mapping is tested on plain data rather than through a jar, because the thing that can go wrong is
 * the <em>rule</em>: a marker that says one thing and a default that does the other is a silent regression
 * (the detailed log is what every diagnosis in this project was read out of), and the reverse direction -
 * a release that quietly keeps per-tick diagnostics on - is exactly what the author asked to stop.
 *
 * <p>The consistency assertion is written so that it holds in BOTH build modes: the release gate runs this
 * same suite with {@code -Prelease}, and a test that demanded "detailed" would then have to be skipped.
 */
class BuildFlagsTest {

    @Test
    @DisplayName("the marker's own value decides: true is a release, absent or false is not")
    void theMarkerDecides() {
        Properties release = new Properties();
        release.setProperty("release", "true");
        assertTrue(BuildFlags.releaseFrom(release), "release=true is a release package");

        Properties development = new Properties();
        development.setProperty("release", "false");
        assertFalse(BuildFlags.releaseFrom(development), "release=false is a development build");
        assertFalse(BuildFlags.releaseFrom(new Properties()), "an empty marker is not a release");
        assertFalse(BuildFlags.releaseFrom(null), "and neither is no marker at all (an IDE run)");
    }

    @Test
    @DisplayName("the light log is exactly the release package's default, and the two never disagree")
    void theDefaultFollowsThePackage() {
        assertEquals(!BuildFlags.release(), BuildFlags.defaultDebugLog(),
                "detailed while being built and tested, light in a release package - and the fallback is "
                        + "always the detailed one, because a light log loses evidence and a detailed one "
                        + "only costs bytes");
        // WITHOUT the run's own request: this is the package's default, the number a player's log obeys.
        // The test task sets `-Dredstonecircuit.debugLog=true` (see build.gradle) so that the rest of the
        // suite keeps the detailed evidence it asserts on, so the property is cleared here and restored
        // afterwards - the only assertion in the repo that reads the released default.
        String saved = System.getProperty("redstonecircuit.debugLog");
        try {
            System.clearProperty("redstonecircuit.debugLog");
            assertEquals(BuildFlags.defaultDebugLog(), RCConfig.debugLog(),
                    "the switch really takes its default from here, so the rule is not a comment");
        } finally {
            if (saved != null) {
                System.setProperty("redstonecircuit.debugLog", saved);
            }
        }
        System.setProperty("redstonecircuit.debugLog", "true");
        assertTrue(RCConfig.debugLog(), "and a run that asks for the detailed log gets it, release or not");
        System.setProperty("redstonecircuit.debugLog", "false");
        assertFalse(RCConfig.debugLog(), "including the other direction, so the property is not one-way");
        if (saved == null) {
            System.clearProperty("redstonecircuit.debugLog");
        } else {
            System.setProperty("redstonecircuit.debugLog", saved);
        }
    }
}
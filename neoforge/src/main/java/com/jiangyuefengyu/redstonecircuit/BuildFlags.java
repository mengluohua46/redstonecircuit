package com.jiangyuefengyu.redstonecircuit;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import org.jetbrains.annotations.Nullable;

/**
 * <b>P1.58: whether this jar is a RELEASE package, and the one thing that follows from it.</b>
 *
 * <h2>The rule (author, 2026-09-28)</h2>
 * "A release package logs lightly by default - fabric included; while it is being built and tested, keep
 * the detailed log." The detailed log is what every diagnosis of this mod was read
 * out of, so it must stay the default for the builds this project actually iterates with; what a
 * released jar must not do is fill a player's log with per-tick diagnostics nobody asked for.
 *
 * <h2>How the jar knows</h2>
 * The build writes {@code redstonecircuit-build.properties} into the jar
 * ({@code release=true} when built with {@code -Prelease}, {@code false} otherwise; see the two
 * {@code build.gradle} files and §60). This class reads that marker once and answers
 * {@link #defaultDebugLog()} - the <b>default</b> for {@code debugLog}, not a lock: the switch itself is
 * unchanged, so a player chasing a bug can still turn the detailed log on, and a developer's build has it
 * on already.
 *
 * <h2>Why the fallback is "detailed"</h2>
 * No marker at all - an IDE run, a unit test, a jar built by something that does not know about the
 * marker - answers "not a release", i.e. detailed. The two directions are not symmetric: a detailed log
 * is verbose, a light one is <b>evidence that is not there</b> when the next bug report arrives.
 */
public final class BuildFlags {

    /** The marker's path inside the jar; written by the build, read once here. */
    private static final String RESOURCE = "/redstonecircuit-build.properties";

    private static final boolean RELEASE = readRelease();

    private BuildFlags() {
    }

    /** True when this jar was built as a release package ({@code -Prelease}). */
    public static boolean release() {
        return RELEASE;
    }

    /**
     * The default for {@code debugLog}: on while building and testing, off in a release package.
     *
     * <p>Asked by {@code RCConfig} for its own default, so there is exactly one place that decides this.
     */
    public static boolean defaultDebugLog() {
        return !RELEASE;
    }

    /**
     * The mapping itself, on plain data - so it is testable without building a jar.
     *
     * @param marker the parsed marker, or {@code null} when there is none
     * @return whether {@code release} is set to {@code true} in it
     */
    static boolean releaseFrom(@Nullable Properties marker) {
        return marker != null && Boolean.parseBoolean(marker.getProperty("release", "false"));
    }

    private static boolean readRelease() {
        try (InputStream in = BuildFlags.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                return false;
            }
            Properties marker = new Properties();
            marker.load(in);
            return releaseFrom(marker);
        } catch (IOException | RuntimeException | LinkageError unreadable) {
            // An unreadable marker is "cannot prove this is a release", and the safe direction for evidence
            // is the detailed log.
            return false;
        }
    }
}

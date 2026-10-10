package com.jiangyuefengyu.redstonecircuit;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import org.jetbrains.annotations.Nullable;

/**
 * Whether this jar is a RELEASE package: a release package logs lightly by default, while a build being built and
 * tested keeps the detailed log.
 *
 * <p>The build writes {@code redstonecircuit-build.properties} into the jar ({@code release=true} with
 * {@code -Prelease}); no marker at all - an IDE run, a unit test - means "not a release". This class reads that
 * marker once and answers {@link #defaultDebugLog()}, the default for {@code debugLog}, not a lock.
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

    /** The default for {@code debugLog}: on while building and testing, off in a release package; {@code RCConfig} asks this. */
    public static boolean defaultDebugLog() {
        return !RELEASE;
    }

    /** The mapping itself, on plain data - so it is testable without building a jar. */
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
            // An unreadable marker cannot prove this is a release, and the safe direction is the detailed log.
            return false;
        }
    }
}

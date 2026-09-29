package com.jiangyuefengyu.redstonecircuit;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;

import com.jiangyuefengyu.redstonecircuit.config.JsonConfig;
import com.mojang.logging.LogUtils;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Common (server-authoritative) configuration.
 *
 * <h2>What changed from the NeoForge build, and what deliberately did not</h2>
 * NeoForge supplied a {@code ModConfigSpec}: a builder that declared each option, its type, its range
 * and its comments, and produced a TOML file plus a config screen. Fabric has no configuration system,
 * and pulling one in (Cloth Config and friends) would turn an optional convenience into something every
 * player has to install. The options here are a flat list of flags, numbers and strings, so they became
 * a plain JSON file read once at startup - {@link JsonConfig}, which writes every key back out on first
 * run, so the file on disk doubles as the documentation the comments used to be.
 *
 * <p>What did <b>not</b> change is this class's surface: every getter keeps its name and its fallback
 * behaviour, and {@link #LOGGER} stays a public field, so no other file in the mod had to be touched.
 *
 * <p>The defaults live in {@link #DEFAULTS} rather than at each getter, because they also have to be
 * the values written into a fresh file. Comments on individual options moved into the JSON keys' names
 * and, where an option is subtle, into the javadoc below.
 */
public final class RCConfig {

    public static final Logger LOGGER = LogUtils.getLogger();

    /** Where the file lives; {@code config/redstonecircuit.json} in the game directory. */
    private static final String FILE_NAME = "redstonecircuit.json";

    /**
     * Every option, in the order it is written to a fresh file.
     *
     * <p>{@code hostOverlayColor}, {@code hostOverlayFrameColor} and {@code superconductorFrameColor}
     * are {@code RRGGBB} hex, tolerating a leading '#'.
     */
    private static final Map<String, Object> DEFAULTS = new LinkedHashMap<>();

    static {
        DEFAULTS.put("validateHosts", true);
        DEFAULTS.put("debugLog", BuildFlags.defaultDebugLog());
        DEFAULTS.put("allowReplace", false);
        DEFAULTS.put("hostOverlayMode", "GOGGLES");
        DEFAULTS.put("hostOverlayAlpha", 150);
        DEFAULTS.put("hostOverlayColor", "9FB6FF");
        DEFAULTS.put("hostOverlayFrameAlpha", 170);
        DEFAULTS.put("hostOverlayFrameColor", "E03020");
        DEFAULTS.put("superconductorFrameColor", "FF8C00");
        DEFAULTS.put("hostAcceptsStrongPower", true);
        DEFAULTS.put("hostConductsStrongPower", false);
        DEFAULTS.put("showInnerComponents", true);
        DEFAULTS.put("seeInnerComponentsThroughWalls", false);
        DEFAULTS.put("wrenchNeedsGoggles", true);
        DEFAULTS.put("wrenchClearOnSameBlock", true);
        DEFAULTS.put("rotationRodNeedsGoggles", true);
        DEFAULTS.put("traceSlotSync", false);
        DEFAULTS.put("slotSyncRange", 0.0);
    }

    /**
     * The file, or {@code null} when it could not be opened at all.
     *
     * <p>It is deliberately not allowed to throw: this class is loaded by the unit tests too, where
     * there is no game directory and no loader, and a configuration file must never be the reason a
     * test JVM or a server fails to start. Every getter falls back to the default above.
     */
    private static final JsonConfig CONFIG = open();

    private static JsonConfig open() {
        try {
            Path path = FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
            return JsonConfig.load(path, DEFAULTS);
        } catch (Throwable unavailable) {
            // No loader (unit tests) or an unwritable directory: the defaults are the answer.
            return null;
        }
    }

    /** When false, placement is refused for every block and only the debug command can store data. */
    public enum OverlayMode {
        GOGGLES,
        ALWAYS,
        OFF
    }

    private RCConfig() {
    }

    public static boolean debugLog() {
        // P1.58: the package default (BuildFlags) and the config file both describe the PACKAGE; a system
        // property is how one RUN asks for the detailed log - on a released jar, and in this project's own
        // test task, which sets it so the suite keeps the evidence its assertions read.
        Boolean forced = forcedDebugLog();
        return forced != null ? forced : bool("debugLog", BuildFlags.defaultDebugLog());
    }

    /** {@code -Dredstonecircuit.debugLog=true|false}, or {@code null} when the run did not ask. */
    private static Boolean forcedDebugLog() {
        String forced = System.getProperty("redstonecircuit.debugLog");
        if ("true".equalsIgnoreCase(forced)) {
            return Boolean.TRUE;
        }
        return "false".equalsIgnoreCase(forced) ? Boolean.FALSE : null;
    }

    public static boolean allowReplace() {
        return bool("allowReplace", false);
    }

    public static boolean validateHosts() {
        return bool("validateHosts", true);
    }

    /**
     * Whether inner redstone also accepts the strong power a neighbouring solid block carries.
     *
     * <p>See the design notes: this is vanilla behaviour, and turning it off narrows what can feed a
     * host block down to the blocks that emit a signal themselves.
     */
    public static boolean hostAcceptsStrongPower() {
        return bool("hostAcceptsStrongPower", true);
    }

    /**
     * Whether a host block carries the strong power pushed into it onwards, as a plain solid block does.
     *
     * <p>Off by default: a host block is the component inside it, so a diode pointing into one hands its
     * signal to that component rather than turning the block into a redstone block for its neighbours.
     */
    public static boolean hostConductsStrongPower() {
        return bool("hostConductsStrongPower", false);
    }

    /**
     * Shell colour as ARGB, or 0 when the shell is switched off or fully transparent.
     *
     * <p>Whether it is <em>drawn</em> at all is the renderer's business (it also depends on the
     * goggles being worn); this only answers "what colour".
     */
    public static int hostOverlayColor() {
        int alpha = intValue("hostOverlayAlpha", 150);
        return alpha <= 0 ? 0
                : (alpha << 24) | parseRgb(string("hostOverlayColor", "9FB6FF"), 0x9FB6FF);
    }

    /** Border colour as ARGB, or 0 when there is no border to draw. */
    public static int hostOverlayFrameColor() {
        int alpha = intValue("hostOverlayFrameAlpha", 170);
        return alpha <= 0 ? 0
                : (alpha << 24) | parseRgb(string("hostOverlayFrameColor", "E03020"), 0xE03020);
    }

    /**
     * Border colour for a block holding superconducting wire, or 0 when there is no border to draw.
     *
     * <p>Shares the border's alpha with the ordinary frame: this is the same frame in another colour,
     * not a second decoration.
     */
    public static int superconductorFrameColor() {
        int alpha = intValue("hostOverlayFrameAlpha", 170);
        return alpha <= 0 ? 0
                : (alpha << 24) | parseRgb(string("superconductorFrameColor", "FF8C00"), 0xFF8C00);
    }

    /** When a block holding redstone is drawn as a translucent cube. */
    public static OverlayMode overlayMode() {
        String name = string("hostOverlayMode", "GOGGLES");
        for (OverlayMode mode : OverlayMode.values()) {
            if (mode.name().equalsIgnoreCase(name)) {
                return mode;
            }
        }
        return OverlayMode.GOGGLES;
    }

    /** Whether the component inside the block is drawn. */
    public static boolean showInnerComponents() {
        return bool("showInnerComponents", true);
    }

    /**
     * Whether the component is drawn even when the line of sight to its block is blocked.
     *
     * <p>Off by default: drawing through a wall is X-ray vision, and it would also show the inside of
     * every host in the level at once, in whatever order the sections happen to be visited.
     */
    public static boolean seeInnerComponentsThroughWalls() {
        return bool("seeInnerComponentsThroughWalls", false);
    }

    public static boolean wrenchNeedsGoggles() {
        return bool("wrenchNeedsGoggles", true);
    }

    public static boolean rotationRodNeedsGoggles() {
        return bool("rotationRodNeedsGoggles", true);
    }

    public static boolean wrenchClearOnSameBlock() {
        return bool("wrenchClearOnSameBlock", true);
    }

    public static boolean traceSlotSync() {
        return bool("traceSlotSync", false);
    }

    /** Radius for component sync, in blocks; 0 means "everyone in the dimension". */
    public static double slotSyncRange() {
        if (CONFIG == null) {
            return 0.0;
        }
        try {
            return CONFIG.getFloat("slotSyncRange", 0.0F);
        } catch (RuntimeException broken) {
            return 0.0;
        }
    }

    // --------------------------------------------------------------- helpers --

    private static boolean bool(String key, boolean fallback) {
        if (CONFIG == null) {
            return fallback;
        }
        try {
            return CONFIG.getBoolean(key, fallback);
        } catch (RuntimeException broken) {
            return fallback;
        }
    }

    private static int intValue(String key, int fallback) {
        if (CONFIG == null) {
            return fallback;
        }
        try {
            return CONFIG.getInt(key, fallback);
        } catch (RuntimeException broken) {
            return fallback;
        }
    }

    private static String string(String key, String fallback) {
        if (CONFIG == null) {
            return fallback;
        }
        try {
            return CONFIG.getString(key, fallback);
        } catch (RuntimeException broken) {
            return fallback;
        }
    }

    /** Parses {@code RRGGBB}, tolerating a leading '#', and falls back rather than throwing. */
    private static int parseRgb(String hex, int fallback) {
        String cleaned = hex == null ? "" : hex.trim();
        if (cleaned.startsWith("#")) {
            cleaned = cleaned.substring(1);
        }
        if (cleaned.length() != 6) {
            return fallback;
        }
        try {
            return Integer.parseInt(cleaned, 16) & 0xFFFFFF;
        } catch (NumberFormatException invalid) {
            return fallback;
        }
    }
}

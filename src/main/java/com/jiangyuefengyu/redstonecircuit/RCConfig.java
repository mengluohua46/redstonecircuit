package com.jiangyuefengyu.redstonecircuit;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Common (server-authoritative) configuration. */
public final class RCConfig {

    public static final Logger LOGGER = LogUtils.getLogger();

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    /** When false, placement is refused for every block and only the debug command can store data. */
    public static final ModConfigSpec.BooleanValue VALIDATE_HOSTS = BUILDER
            .comment("Check that a block may host inner redstone (full, opaque cube) before storing anything.",
                    "Turn this off to allow putting redstone inside ANY block (debug/creative only).")
            .define("validateHosts", true);

    public static final ModConfigSpec.BooleanValue DEBUG_LOG = BUILDER
            .comment("Log every placement/retrieval, including refused ones, to the game log.",
                    "On by default while the mod is in development; set to false once it is stable.")
            .define("debugLog", true);

    public static final ModConfigSpec.BooleanValue ALLOW_REPLACE = BUILDER
            .comment("Allow shift-right-clicking a block that already holds a component to replace it.",
                    "Off by default: a second placement falls through to vanilla instead.")
            .define("allowReplace", false);

    // -------------------------------------------------------- host appearance --
    // These four only affect what a client draws; they are read locally and never sent anywhere, so
    // putting them in the common file (rather than a second, client-only one) costs nothing.

    public static final ModConfigSpec.BooleanValue HOST_OVERLAY = BUILDER
            .comment("Draw a translucent shell around blocks that hold a redstone component, so they",
                    "can be picked out from the outside. Purely visual.")
            .define("hostOverlay", true);

    public static final ModConfigSpec.IntValue HOST_OVERLAY_ALPHA = BUILDER
            .comment("Opacity of that shell, 0-255. Around 90 reads as frosted glass while leaving the",
                    "block's own texture recognisable; 0 keeps the border only.")
            .defineInRange("hostOverlayAlpha", 90, 0, 255);

    public static final ModConfigSpec.ConfigValue<String> HOST_OVERLAY_COLOR = BUILDER
            .comment("Shell colour as RRGGBB hex (a leading '#' is allowed).")
            .define("hostOverlayColor", "9FB6FF");

    public static final ModConfigSpec.IntValue HOST_OVERLAY_FRAME_ALPHA = BUILDER
            .comment("Opacity of the border drawn around each face of the shell, 0-255.",
                    "0 draws the shell without a border.")
            .defineInRange("hostOverlayFrameAlpha", 170, 0, 255);

    public static final ModConfigSpec.ConfigValue<String> HOST_OVERLAY_FRAME_COLOR = BUILDER
            .comment("Border colour as RRGGBB hex (a leading '#' is allowed).")
            .define("hostOverlayFrameColor", "E03020");

    public static final ModConfigSpec.BooleanValue HOST_ACCEPTS_STRONG_POWER = BUILDER
            .comment("Whether a host block also accepts the strong power a neighbouring SOLID block carries.",
                    "On (vanilla semantics): a repeater or torch pushing into a stone block makes redstone next",
                    "to that block live, exactly as it does in vanilla - and a stone block that a wire, torch",
                    "or repeater elsewhere has charged will therefore light the redstone inside the block",
                    "beside it. Turn this OFF to have inner redstone react only to blocks that emit a signal",
                    "themselves, which is friendlier when vanilla wiring runs alongside your host blocks.")
            .define("hostAcceptsStrongPower", true);

    public static final ModConfigSpec SPEC = BUILDER.build();

    private RCConfig() {
    }

    public static boolean debugLog() {
        // Config values throw while the config has not been loaded yet (e.g. during datagen), hence
        // the fallbacks throughout this class.
        return boolOr(DEBUG_LOG, true);
    }

    public static boolean allowReplace() {
        return boolOr(ALLOW_REPLACE, false);
    }

    public static boolean validateHosts() {
        return boolOr(VALIDATE_HOSTS, true);
    }

    /**
     * Whether inner redstone also accepts the strong power a neighbouring solid block carries.
     *
     * <p>See the config comment: this is vanilla behaviour, and turning it off narrows what can feed a
     * host block down to the blocks that emit a signal themselves.
     */
    public static boolean hostAcceptsStrongPower() {
        return boolOr(HOST_ACCEPTS_STRONG_POWER, true);
    }

    /** Shell colour as ARGB, or 0 when the shell is switched off or fully transparent. */
    public static int hostOverlayColor() {
        if (!boolOr(HOST_OVERLAY, true)) {
            return 0;
        }
        int alpha = intOr(HOST_OVERLAY_ALPHA, 90);
        return alpha <= 0 ? 0
                : (alpha << 24) | parseRgb(stringOr(HOST_OVERLAY_COLOR, "9FB6FF"), 0x9FB6FF);
    }

    /** Border colour as ARGB, or 0 when there is no border to draw. */
    public static int hostOverlayFrameColor() {
        if (!boolOr(HOST_OVERLAY, true)) {
            return 0;
        }
        int alpha = intOr(HOST_OVERLAY_FRAME_ALPHA, 170);
        return alpha <= 0 ? 0
                : (alpha << 24) | parseRgb(stringOr(HOST_OVERLAY_FRAME_COLOR, "E03020"), 0xE03020);
    }

    // --------------------------------------------------------------- helpers --

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

    private static boolean boolOr(ModConfigSpec.BooleanValue value, boolean fallback) {
        try {
            return value.get();
        } catch (IllegalStateException notLoadedYet) {
            return fallback;
        }
    }

    private static int intOr(ModConfigSpec.IntValue value, int fallback) {
        try {
            return value.get();
        } catch (IllegalStateException notLoadedYet) {
            return fallback;
        }
    }

    private static String stringOr(ModConfigSpec.ConfigValue<String> value, String fallback) {
        try {
            return value.get();
        } catch (IllegalStateException notLoadedYet) {
            return fallback;
        }
    }
}

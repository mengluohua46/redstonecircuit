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
    // These only affect what a client draws; they are read locally and never sent anywhere, so
    // putting them in the common file (rather than a second, client-only one) costs nothing.

    public static final ModConfigSpec.EnumValue<OverlayMode> HOST_OVERLAY_MODE = BUILDER
            .comment("When the block containing redstone is drawn as a translucent, framed cube with the",
                    "component visible inside it.",
                    "GOGGLES - only while wearing the redstone goggles, as the design asks (R6);",
                    "ALWAYS  - hand out the effect for free, useful while testing;",
                    "OFF     - never, exactly as if the mod drew nothing.")
            .defineEnum("hostOverlayMode", OverlayMode.GOGGLES);

    /** When a host block is drawn specially. */
    public enum OverlayMode {
        GOGGLES,
        ALWAYS,
        OFF
    }

    public static final ModConfigSpec.IntValue HOST_OVERLAY_ALPHA = BUILDER
            .comment("Opacity of that shell, 0-255. Around 150 reads as frosted glass - the component",
                    "inside stays clearly visible; 0 draws the border only.",
                    "Switch the goggle check off with hostOverlayMode=ALWAYS to see the difference.")
            .defineInRange("hostOverlayAlpha", 150, 0, 255);

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

    public static final ModConfigSpec.BooleanValue INNER_COMPONENTS = BUILDER
            .comment("Draw the component itself inside the shell, so a wire, a repeater's facing and a",
                    "lit torch can be read from outside the block. Off leaves only the coloured shell.")
            .define("showInnerComponents", true);

    public static final ModConfigSpec.BooleanValue INNER_VISIBLE_THROUGH_WALLS = BUILDER
            .comment("Draw the component even when a solid block is in the way. Off (the default) means",
                    "the inside of a block is only shown when you can actually see that block, so the",
                    "effect never becomes X-ray vision through a wall.")
            .define("seeInnerComponentsThroughWalls", false);

    // ----------------------------------------------------------------- wrench --

    public static final ModConfigSpec.BooleanValue WRENCH_NEEDS_GOGGLES = BUILDER
            .comment("Require the redstone goggles to be worn before the wrench will do anything, as the",
                    "design asks (R7). Turn it off to adjust connections without them.")
            .define("wrenchNeedsGoggles", true);

    public static final ModConfigSpec.BooleanValue WRENCH_CLEAR_ON_SAME_BLOCK = BUILDER
            .comment("Shift-right-clicking the block you already selected with the wrench clears all of",
                    "that block's connection overrides. On by default: without it there is no way back to",
                    "automatic behaviour from the item itself.")
            .define("wrenchClearOnSameBlock", true);

    // ------------------------------------------------------------------- sync --

    public static final ModConfigSpec.BooleanValue TRACE_SLOT_SYNC = BUILDER
            .comment("Log every component update sent to clients. Very noisy while a circuit runs; it",
                    "answers 'is the client being told about this block at all'.")
            .define("traceSlotSync", false);

    public static final ModConfigSpec.DoubleValue SLOT_SYNC_RANGE = BUILDER
            .comment("How far component updates reach, in blocks. 0 sends them to everyone in the",
                    "dimension, which is the safe default: a client that misses an update keeps drawing",
                    "the old state until something re-sends it.")
            .defineInRange("slotSyncRange", 0.0, 0.0, 512.0);

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

    /**
     * Shell colour as ARGB, or 0 when the shell is switched off or fully transparent.
     *
     * <p>Whether it is <em>drawn</em> at all is the renderer's business (it also depends on the
     * goggles being worn); this only answers "what colour".
     */
    public static int hostOverlayColor() {
        int alpha = intOr(HOST_OVERLAY_ALPHA, 150);
        return alpha <= 0 ? 0
                : (alpha << 24) | parseRgb(stringOr(HOST_OVERLAY_COLOR, "9FB6FF"), 0x9FB6FF);
    }

    /** Border colour as ARGB, or 0 when there is no border to draw. */
    public static int hostOverlayFrameColor() {
        int alpha = intOr(HOST_OVERLAY_FRAME_ALPHA, 170);
        return alpha <= 0 ? 0
                : (alpha << 24) | parseRgb(stringOr(HOST_OVERLAY_FRAME_COLOR, "E03020"), 0xE03020);
    }

    /** When a block holding redstone is drawn as a translucent cube. */
    public static OverlayMode overlayMode() {
        try {
            return HOST_OVERLAY_MODE.get();
        } catch (IllegalStateException notLoadedYet) {
            return OverlayMode.GOGGLES;
        }
    }

    /** Whether the component inside the block is drawn. */
    public static boolean showInnerComponents() {
        return boolOr(INNER_COMPONENTS, true);
    }

    /**
     * Whether the component is drawn even when the line of sight to its block is blocked.
     *
     * <p>Off by default: drawing through a wall is X-ray vision, and it would also show the inside of
     * every host in the level at once, in whatever order the sections happen to be visited.
     */
    public static boolean seeInnerComponentsThroughWalls() {
        return boolOr(INNER_VISIBLE_THROUGH_WALLS, false);
    }

    public static boolean wrenchNeedsGoggles() {
        return boolOr(WRENCH_NEEDS_GOGGLES, true);
    }

    public static boolean wrenchClearOnSameBlock() {
        return boolOr(WRENCH_CLEAR_ON_SAME_BLOCK, true);
    }

    public static boolean traceSlotSync() {
        return boolOr(TRACE_SLOT_SYNC, false);
    }

    /** Radius for component sync, in blocks; 0 means "everyone in the dimension". */
    public static double slotSyncRange() {
        try {
            return SLOT_SYNC_RANGE.get();
        } catch (IllegalStateException notLoadedYet) {
            return 0.0;
        }
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

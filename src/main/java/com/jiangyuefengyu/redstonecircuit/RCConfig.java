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

    public static final ModConfigSpec SPEC = BUILDER.build();

    private RCConfig() {
    }

    public static boolean debugLog() {
        // Config values throw while the config has not been loaded yet (e.g. during datagen).
        try {
            return DEBUG_LOG.get();
        } catch (IllegalStateException notLoadedYet) {
            return true;
        }
    }

    public static boolean allowReplace() {
        try {
            return ALLOW_REPLACE.get();
        } catch (IllegalStateException notLoadedYet) {
            return false;
        }
    }

    public static boolean validateHosts() {
        try {
            return VALIDATE_HOSTS.get();
        } catch (IllegalStateException notLoadedYet) {
            return true;
        }
    }
}

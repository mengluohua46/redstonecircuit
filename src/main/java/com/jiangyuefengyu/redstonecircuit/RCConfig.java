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
            .comment("Log every placement/retrieval to the game log.")
            .define("debugLog", false);

    public static final ModConfigSpec.IntValue MAX_COMPONENTS_PER_BLOCK = BUILDER
            .comment("Maximum number of redstone components stored in a single block (1-6).")
            .defineInRange("maxComponentsPerBlock", 6, 1, 6);

    public static final ModConfigSpec SPEC = BUILDER.build();

    private RCConfig() {
    }

    public static boolean debugLog() {
        // Config values throw while the config has not been loaded yet (e.g. during datagen).
        try {
            return DEBUG_LOG.get();
        } catch (IllegalStateException notLoadedYet) {
            return false;
        }
    }

    public static int maxComponentsPerBlock() {
        try {
            return MAX_COMPONENTS_PER_BLOCK.get();
        } catch (IllegalStateException notLoadedYet) {
            return 6;
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

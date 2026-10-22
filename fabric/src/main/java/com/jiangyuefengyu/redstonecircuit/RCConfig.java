package com.jiangyuefengyu.redstonecircuit;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
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

    /**
     * What a capture refuses to touch, before any server changes it.
     *
     * <p>Bedrock, the portals and the command blocks: a captured one can be released anywhere, which
     * turns a structure block into a way of moving things the game deliberately keeps still.
     */
    private static final List<String> DEFAULT_BLOCK_BLACKLIST = List.of(
            "minecraft:bedrock",
            "minecraft:barrier",
            "minecraft:command_block",
            "minecraft:chain_command_block",
            "minecraft:repeating_command_block",
            "minecraft:structure_block",
            "minecraft:structure_void",
            "minecraft:jigsaw",
            "minecraft:end_portal",
            "minecraft:end_portal_frame",
            "minecraft:end_gateway",
            "minecraft:nether_portal",
            "minecraft:light");

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
        DEFAULTS.put("placerCreatesStructures", false);
        DEFAULTS.put("traceSlotSync", false);
        DEFAULTS.put("slotSyncRange", 0.0);
        DEFAULTS.put("forceOverlayDraw", false);
        DEFAULTS.put("innerAdjustNeedsGoggles", true);

        // A moving structure's load: whether components ride it at all, how the bridge back into the
        // world behaves, and which of the mod's tools still work on what it is carrying.
        DEFAULTS.put("carrierEnabled", true);
        DEFAULTS.put("traceCarrier", false);
        DEFAULTS.put("carrierFreezeWorldIo", false);
        DEFAULTS.put("carrierStructureSignals", true);
        DEFAULTS.put("carrierNotifyStructureNeighbours", true);
        DEFAULTS.put("carrierBlockDrops", true);
        DEFAULTS.put("adoptWorldComponents", true);
        DEFAULTS.put("carrierSelfHealCheckPerTick", 8);
        DEFAULTS.put("carrierWorldReadsProjection", true);
        DEFAULTS.put("carriedWrench", true);
        DEFAULTS.put("carriedRotationRod", true);
        DEFAULTS.put("carriedInsert", true);
        DEFAULTS.put("carriedInsertPriority", true);
        DEFAULTS.put("carriedSwitches", true);
        DEFAULTS.put("carriedJade", true);
        DEFAULTS.put("carrierPoseStaleTicks", 60);
        DEFAULTS.put("carrierPoseHeartbeatTicks", 20);
        DEFAULTS.put("carrierPoseSampleTicks", 2);
        DEFAULTS.put("carrierSolveEnabled", true);
        DEFAULTS.put("carrierSolveMaxComponentsPerTick", 64);
        DEFAULTS.put("carrierSolveMaxNodesPerCarrier", 256);
        DEFAULTS.put("traceCarrierSolve", false);

        // The solver's own diagnostics: noise that is worth having while a build is being understood.
        DEFAULTS.put("verboseSolverLog", false);
        DEFAULTS.put("verboseRenderLog", false);
        DEFAULTS.put("solverSpinWarnPerTick", 8);
        DEFAULTS.put("solverCoalesceNotifications", true);

        // The structure block: capture limits, the preview that travels with a product, the manual
        // panel, the pocket that holds the blocks, and how a release is paced.
        DEFAULTS.put("structureMaxSize", 48);
        DEFAULTS.put("structurePreviewBytes", 65536);
        DEFAULTS.put("structurePreviewCells", 4096);
        DEFAULTS.put("structurePreviewCached", true);
        DEFAULTS.put("structurePreviewCull", true);
        DEFAULTS.put("structurePreviewFaces", true);
        DEFAULTS.put("structurePreviewIconDetail", true);
        DEFAULTS.put("structureCaptureContextShell", true);
        DEFAULTS.put("structureManualRange", 8.0);
        DEFAULTS.put("structureManualPulseTicks", 10);
        DEFAULTS.put("structureManualCooldownTicks", 2);
        DEFAULTS.put("structureInjectionLimitTicks", 4);
        DEFAULTS.put("structureMaxLoadedColumns", 256);
        DEFAULTS.put("structureMaxLoadedPockets", 16);
        DEFAULTS.put("structureManualEntryLimit", 32);
        DEFAULTS.put("structurePocketSyncEnvironment", true);
        DEFAULTS.put("structurePocketSounds", true);
        DEFAULTS.put("structureAllowCopy", true);
        DEFAULTS.put("structureMaxNestingDepth", 1);
        DEFAULTS.put("structureBlockBlacklist", DEFAULT_BLOCK_BLACKLIST);
        DEFAULTS.put("structureMigrateSlots", true);
        DEFAULTS.put("structureReleaseConflictGuard", true);
        DEFAULTS.put("structureReleaseBlocksPerTick", 4096);
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

    /** Whether a bare hand may change a setting inside a block without the goggles. */
    public static boolean innerAdjustNeedsGoggles() {
        return bool("innerAdjustNeedsGoggles", true);
    }

    /**
     * DEBUG. Draw the shell for every known host, whatever the colour and visibility settings say.
     *
     * <p>The one setting whose whole purpose is to ignore the others: it exists so that "is the set of
     * hosts itself wrong" can be answered without the goggles, the line of sight and the alpha all
     * having to be right at the same time.
     */
    public static boolean forceOverlayDraw() {
        return bool("forceOverlayDraw", false);
    }

    /** Whether the block placer may make a standalone one-block structure while it is itself carried. */
    public static boolean placerCreatesStructures() {
        return bool("placerCreatesStructures", false);
    }

    // ------------------------------------------------- components that travel --

    /** Whether components ride the contraption or sub-level that picked their block up. */
    public static boolean carrierEnabled() {
        return bool("carrierEnabled", true);
    }

    /** Log every carrier callback, including the ones that carry nothing. */
    public static boolean traceCarrier() {
        return bool("traceCarrier", false);
    }

    /**
     * Close the bridge between a carried component and the world it is flying through.
     *
     * <p>A diagnostic: it answers "is the trouble in the carried network or in the reading of the world
     * around it" by removing the second half of the question.
     */
    public static boolean carrierFreezeWorldIo() {
        return bool("carrierFreezeWorldIo", false);
    }

    /**
     * Whether the redstone inside a moving structure reads the signal sources that structure carries.
     *
     * <p>On, because a machine's own levers and torches are part of its circuit. Turning it off leaves
     * only what the world outside the structure provides.
     */
    public static boolean carrierStructureSignals() {
        return bool("carrierStructureSignals", true);
    }

    /** Also notify the neighbours of a carried host's own structure coordinate when its output changes. */
    public static boolean carrierNotifyStructureNeighbours() {
        return bool("carrierNotifyStructureNeighbours", true);
    }

    /** Drop the component as an item when a block a structure is carrying is broken, as in the world. */
    public static boolean carrierBlockDrops() {
        return bool("carrierBlockDrops", true);
    }

    /** Adopt a component the world's own store holds at a moving structure's coordinate. */
    public static boolean adoptWorldComponents() {
        return bool("adoptWorldComponents", true);
    }

    /** How many blocks riding a moving structure the self-healing sweep may examine per tick. */
    public static int carrierSelfHealCheckPerTick() {
        return intValue("carrierSelfHealCheckPerTick", 8);
    }

    /** Whether the redstone in an ordinary world block reads what a structure is carrying. */
    public static boolean carrierWorldReadsProjection() {
        return bool("carrierWorldReadsProjection", true);
    }

    /** Whether the wrench works on a host block a moving structure is carrying. */
    public static boolean carriedWrench() {
        return bool("carriedWrench", true);
    }

    /** Whether the rotation rod works on a host block a moving structure is carrying. */
    public static boolean carriedRotationRod() {
        return bool("carriedRotationRod", true);
    }

    /** Whether a component may be put into - and taken back out of - a block a structure is carrying. */
    public static boolean carriedInsert() {
        return bool("carriedInsert", true);
    }

    /** Whether the carried path answers for a coordinate it owns before the world's own click handling. */
    public static boolean carriedInsertPriority() {
        return bool("carriedInsertPriority", true);
    }

    /** Whether a bare hand may work a component inside a block a moving structure is carrying. */
    public static boolean carriedSwitches() {
        return bool("carriedSwitches", true);
    }

    /** Whether Jade shows the component hidden inside a block a moving structure is carrying. */
    public static boolean carriedJade() {
        return bool("carriedJade", true);
    }

    /** How long, in ticks, a moving structure's pose stays trustworthy on the client. */
    public static int carrierPoseStaleTicks() {
        return intValue("carrierPoseStaleTicks", 60);
    }

    /** How often, in ticks, the server re-sends the pose of a structure that is holding something. */
    public static int carrierPoseHeartbeatTicks() {
        return intValue("carrierPoseHeartbeatTicks", 20);
    }

    /** How often, in ticks, that pose is re-sent while the structure may be turning. */
    public static int carrierPoseSampleTicks() {
        return intValue("carrierPoseSampleTicks", 2);
    }

    /** Whether the redstone inside a moving structure is recomputed from its own local layout. */
    public static boolean carrierSolveEnabled() {
        return bool("carrierSolveEnabled", true);
    }

    /** How many components one structure may settle in a single tick. */
    public static int carrierSolveMaxComponentsPerTick() {
        return intValue("carrierSolveMaxComponentsPerTick", 64);
    }

    /** How many of one structure's host blocks may start a solve, per tick. */
    public static int carrierSolveMaxNodesPerCarrier() {
        return intValue("carrierSolveMaxNodesPerCarrier", 256);
    }

    /** Log one line per pass for every structure that has components riding it. */
    public static boolean traceCarrierSolve() {
        return bool("traceCarrierSolve", false);
    }

    // -------------------------------------------------------- solver diagnostics --

    /** Log every individual solver step; one tick of an ordinary build can produce hundreds of lines. */
    public static boolean verboseSolverLog() {
        return bool("verboseSolverLog", false);
    }

    /** Log the client's carried-render diagnostics every frame instead of when the picture changes. */
    public static boolean verboseRenderLog() {
        return bool("verboseRenderLog", false);
    }

    /** How many times one host may be recomputed in a tick before a "solver spin" warning names it. */
    public static int solverSpinWarnPerTick() {
        return intValue("solverSpinWarnPerTick", 8);
    }

    /** Coalesce the solver's neighbour notifications to the tick boundary. */
    public static boolean solverCoalesceNotifications() {
        return bool("solverCoalesceNotifications", true);
    }

    // ------------------------------------------------------------- the structure block --

    /** The longest axis a captured structure may have, in blocks; 48 is vanilla's own limit. */
    public static int structureMaxSize() {
        return intValue("structureMaxSize", 48);
    }

    /** How large the downsampled preview that travels with a structure may be, in bytes. */
    public static int structurePreviewBytes() {
        return intValue("structurePreviewBytes", 65536);
    }

    /**
     * How many cells the downsampled preview that travels with a structure may have.
     *
     * <p>Together with {@link #structurePreviewBytes()} this decides the preview's scale; the coarser of the two wins.
     * 4,096 cells is 16³, which is the picture a 32³ structure arrives as; the range the NeoForge build documented is
     * 1024..110592, and nothing here clamps a value the file holds - 110592 is 48³, the largest structure allowed.
     */
    public static int structurePreviewCells() {
        return intValue("structurePreviewCells", 4096);
    }

    /**
     * Whether a preview's miniature is emitted once into uploaded vertex data and redrawn, instead of rebuilt cell by
     * cell at every draw.
     *
     * <p>On by default because the emission is the whole cost: a 1,405-cell miniature is about 0.5 ms of block model
     * geometry per draw, and the same picture is drawn for the block in the world, for the stack in the hand and for
     * the slot that stack sits in. Emitted once, a draw is one upload per render type, which is what vanilla pays for
     * a chunk.
     *
     * <p>Off, the miniature is drawn one cell at a time exactly as it was before. The switch exists because this path
     * uploads vertex data of its own, and a driver that mishandles that takes the game down: a player who sees it
     * happen gets their picture back by turning this off on the next launch, which is worth more than any
     * explanation.
     */
    public static boolean structurePreviewCached() {
        return bool("structurePreviewCached", true);
    }

    /**
     * Whether cells that nothing can see are dropped from a preview.
     *
     * <p>On by default: a cell whose six neighbours all render as a full cube shows nothing but its own faces, and a
     * 48³ structure is 110,592 of them. Off, every cell is drawn - the bisect switch for a miniature that looks like
     * it is missing blocks.
     */
    public static boolean structurePreviewCull() {
        return bool("structurePreviewCull", true);
    }

    /**
     * Whether a cell's faces that the picture behind it hides are left out of the emission.
     *
     * <p>The second half of the culling, and the bigger one: a one-cell-thick wall drops four of every cell's six
     * faces, and the bytes a miniature uploads are what a draw actually costs (a measured 3 MB per draw of a 32³
     * structure, about 0.7 ms). On by default. Off, every cell of the picture is emitted whole - the bisect switch for
     * a miniature that looks like it is missing faces.
     */
    public static boolean structurePreviewFaces() {
        return bool("structurePreviewFaces", true);
    }

    /**
     * Whether a structure drawn as an icon is drawn at half the resolution.
     *
     * <p>A stack in the hand, in a slot, and the model hand a screen draws all show the same picture at a few dozen
     * pixels, and each of them paid for the full one: a measured 3 MB of vertices and about 0.7 ms per draw, three
     * times a frame in the worst case. Half the resolution is a quarter of the cells at the same silhouette. On by
     * default. Off, every place draws the full picture - the bisect switch for an icon that looks wrong.
     */
    public static boolean structurePreviewIconDetail() {
        return bool("structurePreviewIconDetail", true);
    }

    /** Snapshot the ring of world around a selection when capturing it, so the product has context. */
    public static boolean structureCaptureContextShell() {
        return bool("structureCaptureContextShell", true);
    }

    /** How far from a structure a player may be and still press its manual keys, in blocks. */
    public static double structureManualRange() {
        return floatValue("structureManualRange", 8.0F);
    }

    /** How long one manual press pushes, in ticks; a press is a pulse, never a hold. */
    public static int structureManualPulseTicks() {
        return intValue("structureManualPulseTicks", 10);
    }

    /** The least number of ticks between two accepted presses from one player. */
    public static int structureManualCooldownTicks() {
        return intValue("structureManualCooldownTicks", 2);
    }

    /** The least number of ticks between two changes of one cell's injected signal. */
    public static int structureInjectionLimitTicks() {
        return intValue("structureInjectionLimitTicks", 4);
    }

    /** How many chunk columns structures may hold loaded at once, counted in columns. */
    public static int structureMaxLoadedColumns() {
        return intValue("structureMaxLoadedColumns", 256);
    }

    /** How many structures may be ticked at once, whatever their size. */
    public static int structureMaxLoadedPockets() {
        return intValue("structureMaxLoadedPockets", 16);
    }

    /** How many entries the manual-mode panel may hold in one structure. */
    public static int structureManualEntryLimit() {
        return intValue("structureManualEntryLimit", 32);
    }

    /** Keep the pocket's time and weather equal to the overworld's. */
    public static boolean structurePocketSyncEnvironment() {
        return bool("structurePocketSyncEnvironment", true);
    }

    /** Bridge a sound made inside a structure out to the world, at the structure block. On: silence was the bug. */
    public static boolean structurePocketSounds() {
        return bool("structurePocketSounds", true);
    }

    /** Allow the copying tier at all; it duplicates container contents along with the blocks. */
    public static boolean structureAllowCopy() {
        return bool("structureAllowCopy", true);
    }

    /** How many structures deep a capture may go; 1 means a structure may hold another, not a third. */
    public static int structureMaxNestingDepth() {
        return intValue("structureMaxNestingDepth", 1);
    }

    /**
     * Block ids a capture may not touch.
     *
     * <p>An unknown id is skipped rather than refusing to start: the list is also a player's, and a
     * datapack can remove a block this mod was told to avoid.
     */
    public static List<String> structureBlockBlacklistIds() {
        return listOfStrings("structureBlockBlacklist", DEFAULT_BLOCK_BLACKLIST);
    }

    /** Whether the mod's own components that live inside blocks travel with a captured structure. */
    public static boolean structureMigrateSlots() {
        return bool("structureMigrateSlots", true);
    }

    /**
     * The size limits a payload is checked against, in the loader-free form the payload layer can use.
     *
     * <p>The volume limit is the cube of the axis limit rather than a second, independently tunable
     * number: a structure that fits 48 on its longest axis but is 48×48×48 is the biggest case the design
     * decided on, and two settings that could disagree about it would be a way to make the game accept
     * something the load budget was never sized for.
     */
    public static com.jiangyuefengyu.redstonecircuit.structure.PayloadLimits structurePayloadLimits() {
        int maxAxis = structureMaxSize();
        return new com.jiangyuefengyu.redstonecircuit.structure.PayloadLimits(maxAxis,
                maxAxis * maxAxis * maxAxis, structureManualEntryLimit());
    }

    /** How a release is paced, and whether it asks before overwriting. */
    public static com.jiangyuefengyu.redstonecircuit.structure.StructureRelease.Options
            structureReleaseOptions() {
        return new com.jiangyuefengyu.redstonecircuit.structure.StructureRelease.Options(
                intValue("structureReleaseBlocksPerTick", 4096),
                bool("structureReleaseConflictGuard", true));
    }

    /**
     * Everything a capture is checked against, with the blacklist's ids resolved to blocks.
     *
     * <p>Resolution happens here, at the one place that has both the config and the registries. An id that
     * names no block is logged and skipped: a typo in a config file must cost the player that one entry,
     * not the whole feature.
     */
    public static com.jiangyuefengyu.redstonecircuit.structure.StructureCapture.Limits
            structureCaptureLimits() {
        java.util.Set<net.minecraft.world.level.block.Block> blacklist = new java.util.HashSet<>();
        for (String id : structureBlockBlacklistIds()) {
            net.minecraft.resources.ResourceLocation location =
                    net.minecraft.resources.ResourceLocation.tryParse(id);
            if (location == null || !net.minecraft.core.registries.BuiltInRegistries.BLOCK
                    .containsKey(location)) {
                LOGGER.warn("[redstonecircuit] structureBlockBlacklist names {}, which is not a block; "
                        + "skipping it", id);
                continue;
            }
            blacklist.add(net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(location));
        }
        return new com.jiangyuefengyu.redstonecircuit.structure.StructureCapture.Limits(
                structurePayloadLimits(), blacklist, structureMaxNestingDepth(), structureMigrateSlots());
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

    private static float floatValue(String key, float fallback) {
        if (CONFIG == null) {
            return fallback;
        }
        try {
            return CONFIG.getFloat(key, fallback);
        } catch (RuntimeException broken) {
            return fallback;
        }
    }

    private static List<String> listOfStrings(String key, List<String> fallback) {
        if (CONFIG == null) {
            return fallback;
        }
        try {
            return CONFIG.getStringList(key, fallback);
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

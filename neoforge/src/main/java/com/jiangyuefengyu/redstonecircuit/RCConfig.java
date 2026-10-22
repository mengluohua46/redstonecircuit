package com.jiangyuefengyu.redstonecircuit;

import java.util.List;

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
            .comment("Log every placement and retrieval, including refused ones, to the game log.",
                    "Off in release builds; set it to true when you need a diagnostic log.")
            .define("debugLog", BuildFlags.defaultDebugLog());

    public static final ModConfigSpec.BooleanValue ALLOW_REPLACE = BUILDER
            .comment("Allow shift-right-clicking a block that already holds a component to replace it.",
                    "Off by default: a second placement falls through to vanilla instead.")
            .define("allowReplace", false);

    /**
     * Whether 方块放置器 may create a standalone structure - OFF by default, because of Sable's side of the fence.
     *
     * <p>That machine's physical half makes a new Sable sub-level for the block it places, and a <em>saved</em>
     * sub-level whose global storage entry cannot be read is reported on every world load. Allocating a structure and
     * removing it again - which is what a refused placement does - is the only thing this mod does that touches that
     * bookkeeping, so until Sable's storage semantics are understood the machines create nothing at all: the
     * activation is refused, the item is not spent and nothing is written anywhere.
     */
    public static final ModConfigSpec.BooleanValue PLACER_CREATES_STRUCTURES = BUILDER
            .comment("Let the block placer create a standalone one-block structure when it is itself physical.",
                    "Off by default.")
            .define("placerCreatesStructures", false);

    // -------------------------------------------------------- host appearance --
    // These only affect what a client draws; they are read locally and never sent anywhere, so
    // putting them in the common file (rather than a second, client-only one) costs nothing.

    public static final ModConfigSpec.EnumValue<OverlayMode> HOST_OVERLAY_MODE = BUILDER
            .comment("When the block holding redstone is drawn as a translucent, framed cube with the component visible inside.",
                    "GOGGLES (default) only while wearing the redstone goggles, ALWAYS, or OFF for no overlay at all.")
            .defineEnum("hostOverlayMode", OverlayMode.GOGGLES);

    /** When a host block is drawn specially. */
    public enum OverlayMode {
        GOGGLES,
        ALWAYS,
        OFF
    }

    public static final ModConfigSpec.IntValue HOST_OVERLAY_ALPHA = BUILDER
            .comment("Opacity of the shell, 0-255. Around 150 reads as frosted glass; 0 draws the border only.")
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

    public static final ModConfigSpec.ConfigValue<String> SUPERCONDUCTOR_FRAME_COLOR = BUILDER
            .comment("Border colour for a block holding SUPERCONDUCTING wire, as RRGGBB hex.",
                    "Tells the two wire materials apart; set it equal to hostOverlayFrameColor to drop the distinction.")
            .define("superconductorFrameColor", "FF8C00");

    public static final ModConfigSpec.BooleanValue HOST_ACCEPTS_STRONG_POWER = BUILDER
            .comment("Whether a host block is powered by the strong power a neighbouring SOLID block carries, as in vanilla.",
                    "Turn this off so inner redstone reacts only to blocks that emit a signal themselves.")
            .define("hostAcceptsStrongPower", true);

    public static final ModConfigSpec.BooleanValue HOST_CONDUCTS_STRONG_POWER = BUILDER
            .comment("Whether a host block passes on the strong power a neighbouring diode pushes into it.",
                    "Off by default, so that power stops at the component inside; on gives vanilla block semantics.")
            .define("hostConductsStrongPower", false);

    public static final ModConfigSpec.BooleanValue INNER_COMPONENTS = BUILDER
            .comment("Draw the component itself inside the shell, so a wire, a repeater's facing and a",
                    "lit torch can be read from outside the block. Off leaves only the coloured shell.")
            .define("showInnerComponents", true);

    public static final ModConfigSpec.BooleanValue INNER_VISIBLE_THROUGH_WALLS = BUILDER
            .comment("Draw the component even when a solid block is in the way.",
                    "Off (the default) so the effect never becomes X-ray vision through a wall.")
            .define("seeInnerComponentsThroughWalls", false);

    public static final ModConfigSpec.BooleanValue FORCE_OVERLAY_DRAW = BUILDER
            .comment("DEBUG. Draw the shell and component for every known host, ignoring the colour and visibility settings.",
                    "Off by default; hostOverlayMode=OFF still draws nothing.")
            .define("forceOverlayDraw", false);

    // ----------------------------------------------------------------- wrench --

    public static final ModConfigSpec.BooleanValue WRENCH_NEEDS_GOGGLES = BUILDER
            .comment("Require the redstone goggles to be worn before the wrench will do anything.",
                    "On by default. Turn it off to adjust connections without them.")
            .define("wrenchNeedsGoggles", true);

    public static final ModConfigSpec.BooleanValue WRENCH_CLEAR_ON_SAME_BLOCK = BUILDER
            .comment("Shift-right-clicking the block already selected with the wrench clears its connection overrides.",
                    "On by default; without it there is no way back to automatic behaviour from the item.")
            .define("wrenchClearOnSameBlock", true);

    // ---------------------------------------------------------- rotation rod --

    public static final ModConfigSpec.BooleanValue ROTATION_ROD_NEEDS_GOGGLES = BUILDER
            .comment("Require the redstone goggles to be worn before the rotation rod will turn anything.",
                    "On by default, like the wrench: aiming a component is done by looking at it.")
            .define("rotationRodNeedsGoggles", true);

    // ------------------------------------------------------- inside the block --

    public static final ModConfigSpec.BooleanValue INNER_ADJUST_NEEDS_GOGGLES = BUILDER
            .comment("Require the redstone goggles before a bare hand can change a setting inside a block (a delay, a mode).",
                    "On by default. Levers and buttons are exempt, and a refusal is silent.")
            .define("innerAdjustNeedsGoggles", true);

    // ------------------------------------------------------------------- sync --

    public static final ModConfigSpec.BooleanValue TRACE_SLOT_SYNC = BUILDER
            .comment("Log every component update sent to clients. Very noisy while a circuit runs; it",
                    "answers 'is the client being told about this block at all'.")
            .define("traceSlotSync", false);

    // ------------------------------------------------------- moving structures --
    // These settings only do anything when Sable and/or Create is installed; with neither, nothing
    // in this mod ever consults them.

    public static final ModConfigSpec.BooleanValue CARRIER_ENABLED = BUILDER
            .comment("Let inner redstone components travel with the Create contraption or Sable sub-level that picks their block up.",
                    "On by default. Turn it off to leave components behind at their world position.")
            .define("carrierEnabled", true);

    public static final ModConfigSpec.BooleanValue TRACE_CARRIER = BUILDER
            .comment("Log every contraption callback, including the ones that carry nothing.",
                    "Off by default (one line per block of a machine); use it to check whether your block type is hooked.")
            .define("traceCarrier", false);

    public static final ModConfigSpec.BooleanValue CARRIER_FREEZE_WORLD_IO = BUILDER
            .comment("Close the bridge between a carried component and the world it is flying through.",
                    "Off by default: the two sides can see each other, so a machine lights the redstone it passes over.")
            .define("carrierFreezeWorldIo", false);

    /**
     * Whether a structure's <b>own</b> blocks count as an input to the solve inside it.
     *
     * <p>A host block riding a machine has two neighbourhoods: the world it is flying through (the bridge
     * {@code carrierFreezeWorldIo} closes) and the machine's own blocks, which travel with it. A lever carried
     * <em>inside</em> the structure emits its signal as a block state, so with this on the inside solve reads its own
     * input side as the larger of the two halves, and a lever next to a carried host block powers the redstone inside
     * it exactly as it would on the ground. Off reads only the world half.
     */
    public static final ModConfigSpec.BooleanValue CARRIER_STRUCTURE_SIGNALS = BUILDER
            .comment("Let the redstone inside a moving structure read the signal sources the structure itself is carrying.",
                    "On by default: a lever carried next to a host block powers the redstone inside it. Off reads only the world outside.")
            .define("carrierStructureSignals", true);

    /**
     * Whether an output change inside a structure also wakes the neighbours of the <b>structure's own
     * coordinate</b>.
     *
     * <p>A Sable sub-level's blocks are the level's own blocks at their plot coordinates, so a vanilla device the
     * machine carries - a piston, a lamp, a piece of wire - lives at a real level position and is ticked by the
     * level, but nothing notifies it: the only position a carried host is projected onto is the world position the
     * machine <em>looks</em> like it is at, not where its own blocks are. With this on the structure coordinate is
     * notified as well, once per tick and read-only - the world store is never written there - with the same rule
     * vanilla uses. Independent of {@code carrierFreezeWorldIo}, which is about the world the machine flies through.
     */
    public static final ModConfigSpec.BooleanValue CARRIER_NOTIFY_STRUCTURE_NEIGHBOURS = BUILDER
            .comment("Also notify the neighbours of a carried host's OWN structure coordinate when its output changes.",
                    "On by default: a piston, lamp or wire the machine carries wakes up, as it would on the ground. Off notifies only the projected world position.")
            .define("carrierNotifyStructureNeighbours", true);

    /**
     * Whether breaking a block that a structure is carrying drops the component inside it.
     *
     * <p>In the world, breaking a host block drops its component. A physicalised host block is removed by whoever
     * owns those blocks, and nothing dropped the component: it stayed in the carried record, invisible, and could
     * write a component back into a block that no longer exists. On, a break at a position the carrier layer answers
     * for drops the item once, at the position the block was, and forgets the record; nothing is dropped for a
     * release or a landing.
     */
    public static final ModConfigSpec.BooleanValue CARRIER_BLOCK_DROPS = BUILDER
            .comment("Drop the component as an item when a block a structure is CARRYING is broken, as in the world.",
                    "On by default; it drops once, where the block was. Never fires for a release or a landing.")
            .define("carrierBlockDrops", true);

    /**
     * Whether a world-store entry standing at a structure's own plot coordinate is <b>migrated</b> into the carrier
     * index.
     *
     * <p>A Sable sub-level's blocks <b>are</b> the level's blocks at their own local coordinates, so the world store's
     * key space and a structure's local coordinate space overlap and one block can have two truths: Jade reads the
     * world store and shows the component while the overlay, which reads the carrier index, draws nothing. On, the
     * claim sweep adopts such entries - the component becomes a carrier record there and the <b>world entry is
     * removed</b> - with {@code origin} set to the world position the structure's pose names for that coordinate now.
     * Bounded and throttled: {@code ADOPT_NODES_PER_PASS} entries per structure per attempt, one attempt per
     * {@code ADOPT_INTERVAL} ticks, stopping after {@code ADOPT_MAX_PASSES}.
     */
    public static final ModConfigSpec.BooleanValue ADOPT_WORLD_COMPONENTS = BUILDER
            .comment("Migrate a component in the WORLD's own store at a moving structure's own coordinate into that structure's record.",
                    "On by default; this repairs a save that already has such an entry.")
            .define("adoptWorldComponents", true);

    /**
     * How many carried hosts the self-healing sweep may examine per level tick; {@code 0} turns it off.
     *
     * <p>The sweep is the second trigger for "a block a structure is carrying has gone", and the one that closes the
     * ghost off rather than moving it: a break event is only one of the ways a carried block can leave, and it never
     * fires for a Create drill or saw taking a block out through its own movement behaviour. It reads one block per
     * host on the level tick, so it walks at most this many hosts per tick, continuing from a per-carrier cursor on
     * the next tick. It never acts on a block that is still there, so a structure merely being moved cannot be
     * mistaken for one that has been broken.
     */
    public static final ModConfigSpec.IntValue CARRIER_SELF_HEAL_CHECK_PER_TICK = BUILDER
            .comment("How many blocks riding a moving structure the self-healing sweep may examine per tick.",
                    "It drops the component of a carried block removed without a break event (a drill or saw). 0 turns it off.")
            .defineInRange("carrierSelfHealCheckPerTick", 8, 0, 4096);

    /**
     * Whether the world's <b>inner</b> solve reads the output of a host a structure is carrying.
     *
     * <p>The reverse direction of {@link #CARRIER_STRUCTURE_SIGNALS}: a component inside a block standing in the
     * world must see the signal a carried host emits at the world position that host currently occupies. Vanilla's
     * own query already answers it through the projection table, and this makes the mod's world-side read ask
     * directly as well, with the same rule and the same direction conversion, so a lamp, a piston or a piece of
     * redstone beside a passing machine behaves the same whether vanilla or this mod reads it. Off reads only
     * vanilla's own query.
     */
    public static final ModConfigSpec.BooleanValue CARRIER_WORLD_READS_PROJECTION = BUILDER
            .comment("Let the redstone inside an ordinary block in the world read the output of a host a structure is CARRYING.",
                    "On by default, at the world position that host occupies; off reads only vanilla's own query.")
            .define("carrierWorldReadsProjection", true);

    /**
     * Whether the redstone wrench can be used on a host a structure is carrying.
     *
     * <p>Through a <b>carrier-only</b> entry point that never touches the world's own click handling
     * ({@code CarrierInteraction}): the click is resolved to a host in the carrier index, the three-state
     * lock/cut/automatic cycle is applied to that host's record, and the client is re-sent so the overlay redraws at
     * once. Off, such a click does nothing at all; the world's wrench is not affected either way.
     */
    public static final ModConfigSpec.BooleanValue CARRIED_WRENCH = BUILDER
            .comment("Let the redstone wrench be used on a host block a moving structure is CARRYING: pick it, then shift-click a neighbour.",
                    "That steps the line through locked / cut / automatic. Off leaves a carried host untouched.")
            .define("carriedWrench", true);

    /**
     * Whether the rotation rod can be used on a host a structure is carrying.
     *
     * <p>The same as {@link #CARRIED_WRENCH}, for 方向转向杆: clicking a face of a carried host turns the component
     * inside it so that face is its input side, or its output side if that is already where it reads from. The
     * clicked face arrives as a <b>world</b> direction and goes through the same quaternion conversion the signal
     * paths use, so a machine that has been rotated turns its components the way the player expects. Off, such a
     * click does nothing; the world's rod is not affected either way.
     */
    public static final ModConfigSpec.BooleanValue CARRIED_ROTATION_ROD = BUILDER
            .comment("Let the rotation rod be used on a host block a moving structure is CARRYING.",
                    "Click a face to make it the component's input side, again for the output side.")
            .define("carriedRotationRod", true);

    /**
     * Whether a component can be put into, and taken back out of, a block a structure is carrying.
     *
     * <p>A physicalised block is a sub-level's or a contraption's block, its position in the level is the
     * structure's own local coordinate, and the ordinary host sync has nothing at that coordinate; the carrier
     * payload carries the component keyed by exactly that coordinate, so the carrier-side click path is what
     * answers a click there. Off leaves a carried block untouched.
     */
    public static final ModConfigSpec.BooleanValue CARRIED_INSERT = BUILDER
            .comment("Let a component be put INTO a block a moving structure is CARRYING, and taken back out again.",
                    "On by default. Turn it off first if placing components inside a carried block misbehaves.")
            .define("carriedInsert", true);

    /**
     * Whether a coordinate a structure owns belongs to the <b>carrier</b> side before the world's own click
     * handling sees it.
     *
     * <p>The one exception to "the world's click path is untouched", needed because a Sable sub-level's blocks
     * <b>are</b> level blocks stored at their own local coordinates, so a click on one arrives at the ordinary
     * block-click handler carrying a coordinate that is simultaneously a position in the level and a host's
     * coordinate inside a structure. The world handler cannot tell those apart, and without this it won the click:
     * the component went into the world's store at the plot coordinate, so Jade showed it while the overlay drew
     * nothing and the world bridge could not see it either.
     *
     * <p>The check runs before {@code HostRules#claimsClick}, so the world side does not merely skip its action -
     * it never claims the click - and the refusal does not consume the click: the carrier's own listener decides,
     * and if it decides the click is not its own the event is left to vanilla. A coordinate is owned when a live
     * carrier record exists there, <em>or</em> the level position is a plot cell of a live sub-level (a sub-level
     * loaded from a save has no records at all). A Create contraption's block, an ordinary world block, a carried
     * host's origin and a host already put down are all outside that set.
     */
    public static final ModConfigSpec.BooleanValue CARRIED_INSERT_PRIORITY = BUILDER
            .comment("Handle a coordinate a moving structure OWNS on the carrier side before the world's own click handling.",
                    "On by default. Turn it off first if a click on a carried block misbehaves.")
            .define("carriedInsertPriority", true);

    public static final ModConfigSpec.BooleanValue CARRIED_SWITCHES = BUILDER
            .comment("Let a bare hand work a component inside a block a moving structure is CARRYING: flick a lever, step a delay, swap a mode.",
                    "On by default. Buttons are not included, as their release needs a fixed level position.")
            .define("carriedSwitches", true);

    public static final ModConfigSpec.BooleanValue VERBOSE_SOLVER_LOG = BUILDER
            .comment("Log every individual solver step. Very noisy: one tick of an ordinary build can produce hundreds of lines.",
                    "Off by default; a summary line every 20 ticks carries the same information.")
            .define("verboseSolverLog", false);

    public static final ModConfigSpec.BooleanValue VERBOSE_RENDER_LOG = BUILDER
            .comment("Log the client's carried-render diagnostics every frame instead of only when the picture changes.",
                    "Off by default, as it grows the log to tens of megabytes. It never changes what is drawn.")
            .define("verboseRenderLog", false);

    public static final ModConfigSpec.IntValue SOLVER_SPIN_WARN_PER_TICK = BUILDER
            .comment("How many times ONE host may be recomputed inside a single tick before a 'solver spin' warning names it.",
                    "Only a defective circuit reaches this; the warning is written at most once per host per tick.")
            .defineInRange("solverSpinWarnPerTick", 8, 1, 4096);

    public static final ModConfigSpec.BooleanValue SOLVER_COALESCE_NOTIFICATIONS = BUILDER
            .comment("Coalesce the solver's neighbour notifications to the tick boundary.",
                    "On by default, so the solver cannot re-feed itself inside one tick. Off sends one notification per change.")
            .define("solverCoalesceNotifications", true);

    public static final ModConfigSpec.BooleanValue CARRIED_JADE = BUILDER
            .comment("Let Jade show the component hidden inside a block a moving structure is CARRYING, as it does for a world block.",
                    "On by default; the lines Jade shows for ordinary world blocks are unaffected.")
            .define("carriedJade", true);

    public static final ModConfigSpec.IntValue CARRIER_POSE_STALE_TICKS = BUILDER
            .comment("How long, in ticks, a moving structure's pose stays trustworthy on the client.",
                    "Past this age the client stops drawing the component inside it, so keep it well above carrierPoseHeartbeatTicks.")
            .defineInRange("carrierPoseStaleTicks", 60, 5, 1200);

    public static final ModConfigSpec.IntValue CARRIER_POSE_HEARTBEAT_TICKS = BUILDER
            .comment("How often, in ticks, the server re-sends the pose of every structure that is holding something, moved or not.",
                    "The heartbeat is what lets the client tell a parked machine from one that has gone away. Default 20.")
            .defineInRange("carrierPoseHeartbeatTicks", 20, 1, 600);

    public static final ModConfigSpec.IntValue CARRIER_POSE_SAMPLE_TICKS = BUILDER
            .comment("How often, in ticks, the server re-sends the pose of a structure holding something while it may be turning.",
                    "The upper bound on how old a rotating angle can be on screen. 0 disables sampling.")
            .defineInRange("carrierPoseSampleTicks", 2, 0, 600);

    // ------------------------------------------------ in-structure solve --
    // A carried component gets a solver of its own, in the structure's local coordinates; the world
    // boundary stays frozen (see carrierFreezeWorldIo), so these settings are about the inside of the
    // machine only.

    public static final ModConfigSpec.BooleanValue CARRIER_SOLVE_ENABLED = BUILDER
            .comment("Recompute the redstone inside a moving structure from its own local layout.",
                    "On by default; off leaves every component at the value it had when the block was picked up.")
            .define("carrierSolveEnabled", true);

    public static final ModConfigSpec.IntValue CARRIER_SOLVE_MAX_COMPONENTS_PER_TICK = BUILDER
            .comment("How many components one structure may settle in a single tick.",
                    "A ceiling on work, not on machine size: a larger structure keeps settling over the following ticks.")
            .defineInRange("carrierSolveMaxComponentsPerTick", 64, 1, 4096);

    public static final ModConfigSpec.IntValue CARRIER_SOLVE_MAX_NODES_PER_CARRIER = BUILDER
            .comment("How many of one structure's host blocks may be used as a starting point for a solve, per tick.",
                    "The remaining hosts are solved over the following ticks.")
            .defineInRange("carrierSolveMaxNodesPerCarrier", 256, 1, 4096);

    public static final ModConfigSpec.BooleanValue TRACE_CARRIER_SOLVE = BUILDER
            .comment("Log one line per pass for every structure that has components riding it.",
                    "Off by default; it prints once a tick.")
            .define("traceCarrierSolve", false);

    public static final ModConfigSpec.DoubleValue SLOT_SYNC_RANGE = BUILDER
            .comment("How far component updates reach, in blocks. 0 sends them to everyone in the dimension.",
                    "0 is the safe default: a client that misses an update keeps drawing the old state.")
            .defineInRange("slotSyncRange", 0.0, 0.0, 512.0);

    // -------------------------------------------- redstone structure block --
    // The pocket dimension, its plot allocator and the payload format.

    public static final ModConfigSpec.IntValue STRUCTURE_MAX_SIZE = BUILDER
            .comment("The longest axis a captured structure may have, in blocks; 48 is vanilla's own structure block limit.",
                    "The load budget is counted from it: 48³ plus the context shell is 16 chunk columns.")
            .defineInRange("structureMaxSize", 48, 1, 128);

    /**
     * The budget for the <b>preview</b> that travels with a structure - the design's second ceiling.
     *
     * <p>A complete structure lives in this save's files and never goes over the network; what a client
     * needs is a small, downsampled voxel preview it can draw on the block, in the hand and in the icon.
     * The design gives that its own budget ({@code structurePreviewBytes}, 64 KiB by default) precisely so
     * the two ceilings cannot be confused: a 48³ structure is tens of megabytes of blocks and a few
     * kilobytes of "roughly what it looks like".
     */
    public static final ModConfigSpec.IntValue STRUCTURE_PREVIEW_BYTES = BUILDER
            .comment("How large the downsampled preview that travels with a structure may be, in bytes.",
                    "The preview is what a client draws on the placed block; keep it well below the game's 1 MiB payload cap.")
            .defineInRange("structurePreviewBytes", 65536, 1024, 524288);

    /**
     * The second ceiling the preview is cut to: how many <b>cells</b> it may have.
     *
     * <p>The byte budget bounds what crosses the network, which is paid once; this bounds what a client has to
     * draw, which is paid every frame. A 48³ structure fits the byte budget at full detail and is 110,592 cells
     * to draw, so it is the cell count that decides how coarse a large structure's miniature is - at the default
     * 4,096 it is drawn at scale 3, a twenty-seventh of the cells. The two are applied together and the coarser
     * of them wins (see {@code StructurePreview#of}).
     */
    public static final ModConfigSpec.IntValue STRUCTURE_PREVIEW_CELLS = BUILDER
            .comment("How many cells the downsampled preview may have, as a volume - not how many are filled.",
                    "Together with structurePreviewBytes this decides the preview's scale; the coarser of the two wins.",
                    "A 48³ structure at 4096 cells is drawn at scale 3 (16³ cells).")
            .defineInRange("structurePreviewCells", 4096, 1024, 110592);

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
    public static final ModConfigSpec.BooleanValue STRUCTURE_PREVIEW_CACHED = BUILDER
            .comment("Emit a structure preview's geometry once and redraw it, instead of rebuilding the miniature every frame.",
                    "On by default: this is what makes a placed structure cost a fraction of what it did.",
                    "Turn it off if a preview crashes or draws wrongly - the game then draws one cell at a time again.")
            .define("structurePreviewCached", true);

    /**
     * Whether cells that nothing can see are dropped from a preview.
     *
     * <p>On by default: a cell whose six neighbours all render as a full cube shows nothing but its own faces, and a
     * 48³ structure is 110,592 of them. Off, every cell is drawn - the bisect switch for a miniature that looks like
     * it is missing blocks.
     */
    public static final ModConfigSpec.BooleanValue STRUCTURE_PREVIEW_CULL = BUILDER
            .comment("Drop the cells of a structure preview that are completely buried inside it.",
                    "On by default. Turn it off to draw every cell, which tells a culling problem from a sampling one.")
            .define("structurePreviewCull", true);

    /**
     * Whether a cell's faces that the picture behind it hides are left out of the emission.
     *
     * <p>The second half of the culling, and the bigger one: a one-cell-thick wall drops four of every cell's six
     * faces, and the bytes a miniature uploads are what a draw actually costs (a measured 3 MB per draw of a 32³
     * structure, about 0.7 ms). On by default. Off, every cell of the picture is emitted whole - the bisect switch for
     * a miniature that looks like it is missing faces.
     */
    public static final ModConfigSpec.BooleanValue STRUCTURE_PREVIEW_FACES = BUILDER
            .comment("Leave out the faces of a structure preview that the cell behind them hides.",
                    "On by default: a wall spends two faces out of six on the picture instead of six.",
                    "Turn it off to draw every face, which tells a face-culling problem from a sampling one.")
            .define("structurePreviewFaces", true);

    /**
     * Whether a structure drawn as an icon is drawn at half the resolution.
     *
     * <p>A stack in the hand, in a slot, and the model hand a screen draws all show the same picture at a few dozen
     * pixels, and each of them paid for the full one: a measured 3 MB of vertices and about 0.7 ms per draw, three
     * times a frame in the worst case. Half the resolution is a quarter of the cells at the same silhouette. On by
     * default. Off, every place draws the full picture - the bisect switch for an icon that looks wrong.
     */
    public static final ModConfigSpec.BooleanValue STRUCTURE_PREVIEW_ICON_DETAIL = BUILDER
            .comment("Draw a structure at half resolution wherever it is drawn as an icon (hand, slot, ground).",
                    "On by default: an icon is a few dozen pixels, and the full picture costs what a placed block costs.",
                    "Turn it off to draw the full picture everywhere.")
            .define("structurePreviewIconDetail", true);

    public static final ModConfigSpec.BooleanValue STRUCTURE_CAPTURE_CONTEXT_SHELL = BUILDER
            .comment("Snapshot the one block of world around a selection when capturing it.",
                    "On by default: the shell travels into the plot but is never written into the payload or released.")
            .define("structureCaptureContextShell", true);

    public static final ModConfigSpec.DoubleValue STRUCTURE_MANUAL_RANGE = BUILDER
            .comment("How far from a structure a player may be and still press its manual keys, in blocks.",
                    "Measured from the player's eyes to the block, and checked by the server on every press.")
            .defineInRange("structureManualRange", 8.0, 1.0, 64.0);

    public static final ModConfigSpec.IntValue STRUCTURE_MANUAL_PULSE_TICKS = BUILDER
            .comment("How long one press pushes, in ticks. A press is a pulse, never a hold, so it cannot be left running.",
                    "10 ticks (half a second) is enough for a repeater, a piston or a dispenser.")
            .defineInRange("structureManualPulseTicks", 10, 1, 200);

    public static final ModConfigSpec.IntValue STRUCTURE_MANUAL_COOLDOWN_TICKS = BUILDER
            .comment("The least number of ticks between two accepted presses from one player.",
                    "Keeps a held or spammed key from driving a machine at tick speed.")
            .defineInRange("structureManualCooldownTicks", 2, 0, 40);

    public static final ModConfigSpec.IntValue STRUCTURE_INJECTION_LIMIT_TICKS = BUILDER
            .comment("The least number of ticks between two changes of the signal a structure injects into one cell of its plot.",
                    "A change that arrives too soon is held and let through when its window opens. Default 4.")
            .defineInRange("structureInjectionLimitTicks", 4, 0, 40);

    public static final ModConfigSpec.IntValue STRUCTURE_MAX_LOADED_COLUMNS = BUILDER
            .comment("How many chunk columns may be held loaded by structures at once, counted in COLUMNS, never structures.",
                    "A structure that does not fit is asleep: it still exists and can be released, but is not ticked.")
            .defineInRange("structureMaxLoadedColumns", 256, 0, 65536);

    public static final ModConfigSpec.IntValue STRUCTURE_MAX_LOADED_POCKETS = BUILDER
            .comment("How many structures may be ticked at once, whatever their size.",
                    "Checked together with the column budget: both have to allow a structure before it ticks.")
            .defineInRange("structureMaxLoadedPockets", 16, 0, 4096);

    public static final ModConfigSpec.IntValue STRUCTURE_MANUAL_ENTRY_LIMIT = BUILDER
            .comment("How many entries the manual-mode panel may hold in one structure.")
            .defineInRange("structureManualEntryLimit", 32, 0, 512);

    public static final ModConfigSpec.BooleanValue STRUCTURE_POCKET_SYNC_ENVIRONMENT = BUILDER
            .comment("Keep the pocket dimension's time and weather equal to the overworld's.",
                    "On by default: a weather detector or a daylight-dependent block reads what it read where it stood.")
            .define("structurePocketSyncEnvironment", true);

    public static final ModConfigSpec.BooleanValue STRUCTURE_POCKET_SOUNDS = BUILDER
            .comment("Play the sounds a captured structure makes where the structure stands.",
                    "On by default: the blocks inside a structure run in the hidden pocket dimension, where no player",
                    "ever is, so a note block or a lever in there was silent.")
            .define("structurePocketSounds", true);

    public static final ModConfigSpec.BooleanValue STRUCTURE_ALLOW_COPY = BUILDER
            .comment("Allow the copying tier at all. It copies container contents along with the blocks, so a chest of items is duplicated too.",
                    "Creative mode only; survival is always cut-only, whatever this says.")
            .define("structureAllowCopy", true);

    public static final ModConfigSpec.IntValue STRUCTURE_MAX_NESTING_DEPTH = BUILDER
            .comment("How many structures deep a capture may go. 1 means a structure may hold another structure, but not a third.",
                    "0 forbids any structure block inside a capture; raising it multiplies how many pockets tick at once.")
            .defineInRange("structureMaxNestingDepth", 1, 0, 16);

    public static final ModConfigSpec.ConfigValue<List<? extends String>> STRUCTURE_BLOCK_BLACKLIST =
            BUILDER
                    .comment("Block ids that may not be captured. Bedrock, the portals and the command blocks are here because a captured one can be released anywhere.",
                            "An unknown id is logged and skipped rather than refusing to start.")
                    .defineListAllowEmpty("structureBlockBlacklist", List.of(
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
                            "minecraft:light"), value -> value instanceof String);

    public static final ModConfigSpec.BooleanValue STRUCTURE_MIGRATE_SLOTS = BUILDER
            .comment("Carry this mod's own components that live INSIDE blocks with a captured structure, and put them back on release.",
                    "On by default: without it a captured host block arrives empty inside.")
            .define("structureMigrateSlots", true);

    public static final ModConfigSpec.BooleanValue STRUCTURE_RELEASE_CONFLICT_GUARD = BUILDER
            .comment("Ask before a release overwrites blocks.",
                    "On by default: a target holding anything is refused once, with the block count, and goes ahead when asked again.")
            .define("structureReleaseConflictGuard", true);

    public static final ModConfigSpec.IntValue STRUCTURE_RELEASE_BLOCKS_PER_TICK = BUILDER
            .comment("How many blocks a release writes per tick. A 48³ structure is 110,592 blocks, too many for one tick.",
                    "4096 is about 27 ticks (1.4 s) for the largest allowed structure.")
            .defineInRange("structureReleaseBlocksPerTick", 4096, 1, 110592);

    public static final ModConfigSpec SPEC = BUILDER.build();

    private RCConfig() {
    }

    public static boolean debugLog() {
        // The package default (BuildFlags) and the config file both describe the PACKAGE; a system property is
        // how one RUN asks for the detailed log - a released jar, or this project's own test task.
        Boolean forced = forcedDebugLog();
        if (forced != null) {
            return forced;
        }
        // Config values throw while the config has not been loaded yet (e.g. during datagen), hence
        // the fallbacks throughout this class.
        return boolOr(DEBUG_LOG, BuildFlags.defaultDebugLog());
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
        return boolOr(ALLOW_REPLACE, false);
    }

    public static boolean validateHosts() {
        return boolOr(VALIDATE_HOSTS, true);
    }

    /** See {@link #PLACER_CREATES_STRUCTURES}: off by default while Sable's storage question is open. */
    public static boolean placerCreatesStructures() {
        return boolOr(PLACER_CREATES_STRUCTURES, false);
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
     * Whether a host block carries the strong power pushed into it onwards, as a plain solid block does.
     *
     * <p>Off by default: a host block is the component inside it, so a diode pointing into one hands its
     * signal to that component rather than turning the block into a redstone block for its neighbours.
     */
    public static boolean hostConductsStrongPower() {
        return boolOr(HOST_CONDUCTS_STRONG_POWER, false);
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

    /**
     * Border colour for a block holding superconducting wire, or 0 when there is no border to draw.
     *
     * <p>Shares the border's alpha with the ordinary frame: this is the same frame in another colour,
     * not a second decoration.
     */
    public static int superconductorFrameColor() {
        int alpha = intOr(HOST_OVERLAY_FRAME_ALPHA, 170);
        return alpha <= 0 ? 0
                : (alpha << 24) | parseRgb(stringOr(SUPERCONDUCTOR_FRAME_COLOR, "FF8C00"), 0xFF8C00);
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

    /**
     * Whether the per-host gates are ignored so a frame draws whatever was collected.
     *
     * <p>Off by default, and it changes nothing about <em>what</em> is collected - only about whether
     * the colours, {@code showInnerComponents} and the shader pack's solid-frame rule are allowed to
     * refuse a quad. See the config comment: the point is that a run with this on and a frame summary
     * still reporting zero emitted quads is proof that the geometry was drawn and not seen, which is
     * the one question a log could not previously answer.
     */
    public static boolean forceOverlayDraw() {
        return boolOr(FORCE_OVERLAY_DRAW, false);
    }

    public static boolean wrenchNeedsGoggles() {
        return boolOr(WRENCH_NEEDS_GOGGLES, true);
    }

    public static boolean rotationRodNeedsGoggles() {
        return boolOr(ROTATION_ROD_NEEDS_GOGGLES, true);
    }

    /**
     * Whether working what is inside a block - a lever, a repeater's delay, a comparator's mode - needs the goggles
     * on. The rule itself is {@code logic.InnerAccess}; this is only where the setting is read.
     */
    public static boolean innerAdjustNeedsGoggles() {
        return boolOr(INNER_ADJUST_NEEDS_GOGGLES, true);
    }

    public static boolean wrenchClearOnSameBlock() {
        return boolOr(WRENCH_CLEAR_ON_SAME_BLOCK, true);
    }

    public static boolean traceSlotSync() {
        return boolOr(TRACE_SLOT_SYNC, false);
    }

    /** Whether components travel with the moving structure that picked their block up. */
    public static boolean carrierEnabled() {
        return boolOr(CARRIER_ENABLED, true);
    }

    /**
     * Whether every contraption callback is logged, including the ones that carry nothing.
     *
     * <p>Off by default because it is one line per block of a machine. Turn it on to answer "is this
     * block type being hooked at all", which is otherwise invisible until you look at a machine that
     * has already been assembled.
     */
    public static boolean traceCarrier() {
        return boolOr(TRACE_CARRIER, false);
    }

    /**
     * Whether the bridge between a carried host and the world around it is closed.
     *
     * <p>Off by default, which means the bridge is <b>open</b> and the two directions work. {@code true} closes it -
     * the inside of a machine and the world it is flying through cannot see each other - which is what a player who
     * wants the old behaviour should turn on.
     */
    public static boolean carrierFreezeWorldIo() {
        return boolOr(CARRIER_FREEZE_WORLD_IO, false);
    }

    /**
     * Whether the inside solve also reads the signal sources the structure itself is carrying.
     *
     * <p>See {@link #CARRIER_STRUCTURE_SIGNALS}: independent of {@link #carrierFreezeWorldIo()}, which is the world
     * boundary and only ever closes the world half, while a lever the machine is carrying is not part of the world
     * at all.
     */
    public static boolean carrierStructureSignals() {
        return boolOr(CARRIER_STRUCTURE_SIGNALS, true);
    }

    /**
     * Whether an output change also wakes the neighbours of the structure's own coordinate.
     *
     * <p>See {@link #CARRIER_NOTIFY_STRUCTURE_NEIGHBOURS}. Read once per carrier pass (not per change
     * and not per host), and the notification it turns on is additionally subject to
     * {@link #solverCoalesceNotifications()} - see {@code SolverNotifications#structurePass}: with the
     * coalescing switch off nothing new happens at all, which is the "back to the previous build"
     * direction that switch has always meant.
     */
    public static boolean carrierNotifyStructureNeighbours() {
        return boolOr(CARRIER_NOTIFY_STRUCTURE_NEIGHBOURS, true);
    }

    /**
     * Whether breaking a block a structure is carrying drops the component inside it.
     *
     * <p>See {@link #CARRIER_BLOCK_DROPS}. Never consulted on a release or a landing: only the
     * destroy path asks.
     */
    public static boolean carrierBlockDrops() {
        return boolOr(CARRIER_BLOCK_DROPS, true);
    }

    /**
     * Whether a world-store entry standing at a structure's own plot coordinate is adopted.
     *
     * <p>See {@link #ADOPT_WORLD_COMPONENTS}. Read once per structure per adoption attempt, which is
     * throttled to one attempt every {@code SableComponentAdoption.ADOPT_INTERVAL} ticks - so this is a
     * config read per structure per second, not per block and not per tick.
     */
    public static boolean adoptWorldComponents() {
        return boolOr(ADOPT_WORLD_COMPONENTS, true);
    }

    /**
     * How many carried hosts the self-healing sweep may examine per level tick; {@code 0} turns it off.
     *
     * <p>See {@link #CARRIER_SELF_HEAL_CHECK_PER_TICK}: the hard upper bound on the one per-tick walk
     * over a structure's own blocks that this feature adds.
     */
    public static int carrierSelfHealCheckPerTick() {
        return intOr(CARRIER_SELF_HEAL_CHECK_PER_TICK, 8);
    }

    /**
     * Whether the world-side inner solve reads a carried host's output at its projected position.
     *
     * <p>See {@link #CARRIER_WORLD_READS_PROJECTION}. Read on the world solve's neighbour query, so it is a field on
     * the solver's environment rather than a config read per side.
     */
    public static boolean carrierWorldReadsProjection() {
        return boolOr(CARRIER_WORLD_READS_PROJECTION, true);
    }

    /**
     * Whether the redstone wrench acts on a host a structure is carrying.
     *
     * <p>See {@link #CARRIED_WRENCH}. Read on the carrier-side click path only, which is reached only for a click
     * the carrier layer has already resolved to one of its own hosts.
     */
    public static boolean carriedWrench() {
        return boolOr(CARRIED_WRENCH, true);
    }

    /**
     * Whether the rotation rod acts on a host a structure is carrying.
     *
     * <p>See {@link #CARRIED_ROTATION_ROD}, and {@link #carriedWrench()} for where the switch is read.
     */
    public static boolean carriedRotationRod() {
        return boolOr(CARRIED_ROTATION_ROD, true);
    }

    /**
     * Whether Jade shows the component inside a carried block.
     *
     * <p>See {@link #CARRIED_JADE}. Read by the optional Jade plugin, which is only ever loaded when
     * Jade itself is installed; {@code false} makes the carried branch say nothing at all, which is
     * exactly the behaviour before this switch existed.
     */
    public static boolean carriedJade() {
        return boolOr(CARRIED_JADE, true);
    }

    /**
     * Whether a component can be put into, and taken out of, a block a structure is carrying.
     *
     * <p>See {@link #CARRIED_INSERT}. Read on the carrier-side click path only, which is reached only
     * for a click the carrier layer has already resolved to one of its own hosts - so {@code false} is
     * exactly "the behaviour before this switch existed" and nothing else changes. The world's own
     * placement and retrieval paths never read it.
     */
    public static boolean carriedInsert() {
        return boolOr(CARRIED_INSERT, true);
    }

    /**
     * Whether a coordinate a structure owns is declined by the world's click handling.
     *
     * <p>See {@link #CARRIED_INSERT_PRIORITY}. Read on <b>both</b> sides of one decision: the world's
     * click handler asks it before it claims anything, and the carrier's resolver asks it so the two
     * cannot disagree about which of them owns a position. {@code false} is exactly the behaviour before
     * this switch existed - the world path may write its own store at a structure's own coordinate.
     */
    public static boolean carriedInsertPriority() {
        return boolOr(CARRIED_INSERT_PRIORITY, true);
    }

    /**
     * Whether a bare hand can work a component inside a block a structure is carrying.
     *
     * <p>See {@link #CARRIED_SWITCHES}. Read on the carrier-side click path only, and only for the
     * gesture it describes - a bare hand, not sneaking - so {@code false} is exactly "the behaviour
     * before this switch existed": the click resolves, the carrier layer decides it has nothing to do,
     * and the event is not consumed. The world's own switch path never reads it.
     */
    public static boolean carriedSwitches() {
        return boolOr(CARRIED_SWITCHES, true);
    }

    /**
     * Whether the solver writes one line per step rather than one summary line per window.
     *
     * <p>See {@link #VERBOSE_SOLVER_LOG}. Read only by {@code SolverLog}, and only to decide whether the
     * four per-step lines are written; the counting, the summary and the spin warning happen either way.
     */
    public static boolean verboseSolverLog() {
        return boolOr(VERBOSE_SOLVER_LOG, false);
    }

    /**
     * Whether the client's carried-render diagnostics are written every frame.
     *
     * <p>See {@link #VERBOSE_RENDER_LOG}. Read only by {@code CarrierRenderTrace}, and only to decide
     * <em>when</em> its three lines are written: the counters, the gates, the geometry and the verdicts are
     * recorded either way, so turning this on cannot change a single number - only how often one is
     * printed.
     */
    public static boolean verboseRenderLog() {
        return boolOr(VERBOSE_RENDER_LOG, false);
    }

    /**
     * Whether the solver's neighbour notifications are held back to the tick boundary.
     *
     * <p>See the config comment: this is the switch that stops the solver feeding itself inside one
     * tick. It is a plain boolean read at the top of a solve, so a unit test can pin both settings
     * without a loaded config file (the same form {@code carrierStructureSignals} uses).
     */
    public static boolean solverCoalesceNotifications() {
        return boolOr(SOLVER_COALESCE_NOTIFICATIONS, true);
    }

    /**
     * How many recomputes of one host in one tick count as "the solver is spinning".
     *
     * <p>See {@link #SOLVER_SPIN_WARN_PER_TICK}. Read by {@code SolverLog} when it ends a tick.
     */
    public static int solverSpinWarnPerTick() {
        return intOr(SOLVER_SPIN_WARN_PER_TICK, 8);
    }

    /**
     * How long a carrier's pose stays trustworthy on the client, in ticks.
     *
     * <p>Past this age the client stops drawing the carrier's hosts inside it and puts them back on the
     * world path (see {@code ClientHostCache}). The threshold exists so that a carrier the server has
     * stopped mentioning - because it was released by a path that forgot to say so, because the
     * connection hiccuped, because anything at all - cannot leave a component invisible forever.
     */
    public static int carrierPoseStaleTicks() {
        return intOr(CARRIER_POSE_STALE_TICKS, 60);
    }

    /**
     * How often the server re-sends the pose of every structure that is holding something, in ticks.
     *
     * <p>Sending poses only on change is what keeps a moving machine cheap, but a structure that stops
     * moving then stops being mentioned, which looks exactly like a structure that has gone away. The
     * heartbeat is what keeps the two distinguishable - and therefore what lets the client treat
     * silence as absence.
     */
    public static int carrierPoseHeartbeatTicks() {
        return intOr(CARRIER_POSE_HEARTBEAT_TICKS, 20);
    }

    /**
     * How often the server re-sends the pose of a structure that is holding something while it may be
     * turning, in ticks; {@code 0} disables sampling.
     *
     * <p>The upper bound on how long a client can be drawing an old angle. "The pose changed since I
     * last looked" and "the client has been told what the pose is now" are different statements - see
     * the comment on {@link #CARRIER_POSE_SAMPLE_TICKS}.
     */
    public static int carrierPoseSampleTicks() {
        return intOr(CARRIER_POSE_SAMPLE_TICKS, 2);
    }

    /**
     * Whether the redstone inside a moving structure is recomputed in the structure's own coordinates.
     *
     * <p>Off, the components travel and come back and never change value on the way; on, one structure settles at
     * most {@link #carrierSolveMaxComponentsPerTick()} components per tick.
     */
    public static boolean carrierSolveEnabled() {
        return boolOr(CARRIER_SOLVE_ENABLED, true);
    }

    /** How many components one structure may settle in one tick; the per-structure work ceiling. */
    public static int carrierSolveMaxComponentsPerTick() {
        return intOr(CARRIER_SOLVE_MAX_COMPONENTS_PER_TICK, 64);
    }

    /** How many of one structure's host blocks may seed a solve in one tick. */
    public static int carrierSolveMaxNodesPerCarrier() {
        return intOr(CARRIER_SOLVE_MAX_NODES_PER_CARRIER, 256);
    }

    /** Whether every solve pass over a structure is logged; see the config comment. */
    public static boolean traceCarrierSolve() {
        return boolOr(TRACE_CARRIER_SOLVE, false);
    }

    /** Radius for component sync, in blocks; 0 means "everyone in the dimension". */
    public static double slotSyncRange() {
        try {
            return SLOT_SYNC_RANGE.get();
        } catch (IllegalStateException notLoadedYet) {
            return 0.0;
        }
    }

    // ------------------------------------ redstone structure block accessors --

    /** The longest axis a captured structure may have. */
    public static int structureMaxSize() {
        return intOr(STRUCTURE_MAX_SIZE, 48);
    }

    /** Whether the one-block context shell is snapshotted with a captured structure. */
    public static boolean structureCaptureContextShell() {
        return boolOr(STRUCTURE_CAPTURE_CONTEXT_SHELL, true);
    }

    /** How many chunk columns structures may hold loaded, in total. */
    public static int structureMaxLoadedColumns() {
        return intOr(STRUCTURE_MAX_LOADED_COLUMNS, 256);
    }

    /** How many structures may tick at once, in total. */
    public static int structureMaxLoadedPockets() {
        return intOr(STRUCTURE_MAX_LOADED_POCKETS, 16);
    }

    /** How many manual-mode entries one structure may carry. */
    public static int structureManualEntryLimit() {
        return intOr(STRUCTURE_MANUAL_ENTRY_LIMIT, 32);
    }

    /** Whether the pocket dimension mirrors the overworld's time and weather. */
    public static boolean structurePocketSyncEnvironment() {
        return boolOr(STRUCTURE_POCKET_SYNC_ENVIRONMENT, true);
    }

    /** Whether a sound made inside a captured structure is heard where that structure stands. */
    public static boolean structurePocketSounds() {
        return boolOr(STRUCTURE_POCKET_SOUNDS, true);
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

    /** How deep a capture may nest structure blocks. */
    public static int structureMaxNestingDepth() {
        return intOr(STRUCTURE_MAX_NESTING_DEPTH, 1);
    }

    /** How large the downsampled preview that travels with a structure may be. See the option's comment. */
    public static int structurePreviewBytes() {
        return intOr(STRUCTURE_PREVIEW_BYTES, 65536);
    }

    /** How many cells the downsampled preview may have. See the option's comment. */
    public static int structurePreviewCells() {
        return intOr(STRUCTURE_PREVIEW_CELLS, 4096);
    }

    /**
     * Whether a preview is emitted once and redrawn from the uploaded copy. See {@link #STRUCTURE_PREVIEW_CACHED}.
     *
     * <p>Read at the top of every draw, so the switch is also the way out of a build that crashes: a picture that is
     * already uploaded is dropped the first frame this answers false.
     */
    public static boolean structurePreviewCached() {
        return boolOr(STRUCTURE_PREVIEW_CACHED, true);
    }

    /** Whether buried cells are dropped from a preview. See {@link #STRUCTURE_PREVIEW_CULL}. */
    public static boolean structurePreviewCull() {
        return boolOr(STRUCTURE_PREVIEW_CULL, true);
    }

    /** Whether the faces a preview hides are left out of it. See {@link #STRUCTURE_PREVIEW_FACES}. */
    public static boolean structurePreviewFaces() {
        return boolOr(STRUCTURE_PREVIEW_FACES, true);
    }

    /** Whether an icon is drawn at half resolution. See {@link #STRUCTURE_PREVIEW_ICON_DETAIL}. */
    public static boolean structurePreviewIconDetail() {
        return boolOr(STRUCTURE_PREVIEW_ICON_DETAIL, true);
    }

    /** How far from a structure a player may press its manual keys. */
    public static double structureManualRange() {
        return doubleOr(STRUCTURE_MANUAL_RANGE, 8.0);
    }

    /** How long one manual press pushes. */
    public static int structureManualPulseTicks() {
        return intOr(STRUCTURE_MANUAL_PULSE_TICKS, 10);
    }

    /** The least number of ticks between two accepted presses from one player. */
    public static int structureManualCooldownTicks() {
        return intOr(STRUCTURE_MANUAL_COOLDOWN_TICKS, 2);
    }

    /** The least number of ticks between two changes of the signal injected into one cell of a plot. */
    public static int structureInjectionLimitTicks() {
        return intOr(STRUCTURE_INJECTION_LIMIT_TICKS, 4);
    }

    /** Whether the copying tier is available at all (creative only, always). */
    public static boolean structureAllowCopy() {
        return boolOr(STRUCTURE_ALLOW_COPY, true);
    }

    /** How a release is paced, and whether it asks before overwriting. */
    public static com.jiangyuefengyu.redstonecircuit.structure.StructureRelease.Options
            structureReleaseOptions() {
        return new com.jiangyuefengyu.redstonecircuit.structure.StructureRelease.Options(
                intOr(STRUCTURE_RELEASE_BLOCKS_PER_TICK, 4096),
                boolOr(STRUCTURE_RELEASE_CONFLICT_GUARD, true));
    }

    /** The ids in the capture blacklist, as written in the config file. */
    public static List<String> structureBlockBlacklistIds() {
        try {
            return List.copyOf(STRUCTURE_BLOCK_BLACKLIST.get());
        } catch (IllegalStateException notLoadedYet) {
            return List.of();
        }
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

    /** Whether our own components inside blocks travel with a captured structure. */
    public static boolean structureMigrateSlots() {
        return boolOr(STRUCTURE_MIGRATE_SLOTS, true);
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

    private static double doubleOr(ModConfigSpec.DoubleValue value, double fallback) {
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

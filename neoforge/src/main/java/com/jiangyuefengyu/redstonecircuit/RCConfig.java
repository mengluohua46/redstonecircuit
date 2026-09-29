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
                    "The DEFAULT follows the package: detailed while the mod is built and tested, light",
                    "(false) in a release package - see BuildFlags. On a released",
                    "jar, set this to true when you need a diagnostic log: that is the supported way to ask.",
                    "The build flags the detailed per-tick diagnostics in the compat/carrier layer too.")
            .define("debugLog", BuildFlags.defaultDebugLog());

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

    public static final ModConfigSpec.ConfigValue<String> SUPERCONDUCTOR_FRAME_COLOR = BUILDER
            .comment("Border colour for a block holding SUPERCONDUCTING wire, as RRGGBB hex.",
                    "The wire inside a block is small, so the border is what tells the two wire materials",
                    "apart at a glance. Defaults to the orange the superconducting art itself carries;",
                    "set it to the same value as hostOverlayFrameColor to switch the distinction off.")
            .define("superconductorFrameColor", "FF8C00");

    public static final ModConfigSpec.BooleanValue HOST_ACCEPTS_STRONG_POWER = BUILDER
            .comment("Whether a host block also accepts the strong power a neighbouring SOLID block carries.",
                    "On (vanilla semantics): a repeater or torch pushing into a stone block makes redstone next",
                    "to that block live, exactly as it does in vanilla - and a stone block that a wire, torch",
                    "or repeater elsewhere has charged will therefore light the redstone inside the block",
                    "beside it. Turn this OFF to have inner redstone react only to blocks that emit a signal",
                    "themselves, which is friendlier when vanilla wiring runs alongside your host blocks.")
            .define("hostAcceptsStrongPower", true);

    public static final ModConfigSpec.BooleanValue HOST_CONDUCTS_STRONG_POWER = BUILDER
            .comment("Whether a host block passes on the strong power a neighbouring diode pushes into it,",
                    "the way a plain block of stone does.",
                    "Off (the default): a repeater that points into another repeater's block hands its",
                    "signal to that component and nothing else. With it on, that block also carried the",
                    "power onwards to everything around it, so two repeaters in a row lit every piece of",
                    "redstone dust beside the first one - which reads as the repeater signalling sideways.",
                    "Turn it on for strict vanilla block semantics.")
            .define("hostConductsStrongPower", false);

    public static final ModConfigSpec.BooleanValue INNER_COMPONENTS = BUILDER
            .comment("Draw the component itself inside the shell, so a wire, a repeater's facing and a",
                    "lit torch can be read from outside the block. Off leaves only the coloured shell.")
            .define("showInnerComponents", true);

    public static final ModConfigSpec.BooleanValue INNER_VISIBLE_THROUGH_WALLS = BUILDER
            .comment("Draw the component even when a solid block is in the way. Off (the default) means",
                    "the inside of a block is only shown when you can actually see that block, so the",
                    "effect never becomes X-ray vision through a wall.")
            .define("seeInnerComponentsThroughWalls", false);

    public static final ModConfigSpec.BooleanValue FORCE_OVERLAY_DRAW = BUILDER
            .comment("DEBUG. Draw the shell and the component for every host the pass knows about,",
                    "ignoring the colour settings, hostOverlayFrameAlpha, showInnerComponents and the",
                    "shader pack's solid-frame rule.",
                    "It deliberately does NOT ignore hostOverlayMode: with the mode at OFF nothing is",
                    "collected at all, so forcing the per-host gates has nothing to force, and turning",
                    "the mode off must stay a way to see the world exactly as if this mod drew nothing.",
                    "Its one job is to separate the two ways a component can be missing on screen:",
                    "'a setting switched the geometry off' and 'the geometry was drawn and not seen'.",
                    "A frame with forceOverlayDraw=true and a frame summary that still reports 0 quads",
                    "is the second one, and the frame summary now says so in as many words.")
            .define("forceOverlayDraw", false);

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

    // ---------------------------------------------------------- rotation rod --

    public static final ModConfigSpec.BooleanValue ROTATION_ROD_NEEDS_GOGGLES = BUILDER
            .comment("Require the redstone goggles to be worn before the rotation rod will turn anything.",
                    "On by default, like the wrench: aiming a component is done by looking at it.")
            .define("rotationRodNeedsGoggles", true);

    // ------------------------------------------------------------------- sync --

    public static final ModConfigSpec.BooleanValue TRACE_SLOT_SYNC = BUILDER
            .comment("Log every component update sent to clients. Very noisy while a circuit runs; it",
                    "answers 'is the client being told about this block at all'.")
            .define("traceSlotSync", false);

    // -------------------------------------------------- moving structures (P1) --
    // The three settings below only do anything when Sable and/or Create is installed. With neither,
    // nothing in this mod ever consults them.

    public static final ModConfigSpec.BooleanValue CARRIER_ENABLED = BUILDER
            .comment("Let inner redstone components travel with the Create contraption or Sable",
                    "sub-level their host block is picked up by, and put them back when it is set down.",
                    "On by default. Turn it off to keep the old behaviour (a component stays behind at",
                    "the world position, which then reads as air) without uninstalling anything.")
            .define("carrierEnabled", true);

    public static final ModConfigSpec.BooleanValue TRACE_CARRIER = BUILDER
            .comment("Log every contraption callback, including the ones that carry nothing because",
                    "the block holds no component. That is one line per block of the machine, so it is",
                    "off by default; it is what answers 'is my block type being hooked at all'.")
            .define("traceCarrier", false);

    public static final ModConfigSpec.BooleanValue CARRIER_FREEZE_WORLD_IO = BUILDER
            .comment("Close the bridge between a carried component and the world it is flying through.",
                    "OFF (the default, P4): the two sides can see each other. A host block riding a",
                    "structure reports its power at the world position it currently occupies - so a",
                    "machine lights the redstone it passes over - and reads the world signal at that",
                    "same position, so a lever on the ground feeds the machine. Nothing in the world is",
                    "ever modified: only readings are provided.",
                    "ON: exactly the P1/P2 behaviour, in which neither direction is open. Kept so the",
                    "bridge can be compared against a machine that cannot see the world at all.")
            .define("carrierFreezeWorldIo", false);

    /**
     * P1.19, defect two: whether a structure's <b>own</b> blocks count as an input to the solve inside
     * it.
     *
     * <p>A host block riding a machine has two neighbourhoods: the world the machine is flying through
     * (the P4 bridge, closed by {@code carrierFreezeWorldIo}) and the machine's own blocks, which
     * travel with it. The author's report - "the lever can be flicked but nothing inside wakes up" - is
     * the second one: a lever or an Aeronautics throttle lever carried <em>inside</em> the structure
     * emits its signal as a block state, and before this switch existed nothing ever read it.
     *
     * <p>On (the default): the inside solve reads its own input side as the larger of the two halves,
     * so a lever next to a carried host block powers the redstone inside it exactly as it would on the
     * ground, and the reading rules are the same function the world half uses.
     *
     * <p>Off: exactly the withdrawn behaviour - only the world half is read, and a structure's own
     * lever reaches nothing. Kept as a one-line escape hatch so a machine that misbehaves can be put
     * back to the previous behaviour without swapping the jar.
     */
    public static final ModConfigSpec.BooleanValue CARRIER_STRUCTURE_SIGNALS = BUILDER
            .comment("Let the redstone inside a moving structure read the SIGNAL SOURCES THE STRUCTURE",
                    "ITSELF IS CARRYING (a lever, a throttle lever, a button, a torch) as an input to",
                    "the in-structure solve, the same way it reads the world around it.",
                    "ON (default): the inside solve takes the larger of 'the world outside' and 'the",
                    "machine's own blocks', so a lever carried next to a host block powers the redstone",
                    "inside it. OFF: only the world outside is read - the previous behaviour.")
            .define("carrierStructureSignals", true);

    /**
     * P1.36: whether an output change inside a structure also wakes the neighbours of the
     * <b>structure's own coordinate</b>.
     *
     * <p>A Sable sub-level's blocks are the level's own blocks at their plot coordinates (P1.30
     * verified this: plot coordinate == level coordinate), so a vanilla device the machine carries - a
     * piston, a lamp, a piece of redstone wire - lives at a real level position and is ticked by the
     * level. What it does <em>not</em> get is a notification: the only thing P4 ever notified was the
     * world position a host is projected onto, which is where the machine <em>looks</em> like it is and
     * not where its own blocks are. The author's report - "the redstone inside a physicalised block
     * cannot drive the piston above it" - is exactly that missing notification.
     *
     * <p>On (the default): when a carried host's output changes, its structure coordinate is notified
     * as well, once per tick, with the same rule vanilla uses. It is read-only - the world store is
     * never written at that coordinate, which is the mistake sections 26-30 removed.
     *
     * <p>Off: exactly the behaviour before this round (only the projected world position is
     * notified). Independent of {@code carrierFreezeWorldIo}: that switch closes the bridge between
     * the machine and the world it flies through, while this one is about the machine's own blocks,
     * which travel with it.
     */
    public static final ModConfigSpec.BooleanValue CARRIER_NOTIFY_STRUCTURE_NEIGHBOURS = BUILDER
            .comment("Also notify the neighbours of a carried host's OWN structure coordinate when the",
                    "component's output changes - not only the world position the host is projected",
                    "onto.",
                    "ON (default): a vanilla device the machine is carrying (a piston above the",
                    "redstone, a lamp, a wire) is woken and re-reads the signal, the way it would if",
                    "the same blocks stood in the world. It is only a notification: nothing is ever",
                    "written into the world store at that coordinate.",
                    "OFF: the previous behaviour - only the projected world position is notified, so a",
                    "device inside the structure never hears about the change.")
            .define("carrierNotifyStructureNeighbours", true);

    /**
     * P1.19, defect one: whether breaking a block that a structure is carrying drops the component
     * inside it.
     *
     * <p>In the world, breaking a host block drops its component (the {@code BlockEvent.BreakEvent}
     * path has always done that). A host block that has been physicalised - taken into a Sable
     * sub-level or a Create contraption - is removed by whoever owns those blocks, and nothing dropped
     * the component: it stayed in the carried record until the structure was released, which is
     * invisible and, worse, can write a component back into a block that no longer exists.
     *
     * <p>On (the default): a break at a position the carrier layer answers for drops the component as
     * an item at the position the block was, exactly once, and forgets the record. Nothing is dropped
     * for a release or a landing - only when the block has really been removed.
     *
     * <p>Off: the previous behaviour - nothing is dropped on that path.
     */
    public static final ModConfigSpec.BooleanValue CARRIER_BLOCK_DROPS = BUILDER
            .comment("Drop the component as an item when a block a structure is CARRYING is broken,",
                    "the same way breaking a host block in the world does.",
                    "ON (default): the component is dropped where the carried block was, exactly once,",
                    "and the carried record is forgotten. OFF: nothing is dropped (the previous",
                    "behaviour - the component stays in the carried record until the structure is put",
                    "down). Never fires for a release or a landing, only for a block that was removed.")
            .define("carrierBlockDrops", true);

    /**
     * P1.30: whether a world-store entry that stands at a structure's own plot coordinate is
     * <b>migrated</b> into the carrier index.
     *
     * <h2>The state this repairs, and why it is a state and not an event</h2>
     * A Sable sub-level's blocks <b>are</b> the level's blocks at their own local coordinates inside the
     * plot region, so the world store's key space and a structure's local coordinate space overlap: a
     * component can be sitting in {@code InnerRedstoneStore} at a coordinate that is simultaneously "a
     * position in the level" and "a block inside a machine". The author's log is exactly that -
     * {@code placed RETAINING_REPEATER at 20481032, 127, 20481032} with {@code carrier claim = 0} for the
     * whole session - and the consequence is two truths about one block: Jade reads the world store and
     * displays the component, the overlay reads the carrier index and draws nothing, the world bridge
     * cannot see it, and breaking the block drops it through the world's own break path.
     *
     * <p>On (the default): the claim sweep also adopts such entries - the component becomes a carrier
     * record at that coordinate and the <b>world entry is removed</b>, so the two stores cannot disagree
     * any more. The {@code origin} recorded for an adopted host is the world position the structure's own
     * pose names for that coordinate right now; see {@code CarrierStore#adoptWorldComponent} for why that
     * is the honest value and not the plot coordinate.
     *
     * <p>Off: the previous behaviour - nothing is migrated, and a save that is already in this state stays
     * in it. Turning it off does not undo an adoption that has already happened, and does not stop P1.30's
     * prevention half ({@code carriedInsertPriority}), which is what keeps new ones from being written.
     *
     * <p>Bounded and throttled: at most {@code ADOPT_NODES_PER_PASS} world-store entries are examined per
     * structure per attempt, one attempt per structure per {@code ADOPT_INTERVAL} ticks, and the attempts
     * stop after {@code ADOPT_MAX_PASSES}. The work is never done per tick per block.
     */
    public static final ModConfigSpec.BooleanValue ADOPT_WORLD_COMPONENTS = BUILDER
            .comment("Migrate a component that is in the WORLD's own store at a moving structure's own",
                    "coordinate into that structure's carried record, and remove the world entry.",
                    "This is the repair for a save that already has such an entry: without it the",
                    "component is displayed by Jade, drawn by nothing, inert towards the world, and",
                    "dropped by the world's own break path - two truths about one block.",
                    "ON (default). OFF: nothing is migrated (the previous behaviour).",
                    "Bounded and throttled: a few world-store entries per structure every second, and a",
                    "limited number of attempts per structure.")
            .define("adoptWorldComponents", true);

    /**
     * P1.20: how many carried hosts the self-healing sweep may examine per level tick.
     *
     * <p>The second trigger for "a block a structure is carrying has gone", and the one that closes the
     * ghost off rather than moving it: a break event is only one of the ways a carried block can leave,
     * and {@code BlockEvent.BreakEvent} never fires for a Create drill or saw taking a block out through
     * its own movement behaviour.
     *
     * <p><b>It reads one block per host, on the level tick, so it has to be bounded.</b> This value is
     * that bound: the sweep walks at most this many hosts per tick, continuing from a per-carrier cursor
     * on the next tick, so a machine larger than the budget is finished over several ticks rather than
     * examined all at once. The default is deliberately small - the sweep only has to be eventually
     * complete, and the ordinary path is the break event, which is immediate.
     *
     * <p>{@code 0} switches the sweep off completely (and restores exactly the P1.19 behaviour), so a
     * player who suspects it can turn it off without changing anything else.
     *
     * <p>It never acts on a structure it cannot read, and never on a block that is still there - so a
     * structure that is merely being moved cannot be mistaken for one that has been broken.
     */
    public static final ModConfigSpec.IntValue CARRIER_SELF_HEAL_CHECK_PER_TICK = BUILDER
            .comment("How many blocks riding a moving structure the self-healing sweep may examine per",
                    "tick, looking for carried host blocks that have been removed without a break event",
                    "(a Create drill or saw, another mod's structure edit). A host whose own structure no",
                    "longer has a block at its local coordinate is treated as broken: its component is",
                    "dropped as an item where the block was, and the client is told to stop drawing it.",
                    "The sweep walks at most this many hosts per tick and continues from where it left",
                    "off, so a large machine is covered over several ticks. 0 turns the sweep off entirely",
                    "(the P1.19 behaviour: only a break event drops a carried component).")
            .defineInRange("carrierSelfHealCheckPerTick", 8, 0, 4096);

    /**
     * P1.20: whether the world's <b>inner</b> solve reads the output of a host a structure is carrying.
     *
     * <p>The reverse of {@link #CARRIER_STRUCTURE_SIGNALS}, and the direction the author asked for: a
     * component inside a block standing in the world must see the signal a carried host emits at the
     * world position that host currently occupies. Vanilla's own signal query already answers it through
     * the projection table ({@code BlockStateSignalMixin}), and this makes the mod's own world-side read
     * ask directly as well, with the same rule and the same direction conversion - so a lamp, a piston
     * or a piece of redstone beside a passing machine behaves the same whether it is read by vanilla or
     * by us.
     *
     * <p>On (the default). Off: the world-side read is exactly what it was before - whatever vanilla's
     * query answers - and no projection table is consulted from the solve.
     */
    public static final ModConfigSpec.BooleanValue CARRIER_WORLD_READS_PROJECTION = BUILDER
            .comment("Let the redstone inside an ordinary block standing in the world read the output of",
                    "a host block a moving structure is CARRYING, at the world position it currently",
                    "occupies. ON (default): the world-side solve reads the projection table directly, with",
                    "the same rule and the same direction conversion vanilla's own signal query uses, so a",
                    "machine passing a lamp or a piece of redstone is seen by it. OFF: only vanilla's own",
                    "query answers - the behaviour before this switch existed.")
            .define("carrierWorldReadsProjection", true);

    /**
     * P1.20: whether the redstone wrench can be used on a host a structure is carrying.
     *
     * <p>The author's question - <em>can a physicalised block be operated with the wrench or the rotation
     * rod</em> - and the answer is yes, through a <b>carrier-only</b> entry point that never touches the
     * world's own click handling ({@code CarrierInteraction}): the click is resolved to a host in the
     * carrier index, the three-state lock/cut/automatic cycle is applied to that host's record, and the
     * client is re-sent so the overlay redraws at once.
     *
     * <p>On (the default). Off: a click with the wrench on a carried host does nothing at all - exactly
     * the P1.19 behaviour. The world's wrench is <b>not</b> affected by this switch in either position.
     */
    public static final ModConfigSpec.BooleanValue CARRIED_WRENCH = BUILDER
            .comment("Let the redstone wrench be used on a host block a moving structure is CARRYING: pick",
                    "it, then shift-click a neighbouring carried host to step that line through locked /",
                    "cut / automatic, exactly as on a block standing in the world.",
                    "ON (default). OFF: nothing happens on a carried host, i.e. the previous behaviour.",
                    "The world's own wrench behaviour is not affected either way.")
            .define("carriedWrench", true);

    /**
     * P1.20: whether the rotation rod can be used on a host a structure is carrying.
     *
     * <p>The same as {@link #CARRIED_WRENCH}, for 方向转向杆: clicking a face of a carried host turns the
     * component inside it so that face is its input side, or its output side if that is already where it
     * reads from. The clicked face arrives as a <b>world</b> direction and is turned into the structure's
     * own frame through the same quaternion conversion the signal paths use, so a machine that has been
     * rotated turns its components the way the player expects.
     *
     * <p>On (the default). Off: a click with the rod on a carried host does nothing - the P1.19
     * behaviour. The world's rod is not affected either way.
     */
    public static final ModConfigSpec.BooleanValue CARRIED_ROTATION_ROD = BUILDER
            .comment("Let the rotation rod be used on a host block a moving structure is CARRYING: click a",
                    "face to make it the component's input side, click it again to make it the output side.",
                    "ON (default). OFF: nothing happens on a carried host, i.e. the previous behaviour.",
                    "The world's own rod behaviour is not affected either way.")
            .define("carriedRotationRod", true);

    /**
     * P1.21: whether Jade (玉) shows the inner component of a block a structure is <b>carrying</b>.
     *
     * <p>The author asked whether Jade can be made to work "under a physicalised block". It can, but
     * not by the same lookup the world side uses: a physicalised block is a Sable sub-level's block or
     * a Create contraption's, its position in the level is the structure's own local coordinate, and the
     * ordinary host sync has nothing at that coordinate. The carrier payload has the component all
     * along, keyed by exactly that coordinate, so the Jade line is answered from the client's own
     * carried index.
     *
     * <p>On (the default). Off: Jade says nothing about a carried block, which is exactly the
     * behaviour before this switch existed. The world-side Jade lines are not affected either way.
     */
    public static final ModConfigSpec.BooleanValue CARRIED_INSERT = BUILDER
            .comment("Let a redstone component be put INTO a block a moving structure is CARRYING, and",
                    "taken back out again: shift-right-click a carried host with a component in hand to",
                    "store it (the clicked face becomes its input side), shift-right-click with a bare",
                    "hand to take it out. The world's own placement/retrieval is unchanged either way.",
                    "ON (default). OFF: nothing happens on a carried host, i.e. the previous behaviour.",
                    "This is the switch to turn off first if placing components inside a physicalised",
                    "block misbehaves; carrying a machine is otherwise unaffected.")
            .define("carriedInsert", true);

    /**
     * P1.26, defect one: whether a coordinate a structure owns belongs to the <b>carrier</b> side before
     * the world's own click handling sees it.
     *
     * <p>This is the one deliberate exception to the "the world's click path is untouched" rule, and it
     * exists because of a fact about Sable that the world path cannot see: a sub-level's blocks <b>are
     * level blocks</b>, stored at their own local coordinates in the plot region, so a click on one
     * arrives at the ordinary block-click handler carrying a coordinate that is simultaneously
     * "a position in the level" and "a host's coordinate inside a structure". The world handler cannot
     * tell those apart on its own, and it used to win the click: the component was written into the
     * world's own store at the plot coordinate, so Jade (which reads that store) showed it while the
     * overlay drew nothing and the world bridge - which projects from the carrier index - could not see
     * it either. The author's report is exactly that shape: "the component has a display, but nothing is
     * drawn over it and it cannot be connected to anything".
     *
     * <p>On (the default): a click whose position is a coordinate a live structure owns is <b>declined</b>
     * by the world side, which then does not write the world store at all, and the carrier's own entry
     * point records it in the carrier index instead. The narrowing is exact and is the whole of the
     * change:
     * <ul>
     *   <li>only "this position is a coordinate a structure owns" declines anything. Every other world
     *       position - including a carried host's <em>origin</em>, which is an ordinary world block - is
     *       byte-for-byte the behaviour it was before;</li>
     *   <li>the check runs <b>before</b> {@code HostRules#claimsClick}, so the world side does not merely
     *       skip its action: it never claims the click in the first place;</li>
     *   <li>the click is <b>not</b> consumed by that refusal - the carrier's own listener is the one that
     *       decides, and if it decides the click is not its own the event is left to vanilla exactly as
     *       it was before.</li>
     * </ul>
     *
     * <p><b>P1.30 widened "a structure owns it" and nothing else.</b> The set used to be "the carrier
     * index holds a live record at this coordinate"; it is now "a live record, <em>or</em> the level
     * position is a plot cell of a live sub-level". The second half exists because a sub-level loaded from
     * a save has no records at all - a claim is only ever created by taking a component out of a
     * <em>world</em> position - so such a coordinate had no owner as far as the world side could tell, and
     * the click wrote the world store at the plot cell. Neither a Create contraption (its blocks are not
     * level blocks), nor an ordinary world block, nor a carried host's origin, nor a host already put down
     * is admitted by the widened test.
     *
     * <p>Off: the previous behaviour - the world side may write its own store at a structure's
     * coordinate, which is the defect this switch exists to be able to reproduce. Turn it off first if a
     * click on a carried block misbehaves.
     */
    public static final ModConfigSpec.BooleanValue CARRIED_INSERT_PRIORITY = BUILDER
            .comment("Let a coordinate a moving structure OWNS be handled by the carrier side before the",
                    "world's own click handling: a click there is declined by the world path (nothing is",
                    "written into the world's own store at that coordinate) and recorded in the carrier",
                    "index instead, which is what makes a component put into a physicalised block visible",
                    "on the overlay and connected to the world.",
                    "A coordinate counts as the structure's when the carrier index holds a live record",
                    "there, OR when it is a plot cell of a live sub-level - the second case is a structure",
                    "loaded from a save, which has no records of its own.",
                    "ON (default). OFF: the previous behaviour, in which the world path could win the",
                    "click and store the component at the structure's own coordinate - visible to Jade,",
                    "invisible to the overlay and inert towards the world.",
                    "This is the switch to turn off first if a click on a carried block misbehaves.")
            .define("carriedInsertPriority", true);

    public static final ModConfigSpec.BooleanValue CARRIED_SWITCHES = BUILDER
            .comment("Let a bare hand work a component inside a block a moving structure is CARRYING,",
                    "exactly as it works one inside a block standing in the world: right-click (no shift)",
                    "on a carried host to flick a lever, step a repeater's delay, or swap a comparator's",
                    "mode. A button is deliberately NOT included: its release has to be scheduled on a",
                    "level position, and a carried block's level position moves with the machine.",
                    "ON (default). OFF: a bare-hand click on a carried host does nothing, i.e. the",
                    "previous behaviour. The world's own switch behaviour is unchanged either way.")
            .define("carriedSwitches", true);

    public static final ModConfigSpec.BooleanValue VERBOSE_SOLVER_LOG = BUILDER
            .comment("Log every individual solver step: each world/output change that re-queues a host,",
                    "each component recompute with its component list, and each power change with its",
                    "supply and external signal. OFF by default, and it should stay off: one tick of an",
                    "ordinary build can produce hundreds of these lines (a measured run produced 242,588",
                    "of them in a minute), and the same information is summarised as one 'solver:' line",
                    "per 20 ticks. Turn it on only while chasing a solver problem, then turn it back off.")
            .define("verboseSolverLog", false);

    public static final ModConfigSpec.BooleanValue VERBOSE_RENDER_LOG = BUILDER
            .comment("Log the client's carried-render diagnostics every frame instead of on change.",
                    "OFF (default). The three client lines - 'client: carrier frame …', 'client:",
                    "matrixProbe[…]' and 'client: drawCheck …' - are written when the picture actually",
                    "changes, at most once per 20 ticks, plus a heartbeat every two minutes while",
                    "everything is settled. That is the difference between a ~40 MB log and a well under",
                    "1 MB one for the same session: a frame that draws the same thing as the previous",
                    "frame carries no information, and the author's log had 26,668 copies of 'carrier",
                    "frame drew 0, everything settled'.",
                    "ON: the previous per-frame behaviour, for when the frame-by-frame detail is exactly",
                    "what is wanted. It only ever changes WHEN the lines are printed - never what they",
                    "say, and never what is drawn.")
            .define("verboseRenderLog", false);

    public static final ModConfigSpec.IntValue SOLVER_SPIN_WARN_PER_TICK = BUILDER
            .comment("How many times ONE host may be recomputed inside a single tick before the mod writes",
                    "a 'solver spin' warning naming it. Reaching this is not normal: a host recomputed many",
                    "times in one tick means the solver is being re-fed by its own neighbour notifications",
                    "within that tick, which is a defect (two halves of the same circuit re-deriving from",
                    "each other) and not a slow machine. The warning is written at most once per host per",
                    "tick and is capped at four hosts per tick, and it is NOT behind verboseSolverLog - it",
                    "is the one line that survives the aggregation.")
            .defineInRange("solverSpinWarnPerTick", 8, 1, 4096);

    public static final ModConfigSpec.BooleanValue SOLVER_COALESCE_NOTIFICATIONS = BUILDER
            .comment("Coalesce the solver's neighbour notifications to the tick boundary.",
                    "ON (default): while the solver is resolving, no level.updateNeighborsAt is sent;",
                    "the positions whose output changed are collected and notified once, after the",
                    "solve has reached its fixed point (and, for a command-driven solve, before the",
                    "command returns). Every notify re-enters the solver synchronously through",
                    "NeighborNotifyEvent, and a re-entrant notify lets the two halves of one circuit",
                    "(the world reading a host block, the host reading the world) re-derive from each",
                    "other over and over inside a single tick - dozens of rounds per tick, for ever,",
                    "which is the defect this switch fixes.",
                    "OFF: the previous behaviour, one updateNeighborsAt per changed position while the",
                    "solve is still running. It exists so the change can be A/B tested and reverted",
                    "from the config file without swapping the jar; a build with it off behaves exactly",
                    "as the build before it.")
            .define("solverCoalesceNotifications", true);

    public static final ModConfigSpec.BooleanValue CARRIED_JADE = BUILDER
            .comment("Let Jade (jade) show the component hidden inside a block a moving structure is",
                    "CARRYING, on the same terms it shows one inside a block standing in the world:",
                    "the type, its setting and its current state, and only while the redstone goggles",
                    "rule lets it (see overlayMode). ON (default). OFF: nothing is shown for a carried",
                    "block. The lines Jade shows for ordinary world blocks are not affected either way.")
            .define("carriedJade", true);

    public static final ModConfigSpec.IntValue CARRIER_POSE_STALE_TICKS = BUILDER
            .comment("How long, in ticks, a moving structure's pose stays trustworthy on the client.",
                    "The client only draws a component inside its structure while the pose is fresh; if",
                    "the pose has not been refreshed for this many ticks the carrier is let go of and",
                    "the component is drawn on the world path again. This is the backstop that makes",
                    "'a component inside a moving structure can disappear for good' impossible: even if",
                    "the server never says a carrier is gone, the client stops trusting it within this",
                    "many ticks. It must stay comfortably above carrierPoseHeartbeatTicks, because a",
                    "genuinely parked machine is kept alive by that heartbeat and nothing else.")
            .defineInRange("carrierPoseStaleTicks", 60, 5, 1200);

    public static final ModConfigSpec.IntValue CARRIER_POSE_HEARTBEAT_TICKS = BUILDER
            .comment("How often, in ticks, the server re-sends the pose of every structure that is",
                    "holding something, even if it has not moved. Sending a pose only on change is what",
                    "keeps a moving machine cheap, but it also means a structure that stops moving stops",
                    "being mentioned - which is indistinguishable from a structure that has gone away.",
                    "A heartbeat every second (20 ticks) makes the two distinguishable, and it is what",
                    "lets the client treat 'no pose for a while' as 'this carrier is gone'.")
            .defineInRange("carrierPoseHeartbeatTicks", 20, 1, 600);

    public static final ModConfigSpec.IntValue CARRIER_POSE_SAMPLE_TICKS = BUILDER
            .comment("How often, in ticks, the server re-sends the pose of every structure that is",
                    "holding something *while it may be turning*, regardless of whether the pose looks",
                    "changed. This is the upper bound on how long a client can be drawing an old angle:",
                    "with the default of 2, a rotating bearing's angle on screen is at most two ticks",
                    "behind the server's.",
                    "It exists because 'the pose changed since I last looked' and 'the client has been",
                    "told what the pose is now' are different statements: a rotation written by Create's",
                    "actor tick or by Sable's physics step can be produced and consumed between two",
                    "ticks of the server's own pose pass. It costs a handful of bytes per carrier while",
                    "something is riding it, and nothing at all when nothing is.",
                    "Set to 0 to disable sampling and send only on change plus the heartbeat.")
            .defineInRange("carrierPoseSampleTicks", 2, 0, 600);

    // -------------------------------------------------- in-structure solve (P2) --
    // P2 gives a carried component a solver of its own, in the structure's local coordinates. The
    // world boundary stays frozen (that is what carrierFreezeWorldIo above is about and bridging it
    // is P4), so these settings are about the inside of the machine only.

    public static final ModConfigSpec.BooleanValue CARRIER_SOLVE_ENABLED = BUILDER
            .comment("Recompute the redstone inside a moving structure from its own local layout,",
                    "instead of leaving every component at the value it had when the block was picked",
                    "up. On by default. Turning it off restores exactly the P1 behaviour (travel, come",
                    "back, never recompute) without uninstalling anything, which is what the A/B",
                    "comparison that this switch exists for.")
            .define("carrierSolveEnabled", true);

    public static final ModConfigSpec.IntValue CARRIER_SOLVE_MAX_COMPONENTS_PER_TICK = BUILDER
            .comment("How many components one structure may settle in a single tick. Each component",
                    "is a connected run of components - a wire that is one block long is one - so this",
                    "is a ceiling on work, not on the size of a machine: a structure larger than it",
                    "keeps settling over the following ticks, and the log says so. It exists so a",
                    "pathological build cannot stall a tick.")
            .defineInRange("carrierSolveMaxComponentsPerTick", 64, 1, 4096);

    public static final ModConfigSpec.IntValue CARRIER_SOLVE_MAX_NODES_PER_CARRIER = BUILDER
            .comment("How many of one structure's host blocks may be used as a starting point for a",
                    "solve, per tick. This is the only step that touches every host of a structure, so",
                    "it is capped as well; a machine with more components than this has its remaining",
                    "ones solved over the following ticks.")
            .defineInRange("carrierSolveMaxNodesPerCarrier", 256, 1, 4096);

    public static final ModConfigSpec.BooleanValue TRACE_CARRIER_SOLVE = BUILDER
            .comment("Log one line per pass for every structure that has components riding it: how many",
                    "components were settled and how many coordinates changed. This is the only",
                    "measurable evidence that the in-structure solve is running, so it is what the P2",
                    "acceptance procedure uses; it is off by default because it prints once a tick.")
            .define("traceCarrierSolve", false);

    public static final ModConfigSpec.DoubleValue SLOT_SYNC_RANGE = BUILDER
            .comment("How far component updates reach, in blocks. 0 sends them to everyone in the",
                    "dimension, which is the safe default: a client that misses an update keeps drawing",
                    "the old state until something re-sends it.")
            .defineInRange("slotSyncRange", 0.0, 0.0, 512.0);

    public static final ModConfigSpec SPEC = BUILDER.build();

    private RCConfig() {
    }

    public static boolean debugLog() {
        // P1.58: the package default (BuildFlags) and the config file both describe the PACKAGE; a system
        // property is how one RUN asks for the detailed log - on a released jar, and in this project's own
        // test task, which sets it so the suite keeps the evidence its assertions read.
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
     * <p>Off by default since P4, which means the bridge is <b>open</b>: the two directions work.
     * {@code true} restores exactly the P1/P2 state - the inside of a machine and the world it is
     * flying through cannot see each other - which is what makes an A/B comparison possible without a
     * second jar, and what a player who wants the old behaviour should turn on.
     */
    public static boolean carrierFreezeWorldIo() {
        return boolOr(CARRIER_FREEZE_WORLD_IO, false);
    }

    /**
     * Whether the inside solve also reads the signal sources the structure itself is carrying.
     *
     * <p>See {@link #CARRIER_STRUCTURE_SIGNALS}: this is P1.19's one-line escape hatch, and it is
     * deliberately <em>independent</em> of {@link #carrierFreezeWorldIo()} - that switch is the P1/P2
     * world boundary and only ever closes the world half, while a lever the machine is carrying is not
     * part of the world at all.
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
     * <p>See {@link #CARRIER_WORLD_READS_PROJECTION}. Read on the world solve's neighbour query, so it is
     * a field on the solver's environment rather than a config read per side; {@code false} restores
     * exactly the pre-P1.20 behaviour for that direction.
     */
    public static boolean carrierWorldReadsProjection() {
        return boolOr(CARRIER_WORLD_READS_PROJECTION, true);
    }

    /**
     * Whether the redstone wrench acts on a host a structure is carrying.
     *
     * <p>See {@link #CARRIED_WRENCH}. Read on the carrier-side click path only, which is reached only for
     * a click the carrier layer has already resolved to one of its own hosts - so {@code false} is
     * exactly "the P1.19 behaviour" and nothing else changes.
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
     * <p>Off restores the P1 behaviour exactly - the components travel and come back, and never change
     * value on the way - which is what makes the A/B comparison in the P2 acceptance procedure
     * possible without a second jar.
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

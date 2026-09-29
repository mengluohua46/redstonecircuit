package com.jiangyuefengyu.redstonecircuit.carrier;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import com.jiangyuefengyu.redstonecircuit.RCConfig;
import com.jiangyuefengyu.redstonecircuit.data.Slot;
import com.jiangyuefengyu.redstonecircuit.logic.PowerSolver;
import com.jiangyuefengyu.redstonecircuit.logic.SignalReadTrace;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Position;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

/**
 * P4's <b>projection table</b>: where every host a moving structure is carrying currently stands in
 * the world.
 *
 * <h2>Why a table rather than a search</h2>
 * The "structure inside -> world" half of the bridge has to answer one question from the hottest path
 * in the game - a vanilla signal query, which asks "what does the block at <em>this world position</em>
 * emit towards <em>this direction</em>" - and it has to answer it for a position that is usually air.
 * The answer for a riding component is "the component inside the machine whose current world position
 * is this one".
 *
 * <p>The other way round - take the queried position, inverse-transform it into each machine's local
 * space, look it up - would be correct and would need no table, but it costs a pose inversion and a
 * lookup <em>per machine</em> on every signal query in the level. Building the table once per tick
 * instead costs one forward transform per carried host (a handful), and turns the query into one hash
 * lookup with a boolean in front of it. The brief is explicit that the author's world has ~840 host
 * blocks and that none of them may be walked per tick; this table walks only the ones that are
 * actually riding.
 *
 * <h2>What it is not</h2>
 * It never writes to the world. A projected host is a <em>read-only</em> claim - "there is a signal
 * source here, and here is what it emits" - and no block is placed, replaced or modified. That is
 * deliberate: the machine's blocks are an entity's business (Create renders them, Sable keeps them in
 * its plot), and putting copies of them into the world would fight every other mod that manages that
 * space.
 *
 * <h2>Purity</h2>
 * Nothing here names a level, an entity or a mod: it is a map plus the arithmetic that fills it, so
 * both the arithmetic and the table contract are pinned down by offline unit tests. The one thing it
 * cannot do is tell the world that the projection moved - that needs a level, and belongs to
 * {@code CarrierStore}, which drives this class once per tick.
 */
public final class CarrierProjection {

    /**
     * One carried host, virtually standing at a world position.
     *
     * @param carrier the structure carrying it
     * @param localPos its coordinate inside that structure
     * @param worldPos where that coordinate currently is in the world
     * @param pose the carrier's pose at projection time, kept because turning a world signal query
     *     back into the structure's own frame needs it
     * @param slot the component, live (the in-structure solve mutates slots in place, so this stays
     *     current between rebuilds)
     * @param power the power it held when the table was built, which is the "before" side of the
     *     change comparison: the slot objects are mutated in place, so a live-versus-live comparison
     *     would always see a change and never report one
     */
    public record ProjectedHost(CarrierId carrier, BlockPos localPos, BlockPos worldPos,
                                CarrierPose pose, Slot slot, int power) {

        /** One entry for the diagnostics: "local -> world (what is inside)". */
        public String describe() {
            return localPos.toShortString() + "->" + worldPos.toShortString()
                    + " (" + carrier.shortId() + " " + (slot == null ? "-" : slot.type)
                    + " out=" + power + ")";
        }
    }

    /**
     * <b>The one rule that answers "what does the host projected here emit towards this world
     * direction".</b>
     *
     * <h2>Why it is here, and not written out at each reader</h2>
     * There are two readers of a projected host's output, and they must never disagree:
     *
     * <ul>
     *   <li>vanilla's own signal query, through {@code BlockStateSignalMixin} - this is what a redstone
     *       lamp, a piston or a piece of wire standing next to a passing machine reads;</li>
     *   <li>the mod's <b>world-side inner solve</b> ({@code InnerRedstoneNetwork.SolverEnvironment}) -
     *       this is what a component inside an ordinary block beside that machine reads, which is the
     *       direction the author asked for in P1.20.</li>
     * </ul>
     *
     * <p>Both need three things and nothing else: the structure's own direction (a world direction
     * turned back through the carrier's pose, via {@link CarrierDirections#localDirection} - the mod's
     * one conversion), whether the component emits that way, and whether the caller is asking the
     * <em>weak</em> question or the <em>strong</em> one. Writing that arithmetic once means a change to
     * either reader cannot silently make the other answer differently.
     *
     * <p>Note the direction convention, which is vanilla's and is the single most error-prone thing
     * here: the caller passes the direction <b>from the asker towards the block being asked</b>, i.e.
     * the one a {@code getSignal(pos, direction)} call carries; the emitted direction is its opposite.
     *
     * @param host a projected host, or {@code null} - which answers 0, the same as a machine that is not
     *     there
     * @param worldDirection the direction from the block asking towards the projected host
     * @param strong whether the caller is the strong-power question ({@code getDirectSignal})
     * @param neighbourHoldsComponent whether the block doing the asking holds a component of its own,
     *     which is what stops a carried host acting as a redstone block for everything around it; only
     *     consulted for the strong question
     */
    public static int emittedToward(@Nullable ProjectedHost host, @Nullable Direction worldDirection,
                                    boolean strong, boolean neighbourHoldsComponent) {
        if (host == null || worldDirection == null || host.slot() == null) {
            return 0;
        }
        Direction emitted = CarrierDirections.localDirection(host.pose(), worldDirection.getOpposite());
        return PowerSolver.signalAnswer(host.slot(), emitted, strong, neighbourHoldsComponent,
                RCConfig.hostConductsStrongPower());
    }

    /**
     * <b>The same rule for a query that already arrives in the structure's own frame.</b>
     *
     * <p>P1.36's read half. A piston the machine is carrying asks about the host block next to it in
     * the <em>structure's</em> coordinates, so the world direction the world-side rule would have to
     * turn back through the pose is already a structure-frame direction: converting it a second time
     * would rotate the answer by the machine's orientation and make a repeater fire sideways. The two
     * rules are one line apart on purpose, and this one is the {@code projected == null} branch of
     * {@code BlockStateSignalMixin} - a host standing on the ground has no frame of its own, and
     * neither has a query made from inside a structure.
     *
     * @param host a host indexed by its structure coordinate, or {@code null} - which answers 0, the
     *     same as a coordinate that carries nothing
     * @param queryDirection the direction <b>from the block asking towards the host</b>, i.e. vanilla's
     *     own convention, in the structure's frame
     */
    public static int emittedTowardInStructureFrame(@Nullable ProjectedHost host,
                                                    @Nullable Direction queryDirection, boolean strong,
                                                    boolean neighbourHoldsComponent) {
        if (host == null || queryDirection == null || host.slot() == null) {
            return 0;
        }
        return PowerSolver.signalAnswer(host.slot(), queryDirection.getOpposite(), strong,
                neighbourHoldsComponent, RCConfig.hostConductsStrongPower());
    }

    /**
     * One structure that has <b>turned since the previous pass</b>, reported once per carrier per tick.
     *
     * <p>It exists because a turn is invisible to the rest of the change comparison. When a structure
     * rotates about its own pivot, every one of its blocks can land back on the world position it
     * already occupied - a one-block bearing carrying a host block is exactly that case, and the
     * author's log has it: {@code computedWorld=(2774.500, 77.500, -1394.500)} at every angle,
     * {@code delta=0.000}. The position did not move, the component's power did not change, and yet the
     * answer to "which way does this block emit" is a different answer, because that half is the
     * structure's own orientation ({@link CarrierDirections#worldDirection} uses nothing else). A change
     * comparison that looked only at position and value therefore said "nothing happened" while the
     * machine turned, and a lamp next to it - which only re-reads when something notifies it - kept the
     * value it happened to latch onto, including a zero from the moment a dead face swept past it.
     *
     * @param carrier the structure that turned
     * @param localPos the host the turn was noticed on (the first one, which is enough to report it)
     * @param worldPos where that host is; the position that has to be announced to the world
     * @param before the pose the previous pass projected with
     * @param after the pose this pass projects with
     */
    public record TurnedHost(CarrierId carrier, BlockPos localPos, BlockPos worldPos,
                             CarrierPose before, CarrierPose after) {

        /** How far the structure turned between the two passes, in degrees. */
        public double deltaDegrees() {
            return before.orientationDeltaDegrees(after);
        }

        /** One readable phrase for the log: the two orientations and the angle between them. */
        public String describe() {
            return "rot " + degrees(before) + "deg -> " + degrees(after) + "deg (delta="
                    + String.format(java.util.Locale.ROOT, "%.3f", deltaDegrees()) + "deg)";
        }

        private static String degrees(CarrierPose pose) {
            return String.format(java.util.Locale.ROOT, "%.3f", pose.rotationDegrees());
        }
    }

    /** One dimension's table, plus whether the bridge is open at all. */
    private record Table(boolean bridged, Long2ObjectMap<ProjectedHost> byWorldPos,
                         Long2ObjectMap<ProjectedHost> byStructurePos) {
    }

    /** The current table per dimension. */
    private final Map<ResourceKey<Level>, Table> tables = new HashMap<>();

    /** Last tick's table, kept so "what changed" can be answered without a second pass over the poses. */
    private final Map<ResourceKey<Level>, Table> previous = new HashMap<>();

    // Diagnostics only; the table's own job never reads these.
    private long queries;
    private long queriesAtLastBegin;
    private int queriesThisTick;
    @Nullable
    private BlockPos lastQueryPos;
    private int lastQueryPower;

    /**
     * How many times a signal query was answered from the <b>structure's own frame</b> since the
     * process started; see {@link #atStructure}.
     *
     * <p>P1.36's read half. The author's next log has to be able to tell "the piston was woken and read
     * the right value" from "the piston was woken and read zero", and this counter is that evidence:
     * the world-side one ({@link #queries}) covers a machine feeding the world, this one covers the
     * machine feeding itself.
     */
    private long structureQueries;

    /**
     * How many structure-frame queries were <b>declined</b> because the coordinate belongs to another
     * live sub-level; see {@link StructureRead#OTHER_STRUCTURE} and {@link #atStructureOwnedBy}.
     *
     * <p>P1.37, and the number that makes the fix visible: a machine whose neighbour is another
     * physicalised block has every one of those reads pass through this counter, and each one is a read
     * that the world projection source answers instead. Paired with {@link #structureQueries}: the two
     * together are "the machine read itself" versus "the machine read its neighbour's world position".
     */
    private long structureDeclines;

    /** The last read that was classified, so a diagnostic can name the frame with one word. */
    private ReadMode readMode = ReadMode.WORLD_PROJECTION;

    /** The structure the last declined question really belonged to, so the line can name it. */
    @Nullable
    private CarrierId lastDeclinedCarrier;

    /**
     * How many host positions a turn has forced a re-publication of since the process started.
     *
     * <p>Printed by {@link #describe}: it is the number that separates "the world was told because the
     * machine moved" from "the world was told because the machine turned", and the second one used to be
     * zero for ever.
     */
    private long turnRepublish;

    // ------------------------------------------------------------ arithmetic --

    /**
     * The world position a block at local coordinate {@code localPos} occupies, for {@code pose}.
     *
     * <p>This is {@link CarrierPose#blockCentre} rounded to the block grid - the same arithmetic the
     * landing position of a released host uses, and using one implementation for both is the point:
     * "where would this host be put down right now" and "where is this host right now" are the same
     * question, and two answers that can drift apart would be a class of bug nobody could see.
     */
    public static BlockPos project(CarrierPose pose, BlockPos localPos) {
        Vector3d centre = pose.blockCentre(
                new Vector3d(localPos.getX(), localPos.getY(), localPos.getZ()), new Vector3d());
        return BlockPos.containing(new WorldPoint(centre));
    }

    /**
     * A plain 3D point, seen as the {@link Position} {@link BlockPos#containing} takes.
     *
     * <p>The transform works in {@code Vector3d} and the block grid wants a {@code Position}; this is
     * the two-line adapter between them.
     */
    private record WorldPoint(Vector3d point) implements Position {

        @Override
        public double x() {
            return point.x;
        }

        @Override
        public double y() {
            return point.y;
        }

        @Override
        public double z() {
            return point.z;
        }
    }

    // ---------------------------------------------------------------- writing --

    /**
     * Starts a fresh table for one dimension, keeping the one it replaces for the change comparison.
     *
     * <p>Called once per level tick before the carriers are projected, so the table always describes
     * the tick it was built in. {@code bridged} is the {@code carrierFreezeWorldIo} switch: when it is
     * false the table exists but answers nothing, which is exactly the P1/P2 behaviour.
     */
    public void begin(ResourceKey<Level> dimension, boolean bridged) {
        Table old = tables.get(dimension);
        if (old != null && !old.byWorldPos().isEmpty()) {
            previous.put(dimension, old);
        } else {
            previous.remove(dimension);
        }
        queriesThisTick = (int) Math.min(Integer.MAX_VALUE, queries - queriesAtLastBegin);
        queriesAtLastBegin = queries;
        tables.put(dimension, new Table(bridged, new Long2ObjectOpenHashMap<>(),
                new Long2ObjectOpenHashMap<>()));
    }

    /**
     * Adds one projected host.
     *
     * <p>Ignored when the dimension's table is closed or absent.
     *
     * <h2>Two keys, and why the second one is not merely the first one renamed</h2>
     * The world table is keyed by where the host <em>is</em>; it answers a query from the world, which
     * only ever names world coordinates. A Sable sub-level's blocks are the level's own blocks at their
     * plot coordinates (P1.30 section 30.1.2: plot coordinate == level coordinate, no pose arithmetic),
     * so a query made <em>from inside the structure</em> - a piston the machine is carrying asking the
     * host block beside it for a signal, and the notification that wakes that piston - names the local
     * coordinate instead. That is a different question with a different key, so it gets its own table.
     *
     * <p>Only sub-level carriers are indexed there, and that is a rule rather than an optimisation: a
     * Create contraption's local coordinates are 0..15 relative to its anchor, which are <b>not</b>
     * level positions, so answering (or notifying) at them would talk about whatever blocks happen to
     * sit near the world origin. A Sable sub-level's plot coordinates <em>are</em> level positions, and
     * {@code CarrierSource#plotOwnerAt} - the plot containment test P1.30 verified - is the authority
     * on that. Which classes of carrier hold plot-coordinate records is decided upstream: the Create
     * half stands down for a physics-assembled structure (see {@code CarrierResolver#isSubLevelBacked}),
     * so a carrier filed as a sub-level holds sub-level coordinates.
     */
    public void put(ResourceKey<Level> dimension, ProjectedHost host) {
        Table table = tables.get(dimension);
        if (table == null || !table.bridged()) {
            return;
        }
        table.byWorldPos().put(host.worldPos().asLong(), host);
        if (host.carrier().isSubLevel()) {
            table.byStructurePos().put(host.localPos().asLong(), host);
        }
    }

    /**
     * The host projected at a world position, or {@code null}.
     *
     * <p>The hot-path read: one map lookup, and a boolean in front of it while the bridge is closed.
     * It is uncounted on purpose - the diagnostics count the <em>world's</em> queries, which is what
     * {@link #noteQuery} is for, and this accessor is also used internally.
     */
    @Nullable
    public ProjectedHost at(ResourceKey<Level> dimension, BlockPos worldPos) {
        Table table = tables.get(dimension);
        if (table == null || !table.bridged()) {
            return null;
        }
        return table.byWorldPos().get(worldPos.asLong());
    }

    /** True when the dimension's table is open, i.e. when the bridge is not frozen. */
    public boolean isBridged(ResourceKey<Level> dimension) {
        Table table = tables.get(dimension);
        return table != null && table.bridged();
    }

    /**
     * <b>Which structure a query at a plot coordinate is addressed to.</b>
     *
     * <p>P1.37. A Sable sub-level's plot coordinates are level coordinates (P1.30 section 30.1.2), and
     * Sable hands out a plot region per physicalised structure - so when the author builds by placing
     * one block, physicalising it, and placing the next one beside it, <b>structure B's own coordinate
     * is numerically the same number as structure A's world position</b>. One integer, two questions.
     *
     * <p>That makes the second table's key ambiguous: {@code byStructurePos} holds "the host whose
     * coordinate inside its structure is this number", and a query arriving at that number may have been
     * made from inside A, from inside B, or by the world. This enum is the answer to the only part of
     * that the carrier layer can decide offline, and it is decided by the one authority P1.30
     * established for plot cells - {@code CarrierSource#plotOwnerAt}:
     *
     * <ul>
     *   <li>{@link #OWN_STRUCTURE} - the coordinate is the found host's <b>own</b> structure's block, so
     *       a query made in the structure's frame is answered here, in the structure's own frame. This
     *       is P1.36's ability: the author's piston asking the host block beside it;</li>
     *   <li>{@link #OTHER_STRUCTURE} - the coordinate belongs to a <b>different</b> live sub-level, so
     *       the found host only matches <em>numerically</em>: it is another structure's block standing
     *       in the world, and the query is really about that world position. Answering it here would be
     *       the pose-rotated wrong frame; it belongs to the world projection source, exactly as it did
     *       before P1.36;</li>
     *   <li>{@link #NOT_A_STRUCTURE} - no live structure's own block storage contains the coordinate,
     *       so it is an ordinary world coordinate (or a plot region nothing is standing on) and the
     *       structure frame has no business answering at all.</li>
     * </ul>
     */
    public enum StructureRead {
        /** The coordinate is the found host's own structure's block: the structure frame owns it. */
        OWN_STRUCTURE,
        /** The coordinate is another live sub-level's block: the world projection source owns it. */
        OTHER_STRUCTURE,
        /** No live structure's own storage holds the coordinate: not a structure-frame question. */
        NOT_A_STRUCTURE
    }

    /**
     * Own are the reads this round answers in the structure's own frame, and the one marker the
     * diagnostics print so "the machine fed itself" is distinguishable from "the world read the
     * machine" at a glance - see {@link #readMode()}.
     */
    public enum ReadMode {
        /** No structure-frame read has been answered or declined since the process started. */
        WORLD_PROJECTION,
        /** A read at the structure's own coordinate was answered here. */
        STRUCTURE_FRAME,
        /** A read at another structure's coordinate was <b>declined</b> here and left to the world. */
        CROSS_STRUCTURE_DECLINED
    }

    /**
     * <b>The pure decision.</b> Whether a query at a plot coordinate is addressed to the found host's
     * own structure.
     *
     * <p>Deliberately a static function of two values: it is the whole of P1.37's fix, and it is
     * exercised offline by the tests instead of being reachable only through a live sub-level container.
     *
     * @param found the host the structure index holds at that coordinate, or {@code null} when it holds
     *     none
     * @param plotOwner the structure whose own block storage contains the coordinate, or {@code null}
     *     when no live structure's does
     */
    public static boolean withinStructureFrame(@Nullable ProjectedHost found,
                                               @Nullable CarrierId plotOwner) {
        return found != null && plotOwner != null && plotOwner.equals(found.carrier());
    }

    /** {@link #withinStructureFrame} with the reason kept, so a line can name the rule that fired. */
    public static StructureRead structureRead(@Nullable ProjectedHost found,
                                              @Nullable CarrierId plotOwner) {
        if (found == null || plotOwner == null) {
            return StructureRead.NOT_A_STRUCTURE;
        }
        return plotOwner.equals(found.carrier())
                ? StructureRead.OWN_STRUCTURE
                : StructureRead.OTHER_STRUCTURE;
    }

    /**
     * <b>P1.41: whether a projected host may be read as an <em>external supply</em> by one carrier.</b>
     *
     * <h2>The rule, and the two cases it must not confuse</h2>
     * The projected half of the world read ({@code InnerRedstoneNetwork#projectedSignalAt}) is a
     * <em>raw emission</em>: it asks what the host a structure is carrying emits that way, and it charges
     * no hop. That is right for a host <b>another</b> structure is carrying - the whole of P1.38, and the
     * only route by which two independently physicalised blocks can read each other, because the level
     * itself sees air where a carried block stands - and wrong for one of the <b>reader's own</b> hosts.
     * There the neighbour is a node of the running solve, and its value already reaches the asker through
     * the inner-neighbour relationship, which charges the hop ({@code PowerSolver#afterHop}: one for dust,
     * nothing for a superconductor). A reader that took the projected copy as well would hold the same
     * component twice and, since the two are combined with {@code max}, would keep the unattenuated one: a
     * run of carried dust would stop fading while the same run on the ground still faded.
     *
     * <p>A {@code null} reader means "no filtering" - the world-side solve, and any read with no carrier
     * identity behind it - which is exactly the behaviour every reader had before P1.41. So this predicate
     * is narrow on purpose: it removes the reader's own projection and nothing else, and the seam P1.38
     * opened is untouched.
     *
     * <p>Split out here, next to {@link ProjectedHost}, because it is a pure question of two plain values:
     * it is reachable offline, so the rule itself is pinned by a unit test rather than only by a running
     * server, and {@code InnerRedstoneNetwork#projectionReadableBy} - the name the read uses - cannot
     * disagree with it.
     *
     * @param found the host projected at the position being read, or {@code null} for air
     * @param reader the carrier doing the reading, or {@code null} when the read is not carrier-side
     */
    public static boolean readableBy(@Nullable ProjectedHost found, @Nullable CarrierId reader) {
        return found != null && (reader == null || !reader.equals(found.carrier()));
    }

    /**
     * The host whose <b>structure's own coordinate</b> is this level position, or {@code null}.
     *
     * <h2>What asks this, and why one hash lookup is the whole cost</h2>
     * A vanilla device the machine is carrying - the author's piston - asks the level for the signal at
     * the coordinate of the host block next to it. That coordinate is a plot coordinate, and the
     * projection table's world key does not hold it, so before P1.36 every such query was answered
     * zero: the piston could be woken (it is a real level block, ticked by the level) and would still
     * see nothing. This is that read, and it is the hot path's shape again - one map lookup with the
     * bridge's boolean in front of it - so that "the machine's own piston reads the machine's own
     * host block" costs what "the world reads the machine" costs.
     *
     * <p>Read-only, and it answers only for carriers whose local coordinates <em>are</em> level
     * coordinates; see {@link #put}.
     *
     * <p><b>P1.37: this form is the unguarded index lookup.</b> It answers whatever host the second
     * table holds at that number, which is the right answer for a caller that already knows whose
     * coordinate it is asking about (the diagnostics, a size report) and the <b>wrong</b> one for a
     * signal query, which does not. Production's signal path goes through
     * {@link #atStructureOwnedBy(ResourceKey, BlockPos, CarrierId)} with the plot owner as the answer -
     * see {@code CarrierStore#structureHostAt}, which is the only reader of that form.
     */
    @Nullable
    public ProjectedHost atStructure(ResourceKey<Level> dimension, BlockPos structurePos) {
        return atStructureOwnedBy(dimension, structurePos, null);
    }

    /**
     * The same lookup, guarded by <b>whose coordinate this is</b> - the shape every signal query asks.
     *
     * <p>{@code owner} answers "which structure's own block storage contains this coordinate", the
     * {@code CarrierSource#plotOwnerAt} question P1.30 verified, already resolved by the caller so the
     * decision here stays a two-value comparison. A coordinate that belongs to another live sub-level is
     * refused and left to the world projection source, which is what keeps two independently
     * physicalised blocks talking to each other (P1.37) while the machine's own blocks keep reading
     * their own host in their own frame (P1.36).
     *
     * <p>{@code null} keeps the unguarded behaviour (any host the second table holds at that number is
     * answered, which is what the diagnostics and the notification pass want), and so does an owner of
     * {@code null} (no structure owns the coordinate): the caller in {@code CarrierStore} documents why
     * that fallback is the honest one rather than a silent regression - it only asks when the second
     * table holds something, so the ordinary "nothing is indexed here" answer costs no ownership
     * question at all.
     */
    @Nullable
    public ProjectedHost atStructureOwnedBy(ResourceKey<Level> dimension, BlockPos structurePos,
                                            @Nullable CarrierId owner) {
        Table table = tables.get(dimension);
        if (table == null || !table.bridged() || structurePos == null) {
            return null;
        }
        // One boolean before the coordinate lookup, and it is the case this runs into most often: a
        // level with no sub-level carrier holding anything has an empty second table, so the cost this
        // adds to the hottest path in the game is one map lookup and one emptiness check - see
        // BlockStateSignalMixin, which asks this on every signal query the world store does not answer.
        if (table.byStructurePos().isEmpty()) {
            return null;
        }
        ProjectedHost found = table.byStructurePos().get(structurePos.asLong());
        if (found == null) {
            return null;
        }
        // A null owner is "the caller already knows whose coordinate this is" - the unguarded form, and
        // the only one the diagnostics and the notification pass use. A non-null owner is the guard.
        return owner == null || withinStructureFrame(found, owner) ? found : null;
    }

    /** How many hosts are indexed by their structure coordinate in one dimension right now. */
    public int structureSize(ResourceKey<Level> dimension) {
        Table table = tables.get(dimension);
        return table == null ? 0 : table.byStructurePos().size();
    }

    /**
     * The one dimension a table is currently holding anything for, or {@code null} when there is none.
     *
     * <p>Exists for the one caller that has no level to name: the unit tests, which drive the click
     * resolver with a dimension the production paths never have to guess at. Deliberately answers
     * {@code null} when more than one table holds something, rather than picking one - a "which level
     * did you mean" with two answers is exactly the kind of guess this class refuses to make.
     */
    @Nullable
    public ResourceKey<Level> onlyDimension() {
        ResourceKey<Level> found = null;
        for (Map.Entry<ResourceKey<Level>, Table> entry : tables.entrySet()) {
            if (entry.getValue().byWorldPos().isEmpty()) {
                continue;
            }
            if (found != null) {
                return null;
            }
            found = entry.getKey();
        }
        return found;
    }

    /** How many hosts are projected in one dimension right now. */
    public int size(ResourceKey<Level> dimension) {
        Table table = tables.get(dimension);
        return table == null ? 0 : table.byWorldPos().size();
    }

    /** Every world position something is projected at, in one dimension; for the diagnostics. */
    public List<ProjectedHost> hosts(ResourceKey<Level> dimension) {
        Table table = tables.get(dimension);
        return table == null ? List.of() : new ArrayList<>(table.byWorldPos().values());
    }

    // ------------------------------------------------------------ change pass --

    /**
     * Visits every position where this tick's projection differs from the previous one.
     *
     * <p>This is what makes the outward half <em>visible</em>. A redstone lamp only re-reads its
     * neighbours when it is notified, and a machine moving past one notifies nothing by itself: its
     * blocks are an entity, not block changes. So a position whose projection appeared, vanished,
     * moved, changed value <b>or turned</b> has to be announced to the world, and those positions are
     * exactly what this reports - the vanished ones so that whatever was lit there goes dark, the new
     * ones so that whatever is now next to a live component wakes up.
     *
     * <p>The value compared is the <b>live</b> one ({@code slot.power}), not the snapshot in the
     * record: the caller runs this after the tick's solve, so a component whose power the solve just
     * changed is announced in the same tick rather than the next one. The <em>old</em> side has to be
     * the snapshot, because the slot objects are mutated in place and a live-versus-live comparison
     * would always see a change and never report one.
     *
     * <h2>A turn is a change, and it is the one this comparison used to miss</h2>
     * Position, local coordinate, carrier and value are not the whole of "what the world reads here":
     * a host block answers a signal query in <em>its own</em> frame, so which way it emits towards the
     * world is the structure's orientation and nothing else. A structure that turns about its own pivot
     * can put every host back onto the world position it already occupied - a one-block bearing is the
     * author's own case - so all four of those fields stay identical while the emitted directions
     * rotate. Comparing them alone therefore reported "nothing changed" for the whole turn: a consumer
     * that had latched onto a zero (the torch's attachment face sweeping past it) was never woken
     * again, and the signal stayed dark after the machine had turned the live face back onto it. The
     * pose is compared through {@link CarrierPose#sameOrientation}, which is the same
     * orientation-sensitive test the pose broadcast uses, so the two halves cannot drift apart.
     *
     * <h2>P1.36: and the structure's own coordinate, which is a second, different position</h2>
     * Everything above is about the <b>world</b> position a host is projected onto. A host the machine
     * is carrying also has a position <em>inside the machine</em>, and for a Sable sub-level that
     * position is a real level position too (plot coordinate == level coordinate, P1.30 section
     * 30.1.2). A vanilla device the machine carries - the author's piston above the redstone - lives
     * there and is ticked by the level, so it has to be told about a change as well; the world-side
     * notification alone never reaches it. That is {@code structureAction}, and it is answered from the
     * structure-frame history ({@link Table#byStructurePos()}) rather than from the world one, because
     * "the machine moved" is not "the component's output changed": a machine that flies somewhere new
     * has changed every world position it occupies and none of its own blocks.
     *
     * <p>Two deliberate omissions, and both are the reason this is one walk and not two:
     *
     * <ul>
     *   <li><b>A vanished host is not offered.</b> The {@code action} walk above reports positions that
     *       are no longer projected so whatever they were lighting goes dark; the structure coordinate
     *       itself has no such duty, and the block there may not even exist any more.</li>
     *   <li><b>A host whose structure coordinate <em>is</em> its world coordinate is not offered.</b>
     *       That is the case where the two halves of this class name the same number, and notifying it
     *       twice would be one coordinate woken twice in one tick. The caller has already been told
     *       about that coordinate by {@code action}, so there is nothing left to say.</li>
     *   <li><b>A Create contraption's hosts are not offered at all.</b> Its local coordinates are 0..15
     *       around its anchor, which are not level positions, so there is no coordinate of the
     *       structure's own to wake - see {@link #put}, where the same rule decides the index.</li>
     * </ul>
     *
     * <p>Deliberately allocation-free in the ordinary case: the caller supplies the actions, and the
     * two tables are walked in place. The work is bounded by the number of hosts one structure carries,
     * never by the level's own store. The one thing that is allocated - a small set of carriers the
     * turn report has already named - is created only when a structure actually turned.
     *
     * @param onTurn told once per carrier per call about a structure that turned, or {@code null} when
     *     the caller only wants the positions; the positions themselves are reported either way
     * @param structureAction told about each host whose own output changed, or {@code null} when the
     *     caller only wants the world positions
     */
    public void forEachChange(ResourceKey<Level> dimension, Consumer<BlockPos> action,
                              @Nullable Consumer<ProjectedHost> structureAction,
                              @Nullable Consumer<TurnedHost> onTurn) {
        Table now = tables.get(dimension);
        Table before = previous.get(dimension);

        if (before != null) {
            for (ProjectedHost old : before.byWorldPos().values()) {
                boolean stillThere = now != null && now.bridged()
                        && now.byWorldPos().containsKey(old.worldPos().asLong());
                if (!stillThere) {
                    action.accept(old.worldPos());
                }
            }
        }
        if (now == null || !now.bridged()) {
            return;
        }
        // Allocated only when a structure actually turned; a parked machine must keep costing nothing.
        Set<CarrierId> reported = null;
        for (ProjectedHost host : now.byWorldPos().values()) {
            ProjectedHost old = before == null ? null : before.byWorldPos().get(host.worldPos().asLong());
            boolean turned = old != null && turnedSince(old.pose(), host.pose());
            if (old == null || old.power() != livePowerOf(host) || !old.localPos().equals(host.localPos())
                    || !old.carrier().equals(host.carrier()) || turned) {
                action.accept(host.worldPos());
            }
            // The carrier's kind is part of the condition, not only of `put`: a Create contraption's
            // local coordinates are 0..15 around its anchor and are NOT level positions, so offering
            // them here would wake whatever happens to sit near the world origin. Only a Sable
            // sub-level's coordinates are level coordinates - see `put`, which is where the frame
            // decision is written down.
            if (structureAction != null && host.carrier().isSubLevel()
                    && !host.localPos().equals(host.worldPos())
                    && structureOutputChanged(before, host)) {
                structureAction.accept(host);
            }
            if (!turned) {
                continue;
            }
            turnRepublish++;
            if (onTurn == null) {
                continue;
            }
            if (reported == null) {
                reported = new HashSet<>();
            }
            if (reported.add(host.carrier())) {
                onTurn.accept(new TurnedHost(host.carrier(), host.localPos(), host.worldPos(),
                        old.pose(), host.pose()));
            }
        }
    }

    /**
     * The world-positions-only form, which is what every caller that is not the notification pass
     * uses; see the four-argument method for the whole of the contract.
     */
    public void forEachChange(ResourceKey<Level> dimension, Consumer<BlockPos> action) {
        forEachChange(dimension, action, null, null);
    }

    /** The form the world half has always called: the positions plus the turn report. */
    public void forEachChange(ResourceKey<Level> dimension, Consumer<BlockPos> action,
                              @Nullable Consumer<TurnedHost> onTurn) {
        forEachChange(dimension, action, null, onTurn);
    }

    /**
     * Whether a host's <b>own output</b> changed since the previous pass.
     *
     * <p>Asked of the structure-frame history, so a machine that moved without changing anything stays
     * silent. {@code previous == null} means "this coordinate was not in the last pass at all", and the
     * honest answer there is "tell it if the component holds anything": a host with a live output that
     * has just appeared (a structure picked up with a lever already thrown, or the first pass after a
     * level load) should wake whatever is beside it once, and a host holding nothing has nothing to
     * say. That is the same shape the world half uses, one table over.
     */
    private static boolean structureOutputChanged(@Nullable Table before, ProjectedHost host) {
        if (before == null || !before.bridged()) {
            return livePowerOf(host) != 0;
        }
        ProjectedHost previousLocal = before.byStructurePos().get(host.localPos().asLong());
        if (previousLocal == null) {
            return livePowerOf(host) != 0;
        }
        return !previousLocal.carrier().equals(host.carrier())
                || previousLocal.power() != livePowerOf(host);
    }

    /**
     * Whether the projection has turned since the previous pass.
     *
     * <p>Only the orientation is consulted, and that is exactly right rather than an approximation: the
     * world position of a host is the map key this comparison is already keyed on, and the <em>only</em>
     * other thing the world reads from a projected host is which world direction its own sides point
     * at - which is {@link CarrierPose#orientation()} and nothing else. A pose whose position or scale
     * moved by less than a block therefore stays quiet, which is what keeps a machine nudging back and
     * forth inside one block from notifying the world every tick.
     */
    private static boolean turnedSince(@Nullable CarrierPose before, @Nullable CarrierPose now) {
        if (before == null || now == null) {
            return false;
        }
        return !before.sameOrientation(now);
    }

    /** The power the projected component holds right now, or 0 when it has been taken away. */
    private static int livePowerOf(ProjectedHost host) {
        return host.slot() == null ? 0 : host.slot().power;
    }

    // ------------------------------------------------------------ diagnostics --

    /** Records that the world asked a projected host for its signal; used by the acceptance report. */
    public void noteQuery(@Nullable ProjectedHost host) {
        if (host == null) {
            return;
        }
        queries++;
        lastQueryPos = host.worldPos();
        lastQueryPower = host.power();
    }

    /** How many times the world has asked a projected host for a signal since the process started. */
    public long queries() {
        return queries;
    }

    /** How many of those happened during the last tick. */
    public int queriesThisTick() {
        return queriesThisTick;
    }

    /**
     * Records that something <b>inside</b> the structure asked a host for its signal at the structure's
     * own coordinate; see {@link #atStructure}. Counted always, printed by {@link #describe}.
     */
    public void noteStructureQuery(@Nullable ProjectedHost host) {
        if (host == null) {
            return;
        }
        structureQueries++;
        readMode = ReadMode.STRUCTURE_FRAME;
    }

    /**
     * Records that a structure-frame query was <b>declined</b> because the coordinate belongs to another
     * live sub-level, so the world projection source answers it instead (P1.37).
     *
     * <p>Counted even when the world source then has nothing too: the interesting question is "was this
     * coordinate refused here", and the answer to it is what separates the two machines in the log.
     */
    public void noteStructureFrameDeclined(@Nullable ProjectedHost host) {
        structureDeclines++;
        if (host != null) {
            lastDeclinedCarrier = host.carrier();
        }
        readMode = ReadMode.CROSS_STRUCTURE_DECLINED;
        // P1.38: and the same fact in the counters the tick log prints, so a log with no /rc command in
        // it can still tell "nothing was declined" from "the command was never typed".
        SignalReadTrace.declined();
    }

    /**
     * Which frame the last structure-frame question was answered in: the marker that lets the author
     * read "the machine fed itself" and "the two machines talk across the seam" apart at a glance.
     */
    public ReadMode readMode() {
        return readMode;
    }

    /** How many structure-frame questions were declined as another structure's coordinate (P1.37). */
    public long structureDeclines() {
        return structureDeclines;
    }

    /** The structure whose coordinate the last declined question really was, or {@code null}. */
    @Nullable
    public CarrierId lastDeclinedCarrier() {
        return lastDeclinedCarrier;
    }

    /**
     * How many times the structure's own frame has been answered since the process started.
     *
     * <p>P1.36's read half, and the number that separates "the piston was woken and read the host block
     * beside it" from "the piston was woken and read zero". Paired with the notification line, which is
     * the other half of the same question.
     */
    public long structureQueries() {
        return structureQueries;
    }

    /** One line for {@code /rc carrier}: how many hosts are projected and whether the world reads them. */
    public String describe(ResourceKey<Level> dimension) {
        Table table = tables.get(dimension);
        if (table == null) {
            return "projected=0 (no pass has run in this dimension yet)";
        }
        if (!table.bridged()) {
            return "projected=0 (bridge closed: carrierFreezeWorldIo=true, neither direction is open)";
        }
        return "projected=" + table.byWorldPos().size() + " host(s)"
                + " worldQueries=" + queries + " (thisTick=" + queriesThisTick + ")"
                + " structureFrames=" + table.byStructurePos().size()
                + " structureQueries=" + structureQueries + " via=structure-frame"
                + " structureDeclines=" + structureDeclines + " via=world-projection"
                + " lastRead=" + readMode.name().toLowerCase(java.util.Locale.ROOT)
                + " turnRepublish=" + turnRepublish
                + (lastQueryPos == null ? ""
                        : " last=" + lastQueryPos.toShortString() + " out=" + lastQueryPower);
    }

    // ------------------------------------------------------------------ state --

    /** Drops one dimension's tables; called when a level unloads. */
    public void clear(ResourceKey<Level> dimension) {
        tables.remove(dimension);
        previous.remove(dimension);
    }

    /** Drops everything. */
    public void clearAll() {
        tables.clear();
        previous.clear();
    }
}

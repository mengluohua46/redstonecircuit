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
 * The projection table: where every host a moving structure is carrying currently stands in the world.
 *
 * <p>Built once per tick because the query it answers - what the block at this world position emits towards
 * this direction - comes from the hottest path in the game and usually names air; one forward transform per
 * ridden host beats a pose inversion per machine per query. Read-only: a projected host is a claim, no block
 * is placed or modified. It names no level, so {@code CarrierStore} drives it once per tick, and telling the
 * world that a projection moved is that caller's business.
 */
public final class CarrierProjection {

    /**
     * One carried host, virtually standing at a world position. {@code slot} stays live (the solve mutates
     * slots in place) while {@code power} is the value from build time - the "before" side of the change
     * comparison, since a live-versus-live one would always see a change and never report one.
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
     * The one rule answering "what does the host projected here emit towards this world direction", shared by
     * the vanilla signal query and the world-side inner solve so the two readers can never disagree; a null
     * host answers 0, like a machine that is not there. {@code worldDirection} is vanilla's
     * from-the-asker-towards-the-block direction, so the emitted direction is its opposite.
     *
     * <p>{@code neighbourHoldsComponent} (consulted for the strong question only) is what stops a carried host
     * acting as a redstone block for everything around it.
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
     * The same rule for a query that already arrives in the structure's own frame: converting the direction a
     * second time would rotate the answer by the machine's orientation and make a repeater fire sideways. A
     * null host answers 0, and {@code queryDirection} is vanilla's from-the-asker-towards-the-host direction,
     * in the structure's frame.
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
     * One structure that turned since the previous pass, reported once per carrier per tick: a rotation about
     * the pivot can put every host back on the world position it already occupied, so position and value alone
     * would report "nothing changed" while every emitted direction rotated; {@code worldPos} is the position
     * that has to be announced, and {@code before}/{@code after} are the two passes' poses.
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
     * How many signal queries were answered from the structure's own frame since the process started; see
     * {@link #atStructure}. {@link #queries} is the world-side counterpart: a machine feeding the world.
     */
    private long structureQueries;

    /**
     * How many structure-frame queries were declined because the coordinate belongs to another live
     * sub-level; see {@link StructureRead#OTHER_STRUCTURE} and {@link #atStructureOwnedBy}. Paired with
     * {@link #structureQueries}: "the machine read itself" versus "the machine read its neighbour".
     */
    private long structureDeclines;

    /** The last read that was classified, so a diagnostic can name the frame with one word. */
    private ReadMode readMode = ReadMode.WORLD_PROJECTION;

    /** The structure the last declined question really belonged to, so the line can name it. */
    @Nullable
    private CarrierId lastDeclinedCarrier;

    /**
     * How many host positions a turn has forced a re-publication of since the process started; it separates
     * "the world was told because the machine moved" from "the world was told because it turned".
     */
    private long turnRepublish;

    // ------------------------------------------------------------ arithmetic --

    /**
     * The world position a block at local coordinate {@code localPos} occupies, for {@code pose}: the same
     * arithmetic the landing position of a released host uses, so "where would this host be put down" and
     * "where is this host" cannot drift apart.
     */
    public static BlockPos project(CarrierPose pose, BlockPos localPos) {
        Vector3d centre = pose.blockCentre(
                new Vector3d(localPos.getX(), localPos.getY(), localPos.getZ()), new Vector3d());
        return BlockPos.containing(new WorldPoint(centre));
    }

    /** A plain 3D point, seen as the {@link Position} {@link BlockPos#containing} takes. */
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
     * Starts a fresh table for one dimension, keeping the one it replaces for the change comparison; called
     * once per level tick, so the table always describes the tick it was built in. {@code bridged} is the
     * {@code carrierFreezeWorldIo} switch - when false the table exists but answers nothing.
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
     * Adds one projected host; ignored when the dimension's table is closed or absent.
     *
     * <p>Two keys: the world position, for queries that name world coordinates, and the structure coordinate,
     * for a query made from inside the structure - a carried piston asking the host block beside it, and the
     * notification that wakes it. Only sub-level carriers are indexed under the second key, and that is a rule
     * rather than an optimisation: a Create contraption's local coordinates are 0..15 around its anchor and are
     * not level positions, so answering there would talk about whatever blocks sit near the world origin. A
     * Sable sub-level's plot coordinates are level positions, and {@code CarrierSource#plotOwnerAt} is the
     * authority on that.
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
     * The host projected at a world position, or {@code null}; one map lookup with the bridge's boolean in
     * front of it. Uncounted, because the diagnostics count the world's queries - see {@link #noteQuery}.
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
     * Which structure a query at a plot coordinate is addressed to. A Sable sub-level's plot coordinates are
     * level coordinates, so structure B's own coordinate can be numerically the same number as structure A's
     * world position - one integer, two questions - and the second table's key alone cannot say which is
     * meant. The owner is decided by {@code CarrierSource#plotOwnerAt}, and the answer is one of the three
     * cases below.
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
     * The marker the diagnostics print so "the machine fed itself" is distinguishable from "the world read
     * the machine" at a glance - see {@link #readMode()}.
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
     * Whether a query at a plot coordinate is addressed to the found host's own structure; a static function
     * of two values, so the rule is reachable offline instead of only through a live sub-level container. A
     * null {@code found} means the structure index holds nothing at that coordinate.
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
     * Whether a projected host may be read as an external supply by {@code reader}'s own carrier.
     *
     * <p>The projected read charges no hop: right for a host another structure carries - the only route by
     * which two independently physicalised blocks can read each other, since the level sees air where a
     * carried block stands - and wrong for one of the reader's own hosts, whose value already arrives through
     * the inner-neighbour relationship that does charge the hop; combining both with {@code max} would keep
     * the unattenuated one, and a run of carried dust would stop fading. A null reader means no filtering.
     */
    public static boolean readableBy(@Nullable ProjectedHost found, @Nullable CarrierId reader) {
        return found != null && (reader == null || !reader.equals(found.carrier()));
    }

    /**
     * The host whose structure's own coordinate is this level position, or {@code null}: the read a vanilla
     * device the machine carries makes about the host block beside it, which the world-keyed table cannot
     * answer - that coordinate is a plot coordinate. One map lookup, like the world read.
     *
     * <p>Read-only, and it answers only for carriers whose local coordinates are level coordinates; see
     * {@link #put}. The unguarded index lookup: right for a caller that knows whose coordinate it asks about,
     * wrong for a signal query, which goes through {@link #atStructureOwnedBy(ResourceKey, BlockPos, CarrierId)}.
     */
    @Nullable
    public ProjectedHost atStructure(ResourceKey<Level> dimension, BlockPos structurePos) {
        return atStructureOwnedBy(dimension, structurePos, null);
    }

    /**
     * The same lookup, guarded by whose coordinate this is - the shape every signal query asks.
     *
     * <p>{@code owner} is the {@code CarrierSource#plotOwnerAt} answer, already resolved by the caller, so the
     * decision stays a comparison of two values: a coordinate belonging to another live sub-level is refused
     * and left to the world projection source - which keeps two independently physicalised blocks talking -
     * while the machine's own blocks keep reading their own host in their own frame. A null owner keeps the
     * unguarded behaviour, as the diagnostics and the notification pass want.
     */
    @Nullable
    public ProjectedHost atStructureOwnedBy(ResourceKey<Level> dimension, BlockPos structurePos,
                                            @Nullable CarrierId owner) {
        Table table = tables.get(dimension);
        if (table == null || !table.bridged() || structurePos == null) {
            return null;
        }
        // The usual case is an empty second table, so the hot path gains one emptiness check.
        if (table.byStructurePos().isEmpty()) {
            return null;
        }
        ProjectedHost found = table.byStructurePos().get(structurePos.asLong());
        if (found == null) {
            return null;
        }
        // A null owner is the unguarded form: the caller already knows whose coordinate this is.
        return owner == null || withinStructureFrame(found, owner) ? found : null;
    }

    /** How many hosts are indexed by their structure coordinate in one dimension right now. */
    public int structureSize(ResourceKey<Level> dimension) {
        Table table = tables.get(dimension);
        return table == null ? 0 : table.byStructurePos().size();
    }

    /**
     * The one dimension a table is currently holding anything for, or {@code null} when there is none or more
     * than one: it exists for the unit tests, which have no level to name, and will not pick between two.
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
     * Visits every position whose projection differs from the previous pass - appeared, vanished, moved, changed
     * value or turned - plus every host whose own output changed at its structure coordinate, because a lamp or
     * a piston only re-reads when it is notified and a machine's blocks are an entity, not block changes.
     *
     * <p>The value is compared live against the build-time snapshot (slots mutate in place), and the pose through
     * {@link CarrierPose#sameOrientation}, since a turn can leave every world position untouched while every
     * emitted direction rotates. Vanished hosts and Create contraptions - whose local coordinates are not level
     * positions - are never announced at structure coordinates.
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
            // Only a Sable sub-level's coordinates are level positions; a Create contraption's would name
            // whatever happens to sit near the world origin.
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

    /** The world-positions-only form, used by every caller but the notification pass. */
    public void forEachChange(ResourceKey<Level> dimension, Consumer<BlockPos> action) {
        forEachChange(dimension, action, null, null);
    }

    /** The form the world half has always called: the positions plus the turn report. */
    public void forEachChange(ResourceKey<Level> dimension, Consumer<BlockPos> action,
                              @Nullable Consumer<TurnedHost> onTurn) {
        forEachChange(dimension, action, null, onTurn);
    }

    /**
     * Whether a host's own output changed since the previous pass, asked of the structure-frame history so a
     * machine that moved without changing anything stays silent. A coordinate absent from the last pass counts
     * as changed whenever the component holds anything, so a live output that has just appeared wakes its
     * neighbours once.
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
     * Whether the projection has turned since the previous pass; only the orientation is consulted, which is
     * exact rather than approximate - the world position is the key this comparison is already keyed on, and
     * the only other thing the world reads from a projected host is which world direction its sides point at
     * ({@link CarrierPose#orientation()}). A pose that only nudges inside one block therefore stays quiet.
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

    /** Records an inside-the-structure query at the structure's own coordinate; see {@link #atStructure}. */
    public void noteStructureQuery(@Nullable ProjectedHost host) {
        if (host == null) {
            return;
        }
        structureQueries++;
        readMode = ReadMode.STRUCTURE_FRAME;
    }

    /**
     * Records that a structure-frame query was declined because the coordinate belongs to another live
     * sub-level, so the world projection source answers it instead. Counted even when that source then has
     * nothing either: the question is whether the coordinate was refused here.
     */
    public void noteStructureFrameDeclined(@Nullable ProjectedHost host) {
        structureDeclines++;
        if (host != null) {
            lastDeclinedCarrier = host.carrier();
        }
        readMode = ReadMode.CROSS_STRUCTURE_DECLINED;
        // The same fact in the counters the tick log prints, so a log without the /rc command still tells
        // "nothing was declined" from "nothing was asked".
        SignalReadTrace.declined();
    }

    /**
     * Which frame the last structure-frame question was answered in; it tells "the machine fed itself" from
     * "the two machines talk across the seam" at a glance.
     */
    public ReadMode readMode() {
        return readMode;
    }

    /** How many structure-frame questions were declined as another structure's coordinate. */
    public long structureDeclines() {
        return structureDeclines;
    }

    /** The structure whose coordinate the last declined question really was, or {@code null}. */
    @Nullable
    public CarrierId lastDeclinedCarrier() {
        return lastDeclinedCarrier;
    }

    /**
     * How many times the structure's own frame has been answered since the process started; it tells "the
     * piston was woken and read the host block beside it" from "the piston was woken and read zero".
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

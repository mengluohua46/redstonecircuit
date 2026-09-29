package com.jiangyuefengyu.redstonecircuit.carrier;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.jiangyuefengyu.redstonecircuit.RCConfig;
import com.jiangyuefengyu.redstonecircuit.HostRules;
import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneNode;
import com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneStore;
import com.jiangyuefengyu.redstonecircuit.data.Slot;
import com.jiangyuefengyu.redstonecircuit.logic.BlockSignalSources;
import com.jiangyuefengyu.redstonecircuit.logic.InnerRedstoneNetwork;
import com.jiangyuefengyu.redstonecircuit.logic.SignalReadTrace;
import com.jiangyuefengyu.redstonecircuit.logic.SolverNotifications;
import com.jiangyuefengyu.redstonecircuit.logic.WrenchSelection;
import com.jiangyuefengyu.redstonecircuit.network.HostSync;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

/**
 * The one place a host is actually moved into a structure and put back out of it.
 *
 * <h2>Where this sits</h2>
 * <pre>
 *   Create's MovementBehaviour  \                         /  CarrierStore   ->  CarriedHostIndex
 *   Sable's SubLevelObserver     >--- MovementHostHook --&lt;    |                     (pure data)
 *   the orphan sweep            /                         \   +-> CarrierNetworks -> CarrierSpace
 *   the level tick              -----------------------------/                       (P2 solve)
 *                                                           \  +-> CarrierProjection  (P4 bridge)
 *                                                                -&gt; HostSync     ->  the client
 * </pre>
 *
 * <p>The hook knows about Create and Sable; {@link CarriedHostIndex} knows about nothing at all; this
 * class is the seam between them. Everything that has to happen for one move - take the snapshot,
 * index it, remove the world entry, decide a landing position, write it back, and tell the client -
 * happens here and nowhere else, so the two hooks and the sweep cannot drift apart.
 *
 * <h2>P4: the same tick also tells the world where the machine is</h2>
 * Once per tick the carriers that are holding something are projected into the world
 * ({@link CarrierProjection}), so the world's own signal queries can read a carried component at the
 * coordinate it currently occupies, and every coordinate whose projection moved <b>or turned</b> is
 * announced to the neighbours around it. Both halves are read-only and both are closed by
 * {@code carrierFreezeWorldIo}.
 *
 * <h2>This is the only class that knows what a pose means</h2>
 * {@link CarrierSource} answers three questions: "where is this structure now", "which structure, if
 * any, is holding this world position", and (P2) "let me read that structure's own blocks". All three
 * are asked of the compat half, which is the only place that names a Sable or Create type. With
 * neither mod installed the source simply answers {@code null} for everything, and none of this ever
 * runs: {@link #isActive()} is false, and the hooks that would call in were never registered.
 *
 * <h2>The move, in order</h2>
 * <ol>
 *   <li><b>Pick up.</b> The hook reports a block. The source works out which structure it is in. The
 *       index snapshots the component and removes it from the world store. The client is told the
 *       block is now riding that carrier at that local coordinate.</li>
 *   <li><b>Ride.</b> The structure moves. Once per tick the pose is compared with the last one that
 *       was sent, and a changed pose goes to the client. No host data is re-sent. <b>P2 adds a second
 *       job here</b>: {@link CarrierNetworks} recomputes the redstone inside every structure that is
 *       holding something, in the structure's own local coordinates, and re-sends the ones whose
 *       values changed.</li>
 *   <li><b>Put down.</b> The hook fires again - four or five times, with identical arguments. The
 *       index answers {@code ALREADY_RELEASED} for every call after the first and touches nothing.
 *       The position comes from the structure's own transform, with the block's original position as
 *       the fallback whenever something else is already there.</li>
 * </ol>
 */
public final class CarrierStore {

    /** How much of a structure's own API this layer needs; implemented in the compat source set. */
    public interface CarrierSource {

        /** Where a structure is now, or {@code null} when it is gone. */
        @Nullable
        CarrierPose poseOf(ServerLevel level, CarrierId carrier);

        /**
         * Which structure, if any, is holding the block that used to stand at {@code worldPos}.
         *
         * <p>Asked with a position rather than with an entity because that is what the assembly
         * callback hands us. Answering "none" is the common case and must be cheap.
         */
        @Nullable
        CarrierId carrierAt(ServerLevel level, BlockPos worldPos);

        /** A short description of the source, for the diagnostics. */
        default String describe() {
            return "none";
        }

        /**
         * The world-space centre of a structure's bounds, or {@code null} when it cannot be read.
         *
         * <p>The one thing {@link CarrierDrawCheck} needs that the pose cannot supply: the pose's
         * origin is a pivot, and a pivot can be a long way from the geometry it turns. A source that
         * cannot answer - the P1 diagnostics, a structure that is gone - returns {@code null}, and the
         * draw check then says "cannot compare" rather than inventing a verdict.
         */
        @Nullable
        default org.joml.Vector3d centreOf(ServerLevel level, CarrierId carrier) {
            return null;
        }

        /**
         * How to read a structure's own blocks, in its own local coordinates.
         *
         * <p>The one thing P2 needs from Sable and Create that P1 did not: the in-structure solve has
         * to answer "what is at this local coordinate of the machine", and only the structure itself
         * can say. Answered per carrier and only for a structure that is actually holding something,
         * which is a handful of calls per tick.
         *
         * <p>A source that cannot read structures - the P1 diagnostics, a build without the compat
         * half - simply does not override this, and the solve still runs: the index is what it reads,
         * and this is only used for the one {@code isRedstoneConductor} query nothing in the solve
         * loop makes.
         */
        @Nullable
        default CarrierAccess access() {
            return null;
        }

        /**
         * The structure whose <b>own block storage</b> contains this level position, or {@code null}.
         *
         * <h2>Why this is not {@link #carrierAt}</h2>
         * The two ask about two different coordinate frames and must not be confused:
         *
         * <ul>
         *   <li>{@code carrierAt(level, worldPos)} is asked about a <b>world</b> position - "which
         *       structure's world bounds cover the block that used to stand here";</li>
         *   <li>this is asked about a position in the level's own coordinates that is a structure's
         *       <b>local</b> coordinate - for a Sable sub-level the plot region is a reserved piece of
         *       the level, so its blocks really do live at their local coordinates, and breaking one
         *       arrives as an ordinary break at that position (see {@code CarrierStructureAccess}
         *       for the verification).</li>
         * </ul>
         *
         * <p>Only Sable can answer it, and only for a block inside a plot: a Create contraption keeps
         * its blocks in a map, so a break inside one never reaches the level's block grid at all.
         * Answering {@code null} is the ordinary answer.
         *
         * <p><b>It is also the ownership question, not only the break question</b> (P1.30): the world's
         * click handler asks it - through {@link #ownedHostAtPlot} - to decide whether a coordinate
         * belongs to a structure <em>even when the index holds no record there</em>, which is the state a
         * sub-level loaded from a save is in. So it must stay what it is: a containment test against the
         * sub-level's own plot region in the level's own coordinates, one comparison per live sub-level,
         * and never a test against the structure's <em>world</em> bounds (which move, and which cover
         * ordinary ground a machine is flying over).
         */
        @Nullable
        default CarrierId plotOwnerAt(ServerLevel level, BlockPos levelPos) {
            return null;
        }
    }

    /**
     * The clock and the dimension a solve pass runs against.
     *
     * <p>Two values rather than a {@code ServerLevel}, because those are the only two things the
     * solve loop actually needs the level for: scheduling a repeater's delay, and keeping "already
     * solved this tick" apart per dimension. Splitting them out is what lets the whole driver be
     * unit-tested with a counter instead of a running game.
     *
     * @param gameTime {@code Level#getGameTime()}
     * @param dimension {@code Level#dimension()}
     */
    public record Clock(long gameTime, ResourceKey<Level> dimension) {

        /** The clock of a live level. */
        public static Clock of(ServerLevel level) {
            return new Clock(level.getGameTime(), level.dimension());
        }
    }

    /**
     * What to do once a solve has changed something: mark the level's data dirty and re-send the
     * structure to the clients.
     *
     * <p>An interface rather than a direct call because the solve loop must be unit-testable without a
     * running game, and because "what happens after a write-back" is the one part of it that is about
     * a level at all. The production implementation is {@link CarrierStore#afterSolveWrote}.
     */
    @FunctionalInterface
    public interface WriteBack {

        /** Called once per structure per pass in which at least one value changed. */
        void wrote(CarrierId carrier);
    }

    /**
     * Everything one solve pass needs, resolved once so the solve itself never asks a level a
     * question.
     *
     * <p>Handed to {@link CarrierNetworks} rather than a {@code ServerLevel} because that half must
     * stay testable without a game: a unit test builds this record out of a fake index, a fake store,
     * a counter and a no-op write-back.
     *
     * <p>{@code level} is P1.41's addition, and it is deliberately <b>not</b> the level the solve asks
     * its questions through - that half still goes through the fakes above. It exists for the one read
     * that cannot be expressed as an interface: the carrier-side world read has to name the carrier doing
     * the asking (so the shared read can refuse that carrier's own projection - see
     * {@code CarrierWorldIo#signalIntoOwnFrame}), and the merged read takes the {@code ServerLevel} the
     * projection table is indexed by. Production always has one ({@link #contextOf}); a fixture passes
     * {@code null} through the six-argument form below, and the bridge then degrades to the unfiltered
     * read, which is the behaviour every reader had before this round.
     */
    public record TickContext(Clock clock, CarriedHostIndex index, CarriedHostIndex.SlotWorld slots,
                              @Nullable CarrierAccess access, WriteBack writeBack,
                              @Nullable CarrierWorldBridge bridge, @Nullable ServerLevel level) {

        /** The form every caller before P1.41 used: no level, so no owner-aware world read. */
        public TickContext(Clock clock, CarriedHostIndex index, CarriedHostIndex.SlotWorld slots,
                           @Nullable CarrierAccess access, WriteBack writeBack,
                           @Nullable CarrierWorldBridge bridge) {
            this(clock, index, slots, access, writeBack, bridge, null);
        }
    }

    /**
     * Whether anything can possibly be carrying a host.
     *
     * <p>False unless the compat half installed itself, so the placement paths and the world-store
     * reads cost one static boolean while Sable and Create are absent. This is the P0 promise in its
     * most concrete form: a jar built with the compat source set behaves exactly like one built
     * without it until a mod that needs it is installed.
     */
    private static volatile boolean active;

    @Nullable
    private static CarrierSource source;

    /** Poses already sent to clients, per carrier, so a parked structure sends nothing. */
    private static final Map<UUID, CarrierPose> SENT_POSES = new HashMap<>();

    /**
     * The level tick at which each carrier's pose last went onto the wire.
     *
     * <p>Exists because "the pose is stale" and "the pose has not changed" look identical in every
     * counter this mod had. The author's report - <em>the bearing turns and the component does not turn
     * with it</em> - arrived with a log full of {@code drawCheck … delta=0.000 OK}, which was true: the
     * arithmetic was self-consistent about a pose the client had been holding for four seconds. The age
     * of the last pose actually sent is the number that tells those two apart, and it is printed on the
     * pose line and on the draw check.
     */
    private static final Map<UUID, Long> SENT_POSE_TICKS = new HashMap<>();

    /**
     * Which carriers a client has been told about, so that removing one can always take it back.
     *
     * <p>The bookkeeping behind the "single release exit" rule: the index is only ever emptied here, and
     * every path that empties part of it goes through {@link #forget}, which asks this whether the
     * client knows the carrier and announces the removal if it does. See
     * {@link CarrierRemovalNotices} for why the notice is owed on every path rather than once.
     */
    private static final CarrierRemovalNotices REMOVALS = new CarrierRemovalNotices();

    /**
     * The compat half's answer to "which contraption entity now owns this provisional identity".
     *
     * <p>Set by {@code CarrierResolver} at install time and {@code null} without it. {@link #tick} is
     * what drives phase two of the two-phase identity: an assembly that attached its entity after
     * {@code startMoving} is settled here, on the next tick, without waiting for anything else to
     * happen. The interface exists so the carrier layer - which knows nothing about Create - can ask
     * the question without naming a Create type.
     */
    @FunctionalInterface
    public interface ProvisionalClaims {

        /**
         * The entity UUID a provisional claim should be re-labelled onto, or {@code null} while the
         * structure still has no entity.
         */
        @Nullable
        UUID entityFor(CarrierId provisional);
    }

    @Nullable
    private static volatile ProvisionalClaims provisionalClaims;

    /** Called by the compat half alongside {@link #install(CarrierSource)}. */
    public static void installProvisionalClaims(@Nullable ProvisionalClaims finder) {
        provisionalClaims = finder;
    }

    /**
     * The compat half's answer to "is the structure this carrier names still alive right now".
     *
     * <p>Set by {@code CarrierResolver} at install time and {@code null} without it - in which case the
     * sweep falls back to the pose query alone, which is the pre-existing behaviour.
     *
     * <h2>Why a second question, when "can you give me a pose" already exists</h2>
     * The orphan sweep's rule was <em>"a structure counts as gone only when the source cannot even name
     * a pose for it"</em>. That rule is one-sided: it assumes the source is always able to answer for a
     * structure that is alive. The author's log disproved it - the Create half answered {@code null} for
     * a carrier whose UUID belonged to a <b>Sable sub-level</b>, so the sweep declared a live structure
     * gone, released its hosts, and wrote their components back into the world while the sub-level was
     * still flying. Twice a second, for as long as the structure existed.
     *
     * <p>So "the pose query failed" is no longer sufficient evidence of death. A carrier that is a
     * Sable sub-level and is <em>still live</em> is not an orphan, whatever the pose query says, and
     * this is the question that decides it.
     */
    @FunctionalInterface
    public interface SubLevelLiveness {

        /** Whether the sub-level this carrier names still exists and has not been removed. */
        boolean subLevelAlive(CarrierId carrier);
    }

    @Nullable
    private static volatile SubLevelLiveness subLevelLiveness;

    /** Called by the compat half alongside {@link #install(CarrierSource)}. */
    public static void installSubLevelLiveness(@Nullable SubLevelLiveness liveness) {
        subLevelLiveness = liveness;
    }

    /**
     * Whether the structure a carrier names is positively known to still be alive.
     *
     * <p>{@code false} whenever nobody can answer - no compat half, or a carrier that is not a Sable
     * sub-level - so a caller that uses this to <em>refuse</em> a release keeps the old behaviour in
     * every case it cannot decide.
     */
    public static boolean structureIsAlive(CarrierId carrier) {
        if (carrier == null || !carrier.isSubLevel()) {
            return false;
        }
        SubLevelLiveness liveness = subLevelLiveness;
        if (liveness == null) {
            return false;
        }
        try {
            return liveness.subLevelAlive(carrier);
        } catch (RuntimeException | LinkageError failed) {
            // "I could not tell" is not "it is alive": a broken liveness query must never be the reason
            // a component is stranded inside a structure nobody can name any more.
            return false;
        }
    }

    /** The pose last sent for a carrier, or {@code null}; for the diagnostics and the tests. */
    @Nullable
    public static CarrierPose lastSentPose(CarrierId carrier) {
        return carrier == null ? null : SENT_POSES.get(carrier.uuid());
    }

    /**
     * How many level ticks ago a carrier's pose last went onto the wire, or {@code -1} when none has.
     *
     * <p>The server's own half of {@code drawCheck … poseAge=}. A client that is drawing an old angle
     * and a client that is drawing a parked machine are indistinguishable from the rendered frame, and
     * this is the number on the server that separates them.
     */
    public static long sentPoseAgeTicks(CarrierId carrier, long now) {
        if (carrier == null) {
            return -1L;
        }
        Long sent = SENT_POSE_TICKS.get(carrier.uuid());
        return sent == null ? -1L : Math.max(0L, now - sent);
    }

    /**
     * P4: where every carried host currently stands in the world.
     *
     * <p>Rebuilt once per level tick from the carriers that are actually holding something - a handful
     * of structures, each walked over its own hosts - and read by the world's own signal queries
     * through {@link #projectedHostAt}. Nothing in the level-wide store is ever walked on its behalf;
     * see {@link CarrierProjection}.
     */
    private static final CarrierProjection PROJECTION = new CarrierProjection();

    /**
     * The projection table itself; the unit tests' seam.
     *
     * <p>The table is static and shared (it is one level's worth of "where is everything"), so a test
     * that wants to exercise {@link #hitAt} has to be able to fill it and empty it. Package-visible
     * rather than public: production reaches it through {@link #projectedHostAt} and
     * {@link #isProjectedAt}, which are the two questions the world actually asks.
     */
    static CarrierProjection projection() {
        return PROJECTION;
    }

    /**
     * P1.20: where the self-healing sweep has got to in each carrier's host list.
     *
     * <p>A cursor per carrier rather than a queue: nothing is allocated per tick, nothing can grow, and a
     * machine with more hosts than one tick's budget is finished over the following ticks instead of
     * being restarted or skipped. Pruned to the carriers still in the index on every pass and dropped
     * with the index itself, so it cannot outlive a level.
     */
    private static final Map<UUID, Integer> SELF_HEAL = new HashMap<>();

    /** How often the orphan sweep runs, in level ticks. Cheap, but not worth doing every tick. */
    private static final int SWEEP_INTERVAL = 40;

    private CarrierStore() {
    }

    // ---------------------------------------------------------------- wiring --

    /** Called by the compat half once, when it has a source to offer. */
    public static void install(CarrierSource carrierSource) {
        source = carrierSource;
        active = carrierSource != null;
        if (active) {
            RCConfig.LOGGER.info("[redstonecircuit] carrier store armed: source={}",
                    carrierSource.describe());
        }
    }

    /** True when some structure could be carrying something right now. */
    public static boolean isActive() {
        return active && RCConfig.carrierEnabled();
    }

    /**
     * True when the bridge between a carried host and the world is closed.
     *
     * <p>Off by default since P4, which means the bridge is open in both directions; see
     * {@link RCConfig#carrierFreezeWorldIo()}. Nothing that <em>freezes a world position</em> depends
     * on it any more - a carried host is excluded from the world solve unconditionally, because its
     * origin is not where it is either way.
     */
    public static boolean freezesWorldIo() {
        return RCConfig.carrierFreezeWorldIo();
    }

    @Nullable
    public static CarrierSource source() {
        return source;
    }

    // ------------------------------------------------------------- querying --

    /**
     * What is inside the block at {@code worldPos}, including data that is riding a structure.
     *
     * <p>The union of the world store and the carried index. Callers that need to know
     * <em>where</em> the data is ask {@link #isCarried}.
     */
    @Nullable
    public static Slot slotAt(ServerLevel level, BlockPos worldPos) {
        CarriedHostIndex index = indexOf(level);
        if (index == null || index.isEmpty()) {
            return null;
        }
        return index.slotAt(worldPos, CarriedHosts.slots(level));
    }

    /**
     * True when the block at {@code worldPos} has been taken away by a structure.
     *
     * <p>This is the "do not solve it here" predicate: a position whose data is riding something is
     * not where the component is, so the world around it is not the world the component lives in. Since
     * P4 the component is not homeless either - it is solved at the position the machine has carried it
     * to (see {@link #projectedHostAt}) - but it is definitely not solved here.
     */
    public static boolean isCarried(ServerLevel level, BlockPos worldPos) {
        CarriedHostIndex index = indexOf(level);
        return index != null && !index.isEmpty() && index.isCarried(worldPos);
    }

    @Nullable
    public static CarriedHostIndex indexOf(ServerLevel level) {
        CarriedHosts holder = CarriedHosts.get(level);
        return holder == null ? null : holder.index();
    }

    // -------------------------------------------------- P4: the world-facing half --

    /**
     * The carried host virtually standing at {@code worldPos}, or {@code null}.
     *
     * <p>This is what makes a component inside a machine visible to the world it is flying over. The
     * world's own signal query ({@code BlockStateSignalMixin}) asks here when the world store has
     * nothing at that position - which is the normal case, because the block the world sees is air:
     * Create and Sable keep a moving structure's blocks in an entity or a plot, not in the world.
     *
     * <p>A read-only claim: nothing is placed, replaced or modified. The answer is a signal strength,
     * and the one thing a caller may do with it is report it.
     *
     * <p>Closed by {@code carrierFreezeWorldIo}, and empty until the first tick has projected
     * anything, so a jar with the compat half but no moving structure answers in one boolean. A
     * {@code null} answer is also what a failure looks like, deliberately: a projection that cannot be
     * read has to behave exactly like a machine that is not there.
     */
    @Nullable
    public static CarrierProjection.ProjectedHost projectedHostAt(ServerLevel level, BlockPos worldPos) {
        if (!active) {
            return null;
        }
        CarrierProjection.ProjectedHost host = PROJECTION.at(level.dimension(), worldPos);
        // P1.42: ONE ACCESSOR, AND THE VANILLA HALF OF THE SAME READ GOES THROUGH IT. This lookup is
        // what `BlockStateSignalMixin` answers a vanilla `getSignal` with, and the carrier half's world
        // read is made through a `ServerLevel` - so the "vanilla" half of that read is answered from
        // this very table. P1.41 refused the asking carrier's own host in the explicit projected half
        // only (`InnerRedstoneNetwork#projectedSignalAt`), so the same host came back through this one
        // and `max(vanilla, projected)` kept the uncharged copy: a run of carried dust never faded and
        // every cell sat at fifteen. The refusal below is read-scoped and exact - one carrier, one
        // position - and is a no-op for every query made outside a carrier-side read, which is every
        // world-side read there is. See `InnerRedstoneNetwork#refusingOwnProjectionAt`.
        return InnerRedstoneNetwork.projectionMayAnswer(host, worldPos) ? host : null;
    }

    /**
     * Records that the world's signal path asked about a projected host.
     *
     * <p>Called only from the mixin - i.e. only by a real vanilla signal query - so the count
     * {@code /rc carrier} prints is evidence rather than an artefact of the diagnostics reading the
     * same table.
     */
    public static void noteWorldQuery(CarrierProjection.ProjectedHost host) {
        PROJECTION.noteQuery(host);
    }

    /**
     * Whether something is projected at {@code worldPos}, without counting a query.
     *
     * <p>Asked while deciding whether a strong signal would be conducted onwards: the block that asks
     * counts as "holds a component" whether it is a host block in the world or a carried one.
     */
    public static boolean isProjectedAt(ServerLevel level, BlockPos worldPos) {
        return active && PROJECTION.at(level.dimension(), worldPos) != null;
    }

    /**
     * <b>The host a structure's own coordinate holds, or {@code null} - P1.36's read half.</b>
     *
     * <p>Asked by {@code BlockStateSignalMixin} when a signal query arrives at a position the world
     * store knows nothing about. For a host the mod is carrying there are two such positions: the world
     * position it is projected onto (answered by {@link #projectedHostAt}) and its coordinate
     * <em>inside</em> the structure. The second one is a real level position for a Sable sub-level
     * (plot coordinate == level coordinate, P1.30 section 30.1.2), and it is where a vanilla device the
     * machine carries - the author's piston - asks "what does the host block beside me emit".
     *
     * <p>One hash lookup in a table built once per pass, plus <b>one ownership question</b> (P1.37): the
     * second table is keyed by a plot coordinate, and a plot coordinate is a level coordinate, so the
     * number a query names can be another structure's world position - that is exactly the case when a
     * player places two blocks and physicalises each on its own. The ownership answer is asked only when
     * the table actually holds something there, and only that host's carrier is compared with it: a
     * coordinate that belongs to a different live sub-level is refused and left to
     * {@link #projectedHostAt}, which is byte-for-byte the P1.35 answer. No pose arithmetic (the query is
     * already in the structure's own frame), no block read, and no world entry is ever created.
     *
     * <p>An owner of {@code null} - no live structure's own storage holds the coordinate - keeps the
     * P1.36 answer rather than closing the frame: {@code plotOwnerAt} answers {@code null} for a
     * structure whose plot cannot be read, and losing the machine's own piston because a containment
     * test came back empty would be a worse failure than the collision this guard exists for.
     *
     * <p>Read-only: this never writes anything at the structure's coordinate, and no world entry is
     * ever created for it.
     */
    @Nullable
    public static CarrierProjection.ProjectedHost structureHostAt(ServerLevel level, BlockPos structurePos) {
        return frameHostAt(level, structurePos, true);
    }

    /**
     * <b>Whether the last structure-frame question in this solve pass was refused</b> because the
     * coordinate belongs to another live sub-level (P1.37's guard).
     *
     * <p>Set by {@link #structureHostAt} and read by {@code BlockSignalMixin}'s caller to label the
     * P1.38 judgement line; cleared on the next structure-frame question. A thread-local is not enough
     * - the question and the answer are in the same call - and a static boolean is what the mixin
     * already reads in the same order, so this is one field with one writer. Reset by {@link #clear}.
     */
    private static boolean structureFrameDeclined;

    /** See {@link #structureFrameDeclined}; true when the last structure-frame question was refused. */
    public static boolean lastStructureFrameDeclined() {
        return structureFrameDeclined;
    }

    /**
     * The one body behind {@link #structureHostAt}, with the diagnostics stateable.
     *
     * <p>{@code diagnose} is false for the two callers that only want the value -
     * {@code CarrierProjection#forEachChange}'s structure half, which has already decided what to
     * notify, and the tests - so a read recorded for a log line can never make a notification look
     * like it was asked for. The counters this method feeds are P1.38's; the guard itself is P1.37's
     * and is unchanged.
     */
    @Nullable
    private static CarrierProjection.ProjectedHost frameHostAt(ServerLevel level, BlockPos structurePos,
                                                              boolean diagnose) {
        if (!active || level == null || structurePos == null) {
            return null;
        }
        CarrierProjection.ProjectedHost indexed = PROJECTION.atStructure(level.dimension(), structurePos);
        if (indexed == null) {
            // Nothing is indexed at this coordinate at all, so this was never a structure-frame
            // question: no read happened, and nothing was declined.
            structureFrameDeclined = false;
            return null;
        }
        CarrierProjection.ProjectedHost host = PROJECTION.atStructureOwnedBy(level.dimension(),
                structurePos, plotOwnerOf(level, structurePos));
        if (host == null) {
            PROJECTION.noteStructureFrameDeclined(indexed);
            structureFrameDeclined = true;
            if (diagnose) {
                // P1.38: this coordinate belongs to another live sub-level, so the structure frame
                // refused it and the world projection source answers instead. Named here as well as in
                // the mixin because a refused read can end with no answer at all, and the mixin's own
                // recording path is only reached when a source answered.
                SignalReadTrace.record(null, structurePos, true,
                        "none-indexed (the asker is in the structure frame)",
                        "declined (another sub-level owns this coordinate)",
                        BlockSignalSources.Source.NO_SIGNAL, 0);
            }
            return null;
        }
        structureFrameDeclined = false;
        PROJECTION.noteStructureQuery(host);
        return host;
    }

    /**
     * Which structure's <b>own block storage</b> contains this level position - the P1.30 authority a
     * structure-frame read is guarded by (P1.37).
     *
     * <p>One question, asked only when the structure index actually holds a host at that coordinate.
     * Deliberately the same {@code plotOwnerAt} the click layer has used since P1.26, kept local to the
     * read so the hot path allocates nothing and no state is cached.
     *
     * <p><b>A failure answers {@code null}, which is the pre-P1.37 behaviour rather than a closed
     * frame.</b> The compat half's {@code plotOwnerAt} already catches its own container failures, and
     * this second guard is for the one thing that cannot be reasoned about from here: a carrier source
     * that throws. Losing the machine's own device because an ownership query failed would be a worse
     * failure than the collision the guard exists for - see {@code atStructureOwnedBy}, which documents
     * the same choice.
     */
    @Nullable
    private static CarrierId plotOwnerOf(ServerLevel level, BlockPos structurePos) {
        CarrierSource carrierSource = source;
        if (carrierSource == null) {
            return null;
        }
        try {
            return carrierSource.plotOwnerAt(level, structurePos);
        } catch (RuntimeException | LinkageError unreadable) {
            return null;
        }
    }

    /**
     * Whether a structure's own coordinate holds a host, without counting a query.
     *
     * <p>The structure-frame twin of {@link #isProjectedAt}: asked while deciding whether a strong
     * signal would be conducted onwards, where the block that asks counts as "holds a component"
     * whether it is a host block in the world, a carried one at its world position, or a carried one at
     * the structure's own coordinate.
     *
     * <p>Ownership-guarded exactly as {@link #structureHostAt} is (P1.37), and asked for the same
     * reason: another structure's world position is not a structure coordinate of ours, and treating it
     * as one would hand out a strong-power answer the world rule never gave it.
     */
    public static boolean isStructureHostAt(ServerLevel level, BlockPos structurePos) {
        return frameHostAt(level, structurePos, false) != null;
    }

    /**
     * <b>One carrier-sourced signal read, recorded for the P1.38 judgement line and counters.</b>
     *
     * <p>Called by {@code BlockStateSignalMixin} for the two sources that are not the world store. The
     * interesting question is not "was a value read" but "<b>which structure asked, and about whose
     * coordinate</b>", and only the carrier layer can answer that: the mixin knows the position and the
     * value, this class knows which structure's plot holds a coordinate and which structure is
     * projected where.
     *
     * <p>The asker is worked out from the read itself, in the order that costs least and can be right:
     *
     * <ul>
     *   <li>a <b>structure-frame</b> read is by definition asked by a device of the structure whose own
     *       block storage holds that coordinate - the authority is {@code plotOwnerAt}, the same one
     *       P1.37's guard uses;</li>
     *   <li>a <b>world-projection</b> read is asked by the component whose world position is the host's
     *       neighbour, one step back along the query direction. When that component is itself inside a
     *       structure it lives at a plot coordinate, so the same ownership question names the asker; a
     *       world component answers {@code null} and the line says so.</li>
     * </ul>
     *
     * <p><b>Cost, and when it runs.</b> Only for the two carrier sources - never for the ordinary world
     * store - and only while {@code debugLog} is on: {@link SignalReadTrace#record} returns after one
     * boolean test with the switch off, and the asker lookups below are performed only when it says the
     * read was actually recorded. Each lookup is {@code plotOwnerAt}, the containment question the click
     * layer has asked on every click since P1.26: <b>bounded by the number of live sub-levels</b>, no
     * pose arithmetic, no container walk beyond the list Sable keeps. The whole line is written at most
     * once per level tick.
     *
     * <p>Read-only: nothing is written, no state is cached, and no level is held.
     *
     * @param level the level the query was made against - used only for the ownership question, never
     *     held or cached
     * @param read what the rule chose, from {@code BlockSignalSources#select}
     * @param askedPos the position the query named - the argument vanilla's {@code getSignal} carried
     * @param direction the direction that argument carried, i.e. from the block asking towards
     *     {@code askedPos}; it is what names the asker's own position
     * @param answer the value the query was finally answered with, 0-15. That is {@code read.answer()}
     *     except when the asking block is a vanilla redstone wire, which charges its own hop for a
     *     wire-type component (P1.44, {@code BlockSignalSources#hopChargedByReader}) - the line reports
     *     what the world received rather than what the host emitted, because that is the number the
     *     author can measure in game
     */
    public static void noteSignalRead(@Nullable ServerLevel level, BlockSignalSources.Read read,
                                      @Nullable BlockPos askedPos, @Nullable Direction direction,
                                      int answer) {
        if (level == null || askedPos == null || direction == null || !SignalReadTrace.enabled()) {
            return;
        }
        boolean structureFrame = read.source() == BlockSignalSources.Source.STRUCTURE_FRAME;
        CarrierSource carrierSource = source;
        CarrierId asker = null;
        if (carrierSource != null) {
            // Which position the asker occupies depends on the frame the answer came from:
            //  * the structure frame: the query was made by a device of the structure whose own block
            //    storage holds the coordinate asked about, so that coordinate's owner IS the asker;
            //  * the world projection: the query was made by the component one step back along
            //    `direction` from the host's world position, which is a plot coordinate only when that
            //    component is itself inside a structure. A world component answers null, and the line
            //    then says "the world" - which is the honest answer for a lamp or a piston on the
            //    ground reading a machine that flew past.
            BlockPos askerPos = structureFrame ? askedPos : askedPos.relative(direction.getOpposite());
            try {
                asker = carrierSource.plotOwnerAt(level, askerPos);
            } catch (RuntimeException | LinkageError unreadable) {
                // A diagnostic must never be the thing that fails a signal query: "I could not tell who
                // asked" is a blank field in a log line, not an exception in the hot path.
                asker = null;
            }
        }
        String projection = read.projectionHit() && read.host() != null
                ? "hit (local=" + read.host().localPos().toShortString() + " carrier="
                        + read.host().carrier().shortId() + ")"
                : "none (nothing is projected at this world position)";
        String frame = structureFrame
                ? "answered (own coordinate)"
                : read.structureFrameDeclined()
                        ? "declined (another sub-level owns this coordinate)"
                        : "none (not a structure's own coordinate)";
        SignalReadTrace.record(asker, askedPos, !structureFrame, projection, frame, read.source(),
                answer);
    }

    /** One line about the P4 bridge, for {@code /rc carrier}. */
    public static String describeBridge(ServerLevel level) {
        boolean frozen = RCConfig.carrierFreezeWorldIo();
        return "carrierBridge=" + (frozen ? "frozen" : "on")
                + " (carrierFreezeWorldIo=" + frozen + ")  " + PROJECTION.describe(level.dimension());
    }

    // ---------------------------------------------- P1.19: a carried block is broken --

    /**
     * <b>Which host a player's click at {@code pos} is about, or {@code null}.</b>
     *
     * <p>Read-only: nothing is dropped and nothing is changed. This is the <b>carrier-only resolver</b>
     * the whole carrier-side click layer goes through - the destroy path below and the wrench/rotation
     * rod entry point both ask it and nothing else - so "is this position one of a structure's own
     * blocks, and which one" has exactly one implementation.
     *
     * <h2>Two frames, checked in a fixed order, and never confused</h2>
     * A click inside a structure can reach us in one of two coordinate frames. They are genuinely
     * different, and this asks both rather than assuming which:
     *
     * <ol>
     *   <li><b>The world frame (preferred).</b> The block is drawn where the structure currently is,
     *       which is what P4's projection table is keyed by: {@code CarrierProjection.project(pose,
     *       local)} for every live host, once a tick. One hash lookup, and the answer carries the
     *       carrier, the local coordinate <em>and</em> the pose - which is precisely because the table
     *       was built from them. This is positive identification, and it is why a break event does not
     *       have to ask the structure's own view to confirm it (see
     *       {@link CarriedBlockRemoval.ViewRequirement#PROJECTION_IS_PROOF}).</li>
     *   <li><b>The structure's own frame (fallback).</b> A Sable sub-level's blocks are the level's
     *       blocks at the plot's own coordinates, so a break reported at one of them arrives as an
     *       ordinary position. The compat half answers "which sub-level's block storage contains this
     *       position", and this additionally proves the answer through the structure's <b>pose</b>:
     *       the local coordinate is recovered by the inverse transform and accepted only when the
     *       forward transform puts it back within a block of the click. A containment test over plot
     *       bounds cannot do that job, because the plot's bounds are expressed in the plot's own frame
     *       and not in the level's.</li>
     * </ol>
     *
     * <p>The work is bounded by two O(1) lookups plus one compat scan that is bounded by the number of
     * sub-levels: no loop over the index, no walk over a plot.
     */
    @Nullable
    public static CarriedBlockRemoval.Hit hitAt(ServerLevel level, BlockPos pos) {
        return hitAt(level, pos, null);
    }

    /**
     * The same resolver, with a place to write down <b>why</b> it missed.
     *
     * <p>{@code CarrierEntryTrace.Miss} is written to and never read from here: every decision below is
     * byte-for-byte the one the form above makes, and the collector only records the steps taken and the
     * three-state verdict on the record scan. It exists because "no hit" has three completely different
     * causes - no record at all, a record that is not live, and a resolver that never got as far as the
     * index - and the author's own 101-miss log could not tell them apart. Allocated by the caller only
     * while {@code debugLog} is on.
     */
    @Nullable
    public static CarriedBlockRemoval.Hit hitAt(ServerLevel level, BlockPos pos,
                                                @Nullable CarrierEntryTrace.Miss miss) {
        CarriedHostIndex index = level == null ? null : indexOf(level);
        // The pose source is what the local-coordinate branch needs to name a WORLD position for a host
        // it has identified by its own key. It is the only thing that branch needs a level for, and it
        // is deliberately a function rather than the level itself so the branch is unit-testable.
        PoseSource poses = level == null ? null : carrier -> poseOf(level, carrier);
        return hitAt(level, PROJECTION, index, pos, miss, poses);
    }

    /**
     * Where a carrier's pose comes from.
     *
     * <p>The single thing the local-coordinate branch of the resolver needs from the level, extracted so
     * that the branch - the half of the resolution that had the defect - can be driven by an offline unit
     * test with a plain map of poses.
     */
    @FunctionalInterface
    public interface PoseSource {

        /** The carrier's current pose, or {@code null} when the structure cannot be read. */
        @Nullable
        CarrierPose poseOf(CarrierId carrier);
    }

    /**
     * The same resolver, with the index and the dimension supplied.
     *
     * <p>Package-visible and level-free so the one decision that matters here - "which host is this
     * click about" - is pinned by an offline unit test that can build an index without a running game.
     * Production reaches it through the level form above, which is the same code with the level's own
     * index and dimension looked up first. A {@code null} level is the unit-test form: the projection
     * table and the index are the only two things this decision is allowed to consult.
     *
     * <p>This is the shape the resolver had <b>before</b> P1.24 - no local-coordinate branch - and it is
     * kept because it is still what the level-free tests drive. {@code poses} is what turns the branch on.
     */
    @Nullable
    static CarriedBlockRemoval.Hit hitAt(@Nullable ServerLevel level, @Nullable CarrierProjection table,
                                         @Nullable CarriedHostIndex index, BlockPos pos) {
        return hitAt(level, table, index, pos, null, null);
    }

    /**
     * <b>Whether this level position is a coordinate a structure owns - including a host that holds
     * nothing.</b>
     *
     * <h2>Why this is not {@link #hitAt}</h2>
     * {@code hitAt} answers "which host does this <em>click</em> resolve to", and every one of its callers
     * then acts on the component inside the host: it opens a wrench line, selects, drops. A host the
     * structure carries that was <b>emptied</b> by the player holds no component, so the resolver
     * consistently refuses it - which is right for those callers and wrong for
     * {@code CarrierClickPriority}, whose question is ownership rather than contents. The author's case is
     * exactly the empty one: physicalise a block that holds nothing and then try to put a component into
     * it. The broken result was that the world's own click handler wrote its store at the plot coordinate;
     * the fix is that this position is <em>owned</em>, so the world must stand down whether or not there
     * is anything inside the block yet.
     *
     * <p>The two frames are the same two {@code hitAt} asks, in the same order, and each answer is a
     * positive identification:
     *
     * <ol>
     *   <li>the <b>projection table</b> names a live record at this position - one hash lookup;</li>
     *   <li>otherwise the <b>structure's own frame</b>: {@code plotOwnerAt} names a sub-level whose own
     *       block storage contains this level position, and the index holds a record for that sub-level
     *       at exactly this coordinate. That coordinate <em>is</em> the block (see
     *       {@link CarrierSource#plotOwnerAt}), so no pose round trip is needed to identify it, and the
     *       record may be empty - or already put down.</li>
     * </ol>
     *
     * <p><b>Narrow by construction.</b> Only a sub-level can be named by the second branch - a Create
     * contraption's blocks are not level blocks at all - and the first branch refuses a landed record, so
     * an ordinary world block and a carried host's <em>origin</em> both answer {@code null} and keep their
     * previous behaviour. Read-only: nothing is moved, released, written or dropped, and no state is
     * created.
     *
     * <p>Bounded by one hash lookup plus {@code scanLocalCoordinate}'s own ceiling
     * ({@link #MAX_CARRIERS_SCANNED}) and one pass over the container's sub-levels, which is what
     * {@link CarrierSource#plotOwnerAt} already costs on the break path.
     */
    @Nullable
    public static CarriedBlockRemoval.Hit ownedHostAt(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null || !isActive()) {
            return null;
        }
        CarriedHostIndex index = indexOf(level);
        if (index == null || index.isEmpty()) {
            return null;
        }
        // Frame one: the projection table, which is keyed by WORLD position and knows the pose outright.
        CarriedBlockRemoval.Hit projected = ownedHostByProjection(PROJECTION, index, pos,
                level.dimension());
        if (projected != null) {
            return projected;
        }
        // Frame two: the structure's own storage. The compat source is what can name a sub-level from a
        // position; without it no structure can be named from a coordinate at all.
        CarrierSource carrierSource = source;
        if (carrierSource == null) {
            return null;
        }
        return ownedHostByPlot(carrierSource::plotOwnerAt, level, index, pos, carrier -> poseOf(level, carrier));
    }

    /**
     * <b>Whether this level position is a coordinate a structure owns - including a coordinate the index
     * has no record for.</b>
     *
     * <h2>The defect this exists for</h2>
     * P1.26's frame two ({@link #ownedHostByPlot}) decided ownership by asking the index for <em>a live
     * record keyed by this coordinate</em> after the plot named a sub-level. That extra requirement is the
     * whole of the author's remaining defect, and the log shows it as a pair of facts that look unrelated
     * until they are read together:
     *
     * <pre>
     *   carrier claim = 0                       the index never held a record at the plot coordinate
     *   placed RETAINING_REPEATER at 20481032, 127, 20481032
     *                                           the component went into the WORLD store at that
     *                                           coordinate, which is a plot cell of a live sub-level
     * </pre>
     *
     * <p>A sub-level that was <b>loaded from the save</b> - or assembled with its blocks already at their
     * plot coordinates - has no record, because a record is only ever created by taking a component out of
     * a <em>world</em> position (see {@code SableCarrierClaim}). So the coordinate is a structure's by
     * every physical fact, the index simply has not heard of it, and the world's click handler won the
     * click: Jade read the world store and displayed the component, the overlay read the carrier index and
     * drew nothing, and the world bridge could not see it.
     *
     * <h2>What changed, precisely</h2>
     * The index record is no longer <em>required</em>, and if one does not exist the coordinate is still
     * owned provided two things hold:
     *
     * <ol>
     *   <li>the compat source's {@code plotOwnerAt} names a sub-level whose own block storage contains
     *       this position. That query walks the live sub-levels of this level and refuses a removed one,
     *       so "named" already means "alive";</li>
     *   <li>{@link #structureIsAlive} agrees - or a record exists after all. It is asked as well rather
     *       than assumed, because a source that answers from a cached plot would otherwise be able to hand
     *       back a structure that is gone, and "I could not tell" must never be read as "it is mine".</li>
     * </ol>
     *
     * <p><b>A record that has been put down still answers here.</b> Ownership is "whose click is this",
     * and a coordinate the structure is standing on is the structure's whether or not the block inside it
     * still holds anything - so a put-down host must keep being declined by the world side, exactly as it
     * was before this round. What callers must <b>not</b> do is mistake it for a record that is missing:
     * see {@code CarrierClickPriority.Probe}, where a landed record is {@code HOST}, and the migration,
     * which must never revive a host the player has already put down. Getting that wrong is how this round
     * would create a second truth about one block - the defect it exists to remove.
     *
     * <p><b>The widening is exactly one coordinate class, and it does not touch the world.</b> A Create
     * contraption's blocks are not level blocks, so no contraption can be named by {@code plotOwnerAt} at
     * all; an ordinary world block and a carried host's <em>origin</em> (a world coordinate, not a plot
     * cell) are both still answered {@code null} and keep their previous behaviour byte for byte.
     * Read-only: nothing is moved, released, written or dropped.
     *
     * <p>Bounded by one hash lookup, one pass over the level's sub-levels (what {@code plotOwnerAt} already
     * costs on the break path) and, only when no record exists, one sub-level liveness lookup - all on a
     * player click, never on a tick, a solve or a render frame.
     *
     * @param alive asked only when the index holds no record; {@code null} means "cannot be established",
     *     which answers "not ours" rather than guessing
     */
    @Nullable
    public static CarriedBlockRemoval.Hit ownedHostAtPlot(ServerLevel level, BlockPos pos,
                                                          @Nullable java.util.function.Predicate<CarrierId> alive) {
        if (level == null || pos == null || !isActive()) {
            return null;
        }
        CarrierSource carrierSource = source;
        if (carrierSource == null) {
            return null;
        }
        CarriedHostIndex index = indexOf(level);
        java.util.function.Predicate<CarrierId> liveness =
                alive == null ? carrier -> false : carrier -> {
                    try {
                        return alive.test(carrier);
                    } catch (RuntimeException | LinkageError failed) {
                        // "I could not tell" is never "it is mine": an exception here keeps the world's
                        // ownership of an ordinary coordinate.
                        return false;
                    }
                };
        return ownedHostAtPlot(carrierSource::plotOwnerAt, level, index, pos,
                carrier -> poseOf(level, carrier), liveness);
    }

    /**
     * <b>Migrates one world-store entry that stands at a structure's own plot coordinate into the carrier
     * index.</b> The repair half of P1.30 (commit B); the prevention half is
     * {@link CarrierClickPriority#owner}.
     *
     * <h2>The defect this repairs, in one sentence</h2>
     * A component written into the world store at a plot coordinate has two truths about one block - Jade
     * reads the world store and displays it, the overlay reads the index and draws nothing, the world
     * bridge cannot see it, and the break path drops it - because the coordinate is simultaneously a level
     * position and a block inside a machine. The author's log is that state ({@code placed
     * RETAINING_REPEATER at 20481032, 127, 20481032} with {@code carrier claim = 0} for the session).
     *
     * <h2>The migration is one {@code claim}, and that is deliberate</h2>
     * {@link CarriedHostIndex#claim} is the same method an ordinary pick-up goes through, and it does
     * exactly the three things this needs: snapshot the world entry, remove it from the world store, and
     * file the record under {@code (carrier, localPos)}. Writing a second "move a component" path here
     * would be the way the two truths come back.
     *
     * <h2>The {@code origin} rule, stated because it is an interpretation</h2>
     * A carrier record's {@code origin} means <em>"the world position the component was taken from"</em>.
     * For a component that was never taken from anywhere - it is already inside the structure's own
     * storage, and the "position it was placed at" is the plot coordinate itself - there is no such
     * position to recover, and this method does not invent one. It records:
     *
     * <pre>
     *   origin = the world position the structure's own pose puts this coordinate at right now
     * </pre>
     *
     * <p>because that is the only position that is <em>true</em> at the moment of adoption, and because it
     * is what every reader of {@code origin} actually wants:
     *
     * <ul>
     *   <li>the release path uses {@code origin} only as the <b>fallback</b> when the structure's pose is
     *       gone at release time - and if the pose is gone, "where the block was when we adopted it" is
     *       the best available answer;</li>
     *   <li>the break-drop path never uses {@code origin} at all: it recomputes the drop position from the
     *       live pose (see {@code dropDestroyedHost}), precisely because the origin is where something
     *       <em>was</em>;</li>
     *   <li>the alternative interpretation - "the plot coordinate, because that is what the world store
     *       said" - would put the fallback two million blocks from anywhere a player can go, which is
     *       strictly worse and is the value the author's log can already read in the store itself.</li>
     * </ul>
     *
     * <p>So a save that is repaired by adoption releases its components where the machine actually is,
     * exactly like a save whose components were claimed normally.
     *
     * <h2>Idempotent, and reported from one place</h2>
     * {@code claim} is idempotent ({@code alreadyCarried}), and the world entry's removal is the second
     * half of the guarantee: after one adoption the world store holds nothing at that coordinate, so it
     * cannot be a candidate again. The judgement line is written here, when and only when a record was
     * really filed, so the next log can prove the migration happened.
     *
     * @param localPos the plot coordinate: the world entry's key <b>and</b> the record's local coordinate
     * @param origin where the component came from, as described above; must not be {@code null}
     * @return what the world store held and what the index made of it, or {@code null} when the layer is
     *     not armed or was asked with nothing to adopt
     */
    @Nullable
    public static WorldComponentAdoption.Plan adoptWorldComponent(ServerLevel level, CarrierId carrier,
                                                                  BlockPos localPos, BlockPos origin,
                                                                  String why) {
        if (level == null || carrier == null || localPos == null || origin == null || !isActive()) {
            return null;
        }
        CarriedHostIndex index = indexOf(level);
        CarriedHostIndex.SlotWorld slots = CarriedHosts.slots(level);
        Slot worldSlot = slots.get(localPos);
        WorldComponentAdoption.Plan plan = WorldComponentAdoption.plan(index, carrier, localPos, origin,
                worldSlot);
        if (!plan.adopts()) {
            return plan;
        }
        // `fill`, not `claim`: the contents are read from the plot coordinate (the world store's own key)
        // while the record is filed with the origin the pose named - two different numbers, which is
        // exactly why that method exists beside `claim`. Reading the contents at the origin is the bug a
        // unit test caught here: the world store holds nothing at the position the machine is flying over.
        CarriedHostIndex.ClaimOutcome outcome = index.fill(carrier, plan.localPos(), plan.origin(),
                plan.localPos(), slots);
        if (!outcome.claimed() || !outcome.moved()) {
            // The index answered "already carried" between the plan and the claim, or the world store
            // emptied underneath us. Either way nobody moved anything, and nothing may be reported as
            // adopted.
            return new WorldComponentAdoption.Plan(WorldComponentAdoption.Decision.ALREADY_CARRIED,
                    carrier, plan.localPos(), plan.origin(), outcome.slot());
        }
        CarriedHosts.get(level).setDirty();
        if (RCConfig.debugLog()) {
            RCConfig.LOGGER.info("[redstonecircuit] {} (reason: {}; the world entry at that coordinate was "
                            + "removed, so the world store and the carrier index no longer disagree about "
                            + "this block) {}",
                    plan.describe(), why, index.summary(carrier));
        }
        // The two halves the ordinary claim does for one host, in the same order and for the same reasons:
        // the client is told the component is riding the structure (the overlay's data source), and it is
        // told to stop drawing a world host at the coordinate the claim just emptied.
        suppressWorldHost(level, plan.origin());
        broadcastCarrier(level, carrier, index, poseOf(level, carrier));
        return plan;
    }

    /**
     * The same, for callers that already hold the index and a way to name a sub-level.
     *
     * <p>Package-visible and level-free so the one decision - "is this position a coordinate a structure
     * owns" - is pinned by an offline unit test with a fake index and a fake plot lookup, exactly as
     * {@code hitAt}'s level-free form is. Production reaches it through the level form above.
     */
    @Nullable
    static CarriedBlockRemoval.Hit ownedHostAt(@Nullable CarrierProjection table,
                                               @Nullable CarriedHostIndex index, BlockPos pos,
                                               @Nullable ResourceKey<Level> dimension,
                                               @Nullable PlotOwnerSource owners,
                                               @Nullable PoseSource poses) {
        if (pos == null || index == null || index.isEmpty() || poses == null) {
            return null;
        }
        CarriedBlockRemoval.Hit projected = ownedHostByProjection(table, index, pos, dimension);
        if (projected != null) {
            return projected;
        }
        if (owners == null) {
            return null;
        }
        return ownedHostByPlot(owners, null, index, pos, poses);
    }

    /**
     * The level-free core of {@link #ownedHostAtPlot} - the same decision, with the index, the plot lookup,
     * the pose source and the liveness question supplied.
     *
     * <p>Package-visible and level-free for the same reason {@link #ownedHostAt} is: the decision only
     * consults the index, one plot lookup and one liveness answer, and all three can be fakes.
     */
    @Nullable
    static CarriedBlockRemoval.Hit ownedHostAtPlot(@Nullable PlotOwnerSource owners,
                                                   @Nullable ServerLevel level,
                                                   @Nullable CarriedHostIndex index, BlockPos pos,
                                                   @Nullable PoseSource poses,
                                                   java.util.function.Predicate<CarrierId> alive) {
        if (pos == null || owners == null || poses == null) {
            return null;
        }
        CarrierId owner;
        try {
            owner = owners.plotOwnerAt(level, pos);
        } catch (RuntimeException | LinkageError failed) {
            // "I could not tell" is never "it is mine": an ordinary world block keeps its own click.
            return null;
        }
        return ownedHostAtPlot(owner, index, pos, poses, alive);
    }

    /**
     * <b>The same decision for a caller that has already asked which structure's own storage contains the
     * coordinate.</b>
     *
     * <p>Split out for {@code CarrierClickPriority}'s ownership answer, which has to know <em>which</em>
     * structure the plot cell is (the answer's {@code owner=host}/{@code owner=sublevel} tag is decided by
     * whether the index holds a record there) and would otherwise ask the compat half's plot walk twice for
     * one click. The question is asked once and the rest of the decision - the record, the liveness answer,
     * the pose - is one implementation, shared with the form above.
     *
     * @param owner the structure whose own block storage contains {@code pos}, or {@code null} when none
     *     does - the same answer {@code PlotOwnerSource#plotOwnerAt} gives, already de-exceptioned
     */
    @Nullable
    static CarriedBlockRemoval.Hit ownedHostAtPlot(@Nullable CarrierId owner,
                                                   @Nullable CarriedHostIndex index, BlockPos pos,
                                                   @Nullable PoseSource poses,
                                                   java.util.function.Predicate<CarrierId> alive) {
        if (owner == null || pos == null || poses == null) {
            return null;
        }
        CarriedHost host = index == null ? null : index.at(owner, pos);
        if (host == null && !alive.test(owner)) {
            // No record, so liveness is the only thing that says "this plot cell is really a structure's
            // right now" rather than "a plot region that no structure is standing on any more".
            return null;
        }
        // A record that has been PUT DOWN still answers here, and that is deliberate rather than
        // overlooked. Ownership answers "whose click is this", and the coordinate is still the
        // structure's: the structure has not gone anywhere, and the world's click at a block the
        // structure is standing on has to be declined for exactly the same reason it always was. The two
        // *contents* questions - "the carrier's resolver can act on the component" and "is there a world
        // entry here that needs adopting" - are answered by the callers:
        // {@code CarrierStore#ownedHostAt} refuses a landed record outright, and
        // {@code CarrierClickPriority#plotCellOwnership} reports a record as a live one only when it is
        // not landed.
        CarrierPose pose = poses.poseOf(owner);
        BlockPos worldPos = pose == null ? null : CarrierProjection.project(pose, pos);
        if (worldPos == null || localForVerified(pose, worldPos) == null) {
            // The same fallback frame two uses: the pose could not name a world position for this
            // coordinate, so the coordinate itself stands in for it. Ownership is decided by the plot
            // cell, not by the arithmetic succeeding.
            worldPos = pos.immutable();
        }
        return new CarriedBlockRemoval.Hit(owner, pos.immutable(), worldPos, pose, true);
    }

    /**
     * Frame one of {@link #ownedHostAt}: the projection table named a live record at this position.
     *
     * <p>One hash lookup, and nothing else: the table is keyed by the world position the pose computed,
     * so an entry is positive identification of both the host and the shape of it.
     */
    @Nullable
    private static CarriedBlockRemoval.Hit ownedHostByProjection(@Nullable CarrierProjection table,
                                                                 CarriedHostIndex index, BlockPos pos,
                                                                 @Nullable ResourceKey<Level> dimension) {
        if (table == null || dimension == null) {
            return null;
        }
        CarrierProjection.ProjectedHost projected = table.at(dimension, pos);
        if (projected == null) {
            return null;
        }
        CarriedHost host = index.at(projected.carrier(), projected.localPos());
        if (host == null || host.landed()) {
            return null;
        }
        return new CarriedBlockRemoval.Hit(projected.carrier(), projected.localPos(),
                projected.worldPos(), projected.pose(), true);
    }

    /**
     * Frame two of {@link #ownedHostAt}: the structure's own block storage contains this position.
     *
     * <p><b>An empty host is accepted here</b> - that is the whole reason this method exists beside
     * {@link #hitAt}, which refuses a host holding nothing because every one of its callers acts on the
     * component inside. The index holding a <em>live</em> record for that sub-level at exactly this
     * coordinate IS the identification (the coordinate is the block: see
     * {@link CarrierSource#plotOwnerAt}), so nothing is guessed; the pose is asked only so the answer can
     * name a world position, and the clicked position is kept when it cannot.
     */
    @Nullable
    private static CarriedBlockRemoval.Hit ownedHostByPlot(PlotOwnerSource owners,
                                                           @Nullable ServerLevel level,
                                                           CarriedHostIndex index, BlockPos pos,
                                                           PoseSource poses) {
        CarrierId owner;
        try {
            owner = owners.plotOwnerAt(level, pos);
        } catch (RuntimeException | LinkageError failed) {
            // "I could not tell" is never "it is mine": an ordinary world block keeps its own click.
            return null;
        }
        if (owner == null) {
            return null;
        }
        CarriedHost host = index.at(owner, pos);
        if (host == null || host.landed()) {
            return null;
        }
        CarrierPose pose = poses.poseOf(owner);
        // The bone the break path fights over, and the answer here is "neither": the structure's own
        // storage names the host, the pose names where that host currently is, and the clicked position is
        // used as the world position only when the pose cannot name one (or cannot put this block back
        // where it was). Ownership is decided by the coordinate being a key of a live record, not by the
        // pose's arithmetic succeeding.
        BlockPos worldPos = pose == null ? null : CarrierProjection.project(pose, pos);
        if (worldPos == null || localForVerified(pose, worldPos) == null) {
            worldPos = pos.immutable();
        }
        return new CarriedBlockRemoval.Hit(owner, pos.immutable(), worldPos, pose, true);
    }

    /**
     * Which structure's own block storage contains a level position.
     *
     * <p>A one-method interface rather than the compat source directly, so the frame-two decision above
     * can be driven by an offline unit test - the same split {@link PoseSource} makes for the poses. The
     * production implementation is {@code CarrierSource#plotOwnerAt}; a {@code null} level is the
     * unit-test form.
     */
    @FunctionalInterface
    public interface PlotOwnerSource {

        /** The structure whose own block storage contains {@code levelPos}, or {@code null}. */
        @Nullable
        CarrierId plotOwnerAt(@Nullable ServerLevel level, BlockPos levelPos);
    }

    @Nullable
    static CarriedBlockRemoval.Hit hitAt(@Nullable ServerLevel level, @Nullable CarrierProjection table,
                                         @Nullable CarriedHostIndex index, BlockPos pos,
                                         @Nullable CarrierEntryTrace.Miss miss,
                                         @Nullable PoseSource poses) {
        if (pos == null || !isActive()) {
            note(miss, "the carrier layer is not armed (or the position is missing), so nothing is "
                    + "resolved at all");
            verdict(miss, CarrierEntryTrace.CARRIER_UNRESOLVED, index, pos);
            return null;
        }
        if (index == null || index.isEmpty() || table == null) {
            note(miss, "index=" + (index == null ? "absent" : index.carrierCount() + " carrier(s)")
                    + " table=" + (table == null ? "absent" : "present")
                    + ": there is nothing in this dimension to resolve against");
            verdict(miss, CarrierEntryTrace.CARRIER_UNRESOLVED, index, pos);
            return null;
        }
        // A level names the dimension; without one the table has to be able to say which dimension it
        // holds (the unit-test form). Two open tables and no level to choose between them is a
        // question with no answer, and this refuses it rather than picking one.
        ResourceKey<Level> dimension = level == null ? table.onlyDimension() : level.dimension();
        if (dimension == null) {
            note(miss, "the projection table does not say which dimension it holds, and no level was "
                    + "given to ask");
            verdict(miss, CarrierEntryTrace.CARRIER_UNRESOLVED, index, pos);
            return null;
        }
        CarrierProjection.ProjectedHost projected = table.at(dimension, pos);
        note(miss, "projection table lookup at " + pos.toShortString() + " -> "
                + (projected == null ? "no entry (the table is keyed by WORLD position)"
                        : projected.carrier().shortId() + " local=" + projected.localPos().toShortString()));
        if (projected != null) {
            CarriedHost host = index.at(projected.carrier(), projected.localPos());
            if (host != null && !host.landed()) {
                return new CarriedBlockRemoval.Hit(projected.carrier(), projected.localPos(),
                        projected.worldPos(), projected.pose(), true);
            }
            note(miss, "the projection named a host the index does not hold live ("
                    + (host == null ? "no record" : "landed") + ")");
        }

        // ---- THE LOCAL-COORDINATE BRANCH (P1.24) -------------------------------------------------
        //
        // THE DEFECT THIS FIXES. A click on a physicalised block arrives in the STRUCTURE'S OWN frame:
        // Sable's plot coordinate *is* the host's local coordinate (CarrierSource#plotOwnerAt says so in
        // as many words), and a Create packet carries `localPos` outright. The resolver used to answer
        // such a click only by inverting the pose - "which local coordinate, projected forward, would
        // land on this level position" - which is the right question for a WORLD position and the wrong
        // one for a coordinate that is already the answer. The author's own log is the proof: 101 clicks,
        // every one MISS, and the raw coordinate of each is exactly a claim's `local=`.
        //
        // So the index is asked with the coordinate itself. Nothing here needs a pose, a projection or a
        // reverse lookup to find the record.
        if (poses != null) {
            CarriedBlockRemoval.Hit local = hitAtLocalCoordinate(scanLocalCoordinate(index, pos), pos,
                    poses, miss);
            if (local != null) {
                return local;
            }
        }

        if (level == null) {
            // The structure-frame fallback needs a level to read the plot with; the unit tests cover
            // the pose round trip that qualifies it (CarrierStore#localForVerified) directly.
            note(miss, "no level was given, so the structure-frame fallbacks were not attempted");
            verdict(miss, CarrierEntryTrace.RECORD_ABSENT, index, pos);
            return null;
        }
        CarrierSource carrierSource = source;
        if (carrierSource == null) {
            note(miss, "the compat source is not installed, so no structure can be named from a "
                    + "coordinate");
            verdict(miss, CarrierEntryTrace.CARRIER_UNRESOLVED, index, pos);
            return null;
        }
        CarrierId owner;
        try {
            owner = carrierSource.plotOwnerAt(level, pos);
        } catch (RuntimeException | LinkageError failed) {
            // The compat half promises not to throw; if it does, the answer is "not ours" - never a
            // guess about a position, and never an exception on the click path.
            note(miss, "plotOwnerAt threw " + failed.getClass().getSimpleName() + ": " + failed.getMessage());
            verdict(miss, CarrierEntryTrace.CARRIER_UNRESOLVED, index, pos);
            RCConfig.LOGGER.warn("[redstonecircuit] plot owner lookup failed at {}: {}",
                    pos.toShortString(), failed.toString());
            return null;
        }
        note(miss, "plotOwnerAt(" + pos.toShortString() + ") -> "
                + (owner == null ? "null (no structure's own block storage contains this position)"
                        : owner.shortId()));
        if (owner == null) {
            verdict(miss, CarrierEntryTrace.CARRIER_UNRESOLVED, index, pos);
            return null;
        }
        // ---- THE PLOT CELL *IS* THE LOCAL COORDINATE (P1.32) --------------------------------------
        //
        // THE DEFECT THIS REPLACES. A click inside a structure arrives in the structure's own frame, so
        // the coordinate the event carries IS the host's local coordinate - which is the whole reason this
        // method asks the index with it above. When the index held no record at that coordinate, this used
        // to fall through to inverting the structure's pose, and for a coordinate that is already a local
        // coordinate that arithmetic produces a DOUBLED answer: the author's own click at
        // `20481032, 127, 20481036` was turned into `local=40962066, 320, 40962079` - a coordinate that
        // cannot exist in the structure the player was looking at, so the lookup could only ever miss and
        // the click could only ever report "record present but NOT live". No pose conversion is needed
        // here and none may be applied: plot coordinates ARE level coordinates (P1.30 verified this, see
        // `CarrierSource#plotOwnerAt`).
        //
        // WHAT IS ANSWERED INSTEAD. `plotOwnerAt` has named a live structure whose own block storage
        // contains this position, so the position belongs to that structure:
        //
        //   * a LIVE record at the coordinate always answers, whether it is the owner's or - in the
        //     degenerate case of two sub-levels sharing a plot cell - another carrier's, because the
        //     coordinate names the host and the index is what has it;
        //   * a landed record is refused, exactly as the scan above refuses it: it is a world host again;
        //   * and with NO record at all this answers `RECORD_MISSING_IN_PLOT` and returns null, WITHOUT
        //     running the pose arithmetic. The caller that can do something about it is
        //     `resolveForPlacement`, which files the missing record; every other caller is told the truth -
        //     "this is a structure's coordinate with nothing filed here" - instead of being handed a
        //     coordinate nothing can be found at.
        CarrierPose pose = poseOf(level, owner);
        CarriedHost host = index.at(owner, pos);
        if (host != null && !host.landed()) {
            note(miss, "the index holds a live record at " + owner.shortId()
                    + " local=" + pos.toShortString() + " (the coordinate IS the record's own key)");
            return new CarriedBlockRemoval.Hit(owner, pos.immutable(), pos.immutable(), pose, false);
        }
        BlockPos world = pose == null ? null : CarrierProjection.project(pose, pos);
        if (world == null || localForVerified(pose, world) != null) {
            // The pose can name a world position for this coordinate and puts it back where the click was:
            // the ordinary case, and what every later read (a sound, a drop, a highlight) wants.
            world = pos.immutable();
        }
        note(miss, "the plot cell " + pos.toShortString() + " belongs to " + owner.shortId()
                + ", and the index holds " + (host == null ? "no record" : "a landed record")
                + " there: the coordinate IS this structure's own local coordinate, so NO pose conversion "
                + "is applied (the previous build inverted the pose here and produced a doubled "
                + "coordinate)");
        verdict(miss, CarrierEntryTrace.RECORD_MISSING_IN_PLOT, index, pos);
        return null;
    }

    /**
     * <b>Resolves the click of a placement or a tool gesture, <em>filing the record a plot cell is
     * missing</em>.</b>
     *
     * <h2>The state this exists for</h2>
     * A block inside a Sable sub-level is a real level block at its own local coordinate, and the carrier
     * index only learns about one when a component is taken out of a <em>world</em> position. So a block
     * that was already inside the structure when it was assembled - the author's case, and the state every
     * freshly physicalised block is in - has no record at all, and {@link #hitAt} answers it with
     * {@code RECORD_MISSING_IN_PLOT}. Refusing is right for the break path, which must not create records;
     * it is useless for the placement path, whose whole purpose is to put a component into that block.
     *
     * <h2>P1.33: only a placement that will really write may create a record, and it is one decision</h2>
     * P1.32 reached this method for the wrench, the rotation rod and a bare hand as well - see the entry
     * point's own call - and filed the record <em>before</em> the placement rule had decided anything.
     * Every one of those clicks then refused, and the coordinate kept an <b>empty carrier host</b> it had
     * never earned: the author's own log is
     * {@code carrier s:c22611c6: filed the carrier host a plot cell was missing local=20481032, 128, 20481032
     * origin=-4, -59, -10 (record=created empty)} on a wrench shift-click, and the very next line is the
     * refusal that wrote nothing. That record is not harmless: it makes the block a structure's host for
     * every later judgement, which is what turned the next shift-click on it into
     * "this carried host is EMPTY and the picked host is not this one".
     *
     * <p>So the caller answers <b>"will the placement store a component here"</b> first, through
     * {@code CarriedInsert#preview} - the same code {@code CarriedInsert#apply} runs a moment later - and
     * hands that answer in as {@code placementWillWrite}. {@code false} means this method writes nothing at
     * all: no record, no world entry read, no item spent. The click falls through as the refusal it always
     * was, and the two halves of one store land together or not at all.
     *
     * <h2>What it does, and only when the plot frame really names the coordinate</h2>
     * The coordinate is the local coordinate (no pose conversion), and the record is filed with
     * {@link CarriedHostIndex#registerAt}:
     *
     * <ul>
     *   <li><b>the origin is the world position the structure's pose puts that coordinate at now</b> - the
     *       same rule and the same function P1.30's adoption records ({@code WorldComponentAdoption#originFor}),
     *       for the same reason: a component that was never taken out of a world block has no "position it
     *       came from" to recover, and the fallback the release path needs is "where it was when we filed
     *       it";</li>
     *   <li><b>a pose that cannot name a position ⇒ no record and no hit</b>, never a guess. A plot
     *       coordinate recorded as the origin would put the release fallback two million blocks from
     *       anywhere a player can go;</li>
     *   <li><b>a world entry at that coordinate is migrated, not duplicated</b>: the registration reads it
     *       through {@code registerAt} and removes it, which is the same one-source-of-truth rule P1.30's
     *       adoption follows. A component the world store still holds at a plot coordinate is the defect
     *       that produced "Jade shows it, the overlay does not", so it is carried over rather than
     *       shadowed;</li>
     *   <li><b>idempotent</b>: an existing LIVE record is returned as it is, with its own snapshot - the
     *       second click of one gesture must not overwrite a component the first one stored, and it must
     *       not empty a host the player has built into.</li>
     * </ul>
     *
     * <p>Read-only on every path that is not "a plot cell with no record". The world store is written only
     * to <em>remove</em> an entry at the plot coordinate, exactly as a claim does.
     *
     * <p>Bounded by one {@link #hitAt} plus one plot lookup and one index lookup - on a player click only.
     *
     * @param miss the same explanation collector {@code hitAt} writes to, or {@code null}
     * @param placementWillWrite whether the placement rule will really store a component in the host this
     *     filing would create - {@code CarriedInsert#wouldInsert} for the click that is running. A
     *     {@code false} files nothing, which is what keeps a wrench, a rotation rod, a bare hand and a
     *     non-sneaking click from leaving an empty host behind
     * @return the host to act on, or {@code null} when this coordinate is not a plot cell of a live
     *     structure (or its pose cannot name a position, or no placement was going to write anything)
     */
    @Nullable
    public static CarriedBlockRemoval.Hit resolveForPlacement(ServerLevel level, BlockPos pos,
                                                              @Nullable CarrierEntryTrace.Miss miss,
                                                              boolean placementWillWrite) {
        CarriedBlockRemoval.Hit known = hitAt(level, pos, miss);
        if (known != null || level == null || pos == null) {
            return known;
        }
        if (!placementWillWrite) {
            // A RECORD IS ONLY EVER FILED FOR A PLACEMENT THAT WILL REALLY WRITE A COMPONENT (P1.33).
            // See the level-free core below for the whole of it; the boolean is the caller's answer to
            // "will the placement rule act on the host this filing would create", computed through
            // {@code CarriedInsert#preview}.
            note(miss, "nothing was filed: this click is not a placement that would store a component in "
                    + "this cell, so no carrier host was created for it");
            return null;
        }
        CarrierSource carrierSource = source;
        if (carrierSource == null) {
            return null;
        }
        CarriedHostIndex index = indexOf(level);
        CarriedHosts holder = CarriedHosts.get(level);
        if (index == null || holder == null) {
            return null;
        }
        CarriedHostIndex.SlotWorld slots = CarriedHosts.slots(level);
        CarriedBlockRemoval.Hit filed = resolveForPlacement(
                (askedLevel, plotPos) -> carrierSource.plotOwnerAt(level, plotPos),
                owner -> poseOf(level, owner),
                index, pos, slots, miss, placementWillWrite);
        if (filed == null) {
            return null;
        }
        holder.setDirty();
        suppressWorldHost(level, filed.worldPos());
        broadcastCarrier(level, filed.carrier(), index, filed.pose());
        return filed;
    }

    /**
     * The same, for the callers P1.32 wrote - the filing decision with no gate on it.
     *
     * <p><b>Nothing in production may call this.</b> It is the P1.32 shape of the decision, which files a
     * record for any click that reaches a plot cell of a live structure, and the whole of P1.33's first
     * defect is that shape being reached by a click that then wrote nothing. It is kept because
     * {@code CarrierPlotCellInsertTest} was written against it and asserts the <em>filing</em> rule
     * (the coordinate is the key, the origin is the pose's projection, a world entry is migrated) rather
     * than the gate; production goes through the form above, which asks
     * {@code CarriedInsert#wouldInsert} first.
     *
     * @param miss the same explanation collector {@code hitAt} writes to, or {@code null}
     * @return the host to act on, or {@code null} when this coordinate is not a plot cell of a live
     *     structure (or its pose cannot name a position)
     */
    @Nullable
    static CarriedBlockRemoval.Hit resolveForPlacement(PlotOwnerSource owners, PoseSource poses,
                                                       @Nullable CarriedHostIndex index, BlockPos pos,
                                                       CarriedHostIndex.SlotWorld slots,
                                                       @Nullable CarrierEntryTrace.Miss miss) {
        return resolveForPlacement(owners, poses, index, pos, slots, miss, true);
    }

    /**
     * The same, as a value, with the index, the plot lookup, the poses and the world store supplied - so
     * the whole of this round's second defect is pinned by an offline unit test that has no level to ask.
     *
     * <p>Package-visible and level-free for the same reason {@link #ownedHostAtPlot}'s core is: the
     * decision consults one plot lookup, one pose, one index lookup and one world read, and all four can be
     * fakes. Production reaches it through the level form above.
     *
     * <p><b>The record is filed through {@link CarriedHostIndex#registerAt}, which is what makes an empty
     * host possible at all</b> - {@code claim}/{@code fill} refuse a coordinate the world store holds
     * nothing at, and an untouched plot cell is exactly that.
     *
     * <h2>{@code placementWillWrite}, and why this is a parameter rather than a caller's promise</h2>
     * P1.32 filed a record for every click that got this far: a placement, and also - through the entry
     * point's own condition - a wrench, a rotation rod and a bare hand. A record was therefore created for
     * clicks that then wrote nothing at all, because the placement rule refuses anything that is not a
     * placing gesture, and the coordinate was left with an <b>empty host</b>: a block that had never
     * carried a component and was now a structure's host. That state changes later judgements (it is what
     * made the author's own shift-click on the block beside it answer "this host is empty and the picked
     * host is not this one" instead of locking the line), so the rule is now: <b>the record and the
     * contents land in one decision, or neither lands.</b> The caller computes this boolean with
     * {@code CarriedInsert#preview} against the very host the filing would create - an empty one - so the
     * answer cannot drift from the write that follows it, and a {@code false} here leaves no state at all
     * (no record, no world entry read, no item spent).
     *
     * @param placementWillWrite whether the placement rule will really store a component in the host this
     *     filing would create; {@code false} files nothing
     */
    @Nullable
    static CarriedBlockRemoval.Hit resolveForPlacement(PlotOwnerSource owners, PoseSource poses,
                                                       @Nullable CarriedHostIndex index, BlockPos pos,
                                                       CarriedHostIndex.SlotWorld slots,
                                                       @Nullable CarrierEntryTrace.Miss miss,
                                                       boolean placementWillWrite) {
        if (owners == null || poses == null || index == null || slots == null || pos == null) {
            return null;
        }
        if (!placementWillWrite) {
            note(miss, "nothing was filed: this click is not a placement that would store a component in "
                    + "this cell");
            return null;
        }
        CarrierId owner;
        try {
            owner = owners.plotOwnerAt(null, pos);
        } catch (RuntimeException | LinkageError failed) {
            // "I could not tell" is never "it is mine": an exception here leaves the click where it was.
            return null;
        }
        if (owner == null) {
            return null;
        }
        CarrierPose pose = poses.poseOf(owner);
        // The origin is the world position this structure's pose puts the coordinate at RIGHT NOW, through
        // the same function and the same arithmetic P1.30's adoption uses - so a record filed here and a
        // record adopted there name the same block, and a pose that cannot be read refuses rather than
        // guessing (see WorldComponentAdoption#originFor for why a guess would be the plot coordinate two
        // million blocks from anywhere a player can go).
        BlockPos origin = WorldComponentAdoption.originFor(pos,
                pose == null ? null : local -> pose.blockCentre(
                        new org.joml.Vector3d(local.getX(), local.getY(), local.getZ()),
                        new org.joml.Vector3d()));
        if (origin == null) {
            note(miss, "the plot cell belongs to " + owner.shortId() + ", but its pose cannot name a world "
                    + "position for it, so no carrier record was filed and nothing is guessed");
            return null;
        }
        Slot worldSlot = slots.get(pos);
        CarriedHost filed = index.registerAt(owner, pos, origin, pos, slots);
        if (filed == null || filed.landed()) {
            return null;
        }
        note(miss, "filed the carrier host this plot cell was missing at " + owner.shortId()
                + " local=" + pos.toShortString() + " origin=" + origin.toShortString()
                + " (the coordinate IS this structure's own local coordinate, so no pose conversion was "
                + "applied)");
        if (RCConfig.debugLog()) {
            RCConfig.LOGGER.info("[redstonecircuit] carrier {}: filed the carrier host a plot cell was "
                            + "missing local={} origin={} (record={}) - the coordinate IS this structure's "
                            + "own local coordinate, so no pose conversion was applied; the world entry at "
                            + "that coordinate, if the world store still held one, was migrated into this "
                            + "record rather than shadowed",
                    owner.shortId(), pos.toShortString(), origin.toShortString(),
                    worldSlot == null ? "created empty" : "migrated " + worldSlot.type);
        }
        return new CarriedBlockRemoval.Hit(owner, pos.immutable(), origin, pose, false);
    }

    /** Records one resolver step, when the caller asked for an explanation. */
    private static void note(@Nullable CarrierEntryTrace.Miss miss, String text) {
        if (miss != null) {
            miss.step(text);
        }
    }

    /** Adds the local-coordinate scan's three-state verdict to an explanation. */
    private static void verdict(@Nullable CarrierEntryTrace.Miss miss, String resolutionState,
                                @Nullable CarriedHostIndex index, @Nullable BlockPos pos) {
        if (miss == null) {
            return;
        }
        if (CarrierEntryTrace.CARRIER_UNRESOLVED.equals(resolutionState)) {
            miss.verdict(CarrierEntryTrace.CARRIER_UNRESOLVED
                    + " (the record scan is reported for completeness, but no structure was named, so "
                    + "nothing would have been looked up in it)");
        } else {
            miss.verdict(resolutionState);
        }
        miss.step("recordsAt(" + CarrierEntryTrace.posText(pos) + "): "
                + CarrierEntryTrace.localVerdict(scanLocalCoordinate(index, pos), pos));
    }

    /** A pose's own description, in the explanation; kept separate so {@code hitAt} stays readable. */
    private static void logPoseRead(@Nullable CarrierEntryTrace.Miss miss, CarrierPose pose) {
        note(miss, "pose=" + pose.describe());
    }

    // ------------------------------------- the local-coordinate scan (P1.24) --

    /**
     * The most carriers one local-coordinate scan will examine.
     *
     * <p>A hard ceiling, and the reason the scan is safe to run on a click: one hash lookup per carrier,
     * so sixty-four of them is a handful of microseconds, and a world that somehow holds more than this
     * many structures at once is answered with "capped" rather than by walking an unbounded list.
     */
    public static final int MAX_CARRIERS_SCANNED = 64;

    /** One record keyed by exactly the coordinate that was asked about. */
    public record LocalRecord(CarrierId carrier, CarriedHost host) {
    }

    /**
     * What one local-coordinate scan found: the records, how many carriers were examined, and whether the
     * ceiling stopped it.
     *
     * <p>Three values rather than a bare list because "no record" and "I stopped looking" must not read
     * the same, and the diagnostics print both.
     */
    public record LocalScan(List<LocalRecord> records, int scanned, boolean capped) {

        /** True when this scan names no record at all. */
        public boolean isEmpty() {
            return records.isEmpty();
        }
    }

    /**
     * <b>Every record in this dimension keyed by exactly this coordinate.</b>
     *
     * <p>This is the question the click resolver should have been asking all along: a click that arrives
     * at a physicalised block carries the block's coordinate <em>inside the structure</em> - Sable's plot
     * coordinate <b>is</b> the local coordinate (see {@code CarrierSource#plotOwnerAt}), and a Create
     * packet carries {@code localPos} outright - so the index can be asked with it directly. Nothing here
     * needs a pose, a projection or a reverse lookup, which is why it cannot be broken by any of them.
     *
     * <p>Bounded by {@link #MAX_CARRIERS_SCANNED} carriers, one hash lookup each, and read-only. It
     * returns every match rather than the first, because two carriers holding a host at one coordinate is
     * a question the caller has to decide (the resolver refuses; the diagnostics print both).
     */
    public static LocalScan scanLocalCoordinate(@Nullable CarriedHostIndex index,
                                                @Nullable BlockPos localPos) {
        if (index == null || localPos == null) {
            return new LocalScan(List.of(), 0, false);
        }
        int carriers = index.carrierCount();
        boolean capped = carriers > MAX_CARRIERS_SCANNED;
        List<LocalRecord> found = new ArrayList<>();
        int scanned = 0;
        for (CarrierId carrier : index.carriers()) {
            if (scanned >= MAX_CARRIERS_SCANNED) {
                break;
            }
            scanned++;
            CarriedHost host = index.at(carrier, localPos);
            if (host != null) {
                found.add(new LocalRecord(carrier, host));
            }
        }
        return new LocalScan(List.copyOf(found), scanned, capped);
    }

    /**
     * <b>The local-coordinate half of the resolver: one live sub-level record keyed by exactly this
     * coordinate, plus a pose that can say where that coordinate is in the world right now.</b>
     *
     * <h2>Why only a sub-level, and why that is not a shortcut</h2>
     * A sub-level's blocks are real level blocks, in Sable's reserved plot region, at their own local
     * coordinates - so a level coordinate that equals one of its records' keys <em>is</em> that block,
     * and the match needs no corroboration. A Create contraption's blocks are not level blocks at all:
     * they live in a map inside the contraption entity, so a level coordinate can never be one of its
     * local coordinates. Restricting the branch to sub-levels is therefore what keeps it from ever
     * mistaking an ordinary world block at, say, {@code (1, 0, 0)} for a contraption host whose local
     * coordinate happens to be {@code (1, 0, 0)} - which would consume a record and drop a component for
     * a break that had nothing to do with any machine. A contraption click reaches the carrier layer
     * through its own packet, which names the entity and is served by {@link #hitInStructure}.
     *
     * <h2>What it will not do</h2>
     * A landed record is a world host and is refused, exactly as everywhere else in this class. Two live
     * sub-levels holding a host at one coordinate is a question with no answer, and it is refused rather
     * than guessed at - the scan returns every match precisely so this method can see that. A pose that
     * cannot name a world position, or that does not round-trip the block at all, is refused too: every
     * caller does something at that position (a highlight, a broadcast, a drop), so a hit without one
     * would be worse than no hit.
     *
     * <h2>The evidence it claims, and why</h2>
     * The hit is marked {@code projected=true}, i.e. positive identification. That is not the projection
     * table answering - it is the record's own key answering, which identifies the host at least as
     * strongly, and it is what {@code hitInStructure} already claims for the identical reason (the caller
     * named the coordinate out of the structure's own space). It matters beyond the log: the break path
     * treats a positive identification as proof that does not need the structure's own view to agree,
     * because at {@code BreakEvent} time that view still reports the block being broken.
     */
    @Nullable
    static CarriedBlockRemoval.Hit hitAtLocalCoordinate(LocalScan scan, BlockPos localPos,
                                                        PoseSource poses,
                                                        @Nullable CarrierEntryTrace.Miss miss) {
        if (scan == null || poses == null || localPos == null) {
            return null;
        }
        if (scan.capped()) {
            note(miss, "recordsAt(" + localPos.toShortString() + "): the dimension holds more than "
                    + MAX_CARRIERS_SCANNED + " carrier(s), so the local-coordinate scan refused to look "
                    + "rather than guess which of them was meant");
            return null;
        }
        List<LocalRecord> candidates = new ArrayList<>(scan.records().size());
        for (LocalRecord record : scan.records()) {
            if (!record.host().landed() && record.carrier().isSubLevel()) {
                candidates.add(record);
            }
        }
        if (candidates.isEmpty()) {
            note(miss, "local-coordinate match at " + localPos.toShortString() + ": "
                    + CarrierEntryTrace.localVerdict(scan, localPos));
            return null;
        }
        if (candidates.size() > 1) {
            note(miss, "local-coordinate match at " + localPos.toShortString() + ": refused, "
                    + candidates.size() + " live sub-level carriers hold a host at this very coordinate "
                    + CarrierEntryTrace.localVerdict(scan, localPos));
            return null;
        }
        LocalRecord only = candidates.get(0);
        CarrierPose pose = poses.poseOf(only.carrier());
        if (pose == null) {
            note(miss, "local-coordinate match at " + localPos.toShortString() + ": the live record under "
                    + only.carrier().shortId() + " has no readable pose, so there is no world position to "
                    + "act at");
            return null;
        }
        BlockPos worldPos = CarrierProjection.project(pose, localPos);
        if (worldPos == null || localForVerified(pose, worldPos) == null) {
            note(miss, "local-coordinate match at " + localPos.toShortString() + ": the pose of "
                    + only.carrier().shortId() + " cannot round-trip that block, so the record is left "
                    + "alone rather than acted on at a guessed position");
            return null;
        }
        note(miss, "local-coordinate match at " + localPos.toShortString() + " -> "
                + only.carrier().shortId() + " world=" + worldPos.toShortString()
                + " (the coordinate IS the record's own key; the pose is only used to name the world "
                + "position)");
        return new CarriedBlockRemoval.Hit(only.carrier(), localPos.immutable(), worldPos, pose, true);
    }

    /**
     * <b>The same resolver, for a click that already arrived in the structure's own frame.</b>
     *
     * <h2>Why this is a different entry and not a branch of {@link #hitAt}</h2>
     * The two callers know different things, and neither can be derived from the other's input:
     *
     * <ul>
     *   <li>{@link #hitAt} is handed a <b>level position</b> and has to work out whether it is a world
     *       position a projection answers for or a structure's own coordinate - a question with two
     *       candidate answers, which is why it asks both frames in a fixed order and proves the
     *       structure one through the pose. Its answer matches the position it was given.</li>
     *   <li>this one is handed the carrier <b>and</b> the local coordinate outright, by a caller that
     *       already knows them: Sable's block click arrives at the plot coordinate (which <em>is</em> the
     *       local coordinate, see {@code CarrierStructureAccess}) and Create's interaction packet carries
     *       {@code localPos} in the contraption's own space. Asking the projection table or scanning
     *       plots for a fact already in hand would be a second, weaker derivation of it - and one that
     *       fails whenever the projection table happens not to be built (the bridge is closed, or the
     *       tick has not run yet).</li>
     * </ul>
     *
     * <p>The only thing left to check is ownership, and that is one hash lookup: the index must hold a
     * <em>live</em> record for that coordinate. A landed one is a world host and belongs to the world's
     * own break and click paths.
     *
     * <p>The world position is computed from the carrier's pose, because the callers need one for the
     * client's highlight and for the world notification. A record with no pose names no world position,
     * and this answers {@code null} rather than a guess: every caller does something at that position.
     */
    @Nullable
    public static CarriedBlockRemoval.Hit hitInStructure(ServerLevel level, CarrierId carrier,
                                                         BlockPos localPos,
                                                         @Nullable BlockPos worldPos) {
        if (level == null || carrier == null || localPos == null || !isActive()) {
            return null;
        }
        CarriedHostIndex index = indexOf(level);
        if (index == null) {
            return null;
        }
        return hitInStructure(index, carrier, localPos, worldPos);
    }

    /**
     * The same, against an index the caller already holds; package-visible and level-free so the one
     * decision it makes - "does this structure carry a live host at this coordinate" - is pinned by an
     * offline unit test.
     */
    @Nullable
    static CarriedBlockRemoval.Hit hitInStructure(@Nullable CarriedHostIndex index, CarrierId carrier,
                                                  BlockPos localPos, @Nullable BlockPos worldPos) {
        if (index == null || carrier == null || localPos == null || worldPos == null) {
            return null;
        }
        CarriedHost host = index.at(carrier, localPos);
        if (host == null || host.landed()) {
            return null;
        }
        // `projected=true` is the caller's own statement: it named this coordinate out of the structure's
        // own space, which is positive identification of the host. The field is only read by log lines.
        return new CarriedBlockRemoval.Hit(carrier, localPos, worldPos.immutable(), null, true);
    }

    /**
     * The local coordinate a world position names, or {@code null} unless the round trip through the
     * pose puts it back on that same position.
     *
     * <h2>Why the answer is proved rather than computed, and why that is still bounded</h2>
     * The two halves of this resolver use two different conventions for the same block, half a block
     * apart: {@link CarrierProjection#project} names a block by its <b>centre</b> and floors it, while
     * the pose itself maps a block's <b>corner</b> (which is what makes {@code plot.getBoundingBox()}
     * through the pose equal {@code SubLevel#boundingBox}, and what {@code SubLevelSpace} documents).
     * Inverting at the world block's centre therefore lands at the middle of the candidate block's
     * interior - and once the structure is turned, "the interior" is not a fixed set of coordinates any
     * more, so no single rounding rule is right for every angle. The half block has to be resolved by
     * asking the projection which of the candidates it names, which is the one direction that cannot be
     * ambiguous.
     *
     * <p>Bounded by <b>eight</b> forward projections of one coordinate each - the cell the centre fell
     * in, plus the seven neighbours the half-block ambiguity can reach - and nothing else. No loop over
     * the index, no walk over a plot, and a pose that cannot round-trip at all (a scale of zero, one
     * being written while it is read, a structure coming apart) names no coordinate, so nothing is
     * dropped and nothing is interacted with on a guess. That is the same guard
     * {@code SubLevelSpace#localForVerified} applies at claim time, repeated here because it has to hold
     * at <em>click</em> time too.
     *
     * <p>Package-visible rather than private so the proof - the thing about it that is invisible in game
     * until it is wrong - is pinned by an offline unit test, including a turn of 37.5 degrees, i.e. a
     * pose that puts block corners at genuinely fractional coordinates.
     */
    @Nullable
    static BlockPos localForVerified(CarrierPose pose, BlockPos worldPos) {
        if (pose == null || worldPos == null) {
            return null;
        }
        Vector3d centre = pose.transformPositionInverse(
                new Vector3d(worldPos.getX() + CarrierPose.BLOCK_CENTRE,
                        worldPos.getY() + CarrierPose.BLOCK_CENTRE,
                        worldPos.getZ() + CarrierPose.BLOCK_CENTRE),
                new Vector3d());
        if (!centre.isFinite()) {
            return null;
        }
        // The inverse lands at the middle of the candidate block's interior - where "the interior" is
        // not the same set of coordinates once the structure is turned, which is why the request is
        // resolved by trying the candidates and keeping the one that PROVES itself: at most four
        // integer coordinates can contain a given point, so the four below cover the answer for any
        // pose, and the projection is then asked which of them really is the block the player acted on.
        //
        // The candidates are the two cells the point can fall in, on each axis independently - the
        // half-open interval [x, x+1) and the one below it - which is the same "it is half a block
        // away" ambiguity between a block's corner and its centre that CarrierProjection#project has in
        // the other direction.
        int baseX = (int) Math.floor(centre.x);
        int baseY = (int) Math.floor(centre.y);
        int baseZ = (int) Math.floor(centre.z);
        for (int dx = 0; dx >= -1; dx--) {
            for (int dy = 0; dy >= -1; dy--) {
                for (int dz = 0; dz >= -1; dz--) {
                    BlockPos candidate = new BlockPos(baseX + dx, baseY + dy, baseZ + dz);
                    if (worldPos.equals(CarrierProjection.project(pose, candidate))) {
                        return candidate;
                    }
                }
            }
        }
        return null;
    }

    /**
     * <b>The carried host whose block has just been destroyed at {@code pos}, or {@code null}.</b>
     *
     * <p>Read-only: nothing is dropped and nothing is changed. Split from {@link #dropDestroyedHost} so
     * "which host was this break about" can be asked, logged and tested without an item ever appearing -
     * and so the drop itself happens at exactly one place.
     *
     * <h2>Why the two frames are accepted on different evidence</h2>
     * A hit the <b>projection table</b> answered is a coordinate computed from this carrier's own pose
     * and its own local coordinate, and the player has just broken exactly that block: that is positive
     * identification, and the structure's own view is <b>not</b> consulted - at {@code BreakEvent} time
     * it still reports the block that is being broken, because the event is delivered before the removal
     * happens. Requiring it to say "air" is what made the first click do nothing at all while the client
     * kept drawing the component.
     *
     * <p>A hit found through the <b>structure's own block storage</b> is a weaker claim - the position is
     * inside a sub-level's storage area, but the click could have been about anything in that region - so
     * there the structure's own view must agree that the block is gone.
     *
     * <p>Both still defer to the world's own store, which owns the position when it holds a component
     * there: that is the ordinary break path, and it drops it.
     */
    @Nullable
    public static CarriedBlockRemoval.Destroyed destroyedHostAt(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null || !isActive() || !RCConfig.carrierBlockDrops()) {
            return null;
        }
        CarriedBlockRemoval.Hit hit = hitAt(level, pos);
        if (hit == null) {
            return null;
        }
        CarriedHostIndex index = indexOf(level);
        if (index == null) {
            return null;
        }
        // WHERE THIS HOST IS NOW, and it is deliberately not `hit.worldPos()`. A hit the projection
        // table answered already carries the current projection, but a hit found through the
        // structure's own storage carries the position the click arrived at - which for a Sable plot is
        // the structure's own coordinate, two million blocks from anywhere a player can go. Using it
        // for the drop is the defect this round fixes (see CarriedBlockRemoval#resolve); recomputing it
        // from the pose the hit carries makes both frames name the same block, which is what "behaves
        // exactly as it does without movement" means.
        BlockPos worldPos = currentWorldPos(level, hit);
        if (worldPos == null) {
            // The pose cannot round-trip this host, so there is no honest answer to "where is it now":
            // nothing is dropped on a guess and the record stays exactly where it was.
            return null;
        }
        // The world's own store wins: a position that still holds a component belongs to the ordinary
        // break path, which drops it. This is also the check that keeps a host that has been put down
        // out of this path. Read at the host's CURRENT position - the origin is where it was picked up,
        // and after the structure has moved that is somewhere else entirely.
        InnerRedstoneNode stored = InnerRedstoneStore.get(level).get(worldPos);
        Slot storedInTheWorld = stored == null || stored.isEmpty() ? null : stored.slot();
        return CarriedBlockRemoval.resolve(index, storedInTheWorld, hit, worldPos,
                structureViewState(hit.carrier(), hit.localPos()),
                hit.projected() ? CarriedBlockRemoval.ViewRequirement.PROJECTION_IS_PROOF
                        : CarriedBlockRemoval.ViewRequirement.VIEW_MUST_SAY_GONE);
    }

    /**
     * <b>Where a resolved host is in the world right now, from the pose the hit carries.</b>
     *
     * <p>One implementation, called by every reader that has to name a position for a carried host: the
     * break path, the self-healing sweep and the carrier-side tools' notifications. It exists because the
     * alternative was written three times and one of the three - the sweep's world-store check - read the
     * record's {@code at()}, which is the position the host was <em>picked up</em> at and does not move
     * while it is riding. After a structure has moved, that is a different block.
     *
     * <p>Never guesses: a host with no pose, or a pose that cannot round-trip the coordinate, answers
     * {@code null} and the caller leaves the record alone.
     */
    @Nullable
    public static BlockPos currentWorldPos(ServerLevel level, CarrierId carrier, BlockPos localPos) {
        if (level == null || carrier == null || localPos == null) {
            return null;
        }
        try {
            CarrierPose pose = poseOf(level, carrier);
            return pose == null ? null : CarrierProjection.project(pose, localPos);
        } catch (RuntimeException | LinkageError failed) {
            // "Where is it now" is a question whose only safe failure answer is "I cannot tell": the
            // caller leaves the record alone rather than acting on a guess.
            return null;
        }
    }

    /** The same, for a hit that already carries the pose it was resolved with. */
    @Nullable
    public static BlockPos currentWorldPos(ServerLevel level, CarriedBlockRemoval.Hit hit) {
        if (hit.pose() != null) {
            return CarrierProjection.project(hit.pose(), hit.localPos());
        }
        return currentWorldPos(level, hit.carrier(), hit.localPos());
    }

    /**
     * What the structure's own view says about one local coordinate.
     *
     * <p>The three-valued read that makes "the block has really been removed" mean something: a throw, a
     * missing view or an unreadable coordinate answers {@link CarriedBlockRemoval.StructureViewState#UNREADABLE},
     * which callers must never read as "gone" - a component is never dropped, and a record is never
     * forgotten, on a guess. Only a view that answers air says {@code GONE}.
     */
    public static CarriedBlockRemoval.StructureViewState structureViewState(CarrierId carrier,
                                                                            BlockPos localPos) {
        CarrierSource carrierSource = source;
        CarrierAccess carrierAccess = carrierSource == null ? null : carrierSource.access();
        if (carrierAccess == null) {
            return CarriedBlockRemoval.StructureViewState.UNREADABLE;
        }
        try {
            BlockGetter view = carrierAccess.blockViewOf(carrier);
            if (view == null) {
                return CarriedBlockRemoval.StructureViewState.UNREADABLE;
            }
            BlockState state = view.getBlockState(localPos);
            if (state == null) {
                return CarriedBlockRemoval.StructureViewState.UNREADABLE;
            }
            return state.isAir() ? CarriedBlockRemoval.StructureViewState.GONE
                    : CarriedBlockRemoval.StructureViewState.PRESENT;
        } catch (RuntimeException | LinkageError unreadable) {
            return CarriedBlockRemoval.StructureViewState.UNREADABLE;
        }
    }

    /**
     * <b>Drops the component of a block a structure was carrying and that has just been broken.</b>
     *
     * <p>The carrier-layer mirror of {@code InnerRedstoneInteraction#cleanup}, which does the same for a
     * host block standing in the world: the record is taken out of the index, the component becomes an
     * item, the client is told, and the world around the position is woken so anything that was reading
     * this block's signal re-reads it. What it deliberately does <b>not</b> do is write the component
     * back into the world - that is a release, and the block it would be written into has just been
     * destroyed.
     *
     * <p>Exactly once, by construction: the record is removed by
     * {@link CarriedHostIndex#discard(CarrierId, BlockPos)} before anything is dropped, so a repeated
     * break event finds nothing on its second call. Nothing here fires for a release or a landing -
     * those go through {@code release}/{@code releaseAll} and never through this method.
     *
     * <p>A host the player had already emptied is discarded and announced like any other; there is simply
     * no item to drop, which is why the answer below is {@code null} for that case as well.
     *
     * @return the component that was dropped, or {@code null} when this position was not a destroyed
     *     carried block, or was one that held nothing
     */
    @Nullable
    public static Slot dropDestroyedHost(ServerLevel level, BlockPos pos) {
        // GUARDED, AND DELIBERATELY SO. This is reached from BlockEvent.BreakEvent: an exception out of
        // here is thrown back at the event bus, which is a crash report. Every failure is contained and
        // warned about once (see CarrierGuards), and the answer is "this was not a carried host" - which
        // leaves the break to vanilla, the honest degradation.
        return CarrierGuards.BREAK.call(pos, () -> {
            CarriedBlockRemoval.Destroyed destroyed = destroyedHostAt(level, pos);
            return destroyed == null ? null : dropCandidate(level, destroyed, "was broken");
        }, failed -> null);
    }

    /**
     * <b>The one write-back for "this carried block is gone": forget the record and drop the item.</b>
     *
     * <p>Shared by the two triggers on purpose - the break listener and the self-healing sweep - so the
     * two can never disagree about what a removal means. The two differ only in which candidates they
     * hand over, which is exactly the decision {@link CarriedBlockRemoval} owns.
     *
     * <p><b>Exactly once, by construction.</b> The record is removed by
     * {@link CarriedHostIndex#discard(CarrierId, BlockPos)} <em>before</em> anything is dropped, so a
     * second trigger for the same block - a repeated break event, or the sweep reaching a host the break
     * listener has already handled - finds nothing and returns {@code null}. Nothing here fires for a
     * release or a landing: those go through {@code release}/{@code releaseAll} and never reach this.
     *
     * <p><b>An empty record is announced too (P1.43).</b> A host the player has taken the component out
     * of is still a host the structure carries, and discarding it is still a removal the client has to be
     * told about - otherwise the record it still holds is the last unannounced removal in the feature.
     * The only difference is that there is nothing to drop.
     *
     * @param why which trigger this was, for the log line
     * @return the component that was dropped, or {@code null} when the record had already gone <b>or held
     *     nothing</b> (both are announced; only the first is a no-op)
     */
    @Nullable
    private static Slot dropCandidate(ServerLevel level, CarriedBlockRemoval.Destroyed destroyed,
                                      String why) {
        CarriedHostIndex index = indexOf(level);
        if (index == null) {
            return null;
        }
        CarriedHost removed = index.discard(destroyed.carrier(), destroyed.localPos());
        if (removed == null) {
            // Somebody got there first - a second break event for one block, the self-healing sweep
            // arriving after the listener, or the record having been released in the same tick. There is
            // then nothing to announce and nothing to drop: "exactly once" is this line, not a counter.
            return null;
        }
        // AN EMPTY RECORD IS STILL A RECORD THAT HAS GONE (P1.43). `slot` is null for a host the player
        // had already taken the component out of, and that is the whole of the difference: there is
        // nothing to drop, and there is still a client to tell. The notice below used to sit behind an
        // early return on the null slot, so the one removal that can be left unannounced was the one a
        // retrieval had just created - and an unannounced removal is what every ghost in this feature has
        // been made of. The record is discarded either way, so the notice is owed either way.
        Slot slot = removed.slot();
        CarriedHosts.get(level).setDirty();
        if (slot != null) {
            Block.popResource(level, destroyed.dropPos(), HostRules.itemFor(slot.type));
        }
        // A carrier-side wrench pick names one structure's hosts, so a record that has just stopped
        // existing cannot still be the near end of somebody's line.
        clearCarrierSelection(destroyed.carrier());
        // A carrier-side wrench pick is a half-finished gesture about one structure's hosts, so it
        // cannot outlive the record it names any more than the record can.
        CarriedSelection.clearCarrier(destroyed.carrier());
        // THE HOST-LEVEL REMOVAL NOTICE. The carrier message carries the host list this record is no
        // longer in, and - separately - this coordinate as a removed one, because "not in the list" is
        // the same shape as a put-down and the client is not allowed to guess between them. Without
        // this the client kept the component as a landed record and drew the outline and the component
        // at the position the block used to occupy for the whole flight: the author's ghost.
        //
        // Sent before the world is notified, so the frame the client is told to stop drawing on is never
        // the frame after something else has already been drawn there.
        broadcastCarrier(level, destroyed.carrier(), index, poseOf(level, destroyed.carrier()),
                List.of(destroyed.localPos()));
        RCConfig.LOGGER.info("[redstonecircuit] carrier {}: host removed and broadcast local={} "
                        + "(reason: {}); {} host(s) still riding that structure. The client is told to "
                        + "drop it, so it is neither drawn as a ghost nor kept as landed",
                destroyed.carrier().shortId(), destroyed.localPos().toShortString(), why,
                index.liveHostsOf(destroyed.carrier()).size());
        // ...and the world around the position this block used to occupy has to re-read its signal.
        notifyWorld(level, destroyed.dropPos());
        // A block that no longer holds anything cannot be either half of a wrench link. Both the drop
        // position (where the player is) and the position the host was answering for are cleared.
        WrenchSelection.clearIf(level, destroyed.dropPos());
        WrenchSelection.clearIf(level, removed.at());
        if (slot == null) {
            // The honest line for the emptied case: nothing is dropped, because nothing was inside. It is
            // written rather than skipped so that "the block broke and no item came out" is answered by
            // the log instead of looking like the drop path never ran.
            RCConfig.LOGGER.info("[redstonecircuit] carrier {}: the block carrying an EMPTY host at "
                            + "local={} was destroyed ({}); the component had already been taken back "
                            + "out, so nothing was dropped - the record is gone and the client is told",
                    destroyed.carrier().shortId(), destroyed.localPos().toShortString(), why);
            return null;
        }
        RCConfig.LOGGER.info("[redstonecircuit] carrier {}: the block carrying {} at local={} was "
                        + "destroyed ({}; the component was riding the structure, so the world store "
                        + "never held it); dropped the component at {} - exactly once",
                destroyed.carrier().shortId(), slot.type, destroyed.localPos().toShortString(), why,
                destroyed.dropPos().toShortString());
        return slot;
    }

    // ------------------------------------------------- P1.20: the self-healing sweep --

    /**
     * <b>The second trigger: a bounded, rotating sweep for carried blocks that have gone.</b>
     *
     * <h2>Why a second trigger at all</h2>
     * A break event is one way a carried block can leave, and it is not the only one. The author asked
     * for the ghost to be closed off rather than for one trigger to be moved, and a Create drill or saw
     * taking a block out through its own movement behaviour produces no {@code BreakEvent} our listener
     * can see at all - the same for a structure edit made by another mod.
     *
     * <h2>The bound</h2>
     * One block read per host, on the level tick, over structures the author's world has hundreds of
     * blocks' worth of. So the sweep is capped by {@code carrierSelfHealCheckPerTick} (default small,
     * {@code 0} switches it off entirely), the cap is applied to the loop as well as to the count, and
     * rotation is a per-carrier cursor so a machine larger than the budget finishes over several ticks
     * instead of being restarted or skipped. Cursors are pruned to the carriers still in the index and
     * cleared with the index, so nothing here can grow or survive a level unload.
     *
     * <h2>What it refuses to do</h2>
     * A view that cannot be read (or that throws) is {@link CarriedBlockRemoval.StructureViewState#UNREADABLE}
     * and the host is left alone, for ever if need be. A view that says a block is still there is left
     * alone, which is why a structure that is merely <em>moving</em> cannot be mistaken for one that has
     * been broken: moving changes the pose, never the block at one of the structure's own local
     * coordinates. A landed record is a world host and belongs to the world's break path.
     *
     * <p><b>Never runs while the level is going away.</b> This is called from {@link #tick(ServerLevel)},
     * which the level tick drives, and {@link #clear(ServerLevel)} drops the cursors on unload. There is
     * no other caller and no timer of its own.
     *
     * @param perTick the most hosts to examine this tick
     * @return what the pass did, for the log and for the tests
     */
    public static CarriedBlockRemoval.SelfHealTick pollDestroyedHosts(ServerLevel level,
                                                                      SelfHealPoll poll,
                                                                      int perTick) {
        if (level == null || poll == null || perTick <= 0 || !isActive()
                || !RCConfig.carrierBlockDrops()) {
            return new CarriedBlockRemoval.SelfHealTick(0, 0, List.of());
        }
        CarriedHostIndex index = indexOf(level);
        if (index == null || index.isEmpty()) {
            SELF_HEAL.clear();
            return new CarriedBlockRemoval.SelfHealTick(0, 0, List.of());
        }
        return scanForDestroyedHosts(level, index, poll, perTick);
    }

    /**
     * The bounded, rotating walk itself, over whatever index it is given.
     *
     * <p>Split out <b>without</b> the config and "is the carrier layer armed" gates so the one thing that
     * cannot be checked by looking at it - the rotation and the per-tick ceiling - is pinned by an
     * offline unit test. {@link #pollDestroyedHosts} is the production entry point and applies the gates
     * first; this is what it calls.
     */
    static CarriedBlockRemoval.SelfHealTick scanForDestroyedHosts(ServerLevel level,
                                                                  CarriedHostIndex index,
                                                                  SelfHealPoll poll, int perTick) {
        if (index == null || poll == null || perTick <= 0 || index.isEmpty()) {
            return new CarriedBlockRemoval.SelfHealTick(0, 0, List.of());
        }
        List<CarrierId> carriers = index.carriers();
        retainSelfHealCursors(carriers);
        List<CarriedBlockRemoval.Destroyed> found = new ArrayList<>();
        int budget = perTick;
        int visited = 0;
        int touched = 0;
        for (CarrierId carrier : carriers) {
            if (budget <= 0) {
                break;
            }
            List<CarriedHost> hosts = index.hostsOf(carrier);
            if (hosts.isEmpty()) {
                continue;
            }
            touched++;
            int start = Math.floorMod(selfHealCursor(carrier), hosts.size());
            int taken = 0;
            for (int offset = 0; offset < hosts.size() && budget > 0; offset++) {
                CarriedHost host = hosts.get((start + offset) % hosts.size());
                if (host.landed()) {
                    // A put-down host is a world host again; the world's own break path owns it. Still
                    // counted as examined, so the cursor cannot get stuck on a finished machine.
                    visited++;
                    taken++;
                    budget--;
                    continue;
                }
                budget--;
                visited++;
                taken++;
                CarriedBlockRemoval.StructureViewState view =
                        structureViewState(carrier, host.localPos());
                if (!CarriedBlockRemoval.selfHealWanted(host, view)) {
                    continue;
                }
                CarriedBlockRemoval.Destroyed destroyed = poll.destroyed(level, carrier, host, view);
                if (destroyed != null) {
                    found.add(destroyed);
                }
            }
            advanceSelfHealCursor(carrier, taken, hosts.size());
        }
        return new CarriedBlockRemoval.SelfHealTick(visited, touched, List.copyOf(found));
    }

    /** Where the sweep has got to in one carrier's host list; {@code 0} for a carrier never swept. */
    private static int selfHealCursor(CarrierId carrier) {
        Integer cursor = SELF_HEAL.get(carrier.uuid());
        return cursor == null ? 0 : cursor;
    }

    /**
     * Drops the sweep's cursors without touching anything else; used by the unit tests.
     *
     * <p>The cursors are process-wide (they describe carriers, not a caller), so a test that reused a
     * previous test's rotation would silently assert nothing - the same reason the solve tests reset
     * {@code CarrierNetworks}.
     */
    static void resetSelfHealCursors() {
        SELF_HEAL.clear();
    }

    /** Records how far the sweep got, so the next tick continues rather than restarting. */
    private static void advanceSelfHealCursor(CarrierId carrier, int taken, int total) {
        if (total <= 0 || taken <= 0) {
            return;
        }
        SELF_HEAL.put(carrier.uuid(), Math.floorMod(selfHealCursor(carrier) + taken, total));
    }

    /** Forgets the cursors of carriers that are no longer in the index, so the map cannot grow. */
    private static void retainSelfHealCursors(List<CarrierId> live) {
        if (SELF_HEAL.isEmpty()) {
            return;
        }
        if (live.isEmpty()) {
            SELF_HEAL.clear();
            return;
        }
        java.util.Set<UUID> keep = new java.util.HashSet<>();
        for (CarrierId carrier : live) {
            keep.add(carrier.uuid());
        }
        SELF_HEAL.keySet().retainAll(keep);
    }

    /**
     * The production sweep: the same bounded walk, with the world's own store consulted per candidate.
     *
     * <p>The store read is the {@link CarriedBlockRemoval#ownsDestruction} guard, and it is skipped
     * entirely while the level's store is empty - which is the ordinary state of a world with no
     * ground-level inner redstone, and the reason this costs one boolean in that case - and is a single
     * hash lookup per candidate otherwise.
     */
    private static void pollForDestroyedHosts(ServerLevel level) {
        int budget = RCConfig.carrierSelfHealCheckPerTick();
        if (budget <= 0) {
            return;
        }
        // GUARDED: this runs on the level tick, and an exception out of it aborts the tick and stops the
        // server. A sweep that fails does nothing at all this pass - the records stay where they are, and
        // the next pass tries again - which is the honest degradation for a background rescue.
        CarrierGuards.SELF_HEAL.call(null, () -> {
            sweepForDestroyedHosts(level, budget);
            return Boolean.TRUE;
        }, false);
    }

    /** The sweep itself; see {@link #pollForDestroyedHosts} for the guard that wraps it. */
    private static void sweepForDestroyedHosts(ServerLevel level, int budget) {
        InnerRedstoneStore store = InnerRedstoneStore.get(level);
        CarriedHostIndex index = indexOf(level);
        CarriedBlockRemoval.SelfHealTick tick = pollDestroyedHosts(level, (pollLevel, pollCarrier,
                host, view) -> {
            // WHERE THIS HOST IS NOW, and this is the half of the sweep that did not converge after a
            // structure had moved. It used to read the world store at `host.at()`, which is where the
            // host was *picked up* and does not move while it rides - so after the structure had gone
            // somewhere else the sweep was asking about a different block, finding the component the
            // player had since stored there, and answering "the world owns this one" for ever. The drop
            // position was always computed from the pose, which is why this was a silent mismatch rather
            // than a wrong drop: the record was simply never consumed.
            BlockPos now = currentWorldPos(pollLevel, pollCarrier, host.localPos());
            if (now == null) {
                // No pose, or a pose that cannot round-trip this coordinate: nothing is dropped on a
                // guess - the orphan sweep still owns that component and will release it.
                CarrierEntryTrace.selfHeal(pollLevel, pollCarrier, host, view, null, null,
                        "the host's local coordinate cannot be projected right now (no pose, or a pose "
                                + "that does not round-trip it), so there is no honest world position to "
                                + "drop at");
                return null;
            }
            InnerRedstoneNode stored = store.isEmpty() ? null : store.get(now);
            Slot storedInTheWorld = stored == null || stored.isEmpty() ? null : stored.slot();
            // THE DECISION IS A PURE FUNCTION, so "the sweep reads the world store at the host's
            // CURRENT position" is an offline assertion rather than a claim about this lambda: see
            // CarriedBlockRemoval#selfHealCandidate and the movement tests.
            CarriedBlockRemoval.Destroyed destroyed = CarriedBlockRemoval.selfHealCandidate(index,
                    pollCarrier, host, view, now, storedInTheWorld);
            // THE CANDIDATE LINE. Reached only for a host the structure's own view already reports as
            // gone - i.e. only when the sweep really has a candidate - and it says which of the two
            // answered: the drop, or a refusal naming what held it back. Diagnostics only: the value
            // returned below is the decision, unchanged.
            CarrierEntryTrace.selfHeal(pollLevel, pollCarrier, host, view, now, destroyed,
                    storedInTheWorld == null
                            ? "the index no longer holds a live record for this coordinate (already "
                                    + "consumed, or put down)"
                            : "the world's own store holds a component at the projected position, so the "
                                    + "ordinary world break path owns it");
            return destroyed;
        }, budget);
        // The pass line: "the sweep ran at all". Throttled, and written before the drops so the two
        // numbers it carries are the sweep's own.
        if (tick.looked()) {
            CarrierEntryTrace.selfHealPass(level, tick.visited(), tick.carriers(), tick.found().size());
        }
        if (!tick.found().isEmpty()) {
            for (CarriedBlockRemoval.Destroyed destroyed : tick.found()) {
                dropCandidate(level, destroyed, "found gone by the self-healing sweep");
            }
        }
    }

    /**
     * How the sweep turns one host into a drop, or {@code null} to leave it.
     *
     * <p>An interface rather than putting the level work inline so a unit test can drive the
     * <b>rotation and the budget</b> - which is where an off-by-one would be invisible - without a
     * running game.
     */
    @FunctionalInterface
    public interface SelfHealPoll {

        /**
         * @param carrier the structure carrying the host
         * @param host the record, still riding
         * @param view what the structure's own view said - always
         *     {@link CarriedBlockRemoval.StructureViewState#GONE} when this is reached, and passed on so
         *     the production implementation can log the evidence it acted on
         */
        @Nullable
        CarriedBlockRemoval.Destroyed destroyed(ServerLevel level, CarrierId carrier, CarriedHost host,
                                                @Nullable CarriedBlockRemoval.StructureViewState view);
    }

    /**
     * Tells the world that the signal at a projected position may have changed.
     *
     * <p>Reuses the notification the world network already sends for a host block standing on the
     * ground: a redstone lamp only re-reads its neighbours when something tells it to, and a machine
     * moving past one changes no block at all - so without this the lamp would stay dark however
     * loudly the projection answered signal queries.
     */
    private static void notifyWorld(ServerLevel level, BlockPos worldPos) {
        InnerRedstoneNetwork.notifyOutputChanged(level, worldPos);
    }

    /**
     * <b>Whether a live carried host actually holds a component</b> - the read every carrier-side action
     * has to make before it touches a record's contents.
     *
     * <p>A structure can be carrying a block that holds nothing: the player took the component back out
     * ({@link CarriedHostIndex#removeSlot}), and the record stays with a {@code null} slot. That is the
     * ordinary state of a block somebody has just built into a machine and emptied - not an error - and
     * the world's own store answers the same question the same way ("this position has no entry"), which
     * is why every carrier action has to treat it as "there is no component here" rather than
     * dereferencing it. Reaching this through one named method is what keeps the answer the same for the
     * wrench, the rotation rod, the bare-hand switch and the placement path.
     *
     * <p>Costs one index lookup, and is only ever reached from a click that has already resolved to a
     * live carried host.
     */
    public static boolean hostHasComponent(ServerLevel level, CarrierId carrier, BlockPos localPos) {
        if (carrier == null || localPos == null) {
            return false;
        }
        CarriedHostIndex index = indexOf(level);
        CarriedHost host = index == null ? null : index.at(carrier, localPos);
        return host != null && !host.landed() && host.slot() != null;
    }

    /**
     * The turn half of the same notification: a structure changed which way its blocks emit, and the
     * world is being told at a position that did not move.
     *
     * <p>Logged because it is otherwise unobservable from outside. "The machine turned on the spot" and
     * "the machine is parked" are the same three numbers on the projection line - same position, same
     * local coordinate, same power - and the author's report ("the signal does not come back after it
     * has turned past") is precisely the difference between them being missed. So the next log has one
     * line per structure per tick that turned, naming the two orientations and the angle between them,
     * which is the direct evidence that this path was reached. Gated on {@code debugLog} like every
     * other per-change line, and emitted at most once per carrier per tick by
     * {@link CarrierProjection#forEachChange}, so a 200-block machine turning does not print 200 of
     * them.
     */
    private static void noteTurned(CarrierProjection.TurnedHost turned) {
        if (!RCConfig.debugLog()) {
            return;
        }
        RCConfig.LOGGER.info("[redstonecircuit] carrier {} turned {}: re-publishing host {} at {} "
                        + "so the world re-reads which way it emits",
                turned.carrier().shortId(), turned.describe(), turned.localPos().toShortString(),
                turned.worldPos().toShortString());
    }

    // --------------------------------------------------- talking to the client --

    /**
     * The one way a carrier goes onto the wire, so that "the client has heard of it" cannot be missed.
     *
     * <p>Every broadcast of a carrier goes through here rather than through {@code HostSync} directly.
     * The reason is the removal rule: a carrier may only be taken back from a client that was told about
     * it, and the ledger behind that ({@link CarrierRemovalNotices}) has to be advanced at exactly the
     * moment the message leaves. Getting that wrong in one direction means a needless packet; getting it
     * wrong in the other means a component that can never be drawn again.
     */
    private static void broadcastCarrier(ServerLevel level, CarrierId carrier,
                                         @Nullable CarriedHostIndex index, @Nullable CarrierPose pose) {
        broadcastCarrier(level, carrier, index, pose, List.of());
    }

    /**
     * The same exit, with the hosts this broadcast is <b>removing</b> named explicitly.
     *
     * <h2>Why "absent from the host list" is not enough</h2>
     * A carrier message replaces one structure's host list wholesale, so a host that is no longer in it
     * has left - but it has left for one of two reasons, and the client could only see the first. A
     * host that was <b>put down</b> must be kept as a landed record, because its component has to be
     * drawn at the landing position even where no block stands. A host whose block was <b>destroyed</b>
     * must be dropped outright, because its component is now an item on the floor and there is nothing
     * to draw anywhere. Guessing "put down" is what produced the author's ghost: the outline and the
     * component carried on being drawn inside the structure until it was put down.
     *
     * <p>So the removal list goes on the wire beside the host list. It is populated by the two removal
     * paths ({@link #dropCandidate}) and by a retrieval ({@link #broadcastCarrierNow(ServerLevel,
     * CarrierId, List)}), and it is the only thing that can make a client forget a host without a
     * landing.
     */
    private static void broadcastCarrier(ServerLevel level, CarrierId carrier,
                                         @Nullable CarriedHostIndex index, @Nullable CarrierPose pose,
                                         List<BlockPos> removed) {
        REMOVALS.mentioned(level.dimension().location().toString(), carrier);
        HostSync.broadcastCarrier(level, carrier, index, pose, removed);
        // How many carriers the server has, told to the client's own trace so that "the client holds
        // more carriers than the server has" becomes a comparison either side can make. Provisional
        // claims are excluded on both sides: they are deliberately never sent with a pose, so counting
        // them would make the ordinary assembly of every machine look like a leak.
        CarrierTrace.noteServerCarriers(level.dimension(), settledCarriers(level));
        if (RCConfig.debugLog()) {
            // `poseInThisMessage=absent` is the honest and important case: a carrier message with no
            // pose IS the removal statement, so it must not be read as "the pose was refreshed". The
            // age of the last pose actually sent is the number that tells "the client is out of date"
            // apart from "the structure has not moved" - see broadcastPose.
            RCConfig.LOGGER.info("[redstonecircuit]   poseInThisMessage={} lastPoseAge={}",
                    pose == null ? "absent (a carrier message with no pose IS the removal message)"
                            : "present",
                    ageText(sentPoseAgeTicks(carrier, level.getGameTime())));
        }
    }

    /**
     * Re-sends one carrier to the clients right now, with its current hosts and pose.
     *
     * <p>The carrier-side tools' write half ({@code CarrierInteraction}): a wrench action or a rod turn
     * changes the connection bars and a component's facing, neither of which is a block state, so the
     * carrier payload - which already replaces a client's whole host list for that structure - is what
     * makes the change visible. Goes through the one broadcast exit, so the removal ledger stays right.
     */
    public static void broadcastCarrierNow(ServerLevel level, CarrierId carrier) {
        broadcastCarrierNow(level, carrier, List.of());
    }

    /**
     * The same, naming the local coordinates this message says are <b>gone</b>.
     *
     * <p>The second caller of the removal list, and it is not a removal of a record: a bare-hand
     * retrieval leaves the host riding and holding nothing, and the carrier payload cannot express that
     * state - an empty host is not in its host list, and "not in the list" is how a put-down travels (see
     * {@code CarriedInsert#removalNotice}, which is where the rule lives). So the retrieval names the
     * coordinate through the same field a break uses, and the client treats the two the same way: that
     * coordinate is dropped from all three places it could be drawn from, and is not kept as a landing.
     *
     * <p>What differs from the break path is only the server's own record, which <b>stays</b> (riding,
     * empty, still answering the wrench and the in-structure solve - the state
     * {@code CarriedInsert#apply} writes through {@code CarriedHostIndex#updateSlot} on a retrieval).
     * The client's copy does not need it: nothing on the client draws an empty host - the shell and the
     * component are both emitted from the slot - and the record is re-sent the moment a component is put
     * back in.
     *
     * @param removed the hosts' local coordinates, in the structure's own frame; empty for an ordinary
     *     message
     */
    public static void broadcastCarrierNow(ServerLevel level, CarrierId carrier, List<BlockPos> removed) {
        if (level == null || carrier == null || !isActive()) {
            return;
        }
        broadcastCarrier(level, carrier, indexOf(level), poseOf(level, carrier),
                removed == null ? List.of() : removed);
    }

    /**
     * Forgets every player's carrier-side wrench pick for one structure.
     *
     * <p>Called when that structure loses a host for any reason other than the player's own wrench: a
     * break, the self-healing sweep, a landing. A pick is a half-finished gesture naming one structure's
     * hosts, and the record it names has just stopped existing - so the honest answer is to drop it
     * rather than leave a second shift-click able to route from a component that is gone.
     *
     * <p>Deliberately per carrier rather than per position: which positions a pick may name is a
     * per-player question, and walking every player's pick on the break path would be work proportional
     * to the player count for a case that costs one extra click to redo.
     */
    public static void clearCarrierSelection(CarrierId carrier) {
        CarriedSelection.clearCarrier(carrier);
    }

    /** {@code 7t} for a known age, {@code never} when nothing has been sent for this carrier yet. */
    private static String ageText(long age) {
        return age < 0L ? "never" : age + "t";
    }

    /** The same, for a pose-only message, which also counts as having told the client the carrier exists. */
    private static void broadcastPose(ServerLevel level, CarrierId carrier, CarrierPose pose) {
        broadcastPose(level, carrier, pose, true, false);
    }

    /** The same, with "this is a heartbeat, not a change" made explicit. */
    private static void broadcastPose(ServerLevel level, CarrierId carrier, CarrierPose pose,
                                      boolean countAsChange) {
        broadcastPose(level, carrier, pose, countAsChange, false);
    }

    /**
     * The same, with "this is a heartbeat, not a change" made explicit, and its own rate limit.
     *
     * <p>Three kinds of pose message go out, and each is worth a different amount of log:
     * <ul>
     *   <li>{@code change} - always printed. This is the one an operator greps for, because it is the
     *       statement "the client has been told the structure moved";</li>
     *   <li>{@code heartbeat} - always printed. One line per carrier per heartbeat interval (a second
     *       by default), which is the pace a reader can actually follow, and it is the evidence that a
     *       parked machine is still being mentioned;</li>
     *   <li>{@code sample} - the periodic re-send while something is riding, ten a second by default.
     *       Printed only on the burst's first message, when the gap in front of it was long enough to
     *       matter, and at most once per twenty. A hundred lines a second of "the structure is still
     *       where it was" is not evidence, it is noise that hides the two lines above.</li>
     * </ul>
     */
    private static void broadcastPose(ServerLevel level, CarrierId carrier, CarrierPose pose,
                                      boolean countAsChange, boolean sampleOnly) {
        // A POSE IS THE PROOF THAT THE STRUCTURE IS STILL BEING DRIVEN, so it cancels any deferral this
        // carrier is holding. That is the arm which covers the direction change the author hit: the
        // bearing reversed, `stopMoving` fired, and poses kept arriving - so the release is cancelled and
        // the component keeps riding. See `stopMovingOrDefer`.
        notePoseArrived(level, carrier);
        REMOVALS.mentioned(level.dimension().location().toString(), carrier);
        // GUARDED, because this is the line the author's crash came out of. Building the carrier payload
        // walks every live host of the structure, and a host whose component has just been taken out holds
        // nothing; `HostSync#carrierPayload` now skips those, and this guard is the second line of defence
        // for whatever the third such state turns out to be. A pose that cannot be sent is retried on the
        // next tick by the ordinary heartbeat, and nothing else in this tick is affected.
        CarrierGuards.POSE.call(null, () -> {
            HostSync.broadcastPose(level, carrier, pose, countAsChange);
            return Boolean.TRUE;
        }, false);
        CarrierTrace.noteServerCarriers(level.dimension(), settledCarriers(level));

        long now = level.getGameTime();
        long previousAge = sentPoseAgeTicks(carrier, now);
        SENT_POSE_TICKS.put(carrier.uuid(), now);
        // THE LAST POSE THE CLIENT WAS TOLD, kept on every message rather than only on a change.
        //
        // This used to be written by the caller of the change branch alone, which was enough for what
        // it was for (`closeTo` on the next tick) and not enough for what it turned out to be needed
        // for: a structure whose own pose query has stopped answering has no other record of where it
        // was, and the release path needs one - see `landingFor`. A heartbeat or a sample is a pose the
        // client is drawing from, so it is a pose a release may land on, and it is recorded here.
        SENT_POSES.put(carrier.uuid(), pose);
        if (!RCConfig.debugLog()) {
            return;
        }
        if (sampleOnly) {
            if (previousAge < 0L || previousAge > RCConfig.carrierPoseSampleTicks()) {
                sampleBurst = 0; // A fresh carrier, or a gap: say so once, then keep quiet.
            } else if (++sampleBurst % SAMPLE_LOG_EVERY != 0) {
                return;
            }
        } else {
            sampleBurst = 0;
        }
        RCConfig.LOGGER.info("[redstonecircuit] sent carrier {} pose={} why={} previousAge={}",
                carrier.shortId(), pose == null ? "none" : pose.describe(),
                sampleOnly ? "sample" : (countAsChange ? "change" : "heartbeat"),
                previousAge < 0 ? "never" : previousAge + "t");
    }

    /** Poses in the current periodic-sampling burst, and how many of them get a log line. */
    private static int sampleBurst;

    private static final int SAMPLE_LOG_EVERY = 20;

    /**
     * How many carriers in this level a client could be told about, i.e. excluding provisional claims.
     *
     * <p>The number the client's own count is compared against; see
     * {@link CarrierIndex#settledCarrierCount()}.
     */
    private static int settledCarriers(ServerLevel level) {
        CarriedHostIndex index = indexOf(level);
        if (index == null) {
            return 0;
        }
        int total = 0;
        for (CarrierId carrier : index.carriers()) {
            if (!CarrierIdentity.isProvisional(carrier)) {
                total++;
            }
        }
        return total;
    }

    /** One line about the removal notices, for {@code /rc carrier}. */
    public static String describeRemovals(ServerLevel level) {
        return REMOVALS.describe(level.dimension().location().toString());
    }

    /**
     * One line about the world side of the two transfers, for {@code /rc carrier}.
     *
     * <p>The two numbers that decide whether "the component is back in the world" reached the client:
     * {@code restoredHosts} counts the world-host announcements made because a structure put a host
     * down, and {@code worldHostSuppressed} counts the ones made because a structure picked one up.
     * A landing that shows {@code moved=1} in the log but leaves {@code restoredHosts=0} is the exact
     * shape of the defect this round fixed, so the two are worth reading side by side.
     */
    public static String describeWorldHostTransfer(ServerLevel level) {
        CarrierTrace.Sync sync = CarrierTrace.syncIfAny(level.dimension());
        long restored = sync == null ? 0 : sync.restored();
        long suppressed = sync == null ? 0 : sync.worldHostSuppressed();
        return "worldHostTransfer: restoredHosts=" + restored
                + " worldHostSuppressed=" + suppressed
                + " (restored = a structure put a host down and the client was told it is a world host"
                + " again; suppressed = a structure picked one up and the client was told to stop"
                + " drawing it there)";
    }

    /**
     * Records that a whole carrier snapshot went to one arriving player.
     *
     * <p>Called by the sync layer, because a snapshot mentions carriers exactly as a single message
     * does: a client that has just been told about a carrier has to be told again when it goes away, and
     * the ledger that decides whether that notice is owed lives here.
     */
    public static void noteSnapshotSent(ServerLevel level, List<CarrierId> carriers) {
        String dimension = level.dimension().location().toString();
        for (CarrierId carrier : carriers) {
            REMOVALS.mentioned(dimension, carrier);
        }
        CarrierTrace.noteServerCarriers(level.dimension(), settledCarriers(level));
    }

    // ----------------------------------------------------------------- moves --

    /**
     * One block reports that it is starting to move with a structure.
     *
     * <p>The P1 diagnostics settled the timing question by measurement: when this is called the block
     * is <b>still in the world</b> ({@code worldState=stone (still there)}), so the component can be
     * read and snapshotted right here - no "remember the world before assembly" pass is needed, which
     * is the whole reason this route was chosen over mixing into Create's assembly.
     *
     * <h2>Two-phase identity: this overload is what the hook calls</h2>
     * {@code entityUuid} is the contraption entity's UUID when the assembly has one, and {@code null}
     * when it does not. A {@code null} is <b>not</b> a reason to drop the claim: {@code provisional} is
     * the identity the claim is made under instead, and {@link #reconcile} re-labels it onto the entity
     * as soon as one exists (normally later in the same tick - see {@link CarrierTrace}).
     *
     * @param entityUuid the contraption entity's UUID, or {@code null} while it has none yet
     * @param provisional the stand-in identity to claim under in that case
     * @param localPos the block's coordinate inside the structure
     * @param worldPos where the block still stands: the structure's anchor plus {@code localPos}
     */
    public static void startMoving(ServerLevel level, @Nullable UUID entityUuid,
                                   @Nullable CarrierId provisional, CarrierKind kind,
                                   BlockPos localPos, BlockPos worldPos) {
        if (entityUuid == null && provisional == null) {
            // No entity and no stand-in: there is nothing this claim could be filed under. Reported
            // rather than swallowed, because it means the compat half could not name the structure.
            RCConfig.LOGGER.warn("[redstonecircuit] carrier claim at {} dropped: no entity and no "
                    + "provisional identity", worldPos.toShortString());
            return;
        }
        CarrierIdentity.Identity identity = CarrierIdentity.claim(entityUuid, provisional, kind);
        CarrierKind finalKind = identity.isProvisional() ? provisional.kind() : kind;
        startMoving(level, identity, finalKind, localPos, worldPos);
    }

    /**
     * The single-identity form, kept for the Sable path and for callers that already know the UUID.
     *
     * <p><b>The kind is taken from the caller, and this is load-bearing.</b> This overload used to
     * hard-code {@code CarrierIdentity.Identity.ofEntity(entityUuid)}, which builds a
     * {@link CarrierKind#CONTRAPTION} id - so a Sable sub-level claimed through here was filed as
     * {@code c:<uuid>} rather than {@code s:<uuid>}. The author's log is the proof: the Sable sweep's
     * own claim line came out as {@code carrier claim c:6c60aeef … identity=entity} for a structure the
     * same log names as {@code sub-level 6c60aeef-…}. Two consequences, both of them the reported
     * defect: {@code /rc carrier} called a sub-level a Create contraption, and the Create half's orphan
     * sweep - which asks the <em>Create</em> half for {@code c:<uuid>}'s pose, and is told there is no
     * such contraption - declared the still-flying sub-level gone and wrote its components back into the
     * world.
     *
     * @param carrierUuid the contraption entity's UUID, or the sub-level's UUID
     * @param kind which of the two it is - taken from the caller rather than assumed
     * @param localPos the block's coordinate inside the structure
     * @param worldPos where the block still stands: the structure's anchor plus {@code localPos}
     */
    public static void startMoving(ServerLevel level, UUID carrierUuid, CarrierKind kind,
                                   BlockPos localPos, BlockPos worldPos) {
        startMoving(level, CarrierIdentity.Identity.of(carrierUuid, kind), kind, localPos, worldPos);
    }

    /** The claim itself, once the identity has been decided. */
    private static void startMoving(ServerLevel level, CarrierIdentity.Identity identity,
                                    CarrierKind kind, BlockPos localPos, BlockPos worldPos) {
        CarrierId carrier = identity.carrier();
        CarriedHostIndex index = indexOf(level);
        if (index == null) {
            return;
        }
        CarriedHostIndex.ClaimOutcome outcome =
                index.claim(carrier, localPos, worldPos, CarriedHosts.slots(level));

        // STAGE 1 of the three-stage diagnostic: did the claim happen, and under which identity. Counted
        // for every call - including the empty-block case, which is the ordinary answer for most of a
        // machine and the reason "claimed=0" needs to be told apart from "nothing to claim".
        if (outcome.performed()) {
            if (identity.isProvisional()) {
                CarrierTrace.noteProvisionalClaim(level.dimension());
            } else {
                CarrierTrace.noteEntityClaim(level.dimension());
            }
        } else if (!outcome.claimed()) {
            CarrierTrace.noteEmptyBlock(level.dimension());
        }

        if (outcome.performed()) {
            CarriedHosts.get(level).setDirty();
        }
        if (RCConfig.debugLog() || outcome.performed()) {
            RCConfig.LOGGER.info("[redstonecircuit] carrier claim {} world={} local={} type={} "
                            + "identity={} {}",
                    carrier.shortId(), outcome.origin().toShortString(),
                    outcome.localPos().toShortString(),
                    outcome.slot() == null ? "-" : outcome.slot().type,
                    identity.describe(), index.summary(carrier));
        }
        if (outcome.performed()) {
            // The client has to stop drawing the component at the position it was told about, and
            // start drawing it inside the structure. The two halves are one statement each and the
            // order between them does not matter over the wire, but both have to happen: the carrier
            // payload moves the host into the structure, and this suppression takes it off the world
            // path. Without the suppression a client that was told "this block holds redstone" when
            // the component was placed keeps drawing a shell over the block the structure has just
            // removed - the "ghost component standing where the machine used to be".
            suppressWorldHost(level, outcome.origin());
            broadcastCarrier(level, carrier, index, poseOf(level, carrier));
        }
    }

    /**
     * <b>The world side of a landing: tell the client this position is a world host again.</b>
     *
     * <p>The carrier message that accompanies a release says "this host no longer rides me", and the
     * ordinary host sync is what says "and it is standing here now". The second half was missing from
     * the transfer, and the failure it produced is the one this round exists to close: the component
     * was back in the server's world store (so {@code /rc dump} and the break-the-block drop both
     * found it) while the client's own copy had gone out with the carrier, leaving the host drawn by
     * neither path - invisible until something else happened to touch that position.
     *
     * <p><b>Only sent when the server really has the component there.</b> A release that ended
     * {@code HELD} (neither the landing position nor the origin was free) leaves the data riding, and
     * announcing a world host then would be a lie the next carrier message would have to undo.
     *
     * <p><b>Idempotent, and deliberately so.</b> {@code stopMoving} fires four or five times for one
     * motion, but every call after the first is answered {@code ALREADY_RELEASED} with {@code moved=0}
     * and never reaches this method; and the announce itself is a pure function of the store, so
     * running it twice for one position (two hosts released onto one block) sends the same message
     * rather than a wrong one. One packet is the correct price for never leaving a component
     * undrawable.
     *
     * <p>Ordered by the caller to leave the client in a state that is correct under <em>any</em>
     * interleaving: this announcement, then the carrier message whose absence of hosts is the
     * "no longer riding" half. The client that has heard both draws the component at the world
     * position; a client that has heard only one of them falls back to the world path, which is the
     * old behaviour rather than a new defect.
     *
     * @param worldPos where the host has just been put down
     * @return the slot that was announced, or {@code null} when there was nothing there to announce
     */
    @Nullable
    private static Slot publishWorldHost(ServerLevel level, @Nullable BlockPos worldPos) {
        if (worldPos == null) {
            return null;
        }
        CarriedHostIndex.SlotWorld slots = CarriedHosts.slots(level);
        if (!WorldHostTransfer.restoresHost(slots, worldPos)) {
            // Nothing in the server's own store at that position: the release did not land here (a
            // HELD outcome, or a landing position the structure's transform named but could not use).
            return null;
        }
        Slot slot = slots.get(worldPos);
        HostSync.broadcast(level, worldPos, slot);
        CarrierTrace.noteHostRestored(level.dimension());
        if (RCConfig.debugLog()) {
            RCConfig.LOGGER.info("[redstonecircuit] restored host {} at {} (a structure put it down "
                            + "here) -> broadcast to the client so it is drawn on the world path again",
                    slot == null ? "-" : slot.type, worldPos.toShortString());
        }
        return slot;
    }

    /**
     * <b>The world side of a pick-up: tell the client this position is no longer a world host.</b>
     *
     * <p>The mirror of {@link #publishWorldHost}. A component that is riding a structure is not at the
     * block the structure took it from, so a client that still believes that block holds redstone
     * draws a ghost there for the whole flight - and, because the block-removal path was the only
     * thing that ever corrected it, keeps drawing it until the player breaks the block.
     *
     * <p>Sent only when the server's own store really has nothing there any more, which is exactly the
     * case when the claim moved the data out. Idempotent in effect: a repeated claim is answered
     * {@code already-carried} by the index and never reaches this method, and a position that has been
     * re-stored with a component is deliberately left alone.
     *
     * @return true when a suppression actually went out
     */
    private static boolean suppressWorldHost(ServerLevel level, @Nullable BlockPos worldPos) {
        if (!WorldHostTransfer.suppressesWorldHost(CarriedHosts.slots(level), worldPos)) {
            // Either there is no position to talk about, or somebody re-stored a component here
            // between the claim and now; that is a world host and must not be suppressed.
            return false;
        }
        HostSync.broadcastRemoval(level, worldPos);
        CarrierTrace.noteWorldHostSuppressed(level.dimension());
        if (RCConfig.debugLog()) {
            RCConfig.LOGGER.info("[redstonecircuit] world host at {} suppressed (a structure picked it "
                            + "up) -> broadcast to the client so no ghost is drawn where it used to be",
                    worldPos.toShortString());
        }
        return true;
    }

    /**
     * <b>Phase two: re-label a provisional claim onto the contraption entity that has just appeared.</b>
     *
     * <p>The invariant this protects is the one the defect broke: a host that was claimed stays
     * claimed. When a structure's entity turns out to have a different UUID than the provisional
     * identity the claim was filed under, the index is re-keyed and the clients are told, in that
     * order and in the same call, so no frame is ever drawn from a carrier the server no longer has.
     *
     * <p>Called from the per-actor tick and from the put-down path, both of which run after assembly
     * has attached the entity. A failure here is logged and counted, never swallowed: the claim
     * survives under the provisional id, which is exactly what the dual-path put-down match is for.
     *
     * @param current the carrier id the caller has been using
     * @param entityUuid the entity that now exists, or {@code null} when it still does not
     * @return the identity to carry on with - the same one when nothing had to change
     */
    public static CarrierIdentity.Identity reconcile(ServerLevel level, CarrierId current,
                                                     @Nullable UUID entityUuid) {
        return reconcile(level, current, entityUuid, null);
    }

    /**
     * The same, with the caller stating which phase the claim it is holding belongs to.
     *
     * <p>Needed because a caller can hold a bare {@link CarrierId} without the
     * {@link CarrierIdentity.Identity} that came with it - the per-actor and put-down callbacks do,
     * since all they have is a contraption object. The phase cannot be recovered from the id alone when
     * the caller has already substituted a real entity UUID for a provisional one, and guessing it
     * wrong would turn a reconciliation into a silent no-op. So the compat half, which does know, says
     * so.
     *
     * @param phase {@code PROVISIONAL} when {@code current} is a claim that still needs settling,
     *     {@code null} to infer it from the UUID's own version bits
     */
    public static CarrierIdentity.Identity reconcile(ServerLevel level, CarrierId current,
                                                     @Nullable UUID entityUuid,
                                                     @Nullable CarrierIdentity.Phase phase) {
        if (level == null || current == null) {
            return CarrierIdentity.Identity.ofProvisional(
                    current == null ? new UUID(0L, 0L) : current.uuid());
        }
        CarrierIdentity.Phase effective = phase != null ? phase
                : (CarrierIdentity.isProvisional(current) ? CarrierIdentity.Phase.PROVISIONAL
                        : CarrierIdentity.Phase.ENTITY);
        CarriedHostIndex index = indexOf(level);
        CarrierIdentity.Identity claim = new CarrierIdentity.Identity(current, effective);
        CarrierIdentity.Identity present = index == null || index.hostsOf(current).isEmpty()
                ? null
                : claim;
        CarrierIdentity.Resolution resolution = CarrierIdentity.resolve(claim, present, entityUuid);
        if (!resolution.rekeys()) {
            return resolution.claim();
        }
        try {
            int moved = index.rekey(resolution.present().carrier(), resolution.claim().carrier());
            CarrierTrace.noteReconcile(level.dimension(), moved > 0);
            CarriedHosts.get(level).setDirty();
            RCConfig.LOGGER.info("[redstonecircuit] carrier reconcile {} -> {} moved={} {}",
                    resolution.present().carrier().shortId(), resolution.claim().carrier().shortId(),
                    moved, index.summary(resolution.claim().carrier()));
            // The client was told about the provisional carrier; say that it is the same structure
            // under its real name, at the same pose and with the same hosts. Without this the client
            // would keep a carrier the server no longer answers a pose for.
            broadcastCarrier(level, resolution.present().carrier(), index, null);
            broadcastCarrier(level, resolution.claim().carrier(), index,
                    poseOf(level, resolution.claim().carrier()));
            // A re-key that moved the last host out of the provisional carrier has removed a carrier
            // from the index as surely as a release has: the name is gone and no pose will ever be
            // answered for it again. Saying so here keeps the rule "nothing leaves the index without
            // the client being told" true on this path too.
            if (index.hostsOf(resolution.present().carrier()).isEmpty()) {
                forget(level, resolution.present().carrier(),
                        "re-keyed onto " + resolution.claim().carrier().shortId());
            }
        } catch (RuntimeException failed) {
            CarrierTrace.noteReconcileFailure(level.dimension());
            RCConfig.LOGGER.warn("[redstonecircuit] carrier reconcile {} -> {} FAILED; the claim stays "
                            + "under the provisional id and the put-down path will match it",
                    resolution.present().carrier().shortId(), resolution.claim().carrier().shortId(),
                    failed);
        }
        return resolution.claim();
    }

    /**
     * One block reports that it is stopping.
     *
     * <p>Idempotent by construction - see {@link CarriedHostIndex#release} - because Create fires this
     * callback four to five times for a single motion, all with identical arguments.
     *
     * <h2>Dual-path matching</h2>
     * This is the <b>second lookup path</b>. {@code entityUuid} is what the contraption entity says
     * now; {@code fallback} is the identity the claim was made under when there was no entity to ask,
     * or {@code null} when the caller has no such identity (the Sable path). The release is attempted
     * under the entity's UUID first and, only when that finds nothing, under the fallback. So a
     * structure that came apart before it ever had an entity still gets its components back, and a
     * caller that does know the entity is unaffected.
     *
     * @param entityUuid the contraption entity's UUID, or {@code null} when there is none
     * @param fallback the provisional identity the claim may have been made under, or {@code null}
     * @param localPos the block's coordinate inside the structure
     * @return what happened, for the caller's log line and for the tests
     */
    public static CarriedHostIndex.ReleaseOutcome stopMoving(ServerLevel level, @Nullable UUID entityUuid,
                                                             @Nullable CarrierId fallback,
                                                             CarrierKind kind, BlockPos localPos) {
        CarriedHostIndex index = indexOf(level);
        if (entityUuid == null && fallback == null) {
            return new CarriedHostIndex.ReleaseOutcome(CarriedHostIndex.ReleaseStatus.UNKNOWN_CARRIER,
                    null, localPos, null, 0, 0, 0, 0);
        }
        // Try the entity first: after a successful reconciliation it is the only name the index knows.
        CarriedHostIndex.ReleaseOutcome underEntity = null;
        if (entityUuid != null) {
            underEntity = release(level, CarrierId.contraption(entityUuid), localPos);
            if (underEntity.status() != CarriedHostIndex.ReleaseStatus.UNKNOWN_CARRIER) {
                return underEntity;
            }
        }
        if (fallback == null) {
            return underEntity;
        }
        // The dual path: the claim never made it onto the entity, so it is still filed under the
        // provisional id. Releasing there is the honest answer, and the log says so.
        CarriedHostIndex.ReleaseOutcome underFallback = release(level, fallback, localPos);
        if (underFallback.status() != CarriedHostIndex.ReleaseStatus.UNKNOWN_CARRIER) {
            RCConfig.LOGGER.info("[redstonecircuit] carrier putdown matched the provisional identity {} "
                            + "(the entity {} had nothing); {}",
                    fallback.shortId(), entityUuid == null ? "absent" : entityUuid, underFallback.describe());
            return underFallback;
        }
        // Neither name is known: report the entity's answer, which is the one a caller can act on.
        return underEntity != null ? underEntity : underFallback;
    }

    /**
     * The single-identity form, kept for the Sable path and for callers that already know the UUID.
     *
     * @param carrierUuid the contraption entity's UUID, or the sub-level's UUID
     * @param localPos the block's coordinate inside the structure
     * @return what happened, for the caller's log line and for the tests
     */
    public static CarriedHostIndex.ReleaseOutcome stopMoving(ServerLevel level, UUID carrierUuid,
                                                             CarrierKind kind, BlockPos localPos) {
        return release(level, new CarrierId(kind, carrierUuid), localPos);
    }

    // ------------------------------------------------ stopMoving is not a put-down --

    /**
     * The deferrals waiting on evidence, per dimension. See {@link PendingRelease}.
     *
     * <p>Keyed by the level's dimension rather than by the {@code ServerLevel} so the book itself needs no
     * game to be driven - which is what lets its idempotence be a unit test.
     */
    private static final Map<ResourceKey<Level>, PendingRelease.Book> DEFERRED = new java.util.HashMap<>();

    /** The deferrals of one level. */
    private static PendingRelease.Book deferred(ServerLevel level) {
        return DEFERRED.computeIfAbsent(level.dimension(), ignored -> new PendingRelease.Book());
    }

    /** How many deferrals one level is holding; for the diagnostics. */
    public static int deferredCount(ServerLevel level) {
        PendingRelease.Book book = DEFERRED.get(level.dimension());
        return book == null ? 0 : book.size();
    }

    /** Forgets one dimension's deferrals; called when the level unloads. */
    public static void forgetDeferred(ResourceKey<Level> dimension) {
        PendingRelease.Book book = DEFERRED.remove(dimension);
        if (book != null) {
            book.clear();
        }
    }

    /**
     * <b>{@code stopMoving}, with the release deferred until the world says it really stopped.</b>
     *
     * <p>Create calls {@code MovementBehaviour#stopMoving} whenever an actor stands down, which includes
     * a direction change: the author's bearing reversed at full speed, this callback fired, the host was
     * released on the spot, and the component vanished off a block that was still {@code AIR} in the world
     * with the contraption entity still present. Those two facts are the whole of the fix - a release now
     * needs positive evidence, and any sign of continued motion cancels it.
     *
     * <p>What this method does <em>not</em> change: a real put-down. The moment the host's world block is
     * back, the release happens as it always did, and lands on that block.
     *
     * @param worldBlock the host's own world position - {@code anchor + localPos}, the block it came from
     *     and the block a put-down puts back
     * @return the same shape as {@link #stopMoving}, with a deferred answer reported as
     *     {@code UNKNOWN_CARRIER} plus a log line, because nothing was released
     */
    public static CarriedHostIndex.ReleaseOutcome stopMovingOrDefer(ServerLevel level,
                                                                    @Nullable UUID entityUuid,
                                                                    @Nullable CarrierId fallback,
                                                                    CarrierKind kind, BlockPos localPos,
                                                                    BlockPos worldBlock) {
        CarrierId carrier = entityUuid != null ? new CarrierId(kind, entityUuid) : fallback;
        if (carrier == null) {
            return new CarriedHostIndex.ReleaseOutcome(CarriedHostIndex.ReleaseStatus.UNKNOWN_CARRIER,
                    null, localPos, null, 0, 0, 0, 0);
        }
        PendingRelease.Evidence evidence = evidenceFor(level, carrier, worldBlock, 0L);
        PendingRelease.Action action = PendingRelease.decide(evidence);
        if (!action.releases()) {
            boolean fresh = deferred(level).defer(carrier, localPos, worldBlock, level.getGameTime());
            RCConfig.LOGGER.info("[redstonecircuit] {}", PendingRelease.describe(action, evidence,
                    carrier.shortId()));
            if (fresh) {
                CarrierTrace.noteDeferredRelease(level.dimension());
            }
            // Nothing is released, so there is no landing to report. The caller logs this as a deferral
            // rather than as a put-down; `UNKNOWN_CARRIER` here means "no release happened", which is the
            // honest answer and keeps the idempotence bookkeeping unchanged.
            return new CarriedHostIndex.ReleaseOutcome(CarriedHostIndex.ReleaseStatus.UNKNOWN_CARRIER,
                    null, localPos, null, 0, 0, 0, 0);
        }
        // The evidence already says it is over: release through the ordinary path, which asks the pose
        // and lands correctly, and forget any deferral this carrier was holding.
        deferred(level).cancel(carrier);
        return stopMoving(level, entityUuid, fallback, kind, localPos);
    }

    /**
     * One tick of the deferral ledger: every host whose structure asked to stand down is re-examined.
     *
     * <p>This is where the three release conditions are actually observed - the world block, the entity's
     * presence, and the grace - and where "still being driven" is turned into a cancellation. Called from
     * {@link #tick(ServerLevel)} once per level tick, so it costs one walk over the deferrals (usually
     * none) and nothing else.
     */
    private static void tickDeferred(ServerLevel level) {
        PendingRelease.Book book = DEFERRED.get(level.dimension());
        if (book == null || book.isEmpty()) {
            return;
        }
        for (PendingRelease.Book.Entry entry : book.entries()) {
            CarrierId carrier = entry.carrier();
            boolean blockBack = !level.getBlockState(entry.worldBlock()).isAir();
            boolean alive = structureIsAlive(carrier);
            boolean entityGone = entityAbsent(level, carrier);
            boolean poseSilent = poseOf(level, carrier) == null;
            // "Positively gone" needs all three, and it is spelled out rather than inferred: an entity
            // that is merely not loaded, or a pose query that cannot name a live structure, must not
            // count as death. That mistake is already in this file's history - see `sweepOrphans`.
            boolean gone = entityGone && poseSilent && !alive;

            PendingRelease.Book.Entry next = entry.next(blockBack, gone, alive);
            PendingRelease.Evidence evidence = next.evidence();
            PendingRelease.Action action = PendingRelease.decide(evidence);
            if (action == PendingRelease.Action.DEFER) {
                book.settle(entry, next);
                continue;
            }
            book.settle(entry, null);
            RCConfig.LOGGER.info("[redstonecircuit] {}", PendingRelease.describe(action, evidence,
                    carrier.shortId()));
            if (action.releases()) {
                release(level, carrier, entry.localPos());
                SENT_POSES.remove(carrier.uuid());
                SENT_POSE_TICKS.remove(carrier.uuid());
                CarrierTrace.noteDeferredReleaseSettled(level.dimension());
            }
        }
    }

    /**
     * Whether a carrier's structure is <b>positively</b> absent: its entity is not in the level.
     *
     * <p>Only asked for a Create contraption, whose identity is its entity's UUID. A Sable sub-level
     * answers through {@link #structureIsAlive} instead, and a provisional identity has no entity by
     * design - which is why "no entity" cannot be the whole test. See {@link #tickDeferred}.
     */
    private static boolean entityAbsent(ServerLevel level, CarrierId carrier) {
        if (carrier.isSubLevel()) {
            return false;
        }
        try {
            return level.getEntity(carrier.uuid()) == null;
        } catch (RuntimeException | LinkageError failed) {
            return false;
        }
    }

    /** The evidence one host's release would be decided on right now; see {@link PendingRelease}. */
    private static PendingRelease.Evidence evidenceFor(ServerLevel level, CarrierId carrier,
                                                       BlockPos worldBlock, long ticksPending) {
        boolean blockBack = !level.getBlockState(worldBlock).isAir();
        boolean alive = structureIsAlive(carrier);
        boolean entityGone = entityAbsent(level, carrier);
        boolean poseSilent = poseOf(level, carrier) == null;
        return new PendingRelease.Evidence(blockBack, entityGone && poseSilent && !alive, !poseSilent,
                false, alive, ticksPending);
    }

    /**
     * Tells the deferral ledger that a pose arrived for this carrier, which cancels its deferrals.
     *
     * <p>A structure that is still being given a pose has not stopped, whatever {@code stopMoving} said.
     * Returns the entries that were cancelled so a caller can log one line per carrier rather than one per
     * host.
     */
    public static int notePoseArrived(ServerLevel level, CarrierId carrier) {
        PendingRelease.Book book = DEFERRED.get(level.dimension());
        if (book == null || book.isEmpty()) {
            return 0;
        }
        int cancelled = book.cancel(carrier);
        if (cancelled > 0) {
            RCConfig.LOGGER.info("[redstonecircuit] carrier {}: a pose arrived after stopMoving - {} "
                    + "deferred release(s) CANCELLED, the structure is still being driven",
                    carrier.shortId(), cancelled);
            CarrierTrace.noteDeferredReleaseCancelled(level.dimension());
        }
        return cancelled;
    }

    /**
     * The same for Create's own actor tick, which is the other half of "it is still moving".
     *
     * <p>{@code MovementBehaviour#tick} runs once per actor per engine tick while the actor is active, so
     * a structure that fires {@code stopMoving} and then keeps ticking has demonstrably not stopped.
     */
    public static int noteActorTicked(ServerLevel level, CarrierId carrier) {
        PendingRelease.Book book = DEFERRED.get(level.dimension());
        if (book == null || book.isEmpty()) {
            return 0;
        }
        int touched = book.noteActorTick(carrier);
        if (touched > 0) {
            RCConfig.LOGGER.info("[redstonecircuit] carrier {}: the actor ticked after stopMoving - {} "
                    + "deferred release(s) CANCELLED, the structure is still moving",
                    carrier.shortId(), touched);
            CarrierTrace.noteDeferredReleaseCancelled(level.dimension());
        }
        return touched;
    }

    /** One line for {@code /rc carrier}: what is waiting on evidence. */
    public static String deferredSummary(ServerLevel level) {
        PendingRelease.Book book = DEFERRED.get(level.dimension());
        if (book == null || book.isEmpty()) {
            return "deferredReleases=0";
        }
        return "deferredReleases=" + book.size() + " carriers=" + book.carriers().size();
    }

    /** One release, under one identity. */
    private static CarriedHostIndex.ReleaseOutcome release(ServerLevel level, CarrierId carrier,
                                                           BlockPos localPos) {
        CarriedHostIndex index = indexOf(level);
        if (index == null) {
            return new CarriedHostIndex.ReleaseOutcome(CarriedHostIndex.ReleaseStatus.UNKNOWN_CARRIER,
                    null, localPos, null, 0, 0, 0, 0);
        }
        // WHERE THIS HOST LANDS. The live pose is the better answer and the first one asked, for the
        // reason the Sable path's own comment gives: a structure that was taken apart somewhere else
        // must not send its components home to the field it was built in. But a live pose is not always
        // available at the instant of a release - see `landingFor` - and when it is not, the pose the
        // client is *still drawing from* is the honest next answer rather than the block the component
        // was picked up from.
        CarrierPose pose = landingPose(level, carrier);
        CarriedHostIndex.Landing landing = landingFor(pose);
        // Read what is riding there before the release, so the "the world has it again now" packet can
        // carry the contents rather than making the client wait for the next ordinary update.
        Slot slot = index.slotAt(localPos, CarriedHosts.slots(level));
        CarriedHostIndex.ReleaseOutcome outcome =
                index.release(carrier, localPos, landing, CarriedHosts.slots(level));
        if (outcome.wrote()) {
            CarriedHosts.get(level).setDirty();
            // THE SEQUENCE IS THE FIX, and it is decided in one pure place so it can be tested without
            // a game: see `WorldHostTransfer#broadcastsFor`. In words:
            //
            //   1. the world host at the landing position, so the client knows the component is
            //      standing somewhere before it is told the carrier is gone;
            //   2. the old position's removal, when the structure drifted, so a client one packet
            //      behind cannot draw the component in two places at once;
            //   3. the carrier message, whose absent host is the "no longer riding" half.
            //
            // The client survives any interleaving (a host that is neither carried nor a world host is
            // drawn by the landed path - see `LandedHosts`), but this order is the one that never
            // leaves a frame with no way to draw the component at all.
            List<WorldHostTransfer.Broadcast> plan = WorldHostTransfer.broadcastsFor(
                    outcome.landing(), outcome.origin(), CarriedHosts.slots(level));
            for (WorldHostTransfer.Broadcast step : plan) {
                switch (step) {
                    case RESTORE_WORLD_HOST -> {
                        Slot announced = publishWorldHost(level, outcome.landing());
                        if (announced == null && outcome.landing() != null) {
                            // The release wrote somewhere the server's store does not report - a HELD
                            // outcome (nothing was written at all) or a position somebody else re-stored
                            // in the same tick. The contents are still in hand, so the client is told
                            // about them directly: an announcement that disagrees with the store is
                            // corrected by the next solve, while a component with no world host at all
                            // can never be drawn.
                            HostSync.broadcast(level, outcome.landing(), slot);
                        }
                    }
                    case DROP_OLD_WORLD_HOST -> HostSync.broadcastRemoval(level, outcome.origin());
                    case CARRIER -> broadcastCarrier(level, carrier, index, pose);
                }
            }
            if (outcome.landing() != null && !outcome.landing().equals(outcome.origin())) {
                // Belt and braces for the one pair of facts that must not both be true at the client:
                // the component is a world host at the landing position, so the position it came from
                // must not be one any more. The plan above already sends this when it could announce
                // the landing position at all; this covers the case where the announcement had to be
                // made from the slot in hand because the store did not report it yet. Sending it twice
                // is one redundant packet; not sending it is a component drawn in two places.
                HostSync.broadcastRemoval(level, outcome.origin());
            }
        } else if (outcome.status() != CarriedHostIndex.ReleaseStatus.UNKNOWN_CARRIER
                && outcome.status() != CarriedHostIndex.ReleaseStatus.NOT_CARRIED) {
            // THE IDEMPOTENT CASE THAT STILL HAS TO TALK. `moved=0` means the index did not change -
            // the ordinary shape of Create's four-to-five repeated put-down callbacks - but "nothing
            // changed" is not "the client knows". A repeat is exactly the moment at which the server
            // can notice that it has stopped being able to answer a pose for this carrier (a structure
            // that has already been torn down), and re-sending the carrier payload is what turns that
            // into a removal on the client: a payload with no pose IS the removal message. One packet
            // per repeated callback, which is a handful per disassembly.
            broadcastCarrier(level, carrier, index, pose);
        }
        RCConfig.LOGGER.info("[redstonecircuit] carrier {} putdown {} {} lastPose={}",
                carrier.shortId(), carrier.isContraption() ? "contraption" : "sub-level",
                outcome.describe(), pose == null ? "none" : pose.describe());
        return outcome;
    }

    /**
     * <b>The single exit: a carrier is going away, so the client has to be told.</b>
     *
     * <p>Every path that takes a carrier out of the index ends here - a wholesale release, a re-key that
     * moved the last host out, the orphan sweep, a removed sub-level, a finished structure being reaped,
     * a level unloading - and this method is the only place that decides whether the client hears about
     * it. Before this existed, "remove the carrier" and "tell the client" were two different statements
     * that each caller had to remember to write, and a caller that wrote only the first left a
     * component permanently invisible: the client still believed the block was riding a carrier, so it
     * was not drawn on the world path, and the server no longer had a pose for that carrier, so it was
     * not drawn inside the structure either.
     *
     * <p><b>Idempotent in the data, not in the notice.</b> Calling this twice for one carrier does not
     * write anything twice - there is nothing left to write - but it does announce again, because the
     * message is what the client acts on and a dropped packet must not be able to strand a component.
     * The announcement is one small packet, bounded by the number of removal paths that fire (a handful
     * per disassembly), so the cost of never being wrong about this is negligible.
     *
     * <p>The carrier is dropped under <b>both</b> of its possible identities. A structure that was
     * re-keyed mid-flight can still be named by the provisional id in a stale record, and a client that
     * was told the provisional name would otherwise keep it forever.
     *
     * @param reason what happened, for the log line; {@code null} is allowed
     * @return how many clients-facing notices went out, for the diagnostics
     */
    public static int forget(ServerLevel level, CarrierId carrier, @Nullable String reason) {
        if (level == null || carrier == null) {
            return 0;
        }
        SENT_POSES.remove(carrier.uuid());
        SENT_POSE_TICKS.remove(carrier.uuid());
        return announceRemoval(level, carrier, reason);
    }

    /**
     * Tells every client in the dimension that this carrier is not there any more.
     *
     * <p>The message is a carrier payload with no pose and no hosts, which is what the client already
     * understands as "drop this carrier and everything I said was riding it".
     */
    private static int announceRemoval(ServerLevel level, CarrierId carrier, @Nullable String reason) {
        String dimension = level.dimension().location().toString();
        if (!REMOVALS.owesRemoval(dimension, carrier)) {
            // Nothing was ever put on the wire for this carrier, so there is nothing to take back.
            // Counted rather than ignored: "the removal was suppressed because the client never knew"
            // and "the removal was forgotten" look identical in a log otherwise.
            REMOVALS.noteRemovalSuppressed();
            return 0;
        }
        HostSync.broadcastCarrier(level, carrier, indexOf(level), null);
        REMOVALS.noteRemovalSent();
        if (reason != null && RCConfig.debugLog()) {
            RCConfig.LOGGER.info("[redstonecircuit] carrier {} removal announced to the client ({})",
                    carrier.shortId(), reason);
        }
        return 1;
    }

    /**
     * Puts everything one structure is holding back where it came from.
     *
     * <p>The failure path. A structure that comes apart without ever calling {@code stopMoving} - the
     * world saved and reloaded in mid-flight, a machine that was broken while it was away - would
     * otherwise leave its components carried forever, which is exactly the "数据不丢" half of the P1
     * promise going wrong. Releasing to the recorded positions is always available and always safe.
     */
    public static int releaseAll(ServerLevel level, CarrierId carrier, @Nullable String reason) {
        return releaseAll(level, carrier, reason, null);
    }

    /**
     * The same, with the caller naming where each host belongs.
     *
     * <p>Needed because "the structure has gone" is not one situation. A Create contraption that stops
     * has called {@code stopMoving} and been released per block by then. A <b>Sable sub-level</b> that
     * is removed - or taken apart - has no per-block callback at all, so this is the only release it
     * gets, and at that moment its pose <em>is</em> readable: the structure knows where its blocks are,
     * and those blocks are where the components have to land. Passing {@code null} keeps the recorded
     * origins, which is the honest answer for a structure that has already been unloaded.
     *
     * <p>Both paths go through the same three steps - write the world entries, publish each landing
     * position as a world host, and take the carrier back from every client - because a second release
     * implementation is how "one path told the client and the other did not" happens.
     *
     * @param landing where the structure's own transform says each host belongs, or {@code null} to
     *     use the positions the hosts came from
     */
    public static int releaseAll(ServerLevel level, CarrierId carrier, @Nullable String reason,
                                 @Nullable CarriedHostIndex.Landing landing) {
        CarriedHostIndex index = indexOf(level);
        if (index == null) {
            return 0;
        }
        // A live structure's own transform is the better answer than the recorded origin when it can
        // be had - a sub-level that was taken apart somewhere else must not send its components home to
        // the field it was built in. When there is no transform, the origin is what is left, and it is
        // always safe: see `publishWorldHost`.
        //
        // AND WHEN THE CALLER COULD NOT ASK THE STRUCTURE, THE LAST POSE IT SENT IS THE ANSWER.
        //
        // The caller is the Sable removal hook, and the author's log is the proof that this matters:
        // `carrier s:98bef0d9 released wholesale (sable sub-level removed (REMOVED)) LANDED
        // origin=2775,77,-1395 local=20481032,127,20524040 landing=2775,77,-1395 moved=1` - the
        // structure had already been taken out of the container by the time this ran, so its pose was
        // gone, the landing fell back to the origin, and a torch that had been flown two blocks away
        // was written back to the block the machine had picked it up from, four and a half seconds
        // earlier. The last pose the server broadcast went out moments before and says `2773.512,
        // 78.083, -1397.505` - i.e. block `2773,77,-1397`, which is where the structure actually was.
        //
        // The rule this encodes: the origin is the fallback for a structure whose position was NEVER
        // known, not for one whose position stopped being knowable a moment ago.
        if (landing == null) {
            CarrierPose last = lastSentPose(carrier);
            landing = landingOnRelease(null, last);
            if (landing != null) {
                RCConfig.LOGGER.info("[redstonecircuit] carrier {} has no live pose to land on; using "
                        + "the last pose the client was told ({}) so its hosts come down where the "
                        + "structure actually is", carrier.shortId(), last.describe());
            }
        }
        CarriedHostIndex.ReleaseOutcome outcome =
                index.releaseAll(carrier, landing, CarriedHosts.slots(level));
        if (outcome.moved() > 0) {
            CarriedHosts.get(level).setDirty();
            // The world half first, and once per host: each one is a world host again at the position
            // it came home to, and the client has to be told before the carrier message below takes
            // the carrier away. See `publishWorldHost`.
            for (CarriedHost host : index.hostsOf(carrier)) {
                if (host.landed()) {
                    publishWorldHost(level, host.at());
                }
            }
        }
        // Unconditionally, and through the single exit: whether anything moved or not, the client may
        // be holding this carrier, and the client is the only thing that can stop drawing it.
        int announced = forget(level, carrier, reason);
        // A pick is a half-finished gesture about one structure's hosts, so a structure that has been
        // taken apart cannot have one pointing at it any more.
        clearCarrierSelection(carrier);
        // A selection is a half-finished gesture about one structure's hosts, so a structure that has
        // been taken apart cannot have one pointing at it any more.
        CarriedSelection.clearCarrier(carrier);
        RCConfig.LOGGER.info("[redstonecircuit] carrier {} released wholesale ({}) {} announced={}",
                carrier.shortId(), reason == null ? "no live structure" : reason, outcome.describe(),
                announced);
        return outcome.moved();
    }

    // ----------------------------------------------------------------- ticking --

    /**
     * Once per level tick: send the poses that changed, rescue the hosts of structures that died, and
     * (P2) recompute the redstone inside every structure that is holding something, and (P4) tell the
     * world where those structures now are.
     *
     * <p>All of it is deliberately cheap when nothing is happening. The pose pass walks the carriers
     * that are holding something - a handful - the sweep runs one tick in {@value #SWEEP_INTERVAL},
     * because "did this structure vanish" is not a question that has to be answered sixty times a
     * second, and the solve is the same shape: a handful of carriers, then each carrier's own hosts,
     * never the level-wide store. P4's two extra jobs are the same shape again: the projection is
     * filled in the pose pass it already makes, and the notification pass walks only the coordinates
     * that actually moved or turned.
     */
    public static void tick(ServerLevel level) {
        if (!active) {
            return;
        }
        CarriedHostIndex index = indexOf(level);
        if (index == null || index.isEmpty()) {
            // Nothing is riding: make sure the world-facing table says so, so a stale projection
            // cannot keep answering after the last machine has gone.
            PROJECTION.clear(level.dimension());
            return;
        }

        ResourceKey<Level> dimension = level.dimension();
        boolean bridged = !RCConfig.carrierFreezeWorldIo();
        // TWO-PHASE IDENTITY, phase two: a claim made before the contraption entity existed is
        // re-labelled onto it here, on the first tick after the entity appears. This runs before
        // anything that reads a pose, because a provisional carrier has no pose to read - which is what
        // would otherwise leave a claimed host invisible on the client for the whole flight.
        reconcileProvisional(level, index);
        // P4: rebuild "where is every carried host right now" for this tick. It has to be current
        // *before* the solve, because the solve reads the world and the world may ask about one of
        // these positions while it does. The previous table is kept, so the notification pass after
        // the solve can tell what moved without a second walk over the poses.
        PROJECTION.begin(dimension, bridged);

        // P1.20: the self-healing sweep, and it runs BEFORE the pose pass on purpose - it needs the
        // table only to project a drop position, and it must see the index this tick with the same
        // records the solve is about to read. Bounded by carrierSelfHealCheckPerTick (0 switches it
        // off) and rotated by a per-carrier cursor, so the author's larger machines are swept over
        // several ticks rather than in one. See pollDestroyedHosts.
        pollForDestroyedHosts(level);

        CarrierSource carrierSource = source;
        if (carrierSource != null) {
            // The heartbeat, decided once for the whole pass: every carrier that is holding something
            // gets its pose re-sent at this interval even when it has not moved. See the loop below.
            boolean heartbeat = RCConfig.carrierPoseHeartbeatTicks() > 0
                    && level.getGameTime() % RCConfig.carrierPoseHeartbeatTicks() == 0;
            int sampleInterval = RCConfig.carrierPoseSampleTicks();
            for (CarrierId carrier : index.carriers()) {
                List<CarriedHost> live = index.liveHostsOf(carrier);
                if (live.isEmpty()) {
                    continue;
                }
                CarrierPose pose = carrierSource.poseOf(level, carrier);
                CarrierPose last = SENT_POSES.get(carrier.uuid());
                if (pose == null) {
                    continue; // The sweep below deals with a structure that is really gone.
                }
                // BOTH CLOCKS ARE CONSULTED, AND EITHER ONE IS ENOUGH.
                //
                // `changed` is "the structure is not where the client was last told it is". Since
                // CarrierPose#closeTo compares the orientation as well as the position, a bearing that
                // is only turning counts as a change - which is the half that was missing, and the
                // author's report was exactly that case.
                //
                // The sampling interval is the belt to that braces: two level ticks, by default. It
                // exists because a moving structure's rotation can be written by something other than
                // this pass (Create's own actor tick, Sable's physics step), so "the pose changed
                // between two of *my* observations" is not the same statement as "the client has been
                // told what it is now". Sending at ten updates a second while a carrier is holding
                // something bounds how long a client can be drawing an old angle, whatever the change
                // detection does, and it costs a handful of bytes per carrier. It is deliberately
                // skipped when nothing is riding (the `live.isEmpty()` above), so an empty carrier
                // stays completely silent.
                boolean changed = !pose.closeTo(last);
                boolean sample = sampleInterval > 0 && level.getGameTime() % sampleInterval == 0;
                if (changed) {
                    broadcastPose(level, carrier, pose, true, false);
                    CarrierTrace.notePoseSent(dimension);
                } else if (heartbeat) {
                    // "It has not moved" and "it is still here" are two different facts, and a client
                    // that only ever hears the poses of *changes* cannot tell a parked machine from a
                    // machine that has gone away. The heartbeat is what makes the difference
                    // observable, and it is the reason the client is allowed to treat silence as
                    // absence (see CarrierIndex#expireStale). It is deliberately not counted as a
                    // "pose sent" in the three-stage trace, which is about change.
                    broadcastPose(level, carrier, pose, false, false);
                } else if (sample) {
                    // Neither a change nor the slow heartbeat: this is the case that keeps a turning
                    // structure's angle current. Counted as a pose sent, because that is what it is.
                    broadcastPose(level, carrier, pose, true, true);
                    CarrierTrace.notePoseSent(dimension);
                }
                if (bridged) {
                    project(dimension, carrier, pose, live);
                }
            }
        }

        // THE DEFERRAL LEDGER, every tick and not only on the sweep. A host whose structure asked to
        // stand down is re-examined here, so the release happens the moment the world block is back
        // rather than up to forty ticks later - and, more importantly, so a structure that is still being
        // driven cancels it. See `stopMovingOrDefer`.
        tickDeferred(level);

        if (level.getGameTime() % SWEEP_INTERVAL == 0) {
            sweepOrphans(level, index);            if (index.releasedCarrierCount() > 0) {                // THE ROW THAT USED TO GO MISSING. Reaping a finished carrier takes it out of the
                // index; before this round it stopped there, and a client that still held it was left
                // with a carrier no pose could be looked up for. Every reaped carrier is announced
                // through the single exit, so "it left the index" and "the client was told" are now
                // the same statement.
                for (CarrierId finished : index.forgetReleased()) {
                    forget(level, finished, "released (nothing left riding it)");
                }
                CarriedHosts.get(level).setDirty();
            }
        }

        if (RCConfig.carrierSolveEnabled()) {
            // The other half of P2: recompute what is inside each structure, in the structure's own
            // coordinates. It reads the index (a handful of carriers, then each carrier's own hosts)
            // and writes back through the same store the pick-up and put-down use.
            CarrierNetworks.tick(contextOf(level, index), Clock.of(level));
        }

        if (bridged) {
            // P4's outward half has to be *announced*, and after the solve so that what the world is
            // told is this tick's value rather than last tick's: a lamp only re-reads its neighbours
            // when something notifies it, and a structure moving past one changes no block at all. So
            // every position whose projection appeared, vanished, moved, changed value **or turned** is
            // woken up here - which is what makes "the machine lit the lamp it flew over" visible rather
            // than merely queryable, and what makes a signal come back after a structure has turned the
            // dead face of a torch away from something again.
            notifyProjectionChanges(level, dimension);
        }
    }

    /**
     * <b>The projection change pass: the world positions (P4) and the structures' own coordinates
     * (P1.36).</b>
     *
     * <h2>The world half is untouched</h2>
     * Every world position the walk reports is handed to {@link #notifyWorld}, one call each, in the
     * order the walk has always produced them. The only thing added beside it is the structure-frame
     * half, which is a second question about a second position and never replaces the first.
     *
     * <h2>The structure half, and why it is a different position</h2>
     * A host the machine is carrying stands at the world position it is projected onto <em>and</em> at
     * its own coordinate inside the machine - and for a Sable sub-level that second one is a real level
     * position (plot coordinate == level coordinate, P1.30 section 30.1.2). A vanilla device the
     * machine carries (the author's piston above the redstone) lives there, is ticked by the level, and
     * until this round was never told that the signal beside it had changed: it read zero as well,
     * because nothing answered a signal query at a plot coordinate either. Both halves are P1.36.
     *
     * <p>Bounded and coalesced: the pass is asked for once per level tick from
     * {@code SolverNotifications#structurePass} (which is subject to {@code solverCoalesceNotifications}
     * and to {@code carrierNotifyStructureNeighbours}), each changed host is offered once, and a
     * coordinate is announced at most once per tick however often it is offered. The work is
     * proportional to the hosts a structure carries, never to the level's store.
     */
    private static void notifyProjectionChanges(ServerLevel level, ResourceKey<Level> dimension) {
        // One line per carrier per pass, and only when the operator asked for lines: a machine turning
        // over twenty hosts must not print twenty lines about the same carrier.
        Set<CarrierId> lineWritten = RCConfig.debugLog() ? new HashSet<>() : null;
        SolverNotifications.StructurePass structures = SolverNotifications.structurePass(level,
                pos -> notifyStructureNow(level, pos, lineWritten));
        try {
            PROJECTION.forEachChange(dimension, pos -> notifyWorld(level, pos),
                    structures == null ? null : host -> structures.notify(host.localPos()),
                    CarrierStore::noteTurned);
        } catch (RuntimeException | LinkageError failed) {
            // The bridge is a convenience over the frozen behaviour, never a requirement: a
            // failure here closes the outward half and says so rather than taking the tick down.
            RCConfig.LOGGER.warn("[redstonecircuit] carrier bridge notification failed; the "
                    + "outward half stays closed until the next tick", failed);
            PROJECTION.begin(dimension, false);
        }
    }

    /**
     * Tells the neighbours of a host's <b>structure's own coordinate</b> that its output changed.
     *
     * <p>Read-only, like every other notification: the world store is never written at this coordinate
     * (that is the second source of truth sections 26-30 removed), no block is placed, and the only
     * thing that happens is the vanilla neighbour update the device there needs in order to re-read.
     *
     * <p>The component is taken from the projection table rather than the world store, because that is
     * where a carried host's contents live - and a diode among them still gets vanilla's second step
     * into the block in front of it, which is what makes "repeater, stone, lamp" work inside a machine
     * exactly as it does on the ground.
     *
     * <p><b>The same ownership guard as the read half</b> (P1.37): the coordinate is woken only when it
     * really is this host's own structure's block. A coordinate that belongs to another live sub-level is
     * that other structure's world position, and its neighbours are already woken by the world half of
     * this very pass - waking it here as well would announce one coordinate twice in one tick for a
     * question nobody asked.
     *
     * @param lineWritten one line per carrier per pass, or {@code null} when {@code debugLog} is off
     */
    private static void notifyStructureNow(ServerLevel level, BlockPos structurePos,
                                           @Nullable Set<CarrierId> lineWritten) {
        CarrierProjection.ProjectedHost host = PROJECTION.atStructureOwnedBy(level.dimension(),
                structurePos, plotOwnerOf(level, structurePos));
        if (host == null) {
            // Either the table moved under the pass (a structure put down in this very tick), or this
            // coordinate is another structure's block and the world half is the one that announces it.
            // Either way there is nothing to notify about and nothing to guess at.
            return;
        }
        if (lineWritten != null && lineWritten.add(host.carrier())) {
            RCConfig.LOGGER.info("[redstonecircuit] carrier {} host local={} out={} emits {}: the "
                            + "structure's OWN coordinate is notified as well, so a device of the "
                            + "machine's own blocks there re-reads and acts",
                    host.carrier().shortId(), structurePos.toShortString(), host.power(),
                    describeEmission(host.slot()));
        }
        InnerRedstoneNetwork.notifyNeighboursNow(level, structurePos, host.slot());
    }

    /**
     * Which way the component emits, as text, for the structure notice line.
     *
     * <p>Diagnostics only, and answered defensively: the line exists so the author can see at a glance
     * whether the coordinate that was woken is the one holding the device that has to move, and a
     * wording failure must never be the thing that stops the notification.
     */
    private static String describeEmission(@Nullable Slot slot) {
        if (slot == null) {
            return "nothing (the host is empty)";
        }
        if (slot.type.isDiode()) {
            return "towards " + slot.facing.getOpposite().getName();
        }
        return slot.type == ComponentType.TORCH ? "every side but " + slot.facing.getName()
                : "every side";
    }

    /** Puts one carrier's live hosts into this tick's projection table. */
    private static void project(ResourceKey<Level> dimension, CarrierId carrier, CarrierPose pose,
                                List<CarriedHost> live) {
        for (CarriedHost host : live) {
            Slot slot = host.slot();
            PROJECTION.put(dimension, new CarrierProjection.ProjectedHost(carrier, host.localPos(),
                    CarrierProjection.project(pose, host.localPos()), pose, slot,
                    slot == null ? 0 : slot.power));
        }
    }

    /**
     * The one place a level is turned into what a solve pass needs.
     *
     * <p>Exists so {@link #tick} and {@link #tickHost} cannot disagree about which index, which store
     * accessor and which structure reader a pass runs against - a disagreement there would show up as
     * a component that solves in one route and not the other.
     */
    static TickContext contextOf(ServerLevel level, CarriedHostIndex index) {
        CarrierSource carrierSource = source;
        CarrierAccess access = carrierSource == null ? null : carrierSource.access();
        // P4: the bridge, or null for the frozen behaviour. Built here - and only here - so the two
        // ways of opening it (the config switch and "there is nothing to read the world with") are
        // decided in one place, and a solve pass can never half-open it.
        CarrierWorldBridge bridge = carrierSource == null || RCConfig.carrierFreezeWorldIo()
                ? null
                : new CarrierWorldBridge(carrier -> poseOf(level, carrier),
                        InnerRedstoneNetwork.worldSignals(level));
        return new TickContext(Clock.of(level), index, CarriedHosts.slots(level), access,
                carrier -> afterSolveWrote(level, carrier), bridge, level);
    }

    /**
     * One structure's solve pass: the entry point a per-actor callback uses.
     *
     * <p>Create calls {@code MovementBehaviour#tick} once per host block per contraption tick, which
     * is the natural place for a structure to be recomputed - but doing the work there would run one
     * full solve per host block of the machine. So the per-block callback answers one question only
     * ("is this block one of ours, and which carrier is it in") and the solve itself is done once for
     * the whole structure. That also keeps the cost proportional to the number of
     * <em>structures</em> rather than to the number of blocks in them.
     *
     * <p>The double-driver problem - this and the level tick both firing in one tick - is handled
     * inside {@link CarrierNetworks#tick}, which is a no-op for a dimension it has already solved at
     * this clock value. So this method is safe to call as often as a caller likes, and the two routes
     * can never disagree or do the work twice.
     */
    public static void tickHost(ServerLevel level, CarrierId carrier) {
        if (!active || !RCConfig.carrierSolveEnabled() || level == null || carrier == null) {
            return;
        }
        // Create's own per-actor tick reached this carrier, which is positive evidence that the structure
        // is still moving it - whatever a `stopMoving` callback may have said. See `noteActorTicked`.
        noteActorTicked(level, carrier);
        CarriedHostIndex index = indexOf(level);
        if (index == null || index.isEmpty() || index.liveHostsOf(carrier).isEmpty()) {
            return;
        }
        CarrierNetworks.tick(contextOf(level, index), Clock.of(level));
    }

    /**
     * A whole-level solve pass, without the pose sync or the sweep.
     *
     * <p>The entry point for a driver that knows "something moved" but not which structure - Sable's
     * physics tick, which is per system rather than per sub-level. The solve itself walks the index,
     * so it is the same work the level tick does and costs the same.
     */
    public static void tickCarriers(ServerLevel level) {
        if (!active || !RCConfig.carrierSolveEnabled()) {
            return;
        }
        CarriedHostIndex index = indexOf(level);
        if (index == null || index.isEmpty()) {
            return;
        }
        CarrierNetworks.tick(contextOf(level, index), Clock.of(level));
    }

    /** What the last solve pass over this level did, for {@code /rc carrier}. */
    public static CarrierNetworks.TickSummary lastSolve(ServerLevel level) {
        return CarrierNetworks.lastTick(level);
    }

    /**
     * After a solve wrote something back: mark the carried file dirty so a machine put down later
     * comes home with the value it actually had, and re-send the structure to the clients.
     *
     * <p>The sync deliberately reuses the message P1 already sends on pick-up and put-down: a carrier
     * payload is "where this structure is, and everything riding it", and a recomputed component
     * changes exactly the second half. No new payload, no protocol bump, and one code path on the
     * client - the one that already replaces a carrier's host list wholesale.
     */
    /**
     * The production write-back: mark the carried file dirty, so a machine put down later comes home
     * with the value it actually had, and re-send the structure to the clients.
     *
     * <p>The sync deliberately reuses the message P1 already sends on pick-up and put-down: a carrier
     * payload is "where this structure is, and everything riding it", and a recomputed component
     * changes exactly the second half. No new payload, no protocol bump, and one code path on the
     * client - the one that already replaces a carrier's host list wholesale.
     */
    private static void afterSolveWrote(ServerLevel level, CarrierId carrier) {
        CarriedHosts.get(level).setDirty();
        CarriedHostIndex index = indexOf(level);
        if (index == null) {
            return;
        }
        broadcastCarrier(level, carrier, index, poseOf(level, carrier));
    }

    /**
     * Releases the hosts of every structure that no longer exists.
     *
     * <p>A structure counts as gone only when the source cannot even name a pose for it - the
     * entity has left the level, the sub-level has been removed. A structure that is simply far away
     * or in an unloaded chunk still answers with a pose, so a machine that is out of sight is never
     * mistaken for a machine that is gone.
     *
     * <h2>The second question, added after the author's log</h2>
     * That rule is one-sided: it takes "no pose" as proof of death, which is only sound if the source
     * can always answer for a structure that is alive. The log disproved exactly that. The Create half
     * cannot report a pose for a UUID that is really a <b>Sable sub-level</b> - and a physics-assembled
     * structure has one UUID shared by its contraption entity and its sub-level, so the Create
     * carrier {@code c:6c60aeef} could never be given a pose while the sub-level was flying. The sweep
     * fired on it every forty ticks, released its host (<i>"structure is gone (orphan sweep) …
     * LANDED … moved=1"</i>) and wrote the component back into the world, twice a second, for as long as
     * the machine existed.
     *
     * <p>So "no pose" is now only sufficient evidence when nobody can positively say the structure is
     * still there. {@link #structureIsAlive} is that second question, answered by the compat half from
     * the sub-level container itself, and a carrier it answers {@code true} for is skipped here and
     * reported - never released, on the strength of a query that cannot name it.
     */
    private static void sweepOrphans(ServerLevel level, CarriedHostIndex index) {
        CarrierSource carrierSource = source;
        if (carrierSource == null) {
            return;
        }
        for (CarrierId carrier : index.carriers()) {
            if (index.liveHostsOf(carrier).isEmpty()) {
                continue;
            }
            if (carrierSource.poseOf(level, carrier) != null) {
                continue;
            }
            if (structureIsAlive(carrier)) {
                // THE MISDIAGNOSIS THIS ROUND FIXES. The pose query failed, but the structure is
                // positively still there - so nothing is released and nothing is written into the world.
                // Logged every sweep rather than once: it is cheap (the sweep runs once in forty ticks
                // per carrier), and a reader who sees it repeating has found the exact defect this
                // guard exists for - a carrier whose pose is being asked of the wrong half of the
                // compat layer.
                if (RCConfig.debugLog()) {
                    RCConfig.LOGGER.info("[redstonecircuit] carrier {} answered 'no pose' by the pose "
                                    + "query but its structure is still alive - NOT an orphan, holding "
                                    + "{} host(s); the pose must be asked of the peer that owns the "
                                    + "structure (see CarrierKind: this is a {})",
                            carrier.shortId(), index.liveHostsOf(carrier).size(),
                            carrier.isSubLevel() ? "sable sub-level" : "create contraption");
                }
                continue;
            }
            releaseAll(level, carrier, "structure is gone (orphan sweep)");
            SENT_POSES.remove(carrier.uuid());
            SENT_POSE_TICKS.remove(carrier.uuid());
        }
    }

    /**
     * Phase two of the two-phase identity, driven once per level tick.
     *
     * <p>Every carrier whose UUID is a provisional one - recognised from the UUID itself, see
     * {@link CarrierIdentity#isProvisional} - is asked of the compat half whether its structure has an
     * entity yet, and re-labelled onto it if so. {@link #reconcile} does the work and the logging.
     *
     * <p><b>Why a sweep and not only the per-actor callback.</b> {@code MovementBehaviour#tick} is the
     * prompt path and normally settles a provisional claim within the same tick, but it is gated on
     * {@code isActorActive} and on the actor actually ticking; a structure that is stalled, or whose
     * entity appeared during a tick that never reached the actors, would otherwise keep its hosts filed
     * under a name no pose can be looked up for - claimed, counted, and invisible. The sweep costs one
     * list walk over the carriers (a handful) plus one identity test each, and nothing else.
     */
    private static void reconcileProvisional(ServerLevel level, CarriedHostIndex index) {
        ProvisionalClaims finder = provisionalClaims;
        if (finder == null) {
            return;
        }
        for (CarrierId carrier : index.carriers()) {
            if (!CarrierIdentity.isProvisional(carrier)) {
                continue;
            }
            UUID entity;
            try {
                entity = finder.entityFor(carrier);
            } catch (RuntimeException | LinkageError failed) {
                CarrierTrace.noteReconcileFailure(level.dimension());
                RCConfig.LOGGER.warn("[redstonecircuit] carrier reconcile lookup for {} failed: {}",
                        carrier.shortId(), failed.toString());
                continue;
            }
            if (entity != null) {
                reconcile(level, carrier, entity);
            }
        }
    }

    /** Drops the per-level caches; called when a level unloads. */
    public static void clear(ServerLevel level) {
        CarriedHostIndex index = indexOf(level);
        if (index != null) {
            index.clear();
        }
        SENT_POSES.clear();
        SENT_POSE_TICKS.clear();
        // P1.20: the self-healing sweep's per-carrier cursors describe this level's structures and are
        // dropped with it, exactly like the projection table below. Nothing here survives an unload.
        SELF_HEAL.clear();
        // A carrier-side wrench pick names one structure's hosts, so it cannot outlive the level either.
        CarriedSelection.clearAll();
        // A level that is going away has no clients left to tell anything to, and the next level the
        // player is in starts its own ledger.
        REMOVALS.clear(level.dimension().location().toString());
        // The three-stage counters describe this level's structures and would otherwise keep reporting
        // a machine that has been unloaded.
        CarrierTrace.clear(level.dimension());
        // P4's projection table describes this level's structures and would otherwise keep answering
        // world signal queries for a level that no longer exists.
        PROJECTION.clear(level.dimension());
        // P1.38: the read counters describe this level's structures too, and the pending tick slot holds
        // a position of one. Dropped with them; nothing here touches a container.
        SignalReadTrace.reset();
        structureFrameDeclined = false;
        // The solve's own bookkeeping - each structure's "what the client last saw" baseline and its
        // pending repeater delays - is keyed by carrier UUID and would otherwise outlive a level it
        // described.
        CarrierNetworks.clear();
    }

    // --------------------------------------------------------------- helpers --

    /** The pose of a carrier, or {@code null} when it is not there / not known. */
    @Nullable
    public static CarrierPose poseOf(ServerLevel level, CarrierId carrier) {
        CarrierSource carrierSource = source;
        return carrierSource == null ? null : carrierSource.poseOf(level, carrier);
    }

    /**
     * Where a carrier's local coordinates land right now, or {@code null} to use the recorded
     * positions.
     *
     * <p>A {@code null} pose deliberately means "put it back where it came from" rather than "refuse":
     * the recorded position is always available, and a component that cannot find its way home is a
     * far worse outcome than one that comes home to the block it left.
     *
     * <p>Delegates to {@link CarrierProjection#project}, which is the same arithmetic P4 uses to say
     * where a carried host currently <em>is</em>. "Where would this be put down now" and "where is this
     * now" are one question, and two implementations of it would be a class of bug nobody could see.
     */
    @Nullable
    public static CarriedHostIndex.Landing landingFor(@Nullable CarrierPose pose) {
        if (pose == null) {
            return null;
        }
        return local -> CarrierProjection.project(pose, local);
    }

    /**
     * <b>The pose a release should land on: the live one, or the last one the client was told.</b>
     *
     * <h2>Why the second half exists</h2>
     * "The structure is not there any more" is exactly when a release happens, so the live pose is
     * exactly the thing that is most likely to be unavailable at that moment - and the old rule,
     * "no pose means put it back where it came from", then sent a component that had been carried
     * across the map home to the block it was picked up from. The author's log is the measurement:
     * the sub-level was at block {@code 2773,77,-1397} when it was removed, and the component was
     * written back at {@code 2775,77,-1395}.
     *
     * <p>The last broadcast pose is not a guess. It is where the client is currently drawing the
     * structure, and it was computed from the same transform the release would have asked for, one
     * tick earlier. Using it is strictly better than the origin, and the origin remains the fallback
     * for the one case where it is right: <b>a structure whose position was never known at all</b>,
     * which is what a {@code null} here means and what {@link #lastSentPose} answers.
     */
    @Nullable
    public static CarrierPose landingPose(ServerLevel level, CarrierId carrier) {
        CarrierPose live = poseOf(level, carrier);
        return live != null ? live : lastSentPose(carrier);
    }

    /**
     * The same choice as a pure function of two poses, so both of its paths can be a unit test.
     *
     * <p>{@code live} is what the structure's own transform answers right now, and it is preferred
     * whenever it exists. {@code lastSent} is the pose the server last broadcast for the carrier, and it
     * is the fallback for the one moment a live pose is most likely to be missing - the release. The
     * returned {@link CarriedHostIndex.Landing} is {@code null} only when there has never been a pose at
     * all, which is the single case in which "put it back where it came from" is the right answer.
     */
    @Nullable
    public static CarriedHostIndex.Landing landingOnRelease(@Nullable CarrierPose live,
                                                            @Nullable CarrierPose lastSent) {
        CarrierPose pose = live != null ? live : lastSent;
        return landingFor(pose);
    }

    /**
     * The world-space centre of a carrier's bounds, or {@code null} when the source cannot report one.
     *
     * <p>The "where is this structure, roughly" answer {@link CarrierDrawCheck} compares a draw point
     * against. Asked of the source rather than derived from the pose, because the pose's origin is a
     * pivot and a pivot says nothing about where a machine's blocks are - and because a bounds query is
     * the thing every physics library actually maintains.
     */
    @Nullable
    public static org.joml.Vector3d structureCentre(ServerLevel level, CarrierId carrier) {
        CarrierSource carrierSource = source;
        if (carrierSource == null || carrier == null) {
            return null;
        }
        try {
            return carrierSource.centreOf(level, carrier);
        } catch (RuntimeException | LinkageError failed) {
            return null;
        }
    }

    /**
     * The draw check for one carrier: where its first riding host would be drawn, and where the
     * structure itself is.
     *
     * <p>The whole of {@code /rc carrier}'s answer to "the log says it drew, so why can I not see it":
     * see {@link CarrierDrawCheck}. Returns a check with no positions rather than {@code null} when
     * there is no pose or no host, so the caller always has a line to print and never has to decide
     * what "nothing to check" means.
     */
    public static CarrierDrawCheck drawCheck(ServerLevel level, CarrierId carrier,
                                             @Nullable CarriedHostIndex index) {
        CarrierPose pose = poseOf(level, carrier);
        BlockPos local = null;
        if (index != null) {
            for (CarriedHost host : index.liveHostsOf(carrier)) {
                local = host.localPos();
                break;
            }
        }
        if (pose == null || local == null) {
            return new CarrierDrawCheck(carrier, local, null, structureCentre(level, carrier),
                    sentPoseAgeTicks(carrier, level.getGameTime()));
        }
        return new CarrierDrawCheck(carrier, local,
                CarrierPose.blockCentreOf(pose, local), structureCentre(level, carrier),
                sentPoseAgeTicks(carrier, level.getGameTime()));
    }

    /**
     * One line about the pose the client is drawing this carrier with, for {@code /rc carrier}.
     *
     * <p>Added because the author's report - <em>the bearing turns and the component does not turn with
     * it</em> - was made against a log in which every carrier looked perfectly healthy. The question that
     * separates a stale client from a parked machine is "how long ago did the last pose go out, and was
     * it a change or a heartbeat", and this is that question answered on the server side.
     */
    public static String describePoseSync(ServerLevel level, CarrierId carrier) {
        long age = sentPoseAgeTicks(carrier, level.getGameTime());
        String health;
        if (age < 0L) {
            health = "NEVER SENT - a client that has been told about this carrier has no pose for it";
        } else if (age > CarrierDrawCheck.POSE_AGE_WARN_TICKS) {
            health = "*** STALE: the client is drawing this structure where it was " + age
                    + " tick(s) ago - check the 'sent carrier ... why=' lines for whether the server is "
                    + "broadcasting at all ***";
        } else {
            health = "fresh";
        }
        return "poseSync: lastSentAge=" + ageText(age)
                + " heartbeat=" + RCConfig.carrierPoseHeartbeatTicks() + "t"
                + " sampleEvery=" + RCConfig.carrierPoseSampleTicks() + "t " + health
                + "   (a client drawing an old angle and a client drawing a parked machine look "
                + "identical on screen; this is the number that tells them apart)";
    }

    /** One line for the diagnostics: how many carriers and hosts are in flight in this level. */
    public static String describe(ServerLevel level) {
        CarriedHostIndex index = indexOf(level);
        if (index == null) {
            return "carried: unavailable for this level";
        }
        if (index.isEmpty()) {
            return "carried: nothing in flight";
        }
        return "carried: " + index.carrierCount() + " carrier(s), " + index.carriedCount()
                + " host(s) riding, " + index.releasedCarrierCount() + " put down";
    }

    /** Every carrier in flight in this level, for the diagnostics. */
    public static List<CarrierId> carriersOf(ServerLevel level) {
        CarriedHostIndex index = indexOf(level);
        return index == null ? List.of() : index.carriers();
    }
}

package com.jiangyuefengyu.redstonecircuit.compat.physics;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.jiangyuefengyu.redstonecircuit.RCConfig;
import com.jiangyuefengyu.redstonecircuit.carrier.CarriedHostIndex;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierId;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierKind;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierPose;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierStore;
import com.jiangyuefengyu.redstonecircuit.carrier.SubLevelSpace;
import com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneNode;
import com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneStore;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.joml.Vector3d;

/**
 * <b>The claim the Sable path never had.</b>
 *
 * <h2>The defect this exists for, stated exactly</h2>
 * The author's report for Create Aeronautics was: <em>physicalise the blocks with the aeronautics
 * wrench, fly the structure away - the redstone components inside the block cannot be seen, and they
 * stay behind instead of following.</em>
 *
 * <p>Both halves of that sentence had one cause. Nothing on the Sable side ever called
 * {@code CarrierStore.startMoving}: the Create side is driven by {@code MovementBehaviour}, and a
 * Sable sub-level has no equivalent callback that P0 or P1 ever found. So a component in a flying
 * sub-level was never moved into the carrier index, and:
 *
 * <ul>
 *   <li>the server's world store still held it at the block the structure had taken it from, so the
 *       component did not follow the machine ("鐣欏湪鍘熷湴");</li>
 *   <li>the client was never told to stop drawing it there, and the block itself is air once the
 *       structure has flown off, so the world render path refused to draw it
 *       ({@code worldPathDraws = worldHost && blockPresent} is false) and the carried path had never
 *       heard of it either ("鐪嬩笉瑙?).</li>
 * </ul>
 *
 * <p>The log confirmed the absence rather than the failure: {@code carrier s:xxxxxxxx released
 * wholesale (sable sub-level removed (UNLOADED)) UNKNOWN_CARRIER} - a <b>release</b> for a carrier that
 * had never been claimed, four of them, and not one {@code carrier claim} line with an {@code s:} id.
 *
 * <h2>Where the claim hangs, given that there is no callback</h2>
 * Two places, because neither is sufficient alone:
 *
 * <ol>
 *   <li>{@link SableSubLevelAccounting#onSubLevelAdded} - the moment the sub-level exists. Prompt, and
 *       it is what makes a re-loaded save reconcile;</li>
 *   <li>this class's {@link #sweep}, driven once per level tick. Necessary because a sub-level is
 *       typically allocated <em>before</em> the blocks move out of the world (the plot is created, and
 *       only then are the blocks copied in and cleared out), so at the instant the add hook fires there
 *       may be nothing to claim yet. The sweep keeps looking until either the claim happens or the
 *       structure proves not to be holding our blocks.</li>
 * </ol>
 *
 * <h2>Why "which world positions" is answered from Sable's own bounds, not by scanning the world</h2>
 * The candidate set is <code>{world store position : it is inside some live sub-level's world
 * bounds}</code>. That is bounded by the number of host blocks in the level (the order of a thousand)
 * against the handful of sub-levels, and it needs no knowledge of the sub-level's internal layout. The
 * alternative - walking every block of every plot - would be a walk over millions of coordinates for a
 * plot that is mostly empty, once per tick, which is not a trade this mod is willing to make.
 *
 * <h2>Why a candidate is only claimed when its world block has really gone</h2>
 * A sub-level can exist over terrain that is still standing in the world: allocate an airship whose
 * balloon overlaps your house and, for one tick, the house's components are inside the sub-level's
 * bounds <em>and</em> still real blocks in the world. Claiming then would take the component out of a
 * block the player can still see and break, and the break would find nothing. So the last gate is
 * physical: <b>the block at the candidate's origin must no longer be the block it was</b>. For a block
 * the structure has really taken, that is air (Sable's own move clears the world side). For a block
 * that has not moved, it is the block itself, and nothing is touched.
 *
 * <p>Every step is verified rather than assumed:
 *
 * <pre>
 *   candidate = a world position the store says holds a component, inside a live sub-level
 *   gate 1    the block at that position is no longer the block the component was placed in
 *   gate 2    pose.logicalPose() to world for local = pose.transformPositionInverse(origin)
 *             round-trips back to origin within a block  (see SubLevelSpace#localForVerified)
 *   claim     CarrierStore.startMoving(level, subLevelUuid, SUB_LEVEL, local, origin)
 * </pre>
 *
 * <p>A candidate that fails either gate is left exactly where it is. The invariant this protects is
 * the same one the Create hook protects: <b>a block that really holds a component is never skipped</b>
 * - the sweep keeps trying - and nothing is ever moved on a guess.
 *
 * <h2>Nothing here runs unless Sable is installed</h2>
 * Every Sable type this class names lives in the compat source set, and the class is only reached from
 * {@link SableSubLevelAccounting}, which is only installed after a {@code ModList} check.
 */
final class SableCarrierClaim {

    /**
     * How many level ticks to wait between two attempts at the same sub-level.
     *
     * <p>Ten: a sub-level is allocated and its blocks moved within the same tick in the ordinary
     * aeronautics flow, so one retry after half a second is already generous, and a player who
     * physicalises something and then walks away costs ten sweeps a second of one hash lookup. Between
     * attempts nothing is walking the store at all.
     */
    private static final int RETRY_INTERVAL = 10;

    /**
     * How many times one sub-level is re-examined before it is left alone for good.
     *
     * <p>Forty attempts at ten ticks each is roughly twenty seconds: long enough for a machine that is
     * assembled and then paused, short enough that a sub-level holding none of our blocks - the
     * overwhelming majority, since most players' contraptions have no inner redstone at all - stops
     * costing anything.
     */
    private static final int MAX_ATTEMPTS = 40;

    /**
     * The most candidates one sub-level may claim in one pass.
     *
     * <p>Four hundred and ninety-six, which is a full section of blocks. A structure holding more
     * components than that finishes over the following passes, which is correct if slower; the ceiling
     * exists so one pathological machine cannot make one tick unbounded.
     */
    private static final int MAX_CLAIMS_PER_PASS = 496;

    /** How many blocks a round trip through the pose may be out before the pose is refused. */
    private static final double ROUND_TRIP_TOLERANCE = 1.0;

    /** What has been seen of one sub-level, per level, so a sweep is not a rescan. */
    private static final class Watched {

        private int attempts;
        private int claimed;

        /**
         * How many adoption attempts this sub-level has had, and when the last one was.
         *
         * <p>P1.30. Deliberately <b>separate</b> from {@link #attempts}: the claim path stops after
         * {@link #MAX_ATTEMPTS} because a structure that has claimed nothing for twenty seconds is
         * holding none of our blocks, whereas the adoption path has to keep looking for a different
         * reason - "a component is in the world store at one of this structure's own plot coordinates" is
         * a state a save can be in for as long as the save exists, and its own cadence is a second, not
         * half a second. Rolling them into one counter would either re-open the claim path for ever or
         * close the adoption path after twenty seconds; both are wrong.
         */
        private int adoptionPasses;
        private long lastAdoptionTick = Long.MIN_VALUE;

        /** True while another sweep of this sub-level could still find something. */
        boolean retryable() {
            return attempts < MAX_ATTEMPTS;
        }

        /** True while this sub-level may still be carrying a world-store entry that needs adopting. */
        boolean adoptionRetryable() {
            return adoptionPasses < SableComponentAdoption.MAX_PASSES;
        }

        /**
         * Whether this tick is one of the structure's own adoption ticks.
         *
         * <p>Staggered by the UUID, the same trick the claim sweep uses, so a world with many structures
         * does not examine them all on the same tick.
         */
        boolean adoptionDue(long now, UUID uuid) {
            if (!adoptionRetryable()) {
                return false;
            }
            if (lastAdoptionTick != Long.MIN_VALUE
                    && now - lastAdoptionTick < SableComponentAdoption.ADOPT_INTERVAL) {
                return false;
            }
            return (now + Math.floorMod(uuid.hashCode(), SableComponentAdoption.ADOPT_INTERVAL))
                    % SableComponentAdoption.ADOPT_INTERVAL == 0;
        }

        void noteAdoption(long now) {
            adoptionPasses++;
            lastAdoptionTick = now;
        }
    }

    private static final java.util.Map<UUID, Watched> WATCHED = new ConcurrentHashMap<>();

    private SableCarrierClaim() {
    }

    // ---------------------------------------------------------------- entry --

    /**
     * One attempt at every sub-level of one level.
     *
     * <p>Called once per level tick by the compat backend, before the carrier tick that would send
     * poses and release orphans - so a claim made here is broadcast with a pose in the same tick rather
     * than a tick later, which is the difference between "the component appears as the machine lifts"
     * and one frame of it standing in the world.
     */
    static void sweep(ServerLevel level) {
        if (level == null || !CarrierStore.isActive()) {
            return;
        }
        try {
            sweepAll(level);
        } catch (RuntimeException | LinkageError failed) {
            // A claim failing must never take a level tick down: the components stay in the world
            // store, which is the safe half of the two states.
            RCConfig.LOGGER.warn("[redstonecircuit] sable claim sweep failed: {}", failed.toString());
        }
    }

    /**
     * The one-shot attempt for a sub-level that has just been added.
     *
     * <p>Returns what the accounting should log. Deliberately does not mark the sub-level as settled
     * when it finds nothing: the add hook fires before the blocks move, so "found nothing" here is the
     * expected answer and the sweep is what actually claims.
     */
    static int onSubLevelAdded(@Nullable ServerLevel level, SubLevel subLevel) {
        if (level == null || subLevel == null || !CarrierStore.isActive()) {
            return 0;
        }
        try {
            return claimFrom(level, subLevel, "sub-level added");
        } catch (RuntimeException | LinkageError failed) {
            RCConfig.LOGGER.warn("[redstonecircuit] sable claim on sub-level add failed: {}",
                    failed.toString());
            return 0;
        }
    }

    /** Forgets every sub-level of one level; called when the level goes away. */
    static void forgetAll() {
        WATCHED.clear();
    }

    /** How many sub-levels are still being retried; for the diagnostics. */
    static int watchedCount() {
        return WATCHED.size();
    }

    /** How many hosts this accounting has claimed on the Sable side since the process started. */
    static int claimedTotal() {
        int total = 0;
        for (Watched watched : WATCHED.values()) {
            total += watched.claimed;
        }
        return total;
    }

    // ------------------------------------------------------------- the sweep --

    private static void sweepAll(ServerLevel level) {
        SubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return;
        }
        List<? extends SubLevel> subLevels = container.getAllSubLevels();
        if (subLevels.isEmpty()) {
            if (!WATCHED.isEmpty()) {
                WATCHED.clear();
            }
            return;
        }
        Set<UUID> live = new HashSet<>();
        long now = level.getGameTime();
        for (SubLevel subLevel : subLevels) {
            if (subLevel == null || subLevel.isRemoved()) {
                continue;
            }
            UUID uuid = subLevel.getUniqueId();
            if (uuid == null) {
                continue;
            }
            live.add(uuid);
            Watched watched = WATCHED.computeIfAbsent(uuid, ignored -> new Watched());
            // THE ADOPTION PASS (P1.30) RUNS INDEPENDENTLY OF THE CLAIM PASS, and before it. The two look
            // at disjoint candidate sets - a claim is about a world position inside the structure's WORLD
            // bounds, an adoption about a level position inside its PLOT bounds - so the order cannot
            // change either answer, and running the repair first means a save that is in the broken state
            // is fixed on the same pass that would otherwise do nothing about it.
            if (watched.adoptionDue(now, uuid)) {
                watched.noteAdoption(now);
                SubLevelSpace adoptionSpace = describe(subLevel);
                if (adoptionSpace != null) {
                    SableComponentAdoption.adopt(level, subLevel, adoptionSpace, true);
                }
            }
            if (!watched.retryable()) {
                continue;
            }
            // Ten ticks between attempts, staggered by the sub-level's own id so a world with many
            // sub-levels does not re-examine all of them on the same tick.
            if ((now + Math.floorMod(uuid.hashCode(), RETRY_INTERVAL)) % RETRY_INTERVAL != 0) {
                continue;
            }
            watched.attempts++;
            watched.claimed += claimFrom(level, subLevel, "sweep #" + watched.attempts);
        }
        WATCHED.keySet().removeIf(uuid -> !live.contains(uuid));
    }

    // ------------------------------------------------------------ the claim --

    /**
     * One sub-level's attempt: build its two spaces, find its candidates, and claim the ones whose
     * block has really left the world.
     *
     * @return how many hosts were newly claimed
     */
    static int claimFrom(ServerLevel level, SubLevel subLevel, String why) {
        SubLevelSpace space = describe(subLevel);
        if (space == null || !space.hasPose()) {
            return 0;
        }
        InnerRedstoneStore store = InnerRedstoneStore.get(level);
        if (store == null || store.isEmpty()) {
            return 0;
        }
        List<BlockPos> candidates = candidates(level, space, store);
        if (candidates.isEmpty()) {
            return 0;
        }
        int claimed = 0;
        int refused = 0;
        for (BlockPos origin : candidates) {
            if (claimed >= MAX_CLAIMS_PER_PASS) {
                break;
            }
            if (!blockHasLeftTheWorld(level, origin)) {
                // Gate 1: the block is still standing where the component is. Claiming now would move
                // a component out of a block a player can still see and break.
                continue;
            }
            BlockPos local = space.localForVerified(origin, ROUND_TRIP_TOLERANCE);
            if (local == null) {
                // Gate 2: the pose does not put this local coordinate back where the block is, so this
                // code cannot say where the component would be drawn. Reported rather than guessed.
                refused++;
                continue;
            }
            if (!blockIsInsideTheStructure(space, local)) {
                // Gate 3: the local coordinate the pose names is not a block this structure is standing
                // on. A pose answers for every point in the world, so a candidate at the origin of a
                // component a *different* machine is carrying would round-trip perfectly and still be
                // the wrong block; the structure's own world bounds are what says so.
                refused++;
                continue;
            }
            CarrierPose pose = space.pose();
            CarrierStore.startMoving(level, space.uuid(), CarrierKind.SUB_LEVEL, local, origin);
            claimed++;
            if (RCConfig.traceCarrier()) {
                RCConfig.LOGGER.info("[redstonecircuit] sable claim {} local={} origin={} why={}",
                        space.carrier().shortId(), local.toShortString(), origin.toShortString(), why);
            }
            if (pose != null) {
                RCConfig.LOGGER.info("[redstonecircuit] sable claim pose {} local={} "
                                + "computedWorld={} origin={}", space.carrier().shortId(),
                        local.toShortString(),
                        format(space.worldFor(local)), origin.toShortString());
            }
        }
        if (claimed > 0) {
            RCConfig.LOGGER.info("[redstonecircuit] sable claim: {} host(s) taken into sub-level {} "
                            + "({}), {} candidate(s) examined, {} refused (pose did not round-trip)",
                    claimed, space.uuid(), why, candidates.size(), refused);
        } else if (refused > 0) {
            RCConfig.LOGGER.warn("[redstonecircuit] sable claim: sub-level {} has {} candidate "
                            + "component(s) but none could be filed - the pose does not round-trip "
                            + "through it. Nothing was moved; they stay in the world store, which is "
                            + "the safe half of the two states", space.uuid(), refused);
        }
        return claimed;
    }

    /**
     * The world positions inside a sub-level that still hold a component in the level's own store.
     *
     * <p>Bounded by the store, not by the plot: see the class doc. The store's own position list is the
     * only enumeration used, and the sub-level's world bounds filter it in one comparison per entry.
     */
    private static List<BlockPos> candidates(ServerLevel level, SubLevelSpace space,
                                             InnerRedstoneStore store) {
        List<BlockPos> out = new ArrayList<>();
        for (BlockPos worldPos : store.positions()) {
            if (out.size() >= MAX_CLAIMS_PER_PASS) {
                break;
            }
            if (!space.containsWorld(worldPos)) {
                continue;
            }
            InnerRedstoneNode node = store.get(worldPos);
            if (node == null || node.isEmpty()) {
                continue;
            }
            out.add(worldPos);
        }
        return out;
    }

    /**
     * Whether the local coordinate the pose named is a block the structure is really standing on.
     *
     * <p>An affine pose answers for every point in the world, so "the pose put this world block at that
     * local coordinate" is true for a block nowhere near the sub-level. What makes a candidate ours is
     * that its local coordinate, put back through the pose, is inside the structure's own world bounds -
     * the bounds Sable maintains from the blocks it actually holds.
     */
    private static boolean blockIsInsideTheStructure(SubLevelSpace space, BlockPos local) {
        Vector3d world = space.worldFor(local);
        return world != null && space.containsWorld(SubLevelSpace.nearestWorldBlock(world));
    }

    /**
     * Whether the block at {@code origin} is no longer the block the component was placed inside.
     *
     * <p>The physical gate. Sable clears the world side when it takes a block into a plot, so for a
     * block the structure has really taken this is air; for a block that is still standing - a
     * sub-level allocated over terrain, a plot whose bounds merely overlap a house - it is the block
     * itself and the candidate is left alone.
     *
     * <p>The test is "air", not "not the same state": a structure that has swapped the block for
     * another one is not a case this code has ever seen, and treating a non-air block as taken would
     * make a claim on the strength of a wrong block. Air is the one answer that means the block was
     * moved.
     */
    private static boolean blockHasLeftTheWorld(ServerLevel level, BlockPos origin) {
        try {
            BlockState state = level.getBlockState(origin);
            return state.isAir();
        } catch (RuntimeException failed) {
            return false;
        }
    }

    // ------------------------------------------------- Sable type conversion --

    /**
     * Everything this code needs about one sub-level, in our own types.
     *
     * <p>The one place a {@code SubLevel} is turned into numbers, so that all of the decision-making
     * above is testable without Sable and this method is the only thing that has to be checked against
     * Sable's own API when it changes.
     */
    @Nullable
    static SubLevelSpace describe(SubLevel subLevel) {
        if (subLevel == null || subLevel.isRemoved()) {
            return null;
        }
        LevelPlot plot = subLevel.getPlot();
        BoundingBox3ic local = plot == null ? null : plot.getBoundingBox();
        BoundingBox3dc world = subLevel.boundingBox();
        if (local == null || world == null) {
            return null;
        }
        return new SubLevelSpace(subLevel.getUniqueId(),
                local.minX(), local.minY(), local.minZ(), local.maxX(), local.maxY(), local.maxZ(),
                world.minX(), world.minY(), world.minZ(), world.maxX(), world.maxY(), world.maxZ(),
                poseOf(subLevel));
    }

    /**
     * A sub-level's {@code logicalPose()} as a {@link CarrierPose}, or {@code null} when it cannot be
     * read.
     *
     * <p>This is the <b>same</b> conversion {@code CarrierResolver#subLevelPose} makes for the live
     * carrier path, and deliberately so: the pose a claim is verified against has to be the pose the
     * renderer and the landing arithmetic later use, or a claim can round-trip here and land nowhere.
     * Written out again rather than shared because that one is private to another class - and the two
     * are pinned against each other by the same {@link SubLevelSpace} tests.
     */
    @Nullable
    static CarrierPose poseOf(SubLevel subLevel) {
        Pose3dc pose = subLevel.logicalPose();
        if (pose == null || pose.position() == null || pose.orientation() == null) {
            return null;
        }
        Vector3d pivot = pose.rotationPoint() == null
                ? new Vector3d(0.5, 0.5, 0.5)
                : new Vector3d(pose.rotationPoint());
        double scale = pose.scale() == null ? 1.0 : pose.scale().x();
        if (!Double.isFinite(scale) || scale == 0.0) {
            scale = 1.0;
        }
        Quaterniond orientation = new Quaterniond(pose.orientation());
        if (!orientation.isFinite() || orientation.lengthSquared() < 1.0E-9) {
            return null;
        }
        Vector3d position = new Vector3d(pose.position());
        if (!position.isFinite() || !pivot.isFinite()) {
            return null;
        }
        return new CarrierPose(position, orientation.normalize(), pivot, scale);
    }

    /**
     * The landing position of one host, from the sub-level's <em>current</em> pose, or {@code null}
     * when there is no pose left to ask.
     *
     * <p>Used on the removal path: a sub-level that is taken apart has a pose at that moment, and the
     * pose is where its blocks actually are. Falling back to the recorded origin is what
     * {@code releaseAll(鈥? null, 鈥?} does.
     */
    @Nullable
    static CarriedHostIndex.Landing landingFor(@Nullable SubLevel subLevel) {
        if (subLevel == null || subLevel.isRemoved()) {
            return null;
        }
        CarrierPose pose = poseOf(subLevel);
        return pose == null ? null : CarrierStore.landingFor(pose);
    }

    /** The carrier id of a sub-level, for callers that only have the object. */
    @Nullable
    static CarrierId carrierOf(@Nullable SubLevel subLevel) {
        return subLevel == null || subLevel.getUniqueId() == null
                ? null : CarrierId.subLevel(subLevel.getUniqueId());
    }

    private static String format(@Nullable Vector3d v) {
        return v == null ? "-" : String.format(java.util.Locale.ROOT, "(%.3f, %.3f, %.3f)",
                v.x, v.y, v.z);
    }
}

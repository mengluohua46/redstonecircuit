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
 * The claim the Sable path needs: a sub-level has no callback comparable to Create's
 * {@code MovementBehaviour}, so nothing ever called {@code CarrierStore.startMoving} and a component inside
 * a flying structure stayed in the server's world store, with the client never told to stop drawing it.
 * <p>Two places claim, because neither suffices: the add hook when the sub-level appears, and {@link #sweep}
 * once per level tick, since a sub-level is usually allocated before its blocks leave the world. Candidates
 * come from the store rather than a walk of the plot, and one is claimed only once its world block is gone.
 */
final class SableCarrierClaim {

    /** Level ticks between two attempts at the same sub-level: a sub-level is allocated and its blocks
     * moved within the same tick in the ordinary flow, so one retry after half a second is generous. */
    private static final int RETRY_INTERVAL = 10;

    /** Attempts before one sub-level is left alone for good: forty at ten ticks each is about twenty
     * seconds, long enough for a machine that is assembled and then paused. */
    private static final int MAX_ATTEMPTS = 40;

    /** The most candidates one sub-level may claim in one pass (a full section of blocks), so no
     * pathological machine can make one tick unbounded; the rest finish over later passes. */
    private static final int MAX_CLAIMS_PER_PASS = 496;

    /** How many blocks a round trip through the pose may be out before the pose is refused. */
    private static final double ROUND_TRIP_TOLERANCE = 1.0;

    /** What has been seen of one sub-level, per level, so a sweep is not a rescan. */
    private static final class Watched {

        private int attempts;
        private int claimed;

        /** Adoption attempts, kept <b>separate</b> from {@link #attempts}: the claim path gives up after
         * {@link #MAX_ATTEMPTS}, while an un-adopted entry can persist as long as the save does. */
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

        /** Whether this tick is one of the structure's own adoption ticks, staggered by the UUID. */
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

    /** One attempt at every sub-level of one level, called once per level tick before the carrier tick
     * that would send poses - so a claim made here is broadcast with a pose in the same tick. */
    static void sweep(ServerLevel level) {
        if (level == null || !CarrierStore.isActive()) {
            return;
        }
        try {
            sweepAll(level);
        } catch (RuntimeException | LinkageError failed) {
            // A claim failing must never take a level tick down: the components stay in the world store.
            RCConfig.LOGGER.warn("[redstonecircuit] sable claim sweep failed: {}", failed.toString());
        }
    }

    /** The one-shot attempt for a sub-level that has just been added. "Found nothing" is the expected
     * answer here and is not settled on: the add hook fires before the blocks move. */
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
            // Adoption runs before the claim and independently: the two look at disjoint candidate sets.
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
            // Ten ticks between attempts, staggered by the sub-level's own id.
            if ((now + Math.floorMod(uuid.hashCode(), RETRY_INTERVAL)) % RETRY_INTERVAL != 0) {
                continue;
            }
            watched.attempts++;
            watched.claimed += claimFrom(level, subLevel, "sweep #" + watched.attempts);
        }
        WATCHED.keySet().removeIf(uuid -> !live.contains(uuid));
    }

    // ------------------------------------------------------------ the claim --

    /** One sub-level's attempt: build its two spaces, find its candidates, and claim the ones whose
     * block has really left the world. Returns how many hosts were newly claimed. */
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
                // Gate 1: the block is still standing there, so claiming would take a component out of a
                // block a player can still see and break.
                continue;
            }
            BlockPos local = space.localForVerified(origin, ROUND_TRIP_TOLERANCE);
            if (local == null) {
                // Gate 2: the pose does not put this local coordinate back where the block is, so this
                // code cannot say where the component would be drawn - reported rather than guessed.
                refused++;
                continue;
            }
            if (!blockIsInsideTheStructure(space, local)) {
                // Gate 3: that local coordinate is not a block this structure stands on. A pose answers
                // for every point in the world, so another machine's host would round-trip too.
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

    /** The world positions inside a sub-level that still hold a component in the level's own store.
     * Bounded by the store, not by the plot: its position list, filtered by the sub-level's bounds. */
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

    /** Whether the local coordinate the pose named is a block the structure really stands on: an affine
     * pose answers for every point in the world, so its own bounds are what make a candidate ours. */
    private static boolean blockIsInsideTheStructure(SubLevelSpace space, BlockPos local) {
        Vector3d world = space.worldFor(local);
        return world != null && space.containsWorld(SubLevelSpace.nearestWorldBlock(world));
    }

    /** The physical gate: the block at {@code origin} is air, so the structure really took it. "Air",
     * not "a different state", because a wrong block must never make a claim. */
    private static boolean blockHasLeftTheWorld(ServerLevel level, BlockPos origin) {
        try {
            BlockState state = level.getBlockState(origin);
            return state.isAir();
        } catch (RuntimeException failed) {
            return false;
        }
    }

    // ------------------------------------------------- Sable type conversion --

    /** Everything this code needs about one sub-level, in our own types - the one place Sable's API is
     * turned into numbers, which keeps every decision above testable without Sable. */
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

    /** A sub-level's {@code logicalPose()} as a {@link CarrierPose}, or {@code null} when it cannot be
     * read - deliberately the same conversion the live carrier path makes, or a claim verified against
     * one pose could round-trip here and land nowhere. */
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

    /** The landing position of one host, from the sub-level's <em>current</em> pose: a sub-level being
     * taken apart has a pose at that moment, and the pose is where its blocks actually are. Falls back
     * to the recorded origin when there is no pose left to ask. */
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

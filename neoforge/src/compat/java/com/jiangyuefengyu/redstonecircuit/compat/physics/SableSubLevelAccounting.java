package com.jiangyuefengyu.redstonecircuit.compat.physics;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.jiangyuefengyu.redstonecircuit.RCConfig;
import com.jiangyuefengyu.redstonecircuit.carrier.CarriedHost;
import com.jiangyuefengyu.redstonecircuit.carrier.CarriedHostIndex;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierId;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierPose;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierStore;

import dev.ryanhcode.sable.api.event.SableSubLevelContainerReadyEvent;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelObserver;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;

import net.minecraft.server.level.ServerLevel;

import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

/**
 * The Sable reconciliation, plus the per-structure tick driver: registers through Sable's own platform
 * SPI (this class is a {@code SubLevelObserver}, and the physics tick is a listener object).
 *
 * <p>"Added" <em>confirms</em> the pairing - each host the index says belongs to this sub-level must
 * land where it was recorded as coming from - and "removed" puts the hosts back where they can be put
 * down.
 */
final class SableSubLevelAccounting implements SubLevelObserver, SableSubLevelContainerReadyEvent {

    /**
     * Half a block: how far a host's local coordinate may land from where it was picked up. A mismatch
     * is reported rather than acted on, so this decides what the log says, not what happens to the data.
     */
    private static final double MATCH_TOLERANCE = 0.5;

    /**
     * Containers already observed. The ready event can fire more than once for one level, and
     * registering the same observer twice would double every removal.
     */
    private static final Set<UUID> REGISTERED = ConcurrentHashMap.newKeySet();

    /** The level this accounting was last told about, keyed by container id; see {@link #register}. */
    private static final java.util.Map<UUID, ServerLevel> LEVELS = new ConcurrentHashMap<>();

    private SableSubLevelAccounting() {
    }

    // ------------------------------------------------------------- registration --

    /** {@code SableSubLevelContainerReadyEvent}: a container exists, so start observing it. */
    @Override
    public void onSubLevelContainerReady(net.minecraft.world.level.Level level,
                                         SubLevelContainer container) {
        try {
            register(level, container);
        } catch (RuntimeException | LinkageError failed) {
            RCConfig.LOGGER.warn("[redstonecircuit] sable accounting could not attach: {}",
                    failed.toString());
        }
    }

    /**
     * Attaches to one container and reconciles whatever it already holds. The reconciliation is not
     * deferred: a sub-level that was on disk when the world loaded is already in the container.
     */
    static int register(net.minecraft.world.level.Level level, SubLevelContainer container) {
        if (container == null) {
            return 0;
        }
        UUID containerId = CONTAINER_IDS.computeIfAbsent(container, ignored -> UUID.randomUUID());
        if (!REGISTERED.add(containerId)) {
            return 0;
        }
        container.addObserver(new SableSubLevelAccounting());
        ServerLevel serverLevel = level instanceof ServerLevel server ? server : null;
        if (serverLevel != null) {
            LEVELS.put(containerId, serverLevel);
        }
        int existing = 0;
        for (SubLevel subLevel : container.getAllSubLevels()) {
            existing += reconcileAdded(serverLevel, subLevel, "already in the container at attach time");
            // A sub-level read from a save has never been claimed: the carrier store holds what the
            // previous session left in it, so claim before anything moves.
            existing += SableCarrierClaim.claimFrom(serverLevel, subLevel, "already in the container");
        }
        RCConfig.LOGGER.info("[redstonecircuit] sable accounting attached to {} ({} sub-level(s) "
                        + "already there, {} host(s) confirmed)",
                serverLevel == null ? String.valueOf(level) : serverLevel.dimension().location(),
                container.getAllSubLevels().size(), existing);
        return existing;
    }

    /** Identity map for containers, so the "already observing" check does not depend on equals. */
    private static final java.util.Map<SubLevelContainer, UUID> CONTAINER_IDS =
            java.util.Collections.synchronizedMap(new java.util.IdentityHashMap<>());

    // ---------------------------------------------------------------- observer --

    /** A structure exists: claim whatever the world store is still holding inside it. */
    @Override
    public void onSubLevelAdded(SubLevel subLevel) {
        try {
            // The claim comes first: reconciliation only confirms a claim that already exists, and a
            // freshly allocated sub-level has none - its components are still in the world store.
            SableCarrierClaim.onSubLevelAdded(levelOf(subLevel), subLevel);
            reconcileAdded(levelOf(subLevel), subLevel, "sub-level added");
        } catch (RuntimeException | LinkageError failed) {
            RCConfig.LOGGER.warn("[redstonecircuit] sable sub-level add accounting failed: {}",
                    failed.toString());
        }
    }

    /**
     * Sable's container tick: the retry the claim needs. The plot is allocated <em>before</em> the
     * blocks are moved out of the world, so nothing may be claimable when the add hook fires.
     */
    @Override
    public void tick(SubLevelContainer container) {
        try {
            if (container == null || !CarrierStore.isActive()) {
                return;
            }
            net.minecraft.world.level.Level level = container.getLevel();
            if (level instanceof ServerLevel server) {
                SableCarrierClaim.sweep(server);
            }
        } catch (RuntimeException | LinkageError failed) {
            RCConfig.LOGGER.warn("[redstonecircuit] sable claim tick failed: {}", failed.toString());
        }
    }

    /**
     * A structure has gone: put its hosts back at the landing the pose names, or at the recorded origin
     * they were picked up from when no pose is left. Release is idempotent.
     */
    @Override
    public void onSubLevelRemoved(SubLevel subLevel, SubLevelRemovalReason reason) {
        try {
            if (subLevel == null) {
                return;
            }
            ServerLevel level = levelOf(subLevel);
            if (level == null) {
                return;
            }
            CarrierId carrier = CarrierId.subLevel(subLevel.getUniqueId());
            CarriedHostIndex.Landing landing = SableCarrierClaim.landingFor(subLevel);
            int moved = CarrierStore.releaseAll(level, carrier, "sable sub-level removed (" + reason + ")",
                    landing);
            if (moved > 0) {
                RCConfig.LOGGER.info("[redstonecircuit] sable accounting: sub-level {} went away ({}); "
                        + "{} host(s) put back{}",
                        subLevel.getUniqueId(), reason, moved,
                        landing != null
                                ? " at the position the structure's own pose names"
                                : " where they came from - either the structure never reported a pose, "
                                        + "or the last one it broadcast was used (see the carrier's "
                                        + "'landing=' field on the release line)");
            }
        } catch (RuntimeException | LinkageError failed) {
            RCConfig.LOGGER.warn("[redstonecircuit] sable sub-level removal accounting failed: {}",
                    failed.toString());
        }
    }

    // ----------------------------------------------------------------- tick --

    /**
     * Sable's physics tick: the sub-level half of the tick driver. A physics step can be subdivided, so
     * the solve hangs off the step rather than off a per-frame callback.
     */
    static void onPhysicsTick(SubLevelPhysicsSystem system, double timeStep) {
        try {
            if (system == null || !CarrierStore.isActive()) {
                return;
            }
            ServerLevel level = system.getLevel();
            if (level == null) {
                return;
            }
            // The retry for a claim the add hook was too early to make.
            SableCarrierClaim.sweep(level);
            // One pass for the whole level: a physics tick is per system, not per sub-level.
            CarrierStore.tickCarriers(level);
        } catch (RuntimeException | LinkageError failed) {
            RCConfig.LOGGER.warn("[redstonecircuit] sable physics-tick solve failed: {}",
                    failed.toString());
        }
    }

    // ------------------------------------------------------------ the pairing --

    /**
     * Confirms every host the index says belongs to {@code subLevel}, and reports the ones it cannot;
     * returns how many hosts were confirmed.
     */
    static int reconcileAdded(@Nullable ServerLevel serverLevel, SubLevel subLevel, String why) {
        if (subLevel == null || serverLevel == null) {
            return 0;
        }
        CarriedHostIndex index = CarrierStore.indexOf(serverLevel);
        if (index == null) {
            return 0;
        }
        CarrierId carrier = CarrierId.subLevel(subLevel.getUniqueId());
        List<CarriedHost> hosts = index.hostsOf(carrier);
        if (hosts.isEmpty()) {
            return 0;
        }
        CarrierPose pose = CarrierStore.poseOf(serverLevel, carrier);

        int confirmed = 0;
        int unconfirmed = 0;
        for (CarriedHost host : hosts) {
            if (host.landed()) {
                continue;
            }
            if (pose != null && matches(pose, host)) {
                confirmed++;
            } else {
                unconfirmed++;
            }
        }

        if (unconfirmed > 0) {
            // Nothing is thrown away on a guess: the hosts stay carried and the log says what disagreed.
            RCConfig.LOGGER.warn("[redstonecircuit] sable accounting: sub-level {} ({}) has {} "
                            + "carried host(s) whose local coordinate does not land where the block "
                            + "was picked up (pose={}); leaving them alone - the orphan sweep will "
                            + "release them if the structure really is gone",
                    subLevel.getUniqueId(), why, unconfirmed,
                    pose == null ? "none" : pose.describe());
        }
        if (RCConfig.traceCarrier() || confirmed > 0) {
            RCConfig.LOGGER.info("[redstonecircuit] sable accounting: sub-level {} ({}) reconciled: "
                            + "{} host(s) confirmed, {} unconfirmed, pose={}",
                    subLevel.getUniqueId(), why, confirmed, unconfirmed,
                    pose == null ? "none" : pose.describe());
        }
        return confirmed;
    }

    /** True when the host's local coordinate, transformed by the structure's pose, is where it came from. */
    private static boolean matches(CarrierPose pose, CarriedHost host) {
        Vector3d localBlock = new Vector3d(host.localPos().getX(), host.localPos().getY(),
                host.localPos().getZ());
        Vector3d world = pose.blockCentre(localBlock, new Vector3d());
        return world.distance(host.origin().getX() + 0.5, host.origin().getY() + 0.5,
                host.origin().getZ() + 0.5) <= MATCH_TOLERANCE;
    }

    /** The server level a sub-level belongs to, or {@code null} when it is not a server sub-level. */
    @Nullable
    private static ServerLevel levelOf(SubLevel subLevel) {
        try {
            net.minecraft.world.level.Level level = subLevel.getLevel();
            return level instanceof ServerLevel server ? server : null;
        } catch (RuntimeException | LinkageError absent) {
            return null;
        }
    }

    /** Registers this accounting with Sable's platform SPI; called once from the backend. */
    static boolean install() {
        try {
            var platform = dev.ryanhcode.sable.platform.SableEventPlatform.INSTANCE;
            if (platform == null) {
                RCConfig.LOGGER.info("[redstonecircuit] sable accounting: no event platform; "
                        + "the orphan sweep stays the only release path for sub-levels");
                return false;
            }
            SableSubLevelAccounting accounting = new SableSubLevelAccounting();
            platform.onSubLevelContainerReady(accounting);
            platform.onPostPhysicsTick(physicsAdapter());
            RCConfig.LOGGER.info("[redstonecircuit] sable accounting installed: sub-level add/remove, "
                    + "container-ready and post-physics-tick hooks are live");
            return true;
        } catch (RuntimeException | LinkageError failed) {
            RCConfig.LOGGER.warn("[redstonecircuit] sable accounting could not install: {}",
                    failed.toString());
            return false;
        }
    }

    /**
     * The physics-tick listener, kept as its own object so the class does not have to implement a third
     * Sable interface just to be registered.
     */
    private static dev.ryanhcode.sable.api.event.SablePostPhysicsTickEvent physicsAdapter() {
        return (system, timeStep) -> onPhysicsTick(system, timeStep);
    }

    /** Every container id currently observed; for the diagnostics. */
    static int observedContainerCount() {
        return REGISTERED.size();
    }

    /** The list of levels this accounting has been told about; for the diagnostics. */
    static List<String> describedLevels() {
        List<String> out = new ArrayList<>();
        for (ServerLevel level : LEVELS.values()) {
            out.add(level.dimension().location().toString());
        }
        return out;
    }
}

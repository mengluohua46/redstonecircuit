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
 * The Sable reconciliation P1 deliberately left out, plus P2's per-structure tick driver.
 *
 * <h2>What P1 did, and what was missing</h2>
 * P1 could only claim and release through Create's {@code MovementBehaviour}. A Sable sub-level has no
 * such callback, so the P1 carrier layer learned about one only through the orphan sweep - "no pose
 * for this carrier any more, put everything back" - which is a fallback, not accounting. The two
 * events that make it accounting exist and need no mixin:
 *
 * <pre>
 *   SubLevelObserver#onSubLevelAdded(SubLevel)                     - a structure exists
 *   SubLevelObserver#onSubLevelRemoved(SubLevel, RemovalReason)    - a structure has gone
 * </pre>
 *
 * <p>and P0 verified from bytecode that {@code SubLevelContainer#allocateSubLevel} calls the first and
 * {@code #removeSubLevel} calls the second. So this class registers itself as an observer and answers
 * both.
 *
 * <h2>Why the "added" case is not merely a log line</h2>
 * The brief calls out one specific path: <b>a world loaded with a structure already on disk.</b> On
 * that path nothing is ever picked up - the components are already in the carried store, read from the
 * save, and the sub-level appears afterwards - so an observer that only logged would leave the two
 * halves never having met. What this does instead is <em>confirm</em> the pairing: for each host the
 * index says belongs to this sub-level, it transforms the host's local coordinate through the
 * structure's own pose and checks that the result is where the host was recorded as coming from.
 *
 * <p>Three outcomes, and the honest handling of each:
 *
 * <ul>
 *   <li><b>It matches.</b> The index and the structure agree; {@code /rc carrier} now reports a live
 *       pose for that carrier, and the in-structure solve starts running on it. This is the state a
 *       reloaded save is supposed to reach, and before this class it never did.</li>
 *   <li><b>It does not match.</b> Either the structure was rebuilt from something else, or the pose
 *       really did move between the save and the load. Nothing is thrown away on a guess: the hosts
 *       stay where they are and the log says so, and the orphan sweep remains the backstop it always
 *       was.</li>
 *   <li><b>The structure is gone.</b> {@code onSubLevelRemoved} puts its hosts back at the positions
 *       they came from - the same safe release the sweep uses, using the recorded origins rather than
 *       a landing computed from a structure that no longer exists.</li>
 * </ul>
 *
 * <h2>How it is reached</h2>
 * Through Sable's own platform SPI, not through the {@code neoforge.event} classes: this class
 * implements {@code SubLevelObserver}, {@code SableSubLevelContainerReadyEvent} and
 * {@code SablePostPhysicsTickEvent} - all three in {@code dev.ryanhcode.sable.api}, the package P0
 * identified as the stable surface - and is registered by the platform. Without Sable installed none
 * of these types resolve, and the class is never loaded.
 */
final class SableSubLevelAccounting implements SubLevelObserver, SableSubLevelContainerReadyEvent {

    /**
     * How far a host's local coordinate may land from where it was picked up before the pairing is
     * called a mismatch.
     *
     * <p>Half a block: a block's centre is what the pose transforms, so an exact match is the normal
     * case and anything beyond half a block cannot be rounding. A mismatch is reported rather than
     * acted on, so this number decides what the log says, not what happens to the data.
     */
    private static final double MATCH_TOLERANCE = 0.5;

    /**
     * Containers already observed.
     *
     * <p>The ready event can fire more than once for one level (a reload, a second platform hook), and
     * registering the same observer twice would double every removal. Keyed by container identity
     * because that is what {@code addObserver} is called on.
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
     * Attaches to one container and reconciles whatever it already holds.
     *
     * <p>The reconciliation is not optional and not deferred: a sub-level that was on disk when the
     * world loaded is <em>already</em> in the container by the time this fires, so "wait for an add
     * event" would wait forever for exactly the case that matters. {@code getAllSubLevels()} is walked
     * once here - a handful of structures - and never again.
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
            // A sub-level that was on disk when the world loaded has never been claimed by this
            // process - the carrier store is read from the save, and what it holds is whatever the
            // previous session left in it. Claiming here is what makes "the world was saved with the
            // airship in flight" come back with its redstone inside it rather than at the blocks the
            // airship has left behind.
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
            // THE CLAIM, which is the half this path never had. Ordered before the reconciliation
            // below on purpose: reconciliation only *confirms* an existing claim, and for a freshly
            // allocated sub-level there is no existing claim to confirm - the components are still in
            // the world store, where nothing has ever moved them out.
            SableCarrierClaim.onSubLevelAdded(levelOf(subLevel), subLevel);
            reconcileAdded(levelOf(subLevel), subLevel, "sub-level added");
        } catch (RuntimeException | LinkageError failed) {
            RCConfig.LOGGER.warn("[redstonecircuit] sable sub-level add accounting failed: {}",
                    failed.toString());
        }
    }

    /**
     * Sable's container tick: the retry the claim needs.
     *
     * <p>Exists because of an ordering fact about assembly: the plot is allocated <em>before</em> the
     * blocks are moved out of the world, so at the instant {@link #onSubLevelAdded} fires there may be
     * nothing claimable yet. Rather than guess a delay, this is asked again on every container tick
     * (see {@code SubLevelContainer#tick}, which calls {@code SubLevelObserver#tick} on every
     * observer), and {@link SableCarrierClaim} stops asking once a sub-level has been examined often
     * enough to conclude it is holding none of our blocks.
     *
     * <p>Deliberately not a mixin and not a NeoForge event: Sable already calls this, and the whole
     * point of registering as an observer is to be called by the code that owns the structure.
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
     * A structure has gone: put its hosts back.
     *
     * <p>Where "back" is depends on one thing, and it is worth being explicit about because the
     * difference is a whole class of defect:
     *
     * <ul>
     *   <li><b>The sub-level still answers a pose.</b> Its blocks are somewhere, and that somewhere is
     *       where the components have to land - otherwise a machine that was disassembled across the
     *       map returns its redstone to the field it was built in. The landing is computed from the
     *       pose, through the same {@code CarrierStore.landingFor} the Create path uses, so "where would
     *       this be put down now" has one implementation.</li>
     *   <li><b>There is no pose left.</b> A sub-level that was unloaded has nothing to ask, and the
     *       recorded origin is both available and safe: it is where each component was picked up, which
     *       is the block the structure took it from. This is the same fallback the orphan sweep
     *       uses.</li>
     * </ul>
     *
     * <p>Release is idempotent, so a sub-level reported twice costs a hash lookup.
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
     * Sable's physics tick: the sub-level half of P2's tick driver.
     *
     * <p>Why this and not the container's own {@code tick()}: the brief is explicit that the physics
     * sub-step is not once per frame - {@code SubLevelPhysicsSystem#getPartialPhysicsTick()} exists
     * precisely because a step can be subdivided - so a solve hung off a per-frame callback would run
     * a different number of times per second depending on the machine's velocity. The physics tick
     * fires once per step, and this asks the carrier layer to solve the structure the step belongs to.
     *
     * <p>It is deliberately not a solve of <em>everything</em>, and not even of one sub-level: it
     * hands over the carrier id and lets {@code CarrierStore} decide, which keeps "how often" and "of
     * what" in one place. The double-driver problem - this and the level tick both firing in one tick -
     * is settled inside {@code CarrierNetworks#tick}: a dimension already solved at this clock value
     * is skipped.
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
            // The retry for a claim the add hook was too early to make. Cheap by construction: the
            // sweep finds nothing to do in one container lookup plus one hash lookup per sub-level.
            SableCarrierClaim.sweep(level);
            // One pass for the whole level, which is what the solve actually needs: a physics tick is
            // per system, not per sub-level, and solving per sub-level would walk the carrier list once
            // for each one.
            CarrierStore.tickCarriers(level);
        } catch (RuntimeException | LinkageError failed) {
            RCConfig.LOGGER.warn("[redstonecircuit] sable physics-tick solve failed: {}",
                    failed.toString());
        }
    }

    // ------------------------------------------------------------ the pairing --

    /**
     * Confirms every host the index says belongs to {@code subLevel}, and reports on the ones it
     * cannot confirm.
     *
     * @return how many hosts were confirmed, i.e. whose local coordinate lands where they came from
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
            // Nothing is thrown away on a guess: the hosts stay carried, the sweep stays the backstop,
            // and the log says exactly what disagreed so the two halves can be compared by hand.
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
     * The physics-tick listener, kept as its own object so the class does not have to implement a
     * third Sable interface just to be registered.
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

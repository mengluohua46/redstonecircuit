package com.jiangyuefengyu.redstonecircuit.compat.physics;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.jiangyuefengyu.redstonecircuit.RCConfig;
import com.jiangyuefengyu.redstonecircuit.carrier.CarriedHostIndex;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierId;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierIdentity;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierKind;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierPose;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierStore;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierTrace;
import com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneNode;
import com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneStore;

import com.simibubi.create.api.behaviour.movement.MovementBehaviour;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.Contraption;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import org.jetbrains.annotations.Nullable;

/**
 * The hook that makes a host block travel: Create's {@code MovementBehaviour} registry, registered on
 * demand for exactly the block types that have ever held a component.
 *
 * <h2>What {@code javap} settled about the shape of a callback</h2>
 * <table>
 *   <tr><td>{@code MovementContext.contraption} is a public field, always set by the constructor</td>
 *       <td>the structure is always nameable, even when nothing else is</td></tr>
 *   <tr><td>{@code MovementContext} has <b>no</b> entity field at all</td>
 *       <td>the entity can only be reached through {@code contraption.entity}</td></tr>
 *   <tr><td>{@code Contraption.entity} is a public, non-final field</td>
 *       <td>it is assigned <em>after</em> the contraption is built, so it is legitimately
 *           {@code null} during assembly</td></tr>
 *   <tr><td>{@code Contraption.startMoving(Level)} iterates one {@code actors} list and constructs a
 *       {@code MovementContext} per actor from the <b>same</b> {@code Contraption} instance</td>
 *       <td>object identity of the contraption is stable across every callback of one assembly, which
 *           is what the fallback identity is built on</td></tr>
 *   <tr><td>{@code MovementContext.localPos} is {@code StructureBlockInfo.pos()}, never null</td>
 *       <td>{@code localPos} needs no defensive branch; the anchor plus it is the block's world
 *           position, exactly as {@link #statePos} computes</td></tr>
 * </table>
 *
 * <h2>The defect this hook used to have</h2>
 * The old body began with
 * <pre>
 *   if (level == null || entity == null || context.localPos == null) { diag(...); return; }
 * </pre>
 * so an assembly whose entity had not been attached yet produced <b>no claim at all</b>: the log said
 * {@code carrier startMoving ... skipped}, the put-down callback answered {@code UNKNOWN_CARRIER}, and
 * the components neither moved nor were drawn. <b>The invariant now is that a block which really holds
 * a component is never skipped</b>: when there is no entity, the claim is made under a provisional
 * identity instead ({@link CarrierIdentity}) and re-labelled onto the entity as soon as one exists -
 * normally within the same tick, by {@link #onTick} or by the level-tick sweep.
 *
 * <p>Precisely: {@code onStartMoving} answers "is there a component inside this block?" first, and
 * returns without a claim <em>only</em> when the answer is no - which is the correct answer for the
 * overwhelming majority of a machine's blocks, and is counted as {@code emptyBlocks} rather than hidden.
 * Everything after that point (no {@code ServerLevel}, no contraption, no anchor, no entity) either has
 * a fallback or is reported as a named failure; none of them is a silent {@code return}.
 *
 * <h2>The three stages this hook participates in</h2>
 * <pre>
 *   1. claim    CarrierStore.startMoving / reconcile   "carrier claim ..."     CarrierTrace.Claims
 *   2. sync     HostSync.broadcastCarrier / broadcastPose                      CarrierTrace.Sync
 *   3. render   ClientHostCache / HostRenderPass                               CarrierTrace.Render
 * </pre>
 * {@link CarrierStore} prints the {@code carrier claim} line, so this hook does not log the claim a
 * second time; what it adds is the one fact only it can see at that moment - whether the contraption
 * entity existed - as the {@code identity=} field of its {@code carrier pickup} line, plus the one-shot
 * identity dump described below.
 *
 * <h2>Doing nothing is the common case, and it is cheap</h2>
 * The hook is per block <em>type</em>, so a contraption carrying fifty stone blocks calls in fifty
 * times, and exactly none of them holds a component. The first thing both callbacks do is ask the
 * world store; only a block that answers "yes, there is a component inside me" costs anything more.
 *
 * <h2>It coexists with anything already registered</h2>
 * The registry is a map, and this only ever writes an entry that was absent - so a block type Create
 * already has behaviour for, or that another mod has claimed, is left alone. That is a deliberate
 * limitation: such a block keeps its existing behaviour and its components do not travel. It is
 * reported at registration time rather than being silently ignored.
 */
final class MovementHostHook {

    /**
     * The one {@link MovementBehaviour} instance every host block type shares.
     *
     * <p>Stateless - everything it needs is in the {@code MovementContext} - so sharing it is safe.
     */
    private static final MovementBehaviour HOOK = new MovementBehaviour() {

        @Override
        public void startMoving(MovementContext context) {
            onStartMoving(context);
        }

        @Override
        public void stopMoving(MovementContext context) {
            onStopMoving(context);
        }

        /**
         * P2's tick driver: Create's own per-actor callback.
         *
         * <p>{@code MovementBehaviour#tick(MovementContext)} exists and is called - {@code javap -c}
         * on {@code AbstractContraptionEntity#tickActors} shows
         * {@code invokeinterface MovementBehaviour.tick:(...MovementContext;)V} at offset 305, once
         * per actor per engine tick, only when
         * {@code isActorActive(ctx, behaviour)} (which is
         * {@code MovementBehaviour#isActive}, whose default is {@code !ctx.disabled}) is true. Nothing
         * here overrides {@code isActive}, so the default applies and the call happens.
         *
         * <p>Deliberately <b>not</b> {@code mustTickWhileDisabled}: returning true there would keep a
         * machine's redstone running after a player disabled its actors with a wrench, which is the
         * opposite of what that control is for.
         *
         * <p>It is also deliberately not {@code visitNewPosition}, which only fires when the actor's
         * active area moves a block - a parked bearing would never be solved, and a machine that has
         * just been assembled would keep the values it had on the ground.
         */
        @Override
        public void tick(MovementContext context) {
            onTick(context);
        }
    };

    /** Block types already given the hook, so a block is registered at most once. */
    private static final Set<Block> REGISTERED = new HashSet<>();

    /**
     * Whether the contraption's entity has ever been non-null, per contraption object.
     *
     * <p>Only read by the one-shot diagnostics, and only to answer the question the whole feature turned
     * on: <b>was the entity there when {@code startMoving} ran?</b> "Never seen" plus "seen now" is the
     * measurement that proves the two-phase identity is needed; "seen at startMoving" would prove it is
     * not. Entries are added, never enumerated, so this stays a handful of booleans.
     */
    private static final Map<Contraption, Boolean> ENTITY_SEEN = new java.util.IdentityHashMap<>();

    private MovementHostHook() {
    }

    /**
     * Registers the hook for one block type, the first time that block type is seen holding a
     * component.
     *
     * <p>Called from the placement paths, on the server thread. Never registers twice for the same
     * block, so the log line is printed exactly once per block type.
     */
    static void register(Block block) {
        if (block == null || !REGISTERED.add(block)) {
            return;
        }
        try {
            MovementBehaviour existing = MovementBehaviour.REGISTRY.get(block.defaultBlockState());
            if (existing != null) {
                // Somebody else's behaviour owns this block type. Overwriting it would break that mod
                // (and, since the registry is a plain map, silently); leaving it means this block
                // type's components do not travel, which is reported and is a far smaller problem.
                RCConfig.LOGGER.info("[redstonecircuit] not hooking {}: another MovementBehaviour is "
                                + "already registered for it, so components inside it will not travel",
                        block.builtInRegistryHolder().key().location());
                return;
            }
            MovementBehaviour.REGISTRY.register(block, HOOK);
            RCConfig.LOGGER.info("[redstonecircuit] registered MovementBehaviour for {} "
                            + "(first block of this type to hold a component)",
                    block.builtInRegistryHolder().key().location());
        } catch (RuntimeException | LinkageError failed) {
            REGISTERED.remove(block);
            RCConfig.LOGGER.warn("[redstonecircuit] could not register MovementBehaviour for {}: {}",
                    block, failed.toString());
        }
    }

    /** How many block types currently carry the hook; used by the {@code /rc physics} report. */
    static int registeredCount() {
        return REGISTERED.size();
    }

    // ------------------------------------------------------------- the move --

    /**
     * A host block has started to move: snapshot it into the contraption.
     *
     * <p>See the class doc for why the world is a usable source of truth at this exact moment. The
     * contraption entity is used for the carrier's identity <b>when it is already there</b>; when it is
     * not, the claim is made under a provisional identity rather than dropped. The only genuinely
     * impossible case - a callback with no contraption and no local coordinate at all - is reported.
     */
    private static void onStartMoving(MovementContext context) {
        try {
            if (!CarrierStore.isActive()) {
                return;
            }
            ServerLevel level = serverLevel(context);
            Contraption contraption = context.contraption;
            AbstractContraptionEntity entity = CarrierResolver.entityOf(contraption);
            oneShotContext("startMoving", context, entity);

            // Answered before anything else, because it is the answer for almost every block: the hook
            // is per block *type*, so a machine full of stone calls in here once per stone.
            BlockPos worldPos = statePos(context);
            InnerRedstoneNode node = level == null || worldPos == null
                    ? null
                    : InnerRedstoneStore.get(level).get(worldPos);
            boolean hasComponent = node != null && !node.isEmpty();

            if (!hasComponent) {
                if (level != null) {
                    CarrierTrace.noteEmptyBlock(level.dimension());
                }
                // Not our block. Only logged when the diagnostics are asked for, because this is the
                // common case and a line per plain stone would drown the log.
                if (RCConfig.debugLog() && RCConfig.traceCarrier()) {
                    diag("startMoving", context, "no component inside; nothing to carry");
                }
                return;
            }

            // From here on there IS a component to carry, and the claim happens: that is the invariant.
            if (level == null) {
                diag("startMoving", context, "component found but the context has no server level");
                return;
            }
            if (contraption == null) {
                diag("startMoving", context, "component found but the context has no contraption");
                return;
            }
            if (worldPos == null) {
                diag("startMoving", context, "component found but there is no anchor to work the "
                        + "original position out from");
                return;
            }
            if (defersToSable("startMoving", context, entity)) {
                return;
            }

            CarrierId provisional = entity == null
                    ? CarrierResolver.provisionalFor(contraption)
                    : null;
            CarrierStore.startMoving(level, entity == null ? null : entity.getUUID(), provisional,
                    CarrierKind.CONTRAPTION, context.localPos, worldPos);
            CarrierPose pose = entity == null
                    ? CarrierStore.poseOf(level, provisional)
                    : CarrierResolver.poseOf(entity);
            RCConfig.LOGGER.info("[redstonecircuit] carrier pickup contraption local={} "
                            + "statePos={} anchor={} worldState={} entity={} identity={} pose={}",
                    context.localPos.toShortString(), worldPos.toShortString(),
                    anchor(context) == null ? "?" : anchor(context).toShortString(),
                    worldState(context, worldPos),
                    entity == null ? "absent (provisional claim)" : entity.getUUID().toString(),
                    entity == null ? "provisional" : "entity",
                    pose == null ? "?" : pose.describe());
        } catch (RuntimeException | LinkageError failed) {
            // This runs inside Create's assembly. A diagnostic that can take the assembly down is
            // worse than no diagnostic, so nothing is allowed out of here.
            diag("startMoving", context, "failed: " + failed);
        }
    }

    /**
     * A host block has stopped: put it down, idempotently, matching either identity.
     *
     * <p>The landing position comes from the contraption's transform - which is where the block is
     * going to be put back by Create a moment later - and the release itself is idempotent, because
     * this callback fires four to five times for one motion with identical arguments.
     *
     * <p>Two things happen before the release, in this order:
     * <ol>
     *   <li><b>Reconcile.</b> If the claim is still filed under a provisional identity and the entity
     *       exists now, it is re-labelled onto the entity here. The put-down then lands on the same
     *       entry the rest of the mod has been solving and drawing.</li>
     *   <li><b>Dual-path release.</b> {@code CarrierStore.stopMoving} tries the entity's UUID first and
     *       the provisional id second, so a structure that came apart before it ever had an entity still
     *       gets its components back. Both paths are logged, so neither can fail silently.</li>
     * </ol>
     */
    private static void onStopMoving(MovementContext context) {
        try {
            if (!CarrierStore.isActive()) {
                return;
            }
            ServerLevel level = serverLevel(context);
            Contraption contraption = context.contraption;
            AbstractContraptionEntity entity = CarrierResolver.entityOf(contraption);
            oneShotContext("stopMoving", context, entity);

            if (context.localPos == null) {
                diag("stopMoving", context, "no local position");
                return;
            }
            if (level == null) {
                diag("stopMoving", context, "no server level");
                return;
            }
            if (defersToSable("stopMoving", context, entity)) {
                return;
            }

            UUID entityUuid = entity == null ? null : entity.getUUID();
            // The identity this claim may still be filed under, if it was made before the entity
            // existed. Asked of the registry rather than remembered here, so the same contraption object
            // answers the same id on every callback of one assembly. An entity with no provisional
            // record - the ordinary case - costs one identity-map lookup and allocates nothing.
            CarrierId provisional = contraption == null ? null
                    : (CarrierResolver.hasProvisional(contraption)
                            ? CarrierResolver.provisionalFor(contraption)
                            : (entityUuid == null ? CarrierResolver.provisionalFor(contraption) : null));
            if (provisional != null && entityUuid != null) {
                // Phase two at the put-down: settle the name first, so the release below finds the live
                // entry under the entity's UUID rather than falling through to the provisional one.
                CarrierStore.reconcile(level, provisional, entityUuid,
                        CarrierIdentity.Phase.PROVISIONAL);
            }

            // DEFERRED, NOT IMMEDIATE. `stopMoving` fires for a direction change as well as for a real
            // put-down: the author's bearing reversed at full speed, this callback ran, the host was
            // released on the spot, and the component vanished off a block that was still AIR in the
            // world with the contraption entity still present - see PendingRelease. The release now waits
            // for positive evidence, and anything that says the structure is still being driven cancels
            // it. A genuine put-down is unchanged: the world block comes back and the release lands there.
            BlockPos worldBlock = statePos(context);
            var outcome = CarrierStore.stopMovingOrDefer(level, entityUuid, provisional,
                    CarrierKind.CONTRAPTION, context.localPos,
                    worldBlock == null ? context.localPos : worldBlock);
            boolean deferred = outcome.status() == CarriedHostIndex.ReleaseStatus.UNKNOWN_CARRIER;
            if (provisional != null
                    && outcome.status() != CarriedHostIndex.ReleaseStatus.UNKNOWN_CARRIER) {
                // The claim is settled, or its hosts are already back in the world; either way nothing
                // is waiting on this provisional identity any more.
                CarrierResolver.forgetProvisional(provisional);
            }
            RCConfig.LOGGER.info("[redstonecircuit] carrier stopMoving contraption local={} "
                            + "statePos={} anchor={} worldState={} entity={} provisional={} deferred={} "
                            + "outcome={}",
                    context.localPos.toShortString(),
                    statePos(context) == null ? "?" : statePos(context).toShortString(),
                    anchor(context) == null ? "?" : anchor(context).toShortString(),
                    statePos(context) == null ? "?" : worldState(context, statePos(context)),
                    entityUuid == null ? "absent" : entityUuid.toString(),
                    provisional == null ? "-" : provisional.shortId(),
                    // "deferred=true" is the whole of this round's fix, and it is a field rather than a
                    // sentence so that a reader can count it: it means the structure asked to stand down
                    // and the world did not agree that it had. A real put-down says deferred=false and is
                    // the only case in which a host was released here.
                    deferred,
                    outcome.describe());
        } catch (RuntimeException | LinkageError failed) {
            diag("stopMoving", context, "failed: " + failed);
        }
    }

    // ------------------------------------------------- one host, one carrier --

    /** Contraption objects already reported as standing down, so the line appears once per structure. */
    private static final Set<Contraption> DEFERRED = java.util.Collections
            .newSetFromMap(new java.util.IdentityHashMap<>());

    /**
     * <b>Whether this Create contraption is really a Sable sub-level, in which case the Create path
     * does nothing at all.</b>
     *
     * <h2>The defect this exists for</h2>
     * A physics-assembled structure - Create Aeronautics' physics assembler, the physics staff - is a
     * Sable sub-level <em>and</em> a Create contraption entity, and the two are created with the
     * <b>same UUID</b>. That is visible in the author's log as one structure named twice:
     *
     * <pre>
     *   carrier claim c:6c60aeef world=2775, 77, -1395 local=20481032, 127, 20524040 type=TORCH …
     *   sable claim pose s:6c60aeef local=20481032, 127, 20524040 computedWorld=(2775.000, …) …
     * </pre>
     *
     * <p>Both halves therefore filed a claim for the same host block, in two different local spaces, and
     * neither could see the other. The two then fought over it for as long as the machine existed, at a
     * rate of about twice a second:
     *
     * <pre>
     *   carrier claim c:6c60aeef …                                   (Create picks it up)
     *   carrier c:6c60aeef released wholesale (structure is gone (orphan sweep)) LANDED … moved=1
     *   carrier claim c:6c60aeef …                                   (and again)
     * </pre>
     *
     * The Create half could never answer a pose for that carrier - the UUID belongs to a sub-level, not
     * to a contraption - so its own orphan sweep declared the still-flying structure gone, wrote the
     * component back into the world and marked the record landed. On the client that is "the component
     * stayed where it was".
     *
     * <h2>Why the Create half is the one that stands down</h2>
     * The sub-level is what actually holds the blocks, and it is the structure whose pose can be read
     * for them; the contraption entity riding on it is a Create-side view of the same thing. So Sable
     * owns it, and this half returns before the claim - and equally before the put-down, the release and
     * the solve, so there is exactly one claimant and exactly one place a pose comes from.
     *
     * <p>The test is the entity UUID against Sable's own container ({@code isSubLevelBacked}), not a
     * guess about positions: the two halves disagree about where the blocks are (plot coordinates
     * against anchor-relative ones), so nothing positional could decide it.
     *
     * @return true when the caller must do nothing at all for this callback
     */
    private static boolean defersToSable(String event, MovementContext context,
                                         @Nullable AbstractContraptionEntity entity) {
        if (entity == null || !CarrierResolver.isSubLevelBacked(entity.getUUID())) {
            return false;
        }
        Contraption contraption = context.contraption;
        if (contraption != null && DEFERRED.add(contraption)) {
            RCConfig.LOGGER.info("[redstonecircuit] carrier {} contraption local={}: this contraption "
                            + "entity {} is a SABLE SUB-LEVEL under the same UUID, so the Create path "
                            + "stands down (no claim, no solve, no release) and the Sable path owns the "
                            + "structure. Two claims for one host is what made the component stay behind",
                    event, context.localPos == null ? "?" : context.localPos.toShortString(),
                    entity.getUUID());
        }
        return true;
    }

    // -------------------------------------------------------------- helpers --

    /**
     * A host block's actor ticked.
     *
     * <p>Two jobs, in this order:
     * <ol>
     *   <li><b>Settle the identity.</b> This callback runs once the contraption entity exists, so it is
     *       the earliest reliable place to re-label a provisional claim onto the entity. Doing it here
     *       means a machine that was claimed during assembly is drawn correctly on its very first moving
     *       tick.</li>
     *   <li><b>Solve the structure.</b> The callback answers one question - "which structure is this
     *       block riding" - and hands the whole structure to the carrier layer to be solved <b>once</b>.
     *       Solving per actor would run a full network solve for every stone in the machine;
     *       {@code CarrierStore#tickHost} cheapens that to one hash lookup per actor plus one solve per
     *       structure, and the same path is what the level tick already uses.</li>
     * </ol>
     *
     * <p>Note that a missing entity is <b>not</b> a reason to return: the reconciliation above is
     * skipped, but the solve below is not, because the claim may legitimately still be provisional and
     * the hosts it protects still need solving.
     */
    private static void onTick(MovementContext context) {
        try {
            if (!CarrierStore.isActive()) {
                return;
            }
            ServerLevel level = serverLevel(context);
            Contraption contraption = context.contraption;
            AbstractContraptionEntity entity = CarrierResolver.entityOf(contraption);
            oneShotContext("tick", context, entity);
            if (level == null || contraption == null) {
                return;
            }
            if (defersToSable("tick", context, entity)) {
                return;
            }

            CarrierId carrier;
            if (entity != null) {
                if (CarrierResolver.hasProvisional(contraption)) {
                    CarrierId provisional = CarrierResolver.provisionalFor(contraption);
                    CarrierStore.reconcile(level, provisional, entity.getUUID(),
                            CarrierIdentity.Phase.PROVISIONAL);
                }
                carrier = CarrierId.contraption(entity.getUUID());
            } else {
                // Still no entity: the claim is under the provisional identity, and that is the carrier
                // whose hosts have to be solved.
                carrier = CarrierResolver.provisionalFor(contraption);
            }
            if (!RCConfig.carrierSolveEnabled()) {
                return;
            }
            CarrierStore.tickHost(level, carrier);
        } catch (RuntimeException | LinkageError failed) {
            // Runs inside Create's actor tick. A failure here must not take a moving machine - and
            // therefore the tick it is moving in - down.
            if (RCConfig.debugLog()) {
                diag("tick", context, "failed: " + failed);
            }
        }
    }

    private static ServerLevel serverLevel(MovementContext context) {
        Level world = context.world;
        return world instanceof ServerLevel server ? server : null;
    }

    /**
     * The world position this contraption block came from: {@code contraption.anchor + localPos}.
     *
     * <p>Confirmed by the P1 diagnostics: the anchor does not move while a contraption does, the same
     * value comes back on every {@code stopMoving} call, and {@code storeHasComponent=true} on that
     * position while the machine is in flight proves the arithmetic recovers the original block.
     */
    private static BlockPos statePos(MovementContext context) {
        Contraption contraption = context.contraption;
        if (contraption == null || contraption.anchor == null || context.localPos == null) {
            return null;
        }
        return contraption.anchor.offset(context.localPos);
    }

    private static BlockPos anchor(MovementContext context) {
        Contraption contraption = context.contraption;
        return contraption == null ? null : contraption.anchor;
    }

    private static String worldState(MovementContext context, BlockPos pos) {
        Level world = context.world;
        if (world == null || pos == null) {
            return "unknown";
        }
        try {
            BlockState state = world.getBlockState(pos);
            return state + (state.isAir() ? " (AIR)" : " (still there)");
        } catch (RuntimeException failed) {
            return "unreadable: " + failed;
        }
    }

    /** Where the contraption's own transform says this block is now, for the log. */
    private static String contraptionSays(AbstractContraptionEntity entity, BlockPos localPos) {
        Vec3 at = CarrierResolver.worldOf(entity, localPos);
        return at == null ? "?" : String.format(java.util.Locale.ROOT, "(%.3f, %.3f, %.3f)",
                at.x, at.y, at.z);
    }

    /** One diagnostic line about a callback that could not do its job. */
    private static void diag(String event, MovementContext context, String reason) {
        try {
            RCConfig.LOGGER.info("[redstonecircuit] carrier {} contraption local={} skipped: {} "
                            + "(contraptionSays={} entity={})",
                    event, context.localPos == null ? "?" : context.localPos.toShortString(), reason,
                    CarrierResolver.worldOf(CarrierResolver.entityOf(context.contraption),
                            context.localPos),
                    CarrierResolver.entityOf(context.contraption) == null ? "absent" : "present");
        } catch (RuntimeException | LinkageError ignored) {
            // A diagnostic about a diagnostic. Nothing useful can be added, and it must not throw.
        }
    }

    // ------------------------------------------------- one-shot identification --

    /** Contraption objects already printed in full, so the dump appears once per structure. */
    private static final Set<Contraption> DIAGNOSED = java.util.Collections
            .newSetFromMap(new java.util.IdentityHashMap<>());

    /**
     * <b>Prints everything a {@code MovementContext} can say about a carrier's identity, once per
     * contraption object.</b>
     *
     * <p>This is the measurement the two-phase identity rests on, and it is deliberately printed on the
     * first callback of every structure rather than behind {@code traceCarrier}: one line per assembly
     * is affordable, and "was the entity there at {@code startMoving}?" is the question that decides
     * whether the fallback path is doing anything at all.
     *
     * <p>Every field is printed, not just the ones that are expected to matter, because the previous
     * round's failure was exactly a guess about which field was missing. Which fields are null, whether
     * the contraption is one object across callbacks, whether an entity exists, the anchor, the bounds,
     * how many blocks the structure holds - all of it, on one line.
     */
    private static void oneShotContext(String event, MovementContext context,
                                       @Nullable AbstractContraptionEntity entity) {
        try {
            Contraption contraption = context.contraption;
            if (contraption == null) {
                if (DIAGNOSED.add(contraption)) {
                    RCConfig.LOGGER.info("[redstonecircuit] carrier identity {}: contraption=null "
                            + "world={} localPos={} state={}", event, context.world != null,
                            context.localPos == null ? "null" : context.localPos.toShortString(),
                            context.state);
                }
                return;
            }
            boolean first = DIAGNOSED.add(contraption);
            if (!first) {
                // One line per structure is the point; a per-callback dump would drown the log. The only
                // thing worth repeating is the first time an entity appears where there had never been
                // one - that is the reconciliation event, and it is logged by CarrierStore itself.
                return;
            }
            Boolean seenBefore = ENTITY_SEEN.put(contraption, entity != null);
            AABB bounds = contraption.bounds;
            Map<BlockPos, ?> blocks = contraption.getBlocks();
            RCConfig.LOGGER.info("[redstonecircuit] carrier identity at {}: contraption={} "
                            + "(identityHash={} entity={} entitySeenBefore={}) localPos={} statePos={} "
                            + "anchor={} bounds={} blocks={} actors={} world={} state={} "
                            + "provisionalNeeded={}",
                    event, contraption.getClass().getSimpleName(),
                    System.identityHashCode(contraption),
                    entity == null ? "null" : entity.getUUID() + " class=" + entity.getClass().getSimpleName(),
                    seenBefore,
                    context.localPos == null ? "null" : context.localPos.toShortString(),
                    statePos(context) == null ? "?" : statePos(context).toShortString(),
                    contraption.anchor == null ? "null" : contraption.anchor.toShortString(),
                    bounds == null ? "null"
                            : String.format(java.util.Locale.ROOT, "[%.2f %.2f %.2f .. %.2f %.2f %.2f]",
                                    bounds.minX, bounds.minY, bounds.minZ, bounds.maxX, bounds.maxY,
                                    bounds.maxZ),
                    blocks == null ? "null" : Integer.toString(blocks.size()),
                    contraption.getActors() == null ? "null"
                            : Integer.toString(contraption.getActors().size()),
                    context.world == null ? "null" : context.world.dimension().location(),
                    context.state,
                    entity == null ? "YES - claimed under a provisional id" : "no");
        } catch (RuntimeException | LinkageError failed) {
            // A diagnostic must never take an assembly down, and this one runs on every first callback.
            try {
                RCConfig.LOGGER.warn("[redstonecircuit] carrier identity dump at {} failed: {}", event,
                        failed.toString());
            } catch (RuntimeException | LinkageError ignored) {
                // Nothing left to do.
            }
        }
    }
}

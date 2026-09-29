package com.jiangyuefengyu.redstonecircuit.compat.physics;

import java.util.Map;
import java.util.UUID;

import com.jiangyuefengyu.redstonecircuit.RCConfig;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierAccess;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierId;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierIdentity;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierPose;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierStore;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.Contraption;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.joml.Vector3d;

/**
 * The compat half's answer to "where is this structure, and which one owns this block".
 *
 * <h2>What it implements, and for whom</h2>
 * {@link CarrierStore.CarrierSource} - the two questions the carrier layer needs and cannot answer
 * itself, because both are about Create and Sable rather than about redstone. This class is the only
 * place in the mod that reads a contraption's transform or a sub-level's pose.
 *
 * <h2>A Create contraption's pose, exactly</h2>
 * Create 6.0.10 does not hand out a quaternion. It hands out
 * {@code AbstractContraptionEntity#applyRotation(Vec3, float)}, and the whole of its coordinate
 * contract is visible in the decompiled {@code toGlobalVector}:
 *
 * <pre>
 *   toGlobalVector(v) = applyRotation(v - 0.5) + 0.5 + getAnchorVec()
 * </pre>
 *
 * <p>Two things follow from that, and both matter:
 *
 * <ol>
 *   <li>The pose is {@code position = getAnchorVec()}, {@code pivot = (0.5, 0.5, 0.5)},
 *       {@code scale = 1} - which is exactly the shape {@link CarrierPose} has, so the renderer and
 *       the landing arithmetic use the same numbers Create itself uses to place actors and
 *       collisions.</li>
 *   <li>The orientation can be recovered <b>without</b> re-deriving Create's internal Euler order:
 *       {@code applyRotation} is linear, so asking it where the three basis vectors go gives the
 *       rotation matrix column by column. That is robust to a rotation state whose convention changes
 *       between Create versions, and it is the reason {@link CarrierPose#fromBasisColumns} exists at
 *       all.</li>
 * </ol>
 *
 * <h2>A Sable sub-level's pose, exactly</h2>
 * Sable does hand one out: {@code SubLevelAccess#logicalPose()} is a {@code Pose3dc} whose
 * {@code transformPosition} is the same {@code position + orientation * (local - pivot) * scale} the
 * P1 diagnostics confirmed round-trips perfectly. That path needs no reconstruction at all.
 *
 * <h2>Nothing here runs unless the mods are installed</h2>
 * The class is only ever loaded from {@link PhysicsCompatBackend}, which is itself only reached by
 * name after a {@code ModList} check. Every entry point checks again, because "is Create here" can
 * differ per level in a way {@code ModList} cannot express.
 */
final class CarrierResolver implements CarrierStore.CarrierSource {

    /** Lazily created; the P2 access shares this resolver's entity and sub-level lookups. */
    @Nullable
    private CarrierAccess structureAccess;

    /**
     * The stand-in identity of every contraption that has been claimed before its entity existed.
     *
     * <p>Keyed by the {@code Contraption} object itself, through an {@link java.util.IdentityHashMap}:
     * Create's {@code Contraption#startMoving} iterates one {@code actor} list and reuses the very same
     * {@code Contraption} instance for every callback, so object identity is what makes the provisional
     * id the same on {@code startMoving}, on the actor tick and on {@code stopMoving}. That is the
     * whole stability argument for the fallback identity, and it is why nothing here keys on a position.
     *
     * <p><b>Static</b>, because the {@link MovementBehaviour} callbacks reach this class through its
     * static entry points while {@code CarrierStore} reaches the same registry through the installed
     * source instance. Two copies of this map would be the exact bug the fallback identity exists to
     * prevent - a claim made under one id and looked up under another - so there is only ever one.
     *
     * <p>An identity map (not a {@code WeakHashMap}) because a weak key can be collected between the
     * claim and the tick that settles it, which would strand the hosts under a name nothing can find; a
     * strong key keeps the contraption alive for at most the handful of ticks the settlement takes.
     * Entries are dropped on settlement and bounded in case a contraption somehow never gets one.
     */
    private static final java.util.Map<Contraption, Provisional> PROVISIONALS =
            new java.util.IdentityHashMap<>();

    /** Source of the per-structure serial in every provisional id. */
    private static long provisionalSerial;

    /**
     * A provisional claim and the entity, if any, seen with it.
     *
     * <p>{@code entity} is refreshed from the contraption itself on every reconcile question, so the
     * moment Create attaches one - or replaces it - this record knows.
     */
    private static final class Provisional {

        private final CarrierId id;
        private final BlockPos anchor;
        private final int blockCount;
        @Nullable
        private volatile AbstractContraptionEntity entity;

        Provisional(CarrierId id, @Nullable BlockPos anchor, int blockCount,
                    @Nullable AbstractContraptionEntity entity) {
            this.id = id;
            this.anchor = anchor;
            this.blockCount = blockCount;
            this.entity = entity;
        }
    }

    /** Installs this resolver into the carrier layer, if either mod can carry anything. */
    static void install() {
        boolean sable = PhysicsCompat.sableLoaded();
        boolean create = PhysicsCompat.createLoaded();
        if (!sable && !create) {
            return;
        }
        CarrierResolver resolver = new CarrierResolver();
        CarrierStore.install(resolver);
        // Phase two of the two-phase identity: the carrier layer asks this when it needs to know
        // whether a provisionally named structure has an entity yet. Installed here, beside the source,
        // so the carrier layer never has to name a Create type.
        CarrierStore.installProvisionalClaims(CarrierResolver::entityFor);
        // The second question the orphan sweep needs, and the one whose absence let it declare a live
        // sub-level gone: "is the structure this carrier names still there", answered from Sable's own
        // container rather than from a pose query that cannot see it.
        CarrierStore.installSubLevelLiveness(CarrierResolver::subLevelAlive);
        RCConfig.LOGGER.info("[redstonecircuit] carrier resolver installed: create={} sable={}",
                create, sable);
    }

    /**
     * {@code CarrierSource#access()}: how to read a structure's own blocks.
     *
     * <p>P2's one requirement of this class that P1 did not have. Handed back as a lazily created
     * companion rather than built per call; it holds nothing but a back-reference.
     */
    @Override
    public CarrierAccess access() {
        if (structureAccess == null) {
            structureAccess = new CarrierStructureAccess(this);
        }
        return structureAccess;
    }

    @Override
    public String describe() {
        return "create=" + PhysicsCompat.createLoaded() + ", sable=" + PhysicsCompat.sableLoaded();
    }

    /**
     * The centre of a structure's world bounds: the "where is this machine, roughly" answer.
     *
     * <p>Needed by {@link com.jiangyuefengyu.redstonecircuit.carrier.CarrierDrawCheck}, whose whole
     * subject is the gap between where a component is drawn and where the structure is. Both mods say
     * it the same way - a world-space bounding box - so both halves are the midpoint of one, and the
     * numbers come out directly comparable between a Create carrier and a Sable one.
     *
     * <p>{@code null} whenever the structure cannot be named, which the draw check reports as "cannot
     * compare" rather than as a fault.
     */
    @Override
    @Nullable
    public Vector3d centreOf(ServerLevel level, CarrierId carrier) {
        try {
            if (carrier.isContraption()) {
                if (!PhysicsCompat.createLoaded()) {
                    return null;
                }
                net.minecraft.world.entity.Entity entity = level.getEntity(carrier.uuid());
                if (!(entity instanceof AbstractContraptionEntity contraption)) {
                    return null;
                }
                AABB box = contraption.getBoundingBox();
                return box == null ? null : new Vector3d(
                        (box.minX + box.maxX) / 2.0, (box.minY + box.maxY) / 2.0,
                        (box.minZ + box.maxZ) / 2.0);
            }
            if (!PhysicsCompat.sableLoaded()) {
                return null;
            }
            SubLevel subLevel = subLevelOf(level, carrier);
            if (subLevel == null) {
                return null;
            }
            dev.ryanhcode.sable.companion.math.BoundingBox3dc box = subLevel.boundingBox();
            return box == null ? null : new Vector3d(
                    (box.minX() + box.maxX()) / 2.0, (box.minY() + box.maxY()) / 2.0,
                    (box.minZ() + box.maxZ()) / 2.0);
        } catch (RuntimeException | LinkageError failed) {
            RCConfig.LOGGER.warn("[redstonecircuit] carrier {} bounds query failed: {}",
                    carrier.shortId(), failed.toString());
            return null;
        }
    }

    // ------------------------------------------------------------------ poses --

    @Override
    @Nullable
    public CarrierPose poseOf(ServerLevel level, CarrierId carrier) {
        try {
            if (carrier.isContraption()) {
                return contraptionPose(level, carrier);
            }
            return subLevelPose(level, carrier);
        } catch (RuntimeException | LinkageError failed) {
            // A pose query runs once per tick per carrier; a diagnostic that can take the server down
            // is worse than a machine whose components stop being drawn for a tick.
            RCConfig.LOGGER.warn("[redstonecircuit] carrier {} pose query failed: {}",
                    carrier.shortId(), failed.toString());
            return null;
        }
    }

    /**
     * A Create contraption's pose, from the entity's own anchor and rotation.
     *
     * <p>{@code null} when the contraption entity has left the level - which is exactly the signal
     * {@link CarrierStore}'s orphan sweep needs to decide that a structure is really gone rather than
     * merely far away.
     */
    @Nullable
    private CarrierPose contraptionPose(ServerLevel level, CarrierId carrier) {
        if (!PhysicsCompat.createLoaded()) {
            return null;
        }
        net.minecraft.world.entity.Entity entity = level.getEntity(carrier.uuid());
        if (!(entity instanceof AbstractContraptionEntity contraption)) {
            return null;
        }
        return poseOf(contraption);
    }

    /**
     * The pose Create itself would use for this contraption entity.
     *
     * <p>Package-visible so the same arithmetic has exactly one implementation: the diagnostic hook
     * uses it too, and "what the log says the pose is" has to be "what the renderer draws".
     */
    @Nullable
    static CarrierPose poseOf(AbstractContraptionEntity contraption) {
        Vec3 anchor = contraption.getAnchorVec();
        if (anchor == null) {
            return null;
        }
        // applyRotation is linear, so three basis answers are the rotation matrix. Asking for the
        // centre as well would be the affine form; a pure rotation has nothing to learn from it,
        // because toGlobalVector has already taken the pivot out.
        Vec3 x = contraption.applyRotation(new Vec3(1.0, 0.0, 0.0), 0.0F);
        Vec3 y = contraption.applyRotation(new Vec3(0.0, 1.0, 0.0), 0.0F);
        Vec3 z = contraption.applyRotation(new Vec3(0.0, 0.0, 1.0), 0.0F);
        // The anchor goes in as-is: CarrierPose.fromContraptionBasis knows that Create's own contract
        // puts the pose's origin half a block past the anchor, and applies that shift itself.
        return CarrierPose.fromContraptionBasis(new Vector3d(anchor.x, anchor.y, anchor.z),
                new Vector3d(x.x, x.y, x.z), new Vector3d(y.x, y.y, y.z), new Vector3d(z.x, z.y, z.z));
    }

    /** A Sable sub-level's pose, straight from Sable - no reconstruction needed. */
    @Nullable
    private CarrierPose subLevelPose(ServerLevel level, CarrierId carrier) {
        SubLevel subLevel = subLevelOf(level, carrier);
        if (subLevel == null) {
            return null;
        }
        return fromPose3d(subLevel.logicalPose());
    }

    /**
     * <b>P1.56: the pose Sable is DRAWING this sub-level at, in this frame - Sable's own answer, not a
     * reconstruction.</b>
     *
     * <p>The author's report after P1.55 was that the components now <em>flicker</em> between the leading
     * position and the block's. That is the shape of a reconstruction that cannot be exact: Sable's client
     * sub-level keeps a <b>buffer of snapshots</b> and samples it by time
     * ({@code SubLevelSnapshotInterpolator#getSampleAt} / {@code #tick}), so the pose it draws a structure
     * with is its own timeline - not "the last two poses the server sent us, interpolated by the frame's
     * partial tick", which is all this side can rebuild through its own channel. The two timelines beat
     * against each other, and what the player sees is the components crossing the block.
     *
     * <p>The exact answer <b>is</b> available on the client, so this asks for it:
     * {@code ClientSubLevel#renderPose(float)} is the very pose Sable draws the structure with, computed
     * from that same buffer. Using it makes "where the components are" and "where the machine is" the same
     * number in every frame, which is the only way this can be exact rather than nearly right.
     *
     * <p>{@code null} - Sable absent, a Create contraption, a sub-level the client does not hold, or an
     * unreadable pose - leaves the caller with the network pose it already had, so no path regresses to
     * "drawn nowhere".
     */
    @Nullable
    public static CarrierPose renderPoseOf(Level level, CarrierId carrier, float partialTick) {
        if (carrier == null || !carrier.isSubLevel() || !(level instanceof ClientLevel clientLevel)) {
            return null;
        }
        SubLevelContainer container = SubLevelContainer.getContainer(clientLevel);
        SubLevel subLevel = container == null ? null : container.getSubLevel(carrier.uuid());
        return subLevel instanceof ClientSubLevel client
                ? fromPose3d(client.renderPose(partialTick))
                : null;
    }

    /**
     * One {@code Pose3dc} as a {@link CarrierPose}, or {@code null} when it cannot be read.
     *
     * <p>The one conversion, shared by the logical pose the server sends and the render pose the client
     * draws with - the two must be the same arithmetic or a claim verified against one would land
     * somewhere else than the other draws.
     */
    @Nullable
    private static CarrierPose fromPose3d(@Nullable Pose3dc pose) {
        if (pose == null || pose.position() == null || pose.orientation() == null) {
            return null;
        }
        Vector3d pivot = pose.rotationPoint() == null
                ? new Vector3d(0.5, 0.5, 0.5)
                : new Vector3d(pose.rotationPoint());
        double scale = pose.scale() == null ? 1.0 : pose.scale().x();
        Quaterniond orientation = new Quaterniond(pose.orientation());
        if (!orientation.isFinite() || orientation.lengthSquared() < 1.0E-9) {
            return null;
        }
        Vector3d position = new Vector3d(pose.position());
        if (!position.isFinite() || !pivot.isFinite()) {
            return null;
        }
        return new CarrierPose(position, orientation.normalize(), pivot, scale == 0.0 ? 1.0 : scale);
    }

    /**
     * The sub-level a carrier names, in one particular level.
     *
     * <p>Package-visible rather than private because {@link CarrierStructureAccess} needs the plot's
     * block accessor, and a second implementation of "which sub-level is this" is exactly the kind of
     * thing that drifts away from this one.
     */
    @Nullable
    SubLevel subLevelOf(ServerLevel level, CarrierId carrier) {
        if (!PhysicsCompat.sableLoaded()) {
            return null;
        }
        var container = dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(level);
        if (container == null) {
            return null;
        }
        SubLevel subLevel = container.getSubLevel(carrier.uuid());
        return subLevel == null || subLevel.isRemoved() ? null : subLevel;
    }

    /**
     * Whether the sub-level a carrier names exists right now, in any loaded level.
     *
     * <p>The second question the carrier layer's orphan sweep asks, and the one this round added. The
     * sweep used to treat "the pose query returned null" as proof that a structure was gone, which is
     * only sound if the pose query can answer for every live structure. It cannot: {@link #poseOf}
     * dispatches on {@link CarrierKind}, so a carrier filed as a Create contraption whose UUID really
     * belongs to a Sable sub-level - which is what a physics-assembled structure looks like, one UUID
     * shared by the contraption entity and the sub-level - is answered {@code null} for the whole
     * flight. The sweep then released the hosts of a machine that was still in the air.
     *
     * <p>Answered from the container itself, so "the sub-level is still here" does not depend on
     * anybody's pose arithmetic. Package-visible and static because {@code CarrierStore} reaches it
     * through a method reference installed at {@link #install}.
     */
    static boolean subLevelAlive(CarrierId carrier) {
        if (carrier == null || !carrier.isSubLevel() || !PhysicsCompat.sableLoaded()) {
            return false;
        }
        try {
            for (ServerLevel level : levels()) {
                var container = dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(level);
                if (container == null) {
                    continue;
                }
                SubLevel subLevel = container.getSubLevel(carrier.uuid());
                if (subLevel != null && !subLevel.isRemoved()) {
                    return true;
                }
            }
            return false;
        } catch (RuntimeException | LinkageError failed) {
            // "I could not tell" must never be read as "it is alive": a broken liveness query is not a
            // reason to keep a component stranded inside a structure nobody can name.
            return false;
        }
    }

    /**
     * Whether a Create contraption entity's UUID is the UUID of a live Sable sub-level.
     *
     * <p><b>The test the Create path uses to stand down.</b> A physics-assembled structure - Create
     * Aeronautics' assembler, the physics staff - is a Sable sub-level <em>and</em> a Create contraption
     * entity, and the two are created with the same UUID. Both halves of this mod therefore have a
     * legitimate claim to file for the same host blocks, in two different local spaces (the sub-level's
     * plot coordinates and the contraption's anchor-relative ones), and neither can see the other.
     *
     * <p>One of them has to be the owner, and it is Sable: the sub-level is the thing that actually
     * holds the blocks and the pose that can be read for them. So the Create half asks this, and when
     * the answer is yes it does not claim, does not solve and does not release - the whole structure is
     * left to the Sable path, which reaches it through {@code SubLevelContainer} and claims the same
     * blocks under {@code s:<the same uuid>}.
     *
     * <p>Deliberately asked of the <b>entity's</b> UUID rather than of a world position: the two halves
     * of a physics assembly do not agree on where their blocks are, so a position-based test would be
     * guessing. The UUID match is exact.
     */
    static boolean isSubLevelBacked(@Nullable UUID entityUuid) {
        if (entityUuid == null || !PhysicsCompat.sableLoaded() || !PhysicsCompat.createLoaded()) {
            // Without Sable there is no sub-level to be backed by, and without Create there is no
            // contraption to stand down - so this can only ever be false, and asking costs nothing.
            return false;
        }
        return subLevelAlive(CarrierId.subLevel(entityUuid));
    }

    // ------------------------------------------------- reads the P2 half needs --

    /**
     * The sub-level a carrier names, found without being told which level it is in.
     *
     * <p>Needed because the solve is driven from the carried index, which is keyed by carrier rather
     * than by level. Sable's own containers are per level, so every loaded level is asked; a server
     * has a handful of levels and the whole lookup happens once per solve, not per block.
     */
    @Nullable
    SubLevel subLevelOf(CarrierId carrier) {
        for (ServerLevel level : levels()) {
            SubLevel found = subLevelOf(level, carrier);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /**
     * The contraption entity a carrier names, found without being told which level it is in.
     *
     * <p>{@code Entity#getUUID()} is unique across the server, so this is an exact lookup rather than
     * a guess; the alternative - remembering the entity from the assembly callback - would go stale
     * the moment the machine crossed a dimension.
     */
    @Nullable
    AbstractContraptionEntity contraptionEntityOf(CarrierId carrier) {
        if (!PhysicsCompat.createLoaded()) {
            return null;
        }
        for (ServerLevel level : levels()) {
            net.minecraft.world.entity.Entity entity = level.getEntity(carrier.uuid());
            if (entity instanceof AbstractContraptionEntity contraption) {
                return contraption;
            }
        }
        return null;
    }

    /** Every loaded server level, or an empty list when the server is not up. */
    private static Iterable<ServerLevel> levels() {
        net.minecraft.server.MinecraftServer server =
                net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        return server == null ? java.util.List.of() : server.getAllLevels();
    }

    // -------------------------------------------------------------- who owns --

    /**
     * Which structure owns the block at a world position, or {@code null}.
     *
     * <p><b>The Create half is not answered here, on purpose.</b> The assembly hook is handed the
     * contraption entity and the block's local coordinate outright, so it passes them in rather than
     * asking anybody to guess - which is both exact and free. What is left is the case where the
     * structure is a Sable sub-level rather than a contraption, and Sable's own "which plot covers this
     * chunk" query is the only lookup that exists for it.
     *
     * <p>The P1 diagnostics proved that query is <b>not</b> an "am I inside a moving structure"
     * predicate - an ordinary block on the ground comes back non-null, because it answers "which plot
     * owns this chunk". So the answer here is narrowed twice: the point must be inside the structure's
     * world bounds, and the structure must be a live, plot-backed sub-level. Even then the caller only
     * ever uses the answer for a block that has a component stored inside it.
     */
    @Override
    @Nullable
    public CarrierId carrierAt(ServerLevel level, BlockPos worldPos) {
        if (!PhysicsCompat.sableLoaded()) {
            return null;
        }
        try {
            var container = dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(level);
            if (container == null) {
                return null;
            }
            for (SubLevel candidate : container.getAllSubLevels()) {
                if (candidate.isRemoved() || candidate.boundingBox() == null) {
                    continue;
                }
                if (!candidate.boundingBox().contains(worldPos.getX() + 0.5, worldPos.getY() + 0.5,
                        worldPos.getZ() + 0.5)) {
                    continue;
                }
                if (candidate.getPlot() == null) {
                    continue;
                }
                return CarrierId.subLevel(candidate.getUniqueId());
            }
            return null;
        } catch (RuntimeException | LinkageError failed) {
            RCConfig.LOGGER.warn("[redstonecircuit] carrier lookup at {} failed: {}",
                    worldPos.toShortString(), failed.toString());
            return null;
        }
    }

    /**
     * {@code CarrierSource#plotOwnerAt}: which sub-level's own block storage contains this level
     * position.
     *
     * <p>The frame is the one {@link CarrierStructureAccess} documents: a sub-level's local coordinates
     * are the level's own block coordinates inside the plot region, and Sable's {@code LevelPlot}
     * reports exactly that region as its bounding box. So the test is a containment test against each
     * live sub-level's plot bounds - one comparison per sub-level, and a handful of sub-levels - with
     * no pose arithmetic and no container walk beyond the list Sable already keeps.
     *
     * <p>Deliberately <b>not</b> {@code Sable.HELPER.getContaining}: P1 measured that query as "which
     * plot owns this chunk", which is non-null for ordinary ground blocks as well, so it cannot answer
     * "is this position one of a structure's own blocks". The plot's own bounds can.
     */
    @Override
    @Nullable
    public CarrierId plotOwnerAt(ServerLevel level, BlockPos levelPos) {
        if (!PhysicsCompat.sableLoaded() || level == null || levelPos == null) {
            return null;
        }
        try {
            var container = dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(level);
            if (container == null) {
                return null;
            }
            BoundingBox3ic bounds = null;
            for (SubLevel candidate : container.getAllSubLevels()) {
                if (candidate == null || candidate.isRemoved()) {
                    continue;
                }
                LevelPlot plot = candidate.getPlot();
                bounds = plot == null ? null : plot.getBoundingBox();
                if (bounds == null || !bounds.contains(levelPos.getX(), levelPos.getY(),
                        levelPos.getZ())) {
                    continue;
                }
                return CarrierId.subLevel(candidate.getUniqueId());
            }
            return null;
        } catch (RuntimeException | LinkageError failed) {
            // "I could not tell" is not an answer: no drop is better than a drop at a guessed position.
            RCConfig.LOGGER.warn("[redstonecircuit] plot owner lookup at {} failed: {}",
                    levelPos.toShortString(), failed.toString());
            return null;
        }
    }

    // ------------------------------------------------------------ one helper --
    /**
     * The contraption entity holding a block, given the contraption object the hook was handed.
     *
     * <p>Kept here rather than in the hook so every Create-typed expression in the compat half is in
     * one file, and so the hook reads as "what happened" rather than "how Create is shaped".
     */
    @Nullable
    static AbstractContraptionEntity entityOf(Contraption contraption) {
        return contraption == null ? null : contraption.entity;
    }

    // ------------------------------- two-phase identity: the fallback half --

    /** How many contraptions may hold a provisional identity at once before the map is cleared. */
    private static final int PROVISIONAL_LIMIT = 64;

    /**
     * <b>The assembly-period identity of one contraption, created on first use and reused after.</b>
     *
     * <p>This is the fallback that makes the invariant "a block with a component is never skipped" hold
     * while a structure has no entity: the same {@code Contraption} object asked twice gets the same
     * {@link CarrierId}, so the claim made at {@code startMoving} is findable at {@code stopMoving}
     * even if the entity never turned up. The id is a version-8 UUID (see
     * {@link com.jiangyuefengyu.redstonecircuit.carrier.CarrierIdentity}), never a world position, and
     * the anchor and block count are recorded beside it purely so a log line can be read by eye.
     *
     * <p>The block count and anchor are also what {@link #describeProvisional} prints, so
     * {@code /rc carrier} can say "this claim belongs to a 47-block structure anchored at X" - the
     * evidence an operator needs to tell one machine from another on paper.
     */
    static CarrierId provisionalFor(Contraption contraption) {
        Provisional existing = PROVISIONALS.get(contraption);
        if (existing != null) {
            return existing.id;
        }
        if (PROVISIONALS.size() >= PROVISIONAL_LIMIT) {
            // Never expected: a provisional claim is settled within a tick or two and dropped then. If
            // it ever is not, bounded growth beats an unbounded identity map that keeps machines alive.
            PROVISIONALS.clear();
        }
        BlockPos anchor = contraption.anchor;
        int blockCount = contraption.getBlocks() == null ? 0 : contraption.getBlocks().size();
        UUID uuid = CarrierIdentity.provisional(++provisionalSerial, System.identityHashCode(contraption),
                anchor == null ? 0 : anchor.getX(), anchor == null ? 0 : anchor.getY(),
                anchor == null ? 0 : anchor.getZ());
        CarrierId id = CarrierId.contraption(uuid);
        Provisional record = new Provisional(id, anchor, blockCount, contraption.entity);
        PROVISIONALS.put(contraption, record);
        return id;
    }

    /**
     * {@code CarrierStore.ProvisionalClaims}: the entity a provisional claim can be re-labelled onto.
     *
     * <p>Answered by looking the contraption up again, so an entity Create attaches after the claim is
     * seen on the very next question - which is the same tick, because the carrier layer asks once per
     * level tick before it does anything else with a pose.
     *
     * <p>{@code null} is a perfectly ordinary answer (the structure really has no entity yet) and is
     * reported as such rather than treated as a failure.
     */
    @Nullable
    static UUID entityFor(CarrierId provisional) {
        Provisional record = recordOf(provisional);
        if (record == null) {
            return null;
        }
        return record.entity == null ? null : record.entity.getUUID();
    }

    /**
     * Forgets a provisional identity, once it has been settled or its hosts have been put down.
     *
     * <p>Called by the hook after a release, so the map only ever holds structures that are genuinely
     * still waiting for an entity.
     */
    static void forgetProvisional(CarrierId provisional) {
        if (provisional == null) {
            return;
        }
        PROVISIONALS.values().removeIf(record -> record.id.equals(provisional));
    }

    /**
     * Whether this contraption has ever been given a provisional identity.
     *
     * <p>Asked by the put-down and actor-tick paths to decide whether there is anything to reconcile at
     * all, which keeps the common case - a structure whose entity was there from the start - to one
     * identity-map lookup and no allocation.
     */
    static boolean hasProvisional(Contraption contraption) {
        return contraption != null && PROVISIONALS.containsKey(contraption);
    }

    /** Drops every provisional identity of this resolver; used when a level goes away. */
    static void clearProvisional() {
        PROVISIONALS.clear();
    }

    /** How many contraptions are currently waiting for an entity; for the diagnostics. */
    static int provisionalCount() {
        return PROVISIONALS.size();
    }

    /**
     * One line about a provisional claim: which structure it belongs to, by anchor and size.
     *
     * <p>Returned rather than logged, so the call site decides whether the operator asked for it, and
     * so {@code /rc carrier} can print it beside the carrier's other fields. A provisional record whose
     * contraption still has no entity says {@code entity=absent} - which is the state that means "the
     * claim is safe and is waiting to be re-labelled", not "something is broken".
     */
    static String describeProvisional(CarrierId provisional) {
        Provisional record = recordOf(provisional);
        if (record == null) {
            return "(unknown provisional identity)";
        }
        return "anchor=" + (record.anchor == null ? "?" : record.anchor.toShortString())
                + " blocks=" + record.blockCount
                + " entity=" + (record.entity == null ? "absent" : record.entity.getUUID());
    }

    @Nullable
    private static Provisional recordOf(CarrierId provisional) {
        if (provisional == null) {
            return null;
        }
        for (Map.Entry<Contraption, Provisional> entry : PROVISIONALS.entrySet()) {
            if (entry.getValue().id.equals(provisional)) {
                // Refresh from the contraption: Create attaches the entity after the claim, and this is
                // the one place that fact becomes visible to the carrier layer.
                Provisional record = entry.getValue();
                record.entity = entry.getKey().entity;
                return record;
            }
        }
        return null;
    }

    /** Where a contraption's local coordinate currently is in the world, for the log lines. */
    @Nullable
    static Vec3 worldOf(AbstractContraptionEntity entity, BlockPos localPos) {
        if (entity == null || localPos == null) {
            return null;
        }
        return entity.toGlobalVector(Vec3.atCenterOf(localPos), 0.0F);
    }
}

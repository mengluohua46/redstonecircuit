package com.jiangyuefengyu.redstonecircuit.client;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import org.jetbrains.annotations.Nullable;

import com.jiangyuefengyu.redstonecircuit.RCConfig;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierHostCheck;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierId;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierIndex;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierPose;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierProjection;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierTrace;
import com.jiangyuefengyu.redstonecircuit.carrier.LandedHosts;
import com.jiangyuefengyu.redstonecircuit.data.HostEntry;
import com.jiangyuefengyu.redstonecircuit.data.Slot;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * The client's copy of "which blocks hold redstone, and what is inside them": the shared {@link HostIndex} and the
 * {@link CarrierIndex}, whose bookkeeping is testable without a game. Nothing is baked into the world - the shell and
 * the component are drawn once per frame rather than written into the chunk mesh, so a change here needs no mesh rebuild.
 *
 * <p>The server is authoritative except for one defensive case: a carrier whose pose has gone stale is let go of and the
 * hosts it held fall back to their original world positions ({@link #tickCarriers}), since a host may only be treated as
 * riding while the client knows where that structure is. That fallback is a view and not data.
 */
public final class ClientHostCache {

    private static final HostIndex INDEX = new HostIndex();
    private static final CarrierIndex CARRIERS = new CarrierIndex();

    /**
     * Hosts whose carrier the server has taken back, still drawn at the position they were put down at: the third state
     * a host can be in, where the block at that position may not exist. Written only on the client thread and read by the
     * render thread, hence {@code volatile}.
     */
    private static volatile LandedHosts landed = new LandedHosts();

    /** Hosts whose carrier was let go of, drawn at their original world position until told otherwise. */
    private static volatile List<CarrierIndex.CarriedView> fallbacks = List.of();

    private ClientHostCache() {
    }

    /** Replaces the whole set. Sent on login, dimension change and respawn. */
    public static void applySnapshot(ResourceKey<Level> dimension, List<HostEntry> entries) {
        INDEX.applySnapshot(dimension, entries);
        // Logged because "the client never learned about the block" and "the client learned but drew nothing" look
        // identical on screen; this line tells the two apart.
        if (RCConfig.debugLog()) {
            RCConfig.LOGGER.info("[redstonecircuit] client: {} host(s) in {}",
                    INDEX.size(), dimension.location());
        }
    }

    /** Adds, updates or removes one position. */
    public static void applyChange(ResourceKey<Level> dimension, BlockPos pos, @Nullable Slot slot) {
        int before = INDEX.size();
        INDEX.applyChange(dimension, pos, slot);
        // A world host record alone is not enough to draw: the block has to be there too, and it arrives a moment after
        // the sync does. So the record is forgotten only when the world path could really draw it now.
        forgetLandedWhereWorldPathDraws(dimension, List.of(new HostEntry(pos.immutable(), slot)));
        if (slot == null && !landed.isEmpty()) {
            // The same rule as the plural path: a removal says "nothing is here", so a landed record there has nothing
            // left to wait for.
            landed.forgetAt(pos);
        }
        if (INDEX.size() != before && RCConfig.debugLog()) {
            RCConfig.LOGGER.info("[redstonecircuit] client: host {} at {} -> {} host(s) in total",
                    slot != null ? "added" : "removed", pos.toShortString(), INDEX.size());
        }
    }

    /** Adds, updates or removes several positions in one packet. */
    public static void applyChanges(ResourceKey<Level> dimension, List<HostEntry> entries) {
        INDEX.applyChanges(dimension, entries);
        forgetLandedWhereWorldPathDraws(dimension, entries);
        // A removal entry is the server's word that no host is there, and the one message that also says no block is
        // coming: without it a landed record whose cell is air would draw its shell in mid-air for ever.
        forgetLandedRemovals(entries);
        // A host frame drawn in air means this table was just told about a cell the world has no block in: a carried
        // host's blocks live at the structure's own coordinates while its entry is addressed where the machine is drawn.
        if (RCConfig.debugLog()) {
            probeForAirborneEntries(entries);
        }
    }

    /** Drops the landed records the server has just said "nothing is here" about; see {@link #applyChanges}. */
    private static void forgetLandedRemovals(List<HostEntry> entries) {
        if (landed.isEmpty()) {
            return;
        }
        for (HostEntry entry : entries) {
            if (entry.slot() == null) {
                landed.forgetAt(entry.pos());
            }
        }
    }

    /** Names every just-applied <b>addition</b> whose cell the world has no block in; see {@link #applyChanges}. */
    private static void probeForAirborneEntries(List<HostEntry> entries) {
        net.minecraft.client.multiplayer.ClientLevel level = net.minecraft.client.Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        for (HostEntry entry : entries) {
            // A removal for an air cell is the ordinary, correct message, so only an ADDITION there is worth naming.
            if (entry.slot() == null || !level.hasChunkAt(entry.pos())) {
                continue;
            }
            net.minecraft.world.level.block.state.BlockState state = level.getBlockState(entry.pos());
            if (state.isAir()) {
                RCConfig.LOGGER.info("[redstonecircuit] ghost probe: host entry for air at {} (slot={})",
                        entry.pos().toShortString(), entry.slot().type.name());
            }
        }
    }

    /**
     * Forgets the landed records the world path has genuinely taken over right now, judged by the one predicate the
     * render pass uses ({@link #worldPathDraws}): a world-host slot is not enough, the block has to be there too. Only
     * entries that add a slot are consulted, since a {@code null} slot must not drop a landed record.
     */
    private static void forgetLandedWhereWorldPathDraws(ResourceKey<Level> dimension,
                                                       List<HostEntry> entries) {
        if (landed.isEmpty() || entries.isEmpty()) {
            return;
        }
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || !level.dimension().equals(dimension)) {
            // No level to ask, or a packet for another dimension: there is no honest answer to "would the world path
            // draw here", so the record stays - the renderer only draws it where the world path does not.
            return;
        }
        for (HostEntry entry : entries) {
            if (entry.slot() != null && worldPathDraws(level, dimension, entry.pos(), entry.slot())) {
                landed.forgetAt(entry.pos());
            }
        }
    }

    /**
     * Whether the ordinary world path draws a position - the single question every drawing and yielding decision on the
     * client is made by. The world-host slot is read from the host index, and the block test additionally requires the
     * chunk to be loaded, because an unloaded chunk answers "air" for every position in it.
     */
    public static boolean worldPathDraws(ClientLevel level, ResourceKey<Level> dimension, BlockPos pos,
                                        @Nullable Slot slot) {
        boolean worldHost = slot != null && slotAt(dimension, pos) != null;
        boolean blockPresent = level.isLoaded(pos) && !level.getBlockState(pos).isAir();
        return LandedHosts.worldPathDraws(worldHost, blockPresent);
    }

    /**
     * The wrench's selection box asks the server's own answer rather than "does this client hold a world record here":
     * a carried pick's block is drawn at the machine, has no world record by construction and sits in air, so that test
     * would veto the very case the box exists for.
     */

    /** Drops everything, e.g. when leaving the world. */
    public static void clear() {        INDEX.clear();
        CARRIERS.clear();
        fallbacks = List.of();
        landed.clear();
        CarrierRenderTrace.clearAll();
    }

    /** Visits every host within a radius of sections; the per-frame renderer's entry point. */
    public static void forEachNear(ResourceKey<Level> dimension, BlockPos center, int sectionRadius,
                                   BiConsumer<BlockPos, Slot> action) {
        INDEX.forEachNear(dimension, center, sectionRadius, action);
    }

    /** What the client believes is inside one block, or {@code null}. */
    @Nullable
    public static Slot slotAt(ResourceKey<Level> dimension, BlockPos pos) {
        return INDEX.slotAt(dimension, pos);
    }

    /**
     * The component inside a block a structure is carrying, by that block's own local coordinate: a carried host has no
     * world position, only a local coordinate inside a structure that is somewhere else - which is the coordinate a ray
     * into a physicalised block yields and the one {@link HostIndex} has nothing at. A null {@code dimension} means
     * "whatever this client is in".
     */
    @Nullable
    public static Slot carriedSlotAtLocal(@Nullable ResourceKey<Level> dimension, BlockPos localPos) {
        if (localPos == null || !CARRIERS.acceptsDimension(dimension)) {
            return null;
        }
        for (CarrierIndex.CarriedView view : CARRIERS.atLocalCoordinate(localPos)) {
            if (view.slot() != null) {
                return view.slot();
            }
        }
        return null;
    }

    /**
     * One frame's "which structure's own coordinate holds a component": the neighbour question the carried render path
     * asks in the structure's own frame. Built once per frame by the caller, not looked up per side per host.
     */
    public static Map<Long, CarrierId> carriedLocalIndex(@Nullable ResourceKey<Level> dimension) {
        if (!CARRIERS.acceptsDimension(dimension)) {
            return Map.of();
        }
        return CARRIERS.localHostIndex();
    }

    /** The same lookup, with the structure and the local coordinate the client resolved - for the diagnostics. */
    @Nullable
    public static CarrierIndex.CarriedView carriedHostAtLocal(@Nullable ResourceKey<Level> dimension,
                                                             BlockPos localPos) {
        if (localPos == null || !CARRIERS.acceptsDimension(dimension)) {
            return null;
        }
        List<CarrierIndex.CarriedView> found = CARRIERS.atLocalCoordinate(localPos);
        return found.isEmpty() ? null : found.get(0);
    }

    /** How many blocks the client currently believes hold redstone; used by the debug command. */
    public static int size() {
        return INDEX.size();
    }

    // ---------------------------------------------------- carriers (moving) --

    /**
     * One carrier's pose, and the hosts it is carrying: a {@code null} pose means the structure is gone, so its pose and
     * everything riding it are dropped, and a host that is not in the new list has been put down and becomes landed
     * ({@link LandedHosts}) - kept until the ordinary world sync claims that position.
     */
    public static void applyCarrier(ResourceKey<Level> dimension, CarrierId carrier,
                                    @Nullable CarrierPose pose,
                                    List<CarrierIndex.CarriedView> hosts) {
        applyCarrier(dimension, carrier, pose, hosts, List.of());
    }

    /**
     * The same, with the hosts the server has discarded named. Without that list a host leaving the carrier could only
     * mean "put down", so a host whose block was destroyed stayed on screen inside the structure. The removal is applied
     * first and the stow filtered by it, so a host named in both lists cannot be re-created as a landed record.
     */
    public static void applyCarrier(ResourceKey<Level> dimension, CarrierId carrier,
                                    @Nullable CarrierPose pose,
                                    List<CarrierIndex.CarriedView> hosts, List<BlockPos> forgotten) {
        // Snapshot what the carrier was holding before anything below changes it: a null pose drops the whole thing and a
        // changed list drops entries, and both need the same "where was each host" answer.
        List<CarrierIndex.CarriedView> wasHolding = CARRIERS.viewsOf(carrier);
        // The removal list is decided once, over the record of what the client held: what was discarded is dropped, and
        // only what is left may be stowed as landed.
        LandedHosts.Forget forget = LandedHosts.forget(wasHolding, forgotten, carrier);
        List<CarrierIndex.CarriedView> forgottenViews = forget.forgotten();
        List<CarrierIndex.CarriedView> stowed = LandedHosts.departed(
                forget.kept(), hosts, pose == null);

        if (!forgotten.isEmpty()) {
            applyForget(dimension, carrier, forgotten, forgottenViews);
        }

        CARRIERS.applyPose(dimension, carrier, pose);
        if (pose == null) {
            // A carrier the server has taken back: its fallback entries go with it, since the components are back in the
            // world and the ordinary world sync draws them where a block is standing.
            dropFallbackFor(carrier);
            // The hosts it was holding are kept as landed hosts: dropping them would take the client's only record of
            // where each belongs with the pose, and the world path skips a position with no block standing.
            int added = landed.stow(stowed);
            // Logged at the moment it happens, so "the client never heard" and "the client heard and ignored it" are told
            // apart.
            CarrierRenderTrace.log(dimension, carrier.shortId()
                    + " gone; kept " + stowed.size() + " host(s) as landed (" + added + " new, "
                    + landed.size() + " held now);");
            noteHeldCounts(dimension);
            return;
        }
        // Wholesale replacement needs the old list gone first; a carrier's host count is a handful.
        for (CarrierIndex.CarriedView existing : CARRIERS.all()) {
            if (existing.carrier().equals(carrier) && !contains(hosts, existing)) {
                CARRIERS.removeHost(existing);
            }
        }
        for (CarrierIndex.CarriedView host : hosts) {
            CARRIERS.applyHost(dimension, host);
        }
        // A carrier heard from again is no longer a fallback: what was drawn at the old world position goes back inside
        // the structure.
        dropFallbackFor(carrier);
        if (!stowed.isEmpty()) {
            // A host that left the list while the carrier is still there was put down by a structure that is still flying:
            // its entry is landed, and only riding hosts are represented in the carrier index.
            landed.stow(stowed);
        }
        // The hosts still riding are not landed: the server is answering a pose for this carrier.
        forgetLandedExcept(carrier, hosts);
        noteHeldCounts(dimension);
    }

    /**
     * Applies one host-level removal notice: gone from the carried index, gone from the landed records and gone from the
     * fallback list - the three places this client can draw a component that no longer exists.
     */
    private static void applyForget(ResourceKey<Level> dimension, CarrierId carrier,
                                    List<BlockPos> forgotten,
                                    List<CarrierIndex.CarriedView> forgottenViews) {
        int dropped = CARRIERS.applyRemovals(carrier, forgotten);
        landed.forgetHosts(carrier, forgotten);
        dropFallbackFor(carrier, forgotten);
        if (RCConfig.debugLog()) {
            RCConfig.LOGGER.info("[redstonecircuit] client: carrier {} told us to forget {} host(s) "
                            + "local={}; dropped {} carried / {} landed / {} fallback - nothing is drawn "
                            + "there and none of them became a landed record",
                    carrier.shortId(), forgotten.size(), describePositions(forgotten), dropped,
                    forgottenViews.size(), countFallbacks(carrier));
        }
    }

    /** {@code 1, 0, 0; 2, 0, 0} for a log line, capped so a big removal cannot flood it. */
    private static String describePositions(List<BlockPos> positions) {
        StringBuilder sb = new StringBuilder();
        int shown = Math.min(4, positions.size());
        for (int i = 0; i < shown; i++) {
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append(positions.get(i).toShortString());
        }
        if (positions.size() > shown) {
            sb.append("; +").append(positions.size() - shown).append(" more");
        }
        return sb.toString();
    }

    /** How many of one carrier's hosts are currently being drawn on the fallback path. */
    private static int countFallbacks(CarrierId carrier) {
        int count = 0;
        for (CarrierIndex.CarriedView view : fallbacks) {
            if (view.carrier().equals(carrier)) {
                count++;
            }
        }
        return count;
    }

    /**
     * Drops the landed records of one carrier that are no longer valid, because its hosts are riding: only the hosts the
     * carrier still lists are forgotten, so a host it put down earlier keeps its landed record.
     */
    private static void forgetLandedExcept(CarrierId carrier, List<CarrierIndex.CarriedView> hosts) {
        if (landed.isEmpty()) {
            return;
        }
        for (CarrierIndex.CarriedView record : landed.all()) {
            if (record.carrier().equals(carrier) && contains(hosts, record)) {
                landed.forgetAt(record.at());
            }
        }
    }

    /** Replaces the whole carrier picture. Sent on login, dimension change and respawn. */
    public static void applyCarrierSnapshot(ResourceKey<Level> dimension,
                                            List<CarrierIndex.CarriedView> hosts,
                                            Map<CarrierId, CarrierPose> poses) {
        CARRIERS.applySnapshot(dimension, hosts, poses);
        fallbacks = List.of();
        landed.clear();
        noteHeldCounts(dimension);
    }

    /**
     * The self-healing sweep: one client tick. Any carrier whose pose the server has stopped refreshing - or whose
     * removal message never arrived - is let go of and the hosts it was holding become world-path fallbacks.
     */
    public static void tickCarriers() {
        CARRIERS.tick();
        List<CarrierId> expired = CARRIERS.expireStale(staleTicks());
        if (expired.isEmpty()) {
            return;
        }
        List<CarrierIndex.CarriedView> kept = new ArrayList<>(fallbacks);
        kept.removeIf(view -> expired.contains(view.carrier()));
        for (CarrierId carrier : expired) {
            kept.addAll(CARRIERS.viewsOf(carrier));
            CARRIERS.drop(carrier);
        }
        fallbacks = List.copyOf(kept);
        RCConfig.LOGGER.info("[redstonecircuit] client: let go of {} carrier(s) whose pose went stale "
                        + "(>{}-tick threshold, the server stopped refreshing it): {} host(s) now "
                        + "drawn on the world path", expired.size(), staleTicks(), fallbacks.size());
        noteHeldCounts(currentDimension());
    }

    /** How long a carrier's pose stays trustworthy, in client ticks. */
    public static int staleTicks() {
        return RCConfig.carrierPoseStaleTicks();
    }

    /**
     * Visits everything the carried path is allowed to draw: the hosts whose carrier the client knows the position of,
     * the landed ones (drawn where they were put down) and the fallbacks (drawn at the block they came from). One walk
     * for all three, because walking them separately could draw the same component twice in one frame.
     */
    public static void forEachDrawable(ResourceKey<Level> dimension, BlockPos center, int sectionRadius,
                                       Consumer<CarrierIndex.Drawable> action) {
        CARRIERS.forEachDrawable(dimension, center, sectionRadius, landed, fallbacks, action);
    }

    /**
     * The same walk with the frame's partial tick, which the carried render pass uses: the poses are interpolated between
     * the last two the server sent, so the components inside a moving machine are drawn at the same instant of its motion
     * as the machine itself.
     */
    public static void forEachDrawable(ResourceKey<Level> dimension, BlockPos center, int sectionRadius,
                                       double partialTick,
                                       Consumer<CarrierIndex.Drawable> action) {
        CARRIERS.forEachDrawable(dimension, center, sectionRadius, landed, fallbacks, partialTick, action);
    }

    /** Visits every carried host whose origin lies near the camera; the renderer's entry point. */
    public static void forEachCarriedNear(ResourceKey<Level> dimension, BlockPos center,
                                          int sectionRadius,
                                          Consumer<CarrierIndex.CarriedView> action) {
        CARRIERS.forEachNear(dimension, center, sectionRadius, action);
    }

    /** Where a structure is, or {@code null} when the client has not been told about it. */
    @Nullable
    public static CarrierPose carrierPose(CarrierId carrier) {
        return CARRIERS.poseOf(carrier);
    }

    /**
     * The same pose, interpolated to this frame - the pose the machine itself is drawn at, and the wrench's selection box
     * inside a machine is placed with it, so the two have to agree. {@code null} means the client has no pose for the
     * structure at all, which is not the same as "the pick is gone".
     */
    @Nullable
    public static CarrierPose carrierPose(CarrierId carrier, double partialTick) {
        return CARRIERS.poseOf(carrier, partialTick);
    }

    /** How many ticks ago this carrier's pose was last refreshed, or {@code -1} when there is none. */
    public static long carrierPoseAge(CarrierId carrier) {
        return CARRIERS.poseAge(carrier);
    }

    /** How many carried hosts the client knows about; used by the debug command. */
    public static int carriedSize() {
        return CARRIERS.size();
    }

    /** How many carriers the client knows a pose for. */
    public static int carrierCount() {
        return CARRIERS.carrierCount();
    }

    /**
     * How many carriers the client is holding that it can no longer place: with it at zero and the render pass drawing
     * nothing, the two facts together are a bug with no other symptom.
     */
    public static int staleCarrierCount() {
        return CARRIERS.staleCarriers(staleTicks()).size();
    }

    /** How many carried hosts the client cannot draw because its carrier has no pose at all. */
    public static int hostsWithoutPose() {
        return CARRIERS.hostsWithoutPose();
    }

    /** How many hosts are currently drawn on the world path because their carrier went stale. */
    public static int fallbackCount() {
        return fallbacks.size();
    }

    /** How many hosts are held as landed, i.e. put down and still drawn at their landing position. */
    public static int landedCount() {
        return landed.size();
    }

    /** The landed-host bookkeeping, for the renderer's read-only questions. */
    public static LandedHosts landedHosts() {
        return landed;
    }

    /** Forgets a landed record because the ordinary host sync has taken that position over. */
    public static void forgetLandedAt(BlockPos pos) {
        if (!landed.isEmpty()) {
            landed.forgetAt(pos);
        }
    }

    /** Every carrier the client is holding, for the warnings and the per-carrier diagnostics. */
    public static List<CarrierId> carriers() {
        return CARRIERS.carriers();
    }

    /**
     * How many live carriers have no pose at all; the B1 sweep's {@code skippedNoPose} input. A carrier count, not a host
     * count: {@link #hostsWithoutPose()} counts the host blocks that cannot be drawn because of this, and the line says
     * which.
     */
    public static int carriersWithoutPoseCount() {
        return CARRIERS.carriersWithoutPose().size();
    }

    // ------------------------------------------------- read-only diagnostics --

    /**
     * The B1 sweep: every carrier the client holds, and which of them the origin walk offered. Called at most once per
     * {@code CarrierSightLog} window, never per frame; the counting itself lives in {@link CarrierIndex#sectionWalk}, and
     * this method only adds what the buckets do not know - the pose's section, its age and the camera's own section.
     */
    public static CarrierSweep carrierSweep(ResourceKey<Level> dimension, @Nullable BlockPos cameraSection,
                                            int sectionRadius, @Nullable BlockPos shift) {
        List<CarrierId> carriers = CARRIERS.carriers();
        List<CarrierSection> problems = new ArrayList<>();
        int collected = 0;
        int originOnlySkipped = 0;
        int noPose = 0;
        int shown = 0;
        for (CarrierId carrier : carriers) {
            CarrierPose pose = CARRIERS.poseOf(carrier);
            long age = CARRIERS.poseAge(carrier);
            CarrierIndex.SectionWalk walk = CARRIERS.sectionWalk(carrier, shift, sectionRadius);
            BlockPos origin = originOf(carrier);
            String originSection = CarrierHostCheck.section(origin);
            String poseSection = pose == null ? "none" : CarrierHostCheck.section(BlockPos.containing(
                    pose.position().x(), pose.position().y(), pose.position().z()));
            if (walk.offered()) {
                collected++;
            } else if (pose == null) {
                noPose++;
            } else {
                originOnlySkipped++;
            }
            // A ROTATING CAP, because the count of carriers is bounded by the session rather than by a
            // constant: at most CARRIER_PROBLEM_LINES_PER_WINDOW problem carriers are named per window, and
            // the ones the cap passes over are still counted in the rollup.
            if (!walk.offered() && shown < CarrierSightLog.CARRIER_PROBLEM_LINES_PER_WINDOW) {
                shown++;
                // THE CHECK, NOT A BAG OF FIELDS: the line the log carries is `CarrierHostCheck#describe`, so the sweep
                // hands it a value rather than reproducing its wording, and a field added there cannot go missing here.
                problems.add(new CarrierSection(new CarrierHostCheck(carrier, originSection, poseSection,
                        cameraSection, false, localPosOf(carrier),
                        pose == null ? null : CarrierProjection.project(pose, localPosOf(carrier)),
                        null, null, null, walk.walkedHosts(), walk.totalHosts(), age, origin)));
            }
        }
        boolean dimensionKnown = CARRIERS.acceptsDimension(dimension);
        return new CarrierSweep(dimensionKnown, carriers.size(), collected, originOnlySkipped, noPose,
                problems);
    }

    /** The first host coordinate one carrier holds, or {@code null}; the worked example for B1 and B2. */
    @Nullable
    private static BlockPos localPosOf(CarrierId carrier) {
        List<CarrierIndex.CarriedView> hosts = CARRIERS.viewsOf(carrier);
        return hosts.isEmpty() ? null : hosts.get(0).localPos();
    }

    /** Where a carrier's blocks came from, from the first host it holds; {@code null} when it holds none. */
    @Nullable
    private static BlockPos originOf(CarrierId carrier) {
        List<CarrierIndex.CarriedView> hosts = CARRIERS.viewsOf(carrier);
        return hosts.isEmpty() ? null : hosts.get(0).origin();
    }

    /**
     * What one B1 sweep found; {@code problems} names the carriers whose every host sits in an origin bucket the walk did
     * not visit, and {@link #problemCount()} is how many were not offered in total.
     */
    public record CarrierSweep(boolean dimensionKnown, int carriers, int collected, int originOnlySkipped,
                               int noPose, List<CarrierSection> problems) {

        /** How many carriers were not offered to the renderer at all; see {@link #problems}. */
        public int problemCount() {
            return originOnlySkipped + noPose;
        }
    }

    /**
     * One carrier that the origin walk did not offer, as the B1 line describes it: a one-field record so that the line
     * the check words and the line a caller prints cannot drift apart.
     */
    public record CarrierSection(CarrierHostCheck check) {
    }

    /**
     * "c:1a2b3c4d age=3t" for every carrier the client holds, so a count that is higher than it should be names the entry
     * that stopped being refreshed rather than leaving the reader to guess.
     */
    public static String describeCarrierAges() {
        var ages = CARRIERS.poseAges();
        if (ages.isEmpty()) {
            return "none";
        }
        StringBuilder sb = new StringBuilder();
        for (var entry : ages.entrySet()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(entry.getKey().shortId()).append(entry.getValue() < 0 ? " (no pose)"
                    : " age=" + entry.getValue() + "t");
        }
        return sb.toString();
    }

    /**
     * Whether one host is currently drawn at the block it came from rather than inside a structure. Kept as a query
     * because "is this host being drawn on the world path" is the question a diagnostic asks; the renderer answers it
     * from {@link CarrierIndex.Drawable#landed()} instead, since it walks both fallback sets in one pass.
     */
    public static boolean isFallingBack(CarrierIndex.CarriedView view) {
        for (CarrierIndex.CarriedView candidate : fallbacks) {
            if (candidate.carrier().equals(view.carrier())
                    && candidate.localPos().equals(view.localPos())) {
                return true;
            }
        }
        return landed.contains(view.at());
    }

    /** A one-line summary for the debug command and the log. */
    public static String describeCarriers() {
        return CARRIERS.carrierCount() + " carrier(s) / " + CARRIERS.size() + " carried host(s)"
                + (landed.isEmpty() ? "" : " / " + landed.size() + " landed")
                + (fallbacks.isEmpty() ? "" : " / " + fallbacks.size() + " falling back to the world");
    }

    /** The dimension the carrier index is describing, or {@code null} before the first message. */
    @Nullable
    private static ResourceKey<Level> currentDimension() {
        return CARRIERS.dimension();
    }

    /** Pushes the client's own counts into the three-stage trace. */
    private static void noteHeldCounts(@Nullable ResourceKey<Level> dimension) {
        if (dimension == null) {
            return;
        }
        // `settledCarrierCount` rather than `carrierCount`: a provisional claim has no pose to send, so it must not be
        // counted on either side of the comparison the trace makes.
        CarrierTrace.noteClientIndex(dimension, CARRIERS.settledCarrierCount(), CARRIERS.size());
    }

    /** Drops any fallback entry belonging to one carrier; it is being drawn inside the structure again. */
    private static void dropFallbackFor(CarrierId carrier) {
        List<CarrierIndex.CarriedView> current = fallbacks;
        if (current.isEmpty()) {
            return;
        }
        List<CarrierIndex.CarriedView> kept = new ArrayList<>(current.size());
        for (CarrierIndex.CarriedView view : current) {
            if (!view.carrier().equals(carrier)) {
                kept.add(view);
            }
        }
        fallbacks = List.copyOf(kept);
    }

    /**
     * Drops one carrier's fallback entries at these local coordinates: the third place a component that no longer exists
     * can still be drawn. A carried host's origin is the block it came from, which is where the player is standing.
     */
    private static void dropFallbackFor(CarrierId carrier, List<BlockPos> localPositions) {
        List<CarrierIndex.CarriedView> current = fallbacks;
        if (current.isEmpty() || localPositions.isEmpty()) {
            return;
        }
        Set<Long> wanted = new HashSet<>();
        for (BlockPos localPos : localPositions) {
            wanted.add(localPos.asLong());
        }
        List<CarrierIndex.CarriedView> kept = new ArrayList<>(current.size());
        for (CarrierIndex.CarriedView view : current) {
            if (view.carrier().equals(carrier)
                    && wanted.contains(view.localPos().asLong())) {
                continue;
            }
            kept.add(view);
        }
        fallbacks = List.copyOf(kept);
    }

    private static boolean contains(List<CarrierIndex.CarriedView> hosts,
                                    CarrierIndex.CarriedView existing) {
        for (CarrierIndex.CarriedView host : hosts) {
            if (host.carrier().equals(existing.carrier())
                    && host.localPos().equals(existing.localPos())) {
                return true;
            }
        }
        return false;
    }
}

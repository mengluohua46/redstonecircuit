package com.jiangyuefengyu.redstonecircuit.carrier;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import com.jiangyuefengyu.redstonecircuit.data.Slot;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import org.jetbrains.annotations.Nullable;

/**
 * What one client believes about the host blocks riding a moving structure, and about where those structures are.
 *
 * <p>The carried hosts (which component is inside which local coordinate of which carrier) and the poses (where a
 * carrier is) are kept apart because they change at different rates: a host list changes only when something is
 * picked up or put down, a pose whenever the structure moves. Hosts are bucketed by <b>origin</b>, the world block
 * they came from, because the pose decides where the drawing happens; the walk is a <b>union</b> of that bucket and
 * the carrier's current pose, never one instead of the other, so a machine that has travelled beyond the origin
 * walk's radius is still drawn and a carrier the client holds no pose for is not lost.
 *
 * <p>Free of any {@code Minecraft} reference, so the rules can be unit-tested;
 * {@link com.jiangyuefengyu.redstonecircuit.client.ClientHostCache} is the thin client-side wrapper.
 */
public final class CarrierIndex {

    /** One host riding a structure, as the client is told about it and draws it. */
    public record CarriedView(CarrierId carrier, BlockPos localPos, BlockPos origin, BlockPos at,
                              Slot slot) {
    }

    /**
     * One host the carried path may draw: either inside its carrier's pose, or - when that pose is no longer
     * trustworthy - at the world block it came from.
     *
     * <p>A {@code null} pose is the fallback, and the host does have a position, it is just not the one the feature
     * wanted to give it. {@code landed} says <em>which</em> position that is: a landed host is drawn at
     * {@link CarriedView#at()}, the position it was put down at, which is authoritative and may be a block-less one;
     * a host whose carrier's pose went stale mid-flight is drawn at {@link CarriedView#origin()}.
     */
    public record Drawable(CarriedView view, @Nullable CarrierPose pose, boolean landed) {

        /** True when this is a host drawn at a world block rather than inside a structure. */
        public boolean isFallback() {
            return pose == null;
        }

        /** The world position this entry is drawn at when it has no pose. */
        public BlockPos fallbackPos() {
            return landed ? view.at() : view.origin();
        }
    }

    /** {@link SectionPos#asLong} -> the carried hosts whose origin is in that section. */
    private final Long2ObjectMap<List<CarriedView>> bySection = new Long2ObjectOpenHashMap<>();

    /**
     * {@link SectionPos#asLong} -> the carriers whose <b>current pose</b> sits in that section.
     *
     * <p>A carrier is filed here under the section of its pose pivot, so a structure the player is next to can be
     * found near the player even when the blocks its components came from are far away. {@link #drawablesNear} walks
     * this <b>in addition to</b> {@link #forEachNear}, never instead of it. No host is stored here, only the
     * carrier's id: the hosts stay in {@link #bySection}, so the two indexes cannot disagree about what exists, and
     * a host can never be drawn twice.
     */
    private final Long2ObjectMap<List<CarrierId>> anchorsByPose = new Long2ObjectOpenHashMap<>();

    /**
     * How far, in sections, the pose-anchored walk reaches.
     *
     * <p>Seven, derived rather than chosen: the renderer's own bound is six sections plus 16 blocks of <b>block</b>
     * distance from the camera to the carrier's pose, so a section walk finer than that could only prune something
     * the block-distance check is about to look at anyway. Anything the exact check would allow must survive this
     * coarse one; the reverse - the walk admitting a carrier the block check then refuses - is harmless and is what
     * {@code skippedFar} counts. <b>If the render distance ever changes, this number must change with it.</b>
     */
    static final int POSE_ANCHOR_RADIUS = 7;

    /** carrier -> its pose, or absent while the client has not been told one. */
    private final Map<CarrierId, CarrierPose> poses = new HashMap<>();

    /**
     * carrier -> the pose it had <b>before</b> the current one: the other end of the render interpolation.
     *
     * <p>Kept here because "the previous pose" is exactly "the pose this index was holding when the new one
     * arrived", which a caller cannot reconstruct afterwards. It is <b>cleared</b> wherever a pose arrives that is
     * not one tick after the last (a snapshot, a landing, a teleport): interpolating across a jump would draw a
     * component flying across the world for one frame. Absent for a carrier that is standing still, so the map
     * holds only moving carriers.
     */
    private final Map<CarrierId, CarrierPose> previousPoses = new HashMap<>();

    /**
     * carrier -> the tick its pose was last refreshed on; see {@link #expireStale}.
     *
     * <p>Kept beside {@link #poses} rather than inside {@link CarrierPose} on purpose: a pose is the
     * structure's geometry and is compared, sent and unit-tested as such, while "how long ago the
     * client heard about it" is client bookkeeping that the server has no opinion about. Folding the
     * two together would put a client clock into every pose comparison.
     */
    private final Map<CarrierId, Long> poseTicks = new HashMap<>();

    /** Which dimension all of the above describes, or {@code null} before the first snapshot. */
    private ResourceKey<Level> dimension;

    /** When the client last acted on this index at all; the clock the pose ages are measured on. */
    private long now;

    // -------------------------------------------------------------- updates --

    /** Replaces everything. Sent on login, dimension change and respawn. */
    public void applySnapshot(ResourceKey<Level> dim, List<CarriedView> hosts,
                              Map<CarrierId, CarrierPose> carrierPoses) {
        bySection.clear();
        anchorsByPose.clear();
        poses.clear();
        previousPoses.clear();
        poseTicks.clear();
        dimension = dim;
        for (Map.Entry<CarrierId, CarrierPose> entry : carrierPoses.entrySet()) {
            if (entry.getValue() != null) {
                poses.put(entry.getKey(), entry.getValue());
                // A snapshot is the client's freshest possible information, so it counts as "now".
                poseTicks.put(entry.getKey(), now);
            }
        }
        for (CarriedView host : hosts) {
            put(host);
        }
        // After the hosts, so every carrier the snapshot mentions is filed under its own pose once,
        // whatever order the two halves of the payload arrived in.
        for (CarrierId carrier : carrierPoses.keySet()) {
            anchor(carrier);
        }
    }

    /**
     * Replaces the pose of one carrier, which is the part that changes while a structure moves.
     *
     * <p>A {@code null} pose means the carrier is gone. Anything still riding it is dropped with it
     * rather than being left behind at a stale world position, because there is no honest place left to
     * draw it.
     *
     * <p>A pose that arrives for another dimension is still <b>honoured for the removal case</b>: a
     * "this carrier is gone" message is never wrong, and dropping it would, if the two sides ever
     * disagreed about the dimension, leave the carrier on the client for good - which is exactly the
     * defect the self-healing sweep in {@link #expireStale} exists to make impossible.
     */
    public void applyPose(ResourceKey<Level> dim, CarrierId carrier, @Nullable CarrierPose pose) {
        if (pose == null) {
            drop(carrier);
            return;
        }
        if (!accepts(dim)) {
            return;
        }
        // The pose being replaced is the other end of the render interpolation; see previousPoses.
        CarrierPose outgoing = poses.put(carrier, pose);
        if (outgoing != null && outgoing != pose) {
            previousPoses.put(carrier, outgoing);
        } else {
            previousPoses.remove(carrier);
        }
        poseTicks.put(carrier, now);
        // The pose is the only thing that moves, so this is the only place the read-side anchor has to
        // follow: one carrier, one small list, and it is refiled rather than accumulated. Note that this
        // runs in the same call that stores the pose, so "the pose is held" and "the anchor is filed" are
        // never true apart - the extra walk cannot be stale with respect to `poses`.
        anchor(carrier);
    }

    /** Adds or replaces one carried host. */
    public void applyHost(ResourceKey<Level> dim, CarriedView host) {
        if (!accepts(dim)) {
            return;
        }
        remove(host);
        put(host);
        // A host arriving for a carrier whose pose the client already holds must be reachable through the
        // pose anchor too - otherwise "the component is registered but never drawn" would come back
        // through this door alone. (The origin walk covers it regardless; this keeps the two indexes in
        // step.)
        anchor(host.carrier());
    }

    /**
     * Removes one carried host, given the same view that was added.
     *
     * <p><b>This is the host-level removal notice's other half.</b> The carrier payload replace below
     * is what a put-down travels as, and by itself it is ambiguous: "not in the new list" means "put
     * down" for a landing and "destroyed" for a break, and a client that guessed put-down kept the
     * component as a {@link LandedHosts} record - the ghost. A host the server names as discarded goes
     * through here instead, so it stops being drawn at once and is <b>never</b> kept in any other shape.
     *
     * @return how many entries were actually removed, so the caller can log it
     */
    public int applyRemovals(CarrierId carrier, List<BlockPos> localPositions) {
        if (carrier == null || localPositions == null || localPositions.isEmpty()) {
            return 0;
        }
        Set<Long> wanted = new HashSet<>();
        for (BlockPos localPos : localPositions) {
            if (localPos != null) {
                wanted.add(localPos.asLong());
            }
        }
        int removed = 0;
        for (List<CarriedView> bucket : bySection.values()) {
            int before = bucket.size();
            bucket.removeIf(view -> view.carrier().equals(carrier)
                    && wanted.contains(view.localPos().asLong()));
            removed += before - bucket.size();
        }
        if (removed > 0) {
            bySection.long2ObjectEntrySet().removeIf(entry -> entry.getValue().isEmpty());
        }
        return removed;
    }

    /**
     * One carried host by its structure and its local coordinate, or {@code null}.
     *
     * <p>The lookup a reader outside the renderer needs: a coordinate that means something in a
     * structure's own frame names one host, and this is how "what is inside that physicalised block"
     * is answered. A linear walk over the occupied sections, i.e. over the hosts this client actually
     * holds - a handful per structure and a handful of structures.
     */
    @Nullable
    public CarriedView atLocal(CarrierId carrier, BlockPos localPos) {
        if (carrier == null || localPos == null) {
            return null;
        }
        for (List<CarriedView> bucket : bySection.values()) {
            for (CarriedView view : bucket) {
                if (view.carrier().equals(carrier) && view.localPos().equals(localPos)) {
                    return view;
                }
            }
        }
        return null;
    }

    /**
     * Every carried host the client holds at one <b>local coordinate</b>, whichever structure owns it.
     *
     * <p>Asked by readers that only have the coordinate the structure's own blocks are addressed by -
     * which is what a ray into a physicalised block yields - and not the structure it belongs to. There
     * can be more than one answer in principle (two structures whose plots overlap is not expected, but
     * this refuses to pretend otherwise) so the caller gets the list rather than a guess.
     */
    public List<CarriedView> atLocalCoordinate(BlockPos localPos) {
        List<CarriedView> out = new ArrayList<>();
        if (localPos == null) {
            return out;
        }
        for (List<CarriedView> bucket : bySection.values()) {
            for (CarriedView view : bucket) {
                if (view.localPos().equals(localPos)) {
                    out.add(view);
                }
            }
        }
        return out;
    }

    /**
     * Removes one carried host, given the same view that was added.
     *
     * <p>Kept for the wholesale-replacement path, which drops the entries a carrier's new list does not
     * name. Anything the server has explicitly told us is <b>destroyed</b> must go through
     * {@link #applyRemovals} instead, so that a landed record can never be made of it.
     */
    public void removeHost(CarriedView host) {
        remove(host);
    }

    /**
     * One frame's index of "which structure's own coordinate holds a component".
     *
     * <p>Built once per frame from the hosts this client holds, because the question is asked once per side of every
     * drawn host: "is the block on this side another host of my own structure?" That is the frame a carried host's
     * neighbours have to be named in, and the section buckets cannot answer it - they are keyed by <em>origin</em>,
     * not by the local coordinate the host now has.
     *
     * <p>One coordinate can in principle be held by two carriers: a Create contraption's local coordinates are 0..15
     * around its anchor, so two contraptions can hold a host at the same coordinate. The last write wins, and the
     * consequence is bounded - the caller compares the carrier it found with its own, so a lost entry can only hide
     * an arm, never invent one. A Sable sub-level's coordinates are plot coordinates, which no second structure
     * shares.
     */
    public Map<Long, CarrierId> localHostIndex() {
        Map<Long, CarrierId> out = new HashMap<>();
        for (List<CarriedView> bucket : bySection.values()) {
            for (CarriedView view : bucket) {
                if (view.slot() != null) {
                    out.put(view.localPos().asLong(), view.carrier());
                }
            }
        }
        return out;
    }

    /**
     * Whether updates for {@code dim} belong to the level this index describes.
     *
     * <p>An update that arrives before the snapshot would mean the server forgot to send one; the
     * dimension is adopted rather than dropping the update silently. Same rule as {@code HostIndex}.
     */
    private boolean accepts(ResourceKey<Level> dim) {
        if (dimension == null) {
            dimension = dim;
        }
        return dimension.equals(dim);
    }

    /**
     * Whether a question about {@code dim} is a question about the level this index describes.
     *
     * <p>The read-only form of {@link #accepts}, for a caller that must <b>not</b> adopt a dimension as
     * a side effect: a Jade overlay asking "is the component inside this physicalised block carried by
     * something I know about" runs once per frame and is not an update, so it may never be the thing
     * that decides which level this index is about. Unlike {@code accepts}, an index that has not been
     * told a dimension yet answers {@code false} rather than adopting the caller's.
     */
    public boolean acceptsDimension(@Nullable ResourceKey<Level> dim) {
        return dim != null && dim.equals(dimension);
    }

    /** Drops everything, e.g. when leaving the world. */
    public void clear() {
        bySection.clear();
        anchorsByPose.clear();
        poses.clear();
        poseTicks.clear();
        dimension = null;
        now = 0L;
    }

    // ------------------------------------------------------- staleness (self-heal) --

    /**
     * Moves the client's own clock forward one tick.
     *
     * <p>Deliberately the client's tick counter and not the level's game time: what is being measured
     * is "how long has this client been holding this pose", and a client that has just joined, or just
     * changed dimension, must not inherit an age from a clock it was not there for.
     */
    public void tick() {
        now++;
    }

    /** The client's own tick counter, for the diagnostics. */
    public long now() {
        return now;
    }

    /**
     * How many ticks ago this carrier's pose was last refreshed, or {@code -1} when there is no pose.
     *
     * <p>The number that decides whether a carried host may still be drawn inside its structure.
     */
    public long poseAge(CarrierId carrier) {
        Long stamped = poseTicks.get(carrier);
        return stamped == null || !poses.containsKey(carrier) ? -1L : Math.max(0L, now - stamped);
    }

    /**
     * Whether this carrier's pose is older than {@code maxAgeTicks}, i.e. no longer trustworthy.
     *
     * <p>Pure arithmetic, so the threshold and the boundary are unit-testable without a game - which is
     * the point: this predicate is the difference between a component that can be drawn and one that
     * can never be drawn again.
     */
    public boolean isPoseStale(CarrierId carrier, long maxAgeTicks) {
        long age = poseAge(carrier);
        return age < 0 || age > Math.max(0L, maxAgeTicks);
    }

    /** Every carrier whose pose is older than {@code maxAgeTicks}; the ones about to be let go of. */
    public List<CarrierId> staleCarriers(long maxAgeTicks) {
        List<CarrierId> out = new ArrayList<>();
        for (CarrierId carrier : poses.keySet()) {
            if (isPoseStale(carrier, maxAgeTicks)) {
                out.add(carrier);
            }
        }
        return out;
    }

    /**
     * The client's last resort: drops every carrier whose pose has gone stale.
     *
     * <p>The server removes a carrier from its index from several places, and one of those forgetting to announce it
     * would leave the client holding a carrier it can never draw - the host is neither drawn inside the structure nor
     * put back on the world path, until the client reconnects. The removal notice is a single exit point on the
     * server (see {@code CarrierStore.forget}), and this is the second, independent guarantee: a pose that has not
     * been refreshed for a while is not a pose, so the carrier is let go of here, on the client.
     *
     * <p>Callers take {@link #viewsOf} for each returned carrier <em>before</em> calling this, or read them
     * afterwards from a copy, because the hosts go with the carrier.
     *
     * @param maxAgeTicks how long a pose stays trustworthy, in client ticks
     * @return the carriers that were dropped, so the caller can put their hosts back on the world path
     */
    public List<CarrierId> expireStale(long maxAgeTicks) {
        List<CarrierId> stale = staleCarriers(maxAgeTicks);
        if (stale.isEmpty()) {
            return List.of();
        }
        List<CarrierId> dropped = new ArrayList<>(stale.size());
        for (CarrierId carrier : stale) {
            drop(carrier);
            dropped.add(carrier);
        }
        return List.copyOf(dropped);
    }

    /** Every carried host the client attributes to one carrier. */
    public List<CarriedView> viewsOf(CarrierId carrier) {
        List<CarriedView> out = new ArrayList<>();
        for (List<CarriedView> bucket : bySection.values()) {
            for (CarriedView view : bucket) {
                if (view.carrier().equals(carrier)) {
                    out.add(view);
                }
            }
        }
        return out;
    }

    /**
     * How many carried hosts are waiting for a pose the client does not have.
     *
     * <p>A provisional carrier is excluded: a claim made before the contraption entity existed
     * genuinely has no pose for a tick or two, and reporting that as a hole would make the ordinary
     * assembly path look broken. Everything else here is a host that cannot be drawn.
     */
    public int hostsWithoutPose() {
        int total = 0;
        for (CarrierId carrier : carriersWithoutPose()) {
            total += viewsOf(carrier).size();
        }
        return total;
    }

    /**
     * The carriers the client holds hosts for and could not place even if it wanted to.
     *
     * <p>Provisional carriers are excluded; see {@link #hostsWithoutPose}.
     */
    public List<CarrierId> carriersWithoutPose() {
        List<CarrierId> out = new ArrayList<>();
        Set<CarrierId> seen = new java.util.LinkedHashSet<>();
        for (List<CarriedView> bucket : bySection.values()) {
            for (CarriedView view : bucket) {
                if (poses.containsKey(view.carrier()) || CarrierIdentity.isProvisional(view.carrier())) {
                    continue;
                }
                if (seen.add(view.carrier())) {
                    out.add(view.carrier());
                }
            }
        }
        return out;
    }

    /** Drops one carrier's pose and everything the client believed was riding it. */
    public void drop(CarrierId carrier) {
        poses.remove(carrier);
        previousPoses.remove(carrier);
        poseTicks.remove(carrier);
        unanchor(carrier);
        bySection.values().forEach(list -> list.removeIf(view -> view.carrier().equals(carrier)));
        bySection.long2ObjectEntrySet().removeIf(entry -> entry.getValue().isEmpty());
    }

    // ---------------------------------------------------------------- reads --

    /** The pose of a carrier, or {@code null} when the client has not been told one. */
    @Nullable
    public CarrierPose poseOf(CarrierId carrier) {
        return poses.get(carrier);
    }

    /**
     * The pose to draw at this frame: the last two received poses, interpolated by the partial tick.
     *
     * <p>{@code partialTick <= 0} and {@code >= 1} answer a whole pose (the previous and the current one), so a
     * caller with no partial tick keeps the whole-pose behaviour - which is every caller that is not drawing: a
     * click, a solve, a test. Sable draws the structure interpolated, so components drawn at the newest whole pose
     * would lead the machine by up to a tick, proportionally to speed.
     */
    @Nullable
    public CarrierPose poseOf(CarrierId carrier, double partialTick) {
        return CarrierPose.lerp(partialTick, previousPoses.get(carrier), poses.get(carrier));
    }

    /** True when the client has been told about this carrier at all. */
    public boolean knows(CarrierId carrier) {
        return poses.containsKey(carrier);
    }

    /** The dimension this index describes, or {@code null} before the first snapshot or update. */
    @Nullable
    public ResourceKey<Level> dimension() {
        return dimension;
    }

    /** How many carriers the client knows a pose for. */
    public int carrierCount() {
        return poses.size();
    }

    /** Every carrier the client is holding a pose for, for the diagnostics. */
    public List<CarrierId> carriers() {
        return new ArrayList<>(poses.keySet());
    }

    /**
     * Every carrier and how many ticks ago its pose arrived, oldest-looking last.
     *
     * <p>For the diagnostics: when a count is higher than it should be, the age is what says which entry
     * is the one that stopped being refreshed - and that is the difference between "a removal was never
     * announced" and "a pose is simply late".
     */
    public Map<CarrierId, Long> poseAges() {
        Map<CarrierId, Long> out = new LinkedHashMap<>();
        for (CarrierId carrier : poses.keySet()) {
            out.put(carrier, poseAge(carrier));
        }
        return out;
    }

    /**
     * How many carriers are filed under a name any client could ever be told about.
     *
     * <p>A provisional carrier is excluded, for a real reason: a claim made before the contraption entity existed is
     * not sent with a pose (there is none to send), so a client count that includes them would read as "the client is
     * holding more carriers than the server has" during the ordinary assembly of every machine. The comparison this
     * feeds is only meaningful between names the wire can actually carry.
     */
    public int settledCarrierCount() {
        int total = 0;
        for (CarrierId carrier : poses.keySet()) {
            if (!CarrierIdentity.isProvisional(carrier)) {
                total++;
            }
        }
        return total;
    }

    /** How many carriers are still filed under a provisional identity; for the diagnostics. */
    public int provisionalCarrierCount() {
        int total = 0;
        for (CarrierId carrier : poses.keySet()) {
            if (CarrierIdentity.isProvisional(carrier)) {
                total++;
            }
        }
        return total;
    }

    /**
     * Visits every carried host whose origin lies in a section near {@code center}.
     *
     * <p>Section-based like {@code HostIndex#forEachNear}, and for the same reason: the renderer asks
     * once per frame, so it must walk occupied sections rather than every host in the level. The
     * <em>drawn</em> position comes from the carrier's pose, so a host bucketed far away may still be
     * drawn near the camera - which is why the renderer measures the transformed position again rather
     * than trusting this radius alone.
     */
    public void forEachNear(ResourceKey<Level> dim, BlockPos center, int sectionRadius,
                            Consumer<CarriedView> action) {
        if (dimension == null || !dimension.equals(dim)) {
            return;
        }
        int centerX = SectionPos.blockToSectionCoord(center.getX());
        int centerY = SectionPos.blockToSectionCoord(center.getY());
        int centerZ = SectionPos.blockToSectionCoord(center.getZ());
        for (Long2ObjectMap.Entry<List<CarriedView>> bucket : bySection.long2ObjectEntrySet()) {
            long key = bucket.getLongKey();
            if (Math.abs(SectionPos.x(key) - centerX) > sectionRadius
                    || Math.abs(SectionPos.y(key) - centerY) > sectionRadius
                    || Math.abs(SectionPos.z(key) - centerZ) > sectionRadius) {
                continue;
            }
            for (CarriedView view : bucket.getValue()) {
                action.accept(view);
            }
        }
    }

    /**
     * Every carried host of a carrier whose current pose lies in a section near {@code center}: the extra walk that
     * finds a machine which has travelled beyond the origin walk's radius.
     *
     * <p>Strictly additional, never a substitute for {@link #forEachNear}. The outer loop is bounded by the number of
     * carriers the client holds (the moving structures in the level, not the number of host blocks), and only a
     * carrier whose pose section is inside the radius has its hosts read at all; that read is {@link #viewsOf}. The
     * key is computed exactly as {@link #forEachNear}'s is - {@code SectionPos.asLong} of a block position compared
     * against the camera's own {@code blockToSectionCoord} - so the two walks cannot disagree about what "near"
     * means.
     */
    public void forEachCarrierNearPose(ResourceKey<Level> dim, BlockPos center, int sectionRadius,
                                       Consumer<CarriedView> action) {
        if (dimension == null || !dimension.equals(dim)) {
            return;
        }
        int centerX = SectionPos.blockToSectionCoord(center.getX());
        int centerY = SectionPos.blockToSectionCoord(center.getY());
        int centerZ = SectionPos.blockToSectionCoord(center.getZ());
        for (Long2ObjectMap.Entry<List<CarrierId>> bucket : anchorsByPose.long2ObjectEntrySet()) {
            long key = bucket.getLongKey();
            if (Math.abs(SectionPos.x(key) - centerX) > sectionRadius
                    || Math.abs(SectionPos.y(key) - centerY) > sectionRadius
                    || Math.abs(SectionPos.z(key) - centerZ) > sectionRadius) {
                continue;
            }
            for (CarrierId carrier : bucket.getValue()) {
                // The hosts come from the one place hosts are stored, so this walk cannot invent one.
                for (CarriedView view : viewsOf(carrier)) {
                    action.accept(view);
                }
            }
        }
    }

    // ------------------------------------------------------ the pose anchor --

    /**
     * Files (or re-files) one carrier under the section its pose currently sits in.
     *
     * <p>Idempotent: a carrier is removed from wherever it was filed and put back under the section the
     * pose names now, so calling it once per pose update cannot accumulate entries. A carrier with no pose
     * is unfiled, which is the honest state - and because the origin walk answers for such a carrier, an
     * empty anchor costs nothing but the extra walk finding nothing.
     *
     * <p>Called from every path that puts a pose into {@link #poses} ({@link #applySnapshot},
     * {@link #applyPose}, {@link #applyHost}), which is what makes "the pose is held" and "the anchor is
     * filed" the same fact rather than two that can drift apart.
     */
    private void anchor(CarrierId carrier) {
        unanchor(carrier);
        CarrierPose pose = poses.get(carrier);
        if (pose == null || pose.position() == null) {
            return;
        }
        long key = poseSectionKey(pose);
        List<CarrierId> bucket = anchorsByPose.computeIfAbsent(key, ignored -> new ArrayList<>());
        if (!bucket.contains(carrier)) {
            bucket.add(carrier);
        }
    }

    /** Removes one carrier from the pose anchor, wherever it currently sits; idempotent. */
    private void unanchor(CarrierId carrier) {
        anchorsByPose.values().forEach(list -> list.remove(carrier));
        anchorsByPose.long2ObjectEntrySet().removeIf(entry -> entry.getValue().isEmpty());
    }

    /**
     * The section a pose sits in - the same computation, in the same frame, as {@link #bySection}'s key.
     *
     * <p>{@code SectionPos.asLong(BlockPos)} for the bucket and {@code SectionPos.x/y/z(long)} against
     * {@code blockToSectionCoord(camera)} for the comparison, the same pattern {@link #forEachNear} uses, so both
     * walks are keyed and compared in one coordinate system. What it cannot promise is that the number inside a pose
     * is a world position at all - that is the pose's business, and it is why this walk is additive rather than
     * authoritative.
     */
    private static long poseSectionKey(CarrierPose pose) {
        return SectionPos.asLong(BlockPos.containing(
                pose.position().x(), pose.position().y(), pose.position().z()));
    }

    /** How many entries the pose anchor holds, for the diagnostics and the idempotence assertions. */
    int anchoredCarrierCount() {
        int total = 0;
        for (List<CarrierId> bucket : anchorsByPose.values()) {
            total += bucket.size();
        }
        return total;
    }

    /** Every carried host the client knows about, for diagnostics. */
    public List<CarriedView> all() {
        List<CarriedView> out = new ArrayList<>();
        for (List<CarriedView> bucket : bySection.values()) {
            out.addAll(bucket);
        }
        return out;
    }

    /**
     * Visits everything the carried path may draw near {@code center}, each entry saying whether it is
     * drawn inside its structure or at the world block it came from.
     *
     * <p>The sets are one walk on purpose. A host is either carried or put down, never both, and a
     * caller that walked the two lists separately could draw the same component twice for a frame -
     * which looks exactly like a duplicated block and is much harder to explain than either of the
     * states it comes from.
     *
     * <p>Three sources, folded in this order, and the order is the precedence:
     *
     * <ol>
     *   <li><b>carried</b> - hosts riding a carrier the client holds a pose for. Drawn inside the
     *       structure.</li>
     *   <li><b>landed</b> - hosts whose carrier the server has taken back, still drawn at the position
     *       they were put down at ({@link LandedHosts}). This is what makes a component that comes home
     *       to a block-less position visible; see that class for the defect it closes.</li>
     *   <li><b>expired</b> - the caller's own fallback list, hosts whose carrier's pose went stale,
     *       drawn at the block they came from.</li>
     * </ol>
     *
     * <p>A carried host always wins over a landed record of the same host: a fresh pose is the newer
     * truth, and the caller drops the landed record when the carrier is heard from again.
     *
     * @param landed hosts whose carrier is gone, drawn at their landing position
     * @param expired hosts whose carrier the caller has already let go of, drawn at their origin
     */
    public void forEachDrawable(ResourceKey<Level> dim, BlockPos center, int sectionRadius,
                                LandedHosts landed, List<CarriedView> expired,
                                Consumer<Drawable> action) {
        forEachDrawable(dim, center, sectionRadius, landed, expired, 1.0, action);
    }

    /** {@link #forEachDrawable} with the frame's partial tick; see {@link #drawablesNear} for why. */
    public void forEachDrawable(ResourceKey<Level> dim, BlockPos center, int sectionRadius,
                                LandedHosts landed, List<CarriedView> expired, double partialTick,
                                Consumer<Drawable> action) {
        for (Drawable drawable : drawablesNear(dim, center, sectionRadius, landed, expired, partialTick)) {
            action.accept(drawable);
        }
    }

    /**
     * The list form of {@link #forEachDrawable}; also what the tests read, with the frame's partial tick: the poses
     * handed out are interpolated between the last two received ones.
     *
     * <p>The carried set is the union of two walks: {@link #forEachNear}, keyed on the block each host <b>came
     * from</b>, which is unconditional and is the guarantee that this method never hands over less than it did
     * before, and {@link #forEachCarrierNearPose}, which adds whatever the carriers' <b>current</b> poses bring into
     * range. The second can only add, and it is written as {@code putIfAbsent} so that it cannot overwrite what the
     * first produced. Both feed one fold, and a host found by both is still one drawable. Landed hosts are folded in
     * by carrier plus local coordinate like everything else, so a host in two lists at once is drawn once.
     *
     * <p>Only the pose that goes into a drawable is interpolated: the anchor walks keep using the <b>whole</b> pose,
     * because they decide which hosts are near the camera and a host must not enter or leave the drawn set because
     * of a sub-tick offset.
     */
    public List<Drawable> drawablesNear(ResourceKey<Level> dim, BlockPos center, int sectionRadius,
                                        @Nullable LandedHosts landed,
                                        List<CarriedView> expired, double partialTick) {
        List<Drawable> out = new ArrayList<>();
        if (dimension == null || !dimension.equals(dim)) {
            return out;
        }
        // Keyed by carrier and local coordinate, which is what identifies one host across all the lists.
        Map<String, CarriedView> folded = new java.util.LinkedHashMap<>();
        Set<String> landedKeys = new java.util.HashSet<>();
        // WALK ONE, ALWAYS: every host whose origin bucket is near the camera, exactly as before.
        forEachNear(dim, center, sectionRadius,
                view -> folded.put(identityOf(view), view));
        // WALK TWO, ADDITIVE: carriers whose current pose is near the camera, which is how a machine that
        // has driven away is still drawn. `putIfAbsent` because this walk may only add - a plain `put`
        // would let it replace the very view walk one handed over.
        forEachCarrierNearPose(dim, center, POSE_ANCHOR_RADIUS,
                view -> folded.putIfAbsent(identityOf(view), view));
        if (landed != null && !landed.isEmpty()) {
            landed.forEachNear(center, sectionRadius, view -> {
                String key = identityOf(view);
                // A carried host always wins: a fresh pose is the newer truth than a put-down record,
                // and the caller drops the record the moment the carrier is heard from again.
                if (folded.putIfAbsent(key, view) == null) {
                    landedKeys.add(key);
                }
            });
        }
        for (CarriedView view : expired) {
            String key = identityOf(view);
            if (folded.containsKey(key)) {
                continue;
            }
            if (!withinRadius(view.origin(), center, sectionRadius)) {
                continue;
            }
            folded.put(key, view);
        }
        for (Map.Entry<String, CarriedView> entry : folded.entrySet()) {
            CarriedView view = entry.getValue();
            out.add(new Drawable(view, poseOf(view.carrier(), partialTick),
                    landedKeys.contains(entry.getKey())));
        }
        return out;
    }

    /**
     * A whole pose (the newest one), which is what every non-render caller wants - a click, a solve, a
     * test, and the wiring tests that read this method's shape. Deliberately written after the
     * partial-tick form so that "the entry point" a reader finds first is the one that does the work.
     */
    public List<Drawable> drawablesNear(ResourceKey<Level> dim, BlockPos center, int sectionRadius,
                                        @Nullable LandedHosts landed,
                                        List<CarriedView> expired) {
        return drawablesNear(dim, center, sectionRadius, landed, expired, 1.0);
    }

    /**
     * The list form kept from before landed hosts existed: no landed set, so only carried and expired
     * hosts are folded in.
     *
     * <p>Kept because it is the shape the existing tests and the fallback sweep read, and because "no
     * landed hosts at all" is a real state (a client that has never seen a structure put anything down).
     */
    public List<Drawable> drawablesNear(ResourceKey<Level> dim, BlockPos center, int sectionRadius,
                                        List<CarriedView> expired) {
        return drawablesNear(dim, center, sectionRadius, null, expired);
    }

    /** How one host is identified: its carrier plus its local coordinate inside it. */
    private static String identityOf(CarriedView view) {
        return view.carrier().shortId() + "@" + view.localPos().asLong();
    }

    /** Whether a position's section is within the radius the renderer asked about. */
    private static boolean withinRadius(BlockPos pos, BlockPos center, int sectionRadius) {
        return Math.abs(SectionPos.blockToSectionCoord(pos.getX())
                        - SectionPos.blockToSectionCoord(center.getX())) <= sectionRadius
                && Math.abs(SectionPos.blockToSectionCoord(pos.getY())
                        - SectionPos.blockToSectionCoord(center.getY())) <= sectionRadius
                && Math.abs(SectionPos.blockToSectionCoord(pos.getZ())
                        - SectionPos.blockToSectionCoord(center.getZ())) <= sectionRadius;
    }

    /** How many carried hosts the client knows about. */
    public int size() {
        int total = 0;
        for (List<CarriedView> bucket : bySection.values()) {
            total += bucket.size();
        }
        return total;
    }

    // ------------------------------------------------- read-only diagnostics --

    /**
     * Which of one carrier's hosts the origin walk actually offered, and how many of them fell outside it.
     *
     * <p>Nothing here decides anything: every field is a question asked of the same buckets and the same pose map the
     * renderer's own walk reads. {@code shift} is the whole of the difference between this sweep and
     * {@link #forEachNear}, and it is the shift that makes the finding visible - the carriers whose every host fell
     * outside the radius are named instead of silently absent. Cost is {@code O(hosts of this carrier)} with no
     * allocation beyond the record, and its callers run it at most once per {@code CarrierSightLog} window.
     *
     * @param carrier the carrier to describe
     * @param shift the centre the walk's radius was measured from, or {@code null} for "no camera"
     * @param radius the walk's section radius; a negative value means "no radius question asked"
     */
    public SectionWalk sectionWalk(CarrierId carrier, @Nullable BlockPos shift, int radius) {
        List<CarriedView> hosts = viewsOf(carrier);
        int walked = 0;
        boolean anyInRadius = false;
        boolean anyOutOfRadius = false;
        if (hosts.isEmpty()) {
            // A carrier the client holds a pose for and no hosts: "every host is outside the radius" is
            // vacuously true, and reporting that as a radius fault would be crying wolf on a carrier
            // that simply has nothing in it.
            return new SectionWalk(0, 0, true);
        }
        for (CarriedView host : hosts) {
            // Origin and landed hosts are bucketed and walked by `origin`; a landed entry additionally
            // has to survive a radius test on `at`, which for a landed host is where it was put down.
            // The conservative half: a landed host whose `at` is out of radius but whose `origin` is in
            // it is counted as walked here, and the renderer's own counters are the authority for it.
            if (radius < 0 || shift == null || withinRadius(host.origin(), shift, radius)) {
                walked++;
                anyInRadius = true;
            } else {
                anyOutOfRadius = true;
            }
        }
        return new SectionWalk(walked, hosts.size(), anyInRadius || !anyOutOfRadius);
    }

    /**
     * One carrier as the origin walk saw it: how many of its hosts were inside the radius, out of how many, and
     * whether the carrier was offered at all.
     *
     * <p><b>This describes the origin walk only.</b> The renderer also walks the pose anchor
     * ({@link #forEachCarrierNearPose}), so a carrier this sweep reports as {@code offered=false} can still be drawn
     * by the additive walk - which is what that walk is for. The two numbers are therefore "what the origin walk
     * did", never "what was drawn".
     *
     * @param walkedHosts hosts whose origin section is inside the radius
     * @param totalHosts hosts the carrier holds
     * @param offered whether the origin walk offered the carrier to the renderer at all; false means every host it
     *     holds sits in an origin bucket nobody walks
     */
    public record SectionWalk(int walkedHosts, int totalHosts, boolean offered) {
    }

    // --------------------------------------------------------------- helpers --

    private void put(CarriedView host) {
        long key = SectionPos.asLong(host.origin());
        List<CarriedView> bucket = bySection.computeIfAbsent(key, ignored -> new ArrayList<>());
        for (int i = 0; i < bucket.size(); i++) {
            CarriedView existing = bucket.get(i);
            if (existing.carrier().equals(host.carrier())
                    && existing.localPos().equals(host.localPos())) {
                bucket.set(i, host);
                return;
            }
        }
        bucket.add(host);
    }

    private void remove(CarriedView host) {
        long key = SectionPos.asLong(host.origin());
        List<CarriedView> bucket = bySection.get(key);
        if (bucket == null) {
            return;
        }
        bucket.removeIf(view -> view.carrier().equals(host.carrier())
                && view.localPos().equals(host.localPos()));
        if (bucket.isEmpty()) {
            bySection.remove(key);
        }
    }
}

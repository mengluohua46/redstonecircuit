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
 * The client's copy of "which blocks hold redstone, and what is inside them".
 *
 * <p>This is the client-only half: it owns the shared {@link HostIndex} and the
 * {@link CarrierIndex}. The bookkeeping itself lives in those two so it can be tested without a game.
 *
 * <h2>Nothing is baked into the world</h2>
 * The shell and the component inside it are drawn once per frame rather than written into the chunk
 * mesh, so a change here needs no mesh rebuild: the next frame simply draws something else. (The
 * earlier build wrote the shell into the section's own buffer through
 * {@code AddSectionGeometryEvent}, which looked right but could not be switched off again - a baked
 * mesh cannot react to a player putting goggles on - and had to force a section rebuild on every
 * power change.)
 *
 * <h2>Two stores on the client as well</h2>
 * {@link HostIndex} holds the hosts standing in the world; {@link CarrierIndex} holds the ones riding
 * a moving structure, together with where each structure is. They are separate here for the same
 * reason they are on the server: a host that has been picked up is not at a world position any more,
 * and pretending it is would be the bug this whole stage exists to fix.
 *
 * <h2>The fallback, and why the client is allowed to have one</h2>
 * The server is authoritative about everything here, with exactly one exception, and it is a defensive
 * one: <b>a carrier whose pose has gone stale is let go of, and the hosts it was holding fall back to
 * their original world positions</b> ({@link #tickCarriers}). The rule this enforces is the one whose
 * absence produced a component that could never be drawn again: a host may only be treated as "in a
 * moving structure" while the client actually knows where that structure is. If it does not, the host
 * is drawn the ordinary way - at the block it came from - which is wrong in a way a player can see and
 * explain, rather than wrong in a way that looks like the component was deleted.
 *
 * <p>The fallback is a <em>view</em>, not data: the dropped hosts are kept in a short side list and
 * handed to the render pass, and nothing is written into {@link HostIndex}. The moment a fresh pose
 * arrives for that carrier the entry is dropped and the host is drawn inside the structure again, so a
 * heartbeat that merely arrived late costs at most a moment of the component sitting where it was
 * picked up.
 */
public final class ClientHostCache {

    private static final HostIndex INDEX = new HostIndex();
    private static final CarrierIndex CARRIERS = new CarrierIndex();

    /**
     * Hosts whose carrier the server has taken back, still drawn at the position they were put down at.
     *
     * <p>The third state a host can be in, and the one that used to be invisible: the carrier is gone,
     * the client was told the component is back in the world, and the block at that position may not
     * exist - so the world path skips it (it refuses to draw a shell over air) and the carried path no
     * longer has it. See {@link LandedHosts} for the full story.
     *
     * <p>Only ever written on the client thread; the render thread reads it through the read-only
     * methods below, which is why it is declared {@code volatile}.
     */
    private static volatile LandedHosts landed = new LandedHosts();

    /**
     * Hosts whose carrier was let go of, drawn at their original world position until told otherwise.
     *
     * <p>A short list, walked once per tick and once per frame. Only ever written on the client thread,
     * but declared volatile so the render thread never reads a half-built list.
     */
    private static volatile List<CarrierIndex.CarriedView> fallbacks = List.of();

    private ClientHostCache() {
    }

    /** Replaces the whole set. Sent on login, dimension change and respawn. */
    public static void applySnapshot(ResourceKey<Level> dimension, List<HostEntry> entries) {
        INDEX.applySnapshot(dimension, entries);
        // Logged because "the client never learned about the block" and "the client learned but drew
        // nothing" look identical on screen; this line tells the two apart.
        if (RCConfig.debugLog()) {
            RCConfig.LOGGER.info("[redstonecircuit] client: {} host(s) in {}",
                    INDEX.size(), dimension.location());
        }
    }

    /** Adds, updates or removes one position. */
    public static void applyChange(ResourceKey<Level> dimension, BlockPos pos, @Nullable Slot slot) {
        int before = INDEX.size();
        INDEX.applyChange(dimension, pos, slot);
        // A position the ordinary sync has just named is a world host again - but that is only half of
        // what it takes for the world path to draw it, and the other half is the block itself. This is
        // the frame window that used to make a put-down component invisible: the sync arrives at once,
        // Create puts the block back a moment later, and a landed record dropped on the sync alone left
        // the world path refusing to draw over air with the carried path already gone. So the record is
        // forgotten here only when the world path could really draw it *now*; while it cannot, the
        // record stays and draws itself (see HostRenderPass#collectCarried).
        forgetLandedWhereWorldPathDraws(dimension, List.of(new HostEntry(pos.immutable(), slot)));
        if (INDEX.size() != before && RCConfig.debugLog()) {
            RCConfig.LOGGER.info("[redstonecircuit] client: host {} at {} -> {} host(s) in total",
                    slot != null ? "added" : "removed", pos.toShortString(), INDEX.size());
        }
    }

    /** Adds, updates or removes several positions in one packet. */
    public static void applyChanges(ResourceKey<Level> dimension, List<HostEntry> entries) {
        INDEX.applyChanges(dimension, entries);
        forgetLandedWhereWorldPathDraws(dimension, entries);
    }

    /**
     * Forgets the landed records the world path has genuinely taken over <b>right now</b>.
     *
     * <p>One predicate for the whole client: {@link #worldPathDraws}, which is the same call the render
     * pass makes before it decides that a landed record should yield. A world-host slot alone is not
     * enough - the block has to be there too - so a position the sync has announced while the block is
     * still on its way keeps its record, which is exactly the state the record exists for.
     *
     * <p>Only consulted for entries that <em>add</em> a slot. An entry with a {@code null} slot is the
     * server saying "this position is not a host any more", and that is the one condition under which a
     * landed record must <b>not</b> be dropped: the component is still there as far as the client is
     * concerned, and the landed record is the only path that can draw it. A world host that later comes
     * back clears the record then, through this same method.
     */
    private static void forgetLandedWhereWorldPathDraws(ResourceKey<Level> dimension,
                                                       List<HostEntry> entries) {
        if (landed.isEmpty() || entries.isEmpty()) {
            return;
        }
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || !level.dimension().equals(dimension)) {
            // No level to ask, or a packet for another dimension: there is no honest answer to "would
            // the world path draw here", so the record stays. Keeping it is the safe half of the
            // choice - the renderer will draw it only where the world path does not.
            return;
        }
        for (HostEntry entry : entries) {
            if (entry.slot() != null && worldPathDraws(level, dimension, entry.pos(), entry.slot())) {
                landed.forgetAt(entry.pos());
            }
        }
    }

    /**
     * <b>Whether the ordinary world path draws a position</b> - the single question every drawing and
     * yielding decision on the client is made by, asked of the live level.
     *
     * <p>The two facts are judged by {@link LandedHosts#worldPathDraws} (in turn
     * {@code HostPathOwnership}), which is pure and unit-tested; this method only supplies them, and it
     * supplies both of them <b>itself</b> rather than taking a caller's word for either:
     *
     * <ul>
     *   <li>{@code worldHost} is read from the host index, so a caller that looked the slot up a moment
     *       ago cannot hand in a stale one;</li>
     *   <li>{@code blockPresent} additionally requires the chunk to be loaded, because an unloaded
     *       chunk answers {@code isAir()} for every position in it - which would look like "the block
     *       is gone" and hand the position back to the landed path while the world path is in fact
     *       about to draw it.</li>
     * </ul>
     *
     * <p>The render pass calls this before drawing a world host, and calls it again before letting a
     * landed record yield: the same call, at both ends of the same question, which is the whole reason
     * it exists. {@code slot} is the caller's copy of the contents, used as a cheap refusal when the
     * caller already knows there is nothing; the host lookup below is what actually decides.
     */
    public static boolean worldPathDraws(ClientLevel level, ResourceKey<Level> dimension, BlockPos pos,
                                        @Nullable Slot slot) {
        boolean worldHost = slot != null && slotAt(dimension, pos) != null;
        boolean blockPresent = level.isLoaded(pos) && !level.getBlockState(pos).isAir();
        return LandedHosts.worldPathDraws(worldHost, blockPresent);
    }

    /** Drops everything, e.g. when leaving the world. */
    public static void clear() {
        INDEX.clear();
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
     * <b>The component inside a block a structure is carrying, by the block's own coordinate.</b>
     *
     * <h2>Why the world lookup cannot answer this</h2>
     * A carried host has no world position of its own: it has a local coordinate inside a structure that
     * is somewhere else. A Sable sub-level's blocks are the level's blocks <em>at their local
     * coordinates</em> (a reserved plot region), so a ray into a physicalised block yields the local
     * coordinate - which is exactly the coordinate the carrier payload names its hosts by, and exactly
     * the coordinate {@link HostIndex} has nothing at. That is why Jade shows an empty overlay over a
     * physicalised block: the world host sync genuinely has no entry to show.
     *
     * <p>Asked only by readers that have a coordinate and want the contents: Jade's block overlay
     * ({@code RedstoneCircuitJadePlugin}), which is a client-only, per-frame question answered out of
     * this client's own copy of the carrier payload. Nothing is sent, and no level is consulted.
     *
     * @param dimension the level the coordinate belongs to, or {@code null} for "whatever this client
     *     is in" - a Jade accessor does not always have one to hand
     * @param localPos the block's coordinate in the structure's own frame
     * @return the component, or {@code null} when nothing is carried there
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
     * <b>One frame's "which structure's own coordinate holds a component" (P1.42).</b>
     *
     * <p>The neighbour question the carried render path has to ask in the structure's own frame: a wire
     * inside a physicalised block connects to the wire inside the block beside it, and that block is
     * named by a local coordinate, not by a world position. Built once per frame by the caller rather
     * than looked up per side per host - see {@link CarrierIndex#localHostIndex()} for the frame, the
     * carrier identity it hands back and the one collision it accepts.
     *
     * @param dimension the level the render pass is drawing, or {@code null} for "nothing"
     */
    public static Map<Long, CarrierId> carriedLocalIndex(@Nullable ResourceKey<Level> dimension) {
        if (!CARRIERS.acceptsDimension(dimension)) {
            return Map.of();
        }
        return CARRIERS.localHostIndex();
    }

    /**
     * The same lookup, with the structure and the local coordinate the client resolved - for the
     * diagnostics, which want to name what they found rather than only its contents.
     */
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
     * One carrier's pose, and the hosts it is carrying.
     *
     * <p>A {@code null} pose means the structure is gone, so its pose and everything riding it are
     * dropped. Otherwise the pose is updated first and the host list is replaced wholesale: a host
     * that was riding this carrier and is not in the new list has been put down.
     *
     * <h2>What happens to a host that is not in the new list</h2>
     * It becomes a <em>landed</em> host ({@link LandedHosts}): kept, and drawn at the position the
     * server says it was put down at, until the ordinary world sync claims that position. That is the
     * whole of the fix for "a component that a structure carried never appears again once it is put
     * down": the world path only draws a host over a real block, and the block a machine comes to rest
     * on is not guaranteed to be there.
     *
     * <p>The views are read <b>before</b> the host list is replaced, and that ordering is the point:
     * the record of where a host belongs only exists while the client still holds it.
     */
    public static void applyCarrier(ResourceKey<Level> dimension, CarrierId carrier,
                                    @Nullable CarrierPose pose,
                                    List<CarrierIndex.CarriedView> hosts) {
        applyCarrier(dimension, carrier, pose, hosts, List.of());
    }

    /**
     * The same, with the hosts the server has <b>discarded</b> named.
     *
     * <h2>Why this parameter exists</h2>
     * Without it, a host leaving the list could only mean one thing to this method - "put down" - and
     * it was kept as a landed record and drawn at {@code at()} for as long as the client held it. A
     * host whose block has been <b>destroyed</b> leaves the list the same way, so the outline and the
     * component stayed on screen inside the structure until the structure was put down: the ghost the
     * author reported, and the reason a removal notice had to be added at the host level rather than
     * only at the carrier level.
     *
     * <p>{@code forgotten} is that notice. Those hosts are dropped from the carried index <b>and</b>
     * from the landed records, and they are deliberately not stowed - the component is an item on the
     * floor now, and there is nothing anywhere to draw.
     *
     * <p>The order below is the part that matters: the removal is applied <b>first</b>, and the stow
     * below is filtered, so a host named in both lists can never be re-created as a landed record in
     * the same call. There is no path from "the server said it was destroyed" to "the client draws it".
     */
    public static void applyCarrier(ResourceKey<Level> dimension, CarrierId carrier,
                                    @Nullable CarrierPose pose,
                                    List<CarrierIndex.CarriedView> hosts, List<BlockPos> forgotten) {
        // Snapshot what the carrier was holding, before anything below changes it. A `null` pose is
        // about to drop the whole thing; a changed host list is about to drop individual entries. Both
        // need the same "where was each host" answer, and it is only available here.
        List<CarrierIndex.CarriedView> wasHolding = CARRIERS.viewsOf(carrier);
        // The removal list is decided once, here, over the record of what the client held: what was
        // discarded is dropped outright, and only what is left may be stowed as landed.
        LandedHosts.Forget forget = LandedHosts.forget(wasHolding, forgotten, carrier);
        List<CarrierIndex.CarriedView> forgottenViews = forget.forgotten();
        List<CarrierIndex.CarriedView> stowed = LandedHosts.departed(
                forget.kept(), hosts, pose == null);

        if (!forgotten.isEmpty()) {
            applyForget(dimension, carrier, forgotten, forgottenViews);
        }

        CARRIERS.applyPose(dimension, carrier, pose);
        if (pose == null) {
            // A carrier the server has taken back. Its fallback entries go with it: the removal means
            // the components are back in the world as far as the server is concerned, and the ordinary
            // world sync is what draws them where a block is standing.
            dropFallbackFor(carrier);
            // The hosts it was holding are NOT dropped - they are kept as landed hosts. Without this
            // the client's only record of where each one belongs goes out with the pose, and the world
            // path then skips it wherever no block is standing: invisible until something else touches
            // that position. This is the line that closes that defect.
            int added = landed.stow(stowed);
            // Logged at the moment it happens rather than only on the next frame: "the client never
            // heard" and "the client heard and ignored it" have to be told apart, and this is the
            // line that says the client heard.
            CarrierRenderTrace.log(dimension, carrier.shortId()
                    + " gone; kept " + stowed.size() + " host(s) as landed (" + added + " new, "
                    + landed.size() + " held now);");
            noteHeldCounts(dimension);
            return;
        }
        // Wholesale replacement needs the old list gone first; a carrier's host count is small enough
        // that this is a handful of removals.
        for (CarrierIndex.CarriedView existing : CARRIERS.all()) {
            if (existing.carrier().equals(carrier) && !contains(hosts, existing)) {
                CARRIERS.removeHost(existing);
            }
        }
        for (CarrierIndex.CarriedView host : hosts) {
            CARRIERS.applyHost(dimension, host);
        }
        // A carrier that is heard from again is no longer a fallback: whatever was being drawn at the
        // old world position goes back to being drawn inside the structure.
        dropFallbackFor(carrier);
        if (!stowed.isEmpty()) {
            // A host that left the carrier's list while the carrier itself is still there: it has been
            // put down by a structure that is still flying. Its entry is a landed one, and it is
            // deliberately not cleared by the pass below - only the hosts that are still riding are
            // represented in the carrier index.
            landed.stow(stowed);
        }
        // The hosts that ARE still riding are not landed: the server is answering a pose for this
        // carrier, so the newer truth for them is "riding".
        forgetLandedExcept(carrier, hosts);
        noteHeldCounts(dimension);
    }

    /**
     * Applies one host-level removal notice: gone from the carried index, gone from the landed
     * records, and gone from the fallback list.
     *
     * <p>All three, because they are the three places this client is able to draw a component that no
     * longer exists. Leaving any one of them is the ghost in a different shape: still riding, put down,
     * or falling back to the block it came from - and the third is exactly the shape the author
     * described ("it still shows the redstone component until the physical state is switched off"),
     * because the fallback is drawn at the origin, which is where the player is standing.
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
     * Drops the landed records of one carrier that are no longer valid, because its hosts are riding.
     *
     * <p>Only the hosts the carrier still lists are forgotten: a host the same carrier put down earlier
     * and is not carrying any more keeps its landed record, which is what makes a machine that puts one
     * block down and keeps flying draw that block where it landed rather than losing it.
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
     * <b>The self-healing sweep: one client tick.</b>
     *
     * <p>One thing happens here, deliberately: any carrier whose pose the server has stopped refreshing
     * - or whose message announcing its removal never arrived - is let go of, and the hosts it was
     * holding are registered as world-path fallbacks. This is the guarantee that "the component inside
     * the machine is invisible forever" cannot happen, whatever the server does or fails to do; the
     * server-side fix (a single release exit that always announces the removal) is what makes it rare.
     *
     * <p>The work is proportional to the number of carriers the client knows about - a handful - and
     * only when something is actually stale.
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
     * Visits everything the carried path is allowed to draw: the hosts whose carrier the client knows
     * the position of, the landed ones (drawn where they were put down), and the fallbacks, which are
     * drawn at the block they came from.
     *
     * <p>One walk for all three, because they are alternatives for the same host and a caller that
     * walked them separately would be able to draw the same component twice in one frame.
     */
    public static void forEachDrawable(ResourceKey<Level> dimension, BlockPos center, int sectionRadius,
                                       Consumer<CarrierIndex.Drawable> action) {
        CARRIERS.forEachDrawable(dimension, center, sectionRadius, landed, fallbacks, action);
    }

    /**
     * The same walk with the frame's partial tick, which is what the carried render pass uses (P1.55): the
     * poses handed to it are interpolated between the last two the server sent, so the components inside a
     * moving machine are drawn at the same instant of its motion that Sable draws the machine itself. See
     * {@link CarrierPose#lerp} and {@code CarrierIndex#drawablesNear}.
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
     * How many carriers the client is holding that it can no longer place.
     *
     * <p>The number that used to be invisible: with it at zero and the render pass drawing nothing,
     * the two facts together are a bug with no other symptom.
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
     * How many live carriers have no pose at all; the B1 sweep's {@code skippedNoPose} input.
     *
     * <p>A carrier count, not a host count: the rollup line counts <b>carriers</b>, and
     * {@link #hostsWithoutPose()} counts the host blocks that cannot be drawn because of this - one
     * carrier with three hosts is one skipped carrier and three skipped hosts, and the line says which.
     */
    public static int carriersWithoutPoseCount() {
        return CARRIERS.carriersWithoutPose().size();
    }

    // ------------------------------------------------- read-only diagnostics --

    /**
     * <b>The B1 sweep: every carrier the client holds, and which of them the origin walk offered.</b>
     *
     * <p>Called at most once per {@code CarrierSightLog} window, never per frame and never from the
     * collector: see {@link CarrierIndex#sectionWalk}, which is where the counting lives and which
     * nothing else reads. This method only adds what the buckets do not know - the pose's section, the
     * pose's age, and the camera's own section - and it writes nothing back.
     *
     * @param dimension the level the question is about
     * @param cameraSection the section the camera is in, or {@code null} when the caller has no camera
     * @param sectionRadius the walk's section radius
     * @param shift the point the radius is measured from for the distance-from-pose question
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
            // constant: at most CARRIER_PROBLEM_LINES_PER_WINDOW problem carriers are named per window,
            // and the ones the cap passes over are still counted in the rollup. A log that grew with the
            // machine count would be a log nobody can bound.
            if (!walk.offered() && shown < CarrierSightLog.CARRIER_PROBLEM_LINES_PER_WINDOW) {
                shown++;
                // THE CHECK, NOT A BAG OF FIELDS. The line the log carries is `CarrierHostCheck#describe`,
                // so the sweep hands it a value rather than reproducing its wording: one place words the
                // B1 line, and a field added there cannot go missing here.
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
     * What one B1 sweep found; {@link CarrierSweep#problems} names the carriers whose every host sits in
     * an origin bucket the walk did not visit.
     *
     * @param dimensionKnown whether the client's index describes the dimension that was asked about
     * @param carriers carriers the client holds
     * @param collected carriers the walk offered to the renderer this frame
     * @param originOnlySkipped carriers with a pose whose hosts were all outside the radius
     * @param noPose carriers the client has no pose for at all
     * @param problems the first {@code CARRIER_PROBLEM_LINES_PER_WINDOW} carriers that were not offered,
     *     each already worded by {@link CarrierHostCheck#describe()}; {@link #problemCount()} is how many
     *     there were in total, and {@code problems().size()} is how many are named
     */
    public record CarrierSweep(boolean dimensionKnown, int carriers, int collected, int originOnlySkipped,
                               int noPose, List<CarrierSection> problems) {

        /** How many carriers were not offered to the renderer at all; see {@link #problems}. */
        public int problemCount() {
            return originOnlySkipped + noPose;
        }
    }

    /**
     * One carrier that the origin walk did not offer, as the B1 line describes it.
     *
     * <p>A one-field record rather than the fields themselves: the check is what knows how to word itself,
     * and a caller that held the pieces separately would be free to print a line that differs from the one
     * the check describes - which is the drift this record exists to make impossible.
     *
     * @param check the carrier's facts, with {@code collected=false} and the reason derivable from it
     */
    public record CarrierSection(CarrierHostCheck check) {
    }

    /**
     * "c:1a2b3c4d age=3t" for every carrier the client holds, so a count that is higher than it should
     * be names the entry that stopped being refreshed rather than leaving the reader to guess.
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
     * Whether one host is currently drawn at the block it came from rather than inside a structure.
     *
     * <p>Kept as a query rather than removed with the renderer's old use of it: "is this host being
     * drawn on the world path" is the question a diagnostic asks, and the renderer now answers it from
     * {@link CarrierIndex.Drawable#landed()} instead because it walks both fallback sets in one pass.
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
        // `settledCarrierCount` rather than `carrierCount`: a provisional claim has no pose to send, so
        // it must not be counted on either side of the comparison the trace makes.
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
     * Drops one carrier's fallback entries at these local coordinates.
     *
     * <p>The third place a component that no longer exists can still be drawn: the list of hosts whose
     * carrier's pose went stale, drawn at the block they came from - which for a carried host is the
     * position the player is standing at, so a leftover entry here is precisely "the block is gone but
     * the component is still on screen".
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

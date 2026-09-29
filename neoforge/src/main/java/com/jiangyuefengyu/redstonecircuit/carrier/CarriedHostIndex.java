package com.jiangyuefengyu.redstonecircuit.carrier;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.jiangyuefengyu.redstonecircuit.data.Slot;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import org.jetbrains.annotations.Nullable;

/**
 * The bookkeeping for host blocks that are riding a moving structure, or have just been put down by
 * one.
 *
 * <h2>What it is, in one sentence</h2>
 * A pure, headless index of {@code carrier -> local coordinate -> host}, plus the reverse maps that
 * answer "is the data for this world position riding something?" and "what is inside it?" in one hash
 * lookup each.
 *
 * <h2>Why it is pure</h2>
 * Everything interesting about P1 is arithmetic and bookkeeping: the identity of a carrier, the round
 * trip between local and world coordinates, and the fact that {@code stopMoving} fires four to five
 * times for a single motion. None of that needs a running game, so none of it is written as if it
 * did. The index holds no Minecraft level, no Create type and no Sable type, and it reaches the real
 * world store only through {@link SlotWorld}. That is what lets the whole contract be pinned down by
 * offline unit tests instead of by assembling a contraption and watching what happens.
 *
 * <h2>The three states of one host</h2>
 * <pre>
 *   live      the world store has it, the index knows nothing    - ordinary play
 *   carried   an entry here with landed=false; the world store   - the structure has it
 *             entry has been MOVED out, never deleted
 *   putDown   an entry here with landed=true; the world store    - back home, still remembered
 *             entry is back, and the host answers at its landing position
 * </pre>
 *
 * <p>Note the deliberate asymmetry: pick-up <em>moves</em> the data rather than copying it, so the
 * world position the structure left behind really is empty - which is exactly what makes
 * {@code worldState=air} readable while the structure flies. The snapshot in
 * {@link CarriedHost#slot()} is what makes that safe: the contents are still held, so a release that
 * cannot land can put everything back.
 *
 * <p>A host answers at exactly one world position at a time: the position it was picked up from while
 * it is carried, and the position it was put down at afterwards. That rule lives in
 * {@link #byPosition}, and both the world reads and the client's view depend on it.
 *
 * <h2>Why the carrier is keyed by UUID and not by its anchor</h2>
 * See {@link CarrierId}. The short version: an anchor is a position, and two contraptions can be
 * assembled at the same one.
 *
 * <h2>Performance</h2>
 * The P1 brief is explicit that a level with ~842 store entries must never be walked per tick on
 * behalf of this feature. Every query here is for a <em>specific</em> world position, carrier or local
 * coordinate; the only loops walk the carriers themselves (a handful of structures in flight) and one
 * carrier's own hosts (bounded by how many host blocks that machine carries).
 */
public final class CarriedHostIndex {

    /** How to read and write one component in the real world store; see {@link SlotWorld}. */
    public interface SlotWorld {

        /** What is stored at {@code pos}, or {@code null} when nothing is. */
        @Nullable
        Slot get(BlockPos pos);

        /** Writes {@code slot}, or removes whatever is there when {@code slot} is {@code null}. */
        void set(BlockPos pos, @Nullable Slot slot);
    }

    /** What happened to a host's data when its structure put it down. */
    public enum ReleaseStatus {
        /** The data went back into the world at the position the structure's transform named. */
        LANDED,
        /**
         * The data went back, but not where the structure's transform says it belongs: that position
         * was occupied, so the block's original position was used instead.
         */
        LANDED_AT_ORIGIN,
        /** This host has already been put down; {@code stopMoving} called itself again. */
        ALREADY_RELEASED,
        /** This carrier is not ours at all - nothing was ever claimed under it. */
        UNKNOWN_CARRIER,
        /** The carrier is ours, but this particular local coordinate was never claimed. */
        NOT_CARRIED,
        /** Neither the landing position nor the original one was free; the data stays carried. */
        HELD
    }

    /** The outcome of one release, in the form the diagnostics print. */
    public record ReleaseOutcome(ReleaseStatus status, @Nullable BlockPos origin,
                                 @Nullable BlockPos localPos, @Nullable BlockPos landing,
                                 int moved, int alreadyReleased, int conflicts, int held) {

        /** True when something actually went back into the world on this call. */
        public boolean wrote() {
            return moved > 0;
        }

        /** One line for the log: what happened, where, and whether anything was in the way. */
        public String describe() {
            return status + " origin=" + shortPos(origin) + " local=" + shortPos(localPos)
                    + " landing=" + shortPos(landing)
                    + " moved=" + moved + " alreadyReleased=" + alreadyReleased
                    + " conflicts=" + conflicts + " held=" + held;
        }
    }

    /** The outcome of one pick-up. */
    public record ClaimOutcome(boolean claimed, boolean moved, boolean alreadyCarried,
                               BlockPos origin, BlockPos localPos, @Nullable Slot slot) {

        /** True when this call did the work, as opposed to finding it already done. */
        public boolean performed() {
            return claimed && moved && !alreadyCarried;
        }

        /** One line for the log: the carrier's local coordinate and where the block came from. */
        public String describe() {
            return (claimed ? (alreadyCarried ? "already-carried" : "claimed") : "no-component")
                    + " origin=" + shortPos(origin) + " local=" + shortPos(localPos)
                    + " type=" + (slot == null ? "-" : slot.type);
        }
    }

    /**
     * Where every local coordinate of one carrier lands in the world.
     *
     * <p>A one-method interface rather than a pose: the index does not need to understand rotations,
     * only to ask the structure - or, in a unit test, a lambda - where a coordinate goes.
     */
    @FunctionalInterface
    public interface Landing {

        /** The world position local coordinate {@code localPos} is being put down at. */
        BlockPos landingFor(BlockPos localPos);
    }

    // ------------------------------------------------------------------ state --

    /** carrier -> its own hosts, by local coordinate. The record of everything that happened. */
    private final Map<UUID, Long2ObjectMap<CarriedHost>> byCarrier = new HashMap<>();

    /** The world position each host answers at, while it is carried and after it is put down. */
    private final Long2ObjectMap<CarriedHost> byPosition = new Long2ObjectOpenHashMap<>();

    /** Which of the carriers here are Sable sub-levels rather than Create contraptions. */
    private final Set<UUID> subLevelIds = new HashSet<>();

    // --------------------------------------------------------------- queries --

    /** True while the index is responsible for nothing, the cheap guard every hot path uses first. */
    public boolean isEmpty() {
        return byPosition.isEmpty();
    }

    /** How many carriers hold at least one host, still riding or just put down. */
    public int carrierCount() {
        return byCarrier.size();
    }

    /** How many hosts this index is responsible for, still riding or just put down. */
    public int size() {
        return byPosition.size();
    }

    /**
     * How many hosts one carrier is responsible for, still riding or just put down.
     *
     * <p>The per-machine number, which is what the self-healing sweep's budget and rotation are stated
     * in. One map lookup and a size, so it is free on a hot path; {@link #hostsOf} is the same count
     * with the records copied out.
     */
    public int hostCount(CarrierId carrier) {
        Long2ObjectMap<CarriedHost> hosts = carrier == null ? null : byCarrier.get(carrier.uuid());
        return hosts == null ? 0 : hosts.size();
    }

    /** How many hosts are still riding a structure right now. */
    public int carriedCount() {
        int total = 0;
        for (CarriedHost host : byPosition.values()) {
            if (!host.landed()) {
                total++;
            }
        }
        return total;
    }

    /** Every carrier this index knows about. */
    public List<CarrierId> carriers() {
        List<CarrierId> out = new ArrayList<>(byCarrier.size());
        for (UUID uuid : byCarrier.keySet()) {
            out.add(kindOf(uuid));
        }
        return out;
    }

    /** One host, by carrier and local coordinate, or {@code null}. */
    @Nullable
    public CarriedHost at(CarrierId carrier, BlockPos localPos) {
        Long2ObjectMap<CarriedHost> hosts = byCarrier.get(carrier.uuid());
        return hosts == null ? null : hosts.get(localPos.asLong());
    }

    /**
     * Everything one carrier is responsible for, still riding or just put down.
     *
     * <p>Kept together so the diagnostics can print the whole story of one structure in one place.
     * The client is only ever sent the ones still riding: the ones put down are already covered by the
     * ordinary world sync.
     */
    public List<CarriedHost> hostsOf(CarrierId carrier) {
        Long2ObjectMap<CarriedHost> hosts = byCarrier.get(carrier.uuid());
        if (hosts == null || hosts.isEmpty()) {
            return List.of();
        }
        return List.copyOf(hosts.values());
    }

    /** Only the hosts still riding one carrier; what the renderer actually draws in the structure. */
    public List<CarriedHost> liveHostsOf(CarrierId carrier) {
        return filterLive(hostsOf(carrier));
    }

    /** Only the hosts still riding anything; what the client is told to draw in a structure. */
    public List<CarriedHost> liveHosts() {
        return filterLive(new ArrayList<>(byPosition.values()));
    }

    /** True when {@code worldPos} is one of the positions this index answers for. */
    public boolean isDisplaced(BlockPos worldPos) {
        return byPosition.containsKey(worldPos.asLong());
    }

    /** True when this world position's data is riding a structure at this moment. */
    public boolean isCarried(BlockPos worldPos) {
        CarriedHost host = byPosition.get(worldPos.asLong());
        return host != null && !host.landed();
    }

    /**
     * Replaces the component one host carries, leaving everything else about it alone - or <b>empties
     * it</b> when {@code slot} is {@code null}.
     *
     * <p>This is how the in-structure solve writes its answer back. The host's contents are an
     * immutable record field, so a recomputed power is a new record in the same maps; the origin, the
     * local coordinate and the landed flag are carried over verbatim, which is what makes "solving a
     * machine" incapable of moving or releasing anything.
     *
     * <p><b>The null case is a placement, not a solve.</b> A player who takes the component back out of a
     * physicalised block leaves a host the structure still carries and that holds nothing - the ordinary
     * state of a block a player has just built into a machine. Before this round there was no way to write
     * that state at all: a carried host's slot could be replaced but never taken away, which is one half
     * of "a component put into a carried block is invisible, and taking one out is impossible".
     *
     * <p>The reverse map is re-pointed rather than left alone: the answer at a host's world position
     * comes from the record stored there, so a stale object would keep reporting the old power for as
     * long as the machine is in the air.
     *
     * @return the record that replaced the old one, or {@code null} when this carrier has no host
     *     there
     */
    @Nullable
    public CarriedHost updateSlot(CarrierId carrier, BlockPos localPos, @Nullable Slot slot) {
        Long2ObjectMap<CarriedHost> hosts = byCarrier.get(carrier.uuid());
        if (hosts == null) {
            return null;
        }
        BlockPos local = localPos.immutable();
        CarriedHost existing = hosts.get(local.asLong());
        if (existing == null) {
            return null;
        }
        CarriedHost updated = existing.withSlot(slot);
        hosts.put(local.asLong(), updated);
        bind(updated);
        return updated;
    }

    /**
     * Takes the component out of one host, leaving the host itself carried and empty.
     *
     * <p>Named rather than written as {@code updateSlot(..., null)} at the call site, because "the block
     * is still riding and holds nothing" is a state worth being able to read out of the code, and because
     * the solve's write-back must never be confused with a player emptying a block.
     *
     * @return true when there was a live host there to empty
     */
    public boolean removeSlot(CarrierId carrier, BlockPos localPos) {
        CarriedHost updated = updateSlot(carrier, localPos, null);
        return updated != null;
    }

    /**
     * Forgets one carrier entirely, without writing anything back.
     *
     * <p>The one caller is the Sable reconciliation: a sub-level the container does not know about
     * has already had its blocks and its data dealt with by somebody else - it was unloaded, or it
     * never came back - so keeping a record of it would leave the index answering for a machine that
     * is not there. Unlike {@link #forgetReleased} this drops live entries, so it is only ever called
     * with a structure the container has positively confirmed is gone.
     *
     * @return how many hosts were dropped
     */
    public int forgetCarrier(CarrierId carrier) {
        Long2ObjectMap<CarriedHost> hosts = byCarrier.remove(carrier.uuid());
        subLevelIds.remove(carrier.uuid());
        if (hosts == null || hosts.isEmpty()) {
            return 0;
        }
        for (CarriedHost host : hosts.values()) {
            // ONLY THE RECORD THAT ANSWERS HERE IS REMOVED. A position can be answered by one host, and
            // with a shared UUID it is not guaranteed to be *this* carrier's record any more: the Create
            // half forgetting c:<uuid> must not take away the reverse-map entry the Sable half's
            // s:<uuid> is using to draw the very same component. See heldByAnotherCarrier.
            CarriedHost answering = byPosition.get(host.at().asLong());
            if (answering == null || answering == host
                    || answering.localPos().equals(host.localPos())
                            && answering.origin().equals(host.origin())) {
                byPosition.remove(host.at().asLong());
            }
        }
        // The reverse map is keyed by position, and a position can only be answered by one host, so
        // removing exactly the ones this carrier owned leaves every other entry intact.
        return hosts.size();
    }

    /**
     * <b>Forgets one live host without writing anything back, because its block has been destroyed.</b>
     *
     * <h2>The one caller, and why it must not go through a release</h2>
     * P1.19's second defect: a block a structure is carrying is broken, and the component inside it has
     * to drop as an item - exactly as breaking a host block standing in the world does. Every other exit
     * from this index <em>writes the component back into the world</em> ({@link #release},
     * {@link #releaseAll}); doing that here would put a component into a block that has just been
     * destroyed, and the drop would then be a duplicate. So this is a removal with no write and no
     * landing: the caller drops the item and this record simply stops existing.
     *
     * <h2>Exactly once, by construction</h2>
     * The record is gone after the first call, so a repeated break event - the client can send two for
     * one block, and a sub-level's block change can be reported more than once - finds nothing and
     * returns {@code null}. That, and not a guard at the call site, is what makes "drops exactly once"
     * true.
     *
     * <p>A host that has already been <b>put down</b> is deliberately not removed: it is back in the
     * world, the world's own break path owns it, and its data must stay where the store can see it.
     *
     * @return the record that was removed, or {@code null} when this carrier has no <em>live</em> host
     *     at that local coordinate
     */
    @Nullable
    public CarriedHost discard(CarrierId carrier, BlockPos localPos) {
        if (carrier == null || localPos == null) {
            return null;
        }
        Long2ObjectMap<CarriedHost> hosts = byCarrier.get(carrier.uuid());
        if (hosts == null) {
            return null;
        }
        BlockPos local = localPos.immutable();
        CarriedHost host = hosts.get(local.asLong());
        if (host == null || host.landed()) {
            return null;
        }
        hosts.remove(local.asLong());
        // The reverse map is shared between carriers that share a UUID (a physics-assembled structure
        // is one contraption and one sub-level), so only the record that really answers at this host's
        // position is removed - the same care `forgetCarrier` takes.
        CarriedHost answering = byPosition.get(host.at().asLong());
        if (answering == null || answering == host
                || answering.localPos().equals(host.localPos())
                        && answering.origin().equals(host.origin())) {
            byPosition.remove(host.at().asLong());
        }
        if (hosts.isEmpty()) {
            byCarrier.remove(carrier.uuid());
        }
        return host;
    }

    /**
     * The carrier this index actually files under one structure's <b>UUID</b>, or {@code null}.
     *
     * <h2>Why this is a UUID lookup and not a kind lookup</h2>
     * The maps inside are keyed by UUID alone, and a physics-assembled structure has <b>one UUID shared
     * by a Create contraption entity and a Sable sub-level</b> - so the same key can be filed as either
     * kind, and only the index knows which. A caller that reaches this with a UUID from another mod
     * (Create's interaction packet hands over the contraption entity's) has no way to know, and guessing
     * {@code CarrierId.contraption(uuid)} would be wrong exactly half the time.
     *
     * <p>Walks the carriers, so it is a handful of comparisons - and it is only ever called on a player's
     * click.
     */
    @Nullable
    public CarrierId carrierForUuid(@Nullable UUID uuid) {
        if (uuid == null) {
            return null;
        }
        return byCarrier.containsKey(uuid) ? kindOf(uuid) : null;
    }

    /**
     * Which carrier the host answering at {@code worldPos} belongs to, or {@code null}.
     *
     * <p>Asked by the diagnostics and by nothing on a hot path, so it scans the carriers rather than
     * keeping a third map in step with the other two. There are a handful of carriers.
     */
    @Nullable
    public CarrierId carrierHolding(BlockPos worldPos) {        CarriedHost host = byPosition.get(worldPos.asLong());
        if (host == null) {
            return null;
        }
        for (Map.Entry<UUID, Long2ObjectMap<CarriedHost>> entry : byCarrier.entrySet()) {
            CarriedHost found = entry.getValue().get(host.localPos().asLong());
            if (found != null && found.origin().equals(host.origin())) {
                return kindOf(entry.getKey());
            }
        }
        return null;
    }

    /** Every world position at which some host currently answers. */
    public List<BlockPos> displacedWorldPositions() {
        List<BlockPos> out = new ArrayList<>(byPosition.size());
        for (long key : byPosition.keySet()) {
            out.add(BlockPos.of(key));
        }
        return out;
    }

    /**
     * One carrier's state as a single log line: how many hosts it has, where it is, and the local
     * coordinates themselves.
     *
     * <p>Written for the P1 acceptance procedure, which asks for "the number of hosts in the carrier
     * and its pose" to be readable at a glance. The local coordinates are included because they are
     * what an operator compares against what the structure looks like in game.
     */
    public String summary(CarrierId carrier) {
        List<CarriedHost> hosts = hostsOf(carrier);
        if (hosts.isEmpty()) {
            return "hosts=0";
        }
        StringBuilder sb = new StringBuilder("hosts=").append(hosts.size());
        int live = 0;
        for (CarriedHost host : hosts) {
            if (!host.landed()) {
                live++;
            }
        }
        sb.append(" riding=").append(live).append(" local=[");
        int limit = Math.min(hosts.size(), 12);
        for (int i = 0; i < limit; i++) {
            CarriedHost host = hosts.get(i);
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(host.localPos().toShortString());
            if (host.landed()) {
                sb.append("->").append(host.at().toShortString());
            }
        }
        if (hosts.size() > limit) {
            sb.append(" ...+").append(hosts.size() - limit);
        }
        return sb.append(']').toString();
    }

    /**
     * What is inside the host that answers at {@code worldPos}, <b>including</b> data that is riding a
     * structure.
     *
     * <p>This is the read the rest of the mod uses, and it is deliberately the union of the two
     * stores: "what is inside this block" must not depend on whether the block happens to be at home
     * or halfway across the world. Every caller that does care asks {@link #isCarried(BlockPos)} as
     * well - that is exactly what the frozen tick handler does.
     */
    @Nullable
    public Slot slotAt(BlockPos worldPos, SlotWorld world) {
        Slot live = world.get(worldPos);
        if (live != null) {
            return live;
        }
        CarriedHost host = byPosition.get(worldPos.asLong());
        return host == null ? null : host.slot();
    }

    // -------------------------------------------------------------- claiming --

    /**
     * Takes the host at {@code worldPos} into {@code carrier} at local coordinate {@code localPos}.
     *
     * <p>Idempotent, because Create's callbacks are: the same block can be reported twice for one
     * assembly, and a second claim must not overwrite the snapshot with the now-empty world entry. The
     * {@code alreadyCarried} answer is what lets the caller say "somebody already did this" instead of
     * logging a second move.
     *
     * @param world where the contents live; the entry is removed from it on a real claim
     */
    public ClaimOutcome claim(CarrierId carrier, BlockPos localPos, BlockPos worldPos, SlotWorld world) {
        return fill(carrier, localPos, worldPos, worldPos, world);
    }

    /**
     * <b>Takes the world entry at {@code storedAt} into {@code carrier} at local coordinate {@code
     * localPos}, where the two positions are deliberately allowed to differ.</b>
     *
     * <h2>Why this exists beside {@link #claim}</h2>
     * {@code claim} takes one position twice, which is the shape every ordinary pick-up has: the block a
     * component is standing in is the block the structure is taking, so "where the contents live" and
     * "where the component came from" are the same number and the signature says so.
     *
     * <p>The adoption of a component that is already inside a structure's own storage is the one case
     * where they are <b>different facts</b>, and it is not a corner: a Sable sub-level's blocks are the
     * level's blocks at their own plot coordinates (see {@code CarrierStore.CarrierSource#plotOwnerAt}), so
     * a component can be sitting in the world's store keyed by a plot coordinate - simultaneously "a
     * position in the level" and "a coordinate inside a structure". Reading the contents from the plot
     * coordinate and recording them with an origin the structure's pose names are two different questions
     * about two different numbers, and a caller that had to pass one position for both would either file
     * the record under the wrong origin or read the world's store at the wrong key.
     *
     * <p>The three answers are {@code claim}'s, unchanged, and so is the idempotence: an existing live
     * record at {@code localPos} is returned as {@code alreadyCarried} with its own snapshot, and the world
     * entry is removed only on a real move.
     *
     * @param localPos the coordinate inside the structure - the key the record is filed under
     * @param origin where the component is recorded as having come from; the caller's own convention, and
     *     never read from the world store
     * @param storedAt where the component's contents actually are in the world's own store
     */
    public ClaimOutcome fill(CarrierId carrier, BlockPos localPos, BlockPos origin, BlockPos storedAt,
                             SlotWorld world) {
        return file(carrier, localPos, origin, storedAt, world);
    }

    /**
     * The body {@link #fill} has always had, kept as its own method so the three answers it produces are
     * visibly untouched: already-carried with the first snapshot, nothing-to-carry, and the real move.
     */
    private ClaimOutcome file(CarrierId carrier, BlockPos localPos, BlockPos origin, BlockPos storedAt,
                              SlotWorld world) {
        BlockPos local = localPos.immutable();
        BlockPos from = storedAt.immutable();
        BlockPos recorded = origin.immutable();
        markKind(carrier);

        CarriedHost existing = at(carrier, local);
        if (existing != null && !existing.landed()) {
            // Already riding this same carrier at this same local coordinate: the snapshot taken by
            // the first call is the good one, and the world entry is already gone.
            return new ClaimOutcome(true, false, true, existing.origin(), local, existing.slot());
        }

        Slot live = world.get(from);
        if (live == null) {
            // Nothing to carry. This is the normal answer for the vast majority of blocks in a
            // contraption - the hook is per block *type*, so every stone in the machine reports in -
            // and it must stay cheap: one hash lookup and no allocation.
            return new ClaimOutcome(false, false, false, recorded, local, null);
        }

        Slot snapshot = live.copy();
        world.set(from, null);
        CarriedHost captured = CarriedHost.captured(recorded, local, snapshot);
        byCarrier.computeIfAbsent(carrier.uuid(), key -> new Long2ObjectOpenHashMap<>())
                .put(local.asLong(), captured);
        bind(captured);
        return new ClaimOutcome(true, true, false, recorded, local, snapshot);
    }

    /**
     * <b>Files the carrier host a structure's own block was missing, with no component in it yet.</b>
     *
     * <h2>Why this exists beside {@link #fill}</h2>
     * {@code fill} (and through it {@code claim}) refuses when the world store holds nothing at
     * {@code storedAt}, because for an ordinary pick-up "there is nothing to carry" is the whole answer.
     * The case this method serves is the opposite one: the coordinate is a block inside a living structure -
     * a Sable sub-level's block <em>is</em> a level block at its own local coordinate - and the index
     * simply has never heard of it, because a record is only ever created by taking a component out of a
     * <em>world</em> position. A block that was already inside the structure when it was assembled is
     * therefore invisible to the carrier layer, and it is exactly the block a player is trying to put a
     * component into.
     *
     * <p>So this files an <b>empty</b> host ({@code slot == null}, the state
     * {@link #removeSlot} also produces and every reader already treats as "no component here"), and if the
     * world store does hold an entry at {@code storedAt} it is <b>migrated instead</b>: the snapshot is
     * taken and the world entry removed, which is the one-source-of-truth rule the adoption path follows.
     * A component the world store still holds at a plot coordinate is the defect that produced "Jade shows
     * it, the overlay draws nothing, nothing outside can connect to it", so it is carried over rather than
     * shadowed.
     *
     * <h2>Idempotent, and the same answers as {@code fill} where they overlap</h2>
     * A live record at {@code localPos} is returned as it is - the first registration's snapshot is the good
     * one, so a second delivery of one click cannot empty a host the player has already built into. A
     * <b>landed</b> record is replaced, exactly as {@code fill} replaces it: the block has been put back
     * into the world and its component landed there, so a fresh registration is the honest state. The
     * landed case is answered by the return value, whose {@code landed()} the caller must check.
     *
     * @param localPos the coordinate inside the structure - the key the record is filed under
     * @param origin the world position to record as "where this came from"; see
     *     {@code CarrierStore#resolveForPlacement} for the rule
     * @param storedAt where a component's contents might be - read for a migration, and only removed when
     *     there really was one
     * @return the record now filed under {@code (carrier, localPos)}, or {@code null} for a null argument
     */
    @Nullable
    public CarriedHost registerAt(CarrierId carrier, BlockPos localPos, BlockPos origin, BlockPos storedAt,
                                  SlotWorld world) {
        if (carrier == null || localPos == null || origin == null || storedAt == null || world == null) {
            return null;
        }
        BlockPos local = localPos.immutable();
        CarriedHost existing = at(carrier, local);
        if (existing != null && !existing.landed()) {
            return existing;
        }
        BlockPos recorded = origin.immutable();
        Slot live = world.get(storedAt.immutable());
        Slot snapshot = live == null ? null : live.copy();
        if (snapshot != null) {
            world.set(storedAt.immutable(), null);
        }
        markKind(carrier);
        CarriedHost filed = new CarriedHost(recorded, local, snapshot, recorded, false, false);
        byCarrier.computeIfAbsent(carrier.uuid(), key -> new Long2ObjectOpenHashMap<>())
                .put(local.asLong(), filed);
        bind(filed);
        return filed;
    }

    // ------------------------------------------------------------ re-labelling --

    /**
     * Moves everything {@code from} is holding onto {@code to}, and forgets {@code from}.
     *
     * <p><b>Why this exists.</b> A Create {@code MovementBehaviour} can be handed a
     * {@code MovementContext} whose contraption has no entity yet, so the carrier's real identity - the
     * entity's UUID - is not knowable at claim time. The claim is made under a provisional UUID instead
     * (see {@link CarrierIdentity}) and re-labelled here the moment the entity appears, so a host is
     * never dropped for want of a name and never carried under two names at once.
     *
     * <h3>What it deliberately keeps</h3>
     * The records themselves are moved verbatim: origin, local coordinate, contents, the landed flag
     * and the landing position. A re-key is a rename, not a move - a host that is already riding stays
     * riding, and a host that has already been put down keeps answering where it was put down, so a
     * repeated {@code stopMoving} arriving under the new name still answers {@code ALREADY_RELEASED}
     * rather than writing the component back a second time.
     *
     * <h3>Why the loop rebuilds the reverse map</h3>
     * The reverse map is keyed by world position and a position is answered by exactly one host, so
     * every entry this carrier owns is re-pointed at the same record that is going into the target map.
     * A rebind through {@link #bind} would leave a stale record answering at the same position.
     *
     * @return how many host records were moved
     */
    public int rekey(CarrierId from, CarrierId to) {
        if (from == null || to == null || from.uuid().equals(to.uuid())) {
            return 0;
        }
        Long2ObjectMap<CarriedHost> source = byCarrier.remove(from.uuid());
        boolean wasSubLevel = subLevelIds.remove(from.uuid());
        if (source == null || source.isEmpty()) {
            // Nothing was ever claimed under the old identity. Still record the kind of the target, so
            // a carrier that only ever appears after the re-key is classified correctly.
            if (wasSubLevel || to.isSubLevel()) {
                markKind(to);
            }
            return 0;
        }
        // The re-key is a rename, and the kind travels with the structure rather than with the name: a
        // sub-level that was filed under a provisional id and then re-labelled onto its own UUID is
        // still a sub-level. `from` and `to` share a UUID in the physics-assembly case, and there the
        // removal above already did nothing (the same key was re-added), so this only matters for the
        // ordinary provisional-to-entity re-key - where it makes no difference either way, because a
        // provisional id is always a contraption's. Kept explicit so that stays true if that changes.
        if (wasSubLevel && !to.isSubLevel()) {
            subLevelIds.add(to.uuid());
        }
        markKind(to);

        Long2ObjectMap<CarriedHost> target =
                byCarrier.computeIfAbsent(to.uuid(), key -> new Long2ObjectOpenHashMap<>());
        int moved = 0;
        for (CarriedHost host : source.values()) {
            long local = host.localPos().asLong();
            CarriedHost incumbent = target.get(local);
            if (incumbent != null && !incumbent.landed()) {
                if (host.landed()) {
                    // The target already holds a live claim at this local coordinate - the same
                    // contraption object reused for a new machine that claimed while the old entry was
                    // still in the index. The live claim is the newer truth, so the stale put-down
                    // record is the one dropped.
                    byPosition.remove(host.at().asLong());
                    continue;
                }
                // Two live claims at one coordinate. Impossible for one carrier; if it happens, the one
                // already under the real name is the one the rest of the mod has been reading, so it
                // stays and the move is reported by the count rather than silently overwriting it.
                continue;
            }
            target.put(local, host);
            byPosition.put(host.at().asLong(), host);
            moved++;
        }
        if (target.isEmpty()) {
            byCarrier.remove(to.uuid());
        }
        return moved;
    }

    // -------------------------------------------------------------- releasing --

    /**
     * Puts every host of {@code carrier} back into the world.
     *
     * <p>Used when a whole structure goes away - a disassembly, a removed sub-level - and by the
     * orphan sweep. Both halves of the P1 promise are here: idempotence (a repeat call answers
     * {@link ReleaseStatus#ALREADY_RELEASED} and touches nothing) and conflict handling (a landing
     * position somebody else has taken is never written over).
     *
     * @param landing where the structure's own transform says each host belongs, or {@code null} to
     *     use the positions the hosts came from
     */
    public ReleaseOutcome releaseAll(CarrierId carrier, @Nullable Landing landing, SlotWorld world) {
        Long2ObjectMap<CarriedHost> hosts = byCarrier.get(carrier.uuid());
        if (hosts == null) {
            return new ReleaseOutcome(ReleaseStatus.UNKNOWN_CARRIER, null, null, null, 0, 0, 0, 0);
        }

        List<CarriedHost> live = filterLive(List.copyOf(hosts.values()));
        if (live.isEmpty()) {
            return new ReleaseOutcome(ReleaseStatus.ALREADY_RELEASED, null, null, null, 0, 0, 0, 0);
        }

        int moved = 0;
        int conflicts = 0;
        int held = 0;
        CarriedHost example = live.get(0);
        BlockPos exampleLanding = landing == null ? example.origin()
                : landing.landingFor(example.localPos());
        for (CarriedHost host : live) {
            if (heldByAnotherCarrier(host)) {
                // SOMEBODY ELSE IS STILL CARRYING THIS HOST, SO THIS RELEASE IS NOT THE OWNER'S TO MAKE.
                //
                // The author's Sable log is the case: the Create half claimed a physics-assembled
                // structure under c:<uuid>, the Sable half claimed the same host under s:<uuid>, and the
                // two share one UUID - so the Create half's orphan sweep released the host *for* the
                // Sable carrier, wrote the component back into the world, and marked the record landed,
                // twice a second, while the sub-level was still flying. The client was told the host had
                // been put down and drew it on the world path from then on: the "the component stays
                // behind" the author reported.
                //
                // Nothing is written, nothing is re-pointed and the record stays live, so the carrier
                // that really owns it keeps drawing it. Counted as a conflict rather than silently
                // skipped, because a release that could not be made is a fact the log has to carry.
                conflicts++;
                continue;
            }
            BlockPos preferred = landing == null ? host.origin() : landing.landingFor(host.localPos());
            BlockPos written = writeBack(host, preferred, world);
            if (written == null) {
                held++;
                continue;
            }
            moved++;
            boolean atOrigin = !written.equals(preferred);
            if (atOrigin) {
                conflicts++;
            }
            put(hosts, host, written, atOrigin);
        }

        ReleaseStatus status = moved == 0
                ? (held > 0 ? ReleaseStatus.HELD : ReleaseStatus.ALREADY_RELEASED)
                : conflicts > 0 ? ReleaseStatus.LANDED_AT_ORIGIN : ReleaseStatus.LANDED;
        return new ReleaseOutcome(status, example.origin(), example.localPos(), exampleLanding,
                moved, live.size() - moved - held - conflicts, conflicts, held);
    }

    /**
     * Whether some <b>other</b> carrier is holding the host this record describes, right now.
     *
     * <p>Two carriers can hold one host in exactly one situation, and it is not a bookkeeping slip: a
     * Create contraption and a Sable sub-level that <b>share a UUID</b> - which is what a
     * physics-assembled structure looks like, because its contraption entity and its sub-level are
     * created together. Both halves then have a legitimate claim to file, and
     * {@link CarrierKind} is the only thing that tells them apart.
     *
     * <p>The test is deliberately "is the record answering at this host's position somebody else's
     * live one", not "does another of my maps mention the same local coordinate": the reverse map
     * ({@link #byPosition}) is the one index of where a host currently <em>is</em>, and it is the map
     * every other read in this class goes through.
     */
    private boolean heldByAnotherCarrier(CarriedHost host) {
        CarriedHost answering = byPosition.get(host.at().asLong());
        if (answering == null || answering.landed() || answering == host) {
            return false;
        }
        // A different record is answering for this host's position. It belongs to another carrier if
        // its local coordinate or its origin differs - the two fields a record carries that name it.
        return !answering.localPos().equals(host.localPos())
                || !answering.origin().equals(host.origin());
    }

    /**
     * Releases one local coordinate, leaving the carrier's other hosts alone.
     *
     * <p>This is the shape the {@code stopMoving} hook actually wants, because that callback is per
     * block. {@link #releaseAll} is the wholesale form used when a structure goes away entirely.
     */
    public ReleaseOutcome release(CarrierId carrier, BlockPos localPos, @Nullable Landing landing,
                                  SlotWorld world) {
        BlockPos local = localPos.immutable();
        markKind(carrier);
        Long2ObjectMap<CarriedHost> hosts = byCarrier.get(carrier.uuid());
        if (hosts == null) {
            return new ReleaseOutcome(ReleaseStatus.UNKNOWN_CARRIER, null, local, null, 0, 0, 0, 0);
        }
        CarriedHost host = hosts.get(local.asLong());
        if (host == null) {
            return new ReleaseOutcome(ReleaseStatus.NOT_CARRIED, null, local, null, 0, 0, 0, 0);
        }
        if (host.landed()) {
            // The second, third, fourth and fifth stopMoving calls for one motion land here. The
            // landing position is deliberately not recomputed: a repeat is identified by the record,
            // not by the arithmetic, so a structure that drifted a fraction between two callbacks
            // cannot turn its own repeat into a second write.
            return new ReleaseOutcome(ReleaseStatus.ALREADY_RELEASED, host.origin(), local,
                    host.at(), 0, 1, 0, 0);
        }

        BlockPos preferred = landing == null ? host.origin() : landing.landingFor(local);
        BlockPos written = writeBack(host, preferred, world);
        if (written == null) {
            // Nowhere safe to put it: the data stays carried and the entry stays live, so a later
            // release can still complete. Losing the component would be much worse than a delay.
            return new ReleaseOutcome(ReleaseStatus.HELD, host.origin(), local, preferred, 0, 0, 1, 1);
        }
        boolean atOrigin = !written.equals(preferred);
        put(hosts, host, written, atOrigin);
        return new ReleaseOutcome(atOrigin ? ReleaseStatus.LANDED_AT_ORIGIN : ReleaseStatus.LANDED,
                host.origin(), local, preferred, 1, 0, atOrigin ? 1 : 0, 0);
    }

    /**
     * Writes one host back, choosing between the preferred landing position and the one it came from.
     *
     * <p><b>An empty host writes nothing.</b> A block a structure carries can now be emptied - a player
     * takes the component out of a physicalised block - and putting that host down must not put anything
     * into the world: the host's contents are the empty state, so the landing position simply stays
     * empty. It still counts as <em>written</em>, because the record really did land there and the
     * position is the one this host answers at from now on; the alternative, refusing the landing,
     * would leave an empty host riding a machine that has stopped for ever.
     *
     * @return the position actually written, or {@code null} when neither was usable
     */
    @Nullable
    private BlockPos writeBack(CarriedHost host, BlockPos preferred, SlotWorld world) {
        BlockPos target = preferred.immutable();
        if (taken(target, world) && !target.equals(host.origin())) {
            // The structure's transform says this is where it belongs, but somebody else is there.
            // Nothing is overwritten: the component goes back to the block it was picked up from.
            target = host.origin();
        }
        if (taken(target, world)) {
            // Even the original position is occupied - a player rebuilt there while the machine was
            // away. The data stays carried rather than being dropped.
            return null;
        }
        Slot slot = host.slot();
        if (slot != null) {
            world.set(target, slot.copy());
        }
        return target;
    }

    /**
     * Whether {@code target} already holds somebody else's component.
     *
     * <p>A position this index itself put a host down at is not "taken": the contents there are this
     * very component, written back by an earlier release or by an earlier callback of the same one.
     * Anything else is somebody's and must not be overwritten.
     */
    private boolean taken(BlockPos target, SlotWorld world) {
        if (world.get(target) == null) {
            return false;
        }
        CarriedHost mine = byPosition.get(target.asLong());
        return mine == null || !mine.landed();
    }

    /** Records that {@code host} was put down at {@code landing}, and moves the reverse map with it. */
    private void put(Long2ObjectMap<CarriedHost> hosts, CarriedHost host, BlockPos landing,
                     boolean atOrigin) {
        CarriedHost record = atOrigin ? host.releasedToOrigin() : host.releasedTo(landing);
        hosts.put(host.localPos().asLong(), record);
        byPosition.remove(host.at().asLong());
        bind(record);
    }

    /** Points the reverse map at the one position this host now answers at. */
    private void bind(CarriedHost host) {
        byPosition.put(host.at().asLong(), host);
    }

    // ------------------------------------------------------------- forgetting --

    /** Drops everything; used when a level unloads. */
    public void clear() {
        byCarrier.clear();
        byPosition.clear();
        subLevelIds.clear();
    }

    /**
     * Forgets carriers that have put everything down, so the index stays bounded.
     *
     * <p>Never drops live data: a carrier with even one host still riding it is left alone, because
     * that host is the only copy of a component. The records of hosts that were put down are dropped
     * here and nowhere else - while they are kept, a repeated callback and a client that has not been
     * re-snapshotted yet both keep reading the right answer.
     *
     * <p><b>This is a removal from the index, so it is a removal the client has to hear about.</b> It
     * is the quietest of them - the carrier's hosts are all already home, so nothing is lost - and that
     * is exactly why it used to be the one that was forgotten: the client was left holding a carrier
     * the server could no longer answer a pose for. The answer is now returned rather than swallowed,
     * so the caller can announce every one of them from a single place.
     *
     * @return the carriers that were dropped, or an empty list when there was nothing to drop
     */
    public List<CarrierId> forgetReleased() {
        List<UUID> finished = new ArrayList<>();
        for (Map.Entry<UUID, Long2ObjectMap<CarriedHost>> entry : byCarrier.entrySet()) {
            if (filterLive(List.copyOf(entry.getValue().values())).isEmpty()) {
                finished.add(entry.getKey());
            }
        }
        if (finished.isEmpty()) {
            return List.of();
        }
        List<CarrierId> forgotten = new ArrayList<>(finished.size());
        for (UUID uuid : finished) {
            forgotten.add(kindOf(uuid));
        }
        Set<UUID> gone = new HashSet<>(finished);
        byCarrier.keySet().removeAll(gone);
        subLevelIds.removeAll(gone);
        // The reverse map is rebuilt from what is left rather than filtered, because "which carrier
        // answers at this position" has to stay true for every entry that survives.
        byPosition.clear();
        for (Map.Entry<UUID, Long2ObjectMap<CarriedHost>> entry : byCarrier.entrySet()) {
            for (CarriedHost host : entry.getValue().values()) {
                byPosition.put(host.at().asLong(), host);
            }
        }
        return List.copyOf(forgotten);
    }

    /** How many carriers are known but hold nothing live; for the diagnostics. */
    public int releasedCarrierCount() {
        int count = 0;
        for (Long2ObjectMap<CarriedHost> hosts : byCarrier.values()) {
            if (filterLive(List.copyOf(hosts.values())).isEmpty()) {
                count++;
            }
        }
        return count;
    }

    // --------------------------------------------------------------- helpers --

    private static List<CarriedHost> filterLive(List<CarriedHost> hosts) {
        List<CarriedHost> out = new ArrayList<>(hosts.size());
        for (CarriedHost host : hosts) {
            if (!host.landed()) {
                out.add(host);
            }
        }
        return out;
    }

    /**
     * Records that one of the carriers here is a Sable sub-level rather than a Create contraption.
     *
     * <h2>Monotone on purpose, and that is the fix for the author's ping-pong</h2>
     * The maps are keyed by UUID, and a physics-assembled structure has <b>one UUID shared by a Create
     * contraption entity and a Sable sub-level</b>. Both halves therefore reach this method for the same
     * key, and the last caller used to win: the Sable half would file the structure correctly, the Create
     * half would then claim the same block and re-file the very same key as a {@code CONTRAPTION}, and
     * {@code /rc carrier} - and, far worse, the Create half's orphan sweep - would spend the rest of the
     * flight asking the {@code c:} question about an {@code s:} structure. The sweep was told there was no
     * such contraption, declared the still-flying sub-level gone, and wrote its components back into the
     * world twice a second.
     *
     * <p>So the flag only ever goes <b>up</b>. "This UUID names a sub-level" is never unlearned while the
     * carrier is in the index, because it is a fact about the structure rather than about whoever claimed
     * it last. It is dropped only when the carrier itself is forgotten ({@link #forgetCarrier}), which is
     * the one moment there is nothing left to be wrong about.
     */
    private void markKind(CarrierId carrier) {
        if (carrier.isSubLevel()) {
            subLevelIds.add(carrier.uuid());
        }
    }

    /**
     * The full id of a carrier from its UUID.
     *
     * <p>The kind is stored once per carrier rather than looked up per call: the maps are keyed by
     * UUID, so this is the one place the two halves are put back together.
     */
    private CarrierId kindOf(UUID uuid) {
        return new CarrierId(subLevelIds.contains(uuid) ? CarrierKind.SUB_LEVEL
                : CarrierKind.CONTRAPTION, uuid);
    }

    private static String shortPos(@Nullable BlockPos pos) {
        return pos == null ? "-" : pos.toShortString();
    }

    // ------------------------------------------------------------------ NBT --

    /**
     * Writes every record into {@code tag}.
     *
     * <p>Takes the tag rather than returning one so the version number stays in exactly one place -
     * {@link CarriedHosts}, the class that owns the file - instead of being written on both sides of
     * the call and being able to disagree.
     */
    public void saveInto(CompoundTag tag) {
        ListTag list = new ListTag();
        for (Map.Entry<UUID, Long2ObjectMap<CarriedHost>> entry : byCarrier.entrySet()) {
            if (entry.getValue().isEmpty()) {
                continue;
            }
            CompoundTag carrierTag = new CompoundTag();
            carrierTag.putUUID("uuid", entry.getKey());
            ListTag hosts = new ListTag();
            for (CarriedHost host : entry.getValue().values()) {
                // An empty host saves nothing (see CarriedHost#save): its only payload is the component,
                // and there is none. `load` answers null for such a tag, so writing one would be a record
                // that cannot be read back - and the null return has to be checked here rather than
                // pushed into the list.
                CompoundTag saved = host.save();
                if (saved != null) {
                    hosts.add(saved);
                }
            }
            carrierTag.put("hosts", hosts);
            list.add(carrierTag);
        }
        tag.put("carriers", list);

        ListTag subLevels = new ListTag();
        for (UUID uuid : subLevelIds) {
            CompoundTag idTag = new CompoundTag();
            idTag.putUUID("uuid", uuid);
            subLevels.add(idTag);
        }
        tag.put("subLevels", subLevels);
    }

    public static CarriedHostIndex load(@Nullable CompoundTag tag) {
        CarriedHostIndex index = new CarriedHostIndex();
        if (tag == null) {
            return index;
        }
        ListTag list = tag.getList("carriers", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag carrierTag = list.getCompound(i);
            if (!carrierTag.hasUUID("uuid")) {
                continue;
            }
            UUID uuid = carrierTag.getUUID("uuid");
            Long2ObjectMap<CarriedHost> hosts = new Long2ObjectOpenHashMap<>();
            ListTag hostList = carrierTag.getList("hosts", Tag.TAG_COMPOUND);
            for (int j = 0; j < hostList.size(); j++) {
                CarriedHost host = CarriedHost.load(hostList.getCompound(j));
                if (host != null) {
                    hosts.put(host.localPos().asLong(), host);
                }
            }
            if (!hosts.isEmpty()) {
                index.byCarrier.put(uuid, hosts);
            }
        }

        ListTag subLevels = tag.getList("subLevels", Tag.TAG_COMPOUND);
        for (int i = 0; i < subLevels.size(); i++) {
            CompoundTag idTag = subLevels.getCompound(i);
            if (idTag.hasUUID("uuid")) {
                index.subLevelIds.add(idTag.getUUID("uuid"));
            }
        }

        // Rebuild the reverse map from the records, so a save taken in mid-flight is still fully
        // answerable: "is this world position carried?" must not depend on the reverse map having been
        // built up in this process.
        for (Map.Entry<UUID, Long2ObjectMap<CarriedHost>> entry : index.byCarrier.entrySet()) {
            for (CarriedHost host : entry.getValue().values()) {
                index.bind(host);
            }
        }
        return index;
    }
}

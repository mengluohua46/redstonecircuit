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
 * The bookkeeping for host blocks riding a moving structure, or just put down by one: an index of
 * {@code carrier -> local coordinate -> host} plus the reverse lookup "is this world position's data riding
 * something, and what is inside it?".
 * <p>A pick-up moves the data rather than copying it, so the world position really is empty; the snapshot in
 * {@link CarriedHost#slot()} keeps the contents. A host answers at exactly one world position at a time (rule in
 * {@link #byPosition}); carriers are keyed by UUID, since an anchor is a position two contraptions can share.
 */
public final class CarriedHostIndex {

    /** How to read and write one component in the real world store. */
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
        /** The data went back, but not where the transform says it belongs: that position was occupied,
         * so the block's original position was used instead. */
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
     * Where every local coordinate of one carrier lands in the world: a one-method interface rather than a pose,
     * because the index never needs rotations - it only asks - and a unit test can pass a lambda.
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
     * How many hosts one carrier is responsible for, still riding or just put down: the per-machine count the
     * self-healing sweep's budget and rotation are stated in.
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
     * Everything one carrier is responsible for, still riding or just put down; only the ones still riding are ever
     * sent to the client, the ones put down being covered by the ordinary world sync.
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
     * Replaces the component one host carries, or empties it for a {@code null} slot, keeping the origin, local
     * coordinate and landed flag verbatim; the reverse map is re-pointed rather than left alone, because the answer
     * at a host's world position comes from the record stored there.
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
     * Takes the component out of one host, leaving the host itself carried and empty - a state worth naming, so a
     * player emptying a block is never confused with the solve's write-back.
     */
    public boolean removeSlot(CarrierId carrier, BlockPos localPos) {
        CarriedHost updated = updateSlot(carrier, localPos, null);
        return updated != null;
    }

    /**
     * Forgets one carrier entirely, without writing anything back. The Sable reconciliation calls it for a sub-level
     * the container does not know about, whose blocks and data somebody else has already dealt with; unlike
     * {@link #forgetReleased} it drops live entries, so it is only called for a structure confirmed to be gone.
     */
    public int forgetCarrier(CarrierId carrier) {
        Long2ObjectMap<CarriedHost> hosts = byCarrier.remove(carrier.uuid());
        subLevelIds.remove(carrier.uuid());
        if (hosts == null || hosts.isEmpty()) {
            return 0;
        }
        for (CarriedHost host : hosts.values()) {
            // Only the record answering here is removed: with a shared UUID it may belong to another carrier.
            CarriedHost answering = byPosition.get(host.at().asLong());
            if (answering == null || answering == host
                    || answering.localPos().equals(host.localPos())
                            && answering.origin().equals(host.origin())) {
                byPosition.remove(host.at().asLong());
            }
        }
        // A position is answered by exactly one host, so removing this carrier's entries leaves the rest intact.
        return hosts.size();
    }

    /**
     * Forgets one live host without writing anything back, because its block has been destroyed: every other exit
     * writes the component back into the world, which here would duplicate the item the caller drops. A host already
     * put down is left alone - the world's own break path owns it.
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
        // Shared UUIDs share the reverse map: remove only the record that really answers here.
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
     * The carrier this index files under one structure's <b>UUID</b>, or {@code null}: a physics-assembled structure
     * shares one UUID between a Create contraption and a Sable sub-level, so only the index knows which kind it is.
     */
    @Nullable
    public CarrierId carrierForUuid(@Nullable UUID uuid) {
        if (uuid == null) {
            return null;
        }
        return byCarrier.containsKey(uuid) ? kindOf(uuid) : null;
    }

    /**
     * Which carrier the host answering at {@code worldPos} belongs to, or {@code null}; asked only by the
     * diagnostics, so it scans the handful of carriers rather than keeping a third map in step.
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
     * One carrier's state as a single log line: how many hosts it has, where it is, and up to twelve local
     * coordinates - what an operator compares against what the structure looks like in game.
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
     * What is inside the host that answers at {@code worldPos}, <b>including</b> data that is riding a structure: the
     * union of the two stores, so "what is inside this block" cannot depend on whether the block is at home or halfway
     * across the world. A caller that does care asks {@link #isCarried(BlockPos)} as well.
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
     * Takes the host at {@code worldPos} into {@code carrier} at local coordinate {@code localPos}. Idempotent,
     * because Create's callbacks are: the same block can be reported twice for one assembly, and a second claim must
     * not overwrite the snapshot with the now-empty world entry.
     */
    public ClaimOutcome claim(CarrierId carrier, BlockPos localPos, BlockPos worldPos, SlotWorld world) {
        return fill(carrier, localPos, worldPos, worldPos, world);
    }

    /**
     * Like {@link #claim}, but with the contents read at {@code storedAt} and the origin recorded separately: a Sable
     * sub-level's blocks are the level's blocks at their own plot coordinates, so the two are different numbers.
     */
    public ClaimOutcome fill(CarrierId carrier, BlockPos localPos, BlockPos origin, BlockPos storedAt,
                             SlotWorld world) {
        return file(carrier, localPos, origin, storedAt, world);
    }

    /** The body behind {@link #fill}: already-carried with the first snapshot, nothing-to-carry, or the real move. */
    private ClaimOutcome file(CarrierId carrier, BlockPos localPos, BlockPos origin, BlockPos storedAt,
                              SlotWorld world) {
        BlockPos local = localPos.immutable();
        BlockPos from = storedAt.immutable();
        BlockPos recorded = origin.immutable();
        markKind(carrier);

        CarriedHost existing = at(carrier, local);
        if (existing != null && !existing.landed()) {
            // Already riding this carrier at this local coordinate: the first call's snapshot is the good one,
            // and the world entry is already gone.
            return new ClaimOutcome(true, false, true, existing.origin(), local, existing.slot());
        }

        Slot live = world.get(from);
        if (live == null) {
            // Nothing to carry, which is the normal answer: the hook is per block *type*, so every stone in
            // the machine reports in. Must stay cheap - one hash lookup and no allocation.
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
     * Files an <b>empty</b> host for a structure's own block that this layer never heard of - exactly the block a player
     * puts a component into; a world entry at {@code storedAt} is <b>migrated</b> instead of shadowed.
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
     * Moves everything {@code from} holds onto {@code to}, keeping each record as it is, and forgets {@code from}: a
     * claim filed under a provisional UUID is re-labelled here once the Create contraption's entity exists.
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
        // The kind travels with the structure rather than with the name: a sub-level re-labelled onto its own
        // UUID is still a sub-level.
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
                    // The target already holds a live claim here - the same contraption reused for a new
                    // machine. The live claim is the newer truth, so the stale put-down record is dropped.
                    byPosition.remove(host.at().asLong());
                    continue;
                }
                // Two live claims at one coordinate: impossible for one carrier, and the one already under the
                // real name is what the rest of the mod reads, so it stays and the count reports the move.
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
     * Puts every host of {@code carrier} back into the world, used when a whole structure goes away - a disassembly,
     * a removed sub-level - and by the orphan sweep. Idempotent (a repeat answers
     * {@link ReleaseStatus#ALREADY_RELEASED}) and a landing position somebody else has taken is never written over.
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
                // Somebody else is still carrying this host. A Create contraption and a Sable sub-level can share
                // one UUID, so this release would write the component back twice while the sub-level is still
                // flying. Nothing is written or re-pointed and the record stays live for its real owner; counted
                // as a conflict, because a release that could not be made is a fact the log has to carry.
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
     * Whether some <b>other</b> carrier is holding the host this record describes, right now: possible only when a
     * Create contraption and a Sable sub-level <b>share a UUID</b>. Asks whether the record answering at this host's
     * position is somebody else's live one - {@link #byPosition} is the one index of where a host is.
     */
    private boolean heldByAnotherCarrier(CarriedHost host) {
        CarriedHost answering = byPosition.get(host.at().asLong());
        if (answering == null || answering.landed() || answering == host) {
            return false;
        }
        // A different record is answering for this host's position. It belongs to another carrier if its local
        // coordinate or its origin differs - the two fields a record carries that name it.
        return !answering.localPos().equals(host.localPos())
                || !answering.origin().equals(host.origin());
    }

    /**
     * Releases one local coordinate, leaving the carrier's other hosts alone - the shape the per-block
     * {@code stopMoving} hook wants; {@link #releaseAll} is the wholesale form.
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
            // The second, third, fourth and fifth stopMoving calls for one motion land here. The landing
            // position is not recomputed: a repeat is identified by the record, not by the arithmetic, so a
            // structure that drifted a fraction between two callbacks cannot turn its repeat into a second write.
            return new ReleaseOutcome(ReleaseStatus.ALREADY_RELEASED, host.origin(), local,
                    host.at(), 0, 1, 0, 0);
        }

        BlockPos preferred = landing == null ? host.origin() : landing.landingFor(local);
        BlockPos written = writeBack(host, preferred, world);
        if (written == null) {
            // Nowhere safe to put it: the data stays carried and the entry stays live, so a later release can
            // still complete. Losing the component would be much worse than a delay.
            return new ReleaseOutcome(ReleaseStatus.HELD, host.origin(), local, preferred, 0, 0, 1, 1);
        }
        boolean atOrigin = !written.equals(preferred);
        put(hosts, host, written, atOrigin);
        return new ReleaseOutcome(atOrigin ? ReleaseStatus.LANDED_AT_ORIGIN : ReleaseStatus.LANDED,
                host.origin(), local, preferred, 1, 0, atOrigin ? 1 : 0, 0);
    }

    /**
     * Writes one host back at the preferred landing position or, if taken, where it came from. <b>An empty host writes
     * nothing</b> - a player may have taken the component out - but still counts as written, because the record did land
     * there and that is where this host answers from now on.
     */
    @Nullable
    private BlockPos writeBack(CarriedHost host, BlockPos preferred, SlotWorld world) {
        BlockPos target = preferred.immutable();
        if (taken(target, world) && !target.equals(host.origin())) {
            // The transform says this is where it belongs, but somebody else is there: the component goes
            // back to the block it was picked up from instead.
            target = host.origin();
        }
        if (taken(target, world)) {
            // Even the original position is occupied - a player rebuilt there while the machine was away.
            // The data stays carried rather than being dropped.
            return null;
        }
        Slot slot = host.slot();
        if (slot != null) {
            world.set(target, slot.copy());
        }
        return target;
    }

    /**
     * Whether {@code target} already holds somebody else's component. A position this index itself put a host down
     * at is not "taken": the contents there are this very component, written back by an earlier release or by an
     * earlier callback of the same one.
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
     * Forgets carriers that have put everything down, so the index stays bounded; it never drops live data, because a
     * host still riding is the only copy of a component, and the records of hosts put down are dropped here and nowhere
     * else. They are returned, so the caller can tell the client about a carrier it can no longer answer a pose for.
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
        // A position is answered by one host, so rebuilding from what is left keeps "which carrier answers
        // here" true for every surviving entry.
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
     * Records that one of the carriers here is a Sable sub-level rather than a Create contraption. A physics-assembled
     * structure shares <b>one UUID between a Create contraption entity and a Sable sub-level</b>, so the flag only ever
     * goes <b>up</b> - otherwise the last caller would re-file a flying sub-level as a contraption and the Create half's
     * orphan sweep would declare it gone.
     */
    private void markKind(CarrierId carrier) {
        if (carrier.isSubLevel()) {
            subLevelIds.add(carrier.uuid());
        }
    }

    /**
     * The full id of a carrier from its UUID: the kind is stored once per carrier rather than looked up per call, so
     * this is the one place the two halves are put back together.
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
     * Writes every record into {@code tag}. The tag is passed in rather than returned so the version number stays in
     * exactly one place - {@link CarriedHosts}, the class that owns the file.
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
                // An empty host saves nothing (see CarriedHost#save): its only payload is the component, and there
                // is none. `load` answers null for such a tag, so the null return has to be checked here.
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

        // Rebuild the reverse map from the records, so a save taken in mid-flight is still fully answerable: "is
        // this world position carried?" must not depend on the reverse map having been built in this process.
        for (Map.Entry<UUID, Long2ObjectMap<CarriedHost>> entry : index.byCarrier.entrySet()) {
            for (CarriedHost host : entry.getValue().values()) {
                index.bind(host);
            }
        }
        return index;
    }
}

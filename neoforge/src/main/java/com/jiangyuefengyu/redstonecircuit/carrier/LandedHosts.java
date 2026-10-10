package com.jiangyuefengyu.redstonecircuit.carrier;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;

import org.jetbrains.annotations.Nullable;

/**
 * The client's memory of hosts a structure has put down: one {@link CarrierIndex.CarriedView} per put-down
 * host, keyed by where it landed, in a pure copy-on-write map beside {@link CarrierIndex}. Landing falls
 * into neither draw path - the carried path has gone with its carrier, and the world path refuses to draw
 * in mid-air - so such a component would be invisible; a landed entry is a candidate, not a claim (see
 * {@link #worldPathDraws}), and writes replace the map because the render thread walks it.
 */
public final class LandedHosts {

    /** One position such a host landed at -> the hosts put down there. A list, because two structures
     * can put hosts down on the same block. */
    private volatile Long2ObjectMap<List<CarrierIndex.CarriedView>> byPosition =
            new Long2ObjectOpenHashMap<>();

    /** How many views are held, for the diagnostics. */
    private volatile int size;

    /** How many carriers have contributed at least one view; for the diagnostics. */
    private volatile int carrierCount;

    // ------------------------------------------------------------ writing --

    /** Takes everything {@code carrier} was holding into this map, because the carrier is gone.
     * Idempotent per host, so a re-sent removal notice leaves one entry, not two. */
    public int stow(@Nullable List<CarrierIndex.CarriedView> hosts) {
        if (hosts == null || hosts.isEmpty()) {
            return 0;
        }
        Long2ObjectMap<List<CarrierIndex.CarriedView>> next = copyOf(byPosition);
        int added = 0;
        boolean replaced = false;
        for (CarrierIndex.CarriedView host : hosts) {
            if (host == null) {
                continue;
            }
            if (put(next, host)) {
                added++;
            } else {
                replaced = true;
            }
        }
        if (added > 0 || replaced) {
            // One write for the whole call: the render thread sees a whole map, or none of this one.
            // A call that only replaced a view still has to write - the newer view is a new record.
            byPosition = next;
            if (added > 0) {
                size += added;
                recountCarriers();
            }
        }
        return added;
    }

    /** One view, keyed by its landing position and identified by carrier plus local coordinate.
     * Returns true when this is a host the map did not have. */
    private static boolean put(Long2ObjectMap<List<CarrierIndex.CarriedView>> map,
                               CarrierIndex.CarriedView host) {
        long key = host.at().asLong();
        List<CarrierIndex.CarriedView> bucket = map.get(key);
        if (bucket == null) {
            bucket = new ArrayList<>(1);
            map.put(key, bucket);
            bucket.add(host);
            return true;
        }
        List<CarrierIndex.CarriedView> replacement = new ArrayList<>(bucket.size() + 1);
        boolean replaced = false;
        for (CarrierIndex.CarriedView existing : bucket) {
            if (!replaced && existing.carrier().equals(host.carrier())
                    && existing.localPos().equals(host.localPos())) {
                // The same host, announced gone again: the newer view wins, but it is not a new entry.
                replacement.add(host);
                replaced = true;
            } else {
                replacement.add(existing);
            }
        }
        if (!replaced) {
            replacement.add(host);
        }
        map.put(key, replacement);
        return !replaced;
    }

    /** Forgets every landed host standing at {@code worldPos}: the world path owns the position from
     * now on, so a landed candidate there would be a second shell. */
    public int forgetAt(@Nullable BlockPos worldPos) {
        if (worldPos == null || byPosition.isEmpty()) {
            return 0;
        }
        long key = worldPos.asLong();
        List<CarrierIndex.CarriedView> bucket = byPosition.get(key);
        if (bucket == null || bucket.isEmpty()) {
            return 0;
        }
        Long2ObjectMap<List<CarrierIndex.CarriedView>> next = copyOf(byPosition);
        next.remove(key);
        byPosition = next;
        size -= bucket.size();
        recountCarriers();
        return bucket.size();
    }

    /** Forgets everything one carrier put down. */
    public int forgetCarrier(@Nullable CarrierId carrier) {
        if (carrier == null || byPosition.isEmpty()) {
            return 0;
        }
        Long2ObjectMap<List<CarrierIndex.CarriedView>> next = new Long2ObjectOpenHashMap<>();
        int dropped = 0;
        for (Long2ObjectMap.Entry<List<CarrierIndex.CarriedView>> entry
                : byPosition.long2ObjectEntrySet()) {
            List<CarrierIndex.CarriedView> kept = null;
            for (CarrierIndex.CarriedView view : entry.getValue()) {
                if (view.carrier().equals(carrier)) {
                    dropped++;
                    continue;
                }
                if (kept == null) {
                    kept = new ArrayList<>(entry.getValue().size());
                }
                kept.add(view);
            }
            if (kept != null) {
                next.put(entry.getLongKey(), kept);
            }
        }
        if (dropped == 0) {
            return 0;
        }
        byPosition = next;
        size -= dropped;
        recountCarriers();
        return dropped;
    }

    /** Drops everything, e.g. when leaving the world or applying a fresh snapshot. */
    public void clear() {
        byPosition = new Long2ObjectOpenHashMap<>();
        size = 0;
        carrierCount = 0;
    }

    /**
     * <b>Which of the hosts a client was holding have left their carrier's list, i.e. been put down.</b>
     *
     * <p>This is the rule the client's carrier handler is built on, kept here so it can be decided
     * offline. A carrier message replaces a carrier's host list wholesale, so "a host is not in the new
     * list" is how a single block being put down travels while the structure itself flies on - and that
     * host has to become a landed record, or it stops being drawn the moment its carrier is heard from
     * again without it.
     *
     * <p>Two ways to leave: the new list does not name it ({@code wholeCarrierGone} false), or the
     * carrier itself is gone and everything it held has been put down ({@code wholeCarrierGone} true).
     * Hosts are identified by carrier plus local coordinate, which is what names one host inside a
     * structure; nothing here compares slots or records by object identity.
     *
     * @param wasHolding what the client held for this carrier before the message
     * @param hosts the carrier's host list as the message just reported it
     * @param wholeCarrierGone true when the message carries no pose, i.e. the carrier is gone
     * @return the views that are now landed, in the order they were held
     */
    public static List<CarrierIndex.CarriedView> departed(
            @Nullable List<CarrierIndex.CarriedView> wasHolding,
            @Nullable List<CarrierIndex.CarriedView> hosts, boolean wholeCarrierGone) {
        if (wasHolding == null || wasHolding.isEmpty()) {
            return List.of();
        }
        Set<String> stillRiding = new HashSet<>();
        if (!wholeCarrierGone && hosts != null) {
            for (CarrierIndex.CarriedView host : hosts) {
                stillRiding.add(identity(host));
            }
        }
        List<CarrierIndex.CarriedView> out = new ArrayList<>(wasHolding.size());
        for (CarrierIndex.CarriedView view : wasHolding) {
            if (!stillRiding.contains(identity(view))) {
                out.add(view);
            }
        }
        return List.copyOf(out);
    }

    /** {@code (carrier, local coordinate)} as one comparable key: the carrier's full UUID, so two
     * structures can never be mistaken for one another, and a string, so a hash collision cannot
     * silently swallow a host. */
    private static String identity(CarrierIndex.CarriedView view) {
        return view.carrier().uuid() + "@" + view.localPos().asLong();
    }

    /** The same key, from the two fields the wire actually carries for a removal. */
    private static String identity(CarrierId carrier, BlockPos localPos) {
        return carrier.uuid() + "@" + localPos.asLong();
    }

    /**
     * <b>Why a host has left its carrier's list: put down, or destroyed.</b>
     *
     * <p>A carrier message replaces the host list wholesale, so "not in the new list" alone read as
     * "put down" - and a host whose block was <em>destroyed</em> leaves by the same door, so the client
     * went on drawing it as a landed record. The server therefore also names the hosts it
     * <em>discarded</em> (a break, or the self-healing sweep), and those are dropped outright instead.
     *
     * <p>The forgotten set wins if a host is ever in both: "this component is gone" must never be
     * softened into a ghost.
     */
    public record Forget(HashSet<String> keys, List<CarrierIndex.CarriedView> held) {

        /** Every held host the message says was discarded. */
        public List<CarrierIndex.CarriedView> forgotten() {
            List<CarrierIndex.CarriedView> out = new ArrayList<>();
            for (CarrierIndex.CarriedView view : held) {
                if (isForgotten(view)) {
                    out.add(view);
                }
            }
            return List.copyOf(out);
        }

        /** The held hosts that are only leaving the list, i.e. the ones to stow as landed. */
        public List<CarrierIndex.CarriedView> kept() {
            List<CarrierIndex.CarriedView> out = new ArrayList<>();
            for (CarrierIndex.CarriedView view : held) {
                if (!isForgotten(view)) {
                    out.add(view);
                }
            }
            return List.copyOf(out);
        }

        /** Whether one held host is named by the removal list. */
        public boolean isForgotten(CarrierIndex.CarriedView view) {
            return view != null && keys.contains(identity(view));
        }
    }

    /** The removal list of a carrier message, turned into something a walk can ask questions of. */
    public static Forget forget(@Nullable List<CarrierIndex.CarriedView> wasHolding,
                                @Nullable List<BlockPos> forgotten, CarrierId carrier) {
        HashSet<String> keys = new HashSet<>();
        if (forgotten != null && carrier != null) {
            for (BlockPos localPos : forgotten) {
                if (localPos != null) {
                    keys.add(identity(carrier, localPos));
                }
            }
        }
        return new Forget(keys, wasHolding == null ? List.of() : List.copyOf(wasHolding));
    }

    /** Drops one structure's landed records at these local coordinates. */
    public int forgetHosts(@Nullable CarrierId carrier, @Nullable Collection<BlockPos> localPositions) {
        if (carrier == null || localPositions == null || localPositions.isEmpty() || byPosition.isEmpty()) {
            return 0;
        }
        Set<Long> wanted = new HashSet<>();
        for (BlockPos localPos : localPositions) {
            if (localPos != null) {
                wanted.add(localPos.asLong());
            }
        }
        Long2ObjectMap<List<CarrierIndex.CarriedView>> next = new Long2ObjectOpenHashMap<>();
        int dropped = 0;
        for (Long2ObjectMap.Entry<List<CarrierIndex.CarriedView>> entry
                : byPosition.long2ObjectEntrySet()) {
            List<CarrierIndex.CarriedView> kept = null;
            for (CarrierIndex.CarriedView view : entry.getValue()) {
                if (view.carrier().equals(carrier) && wanted.contains(view.localPos().asLong())) {
                    dropped++;
                    continue;
                }
                if (kept == null) {
                    kept = new ArrayList<>(entry.getValue().size());
                }
                kept.add(view);
            }
            if (kept != null) {
                next.put(entry.getLongKey(), kept);
            }
        }
        if (dropped == 0) {
            return 0;
        }
        byPosition = next;
        size -= dropped;
        recountCarriers();
        return dropped;
    }

    // ------------------------------------------------------------- reads --
    /** Every landed host, for the renderer and the diagnostics. */
    public List<CarrierIndex.CarriedView> all() {
        Long2ObjectMap<List<CarrierIndex.CarriedView>> map = byPosition;
        List<CarrierIndex.CarriedView> out = new ArrayList<>(size);
        for (List<CarrierIndex.CarriedView> bucket : map.values()) {
            out.addAll(bucket);
        }
        return out;
    }

    /** Visits every landed host whose landing position is in a section near {@code center}. The bucket
     * key is a block position, so the section comes from {@link BlockPos#of(long)} and never from
     * reading that key as a {@link SectionPos} - the two packings are different widths. */
    public void forEachNear(@Nullable BlockPos center, int sectionRadius,
                            Consumer<CarrierIndex.CarriedView> action) {
        Long2ObjectMap<List<CarrierIndex.CarriedView>> map = byPosition;
        if (map.isEmpty() || center == null) {
            return;
        }
        int centerX = SectionPos.blockToSectionCoord(center.getX());
        int centerY = SectionPos.blockToSectionCoord(center.getY());
        int centerZ = SectionPos.blockToSectionCoord(center.getZ());
        for (Long2ObjectMap.Entry<List<CarrierIndex.CarriedView>> entry
                : map.long2ObjectEntrySet()) {
            BlockPos landedAt = BlockPos.of(entry.getLongKey());
            if (Math.abs(SectionPos.blockToSectionCoord(landedAt.getX()) - centerX) > sectionRadius
                    || Math.abs(SectionPos.blockToSectionCoord(landedAt.getY()) - centerY)
                            > sectionRadius
                    || Math.abs(SectionPos.blockToSectionCoord(landedAt.getZ()) - centerZ)
                            > sectionRadius) {
                continue;
            }
            for (CarrierIndex.CarriedView view : entry.getValue()) {
                action.accept(view);
            }
        }
    }

    /** Whether this position still has a landed candidate. */
    public boolean contains(@Nullable BlockPos worldPos) {
        return worldPos != null && byPosition.containsKey(worldPos.asLong());
    }

    // ------------------------------------------------- which path owns a position --

    /** Whether the ordinary world path draws this position at all - the single judge both paths obey.
     * Both facts are needed, and the host sync is not enough: that alone takes the position from the
     * landed path while the world path still refuses to draw it, which is the state that draws
     * nothing. */
    public static boolean worldPathDraws(boolean worldHost, boolean blockPresent) {
        return HostPathOwnership.worldPathDraws(worldHost, blockPresent);
    }

    /** Whether a landed or carried candidate must draw itself rather than yield: the exact complement
     * of {@link #worldPathDraws}, so a host is drawn by one path or the other and never by neither.
     * Delegates to {@link HostPathOwnership}, which owns the rule and is tested on its own. */
    public static boolean fallbackDraws(boolean worldHost, boolean blockPresent) {
        return HostPathOwnership.fallbackDraws(worldHost, blockPresent);
    }

    /** How many landed hosts are held. */
    public int size() {
        return size;
    }

    /** How many carriers have at least one landed host here. */
    public int carrierCount() {
        return carrierCount;
    }

    /** True while there is nothing to draw from this map, the cheap guard the renderer uses first. */
    public boolean isEmpty() {
        return size == 0;
    }

    /** One line for the diagnostics. */
    public String describe() {
        if (size == 0) {
            return "landed=0 (nothing put down since this client loaded the level)";
        }
        return "landed=" + size + " host(s) from " + carrierCount
                + " carrier(s), drawn where no block is standing";
    }

    /** A shallow copy with per-position lists copied, so a walk can never see a list grow. */
    private static Long2ObjectMap<List<CarrierIndex.CarriedView>> copyOf(
            Long2ObjectMap<List<CarrierIndex.CarriedView>> source) {
        Long2ObjectMap<List<CarrierIndex.CarriedView>> copy = new Long2ObjectOpenHashMap<>();
        for (Long2ObjectMap.Entry<List<CarrierIndex.CarriedView>> entry
                : source.long2ObjectEntrySet()) {
            copy.put(entry.getLongKey(), new ArrayList<>(entry.getValue()));
        }
        return copy;
    }

    /** Recomputes how many distinct carriers are represented; a walk over a handful of entries. */
    private void recountCarriers() {
        Set<CarrierId> carriers = new HashSet<>();
        for (List<CarrierIndex.CarriedView> bucket : byPosition.values()) {
            for (CarrierIndex.CarriedView view : bucket) {
                carriers.add(view.carrier());
            }
        }
        carrierCount = carriers.size();
    }
}

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
 * <b>The client's memory of hosts a structure has put down.</b> A pure, copy-on-write map kept beside
 * {@link CarrierIndex} rather than inside it, because it answers a different question.
 *
 * <h2>The hole this fills</h2>
 * The carried path and the world path are two ways to draw the same component, and until now the
 * client had to pick one from two facts it could observe:
 *
 * <ul>
 *   <li><b>carried</b> - a carrier payload named this host, and the client holds a pose for that
 *       carrier. Drawn inside the structure.</li>
 *   <li><b>world host</b> - the ordinary host sync named this position. Drawn at the block - and only
 *       if a block is really there, because the world renderer deliberately refuses to draw a shell in
 *       mid-air (see {@code HostRenderPass#collect}).</li>
 * </ul>
 *
 * <p>Landing falls into neither. The server puts the component back into its world store
 * ({@code CarrierStore}'s put-down path), sends the world sync for the landing position, and then
 * announces the carrier's removal - and the carrier's host list is what the client had been drawing
 * from. The block itself is <b>not</b> guaranteed to be back: a structure's blocks are Create's or
 * Sable's business, and a machine that has come to rest where it started can leave the position the
 * component belongs to as air. When that happens the world path skips the host (there is no block to
 * draw over), the carried path has already been dropped with the carrier, and the component is
 * <b>invisible until something else touches that position</b> - which is exactly the defect this
 * class closes.
 *
 * <h2>What it holds, and why that is enough</h2>
 * One {@link CarrierIndex.CarriedView} per put-down host, taken from the carrier payload the client
 * already has. The view carries the host's landing position in its {@code at} field - the server sends
 * it in every carrier message, pose or no pose - so no new field, no new message and no protocol
 * version bump are needed: the information was already on the wire and was simply thrown away when the
 * pose went {@code null}.
 *
 * <p>A landed entry is a <em>candidate</em>, not a claim. The renderer draws it unless the ordinary
 * world path would really put something on screen there, and that question is answered in one place -
 * {@link #worldPathDraws} - by the same two facts the world renderer itself uses: a host the client's
 * host sync knows about, <b>and</b> a block standing at the position. Both are needed. A host the sync
 * has announced while the block is still air is precisely the state this class exists for, and a
 * candidate that yielded to the world path on the strength of the sync alone would be drawn by
 * <em>neither</em> path for those frames - invisible, with no skip counter to say why.
 *
 * <p>The record is forgotten only when the world path has actually taken the position over (same
 * predicate), or when the server says the position is not a host any more, or when the carrier itself
 * supplies a newer truth. See {@code LandedHosts#worldPathDraws} for the rule and the P1 document's
 * §11.7 for what residual records are <em>expected</em>.
 *
 * <h2>Purity and threading</h2>
 * A map keyed by world position plus a list per position, and nothing that names a level, a packet or
 * a renderer: the whole contract - what is stowed, what is dropped, what survives a re-claim - is
 * therefore decided by offline unit tests. {@code ClientHostCache} is the thin client-side wrapper.
 *
 * <p>Writes <b>replace the map rather than mutate it</b>. The render thread walks this while the client
 * thread can be applying a packet, and a live map under a concurrent walk is the one kind of bug that
 * would be both intermittent and invisible; rebuilding costs a handful of entries per put-down, which
 * happens a few times per disassembly.
 */
public final class LandedHosts {

    /**
     * One position such a host landed at -> the hosts put down there.
     *
     * <p>Keyed by position because that is the one question the hot paths ask: "is this world position
     * one the carried path still owes a draw for". There really can be more than one view at one
     * position - two structures can put hosts down on the same block - so the value is a list.
     */
    private volatile Long2ObjectMap<List<CarrierIndex.CarriedView>> byPosition =
            new Long2ObjectOpenHashMap<>();

    /** How many views are held, for the diagnostics. */
    private volatile int size;

    /** How many carriers have contributed at least one view; for the diagnostics. */
    private volatile int carrierCount;

    // ------------------------------------------------------------ writing --

    /**
     * Takes everything {@code carrier} was holding into this map, because the carrier is gone.
     *
     * <p>Called when a carrier message arrives with no pose: the server has taken the carrier back and
     * the components are back in the world as far as it is concerned, so the client keeps them where
     * they were put down rather than dropping them on the floor.
     *
     * <p>Idempotent per host: the same carrier announced gone twice (the server's release paths are
     * deliberately chatty - idempotent in the data, never in the notice) leaves one entry, not two.
     * A repeat is not rare: a removal notice is re-sent on every repeated {@code stopMoving} callback.
     *
     * @param hosts the views the carrier was holding; {@code null} or empty is a no-op
     * @return how many views were newly taken in
     */
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
            // One write for the whole call: the render thread sees the map it started with or the
            // finished one, never a half-built list. A call that only replaced a view still has to
            // write: the newer view is a new record (the in-structure solve mutates contents in place),
            // and leaving the old one in the map would keep drawing the value from before the update.
            byPosition = next;
            if (added > 0) {
                size += added;
                recountCarriers();
            }
        }
        return added;
    }

    /**
     * One view, keyed by its landing position and identified by carrier plus local coordinate.
     *
     * @return true when this is a host the map did not have, false when it replaced one it did
     */
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
                // The same host, announced gone again. The newer view wins - the slot may have been
                // recomputed in flight - but it is not a new entry.
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

    /**
     * Forgets every landed host standing at {@code worldPos}.
     *
     * <p>The two callers are the same fact seen from two sides: the ordinary host sync says this
     * position is a world host again (the server put a component back there and the client has been
     * told), or the position's contents changed underneath a landed entry. Either way the world path
     * owns the position from now on and a landed candidate would be a second shell.
     *
     * @return how many views were dropped
     */
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

    /**
     * {@code (carrier, local coordinate)} as one comparable key.
     *
     * <p>The carrier's full UUID rather than its short id, so two structures can never be mistaken for
     * one another, and a string rather than a hash so a collision cannot silently swallow a host.
     * Allocating here is deliberate and bounded: a carrier's host list is a handful of entries and this
     * runs once per carrier message, not per frame.
     */
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
     * <h2>The defect this separates</h2>
     * A carrier message replaces one structure's host list wholesale, so "not in the new list" was the
     * only signal a client had - and it meant "put down", because that is what it almost always is. A
     * host whose block was <b>destroyed</b> leaves by the same door, and the client then kept it as a
     * landed record and went on drawing the component and the outline at the position the block used to
     * occupy, for as long as the structure existed. That is the ghost the author reported, and it is
     * not a drawing bug: the client was never told which of the two things had happened.
     *
     * <p>So the server says it: {@code forgotten} is the set of hosts it has <em>discarded</em> (the
     * record was removed without anything being written back - a break, or the self-healing sweep), and
     * every one of them is dropped outright instead of being stowed.
     *
     * <h2>Pure, and one place</h2>
     * The rule is decided offline by unit tests rather than inferred from a running client, exactly
     * like {@link #departed}. A host named in both lists cannot exist - a record that was discarded is
     * not in the carrier's live list either - and the forgotten set deliberately wins if one ever were:
     * "this component is gone" is the answer that must never be softened into a ghost.
     *
     * @param wasHolding what the client held for this carrier before the message
     * @param forgotten carrier plus local coordinate of every host the server discarded
     * @return which of the held hosts must be dropped, and which are merely leaving the list
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

    /**
     * Visits every landed host whose landing position is in a section near {@code center}.
     *
     * <p>The same section walk the other two indexes make, and for the same reason: the renderer asks
     * once per frame, so it must walk occupied sections rather than every host in the level. The bucket
     * key <em>is</em> the landing position here rather than the origin, because a landed host is drawn
     * exactly where it was put down.
     *
     * <p>The section is worked out from the <b>block</b> position the key names
     * ({@link BlockPos#of(long)}) rather than by asking {@link SectionPos} to decode the key. The two
     * packings are different widths for the same three numbers - {@code SectionPos.asLong} is
     * section coordinates, {@code BlockPos.asLong} is block coordinates - so reading one as the other
     * silently produces wrong sections for every y and z. Decoding through {@code BlockPos} is the only
     * spelling that is right by construction.
     *
     * @param action called once per landed host in range
     */
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

    /**
     * <b>Whether the ordinary world path draws a position at all</b> - the single judge both paths
     * obey, expressed in facts rather than in a level so it can be unit-tested.
     *
     * <p>Two facts, and <em>both</em> are needed:
     *
     * <ul>
     *   <li>{@code worldHost} - the client's ordinary host sync has a slot for this position, so this
     *       client believes a component belongs there. Without it the world path has no component to
     *       draw and, with no block needed either, it never looks at the position.</li>
     *   <li>{@code blockPresent} - the block at that position is <b>not air</b>. This is the world
     *       renderer's own gate: it deliberately refuses to draw a shell in mid-air, because a frosted
     *       cube hanging in the sky reads as a bug in the world rather than in the data.</li>
     * </ul>
     *
     * <h2>The defect this closes</h2>
     * Those two facts do <b>not</b> arrive together. When a structure puts a component down the server
     * broadcasts the world host immediately, while the block itself is put back by Create a moment
     * later - so for a frame or two the client holds a world host at a position that is still air.
     * Anything that decided "the world path owns this now" from the host sync alone therefore took the
     * component away from the landed path while the world path was still refusing to draw: <b>neither
     * path drew it and the component was invisible</b>. Asking the same two questions the renderer asks
     * is what makes the geometry impossible rather than unlikely.
     *
     * <p>Delegates to {@link HostPathOwnership}, which owns the rule and is unit-tested on its own; the
     * adapter is here because this is the class a reader following the landed story reaches first.
     *
     * @param worldHost the client's ordinary host sync holds a slot for this position
     * @param blockPresent the block at this position is present, i.e. not air
     */
    public static boolean worldPathDraws(boolean worldHost, boolean blockPresent) {
        return HostPathOwnership.worldPathDraws(worldHost, blockPresent);
    }

    /**
     * <b>Whether a landed or carried candidate must draw itself rather than yield.</b>
     *
     * <p>The exact complement of {@link #worldPathDraws}, and written in one place for that reason: a
     * host is drawn by the world path, or it is drawn by the carried path - never by both (two shells
     * on one block) and never by neither, the shape that used to be invisible. See
     * {@link HostPathOwnership} for the full argument, and use its {@code owner} when the answer is
     * wanted as a decision rather than as a boolean.
     *
     * @param worldHost the client's ordinary host sync holds a slot for this position
     * @param blockPresent the block at this position is present, i.e. not air
     * @return true when this candidate must draw itself rather than yield
     */
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

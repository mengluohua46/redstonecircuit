package com.jiangyuefengyu.redstonecircuit.carrier;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;

/**
 * {@code stopMoving} is not "the structure has put this block down": Create calls
 * {@code MovementBehaviour#stopMoving} whenever an actor stands down, and an actor stands down for many
 * reasons that are not a put-down.
 *
 * <p>The release is therefore <b>deferred</b>: the host keeps riding until one of the positive
 * observations in {@link #decide} holds. The decision is a pure function of booleans and tick counts, so
 * the whole rule is unit-testable.
 */
final class PendingRelease {

    /**
     * How long a deferred release waits for the structure to come back or go away, in level ticks: twenty
     * is one second - long enough that a structure still being driven reports in, short enough that a
     * component is never stranded for long when the structure really has gone. The orphan sweep is the
     * backstop beyond it.
     */
    static final int GRACE_TICKS = 20;

    private PendingRelease() {
    }

    /** What to do about one deferred host, now. */
    enum Action {

        /** Keep riding and keep waiting; nothing is decided yet. */
        DEFER,

        /** The structure is demonstrably still there: forget the request to stand down. */
        CANCEL,

        /** The host's world block is back - a real put-down. Land on that block. */
        RELEASE_BLOCK_BACK,

        /** The structure is positively gone. Land on the last pose the client was told. */
        RELEASE_STRUCTURE_GONE,

        /** The structure cannot be named and the grace has run out. Land on the last known pose. */
        RELEASE_GRACE_EXPIRED;

        /** Whether this action ends the ride. */
        boolean releases() {
            return this == RELEASE_BLOCK_BACK || this == RELEASE_STRUCTURE_GONE
                    || this == RELEASE_GRACE_EXPIRED;
        }

        /** Whether this action ends the deferral without releasing. */
        boolean cancels() {
            return this == CANCEL;
        }
    }

    /**
     * Everything the decision depends on, as facts the caller can stand behind.
     *
     * <p>{@code structureGone} must be <b>positive</b> knowledge - no pose source, not a live sub-level and
     * the entity no longer in the level; "I could not tell" is {@code false}. {@code ticksPending} counts
     * level ticks since {@code stopMoving} asked to stand down.
     */
    record Evidence(boolean worldBlockBack, boolean structureGone, boolean poseArrived,
                    boolean actorTicked, boolean structureAlive, long ticksPending) {

        /** Nothing observed yet: the state a deferral starts in. */
        static Evidence fresh() {
            return new Evidence(false, false, false, false, false, 0L);
        }

        /** The same evidence, one tick older. */
        Evidence tick() {
            return new Evidence(worldBlockBack, structureGone, poseArrived, actorTicked,
                    structureAlive, ticksPending + 1);
        }

        /** The same evidence with the "it is being driven" observations cleared for a new tick. */
        Evidence nextTick(boolean blockBackNow, boolean goneNow, boolean aliveNow) {
            return new Evidence(blockBackNow, goneNow, false, false, aliveNow, ticksPending + 1);
        }
    }

    /**
     * The rule, in order: the world block is back - a put-down by definition, land there; the structure is
     * positively gone - land on the last pose the client was told; anything says it is still driven (a
     * pose, an actor tick, a live sub-level) - cancel; the grace expired with nothing to go on - release
     * on the last known pose; otherwise wait.
     *
     * <p>Ordering is not arbitrary: the first two are observations of the world while the third is
     * evidence of continued activity, and a structure that is both driven and whose block is back did put
     * the block down and then carry on moving.
     */
    static Action decide(Evidence evidence) {
        if (evidence.worldBlockBack()) {
            return Action.RELEASE_BLOCK_BACK;
        }
        if (evidence.structureGone()) {
            return Action.RELEASE_STRUCTURE_GONE;
        }
        if (evidence.poseArrived() || evidence.actorTicked() || evidence.structureAlive()) {
            return Action.CANCEL;
        }
        if (evidence.ticksPending() >= GRACE_TICKS) {
            return Action.RELEASE_GRACE_EXPIRED;
        }
        return Action.DEFER;
    }

    /** The sentence for the log, naming the evidence rather than only the verdict. */
    static String describe(Action action, Evidence evidence, String carrier) {
        String state = "worldState=" + (evidence.worldBlockBack() ? "back" : "air")
                + ", entityGone=" + evidence.structureGone()
                + ", pose=" + (evidence.poseArrived() ? "arriving" : "silent")
                + ", actorTick=" + (evidence.actorTicked() ? "yes" : "no")
                + ", structureAlive=" + evidence.structureAlive()
                + ", age=" + evidence.ticksPending() + "t";
        return switch (action) {
            case CANCEL -> "carrier " + carrier + ": stopMoving seen but the structure is still alive ("
                    + state + ") - release CANCELLED, the host keeps riding";
            case RELEASE_BLOCK_BACK -> "carrier " + carrier + ": deferred release settled - the world "
                    + "block is back (" + state + ") - RELEASED at its own block";
            case RELEASE_STRUCTURE_GONE -> "carrier " + carrier + ": deferred release settled - the "
                    + "structure is gone (" + state + ") - RELEASED at the last known pose";
            case RELEASE_GRACE_EXPIRED -> "carrier " + carrier + ": deferred release expired after "
                    + evidence.ticksPending() + "t with nothing heard from the structure (" + state
                    + ") - RELEASED at the last known pose";
            case DEFER -> "carrier " + carrier + ": stopMoving seen but the structure is still alive ("
                    + state + ") - release DEFERRED, the host keeps riding";
        };
    }

    /**
     * The hosts of one dimension whose release is waiting on evidence.
     *
     * <p>A book rather than a field on {@link CarrierStore}, so the whole deferral - including its
     * idempotence - can be driven by a unit test with no level, no entity and no tick loop. Create calls
     * {@code stopMoving} four to five times for one motion, so a repeat must be a no-op rather than a
     * fresh request.
     */
    static final class Book {

        /** One deferred host: what to release, and since when. */
        record Entry(CarrierId carrier, BlockPos localPos, BlockPos worldBlock, long requestedAt,
                     Evidence evidence) {

            /** The same entry with fresh observations and one more tick behind it. */
            Entry next(boolean blockBackNow, boolean goneNow, boolean aliveNow) {
                return new Entry(carrier, localPos, worldBlock, requestedAt,
                        evidence.nextTick(blockBackNow, goneNow, aliveNow));
            }

            /** The same entry with the "still being driven" observations set. */
            Entry observed(boolean pose, boolean actor) {
                return new Entry(carrier, localPos, worldBlock, requestedAt,
                        new Evidence(evidence.worldBlockBack(), evidence.structureGone(), pose, actor,
                                evidence.structureAlive(), evidence.ticksPending()));
            }
        }

        private final Map<String, Entry> entries = new LinkedHashMap<>();

        /** One entry per carrier and local coordinate, which is what identifies a host. */
        private static String key(CarrierId carrier, BlockPos localPos) {
            return carrier.uuid() + "|" + localPos.asLong();
        }

        /**
         * Notes that this host's structure asked to stand down: {@code true} for a new deferral,
         * {@code false} when one was already pending - the original request time is kept, so repeated
         * callbacks for one motion still wait a single grace period.
         */
        boolean defer(CarrierId carrier, BlockPos localPos, BlockPos worldBlock, long now) {
            String key = key(carrier, localPos);
            Entry existing = entries.get(key);
            if (existing != null) {
                // Not re-dated: idempotence has to mean "one request", or a structure repeating the
                // callback every tick could postpone its own release forever.
                entries.put(key, new Entry(existing.carrier(), existing.localPos(), worldBlock,
                        existing.requestedAt(), existing.evidence()));
                return false;
            }
            entries.put(key, new Entry(carrier, localPos.immutable(), worldBlock.immutable(), now,
                    Evidence.fresh()));
            return true;
        }

        /** Forgets every deferral of one carrier, because it is demonstrably still there. */
        int cancel(CarrierId carrier) {
            List<String> doomed = new ArrayList<>();
            for (Map.Entry<String, Entry> entry : entries.entrySet()) {
                if (entry.getValue().carrier().equals(carrier)) {
                    doomed.add(entry.getKey());
                }
            }
            doomed.forEach(entries::remove);
            return doomed.size();
        }

        /** Forgets every deferral of one carrier, returning them so the caller can log the cancellation. */
        List<Entry> takeCancelled(CarrierId carrier) {
            List<Entry> cancelled = new ArrayList<>();
            List<String> doomed = new ArrayList<>();
            for (Map.Entry<String, Entry> entry : entries.entrySet()) {
                if (entry.getValue().carrier().equals(carrier)) {
                    cancelled.add(entry.getValue());
                    doomed.add(entry.getKey());
                }
            }
            doomed.forEach(entries::remove);
            return cancelled;
        }

        /** Records that a pose arrived for this carrier: the structure is being driven. */
        int notePose(CarrierId carrier) {
            return observe(carrier, true, false);
        }

        /** Records that Create's actor ticked for this carrier: the structure is being driven. */
        int noteActorTick(CarrierId carrier) {
            return observe(carrier, false, true);
        }

        private int observe(CarrierId carrier, boolean pose, boolean actor) {
            int touched = 0;
            for (Map.Entry<String, Entry> entry : entries.entrySet()) {
                Entry value = entry.getValue();
                if (value.carrier().equals(carrier)) {
                    entry.setValue(value.observed(pose, actor));
                    touched++;
                }
            }
            return touched;
        }

        /** Every deferral, oldest first, for one tick of evaluation. */
        List<Entry> entries() {
            return List.copyOf(entries.values());
        }

        /** Replaces one entry with its next-tick form, or removes it when the action settled it. */
        void settle(Entry entry, @Nullable Entry replacement) {
            String key = key(entry.carrier(), entry.localPos());
            if (replacement == null) {
                entries.remove(key);
            } else {
                entries.put(key, replacement);
            }
        }

        /** Drops one entry outright, by the key it was filed under. */
        void forget(CarrierId carrier, BlockPos localPos) {
            entries.remove(key(carrier, localPos));
        }

        int size() {
            return entries.size();
        }

        boolean isEmpty() {
            return entries.isEmpty();
        }

        /** The carriers with something waiting; for {@code /rc carrier} and the diagnostics. */
        List<CarrierId> carriers() {
            List<CarrierId> out = new ArrayList<>();
            for (Entry entry : entries.values()) {
                if (!out.contains(entry.carrier())) {
                    out.add(entry.carrier());
                }
            }
            return out;
        }

        void clear() {
            entries.clear();
        }
    }
}

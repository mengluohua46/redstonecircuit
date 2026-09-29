package com.jiangyuefengyu.redstonecircuit.carrier;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;

/**
 * <b>{@code stopMoving} is not "the structure has put this block down".</b>
 *
 * <h2>The defect this exists for</h2>
 * Create calls {@code MovementBehaviour#stopMoving} whenever an actor stands down - and an actor stands
 * down for many reasons that are not a put-down. The author's log is the measurement: a bearing at full
 * speed <b>reversed direction</b> ({@code +4.8°/tick} to {@code −4.8°/tick}), Create fired {@code
 * stopMoving}, the hook released the host on the spot, and the component vanished from the rotating block
 * - while the world block at the landing position was still {@code AIR} and the contraption entity was
 * <em>still present</em>:
 *
 * <pre>
 *   carrier c:ecef46f7 putdown contraption UNKNOWN_CARRIER origin=- local=0, 0, 0 landing=- moved=0
 *     … lastPose=pos=(2775.500, 77.500, -1394.500)
 *   carrier putdown contraption … worldState=Block{minecraft:air} (AIR)
 *     entity=ecef46f7-65de-4dee-99a0-2aabf4…            ← the entity is right there
 * </pre>
 *
 * <p>Both of the facts that would have justified a release were false. So the release is no longer taken
 * on {@code stopMoving}'s word: it is <b>deferred</b>, the host keeps riding, and it happens only when one
 * of three things is positively true - see {@link #decide}.
 *
 * <h2>Why the decision is a pure function</h2>
 * Everything it needs is a boolean or a tick count, so the whole rule can be a unit test. The alternative
 * - deciding inside the callback, with the level in hand - is what made the defect possible: every input
 * was available and none of it was written down.
 */
final class PendingRelease {

    /**
     * How long a deferred release waits for the structure to come back or go away, in level ticks.
     *
     * <p>Twenty ticks is one second: long enough to cover the reversal that caused the defect with room
     * to spare, short enough that a component is never stranded for a noticeable time when the structure
     * really has gone. The orphan sweep is the backstop beyond it.
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
     * <p>Each one is a question the server can actually answer at the moment it is asked - which is the
     * point: the defect was a release taken on no evidence at all.
     *
     * @param worldBlockBack whether the host's own world block is there again (not air). The one
     *     observation that means "it really was put down"
     * @param structureGone whether the structure is <b>positively</b> gone: no pose source, not a live
     *     sub-level, and its entity no longer in the level. "I could not tell" must be {@code false}
     * @param poseArrived whether the pose source answered for this carrier, or a pose was broadcast this
     *     tick - the structure is being driven, so it has not stopped
     * @param actorTicked whether Create's own {@code MovementBehaviour#tick} ran for this carrier since
     *     the deferral: the actor is still moving the block, whatever {@code stopMoving} said
     * @param structureAlive whether {@link CarrierStore#structureIsAlive} positively knows the structure
     *     exists - the Sable half of the same idea
     * @param ticksPending how many level ticks have passed since {@code stopMoving} asked to stand down
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
     * <b>The rule, in order, with the two answers that are not "keep waiting" checked first.</b>
     *
     * <ol>
     *   <li><b>The world block is back.</b> That is a put-down by definition, and the only observation
     *       that is worth more than any amount of inference. Land on that block.</li>
     *   <li><b>The structure is positively gone.</b> Entity absent, no pose, not a live sub-level. Land on
     *       the last pose the client was told, because the block it came from may be a field away.</li>
     *   <li><b>Anything says it is still there</b> - a pose, an actor tick, a live sub-level - and the
     *       request to stand down was wrong or premature. <b>Cancel</b>, and the host keeps riding through
     *       the direction change that produced the defect.</li>
     *   <li><b>The grace has expired with nothing to go on.</b> Nothing has been heard for a second; the
     *       honest reading is that the structure is not coming back. Release on the last known pose.</li>
     *   <li>Otherwise wait.</li>
     * </ol>
     *
     * <p>Ordering matters and is not arbitrary: 1 and 2 are positive observations of the world, while 3
     * is evidence of continued activity. A structure that is being driven <em>and</em> whose block is back
     * is a structure that put the block down and then carried on moving, which is a put-down.
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
     * <p>A book rather than a field on {@link CarrierStore} so that the whole of the deferral - including
     * its idempotence - can be driven by a unit test with no level, no entity and no tick loop. Create
     * calls {@code stopMoving} four to five times for one motion; that is the reason a repeat must be a
     * no-op rather than a fresh request.
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
         * Notes that this host's structure asked to stand down.
         *
         * @return {@code true} when this is a new deferral, {@code false} when it was already pending -
         *     in which case the original request time is kept, so five callbacks for one motion wait one
         *     grace period rather than five
         */
        boolean defer(CarrierId carrier, BlockPos localPos, BlockPos worldBlock, long now) {
            String key = key(carrier, localPos);
            Entry existing = entries.get(key);
            if (existing != null) {
                // Deliberately NOT re-dated. Idempotence has to mean "one request", or a structure that
                // repeats the callback every tick could postpone its own release forever.
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

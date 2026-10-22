package com.jiangyuefengyu.redstonecircuit.carrier;

import com.jiangyuefengyu.redstonecircuit.data.Slot;

import net.minecraft.core.BlockPos;

import org.jetbrains.annotations.Nullable;

/**
 * The migration decision for a component that is in the world store at a structure's own plot coordinate.
 *
 * <p>A sub-level's blocks <b>are</b> the level's blocks at their own local coordinates inside the plot region (see
 * {@code CarrierStore.CarrierSource#plotOwnerAt}), so one coordinate can be both "a position in the level" and "a
 * block inside a machine", and there are then two truths about one block: Jade reads the world store and displays the
 * component, the overlay reads the carrier index and draws nothing, the world bridge cannot see it, and breaking the
 * block drops it through the world's own break path.
 *
 * <p>This class moves such a component into the carrier index with one call to {@link CarriedHostIndex#fill} - the
 * method an ordinary pick-up goes through - using the plot coordinate as the <b>local coordinate</b> and as the
 * position the contents are read from, and {@link #originFor} as the world block the record answers at. Those are two
 * different numbers for a sub-level, whose blocks are the level's blocks at their plot coordinates, and reading the
 * contents at the origin would find nothing: the world store holds nothing at the block the machine is flying over.
 *
 * <p>Idempotent: {@link #plan} answers {@link Decision#ALREADY_CARRIED} for a coordinate the index already holds a
 * <b>live</b> record at, and {@code claim} itself is idempotent, so a coordinate is adopted at most once however often
 * the sweep runs.
 */
public final class WorldComponentAdoption {

    /**
     * What to do about one world-store entry that stands at a structure's own coordinate.
     *
     * <p>Three answers, each a different fact about the state of the world: {@link #ADOPT} - the world store holds a
     * component here and the index has no live record for it, so the caller files the record; {@link #ALREADY_CARRIED}
     * - the index already holds a live record at {@code (carrier, localPos)}, and in particular the <b>snapshot already
     * taken is the good one</b>, because re-claiming it would overwrite a component the player may have changed on the
     * carrier side with whatever the stale world entry says; {@link #NOTHING_THERE} - the world store holds nothing
     * here, which is the ordinary answer for a plot cell that never had one and for one already adopted.
     */
    public enum Decision {

        /** The world entry should become a carrier record, and the world entry removed. */
        ADOPT("adopt"),

        /** The index already holds a live record here: the migration has already happened. */
        ALREADY_CARRIED("already-carried"),

        /** The world store holds nothing at this coordinate; there is nothing to migrate. */
        NOTHING_THERE("nothing-there");

        private final String label;

        Decision(String label) {
            this.label = label;
        }

        /** The word the judgement line carries. */
        public String label() {
            return label;
        }
    }

    /**
     * What to do about one candidate, as a pair rather than as a bare answer. {@code slot} is the component the world
     * store holds, copied out by the caller and carried on the plan so the judgement line can name <b>what</b> was
     * adopted without reading the store a second time.
     */
    public record Plan(Decision decision, CarrierId carrier, BlockPos localPos, BlockPos origin,
                       @Nullable Slot slot) {

        /** True when the caller should file the record. */
        public boolean adopts() {
            return decision == Decision.ADOPT;
        }

        /**
         * The adoption line: which component, at which coordinate, into which structure, and with which origin - the
         * four facts a reader needs to tell an adoption from an ordinary claim.
         */
        public String describe() {
            return "adopted a world component at " + localPos.toShortString()
                    + " into carrier " + carrier.shortId()
                    + " (host local=" + localPos.toShortString()
                    + ", origin=" + origin.toShortString()
                    + ", " + (slot == null ? "-" : slot.type) + ")";
        }
    }

    private WorldComponentAdoption() {
    }

    /**
     * The decision, as a pure function of the index, the structure and the world's own entry - so the four properties
     * pinned offline (the world entry is adopted into a carrier host, a repeat scan is a no-op, a component that belongs
     * to no structure is untouched, and the origin's meaning at its edge) are assertions about this function alone.
     *
     * <p>A {@code null} index means the dimension has no records, in which case nothing can be adopted and every
     * candidate the world holds a component for is {@link Decision#ADOPT}; {@code localPos} is the world entry's own
     * key, because a plot coordinate is a level coordinate; {@code worldSlot} is what the world's own store holds there,
     * already copied out.
     */
    public static Plan plan(@Nullable CarriedHostIndex index, CarrierId carrier, BlockPos localPos,
                            BlockPos origin, @Nullable Slot worldSlot) {
        CarriedHost existing = index == null ? null : index.at(carrier, localPos);
        if (existing != null && !existing.landed()) {
            // THE IDEMPOTENCE. A live record at this coordinate means the migration has already happened, or the player
            // put the component in through the carrier side - better information than anything the world store still
            // says. The world entry is left alone: the claim that created the record is the one that removes it.
            return new Plan(Decision.ALREADY_CARRIED, carrier, localPos.immutable(), origin.immutable(),
                    existing.slot());
        }
        if (worldSlot == null) {
            return new Plan(Decision.NOTHING_THERE, carrier, localPos.immutable(), origin.immutable(), null);
        }
        return new Plan(Decision.ADOPT, carrier, localPos.immutable(), origin.immutable(), worldSlot.copy());
    }

    /**
     * The {@code origin} rule, as a pure function. An adopted component was never taken out of a world block, so there
     * is no "position it came from" to recover; what is recorded instead is where the structure's own pose puts that
     * coordinate <b>now</b> (see {@code CarrierStore#adoptWorldComponent}).
     *
     * <p><b>{@link CarrierProjection#drawnBlockOf} is the whole of it.</b> The origin is not a second opinion about
     * where a component is drawn: it is the same block the renderer draws the host in, the same block the world-side
     * signal query asks, and the same block the notification pass wakes. Two answers used to exist - a rounded block
     * corner here, a floored block centre there - and they agree only while the structure is unturned, because a
     * rotation carries the half block between a corner and a centre onto all three axes. A record filed under the
     * other answer makes the component belong to a neighbouring cell for every reader of the record: it is
     * interacted with there, it supplies signal there, and only one block ever shows it, since there was only ever
     * one record.
     *
     * <p>The three answers, all of them decisions rather than accidents: a readable pose gives
     * {@link CarrierProjection#project(CarrierPose, BlockPos)}; no pose gives {@code null}, which means "do not adopt
     * yet", because a position that cannot be computed must not be guessed at; and a pose that is not finite gives
     * {@code null} for the same reason, since a scale of zero or a NaN quaternion answers {@code (NaN, NaN, NaN)} and
     * flooring that would produce a block that looks real.
     */
    @Nullable
    public static BlockPos originFor(@Nullable CarrierPose pose, BlockPos localPos) {
        return localPos == null ? null : CarrierProjection.drawnBlockOf(pose, localPos);
    }

    /**
     * Removes the world entry that a plan has just declined to adopt because the index already holds a live record
     * for that coordinate.
     *
     * <p><b>Keeping the snapshot and keeping the world entry are different decisions.</b> The record's contents are
     * the better copy and must not be overwritten by whatever the world store still says ({@link #plan} answers
     * {@link Decision#ALREADY_CARRIED} for exactly that reason). But the world entry at that coordinate is a
     * <em>second answer for one block</em> - the same defect adoption exists to remove: Jade reads the world store
     * and shows the component, the overlays read the index, the world bridge cannot see the machine, and a break
     * drops the component through the world's own path. Leaving it there is not a transient state either: every
     * later scan finds it again and answers {@link Decision#ALREADY_CARRIED} again, so the two truths persist for
     * as long as the save does.
     *
     * @return whether an entry was removed, so the caller can mark its file dirty
     */
    public static boolean clearShadowedWorldEntry(@Nullable Plan plan,
                                                  @Nullable CarriedHostIndex.SlotWorld world) {
        if (plan == null || world == null || plan.decision() != Decision.ALREADY_CARRIED) {
            return false;
        }
        if (world.get(plan.localPos()) == null) {
            return false;
        }
        world.set(plan.localPos(), null);
        return true;
    }
}

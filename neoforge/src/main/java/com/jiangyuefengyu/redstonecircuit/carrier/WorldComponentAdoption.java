package com.jiangyuefengyu.redstonecircuit.carrier;

import com.jiangyuefengyu.redstonecircuit.data.Slot;

import net.minecraft.core.BlockPos;

import org.jetbrains.annotations.Nullable;

/**
 * <b>The migration decision for a component that is in the world store at a structure's own plot
 * coordinate.</b>
 *
 * <h2>The state this exists to repair, and how the log shows it</h2>
 * A sub-level's blocks <b>are</b> the level's blocks at their own local coordinates inside the plot
 * region (see {@code CarrierStore.CarrierSource#plotOwnerAt}). So the world store's key space and a
 * structure's local coordinate space <em>overlap</em>, and a component can be written into the world store
 * at a coordinate that is simultaneously "a position in the level" and "a block inside a machine". The
 * author's log is that state, in two lines four milliseconds apart:
 *
 * <pre>
 *   placed RETAINING_REPEATER at 20481032, 127, 20481032
 *   host 20481032, 127, 20481032
 *     RETAINING_REPEATER out=0 in=south
 * </pre>
 *
 * <p>and, for the whole session, {@code carrier claim = 0}. The consequences are exactly the reported
 * ones - Jade reads the world store and displays the component, the overlay reads the carrier index and
 * draws nothing, the world bridge cannot see it, and breaking the block drops it through the world's own
 * break path - because <b>there are two truths about one block</b>.
 *
 * <p>{@code CarrierClickPriority} stops new ones from being written (the world side now stands down at a
 * coordinate a live sub-level owns, see P1.30 commit A). This class is the other half: it moves the ones
 * that already exist into the carrier index, so a save that is already in this state repairs itself.
 *
 * <h2>What the migration is, in the index's own terms</h2>
 * One call to {@link CarriedHostIndex#claim} - the same method every ordinary pick-up goes through - with
 * the plot coordinate as <b>both</b> the local coordinate and the origin, which is what makes it remove
 * the world entry as it files the record (see {@code claim}: the entry is read, snapshotted and then set
 * to {@code null}).
 *
 * <h2>Idempotence, which is the whole safety argument</h2>
 * {@link #plan} answers {@link Decision#ALREADY_CARRIED} whenever the index already holds a <b>live</b>
 * record at that coordinate, and {@code claim} itself is idempotent (it answers {@code alreadyCarried} and
 * leaves the original snapshot alone). A coordinate is therefore adopted at most once no matter how often
 * the sweep runs, and a repeat costs one map lookup. The record's removal of the world entry is the second
 * half of the same guarantee: after adoption the world store no longer holds anything at the coordinate,
 * so it cannot even be a candidate again.
 *
 * <h2>Why the local coordinate is not enough to identify the candidate</h2>
 * The record is keyed by {@code (carrier, localPos)} and the world entry by a single position. For every
 * candidate this class is handed, those two numbers are <b>equal</b> - that is what "a plot coordinate"
 * means - but they are two different facts and both are passed in, so the day a caller has a candidate
 * whose two frames differ, nothing here silently adopts into the wrong coordinate.
 */
public final class WorldComponentAdoption {

    /**
     * What to do about one world-store entry that stands at a structure's own coordinate.
     *
     * <p>Three answers, and each one is a different fact about the state of the world:
     *
     * <ul>
     *   <li>{@link #ADOPT} - there is a component in the world store at this coordinate, the index has no
     *       live record for it, and this is the migration. The caller files the record.</li>
     *   <li>{@link #ALREADY_CARRIED} - the index already holds a live record at
     *       {@code (carrier, localPos)}. Nothing to do, and in particular the <b>snapshot already taken is
     *       the good one</b>: re-claiming it would overwrite a component the player may have changed on
     *       the carrier side with whatever the (stale) world entry says. This answer is what makes a
     *       repeated sweep a no-op.</li>
     *   <li>{@link #NOTHING_THERE} - the world store holds no component at this coordinate. The ordinary
     *       answer for a plot cell that never had one, and the answer for a coordinate that has already
     *       been adopted (the claim removed the entry).</li>
     * </ul>
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
     * What to do about one candidate, as a pair rather than as a bare answer.
     *
     * <p>{@code slot} is the component the world store holds, copied out by the caller: it is carried on
     * the plan so the judgement line can name <b>what</b> was adopted without reading the store a second
     * time - and so the offline test asserts the same value the record will be built from.
     */
    public record Plan(Decision decision, CarrierId carrier, BlockPos localPos, BlockPos origin,
                       @Nullable Slot slot) {

        /** True when the caller should file the record. */
        public boolean adopts() {
            return decision == Decision.ADOPT;
        }

        /**
         * The adoption line: which component, at which coordinate, into which structure, and with which
         * origin - the four facts a reader needs to tell an adoption from an ordinary claim.
         *
         * <pre>
         *   adopted a world component at 20481032, 127, 20481032 into carrier s:def66f0c
         *     (host local=20481032, 127, 20481032, origin=-12, -60, -12, RETAINING_REPEATER)
         * </pre>
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
     * <b>The decision, as a pure function of the index, the structure and the world's own entry.</b>
     *
     * <p>Pure on purpose, and that is not decoration: the four properties the author asked to be pinned
     * offline - the world entry is adopted into a carrier host, a repeat scan is a no-op, a component that
     * belongs to no structure is untouched, and the origin's meaning at its edge - are all statements
     * about <em>this</em> function, and none of them needs a running game to check.
     *
     * @param index the structure's own records, or {@code null} when the dimension has none - in which
     *     case nothing can be adopted and every candidate is {@link Decision#ADOPT} if the world holds a
     *     component (the index's absence is exactly "no record exists")
     * @param carrier the structure the coordinate belongs to
     * @param localPos the coordinate inside the structure - the world entry's own key, because a plot
     *     coordinate is a level coordinate
     * @param origin where the component is recorded as having come from; see
     *     {@code CarrierStore#adoptWorldComponent} for the rule and its reasons
     * @param worldSlot what the world's own store holds at {@code localPos}, already copied out, or
     *     {@code null} when it holds nothing
     * @return what to do, never {@code null}
     */
    public static Plan plan(@Nullable CarriedHostIndex index, CarrierId carrier, BlockPos localPos,
                            BlockPos origin, @Nullable Slot worldSlot) {
        CarriedHost existing = index == null ? null : index.at(carrier, localPos);
        if (existing != null && !existing.landed()) {
            // THE IDEMPOTENCE. A live record at this coordinate means the migration has already happened
            // (or the player put a component in through the carrier side, which is better information
            // than anything the world store still says). The world entry is deliberately left alone: the
            // claim that created the record is the one that removes it, and a second remover is how two
            // truths get swapped rather than reconciled.
            return new Plan(Decision.ALREADY_CARRIED, carrier, localPos.immutable(), origin.immutable(),
                    existing.slot());
        }
        if (worldSlot == null) {
            return new Plan(Decision.NOTHING_THERE, carrier, localPos.immutable(), origin.immutable(), null);
        }
        return new Plan(Decision.ADOPT, carrier, localPos.immutable(), origin.immutable(), worldSlot.copy());
    }

    /**
     * <b>The {@code origin} rule as a pure function, so its three edge cases are assertions rather than
     * prose.</b>
     *
     * <p>An adopted component was never taken out of a world block, so there is no "position it came from"
     * to recover; what is recorded instead is where the structure's own pose puts that coordinate
     * <b>now</b>. See {@code CarrierStore#adoptWorldComponent} for the argument.
     *
     * <p>The three answers, all of them decisions rather than accidents:
     *
     * <ul>
     *   <li><b>a readable pose</b> - the nearest block to the pose's own answer, rounded by the same
     *       {@link SubLevelSpace#nearestWorldBlock} the claim path uses, so the two frames name one block
     *       rather than two;</li>
     *   <li><b>no pose</b> ({@code worldFor} answered {@code null}) - {@code null}, which means "do not
     *       adopt yet". A position that cannot be computed must not be guessed at: the plot coordinate
     *       would be a legal-looking answer that puts the release fallback two million blocks from
     *       anywhere a player can go;</li>
     *   <li><b>a pose that is not finite</b> - {@code null}, for the same reason. A scale of zero or a
     *       NaN quaternion answers {@code (NaN, NaN, NaN)}, and rounding that would produce a coordinate
     *       that looks like a number.</li>
     * </ul>
     *
     * @param worldFor the structure's own "where is this local coordinate", or {@code null} when there is
     *     no pose to ask
     * @param localPos the coordinate being adopted
     * @return the origin to record, or {@code null} when nothing may be adopted yet
     */
    @Nullable
    public static BlockPos originFor(BlockPos localPos, SubLevelSpace.WorldLookup worldFor) {
        if (localPos == null || worldFor == null) {
            return null;
        }
        org.joml.Vector3d world = worldFor.worldFor(localPos);
        if (world == null || !world.isFinite()) {
            return null;
        }
        return SubLevelSpace.nearestWorldBlock(world);
    }
}

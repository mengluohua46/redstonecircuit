package com.jiangyuefengyu.redstonecircuit.carrier;

import com.jiangyuefengyu.redstonecircuit.data.Slot;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

import org.jetbrains.annotations.Nullable;

/**
 * One host block that a moving structure has picked up.
 *
 * <p>This is the carried half of the data model, and it exists next to - not instead of - the world
 * store. {@code InnerRedstoneStore} keeps its world {@link BlockPos} keys (that is what lets redstone
 * live inside any block, and P0 was explicit that changing it would throw the whole feature away); a
 * host that has been picked up gets an entry <em>here</em> as well, keyed by
 * {@code carrier + localPos}, with a snapshot of its contents so nothing depends on the world entry
 * still being readable.
 *
 * <h2>The four things one entry has to remember</h2>
 * <ol>
 *   <li>{@link #origin()} - where the block stood when it was picked up. This is the "return to where
 *       you started" half of the P1 promise. The landing position is normally recomputed from the
 *       carrier's own transform, but that recomputation can fail (a structure that comes apart
 *       somewhere else, a callback that never arrives), and the original position is the only answer
 *       that is always available and always safe.</li>
 *   <li>{@link #localPos()} - its coordinate inside the structure. Half of the key, and what the
 *       renderer draws at.</li>
 *   <li>{@link #slot()} - a <b>copy</b> of the component, taken before the world entry was touched.
 *       This is what makes the move rollback-able: if the landing position turns out to be occupied,
 *       the data is still here and can be put back exactly as it was.</li>
 *   <li>{@link #at()} and {@link #landed()} - whether the release has happened, and the one world
 *       position this host currently answers at. Create calls {@code stopMoving} four to five times
 *       for a single motion, all with identical arguments, so the release has to be idempotent;
 *       {@code landed} is what makes the second call a no-op rather than a second write. A landed
 *       entry keeps answering at {@link #at()} rather than being deleted, because the client's copy of
 *       the store is driven by explicit snapshots and must keep reading the component where it was
 *       told it is.</li>
 * </ol>
 *
 * @param origin where the block was when the structure picked it up
 * @param localPos its coordinate inside the structure
 * @param slot the component that was inside it, snapshotted at pick-up time, or <b>{@code null} when the
 *     block is being carried and holds nothing</b> - the state a player leaves behind by taking the
 *     component back out ({@code CarriedHostIndex#removeSlot}). Every reader has to treat that as "there
 *     is no component here", exactly as the world store's own "this block has no entry" does: it is not
 *     an error, it is the ordinary state of a block somebody has just built into a machine and emptied,
 *     and dereferencing it is what crashed the server in P1.23
 * @param at the one world position this host currently answers at
 * @param landed true once the release has written this entry back to the world
 * @param conflict true when the release landed at the origin rather than at the position the
 *     structure's transform named, because that position was occupied
 */
public record CarriedHost(BlockPos origin, BlockPos localPos, @Nullable Slot slot, BlockPos at,
                          boolean landed, boolean conflict) {

    /** A host that has just been picked up: still riding, answering at the position it came from. */
    public static CarriedHost captured(BlockPos origin, BlockPos localPos, Slot slot) {
        BlockPos from = origin.immutable();
        return new CarriedHost(from, localPos.immutable(), slot.copy(), from, false, false);
    }

    /** The same entry, put down at {@code landing}. */
    public CarriedHost releasedTo(BlockPos landing) {
        return new CarriedHost(origin, localPos, slot, landing.immutable(), true, conflict);
    }

    /**
     * The same entry, put down at {@code landing}, recording that this was the fallback position.
     *
     * <p>Used when the landing position the structure named was occupied: the component did go back,
     * but not where the structure was standing, and that is worth a line in the log.
     */
    public CarriedHost releasedToOrigin() {
        return new CarriedHost(origin, localPos, slot, origin, true, true);
    }

    /**
     * A copy, so the record is never handed out with a slot the caller can still mutate.
     *
     * <p>An <b>empty</b> host copies as an empty host: {@code null} is a state this record may hold (see
     * the class doc), so the copy has to carry it rather than dereference it. The old body called
     * {@code slot.copy()} unconditionally, which is one of the two places the P1.23 crash could come from.
     */
    public CarriedHost copy() {
        return new CarriedHost(origin.immutable(), localPos.immutable(),
                slot == null ? null : slot.copy(), at.immutable(), landed, conflict);
    }

    /**
     * The same host, holding {@code replacement} instead of the slot it was captured with.
     *
     * <p>Used by the in-structure solve: the component a host carries is a record field, so
     * recomputing its power means putting a new record in the index. Everything else - where it came
     * from, where it is, whether it has been put down - is untouched, which is the point: solving a
     * structure must never move anything.
     *
     * <p>{@code replacement} may be {@code null}: that is a player emptying the block, and
     * {@code CarriedHostIndex#removeSlot} is the named form of it.
     */
    public CarriedHost withSlot(@Nullable Slot replacement) {
        return new CarriedHost(origin, localPos, replacement, at, landed, conflict);
    }

    /** True while this entry is still riding the structure. */
    public boolean isCarried() {
        return !landed;
    }

    // ------------------------------------------------------------------ NBT --

    /**
     * This host, or {@code null} when there is nothing worth saving.
     *
     * <p><b>An empty host is not saved, and that is the whole of the rule.</b> The record's only payload
     * is its component; a block a structure is carrying and that holds nothing has nothing to persist,
     * and {@link #load} already answers {@code null} for a tag with no {@code slot} in it - so writing one
     * would be a record that cannot be read back. It used to be written anyway
     * ({@code slot.save(slotTag)} on a null slot), which is the third place the P1.23 crash could come
     * from; the landing path already refuses to put an empty host back into the world
     * ({@code CarriedHostIndex#writeBack}), so dropping it from the file is the same statement one step
     * earlier.
     */
    @Nullable
    public CompoundTag save() {
        if (slot == null) {
            return null;
        }
        CompoundTag tag = new CompoundTag();
        tag.putLong("origin", origin.asLong());
        tag.putLong("local", localPos.asLong());
        tag.putLong("at", at.asLong());
        tag.putBoolean("landed", landed);
        tag.putBoolean("conflict", conflict);
        CompoundTag slotTag = new CompoundTag();
        slot.save(slotTag);
        tag.put("slot", slotTag);
        return tag;
    }

    /** Reads one back, or {@code null} when the tag is not a carried host. */
    @Nullable
    public static CarriedHost load(@Nullable CompoundTag tag) {
        if (tag == null || !tag.contains("slot")) {
            return null;
        }
        BlockPos origin = BlockPos.of(tag.getLong("origin"));
        return new CarriedHost(origin, BlockPos.of(tag.getLong("local")), Slot.load(tag.getCompound("slot")),
                tag.contains("at") ? BlockPos.of(tag.getLong("at")) : origin,
                tag.getBoolean("landed"), tag.getBoolean("conflict"));
    }
}

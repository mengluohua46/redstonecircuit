package com.jiangyuefengyu.redstonecircuit.carrier;

import com.jiangyuefengyu.redstonecircuit.data.Slot;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

import org.jetbrains.annotations.Nullable;

/**
 * One host block that a moving structure has picked up.
 *
 * <p>The carried half of the data model, next to - not instead of - the world store: a host that has been picked
 * up also gets an entry here, keyed by {@code carrier + localPos}, with a snapshot of its contents so nothing
 * depends on the world entry still being readable. {@link #origin()} is where it stood, always available when
 * the carrier's transform cannot be recomputed, and {@link #slot()} is a copy, which is what makes the move
 * rollback-able.
 *
 * <p>The release has to be idempotent - Create calls {@code stopMoving} four to five times for a single motion -
 * so {@link #landed} makes a second call a no-op, and a landed entry keeps answering at {@link #at()}.
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
     * The same entry, put down at the origin because the landing position the structure named was occupied;
     * {@link #conflict} records the fallback.
     */
    public CarriedHost releasedToOrigin() {
        return new CarriedHost(origin, localPos, slot, origin, true, true);
    }

    /**
     * A copy, so the record is never handed out with a slot the caller can still mutate. An empty host copies as
     * an empty host rather than being dereferenced.
     */
    public CarriedHost copy() {
        return new CarriedHost(origin.immutable(), localPos.immutable(),
                slot == null ? null : slot.copy(), at.immutable(), landed, conflict);
    }

    /**
     * The same host, holding {@code replacement} instead of the slot it was captured with; used by the
     * in-structure solve, which must never move anything. A {@code null} replacement is a player emptying the block.
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
     * This host, or {@code null} when there is nothing worth saving: an empty host is not saved, because
     * {@link #load} answers {@code null} for a tag with no {@code slot}.
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

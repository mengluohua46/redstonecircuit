package com.jiangyuefengyu.redstonecircuit.data;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;

/**
 * Everything stored inside one host block: exactly one redstone component.
 *
 * <p>A block either holds a component or it does not. Connectivity towards the six neighbouring
 * positions is a property of the component (see {@link Slot}), which is what lets inner redstone
 * link vertically between two adjacent blocks - something vanilla redstone dust cannot do.
 */
public final class InnerRedstoneNode {

    private Slot slot;

    public InnerRedstoneNode() {
    }

    public static InnerRedstoneNode of(ComponentType type) {
        InnerRedstoneNode node = new InnerRedstoneNode();
        node.slot = new Slot(type);
        return node;
    }

    @Nullable
    public Slot slot() {
        return slot;
    }

    public boolean isEmpty() {
        return slot == null;
    }

    public void setSlot(Slot slot) {
        this.slot = slot;
    }

    public ComponentType type() {
        return slot == null ? null : slot.type;
    }

    /** Signal strength this block's inner component currently produces (dust only for now). */
    public int power() {
        return slot == null ? 0 : slot.power;
    }

    /** Human readable summary used by the {@code /rc dump} debug command. */
    public String describe(BlockPos pos) {
        if (slot == null) {
            return "host " + pos.toShortString() + " (empty)";
        }
        return "host " + pos.toShortString() + "\n  " + slot.describe();
    }

    // ------------------------------------------------------------------ NBT --

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        if (slot != null) {
            CompoundTag slotTag = new CompoundTag();
            slot.save(slotTag);
            tag.put("component", slotTag);
        }
        return tag;
    }

    public static InnerRedstoneNode load(CompoundTag tag) {
        InnerRedstoneNode node = new InnerRedstoneNode();
        if (tag.contains("component")) {
            node.slot = Slot.load(tag.getCompound("component"));
        }
        return node;
    }
}

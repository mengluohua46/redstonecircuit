package com.jiangyuefengyu.redstonecircuit.data;

import java.util.EnumMap;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/**
 * Everything stored inside one host block.
 *
 * <p>A node is keyed by the host block's position and holds at most one {@link Slot} per
 * {@link Direction}. Keeping the data here (rather than replacing the host block) is what lets
 * redstone live inside untouched vanilla blocks such as stone.
 */
public final class InnerRedstoneNode {

    private final EnumMap<Direction, Slot> slots = new EnumMap<>(Direction.class);
    /** Monotonic counter handed out by {@link #nextPlaceOrder()}; persisted so ordering survives reloads. */
    private int placeCounter;

    public Map<Direction, Slot> slots() {
        return slots;
    }

    public boolean isEmpty() {
        return slots.isEmpty();
    }

    public int size() {
        return slots.size();
    }

    public Slot get(Direction face) {
        return slots.get(face);
    }

    public boolean has(Direction face) {
        return slots.containsKey(face);
    }

    /** Installs (or replaces) the component on a face and stamps its placement order. */
    public Slot put(Direction face, ComponentType type) {
        Slot slot = new Slot(type);
        slot.placeOrder = ++placeCounter;
        slots.put(face, slot);
        return slot;
    }

    public Slot remove(Direction face) {
        return slots.remove(face);
    }

    public int nextPlaceOrder() {
        return ++placeCounter;
    }

    /**
     * The face whose component was placed most recently, or {@code null} when empty.
     * Used to take components back out in reverse placement order.
     */
    public Direction newestFace() {
        Direction newest = null;
        int best = Integer.MIN_VALUE;
        for (Map.Entry<Direction, Slot> entry : slots.entrySet()) {
            if (entry.getValue().placeOrder >= best) {
                best = entry.getValue().placeOrder;
                newest = entry.getKey();
            }
        }
        return newest;
    }

    /** Sum of every dust slot's current power; reserved for stage 3's signal bridging. */
    public int maxPower() {
        int max = 0;
        for (Slot slot : slots.values()) {
            if (slot.type == ComponentType.DUST && slot.power > max) {
                max = slot.power;
            }
        }
        return max;
    }

    // ------------------------------------------------------------------ NBT --

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("placeCounter", placeCounter);

        ListTag list = new ListTag();
        for (Map.Entry<Direction, Slot> entry : slots.entrySet()) {
            CompoundTag slotTag = new CompoundTag();
            slotTag.putString("face", entry.getKey().getName());
            entry.getValue().save(slotTag);
            list.add(slotTag);
        }
        tag.put("slots", list);
        return tag;
    }

    public static InnerRedstoneNode load(CompoundTag tag) {
        InnerRedstoneNode node = new InnerRedstoneNode();
        node.placeCounter = tag.getInt("placeCounter");

        ListTag list = tag.getList("slots", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag slotTag = list.getCompound(i);
            Direction face = readDirection(slotTag.getString("face"));
            if (face != null) {
                node.slots.put(face, Slot.load(slotTag));
            }
        }
        return node;
    }

    private static Direction readDirection(String name) {
        for (Direction direction : Direction.values()) {
            if (direction.getName().equalsIgnoreCase(name)) {
                return direction;
            }
        }
        return null;
    }

    /** Human readable summary used by the {@code /rc dump} debug command. */
    public String describe(BlockPos pos) {
        StringBuilder sb = new StringBuilder("host ").append(pos.toShortString()).append(" (")
                .append(slots.size()).append(" component(s))");
        for (Direction face : Direction.values()) {
            Slot slot = slots.get(face);
            if (slot != null) {
                sb.append("\n  ").append(face.getName()).append(": ").append(slot.describe());
            }
        }
        return sb.toString();
    }
}

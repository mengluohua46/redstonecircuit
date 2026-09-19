package com.jiangyuefengyu.redstonecircuit.data;

import java.util.LinkedHashSet;
import java.util.Set;

import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;

/**
 * One redstone component attached to a single face of a host block.
 *
 * <p>A host block can hold up to six of these (one per {@link Direction}).
 */
public final class Slot {

    /** What is installed on this face. */
    public ComponentType type;
    /** Signal strength for {@link ComponentType#DUST}, 0-15. Ignored by the other types. */
    public int power;
    /** On/off state for torches, repeaters and comparators. */
    public boolean powered;
    /** In-face orientation for diodes (repeaters/comparators) and wall-mounted parts. */
    public Direction facing;
    /** Repeater delay in redstone ticks, 1-4. */
    public int delay = 1;
    /** Comparator output mode. */
    public ComparatorMode mode = ComparatorMode.COMPARE;
    /**
     * Connections locked by the redstone wrench. When non-empty the automatic topology is
     * ignored for this slot, which keeps the locked connections stable while neighbouring
     * redstone is added or removed.
     */
    public final Set<Direction> wrenchLinks = new LinkedHashSet<>();
    /**
     * Monotonic placement counter, used to retrieve components in reverse placement order
     * (last one placed is the first one taken back out).
     */
    public int placeOrder;

    public Slot(ComponentType type) {
        this.type = type;
        this.facing = Direction.NORTH;
    }

    private Slot() {
    }

    public Slot copy() {
        Slot copy = new Slot(this.type);
        copy.power = this.power;
        copy.powered = this.powered;
        copy.facing = this.facing;
        copy.delay = this.delay;
        copy.mode = this.mode;
        copy.wrenchLinks.addAll(this.wrenchLinks);
        copy.placeOrder = this.placeOrder;
        return copy;
    }

    // ------------------------------------------------------------------ NBT --

    public void save(CompoundTag tag) {
        tag.putString("type", type.name());
        tag.putInt("power", power);
        tag.putBoolean("powered", powered);
        tag.putString("facing", facing.getName());
        tag.putInt("delay", delay);
        tag.putString("mode", mode.name());
        tag.putInt("placeOrder", placeOrder);

        int[] links = wrenchLinks.stream().mapToInt(Direction::get3DDataValue).toArray();
        tag.putIntArray("wrenchLinks", links);
    }

    public static Slot load(CompoundTag tag) {
        Slot slot = new Slot();
        slot.type = ComponentType.byName(tag.getString("type"), ComponentType.DUST);
        slot.power = tag.getInt("power");
        slot.powered = tag.getBoolean("powered");
        slot.facing = readDirection(tag.getString("facing"), Direction.NORTH);
        slot.delay = Math.max(1, Math.min(4, tag.getInt("delay") == 0 ? 1 : tag.getInt("delay")));
        slot.mode = ComparatorMode.byName(tag.getString("mode"), ComparatorMode.COMPARE);
        slot.placeOrder = tag.getInt("placeOrder");

        for (int value : tag.getIntArray("wrenchLinks")) {
            Direction direction = directionByIndex(value);
            if (direction != null) {
                slot.wrenchLinks.add(direction);
            }
        }
        return slot;
    }

    private static Direction readDirection(String name, Direction fallback) {
        for (Direction direction : Direction.values()) {
            if (direction.getName().equalsIgnoreCase(name)) {
                return direction;
            }
        }
        return fallback;
    }

    private static Direction directionByIndex(int index) {
        for (Direction direction : Direction.values()) {
            if (direction.get3DDataValue() == index) {
                return direction;
            }
        }
        return null;
    }

    /** Human readable summary used by the {@code /rc dump} debug command. */
    public String describe() {
        StringBuilder sb = new StringBuilder(type.name());
        if (type == ComponentType.DUST) {
            sb.append(" power=").append(power);
        } else {
            sb.append(" facing=").append(facing.getName());
            if (type == ComponentType.REPEATER) {
                sb.append(" delay=").append(delay);
            } else if (type == ComponentType.COMPARATOR) {
                sb.append(" mode=").append(mode.name());
            }
            sb.append(" powered=").append(powered);
        }
        if (!wrenchLinks.isEmpty()) {
            sb.append(" locked=[");
            boolean first = true;
            for (Direction direction : wrenchLinks) {
                if (!first) {
                    sb.append(',');
                }
                sb.append(direction.getName());
                first = false;
            }
            sb.append(']');
        }
        return sb.toString();
    }
}

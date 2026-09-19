package com.jiangyuefengyu.redstonecircuit.data;

import java.util.EnumSet;
import java.util.Set;

import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;

/**
 * The single redstone component stored inside one host block.
 *
 * <p>Design note: a host block holds <b>one</b> component, not one per face. The component can
 * connect towards any of the six neighbouring positions; connectivity is derived from what is
 * around it and can be overridden per direction with the redstone wrench.
 *
 * <pre>
 *   forcedOn  - directions the wrench explicitly connected
 *   forcedOff - directions the wrench explicitly disconnected
 *   neither   - automatic: connect if the neighbour holds inner redstone, or if a piece of
 *               vanilla redstone wire sits against this block on that side
 * </pre>
 */
public final class Slot {

    /** What is installed inside this block. */
    public ComponentType type;
    /** Signal strength for {@link ComponentType#DUST}, 0-15. Ignored by the other types. */
    public int power;
    /** On/off state for torches, repeaters and comparators. */
    public boolean powered;
    /** Orientation for diodes (repeaters/comparators) and directional parts. */
    public Direction facing;
    /** Repeater delay in redstone ticks, 1-4. */
    public int delay = 1;
    /** Comparator output mode. */
    public ComparatorMode mode = ComparatorMode.COMPARE;

    /** Directions the wrench forced ON. */
    public final Set<Direction> forcedOn = EnumSet.noneOf(Direction.class);
    /** Directions the wrench forced OFF. */
    public final Set<Direction> forcedOff = EnumSet.noneOf(Direction.class);

    /**
     * Marks this component as a <em>fixed power source</em> for the network solver: its {@link #power}
     * is treated as an input rather than something to be derived.
     *
     * <p>Only the debug command sets this, to seed a network for experiments and tests. Power that a
     * component gained through propagation never sets it, so removing the real supply still drains
     * the network as it should.
     */
    public boolean fixedSource;

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
        copy.forcedOn.addAll(this.forcedOn);
        copy.forcedOff.addAll(this.forcedOff);
        return copy;
    }

    // ---------------------------------------------------------- connectivity --

    /** True when this component may connect towards {@code direction}. */
    public boolean isConnected(Direction direction) {
        if (forcedOn.contains(direction)) {
            return true;
        }
        if (forcedOff.contains(direction)) {
            return false;
        }
        return true; // AUTO: whether it actually links up is decided by the network solver.
    }

    /** True when the wrench has pinned this direction either way. */
    public boolean isLocked(Direction direction) {
        return forcedOn.contains(direction) || forcedOff.contains(direction);
    }

    /** Sets an explicit connection state, or clears the override for {@link ConnectionState#AUTO}. */
    public void setConnection(Direction direction, ConnectionState state) {
        forcedOn.remove(direction);
        forcedOff.remove(direction);
        switch (state) {
            case ON -> forcedOn.add(direction);
            case OFF -> forcedOff.add(direction);
            case AUTO -> {
            }
        }
    }

    public void clearConnections() {
        forcedOn.clear();
        forcedOff.clear();
    }

    // ------------------------------------------------------------------ NBT --

    public void save(CompoundTag tag) {
        tag.putString("type", type.name());
        tag.putInt("power", power);
        tag.putBoolean("powered", powered);
        tag.putString("facing", facing.getName());
        tag.putInt("delay", delay);
        tag.putString("mode", mode.name());
        tag.putIntArray("forcedOn", encode(forcedOn));
        tag.putIntArray("forcedOff", encode(forcedOff));
    }

    public static Slot load(CompoundTag tag) {
        Slot slot = new Slot();
        slot.type = ComponentType.byName(tag.getString("type"), ComponentType.DUST);
        slot.power = tag.getInt("power");
        slot.powered = tag.getBoolean("powered");
        slot.facing = readDirection(tag.getString("facing"), Direction.NORTH);
        slot.delay = Math.max(1, Math.min(4, tag.getInt("delay") == 0 ? 1 : tag.getInt("delay")));
        slot.mode = ComparatorMode.byName(tag.getString("mode"), ComparatorMode.COMPARE);
        decode(tag.getIntArray("forcedOn"), slot.forcedOn);
        decode(tag.getIntArray("forcedOff"), slot.forcedOff);
        return slot;
    }

    private static int[] encode(Set<Direction> directions) {
        return directions.stream().mapToInt(Direction::get3DDataValue).toArray();
    }

    private static void decode(int[] values, Set<Direction> out) {
        for (int value : values) {
            for (Direction direction : Direction.values()) {
                if (direction.get3DDataValue() == value) {
                    out.add(direction);
                }
            }
        }
    }

    private static Direction readDirection(String name, Direction fallback) {
        for (Direction direction : Direction.values()) {
            if (direction.getName().equalsIgnoreCase(name)) {
                return direction;
            }
        }
        return fallback;
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
        if (!forcedOn.isEmpty()) {
            sb.append(" forcedOn=").append(names(forcedOn));
        }
        if (!forcedOff.isEmpty()) {
            sb.append(" forcedOff=").append(names(forcedOff));
        }
        return sb.toString();
    }

    private static String names(Set<Direction> directions) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (Direction direction : directions) {
            if (!first) {
                sb.append(',');
            }
            sb.append(direction.getName());
            first = false;
        }
        return sb.append(']').toString();
    }
}

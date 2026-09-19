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
 *   forcedOn  - directions the wrench locked
 *   forcedOff - directions the wrench explicitly cut
 *   neither   - automatic: connect if the neighbour holds inner redstone, or if a piece of
 *               vanilla redstone wire sits against this block on that side
 * </pre>
 *
 * <h2>Locking is not the same as cutting</h2>
 * Once a component has <em>any</em> locked direction, its routing is explicit: every side that is not
 * locked is treated as cut ({@link #isClosed}). That is what makes a lock mean "this line and nothing
 * else", and it is deliberately <b>derived rather than stored</b>:
 *
 * <ul>
 *   <li>Locking a second direction takes one click, because a side that was cut by a lock still reads
 *       as automatic ({@code AUTO -> ON} rather than {@code OFF -> AUTO -> ON}). Wires keep their
 *       connections as a set of locked lines, so a route is built one click per hop.</li>
 *   <li>A block with only an explicit cut ({@code forcedOff}) and no lock keeps deciding for itself on
 *       every other side, so cutting one side does not silently freeze the whole block.</li>
 *   <li>Nothing can drift: the rule is applied in one place, the solver, so the drawing and the signal
 *       queries cannot disagree with it.</li>
 * </ul>
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

    /** Directions the wrench locked. */
    public final Set<Direction> forcedOn = EnumSet.noneOf(Direction.class);
    /** Directions the wrench explicitly cut. */
    public final Set<Direction> forcedOff = EnumSet.noneOf(Direction.class);

    /**
     * A power supply planted by hand (debug command and game tests), 0-15, or {@code -1} when the
     * component has none.
     *
     * <p>This is an <em>input</em> to the solver, exactly like a lever touching the host block: the
     * component holds at least this strength no matter what surrounds it. Power a component gained
     * through propagation never sets it, which is what lets a chain drain once its real supply is
     * removed. Deliberately not saved - it exists to seed experiments, not to persist in a world.
     */
    public int injectedPower = -1;

    /** True when {@link #injectedPower} is an actual supply rather than "none". */
    public boolean hasInjectedPower() {
        return injectedPower >= 0;
    }

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
        copy.injectedPower = this.injectedPower;
        return copy;
    }

    // ---------------------------------------------------------- connectivity --

    /**
     * Whether the wrench has locked this component anywhere.
     *
     * <p>When it has, the component's routing is explicit: see {@link #isClosed}.
     */
    public boolean isRoutingLocked() {
        return !forcedOn.isEmpty();
    }

    /** True when this side was locked: the signal goes out here, whatever the neighbours say. */
    public boolean isOpen(Direction direction) {
        return forcedOn.contains(direction);
    }

    /**
     * True when this side is cut: the wrench cut it, or it was left out of the lock.
     *
     * <p>The derived half is what makes a lock mean "this line and nothing else" - and because it is
     * derived, locking several directions is a matter of locking each of them.
     */
    public boolean isClosed(Direction direction) {
        if (forcedOff.contains(direction)) {
            return true;
        }
        return isRoutingLocked() && !isOpen(direction);
    }

    /** True when this side was cut by hand, as opposed to being left out of a lock. */
    public boolean isExplicitlyCut(Direction direction) {
        return forcedOff.contains(direction);
    }

    /**
     * True when signal may pass across this side: locked sides, and automatic sides of a component
     * that has not been locked anywhere.
     */
    public boolean isConnected(Direction direction) {
        return !isClosed(direction);
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
        sb.append(" out=").append(power);
        if (type.isManual()) {
            sb.append(" on=").append(powered);
        }
        if (type.isDriven()) {
            sb.append(" in=").append(facing.getName());
        } else if (type.isDiode()) {
            sb.append(" facing=").append(facing.getName());
        }
        if (type == ComponentType.REPEATER) {
            sb.append(" delay=").append(delay).append(" (").append(type.delayTicks(delay)).append(" ticks)");
        } else if (type == ComponentType.COMPARATOR) {
            sb.append(" mode=").append(mode.name());
        }
        if (hasInjectedPower()) {
            sb.append(" injected=").append(injectedPower);
        }
        if (isRoutingLocked()) {
            sb.append(" locked=").append(names(forcedOn)).append(" (every other side cut)");
        }
        if (!forcedOff.isEmpty()) {
            sb.append(" cut=").append(names(forcedOff));
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

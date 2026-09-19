package com.jiangyuefengyu.redstonecircuit.data;

/**
 * The kinds of redstone components that can live inside a host block.
 *
 * <p>Stages 1-2 only ever create {@link #DUST}, because placement currently accepts redstone
 * dust alone. The remaining constants exist so that the data file format is already complete:
 * adding repeaters/comparators later is then purely a behaviour change, with no save migration.
 */
public enum ComponentType {
    /** Redstone dust (wire). */
    DUST,
    /** Redstone repeater. Uses {@link Slot#delay}. */
    REPEATER,
    /** Redstone comparator. Uses {@link Slot#mode}. */
    COMPARATOR,
    /** Redstone torch (including the wall variant). */
    TORCH,
    /** Lever. */
    LEVER,
    /** Button. */
    BUTTON,
    /** Pressure plate. */
    PRESSURE_PLATE;

    /** True when this component is a power source rather than a conductor/relay. */
    public boolean isSource() {
        return this == TORCH || this == LEVER || this == BUTTON || this == PRESSURE_PLATE;
    }

    /** True when this component relays a signal and therefore has a direction. */
    public boolean isDiode() {
        return this == REPEATER || this == COMPARATOR;
    }

    public static ComponentType byName(String name, ComponentType fallback) {
        for (ComponentType type : values()) {
            if (type.name().equalsIgnoreCase(name)) {
                return type;
            }
        }
        return fallback;
    }
}

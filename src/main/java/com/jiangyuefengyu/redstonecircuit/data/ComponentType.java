package com.jiangyuefengyu.redstonecircuit.data;

/**
 * The kinds of redstone component that can live inside a host block.
 *
 * <p>Every one of them stores its <b>output strength</b> in {@link Slot#power}, so a host block can
 * report "what is inside me" to the outside world without knowing which kind it is
 * ({@code InnerRedstoneStore#getSignal}). What differs between the types is where that output goes,
 * what drives it, and how long it takes to follow a change - all decided by {@code PowerSolver}.
 */
public enum ComponentType {
    /** Redstone dust (wire). The only type that behaves like a wire rather than like a device. */
    DUST,
    /**
     * 超导红石粉 - superconducting dust: a wire whose hops cost nothing.
     *
     * <p>Vanilla charges every wire hop to the wire that receives it ({@code max(sources, best
     * neighbouring wire - 1)}), which is why a normal line fades and why fifteen cannot travel far. A
     * superconductor is the same wire with a hop cost of zero, so a run of it carries whatever strength
     * it was given all the way to the end. Everything else about it - reading and emitting on every
     * side, giving no strong power - is ordinary dust.
     */
    SUPERCONDUCTOR,
    /** Redstone repeater. Reads {@link Slot#facing}, delays by {@link Slot#delay}. */
    REPEATER,
    /** Redstone comparator. Reads {@link Slot#facing} and its two sides, uses {@link Slot#mode}. */
    COMPARATOR,
    /** Redstone torch: lit unless whatever it is attached to is powered. Inverts. */
    TORCH,
    /** Lever: a manual source. */
    LEVER,
    /** Button: a lever that releases itself. */
    BUTTON,
    /** Pressure plate: a source that answers to whatever stands on the host block. */
    PRESSURE_PLATE;

    /** True when this component is a power source rather than a conductor/relay. */
    public boolean isSource() {
        return this == TORCH || this == LEVER || this == BUTTON || this == PRESSURE_PLATE;
    }

    /**
     * True when this component propagates like a wire: it reads and emits on every side, and a hop
     * between two of them is charged by the receiver.
     */
    public boolean isWire() {
        return this == DUST || this == SUPERCONDUCTOR;
    }

    /**
     * What one hop into this wire costs.
     *
     * <p>One for ordinary dust - vanilla's rule, and the reason a line fades - and nothing for a
     * superconductor, which is the whole point of it.
     */
    public int hopCost() {
        return this == SUPERCONDUCTOR ? 0 : 1;
    }

    /** True when this component relays a signal and therefore has a direction. */
    public boolean isDiode() {
        return this == REPEATER || this == COMPARATOR;
    }

    /**
     * True when this component has an input side: its output is decided by what reaches it, not by
     * hand. These are the components whose output lags behind their input.
     */
    public boolean isDriven() {
        return this == TORCH || this == REPEATER || this == COMPARATOR;
    }

    /** True when flipping this component needs a player (or the debug command). */
    public boolean isManual() {
        return this == LEVER || this == BUTTON;
    }

    /**
     * How many ticks the output lags behind a change of input.
     *
     * <p>Matches vanilla: a torch needs {@code TOGGLE_DELAY = 2} ticks, a repeater is one redstone
     * tick (2 game ticks) per setting, and a comparator runs at the base diode delay of 2 ticks.
     * Everything else - dust, lever, button, plate - reacts within the same tick, exactly as it does
     * in vanilla.
     *
     * @param repeaterDelay the repeater's 1-4 setting; ignored by every other type
     */
    public int delayTicks(int repeaterDelay) {
        switch (this) {
            case REPEATER:
                return 2 * Math.max(1, Math.min(4, repeaterDelay));
            case COMPARATOR:
            case TORCH:
                return 2;
            default:
                return 0;
        }
    }

    /**
     * The output a freshly placed component starts with, before anything has been solved.
     */
    public int initialPower() {
        // A torch is lit the moment it is placed; everything else starts silent and is raised by
        // whatever feeds it (or by the player, for a lever).
        return this == TORCH ? 15 : 0;
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

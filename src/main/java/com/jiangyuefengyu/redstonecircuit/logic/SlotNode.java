package com.jiangyuefengyu.redstonecircuit.logic;

import com.jiangyuefengyu.redstonecircuit.data.ComparatorMode;
import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.Slot;

import net.minecraft.core.Direction;

/**
 * Adapts a stored {@link Slot} to the narrow {@link PowerEnvironment.Node} the solver works with.
 *
 * <p>The connection overrides and the orientation live on the slot; the rules that interpret them
 * live in {@link PowerSolver}. This is only the bridge between the two.
 */
public final class SlotNode implements PowerEnvironment.Node {

    private final Slot slot;

    private SlotNode(Slot slot) {
        this.slot = slot;
    }

    /** Wraps a slot, or returns {@code null} for {@code null} so callers can pass a lookup straight in. */
    public static PowerEnvironment.Node of(Slot slot) {
        return slot == null ? null : new SlotNode(slot);
    }

    @Override
    public ComponentType type() {
        return slot.type;
    }

    @Override
    public int power() {
        return slot.power;
    }

    @Override
    public void setPower(int power) {
        slot.power = PowerSolver.clamp(power);
    }

    @Override
    public Direction facing() {
        return slot.facing;
    }

    @Override
    public ComparatorMode mode() {
        return slot.mode;
    }

    @Override
    public boolean forcedOn(Direction direction) {
        return slot.forcedOn.contains(direction);
    }

    @Override
    public boolean forcedOff(Direction direction) {
        return slot.forcedOff.contains(direction);
    }
}

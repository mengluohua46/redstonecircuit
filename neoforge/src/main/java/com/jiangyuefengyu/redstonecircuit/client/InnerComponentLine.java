package com.jiangyuefengyu.redstonecircuit.client;

import java.util.Objects;

import com.jiangyuefengyu.redstonecircuit.data.ComparatorMode;
import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.Slot;

import org.jetbrains.annotations.Nullable;

/**
 * The fields of the "what is inside this block" line, as values rather than as text.
 *
 * <p>Two readers must never answer differently - the goggles' Jade overlay for a block standing in the world
 * and for one a structure is carrying - so what the component says is decided here, once, as a translation
 * key plus its arguments rather than as a {@code Component}.
 *
 * <p>The state is an enum rather than a bare number: the same {@code power} means different things by type,
 * and a comparator has no delay setting while a switch is only on or off.
 */
public record InnerComponentLine(ComponentType type, StateKind kind, int power, int delay,
                                 boolean subtrahend, boolean powered) {

    /** Which shape the state takes, and therefore which words the overlay uses. */
    public enum StateKind {
        /** A wire or a superconductor: the strength itself, in levels. */
        STRENGTH,
        /** A repeater: "has output" or "no output", plus the delay setting. */
        REPEATER,
        /** A comparator: its mode (compare / subtract) and whether it has output. */
        COMPARATOR,
        /** A torch: lit or dark. Same field as {@code powered}, different words. */
        TORCH,
        /** A lever or a button: on or off. */
        SWITCH,
        /** A pressure plate, which nobody flips: its strength. */
        PLATE,
        /**
         * 控制器: whether it is answering, at the strength it was told to answer with; its settings live on
         * its own screen.
         */
        CONTROLLER,
        /**
         * 红石变压器: the controller's shape, a kind of its own because its line names a strength it was set to.
         */
        TRANSFORMER,
        /** 时钟源: whether it is emitting, at the strength it was set to; its period lives on the device's screen. */
        CLOCK,
        /**
         * 信号延长器: whether it is answering, at the strength it is answering with - the input's own while it is
         * arriving, and the strength it was answering with while a hold runs; both settings live on its screen.
         */
        EXTENDER
    }

    /**
     * The line for one component, or {@code null} when there is nothing to say. The single decision both
     * readers make, so "the world overlay and the carried overlay agree" holds by construction.
     */
    @Nullable
    public static InnerComponentLine of(@Nullable Slot slot) {
        if (slot == null || slot.type == null) {
            return null;
        }
        StateKind kind = switch (slot.type) {
            case DUST, SUPERCONDUCTOR -> StateKind.STRENGTH;
            case REPEATER, RETAINING_REPEATER, DOUBLE_REPEATER, DOUBLE_RETAINING_REPEATER ->
                    StateKind.REPEATER;
            case COMPARATOR -> StateKind.COMPARATOR;
            case TORCH -> StateKind.TORCH;
            case LEVER, BUTTON -> StateKind.SWITCH;
            case PRESSURE_PLATE -> StateKind.PLATE;
            case CONTROLLER -> StateKind.CONTROLLER;
            case TRANSFORMER -> StateKind.TRANSFORMER;
            case CLOCK -> StateKind.CLOCK;
            case EXTENDER -> StateKind.EXTENDER;
        };
        return new InnerComponentLine(slot.type, kind, slot.power, slot.delay,
                slot.mode == ComparatorMode.SUBTRACT, slot.powered);
    }

    /** True when the component is carrying anything at all, for the shapes that reduce to that. */
    public boolean hasOutput() {
        return power > 0;
    }

    /**
     * One short, game-free rendering of this line, for a log or a test. <b>Not</b> what the overlay prints:
     * that is a translated {@code Component}, because a player reads it.
     */
    public String describe() {
        return switch (kind) {
            case STRENGTH -> type + " out=" + power;
            case REPEATER -> type + " " + (hasOutput() ? "output" : "no output") + " delay=" + delay;
            case COMPARATOR -> type + " " + (hasOutput() ? "output" : "no output")
                    + " mode=" + (subtrahend ? "subtract" : "compare");
            case TORCH -> type + " " + (hasOutput() ? "lit" : "unlit");
            case SWITCH -> type + " " + (powered ? "on" : "off");
            case PLATE -> type + " out=" + power;
            case CONTROLLER -> type + " " + (hasOutput() ? "output=" + power : "no output");
            case TRANSFORMER -> type + " " + (hasOutput() ? "output=" + power : "no output");
            case CLOCK -> type + " " + (hasOutput() ? "output=" + power : "no output");
            case EXTENDER -> type + " " + (hasOutput() ? "output=" + power : "no output");
        };
    }

    /** Two lines describe the same thing, field for field. */
    public boolean sameFieldsAs(@Nullable InnerComponentLine other) {
        return other != null
                && type == other.type
                && kind == other.kind
                && power == other.power
                && delay == other.delay
                && subtrahend == other.subtrahend
                && powered == other.powered;
    }

    /** Non-null type, for the one caller that reads it directly. */
    public ComponentType requireType() {
        return Objects.requireNonNull(type, "a line is only built for a slot that has a type");
    }
}

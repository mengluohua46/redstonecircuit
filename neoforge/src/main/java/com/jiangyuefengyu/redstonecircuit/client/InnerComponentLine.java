package com.jiangyuefengyu.redstonecircuit.client;

import java.util.Objects;

import com.jiangyuefengyu.redstonecircuit.data.ComparatorMode;
import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.Slot;

import org.jetbrains.annotations.Nullable;

/**
 * <b>The fields of the "what is inside this block" line, as values rather than as text.</b>
 *
 * <h2>Why the numbers are separated from the words</h2>
 * Two readers ask this question and they must never answer differently: the goggles' Jade overlay for a
 * block standing in the world, and the same overlay for a block a structure is <em>carrying</em> - the
 * author's "can Jade be made to work under a physicalised block". The two reach the component through
 * different stores (the world host sync and the carrier payload), and the one thing they share is what
 * the component <em>says</em>: its type, its setting and its current state.
 *
 * <p>That shared part is decided here, once, and it is deliberately a value rather than a
 * {@code Component}: a translation key plus its arguments. The overlay turns it into text, and an offline
 * unit test can assert on it without a language file, a renderer or a game - which is the only way
 * "the carried overlay shows the same fields as the world one" is checkable before it is in front of a
 * player.
 *
 * <h2>Why the state is a value and not a number</h2>
 * The same {@code power} means different things by type: a wire's strength is worth reading off, a
 * repeater's is only ever nothing or fifteen (so the mod words it as "has output"), a comparator has no
 * delay setting at all, and a switch is on or off. Encoding that in an enum keeps the <em>wording</em>
 * where wording belongs - in the Jade overlay - and keeps the <em>decision</em> here, where a test can
 * see it.
 *
 * @param type the kind of component inside the block
 * @param kind which shape the state takes for that type
 * @param power the strength, for the shapes that report one
 * @param delay the delay setting, for the shapes that have one
 * @param subtrahend true for a comparator in subtract mode, false in compare mode
 * @param powered true for a switch that is thrown, false for one that is not
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
        PLATE
    }

    /**
     * The line for one component, or {@code null} when there is nothing to say (no component at all).
     *
     * <p>The single decision both readers make, so "the world overlay and the carried overlay agree" is
     * true by construction rather than by two switch statements being kept in step by hand.
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
        };
        return new InnerComponentLine(slot.type, kind, slot.power, slot.delay,
                slot.mode == ComparatorMode.SUBTRACT, slot.powered);
    }

    /** True when the component is carrying anything at all, for the shapes that reduce to that. */
    public boolean hasOutput() {
        return power > 0;
    }

    /**
     * One short, game-free rendering of this line, for a log or a test.
     *
     * <p><b>Not</b> what the overlay prints: that is a translated {@code Component}, because a player
     * reads it. This is the same fields in one unlocalised phrase, so a diagnosis can say what was shown
     * without a language file - and so a test can compare two of them.
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
        };
    }

    /**
     * Two lines describe the same thing, field for field.
     *
     * <p>Written out rather than left to the record's own {@code equals} only so the intent is visible at
     * the call site: a test that asserts "the carried overlay shows what the world overlay shows" is
     * comparing two independently built lines, and the comparison is the assertion.
     */
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

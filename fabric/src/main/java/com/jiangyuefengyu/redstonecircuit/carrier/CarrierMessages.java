package com.jiangyuefengyu.redstonecircuit.carrier;

import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.Slot;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;

import org.jetbrains.annotations.Nullable;

/**
 * <b>Every word a carried block shows a player - and the rule that no word of it may contain a structure's
 * own coordinate.</b>
 *
 * <p>No internal coordinate is ever shown: a plot coordinate is a key, not a place, and a physicalised
 * block's world position is a projection of its pose, so it is the wrong place the moment it is read. A
 * block is named by what it is, which way it faces and where it is relative to the click; log lines keep
 * every coordinate they ever had. The wording is built from pure helpers, so the rule is checkable without
 * a game.
 */
public final class CarrierMessages {

    /**
     * A sentence longer than this is not an action-bar line. Measured on the rendered text, after the
     * arguments are substituted.
     */
    public static final int MAX_ACTION_BAR_CHARS = 130;

    private CarrierMessages() {
    }

    /**
     * <b>What a carried block is called when a player has to be told which one, as a template key.</b> Its
     * name and, when the component has one, the direction it faces - answered as a key rather than as a
     * {@code Component}, so the rule can be asserted with no loaded language file.
     */
    public static String blockLabelKey(boolean facing) {
        return facing ? "message.redstonecircuit.wrench.block_named_facing"
                : "message.redstonecircuit.wrench.block_unnamed";
    }

    /** The block label as text: the name, and its facing in words when it has one. */
    public static Component blockLabel(@Nullable Component name, @Nullable Direction facing) {
        if (name == null) {
            return Component.translatable(blockLabelKey(false));
        }
        if (facing == null) {
            return name;
        }
        return Component.translatable(blockLabelKey(true), name,
                Component.translatable(faceKey(facing)));
    }

    /** The same, from a slot - the form the entry point holds. */
    public static Component blockLabel(@Nullable Slot slot, Component name) {
        return blockLabel(slot == null ? null : name, slot == null ? null : slot.facing);
    }

    /** The translation key {@link #whereRelativeTo} answers with, for a test with no language file. */
    public static String whereKey(@Nullable BlockPos from, @Nullable BlockPos to) {
        Direction direction = from == null || to == null ? null
                : com.jiangyuefengyu.redstonecircuit.logic.WrenchLinks.directionBetween(from, to)
                        .orElse(null);
        return direction == null ? "message.redstonecircuit.wrench.that_one" : directionKey(direction);
    }

    /**
     * <b>Where the other block is, relative to the one the player just clicked.</b> The two are neighbours,
     * so "the block above it" is exact where a coordinate would not be; the direction is computed in one
     * frame - for a cross-structure line, the world.
     */
    public static Component whereRelativeTo(@Nullable BlockPos from, @Nullable BlockPos to) {
        return Component.translatable(whereKey(from, to));
    }

    /** The translation key naming a direction as a place, for {@link #whereRelativeTo}. */
    public static String directionKey(Direction direction) {
        return "message.redstonecircuit.wrench.side." + direction.getName();
    }

    /** The translation key of a face, for a sentence that names one. */
    public static String faceKey(Direction direction) {
        return "message.redstonecircuit.wrench.face." + direction.getName();
    }

    /**
     * The translation key of a component's item. Mirrors {@code HostRules#itemFor}, the one place that maps
     * a component to the item it turns back into.
     */
    public static String itemKey(ComponentType type) {
        return switch (type) {
            case DUST -> "item.minecraft.redstone";
            case SUPERCONDUCTOR -> "item.redstonecircuit.superconducting_redstone";
            case REPEATER -> "item.minecraft.repeater";
            case RETAINING_REPEATER -> "item.redstonecircuit.retaining_repeater";
            case DOUBLE_REPEATER -> "item.redstonecircuit.double_repeater";
            case DOUBLE_RETAINING_REPEATER -> "item.redstonecircuit.double_retaining_repeater";
            case COMPARATOR -> "item.minecraft.comparator";
            case TORCH -> "item.minecraft.redstone_torch";
            case LEVER -> "item.minecraft.lever";
            case BUTTON -> "item.minecraft.stone_button";
            case PRESSURE_PLATE -> "item.minecraft.stone_pressure_plate";
            case CONTROLLER -> "block.redstonecircuit.controller";
            case TRANSFORMER -> "block.redstonecircuit.transformer";
            case CLOCK -> "block.redstonecircuit.clock";
            case EXTENDER -> "block.redstonecircuit.extender";
        };
    }

    /** A component's name for a message that has to name one. */
    public static Component componentName(@Nullable ComponentType type) {
        return type == null ? Component.empty() : Component.translatable(itemKey(type));
    }

    /**
     * The colour every refusal wears, so a player can tell "no" from "done" without reading. One helper
     * rather than a colour at every call site, so no branch can be missed.
     */
    public static Component refused(Component text) {
        return text.copy().withStyle(net.minecraft.ChatFormatting.RED);
    }

    /** The colour a successful line change wears. */
    public static Component accepted(Component text) {
        return text.copy().withStyle(net.minecraft.ChatFormatting.GREEN);
    }
}

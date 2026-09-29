package com.jiangyuefengyu.redstonecircuit.carrier;

import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.Slot;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;

import org.jetbrains.annotations.Nullable;

/**
 * <b>Every word a carried block shows a player - and the rule that no word of it may contain a
 * structure's own coordinate.</b>
 *
 * <h2>The author's question, which is the whole reason this class exists</h2>
 * <em>"提示会出现很长的数字是为什么？坐标吗？"</em> - and the answer is yes: P1.26's
 * {@code message.redstonecircuit.wrench.picked_elsewhere} filled four slots with a carrier's short id and
 * a <b>Sable plot coordinate</b> ({@code 20481032, 127, 20481032} - a nine-digit number, in a place the
 * player has never been and cannot walk to). The action bar got a sentence about the inside of the mod's
 * own bookkeeping in the middle of a gesture that was supposed to say "that one, not this one".
 *
 * <h2>The rule, stated once</h2>
 * <ol>
 *   <li><b>No internal coordinate is ever shown.</b> A plot coordinate is not a place; it is a key. A
 *       block is named by <b>what it is</b> ({@code Retaining Repeater}), <b>which way it faces</b>
 *       ({@code facing east}) and <b>where it is relative to the click</b> ({@code next to the block you
 *       clicked}) - never by a number;</li>
 *   <li><b>World coordinates are not shown either, and for a better reason.</b> A physicalised block's
 *       world position is a <b>projection of its pose</b>: it follows the machine as it moves and turns,
 *       so a coordinate in a message is the wrong place the moment it is read. Name + face is the only
 *       description that stays true;</li>
 *   <li><b>Log lines keep every coordinate they ever had.</b> The numbers are how a report is diagnosed,
 *       and {@code CarrierEntryTrace} / {@code CarrierLinkLedger} are where they belong. This class is the
 *       <b>player</b> half and nothing else.</li>
 * </ol>
 *
 * <h2>Why it is a class and not a few {@code Component.translatable} calls at the call site</h2>
 * The rule above is only worth having if it is checkable, and the check has to run without a game. So the
 * wording is built here out of pure helpers, and {@code CarrierMessageTextTest} renders <b>every</b>
 * {@code message.redstonecircuit.*} key of both language files through those helpers and asserts that no
 * rendered sentence matches an internal coordinate ({@code 20\d{7}}) and that none is longer than a
 * readable action-bar line. {@code CarrierInteraction} then only decides <b>which</b> sentence - never how
 * it reads.
 *
 * <p>The world's own messages ({@code InnerRedstoneInteraction}, {@code WrenchLinks}) are deliberately
 * <b>not</b> routed through here: they run on ordinary world positions, where a coordinate is a place the
 * player can stand and see, and P1.26's red line says those paths stay byte for byte as they were.
 */
public final class CarrierMessages {

    /**
     * A sentence longer than this is not an action-bar line.
     *
     * <p>Measured on the rendered text, after the arguments are substituted, and asserted by
     * {@code CarrierMessageTextTest} for every {@code message.redstonecircuit.*} key in both languages.
     * The number is deliberately generous: the longest sentence the mod needs is the cross-structure one,
     * and what the rule is really protecting against is a paragraph like P1.26's, which was 210
     * characters of coordinates and clause.
     */
    public static final int MAX_ACTION_BAR_CHARS = 130;

    private CarrierMessages() {
    }

    /**
     * <b>What a carried block is called when a player has to be told which one - as a template key.</b>
     *
     * <p>Its name, and the direction it faces when the component has one, and nothing else. That is enough
     * to tell two blocks of one machine apart in the place it matters (one is a retaining repeater facing
     * north, the other facing east), it stays true while the machine moves, and it cannot leak a key.
     *
     * <p>Answered as a <b>key</b> rather than as a {@code Component} so the rule can be asserted without a
     * running game - a unit test has no loaded language file, so a {@code Component} would be unreadable
     * there. The two production forms below build the text.
     *
     * @param facing whether the component has a facing to add
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
     * <b>Where the other block is, relative to the one the player just clicked.</b>
     *
     * <p>The one positional fact that is both true and useful on an action bar: the two blocks are
     * neighbours, so "the block above it" is unambiguous and exact, where a coordinate would be neither.
     * The direction is computed from the two positions in <b>one</b> frame - for a cross-structure line
     * that is the world, which is the frame in which "neighbours" is a fact at all.
     *
     * @param from the block the player clicked
     * @param to the other end, in the same frame as {@code from}
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
     * The translation key of a component's item, for a caller that wants the name and not a coordinate.
     *
     * <p>This mirrors {@code HostRules#itemFor}, which is the one place that maps a component to the item
     * it turns back into, and {@code CarrierMessageTextTest} asserts every key of it exists in both
     * language files - the mapping cannot rot into a missing name.
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
        };
    }

    /** A component's name for a message that has to name one. */
    public static Component componentName(@Nullable ComponentType type) {
        return type == null ? Component.empty() : Component.translatable(itemKey(type));
    }

    /**
     * The colour every refusal wears, so a player can tell "no" from "done" without reading.
     *
     * <p>One helper rather than {@code ChatFormatting.RED} at six call sites: the messages this replaces
     * styled each branch by hand, and one branch was missed - which is how a refusal came to look like a
     * success in the first place.
     */
    public static Component refused(Component text) {
        return text.copy().withStyle(net.minecraft.ChatFormatting.RED);
    }

    /** The colour a successful line change wears. */
    public static Component accepted(Component text) {
        return text.copy().withStyle(net.minecraft.ChatFormatting.GREEN);
    }
}

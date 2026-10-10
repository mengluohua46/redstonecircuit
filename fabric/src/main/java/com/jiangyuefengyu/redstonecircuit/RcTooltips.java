package com.jiangyuefengyu.redstonecircuit;

import java.util.List;

import com.jiangyuefengyu.redstonecircuit.logic.ShiftKey;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.TooltipFlag;

/**
 * Our items' descriptions, in Create's wrench shape: the default tooltip is one grey hint and nothing else, and the
 * summary and then the how-to appear only while [Shift] is held. One rule for all of them, so nine hand-written
 * tooltips cannot disagree; a requirement (the red "this tool needs the goggles" line) is a detail like the rest.
 */
public final class RcTooltips {

    /** The hint line: names the key the player has to hold. */
    public static final String HOLD_SHIFT = "tooltip.redstonecircuit.hold_shift";

    private RcTooltips() {
    }

    /** What this thing is for: the first line once [Shift] is held. */
    public static Component summary(String key) {
        return Component.translatable(key).withStyle(ChatFormatting.GRAY);
    }

    /** How to use it: visible while [Shift] is held. */
    public static Component detail(String key) {
        return Component.translatable(key).withStyle(ChatFormatting.DARK_GRAY);
    }

    /** A detail with a colour of its own. */
    public static Component detail(String key, ChatFormatting style) {
        return Component.translatable(key).withStyle(style);
    }

    /** A requirement or a warning: also a [Shift] detail, in red, because it is the line that says "this will not work". */
    public static Component required(String key) {
        return Component.translatable(key).withStyle(ChatFormatting.DARK_RED);
    }

    /**
     * The hint on its own, or - while [Shift] is held - the summary and then every detail.
     *
     * <p>The key state comes from {@link ShiftKey}, which the client refreshes once per tick, and
     * deliberately not from {@code flag.isAdvanced()}: F3+H is a debug switch, while this is a reading habit.
     * The parameter is kept because every caller in the mod has it to hand and the shape is shared with the
     * other build, where the loader's own flag can answer the question - on this one it cannot, so it goes
     * unread rather than being asked something it does not know.
     */
    public static void append(List<Component> tooltip, TooltipFlag flag, String summaryKey, Component... details) {
        if (!ShiftKey.held()) {
            tooltip.add(Component.translatable(HOLD_SHIFT).withStyle(ChatFormatting.DARK_GRAY));
            return;
        }
        tooltip.add(summary(summaryKey));
        for (Component detail : details) {
            tooltip.add(detail);
        }
    }
}

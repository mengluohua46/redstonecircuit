package com.jiangyuefengyu.redstonecircuit.carrier;

import java.util.ArrayList;
import java.util.List;

import com.jiangyuefengyu.redstonecircuit.RCConfig;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

import org.jetbrains.annotations.Nullable;

/**
 * Records the clicks the world-side listener declined, so the carrier-side line can be paired with them.
 *
 * <p>One click reaches both listeners and the bus gives their order no contract, so a sighting is matched
 * on the tick, the position and the hand rather than on "the last one". The list is cleared when the tick
 * moves and capped at {@link #MAX_SIGHTINGS}; nothing here reads the world or decides anything, and the
 * entry points write only under {@code debugLog}. It holds no level and no player, so a stop or an unload
 * cannot leave anything behind; {@link #forget()} is called on logout.
 */
public final class CarrierClickPairing {

    /** How many declines may be remembered at once: one click is two hands, and two is the ceiling. */
    public static final int MAX_SIGHTINGS = 4;

    /** One click the world side declined, as the facts that identify the delivery. */
    public record Sighting(long tick, BlockPos pos, InteractionHand hand, String item, String tool) {

        /** The same, as the reference the carrier line appends. */
        public String describe() {
            return "tick=" + tick + " hand=" + hand + " item=" + item + " tool=" + tool;
        }
    }

    private static long recordedTick = Long.MIN_VALUE;
    private static final List<Sighting> SIGHTINGS = new ArrayList<>();

    private CarrierClickPairing() {
    }

    /**
     * The world side: records one declined delivery before returning. The caller has already resolved the
     * coordinate to a live carried host, so this cannot fire for an ordinary block.
     */
    public static void noteWorldDecline(long tick, @Nullable BlockPos pos, @Nullable InteractionHand hand,
                                        @Nullable ItemStack stack) {
        if (pos == null || hand == null) {
            return;
        }
        if (tick != recordedTick) {
            SIGHTINGS.clear();
            recordedTick = tick;
        }
        for (int i = 0; i < SIGHTINGS.size(); i++) {
            Sighting seen = SIGHTINGS.get(i);
            if (seen.pos().equals(pos) && seen.hand() == hand) {
                SIGHTINGS.set(i, new Sighting(tick, pos.immutable(), hand, itemOf(stack), toolOf(stack)));
                return;
            }
        }
        if (SIGHTINGS.size() >= MAX_SIGHTINGS) {
            // Evict rather than refuse: the newest delivery is the one being read.
            SIGHTINGS.remove(0);
        }
        SIGHTINGS.add(new Sighting(tick, pos.immutable(), hand, itemOf(stack), toolOf(stack)));
    }

    /**
     * The carrier side: what the world side saw of this very click, as one bracket for its own line. Both
     * lines of one click carry the same tick, so they can be paired by grep even when this note is empty.
     */
    public static String carrierNote(long tick, @Nullable BlockPos pos, @Nullable InteractionHand hand) {
        if (pos == null || hand == null) {
            return "tick=" + tick + " worldDecline=none (the event carried no position or no hand)";
        }
        for (Sighting seen : SIGHTINGS) {
            if (seen.tick() == tick && seen.pos().equals(pos) && seen.hand() == hand) {
                return "tick=" + tick + " worldDecline=[" + seen.describe() + "]";
            }
        }
        return "tick=" + tick + " worldDecline=none (no world-side decline at this tick, this position and "
                + "this hand: either the coordinate is not a structure's, or this delivery never reached the "
                + "world listener)";
    }

    /**
     * The world line, built from the same facts it records; pure so the format can be asserted offline.
     * The event's decision fields are printed to tell "the world stood down" from "the world consumed it".
     */
    public static String declineLine(String owned, String pos, long tick, @Nullable InteractionHand hand,
                                     @Nullable ItemStack stack, boolean useBlock, boolean useItem,
                                     boolean canceled) {
        return "[redstonecircuit] world insert declined: this position belongs to carrier " + owned
                + " (plot cell) - the world store is NOT written at " + pos
                + "; the carrier entry point records it in the carrier index instead"
                + " | tick=" + tick
                + " hand=" + (hand == null ? "n/a" : hand)
                + " item=" + itemOf(stack)
                + " tool=" + toolOf(stack)
                + " event[useBlock=" + useBlock + " useItem=" + useItem + " canceled=" + canceled + ']'
                + " -> " + outcome(hand, stack);
    }

    /** What the line says the world did with the click; a component in hand is the placement click. */
    private static String outcome(@Nullable InteractionHand hand, @Nullable ItemStack stack) {
        if (hand == null) {
            return "no hand on the event, so nothing was consumed";
        }
        if (stack == null || stack.isEmpty()) {
            return "the hand is empty: nothing of the carrier layer's was in it (a switch or a retrieval is "
                    + "still possible, and the carrier listener decides)";
        }
        if (toolOf(stack).equals("component")) {
            return "a redstone component is in hand: this IS the placement click, and the carrier listener "
                    + "must now write the carrier record (a `carrier insert trace` line on the same tick is "
                    + "the proof it did)";
        }
        return "the hand holds something the carrier layer has no placement for (" + toolOf(stack)
                + "), so no component was put into a block by this click";
    }

    // ------------------------------------------------------------------ the readers --

    /**
     * The held item as the trace spells it, reusing the entry trace's formatting so the decline line and
     * the carrier line can be compared column by column.
     */
    static String itemOf(@Nullable ItemStack stack) {
        return CarrierEntryTrace.itemText(stack);
    }

    /** What that item means to the carrier layer, reusing the entry trace's own classification. */
    static String toolOf(@Nullable ItemStack stack) {
        return CarrierEntryTrace.toolText(stack);
    }

    // ------------------------------------------------------------------ the seams --

    /** How many declines are remembered right now; tests only. */
    public static int rememberedForTests() {
        return SIGHTINGS.size();
    }

    /** Forgets everything, so one test cannot leak into the next, and a logout cannot keep a click; */
    public static void forget() {
        SIGHTINGS.clear();
        recordedTick = Long.MIN_VALUE;
    }

    /**
     * Writes the world-side decline line, under the same switch as every other per-click line; kept here so
     * the two halves of one click cannot drift apart in format.
     */
    public static void logDecline(String owned, BlockPos pos, long tick, @Nullable InteractionHand hand,
                                  @Nullable ItemStack stack, boolean useBlock, boolean useItem,
                                  boolean canceled) {
        if (!RCConfig.debugLog()) {
            return;
        }
        RCConfig.LOGGER.info("{}", declineLine(owned, CarrierEntryTrace.posText(pos), tick, hand, stack,
                useBlock, useItem, canceled));
    }
}

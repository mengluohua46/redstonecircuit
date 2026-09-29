package com.jiangyuefengyu.redstonecircuit.carrier;

import java.util.ArrayList;
import java.util.List;

import com.jiangyuefengyu.redstonecircuit.RCConfig;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

import org.jetbrains.annotations.Nullable;

/**
 * <b>One click, two listeners: which of them saw it, written down so the next log can be paired.</b>
 *
 * <h2>The question this class exists to answer, and why the previous logs could not</h2>
 * A click on a coordinate a structure owns is delivered to <b>two</b> independent listeners - the world's
 * {@code InnerRedstoneInteraction} and the carrier's {@code CarrierInteraction} - and P1.26 made the world
 * one stand down without consuming the click (see {@link CarrierClickPriority}). That left the two most
 * different possible worlds looking identical in the log:
 *
 * <pre>
 *   (a) the click reached the world listener, which declined, and then reached the carrier listener;
 *   (b) the click reached the world listener, which declined, and the carrier listener never ran.
 * </pre>
 *
 * <p>In both, {@code world insert declined} appears. In neither does anything say whether the second
 * listener was reached, and the author's log proved the gap is real: eighteen declines, and not one of them
 * could be matched to a carrier-side line, because the decline did not print <b>what was in hand</b> either.
 * So "did the placement click arrive, and where did it go" had no answer in the log at all.
 *
 * <h2>What is recorded, and the bound on it</h2>
 * The world side writes one {@link Sighting} per <b>hand</b> per tick - a right click dispatches once for
 * the main hand and once for the off hand, so at most two - and the carrier side reads back the sighting
 * for its own hand at its own tick and position. The record is:
 *
 * <ul>
 *   <li><b>bounded</b>: the list is cleared the moment the tick number moves, and truncated to
 *       {@link #MAX_SIGHTINGS} entries, so a pathological click storm cannot grow it;</li>
 *   <li><b>not a decision</b>: nothing here reads the world or touches a record. It is written to and read
 *       from by the two entry points, and the click's meaning is decided exactly where it always was;</li>
 *   <li><b>inert when the diagnostics are off</b>: the two entry points ask for a note only under
 *       {@code debugLog}, and the note is a string built from facts they already hold;</li>
 *   <li><b>safe when the world stops</b>: it holds no level, no player and no reference into a structure -
 *       two primitives and a short list - so a stop, an unload or a removed sub-level cannot leave anything
 *       behind. {@link #forget()} is called on logout for the same reason the other ledgers are.</li>
 * </ul>
 *
 * <h2>Why the two lines are paired by (tick, position, hand) rather than by "the last one"</h2>
 * The world listener and the carrier listener are registered on one bus with no explicit priority, so the
 * order between them is a fact about registration rather than a contract - and a pairing that assumed
 * "the world always went first" would silently report "the carrier never saw it" on the one click where
 * that was not true. The sighting is therefore matched on the three facts that identify a delivery - the
 * tick, the position and the hand - and every line carries the tick, so a reader can pair the two lines in
 * a log even where the note could not be written.
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
     * <b>The world side: this click was declined, and here is everything needed to pair it.</b>
     *
     * <p>Called once per delivery the world path stands down from, before it returns. The caller has
     * already resolved the coordinate to a live carried host, so nothing here can fire for a click on an
     * ordinary block.
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
            // The ceiling holds by eviction rather than by refusing to record: the newest delivery is the
            // one a reader is looking at, and a record that stops recording would turn this diagnostic
            // back into the silence it was written to remove.
            SIGHTINGS.remove(0);
        }
        SIGHTINGS.add(new Sighting(tick, pos.immutable(), hand, itemOf(stack), toolOf(stack)));
    }

    /**
     * <b>The carrier side: what the world side saw of this very click, as one bracket for its own line.</b>
     *
     * <p>Three answers, and they are three different facts:
     *
     * <ul>
     *   <li>{@code worldDecline=[tick=… hand=… item=… tool=…]} - the world listener saw the same delivery
     *       and stood down, so this click reached both listeners;</li>
     *   <li>{@code worldDecline=none (tick=…)} - nothing was declined at this tick and position for this
     *       hand. Read with the tick on both lines, that says the world side did not own this delivery:
     *       either it is an ordinary world coordinate, or the click never reached the world listener
     *       either;</li>
     *   <li>the same, always, with the tick: the two lines of one click name one tick number, so they can
     *       be paired by grep even when the note itself cannot be written.</li>
     * </ul>
     *
     * @param tick the level's game time at the delivery, so both lines of one click carry one number
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
     * <b>The world line, built from the same facts it records.</b>
     *
     * <p>Pure on purpose: the format is asserted offline, because the one thing that made P1.26's log
     * unreadable was a line that did not carry the held item. The event's two decision fields are printed
     * as well - they are what the world side does to the click after declining, and a reader comparing two
     * logs needs them to tell "the world stood down" from "the world consumed it".
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

    /**
     * What the line says the world did with the click, which is the whole point of the line.
     *
     * <p>A component in hand was the case that could not be read before: the author's placement click and
     * a wrench click produced the same text. Now the item is on the line and the sentence says what it
     * means for this one.
     */
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
     * The held item as the trace spells it, reusing the entry trace's own formatting.
     *
     * <p>One implementation of "how an ItemStack is written on a diagnostic" rather than two, so the
     * decline line and the carrier line can be compared column by column.
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
     * Writes the world-side decline line, under the same switch as every other per-click line.
     *
     * <p>Kept here rather than at the call site so the two halves of one click - this line and the carrier's
     * note - are produced by one class and cannot drift apart in format.
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

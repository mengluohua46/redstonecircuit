package com.jiangyuefengyu.redstonecircuit.carrier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.Slot;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * <b>P1.29, defect A: one click, two listeners, and a log that could not say which of them saw it.</b>
 *
 * <h2>The report, and the two facts the previous log kept apart badly</h2>
 * The author's answer to "what were you holding" was <b>a retaining repeater</b>, shift-right-clicking a
 * physicalised block, and the previous round's log contained:
 *
 * <pre>
 *   carrier entry trace   x45   - gesture=NONE 15, SWITCH 3, ROD 1, WRENCH 8, PLACEMENT 0
 *   world insert declined x18   - and not one of them named the item in hand
 *   carrier insert trace  x0
 * </pre>
 *
 * <p>So the log could not separate <em>"the click reached the carrier listener and the rule dropped it"</em>
 * from <em>"the click never reached the carrier listener"</em>, and neither world line could be matched to a
 * carrier line: the decline line did not print the hand or the item, and the carrier line did not print the
 * tick. Both facts are fixed here, and both are asserted offline because the game is not what decides them.
 *
 * <h2>What is pinned</h2>
 * <ol>
 *   <li><b>the pairing record</b>: a decline is remembered per (tick, position, hand), so a carrier line can
 *       say whether the world side declined this very delivery - and a different tick, position or hand is a
 *       different delivery, not a match;</li>
 *   <li><b>the same delivery answered in both directions</b>: both lines carry the tick, so a reader can
 *       pair them in a file even without the note;</li>
 *   <li><b>the line's own shape</b>: the held item and the hand are on the world line, which is what the
 *       P1.26 log was missing;</li>
 *   <li><b>the bound</b>: the record is capped and is cleared when the tick moves, so a click storm cannot
 *       grow it;</li>
 *   <li><b>the sentence the line writes about a component in hand</b>: it says this IS the placement click,
 *       because "the world declined a click holding a redstone component" is the one fact that tells a
 *       placement apart from a wrench click.</li>
 * </ol>
 */
class CarrierClickPairingTest {

    private static final BlockPos PLOT = new BlockPos(20481032, 128, 20481032);

    @BeforeEach
    void reset() {
        CarrierClickPairing.forget();
    }

    // ------------------------------------------------------------- the pairing --

    @Nested
    @DisplayName("the two halves of one click")
    class TheTwoHalves {

        @Test
        @DisplayName("the carrier line says the world side declined this very delivery")
        void theCarrierLineFindsTheDecline() {
            CarrierClickPairing.noteWorldDecline(1200L, PLOT, net.minecraft.world.InteractionHand.MAIN_HAND,
                    null);

            String note = CarrierClickPairing.carrierNote(1200L, PLOT,
                    net.minecraft.world.InteractionHand.MAIN_HAND);

            assertTrue(note.contains("worldDecline=["), note);
            assertTrue(note.contains("tick=1200"), "the tick is on BOTH lines, which is what makes them "
                    + "pair-able even when this note cannot be written: " + note);
            assertTrue(note.contains("hand=MAIN_HAND"), note);
        }

        @Test
        @DisplayName("a different hand, tick or position is a different delivery, not a match")
        void aDifferentDeliveryIsNotAMatch() {
            CarrierClickPairing.noteWorldDecline(1200L, PLOT, net.minecraft.world.InteractionHand.MAIN_HAND,
                    null);

            assertTrue(CarrierClickPairing.carrierNote(1200L, PLOT,
                    net.minecraft.world.InteractionHand.OFF_HAND).contains("worldDecline=none"),
                    "the off hand is its own delivery: a right click dispatches twice, and the two halves "
                            + "of one click are exactly what this record exists to tell apart");
            assertTrue(CarrierClickPairing.carrierNote(1201L, PLOT,
                    net.minecraft.world.InteractionHand.MAIN_HAND).contains("worldDecline=none"),
                    "a different tick is a different click");
            assertTrue(CarrierClickPairing.carrierNote(1200L, PLOT.above(),
                    net.minecraft.world.InteractionHand.MAIN_HAND).contains("worldDecline=none"),
                    "and so is a different position");
        }

        @Test
        @DisplayName("both hands of one click are remembered, one entry each")
        void bothHandsAreRemembered() {
            CarrierClickPairing.noteWorldDecline(7L, PLOT, net.minecraft.world.InteractionHand.MAIN_HAND,
                    null);
            CarrierClickPairing.noteWorldDecline(7L, PLOT, net.minecraft.world.InteractionHand.OFF_HAND,
                    null);

            assertEquals(2, CarrierClickPairing.rememberedForTests());
            assertTrue(CarrierClickPairing.carrierNote(7L, PLOT,
                    net.minecraft.world.InteractionHand.MAIN_HAND).contains("worldDecline=["));
            assertTrue(CarrierClickPairing.carrierNote(7L, PLOT,
                    net.minecraft.world.InteractionHand.OFF_HAND).contains("worldDecline=["));
        }

        @Test
        @DisplayName("a click with no decline is answered `none`, which is a statement and not an absence")
        void aClickNobodyDeclinedSaysSo() {
            String note = CarrierClickPairing.carrierNote(99L, PLOT,
                    net.minecraft.world.InteractionHand.MAIN_HAND);
            assertTrue(note.contains("worldDecline=none"), note);
            assertTrue(note.contains("tick=99"), "the tick is still printed, so this line can be paired "
                    + "with a world line that arrives later in the same tick: " + note);
            assertFalse(note.contains("worldDecline=["), note);
        }
    }

    // ----------------------------------------------------------------- the bound --

    @Nested
    @DisplayName("the record is bounded and forgettable")
    class TheBound {

        @Test
        @DisplayName("the list never grows past its ceiling")
        void theCeilingHolds() {
            for (int i = 0; i < CarrierClickPairing.MAX_SIGHTINGS * 4; i++) {
                CarrierClickPairing.noteWorldDecline(5L, PLOT.offset(i, 0, 0),
                        net.minecraft.world.InteractionHand.MAIN_HAND, null);
            }
            assertTrue(CarrierClickPairing.rememberedForTests() <= CarrierClickPairing.MAX_SIGHTINGS,
                    "the ceiling holds: " + CarrierClickPairing.rememberedForTests());
            assertTrue(CarrierClickPairing.MAX_SIGHTINGS <= 4,
                    "and it is small: one click is two hands, so a handful is already generous");
        }

        @Test
        @DisplayName("a new tick clears the record, so yesterday's click cannot be paired with today's")
        void aNewTickClearsTheRecord() {
            CarrierClickPairing.noteWorldDecline(5L, PLOT, net.minecraft.world.InteractionHand.MAIN_HAND,
                    null);
            assertEquals(1, CarrierClickPairing.rememberedForTests());

            CarrierClickPairing.noteWorldDecline(6L, PLOT.above(),
                    net.minecraft.world.InteractionHand.MAIN_HAND, null);

            assertEquals(1, CarrierClickPairing.rememberedForTests(),
                    "the moved tick cleared the old one and recorded the new one");
            assertTrue(CarrierClickPairing.carrierNote(5L, PLOT,
                    net.minecraft.world.InteractionHand.MAIN_HAND).contains("worldDecline=none"),
                    "and the old tick no longer matches anything");
        }

        @Test
        @DisplayName("forget empties it, which is what a logout calls")
        void forgetEmptiesIt() {
            CarrierClickPairing.noteWorldDecline(5L, PLOT, net.minecraft.world.InteractionHand.MAIN_HAND,
                    null);
            CarrierClickPairing.forget();
            assertEquals(0, CarrierClickPairing.rememberedForTests());
            assertTrue(CarrierClickPairing.carrierNote(5L, PLOT,
                    net.minecraft.world.InteractionHand.MAIN_HAND).contains("worldDecline=none"));
        }

        @Test
        @DisplayName("nothing to record answers without recording: no position, no hand, no entry")
        void nothingToRecordIsNotAnEntry() {
            CarrierClickPairing.noteWorldDecline(5L, null,
                    net.minecraft.world.InteractionHand.MAIN_HAND, null);
            CarrierClickPairing.noteWorldDecline(5L, PLOT, null, null);
            assertEquals(0, CarrierClickPairing.rememberedForTests(),
                    "a delivery with no position or no hand identifies nothing, so remembering it would "
                            + "only be a way of matching the wrong click later");
        }
    }

    // --------------------------------------------------------------- the line --

    @Nested
    @DisplayName("the world's decline line, which is the line P1.26 could not be read from")
    class TheLine {

        @Test
        @DisplayName("it carries the hand, the item and the tick - the three facts the old line was missing")
        void theLineCarriesWhatWasMissing() {
            String line = CarrierClickPairing.declineLine("s:650a149c local=20481032, 128, 20483080 "
                            + "world=-6, -60, -12", "20481032, 128, 20483080", 4242L,
                    net.minecraft.world.InteractionHand.MAIN_HAND, null, false, false, false);

            assertTrue(line.startsWith("[redstonecircuit] world insert declined: "), line);
            assertTrue(line.contains("this position belongs to carrier s:650a149c"), line);
            assertTrue(line.contains("the world store is NOT written at 20481032, 128, 20483080"), line);
            assertTrue(line.contains("tick=4242"), "so the carrier line of the same click carries the same "
                    + "number: " + line);
            assertTrue(line.contains("hand=MAIN_HAND"), line);
            assertTrue(line.contains("item=n/a"), "a null stack is `n/a`, which is not an empty hand: " + line);
            assertTrue(line.contains("tool=n/a"), line);
            assertTrue(line.contains("useBlock=false"), "the event fields the world side set: " + line);
            assertTrue(line.contains("useItem=false"), line);
            assertTrue(line.contains("canceled=false"), "and that it did NOT cancel, which is what leaves "
                    + "the carrier listener its delivery: " + line);
            assertFalse(line.contains("\n"), "one line, always");
        }

        @Test
        @DisplayName("with a null hand it says so rather than inventing the main hand")
        void aNullHandIsNamed() {
            String line = CarrierClickPairing.declineLine("s:1 local=1, 2, 3 world=4, 5, 6", "1, 2, 3", 1L,
                    null, null, false, false, false);
            assertTrue(line.contains("hand=n/a"), line);
            assertTrue(line.contains("no hand on the event, so nothing was consumed"), line);
        }

        @Test
        @DisplayName("the sentence about the click says what the world side did with it")
        void theSentenceExplainsTheDecision() {
            String owned = "s:1 local=1, 2, 3 world=4, 5, 6";

            String empty = CarrierClickPairing.declineLine(owned, "1, 2, 3", 1L,
                    net.minecraft.world.InteractionHand.MAIN_HAND, null, false, false, false);
            assertTrue(empty.contains("the hand is empty"), empty);

            String other = CarrierClickPairing.declineLine(owned, "1, 2, 3", 1L,
                    net.minecraft.world.InteractionHand.MAIN_HAND, null, false, false, false);
            assertFalse(other.contains("this IS the placement click"),
                    "a null/empty stack is not a placement: " + other);
        }
    }

    // -------------------------------------------------------- the action fields --

    @Nested
    @DisplayName("what the world side does to the event")
    class TheActionFields {

        @Test
        @DisplayName("an owner means both action fields are FALSE and the event is NOT cancelled")
        void anOwnerStandsTheActionDownWithoutCancelling() {
            CarrierClickPriority.Owned owned = new CarrierClickPriority.Owned(
                    CarrierId.subLevel(java.util.UUID.fromString("00000000-0000-0000-0000-00000000c1a9")),
                    PLOT, new BlockPos(-6, -60, -12));

            CarrierClickPriority.StandDown standDown = CarrierClickPriority.standDown(owned);

            assertFalse(standDown.useBlock(), "vanilla's own block use must not run there");
            assertFalse(standDown.useItem(), "and neither must the item's useOn, which is what would PLACE "
                    + "the retaining repeater in the plot cell");
            assertFalse(standDown.canceled(), "AND NOT CANCELLED: a cancelled RightClickBlock is not "
                    + "delivered to a listener registered with the default receiveCanceled=false, so "
                    + "cancelling here would delete the carrier delivery this gate exists to hand over");
        }

        @Test
        @DisplayName("no owner leaves the world's own answer alone")
        void noOwnerChangesNothing() {
            CarrierClickPriority.StandDown standDown = CarrierClickPriority.standDown(null);
            assertTrue(standDown.useBlock(),
                    "a coordinate no structure owns is answered exactly as the event arrived");
            assertTrue(standDown.useItem());
            assertFalse(standDown.canceled());
        }

        @Test
        @DisplayName("the two outcomes are genuinely different, so the test above can fail")
        void theTwoOutcomesDiffer() {
            CarrierClickPriority.Owned owned = new CarrierClickPriority.Owned(
                    CarrierId.subLevel(java.util.UUID.randomUUID()), PLOT, PLOT);
            assertNotEquals(CarrierClickPriority.standDown(owned),
                    CarrierClickPriority.standDown(null));
        }
    }

    // ------------------------------------------------------------ what a name is --

    @Test
    @DisplayName("the held item is written with the same spelling the entry trace uses")
    void theItemSpellingIsShared() {
        // One implementation of "how an ItemStack is written on a diagnostic", so the decline line and the
        // carrier line can be compared column by column. A null stack is the only case a bare JVM can
        // build (an ItemStack needs the item registry), and it is the case the log has to name anyway.
        assertEquals(CarrierEntryTrace.itemText(null), CarrierClickPairing.itemOf(null));
        assertEquals(CarrierEntryTrace.toolText(null), CarrierClickPairing.toolOf(null));
        assertEquals("n/a", CarrierClickPairing.itemOf(null));
        assertNotNull(CarrierClickPairing.toolOf(null));
    }

    @Test
    @DisplayName("a Slot is not needed to write the line, and the line never prints a coordinate twice")
    void theLineDoesNotInventFacts() {
        String line = CarrierClickPairing.declineLine("s:abc local=1, 2, 3 world=4, 5, 6", "1, 2, 3", 42L,
                net.minecraft.world.InteractionHand.OFF_HAND, null, false, false, false);
        // The position appears exactly twice: once inside the owned description (which is the caller's own
        // text) and once as the position the world store was NOT written at. The line adds no third copy.
        int at = 0;
        int count = 0;
        while ((at = line.indexOf("1, 2, 3", at)) >= 0) {
            count++;
            at += "1, 2, 3".length();
        }
        assertEquals(2, count, "the line adds no copy of the coordinate: " + line);
    }

    @Test
    @DisplayName("a Slot's own describe() is not what a player reads - asserted where the two are compared")
    void theLogAndThePlayerNameAreDifferentStrings() {
        Slot repeater = new Slot(ComponentType.RETAINING_REPEATER);
        repeater.facing = Direction.NORTH;

        String log = com.jiangyuefengyu.redstonecircuit.logic.WrenchLinks.describe(repeater);
        assertTrue(log.startsWith("RETAINING_REPEATER"),
                "the log keeps the enum constant, which is what a report is grepped against: " + log);
        assertNotEquals(log, CarrierMessages.itemKey(ComponentType.RETAINING_REPEATER),
                "and the player's name is a translation key, so the two can never be the same string");
    }
}

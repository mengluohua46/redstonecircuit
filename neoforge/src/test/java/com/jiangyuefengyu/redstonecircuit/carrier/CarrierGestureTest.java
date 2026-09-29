package com.jiangyuefengyu.redstonecircuit.carrier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.Slot;
import com.jiangyuefengyu.redstonecircuit.logic.CarriedSwitch;
import com.jiangyuefengyu.redstonecircuit.logic.CarriedTool;
import com.jiangyuefengyu.redstonecircuit.logic.InnerSwitches;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>"Which gesture is this click" - one rule, and the author's bug is a row of its table.</b>
 *
 * <h2>What went wrong</h2>
 * The carrier side asked "did I recognise a component in this hand?" and treated <em>no</em> as "the hand
 * is empty". A wrench is not a component, so a click holding a wrench answered <em>no</em> and the switch
 * gesture thought the hand was bare. Vanilla delivers one right-click twice - main hand and off hand, same
 * tick, same position - so the author's log has, side by side, a rod turn on the main hand and a repeater
 * delay step on an empty off hand:
 *
 * <pre>
 *   hand=MAIN_HAND item=redstonecircuit:rotation_rod -&gt; rod ... -&gt; input side east
 *   hand=OFF_HAND  item=empty                        -&gt; switch adjusted RETAINING_REPEATER ...
 * </pre>
 *
 * <p>The world side never had this: {@code InnerRedstoneInteraction} asks {@code stack.isEmpty()} before
 * it offers a click to {@code InnerSwitches}. The rule is now asked in one place, {@link CarrierGesture},
 * in a form a unit test can drive - four booleans and the sneak state - and this class is that table.
 *
 * <h2>The two claims</h2>
 * <ol>
 *   <li><b>Only an actually empty hand reaches the switch gesture.</b> Every other content of the hand -
 *       a wrench, the rod, a component, an ordinary block, a sword - is classified as its own gesture or
 *       as nobody's, and never as {@link CarrierGesture.Kind#SWITCH}.</li>
 *   <li><b>The sneak state does not change which gesture an item means.</b> A tool is a tool either way,
 *       a component is a placement either way, and an item nobody owns is nobody's either way - which is
 *       what makes this rule the same one the world side asks.</li>
 * </ol>
 */
class CarrierGestureTest {

    private static final CarrierId CARRIER =
            CarrierId.contraption(UUID.fromString("00000000-0000-0000-0000-000000002501"));

    /** The world store, as a map - the same stand-in every carrier test uses. */
    private static final class FakeWorld implements CarriedHostIndex.SlotWorld {

        private final Map<Long, Slot> slots = new HashMap<>();

        @Override
        @Nullable
        public Slot get(BlockPos pos) {
            return slots.get(pos.asLong());
        }

        @Override
        public void set(BlockPos pos, @Nullable Slot slot) {
            if (slot == null) {
                slots.remove(pos.asLong());
            } else {
                slots.put(pos.asLong(), slot);
            }
        }
    }

    private final CarriedHostIndex index = new CarriedHostIndex();
    private final FakeWorld world = new FakeWorld();

    // ------------------------------------------------------------- the table --

    /**
     * <b>The rule, one row per thing a hand can hold.</b>
     *
     * <p>The "{@code notEmpty}" rows are the fix: before it, every one of them answered
     * {@code SWITCH} when the sneak key was not held, which is exactly "非空手也能调".
     */
    @Test
    @DisplayName("only an empty hand is classified as the switch gesture")
    void onlyAnEmptyHandReachesTheSwitch() {
        record Row(String what, boolean wrench, boolean rod, boolean component, boolean empty,
                   CarrierGesture.Kind expected) {
        }

        Row[] table = {
                new Row("a wrench", true, false, false, false, CarrierGesture.Kind.WRENCH),
                new Row("the rotation rod", false, true, false, false, CarrierGesture.Kind.ROD),
                new Row("a redstone component", false, false, true, false,
                        CarrierGesture.Kind.PLACEMENT),
                new Row("an ordinary block or a sword", false, false, false, false,
                        CarrierGesture.Kind.NONE),
                new Row("nothing at all", false, false, false, true, CarrierGesture.Kind.SWITCH),
        };

        for (Row row : table) {
            // Not sneaking: the plain click, which is where the switch gesture lives.
            CarrierGesture.Kind plain = CarrierGesture.of(row.wrench(), row.rod(), row.component(),
                    row.empty(), false);
            assertEquals(row.expected(), plain, "not sneaking, holding " + row.what());

            // And the property the whole class exists for, stated once rather than per row.
            if (row.empty()) {
                assertTrue(CarrierGesture.requiresEmptyHand(plain),
                        "the empty hand's gesture may only be reached by an empty hand");
            } else {
                assertFalse(plain == CarrierGesture.Kind.SWITCH,
                        "holding " + row.what() + " must never be classified as a bare-hand switch");
            }
        }
    }

    /**
     * The sneak state chooses between gestures that exist; it never turns an item into another item's
     * gesture.
     *
     * <p>This is the half of the author's report that made the bug visible - a rod click while sneaking is
     * still a rod click, and it must not also be a retrieval just because the sneak key was down.
     */
    @Test
    @DisplayName("sneaking never changes which gesture an item means")
    void sneakingNeverReclassifiesAnItem() {
        assertEquals(CarrierGesture.Kind.WRENCH,
                CarrierGesture.of(true, false, false, false, true),
                "a wrench is a wrench while sneaking too - shift is the wrench's own second gesture");
        assertEquals(CarrierGesture.Kind.ROD, CarrierGesture.of(false, true, false, false, true));
        assertEquals(CarrierGesture.Kind.PLACEMENT, CarrierGesture.of(false, false, true, false, true),
                "a component is a placement either way; the sneak state decides which half");
        assertEquals(CarrierGesture.Kind.NONE, CarrierGesture.of(false, false, false, false, true),
                "an item nobody owns stays nobody's, sneaking or not");

        // The one thing the sneak state does decide: which half of an empty hand's gesture it is.
        assertEquals(CarrierGesture.Kind.SWITCH, CarrierGesture.of(false, false, false, true, false),
                "empty + plain = work what is inside the block");
        assertEquals(CarrierGesture.Kind.PLACEMENT, CarrierGesture.of(false, false, false, true, true),
                "empty + shift = take it back out (CarriedInsert owns that rule)");
        assertEquals(CarrierGesture.Kind.SWITCH, CarrierGesture.emptyHandGesture(false));
        assertEquals(CarrierGesture.Kind.PLACEMENT, CarrierGesture.emptyHandGesture(true));
    }

    /** A tool beats a component: a wrench in a hand that also "recognises" a component is still a tool. */
    @Test
    @DisplayName("the order of the rule is the order it is read: a tool owns the click")
    void aToolOwnsTheClick() {
        assertEquals(CarrierGesture.Kind.WRENCH,
                CarrierGesture.of(true, false, true, false, false),
                "the wrench branch is read first, whatever else the stack would answer");
        assertEquals(CarrierGesture.Kind.ROD, CarrierGesture.of(false, true, true, false, false));
    }

    /** A hand that holds nothing is never a tool and never a component: the three facts are consistent. */
    @Test
    @DisplayName("an empty hand is neither a tool nor a component, and is exactly one gesture")
    void anEmptyHandIsExactlyOneGesture() {
        for (boolean sneaking : new boolean[] {false, true}) {
            CarrierGesture.Kind kind = CarrierGesture.of(false, false, false, true, sneaking);
            assertTrue(kind == CarrierGesture.Kind.SWITCH || kind == CarrierGesture.Kind.PLACEMENT,
                    "an empty hand is one of the two empty-hand gestures, never " + kind);
            assertTrue(CarrierGesture.requiresEmptyHand(kind));
        }
    }

    // ------------------------------------------- the rule against the actions --

    /**
     * The classification is not cosmetic: an empty host and a host that is not ours are still answered by
     * the actions, and the switch action refuses a component it cannot work.
     *
     * <p>This is the seam the classification feeds. It exists so "the classification changed" and "the
     * action refused" cannot be confused in the log or in a test.
     */
    @Test
    @DisplayName("the switch action refuses by its own rule, whatever the classification said")
    void theSwitchActionStillOwnsItsOwnRefusals() {
        BlockPos local = new BlockPos(1, 0, 1);
        Slot slot = new Slot(ComponentType.DUST);
        world.set(new BlockPos(600, 64, 600), slot);
        index.claim(CARRIER, local, new BlockPos(600, 64, 600), world);

        // A wire is a component, so the classification says PLACEMENT (if in hand) - but the switch action
        // itself must also refuse it, because "not in hand" and "in hand" are answered by different code.
        assertFalse(CarriedSwitch.workable(index, CARRIER, local),
                "dust has no bare-hand action, and the action says so on its own");
        assertSame(CarriedSwitch.Action.REFUSED_POSITION,
                CarriedSwitch.apply(index, CARRIER, new BlockPos(9, 0, 9)),
                "and a coordinate the structure carries nothing at is still the world's click");

        // The wrench on the same host still works: the classification is not what refuses it.
        assertTrue(CarriedTool.select(index, CARRIER, local) instanceof CarriedTool.Action.Selected);
    }

    /** The rotation rod's own refusal for a component with no orientation is untouched by this rule. */
    @Test
    @DisplayName("the rod still refuses a component that has no orientation")
    void theRodStillRefusesWire() {
        BlockPos local = new BlockPos(2, 0, 2);
        Slot slot = new Slot(ComponentType.DUST);
        world.set(new BlockPos(602, 64, 602), slot);
        index.claim(CARRIER, local, new BlockPos(602, 64, 602), world);

        assertEquals(CarriedTool.Action.NoDirection.class,
                CarriedTool.turn(index, CARRIER, local, Direction.UP).getClass(),
                "a piece of dust has no input side to turn");
    }

    // ==================================================================================================
    // P1.25: the SECOND half of the same defect - the other hand.
    //
    // The table above is about ONE hand, and every row of it was already true after P1.24 while the
    // author's bug was still there. Vanilla delivers one right-click twice, and the second delivery
    // comes from a hand that genuinely IS empty:
    //
    //   hand=MAIN_HAND item=redstonecircuit:redstone_wrench x1 tool=wrench -> MATCH … clear …
    //   hand=OFF_HAND  item=empty                            tool=bare hand -> MATCH
    //   carrier s:aa684475: switch adjusted RETAINING_REPEATER out=0 in=east -> …
    //
    // So the criterion has to be asked over BOTH hands. Everything below is that, driven as a table.
    // ==================================================================================================

    /** The five things a hand can hold, for the two-hand table. */
    private static final CarrierGesture.Hold WRENCH = new CarrierGesture.Hold(true, false, false, false);
    private static final CarrierGesture.Hold ROD = new CarrierGesture.Hold(false, true, false, false);
    private static final CarrierGesture.Hold COMPONENT = new CarrierGesture.Hold(false, false, true, false);
    private static final CarrierGesture.Hold PLAIN = CarrierGesture.Hold.SOMETHING_ELSE;
    private static final CarrierGesture.Hold EMPTY = CarrierGesture.Hold.NOTHING;

    private static CarrierGesture.Hold[] holds() {
        return new CarrierGesture.Hold[] {WRENCH, ROD, COMPONENT, PLAIN, EMPTY};
    }

    private static String nameOf(CarrierGesture.Hold hold) {
        if (hold == WRENCH) {
            return "a wrench";
        }
        if (hold == ROD) {
            return "the rotation rod";
        }
        if (hold == COMPONENT) {
            return "a component";
        }
        if (hold == EMPTY) {
            return "nothing";
        }
        return "a plain block";
    }

    /**
     * <b>THE RULE, AS THE AUTHOR FOUND IT BROKEN: the switch gesture requires BOTH hands to be empty.</b>
     *
     * <p>One row per (dispatched hand x other hand x sneak state). For each row both deliveries of one
     * click are computed - the main hand's and the off hand's - because the click is one click and the
     * rule must not depend on the order they arrive in.
     *
     * <p><b>RED before this round's fix, for every row whose dispatched hand is empty and whose other
     * hand is not</b>: those are exactly the author's two log lines, and the old rule - which read only
     * the dispatched hand - answered {@code SWITCH} for the second of them.
     */
    @Test
    @DisplayName("only two empty hands reach the switch gesture, whichever hand the delivery is for")
    void onlyTwoEmptyHandsReachTheSwitch() {
        for (CarrierGesture.Hold main : holds()) {
            for (CarrierGesture.Hold off : holds()) {
                boolean bothEmpty = main.empty() && off.empty();
                for (boolean sneaking : new boolean[] {false, true}) {
                    // ONE CLICK, TWO DELIVERIES. A click is one gesture, so exactly one delivery may own
                    // an acting gesture - and the switch half of it only exists when nothing at all is
                    // held.
                    CarrierGesture.Kind fromMain = CarrierGesture.of(main, off, sneaking);
                    CarrierGesture.Kind fromOff = CarrierGesture.of(off, main, sneaking);
                    String row = "main=" + nameOf(main) + " off=" + nameOf(off)
                            + " sneaking=" + sneaking;

                    boolean anySwitch = fromMain == CarrierGesture.Kind.SWITCH
                            || fromOff == CarrierGesture.Kind.SWITCH;
                    assertEquals(bothEmpty && !sneaking, anySwitch,
                            "a switch may only happen with two empty hands and no sneak key: " + row
                                    + " (main delivery said " + fromMain + ", off delivery said " + fromOff
                                    + ")");

                    // AND NOTHING ELSE BECOMES A SWITCH EITHER: holding any of the four things can never
                    // be classified as the bare-hand gesture, in either delivery.
                    if (!(bothEmpty && !sneaking)) {
                        assertFalse(fromMain == CarrierGesture.Kind.SWITCH || fromOff == CarrierGesture.Kind.SWITCH,
                                "holding something must never be a bare-hand switch: " + row);
                    }
                }
            }
        }
    }

    /**
     * <b>The author's two log lines, as two assertions.</b>
     *
     * <p>The main hand's delivery is the tool's; the off hand's delivery - the one with the empty stack -
     * must be nobody's. Before the fix the second one was {@code SWITCH} and stepped the repeater's delay.
     */
    @Test
    @DisplayName("a tool in one hand makes the other hand's empty delivery nobody's")
    void aToolInOneHandSuppressesTheOtherEmptyDelivery() {
        for (CarrierGesture.Hold tool : new CarrierGesture.Hold[] {WRENCH, ROD}) {
            // The tool's own delivery: the tool's gesture, exactly as before.
            assertEquals(tool == WRENCH ? CarrierGesture.Kind.WRENCH : CarrierGesture.Kind.ROD,
                    CarrierGesture.of(tool, EMPTY, false),
                    "the hand holding " + nameOf(tool) + " still owns the click as that tool");
            // The other hand's delivery: nobody's. This is the line that was missing.
            assertEquals(CarrierGesture.Kind.NONE, CarrierGesture.of(EMPTY, tool, false),
                    "an empty hand whose OTHER hand holds " + nameOf(tool)
                            + " must not be offered the switch - this is `switch adjusted` on the author's"
                            + " second trace line");
            // And the same while sneaking: not a retrieval either.
            assertEquals(CarrierGesture.Kind.NONE, CarrierGesture.of(EMPTY, tool, true),
                    "sneaking does not turn it into a retrieval either while a tool is in the other hand");
        }
    }

    /** A component or an ordinary block in one hand is the same statement, one step down the priority. */
    @Test
    @DisplayName("a component or a plain block in one hand also suppresses the other empty delivery")
    void anyHeldItemSuppressesTheOtherEmptyDelivery() {
        for (CarrierGesture.Hold held : new CarrierGesture.Hold[] {COMPONENT, PLAIN}) {
            assertEquals(CarrierGesture.Kind.NONE, CarrierGesture.of(EMPTY, held, false),
                    "an empty hand is not a bare hand while the other holds " + nameOf(held));
            assertEquals(CarrierGesture.Kind.NONE, CarrierGesture.of(EMPTY, held, true),
                    "and not a retrieval either: " + nameOf(held));
        }

        // The other way round: the hand holding the component still places, and the empty one is nobody's.
        assertEquals(CarrierGesture.Kind.PLACEMENT, CarrierGesture.of(COMPONENT, EMPTY, true));
        assertEquals(CarrierGesture.Kind.NONE, CarrierGesture.of(EMPTY, COMPONENT, true),
                "the placement belongs to the hand holding the component, not to the empty one");
    }

    /**
     * <b>PRIORITY: the tool first, then the component, then the bare hand.</b>
     *
     * <p>Every tool/component combination, both deliveries, both sneak states: the hand holding the tool
     * always gets the tool's gesture, and the other delivery is nobody's - so "拿扳手就是扳手、拿转向杆就是
     * 转向杆、拿元件就是放取" holds whatever the other hand is doing, independent of dispatch order.
     */
    @Test
    @DisplayName("a tool beats a component beats a bare hand, in either delivery order")
    void aToolBeatsAComponentBeatsABareHand() {
        for (CarrierGesture.Hold tool : new CarrierGesture.Hold[] {WRENCH, ROD}) {
            for (CarrierGesture.Hold other : new CarrierGesture.Hold[] {COMPONENT, PLAIN, EMPTY}) {
                assertEquals(tool == WRENCH ? CarrierGesture.Kind.WRENCH : CarrierGesture.Kind.ROD,
                        CarrierGesture.of(tool, other, false),
                        "the tool's own hand owns the click whatever the other hand holds");
                assertEquals(CarrierGesture.Kind.NONE, CarrierGesture.of(other, tool, false),
                        "and the other hand's delivery is nobody's, even holding " + nameOf(other));
            }
        }
        // A component beats a bare hand: it is a placement, and the empty delivery says nothing.
        assertEquals(CarrierGesture.Kind.PLACEMENT, CarrierGesture.of(COMPONENT, EMPTY, false));
        assertEquals(CarrierGesture.Kind.NONE, CarrierGesture.of(EMPTY, COMPONENT, false));
        // Two tools: each delivery is its own tool, and neither is a switch.
        assertEquals(CarrierGesture.Kind.WRENCH, CarrierGesture.of(WRENCH, ROD, false));
        assertEquals(CarrierGesture.Kind.ROD, CarrierGesture.of(ROD, WRENCH, false));
    }

    /** Which hand a delivery is for, and whether the other hand's gesture owns the click. */
    @Test
    @DisplayName("the other hand is nameable, and its gesture is what suppresses a delivery")
    void theOtherHandIsReadable() {
        assertEquals(net.minecraft.world.InteractionHand.OFF_HAND,
                CarrierGesture.otherHand(net.minecraft.world.InteractionHand.MAIN_HAND),
                "the other hand of the main hand is the off hand");
        assertEquals(net.minecraft.world.InteractionHand.MAIN_HAND,
                CarrierGesture.otherHand(net.minecraft.world.InteractionHand.OFF_HAND),
                "and the other way round - nothing here may assume the main hand came first");

        for (CarrierGesture.Hold owns : new CarrierGesture.Hold[] {WRENCH, ROD, COMPONENT}) {
            assertTrue(CarrierGesture.ownsTheClick(owns), nameOf(owns) + " owns a click of its own");
        }
        assertFalse(CarrierGesture.ownsTheClick(PLAIN),
                "a plank owns no carrier click: it is covered by the weaker 'holding something' rule");
        assertFalse(CarrierGesture.ownsTheClick(EMPTY), "an empty hand owns nothing");
    }

    /**
     * <b>② the tool's own action is what runs.</b>
     *
     * <p>The classification is the gate: the entry point dispatches on it, so the wrench's own delivery
     * must say {@code WRENCH} (and the tool action itself must work on the record), and the bare-hand switch
     * must not be reachable from a click that holds one. The end-to-end wiring is the author's own
     * acceptance action; what can be pinned offline is the dispatch and the action behind it.
     */
    @Test
    @DisplayName("a tool's delivery is the tool's gesture, and the tool's action really runs")
    void aToolsDeliveryRunsTheToolsAction() {
        BlockPos local = new BlockPos(4, 0, 4);
        Slot slot = new Slot(ComponentType.REPEATER);
        world.set(new BlockPos(604, 64, 604), slot);
        index.claim(CARRIER, local, new BlockPos(604, 64, 604), world);

        assertFalse(CarrierGesture.requiresEmptyHand(CarrierGesture.Kind.WRENCH),
                "the wrench gesture is the one that may hold something");
        assertFalse(CarrierGesture.requiresEmptyHand(CarrierGesture.Kind.ROD));

        // The tool's action on a host that holds a component: the pick works, and the switch would have
        // adjusted the same host (so "the tool ran" and "the switch ran" are different observations).
        assertTrue(CarriedTool.select(index, CARRIER, local) instanceof CarriedTool.Action.Selected,
                "the wrench's own action on this host picks it");
        assertTrue(CarriedSwitch.workable(index, CARRIER, local),
                "and the same host is workable, which is why the switch had to be gated off");

        // The gate itself, stated as the entry point reads it: the empty delivery of this same click is
        // not a switch, so `switch adjusted` cannot be produced by it.
        assertEquals(CarrierGesture.Kind.NONE, CarrierGesture.of(EMPTY, WRENCH, false));
    }

    /**
     * <b>The judgement line: {@code tool=} must name the gesture that actually ran.</b>
     *
     * <p>It used to be a boolean at the call site - {@code wrench ? "wrench" : "rotation rod"} - so the
     * author's log has {@code switch adjusted … tool=rotation rod} and {@code retrieve … tool=rotation rod}
     * on clicks where no rod existed. Two different defects (a switch that should not have happened, and a
     * label that lied about who did it) were therefore readable as one.
     */
    @Test
    @DisplayName("the action line's tool= names the gesture that ran, never a tool that did not")
    void theActionLineNamesTheGestureThatRan() {
        assertEquals("wrench", CarrierGesture.toolLabel(CarrierGesture.Kind.WRENCH));
        assertEquals("rotation rod", CarrierGesture.toolLabel(CarrierGesture.Kind.ROD));
        assertEquals("bare hand", CarrierGesture.toolLabel(CarrierGesture.Kind.SWITCH));
        assertEquals("placement", CarrierGesture.toolLabel(CarrierGesture.Kind.PLACEMENT));

        // The two labels the author's log got wrong.
        assertNotEquals("rotation rod", CarrierGesture.toolLabel(CarrierGesture.Kind.SWITCH),
                "a bare-hand switch is not a rotation rod: this is the `switch adjusted … tool=rotation rod`"
                        + " line from the author's P1.24 log");
        assertNotEquals("rotation rod", CarrierGesture.toolLabel(CarrierGesture.Kind.PLACEMENT),
                "a retrieval is not a rotation rod either: `carrier s:aa684475: retrieve … tool=rotation rod`"
                        + " is on the author's log too");

        // Five kinds, five distinct labels: no two gestures can be confused by reading the line.
        long distinct = java.util.Arrays.stream(CarrierGesture.Kind.values())
                .map(CarrierGesture::toolLabel).distinct().count();
        assertEquals(CarrierGesture.Kind.values().length, distinct,
                "every gesture has its own label, so `tool=` cannot be ambiguous");
    }

    // ------------------------------------------------ the rule against the world side --

    /**
     * <b>③ 对拍: the world side's own bare-hand gate, read from the world side's own source - and the one
     * place it now asks the carrier-side rule.</b>
     *
     * <p>It reads {@code InnerRedstoneInteraction.java} and pins the two expressions the bare-hand gesture
     * is gated on - {@code stack.isEmpty()} for the plain click and the same for the shift retrieval.
     * Widening that gate back to "no component was recognised" (the original defect) makes this red.
     *
     * <p><b>P1.34: the two rules are joined at exactly one point, and this asserts which one.</b> The world
     * side's stand-down used to be decided by <em>ownership of the coordinate</em>, which is why a piston
     * could no longer be placed on a physicalised block: every click on a structure's cell stopped vanilla's
     * own {@code Item#useOn}. It now asks the same question the carrier's entry point answers - which
     * gesture is this, and what does the clicked cell hold - and the value it asks it with is
     * {@code CarrierGesture.of(player, event.getHand())}, the <b>same</b> production form the carrier's own
     * entry point calls. That is deliberately the only carrier-side name in this file, and the sentence
     * below pins it: importing the rule is what stops the world side and the carrier side disagreeing about
     * what a click is, which is precisely the class of defect this round repairs.
     *
     * <p>A source-level assertion rather than a call, because {@code HostRules#claimsClick} takes an
     * {@code ItemStack} and {@code ItemStack.EMPTY} cannot be initialised on a bare JVM (no registries):
     * that is the same reason no other test in this project builds one. What the world side then does with
     * an empty stack is asserted on the real {@code InnerSwitches} predicate below, which does not need
     * one.
     */
    @Test
    @DisplayName("the world side still offers the bare-hand gesture on an empty stack, and asks the carrier rule once")
    void theWorldsOwnBareHandGateIsUnchanged() throws java.io.IOException {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(
                        "src/main/java/com/jiangyuefengyu/redstonecircuit/InnerRedstoneInteraction.java"))
                .replace("\r\n", "\n");

        assertTrue(source.contains("if (stack.isEmpty() && tryToggle(serverLevel, player, pos)) {"),
                "the world side offers a plain click to InnerSwitches on `stack.isEmpty()` and nothing else"
                        + " - this is the expression the carrier side has to ask over BOTH hands");
        assertTrue(source.contains("if (stack.isEmpty()) {\n            if (tryRetrieve(serverLevel, "
                        + "player, pos)) {"),
                "and the shift retrieval on the same test, in its own branch");
        assertTrue(source.contains("CarrierGesture.of(player, event.getHand())"),
                "P1.34: the world side's stand-down is decided by the gesture, and it reads that gesture "
                        + "through the carrier-side rule's own production form rather than a second copy of "
                        + "it - one definition of `which click is this` for both listeners");
        assertTrue(source.contains("CarrierClickPriority.weOwnThisClick")
                        || source.contains("CarrierClickPriority.standDown(carrierOwns,"),
                "and the decision it feeds is the carrier layer's own predicate, so `the world yields` and "
                        + "`the carrier will act` cannot be answered differently by the two listeners");
        assertFalse(source.contains("import com.jiangyuefengyu.redstonecircuit.logic.CarriedTool;"),
                "while the tool gestures themselves stay carrier-side: the world side names the gesture "
                        + "and nothing more");

        // The component set the world side gates the gesture on, through its own predicate. A bare hand
        // works a repeater / comparator / lever and does nothing at all to a wire, exactly as before.
        assertTrue(InnerSwitches.isWorkable(ComponentType.REPEATER));
        assertTrue(InnerSwitches.isWorkable(ComponentType.RETAINING_REPEATER));
        assertTrue(InnerSwitches.isWorkable(ComponentType.COMPARATOR));
        assertTrue(InnerSwitches.isWorkable(ComponentType.LEVER));
        assertFalse(InnerSwitches.isWorkable(ComponentType.DUST),
                "dust has never had a bare-hand action, on either side");
    }

    /**
     * The two rules must agree in the direction that matters: <b>whatever the carrier side calls a switch,
     * both hands are empty</b> - which is exactly what the world side's own gate plus its two-delivery hand
     * guard amount to (see {@link #theWorldsOwnBareHandGateIsUnchanged()} for the world half).
     *
     * <p>The carrier rule is deliberately <em>stricter</em> than the world side's per-hand test, and this
     * states the implication rather than an equality, so the extra requirement is visible rather than
     * assumed.
     */
    @Test
    @DisplayName("the carrier rule never invents a switch the world side would have refused")
    void theCarrierRuleIsASubsetOfTheWorldsGate() {
        boolean sawASwitch = false;
        for (CarrierGesture.Hold main : holds()) {
            for (CarrierGesture.Hold off : holds()) {
                CarrierGesture.Kind kind = CarrierGesture.of(main, off, false);
                if (kind != CarrierGesture.Kind.SWITCH) {
                    continue;
                }
                sawASwitch = true;
                assertTrue(main.empty() && off.empty(),
                        "a carrier switch means two empty hands: main=" + nameOf(main)
                                + " off=" + nameOf(off));

                assertTrue(InnerSwitches.isWorkable(ComponentType.REPEATER),
                        "and the component the world side's gate is asked about is one it does offer the "
                                + "bare-hand gesture for");
            }
        }
        assertTrue(sawASwitch, "the two-empty-hands row is the one that must reach the switch");
    }
}

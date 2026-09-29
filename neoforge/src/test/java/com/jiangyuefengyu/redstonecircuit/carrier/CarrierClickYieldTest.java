package com.jiangyuefengyu.redstonecircuit.carrier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

import com.jiangyuefengyu.redstonecircuit.RCConfig;
import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.Slot;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * <b>P1.34 commit A: the world side stands down only for a click that is really ours - so an ordinary
 * block can be placed on a physicalised structure again. P1.35 adds the second half of the same defect:
 * the SNEAK KEY is part of "is this click ours".</b>
 *
 * <h2>The author's report, and the numbers behind it</h2>
 * <em>"仍然锁不到普通方块，物理方块上面活塞都不能放了"</em> - and the log counts it precisely:
 * {@code world insert declined} appears <b>38</b> times in one session, and by held item
 *
 * <pre>
 *   19x minecraft:piston x1        the author's report: an ordinary block that cannot be placed
 *   12x minecraft:grass_block x1   the same, one ordinary block earlier
 *    8x redstonecircuit:redstone_wrench x1
 *    8x empty (bare hand)
 *    3x minecraft:redstone x25     the only family that really is ours
 * </pre>
 *
 * <p>Every one of them set {@code useBlock=false useItem=false}, because {@code CarrierClickPriority#owner}
 * answered for the <b>coordinate</b> and the world read that as "nothing may happen here". Vanilla places
 * a block through {@code Item#useOn}, which those two fields stop - so a piston could not be put onto a
 * physicalised machine at all, and neither could the grass block the author was building with.
 *
 * <h2>P1.35: the next session's log, and the row the kind alone could not tell apart</h2>
 * <em>"活塞没问题了，但是……一个新问题，拉杆放不上去了"</em> - the piston half was fixed and the next log
 * has <b>41</b> declines, grouped by hand as
 *
 * <pre>
 *   21x redstonecircuit:redstone_wrench x1   the wrench's own shift-click: still ours, as it must be
 *   19x minecraft:lever x4  tool=component   the author's report, every one of them sneaking=false
 *    1x minecraft:redstone x30 tool=component the one legitimate family - and it is sneaking=true
 * </pre>
 *
 * <p>{@code CarrierGesture.Kind#PLACEMENT} is one kind for two halves: a component in hand is a placement
 * whether or not the sneak key is down, and the sneak state only chooses which half (store it in the
 * machine's record, or let vanilla put the item <b>down</b> as a block). The predicate read the kind and
 * not the sneak state, so all 19 lever clicks were stood down - and a lever, a torch and a repeater are
 * exactly the items a player places as blocks. That is this round's row.
 *
 * <h2>What is pinned here, and how the two halves are cross-checked</h2>
 * <ol>
 *   <li><b>the predicate itself, over a table</b> - the four things a hand can hold, both sneak states, the
 *       clicked cell's own contents and all four switch combinations, asserted row by row against the
 *       answer it must give. The rule has one home ({@code weOwnThisClick}) and the table is its
 *       definition;</li>
 *   <li><b>the cross-check with the carrier's own entry point</b> - the same table is also asserted against
 *       the switch each of the four gesture paths reads in {@code CarrierInteraction}
 *       ({@code handleToolClick}, {@code handlePlacementClick}, {@code handleSwitchClick}), so "the world
 *       yields" and "the carrier will act" cannot drift apart. A change to one side that is not made to the
 *       other fails here, which is the whole reason the judgement was asked to live in one place;</li>
 *   <li><b>the wiring, over the source</b> - the world side asks that one predicate with the gesture, the
 *       sneak state and the clicked cell's state, and returns before touching the event when the answer is
 *       "not ours";</li>
 *   <li><b>the ownership half, driven as production asks it</b> - with a live plot-owning structure the
 *       cell is the structure's and the world stands down for a component <b>while sneaking</b>, a tool and
 *       a bare hand on a host that holds one; with a piston in hand, and with a component in hand and no
 *       sneak key, it does <b>not</b>;</li>
 *   <li><b>the world's own coordinates, byte for byte</b> - an ordinary block, a plot-shaped coordinate no
 *       sub-level owns, a landed host and a dead sub-level all keep the event exactly as it arrived, so no
 *       coordinate outside the three positive answers changed behaviour at all.</li>
 * </ol>
 *
 * <h2>Why the new decision is asked by name</h2>
 * The whole defect is "the world side answered about the coordinate instead of the click", and the fix is a
 * <b>new</b> answer to a question that used to have only one form. So the test asks for it
 * <b>reflectively</b>: on the previous build the method does not exist, every call answers "not ours", and
 * every assertion about a piston / a component / a bare hand fails as a plain assertion failure against a
 * tree that compiles and runs. That is the red half of this round's evidence, measured rather than
 * remembered, and every failure message these tests print says which half is which.
 *
 * <p>P1.35 adds one argument to that signature rather than a new method, and the same reflective lookup
 * carries the red half: on the P1.34 tree the ten-argument form does not exist, so every row answers
 * {@code false} - including the ones that must yield - and the table below fails row by row.
 */
class CarrierClickYieldTest {

    /** The author's own carrier, from {@code carrier s:e0677381}. */
    private static final CarrierId AUTHOR =
            CarrierId.subLevel(UUID.fromString("e0677381-0000-0000-0000-000000000000"));

    /** A cell of that structure: the coordinate the piston was aimed at, one block along from the log's. */
    private static final BlockPos PLOT = new BlockPos(20481034, 127, 20481032);

    /** A second cell of the same structure, used for the empty-record state. */
    private static final BlockPos EMPTY_CELL = new BlockPos(20481033, 127, 20481032);

    /** Where the author's pose put those cells: {@code world=-2, -60, -10} in the log. */
    private static final BlockPos VISIBLE = new BlockPos(-2, -60, -10);

    /** The author's own {@code origin=} from the {@code filed the carrier host} line. */
    private static final BlockPos ORIGIN = new BlockPos(-1, -60, -10);

    /** An ordinary world block, nowhere near any structure's plot region. */
    private static final BlockPos ORDINARY = new BlockPos(-1, -60, -10);

    /** A plot-shaped coordinate no sub-level owns. */
    private static final BlockPos OTHER_PLOT = new BlockPos(20481032, 128, 20524040);

    private static final ResourceKey<Level> DIMENSION = ResourceKey.create(Registries.DIMENSION,
            ResourceLocation.parse("redstonecircuit:carrierclickyieldtest"));

    private final CarriedHostIndex index = new CarriedHostIndex();
    private final Map<Long, Slot> world = new HashMap<>();
    private final Map<Long, CarrierId> plotOwners = new HashMap<>();
    private final CarrierProjection table = new CarrierProjection();

    /** Every carrier the liveness question was asked about, so "it was asked" is assertable. */
    private final List<CarrierId> asked = new ArrayList<>();

    /** Which carriers count as alive; a sub-level that has gone is simply left out. */
    private Predicate<CarrierId> liveness = carrier -> true;

    private CarrierStore.CarrierSource previousSource;

    @BeforeEach
    void arm() {
        CarrierClickPairing.forget();
        previousSource = CarrierStore.source();
        CarrierStore.install(new BareSource());
        CarrierStore.projection().clearAll();
        table.begin(DIMENSION, true);
    }

    @AfterEach
    void disarm() {
        CarrierStore.projection().clearAll();
        CarrierStore.install(previousSource);
        CarrierClickPairing.forget();
    }

    /** A source that can answer nothing: the shape a build without the compat half has. */
    private static final class BareSource implements CarrierStore.CarrierSource {

        @Override
        public CarrierPose poseOf(ServerLevel level, CarrierId carrier) {
            return null;
        }

        @Override
        public CarrierId carrierAt(ServerLevel level, BlockPos worldPos) {
            return null;
        }
    }

    // ------------------------------------------------------------------ setup --

    /** The world store, as a map - the same stand-in the index's own tests use. */
    private record MapWorld(Map<Long, Slot> slots) implements CarriedHostIndex.SlotWorld {

        @Override
        public Slot get(BlockPos position) {
            return slots.get(position.asLong());
        }

        @Override
        public void set(BlockPos position, Slot slot) {
            if (slot == null) {
                slots.remove(position.asLong());
            } else {
                slots.put(position.asLong(), slot);
            }
        }
    }

    /** A pose that carries {@code local} to {@code worldPos}: the author's own is a translation. */
    private static CarrierPose moving(BlockPos local, BlockPos worldPos) {
        return CarrierPose.of(new Vector3d(worldPos.getX() - local.getX(),
                worldPos.getY() - local.getY(), worldPos.getZ() - local.getZ()), new Quaterniond());
    }

    /** The sub-level owns both cells; {@link #PLOT} holds DUST and {@link #EMPTY_CELL} holds nothing. */
    private void theAuthorsMachine() {
        theAuthorsMachine(true);
    }

    /**
     * The same machine, with the empty-record cell optional.
     *
     * <p>Without a record at {@link #EMPTY_CELL} that cell is a plot cell the index has never heard of,
     * which is the one state that has to ask whether the structure is still there at all
     * ({@code CarrierStore#ownedHostAtPlot} asks liveness only when the index holds no record).
     */
    private void theAuthorsMachine(boolean withEmptyRecord) {
        plotOwners.put(PLOT.asLong(), AUTHOR);
        plotOwners.put(EMPTY_CELL.asLong(), AUTHOR);
        world.put(VISIBLE.asLong(), new Slot(ComponentType.DUST));
        index.claim(AUTHOR, PLOT, VISIBLE, new MapWorld(world));
        if (withEmptyRecord) {
            index.registerAt(AUTHOR, EMPTY_CELL, ORIGIN, EMPTY_CELL, new MapWorld(world));
            assertNotNull(index.at(AUTHOR, EMPTY_CELL), "the setup: this cell has a record");
            assertNull(index.at(AUTHOR, EMPTY_CELL).slot(), "and it holds nothing");
        } else {
            assertNull(index.at(AUTHOR, EMPTY_CELL), "the setup: this cell has no record at all");
        }
        assertNotNull(index.at(AUTHOR, PLOT).slot(), "the setup: this cell holds a component");
    }

    // ------------------------------------------------------- the rule, by name --

    /** One of the four things a right-click can carry into this decision, as the rule reads it. */
    private record Hold(String label, boolean wrench, boolean rod, boolean component, boolean empty) {

        static final Hold PISTON = new Hold("piston", false, false, false, false);
        static final Hold GRASS_BLOCK = new Hold("grass block", false, false, false, false);
        static final Hold COMPONENT = new Hold("component", false, false, true, false);
        static final Hold WRENCH = new Hold("wrench", true, false, false, false);
        static final Hold ROD = new Hold("rotation rod", false, true, false, false);
        static final Hold BARE = new Hold("bare hand", false, false, false, true);
    }

    /**
     * The gesture a hand and a sneak state make, through the production rule rather than a literal.
     *
     * <p>Asked through {@code CarrierGesture} so this file cannot invent a gesture the entry point would
     * never see: the two halves of "which click is this" have one implementation.
     */
    private static CarrierGesture.Kind gestureOf(Hold hand, boolean sneaking) {
        return CarrierGesture.of(new CarrierGesture.Hold(hand.wrench(), hand.rod(), hand.component(),
                hand.empty()), CarrierGesture.Hold.NOTHING, sneaking);
    }

    /**
     * {@code CarrierClickPriority#weOwnThisClick(...)}, asked by name.
     *
     * <p>The previous build has no such decision (and the one before this round has no sneak state in it),
     * so the reflective lookup fails and this answers {@code false} - "the world does not stand down",
     * which is the state the piston and the author's lever both need. Every assertion below then fails
     * against a tree that really compiles and runs.
     */
    private static boolean weOwn(CarrierGesture.Kind gesture, Hold hand, boolean sneaking,
                                 boolean cellHoldsComponent, boolean insert, boolean wrench,
                                 boolean switches) {
        try {
            java.lang.reflect.Method method = CarrierClickPriority.class.getDeclaredMethod("weOwnThisClick",
                    CarrierGesture.Kind.class, boolean.class, boolean.class, boolean.class, boolean.class,
                    boolean.class, boolean.class, boolean.class, boolean.class, boolean.class);
            method.setAccessible(true);
            Object answer = method.invoke(null, gesture, sneaking, hand.wrench(), hand.rod(),
                    hand.component(), hand.empty(), cellHoldsComponent, insert, wrench, switches);
            return Boolean.TRUE.equals(answer);
        } catch (ReflectiveOperationException absent) {
            return false;
        }
    }

    /** The answer with every switch on, which is the default configuration. */
    private static boolean weOwn(CarrierGesture.Kind gesture, Hold hand, boolean sneaking,
                                 boolean cellHoldsComponent) {
        return weOwn(gesture, hand, sneaking, cellHoldsComponent, true, true, true);
    }

    /**
     * {@code CarrierClickPriority#standDown(Owned, Kind, Hold, boolean, boolean, boolean, boolean, boolean)},
     * asked by name - the production form, with the sneak state this round adds to it.
     *
     * <p>A direct call would stop this file from compiling against the previous build, which is the one
     * thing it must be able to do: the red half of this round's evidence is a real run on that tree. So it
     * is asked reflectively, and the previous build's answer - the method does not exist - is the no-arg
     * form below, which stands down for <b>every</b> owned coordinate and is exactly the defect.
     */
    private static CarrierClickPriority.StandDown standDown(CarrierClickPriority.Owned owned,
                                                            CarrierGesture.Kind gesture, Hold hand,
                                                            boolean sneaking,
                                                            boolean cellHoldsComponent) {
        try {
            java.lang.reflect.Method method = CarrierClickPriority.class.getDeclaredMethod("standDown",
                    CarrierClickPriority.Owned.class, CarrierGesture.Kind.class, CarrierGesture.Hold.class,
                    boolean.class, boolean.class, boolean.class, boolean.class, boolean.class);
            method.setAccessible(true);
            Object answer = method.invoke(null, owned, gesture,
                    new CarrierGesture.Hold(hand.wrench(), hand.rod(), hand.component(), hand.empty()),
                    sneaking, cellHoldsComponent, true, true, true);
            return (CarrierClickPriority.StandDown) answer;
        } catch (ReflectiveOperationException absent) {
            // The previous build: ownership of the coordinate is the whole question, so a piston in hand
            // stands the world down exactly like a component does. That is the author's defect.
            return CarrierClickPriority.standDown(owned);
        }
    }

    /**
     * <b>Whether the carrier's own entry point would act on this click - the gate each path opens with.</b>
     *
     * <p>Not a re-implementation of the rule: it is the <b>gate</b> each of the four paths in
     * {@code CarrierInteraction} opens with, written out so the cross-check can compare it against the
     * world side's answer. {@code handleToolClick} reads {@code carriedWrench} / {@code carriedRotationRod},
     * {@code handlePlacementClick} reads {@code carriedInsert}, {@code handleSwitchClick} reads
     * {@code carriedSwitches} <b>and</b> has to find something to work
     * ({@code CarrierStore#hostHasComponent}), and a click no gesture owns never reaches the carrier layer
     * at all.
     *
     * <p>The item half matters exactly where the gesture is a placement: a component in hand is stored
     * against the cell's own contents <b>while sneaking</b> - the plain half is the world's own block
     * placement, and {@code CarriedInsert#preview} answers {@code NOT_SNEAKING} for it - while an empty
     * hand has nothing to take out of a cell that holds nothing.
     */
    private static boolean theCarrierWouldAct(CarrierGesture.Kind gesture, Hold hand, boolean sneaking,
                                             boolean cellHoldsComponent, boolean insert, boolean wrench,
                                             boolean switches) {
        return switch (gesture) {
            case WRENCH -> wrench;
            case ROD -> true;
            case PLACEMENT -> insert && sneaking && (hand.component() || (hand.empty() && cellHoldsComponent));
            case SWITCH -> switches && hand.empty() && cellHoldsComponent;
            case NONE -> false;
        };
    }

    // ------------------------------------------------------------------- cases --

    @Nested
    @DisplayName("the predicate: only a click we can act on is yielded")
    class TheRule {

        @Test
        @DisplayName("holding an ORDINARY BLOCK - the author's piston - is never ours, whatever else is true")
        void anOrdinaryBlockIsNeverOurs() {
            for (boolean sneaking : new boolean[] {false, true}) {
                assertFalse(weOwn(CarrierGesture.Kind.NONE, Hold.PISTON, sneaking, false),
                        "a piston is not a component, not a tool, and not an empty hand: this is the click "
                                + "the author could not make, and it must fall through to vanilla's own "
                                + "placement (sneaking=" + sneaking + ")");
                assertFalse(weOwn(CarrierGesture.Kind.NONE, Hold.PISTON, sneaking, true),
                        "and a cell that holds a component changes nothing about it: the item in hand "
                                + "decides which gesture this is, and a piston owns no gesture of ours");
                assertFalse(weOwn(CarrierGesture.Kind.NONE, Hold.GRASS_BLOCK, sneaking, true),
                        "the same for the grass block the author was building with - P1.34's regression "
                                + "assertion, kept: this row must never stand the world down again");
            }
        }

        /**
         * <b>P1.35's row: the sneak key is part of "is this click ours".</b>
         *
         * <p>The author's "拉杆放不上去了", as a table: a lever, a torch and a repeater are all
         * {@code Hold.COMPONENT} (the only fact the predicate has about the item - see
         * {@code CarrierGesture.Hold#component}), the 19 declines in their log were all
         * {@code snaking=false}, and the plain half of a placement is the world's own block placement.
         * Both halves are asserted for both cell states, which is the four-row table this round is about.
         */
        @Test
        @DisplayName("a component in hand is ours ONLY while sneaking: the plain click is vanilla's placement")
        void aComponentIsOursOnlyWhileSneaking() {
            assertEquals(CarrierGesture.Kind.PLACEMENT, gestureOf(Hold.COMPONENT, false));
            assertEquals(CarrierGesture.Kind.PLACEMENT, gestureOf(Hold.COMPONENT, true),
                    "one kind for both halves: the sneak state does not make it another gesture, which is "
                            + "exactly why the kind alone could not tell them apart");

            for (boolean cell : new boolean[] {false, true}) {
                assertFalse(weOwn(CarrierGesture.Kind.PLACEMENT, Hold.COMPONENT, false, cell),
                        "a lever / torch / repeater in hand with NO sneak key (cellHoldsComponent=" + cell
                                + "): not ours - the author has to be able to put the block down, and this "
                                + "is the 19-decline row of their log");
                assertEquals(true, weOwn(CarrierGesture.Kind.PLACEMENT, Hold.COMPONENT, true, cell),
                        "the same item while sneaking IS ours: it is stored in the carrier's own record "
                                + "(cellHoldsComponent=" + cell + "; allowReplace decides replace-or-not, "
                                + "not this predicate)");
            }

            assertEquals(true, weOwn(CarrierGesture.Kind.PLACEMENT, Hold.COMPONENT, true, false, true, true,
                            true),
                    "and the legitimate family of the author's log - the single `redstone x30` click with "
                            + "sneaking=true - still yields, which is what keeps the placement working");
            assertFalse(weOwn(CarrierGesture.Kind.PLACEMENT, Hold.COMPONENT, false, false, false, true, true),
                    "while the plain half is not ours even with the placement switched off: two independent "
                            + "reasons, and neither of them may turn into a stand-down");
        }

        @Test
        @DisplayName("a tool of ours is ours - its own gesture has to run on the carrier's record")
        void aToolIsOurs() {
            assertEquals(CarrierGesture.Kind.WRENCH, gestureOf(Hold.WRENCH, false));
            assertEquals(CarrierGesture.Kind.WRENCH, gestureOf(Hold.WRENCH, true),
                    "the sneak state does not change which gesture a wrench is");
            assertEquals(CarrierGesture.Kind.ROD, gestureOf(Hold.ROD, false));
            for (boolean sneaking : new boolean[] {false, true}) {
                assertEquals(true, weOwn(CarrierGesture.Kind.WRENCH, Hold.WRENCH, sneaking, false),
                        "the wrench's own two halves are both ours (sneaking=" + sneaking + ")");
                assertEquals(true, weOwn(CarrierGesture.Kind.ROD, Hold.ROD, sneaking, false),
                        "and so is the rod, either way: `shift` is a second gesture of the wrench, not a "
                                + "different tool");
            }
        }

        @Test
        @DisplayName("an empty hand is ours only where the cell really holds a component")
        void anEmptyHandNeedsSomethingToWork() {
            assertEquals(CarrierGesture.Kind.SWITCH, gestureOf(Hold.BARE, false));
            assertEquals(CarrierGesture.Kind.PLACEMENT, gestureOf(Hold.BARE, true),
                    "sneaking with two empty hands is the retrieval half, and it is the same PLACEMENT kind");

            assertEquals(true, weOwn(CarrierGesture.Kind.SWITCH, Hold.BARE, false, true),
                    "a bare-hand click on a host that holds a component is ours: that is the workable block");
            assertFalse(weOwn(CarrierGesture.Kind.SWITCH, Hold.BARE, false, false),
                    "and a cell that holds NOTHING is not: a piston, a lamp, a chest, a lever - the world "
                            + "and vanilla keep the click, which is what the author asked for");
            assertEquals(true, weOwn(CarrierGesture.Kind.PLACEMENT, Hold.BARE, true, true),
                    "shift + empty hand on a host that holds a component takes it back out");
            assertFalse(weOwn(CarrierGesture.Kind.PLACEMENT, Hold.BARE, true, false),
                    "and on a cell that holds nothing there is nothing to take out: the carrier's own "
                            + "path refuses it by name, so the world keeps the click and the player is not "
                            + "left with a gesture that silently did nothing");
        }

        @Test
        @DisplayName("the switches are honoured on both sides: a gesture that is off is nobody's click")
        void theSwitchesAreHonoured() {
            assertFalse(weOwn(CarrierGesture.Kind.PLACEMENT, Hold.COMPONENT, true, false, false, true, true),
                    "carriedInsert=false: the placement itself refuses, so the world keeps the click");
            assertFalse(weOwn(CarrierGesture.Kind.WRENCH, Hold.WRENCH, false, true, true, false, true),
                    "carriedWrench=false: the carrier's whole wrench is off");
            assertFalse(weOwn(CarrierGesture.Kind.SWITCH, Hold.BARE, false, true, true, true, false),
                    "carriedSwitches=false: the bare-hand switch refuses");
            assertEquals(true, weOwn(CarrierGesture.Kind.ROD, Hold.ROD, false, false, false, false, false),
                    "the rotation rod has no switch of its own in this predicate: its own path checks "
                            + "carriedRotationRod and refuses by name, and the click is still ours to "
                            + "refuse rather than the world's to act on");
            assertFalse(weOwn(CarrierGesture.Kind.PLACEMENT, Hold.COMPONENT, false, false, true, true, true),
                    "and with every switch ON, the sneak key alone still decides the plain half: this is "
                            + "the row the author's 19 lever clicks were on");
        }

        @Test
        @DisplayName("a NONE gesture is never ours, for every cell state and both sneak states")
        void aNoneGestureIsNeverOurs() {
            for (Hold hand : List.of(Hold.PISTON, Hold.GRASS_BLOCK)) {
                for (boolean cell : new boolean[] {false, true}) {
                    for (boolean sneaking : new boolean[] {false, true}) {
                        assertFalse(weOwn(CarrierGesture.Kind.NONE, hand, sneaking, cell),
                                "an item no gesture owns (" + hand.label() + ", cell=" + cell
                                        + ", sneaking=" + sneaking + ")");
                    }
                }
            }
        }

        /**
         * <b>The cross-check the round asked for: the judgement has one home, and these two must agree.</b>
         *
         * <p>Walked over every combination of the four things a hand can hold, both sneak states, both cell
         * states and all four switch combinations: 6 x 2 x 2 x 2 x 2 x 2 = 384 rows. If somebody changes one
         * side - a new gesture in the rule, a new gate in an entry path - this fails with the exact row,
         * instead of the two halves drifting silently apart the way they did when the world side answered
         * about the coordinate and the carrier side about the click.
         *
         * <p>The sneak dimension is explicit and not folded into the gesture, which is the whole of P1.35:
         * the same kind covers "shift + component = store it here" and "plain + component = vanilla puts the
         * block down", and only the row that the carrier's own placement rule would act on may yield.
         */
        @Test
        @DisplayName("the yield judgement and the carrier's own gates are the same table")
        void theYieldJudgementAgreesWithTheCarrierEntryPoint() {
            List<Hold> hands = List.of(Hold.PISTON, Hold.GRASS_BLOCK, Hold.COMPONENT, Hold.WRENCH, Hold.ROD,
                    Hold.BARE);
            int rows = 0;
            for (Hold hand : hands) {
                for (boolean sneaking : new boolean[] {false, true}) {
                    CarrierGesture.Kind gesture = gestureOf(hand, sneaking);
                    for (boolean cell : new boolean[] {false, true}) {
                        for (boolean insert : new boolean[] {false, true}) {
                            for (boolean wrench : new boolean[] {false, true}) {
                                for (boolean switches : new boolean[] {false, true}) {
                                    boolean yielded = weOwn(gesture, hand, sneaking, cell, insert, wrench,
                                            switches);
                                    boolean carrierActs = theCarrierWouldAct(gesture, hand, sneaking, cell,
                                            insert, wrench, switches);
                                    assertEquals(carrierActs, yielded,
                                            "row: hand=" + hand.label() + " sneaking=" + sneaking
                                                    + " gesture=" + gesture + " cellHoldsComponent=" + cell
                                                    + " carriedInsert=" + insert
                                                    + " carriedWrench=" + wrench
                                                    + " carriedSwitches=" + switches
                                                    + " - the world must yield exactly when the carrier "
                                                    + "entry point would act on this click, and never "
                                                    + "otherwise");
                                    rows++;
                                }
                            }
                        }
                    }
                }
            }
            assertEquals(6 * 2 * 2 * 2 * 2 * 2, rows, "the table is exhaustive, not sampled");
        }
    }

    /**
     * <b>The wiring, asserted over the source, because a unit test has no player.</b>
     *
     * <p>The rule above is only half of the fix: the decision is worth nothing unless the world side asks
     * it. That call site is in {@code InnerRedstoneInteraction#onRightClickBlock}, which needs a
     * {@code ServerPlayer} to run - so what is pinned here is the shape of the call: the gesture is read
     * from the carrier-side rule's own production form, the cell's contents come from the same method the
     * carrier entry point uses, and the answer is acted on by returning before the event is touched.
     */
    @Nested
    @DisplayName("the production wiring: the world side asks this one predicate, with the click named")
    class TheWorldSideAsksTheOnePredicate {

        @Test
        @DisplayName("the gesture, the hand and the cell's contents all reach the decision")
        void theCallSiteNamesTheClick() throws java.io.IOException {
            String source = source();

            assertTrue(source.contains("CarrierClickPriority.standDown(carrierOwns,"),
                    "the ownership answer is no longer the whole input: the click is passed beside it");
            assertTrue(source.contains("CarrierGesture.of(player, event.getHand())"),
                    "and the gesture comes from the carrier-side rule's own production form, so the two "
                            + "listeners cannot disagree about what a click is");
            assertTrue(source.contains("CarrierGesture.Hold.of(stack)"),
                    "the item is read once, as the four facts the rule is written over");
            assertTrue(source.contains("CarrierStore.hostHasComponent(serverLevel, carrierOwns.carrier(),"),
                    "and the clicked cell's own contents are asked with the same method the carrier entry "
                            + "point asks before it acts");
            assertTrue(source.contains("CarrierGesture.Hold.of(stack), player.isSecondaryUseActive(),"),
                    "P1.35: and the SNEAK STATE is passed beside them, because the kind cannot carry it: a "
                            + "component in hand is a placement either way, and only the sneaking half of it "
                            + "is a store");
        }

        @Test
        @DisplayName("an answer of `not ours` returns before the event is touched")
        void theWorldKeepsEverythingWhenItIsNotOurs() throws java.io.IOException {
            String source = source();

            assertTrue(source.contains("if (standDown.useBlock() && standDown.useItem()) {"),
                    "the two action fields are the world's own answer, and a `keep` answer is acted on");
            assertTrue(source.contains("// NOT OURS: the event is left exactly as it arrived"),
                    "with the reason written where the early return is, because this is the line that puts "
                            + "a piston back on a physicalised block");
            assertTrue(source.contains("event.setUseBlock(net.neoforged.neoforge.common.util.TriState.FALSE);"),
                    "and the stand-down itself is unchanged: the two action fields, and not cancellation");
            assertFalse(source.contains("event.setCanceled(true);\n            return;\n        }\n\n        // The wrench"),
                    "the gate must not cancel: a cancelled RightClickBlock is not delivered to the carrier's "
                            + "own listener");
        }

        private static String source() {
            try {
                return java.nio.file.Files.readString(java.nio.file.Path.of(
                                "src/main/java/com/jiangyuefengyu/redstonecircuit/InnerRedstoneInteraction.java"))
                        .replace("\r\n", "\n");
            } catch (java.io.IOException unreadable) {
                throw new AssertionError("the world handler's own source could not be read (run the tests "
                        + "from the project directory)", unreadable);
            }
        }
    }

    @Nested
    @DisplayName("the ownership half, driven the way production asks it")
    class TheStandDown {

        @Test
        @DisplayName("a piston, and a component with no sneak key, both keep the whole event")
        void thePistonAndThePlainComponentAreNotStoodDown() {
            theAuthorsMachine();
            CarrierClickPriority.Owned owned = probe(PLOT, answeringLookup());
            assertNotNull(owned, "the coordinate IS the structure's - ownership is not the question here");

            CarrierClickPriority.StandDown standDown = standDown(owned,
                    CarrierGesture.Kind.NONE, Hold.PISTON, false, true);

            assertTrue(standDown.useBlock(), "vanilla's own block use must run: this is the coordinate the "
                    + "author could not place a block on");
            assertTrue(standDown.useItem(), "and the item's useOn, which is how a block is really placed");
            assertFalse(standDown.canceled(), "nothing is cancelled, so every listener still sees the click");
            assertEquals(new CarrierClickPriority.StandDown(true, true, false), standDown,
                    "the whole event is left exactly as it arrived");

            // P1.35, and this is the author's lever: the SAME component-in-hand click the row below stands
            // the world down for becomes the world's again the moment the sneak key is not held. Both cell
            // states, because the cell's own contents must not be able to change it either.
            for (boolean cell : new boolean[] {false, true}) {
                assertEquals(new CarrierClickPriority.StandDown(true, true, false),
                        standDown(owned, CarrierGesture.Kind.PLACEMENT, Hold.COMPONENT, false, cell),
                        "a lever / torch / repeater in hand with no sneak key: the world keeps both action "
                                + "fields, so vanilla's Item#useOn puts the block down (cellHoldsComponent="
                                + cell + ")");
            }
        }

        @Test
        @DisplayName("a redstone component there WHILE SNEAKING: the world stands down, and is not cancelled")
        void theComponentStandsTheWorldDown() {
            theAuthorsMachine();

            CarrierClickPriority.StandDown standDown = standDown(
                    probe(PLOT, answeringLookup()), CarrierGesture.Kind.PLACEMENT, Hold.COMPONENT, true,
                    true);

            assertFalse(standDown.useBlock(), "the component has to go into the carrier record, not the "
                    + "world store at a plot coordinate");
            assertFalse(standDown.useItem());
            assertFalse(standDown.canceled(), "and NOT cancelled: the carrier's own listener - the one this "
                    + "exception exists to hand the click to - is still called");
        }

        @Test
        @DisplayName("the tools and both halves of a bare hand on a host that holds a component")
        void theToolsAndTheBareHand() {
            theAuthorsMachine();
            CarrierClickPriority.Owned held = probe(PLOT, answeringLookup());
            CarrierClickPriority.Owned emptyHost = probe(EMPTY_CELL, answeringLookup());

            for (boolean sneaking : new boolean[] {false, true}) {
                assertFalse(standDown(held, CarrierGesture.Kind.WRENCH, Hold.WRENCH, sneaking, true)
                                .useBlock(),
                        "the carrier's own wrench gesture needs the click, plain or shift (sneaking="
                                + sneaking + ")");
                assertFalse(standDown(held, CarrierGesture.Kind.ROD, Hold.ROD, sneaking, true).useBlock(),
                        "and the rod");
            }
            assertFalse(standDown(held, CarrierGesture.Kind.SWITCH, Hold.BARE, false, true).useBlock(),
                    "a bare hand on a host that holds a component: our switch");
            assertFalse(standDown(held, CarrierGesture.Kind.PLACEMENT, Hold.BARE, true, true).useBlock(),
                    "shift + bare hand: our retrieval");
            assertTrue(standDown(emptyHost, CarrierGesture.Kind.SWITCH, Hold.BARE, false, false).useBlock(),
                    "and a bare hand on a host that holds NOTHING is not ours: the author's piston again, "
                            + "which is why the cell's own contents are part of the question");
            assertTrue(standDown(emptyHost, CarrierGesture.Kind.PLACEMENT, Hold.BARE, true, false)
                            .useBlock(),
                    "the retrieval half is unchanged too: nothing to take out of an empty host");
        }

        @Test
        @DisplayName("the switch off is the escape hatch, and it is on by default")
        void theSwitchDefaultsOn() {
            assertTrue(carriedInsertPriority(), "with it off the world keeps every click again, exactly as "
                    + "it did before this class existed");
        }
    }

    @Nested
    @DisplayName("the world's own coordinates are untouched, byte for byte")
    class NothingElseMoved {

        @Test
        @DisplayName("an ordinary world block keeps the event, and it is not ours - either half of the click")
        void anOrdinaryBlockKeepsEverything() {
            theAuthorsMachine();

            assertNull(probe(ORDINARY, answeringLookup()), "no sub-level's storage contains ordinary ground");
            for (boolean sneaking : new boolean[] {false, true}) {
                CarrierClickPriority.StandDown standDown = standDown(null,
                        CarrierGesture.Kind.PLACEMENT, Hold.COMPONENT, sneaking, false);

                assertTrue(standDown.useBlock(), "the world path decides this click, exactly as before "
                        + "(sneaking=" + sneaking + ")");
                assertTrue(standDown.useItem());
                assertFalse(standDown.canceled());
            }
        }

        @Test
        @DisplayName("a plot-SHAPED coordinate no sub-level owns is still nobody's")
        void anUnownedPlotCoordinateIsNobody() {
            theAuthorsMachine();

            assertNull(probe(OTHER_PLOT, answeringLookup()),
                    "the shape of the number is not evidence of anything: a sub-level has to name it");
        }

        @Test
        @DisplayName("a landed host is the world's again, and a dead sub-level owns nothing")
        void landedAndDeadAreStillTheWorlds() {
            theAuthorsMachine();
            world.put(ORDINARY.asLong(), new Slot(ComponentType.DUST));
            index.release(AUTHOR, PLOT, landing -> ORDINARY, new MapWorld(world));
            assertTrue(index.at(AUTHOR, PLOT).landed(), "the setup: the host was put down");

            assertNull(probe(PLOT, answeringLookup()), "a put-down host is the world's");

            // A cell with NO record, on a sub-level nothing stands on any more: this is the one path that
            // has to ask the liveness question (a cell the index holds a record for does not need it - the
            // record is the structure's own claim on that coordinate), and the answer must not be "mine".
            index.clear();
            theAuthorsMachine(false);
            liveness = carrier -> false;
            assertNull(probe(EMPTY_CELL, answeringLookup()), "a plot region no live structure stands on is "
                    + "not anybody's coordinate");
            assertTrue(asked.contains(AUTHOR), "and the liveness question is what decided it");
        }

        @Test
        @DisplayName("the only thing that changed about ownership is that it is no longer sufficient")
        void ownershipIsUnchangedAndNoLongerSufficient() {
            theAuthorsMachine();

            // The same cell, the same ownership answer, three clicks: the sneaking component is stood down,
            // the plain component and the piston are not. That is the whole of P1.34 + P1.35 - not a narrower
            // ownership question, a narrower question about the CLICK.
            CarrierClickPriority.Owned owned = probe(PLOT, answeringLookup());
            assertNotNull(owned, "ownership answers for the cell exactly as it did before this round");
            assertEquals(CarrierClickPriority.Probe.HOST, owned.probe(),
                    "and the tag is unchanged: there is a live record here");
            assertEquals(PLOT, owned.localPos(), "and the coordinate IS the structure's local coordinate");

            assertFalse(standDown(owned, CarrierGesture.Kind.PLACEMENT, Hold.COMPONENT, true, true)
                    .useBlock());
            assertTrue(standDown(owned, CarrierGesture.Kind.PLACEMENT, Hold.COMPONENT, false, true)
                    .useBlock());
            assertTrue(standDown(owned, CarrierGesture.Kind.NONE, Hold.PISTON, false, true).useBlock());
        }
    }

    // ---------------------------------------------------------------- fixtures --

    /** See {@code CarrierPlotOwnershipLevelTest}: a unit test has no {@code ServerLevel} to hand over. */
    private static final ServerLevel PLOT_LEVEL = null;

    @Nullable
    private CarrierClickPriority.Owned probe(BlockPos pos, CarrierStore.PlotOwnerSource owners) {
        try {
            java.lang.reflect.Method method = CarrierClickPriority.class.getDeclaredMethod("probeOwner",
                    CarrierProjection.class, CarriedHostIndex.class, ServerLevel.class, BlockPos.class,
                    CarrierStore.PlotOwnerSource.class, CarrierStore.PoseSource.class,
                    Predicate.class);
            method.setAccessible(true);
            Object result = method.invoke(null, table, index, PLOT_LEVEL, pos, owners,
                    (CarrierStore.PoseSource) carrier -> poseOf(carrier), counted());
            return (CarrierClickPriority.Owned) result;
        } catch (ReflectiveOperationException absent) {
            // The previous build has no such decision: answering null is "no structure owns this
            // position" - the world keeps the click, which is the state this round repairs.
            return null;
        }
    }

    /** A lookup that answers whether or not it is handed a level - P1.30's own fixture shape. */
    private CarrierStore.PlotOwnerSource answeringLookup() {
        return (level, pos) -> plotOwners.get(pos.asLong());
    }

    /** The pose the author's log printed for that structure, over the two cells of this fixture. */
    @Nullable
    private CarrierPose poseOf(CarrierId carrier) {
        return AUTHOR.equals(carrier) ? moving(PLOT, VISIBLE) : null;
    }

    /** The liveness question, with the carrier recorded so "it was asked" is assertable. */
    private Predicate<CarrierId> counted() {
        return carrier -> {
            asked.add(carrier);
            return liveness.test(carrier);
        };
    }

    /** The switch, asked the same way and for the same reason as the other ownership tests. */
    private static boolean carriedInsertPriority() {
        try {
            java.lang.reflect.Method method = RCConfig.class.getDeclaredMethod("carriedInsertPriority");
            return (Boolean) method.invoke(null);
        } catch (ReflectiveOperationException absent) {
            return false;
        }
    }
}

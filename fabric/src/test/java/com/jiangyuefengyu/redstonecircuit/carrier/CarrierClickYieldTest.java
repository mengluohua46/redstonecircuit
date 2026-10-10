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
 * The world side stands down only for a click that is really ours - and the sneak key is part of "is this click ours".
 * Standing down for a whole coordinate stopped vanilla's own item use, so no piston, plank or lever could be placed on a
 * carried block; a component in hand is a placement either way, and only the sneaking half is a store.
 *
 * <p>Pinned here: the predicate over a table of hands, sneak states, cell contents and switch combinations; the
 * cross-check against the switch each gesture path in {@code CarrierInteraction} reads, so "the world yields" and "the
 * carrier will act" cannot drift apart; and the call site's shape, since a unit test has no player.
 */
class CarrierClickYieldTest {

    /** The carrier every fixture in this file is filed under. */
    private static final CarrierId AUTHOR =
            CarrierId.subLevel(UUID.fromString("e0677381-0000-0000-0000-000000000000"));

    /** A cell of that structure. */
    private static final BlockPos PLOT = new BlockPos(20481034, 127, 20481032);

    /** A second cell of the same structure, used for the empty-record state. */
    private static final BlockPos EMPTY_CELL = new BlockPos(20481033, 127, 20481032);

    /** Where the pose puts those cells. */
    private static final BlockPos VISIBLE = new BlockPos(-2, -60, -10);

    /** The structure's origin. */
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

    /** A pose that carries {@code local} to {@code worldPos}: a translation and nothing else. */
    private static CarrierPose moving(BlockPos local, BlockPos worldPos) {
        return CarrierPose.of(new Vector3d(worldPos.getX() - local.getX(),
                worldPos.getY() - local.getY(), worldPos.getZ() - local.getZ()), new Quaterniond());
    }

    /** The sub-level owns both cells; {@link #PLOT} holds DUST and {@link #EMPTY_CELL} holds nothing. */
    private void theAuthorsMachine() {
        theAuthorsMachine(true);
    }

    /**
     * The same machine, with the empty-record cell optional: without a record there, that cell is a plot cell the index
     * has never heard of - the one state that has to ask whether the structure is still there.
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
     * The gesture a hand and a sneak state make, asked through {@code CarrierGesture} so this file cannot invent a
     * gesture the entry point would never see.
     */
    private static CarrierGesture.Kind gestureOf(Hold hand, boolean sneaking) {
        return CarrierGesture.of(new CarrierGesture.Hold(hand.wrench(), hand.rod(), hand.component(),
                hand.empty()), CarrierGesture.Hold.NOTHING, sneaking);
    }

    /**
     * {@code CarrierClickPriority#weOwnThisClick(...)}, asked by name: a build without that decision makes the reflective
     * lookup fail and this answer {@code false}, so every assertion below fails against a tree that compiles and runs.
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
     * {@code CarrierClickPriority#standDown(Owned, Kind, Hold, boolean, boolean, boolean, boolean, boolean)}, asked by
     * name; a build without the method falls back to the no-arg form below, which stands down for every owned coordinate.
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
            // A build without the click-named form: ownership of the coordinate is the whole question, so a piston
            // in hand stands the world down exactly like a component does.
            return CarrierClickPriority.standDown(owned);
        }
    }

    /**
     * The same decision with the host rule stated:
     * {@code CarrierClickPriority#standDown(Owned, Kind, Hold, boolean, boolean, boolean, boolean, boolean, boolean)}.
     * Asked by name for the same reason, and a build without the nine-argument form falls back to the one-argument one.
     */
    private static CarrierClickPriority.StandDown standDown(CarrierClickPriority.Owned owned,
                                                            CarrierGesture.Kind gesture, Hold hand,
                                                            boolean sneaking, boolean cellHoldsComponent,
                                                            boolean hostAcceptsComponent) {
        try {
            java.lang.reflect.Method method = CarrierClickPriority.class.getDeclaredMethod("standDown",
                    CarrierClickPriority.Owned.class, CarrierGesture.Kind.class, CarrierGesture.Hold.class,
                    boolean.class, boolean.class, boolean.class, boolean.class, boolean.class, boolean.class);
            method.setAccessible(true);
            Object answer = method.invoke(null, owned, gesture,
                    new CarrierGesture.Hold(hand.wrench(), hand.rod(), hand.component(), hand.empty()),
                    sneaking, cellHoldsComponent, hostAcceptsComponent, true, true, true);
            return (CarrierClickPriority.StandDown) answer;
        } catch (ReflectiveOperationException absent) {
            return CarrierClickPriority.standDown(owned);
        }
    }

    /**
     * Whether the carrier's own entry point would act on this click: the gate each path in {@code CarrierInteraction}
     * opens with, written out so the cross-check can compare it against the world side's answer.
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
        @DisplayName("holding an ORDINARY BLOCK - a piston - is never ours, whatever else is true")
        void anOrdinaryBlockIsNeverOurs() {
            for (boolean sneaking : new boolean[] {false, true}) {
                assertFalse(weOwn(CarrierGesture.Kind.NONE, Hold.PISTON, sneaking, false),
                        "a piston is not a component, not a tool, and not an empty hand: this is the click "
                                + "that must fall through to vanilla's own placement (sneaking=" + sneaking + ")");
                assertFalse(weOwn(CarrierGesture.Kind.NONE, Hold.PISTON, sneaking, true),
                        "and a cell that holds a component changes nothing about it: the item in hand "
                                + "decides which gesture this is, and a piston owns no gesture of ours");
                assertFalse(weOwn(CarrierGesture.Kind.NONE, Hold.GRASS_BLOCK, sneaking, true),
                        "the same for the grass block: this row must never stand the world down again");
            }
        }

        /**
         * The sneak key is part of "is this click ours": a lever, a torch and a repeater are all {@code Hold.COMPONENT},
         * the only fact the predicate has about the item, and the plain half of a placement is vanilla's own placement.
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
                                + "): not ours - the player has to be able to put the block down");
                assertEquals(true, weOwn(CarrierGesture.Kind.PLACEMENT, Hold.COMPONENT, true, cell),
                        "the same item while sneaking IS ours: it is stored in the carrier's own record "
                                + "(cellHoldsComponent=" + cell + "; allowReplace decides replace-or-not, "
                                + "not this predicate)");
            }

            assertEquals(true, weOwn(CarrierGesture.Kind.PLACEMENT, Hold.COMPONENT, true, false, true, true,
                            true),
                    "a component in hand while sneaking still yields, which is what keeps the placement working");
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
                            + "and vanilla keep the click");
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
                    "and with every switch ON, the sneak key alone still decides the plain half");
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
         * The cross-check: the judgement has one home, so the world side's answer and the carrier's own gate must agree
         * over every combination of hand, sneak state, cell state and switches - 6 x 2 x 2 x 2 x 2 x 2 = 384 rows. A change
         * to one side fails here with the exact row, and the sneak dimension stays explicit: only the row the carrier's own
         * placement rule would act on may yield.
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
     * The wiring, asserted over the source, because a unit test has no player: the decision is worth nothing unless the
     * world side asks it, and that call site needs a player to run. What is pinned is the shape of the call - the gesture
     * from the carrier-side rule's own production form, the cell's contents from the method the carrier entry point uses,
     * and the answer acted on by returning before the event is touched.
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
            assertTrue(source.contains("CarrierGesture.of(player, hand)"),
                    "and the gesture comes from the carrier-side rule's own production form, so the two "
                            + "listeners cannot disagree about what a click is");
            assertTrue(source.contains("CarrierGesture.Hold.of(stack)"),
                    "the item is read once, as the four facts the rule is written over");
            assertTrue(source.contains("CarrierStore.hostHasComponent(serverLevel, carrierOwns.carrier(),"),
                    "and the clicked cell's own contents are asked with the same method the carrier entry "
                            + "point asks before it acts");
            assertTrue(source.contains("CarrierStore.hostAcceptsComponent(carrierOwns.carrier(),"),
                    "and whether the block may hold a component at all is asked beside it, with the same "
                            + "method the carrier's own placement asks - without this half the world stood down for a "
                            + "click the carrier layer was then going to refuse, and the block the player was putting "
                            + "down never appeared at all");
            assertTrue(source.contains("CarrierGesture.Hold.of(stack), player.isSecondaryUseActive(),"),
                    "and the SNEAK STATE is passed beside them, because the kind cannot carry it: a "
                            + "component in hand is a placement either way, and only the sneaking half of it "
                            + "is a store");
        }

        @Test
        @DisplayName("an answer of `not ours` returns before the click is acted on")
        void theWorldKeepsEverythingWhenItIsNotOurs() throws java.io.IOException {
            String source = source();

            assertTrue(source.contains("if (standDown.useBlock() && standDown.useItem()) {"),
                    "the two action fields are the world's own answer, and a `keep` answer is acted on");
            assertTrue(source.contains("// NOT OURS: the click is left exactly as it arrived"),
                    "with the reason written where the early return is, because this is the line that puts "
                            + "a piston back on a physicalised block");
            assertTrue(source.contains("return InteractionResult.PASS;"),
                    "and that early return is PASS - the click handed back exactly as it arrived, which is "
                            + "what lets vanilla place the piston on a physicalised block");
            assertFalse(source.contains("setCanceled"),
                    "and nothing here cancels: on this loader every answer is an InteractionResult, so a "
                            + "stand-down that must stop vanilla is FAIL rather than a cancellation");
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

            assertTrue(standDown.useBlock(), "vanilla's own block use must run: this is a coordinate a block "
                    + "could not be placed on");
            assertTrue(standDown.useItem(), "and the item's useOn, which is how a block is really placed");
            assertFalse(standDown.canceled(), "nothing is cancelled, so every listener still sees the click");
            assertEquals(new CarrierClickPriority.StandDown(true, true, false), standDown,
                    "the whole event is left exactly as it arrived");

            // The same click becomes the world's again the moment the sneak key is not held, for both cell states.
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
                    "and a bare hand on a host that holds NOTHING is not ours: the block holds no component, "
                            + "which is why the cell's own contents are part of the question");
            assertTrue(standDown(emptyHost, CarrierGesture.Kind.PLACEMENT, Hold.BARE, true, false)
                            .useBlock(),
                    "the retrieval half is unchanged too: nothing to take out of an empty host");
        }

        @Test
        @DisplayName("a block that cannot hold a component does not make the click ours")
        void aBlockThatCannotHostYieldsTheClick() {
            theAuthorsMachine();
            CarrierClickPriority.Owned owned = probe(PLOT, answeringLookup());
            assertNotNull(owned, "the coordinate is the structure's; what the block IS is the second input");

            assertTrue(standDown(owned, CarrierGesture.Kind.PLACEMENT, Hold.COMPONENT, true, true, false)
                            .useBlock(),
                    "the ghost was vanilla predicting a placement that never ran: this side had already stood down "
                            + "for a store gesture, and then the carrier's own placement rule refused it because the "
                            + "block may not hold a component (a physicalised pane of glass, a structure block, a "
                            + "crafting table, a slab). With the block's own answer in front of it, the world keeps the "
                            + "click and the block really is put down - the same thing that happens on a pane of glass "
                            + "in the world");
            assertTrue(standDown(owned, CarrierGesture.Kind.PLACEMENT, Hold.COMPONENT, true, true, false)
                            .useItem(),
                    "and the item's useOn is the half that actually places a block, so both fields have to be the "
                            + "world's again");
            assertFalse(standDown(owned, CarrierGesture.Kind.PLACEMENT, Hold.COMPONENT, true, true, true)
                            .useBlock(),
                    "while the same click on a block that MAY hold one is still ours: nothing that worked moved");
            assertFalse(standDown(owned, CarrierGesture.Kind.PLACEMENT, Hold.BARE, true, true, false).useBlock(),
                    "and a bare hand taking a component back OUT of that block stays ours even though the block "
                            + "refuses to hold one - the world's own retrieval asks nothing about the host either, "
                            + "so a component a player put in must always come back out");
        }

        @Test
        @DisplayName("the switch off is the escape hatch, and it is on by default")
        void theSwitchDefaultsOn() {
            assertTrue(carriedInsertPriority(), "with it off the world keeps every click again");
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

                assertTrue(standDown.useBlock(), "the world path decides this click (sneaking=" + sneaking + ")");
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

            // The one path that has to ask the liveness question: a record is the structure's own claim on the coordinate,
            // and the answer here must not be "mine".
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

            // The same cell and ownership answer, three clicks: the sneaking component is stood down, the plain component
            // and the piston are not - a narrower question about the click, not about ownership.
            CarrierClickPriority.Owned owned = probe(PLOT, answeringLookup());
            assertNotNull(owned, "ownership answers for the cell, unchanged");
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

    /** A unit test has no {@code ServerLevel} to hand over, so the plot level is null. */
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
            // A build without that decision answers null - "no structure owns this position" - so the world keeps
            // the click.
            return null;
        }
    }

    /** A lookup that answers whether or not it is handed a level. */
    private CarrierStore.PlotOwnerSource answeringLookup() {
        return (level, pos) -> plotOwners.get(pos.asLong());
    }

    /** The pose for that structure, over the two cells of this fixture. */
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

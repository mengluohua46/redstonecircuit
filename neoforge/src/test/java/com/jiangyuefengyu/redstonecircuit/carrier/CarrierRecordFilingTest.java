package com.jiangyuefengyu.redstonecircuit.carrier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.Slot;
import com.jiangyuefengyu.redstonecircuit.logic.CarriedInsert;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * <b>P1.33 commit A: only a placement that will really write a component may create a carrier record -
 * and the record and the contents land together or not at all.</b>
 *
 * <h2>The author's own three lines, one click</h2>
 * <pre>
 *   world insert declined: this position belongs to carrier s:c22611c6 owner=sublevel local=20481032, 128, 20481032 …
 *   carrier s:c22611c6: filed the carrier host a plot cell was missing local=20481032, 128, 20481032
 *       origin=-4, -59, -10 (record=created empty) …
 *   carrier entry trace: … raw=20481032, 128, 20481032 … -&gt; REFUSED refused: this carried host is EMPTY
 *       and the picked host is not this one, so there is nothing to pin towards it either (INSIDE: NOT_INSIDE)
 * </pre>
 *
 * <p>The item in hand on that click was the <b>wrench</b> (the same log's own {@code item=… tool=wrench}
 * column), so nothing was ever going to be stored - and a record was created anyway, because P1.32's
 * entry point fed the filing helper the wrench, the rotation rod and a bare hand as well as a placement.
 * From that moment the block was a host of that structure with nothing in it, and every later judgement
 * about the coordinate was taken against a record the block had not earned.
 *
 * <h2>What is asserted</h2>
 * <ol>
 *   <li><b>which clicks may file</b>: a wrench, the rotation rod, a bare hand, a non-sneaking click, a
 *       click with no face and {@code carriedInsert=false} all answer "no", and the placing click answers
 *       "yes" - plus the entry point's own source, which is where the defect really lived;</li>
 *   <li><b>the two halves are one decision</b>: the gate is computed by {@code CarriedInsert#preview} for
 *       the empty host the filing would create, and the store that follows is
 *       {@code CarriedInsert#apply}, so the record and the slot land in the same click - asserted by
 *       filing and then writing, and by the level form's own broadcast still sitting after both;</li>
 *   <li><b>a placement that cannot write files nothing</b>: an unreadable pose and a closed gate both
 *       leave the index empty, the world store untouched and the item unspent;</li>
 *   <li><b>an empty record cannot change a judgement</b>: the same plot cell with an empty host record and
 *       with no record at all answers the <em>same</em> carrier, and the two differ only in the tag that
 *       the migration and the placement path read ({@code owner=host} / {@code owner=sublevel});</li>
 *   <li><b>idempotent</b>: a second filing returns the record as it stands and never empties a host that
 *       has been built into.</li>
 * </ol>
 *
 * <h2>Why the new decisions are asked by reflection</h2>
 * Exactly as in P1.31 and P1.32: on the previous tree {@code CarrierInteraction#mayCreateRecord},
 * {@code CarriedInsert#wouldInsert}, the gated {@code CarrierStore#resolveForPlacement} and
 * {@code CarrierClickPriority#plotCellOwnership} do not exist, so a direct call would be a compile error
 * there rather than a readable red assertion - and the report has to say which of the two it is.
 */
class CarrierRecordFilingTest {

    /** The coordinate of the author's wrench click: a cell of a live structure with no record yet. */
    private static final BlockPos PLOT = new BlockPos(20481032, 128, 20481032);

    /** Where that structure's pose puts the cell: the author's own {@code origin=-4, -59, -10}. */
    private static final BlockPos PLOT_WORLD = new BlockPos(-4, -59, -10);

    /** The author's carrier, from {@code carrier s:c22611c6}. */
    private static final CarrierId AUTHOR =
            CarrierId.subLevel(UUID.fromString("c22611c6-0000-0000-0000-000000000000"));

    /** The decision's own source, for the wiring assertions. */
    private static final String STORE_SOURCE =
            "src/main/java/com/jiangyuefengyu/redstonecircuit/carrier/CarrierStore.java";

    private final CarriedHostIndex index = new CarriedHostIndex();
    private final Map<Long, Slot> world = new HashMap<>();
    private final Map<Long, CarrierId> plotOwners = new HashMap<>();

    private CarrierPose pose = moving(PLOT, PLOT_WORLD);

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

    /** A pose that carries {@code local} to {@code worldPos}: the author's claim is a translation. */
    private static CarrierPose moving(BlockPos local, BlockPos worldPos) {
        return CarrierPose.of(new Vector3d(worldPos.getX() - local.getX(),
                worldPos.getY() - local.getY(), worldPos.getZ() - local.getZ()), new Quaterniond());
    }

    /** A structure whose own storage contains the clicked cell. */
    private void liveStructure() {
        plotOwners.put(PLOT.asLong(), AUTHOR);
    }

    private CarrierStore.PlotOwnerSource owners() {
        return (level, pos) -> {
            if (level != null) {
                throw new IllegalStateException("the level-free core must not need a level");
            }
            return plotOwners.get(pos.asLong());
        };
    }

    private CarrierStore.PoseSource poses() {
        return owner -> AUTHOR.equals(owner) ? pose : null;
    }

    // --------------------------------------------- the decisions, by reflection --

    /**
     * {@code CarrierInteraction#mayCreateRecord(gesture, stored, sneaking, face, carriedInsert,
     * allowReplace)} - the answer the entry point gates the filing on.
     */
    private static boolean mayCreate(CarrierGesture.Kind gesture, @Nullable ComponentType stored,
                                     boolean sneaking, @Nullable Direction face) {
        try {
            Class<?> interaction =
                    Class.forName("com.jiangyuefengyu.redstonecircuit.carrier.CarrierInteraction");
            java.lang.reflect.Method method = interaction.getDeclaredMethod("mayCreateRecord",
                    CarrierGesture.Kind.class, ComponentType.class, boolean.class, Direction.class,
                    boolean.class, boolean.class);
            method.setAccessible(true);
            return Boolean.TRUE.equals(method.invoke(null, gesture, stored, sneaking, face, true, false));
        } catch (ReflectiveOperationException absent) {
            // The previous build: the decision does not exist, so the entry point has nothing to gate on
            // and the filing happens for whatever gesture got here. `false` is the answer this round
            // wants, and the assertion that the decision EXISTS is `onlyThePlacingClickMayFile`.
            return false;
        }
    }

    /**
     * {@code CarrierStore#resolveForPlacement(owners, poses, index, pos, slots, miss, mayWrite)} through
     * its gated level-free core.
     */
    @Nullable
    private CarriedBlockRemoval.Hit file(boolean placementWillWrite,
                                         @Nullable CarrierEntryTrace.Miss miss) {
        try {
            Class<?> store = Class.forName("com.jiangyuefengyu.redstonecircuit.carrier.CarrierStore");
            java.lang.reflect.Method method = store.getDeclaredMethod("resolveForPlacement",
                    CarrierStore.PlotOwnerSource.class, CarrierStore.PoseSource.class,
                    CarriedHostIndex.class, BlockPos.class, CarriedHostIndex.SlotWorld.class,
                    CarrierEntryTrace.Miss.class, boolean.class);
            method.setAccessible(true);
            Object result = method.invoke(null, owners(), poses(), index, PLOT, new MapWorld(world), miss,
                    placementWillWrite);
            return (CarriedBlockRemoval.Hit) result;
        } catch (ReflectiveOperationException absent) {
            return null;
        }
    }

    /**
     * {@code CarriedInsert#wouldInsert(localFace, sneaking, held, replaceExisting)} - the gate the entry
     * point computes, which is {@code preview} for the empty host a filing would create.
     */
    private static boolean wouldInsert(@Nullable Direction localFace, boolean sneaking,
                                       @Nullable ComponentType held, boolean replaceExisting) {
        try {
            java.lang.reflect.Method method = CarriedInsert.class.getDeclaredMethod("wouldInsert",
                    Direction.class, boolean.class, ComponentType.class, boolean.class);
            method.setAccessible(true);
            return Boolean.TRUE.equals(method.invoke(null, localFace, sneaking, held, replaceExisting));
        } catch (ReflectiveOperationException absent) {
            return false;
        }
    }

    /**
     * {@code CarriedInsert#preview(hostExists, slot, localFace, sneaking, held, replaceExisting)} - the
     * decision {@code apply} runs, without writing.
     */
    @Nullable
    private static Object preview(boolean hostExists, @Nullable Slot slot, @Nullable Direction localFace,
                                  boolean sneaking, @Nullable ComponentType held,
                                  boolean replaceExisting) {
        try {
            java.lang.reflect.Method method = CarriedInsert.class.getDeclaredMethod("preview",
                    boolean.class, Slot.class, Direction.class, boolean.class, ComponentType.class,
                    boolean.class);
            method.setAccessible(true);
            return method.invoke(null, hostExists, slot, localFace, sneaking, held, replaceExisting);
        } catch (ReflectiveOperationException absent) {
            return null;
        }
    }

    /**
     * {@code CarrierClickPriority#plotCellOwnership(owner, index, pos, poses, alive)} - what one plot cell
     * of a structure is for ownership.
     */
    @Nullable
    private static CarrierClickPriority.Owned ownership(CarrierId owner, CarriedHostIndex index,
                                                        BlockPos pos,
                                                        CarrierStore.PoseSource poses,
                                                        java.util.function.Predicate<CarrierId> alive) {
        try {
            Class<?> priority =
                    Class.forName("com.jiangyuefengyu.redstonecircuit.carrier.CarrierClickPriority");
            java.lang.reflect.Method method = priority.getDeclaredMethod("plotCellOwnership",
                    CarrierId.class, CarriedHostIndex.class, BlockPos.class,
                    CarrierStore.PoseSource.class, java.util.function.Predicate.class);
            method.setAccessible(true);
            return (CarrierClickPriority.Owned) method.invoke(null, owner, index, pos, poses, alive);
        } catch (ReflectiveOperationException absent) {
            return null;
        }
    }

    /** The entry point's own body, for the wiring assertions - the defect really lived in a condition. */
    private static String entryPointSource() {
        String source = CarrierInteraction.sourceOf("onRightClickBlock");
        assertFalse(source.isEmpty(), "the entry point's source must be readable in a development tree");
        return source;
    }

    private static String storeSource() {
        try {
            return java.nio.file.Files.readString(java.nio.file.Path.of(STORE_SOURCE));
        } catch (java.io.IOException unreadable) {
            throw new AssertionError("CarrierStore's source could not be read at " + STORE_SOURCE,
                    unreadable);
        }
    }

    // ------------------------------------------------------------------ cases --

    @Nested
    @DisplayName("only a placing click may create a record")
    class OnlyAPlacingClickMayFile {

        @Test
        @DisplayName("the wrench, the rotation rod, a bare hand and anything else are all `no`")
        void theToolsAndTheEmptyHandNeverFile() {
            assertFalse(mayCreate(CarrierGesture.Kind.WRENCH, ComponentType.DUST, true, Direction.NORTH),
                    "the author's click held the wrench: it can never store a component, so it must never "
                            + "create a host record for the block it landed on");
            assertFalse(mayCreate(CarrierGesture.Kind.ROD, null, true, Direction.NORTH),
                    "the rotation rod is the same shape");
            assertFalse(mayCreate(CarrierGesture.Kind.NONE, null, true, Direction.NORTH),
                    "an ordinary item in hand owns no gesture of ours at all");
            assertFalse(mayCreate(CarrierGesture.Kind.SWITCH, null, false, Direction.NORTH),
                    "a bare-hand click that is not sneaking is the switch gesture");
            assertFalse(mayCreate(CarrierGesture.Kind.PLACEMENT, null, true, Direction.NORTH),
                    "and a bare hand WHILE sneaking is the retrieval half: there is nothing to take out of "
                            + "a block the index has never heard of, so it must not file either");
            assertFalse(mayCreate(CarrierGesture.Kind.PLACEMENT, ComponentType.DUST, false, Direction.NORTH),
                    "a click that is not sneaking could only have been refused for that reason - it must "
                            + "not create state on the way to the refusal");
            assertFalse(mayCreate(CarrierGesture.Kind.PLACEMENT, ComponentType.DUST, true, null),
                    "a click that carried no face cannot name the component's input side");
        }

        @Test
        @DisplayName("the placing click is the one `yes`")
        void onlyThePlacingClickMayFile() {
            assertTrue(mayCreate(CarrierGesture.Kind.PLACEMENT, ComponentType.DUST, true, Direction.NORTH),
                    "sneaking, a redstone component in hand, a face on the click: this one really stores, "
                            + "and the filing decision must exist to say so");
        }

        @Test
        @DisplayName("the entry point gates the filing on that decision, and no longer on the tool")
        void theEntryPointAsksTheDecision() {
            String source = entryPointSource();

            assertTrue(source.contains("mayCreateRecord(gesture, stored, player.isSecondaryUseActive(), "
                            + "face,"),
                    "the entry point asks which clicks may create a record; without this the decision is "
                            + "unreachable and the defect is intact");
            assertTrue(source.contains("resolveForPlacement(serverLevel, event.getPos(), miss, true)"),
                    "and it only reaches the filing when that answer is yes");
            assertFalse(source.contains("|| wrench"),
                    "the P1.32 condition - a placement OR the wrench OR the rotation rod - is exactly the "
                            + "defect: it fed the filing helper the gestures that can never store anything");
        }
    }

    @Nested
    @DisplayName("the record and the component land in one decision")
    class TheyLandTogether {

        @Test
        @DisplayName("the gate is the placement's own decision, and it is `yes` for an empty host")
        void theGateIsThePlacementsOwnDecision() {
            assertTrue(wouldInsert(Direction.NORTH, true, ComponentType.DUST, false),
                    "the host a filing would create holds nothing, and an empty host accepts a component");
            assertFalse(wouldInsert(Direction.NORTH, false, ComponentType.DUST, false),
                    "not sneaking");
            assertFalse(wouldInsert(Direction.NORTH, true, null, false),
                    "nothing to store");
            assertFalse(wouldInsert(null, true, ComponentType.DUST, false),
                    "no face to name the input side with");
            // AND THE HALF THAT MAKES IT ONE DECISION rather than a promise: `preview` is the very code
            // `apply` runs, so a store the gate said yes to cannot be refused a moment later.
            assertEquals(CarriedInsert.Action.INSERT,
                    preview(true, null, Direction.NORTH, true, ComponentType.DUST, false),
                    "the decision the gate is computed from is the decision the placement reaches");
        }

        @Test
        @DisplayName("filing and storing happen together: the record and its slot are both there")
        void theRecordAndTheSlotLandTogether() {
            liveStructure();

            CarriedBlockRemoval.Hit hit = file(true, null);

            assertNotNull(hit, "the cell belongs to a live structure, holds no record, and a placement is "
                    + "going to store a component in it - so this is the click the round is about");
            assertEquals(PLOT, hit.localPos(), "the coordinate IS the structure's own local coordinate");
            assertNotNull(index.at(AUTHOR, PLOT), "the record is filed");

            CarriedInsert.Action action = CarriedInsert.apply(index, AUTHOR, PLOT, Direction.NORTH, true,
                    ComponentType.DUST, false);

            assertEquals(CarriedInsert.Action.INSERT, action, "and the component really goes in: " + action);
            Slot stored = index.at(AUTHOR, PLOT).slot();
            assertNotNull(stored, "the slot is written in the same click as the record - there is no "
                    + "interleaving in which the record exists and the slot does not");
            assertEquals(ComponentType.DUST, stored.type);
            assertFalse(index.at(AUTHOR, PLOT).landed(), "and the host is riding, not put down");
        }

        @Test
        @DisplayName("the level form still broadcasts the record it filed, after the gate")
        void theLevelFormStillBroadcasts() {
            String source = storeSource();

            assertTrue(source.contains("broadcastCarrier(level, filed.carrier(), index, filed.pose());"),
                    "a filed record is still announced to the client, or the overlay would draw nothing "
                            + "over a block that now carries a host");
            assertTrue(source.contains("suppressWorldHost(level, filed.worldPos());"),
                    "and the world entry the filing migrated is still suppressed on the client");
            assertTrue(source.indexOf("if (!placementWillWrite)") < source.indexOf("broadcastCarrier(level, "
                            + "filed.carrier()"),
                    "the gate is asked BEFORE anything is written or announced");
        }
    }

    @Nested
    @DisplayName("a placement that cannot write files nothing")
    class NoWriteMeansNoRecord {

        @Test
        @DisplayName("a pose that cannot name a position: no record, nothing spent")
        void anUnreadablePoseFilesNothing() {
            liveStructure();
            pose = null;
            CarrierEntryTrace.Miss miss = new CarrierEntryTrace.Miss();

            CarriedBlockRemoval.Hit hit = file(true, miss);

            assertNull(hit);
            assertTrue(index.isEmpty(), "a position that cannot be computed must not be guessed at: "
                    + "recording the plot coordinate as the origin would put the release fallback two "
                    + "million blocks from anywhere a player can go");
            assertTrue(miss.describe().contains("pose cannot name a world position"), miss.describe());

            CarriedInsert.Action action = CarriedInsert.apply(index, AUTHOR, PLOT, Direction.NORTH, true,
                    ComponentType.DUST, false);
            assertEquals(CarriedInsert.Action.REFUSED_POSITION, action);
            assertFalse(action.consumesItem(), "and nothing is spent, because there is no host to store in");
        }

        @Test
        @DisplayName("a closed gate: no record, no world read, and the reason is on the trace")
        void aClosedGateFilesNothing() {
            liveStructure();
            CarrierEntryTrace.Miss miss = new CarrierEntryTrace.Miss();
            Map<Long, Slot> before = new HashMap<>(world);

            CarriedBlockRemoval.Hit hit = file(false, miss);

            assertNull(hit, "nothing was going to be written, so nothing may be created");
            assertTrue(index.isEmpty(), "and in particular no EMPTY host: that is the state the author's "
                    + "log left behind, and it changed every later judgement about the block");
            assertEquals(before, world, "the world store is not even read for a migration");
            assertTrue(miss.describe().contains("not a placement that would store a component"),
                    miss.describe());
        }

        @Test
        @DisplayName("the item in hand is not a component: the gate says no and nothing is filed")
        void anItemThatIsNotAComponentFilesNothing() {
            liveStructure();

            assertFalse(mayCreate(CarrierGesture.Kind.PLACEMENT, null, true, Direction.NORTH),
                    "`stored == null` is not a component, whatever the gesture rule called the click");

            assertNull(file(false, null));
            assertTrue(index.isEmpty());
        }
    }

    @Nested
    @DisplayName("an empty record cannot change any judgement")
    class AnEmptyRecordIsInert {

        @Test
        @DisplayName("with an empty host record and with no record: the SAME owner, the same local coordinate")
        void theTwoStatesAnswerTheSameOwner() {
            CarrierClickPriority.Owned withoutRecord = ownership(AUTHOR, index, PLOT, poses(),
                    carrier -> true);
            assertNotNull(withoutRecord, "a plot cell of a live structure with no record is the "
                    + "structure's - that is P1.30's widening and it must stay");
            assertEquals(AUTHOR, withoutRecord.carrier());
            assertEquals(PLOT, withoutRecord.localPos());
            assertEquals(CarrierClickPriority.Probe.SUB_LEVEL, withoutRecord.probe());

            // The record the previous build's wrench click left behind: a live host holding nothing.
            index.registerAt(AUTHOR, PLOT, PLOT_WORLD, PLOT, new MapWorld(world));
            assertNotNull(index.at(AUTHOR, PLOT), "the setup is the point");
            assertNull(index.at(AUTHOR, PLOT).slot(), "and it is EMPTY: no component was ever put there");

            CarrierClickPriority.Owned withRecord = ownership(AUTHOR, index, PLOT, poses(),
                    carrier -> true);

            assertNotNull(withRecord, "the empty record must NOT answer `not ours`: that is exactly what "
                    + "made the author's next shift-click answer INSIDE: NOT_INSIDE instead of locking the "
                    + "line towards the block beside it");
            assertEquals(withoutRecord.carrier(), withRecord.carrier(),
                    "both states name the same structure, so every decision taken from this answer is the "
                    + "same decision");
            assertEquals(withoutRecord.localPos(), withRecord.localPos());
        }

        @Test
        @DisplayName("what the record does change is the tag, and that is what the migration reads")
        void onlyTheTagDiffers() {
            CarrierClickPriority.Owned withoutRecord = ownership(AUTHOR, index, PLOT, poses(),
                    carrier -> true);
            index.registerAt(AUTHOR, PLOT, PLOT_WORLD, PLOT, new MapWorld(world));
            CarrierClickPriority.Owned withRecord = ownership(AUTHOR, index, PLOT, poses(),
                    carrier -> true);

            assertNotNull(withoutRecord);
            assertNotNull(withRecord);
            assertEquals(CarrierClickPriority.Probe.SUB_LEVEL, withoutRecord.probe(),
                    "no record is `owner=sublevel`, the value the migration and the placement path key off");
            assertEquals(CarrierClickPriority.Probe.HOST, withRecord.probe(),
                    "and a record - empty or not - is `owner=host`: the coordinate has a carried host, and "
                            + "the block holding nothing is a fact about the component, not about ownership");
            assertFalse(withoutRecord.describe().equals(withRecord.describe()),
                    "so the two are tellable apart on the judgement line");
        }

        @Test
        @DisplayName("a record that has been put down is still the world's, in both answer forms")
        void aLandedRecordIsStillRefused() {
            liveStructure();
            index.registerAt(AUTHOR, PLOT, PLOT_WORLD, PLOT, new MapWorld(world));
            // An empty host writes nothing back into the world (see CarriedHostIndex#writeBack), so the
            // landing position simply stays empty and the record really does land there.
            index.release(AUTHOR, PLOT, landing -> PLOT_WORLD, new MapWorld(world));
            assertTrue(index.at(AUTHOR, PLOT).landed(), "the setup is the point");

            assertNull(ownership(AUTHOR, index, PLOT, poses(), carrier -> true),
                    "a put-down host means the world owns that block again, and it must never come back as "
                            + "SUB_LEVEL - the migration would adopt a host the player has already released");
        }

        @Test
        @DisplayName("no record and a structure that is gone is nobody's")
        void aDeadStructureOwnsNothing() {
            liveStructure();

            assertNull(ownership(AUTHOR, index, PLOT, poses(), carrier -> false),
                    "a plot region no live structure stands on is not anybody's coordinate");
        }
    }

    @Nested
    @DisplayName("filing is idempotent")
    class FilingIsIdempotent {

        @Test
        @DisplayName("a second filing returns the record as it stands and never empties it")
        void theSecondFilingChangesNothing() {
            liveStructure();
            assertNotNull(file(true, null), "the first click files it");
            CarriedInsert.apply(index, AUTHOR, PLOT, Direction.NORTH, true, ComponentType.DUST, false);
            Slot before = index.at(AUTHOR, PLOT).slot();
            BlockPos originBefore = index.at(AUTHOR, PLOT).origin();
            assertNotNull(before, "the setup is the point");

            CarriedBlockRemoval.Hit again = file(true, null);

            assertNotNull(again, "the same coordinate resolves again");
            assertEquals(PLOT, again.localPos());
            Slot after = index.at(AUTHOR, PLOT).slot();
            assertNotNull(after, "and the component the first click stored is still there: a repeated "
                    + "delivery of one click must not empty a host the player has built into");
            assertEquals(before.type, after.type);
            assertEquals(before.facing, after.facing);
            assertEquals(originBefore, index.at(AUTHOR, PLOT).origin(),
                    "and the record is not re-filed over itself");
        }
    }
}

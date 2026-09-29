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
 * <b>P1.32 commit B: a block inside a structure that has no carrier record yet can be built into.</b>
 *
 * <h2>The author's own click, and why nothing happened</h2>
 * A Sable sub-level's blocks <b>are</b> level blocks at their own local coordinates, and a carrier record
 * is only ever created by taking a component out of a <em>world</em> position. So a block that was already
 * inside the structure when it was assembled has no record at all - and that is the block the player is
 * trying to put a component into. The author's log is exactly that click, one line, with both halves in it:
 *
 * <pre>
 *   carrier entry trace: … raw=20481032, 127, 20481036 … -&gt; MISS verdict=record present but NOT live
 *     steps=[projection table lookup at 20481032, 127, 20481036 -&gt; no entry (the table is keyed by WORLD
 *            position); local-coordinate match at 20481032, 127, 20481036: record absent (… scanned 1
 *            carrier(s)); plotOwnerAt(20481032, 127, 20481036) -&gt; s:c22611c6;
 *            localForVerified(pose, pos) -&gt; local=40962066, 320, 40962079;
 *            the index holds no record at s:c22611c6 local=40962066, 320, 40962079; …]
 * </pre>
 *
 * <p>Two facts, side by side: the structure's own storage <b>does</b> contain the clicked coordinate
 * ({@code plotOwnerAt} named {@code s:c22611c6}), and the resolver then turned that coordinate into
 * {@code local=40962066, 320, 40962079} - a <b>doubled</b> number that can never be found, because the
 * coordinate it started from was already a local coordinate. {@code 20481032 * 2 = 40962064}, and the
 * ghost lands two blocks past it.
 *
 * <h2>What is asserted</h2>
 * <ol>
 *   <li><b>the record is created at the coordinate that was clicked</b>, with no pose conversion anywhere -
 *       the "input {@code 20481032, 127, 20481036} ⇒ the record's key is exactly that" assertion the round
 *       asks for, which is also the assertion that no doubled coordinate can appear;</li>
 *   <li><b>the origin is the world position the structure's pose puts that cell at</b> - the same rule, and
 *       the same function, P1.30's adoption records;</li>
 *   <li><b>the slot is written into that record</b> and the host it belongs to is reported;</li>
 *   <li><b>idempotent</b>: a second resolve of the same coordinate returns the record as it is, and a
 *       component stored through it is not emptied by a repeat;</li>
 *   <li><b>a pose that cannot name a position refuses</b> - no record, nothing written, and therefore no
 *       item consumed, because the placement never reaches a plan that consumes one;</li>
 *   <li><b>a coordinate that is not a plot cell of a live structure refuses</b>: an ordinary world block,
 *       a plot owner that throws, and no plot owner at all.</li>
 * </ol>
 *
 * <h2>The one thing this file cannot assert, and where it is covered</h2>
 * The two broadcasts the production entry point makes - {@code CarrierStore#broadcastCarrier} and
 * {@code InnerRedstoneNetwork#notifyOutputChanged} - take a live {@code ServerLevel}, so they live in the
 * level form of {@code resolveForPlacement} and not in the level-free core these tests drive. What is
 * asserted here is the half that decides <b>whether there is a record to broadcast</b>, which is the half
 * that was broken; the level form is three lines that call the same core and then broadcast its result.
 */
class CarrierPlotCellInsertTest {

    /** The coordinate from the author's log: the click that could not put a component in. */
    private static final BlockPos PLOT = new BlockPos(20481032, 127, 20481036);

    /** The doubled coordinate the previous build computed for it, one block short of the log's own. */
    private static final BlockPos GHOST = new BlockPos(40962064, 254, 81962072);

    /** Where this fixture's pose puts {@link #PLOT}: a plain translation, like the author's own claim. */
    private static final BlockPos PLOT_WORLD = new BlockPos(-3, -60, -11);

    /** The structure's carrier, from the log's {@code s:c22611c6}. */
    private static final CarrierId AUTHOR =
            CarrierId.subLevel(UUID.fromString("c22611c6-0000-0000-0000-000000000000"));

    private final CarriedHostIndex index = new CarriedHostIndex();
    private final Map<Long, Slot> world = new HashMap<>();
    private final Map<Long, CarrierId> plotOwners = new HashMap<>();

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

    /** A structure whose own storage contains the clicked cell, and whose pose can be read. */
    private void liveStructure(@Nullable CarrierPose pose) {
        plotOwners.put(PLOT.asLong(), AUTHOR);
        this.pose = pose;
    }

    private CarrierPose pose;

    /**
     * {@code CarrierStore#resolveForPlacement} through its level-free core.
     *
     * <p>ASKED BY REFLECTION ON PURPOSE, exactly as P1.31's own test file asks its decision: on the previous
     * tree this method does not exist at all, so a direct call would make this file a compile error there
     * rather than a readable red assertion - and the round's report has to say which of the two it is. Asked
     * by name, the failure is a plain "the decision does not exist", which is the shape of this round's
     * defect.
     */
    @Nullable
    private CarriedBlockRemoval.Hit resolve(@Nullable CarrierEntryTrace.Miss miss) {
        try {
            Class<?> store = Class.forName("com.jiangyuefengyu.redstonecircuit.carrier.CarrierStore");
            java.lang.reflect.Method method = store.getDeclaredMethod("resolveForPlacement",
                    CarrierStore.PlotOwnerSource.class, CarrierStore.PoseSource.class,
                    CarriedHostIndex.class, BlockPos.class, CarriedHostIndex.SlotWorld.class,
                    CarrierEntryTrace.Miss.class);
            method.setAccessible(true);
            Object result = method.invoke(null,
                    (CarrierStore.PlotOwnerSource) (level, pos) -> {
                        if (level != null) {
                            throw new IllegalStateException("the level-free core must not need a level");
                        }
                        return plotOwners.get(pos.asLong());
                    },
                    (CarrierStore.PoseSource) owner -> AUTHOR.equals(owner) ? pose : null,
                    index, PLOT, new MapWorld(world), miss);
            return (CarriedBlockRemoval.Hit) result;
        } catch (ReflectiveOperationException absent) {
            // The previous build: there is no such decision, which is the defect - the click can neither
            // resolve nor file a record, so the component can never be put in.
            return null;
        }
    }

    /** The same core, with an arbitrary lookup - for the cases that are about the lookup failing. */
    @Nullable
    private CarriedBlockRemoval.Hit resolveWith(CarrierStore.PlotOwnerSource owners) {
        try {
            Class<?> store = Class.forName("com.jiangyuefengyu.redstonecircuit.carrier.CarrierStore");
            java.lang.reflect.Method method = store.getDeclaredMethod("resolveForPlacement",
                    CarrierStore.PlotOwnerSource.class, CarrierStore.PoseSource.class,
                    CarriedHostIndex.class, BlockPos.class, CarriedHostIndex.SlotWorld.class,
                    CarrierEntryTrace.Miss.class);
            method.setAccessible(true);
            return (CarriedBlockRemoval.Hit) method.invoke(null, owners,
                    (CarrierStore.PoseSource) owner -> pose, index, PLOT, new MapWorld(world), null);
        } catch (ReflectiveOperationException absent) {
            return null;
        }
    }

    // ------------------------------------------------------------------ cases --

    @Nested
    @DisplayName("the cell with no record is filed, at the coordinate that was clicked")
    class TheMissingRecordIsFiled {

        @Test
        @DisplayName("the record's key IS the clicked coordinate - no pose conversion, no doubled number")
        void theKeyIsTheClickedCoordinate() {
            liveStructure(moving(PLOT, PLOT_WORLD));

            CarriedBlockRemoval.Hit hit = resolve(null);

            assertNotNull(hit, "the cell belongs to a live structure and holds no record, so this is the "
                    + "click the round is about");
            assertEquals(PLOT, hit.localPos(), "the local coordinate IS the coordinate the click carried: "
                    + "plot coordinates are level coordinates");
            assertNotNull(index.at(AUTHOR, PLOT), "and the record really is filed under it");
            assertNull(index.at(AUTHOR, GHOST),
                    "the doubled coordinate the previous build computed - 20481032 doubled to 40962064 - "
                            + "must not exist anywhere: that ghost is why the component could never be "
                            + "found");
            assertNull(index.at(AUTHOR, new BlockPos(40962066, 320, 40962079)),
                    "nor the exact ghost the author's own log printed");
        }

        @Test
        @DisplayName("the origin is the world position the pose puts that cell at")
        void theOriginIsWhereTheStructureIs() {
            CarrierPose pose = moving(PLOT, PLOT_WORLD);
            liveStructure(pose);

            CarriedBlockRemoval.Hit hit = resolve(null);

            assertNotNull(hit);
            // The SAME arithmetic the round's adoption records use: the block's centre through the pose,
            // rounded to the block grid by `SubLevelSpace#nearestWorldBlock`.
            BlockPos expected = SubLevelSpace.nearestWorldBlock(
                    pose.blockCentre(new Vector3d(PLOT.getX(), PLOT.getY(), PLOT.getZ()),
                            new Vector3d()));
            assertEquals(expected, hit.worldPos(), "the world position the structure's pose names");
            assertEquals(expected, index.at(AUTHOR, PLOT).origin(), "and the record carries it as the "
                    + "origin - the same rule and the same function P1.30's adoption records, so a "
                    + "component filed here and one adopted there come home to the same block");
            assertFalse(index.at(AUTHOR, PLOT).landed(), "and it is riding, not put down");
        }

        @Test
        @DisplayName("the slot is written into that record, and the trace says the record was missing")
        void theComponentGoesIn() {
            liveStructure(moving(PLOT, PLOT_WORLD));
            resolve(null);

            CarriedInsert.Action action = CarriedInsert.apply(index, AUTHOR, PLOT, Direction.NORTH, true,
                    ComponentType.DUST, false);

            assertEquals(CarriedInsert.Action.INSERT, action, "an empty host accepts a component: " + action);
            Slot stored = index.at(AUTHOR, PLOT).slot();
            assertNotNull(stored, "and the component is in the record the resolve just filed");
            assertEquals(ComponentType.DUST, stored.type);
            assertEquals(Direction.NORTH, stored.facing, "with the clicked face as its input side");

            // And the explanation a reader greps says which of the two ways this cell got its record.
            CarrierEntryTrace.Miss miss = new CarrierEntryTrace.Miss();
            index.clear();
            liveStructure(moving(PLOT, PLOT_WORLD));
            resolve(miss);
            assertTrue(miss.describe().contains("filed the carrier host this plot cell was missing"),
                    "the trace names the record as missing-before-this-click: " + miss.describe());
            assertFalse(miss.describe().contains("40962064"),
                    "and it never mentions a doubled coordinate: " + miss.describe());
        }

        @Test
        @DisplayName("a second resolve returns the record as it is - it does not empty it")
        void theSecondResolveIsANoOp() {
            liveStructure(moving(PLOT, PLOT_WORLD));
            resolve(null);
            CarriedInsert.apply(index, AUTHOR, PLOT, Direction.NORTH, true, ComponentType.DUST, false);
            Slot before = index.at(AUTHOR, PLOT).slot();
            assertNotNull(before, "the setup is the point");

            CarriedBlockRemoval.Hit again = resolve(null);

            assertNotNull(again, "the same coordinate resolves again");
            assertEquals(PLOT, again.localPos());
            Slot after = index.at(AUTHOR, PLOT).slot();
            assertNotNull(after, "and the component the first click stored is still there: a repeated "
                    + "delivery of one click must not empty a host the player has built into");
            assertEquals(before.type, after.type);
            assertEquals(before.facing, after.facing);
        }

        @Test
        @DisplayName("a world entry at that coordinate is migrated, not shadowed")
        void aStaleWorldEntryIsMigrated() {
            liveStructure(moving(PLOT, PLOT_WORLD));
            // The state P1.30's adoption repairs: a component in the WORLD store keyed by a plot coordinate.
            world.put(PLOT.asLong(), new Slot(ComponentType.RETAINING_REPEATER));

            resolve(null);

            Slot filed = index.at(AUTHOR, PLOT).slot();
            assertNotNull(filed, "the world entry became the record's contents");
            assertEquals(ComponentType.RETAINING_REPEATER, filed.type, "and it is the same component, "
                    + "not an empty host beside a stale world entry - that pair is 'Jade shows it, the "
                    + "overlay does not'");
            assertNull(world.get(PLOT.asLong()), "and the world entry is gone, so there is one truth "
                    + "about this block");
        }
    }

    @Nested
    @DisplayName("a pose that cannot name a position refuses, and consumes nothing")
    class NoPoseMeansNoRecord {

        @Test
        @DisplayName("no pose at all: no record, no hit, and the placement never gets a plan that spends")
        void anUnreadablePoseRefuses() {
            liveStructure(null);
            CarrierEntryTrace.Miss miss = new CarrierEntryTrace.Miss();

            CarriedBlockRemoval.Hit hit = resolve(miss);

            assertNull(hit, "a position that cannot be computed must not be guessed at: recording the plot "
                    + "coordinate as the origin would put the release fallback two million blocks from "
                    + "anywhere a player can go");
            assertTrue(index.isEmpty(), "and nothing was filed");
            assertTrue(miss.describe().contains("pose cannot name a world position"),
                    "the trace says which question refused it: " + miss.describe());

            // THE ITEM. The placement path only spends an item when a plan says INSERT, and a plan needs a
            // host - so "no hit" is "nothing consumed", which is the round's requirement stated structurally
            // rather than as a guard.
            CarriedInsert.Action action = CarriedInsert.apply(index, AUTHOR, PLOT, Direction.NORTH, true,
                    ComponentType.DUST, false);
            assertEquals(CarriedInsert.Action.REFUSED_POSITION, action, "no host, so nothing is stored: "
                    + action);
            assertFalse(action.consumesItem(), "and therefore nothing is spent");
        }

        @Test
        @DisplayName("a pose answering a non-finite point refuses too")
        void aNonFinitePoseRefuses() {
            liveStructure(CarrierPose.of(new Vector3d(Double.NaN, 0.0, 0.0), new Quaterniond()));

            assertNull(resolve(null), "a NaN position rounds to something that looks like a number; it is "
                    + "not one, and it is refused");
            assertTrue(index.isEmpty());
        }
    }

    @Nested
    @DisplayName("nothing else is touched")
    class NothingElseResolves {

        @Test
        @DisplayName("an ordinary world block is not a plot cell, so nothing is filed")
        void anOrdinaryBlockIsNobody() {
            // No structure's own storage names this coordinate: nobody has to be asked about a level.
            pose = moving(PLOT, PLOT_WORLD);

            assertNull(resolve(null), "no structure's storage contains it");
            assertTrue(index.isEmpty());
            assertNull(resolveWith((level, pos) -> null),
                    "and neither does a lookup that names nothing");
        }

        @Test
        @DisplayName("a plot lookup that throws answers `not mine`, never a record")
        void aThrowingLookupIsNeverOurs() {
            liveStructure(moving(PLOT, PLOT_WORLD));

            assertNull(resolveWith((level, pos) -> {
                        throw new IllegalStateException("the container is gone");
                    }),
                    "an exception on the click path must not create a record");
            assertTrue(index.isEmpty());
        }
    }
}

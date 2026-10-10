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
 * A block inside a structure that has no carrier record yet can be built into.
 *
 * <p>The structure's own storage holds the clicked coordinate as-is ({@code plotOwnerAt} names the owner),
 * so converting it through the pose doubles it and the record can never be found again. The record must
 * therefore be filed at the clicked coordinate, with the origin the pose puts that cell at - the same rule
 * the adoption path records - and a pose or a plot lookup that cannot name a position must refuse, so
 * nothing is consumed.
 */
class CarrierPlotCellInsertTest {

    /** The clicked coordinate: a plot cell with no carrier record yet. */
    private static final BlockPos PLOT = new BlockPos(20481032, 127, 20481036);

    /** The doubled coordinate such a conversion produces; no record may ever be filed here. */
    private static final BlockPos GHOST = new BlockPos(40962064, 254, 81962072);

    /** Where this fixture's pose puts {@link #PLOT}: a plain translation. */
    private static final BlockPos PLOT_WORLD = new BlockPos(-3, -60, -11);

    /** The structure's carrier. */
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

    /** A pose that carries {@code local} to {@code worldPos} by translation. */
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
     * {@code CarrierStore#resolveForPlacement} through its level-free core, asked by reflection so an
     * absent decision reads as a plain failure rather than as a compile error.
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
            // No such decision: the click can neither resolve nor file a record, so no component can go in.
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

            assertNotNull(hit, "the cell belongs to a live structure and holds no record");
            assertEquals(PLOT, hit.localPos(), "the local coordinate IS the coordinate the click carried: "
                    + "plot coordinates are level coordinates");
            assertNotNull(index.at(AUTHOR, PLOT), "and the record really is filed under it");
            assertNull(index.at(AUTHOR, GHOST),
                    "a doubled coordinate must not exist anywhere: 20481032 doubled is 40962064, and a "
                            + "record filed there can never be found");
            assertNull(index.at(AUTHOR, new BlockPos(40962066, 320, 40962079)),
                    "nor the doubled coordinate a resolver lands on two past it");
        }

        @Test
        @DisplayName("the origin is the world position the pose puts that cell at")
        void theOriginIsWhereTheStructureIs() {
            CarrierPose pose = moving(PLOT, PLOT_WORLD);
            liveStructure(pose);

            CarriedBlockRemoval.Hit hit = resolve(null);

            assertNotNull(hit);
            // The block's centre through the pose, rounded to the block grid by SubLevelSpace#nearestWorldBlock.
            BlockPos expected = SubLevelSpace.nearestWorldBlock(
                    pose.blockCentre(new Vector3d(PLOT.getX(), PLOT.getY(), PLOT.getZ()),
                            new Vector3d()));
            assertEquals(expected, hit.worldPos(), "the world position the structure's pose names");
            assertEquals(expected, index.at(AUTHOR, PLOT).origin(), "and the record carries it as the "
                    + "origin - the same rule the adoption path records, so a component filed here and "
                    + "one adopted there come home to the same block");
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
            assertNotNull(before, "the first click did store a component");

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
            // A component in the WORLD store keyed by a plot coordinate - the state adoption repairs.
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

            // The placement path only spends an item when a plan says INSERT, and a plan needs a host.
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

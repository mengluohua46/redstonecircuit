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
 * The second identification is asked <em>with the level</em>, and the world side stands down at a plot cell
 * whose record is missing.
 *
 * <p>{@code CarrierResolver#plotOwnerAt} answers "which sub-level's own block storage contains this level
 * position" by walking <b>that level's</b> sub-level container, so it answers {@code null} for a {@code null}
 * level - there is no container to walk - and the second identification then answers "not ours" at every plot
 * cell. Asserted here: the same cell is refused with a null level and owned with a level, and, over the source,
 * that the decision forwards the level it was given and never passes a literal {@code null} to the lookup.
 */
class CarrierPlotOwnershipLevelTest {

    /** The plot coordinate the click that did not connect arrived at. */
    private static final BlockPos PLOT = new BlockPos(20481032, 127, 20481036);

    /** A cell of the same structure that the log's {@code adopted} lines really filed a record for. */
    private static final BlockPos HELD = new BlockPos(20481032, 127, 20481035);

    /** Where the structure's pose puts those cells: the claim frame. */
    private static final BlockPos VISIBLE = new BlockPos(-3, -60, -7);

    /** An ordinary world block, nowhere near any structure's plot region. */
    private static final BlockPos ORDINARY = new BlockPos(10, 64, 10);

    /** A plot-shaped coordinate that <b>no</b> sub-level owns. */
    private static final BlockPos OTHER_PLOT = new BlockPos(20481032, 128, 20524040);

    /** The structure the click was on. */
    private static final CarrierId AUTHOR =
            CarrierId.subLevel(UUID.fromString("c22611c6-0000-0000-0000-000000000000"));

    /** The file the wiring assertion reads: the decision's own source. */
    private static final String DECISION_SOURCE =
            "src/main/java/com/jiangyuefengyu/redstonecircuit/carrier/CarrierClickPriority.java";

    private static final ResourceKey<Level> DIMENSION = ResourceKey.create(Registries.DIMENSION,
            ResourceLocation.parse("redstonecircuit:carrierplotownershipleveltest"));

    private final CarriedHostIndex index = new CarriedHostIndex();
    private final Map<Long, Slot> world = new HashMap<>();
    private final Map<Long, CarrierId> plotOwners = new HashMap<>();
    private final CarrierProjection table = new CarrierProjection();

    /** Every level the plot lookup was handed, so the decision's own forwarding is observable. */
    private final List<Object> levelsAsked = new ArrayList<>();

    /** Whether the plot lookup was reached at all. */
    private boolean lookupReached;

    /** Every carrier the liveness question was asked about. */
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

    /** A pose that carries {@code local} to {@code worldPos}: the claim is a translation. */
    private static CarrierPose moving(BlockPos local, BlockPos worldPos) {
        return CarrierPose.of(new Vector3d(worldPos.getX() - local.getX(),
                worldPos.getY() - local.getY(), worldPos.getZ() - local.getZ()), new Quaterniond());
    }

    /** The sub-level owns both plot cells; the index holds nothing for {@link #PLOT}. */
    private void loadedFromSave() {
        plotOwners.put(PLOT.asLong(), AUTHOR);
        plotOwners.put(HELD.asLong(), AUTHOR);
    }

    /** The record the log's {@code adopted a world component} lines filed at {@link #HELD}. */
    private void adoptedAtHeld() {
        world.put(HELD.asLong(), new Slot(ComponentType.DUST));
        index.fill(AUTHOR, HELD, VISIBLE, HELD, new MapWorld(world));
    }

    // ------------------------------------------------------------------ cases --

    @Nested
    @DisplayName("the second identification answers for a plot cell whose record is missing")
    class TheMissingRecordIsStillOwned {

        @Test
        @DisplayName("owner=sublevel, and the world side stands down for it")
        void theCellIsOwned() {
            loadedFromSave();
            adoptedAtHeld();
            assertNull(index.at(AUTHOR, PLOT), "the setup is the point: this cell has no record");

            CarrierClickPriority.Owned owned = probe(PLOT, answeringLookup());

            assertNotNull(owned, "the plot cell is the structure's by every physical fact, and the world "
                    + "side has to stand down there whether or not a record exists yet");
            assertEquals(CarrierClickPriority.Probe.SUB_LEVEL, owned.probe(),
                    "no record means SUB_LEVEL, which is the answer both the migration and the placement "
                            + "path key off");
            assertEquals(PLOT, owned.localPos(), "the coordinate IS the host's local coordinate inside "
                    + "the structure - no pose arithmetic is involved in identifying it");
            assertTrue(lookupReached, "the plot lookup was really consulted; a frame that answered without "
                    + "asking it would be a different decision");
            assertTrue(asked.contains(AUTHOR), "and liveness was asked, because no record exists");

            CarrierClickPriority.StandDown standDown = CarrierClickPriority.standDown(owned);
            assertFalse(standDown.useBlock(), "the world side stands down at this coordinate");
            assertFalse(standDown.useItem(), "both action fields, so vanilla cannot place a block here");
            assertFalse(standDown.canceled(), "while the event is NOT cancelled: the carrier's own "
                    + "listener - the one this exception exists to hand the click to - is still called");
        }

        @Test
        @DisplayName("a live record is still `owner=host`, never `sublevel`")
        void aHeldCoordinateIsStillHost() {
            loadedFromSave();
            adoptedAtHeld();

            CarrierClickPriority.Owned owned = probe(HELD, answeringLookup());

            assertNotNull(owned, "a live record at a plot coordinate is the stronger answer");
            assertEquals(CarrierClickPriority.Probe.HOST, owned.probe(),
                    "never SUB_LEVEL: the migration must not be handed a coordinate that already has a "
                            + "record, or it would file a second one over it");
        }

        @Test
        @DisplayName("the switch is the escape hatch, and it is on by default")
        void theSwitchDefaultsOn() {
            assertTrue(prioritySwitch(), "with it off the world keeps every click again");
        }
    }

    @Nested
    @DisplayName("the mechanism: a lookup with no level to walk answers `not ours`")
    class TheLevelIsPartOfTheQuestion {

        @Test
        @DisplayName("a lookup that needs a level refuses the cell, even though the cell is really the structure's")
        void noLevelMeansNoOwner() {
            loadedFromSave();
            adoptedAtHeld();

            // THE COMPAT HALF'S OWN SHAPE, in three lines: it has no container to walk without a level, so it
            // answers "not mine", and then the world side never stands down for any coordinate.
            CarrierStore.PlotOwnerSource needsALevel = (level, pos) -> {
                levelsAsked.add(level);
                lookupReached = true;
                return level == null ? null : plotOwners.get(pos.asLong());
            };

            assertNull(probeWith(PLOT, needsALevel, null),
                    "without a level the second identification cannot name the structure, and the world "
                            + "keeps the click");
            assertTrue(lookupReached, "and it was the plot lookup's answer, not some earlier frame, that "
                    + "decided it");

            // The other half of the mechanism - the same cell IS owned once a level is handed over - cannot be
            // driven from here, because a unit test has no ServerLevel and JDK 21's reflective invocation
            // refuses a substitute. It is asserted at the level the machinery does expose
            // (`CarrierStore#ownedHostAtPlot`), and the forwarding that supplies the level is asserted over
            // the source in TheWiringForwardsTheLevel. Both are named in this file's header.
            assertNotNull(probeWith(PLOT, answeringLookup(), null),
                    "and with a lookup that needs no level the very same cell is the structure's, so the "
                            + "level is the only thing that was missing");
        }
    }

    @Nested
    @DisplayName("nothing else is widened")
    class NothingElseWidened {

        @Test
        @DisplayName("an ordinary world block is nobody's")
        void anOrdinaryBlockIsNobody() {
            loadedFromSave();

            assertNull(probe(ORDINARY, answeringLookup()), "no sub-level's storage contains ordinary "
                    + "ground, whatever level the lookup is handed");
        }

        @Test
        @DisplayName("a plot-SHAPED coordinate no sub-level owns is still nobody's")
        void anUnownedPlotCoordinateIsNobody() {
            loadedFromSave();

            assertNull(probe(OTHER_PLOT, answeringLookup()), "the shape of the number is not evidence of "
                    + "anything: a sub-level has to name the coordinate");
        }

        @Test
        @DisplayName("a host the index has put down is the world's again")
        void aLandedHostIsTheWorldsAgain() {
            loadedFromSave();
            adoptedAtHeld();
            world.put(ORDINARY.asLong(), new Slot(ComponentType.DUST));
            index.release(AUTHOR, HELD, landing -> ORDINARY, new MapWorld(world));
            assertTrue(index.at(AUTHOR, HELD).landed(), "the setup is the point");

            assertNull(probe(HELD, answeringLookup()), "a put-down host is the world's, and it must never "
                    + "come back as SUB_LEVEL - that would adopt a host the player has already released");
        }

        @Test
        @DisplayName("a sub-level that is GONE owns nothing")
        void aDeadSubLevelOwnsNothing() {
            loadedFromSave();
            liveness = carrier -> false;

            assertNull(probe(PLOT, answeringLookup()), "a plot region no live structure stands on is not "
                    + "anybody's coordinate");
            assertTrue(asked.contains(AUTHOR), "and the liveness question is what decided it");
        }

        @Test
        @DisplayName("a plot lookup that throws answers `not mine`, never `mine`")
        void aThrowingLookupIsNeverOurs() {
            loadedFromSave();
            CarrierStore.PlotOwnerSource throwing = (level, pos) -> {
                throw new IllegalStateException("the structure's container is gone");
            };

            assertNull(probeWith(PLOT, throwing, PLOT_LEVEL), "an exception on the click path must not "
                    + "become a claim on a coordinate");
        }
    }

    /**
     * The wiring, asserted where it can be observed: a unit test cannot see which {@code ServerLevel} production
     * hands over, so the assertion is made over the source of the decision - {@code carrierOf} took the level as an
     * argument and passed a literal {@code null} on, which made every caller ask a question it could not answer.
     */
    @Nested
    @DisplayName("the production wiring: the level is forwarded, never replaced with null")
    class TheWiringForwardsTheLevel {

        @Test
        @DisplayName("every plotOwnerAt call in the decision passes a level, not a literal null")
        void thePlotLookupIsNeverAskedWithALiteralNull() {
            String source = source();

            assertTrue(source.contains("owners.plotOwnerAt(level, pos)"),
                    "the one place the decision names the structure for a position forwards the level it "
                            + "was handed; without this the whole second identification answers `not ours`");
            assertFalse(source.contains("plotOwnerAt(null"),
                    "and no call site may substitute a null level: the compat half walks one level's "
                            + "sub-level container and can only answer null for a null argument. This is "
                            + "the assertion that keeps a null level out of that call site");
        }

        @Test
        @DisplayName("the level-taking form is the one production calls, and the level-free form says why it exists")
        void theProductionEntryPointUsesTheLevelTakingForm() {
            String source = source();

            assertTrue(source.contains("return probeOwner(CarrierStore.projection(), "
                            + "CarrierStore.indexOf(level), level, pos,"),
                    "`owner(final the level)` must pass the level into the decision");
            assertFalse(source.contains("public static Owned owner(")
                            && source.contains("probeOwner(CarrierStore.projection(), "
                                    + "CarrierStore.indexOf(level), pos,"),
                    "and must not call the level-free form, which exists only for the unit tests");
        }

        private static String source() {
            try {
                return java.nio.file.Files.readString(java.nio.file.Path.of(DECISION_SOURCE));
            } catch (java.io.IOException unreadable) {
                throw new AssertionError("the decision's own source could not be read at "
                        + DECISION_SOURCE + " (run the tests from the project directory)", unreadable);
            }
        }
    }

    // ---------------------------------------------------------------- fixtures --

    /**
     * Stand-in levels, and why the forwarding itself is asserted over the source instead of here.
     *
     * <p>A unit test has no {@code ServerLevel} to offer - its static initialiser needs the vanilla registries and,
     * through NeoForge's bootstrap, a running mod loader - and no unrelated reference can be substituted either,
     * because {@code Method#invoke} on JDK 21 validates argument types before the call. So a unit test can observe
     * two things: with no level the decision answers "not ours" at a plot cell whose record is missing, and with a
     * lookup that answers regardless of the level the whole second identification works. Which level production
     * hands over is pinned over the source by {@link TheWiringForwardsTheLevel}, which is the only kind of
     * assertion that can catch a literal {@code null} at a call site.
     */
    private static final ServerLevel PLOT_LEVEL = null;

    /** The decision, asked the way production asks it, with a lookup that records what it is handed. */
    @Nullable
    private CarrierClickPriority.Owned probe(BlockPos pos, CarrierStore.PlotOwnerSource owners) {
        return probeWith(pos, owners, PLOT_LEVEL);
    }

    /** A lookup that answers whether or not it is handed a level - the fixture shape that cannot see the defect. */
    private CarrierStore.PlotOwnerSource answeringLookup() {
        return (level, pos) -> {
            levelsAsked.add(level);
            lookupReached = true;
            return plotOwners.get(pos.asLong());
        };
    }

    @Nullable
    private CarrierClickPriority.Owned probeWith(BlockPos pos, CarrierStore.PlotOwnerSource owners,
                                                 @Nullable Object level) {
        try {
            java.lang.reflect.Method method = CarrierClickPriority.class.getDeclaredMethod("probeOwner",
                    CarrierProjection.class, CarriedHostIndex.class, ServerLevel.class, BlockPos.class,
                    CarrierStore.PlotOwnerSource.class, CarrierStore.PoseSource.class,
                    Predicate.class);
            method.setAccessible(true);
            Object result = method.invoke(null, table, index, level, pos, owners,
                    (CarrierStore.PoseSource) carrier -> poseOf(carrier), counted());
            return (CarrierClickPriority.Owned) result;
        } catch (ReflectiveOperationException absent) {
            // No such decision exists: answering null means "no structure owns this position", so the world
            // keeps the click.
            return null;
        }
    }

    /** The pose printed for that structure, over the two plot cells of this fixture. */
    @Nullable
    private CarrierPose poseOf(CarrierId carrier) {
        return AUTHOR.equals(carrier) ? moving(HELD, VISIBLE) : null;
    }

    /** The liveness question, with the carrier recorded so "it was asked" is assertable. */
    private Predicate<CarrierId> counted() {
        return carrier -> {
            asked.add(carrier);
            return liveness.test(carrier);
        };
    }

    /** The switch, asked the same way and for the same reason. */
    private static boolean prioritySwitch() {
        try {
            java.lang.reflect.Method method = RCConfig.class.getDeclaredMethod("carriedInsertPriority");
            return (Boolean) method.invoke(null);
        } catch (ReflectiveOperationException absent) {
            return false;
        }
    }
}

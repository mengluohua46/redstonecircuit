package com.jiangyuefengyu.redstonecircuit.carrier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.jiangyuefengyu.redstonecircuit.RCConfig;
import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.Slot;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
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
 * <b>P1.30 commit A: a coordinate a live sub-level owns is the structure's <em>whether or not the index
 * has a record for it</em>.</b>
 *
 * <h2>The defect this pins, and the log that shows it</h2>
 * The author's report (<em>"rc list is normal, but re-physicalising and putting it in is still the
 * same"</em> - the component is visible to Jade and the overlay draws nothing) comes with a log whose two
 * decisive facts look unrelated until they are read together:
 *
 * <pre>
 *   carrier claim = 0                                        the whole session
 *   placed RETAINING_REPEATER at 20481032, 127, 20481032     the component is in the WORLD store
 *   dropped RETAINING_REPEATER from broken host 20481032, 127, 20481032
 * </pre>
 *
 * <p>So the component is in the world store, keyed by a <b>plot coordinate</b> - a coordinate that is
 * simultaneously "a position in the level" and "a block inside a structure". The world's click handler
 * won that click because <b>the index held no record at that coordinate</b>, which is exactly the state a
 * structure loaded from a save is in: every claim is created by taking a component out of a <em>world</em>
 * position, and a sub-level whose blocks were already inside it when it was saved has no claims at all.
 *
 * <h2>What is asserted</h2>
 * The whole of commit A, with no game running:
 *
 * <ol>
 *   <li><b>the second identification</b> ({@code CarrierStore#ownedHostAtPlot}): a plot cell of a live
 *       sub-level is recognised with <b>an empty index</b>, which the previous build could not do;</li>
 *   <li><b>the distinction on the line</b>: {@code owner=sublevel} for that case and {@code owner=host}
 *       for a coordinate the index really holds, so the next log separates the two without anybody having
 *       to reason about which frame answered;</li>
 *   <li><b>the world is not widened</b>: an ordinary world block, a plot-shaped coordinate no sub-level
 *       owns, and a host the index has already put down are all still the world's, so commit B's migration
 *       is the only thing that changes and no ordinary click is ever swallowed;</li>
 *   <li><b>the dead sub-level is refused</b>: a plot coordinate whose sub-level is gone is not the
 *       structure's, so a removed structure cannot keep declining clicks on the ground it used to cover.</li>
 * </ol>
 *
 * <h2>Before the fix (the red half)</h2>
 * The round's own test files are written against the <b>previous</b> tree by reflection, so every
 * assertion about {@code ownedHostAtPlot} and {@code owner=sublevel} fails there with a plain, readable
 * message rather than a compile error. The expected red is seven assertions, all of them in this file:
 * they answer "not ours" on the previous tree and pass on this one.
 */
class CarrierPlotOwnershipTest {

    /** The coordinate from the author's log: a plot cell, printed by {@code placed … at}. */
    private static final BlockPos PLOT = new BlockPos(20481032, 127, 20481032);

    /** Where the structure's pose puts that cell: the author's own claim frame. */
    private static final BlockPos VISIBLE = new BlockPos(-12, -60, -12);

    /** An ordinary world block, nowhere near any structure's plot region. */
    private static final BlockPos ORDINARY = new BlockPos(10, 64, 10);

    /** A plot-shaped coordinate that <b>no</b> sub-level owns - the trap the previous test file names. */
    private static final BlockPos OTHER_PLOT = new BlockPos(20481032, 128, 20524040);

    /** The author's carrier, from the log's {@code carrier claim s:d315200a}. */
    private static final CarrierId AUTHOR =
            CarrierId.subLevel(UUID.fromString("d315200a-0000-0000-0000-000000000000"));

    private static final ResourceKey<Level> DIMENSION = ResourceKey.create(Registries.DIMENSION,
            ResourceLocation.parse("redstonecircuit:carrierplotownershiptest"));

    private final CarriedHostIndex index = new CarriedHostIndex();
    private final Map<Long, Slot> world = new HashMap<>();
    private final Map<UUID, CarrierPose> poses = new HashMap<>();
    private final Map<Long, CarrierId> plotOwners = new HashMap<>();
    private final CarrierProjection table = new CarrierProjection();

    /** Every carrier {@link #liveness} has been asked about, so "it was asked" is assertable too. */
    private final java.util.List<CarrierId> asked = new java.util.ArrayList<>();

    /** Which carriers count as alive; a sub-level that has gone is simply left out. */
    private java.util.function.Predicate<CarrierId> liveness = carrier -> true;

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
        public CarrierPose poseOf(net.minecraft.server.level.ServerLevel level, CarrierId carrier) {
            return null;
        }

        @Override
        public CarrierId carrierAt(net.minecraft.server.level.ServerLevel level, BlockPos worldPos) {
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

    /** A pose that carries {@code local} to {@code worldPos}: the author's claim is a translation. */
    private static CarrierPose moving(BlockPos local, BlockPos worldPos) {
        return CarrierPose.of(new Vector3d(worldPos.getX() - local.getX(),
                worldPos.getY() - local.getY(), worldPos.getZ() - local.getZ()), new Quaterniond());
    }

    /**
     * A sub-level that was <b>loaded from a save</b>: it owns its plot cell, it has a readable pose, and
     * the index holds nothing at all.
     *
     * <p>That is the whole of the author's state, and it is produced here by writing only the two things
     * a reloaded sub-level really has - a pose and a plot cell - and never calling {@code claim}.
     */
    private void loadedFromSave(CarrierId carrier, BlockPos plotCell, BlockPos visibleAt) {
        poses.put(carrier.uuid(), moving(plotCell, visibleAt));
        plotOwners.put(plotCell.asLong(), carrier);
    }

    /** A sub-level whose plot cell the index really holds a live record for: the P1.26 case. */
    private CarriedHost claimedHost(CarrierId carrier, BlockPos plotCell, BlockPos worldAt) {
        world.put(worldAt.asLong(), new Slot(ComponentType.RETAINING_REPEATER));
        index.claim(carrier, plotCell, worldAt, new MapWorld(world));
        poses.put(carrier.uuid(), moving(plotCell, worldAt));
        plotOwners.put(plotCell.asLong(), carrier);
        return index.at(carrier, plotCell);
    }

    // ------------------------------------------------- the second identification --

    @Nested
    @DisplayName("a plot cell of a live sub-level with NO index record: the structure owns it")
    class ThePlotCellIsTheStructures {

        @Test
        @DisplayName("the index is empty, and the coordinate is still the structure's")
        void anEmptyIndexStillOwnsThePlotCell() {
            loadedFromSave(AUTHOR, PLOT, VISIBLE);
            assertTrue(index.isEmpty(), "the setup is the point: this is a structure loaded from a save, "
                    + "which has no claims at all - the author's `carrier claim = 0`");

            CarriedBlockRemoval.Hit owned = plotOwnership(PLOT, true);

            assertNotNull(owned, "the plot cell is the structure's by every physical fact; the index "
                    + "simply has not heard of it yet, and that must not hand the click to the world");
            assertEquals("s:d315200a", owned.carrier().shortId(), "the author's own carrier");
            assertEquals(PLOT, owned.localPos(), "the coordinate IS the host's coordinate inside the "
                    + "structure, which is the fact this whole exception rests on");
            assertEquals(VISIBLE, owned.worldPos(), "and the pose names where that block is in the world");
            assertTrue(asked.contains(AUTHOR), "liveness was really asked - the record's absence is not "
                    + "being taken as proof of anything");
        }

        @Test
        @DisplayName("the owner line says `owner=sublevel`, so the next log separates the two answers")
        void theLineSaysSubLevel() {
            loadedFromSave(AUTHOR, PLOT, VISIBLE);

            CarrierClickPriority.Owned owned = probe(PLOT, true);

            assertNotNull(owned);
            assertEquals(CarrierClickPriority.Probe.SUB_LEVEL, owned.probe(),
                    "the world side declined a coordinate whose record is still missing, and a reader has "
                            + "to be able to tell that apart from a coordinate the carrier can act on");
            assertTrue(owned.describe().contains("owner=sublevel"),
                    "the word is on the judgement line: " + owned.describe());
        }

        @Test
        @DisplayName("and a coordinate the index DOES hold says `owner=host`")
        void aHeldCoordinateSaysHost() {
            claimedHost(AUTHOR, PLOT, VISIBLE);

            CarrierClickPriority.Owned owned = probe(PLOT, true);

            assertNotNull(owned, "a live index record is still the first and strongest answer");
            assertEquals(CarrierClickPriority.Probe.HOST, owned.probe(),
                    "this is the state P1.26 could already handle: the carrier's resolver will find the "
                            + "component and act on the click");
            assertTrue(owned.describe().contains("owner=host"),
                    "the word is on the judgement line: " + owned.describe());
        }

        @Test
        @DisplayName("the world side stands down there - both event fields, event not cancelled")
        void theWorldStandsDown() {
            loadedFromSave(AUTHOR, PLOT, VISIBLE);

            CarrierClickPriority.Owned owned = probe(PLOT, true);

            assertNotNull(owned, "the setup is the point");
            CarrierClickPriority.StandDown standDown = CarrierClickPriority.standDown(owned);
            assertFalse(standDown.useBlock(), "the block's own use never runs at a sub-level's plot cell");
            assertFalse(standDown.useItem(), "and neither does the item's useOn - vanilla's placement into "
                    + "the plot cell is the same class of failure as the world store write");
            assertFalse(standDown.canceled(), "while the event is NOT cancelled, so the carrier's own "
                    + "listener - the one this exception exists to hand the click to - is still called");
        }

        @Test
        @DisplayName("the probe is on by default, and off means the world keeps every click")
        void theSwitchDefaultsOn() {
            assertTrue(prioritySwitch(), "the widened decision is the default; `false` is the escape hatch "
                    + "back to the behaviour before P1.26");
        }
    }

    // ----------------------------------------------------- nothing else widened --

    @Nested
    @DisplayName("every other coordinate is still the world's, byte for byte")
    class NothingElseWidened {

        @Test
        @DisplayName("an ordinary world block is nobody's")
        void anOrdinaryBlockIsNobody() {
            loadedFromSave(AUTHOR, PLOT, VISIBLE);

            assertNull(plotOwnership(ORDINARY, true),
                    "a world block is not a plot cell of any sub-level, and this is the comparison that "
                            + "keeps the exception from swallowing clicks on ordinary ground");
            assertNull(probe(ORDINARY, true));
        }

        @Test
        @DisplayName("a plot-SHAPED coordinate no sub-level owns is still nobody's")
        void anUnownedPlotCoordinateIsNobody() {
            loadedFromSave(AUTHOR, PLOT, VISIBLE);

            assertNull(plotOwnership(OTHER_PLOT, true),
                    "the shape of the number is not evidence of anything: a sub-level has to name the "
                            + "coordinate. This is the assertion that stops the widened test from becoming "
                            + "\"twenty-million looks like a plot, so it is ours\"");
        }

        @Test
        @DisplayName("a host the index has put down is the world's again - never a `sublevel` answer")
        void aLandedHostIsTheWorldsAgain() {
            claimedHost(AUTHOR, PLOT, VISIBLE);
            // The structure puts the block down: the record stays, marked landed, and the component is
            // written back into the world store at the coordinate it landed on.
            world.put(ORDINARY.asLong(), new Slot(ComponentType.RETAINING_REPEATER));
            index.release(AUTHOR, PLOT, landing -> ORDINARY, new MapWorld(world));
            assertTrue(index.at(AUTHOR, PLOT).landed(), "the setup is the point");

            assertNull(hostRecord(PLOT),
                    "the host-record question refuses a put-down host: `ownedHostAt` is what the world's "
                            + "own placement path asks, and a block the structure has put down behaves "
                            + "exactly as it did before this round");

            assertNull(probe(PLOT, true),
                    "and the DECISION refuses it too - the world keeps the click on a block that has been "
                            + "put down, exactly as before this round. What matters is *how* it refuses: the "
                            + "answer must not be Probe.SUB_LEVEL, because that value means \"the index has "
                            + "no record here\" and the migration keys off it - a put-down host reported "
                            + "that way would be adopted a second time, over a host the player has already "
                            + "released");
        }

        @Test
        @DisplayName("a sub-level that is GONE owns nothing")
        void aDeadSubLevelOwnsNothing() {
            loadedFromSave(AUTHOR, PLOT, VISIBLE);
            liveness = carrier -> false;

            assertNull(plotOwnership(PLOT, true),
                    "a plot region no live structure is standing on is not anybody's coordinate: without "
                            + "this, a removed structure would keep declining clicks on ground it used to "
                            + "cover, and nothing would ever handle them");
            assertTrue(asked.contains(AUTHOR), "and the liveness question was the thing that decided it");
        }

        @Test
        @DisplayName("a plot lookup that throws answers `not mine`, never `mine`")
        void aThrowingLookupIsNotOurs() {
            loadedFromSave(AUTHOR, PLOT, VISIBLE);
            // A source that throws is the documented failure of the compat half; the honest answer is
            // "I could not tell", and that is never "it is mine".
            Object throwing = java.lang.reflect.Proxy.newProxyInstance(
                    CarrierStore.PlotOwnerSource.class.getClassLoader(),
                    new Class<?>[] {CarrierStore.PlotOwnerSource.class},
                    (proxy, method, args) -> {
                        throw new IllegalStateException("the structure's container is gone");
                    });
            assertNotNull(throwing);

            assertNull(invokePlotOwnership(throwing, PLOT),
                    "an exception on the click path must not become a claim on a coordinate");
        }

        @Test
        @DisplayName("with no compat source at all, nothing is a plot cell")
        void noSourceMeansNoPlotCells() {
            loadedFromSave(AUTHOR, PLOT, VISIBLE);

            assertNull(plotOwnership(PLOT, false),
                    "the plot lookup is the only thing that can name a sub-level from a coordinate; "
                            + "without it the answer is the P1.26 one");
        }
    }

    // ------------------------------------------------------------------ fixtures --

    /** The decision, asked through the production entry point by reflection. */
    @Nullable
    private CarrierClickPriority.Owned probe(BlockPos pos, boolean withPlotOwners) {
        try {
            java.lang.reflect.Method method = CarrierClickPriority.class.getDeclaredMethod("probeOwner",
                    CarrierProjection.class, CarriedHostIndex.class, BlockPos.class,
                    ResourceKey.class, CarrierStore.PlotOwnerSource.class,
                    CarrierStore.PoseSource.class, java.util.function.Predicate.class);
            method.setAccessible(true);
            Object plotOwners = withPlotOwners ? plotOwnerProxy() : null;
            Object result = method.invoke(null, table, index, pos, DIMENSION, plotOwners,
                    (CarrierStore.PoseSource) carrier -> poses.get(carrier.uuid()), counted());
            return (CarrierClickPriority.Owned) result;
        } catch (ReflectiveOperationException absent) {
            // The previous build: there is no such decision, which is exactly the defect. Answering null
            // is "no structure owns this position" - the world keeps the click.
            return null;
        }
    }

    /** {@code CarrierStore#ownedHostAt}, asked through the packaged level-free form. */
    @Nullable
    private CarriedBlockRemoval.Hit hostRecord(BlockPos pos) {
        try {
            java.lang.reflect.Method method = CarrierStore.class.getDeclaredMethod("ownedHostAt",
                    CarrierProjection.class, CarriedHostIndex.class, BlockPos.class,
                    ResourceKey.class, CarrierStore.PlotOwnerSource.class,
                    CarrierStore.PoseSource.class);
            method.setAccessible(true);
            Object result = method.invoke(null, table, index, pos, DIMENSION, plotOwnerProxy(),
                    (CarrierStore.PoseSource) carrier -> poses.get(carrier.uuid()));
            return (CarriedBlockRemoval.Hit) result;
        } catch (ReflectiveOperationException absent) {
            return null;
        }
    }

    /** {@code CarrierStore#ownedHostAtPlot}, asked through the packaged level-free form. */
    @Nullable
    private CarriedBlockRemoval.Hit plotOwnership(BlockPos pos, boolean withPlotOwners) {
        try {
            java.lang.reflect.Method method = CarrierStore.class.getDeclaredMethod("ownedHostAtPlot",
                    CarrierStore.PlotOwnerSource.class, net.minecraft.server.level.ServerLevel.class,
                    CarriedHostIndex.class, BlockPos.class, CarrierStore.PoseSource.class,
                    java.util.function.Predicate.class);
            method.setAccessible(true);
            Object plotOwners = withPlotOwners ? plotOwnerProxy() : null;
            Object result = method.invoke(null, plotOwners, null, index, pos,
                    (CarrierStore.PoseSource) carrier -> poses.get(carrier.uuid()), counted());
            return (CarriedBlockRemoval.Hit) result;
        } catch (ReflectiveOperationException absent) {
            return null;
        }
    }

    /** The same, with an arbitrary plot-owner object - the one case that is about the lookup failing. */
    @Nullable
    private CarriedBlockRemoval.Hit invokePlotOwnership(Object owners, BlockPos pos) {
        try {
            java.lang.reflect.Method method = CarrierStore.class.getDeclaredMethod("ownedHostAtPlot",
                    CarrierStore.PlotOwnerSource.class, net.minecraft.server.level.ServerLevel.class,
                    CarriedHostIndex.class, BlockPos.class, CarrierStore.PoseSource.class,
                    java.util.function.Predicate.class);
            method.setAccessible(true);
            Object result = method.invoke(null, owners, null, index, pos,
                    (CarrierStore.PoseSource) carrier -> poses.get(carrier.uuid()), counted());
            return (CarriedBlockRemoval.Hit) result;
        } catch (ReflectiveOperationException absent) {
            return null;
        }
    }

    /** The liveness question, with the carrier recorded so "it was asked" is assertable. */
    private java.util.function.Predicate<CarrierId> counted() {
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

    /**
     * The plot-owner lookup, as an instance of {@code CarrierStore$PlotOwnerSource} <b>built at
     * runtime</b>.
     *
     * <p>A lambda would name the interface in this file's bytecode, and the interface is what the fix
     * adds - so a lambda would stop this test file from compiling against the previous build, which is the
     * one thing it must be able to do. A dynamic proxy names it only as a string.
     */
    private Object plotOwnerProxy() {
        return java.lang.reflect.Proxy.newProxyInstance(
                CarrierStore.PlotOwnerSource.class.getClassLoader(),
                new Class<?>[] {CarrierStore.PlotOwnerSource.class},
                (proxy, method, args) -> plotOwners.get(((BlockPos) args[1]).asLong()));
    }
}

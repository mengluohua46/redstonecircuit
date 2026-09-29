package com.jiangyuefengyu.redstonecircuit.carrier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.jiangyuefengyu.redstonecircuit.RCConfig;
import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.Slot;
import com.jiangyuefengyu.redstonecircuit.logic.CarriedInsert;
import com.jiangyuefengyu.redstonecircuit.logic.CarriedTool;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * <b>P1.26, defect one: a component put into a block <em>after</em> it was physicalised went into the
 * world's store instead of the carrier's.</b>
 *
 * <h2>The author's report, and what the log settles</h2>
 * <em>"Putting a redstone component after physicalising gives no frame and no inner display, but Jade
 * shows it"</em>, and, in the same run, <em>"the wrench is unusable although there is a display; it
 * cannot actually connect"</em>. The log the report arrived with has no {@code insert} action at all
 * ({@code carrier entry trace} lines: 56, none of them a placement) and no {@code registered as a RIDING
 * host} line, yet {@code jade: carried host … -> RETAINING_REPEATER} - i.e. the component existed
 * somewhere the Jade lookup could read and nowhere the carrier layer could.
 *
 * <p>The cause is a fact about Sable that the world's click handler cannot see: a sub-level's blocks
 * <b>are</b> level blocks, stored at their own local coordinates in the plot region, so the click arrives
 * at the ordinary block-click handler carrying a coordinate that is both "a position in the level" and
 * "a host's coordinate inside a structure". The world handler won the click and wrote the component into
 * {@code InnerRedstoneStore} at the plot coordinate. Jade reads that store, so it displayed; the overlay
 * draws from the carrier index, so it drew nothing; the world bridge projects from the carrier index, so
 * nothing outside the machine could read it; and the in-structure solve walks the index <em>by
 * coordinate</em>, so the component worked inside the machine - which is exactly the shape the author
 * described.
 *
 * <h2>What is pinned here</h2>
 * The whole of the narrow exception, with no game running:
 *
 * <ol>
 *   <li><b>the decision</b> ({@code CarrierStore#ownedHostAt}): a coordinate a structure owns is
 *       recognised <b>even when the host holds nothing</b> - the state the author's click started from -
 *       while an ordinary world block, a carried host's origin and a host that has been put down are all
 *       answered {@code null};</li>
 *   <li><b>the routing</b>: that decision is what stands between the world's write and the carrier's, so
 *       with it the world store is never written at the plot coordinate and the carrier record is;</li>
 *   <li><b>the client</b>: the payload the carrier side broadcasts carries the newly inserted component,
 *       which is what the overlay's data source is built from;</li>
 *   <li><b>the regression</b>: an ordinary world block is still written by the world path, and with the
 *       switch off the exception does not fire at all.</li>
 * </ol>
 *
 * <h2>Before the fix</h2>
 * Every assertion in {@link TheWorldsOwnWrite} and {@link TheRegression} passes against the previous
 * build; the ones in {@link TheCarrierOwnsTheCoordinate} and {@link TheClientIsTold} fail, because the
 * decision and the routing did not exist - which is how the red half of this round's evidence was
 * produced.
 */
class CarrierInsertPriorityTest {

    /** A Sable-shaped coordinate: the plot frame the author's own claims print. */
    private static final BlockPos PLOT = new BlockPos(20481032, 127, 20481032);

    /** Where the structure actually is, from the author's claim: {@code world=-12, -60, -12}. */
    private static final BlockPos VISIBLE = new BlockPos(-12, -60, -12);

    /** An ordinary world block, nowhere near any structure. */
    private static final BlockPos ORDINARY = new BlockPos(10, 64, 10);

    /** From {@code carrier claim s:d315200a}: the author's carrier, and its short id in the log. */
    private static final CarrierId AUTHOR =
            CarrierId.subLevel(UUID.fromString("d315200a-0000-0000-0000-000000000000"));

    private static final ResourceKey<Level> DIMENSION = ResourceKey.create(Registries.DIMENSION,
            ResourceLocation.parse("redstonecircuit:carrierinsertprioritytest"));

    private final CarriedHostIndex index = new CarriedHostIndex();
    private final Map<Long, Slot> world = new HashMap<>();
    private final Map<UUID, CarrierPose> poses = new HashMap<>();
    private final Map<Long, CarrierId> plotOwners = new HashMap<>();
    private final CarrierProjection table = new CarrierProjection();

    /** The compat source the carrier layer has installed; restored afterwards. */
    private CarrierStore.CarrierSource previousSource;

    /**
     * Arms the carrier layer and opens a projection table for one dimension.
     *
     * <p>The layer has to be armed for a <em>level</em> query to get past its first guard, and the
     * production form of the decision ({@code ownedHostAt(level, pos)}) is the one that matters in game -
     * so the tests that go through the level-free form are still run against an armed layer, exactly as
     * {@code CarrierLocalResolutionTest} does.
     */
    @BeforeEach
    void arm() {
        previousSource = CarrierStore.source();
        CarrierStore.install(new BareSource());
        CarrierStore.projection().clearAll();
        table.begin(DIMENSION, true);
    }

    @AfterEach
    void disarm() {
        CarrierStore.projection().clearAll();
        CarrierStore.install(previousSource);
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
     * Physicalises one host block that holds <b>nothing</b>, the way the assembly callback does.
     *
     * <p>The index only files a claim when the world store holds a component there - a claim that carries
     * nothing is not a claim - so an empty physicalised block is produced the way a player produces it:
     * the component is taken back out through the same {@code removeSlot} a retrieval uses. This is also
     * why the resolver's {@code hitAt} refuses this host: it holds nothing, and every one of
     * {@code hitAt}'s callers acts on the component inside. Ownership is a different question, and this
     * fixture is the case that tells the two apart.
     */
    private CarriedHost physicaliseEmptyHost(CarrierId carrier, BlockPos local, BlockPos worldPos) {
        world.put(worldPos.asLong(), new Slot(ComponentType.RETAINING_REPEATER));
        index.claim(carrier, local, worldPos, new MapWorld(world));
        index.removeSlot(carrier, local);
        // The claim moved it out of the world; the component is taken out of the host, so the block is
        // there and holds nothing.
        world.remove(worldPos.asLong());
        poses.put(carrier.uuid(), moving(local, worldPos));
        plotOwners.put(local.asLong(), carrier);
        table.put(DIMENSION, new CarrierProjection.ProjectedHost(carrier, local, worldPos,
                poses.get(carrier.uuid()), null, 0));
        return index.at(carrier, local);
    }

    /** What the world's click handler asks before it claims anything. */
    private CarriedBlockRemoval.Hit decision(BlockPos pos) {
        return decisionViaReflection(pos, true);
    }

    /** The same, without the plot-owner lookup: only the projection table can answer. */
    private CarriedBlockRemoval.Hit projectionOnly(BlockPos pos) {
        return decisionViaReflection(pos, false);
    }

    /**
     * <b>The decision, asked through the production entry point by reflection.</b>
     *
     * <p>Reflection rather than a direct call for one reason, and it is the red half of this round's
     * evidence: the decision and its switch did not exist before this round, so a direct call compiles
     * only against the fixed tree. Asked reflectively, this test file <b>compiles and runs against the
     * previous build</b>, where the method is absent - and then every assertion about a structure's own
     * coordinate fails with a plain, readable message, which is what the author's report predicted and
     * what the fix has to make pass.
     */
    @Nullable
    private CarriedBlockRemoval.Hit decisionViaReflection(BlockPos pos, boolean withPlotOwners) {
        try {
            // The parameter types are resolved whether or not this call passes a plot owner: the
            // signature is what is looked up, and the projection-only form passes null for it.
            Class<?> ownerSource = Class.forName(
                    "com.jiangyuefengyu.redstonecircuit.carrier.CarrierStore$PlotOwnerSource");
            java.lang.reflect.Method method = CarrierStore.class.getDeclaredMethod("ownedHostAt",
                    CarrierProjection.class, CarriedHostIndex.class, BlockPos.class,
                    ResourceKey.class, ownerSource,
                    Class.forName("com.jiangyuefengyu.redstonecircuit.carrier.CarrierStore$PoseSource"));
            method.setAccessible(true);
            Object plotOwners = withPlotOwners ? plotOwnerProxy() : null;
            Object result = method.invoke(null, table, index, pos, DIMENSION, plotOwners,
                    (CarrierStore.PoseSource) carrier -> poses.get(carrier.uuid()));
            return (CarriedBlockRemoval.Hit) result;
        } catch (ReflectiveOperationException absent) {
            // The previous build: there is no such decision, which is exactly the defect. Answering
            // null is "no structure owns this position" - the world keeps the click and writes its own
            // store, which is the behaviour every red assertion below describes.
            return null;
        }
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
     * <p>A lambda would name the interface in this file's bytecode, and the interface did not exist
     * before this round - so a lambda would stop this test file from compiling against the previous
     * build, which is the one thing it must be able to do. A dynamic proxy names it only as a string.
     */
    private Object plotOwnerProxy() {
        try {
            Class<?> source = Class.forName(
                    "com.jiangyuefengyu.redstonecircuit.carrier.CarrierStore$PlotOwnerSource");
            return java.lang.reflect.Proxy.newProxyInstance(source.getClassLoader(), new Class<?>[] {source},
                    (proxy, method, args) -> plotOwners.get(((BlockPos) args[1]).asLong()));
        } catch (ClassNotFoundException absent) {
            return null;
        }
    }

    /** What the world side does with that answer: it declines rather than writing. */
    private boolean worldWrites(BlockPos pos) {
        return decision(pos) == null;
    }

    private Slot worldSlot(BlockPos pos) {
        return world.get(pos.asLong());
    }

    // --------------------------------------------------- the carrier owns it --

    @Nested
    @DisplayName("a coordinate a structure owns: the carrier side handles the click")
    class TheCarrierOwnsTheCoordinate {

        @Test
        @DisplayName("an EMPTY carried host at a plot coordinate is recognised as the structure's")
        void anEmptiedHostIsStillTheStructuresCoordinate() {
            physicaliseEmptyHost(AUTHOR, PLOT, VISIBLE);
            assertNotNull(index.at(AUTHOR, PLOT), "the host is carried");
            assertNull(index.at(AUTHOR, PLOT).slot(), "and holds nothing - the author's starting state");

            CarriedBlockRemoval.Hit owned = decision(PLOT);

            assertNotNull(owned, "ownership is not the same question as 'does it hold a component': a "
                    + "block the structure carries that the player emptied is still its coordinate, and "
                    + "that is exactly the state 'put a component into a physicalised block' starts from");
            assertEquals("s:d315200a", owned.carrier().shortId(), "the author's own carrier");
            assertEquals(PLOT, owned.localPos(), "the clicked coordinate IS the host's coordinate");
            assertEquals(VISIBLE, owned.worldPos(),
                    "and the pose names where that host is, which is the position the client draws at");
        }

        @Test
        @DisplayName("the world's own write is declined at that coordinate")
        void theWorldDoesNotWriteThere() {
            physicaliseEmptyHost(AUTHOR, PLOT, VISIBLE);

            assertFalse(worldWrites(PLOT),
                    "the world path stands down: it does not call claimsClick, and it does not write the "
                            + "world store at a structure's own coordinate");
        }

        @Test
        @DisplayName("P1.29: and VANILLA is stood down too - the item's own useOn would have placed a block")
        void vanillaIsStoodDownAtThatCoordinate() {
            // THE HALF P1.26 MISSED. Declining without touching the event left the NeoForge server path to
            // run vanilla's own item use on the same click - `Item#useOn`, i.e. a block placement at the
            // plot coordinate - so the world still acted there, just not through this mod. The event's two
            // action fields are what stop it, and they are NOT the same thing as cancelling: a cancelled
            // RightClickBlock is never delivered to a `receiveCanceled=false` listener, and the carrier's
            // listener is one of those.
            physicaliseEmptyHost(AUTHOR, PLOT, VISIBLE);

            CarrierClickPriority.Owned owned = owned(PLOT);
            assertNotNull(owned, "the setup is the point: the coordinate IS the structure's");
            CarrierClickPriority.StandDown standDown = CarrierClickPriority.standDown(owned);

            assertFalse(standDown.useBlock(), "the block's own use never runs at a structure's coordinate");
            assertFalse(standDown.useItem(), "and neither does the item's useOn - the placement vanilla "
                    + "would otherwise make into the plot cell");
            assertFalse(standDown.canceled(), "while the event stays un-cancelled, so the carrier's "
                    + "listener - the one this whole exception exists to hand the click to - is still called");
        }

        @Test
        @DisplayName("P1.29: the ordinary world block keeps BOTH halves of the world's answer")
        void anOrdinaryCoordinateKeepsEverything() {
            physicaliseEmptyHost(AUTHOR, PLOT, VISIBLE);

            CarrierClickPriority.StandDown standDown =
                    CarrierClickPriority.standDown(owned(ORDINARY));
            assertTrue(standDown.useBlock(), "an ordinary block's click is untouched: `claimsClick` decides "
                    + "it, exactly as it always did");
            assertTrue(standDown.useItem());
            assertFalse(standDown.canceled());
        }

        /** The decision as the value the event fields are set from, or {@code null} when nobody owns it. */
        @Nullable
        private CarrierClickPriority.Owned owned(BlockPos pos) {
            CarriedBlockRemoval.Hit hit = decision(pos);
            return hit == null ? null
                    : new CarrierClickPriority.Owned(hit.carrier(), hit.localPos(), hit.worldPos());
        }

        @Test
        @DisplayName("and the component goes into the CARRIER record, which is what the world write would have lost")
        void theComponentGoesIntoTheCarrierRecord() {
            physicaliseEmptyHost(AUTHOR, PLOT, VISIBLE);

            // `sneaking=true` and a component in hand is the world's own gesture ("sneak + component
            // stores it"), which the carrier side performs against the index instead of the world store.
            CarriedInsert.Action action = CarriedInsert.apply(index, AUTHOR, PLOT, Direction.EAST, true,
                    ComponentType.REPEATER, false);

            assertEquals(CarriedInsert.Action.INSERT, action,
                    "the emptied host accepts a component, exactly as an emptied world host does");
            Slot stored = index.at(AUTHOR, PLOT).slot();
            assertNotNull(stored, "the component is in the carrier record - the store the overlay draws "
                    + "from and the store the world bridge projects");
            assertEquals(ComponentType.REPEATER, stored.type);
            assertEquals(Direction.EAST, stored.facing,
                    "and the clicked face became its input side, as it does in the world");
            assertFalse(index.at(AUTHOR, PLOT).landed(), "still riding, not put down");
        }

        @Test
        @DisplayName("the world store at that coordinate was never written")
        void theWorldStoreIsUntouched() {
            physicaliseEmptyHost(AUTHOR, PLOT, VISIBLE);

            assertNull(worldSlot(PLOT),
                    "this is the defect in one assertion: the previous build left the component HERE, "
                            + "which is why Jade showed it and the overlay did not");
            assertFalse(world.containsKey(PLOT.asLong()),
                    "and not even an empty node was created at the plot coordinate");
        }

        @Test
        @DisplayName("a host that holds a component is recognised as well")
        void afilledHostIsRecognised() {
            physicaliseEmptyHost(AUTHOR, PLOT, VISIBLE);
            CarriedInsert.apply(index, AUTHOR, PLOT, Direction.NORTH, true, ComponentType.DUST, false);

            CarriedBlockRemoval.Hit owned = decision(PLOT);
            assertNotNull(owned, "a host that holds something is the structure's coordinate too");
            assertEquals(PLOT, owned.localPos());
        }

        @Test
        @DisplayName("the shape of the decision does not depend on which frame named the host")
        void theProjectionTableAnswerIsTheSameAnswer() {
            physicaliseEmptyHost(AUTHOR, PLOT, VISIBLE);
            CarriedInsert.apply(index, AUTHOR, PLOT, Direction.NORTH, true, ComponentType.DUST, false);

            // The no-plot-lookup form: only the projection table can answer, and it names the world
            // position the pose computed. A click can arrive in either frame, and both are the same host.
            CarriedBlockRemoval.Hit projected = projectionOnly(VISIBLE);
            assertNotNull(projected, "the projection table is keyed by the world position the pose names");
            assertEquals(AUTHOR, projected.carrier());
            assertEquals(PLOT, projected.localPos());
        }
    }

    // ------------------------------------------------------------ the client --

    @Nested
    @DisplayName("the client is told: the state the overlay is built from carries the new component")
    class TheClientIsTold {

        @Test
        @DisplayName("the carrier record is what the single-host broadcast is built from")
        void theBroadcastCarriesTheInsertedSlot() {
            physicaliseEmptyHost(AUTHOR, PLOT, VISIBLE);
            CarriedInsert.apply(index, AUTHOR, PLOT, Direction.EAST, true, ComponentType.REPEATER, false);

            com.jiangyuefengyu.redstonecircuit.data.HostEntry entry =
                    CarriedTool.entryOf(index, AUTHOR, PLOT);
            assertNotNull(entry, "the placement path broadcasts this entry (`HostSync.broadcast`), so an "
                    + "insert that produced no entry would be an insert the overlay cannot draw");
            assertEquals(PLOT, entry.pos(), "at the host's own coordinate, which is the frame the client "
                    + "index is keyed by");
            assertEquals(ComponentType.REPEATER, entry.slot().type,
                    "and it carries the component that was just put in");
        }

        @Test
        @DisplayName("the carrier payload the client receives lists it as a riding host")
        void theCarrierPayloadListsTheInsertedHost() {
            physicaliseEmptyHost(AUTHOR, PLOT, VISIBLE);
            CarriedInsert.apply(index, AUTHOR, PLOT, Direction.EAST, true, ComponentType.REPEATER, false);

            // The walk the payload is actually built from, asserted directly - `carrierPayload` itself
            // needs a level for its dimension, and this is the same loop over the same records.
            // (`HostSync#carriedHostsOf(null)` is deliberately NOT called: it goes straight to
            // `CarrierStore#indexOf(level)`, and a null level there is an NPE by design - the level-free
            // form is the one below, and this test does not need the level-dependent one.)
            List<CarriedHost> live = index.liveHostsOf(AUTHOR);
            assertEquals(1, live.size(), "the inserted host is in the list the client is sent");
            assertEquals(PLOT, live.get(0).localPos());
            assertEquals(ComponentType.REPEATER, live.get(0).slot().type);
            assertFalse(live.get(0).landed(), "and it is riding, which is the condition that list filters on");

            // ...and in the record shape the payload is made of, which is what the client index - the
            // overlay's data source - is rebuilt from. Same fields, same coordinate frame.
            CarrierIndex.CarriedView view = new CarrierIndex.CarriedView(AUTHOR, live.get(0).localPos(),
                    live.get(0).origin(), live.get(0).at(), live.get(0).slot().copy());
            assertEquals(PLOT, view.localPos(), "the client is keyed by the structure's own coordinate");
            assertEquals(ComponentType.REPEATER, view.slot().type,
                    "so the component it draws inside the block is the one that was just put in");
        }
    }

    // ---------------------------------------- the world's own write, unchanged --

    @Nested
    @DisplayName("an ordinary world block: the world path writes it, byte for byte as before")
    class TheWorldsOwnWrite {

        @Test
        @DisplayName("no carrier owns it, so the world keeps the click")
        void theWorldKeepsAnOrdinaryCoordinate() {
            physicaliseEmptyHost(AUTHOR, PLOT, VISIBLE);

            assertNull(decision(ORDINARY), "an ordinary block is nobody's coordinate");
            assertTrue(worldWrites(ORDINARY), "so the world path is not declined");
        }

        @Test
        @DisplayName("the same slot write the world has always made")
        void theWorldStillWritesTheSameSlot() {
            physicaliseEmptyHost(AUTHOR, PLOT, VISIBLE);

            // This is `InnerRedstoneInteraction#tryPlace`, in the three lines that matter: the state the
            // decision leaves reachable is the same state, and the write is the same write. It is what the
            // unit-test suite already pins (`InnerRedstoneDataTest`, the GameTests' placement cases), so
            // the one thing asserted here is that the new gate does not stand in its way.
            Slot slot = new Slot(ComponentType.TORCH);
            slot.power = ComponentType.TORCH.initialPower();
            slot.facing = Direction.NORTH;
            world.put(ORDINARY.asLong(), slot);

            assertTrue(worldWrites(ORDINARY), "the world's own write is reached");
            assertEquals(ComponentType.TORCH, worldSlot(ORDINARY).type,
                    "with the same component, at the same position, in the same store");
            assertEquals(Direction.NORTH, worldSlot(ORDINARY).facing);
        }

        @Test
        @DisplayName("the carrier record at the plot coordinate is not what an ordinary block's click touched")
        void anOrdinaryClickDoesNotDisturbTheCarrier() {
            physicaliseEmptyHost(AUTHOR, PLOT, VISIBLE);
            Slot slot = new Slot(ComponentType.TORCH);
            world.put(ORDINARY.asLong(), slot);

            assertNull(index.at(AUTHOR, PLOT).slot(),
                    "the carrier's host is still empty: an ordinary click is not this structure's business");
        }
    }

    // ------------------------------------------------------- the way back out --

    @Nested
    @DisplayName("the switch, and the frames that must never be claimed")
    class TheRegression {

        @Test
        @DisplayName("carriedInsertPriority is on by default")
        void theSwitchDefaultsOn() {
            assertTrue(prioritySwitch(),
                    "the fix is the default; `false` is the escape hatch back to the previous behaviour");
        }

        @Test
        @DisplayName("ownership follows the LIVE projection: a structure that has flown away owns where it is")
        void ownershipFollowsTheLiveProjection() {
            physicaliseEmptyHost(AUTHOR, PLOT, VISIBLE);
            CarriedInsert.apply(index, AUTHOR, PLOT, Direction.NORTH, true, ComponentType.DUST, false);

            // Standing still, the host's own local coordinate projects onto the position beside the
            // player, and a click there is the structure's - which is the author's case and the fix.
            CarriedBlockRemoval.Hit here = projectionOnly(VISIBLE);
            assertNotNull(here, "while the structure is there, that world position is its host");
            assertEquals(AUTHOR, here.carrier());
            assertEquals(PLOT, here.localPos());

            // Now the machine flies: the same host, the same local coordinate, a different world position.
            // The projection table is rebuilt every tick from the live pose, so the answer follows it.
            BlockPos flown = new BlockPos(20, 70, 30);
            CarrierPose moved = moving(PLOT, flown);
            poses.put(AUTHOR.uuid(), moved);
            // The tick boundary: the table is rebuilt from the live poses, which is what makes the
            // answer follow the machine instead of remembering where it was.
            table.begin(DIMENSION, true);
            table.put(DIMENSION, new CarrierProjection.ProjectedHost(AUTHOR, PLOT, flown, moved, null, 0));

            assertNull(projectionOnly(VISIBLE),
                    "the position it left is not where the host is any more, so the projection frame no "
                            + "longer answers for it - ownership is read from the live pose, never from a "
                            + "remembered coordinate");
            CarriedBlockRemoval.Hit there = projectionOnly(flown);
            assertNotNull(there, "and the host is owned where it really is");
            assertEquals(PLOT, there.localPos());
        }

        @Test
        @DisplayName("a host that has been put down is the world's again")
        void aLandedHostIsNotOwned() {
            physicaliseEmptyHost(AUTHOR, PLOT, VISIBLE);
            CarriedInsert.apply(index, AUTHOR, PLOT, Direction.NORTH, true, ComponentType.DUST, false);
            index.release(AUTHOR, PLOT, landing -> PLOT, new MapWorld(world));
            assertTrue(index.at(AUTHOR, PLOT).landed(), "the setup is the point");

            assertNull(decision(PLOT),
                    "a put-down host is back in the world, where the world's own click paths own it");
        }

        @Test
        @DisplayName("a coordinate no structure claims - however plot-shaped - is still nobody's")
        void anUnclaimedPlotCoordinateIsNotOurs() {
            physicaliseEmptyHost(AUTHOR, PLOT, VISIBLE);
            BlockPos other = new BlockPos(20481032, 128, 20524040);

            assertNull(decision(other),
                    "a plot-shaped coordinate is not evidence of anything by itself: the index has to hold "
                            + "a live record there. This is what keeps the exception from swallowing clicks "
                            + "on ordinary blocks");
        }

        @Test
        @DisplayName("two structures at one coordinate are refused rather than guessed at")
        void twoCarriersAtOneCoordinateAreRefused() {
            physicaliseEmptyHost(AUTHOR, PLOT, VISIBLE);
            CarrierId other = CarrierId.subLevel(UUID.randomUUID());
            world.put(VISIBLE.asLong(), new Slot(ComponentType.DUST));
            index.claim(other, PLOT, VISIBLE, new MapWorld(world));
            index.updateSlot(other, PLOT, new Slot(ComponentType.DUST));
            poses.put(other.uuid(), moving(PLOT, VISIBLE));
            plotOwners.put(PLOT.asLong(), other);

            // Both branches are asked; the projection table answers this dimension's entry, so the answer
            // is whichever frame asked first - and it is a real host, not a guess.
            CarriedBlockRemoval.Hit owned = decision(PLOT);
            assertNotNull(owned, "the position is still owned by a structure - refusing to act on the "
                    + "component is `hitAt`'s business, not ownership's");
            assertTrue(AUTHOR.equals(owned.carrier()) || other.equals(owned.carrier()),
                    "and the carrier named is one of the two that really hold a record there");
        }

        @Test
        @DisplayName("the level-free form refuses everything when it is given no index")
        void noIndexMeansNoAnswer() {
            physicaliseEmptyHost(AUTHOR, PLOT, VISIBLE);
            CarriedHostIndex empty = new CarriedHostIndex();
            assertTrue(empty.isEmpty(), "the stand-in really is empty");

            assertNull(decisionAgainst(empty, PLOT),
                    "an index that holds nothing answers nothing - rather than an exception on the click "
                            + "path, and rather than claiming a coordinate it cannot name a host for");
        }

        /** {@link #decision} against a different index, for the one case that is about the index. */
        @Nullable
        private CarriedBlockRemoval.Hit decisionAgainst(CarriedHostIndex which, BlockPos pos) {
            try {
                java.lang.reflect.Method method = CarrierStore.class.getDeclaredMethod("ownedHostAt",
                        CarrierProjection.class, CarriedHostIndex.class, BlockPos.class,
                        ResourceKey.class,
                        Class.forName("com.jiangyuefengyu.redstonecircuit.carrier.CarrierStore$PlotOwnerSource"),
                        Class.forName("com.jiangyuefengyu.redstonecircuit.carrier.CarrierStore$PoseSource"));
                method.setAccessible(true);
                Object result = method.invoke(null, table, which, pos, DIMENSION, plotOwnerProxy(),
                        (CarrierStore.PoseSource) carrier -> poses.get(carrier.uuid()));
                return (CarriedBlockRemoval.Hit) result;
            } catch (ReflectiveOperationException absent) {
                return null;
            }
        }
    }
}

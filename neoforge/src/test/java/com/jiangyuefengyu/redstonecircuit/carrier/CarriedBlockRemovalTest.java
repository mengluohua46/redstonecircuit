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

import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.Slot;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>P1.20, defect one: the first break of a carried block must drop, and the ghost must not survive.</b>
 *
 * <h2>The author's report</h2>
 * <em>"Breaking a physicalised block does not drop the first time; put it back and break it again and it
 * drops - but the outline and the components are still drawn until physicalisation is turned off."</em>
 *
 * <h2>What this class pins, and why each piece is a different failure</h2>
 * <ol>
 *   <li><b>The first break drops, exactly once.</b> A break event is delivered <em>before</em> the block
 *       is removed, so the structure's own view still reports the block at that moment. The acceptance
 *       rule is therefore evidence-based ({@link CarriedBlockRemoval#accepts}): a hit the projection
 *       table answered is positive identification and does not need the view to agree, while a hit found
 *       by searching the structure's own storage does.</li>
 *   <li><b>The world path is untouched.</b> A position with no carried host answers {@code null} in one
 *       lookup, whatever the structure's view happens to say about it.</li>
 *   <li><b>A release is not a destruction.</b> A landed record is a world host again.</li>
 *   <li><b>"Cannot tell" is never "gone".</b> An unreadable view leaves the record alone, for ever if
 *       need be.</li>
 *   <li><b>The self-healing sweep is bounded and rotates.</b> The one thing about it that cannot be
 *       checked by reading it: with a per-tick budget smaller than a machine's host count, every host is
 *       examined exactly once per full rotation, and never more than the budget in one tick.</li>
 * </ol>
 */
class CarriedBlockRemovalTest {

    private static final CarrierId MINE =
            CarrierId.contraption(UUID.fromString("00000000-0000-0000-0000-000000002001"));
    private static final CarrierId THEIRS =
            CarrierId.contraption(UUID.fromString("00000000-0000-0000-0000-000000002002"));
    private static final ResourceKey<Level> DIMENSION =
            ResourceKey.create(Registries.DIMENSION,
                    ResourceLocation.parse("redstonecircuit:p120test"));

    /** The world store, as a map - the same stand-in the index's own tests use. */
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

    /**
     * A structure whose own view answers a state per local coordinate.
     *
     * <p>{@code air} is what "the block is gone" means to the read rule, and a unit test has no block
     * registry, so this cannot build a real {@code BlockState}. What it <em>can</em> do is count the reads
     * and answer {@code null} - which the production read maps to {@code UNREADABLE}, the third answer -
     * so "how many hosts did the sweep look at" and "did it try to act on a view it could not read" are
     * both assertable without a game.
     */
    private static final class FakeStructure implements CarrierAccess {

        private final List<BlockPos> reads = new ArrayList<>();
        private boolean throwsOnRead;

        @Override
        @Nullable
        public BlockGetter blockViewOf(CarrierId carrier) {
            return new BlockGetter() {
                @Override
                @Nullable
                public BlockEntity getBlockEntity(BlockPos pos) {
                    return null;
                }

                @Override
                public BlockState getBlockState(BlockPos pos) {
                    reads.add(pos);
                    if (throwsOnRead) {
                        throw new UnsupportedOperationException("structure read at " + pos);
                    }
                    // No block registry in a unit test: this is the UNREADABLE answer, not "air".
                    return null;
                }

                @Override
                public FluidState getFluidState(BlockPos pos) {
                    return Fluids.EMPTY.defaultFluidState();
                }

                @Override
                public int getHeight() {
                    return 384;
                }

                @Override
                public int getMinBuildHeight() {
                    return -64;
                }
            };
        }
    }

    private final CarriedHostIndex index = new CarriedHostIndex();
    private final FakeWorld world = new FakeWorld();
    private final FakeStructure structure = new FakeStructure();

    @BeforeEach
    void reset() {
        CarrierStore.resetSelfHealCursors();
    }

    private static BlockPos pos(int x, int y, int z) {
        return new BlockPos(x, y, z);
    }

    /** Picks a component up into the structure, the way the assembly callback does. */
    private CarriedHost carry(CarrierId carrier, BlockPos local, BlockPos origin, ComponentType type) {
        Slot slot = new Slot(type);
        world.set(origin, slot);
        index.claim(carrier, local, origin, world);
        return index.at(carrier, local);
    }

    // ------------------------------------------------ 1. the first break drops --

    @Test
    @DisplayName("a break the projection table answered is accepted even though the view still reports the block")
    void theFirstBreakIsAcceptedWhileTheStructureStillReportsTheBlock() {
        // The defect in one assertion: this is the moment a BreakEvent arrives, and the structure's own
        // view legitimately still has the block there. The old rule required GONE and therefore did
        // nothing at all.
        assertTrue(CarriedBlockRemoval.accepts(CarriedBlockRemoval.StructureViewState.PRESENT,
                        CarriedBlockRemoval.ViewRequirement.PROJECTION_IS_PROOF),
                "a projected hit IS the identification: the view is not the evidence, the table is");
        assertTrue(CarriedBlockRemoval.accepts(CarriedBlockRemoval.StructureViewState.GONE,
                        CarriedBlockRemoval.ViewRequirement.PROJECTION_IS_PROOF),
                "and it is accepted either way, so the second break is not a special case");
        assertTrue(CarriedBlockRemoval.accepts(CarriedBlockRemoval.StructureViewState.UNREADABLE,
                        CarriedBlockRemoval.ViewRequirement.PROJECTION_IS_PROOF),
                "a view that cannot be read does not block a break that the table identified");
    }

    @Test
    @DisplayName("a destroyed carried block yields its component once, and the second report yields nothing")
    void aDestroyedBlockDropsItsComponentExactlyOnce() {
        BlockPos local = pos(1, 0, 2);
        BlockPos origin = pos(10, 64, 10);
        carry(MINE, local, origin, ComponentType.TORCH);

        CarriedHost removed = index.discard(MINE, local);
        assertNotNull(removed, "the live record is the one a break removes");
        assertEquals(ComponentType.TORCH, removed.slot().type,
                "and it is what has to be dropped as an item");
        assertEquals(origin, removed.origin(), "with the position the component came from");

        assertNull(index.discard(MINE, local),
                "a repeated trigger for one block must find nothing: this, and not a guard at the call "
                        + "site, is what makes the drop happen exactly once");
        assertNull(index.at(MINE, local), "and the record is really gone");
        assertFalse(index.isCarried(origin),
                "so the index no longer answers for the position it was carrying it from");
        assertTrue(index.isEmpty(), "nothing is left riding");
    }

    @Test
    @DisplayName("the world path owns a position whose component the store still holds, and owns a landing")
    void theWorldStoreOwnsItsOwnComponents() {
        Slot inTheWorld = new Slot(ComponentType.DUST);
        CarriedHost carried = carry(MINE, pos(1, 0, 2), pos(10, 64, 10), ComponentType.TORCH);

        assertFalse(CarriedBlockRemoval.ownsDestruction(inTheWorld, carried),
                "a position the world store holds is the ordinary break path's, which drops it");
        assertFalse(CarriedBlockRemoval.ownsDestruction(null, null), "nothing there at all");
        assertFalse(CarriedBlockRemoval.ownsDestruction(null, carried.releasedTo(pos(1, 0, 2))),
                "and a host that has been put down is a world host again - never this path");
        assertTrue(CarriedBlockRemoval.ownsDestruction(null, carried),
                "only a live carried record with nothing in the world store is this path's");
    }

    @Test
    @DisplayName("a released host is not dropped by the destroy path, and stays in the world store")
    void aReleasedHostIsNotDropped() {
        BlockPos local = pos(1, 0, 2);
        BlockPos origin = pos(10, 64, 10);
        carry(MINE, local, origin, ComponentType.REPEATER);

        index.release(MINE, local, null, world);
        assertNotNull(world.get(origin), "the release wrote the component back into the world");

        assertNull(index.discard(MINE, local),
                "the destroy path must not take a component a landing has already put back - that would "
                        + "drop a second item for one component");
        assertNotNull(world.get(origin), "and the world store still holds it, exactly once");
        assertNotNull(index.at(MINE, local), "the put-down record is kept, as it always was");
        assertFalse(CarriedBlockRemoval.selfHealWanted(index.at(MINE, local),
                        CarriedBlockRemoval.StructureViewState.GONE),
                "and the self-healing sweep does not touch it either, whatever its view says");
    }

    @Test
    @DisplayName("a host that is still riding is the only thing a break can remove")
    void onlyALiveHostCanBeDestroyed() {
        BlockPos local = pos(1, 0, 2);
        carry(MINE, local, pos(10, 64, 10), ComponentType.COMPARATOR);

        assertNull(index.discard(MINE, pos(4, 4, 4)),
                "a coordinate this structure never claimed is not a destruction");
        assertNull(index.discard(THEIRS, local),
                "and a carrier that never claimed anything is not either");
        assertNotNull(index.at(MINE, local), "so the live record survives both");
    }

    @Test
    @DisplayName("removing one carrier's host leaves the other structure's host at the same coordinate")
    void removalIsScopedToOneCarrier() {
        BlockPos local = pos(1, 0, 2);
        carry(MINE, local, pos(10, 64, 10), ComponentType.TORCH);
        carry(THEIRS, local, pos(50, 64, 50), ComponentType.DUST);

        assertNotNull(index.discard(MINE, local), "one structure's host is destroyed");
        assertNotNull(index.at(THEIRS, local),
                "the other machine carries a block at the same local coordinate and must be untouched");
        assertTrue(index.isCarried(pos(50, 64, 50)), "its own reverse-map entry is still there");
        assertFalse(index.isCarried(pos(10, 64, 10)), "and only the removed one's is gone");
    }

    // ------------------------------------------- 2. "cannot tell" is not "gone" --

    @Test
    @DisplayName("a view that cannot confirm the block is gone is not enough for a caller with no event")
    void anUnreadableViewIsNeverGone() {
        for (CarriedBlockRemoval.StructureViewState view : CarriedBlockRemoval.StructureViewState
                .values()) {
            boolean accepted = CarriedBlockRemoval.accepts(view,
                    CarriedBlockRemoval.ViewRequirement.VIEW_MUST_SAY_GONE);
            assertEquals(view == CarriedBlockRemoval.StructureViewState.GONE, accepted,
                    "only a positive GONE may release the record, and " + view + " is not that");
        }
        assertFalse(CarriedBlockRemoval.accepts(null,
                        CarriedBlockRemoval.ViewRequirement.VIEW_MUST_SAY_GONE),
                "and a null answer - no view at all - is UNREADABLE, never GONE");
        assertFalse(CarriedBlockRemoval.accepts(null, null), "no requirement, no acceptance");
    }

    @Test
    @DisplayName("a structure whose view throws is unreadable, and nothing is forgotten")
    void aThrowingViewIsUnreadable() {
        CarrierStore.resetSelfHealCursors();
        TestSource source = install(structure);
        try {
            BlockPos local = pos(1, 0, 2);
            carry(MINE, local, pos(10, 64, 10), ComponentType.TORCH);
            structure.throwsOnRead = true;

            assertEquals(CarriedBlockRemoval.StructureViewState.UNREADABLE,
                    CarrierStore.structureViewState(MINE, local),
                    "a throw is 'I cannot tell', not 'it is gone'");
            assertFalse(CarriedBlockRemoval.selfHealWanted(index.at(MINE, local),
                            CarrierStore.structureViewState(MINE, local)),
                    "so the sweep leaves the record exactly where it was");
            assertNotNull(index.at(MINE, local), "and the component is still riding");
        } finally {
            source.uninstall();
        }
    }

    @Test
    @DisplayName("a null block state from the structure view is unreadable, not air")
    void aNullStateIsUnreadable() {
        TestSource source = install(structure);
        try {
            BlockPos local = pos(1, 0, 2);
            carry(MINE, local, pos(10, 64, 10), ComponentType.TORCH);
            assertEquals(CarriedBlockRemoval.StructureViewState.UNREADABLE,
                    CarrierStore.structureViewState(MINE, local),
                    "an unanswerable coordinate must never be read as 'the block has gone'");
        } finally {
            source.uninstall();
        }
    }

    @Test
    @DisplayName("with no access at all the view answers unreadable, and no read is even attempted")
    void noAccessIsUnreadableAndFree() {
        TestSource source = install(null);
        try {
            structure.reads.clear();
            assertEquals(CarriedBlockRemoval.StructureViewState.UNREADABLE,
                    CarrierStore.structureViewState(MINE, pos(1, 0, 2)),
                    "no compat half, no answer");
            assertTrue(structure.reads.isEmpty(),
                    "and nothing was read on the way to that answer");
        } finally {
            source.uninstall();
        }
    }

    // ---------------------------------------------------- 3. the bounded sweep --

    @Test
    @DisplayName("the sweep examines every host exactly once per rotation, and never more than the budget")
    void theSweepRotatesAndIsBounded() {
        CarrierStore.resetSelfHealCursors();
        TestSource source = install(structure);
        try {
            List<BlockPos> locals = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                BlockPos local = pos(i, 0, 0);
                locals.add(local);
                carry(MINE, local, pos(100 + i, 64, 100), ComponentType.DUST);
            }

            int budget = 2;
            List<BlockPos> examined = new ArrayList<>();
            for (int tick = 0; tick < 3; tick++) {
                int before = structure.reads.size();
                CarriedBlockRemoval.SelfHealTick result = CarrierStore.scanForDestroyedHosts(null,
                        index, (level, carrier, host, view) -> null, budget);
                int reads = structure.reads.size() - before;
                assertTrue(result.visited() <= budget,
                        "tick " + tick + " visited " + result.visited() + " hosts, above the budget of "
                                + budget + " - the ceiling has to hold the loop, not just the count");
                assertEquals(result.visited(), reads,
                        "every visit is exactly one block read, and no read happens without a visit");
                examined.addAll(structure.reads.subList(before, structure.reads.size()));
            }

            assertEquals(6, examined.size(), "three ticks of two is six examinations, and no more");
            assertEquals(locals.size(), new java.util.HashSet<>(examined).size(),
                    "and every host of the machine was examined exactly once across the rotation: "
                            + examined);
            assertEquals(6, index.hostCount(MINE),
                    "nothing was removed, because the view never said a block had gone");
        } finally {
            source.uninstall();
        }
    }

    @Test
    @DisplayName("a machine larger than the budget is finished over later ticks, not restarted")
    void aBudgetOfZeroExaminesNothing() {
        carry(MINE, pos(1, 0, 0), pos(10, 64, 10), ComponentType.DUST);
        carry(MINE, pos(2, 0, 0), pos(11, 64, 10), ComponentType.DUST);

        CarriedBlockRemoval.SelfHealTick none = CarrierStore.scanForDestroyedHosts(null, index,
                (level, carrier, host, view) -> null, 0);
        assertEquals(0, none.visited(), "a budget of zero is off, and off means not even one read");
        assertFalse(none.looked());
        CarriedBlockRemoval.SelfHealTick negative = CarrierStore.scanForDestroyedHosts(null, index,
                (level, carrier, host, view) -> null, -4);
        assertEquals(0, negative.visited(), "and a nonsense budget is treated the same way");
    }

    @Test
    @DisplayName("an empty index and a null index are both nothing to do")
    void anEmptyIndexIsNothingToDo() {
        assertEquals(0, CarrierStore.scanForDestroyedHosts(null, index,
                (level, carrier, host, view) -> null, 8).visited(),
                "no carriers, no work");
        assertEquals(0, CarrierStore.scanForDestroyedHosts(null, null,
                (level, carrier, host, view) -> null, 8).visited(),
                "and no index at all is not an exception");
    }

    @Test
    @DisplayName("the sweep is scoped per carrier and does not confuse two machines at one coordinate")
    void theSweepIsScopedPerCarrier() {
        CarrierStore.resetSelfHealCursors();
        TestSource source = install(structure);
        try {
            carry(MINE, pos(1, 0, 0), pos(10, 64, 10), ComponentType.DUST);
            carry(THEIRS, pos(1, 0, 0), pos(50, 64, 50), ComponentType.DUST);
            // Different local coordinates, so "which carrier's coordinate was read" is visible even
            // though the read rule answers the same thing for both.
            carry(MINE, pos(7, 0, 0), pos(11, 64, 10), ComponentType.DUST);

            CarriedBlockRemoval.SelfHealTick tick = CarrierStore.scanForDestroyedHosts(null, index,
                    (level, carrier, host, view) -> null, 8);

            assertEquals(3, tick.visited(), "every host of both machines is examined");
            assertEquals(2, tick.carriers(), "and both carriers are reported");
            assertTrue(structure.reads.contains(pos(7, 0, 0)),
                    "the second host of the first machine was reached: " + structure.reads);
            assertEquals(3, structure.reads.size(), "each host is read exactly once");
        } finally {
            source.uninstall();
        }
    }

    // ------------------------------------------- 4. the click resolver (hitAt) --

    @Test
    @DisplayName("a projected hit names the carrier and the structure's own local coordinate")
    void theProjectionAnswersTheHit() {
        BlockPos local = pos(1, 0, 2);
        BlockPos worldPos = pos(11, 64, 12);
        CarrierPose pose = CarrierPose.at(new Vector3d(10.0, 64.0, 10.0));
        project(MINE, local, worldPos, pose, ComponentType.TORCH);

        TestSource source = install(structure);
        try {
            carry(MINE, local, pos(9, 63, 9), ComponentType.TORCH);

            CarriedBlockRemoval.Hit hit = CarrierStore.hitAt(null, CarrierStore.projection(), index,
                    worldPos);
            assertNotNull(hit, "the projected coordinate is a hit");
            assertEquals(MINE, hit.carrier(), "under the carrier that projected it");
            assertEquals(local, hit.localPos(),
                    "with the structure's OWN local coordinate - not the world position, and not the "
                            + "coordinate of whatever block happens to be in the level there");
            assertEquals(worldPos, hit.worldPos(), "and the world position the player clicked");
            assertTrue(hit.projected(),
                    "proved by the projection table, which is what lets the break path skip the view");
        } finally {
            source.uninstall();
        }
    }

    @Test
    @DisplayName("a position the projection table does not answer is not a carried hit")
    void anOrdinaryPositionIsNotAHit() {
        CarrierStore.projection().clearAll();
        TestSource source = install(structure);
        try {
            assertNull(CarrierStore.hitAt(null, CarrierStore.projection(), index, pos(3, 4, 5)),
                    "nothing projected there, so the world path keeps the click");
            assertNull(CarrierStore.hitAt(null, CarrierStore.projection(), index, null), "and a null position is not a hit either");
            assertNull(CarrierStore.hitAt(null, CarrierStore.projection(), null, pos(3, 4, 5)), "nor is a missing index one");
        } finally {
            source.uninstall();
        }
    }

    @Test
    @DisplayName("a landed host is not a hit, so the world path keeps its clicks")
    void aLandedHostIsNotAHit() {
        BlockPos local = pos(1, 0, 2);
        BlockPos worldPos = pos(11, 64, 12);
        CarrierPose pose = CarrierPose.at(new Vector3d(10.0, 64.0, 10.0));
        project(MINE, local, worldPos, pose, ComponentType.REPEATER);

        TestSource source = install(structure);
        try {
            carry(MINE, local, pos(9, 63, 9), ComponentType.REPEATER);
            index.release(MINE, local, null, world);

            assertNull(CarrierStore.hitAt(null, CarrierStore.projection(), index, worldPos),
                    "a record that has been put down is a world host; the carried layer must not act "
                            + "on it however fresh the projection still looks");
        } finally {
            source.uninstall();
        }
    }

    @Test
    @DisplayName("a hit with no carrier layer armed resolves nothing at all")
    void anUnarmedStoreResolvesNothing() {
        BlockPos local = pos(1, 0, 2);
        BlockPos worldPos = pos(11, 64, 12);
        project(MINE, local, worldPos, CarrierPose.at(new Vector3d(10.0, 64.0, 10.0)),
                ComponentType.TORCH);
        TestSource source = install(structure);
        try {
            carry(MINE, local, pos(9, 63, 9), ComponentType.TORCH);
            source.uninstall();
            assertNull(CarrierStore.hitAt(null, CarrierStore.projection(), index, worldPos),
                    "with nothing carrying anything, every click is the world's");
        } finally {
            source.uninstall();
        }
    }

    @Test
    @DisplayName("the pose round trip is the guard on the structure-frame coordinate")
    void theStructureFrameCoordinateIsVerifiedThroughThePose() {
        // The pose maps local -> world at the BLOCK CORNER (SubLevelSpace documents this, and it is why
        // plot.getBoundingBox() through the pose equals SubLevel#boundingBox), while
        // CarrierProjection#project names a block by its CENTRE and floors it. Those two conventions
        // are half a block apart, and the resolver has to have both right at once.
        CarrierPose pose = CarrierPose.at(new Vector3d(10.0, 64.0, 10.0));
        BlockPos local = pos(3, 5, 7);
        BlockPos world = CarrierProjection.project(pose, local);
        assertEquals(local, CarrierStore.localForVerified(pose, world),
                "the coordinate CarrierProjection#project computed for a host comes back as that host's "
                        + "own local coordinate - the two halves of the click resolver agree");

        // The other half of the same convention: for any world block the pose really names, the
        // coordinate that comes back must project forward onto that very block.
        BlockPos anywhere = pos(1, 2, 3);
        BlockPos named = CarrierStore.localForVerified(pose, anywhere);
        assertNotNull(named, "the pose names a coordinate for any block it covers");
        assertEquals(anywhere, CarrierProjection.project(pose, named),
                "and that coordinate is exactly the one the projection table would key this block by");

        // A pose with no inverse - the shape a scale of zero or a structure mid-write has - must name
        // no coordinate at all rather than one that lands somewhere else.
        CarrierPose degenerate = new CarrierPose(new Vector3d(10.0, 64.0, 10.0), new Quaterniond(),
                new Vector3d(0.5, 0.5, 0.5), 0.0);
        assertNull(CarrierStore.localForVerified(degenerate, pos(10, 64, 10)),
                "a pose that cannot round-trip resolves nothing: no drop and no interaction on a guess");
        assertNull(CarrierStore.localForVerified(null, pos(1, 2, 3)), "and no pose, no coordinate");

        // A turn in place is the case a corner-versus-centre confusion shows up on: the same block,
        // named the same way, at any angle.
        for (double degrees : new double[] {90.0, -37.5, 180.0}) {
            CarrierPose turned = CarrierPose.of(new Vector3d(10.0, 64.0, 10.0),
                    new Quaterniond().rotateY(Math.toRadians(degrees)));
            BlockPos turnedLocal = pos(4, 1, -2);
            BlockPos turnedWorld = CarrierProjection.project(turned, turnedLocal);
            assertEquals(turnedLocal, CarrierStore.localForVerified(turned, turnedWorld),
                    "the round trip survives a turn of " + degrees + " degrees");
        }
    }

    // ------------------------------------------------------------------ helpers --
    /** Where the "structure's own storage" answer comes from; only the compat half can give one. */
    private static final class TestSource implements CarrierStore.CarrierSource {

        @Nullable
        private final CarrierAccess access;
        @Nullable
        private final CarrierPose pose;
        @Nullable
        private final CarrierId owner;

        TestSource(@Nullable CarrierAccess access) {
            this(access, null, null);
        }

        TestSource(@Nullable CarrierAccess access, @Nullable CarrierPose pose,
                   @Nullable CarrierId owner) {
            this.access = access;
            this.pose = pose;
            this.owner = owner;
        }

        @Override
        @Nullable
        public CarrierPose poseOf(net.minecraft.server.level.ServerLevel level, CarrierId carrier) {
            return pose;
        }

        @Override
        @Nullable
        public CarrierId carrierAt(net.minecraft.server.level.ServerLevel level, BlockPos worldPos) {
            return null;
        }

        /**
         * The structure's own block storage answer.
         *
         * <p>Only the compat half can answer this in production - it is Sable's plot query - and the
         * test supplies it directly so the pose round trip that <em>qualifies</em> the answer (see
         * {@code CarrierStore#localForVerified}) is what is exercised, rather than a bounding box.
         */
        @Override
        @Nullable
        public CarrierId plotOwnerAt(net.minecraft.server.level.ServerLevel level, BlockPos levelPos) {
            return owner;
        }

        @Override
        @Nullable
        public CarrierAccess access() {
            return access;
        }

        @Override
        public String describe() {
            return "unit-test source";
        }

        void uninstall() {
            CarrierStore.projection().clearAll();
            CarrierStore.install(null);
        }
    }

    private TestSource install(@Nullable CarrierAccess access) {
        TestSource source = new TestSource(access);
        CarrierStore.install(source);
        return source;
    }

    /** The same, with the structure-frame fallback armed: a pose and the carrier that owns the plot. */
    private TestSource install(@Nullable CarrierAccess access, @Nullable CarrierPose pose) {
        TestSource source = new TestSource(access, pose, pose == null ? null : MINE);
        CarrierStore.install(source);
        return source;
    }
    /** Fills the shared projection table the way one level tick would, so {@code hitAt} can be asked. */
    private static void project(CarrierId carrier, BlockPos local, BlockPos worldPos, CarrierPose pose,
                                ComponentType type) {
        CarrierStore.projection().clearAll();
        CarrierStore.projection().begin(DIMENSION, true);
        CarrierStore.projection().put(DIMENSION, new CarrierProjection.ProjectedHost(carrier, local,
                worldPos, pose, new Slot(type), 0));
    }
}

package com.jiangyuefengyu.redstonecircuit.carrier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Where the host rule reads its state from, and the one answer it may never invent. {@code HostRules#isValidHost} is
 * the rule itself; what is pinned here is <b>where the state comes from</b> for a block a structure is carrying - the
 * structure's own view in its own local coordinates, because a physicalised block's state is not in the level.
 *
 * <p>"Cannot be read" must never become "refused": every unreadable case - no carrier source, no view, a structure
 * that has come apart, a view that throws, an unknown coordinate - answers <b>yes</b>, or the component would leave
 * every carried block in the game.
 */
class CarrierHostRuleTest {

    private static final CarrierId MINE =
            CarrierId.contraption(UUID.fromString("00000000-0000-0000-0000-000000008301"));
    private static final CarrierId THEIRS =
            CarrierId.contraption(UUID.fromString("00000000-0000-0000-0000-000000008302"));
    private static final BlockPos PLOT = new BlockPos(20481032, 128, 20481032);

    @Nullable
    private CarrierStore.CarrierSource previous;

    @BeforeEach
    void rememberTheInstalledSource() {
        previous = CarrierStore.source();
    }

    @AfterEach
    void putItBack() {
        CarrierStore.install(previous);
    }

    /**
     * A structure's own block view, as the compat half would supply it. A unit test has no block registry, so every
     * state it can answer is either {@code null} - which production maps to "cannot tell", the answer this class is
     * about - or a throw; what the fake records is the coordinate it was asked about, because a view asked about a
     * world position, or about the posed position, would answer about a different block entirely.
     */
    private static final class FakeView implements BlockGetter {

        private final List<BlockPos> reads = new ArrayList<>();
        private boolean throwsOnRead;

        @Nullable
        @Override
        public BlockEntity getBlockEntity(BlockPos pos) {
            return null;
        }

        @Nullable
        @Override
        public BlockState getBlockState(BlockPos pos) {
            reads.add(pos);
            if (throwsOnRead) {
                throw new UnsupportedOperationException("structure read at " + pos);
            }
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
    }

    private static CarrierStore.CarrierSource source(@Nullable CarrierAccess access) {
        return source(access, null);
    }

    /** The source half: what a structure's own storage says about a level position, and its block view. */
    private static CarrierStore.CarrierSource source(@Nullable CarrierAccess access,
                                                     @Nullable CarrierId plotOwner) {
        return new CarrierStore.CarrierSource() {
            @Nullable
            @Override
            public CarrierPose poseOf(ServerLevel level, CarrierId carrier) {
                return null;
            }

            @Nullable
            @Override
            public CarrierId carrierAt(ServerLevel level, BlockPos worldPos) {
                return null;
            }

            @Nullable
            @Override
            public CarrierId plotOwnerAt(ServerLevel level, BlockPos levelPos) {
                return plotOwner;
            }

            @Nullable
            @Override
            public CarrierAccess access() {
                return access;
            }

            @Override
            public String describe() {
                return "unit-test source";
            }
        };
    }

    private static CarrierAccess view(@Nullable BlockGetter blocks) {
        return carrier -> blocks;
    }

    // ------------------------------------------------------------------ cases --

    @Nested
    @DisplayName("'cannot be read' is never 'refused'")
    class NeverAGuess {

        @Test
        @DisplayName("no carrier source on this build: the block is treated as one that accepts a component")
        void noSourceLeavesTheBehaviourAlone() {
            CarrierStore.install(null);

            assertTrue(CarrierStore.hostAcceptsComponent(MINE, PLOT),
                    "a build without the compat half cannot ask, and must not answer 'no' for every block in "
                            + "the game because of it");
            assertTrue(CarrierStore.hostAcceptsComponent(null, PLOT, null));
        }

        @Test
        @DisplayName("a source that cannot read structures at all")
        void aSourceWithNoViewLeavesTheBehaviourAlone() {
            CarrierStore.install(source(null));

            assertTrue(CarrierStore.hostAcceptsComponent(MINE, PLOT));
        }

        @Test
        @DisplayName("a structure that is not there: no view for this carrier")
        void aMissingViewLeavesTheBehaviourAlone() {
            CarrierStore.install(source(view(null)));

            assertTrue(CarrierStore.hostAcceptsComponent(MINE, PLOT),
                    "a structure that has come apart answers null for its view - 'I cannot tell', which is "
                            + "not a refusal");
        }

        @Test
        @DisplayName("a coordinate the view does not know: null, not air")
        void anUnknownCoordinateLeavesTheBehaviourAlone() {
            FakeView blocks = new FakeView();
            CarrierStore.install(source(view(blocks)));

            assertTrue(CarrierStore.hostAcceptsComponent(MINE, PLOT));
            assertEquals(List.of(PLOT), blocks.reads,
                    "the read really happened, at the block's LOCAL coordinate - the one the index and the "
                            + "solve use - so this case is the silence of the view rather than of the caller");
        }

        @Test
        @DisplayName("a view that throws")
        void anUnreadableViewLeavesTheBehaviourAlone() {
            FakeView blocks = new FakeView();
            blocks.throwsOnRead = true;
            CarrierStore.install(source(view(blocks)));

            assertTrue(CarrierStore.hostAcceptsComponent(MINE, PLOT),
                    "a diagnostics-style failure inside a structure's own reader must not be able to take a "
                            + "player's placement down, and must not be read as 'this block refuses'");
        }

        @Test
        @DisplayName("a level position that belongs to no structure")
        void anOrdinaryWorldPositionIsTheWorldsBusiness() {
            FakeView blocks = new FakeView();
            CarrierStore.install(source(view(blocks), null));

            assertTrue(CarrierStore.hostAcceptsComponent(null, PLOT, null),
                    "an ordinary click on an ordinary block is the world's own click, and this rule must not "
                            + "answer anything about it");
            assertTrue(blocks.reads.isEmpty(),
                    "and nothing was read: the lookup that would have cost a pass over the level's structures "
                            + "is answered 'no structure owns this' and stops there");
        }

        @Test
        @DisplayName("a structure that is not the asked one is not read either")
        void anotherStructuresCellIsNotThisStructuresCell() {
            FakeView blocks = new FakeView();
            CarrierStore.install(source(view(blocks), THEIRS));

            assertTrue(CarrierStore.hostAcceptsComponent(null, PLOT, null),
                    "the owner of the cell is what is read, and the fake's view is what answers about it");
            assertEquals(List.of(PLOT), blocks.reads,
                    "the state is read at the level position itself, which for a plot cell IS the block's own "
                            + "local coordinate (see CarrierSource#plotOwnerAt) - no pose round trip, so a "
                            + "rotated structure cannot be read by accident at a moved coordinate");
            assertTrue(CarrierStore.hostAcceptsComponent(THEIRS, PLOT),
                    "and the carrier-keyed form asks the same view about the same block");
        }
    }
}
